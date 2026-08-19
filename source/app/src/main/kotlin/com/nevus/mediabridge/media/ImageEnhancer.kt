package com.nevus.mediabridge.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.nevus.mediabridge.util.NevusLog
import java.io.File
import java.io.FileOutputStream

/**
 * Real, bounded image processing — a contrast boost via a standard [ColorMatrix] and a 3x3
 * unsharp-style convolution kernel. This is the image counterpart to [MediaEnhancer]'s video
 * path (media3 Transformer doesn't handle single images). Same honesty rule applies: this
 * sharpens/boosts contrast in the existing pixels, it does not invent detail that wasn't there.
 */
object ImageEnhancer {

    fun enhance(inputPath: String, outputPath: String, contrastBoost: Float = 0.25f, sharpen: Boolean = true): Boolean {
        val source = BitmapFactory.decodeFile(inputPath) ?: run {
            NevusLog.w(TAG, "Could not decode $inputPath")
            return false
        }
        return try {
            val contrasted = applyContrast(source, contrastBoost)
            val result = if (sharpen) applySharpen(contrasted) else contrasted
            encode(result, outputPath)
            true
        } catch (t: Throwable) {
            NevusLog.w(TAG, "Enhance failed for $inputPath", t)
            false
        } finally {
            source.recycle()
        }
    }

    private fun applyContrast(source: Bitmap, boost: Float): Bitmap {
        // Standard contrast-around-midpoint matrix: scale channels by `scale`, then re-center.
        val scale = 1f + boost
        val translate = (1f - scale) * 128f
        val matrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) })
        return out
    }

    /** 3x3 unsharp kernel `[[0,-1,0],[-1,5,-1],[0,-1,0]]` — a real, standard sharpen filter. */
    private fun applySharpen(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                    out[y * w + x] = pixels[y * w + x]
                    continue
                }
                var r = 0; var g = 0; var b = 0
                for ((dy, dx, weight) in KERNEL) {
                    val p = pixels[(y + dy) * w + (x + dx)]
                    r += ((p shr 16) and 0xFF) * weight
                    g += ((p shr 8) and 0xFF) * weight
                    b += (p and 0xFF) * weight
                }
                val a = (pixels[y * w + x] shr 24) and 0xFF
                out[y * w + x] = (a shl 24) or
                    (r.coerceIn(0, 255) shl 16) or
                    (g.coerceIn(0, 255) shl 8) or
                    b.coerceIn(0, 255)
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        source.recycle()
        return result
    }

    private fun encode(bitmap: Bitmap, outputPath: String) {
        val hasAlpha = bitmap.hasAlpha()
        FileOutputStream(File(outputPath)).use { out ->
            val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.WEBP_LOSSY
            bitmap.compress(format, 92, out)
        }
        bitmap.recycle()
    }

    private const val TAG = "ImageEnhancer"

    // (dy, dx, weight) offsets for the sharpen kernel above.
    private val KERNEL = listOf(
        Triple(-1, 0, -1), Triple(0, -1, -1), Triple(0, 0, 5), Triple(0, 1, -1), Triple(1, 0, -1),
    )
}
