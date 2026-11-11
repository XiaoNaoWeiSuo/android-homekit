package dev.local.mihotspot.peripheral

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import dev.local.mihotspot.peripheral.usb.UsbHub
import dev.local.mihotspot.ui.UiTheme
import kotlin.math.abs

/**
 * The peripheral surface itself (keyboard + touchpad), in two themes:
 *
 *  - immersive: black, glowing glyphs, minimum screen brightness, keeps the
 *    screen awake — the closest an app can get to an always-on-display without
 *    patching SystemUI. This is the low-power keyboard/mouse mode.
 *  - default: the same layout in the normal light theme.
 *
 * Layout rules (per product spec):
 *  - entering the surface always starts landscape, bar flush right, keyboard
 *    full width and the touchpad pane disabled (zero width);
 *  - dragging the bar resizes the panes;
 *  - double-tapping the bar switches to a 50/50 split, and only that state
 *    unlocks portrait. Rotating swaps the axis but keeps the order:
 *    landscape = keyboard | touchpad, portrait = keyboard over touchpad;
 *  - the ✕ lives inside the keyboard as its top-right key.
 */
class PeripheralPadActivity : Activity() {

    companion object {
        const val EXTRA_THEME = "peripheral_theme"
        const val THEME_IMMERSIVE = "immersive"
        const val THEME_DEFAULT = "default"
        const val EXTRA_TRANSPORT = "peripheral_transport"
        const val TRANSPORT_BT = "bt"
        const val TRANSPORT_USB = "usb"
        const val PREFS = BtHidPeripheral.PREFS
        const val KEY_SENSITIVITY = "touchpad_sensitivity"
        const val KEY_TRAIL = "touchpad_trail"

        fun intent(
            context: Context,
            immersive: Boolean,
            transport: String = TRANSPORT_BT
        ): Intent =
            Intent(context, PeripheralPadActivity::class.java)
                .putExtra(EXTRA_THEME, if (immersive) THEME_IMMERSIVE else THEME_DEFAULT)
                .putExtra(EXTRA_TRANSPORT, transport)
    }

    private val bridge by lazy { PeripheralHub.bridge(this) }
    private val usbMode: Boolean by lazy {
        intent.getStringExtra(EXTRA_TRANSPORT) == TRANSPORT_USB
    }
    private val usbLink by lazy { UsbHub.link(this) }

    private fun sendKeyboardState(modifiers: Int, usages: IntArray) {
        if (usbMode) usbLink.sendKeyboardState(modifiers, usages)
        else bridge.sendKeyboardState(modifiers, usages)
    }

    private fun moveMouse(dx: Int, dy: Int) {
        if (usbMode) usbLink.mouseMove(dx, dy) else bridge.mouseMove(dx, dy)
    }

    private fun scrollMouse(steps: Int) {
        if (usbMode) usbLink.mouseScroll(steps) else bridge.mouseScroll(steps)
    }

    private fun mouseButton(button: Int, down: Boolean) {
        if (usbMode) usbLink.mouseButton(button, down) else bridge.mouseButton(button, down)
    }

    private fun clickMouse(button: Int) {
        if (usbMode) usbLink.click(button) else bridge.click(button)
    }
    private lateinit var split: SplitLayout
    private lateinit var keyboard: KeyboardPanel
    private lateinit var touchpad: TouchpadView
    private var dark = true
    private var backInvoker: android.window.OnBackInvokedCallback? = null
    private var lastBackHintAt = 0L

