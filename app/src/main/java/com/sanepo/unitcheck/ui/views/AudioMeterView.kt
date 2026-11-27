package com.sanepo.unitcheck.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class AudioMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var levelFraction = 0f
    private var peakFraction = 0f

    private val bgPaint = Paint().apply { color = Color.parseColor("#0A0C0E") }
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#323A45")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val offSegmentPaint = Paint().apply { color = Color.parseColor("#1C2229") }
    private val greenPaint = Paint().apply { color = Color.parseColor("#22C55E") }
    private val amberPaint = Paint().apply { color = Color.parseColor("#FF6B00") }
    private val redPaint = Paint().apply { color = Color.parseColor("#F87171") }

    fun setLevel(fraction: Float) {
        levelFraction = fraction.coerceIn(0f, 1f)
        if (levelFraction > peakFraction) peakFraction = levelFraction
        invalidate()
    }

    fun resetPeak() {
        peakFraction = 0f
        levelFraction = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        canvas.drawRect(1f, 1f, w - 1f, h - 1f, borderPaint)

        val segments = 24
        val pad = 10f
        val gap = 4f
        val usableW = (w - pad * 2 - gap * (segments - 1)).coerceAtLeast(1f)
        val segW = usableW / segments
        val litCount = (levelFraction * segments).toInt()
        val peakIdx = (peakFraction * segments).toInt().coerceIn(0, segments - 1)

        for (i in 0 until segments) {
            val left = pad + i * (segW + gap)
            val right = left + segW
            val paint = when {
                i < litCount || (peakFraction > 0.02f && i == peakIdx) -> {
                    when {
                        i >= 20 -> redPaint
                        i >= 14 -> amberPaint
                        else -> greenPaint
                    }
                }
                else -> offSegmentPaint
            }
            canvas.drawRect(left, pad, right, h - pad, paint)
        }
    }
}
