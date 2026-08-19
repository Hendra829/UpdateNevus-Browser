package com.nevus.mediabridge.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.nevus.mediabridge.crypto.CSPRNGProvider

/**
 * The classic RNG "visual test": render a fresh sample as one pixel per bit, black/white. A
 * sound CSPRNG looks like uniform static; visible stripes, grids, or blocks indicate bias. This
 * is a bias-detection aid for the audit screen — it says nothing about, and cannot be used to
 * predict, any individual future output.
 */
class RandomBitmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
    private val srcRect = Rect(0, 0, GRID_SIZE, GRID_SIZE)
    private val dstRect = Rect()

    /** Draw a brand-new sample from [CSPRNGProvider] — call to refresh the visual. */
    fun refresh() {
        val bytes = CSPRNGProvider.nextBytes(GRID_SIZE * GRID_SIZE / 8)
        val bmp = Bitmap.createBitmap(GRID_SIZE, GRID_SIZE, Bitmap.Config.ARGB_8888)
        var bitIndex = 0
        for (y in 0 until GRID_SIZE) {
            for (x in 0 until GRID_SIZE) {
                val byteIdx = bitIndex / 8
                val bit = (bytes[byteIdx].toInt() ushr (bitIndex % 8)) and 1
                bmp.setPixel(x, y, if (bit == 1) BLACK else WHITE)
                bitIndex++
            }
        }
        bitmap = bmp
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        dstRect.set(0, 0, width, height)
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
    }

    private companion object {
        const val GRID_SIZE = 64
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