    private val stateListener = object : BtHidPeripheral.Listener {
        override fun onStateChanged(state: BtHidPeripheral.State, detail: String?) {
            runOnUiThread {
                if (state == BtHidPeripheral.State.STOPPED ||
                    state == BtHidPeripheral.State.FAILED ||
                    state == BtHidPeripheral.State.UNSUPPORTED
                ) {
                    finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = intent.getStringExtra(EXTRA_THEME) != THEME_DEFAULT
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (dark) {
            // "AOD-like": keep our own black surface and dim the panel as far as
            // the framework allows. True AOD belongs to SystemUI and cannot host
            // a normal app window, so this is the honest approximation.
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        }
        setContentView(buildUi())
        if (dark) {
            hideSystemBars()
            excludeSystemBackGesture()
            registerBackSwallow()
        }
        if (usbMode) {
            usbLink.start()
        } else {
            bridge.addListener(stateListener)
            bridge.start()
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) {
            backInvoker?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        if (usbMode) usbLink.stop() else bridge.removeListener(stateListener)
        super.onDestroy()
    }

    /**
     * MIUI's gesture stubs do not honour setSystemGestureExclusionRects, so the
     * edge swipe still reaches the activity as a back event. Swallow it: only
     * the ✕ key leaves the peripheral surface.
     */
    private fun registerBackSwallow() {
        if (Build.VERSION.SDK_INT < 33) return
        val callback = android.window.OnBackInvokedCallback { notifyExitOnly() }
        backInvoker = callback
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            callback
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        notifyExitOnly()
    }

    private fun notifyExitOnly() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBackHintAt < 2000L) return
        lastBackHintAt = now
        Toast.makeText(this, "沉浸模式已屏蔽返回手势，点键盘上的 ✕ 退出", Toast.LENGTH_SHORT).show()
    }

    /**
     * Immersive mode is used as a keyboard/touchpad, so the system back gesture
     * along the screen edges is pure accident. Exclude the whole window from
     * gesture navigation; the ✕ key is the only way out.
     *
     * The platform clamps exclusions to 200 dp per edge, which is exactly the
     * back-gesture zone; the bottom home gesture is not excludable and stays.
     */
    private fun excludeSystemBackGesture() {
        if (Build.VERSION.SDK_INT < 29) return
        val content = findViewById<android.view.View>(android.R.id.content) ?: return
        val update = {
            val rects = listOf(android.graphics.Rect(0, 0, content.width, content.height))
            content.systemGestureExclusionRects = rects
            // Also apply on the decor root: some OEM gesture handlers only
            // look at the window's top-level view.
            window.decorView.systemGestureExclusionRects = rects
        }
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        content.post(update)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
            window.insetsController?.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun dp(v: Float): Int = UiTheme.dp(this, v)

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(if (dark) Color.BLACK else UiTheme.COLOR_BG)
        }

        keyboard = KeyboardPanel(this).apply {
            darkTheme = dark
            glow = dark
            onKeyboardState = { modifiers, usages ->
                sendKeyboardState(modifiers, usages)
            }
            onExit = { finish() }
        }
        touchpad = TouchpadView(this).apply {
            darkTheme = dark
            trailEnabled = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_TRAIL, false)
            sensitivity = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_SENSITIVITY, 180) / 100f
            listener = object : TouchpadView.Listener {
                override fun onMove(dx: Int, dy: Int) = moveMouse(dx, dy)
                override fun onScroll(steps: Int) = scrollMouse(steps)
                override fun onDragStart() = mouseButton(HidProtocol.BUTTON_LEFT, true)
                override fun onDragEnd() = mouseButton(HidProtocol.BUTTON_LEFT, false)
                override fun onClick(button: Int) = clickMouse(button)
            }
        }

        val bar = BarView(this).apply {
            darkTheme = dark
            onDrag = { delta -> split.moveBar(delta) }
            onSwipeVertical = { up -> split.animateTo(if (up) 1f else 0f) }
            onDoubleTap = { split.animateTo(0.5f) }
        }

        split = SplitLayout(this).apply {
            addView(keyboard)
            addView(bar)
            addView(touchpad)
            setFraction(1f)
            onFractionChanged = { fraction -> lockOrientationFor(fraction) }
        }
        root.addView(
            split,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return root
    }

    /**
     * Everything except the 50/50 coexistence state is landscape-locked.
     * In the split state the user may rotate; the panes keep their order and
     * only the axis changes.
     */
    private fun lockOrientationFor(fraction: Float) {
        val half = abs(fraction - 0.5f) <= 0.02f
        val target = if (half) {
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (requestedOrientation != target) requestedOrientation = target
    }
}

/**
 * Three-pane layout: [keyboard][bar][touchpad] in landscape, and
 * [keyboard]/[bar]/[touchpad] stacked in portrait. `fraction` is how much of the
 * available space the keyboard gets along the split axis.
 */
private class SplitLayout(context: Context) : ViewGroup(context) {

    var fraction: Float = 1f
        private set

    var onFractionChanged: ((Float) -> Unit)? = null

    private val barThickness = UiTheme.dp(context, 26f)
    private var verticalSplit = false

    fun moveBar(deltaPixels: Float) {
        val available = if (verticalSplit) {
            (height - barThickness).coerceAtLeast(1)
        } else {
            (width - barThickness).coerceAtLeast(1)
        }
        setFraction(fraction + deltaPixels / available)
    }

