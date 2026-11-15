// SPDX-License-Identifier: Apache-2.0
package dev.viagaralauncher.visualizer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max

class AudioVisualizerWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "AudioVizWallpaper"
        private val activeEngines = CopyOnWriteArraySet<VisualizerEngine>()

        fun reloadWallpaper() {
            activeEngines.forEach { it.requestReloadWallpaper() }
        }

        fun reloadSettings() {
            activeEngines.forEach { it.requestReloadSettings() }
        }
    }

    override fun onCreateEngine(): Engine {
        return VisualizerEngine()
    }

    inner class VisualizerEngine : Engine(), Choreographer.FrameCallback {

        private val engineScope = CoroutineScope(Dispatchers.Default + Job())
        private val prefs = VisualizerPreferences(this@AudioVisualizerWallpaperService)
        private val playbackTracker = MediaPlaybackTracker(this@AudioVisualizerWallpaperService)

        @Volatile
        private var settings: VisualizerSettings = prefs.settings.value

        @Volatile
        private var wallpaperBitmap: Bitmap? = null

        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var isEngineVisible = false
        private var isFrameCallbackScheduled = false

        private var lastFrameTimeNanos: Long = 0L
        private var activeAlphaAnim: Float = 0f
        private var xParallaxOffset: Float = 0.5f

        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = AndroidColor.BLACK
        }

        private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val rightBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val rectF = RectF()

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            activeEngines.add(this)

            // Observa configurações continuamente
            engineScope.launch {
                prefs.settings.collectLatest { newSettings ->
                    settings = newSettings
                    withContext(Dispatchers.Main) {
                        forceRenderCurrentFrame()
                    }
                }
            }

            // Observa frames de áudio para acordar o renderizador
            engineScope.launch {
                AudioVisualizerManager.frameFlow.collectLatest { frame ->
                    if (frame.hasAudio && isEngineVisible && !isFrameCallbackScheduled) {
                        withContext(Dispatchers.Main) {
                            scheduleNextFrame()
                        }
                    }
                }
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            activeEngines.remove(this)
            playbackTracker.stopTracking()
            AudioVisualizerManager.onWallpaperVisibilityChanged(this@AudioVisualizerWallpaperService, false)
            removeFrameCallback()
            engineScope.cancel()

            wallpaperBitmap?.recycle()
            wallpaperBitmap = null
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            isEngineVisible = visible
            AudioVisualizerManager.onWallpaperVisibilityChanged(this@AudioVisualizerWallpaperService, visible)

            if (visible) {
                // Atualiza configurações mais recentes
                settings = prefs.settings.value

                // Se o bitmap não estiver carregado, recarrega
                val bmp = wallpaperBitmap
                if (bmp == null || bmp.isRecycled) {
                    loadWallpaperAsync()
                }

                playbackTracker.startTracking()
                lastFrameTimeNanos = 0L

                // Força render imediato do estado atual
                forceRenderCurrentFrame()
                scheduleNextFrame()
            } else {
                playbackTracker.stopTracking()
                removeFrameCallback()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            loadWallpaperAsync()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            gradientPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                AndroidColor.parseColor("#121212"),
                AndroidColor.parseColor("#1E1E1E"),
                Shader.TileMode.CLAMP
            )

            val bmp = wallpaperBitmap
            if (bmp == null || bmp.isRecycled) {
                loadWallpaperAsync()
            } else {
                forceRenderCurrentFrame()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            removeFrameCallback()
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            if (xOffset in 0f..1f && xOffset != xParallaxOffset) {
                xParallaxOffset = xOffset
                forceRenderCurrentFrame()
            }
        }

        fun requestReloadWallpaper() {
            loadWallpaperAsync()
        }

        fun requestReloadSettings() {
            settings = prefs.settings.value
            forceRenderCurrentFrame()
        }

        private fun loadWallpaperAsync() {
            engineScope.launch(Dispatchers.IO) {
                val targetW = if (surfaceWidth > 0) surfaceWidth else 1080
                val targetH = if (surfaceHeight > 0) surfaceHeight else 2400
                val bmp = WallpaperHelper.loadWallpaperBitmap(
                    this@AudioVisualizerWallpaperService,
                    targetW,
                    targetH
                )
                withContext(Dispatchers.Main) {
                    val old = wallpaperBitmap
                    wallpaperBitmap = bmp
                    if (old != null && old != bmp) {
                        old.recycle()
                    }
                    forceRenderCurrentFrame()
                }
            }
        }

        private fun scheduleNextFrame() {
            if (!isFrameCallbackScheduled && isEngineVisible) {
                isFrameCallbackScheduled = true
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        private fun removeFrameCallback() {
            if (isFrameCallbackScheduled) {
                Choreographer.getInstance().removeFrameCallback(this)
                isFrameCallbackScheduled = false
            }
        }

        override fun doFrame(frameTimeNanos: Long) {
            isFrameCallbackScheduled = false
            if (!isEngineVisible) return

            val dtSeconds = if (lastFrameTimeNanos > 0L) {
                ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
            } else {
                0.016f
            }
            lastFrameTimeNanos = frameTimeNanos

            val frame = AudioVisualizerManager.frameFlow.value
            val targetAlpha = if (frame.hasAudio) 1f else 0f

            // Fade in/out suave (~300ms)
            val fadeSpeed = 1f / 0.30f
            if (activeAlphaAnim < targetAlpha) {
                activeAlphaAnim = (activeAlphaAnim + fadeSpeed * dtSeconds).coerceAtMost(1f)
            } else if (activeAlphaAnim > targetAlpha) {
                activeAlphaAnim = (activeAlphaAnim - fadeSpeed * dtSeconds).coerceAtLeast(0f)
            }

            renderCurrentFrame(frame)

            // Continua o loop enquanto houver áudio ou a animação de fade não tiver chegado a 0
            if (activeAlphaAnim > 0.001f || frame.hasAudio) {
                scheduleNextFrame()
            }
        }

        private fun forceRenderCurrentFrame() {
            if (!isEngineVisible) return
            val frame = AudioVisualizerManager.frameFlow.value
            renderCurrentFrame(frame)
        }

        private fun renderCurrentFrame(frame: VisualizerFrame) {
            val holder = surfaceHolder ?: return
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return

            val canvas: Canvas = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    holder.lockHardwareCanvas()
                } else {
                    holder.lockCanvas()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao travar canvas: ${e.message}")
                null
            } ?: return

            try {
                drawBackground(canvas)
                drawVisualizerBars(canvas, frame)
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao destravar canvas: ${e.message}")
                }
            }
        }

        private fun drawBackground(canvas: Canvas) {
            val bmp = wallpaperBitmap
            val sw = surfaceWidth.toFloat()
            val sh = surfaceHeight.toFloat()

            if (bmp != null && !bmp.isRecycled) {
                val bw = bmp.width.toFloat()
                val bh = bmp.height.toFloat()

                val scale = max(sw / bw, sh / bh)
                val scaledW = bw * scale
                val scaledH = bh * scale

                val maxScrollX = max(0f, scaledW - sw)
                val left = -maxScrollX * xParallaxOffset
                val top = (sh - scaledH) / 2f

                rectF.set(left, top, left + scaledW, top + scaledH)
                canvas.drawBitmap(bmp, null, rectF, backgroundPaint)

                // Aplica escurecimento (dim) para aumentar o contraste
                val dimAlpha = (settings.wallpaperDim * 255f).toInt().coerceIn(0, 255)
                if (dimAlpha > 0) {
                    dimPaint.alpha = dimAlpha
                    canvas.drawRect(0f, 0f, sw, sh, dimPaint)
                }
            } else {
                // Fundo gradiente moderno caso não haja imagem carregada
                canvas.drawRect(0f, 0f, sw, sh, gradientPaint)
            }
        }

        private fun drawVisualizerBars(canvas: Canvas, frame: VisualizerFrame) {
            val bands = frame.bands
            val peaks = frame.peaks
            val strips = bands.size
            if (strips == 0) return

            val w = surfaceWidth.toFloat()
            val h = surfaceHeight.toFloat()
            val density = resources.displayMetrics.density

            val gap = 3.5f * density
            val stripW = ((w - gap * (strips - 1) - gap * 2) / strips).coerceAtLeast(3f * density)
            val cr = stripW * 0.4f
            val half = (stripW / 2f - 1f * density).coerceAtLeast(1.5f * density)
            val peakBlockHeight = 3f * density
            val peakCr = 1.5f * density

            val totalWidth = strips * stripW + (strips - 1) * gap
            val startX = (w - totalWidth) / 2f

            // Posição vertical e altura totalmente reativas às configurações
            val isAtBottom = settings.wallpaperVerticalPosition >= 0.99f
            val baselineY = if (isAtBottom) h else (h * settings.wallpaperVerticalPosition)
            val bottomOffset = if (isAtBottom) cr else 0f
            val maxBarHeight = (settings.wallpaperHeightDp * density).coerceAtLeast(stripW)

            val baseBarArgb = settings.barColor.toArgb()
            val basePeakArgb = settings.peakColor.toArgb()
            val showGuides = settings.wallpaperShowGuides

            for (i in 0 until strips) {
                val v = bands[i]
                val pk = peaks[i]

                val x = startX + i * (stripW + gap)
                val rx = x + stripW / 2f + 1f * density

                // 1. Guias de trilhas de fundo translúcidas nítidas
                if (showGuides) {
                    val trackAlphaFloat = settings.trackAlpha.coerceAtLeast(0.28f)
                    val trackAlphaInt = (trackAlphaFloat * 255).toInt().coerceIn(0, 255)
                    guidePaint.color = baseBarArgb
                    guidePaint.alpha = trackAlphaInt

                    rectF.set(x, baselineY - maxBarHeight, x + half, baselineY + bottomOffset)
                    canvas.drawRoundRect(rectF, cr, cr, guidePaint)

                    guidePaint.alpha = (trackAlphaInt * 0.9f).toInt()
                    rectF.set(rx, baselineY - maxBarHeight, rx + half, baselineY + bottomOffset)
                    canvas.drawRoundRect(rectF, cr, cr, guidePaint)
                }

                // 2. Barra do Canal Esquerdo
                val lh = (v * maxBarHeight).coerceAtLeast(0f)
                if (lh > 1f && activeAlphaAnim > 0.01f) {
                    val stripAlpha = (0.35f + v * 0.65f).coerceIn(0.2f, 0.95f) * activeAlphaAnim
                    barPaint.color = baseBarArgb
                    barPaint.alpha = (stripAlpha * 255).toInt().coerceIn(0, 255)

                    rectF.set(x, baselineY - lh, x + half, baselineY + bottomOffset)
                    canvas.drawRoundRect(rectF, cr, cr, barPaint)
                }

                // 3. Pico flutuante do Canal Esquerdo
                val pkH = (pk * maxBarHeight).coerceAtLeast(0f)
                if (pk > 0.03f && pkH > 2f && activeAlphaAnim > 0.01f) {
                    val peakTop = (baselineY - pkH - 2f * density).coerceAtLeast(0f)
                    peakPaint.color = basePeakArgb
                    peakPaint.alpha = (0.88f * activeAlphaAnim * 255).toInt().coerceIn(0, 255)

                    rectF.set(x, peakTop, x + half, peakTop + peakBlockHeight)
                    canvas.drawRoundRect(rectF, peakCr, peakCr, peakPaint)
                }

                // 4. Barra do Canal Direito (atenuação estéreo de 0.92f)
                val rv = v * 0.92f
                val rh = (rv * maxBarHeight).coerceAtLeast(0f)
                if (rh > 1f && activeAlphaAnim > 0.01f) {
                    val rightStripAlpha = ((0.35f + rv * 0.65f) * 0.9f).coerceIn(0.18f, 0.85f) * activeAlphaAnim
                    rightBarPaint.color = baseBarArgb
                    rightBarPaint.alpha = (rightStripAlpha * 255).toInt().coerceIn(0, 255)

                    rectF.set(rx, baselineY - rh, rx + half, baselineY + bottomOffset)
                    canvas.drawRoundRect(rectF, cr, cr, rightBarPaint)
                }

                // 5. Pico flutuante do Canal Direito
                val rpk = (pk * 0.92f).coerceIn(0f, 1f)
                val rpkH = (rpk * maxBarHeight).coerceAtLeast(0f)
                if (rpk > 0.03f && rpkH > 2f && activeAlphaAnim > 0.01f) {
                    val rightPeakTop = (baselineY - rpkH - 2f * density).coerceAtLeast(0f)
                    peakPaint.color = basePeakArgb
                    peakPaint.alpha = (0.78f * activeAlphaAnim * 255).toInt().coerceIn(0, 255)

                    rectF.set(rx, rightPeakTop, rx + half, rightPeakTop + peakBlockHeight)
                    canvas.drawRoundRect(rectF, peakCr, peakCr, peakPaint)
                }
            }
        }
    }
}
