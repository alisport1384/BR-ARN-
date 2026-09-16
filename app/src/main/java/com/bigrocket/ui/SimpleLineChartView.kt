package com.bigrocket.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Minimal line chart for a short, bounded history of numeric samples (throughput in Mbps).
 *
 * Deliberately simple: no external charting library, no per-frame animation, no allocation in
 * onDraw beyond a couple of local Paths reused from fields. The view only repaints when
 * [setData] is called with new points (driven by the ~4s bonding update cycle), so it costs
 * essentially nothing while otherwise idle.
 */
class SimpleLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var points: List<Float> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#32E0C4")
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3332E0C4")
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        // Was #33000000 (semi-transparent black) - invisible against the card's dark
        // navy background; a light, low-opacity line is what actually shows up here.
        color = Color.parseColor("#1FE8EEF7")
    }

    private val linePath = Path()
    private val fillPath = Path()

    fun setData(newPoints: List<Float>) {
        points = newPoints
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Simple horizontal baseline grid (3 lines) for a bit of visual reference, cheap to draw.
        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        if (points.size < 2) return

        val maxValue = (points.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val stepX = w / (points.size - 1)

        linePath.reset()
        fillPath.reset()

        points.forEachIndexed { index, value ->
            val x = index * stepX
            val y = h - (value / maxValue) * h
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo((points.size - 1) * stepX, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
    }
}
