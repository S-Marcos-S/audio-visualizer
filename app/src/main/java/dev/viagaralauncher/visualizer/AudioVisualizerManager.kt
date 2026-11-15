// SPDX-License-Identifier: Apache-2.0
package dev.viagaralauncher.visualizer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

data class VisualizerFrame(
    val bands: FloatArray = FloatArray(14),
    val peaks: FloatArray = FloatArray(14),
    val rms: Float = 0f,
    val hasAudio: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VisualizerFrame
        if (!bands.contentEquals(other.bands)) return false
        if (!peaks.contentEquals(other.peaks)) return false
        if (rms != other.rms) return false
        if (hasAudio != other.hasAudio) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bands.contentHashCode()
        result = 31 * result + peaks.contentHashCode()
        result = 31 * result + rms.hashCode()
        result = 31 * result + hasAudio.hashCode()
        return result
    }
}

private enum class VisualizerState {
    STOPPED, STARTING, RUNNING, PAUSED, STOPPING
}

object AudioVisualizerManager {

    private const val TAG = "AudioVisualizer"
    private const val NUM_BANDS = 14

    private val _frameFlow = MutableStateFlow(VisualizerFrame())
    val frameFlow: StateFlow<VisualizerFrame> = _frameFlow.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val lifecycleMutex = Mutex()
    private var currentState = VisualizerState.STOPPED
    private var currentGeneration = 0L

    private var supervisorJob: Job? = null
    private var readerJob: Job? = null

    private var daemonProcess: Process? = null

    private val currentLevels = FloatArray(NUM_BANDS)
    private val currentPeaks = FloatArray(NUM_BANDS)

    // Curva de Equalização Acústica de Ruído Rosa (1/f)
    private val SPECTRAL_WEIGHTS = floatArrayOf(
        0.90f, 0.88f, 0.90f, 0.93f, 0.96f, 0.98f, 1.00f,
        1.03f, 1.06f, 1.08f, 1.10f, 1.12f, 1.14f, 1.15f
    )

    @Volatile
    private var currentSpeed: Float = 0.50f

    @Volatile
    private var isAppForeground = true
    @Volatile
    private var isWallpaperVisible = false
    @Volatile
    private var lastEnabledSetting = true
    @Volatile
    private var lastHasSession = false
    @Volatile
    private var lastIsPlaying = false
    @Volatile
    var isAlwaysOnMode: Boolean = true

    @Volatile
    private var appContext: Context? = null
    private var stopJob: Job? = null
    @Volatile
    private var resumeSignal: CompletableDeferred<Unit>? = null

    fun setSpeed(speed: Float) {
        currentSpeed = speed.coerceIn(0.10f, 1.00f)
    }

    fun getSpeed(): Float = currentSpeed

    fun setAlwaysOn(alwaysOn: Boolean) {
        isAlwaysOnMode = alwaysOn
        scope.launch { evaluateStateAndAct() }
    }

    fun updatePlaybackState(context: Context, enabled: Boolean, isPlaying: Boolean, hasSession: Boolean) {
        appContext = context.applicationContext
        lastEnabledSetting = enabled
        lastHasSession = hasSession
        lastIsPlaying = isPlaying
        scope.launch { evaluateStateAndAct() }
    }

    fun onWallpaperVisibilityChanged(context: Context, visible: Boolean) {
        appContext = context.applicationContext
        isWallpaperVisible = visible
        scope.launch { evaluateStateAndAct() }
    }

