// SPDX-License-Identifier: Apache-2.0
package dev.viagaralauncher.visualizer

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ColorPreset(
    val name: String,
    val barColor: Color,
    val peakColor: Color,
)

data class VisualizerSettings(
    val speed: Float = 0.50f,
    val barColor: Color = Color(0xFF00E5FF),
    val peakColor: Color = Color(0xFFFF4081),
    val trackAlpha: Float = 0.05f,
    val isAlwaysOn: Boolean = true,
    val isFloatingEnabled: Boolean = false,
    val floatingHeightDp: Int = 90,
    val isFloatingAtBottom: Boolean = true,
)

class VisualizerPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<VisualizerSettings> = _settings.asStateFlow()

    private fun getStoredColor(key: String, defaultColor: Color): Color {
        val defaultArgb = defaultColor.toArgb()
        val argb = try {
            prefs.getInt(key, defaultArgb)
        } catch (_: ClassCastException) {
            try {
                prefs.getLong(key, defaultArgb.toLong()).toInt()
            } catch (_: Throwable) {
                defaultArgb
            }
        }
        return Color(argb)
    }

    private fun loadSettings(): VisualizerSettings {
        val speed = prefs.getFloat(KEY_SPEED, 0.50f)
        val barColor = getStoredColor(KEY_BAR_COLOR, Color(0xFF00E5FF))
        val peakColor = getStoredColor(KEY_PEAK_COLOR, Color(0xFFFF4081))
        val trackAlpha = prefs.getFloat(KEY_TRACK_ALPHA, 0.05f)
        val isAlwaysOn = prefs.getBoolean(KEY_ALWAYS_ON, true)
        val isFloating = prefs.getBoolean(KEY_FLOATING_ENABLED, false)
        val floatingHeight = prefs.getInt(KEY_FLOATING_HEIGHT, 90)
        val floatingBottom = prefs.getBoolean(KEY_FLOATING_BOTTOM, true)

        return VisualizerSettings(
            speed = speed,
            barColor = barColor,
            peakColor = peakColor,
            trackAlpha = trackAlpha,
            isAlwaysOn = isAlwaysOn,
            isFloatingEnabled = isFloating,
            floatingHeightDp = floatingHeight,
            isFloatingAtBottom = floatingBottom,
        )
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.10f, 1.00f)
        prefs.edit().putFloat(KEY_SPEED, clamped).apply()
        _settings.value = _settings.value.copy(speed = clamped)
        AudioVisualizerManager.setSpeed(clamped)
    }

    fun setColors(barColor: Color, peakColor: Color) {
        prefs.edit()
            .remove(KEY_BAR_COLOR)
            .remove(KEY_PEAK_COLOR)
            .putInt(KEY_BAR_COLOR, barColor.toArgb())
            .putInt(KEY_PEAK_COLOR, peakColor.toArgb())
            .apply()
        _settings.value = _settings.value.copy(barColor = barColor, peakColor = peakColor)
    }

    fun setAlwaysOn(alwaysOn: Boolean) {
        prefs.edit().putBoolean(KEY_ALWAYS_ON, alwaysOn).apply()
        _settings.value = _settings.value.copy(isAlwaysOn = alwaysOn)
        AudioVisualizerManager.setAlwaysOn(alwaysOn)
    }

    fun setFloatingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FLOATING_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(isFloatingEnabled = enabled)
    }

    fun setFloatingHeight(heightDp: Int) {
        val clamped = heightDp.coerceIn(40, 240)
        prefs.edit().putInt(KEY_FLOATING_HEIGHT, clamped).apply()
        _settings.value = _settings.value.copy(floatingHeightDp = clamped)
    }

    fun setFloatingPosition(atBottom: Boolean) {
        prefs.edit().putBoolean(KEY_FLOATING_BOTTOM, atBottom).apply()
        _settings.value = _settings.value.copy(isFloatingAtBottom = atBottom)
    }

    companion object {
        private const val PREFS_NAME = "audio_viz_settings"
        private const val KEY_SPEED = "viz_speed"
        private const val KEY_BAR_COLOR = "viz_bar_color"
        private const val KEY_PEAK_COLOR = "viz_peak_color"
        private const val KEY_TRACK_ALPHA = "viz_track_alpha"
        private const val KEY_ALWAYS_ON = "viz_always_on"
        private const val KEY_FLOATING_ENABLED = "viz_floating_enabled"
        private const val KEY_FLOATING_HEIGHT = "viz_floating_height"
        private const val KEY_FLOATING_BOTTOM = "viz_floating_bottom"

        val PRESETS = listOf(
            ColorPreset("Neon Cyan", Color(0xFF00E5FF), Color(0xFFFF4081)),
            ColorPreset("Matrix Green", Color(0xFF00E676), Color(0xFF76FF03)),
            ColorPreset("Electric Violet", Color(0xFFD500F9), Color(0xFF00E5FF)),
            ColorPreset("Sunset Blaze", Color(0xFFFF6D00), Color(0xFFFFD600)),
            ColorPreset("Cyber Crimson", Color(0xFFFF1744), Color(0xFFFF9100)),
            ColorPreset("Deep Ice", Color(0xFF2979FF), Color(0xFF00E5FF)),
            ColorPreset("Pure White", Color(0xFFFFFFFF), Color(0xFFB0BEC5)),
        )
    }
}
