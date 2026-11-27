package com.sanepo.unitcheck.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min

class SpiritLevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var tiltX = 0f
    private var tiltY = 0f

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val bgPaint = Paint().apply { color = Color.parseColor("#0A0C0E") }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5A6778")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val crossPaint = Paint().apply {
        color = Color.parseColor("#323A45")
        strokeWidth = dp(1f)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6B00")
        style = Paint.Style.FILL
    }
    private val levelBubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ADE80")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F4F6F8")
        textSize = sp(11f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    fun updateAcceleration(ax: Float, ay: Float) {
        tiltX = ax
        tiltY = ay
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val cx = w / 2f
        val cy = h / 2f
        val maxR = (min(w, h) * 0.4f).coerceAtLeast(dp(16f))

        canvas.drawLine(cx - maxR, cy, cx + maxR, cy, crossPaint)
        canvas.drawLine(cx, cy - maxR, cx, cy + maxR, crossPaint)
        canvas.drawCircle(cx, cy, maxR, ringPaint)
        canvas.drawCircle(cx, cy, maxR * 0.35f, ringPaint)

        val normX = (-tiltX / 9.81f).coerceIn(-1f, 1f)
        val normY = (tiltY / 9.81f).coerceIn(-1f, 1f)
        val bx = cx + normX * (maxR * 0.85f)
        val by = cy + normY * (maxR * 0.85f)
        val isFlat = hypot(tiltX.toDouble(), tiltY.toDouble()) < 0.7

        canvas.drawCircle(bx, by, dp(8f), if (isFlat) levelBubblePaint else bubblePaint)
        val readout = String.format(Locale.US, "X:%.2f Y:%.2f m/s2", tiltX, tiltY)
        canvas.drawText(readout, dp(8f), h - dp(7f), textPaint)
    }
}
