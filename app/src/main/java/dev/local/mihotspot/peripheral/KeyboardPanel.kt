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
 * Compact on-screen keyboard. Every key maps to a standard HID usage and is sent
 * as one keyboard report; modifier keys latch until the next keystroke, which is
 * how a Bluetooth keyboard composes Ctrl+C / Cmd+Tab.
 *
 * Supports the two peripheral themes: dark "immersive" (glowing glyphs on black)
 * and the normal light card.
 */
class KeyboardPanel(context: Context) : LinearLayout(context) {

    /** Called with the HID usage to tap and the modifiers that go with it. */
    var onKey: ((usage: Int, modifiers: Int) -> Unit)? = null

    /** The ✕ key appended to the top row exits the peripheral surface. */
    var onExit: (() -> Unit)? = null

    var darkTheme: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyTheme()
        }

    /** Glowing key glyphs (immersive / AOD-like mode). */
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
        val exit: Boolean = false
    )

    private val latchedMods = mutableSetOf<Int>()
    private val keyViews = mutableListOf<Pair<TextView, KeySpec>>()

    init {
        orientation = VERTICAL
        setPadding(0, UiTheme.dp(context, 4f), 0, UiTheme.dp(context, 4f))
        build()
        applyTheme()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTheme()
    }

    private fun build() {
        addRow(
            listOf(
                key("esc", HidProtocol.KEY_ESC),
                digit("1"), digit("2"), digit("3"), digit("4"), digit("5"),
                digit("6"), digit("7"), digit("8"), digit("9"), digit("0"),
                char("-"), char("="),
                key("⌫", HidProtocol.KEY_BACKSPACE, 1.3f),
                KeySpec("✕", 0, 0.9f, exit = true)
            )
        )
        addRow(
            listOf(
                key("⇥", HidProtocol.KEY_TAB, 1.2f),
                letter("q"), letter("w"), letter("e"), letter("r"), letter("t"),
                letter("y"), letter("u"), letter("i"), letter("o"), letter("p"),
                char("["), char("]"), char("\\", 1.2f)
            )
        )
        addRow(
            listOf(
                key("⇪", HidProtocol.KEY_CAPS_LOCK, 1.3f),
                letter("a"), letter("s"), letter("d"), letter("f"), letter("g"),
                letter("h"), letter("j"), letter("k"), letter("l"),
                char(";"), char("'"),
                key("⏎", HidProtocol.KEY_ENTER, 1.8f)
            )
        )
        addRow(
            listOf(
                latch("⇧", HidProtocol.MOD_LEFT_SHIFT, 1.5f),
                letter("z"), letter("x"), letter("c"), letter("v"), letter("b"),
                letter("n"), letter("m"),
                char(","), char("."), char("/"),
                latch("⇧", HidProtocol.MOD_LEFT_SHIFT, 1.5f)
            )
        )
        addRow(
            listOf(
                latch("⌃", HidProtocol.MOD_LEFT_CTRL, 1.2f),
                latch("⌥", HidProtocol.MOD_LEFT_ALT, 1.2f),
                latch("⌘", HidProtocol.MOD_LEFT_GUI, 1.2f),
                key("space", HidProtocol.KEY_SPACE, 4f),
                key("←", HidProtocol.KEY_LEFT_ARROW, 1.1f),
                key("↑", HidProtocol.KEY_UP_ARROW, 1.1f),
                key("↓", HidProtocol.KEY_DOWN_ARROW, 1.1f),
                key("→", HidProtocol.KEY_RIGHT_ARROW, 1.1f)
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
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isClickable = true
            isFocusable = false
            includeFontPadding = false
            background = keyBackground(selected = false)
            val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, spec.weight)
            lp.setMargins(
                UiTheme.dp(context, 1.5f), UiTheme.dp(context, 1.5f),
                UiTheme.dp(context, 1.5f), UiTheme.dp(context, 1.5f)
            )
            layoutParams = lp
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // Fire on press, like a physical keyboard: waiting for
                        // ACTION_UP adds the whole finger-down duration to the
                        // perceived latency.
                        v.alpha = 0.55f
                        activate(spec)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1f
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
        val cellWidth = if (width > 0) width / 15f else 0f
        val cellHeight = if (height > 0) height / rows.toFloat() else 0f
        val density = resources.displayMetrics.density
        val fitted = if (cellWidth > 0f && cellHeight > 0f) {
            (minOf(cellWidth / 1.28f, cellHeight * 0.42f) / density).coerceIn(9f, 20f)
        } else {
            13f
        }
        for ((view, spec) in keyViews) {
            val active = spec.latched && latchedMods.contains(spec.modBit)
            view.background = keyBackground(active)
            view.setTextColor(
                when {
                    active -> if (darkTheme) 0xFF7FE6FF.toInt() else Color.WHITE
                    darkTheme -> 0xFFD8ECFF.toInt()
                    else -> UiTheme.COLOR_TEXT_PRIMARY
                }
            )
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (spec.label.length > 2) fitted * 0.82f else fitted)
            if (darkTheme && glow) {
                view.setShadowLayer(
                    UiTheme.dp(context, 10f).toFloat(), 0f, 0f, 0xFF2FC8FF.toInt()
                )
            } else {
                view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        }
    }

    /**
     * Immersive: no keycap fill at all — only the glowing glyphs sit on pure
     * black, so AMOLED pixels stay off. Latched modifiers get a faint tint.
     */
    private fun keyBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = UiTheme.dp(context, 8f).toFloat()
        setColor(
            when {
                !darkTheme && selected -> UiTheme.COLOR_ACCENT
                !darkTheme -> UiTheme.COLOR_BTN_SECONDARY_BG
                selected -> 0x332FD4FF.toInt()
                else -> Color.TRANSPARENT
            }
        )
    }

    private fun key(label: String, usage: Int, weight: Float = 1f) = KeySpec(label, usage, weight)

    private fun latch(label: String, modBit: Int, weight: Float = 1f) =
        KeySpec(label, 0, weight, modBit, latched = true)

    private fun letter(c: String): KeySpec =
        KeySpec(c, HidProtocol.strokeFor(c[0])?.usage ?: 0)

    private fun digit(c: String): KeySpec =
        KeySpec(c, HidProtocol.strokeFor(c[0])?.usage ?: 0)

    private fun char(c: String, weight: Float = 1f): KeySpec =
        KeySpec(c, HidProtocol.strokeFor(c[0])?.usage ?: 0, weight)
}
