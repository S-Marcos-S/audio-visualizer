# Proguard / R8 Configuration for Audio Visualizer

# Visualizer Core & Daemon Reflection
-keep class dev.viagaralauncher.visualizer.** { *; }
-keepclassmembers class dev.viagaralauncher.visualizer.AudioVisualizerDaemon {
    public static void main(java.lang.String[]);
}

# Android Framework and Audio Effects
-keep class android.media.audiofx.Visualizer { *; }
-keep class android.net.LocalSocket { *; }
-keep class android.net.LocalSocketAddress { *; }
-keep class android.net.LocalServerSocket { *; }

# Compose Rules
-keepattributes *Annotation*
-dontwarn androidx.compose.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# DataStore
-keepclassmembers class * extends androidx.datastore.preferences.core.Preferences { *; }
