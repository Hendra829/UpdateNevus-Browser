package com.nevus.mediabridge.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.nevus.mediabridge.util.NevusLog
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Two honestly-different ways to turn a downloaded image into a sticker — the user picks, both
 * in [com.nevus.mediabridge.ui.DownloadOptionsDialog] up front and later from history via
 * [com.nevus.mediabridge.ui.StickerChoiceDialog]:
 *  - [simpleBorder]: decorative white rounded border, background untouched. No new dependency.
 *  - [removeBackground]: real subject/background separation via ML Kit Subject Segmentation
 *    (on-device, downloaded through Play services) — an actual cutout, not a border trick.
 *
 * Both encode lossless WebP to preserve transparency.
 */
object StickerMaker {

    fun simpleBorder(inputPath: String, outputPath: String): Boolean {
        val source = BitmapFactory.decodeFile(inputPath) ?: run {
            NevusLog.w(TAG, "Could not decode $inputPath")
            return false
        }
        return try {
            val border = (minOf(source.width, source.height) * BORDER_FRACTION).toInt().coerceAtLeast(MIN_BORDER_PX)
            val outW = source.width + border * 2
            val outH = source.height + border * 2
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)

            val cornerRadius = border.toFloat()
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawRoundRect(RectF(0f, 0f, outW.toFloat(), outH.toFloat()), cornerRadius, cornerRadius, bgPaint)
            canvas.drawBitmap(source, border.toFloat(), border.toFloat(), null)

            encodeWebpLossless(out, outputPath)
            true
        } catch (t: Throwable) {
            NevusLog.w(TAG, "simpleBorder failed for $inputPath", t)
            false
        } finally {
            source.recycle()
        }
    }

    suspend fun removeBackground(inputPath: String, outputPath: String): Boolean {
        val source = BitmapFactory.decodeFile(inputPath) ?: run {
            NevusLog.w(TAG, "Could not decode $inputPath")
            return false
        }
        val segmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        )
        return try {
            val image = InputImage.fromBitmap(source, 0)
            val result = suspendCancellableCoroutine { cont ->
                segmenter.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { t -> cont.resumeWithException(t) }
            }
            val foreground = result.foregroundBitmap ?: run {
                NevusLog.w(TAG, "Segmentation returned no foreground bitmap for $inputPath")
                return false
            }
            encodeWebpLossless(foreground, outputPath)
            true
        } catch (t: Throwable) {
            NevusLog.w(TAG, "removeBackground failed for $inputPath", t)
            false
        } finally {
            segmenter.close()
            source.recycle()
        }
    }

    private fun encodeWebpLossless(bitmap: Bitmap, outputPath: String) {
        FileOutputStream(File(outputPath)).use { out ->
            @Suppress("DEPRECATION") // WEBP_LOSSLESS is the modern replacement; old WEBP const still resolves pre-30.
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
        }
        bitmap.recycle()
    }

    private const val TAG = "StickerMaker"
    private const val BORDER_FRACTION = 0.06f
    private const val MIN_BORDER_PX = 12
}
