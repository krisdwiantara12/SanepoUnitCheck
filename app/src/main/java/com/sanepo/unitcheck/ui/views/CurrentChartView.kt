package com.sanepo.unitcheck.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.abs
import kotlin.math.max

class CurrentChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var samples: List<Int> = emptyList()

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val bgPaint = Paint().apply { color = Color.parseColor("#0A0C0E") }
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#323A45")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val zeroLinePaint = Paint().apply {
        color = Color.parseColor("#5A6778")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ADE80")
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val dischargeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8C38")
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#96A3B3")
        textSize = sp(11f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    fun setSamples(values: List<Int>) {
        samples = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        canvas.drawRect(1f, 1f, w - 1f, h - 1f, borderPaint)

        val padX = dp(12f)
        val topPad = dp(22f)
        val botPad = dp(20f)
        val midY = topPad + (h - topPad - botPad) / 2f
        canvas.drawLine(padX, midY, w - padX, midY, zeroLinePaint)

        if (samples.isEmpty()) {
            canvas.drawText("Collecting live current samples...", padX, midY - dp(6f), labelPaint)
            return
        }

        val maxAbs = max(500, samples.maxOf { abs(it) })
        canvas.drawText("+${maxAbs}mA", padX, dp(14f), labelPaint)
        canvas.drawText("0mA", padX, midY - dp(4f), labelPaint)
        canvas.drawText("-${maxAbs}mA", padX, h - dp(5f), labelPaint)

        val plotW = (w - padX * 2).coerceAtLeast(1f)
        val plotHalfH = ((h - topPad - botPad) / 2f).coerceAtLeast(1f)
        val path = Path()
        val count = samples.size
        for (i in 0 until count) {
            val x = if (count == 1) padX else padX + (i.toFloat() / (count - 1)) * plotW
            val ratio = (samples[i].toFloat() / maxAbs).coerceIn(-1f, 1f)
            val y = midY - ratio * plotHalfH
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val last = samples.last()
        canvas.drawPath(path, if (last >= 0) linePaint else dischargeLinePaint)
    }
}
