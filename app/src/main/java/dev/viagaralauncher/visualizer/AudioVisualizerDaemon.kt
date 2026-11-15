// SPDX-License-Identifier: Apache-2.0
package dev.viagaralauncher.visualizer

import android.media.audiofx.Visualizer
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Daemon autônomo executado sob UID 0 (root) através do /system/bin/app_process64.
 * Captura a saída de áudio global do sistema através da Sessão 0,
 * decompõe o sinal em 14 bandas acústicas logarítmicas de 25 Hz a 18.500 Hz,
 * aplica a integração espectral de Parseval (RMS) e transmite os dados via LocalSocket.
 */
object AudioVisualizerDaemon {

    private const val SOCKET_NAME = "viagara_audio_viz"
    private const val CAPTURE_SIZE = 1024
    private const val NUM_BANDS = 14
    private const val FRAME_INTERVAL_MS = 16L // ~60 FPS

    @JvmStatic
    fun main(args: Array<String>) {
        var visualizer: Visualizer? = null
        var socket: LocalSocket? = null
        var outStream: OutputStream? = null
        var inStream: InputStream? = null

        try {
            socket = LocalSocket()
            var connected = false
            for (i in 0 until 50) {
                try {
                    socket.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
                    connected = true
                    break
                } catch (_: Throwable) {
                    Thread.sleep(100)
                }
            }

            if (!connected) return

            socket.soTimeout = 500
            outStream = socket.outputStream
            inStream = socket.inputStream

            // Inicialização da Sessão Global 0 com permissão root
            visualizer = Visualizer(0)
            visualizer.captureSize = CAPTURE_SIZE
            visualizer.enabled = true

            val actualCaptureSize = visualizer.captureSize
            val rawSampleRate = visualizer.samplingRate
            val sampleRateHz = if (rawSampleRate > 0) rawSampleRate / 1000 else 48000
            val binWidth = sampleRateHz.toFloat() / actualCaptureSize.toFloat()

            // 14 Bandas Logarítmicas cobrindo todo o espectro auditivo humano:
            val bandFreqs = floatArrayOf(
                25f, 65f, 115f, 175f, 260f, 390f, 580f, 870f,
                1300f, 2000f, 3200f, 5000f, 8000f, 12500f, 18500f
            )

            val bandStartBins = IntArray(NUM_BANDS)
            val bandEndBins = IntArray(NUM_BANDS)
            var lastEndBin = 0
            for (b in 0 until NUM_BANDS) {
                val start = max(lastEndBin + 1, (bandFreqs[b] / binWidth).roundToInt())
                val end = max(start, (bandFreqs[b + 1] / binWidth).roundToInt())
                bandStartBins[b] = start
                bandEndBins[b] = min(511, end)
                lastEndBin = bandEndBins[b]
            }

            val uniformGain = 1.25f
            val emaBands = FloatArray(NUM_BANDS)
            val fftBuffer = ByteArray(CAPTURE_SIZE)
            val waveBuffer = ByteArray(CAPTURE_SIZE)
            val magnitudes = FloatArray(NUM_BANDS)

            // Pacote Little-Endian: 4 bytes mágica + 1 byte bandas + 4 bytes rms + (14 * 4 bytes)
            val packetBuffer = ByteBuffer.allocate(4 + 1 + 4 + (NUM_BANDS * 4)).apply {
                order(ByteOrder.LITTLE_ENDIAN)
            }

            var isPaused = false

            while (true) {
                // Leitura de comandos de controle do app
                while (inStream.available() > 0) {
                    val cmd = inStream.read()
                    if (cmd == -1 || cmd == 'S'.code || cmd == 'Q'.code) {
                        return // Encerramento gracioso
                    } else if (cmd == 'P'.code) {
                        if (!isPaused) {
                            isPaused = true
                            try { visualizer.enabled = false } catch (_: Throwable) {}
                        }
                    } else if (cmd == 'R'.code) {
                        if (isPaused) {
                            isPaused = false
                            try { visualizer.enabled = true } catch (_: Throwable) {}
                        }
                    }
                }

                if (isPaused) {
                    Thread.sleep(50)
                    continue
                }

                val loopStartTime = System.currentTimeMillis()

                val fftResult = visualizer.getFft(fftBuffer)
                val waveResult = visualizer.getWaveForm(waveBuffer)
                var rms = 0f

                if (fftResult == Visualizer.SUCCESS) {
                    var totalLevelSum = 0f
                    for (b in 0 until NUM_BANDS) {
                        val rawRms = computeBandRms(fftBuffer, bandStartBins[b], bandEndBins[b])
                        // Fast EMA (alpha = 0.35)
                        emaBands[b] = emaBands[b] * 0.65f + rawRms * 0.35f
                        val level = min(1.0f, emaBands[b] * uniformGain)
                        magnitudes[b] = level
                        totalLevelSum += level
                    }

                    rms = if (waveResult == Visualizer.SUCCESS) {
                        computeWaveRms(waveBuffer)
                    } else {
                        (totalLevelSum / NUM_BANDS).coerceIn(0f, 1f)
                    }
                } else {
                    magnitudes.fill(0f)
                    emaBands.fill(0f)
                    if (waveResult == Visualizer.SUCCESS) {
                        rms = computeWaveRms(waveBuffer)
                    }
                }

                // Serialização do pacote VIZ1
                packetBuffer.clear()
                packetBuffer.put('V'.code.toByte())
                packetBuffer.put('I'.code.toByte())
                packetBuffer.put('Z'.code.toByte())
                packetBuffer.put('1'.code.toByte())
                packetBuffer.put(NUM_BANDS.toByte())
                packetBuffer.putFloat(rms)
                for (b in 0 until NUM_BANDS) {
                    packetBuffer.putFloat(magnitudes[b])
                }

                outStream.write(packetBuffer.array())
                outStream.flush()

                val elapsed = System.currentTimeMillis() - loopStartTime
                val sleepTime = FRAME_INTERVAL_MS - elapsed
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime)
                }
            }
        } catch (_: Throwable) {
        } finally {
            try { visualizer?.enabled = false } catch (_: Throwable) {}
            try { visualizer?.release() } catch (_: Throwable) {}
            try { inStream?.close() } catch (_: Throwable) {}
            try { outStream?.close() } catch (_: Throwable) {}
            try { socket?.close() } catch (_: Throwable) {}
        }
    }

    private fun computeBandRms(fft: ByteArray, startBin: Int, endBin: Int): Float {
        var sumPower = 0.0
        val maxK = min(511, endBin)
        for (k in startBin..maxK) {
            val re: Float
            val im: Float
            if (k == 0) {
                re = fft[0].toFloat() / 128.0f
                im = 0.0f
            } else {
                re = fft[2 * k].toFloat() / 128.0f
                im = if (2 * k + 1 < fft.size) fft[2 * k + 1].toFloat() / 128.0f else 0.0f
            }
            sumPower += (re * re + im * im).toDouble()
        }
        return sqrt(sumPower / 2.0).toFloat()
    }

    private fun computeWaveRms(wave: ByteArray): Float {
        var sumSquares = 0.0
        for (b in wave) {
            val v = (b.toInt() and 0xFF) - 128
            sumSquares += (v * v).toDouble()
        }
        val mean = sumSquares / wave.size
        return min(1.0, sqrt(mean) / 128.0).toFloat()
    }
}