    private suspend fun evaluateStateAndAct() {
        val effectivePlaying = isAlwaysOnMode || lastIsPlaying
        val effectiveSession = isAlwaysOnMode || lastHasSession
        val isUiActive = isAppForeground || isWallpaperVisible

        val shouldBeRunning = isUiActive && lastEnabledSetting && effectiveSession && effectivePlaying
        val isPausedSession = isUiActive && lastEnabledSetting && effectiveSession && !effectivePlaying

        if (shouldBeRunning) {
            stopJob?.cancel()
            stopJob = null
            if (currentState == VisualizerState.PAUSED) {
                resumeInternal()
            } else if (currentState == VisualizerState.STOPPED) {
                appContext?.let { startInternal(it) }
            }
        } else if (isPausedSession) {
            if (currentState == VisualizerState.RUNNING) {
                pauseInternal()
            }
            if (currentState != VisualizerState.STOPPED && stopJob == null) {
                stopJob = scope.launch {
                    delay(30_000L)
                    stopInternal()
                    stopJob = null
                }
            }
        } else {
            if (!isUiActive) {
                if (currentState == VisualizerState.RUNNING) {
                    pauseInternal()
                }
                // Se a UI não está ativa (app em segundo plano ou tela desligada),
                // agenda parada total do processo daemon após 15 segundos para zerar 100% o consumo de bateria
                if (currentState != VisualizerState.STOPPED && stopJob == null) {
                    stopJob = scope.launch {
                        delay(15_000L)
                        stopInternal()
                        stopJob = null
                    }
                }
            } else if (!lastEnabledSetting || !effectiveSession) {
                stopJob?.cancel()
                stopJob = null
                stopInternal()
            }
        }
    }

    private suspend fun pauseInternal() {
        lifecycleMutex.withLock {
            if (currentState == VisualizerState.RUNNING) {
                currentState = VisualizerState.PAUSED
                if (resumeSignal == null || resumeSignal?.isCompleted == true) {
                    resumeSignal = CompletableDeferred()
                }
                sendControlCommand('P')
                withContext(Dispatchers.Default) {
                    currentLevels.fill(0f)
                    currentPeaks.fill(0f)
                    _frameFlow.value = VisualizerFrame()
                }
            }
        }
    }

    private suspend fun resumeInternal() {
        lifecycleMutex.withLock {
            if (currentState == VisualizerState.PAUSED) {
                currentState = VisualizerState.RUNNING
                sendControlCommand('R')
                resumeSignal?.complete(Unit)
                resumeSignal = null
            }
        }
    }

    private suspend fun startInternal(context: Context) {
        lifecycleMutex.withLock {
            if (currentState != VisualizerState.STOPPED) return@withLock
            currentState = VisualizerState.STARTING
            val gen = ++currentGeneration

            supervisorJob = scope.launch {
                runSupervisor(context.applicationContext, gen)
            }
        }
    }

    private suspend fun stopInternal() {
        lifecycleMutex.withLock {
            if (currentState == VisualizerState.STOPPED || currentState == VisualizerState.STOPPING) return@withLock
            currentState = VisualizerState.STOPPING
            val gen = currentGeneration

            supervisorJob?.cancel()
            supervisorJob = null

            resumeSignal?.cancel()
            resumeSignal = null

            cleanupDaemonLocked(gen)

            withContext(Dispatchers.Default) {
                currentLevels.fill(0f)
                currentPeaks.fill(0f)
                _frameFlow.value = VisualizerFrame()
            }

            currentState = VisualizerState.STOPPED
        }
    }

    fun onResume(context: Context? = null) {
        isAppForeground = true
        if (context != null) appContext = context.applicationContext
        scope.launch { evaluateStateAndAct() }
    }

    fun onPause() {
        isAppForeground = false
        scope.launch { evaluateStateAndAct() }
    }

