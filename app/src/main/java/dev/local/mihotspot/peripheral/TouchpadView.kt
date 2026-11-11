package dev.local.mihotspot.peripheral

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.local.mihotspot.ui.UiTheme
import kotlin.math.abs

/**
 * Borderless touchpad surface: pure background with a small crosshair in the
 * middle (no text, no card). Finger travel becomes relative HID mouse motion;
 * taps become clicks, two-finger drag scrolls, long-press-and-move drags.
 *
 * An optional fading trail visualises the finger path.
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

    /** Dark "immersive" theme: faint light crosshair. */
    var darkTheme: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Fading finger trail (opt-in). */
    var trailEnabled: Boolean = false

    private class TrailPoint(val x: Float, val y: Float, val at: Long)

    private val trail = ArrayList<TrailPoint>(48)
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())

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
    }

    private fun beginDrag() {
        if (maxPointers != 1 || moved) return
        dragging = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        listener?.onDragStart()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val arm = UiTheme.dp(context, 14f).toFloat()
        crossPaint.strokeWidth = UiTheme.dp(context, 1.2f).toFloat()
        crossPaint.color = if (darkTheme) 0x55FFFFFF else 0x33000000
        canvas.drawLine(cx - arm, cy, cx + arm, cy, crossPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, crossPaint)
        if (trailEnabled) drawTrail(canvas)
    }

    private fun drawTrail(canvas: Canvas) {
        if (trail.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        trail.removeAll { now - it.at > TRAIL_LIFETIME_MS }
        if (trail.size < 2) return
        trailPaint.strokeWidth = UiTheme.dp(context, 2f).toFloat()
        for (i in 1 until trail.size) {
            val prev = trail[i - 1]
            val point = trail[i]
            val age = (now - point.at).coerceIn(0, TRAIL_LIFETIME_MS)
            val alpha = ((1f - age / TRAIL_LIFETIME_MS.toFloat()) * 110f).toInt()
            if (alpha <= 0) continue
            trailPaint.color = if (darkTheme) {
                (alpha shl 24) or 0x35CBFF
            } else {
                (alpha shl 24) or 0x0D84FF
            }
            canvas.drawLine(prev.x, prev.y, point.x, point.y, trailPaint)
        }
        postInvalidateOnAnimation()
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
                if (trailEnabled) {
                    trail.clear()
                    addTrailPoint(event.x, event.y)
                }
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
                    if (trailEnabled) {
                        addTrailPoint(event.x, event.y)
                        postInvalidateOnAnimation()
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                handler.removeCallbacks(longPress)
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
                postInvalidateOnAnimation()
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

    private fun addTrailPoint(x: Float, y: Float) {
        trail.add(TrailPoint(x, y, SystemClock.uptimeMillis()))
        while (trail.size > 64) trail.removeAt(0)
    }

    private fun averageY(event: MotionEvent): Float {
        if (event.pointerCount == 0) return 0f
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return sum / event.pointerCount
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPress)
        trail.clear()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TRAIL_LIFETIME_MS = 520L
    }
}
