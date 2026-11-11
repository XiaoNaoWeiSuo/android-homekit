package dev.local.mihotspot.peripheral

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import dev.local.mihotspot.ui.UiTheme
import kotlin.math.min

/**
 * On-screen keyboard on a 16-unit grid (tab/caps 2u, shift 2.5/3.5u, enter 3u,
 * space 7.5u) so every row has the same width.
 *
 *  - Letters are uppercase; special keys carry a hand-drawn, uniform icon set
 *    (same stroke, same size regardless of keycap width).
 *  - Icons on left-hand keys sit bottom-left, right-hand keys bottom-right,
 *    like a MacBook keyboard.
 *  - The panel owns multi-touch: several keys can be held at once (Ctrl + C),
 *    a held key stays in the HID report until release, so the host repeats it.
 */
class KeyboardPanel(context: Context) : LinearLayout(context) {

    /** Complete keyboard state changes: held modifiers + held usages. */
    var onKeyboardState: ((modifiers: Int, usages: IntArray) -> Unit)? = null

    var onExit: (() -> Unit)? = null

    var darkTheme: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyTheme()
        }

    var glow: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyTheme()
        }

    private enum class Icon { TAB, CAPS, ENTER, BACKSPACE, SHIFT, CTRL, ALT, CMD, LEFT, UP, DOWN, RIGHT, EXIT }

    private enum class Align { CENTER, START, END }

    private data class KeySpec(
        val label: String?,
        val icon: Icon?,
        val usage: Int,
        val weight: Float = 1f,
        val modBit: Int = 0,
        val special: Boolean = false,
        val align: Align = Align.CENTER,
        val small: Boolean = false,
        val exit: Boolean = false
    )

    private val pointerKeys = SparseArray<KeyView>()
    private val keyViews = mutableListOf<KeyView>()

    init {
        orientation = VERTICAL
        setPadding(0, UiTheme.dp(context, 2f), 0, UiTheme.dp(context, 2f))
        build()
        applyTheme()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTheme()
    }

    // ---- Layout -----------------------------------------------------------

    private fun build() {
        addRow(
            listOf(
                text("esc", HidProtocol.KEY_ESC, 1f, special = true, small = true)
            ) + listOf(
                digit("1"), digit("2"), digit("3"), digit("4"), digit("5"),
                digit("6"), digit("7"), digit("8"), digit("9"), digit("0")
            ) + listOf(
                char("-"), char("="),
                iconKey(Icon.BACKSPACE, HidProtocol.KEY_BACKSPACE, 2f, Align.END),
                KeySpec(null, Icon.EXIT, 0, 1f, special = true, align = Align.END, exit = true)
            )
        )
        addRow(
            listOf(
                iconKey(Icon.TAB, HidProtocol.KEY_TAB, 2f, Align.START)
            ) + listOf(
                letter("q"), letter("w"), letter("e"), letter("r"), letter("t"),
                letter("y"), letter("u"), letter("i"), letter("o"), letter("p")
            ) + listOf(
                char("["), char("]"), char("\\", 2f)
            )
        )
        addRow(
            listOf(
                iconKey(Icon.CAPS, HidProtocol.KEY_CAPS_LOCK, 2f, Align.START)
            ) + listOf(
                letter("a"), letter("s"), letter("d"), letter("f"), letter("g"),
                letter("h"), letter("j"), letter("k"), letter("l")
            ) + listOf(
                char(";"), char("'"),
                iconKey(Icon.ENTER, HidProtocol.KEY_ENTER, 3f, Align.END)
            )
        )
        addRow(
            listOf(
                iconKey(Icon.SHIFT, 0, 2.5f, Align.START, HidProtocol.MOD_LEFT_SHIFT)
            ) + listOf(
                letter("z"), letter("x"), letter("c"), letter("v"), letter("b"),
                letter("n"), letter("m")
            ) + listOf(
                char(","), char("."), char("/"),
                iconKey(Icon.SHIFT, 0, 3.5f, Align.END, HidProtocol.MOD_RIGHT_SHIFT)
            )
        )
        addRow(
            listOf(
                iconKey(Icon.CTRL, 0, 1.5f, Align.START, HidProtocol.MOD_LEFT_CTRL),
                iconKey(Icon.ALT, 0, 1.5f, Align.START, HidProtocol.MOD_LEFT_ALT),
                iconKey(Icon.CMD, 0, 1.5f, Align.START, HidProtocol.MOD_LEFT_GUI),
                text("space", HidProtocol.KEY_SPACE, 7.5f, small = true),
                iconKey(Icon.LEFT, HidProtocol.KEY_LEFT_ARROW, 1f, Align.END),
                iconKey(Icon.UP, HidProtocol.KEY_UP_ARROW, 1f, Align.END),
                iconKey(Icon.DOWN, HidProtocol.KEY_DOWN_ARROW, 1f, Align.END),
                iconKey(Icon.RIGHT, HidProtocol.KEY_RIGHT_ARROW, 1f, Align.END)
            )
        )
    }

    private fun addRow(keys: List<KeySpec>) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        for (spec in keys) {
            val view = KeyView(spec)
            row.addView(view)
            keyViews.add(view)
        }
        addView(row)
    }

    private fun text(
        label: String,
        usage: Int,
        weight: Float = 1f,
        special: Boolean = false,
        small: Boolean = false
    ) = KeySpec(label, null, usage, weight, special = special, small = small)

    private fun iconKey(
        icon: Icon,
        usage: Int,
        weight: Float,
        align: Align,
        modBit: Int = 0
    ) = KeySpec(null, icon, usage, weight, modBit, special = true, align = align)

    private fun letter(c: String) = KeySpec(c.uppercase(), null, HidProtocol.strokeFor(c[0])?.usage ?: 0)

    private fun digit(c: String) = KeySpec(c, null, HidProtocol.strokeFor(c[0])?.usage ?: 0)

    private fun char(c: String, weight: Float = 1f) =
        KeySpec(c, null, HidProtocol.strokeFor(c[0])?.usage ?: 0, weight)

    // ---- Theme ------------------------------------------------------------

    private fun applyTheme() {
        val rows = childCount.coerceAtLeast(1)
        val cellWidth = if (width > 0) width / 16f else 0f
        val cellHeight = if (height > 0) height / rows.toFloat() else 0f
        val density = resources.displayMetrics.density
        val fitted = if (cellWidth > 0f && cellHeight > 0f) {
            (min(cellWidth / 1.22f, cellHeight * 0.36f) / density).coerceIn(9f, 21f)
        } else {
            13f
        }
        for (view in keyViews) {
            view.textSizePx = (if (view.spec.small) fitted * 0.78f else fitted) * density
            view.invalidate()
        }
    }

    // ---- Touch (multi-pointer, chords) ------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                assign(event.getPointerId(index), event.getX(index), event.getY(index))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    assign(event.getPointerId(i), event.getX(i), event.getY(i))
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(event.getPointerId(event.actionIndex))
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerKeys.clear()
                keyViews.forEach { it.keyPressed = false; it.invalidate() }
                notifyState()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun assign(pointerId: Int, x: Float, y: Float) {
        val target = keyAt(x, y)
        val current = pointerKeys.get(pointerId)
        if (target === current) return
        if (current != null) {
            pointerKeys.remove(pointerId)
            current.keyPressed = false
            current.invalidate()
        }
        if (target == null) {
            notifyState()
            return
        }
        if (target.spec.exit) {
            onExit?.invoke()
            notifyState()
            return
        }
        pointerKeys.put(pointerId, target)
        target.keyPressed = true
        target.invalidate()
        KeyHaptics.tap(context)
        notifyState()
    }

    private fun release(pointerId: Int) {
        val current = pointerKeys.get(pointerId) ?: return
        pointerKeys.remove(pointerId)
        current.keyPressed = false
        current.invalidate()
        notifyState()
    }

    private fun notifyState() {
        var modifiers = 0
        val usages = LinkedHashSet<Int>()
        for (i in 0 until pointerKeys.size()) {
            val spec = pointerKeys.valueAt(i).spec
            modifiers = modifiers or spec.modBit
            if (spec.usage != 0) usages.add(spec.usage)
        }
        onKeyboardState?.invoke(modifiers, usages.take(6).toIntArray())
    }

    private fun keyAt(x: Float, y: Float): KeyView? {
        for (r in 0 until childCount) {
            val row = getChildAt(r)
            if (y < row.top || y > row.bottom) continue
            for (i in 0 until (row as ViewGroup).childCount) {
                val key = row.getChildAt(i) as? KeyView ?: continue
                if (x >= key.left && x <= key.right) return key
            }
        }
        return null
    }

    override fun onDetachedFromWindow() {
        pointerKeys.clear()
        onKeyboardState?.invoke(0, IntArray(0))
        super.onDetachedFromWindow()
    }

    // ---- Key view ---------------------------------------------------------

    private inner class KeyView(val spec: KeySpec) : View(context) {

        var keyPressed = false
        var textSizePx = 0f

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val rect = RectF()
        private val path = Path()

        init {
            isClickable = false
            isFocusable = false
            val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, spec.weight)
            lp.setMargins(
                UiTheme.dp(context, 1.5f), UiTheme.dp(context, 1.5f),
                UiTheme.dp(context, 1.5f), UiTheme.dp(context, 1.5f)
            )
            layoutParams = lp
        }

        override fun onDraw(canvas: Canvas) {
            drawBackground(canvas)
            if (spec.label != null) {
                drawText(canvas, spec.label)
            } else if (spec.icon != null) {
                drawIcon(canvas)
            }
        }

        private fun drawBackground(canvas: Canvas) {
            val radius = UiTheme.dp(context, 8f).toFloat()
            // Stroking on the exact view bounds clips half the stroke, which made
            // the special-key borders almost invisible on black. Inset it.
            val inset = UiTheme.dp(context, 0.75f).toFloat()
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            val strokeRect = RectF(inset, inset, width - inset, height - inset)
            val strokeRadius = (radius - inset).coerceAtLeast(1f)
            if (darkTheme) {
                when {
                    keyPressed -> {
                        bgPaint.color = 0x3DFFFFFF
                        canvas.drawRoundRect(rect, radius, radius, bgPaint)
                        drawBorder(canvas, strokeRect, strokeRadius, 0xB3FFFFFF.toInt())
                    }
                    spec.special -> drawBorder(canvas, strokeRect, strokeRadius, 0x66FFFFFF)
                }
            } else {
                bgPaint.color = if (keyPressed) UiTheme.COLOR_ACCENT_LIGHT else UiTheme.COLOR_BTN_SECONDARY_BG
                canvas.drawRoundRect(rect, radius, radius, bgPaint)
                when {
                    keyPressed -> drawBorder(canvas, strokeRect, strokeRadius, UiTheme.COLOR_ACCENT)
                    spec.special -> drawBorder(canvas, strokeRect, strokeRadius, 0xFFD2D7DE.toInt())
                }
            }
        }

        private fun drawBorder(canvas: Canvas, target: RectF, borderRadius: Float, color: Int) {
            strokePaint.strokeWidth = UiTheme.dp(context, 1.1f).toFloat()
            strokePaint.color = color
            canvas.drawRoundRect(target, borderRadius, borderRadius, strokePaint)
        }

        private fun drawText(canvas: Canvas, label: String) {
            textPaint.textSize = textSizePx
            textPaint.color = if (darkTheme) 0xFFDFF1FF.toInt() else UiTheme.COLOR_TEXT_PRIMARY
            if (darkTheme && glow) {
                textPaint.setShadowLayer(UiTheme.dp(context, 9f).toFloat(), 0f, 0f, 0xFF35CBFF.toInt())
            } else {
                textPaint.clearShadowLayer()
            }
            val fm = textPaint.fontMetrics
            val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, width / 2f, baseline, textPaint)
        }

        private fun drawIcon(canvas: Canvas) {
            val size = min(
                UiTheme.dp(context, 17f).toFloat(),
                min(height * 0.48f, width * 0.52f)
            )
            val padX = UiTheme.dp(context, 9f).toFloat()
            val padBottom = UiTheme.dp(context, 7f).toFloat()
            val left = when (spec.align) {
                Align.START -> padX
                Align.END -> width - size - padX
                Align.CENTER -> (width - size) / 2f
            }
            val top = height - size - padBottom
            strokePaint.strokeWidth = UiTheme.dp(context, 1.5f).toFloat()
            strokePaint.color = if (darkTheme) 0xFFDFF1FF.toInt() else UiTheme.COLOR_TEXT_PRIMARY
            if (darkTheme && glow) {
                strokePaint.setShadowLayer(UiTheme.dp(context, 9f).toFloat(), 0f, 0f, 0xFF35CBFF.toInt())
            } else {
                strokePaint.clearShadowLayer()
            }
            drawIconPath(canvas, spec.icon ?: return, left, top, size)
        }

        private fun drawIconPath(canvas: Canvas, icon: Icon, left: Float, top: Float, s: Float) {
            fun x(f: Float) = left + f * s
            fun y(f: Float) = top + f * s
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                canvas.drawLine(x(x1), y(y1), x(x2), y(y2), strokePaint)

            val head = s * 0.1f
            when (icon) {
                Icon.TAB -> {
                    line(0.08f, 0.50f, 0.68f, 0.50f)
                    line(0.68f, 0.50f, 0.52f, 0.34f)
                    line(0.68f, 0.50f, 0.52f, 0.66f)
                    line(0.90f, 0.24f, 0.90f, 0.76f)
                }
                Icon.CAPS -> {
                    line(0.20f, 0.14f, 0.80f, 0.14f)
                    line(0.50f, 0.34f, 0.14f, 0.66f)
                    line(0.50f, 0.34f, 0.86f, 0.66f)
                    line(0.16f, 0.86f, 0.84f, 0.86f)
                }
                Icon.ENTER -> {
                    line(0.86f, 0.20f, 0.86f, 0.56f)
                    line(0.86f, 0.56f, 0.28f, 0.56f)
                    canvas.drawLine(x(0.28f), y(0.56f), x(0.44f), y(0.40f), strokePaint)
                    canvas.drawLine(x(0.28f), y(0.56f), x(0.44f), y(0.72f), strokePaint)
                }
                Icon.BACKSPACE -> {
                    path.reset()
                    path.moveTo(x(0.90f), y(0.20f))
                    path.lineTo(x(0.44f), y(0.20f))
                    path.lineTo(x(0.10f), y(0.50f))
                    path.lineTo(x(0.44f), y(0.80f))
                    path.lineTo(x(0.90f), y(0.80f))
                    path.close()
                    canvas.drawPath(path, strokePaint)
                    canvas.drawLine(x(0.52f), y(0.36f), x(0.74f), y(0.64f), strokePaint)
                    canvas.drawLine(x(0.74f), y(0.36f), x(0.52f), y(0.64f), strokePaint)
                }
                Icon.SHIFT -> {
                    path.reset()
                    path.moveTo(x(0.50f), y(0.16f))
                    path.lineTo(x(0.08f), y(0.58f))
                    path.lineTo(x(0.28f), y(0.58f))
                    path.lineTo(x(0.28f), y(0.86f))
                    path.lineTo(x(0.72f), y(0.86f))
                    path.lineTo(x(0.72f), y(0.58f))
                    path.lineTo(x(0.92f), y(0.58f))
                    path.close()
                    canvas.drawPath(path, strokePaint)
                }
                Icon.CTRL -> {
                    line(0.14f, 0.68f, 0.50f, 0.34f)
                    line(0.50f, 0.34f, 0.86f, 0.68f)
                }
                Icon.ALT -> {
                    line(0.08f, 0.70f, 0.40f, 0.70f)
                    line(0.40f, 0.70f, 0.72f, 0.30f)
                    line(0.72f, 0.30f, 0.92f, 0.30f)
                }
                Icon.CMD -> {
                    path.reset()
                    path.moveTo(x(0.50f), y(0.26f))
                    path.lineTo(x(0.50f), y(0.74f))
                    path.moveTo(x(0.26f), y(0.50f))
                    path.lineTo(x(0.74f), y(0.50f))
                    path.addRect(x(0.26f), y(0.26f), x(0.74f), y(0.74f), Path.Direction.CW)
                    canvas.drawPath(path, strokePaint)
                    val r = s * 0.14f
                    canvas.drawCircle(x(0.26f), y(0.26f), r, strokePaint)
                    canvas.drawCircle(x(0.74f), y(0.26f), r, strokePaint)
                    canvas.drawCircle(x(0.26f), y(0.74f), r, strokePaint)
                    canvas.drawCircle(x(0.74f), y(0.74f), r, strokePaint)
                }
                Icon.LEFT -> {
                    line(0.82f, 0.50f, 0.34f, 0.50f)
                    canvas.drawLine(x(0.34f), y(0.50f), x(0.50f), y(0.34f), strokePaint)
                    canvas.drawLine(x(0.34f), y(0.50f), x(0.50f), y(0.66f), strokePaint)
                }
                Icon.RIGHT -> {
                    line(0.18f, 0.50f, 0.66f, 0.50f)
                    canvas.drawLine(x(0.66f), y(0.50f), x(0.50f), y(0.34f), strokePaint)
                    canvas.drawLine(x(0.66f), y(0.50f), x(0.50f), y(0.66f), strokePaint)
                }
                Icon.UP -> {
                    line(0.50f, 0.82f, 0.50f, 0.34f)
                    canvas.drawLine(x(0.50f), y(0.34f), x(0.34f), y(0.50f), strokePaint)
                    canvas.drawLine(x(0.50f), y(0.34f), x(0.66f), y(0.50f), strokePaint)
                }
                Icon.DOWN -> {
                    line(0.50f, 0.18f, 0.50f, 0.66f)
                    canvas.drawLine(x(0.50f), y(0.66f), x(0.34f), y(0.50f), strokePaint)
                    canvas.drawLine(x(0.50f), y(0.66f), x(0.66f), y(0.50f), strokePaint)
                }
                Icon.EXIT -> {
                    line(0.26f, 0.26f, 0.74f, 0.74f)
                    line(0.74f, 0.26f, 0.26f, 0.74f)
                }
            }
        }
    }
}
