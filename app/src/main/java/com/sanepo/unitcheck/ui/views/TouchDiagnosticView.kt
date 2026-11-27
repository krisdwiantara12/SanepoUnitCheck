package com.sanepo.unitcheck.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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
    }

    private data class PointerPoint(
        val id: Int,
        val x: Float,
        val y: Float,
        val pressure: Float,
        val sizeMajor: Float
    )

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
        strokeWidth = 2f
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6B00")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val pointerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ADE80")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F87171")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F4F6F8")
        textSize = 30f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    fun resetState() {
        touchedGrid.fill(false)
        hitCount = 0
        maxPointersObserved = 0
        activePointers.clear()
        lastPointerPos.clear()
        ghostHits.clear()
        listener?.onGridProgress(0, touchedGrid.size)
        listener?.onMultiTouchChanged(0, 0)
        invalidate()
    }

    fun getGridCoverage(): Pair<Int, Int> = Pair(hitCount, touchedGrid.size)
    fun getMaxPointers(): Int = maxPointersObserved
    fun getGhostHitCount(): Int = ghostHits.size

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
        for (p in activePointers) {
            canvas.drawLine(0f, p.y, width.toFloat(), p.y, crosshairPaint)
            canvas.drawLine(p.x, 0f, p.x, height.toFloat(), crosshairPaint)
            val radius = max(56f, p.sizeMajor * 0.5f)
            canvas.drawCircle(p.x, p.y, radius, pointerCirclePaint)
            val label = "#${p.id} (${p.x.toInt()},${p.y.toInt()}) P:${String.format("%.2f", p.pressure)}"
            val ty = (p.y - radius - 16f).coerceIn(48f, height - 24f)
            val tx = (p.x - 140f).coerceIn(16f, max(16f, width - 360f))
            canvas.drawText(label, tx, ty, textPaint)
        }
    }

    private fun drawGhostMonitor(canvas: Canvas) {
        for ((gx, gy) in ghostHits) {
            canvas.drawCircle(gx, gy, 24f, ghostPaint)
            canvas.drawText("GHOST (${gx.toInt()},${gy.toInt()})", (gx + 30f).coerceAtMost(width - 280f), gy, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (mode) {
            Mode.GRID -> handleGridTouch(event)
            Mode.MULTITOUCH -> handleMultiTouch(event)
            Mode.GHOST -> handleGhostTouch(event)
        }
        return true
    }

    private fun handleGridTouch(event: MotionEvent) {
        if (width <= 0 || height <= 0) return
        val action = event.actionMasked
        val cellMin = min(width.toFloat() / cols, height.toFloat() / rows).coerceAtLeast(8f)

        for (i in 0 until event.pointerCount) {
            val pid = event.getPointerId(i)
            val x = event.getX(i).coerceIn(0f, (width - 1).toFloat())
            val y = event.getY(i).coerceIn(0f, (height - 1).toFloat())

            if (action == MotionEvent.ACTION_MOVE) {
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
            } else if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                markCellAt(x, y)
            }
            lastPointerPos[pid] = Pair(x, y)
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            lastPointerPos.clear()
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            lastPointerPos.remove(event.getPointerId(event.actionIndex))
        }
        listener?.onGridProgress(hitCount, touchedGrid.size)
        invalidate()
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
            ghostHits.add(Pair(x, y))
            listener?.onGhostTouchHit(x, y)
            invalidate()
        }
    }
}
