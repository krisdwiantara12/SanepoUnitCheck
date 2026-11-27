package com.sanepo.unitcheck.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class TouchDiagnosticView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode {
        GRID, MULTITOUCH, GHOST
    }

    interface Listener {
        fun onGridProgress(hitCells: Int, totalCells: Int)
        fun onMultiTouchChanged(activeCount: Int, maxSimultaneous: Int)
        fun onGhostTouchHit(x: Float, y: Float)
        fun onSamplingRateUpdated(medianHz: Float, peakHz: Float, sampleCount: Int)
    }

    private data class PointerPoint(
        val id: Int,
        val x: Float,
        val y: Float,
        val pressure: Float,
        val sizeMajor: Float
    )

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    var listener: Listener? = null
    var mode: Mode = Mode.GRID
        set(value) {
            field = value
            activePointers.clear()
            lastPointerPos.clear()
            invalidate()
        }

    var ghostMonitoringActive: Boolean = false

    private val cols = 11
    private val rows = 20
    private val touchedGrid = BooleanArray(cols * rows)
    private var hitCount = 0
    private var maxPointersObserved = 0
    private val activePointers = ArrayList<PointerPoint>()
    private val lastPointerPos = HashMap<Int, Pair<Float, Float>>()
    private val ghostHits = ArrayList<Pair<Float, Float>>()

    // Sampling rate estimator using hardware historical MotionEvent timestamps (captures true 120Hz/240Hz/360Hz digitizer rate).
    private var lastSampleTimestampMs: Long = -1L
    private val rateWindowHz = ArrayDeque<Float>(60)
    private var medianSamplingHz = 0f
    private var peakSamplingHz = 0f
    private var validSamplingIntervals = 0

    private val bgPaint = Paint().apply { color = Color.parseColor("#0A0C0E") }
    private val cellEmptyPaint = Paint().apply {
        color = Color.parseColor("#14181D")
        style = Paint.Style.FILL
    }
    private val cellHitPaint = Paint().apply {
        color = Color.parseColor("#166534")
        style = Paint.Style.FILL
    }
    private val gridBorderPaint = Paint().apply {
        color = Color.parseColor("#323A45")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6B00")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val pointerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ADE80")
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F87171")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F4F6F8")
        textSize = sp(12f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && w > 0 && h > 0) {
            val edgeW = dp(36f).roundToInt()
            val midTop = (h * 0.25f).roundToInt()
            val midBot = (h * 0.75f).roundToInt()
            systemGestureExclusionRects = listOf(
                Rect(0, midTop, edgeW, midBot),
                Rect(w - edgeW, midTop, w, midBot)
            )
        }
    }

    fun resetState() {
        touchedGrid.fill(false)
        hitCount = 0
        maxPointersObserved = 0
        activePointers.clear()
        lastPointerPos.clear()
        ghostHits.clear()
        lastSampleTimestampMs = -1L
        rateWindowHz.clear()
        medianSamplingHz = 0f
        peakSamplingHz = 0f
        validSamplingIntervals = 0
        listener?.onGridProgress(0, touchedGrid.size)
        listener?.onMultiTouchChanged(0, 0)
        listener?.onSamplingRateUpdated(0f, 0f, 0)
        invalidate()
    }

    fun getGridCoverage(): Pair<Int, Int> = Pair(hitCount, touchedGrid.size)
    fun getMaxPointers(): Int = maxPointersObserved
    fun getGhostHitCount(): Int = ghostHits.size
    fun getSamplingStats(): Triple<Float, Float, Int> = Triple(medianSamplingHz, peakSamplingHz, validSamplingIntervals)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        when (mode) {
            Mode.GRID -> drawGrid(canvas)
            Mode.MULTITOUCH -> drawMultiTouch(canvas)
            Mode.GHOST -> drawGhostMonitor(canvas)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val cellW = width.toFloat() / cols
        val cellH = height.toFloat() / rows
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = c * cellW
                val top = r * cellH
                val right = left + cellW
                val bottom = top + cellH
                val hit = touchedGrid[r * cols + c]
                canvas.drawRect(left, top, right, bottom, if (hit) cellHitPaint else cellEmptyPaint)
                canvas.drawRect(left, top, right, bottom, gridBorderPaint)
            }
        }
    }

    private fun drawMultiTouch(canvas: Canvas) {
        val minRad = dp(24f)
        for (p in activePointers) {
            canvas.drawLine(0f, p.y, width.toFloat(), p.y, crosshairPaint)
            canvas.drawLine(p.x, 0f, p.x, height.toFloat(), crosshairPaint)
            val radius = max(minRad, p.sizeMajor * 0.5f)
            canvas.drawCircle(p.x, p.y, radius, pointerCirclePaint)
            val label = "#${p.id} (${p.x.toInt()},${p.y.toInt()}) P:${String.format(Locale.US, "%.2f", p.pressure)}"
            val ty = (p.y - radius - dp(8f)).coerceIn(dp(24f), height - dp(12f))
            val tx = (p.x - dp(60f)).coerceIn(dp(8f), max(dp(8f), width - dp(180f)))
            canvas.drawText(label, tx, ty, textPaint)
        }
    }

    private fun drawGhostMonitor(canvas: Canvas) {
        val r = dp(10f)
        for ((gx, gy) in ghostHits) {
            canvas.drawCircle(gx, gy, r, ghostPaint)
            canvas.drawText("GHOST (${gx.toInt()},${gy.toInt()})", (gx + dp(14f)).coerceAtMost(width - dp(130f)), gy, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (mode) {
            Mode.GRID -> {
                trackSamplingRate(event)
                handleGridTouch(event)
            }
            Mode.MULTITOUCH -> {
                trackSamplingRate(event)
                handleMultiTouch(event)
            }
            Mode.GHOST -> handleGhostTouch(event)
        }
        return true
    }

    private fun trackSamplingRate(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastSampleTimestampMs = event.eventTime
                return
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until event.historySize) {
                    recordDeltaMs(event.getHistoricalEventTime(h))
                }
                recordDeltaMs(event.eventTime)
                listener?.onSamplingRateUpdated(medianSamplingHz, peakSamplingHz, validSamplingIntervals)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastSampleTimestampMs = -1L
            }
        }
    }

    private fun recordDeltaMs(tsMs: Long) {
        val prev = lastSampleTimestampMs
        lastSampleTimestampMs = tsMs
        if (prev <= 0L || tsMs <= prev) return
        val dt = tsMs - prev
        if (dt in 2L..45L) {
            val hz = (1000f / dt.toFloat()).coerceIn(22f, 480f)
            if (rateWindowHz.size >= 45) rateWindowHz.removeFirst()
            rateWindowHz.addLast(hz)
            validSamplingIntervals++
            val sorted = rateWindowHz.sorted()
            medianSamplingHz = sorted[sorted.size / 2]
            if (hz > peakSamplingHz) peakSamplingHz = hz
        }
    }

    private fun handleGridTouch(event: MotionEvent) {
        if (width <= 0 || height <= 0) return
        val action = event.actionMasked
        val cellMin = min(width.toFloat() / cols, height.toFloat() / rows).coerceAtLeast(8f)

        val liveCount = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            0
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            (event.pointerCount - 1).coerceAtLeast(0)
        } else {
            event.pointerCount
        }
        if (liveCount > maxPointersObserved) {
            maxPointersObserved = liveCount
            listener?.onMultiTouchChanged(liveCount, maxPointersObserved)
        }

        if (action == MotionEvent.ACTION_MOVE && event.historySize > 0) {
            for (h in 0 until event.historySize) {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val hx = event.getHistoricalX(i, h).coerceIn(0f, (width - 1).toFloat())
                    val hy = event.getHistoricalY(i, h).coerceIn(0f, (height - 1).toFloat())
                    interpolateTo(pid, hx, hy, cellMin)
                }
            }
        }

        for (i in 0 until event.pointerCount) {
            val pid = event.getPointerId(i)
            val x = event.getX(i).coerceIn(0f, (width - 1).toFloat())
            val y = event.getY(i).coerceIn(0f, (height - 1).toFloat())

            if (action == MotionEvent.ACTION_MOVE) {
                interpolateTo(pid, x, y, cellMin)
            } else if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                markCellAt(x, y)
                lastPointerPos[pid] = Pair(x, y)
            }
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            lastPointerPos.clear()
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            lastPointerPos.remove(event.getPointerId(event.actionIndex))
        }
        listener?.onGridProgress(hitCount, touchedGrid.size)
        invalidate()
    }

    private fun interpolateTo(pid: Int, x: Float, y: Float, cellMin: Float) {
        val prev = lastPointerPos[pid]
        if (prev != null) {
            val dist = hypot((x - prev.first).toDouble(), (y - prev.second).toDouble()).toFloat()
            val steps = max(1, (dist / (cellMin / 3f)).toInt())
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                val ix = prev.first + (x - prev.first) * t
                val iy = prev.second + (y - prev.second) * t
                markCellAt(ix, iy)
            }
        } else {
            markCellAt(x, y)
        }
        lastPointerPos[pid] = Pair(x, y)
    }

    private fun markCellAt(x: Float, y: Float) {
        val c = ((x / width) * cols).toInt().coerceIn(0, cols - 1)
        val r = ((y / height) * rows).toInt().coerceIn(0, rows - 1)
        val idx = r * cols + c
        if (!touchedGrid[idx]) {
            touchedGrid[idx] = true
            hitCount++
        }
    }

    private fun handleMultiTouch(event: MotionEvent) {
        activePointers.clear()
        val action = event.actionMasked
        val upIndex = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until event.pointerCount) {
                if (i == upIndex) continue
                activePointers.add(
                    PointerPoint(
                        id = event.getPointerId(i),
                        x = event.getX(i),
                        y = event.getY(i),
                        pressure = event.getPressure(i),
                        sizeMajor = event.getTouchMajor(i)
                    )
                )
            }
        }
        if (activePointers.size > maxPointersObserved) {
            maxPointersObserved = activePointers.size
        }
        listener?.onMultiTouchChanged(activePointers.size, maxPointersObserved)
        invalidate()
    }

    private fun handleGhostTouch(event: MotionEvent) {
        if (!ghostMonitoringActive) return
        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
            event.actionMasked == MotionEvent.ACTION_MOVE
        ) {
            val x = event.getX(0)
            val y = event.getY(0)
            if (ghostHits.size < 200) {
                ghostHits.add(Pair(x, y))
            }
            listener?.onGhostTouchHit(x, y)
            invalidate()
        }
    }
}
