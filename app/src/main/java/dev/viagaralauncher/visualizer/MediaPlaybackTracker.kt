// SPDX-License-Identifier: Apache-2.0
package dev.viagaralauncher.visualizer

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MediaPlaybackTracker(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var pollingJob: Job? = null

    fun startTracking() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                val isPlaying = audioManager.isMusicActive
                AudioVisualizerManager.updatePlaybackState(
                    context = context,
                    enabled = true,
                    isPlaying = isPlaying,
                    hasSession = true
                )
                delay(1000L)
            }
        }
    }

    fun stopTracking() {
        pollingJob?.cancel()
        pollingJob = null
    }
}
