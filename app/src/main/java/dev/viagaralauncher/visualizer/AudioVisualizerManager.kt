// SPDX-License-Identifier: Apache-2.0
package dev.viagaralauncher.visualizer

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
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

    private const val SOCKET_NAME = "viagara_audio_viz"
    private const val NUM_BANDS = 14

    private val _frameFlow = MutableStateFlow(VisualizerFrame())
    val frameFlow: StateFlow<VisualizerFrame> = _frameFlow.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val lifecycleMutex = Mutex()
    private var currentState = VisualizerState.STOPPED
    private var currentGeneration = 0L

    private var supervisorJob: Job? = null
    private var readerJob: Job? = null

    private var serverSocket: LocalServerSocket? = null
    private var clientSocket: LocalSocket? = null
    private var clientOut: OutputStream? = null

    private var daemonProcess: Process? = null
    private var daemonPid: Int = -1

    private val currentLevels = FloatArray(NUM_BANDS)
    private val currentPeaks = FloatArray(NUM_BANDS)

    // Curva de Equalização de Ruído Rosa (1/f)
    private val SPECTRAL_WEIGHTS = floatArrayOf(
        0.88f, 0.86f, 0.88f, 0.91f, 0.94f, 0.97f, 1.00f,
        1.03f, 1.06f, 1.08f, 1.10f, 1.12f, 1.14f, 1.14f
    )

    @Volatile
    private var currentSpeed: Float = 0.50f

    @Volatile
    private var isAppForeground = true
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

    private suspend fun evaluateStateAndAct() {
        val effectivePlaying = isAlwaysOnMode || lastIsPlaying
        val effectiveSession = isAlwaysOnMode || lastHasSession

        val shouldBeRunning = isAppForeground && lastEnabledSetting && effectiveSession && effectivePlaying
        val isPausedSession = isAppForeground && lastEnabledSetting && effectiveSession && !effectivePlaying

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
            stopJob?.cancel()
            stopJob = null
            if (!isAppForeground && currentState == VisualizerState.RUNNING) {
                pauseInternal()
            } else if (!lastEnabledSetting || !effectiveSession) {
                stopInternal()
            }
        }
    }

    private suspend fun pauseInternal() {
        lifecycleMutex.withLock {
            if (currentState == VisualizerState.RUNNING) {
                currentState = VisualizerState.PAUSED
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
            clientOut?.let {
                it.write(cmd.code)
                it.flush()
            }
        } catch (_: Throwable) {}
    }

    private suspend fun runSupervisor(context: Context, generation: Long) = withContext(Dispatchers.IO) {
        var localDaemonPid = -1
        var localProcess: Process? = null
        var localClient: LocalSocket? = null
        try {
            try {
                serverSocket = LocalServerSocket(SOCKET_NAME)
            } catch (_: Throwable) {
                serverSocket?.close()
                serverSocket = LocalServerSocket(SOCKET_NAME)
            }

            lifecycleMutex.withLock {
                if (generation != currentGeneration) return@withContext
            }

            val dexPath = extractOrPrepareDex(context) ?: run {
                lifecycleMutex.withLock {
                    if (generation == currentGeneration) {
                        cleanupDaemonLocked(generation)
                        currentState = VisualizerState.STOPPED
                    }
                }
                return@withContext
            }

            // Executa o daemon como Root via app_process64
            val launchScript = """
                export CLASSPATH="$dexPath"
                /system/bin/app_process64 /data/local/tmp dev.viagaralauncher.visualizer.AudioVisualizerDaemon &
                echo "PID:${'$'}!"
                wait
            """.trimIndent()

            val process = ProcessBuilder("su", "-c", launchScript).start()
            localProcess = process

            val reader = process.inputStream.bufferedReader()
            var extractedPid = -1
            val timeoutMillis = System.currentTimeMillis() + 2000L
            while (System.currentTimeMillis() < timeoutMillis) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("PID:")) {
                        extractedPid = line.substring(4).trim().toIntOrNull() ?: -1
                        break
                    }
                } else {
                    delay(40)
                }
            }
            localDaemonPid = extractedPid

            lifecycleMutex.withLock {
                if (generation != currentGeneration || currentState != VisualizerState.STARTING) {
                    terminateProcessAndPid(process, localDaemonPid)
                    return@withContext
                }
                daemonProcess = process
                daemonPid = extractedPid
            }

            val client = serverSocket?.accept() ?: run {
                lifecycleMutex.withLock {
                    if (generation == currentGeneration) {
                        cleanupDaemonLocked(generation)
                        currentState = VisualizerState.STOPPED
                    }
                }
                return@withContext
            }
            localClient = client

            lifecycleMutex.withLock {
                if (generation != currentGeneration || currentState != VisualizerState.STARTING) {
                    try { client.close() } catch (_: Throwable) {}
                    if (generation == currentGeneration) {
                        cleanupDaemonLocked(generation)
                    } else {
                        terminateProcessAndPid(process, localDaemonPid)
                    }
                    return@withLock
                }
                clientSocket = client
                clientOut = client.outputStream
                currentState = VisualizerState.RUNNING
            }

            startReaderLoop(client.inputStream)
        } catch (_: Throwable) {
            lifecycleMutex.withLock {
                if (generation == currentGeneration) {
                    cleanupDaemonLocked(generation)
                    currentState = VisualizerState.STOPPED
                } else {
                    try { localClient?.close() } catch (_: Throwable) {}
                    if (localDaemonPid > 0 && localDaemonPid != daemonPid) {
                        terminateProcessAndPid(localProcess, localDaemonPid)
                    }
                }
            }
        }
    }

    private fun startReaderLoop(inputStream: InputStream) {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.Default) {
            val packetSize = 4 + 1 + 4 + (NUM_BANDS * 4) // 65 bytes
            val rawBytes = ByteArray(packetSize)
            val byteBuffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
            val incomingBands = FloatArray(NUM_BANDS)

            while (isActive && (currentState == VisualizerState.RUNNING || currentState == VisualizerState.PAUSED)) {
                if (currentState == VisualizerState.PAUSED) {
                    delay(100)
                    continue
                }

                var totalRead = 0
                while (totalRead < packetSize && isActive) {
                    val read = inputStream.read(rawBytes, totalRead, packetSize - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                if (totalRead < packetSize) break

                if (rawBytes[0] != 'V'.code.toByte() ||
                    rawBytes[1] != 'I'.code.toByte() ||
                    rawBytes[2] != 'Z'.code.toByte() ||
                    rawBytes[3] != '1'.code.toByte()
                ) continue

                byteBuffer.position(5)
                val rms = byteBuffer.float
                for (b in 0 until NUM_BANDS) {
                    incomingBands[b] = byteBuffer.float
                }

                var activeSignal = false
                val bandsOut = FloatArray(NUM_BANDS)
                val peaksOut = FloatArray(NUM_BANDS)

                // Cálculo da balística
                val normSpeed = ((currentSpeed - 0.10f) / 0.90f).coerceIn(0f, 1f)
                val speedCurve = normSpeed.pow(1.4f)

                val baseRiseAlpha = (0.045f + 0.455f * speedCurve).coerceIn(0.035f, 0.60f)
                val baseFallAlpha = (0.010f + 0.150f * speedCurve).coerceIn(0.008f, 0.20f)
                val peakFallAlpha = (0.0018f + 0.012f * speedCurve).coerceIn(0.0015f, 0.018f)

                for (i in 0 until NUM_BANDS) {
                    val rawTarget = incomingBands[i].coerceIn(0f, 1f)
                    if (rawTarget > 0.01f) activeSignal = true

                    val target = when (i) {
                        0 -> {
                            val cleaned = ((rawTarget - 0.11f) / 0.89f).coerceIn(0f, 1f)
                            (cleaned.pow(1.25f) * 0.88f).coerceIn(0f, 1f)
                        }
                        1 -> {
                            val cleaned = ((rawTarget - 0.08f) / 0.92f).coerceIn(0f, 1f)
                            (cleaned.pow(1.22f) * 0.85f).coerceIn(0f, 1f)
                        }
                        2 -> {
                            val cleaned = ((rawTarget - 0.06f) / 0.94f).coerceIn(0f, 1f)
                            (cleaned.pow(1.18f) * 0.86f).coerceIn(0f, 1f)
                        }
                        else -> {
                            val weight = SPECTRAL_WEIGHTS[i]
                            val cleaned = ((rawTarget - 0.04f) / 0.96f).coerceIn(0f, 1f)
                            (cleaned * weight * 0.84f).coerceIn(0f, 1f)
                        }
                    }

                    // 1. Balística assimétrica de subida ágil e descida amortecida
                    val current = currentLevels[i]
                    val alpha = if (target >= current) baseRiseAlpha else baseFallAlpha
                    currentLevels[i] = current + (target - current) * alpha
                    if (currentLevels[i] < 0.002f) currentLevels[i] = 0f
                    bandsOut[i] = currentLevels[i]

                    // 2. Física dos picos flutuantes
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
                    hasAudio = activeSignal || rms > 0.015f,
                )
            }
        }
    }

    private suspend fun cleanupDaemonLocked(expectedGeneration: Long) = withContext(Dispatchers.IO) {
        if (expectedGeneration != currentGeneration) return@withContext

        readerJob?.cancel()
        readerJob = null

        try {
            clientOut?.write('S'.code)
            clientOut?.flush()
        } catch (_: Throwable) {}

        try { clientSocket?.close() } catch (_: Throwable) {}
        clientSocket = null
        clientOut = null

        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null

        val targetPid = daemonPid
        daemonPid = -1
        val proc = daemonProcess
        daemonProcess = null

        terminateProcessAndPid(proc, targetPid)
    }

    private fun terminateProcessAndPid(proc: Process?, pid: Int) {
        if (proc != null) {
            val exited = runCatching {
                var count = 0
                while (count < 6) {
                    if (!proc.isAlive) return@runCatching true
                    Thread.sleep(50)
                    count++
                }
                false
            }.getOrDefault(false)

            if (!exited) {
                proc.destroy()
                try { Thread.sleep(100) } catch (_: Throwable) {}
                if (proc.isAlive) proc.destroyForcibly()
            }
        }

        if (pid > 0) {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -TERM $pid || true")).waitFor()
                Thread.sleep(80)
                Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -0 $pid && kill -KILL $pid || true")).waitFor()
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

            // Também assegura permissão e disponibilidade em /data/local/tmp caso SELinux restrinja o app_data_file
            try {
                val tmpFile = File("/data/local/tmp/visualizer_daemon.dex")
                Runtime.getRuntime().exec(arrayOf("su", "-c", "cp '${targetFile.absolutePath}' '${tmpFile.absolutePath}' && chmod 777 '${tmpFile.absolutePath}'")).waitFor()
                if (tmpFile.exists() && tmpFile.canRead()) {
                    return tmpFile.absolutePath
                }
            } catch (_: Throwable) {}

            return targetFile.absolutePath
        } catch (_: Throwable) {
            val fallback = File("/data/local/tmp/visualizer_daemon.dex")
            if (fallback.exists()) return fallback.absolutePath
        }
        return null
    }
}
