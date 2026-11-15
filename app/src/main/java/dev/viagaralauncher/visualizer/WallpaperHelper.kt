// SPDX-License-Identifier: Apache-2.0
package dev.viagaralauncher.visualizer

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object WallpaperHelper {

    private const val TAG = "WallpaperHelper"
    private const val CUSTOM_WALLPAPER_FILENAME = "custom_wallpaper.jpg"

    fun getCustomWallpaperFile(context: Context): File {
        return File(context.filesDir, CUSTOM_WALLPAPER_FILENAME)
    }

    fun hasCustomWallpaper(context: Context): Boolean {
        val file = getCustomWallpaperFile(context)
        return file.exists() && file.length() > 0
    }

    fun removeCustomWallpaper(context: Context): Boolean {
        val file = getCustomWallpaperFile(context)
        val deleted = if (file.exists()) file.delete() else true
        AudioVisualizerWallpaperService.reloadWallpaper()
        return deleted
    }

    fun saveCustomWallpaper(context: Context, imageUri: Uri): Boolean {
        return try {
            val resolver = context.contentResolver
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            resolver.openInputStream(imageUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, boundsOptions)
            }

            val maxDimension = 3840
            var sampleSize = 1
            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth > maxDimension || origHeight > maxDimension) {
                val halfWidth = origWidth / 2
                val halfHeight = origHeight / 2
                while ((halfWidth / sampleSize) >= maxDimension || (halfHeight / sampleSize) >= maxDimension) {
                    sampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val bitmap = resolver.openInputStream(imageUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            } ?: return false

            val destFile = getCustomWallpaperFile(context)
            FileOutputStream(destFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
            }
            bitmap.recycle()

            AudioVisualizerWallpaperService.reloadWallpaper()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar papel de parede customizado: ${e.message}", e)
            false
        }
    }

    fun captureSystemWallpaper(context: Context): Boolean {
        return try {
            val wm = WallpaperManager.getInstance(context)
            val drawable: Drawable? = try {
                wm.drawable
            } catch (_: SecurityException) {
                try {
                    wm.peekDrawable()
                } catch (_: Throwable) {
                    null
                }
            } catch (_: Throwable) {
                null
            }

            if (drawable == null) {
                Log.w(TAG, "Não foi possível extrair o drawable do WallpaperManager")
                return false
            }

            val bitmap: Bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap
            } else {
                val width = max(drawable.intrinsicWidth, 1080)
                val height = max(drawable.intrinsicHeight, 1920)
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }

            val destFile = getCustomWallpaperFile(context)
            FileOutputStream(destFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
            }

            AudioVisualizerWallpaperService.reloadWallpaper()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao capturar papel de parede do sistema: ${e.message}", e)
            false
        }
    }

    fun loadWallpaperBitmap(context: Context, targetWidth: Int, targetHeight: Int): Bitmap? {
        val customFile = getCustomWallpaperFile(context)
        if (customFile.exists() && customFile.length() > 0) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = BitmapFactory.decodeFile(customFile.absolutePath, opts)
                if (bmp != null) return bmp
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao decodificar wallpaper customizado: ${e.message}")
            }
        }

        // Tenta fallback com o WallpaperManager
        return try {
            val wm = WallpaperManager.getInstance(context)
            val drawable = try {
                wm.drawable
            } catch (_: Throwable) {
                null
            }

            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap
            } else if (drawable != null) {
                val w = max(drawable.intrinsicWidth, if (targetWidth > 0) targetWidth else 1080)
                val h = max(drawable.intrinsicHeight, if (targetHeight > 0) targetHeight else 1920)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao carregar fallback do WallpaperManager: ${e.message}")
            null
        }
    }

    fun openLiveWallpaperChooser(context: Context) {
        val component = ComponentName(context, AudioVisualizerWallpaperService::class.java)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Não foi possível abrir o seletor de Live Wallpaper: ${e.message}")
            }
        }
    }
}