    fun killAnyOrphanDaemon() {
        scope.launch(Dispatchers.IO) {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f dev.viagaralauncher.visualizer.AudioVisualizerDaemon || true")).waitFor()
            } catch (_: Throwable) {}
        }
    }

    private fun sendControlCommand(cmd: Char) {
        try {
            daemonProcess?.outputStream?.let {
                it.write(cmd.code)
                it.flush()
            }
        } catch (_: Throwable) {}
    }

    private suspend fun runSupervisor(context: Context, generation: Long) = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            lifecycleMutex.withLock {
                if (generation != currentGeneration) return@withContext
            }

            val dexPath = extractOrPrepareDex(context) ?: run {
                Log.e(TAG, "Falha ao preparar visualizer_daemon.dex")
                lifecycleMutex.withLock {
                    if (generation == currentGeneration) {
                        cleanupDaemonLocked(generation)
                        currentState = VisualizerState.STOPPED
                    }
                }
                return@withContext
            }

            val launchScript = """
                base=/data/local/tmp
                mkdir -p ${'$'}base/dalvik-cache
                chmod 777 ${'$'}base/dalvik-cache 2>/dev/null
                export ANDROID_DATA=${'$'}base
                export CLASSPATH="$dexPath"

                APP_PROCESS="/system/bin/app_process64"
                if [ ! -f "${'$'}APP_PROCESS" ]; then
                    if [ -f "/system/bin/app_process" ]; then
                        APP_PROCESS="/system/bin/app_process"
                    elif [ -f "/system/bin/app_process32" ]; then
                        APP_PROCESS="/system/bin/app_process32"
                    else
                        APP_PROCESS="app_process"
                    fi
                fi

                exec ${'$'}APP_PROCESS ${'$'}base dev.viagaralauncher.visualizer.AudioVisualizerDaemon
            """.trimIndent()

            Log.i(TAG, "Iniciando daemon via su com dex em: $dexPath")
            process = ProcessBuilder("su", "-c", launchScript).start()

            // Monitoramento e log de diagnósticos do stderr do daemon
            val errStream = process.errorStream
            scope.launch(Dispatchers.IO) {
                try {
                    errStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            Log.i("VizDaemon", line)
                        }
                    }
                } catch (_: Throwable) {}
            }

            lifecycleMutex.withLock {
                if (generation != currentGeneration || currentState != VisualizerState.STARTING) {
                    process.destroy()
                    return@withContext
                }
                daemonProcess = process
                currentState = VisualizerState.RUNNING
            }

            // Inicia leitura de alta performance dos quadros binários (65 bytes a 60 FPS)
            startReaderLoop(process.inputStream)

            val exitCode = process.waitFor()
            Log.i(TAG, "Daemon encerrado com codigo $exitCode")

        } catch (e: Throwable) {
            Log.e(TAG, "Erro no supervisor do visualizador: ${e.message}", e)
        } finally {
            lifecycleMutex.withLock {
                if (generation == currentGeneration) {
                    cleanupDaemonLocked(generation)
                    currentState = VisualizerState.STOPPED
                }
            }
        }
    }

    private fun startReaderLoop(inputStream: InputStream) {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            val bufferedIn = BufferedInputStream(inputStream, 16384)
            val packetSize = 4 + 1 + 4 + (NUM_BANDS * 4) // 65 bytes
            val payloadSize = packetSize - 4 // 61 bytes após o cabeçalho 'V','I','Z','1'
            val payloadBuffer = ByteArray(payloadSize)
            val byteBuffer = ByteBuffer.wrap(payloadBuffer).order(ByteOrder.LITTLE_ENDIAN)
            val incomingBands = FloatArray(NUM_BANDS)

            while (isActive && (currentState == VisualizerState.RUNNING || currentState == VisualizerState.PAUSED)) {
                if (currentState == VisualizerState.PAUSED) {
                    val signal = resumeSignal
                    if (signal != null) {
                        try {
                            signal.await()
                        } catch (_: Throwable) {
                            break
                        }
                    } else {
                        delay(100)
                    }
                    continue
                }

                // 1. Sincronização estrita de frame com o cabeçalho 'V', 'I', 'Z', '1'
                var b = bufferedIn.read()
                while (b != -1 && b.toByte() != 'V'.code.toByte() && isActive) {
                    b = bufferedIn.read()
                }
                if (b == -1 || !isActive) break

                val b2 = bufferedIn.read()
                val b3 = bufferedIn.read()
                val b4 = bufferedIn.read()
                if (b2 != 'I'.code || b3 != 'Z'.code || b4 != '1'.code) {
                    continue
                }

                // 2. Leitura dos 61 bytes restantes do pacote
                var totalRead = 0
                while (totalRead < payloadSize && isActive) {
                    val read = bufferedIn.read(payloadBuffer, totalRead, payloadSize - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                if (totalRead < payloadSize || !isActive) break

                // Se houver pacotes mais novos acumulados no buffer do pipe (atraso acumulado),
                // continua consumindo para publicar apenas o quadro mais recente em tempo real
                if (bufferedIn.available() >= packetSize) {
                    continue
                }

                // 3. Decodificação dos campos binários Little-Endian
                byteBuffer.position(0)
                val bandsCount = byteBuffer.get().toInt()
                if (bandsCount != NUM_BANDS) continue

                val rms = byteBuffer.float
                for (i in 0 until NUM_BANDS) {
                    incomingBands[i] = byteBuffer.float
                }

                var activeSignal = false
                val bandsOut = FloatArray(NUM_BANDS)
                val peaksOut = FloatArray(NUM_BANDS)

                // 4. Parâmetros dinâmicos de balística física
                val normSpeed = ((currentSpeed - 0.10f) / 0.90f).coerceIn(0f, 1f)
                val speedCurve = normSpeed.pow(1.4f)

                // Ataque instantâneo e responsivo aos transientes sonoros (sem delay)
                val baseRiseAlpha = (0.72f + 0.26f * speedCurve).coerceIn(0.68f, 0.98f)
                // Decaimento suave com amortecimento físico perfeito para batidas musicais
                val baseFallAlpha = (0.12f + 0.24f * speedCurve).coerceIn(0.10f, 0.38f)
                val peakFallAlpha = (0.015f + 0.035f * speedCurve).coerceIn(0.012f, 0.050f)

                for (i in 0 until NUM_BANDS) {
                    val rawTarget = incomingBands[i].coerceIn(0f, 1f)
                    if (rawTarget > 0.005f) activeSignal = true

                    val target = when (i) {
                        0 -> ((rawTarget - 0.03f) / 0.97f).coerceIn(0f, 1f)
                        1 -> ((rawTarget - 0.02f) / 0.98f).coerceIn(0f, 1f)
                        2 -> ((rawTarget - 0.01f) / 0.99f).coerceIn(0f, 1f)
                        else -> {
                            val weight = SPECTRAL_WEIGHTS[i]
                            (rawTarget * weight).coerceIn(0f, 1f)
                        }
                    }

                    // Balística assimétrica (Ataque rápido, Decaimento amortecido)
                    val current = currentLevels[i]
                    val alpha = if (target >= current) baseRiseAlpha else baseFallAlpha
                    currentLevels[i] = current + (target - current) * alpha
                    if (currentLevels[i] < 0.002f) currentLevels[i] = 0f
                    bandsOut[i] = currentLevels[i]

                    // Picos flutuantes independentes
                    val v = currentLevels[i]
                    if (v >= currentPeaks[i]) {
                        currentPeaks[i] = v
                    } else {
                        currentPeaks[i] += (v - currentPeaks[i]) * peakFallAlpha
                    }
                    if (currentPeaks[i] < 0.002f) currentPeaks[i] = 0f
                    peaksOut[i] = currentPeaks[i]
                }

                _frameFlow.value = VisualizerFrame(
                    bands = bandsOut,
                    peaks = peaksOut,
                    rms = rms,
                    hasAudio = activeSignal || rms > 0.008f,
                )
            }
        }
    }

    private suspend fun cleanupDaemonLocked(expectedGeneration: Long) = withContext(Dispatchers.IO) {
        if (expectedGeneration != currentGeneration) return@withContext

        readerJob?.cancel()
        readerJob = null

        try {
            sendControlCommand('S')
        } catch (_: Throwable) {}

        val proc = daemonProcess
        daemonProcess = null

        if (proc != null) {
            try {
                proc.destroy()
                delay(80)
                if (proc.isAlive) proc.destroyForcibly()
            } catch (_: Throwable) {}
        }

        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f dev.viagaralauncher.visualizer.AudioVisualizerDaemon || true")).waitFor()
        } catch (_: Throwable) {}
    }

    private fun extractOrPrepareDex(context: Context): String? {
        val targetFile = File(context.filesDir, "visualizer_daemon.dex")
        try {
            context.assets.open("visualizer_daemon.dex").use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            targetFile.setReadable(true, false)

            // Copia para /data/local/tmp garantindo leitura pelo processo root
            try {
                val tmpPath = "/data/local/tmp/visualizer_daemon.dex"
                val p = Runtime.getRuntime().exec(arrayOf(
                    "su", "-c",
                    "cp '${targetFile.absolutePath}' '$tmpPath' && chmod 644 '$tmpPath'"
                ))
                p.waitFor()
                return tmpPath
            } catch (_: Throwable) {}

            return targetFile.absolutePath
        } catch (_: Throwable) {
            val fallback = File("/data/local/tmp/visualizer_daemon.dex")
            if (fallback.exists()) return fallback.absolutePath
        }
        return null
    }
}
