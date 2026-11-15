// SPDX-License-Identifier: Apache-2.0
package dev.viagaralauncher.visualizer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

class FloatingVisualizerService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val viewModelStoreInstance = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = viewModelStoreInstance

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        startAsForegroundService()
        initFloatingOverlay()
    }

    private fun startAsForegroundService() {
        val channelId = "floating_visualizer_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Visualizador Flutuante",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Execução do visualizador em sobreposição na tela"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Audio Visualizer Ativo")
            .setContentText("Renderizando espectro de áudio flutuante em 60 FPS")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1001, notification)
        }
    }

    private fun initFloatingOverlay() {
        val prefs = VisualizerPreferences(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val settings = prefs.settings.value

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (settings.floatingHeightDp * resources.displayMetrics.density).toInt(),
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (settings.isFloatingAtBottom) Gravity.BOTTOM else Gravity.TOP
            x = 0
            y = 0
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingVisualizerService)
            setViewTreeSavedStateRegistryOwner(this@FloatingVisualizerService)
            setViewTreeViewModelStoreOwner(this@FloatingVisualizerService)

            setContent {
                val currentSettings by prefs.settings.collectAsState()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(currentSettings.floatingHeightDp.dp)
                        .background(Color.Transparent)
                ) {
                    AudioVisualizerView(
                        modifier = Modifier.fillMaxWidth(),
                        barColor = currentSettings.barColor,
                        peakColor = currentSettings.peakColor,
                        trackColor = currentSettings.barColor.copy(alpha = currentSettings.trackAlpha)
                    )
                }
            }
        }

        composeView = view

        try {
            windowManager?.addView(view, params)
        } catch (_: Throwable) {}

        AudioVisualizerManager.onResume(this)
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        composeView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Throwable) {}
        }
        composeView = null
        viewModelStoreInstance.clear()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, FloatingVisualizerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingVisualizerService::class.java)
            context.stopService(intent)
        }
    }
}
