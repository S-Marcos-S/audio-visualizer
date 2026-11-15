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
    private const val SYSTEM_WALLPAPER_FILENAME = "system_wallpaper.jpg"

    fun getCustomWallpaperFile(context: Context): File {
        return File(context.filesDir, CUSTOM_WALLPAPER_FILENAME)
    }

    fun getSystemWallpaperFile(context: Context): File {
        return File(context.filesDir, SYSTEM_WALLPAPER_FILENAME)
    }

    fun hasCustomWallpaper(context: Context): Boolean {
        val file = getCustomWallpaperFile(context)
        return file.exists() && file.length() > 0
    }

    fun hasSystemWallpaper(context: Context): Boolean {
        val file = getSystemWallpaperFile(context)
        return file.exists() && file.length() > 0
    }

    fun removeCustomWallpaper(context: Context): Boolean {
        val file = getCustomWallpaperFile(context)
        val deleted = if (file.exists()) file.delete() else true
        AudioVisualizerWallpaperService.reloadWallpaper()
        return deleted
    }

    /**
     * Extrai o papel de parede do sistema diretamente dos diretórios internos do Android
     * utilizando privilégios de Root (su). Funciona em qualquer versão do Android (incluindo Android 14 e 15),
     * contornando as restrições da API WallpaperManager.
     */
    fun extractSystemWallpaperWithRoot(context: Context): Boolean {
        val destFile = getSystemWallpaperFile(context)
        val tempFile = File(context.filesDir, "temp_sys_wallpaper")

        try {
            val script = """
                for p in /data/system/users/0/wallpaper_orig /data/system/users/0/wallpaper /data/system/users/*/wallpaper_orig /data/system/users/*/wallpaper /data/system/wallpaper_orig /data/system/wallpaper /data/system/users/0/wallpaper_file; do
                    if [ -f "${'$'}p" ] && [ -s "${'$'}p" ]; then
                        cp "${'$'}p" "${tempFile.absolutePath}" && chmod 666 "${tempFile.absolutePath}" && exit 0
                    fi
                done
                exit 1
            """.trimIndent()

            val process = ProcessBuilder("su", "-c", script).start()
            val exitCode = process.waitFor()

            if (exitCode == 0 && tempFile.exists() && tempFile.length() > 0) {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = BitmapFactory.decodeFile(tempFile.absolutePath, opts)
                if (bmp != null) {
                    FileOutputStream(destFile).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    bmp.recycle()
                    tempFile.delete()
                    Log.i(TAG, "Papel de parede do sistema capturado com sucesso via Root! Tamanho: ${destFile.length()} bytes")
                    AudioVisualizerWallpaperService.reloadWallpaper()
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao extrair wallpaper com root: ${e.message}", e)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
        return false
    }

    /**
     * Garante que o wallpaper do usuário esteja disponível localmente em disco.
     * Tenta primeiro a extração via Root; se falhar, tenta fallback via WallpaperManager.
     */
    fun ensureWallpaperCaptured(context: Context): Boolean {
        if (hasCustomWallpaper(context) || hasSystemWallpaper(context)) {
            return true
        }

        if (extractSystemWallpaperWithRoot(context)) {
            return true
        }

        return captureSystemWallpaperFallback(context)
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

    private fun captureSystemWallpaperFallback(context: Context): Boolean {
        return try {
            val wm = WallpaperManager.getInstance(context)
            val drawable: Drawable? = try {
                wm.drawable
            } catch (_: Throwable) {
                try {
                    wm.peekDrawable()
                } catch (_: Throwable) {
                    null
                }
            }

            if (drawable == null) {
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

            val destFile = getSystemWallpaperFile(context)
            FileOutputStream(destFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
            }

            AudioVisualizerWallpaperService.reloadWallpaper()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro no fallback do WallpaperManager: ${e.message}")
            false
        }
    }

    fun loadWallpaperBitmap(context: Context, targetWidth: Int, targetHeight: Int): Bitmap? {
        // 1. Foto customizada da galeria escolhida pelo usuário
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

        // 2. Wallpaper do sistema capturado via Root ou salvo anteriormente
        val systemFile = getSystemWallpaperFile(context)
        if (systemFile.exists() && systemFile.length() > 0) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = BitmapFactory.decodeFile(systemFile.absolutePath, opts)
                if (bmp != null) return bmp
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao decodificar wallpaper do sistema armazenado: ${e.message}")
            }
        }

        // 3. Tenta extrair agora via Root caso ainda não tenha sido extraído
        if (extractSystemWallpaperWithRoot(context)) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bmp = BitmapFactory.decodeFile(systemFile.absolutePath, opts)
                if (bmp != null) return bmp
            } catch (_: Exception) {}
        }

        // 4. Fallback no WallpaperManager (Android <= 13)
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
