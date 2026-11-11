package dev.local.mihotspot.peripheral

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.local.mihotspot.ui.UiTheme

/**
 * On-screen keyboard on a 16-unit grid, so every row adds up to the same width
 * and keys line up like a real ANSI keyboard (tab 2u, caps 2u, shift 2.5u/3.5u,
 * enter 3u, space 7.5u).
 *
 * Visual language:
 *  - immersive: no resting keycaps (pure black), a translucent keycap appears
 *    only while pressed, glyphs glow;
 *  - default: light keycaps, accent tint while pressed.
 */
class KeyboardPanel(context: Context) : LinearLayout(context) {

    var onKey: ((usage: Int, modifiers: Int) -> Unit)? = null

    /** The ✕ key in the top-right corner exits the peripheral surface. */
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

    private data class KeySpec(
        val label: String,
        val usage: Int,
        val weight: Float = 1f,
        val modBit: Int = 0,
        val latched: Boolean = false,
        val exit: Boolean = false,
        val small: Boolean = false
    )

    private val latchedMods = mutableSetOf<Int>()
    private val keyViews = mutableListOf<Pair<TextView, KeySpec>>()

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

    private fun build() {
        // 16 units per row.
        addRow(
            listOf(
                key("esc", HidProtocol.KEY_ESC, 1f, small = true)
            ) + listOf(
                digit("1"), digit("2"), digit("3"), digit("4"), digit("5"),
                digit("6"), digit("7"), digit("8"), digit("9"), digit("0")
            ) + listOf(
                char("-"), char("="),
                key("⌫", HidProtocol.KEY_BACKSPACE, 2f),
                KeySpec("✕", 0, 1f, exit = true)
            )
        )
        addRow(
            listOf(
                key("⇥", HidProtocol.KEY_TAB, 2f, small = true)
            ) + listOf(
                letter("q"), letter("w"), letter("e"), letter("r"), letter("t"),
                letter("y"), letter("u"), letter("i"), letter("o"), letter("p")
            ) + listOf(
                char("["), char("]"),
                char("\\", 2f)
            )
        )
        addRow(
            listOf(
                key("⇪", HidProtocol.KEY_CAPS_LOCK, 2f, small = true)
            ) + listOf(
                letter("a"), letter("s"), letter("d"), letter("f"), letter("g"),
                letter("h"), letter("j"), letter("k"), letter("l")
            ) + listOf(
                char(";"), char("'"),
                key("⏎", HidProtocol.KEY_ENTER, 3f, small = true)
            )
        )
        addRow(
            listOf(
                latch("⇧", HidProtocol.MOD_LEFT_SHIFT, 2.5f)
            ) + listOf(
                letter("z"), letter("x"), letter("c"), letter("v"), letter("b"),
                letter("n"), letter("m")
            ) + listOf(
                char(","), char("."), char("/"),
                latch("⇧", HidProtocol.MOD_LEFT_SHIFT, 3.5f)
            )
        )
        addRow(
            listOf(
                latch("⌃", HidProtocol.MOD_LEFT_CTRL, 1.5f),
                latch("⌥", HidProtocol.MOD_LEFT_ALT, 1.5f),
                latch("⌘", HidProtocol.MOD_LEFT_GUI, 1.5f),
                key("space", HidProtocol.KEY_SPACE, 7.5f, small = true),
                key("←", HidProtocol.KEY_LEFT_ARROW, 1f),
                key("↑", HidProtocol.KEY_UP_ARROW, 1f),
                key("↓", HidProtocol.KEY_DOWN_ARROW, 1f),
                key("→", HidProtocol.KEY_RIGHT_ARROW, 1f)
            )
        )
    }

