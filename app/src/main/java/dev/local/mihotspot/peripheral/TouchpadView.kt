package dev.local.mihotspot.peripheral

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.local.mihotspot.ui.UiTheme
import kotlin.math.abs

/**
 * Touchpad surface: finger travel becomes relative HID mouse motion, taps become
 * clicks, two-finger drag becomes the wheel, long-press-and-move becomes a drag.
 *
 * This mirrors what a physical trackpad sends over standard HID; macOS applies
 * its own pointer acceleration on top of these relative reports.
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onMove(dx: Int, dy: Int)
        fun onScroll(steps: Int)
        fun onDragStart()
        fun onDragEnd()
        fun onClick(button: Int)
    }

    var listener: Listener? = null

    /** Pointer gain; 1.0 ≈ one screen pixel per finger pixel. */
    var sensitivity: Float = 1.8f

    /** Dark "immersive" theme: black glass, dim hints. */
    var darkTheme: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyTheme()
            invalidate()
        }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F6F8.toInt()
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE2E4E8.toInt()
        style = Paint.Style.STROKE
        strokeWidth = UiTheme.dp(context, 1f).toFloat()
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.COLOR_TEXT_MUTED
        textSize = UiTheme.dp(context, 12f).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastAverageY = 0f
    private var maxPointers = 1
    private var moved = false
    private var dragging = false
    private var accumulatorX = 0f
    private var accumulatorY = 0f
    private var scrollAccumulator = 0f

    private val scrollStepPx: Float
        get() = UiTheme.dp(context, 26f).toFloat()

    private val longPress = Runnable { beginDrag() }

    init {
        isClickable = true
        isFocusable = true
        applyTheme()
    }

    private fun applyTheme() {
        fillPaint.color = if (darkTheme) 0xFF06080B.toInt() else 0xFFF5F6F8.toInt()
        strokePaint.color = if (darkTheme) 0xFF1A2531.toInt() else 0xFFE2E4E8.toInt()
        hintPaint.color = if (darkTheme) 0xFF5C7488.toInt() else UiTheme.COLOR_TEXT_MUTED
        hintPaint.textSize = UiTheme.dp(context, if (darkTheme) 11f else 12f).toFloat()
    }

    private fun beginDrag() {
        if (maxPointers != 1 || moved) return
        dragging = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        listener?.onDragStart()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, UiTheme.dp(context, 16f).toFloat(), UiTheme.dp(context, 16f).toFloat(), fillPaint)
        canvas.drawRoundRect(rect, UiTheme.dp(context, 16f).toFloat(), UiTheme.dp(context, 16f).toFloat(), strokePaint)
        val cy = height / 2f - hintPaint.textSize
        canvas.drawText("触控板", width / 2f, cy, hintPaint)
        canvas.drawText("单指移动 · 轻点=左键 · 双指=滚动/右键", width / 2f, cy + hintPaint.textSize * 1.8f, hintPaint)
        if (!darkTheme || width > UiTheme.dp(context, 520f)) {
            canvas.drawText("长按后拖动 = 拖拽", width / 2f, cy + hintPaint.textSize * 3.6f, hintPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downTime = System.currentTimeMillis()
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                maxPointers = 1
                moved = false
                dragging = false
                accumulatorX = 0f
                accumulatorY = 0f
                scrollAccumulator = 0f
                handler.postDelayed(longPress, 420L)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, event.pointerCount)
                handler.removeCallbacks(longPress)
                lastAverageY = averageY(event)
                scrollAccumulator = 0f
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val avg = averageY(event)
                    if (lastAverageY == 0f) {
                        lastAverageY = avg
                        return true
                    }
                    scrollAccumulator -= (avg - lastAverageY)
                    lastAverageY = avg
                    val steps = (scrollAccumulator / scrollStepPx).toInt()
                    if (steps != 0) {
                        scrollAccumulator -= steps * scrollStepPx
                        listener?.onScroll(steps)
                    }
                    if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) moved = true
                } else {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x
                    lastY = event.y
                    if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                        moved = true
                        if (!dragging) handler.removeCallbacks(longPress)
                    }
                    accumulatorX += dx * sensitivity
                    accumulatorY += dy * sensitivity
                    val outX = accumulatorX.toInt()
                    val outY = accumulatorY.toInt()
                    if (outX != 0 || outY != 0) {
                        accumulatorX -= outX
                        accumulatorY -= outY
                        listener?.onMove(outX, outY)
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                handler.removeCallbacks(longPress)
                // Continue tracking with the finger that is still down.
                val remaining = if (event.actionIndex == 0) 1 else 0
                if (event.pointerCount - 1 == 1) {
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                    lastAverageY = 0f
                    scrollAccumulator = 0f
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (dragging) {
                    dragging = false
                    listener?.onDragEnd()
                } else if (!moved && System.currentTimeMillis() - downTime < 280L) {
                    listener?.onClick(
                        if (maxPointers >= 2) HidProtocol.BUTTON_RIGHT else HidProtocol.BUTTON_LEFT
                    )
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (dragging) {
                    dragging = false
                    listener?.onDragEnd()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun averageY(event: MotionEvent): Float {
        if (event.pointerCount == 0) return 0f
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return sum / event.pointerCount
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }
}