    fun animateTo(target: Float) {
        ValueAnimator.ofFloat(fraction, target.coerceIn(0f, 1f)).apply {
            duration = 160L
            addUpdateListener { setFraction(it.animatedValue as Float) }
            start()
        }
    }

    fun setFraction(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (clamped == fraction) return
        fraction = clamped
        requestLayout()
        onFractionChanged?.invoke(clamped)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        verticalSplit = height > width
        (getChildAt(1) as? BarView)?.horizontalBar = verticalSplit

        if (verticalSplit) {
            val available = (height - barThickness).coerceAtLeast(1)
            val keyboardHeight = (available * fraction).toInt()
            val touchpadHeight = available - keyboardHeight
            getChildAt(0).measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(keyboardHeight, MeasureSpec.EXACTLY)
            )
            getChildAt(1).measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(barThickness, MeasureSpec.EXACTLY)
            )
            getChildAt(2).measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(touchpadHeight, MeasureSpec.EXACTLY)
            )
        } else {
            val available = (width - barThickness).coerceAtLeast(1)
            val keyboardWidth = (available * fraction).toInt()
            val touchpadWidth = available - keyboardWidth
            getChildAt(0).measure(
                MeasureSpec.makeMeasureSpec(keyboardWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
            getChildAt(1).measure(
                MeasureSpec.makeMeasureSpec(barThickness, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
            getChildAt(2).measure(
                MeasureSpec.makeMeasureSpec(touchpadWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t
        if (verticalSplit) {
            val keyboardHeight = getChildAt(0).measuredHeight
            getChildAt(0).layout(0, 0, width, keyboardHeight)
            getChildAt(1).layout(0, keyboardHeight, width, keyboardHeight + barThickness)
            getChildAt(2).layout(0, keyboardHeight + barThickness, width, height)
        } else {
            val keyboardWidth = getChildAt(0).measuredWidth
            getChildAt(0).layout(0, 0, keyboardWidth, height)
            getChildAt(1).layout(keyboardWidth, 0, keyboardWidth + barThickness, height)
            getChildAt(2).layout(keyboardWidth + barThickness, 0, width, height)
        }
    }
}

/**
 * Handle between the two panes.
 *  - drag along the split axis → resize the panes
 *  - vertical swipe (landscape) → full keyboard / full touchpad
 *  - double tap                 → 50 / 50
 */
private class BarView(context: Context) : View(context) {

    var onDrag: ((Float) -> Unit)? = null
    var onSwipeVertical: ((Boolean) -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null

    var darkTheme: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /** True when the panes are stacked (portrait): the bar is horizontal. */
    var horizontalBar: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var pressed = false

    private var lastX = 0f
    private var lastY = 0f
    private var totalDx = 0f
    private var totalDy = 0f
    private var dragged = false
    private var lastTapAt = 0L

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // A short Apple-style grab handle, not a full-height pill.
        val thickness = UiTheme.dp(context, 4f).toFloat()
        val length = UiTheme.dp(context, 54f).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        if (horizontalBar) {
            rect.set(cx - length / 2f, cy - thickness / 2f, cx + length / 2f, cy + thickness / 2f)
        } else {
            rect.set(cx - thickness / 2f, cy - length / 2f, cx + thickness / 2f, cy + length / 2f)
        }
        paint.color = when {
            pressed -> if (darkTheme) 0xAAE8F4FF.toInt() else 0x55000000
            darkTheme -> 0x66DCE9F5.toInt()
            else -> 0x33000000
        }
        val radius = thickness / 2f
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                totalDx = 0f
                totalDy = 0f
                dragged = false
                pressed = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                totalDx += dx
                totalDy += dy
                if (horizontalBar) {
                    if (abs(dy) >= 1f) {
                        dragged = true
                        onDrag?.invoke(dy)
                        lastY = event.y
                    }
                } else if (abs(dx) >= 1f) {
                    dragged = true
                    onDrag?.invoke(dx)
                    lastX = event.x
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!horizontalBar && abs(totalDy) > UiTheme.dp(context, 48f) &&
                    abs(totalDy) > abs(totalDx)
                ) {
                    onSwipeVertical?.invoke(totalDy < 0)
                } else if (!dragged) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastTapAt < 320L) {
                        lastTapAt = 0L
                        onDoubleTap?.invoke()
                    } else {
                        lastTapAt = now
                    }
                }
                performClick()
                pressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
