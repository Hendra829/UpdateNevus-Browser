package com.nevus.mediabridge.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.nevus.mediabridge.util.NevusLog
import java.io.FileOutputStream

/**
 * Turns a static image into a short animated GIF via a Ken Burns pan/zoom — real camera-move
 * animation on the same pixels, not AI-generated motion or new content. Renders each frame from
 * a slowly zooming/panning crop of the source and hands them to [GifEncoder].
 */
object KenBurnsAnimator {

    fun generate(inputPath: String, outputPath: String, durationMs: Int = 3000, fps: Int = 12): Boolean {
        val source = BitmapFactory.decodeFile(inputPath) ?: run {
            NevusLog.w(TAG, "Could not decode $inputPath")
            return false
        }
        return try {
            val scale = (MAX_DIMENSION.toFloat() / maxOf(source.width, source.height)).coerceAtMost(1f)
            val frameW = (source.width * scale).toInt().coerceAtLeast(2)
            val frameH = (source.height * scale).toInt().coerceAtLeast(2)
            val frameCount = (durationMs * fps / 1000).coerceAtLeast(2)
            val delayMs = durationMs / frameCount

            FileOutputStream(outputPath).use { fos ->
                val encoder = GifEncoder(fos, frameW, frameH)
                encoder.start()
                for (i in 0 until frameCount) {
                    val t = i.toFloat() / (frameCount - 1)
                    val frame = renderFrame(source, frameW, frameH, t)
                    encoder.addFrame(frame, delayMs)
                    frame.recycle()
                }
                encoder.finish()
            }
            true
        } catch (t: Throwable) {
            NevusLog.w(TAG, "Ken Burns animation failed for $inputPath", t)
            false
        } finally {
            source.recycle()
        }
    }

    /** Slow zoom-in while panning toward the bottom-right corner — a standard Ken Burns move. */
    private fun renderFrame(source: Bitmap, outW: Int, outH: Int, t: Float): Bitmap {
        val zoom = 1f + ZOOM_RANGE * t
        val srcW = source.width / zoom
        val srcH = source.height / zoom
        val srcX = (source.width - srcW) * t
        val srcY = (source.height - srcH) * t

        val srcRect = Rect(srcX.toInt(), srcY.toInt(), (srcX + srcW).toInt(), (srcY + srcH).toInt())
        val dstRect = RectF(0f, 0f, outW.toFloat(), outH.toFloat())
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        Canvas(out).drawBitmap(source, srcRect, dstRect, paint)
        return out
    }

    private const val TAG = "KenBurnsAnimator"
    private const val ZOOM_RANGE = 0.15f
    private const val MAX_DIMENSION = 480
}