    private fun addRow(keys: List<KeySpec>) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        }
        for (spec in keys) row.addView(buildKey(spec))
        addView(row)
    }

    private fun buildKey(spec: KeySpec): View {
        val view = TextView(context).apply {
            text = spec.label
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            isClickable = true
            isFocusable = false
            includeFontPadding = false
            background = keyBackground(spec)
            val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, spec.weight)
            lp.setMargins(
                UiTheme.dp(context, 1.5f), UiTheme.dp(context, 1.5f),
                UiTheme.dp(context, 1.5f), UiTheme.dp(context, 1.5f)
            )
            layoutParams = lp
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.background = keyBackground(spec, pressed = true)
                        activate(spec)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.background = keyBackground(spec)
                        true
                    }
                    else -> true
                }
            }
        }
        keyViews.add(view to spec)
        return view
    }

    private fun activate(spec: KeySpec) {
        if (spec.exit) {
            onExit?.invoke()
            return
        }
        if (spec.latched) {
            if (!latchedMods.add(spec.modBit)) latchedMods.remove(spec.modBit)
            applyTheme()
        } else {
            val mods = latchedMods.fold(0) { acc, bit -> acc or bit }
            onKey?.invoke(spec.usage, mods)
            if (latchedMods.isNotEmpty()) {
                latchedMods.clear()
                applyTheme()
            }
        }
        KeyHaptics.tap(context)
    }

    private fun applyTheme() {
        val rows = childCount.coerceAtLeast(1)
        val cellWidth = if (width > 0) width / 16f else 0f
        val cellHeight = if (height > 0) height / rows.toFloat() else 0f
        val density = resources.displayMetrics.density
        val fitted = if (cellWidth > 0f && cellHeight > 0f) {
            (minOf(cellWidth / 1.22f, cellHeight * 0.38f) / density).coerceIn(9f, 21f)
        } else {
            13f
        }
        for ((view, spec) in keyViews) {
            view.background = keyBackground(spec)
            view.setTextColor(
                when {
                    darkTheme && spec.latched && latchedMods.contains(spec.modBit) -> 0xFF8FEBFF.toInt()
                    darkTheme -> 0xFFDFF1FF.toInt()
                    spec.latched && latchedMods.contains(spec.modBit) -> Color.WHITE
                    else -> UiTheme.COLOR_TEXT_PRIMARY
                }
            )
            val size = if (spec.small) fitted * 0.78f else fitted
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (darkTheme && glow) {
                view.setShadowLayer(
                    UiTheme.dp(context, 9f).toFloat(), 0f, 0f, 0xFF35CBFF.toInt()
                )
            } else {
                view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        }
    }

    /**
     * Immersive keys have no resting fill; the keycap only appears while the
     * finger is down. Light theme keeps normal keycaps.
     */
    private fun keyBackground(spec: KeySpec, pressed: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = UiTheme.dp(context, 8f).toFloat()
            val latched = spec.latched && latchedMods.contains(spec.modBit)
            when {
                darkTheme && pressed -> {
                    setColor(0x33FFFFFF)
                    setStroke(UiTheme.dp(context, 1f), 0x66FFFFFF)
                }
                darkTheme && latched -> {
                    setColor(0x2A2FD4FF)
                    setStroke(UiTheme.dp(context, 1f), 0x552FD4FF)
                }
                darkTheme -> setColor(Color.TRANSPARENT)
                pressed -> {
                    setColor(UiTheme.COLOR_ACCENT_LIGHT)
                    setStroke(UiTheme.dp(context, 1f), UiTheme.COLOR_ACCENT)
                }
                latched -> setColor(UiTheme.COLOR_ACCENT)
                else -> setColor(UiTheme.COLOR_BTN_SECONDARY_BG)
            }
        }

    private fun key(label: String, usage: Int, weight: Float = 1f, small: Boolean = false) =
        KeySpec(label, usage, weight, small = small)

    private fun latch(label: String, modBit: Int, weight: Float = 1f) =
        KeySpec(label, 0, weight, modBit, latched = true, small = true)

    private fun letter(c: String): KeySpec =
        KeySpec(c.uppercase(), HidProtocol.strokeFor(c[0])?.usage ?: 0)

    private fun digit(c: String): KeySpec =
        KeySpec(c, HidProtocol.strokeFor(c[0])?.usage ?: 0)

    private fun char(c: String, weight: Float = 1f): KeySpec =
        KeySpec(c, HidProtocol.strokeFor(c[0])?.usage ?: 0, weight)
}
