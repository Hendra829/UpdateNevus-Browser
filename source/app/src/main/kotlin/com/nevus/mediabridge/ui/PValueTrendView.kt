package com.nevus.mediabridge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.nevus.mediabridge.R
import com.nevus.mediabridge.audit.LiveStatisticalValidator

/**
 * Plots the last N [LiveStatisticalValidator.TestReport] p-values as three simple trend lines —
 * a real research/audit aid (are recent evaluations trending toward the alarm threshold?), not a
 * prediction tool. A dashed reference line marks the alarm p-value so a viewer can see how close
 * the stream is running to that threshold.
 */
class PValueTrendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var reports: List<LiveStatisticalValidator.TestReport> = emptyList()
    private var alarmThreshold: Double = 0.01

    private val monobitPaint = linePaint(R.color.nevus_kind_video)
    private val runsPaint = linePaint(R.color.nevus_kind_audio)
    private val chiPaint = linePaint(R.color.nevus_kind_music)
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.nevus_ink)
        alpha = 90
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private fun linePaint(colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, colorRes)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    fun setData(reports: List<LiveStatisticalValidator.TestReport>, alarmThreshold: Double) {
        this.reports = reports
        this.alarmThreshold = alarmThreshold
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val thresholdY = h - (alarmThreshold.toFloat() * h)
        canvas.drawLine(0f, thresholdY, w, thresholdY, thresholdPaint)

        if (reports.size < 2) return
        drawLine(canvas, w, h, monobitPaint) { it.monobitP }
        drawLine(canvas, w, h, runsPaint) { it.runsP }
        drawLine(canvas, w, h, chiPaint) { it.chiSquareP }
    }

    private fun drawLine(
        canvas: Canvas,
        w: Float,
        h: Float,
        paint: Paint,
        value: (LiveStatisticalValidator.TestReport) -> Double,
    ) {
        val n = reports.size
        val stepX = w / (n - 1)
        var prevX = 0f
        var prevY = h - (value(reports[0]).toFloat().coerceIn(0f, 1f) * h)
        for (i in 1 until n) {
            val x = i * stepX
            val y = h - (value(reports[i]).toFloat().coerceIn(0f, 1f) * h)
            canvas.drawLine(prevX, prevY, x, y, paint)
            prevX = x
            prevY = y
        }
    }
}
