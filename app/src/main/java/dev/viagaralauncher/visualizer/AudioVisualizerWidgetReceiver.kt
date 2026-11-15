// SPDX-License-Identifier: Apache-2.0
package dev.viagaralauncher.visualizer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb

class AudioVisualizerWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs = VisualizerPreferences(context)
            val settings = prefs.settings.value

            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            val bitmap = renderWidgetBitmap(settings)
            views.setImageViewBitmap(R.id.widget_visualizer_image, bitmap)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, AudioVisualizerWidgetReceiver::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (widgetId in allWidgetIds) {
                updateAppWidget(context, appWidgetManager, widgetId)
            }
        }

        private fun renderWidgetBitmap(settings: VisualizerSettings): Bitmap {
            val width = 480
            val height = 200
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val numStrips = 14
            val gap = 6f
            val totalGap = gap * (numStrips - 1)
            val stripWidth = (width - 32f - totalGap) / numStrips
            val halfWidth = (stripWidth / 2f - 2f).coerceAtLeast(3f)
            val startX = 16f
            val baselineY = height - 16f
            val maxBarHeight = height - 32f

            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = settings.barColor.toArgb()
                style = Paint.Style.FILL
            }

            val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = settings.peakColor.toArgb()
                style = Paint.Style.FILL
            }

            val simulatedBands = floatArrayOf(
                0.85f, 0.95f, 0.70f, 0.60f, 0.50f, 0.65f, 0.80f,
                0.75f, 0.60f, 0.55f, 0.45f, 0.50f, 0.40f, 0.35f
            )

            for (i in 0 until numStrips) {
                val v = simulatedBands[i]
                val x = startX + i * (stripWidth + gap)
                val rx = x + stripWidth / 2f + 1f

                val lh = v * maxBarHeight
                val cr = stripWidth * 0.35f

                // Left bar
                canvas.drawRoundRect(
                    RectF(x, baselineY - lh, x + halfWidth, baselineY),
                    cr,
                    cr,
                    barPaint
                )

                // Left peak
                val peakTop = (baselineY - lh - 8f).coerceAtLeast(4f)
                canvas.drawRoundRect(
                    RectF(x, peakTop, x + halfWidth, peakTop + 6f),
                    3f,
                    3f,
                    peakPaint
                )

                // Right bar (slight stereo attenuation)
                val rh = lh * 0.92f
                canvas.drawRoundRect(
                    RectF(rx, baselineY - rh, rx + halfWidth, baselineY),
                    cr,
                    cr,
                    barPaint
                )

                // Right peak
                val rightPeakTop = (baselineY - rh - 8f).coerceAtLeast(4f)
                canvas.drawRoundRect(
                    RectF(rx, rightPeakTop, rx + halfWidth, rightPeakTop + 6f),
                    3f,
                    3f,
                    peakPaint
                )
            }

            return bitmap
        }
    }
}
