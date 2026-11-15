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
    val wallpaperDim: Float = 0.20f,
    val wallpaperHeightDp: Int = 110,
    val wallpaperVerticalPosition: Float = 0.82f,
    val wallpaperShowGuides: Boolean = true,
)

class VisualizerPreferences(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val updated = loadSettings()
        _sharedSettings.value = updated
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        if (_sharedSettings.value == null) {
            _sharedSettings.value = loadSettings()
        }
    }

    val settings: StateFlow<VisualizerSettings>
        get() = _observableSettings

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

    fun loadSettings(): VisualizerSettings {
        val speed = prefs.getFloat(KEY_SPEED, 0.50f)
        val barColor = getStoredColor(KEY_BAR_COLOR, Color(0xFF00E5FF))
        val peakColor = getStoredColor(KEY_PEAK_COLOR, Color(0xFFFF4081))
        val trackAlpha = prefs.getFloat(KEY_TRACK_ALPHA, 0.05f)
        val isAlwaysOn = prefs.getBoolean(KEY_ALWAYS_ON, true)
        val isFloating = prefs.getBoolean(KEY_FLOATING_ENABLED, false)
        val floatingHeight = prefs.getInt(KEY_FLOATING_HEIGHT, 90)
        val floatingBottom = prefs.getBoolean(KEY_FLOATING_BOTTOM, true)
        val wallpaperDim = prefs.getFloat(KEY_WALLPAPER_DIM, 0.20f)
        val wallpaperHeight = prefs.getInt(KEY_WALLPAPER_HEIGHT, 110)
        val wallpaperVerticalPosition = prefs.getFloat(KEY_WALLPAPER_VERTICAL_POS, 0.82f)
        val wallpaperShowGuides = prefs.getBoolean(KEY_WALLPAPER_SHOW_GUIDES, true)

        return VisualizerSettings(
            speed = speed,
            barColor = barColor,
            peakColor = peakColor,
            trackAlpha = trackAlpha,
            isAlwaysOn = isAlwaysOn,
            isFloatingEnabled = isFloating,
            floatingHeightDp = floatingHeight,
            isFloatingAtBottom = floatingBottom,
            wallpaperDim = wallpaperDim,
            wallpaperHeightDp = wallpaperHeight,
            wallpaperVerticalPosition = wallpaperVerticalPosition,
            wallpaperShowGuides = wallpaperShowGuides,
        )
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.10f, 1.00f)
        prefs.edit().putFloat(KEY_SPEED, clamped).apply()
        updateSettings { it.copy(speed = clamped) }
        AudioVisualizerManager.setSpeed(clamped)
        AudioVisualizerWallpaperService.reloadSettings()
    }

    fun setColors(barColor: Color, peakColor: Color) {
        prefs.edit()
            .remove(KEY_BAR_COLOR)
            .remove(KEY_PEAK_COLOR)
            .putInt(KEY_BAR_COLOR, barColor.toArgb())
            .putInt(KEY_PEAK_COLOR, peakColor.toArgb())
            .apply()
        updateSettings { it.copy(barColor = barColor, peakColor = peakColor) }
        AudioVisualizerWallpaperService.reloadSettings()
    }

    fun setAlwaysOn(alwaysOn: Boolean) {
        prefs.edit().putBoolean(KEY_ALWAYS_ON, alwaysOn).apply()
        updateSettings { it.copy(isAlwaysOn = alwaysOn) }
        AudioVisualizerManager.setAlwaysOn(alwaysOn)
    }

    fun setFloatingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FLOATING_ENABLED, enabled).apply()
        updateSettings { it.copy(isFloatingEnabled = enabled) }
    }

    fun setFloatingHeight(heightDp: Int) {
        val clamped = heightDp.coerceIn(40, 240)
        prefs.edit().putInt(KEY_FLOATING_HEIGHT, clamped).apply()
        updateSettings { it.copy(floatingHeightDp = clamped) }
    }

    fun setFloatingPosition(atBottom: Boolean) {
        prefs.edit().putBoolean(KEY_FLOATING_BOTTOM, atBottom).apply()
        updateSettings { it.copy(isFloatingAtBottom = atBottom) }
    }

    fun setWallpaperDim(dim: Float) {
        val clamped = dim.coerceIn(0.00f, 0.70f)
        prefs.edit().putFloat(KEY_WALLPAPER_DIM, clamped).apply()
        updateSettings { it.copy(wallpaperDim = clamped) }
        AudioVisualizerWallpaperService.reloadSettings()
    }

    fun setWallpaperHeight(heightDp: Int) {
        val clamped = heightDp.coerceIn(40, 260)
        prefs.edit().putInt(KEY_WALLPAPER_HEIGHT, clamped).apply()
        updateSettings { it.copy(wallpaperHeightDp = clamped) }
        AudioVisualizerWallpaperService.reloadSettings()
    }

    fun setWallpaperVerticalPosition(position: Float) {
        val clamped = position.coerceIn(0.10f, 0.95f)
        prefs.edit().putFloat(KEY_WALLPAPER_VERTICAL_POS, clamped).apply()
        updateSettings { it.copy(wallpaperVerticalPosition = clamped) }
        AudioVisualizerWallpaperService.reloadSettings()
    }

    fun setWallpaperShowGuides(showGuides: Boolean) {
        prefs.edit().putBoolean(KEY_WALLPAPER_SHOW_GUIDES, showGuides).apply()
        updateSettings { it.copy(wallpaperShowGuides = showGuides) }
        AudioVisualizerWallpaperService.reloadSettings()
    }

    private inline fun updateSettings(transform: (VisualizerSettings) -> VisualizerSettings) {
        val current = _sharedSettings.value ?: loadSettings()
        val updated = transform(current)
        _sharedSettings.value = updated
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
        private const val KEY_WALLPAPER_DIM = "viz_wallpaper_dim"
        private const val KEY_WALLPAPER_HEIGHT = "viz_wallpaper_height"
        private const val KEY_WALLPAPER_VERTICAL_POS = "viz_wallpaper_vert_pos"
        private const val KEY_WALLPAPER_SHOW_GUIDES = "viz_wallpaper_show_guides"

        private val _sharedSettings = MutableStateFlow<VisualizerSettings?>(null)
        private val _fallbackDefaultSettings = VisualizerSettings()

        private val _observableSettings: StateFlow<VisualizerSettings> = MutableStateFlow(_fallbackDefaultSettings).apply {
            // Se já inicializado, assume o valor atual
            val initial = _sharedSettings.value
            if (initial != null) value = initial
        }

        init {
            // Garante reflexo contínuo das alterações no StateFlow exposto
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                _sharedSettings.collect { value ->
                    if (value != null) {
                        (_observableSettings as MutableStateFlow).value = value
                    }
                }
            }
        }

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
