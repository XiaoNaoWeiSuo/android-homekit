package dev.local.mihotspot.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal enum class Symbol {
    HOME, AIRDROP, PERIPHERALS, KEYBOARD, BACK, NEXT, BLUETOOTH, USB, DISPLAY,
    SOUND, NETWORK, SLIDERS, FILES, SEND, SETTINGS, LINK, CAMERA, POWER
}

internal enum class StatusTone(val foreground: Int, val background: Int) {
    IDLE(UiTheme.COLOR_TEXT_SECONDARY, UiTheme.COLOR_INPUT_BG),
    ACTIVE(UiTheme.COLOR_SUCCESS, UiTheme.COLOR_SUCCESS_LIGHT),
    WAITING(UiTheme.COLOR_ACCENT, UiTheme.COLOR_ACCENT_LIGHT),
    BUSY(UiTheme.COLOR_WARNING, UiTheme.COLOR_WARNING_LIGHT),
    ERROR(UiTheme.COLOR_DANGER, UiTheme.COLOR_DANGER_LIGHT)
}

internal data class UiStatus(val label: String, val tone: StatusTone = StatusTone.IDLE)

internal fun TextView.renderStatus(status: UiStatus) {
    if (text == status.label && currentTextColor == status.tone.foreground) return
    text = status.label
    setTextColor(status.tone.foreground)
    background = UiTheme.createPillDrawable(context, status.tone.background)
}

internal class ProductUi(private val context: Context) {
    private fun dp(value: Int) = UiTheme.dp(context, value)

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun text(value: String, size: Int = 14, color: Int = UiTheme.COLOR_TEXT_PRIMARY, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    fun status(initial: String = "读取中") =
        createStatusBadge(context, initial, StatusTone.IDLE.foreground, StatusTone.IDLE.background)

    fun heading(title: String, description: String? = null) = column().apply {
        setPadding(dp(2), dp(12), dp(2), dp(20))
        addView(text(title, 32, bold = true).apply { accessibilityHeading() })
        if (description != null) addView(text(description, 14, UiTheme.COLOR_TEXT_SECONDARY).apply {
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun TextView.accessibilityHeading() {
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    fun iconAction(symbol: Symbol, label: String, action: () -> Unit) =
        FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            contentDescription = label
            isFocusable = true
            background = UiTheme.createButtonRipple(context, UiTheme.COLOR_ACCENT_LIGHT, 0x190B6052, 16f)
            addView(SymbolView(context, symbol, UiTheme.COLOR_ACCENT), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            onHapticClick(action)
        }

    private fun symbolTile(symbol: Symbol, size: Int, color: Int, tint: Int) =
        FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginEnd = dp(12) }
            background = UiTheme.createPillDrawable(context, tint, radiusDp = 15f)
            addView(SymbolView(context, symbol, color), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }

    fun surface() = column().apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = UiTheme.createCardDrawable(context, radiusDp = 22f)
    }

    fun row(title: String, subtitle: String? = null, trailing: View? = null, symbol: Symbol? = null,
            action: (() -> Unit)? = null): LinearLayout = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(56)
        setPadding(0, dp(7), 0, dp(7))
        if (symbol != null) addView(symbolTile(symbol, 40, UiTheme.COLOR_ACCENT, UiTheme.COLOR_ACCENT_LIGHT))
        val labels = column().apply {
            addView(text(title, 15, bold = true))
            if (subtitle != null) addView(text(subtitle, 13, UiTheme.COLOR_TEXT_SECONDARY).apply {
                setPadding(0, dp(3), dp(6), 0)
            })
        }
        addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) addView(trailing)
        if (action != null) {
            addView(SymbolView(context, Symbol.NEXT, UiTheme.COLOR_TEXT_MUTED), LinearLayout.LayoutParams(dp(20), dp(20)))
            isFocusable = true
            background = UiTheme.createButtonRipple(context, Color.TRANSPARENT, 0x100B6052, 12f)
            onHapticClick(action)
        }
    }

    fun featureCard(title: String, symbol: Symbol, color: Int, tint: Int,
                    shortcut: View? = null, action: () -> Unit, body: LinearLayout.() -> Unit): View = surface().apply {
        background = UiTheme.createButtonRipple(context, UiTheme.COLOR_CARD_BG, 0x150B6052, 24f,
            UiTheme.COLOR_CARD_BORDER, 1f)
        isFocusable = true
        onHapticClick(action)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(symbolTile(symbol, 48, color, tint))
            addView(text(title, 21, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            if (shortcut != null) addView(shortcut)
            addView(SymbolView(context, Symbol.NEXT, UiTheme.COLOR_TEXT_MUTED), LinearLayout.LayoutParams(dp(20), dp(20)))
        })
        addView(column().apply {
            setPadding(0, dp(8), 0, 0)
            body()
        })
    }

    fun statusControl(label: String, status: TextView, detail: TextView, control: View): View = column().apply {
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            addView(text(label, 14, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(status, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            addView(control, LinearLayout.LayoutParams(-2, dp(48)))
        })
        addView(detail)
    }

    fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        UiTheme.styleButton(this, if (primary) UiTheme.COLOR_ACCENT else UiTheme.COLOR_ACCENT_LIGHT,
            if (primary) Color.WHITE else UiTheme.COLOR_ACCENT, radiusDp = 14f)
        minimumHeight = dp(48)
        onHapticClick(action)
    }

    fun section(label: String) = text(label, 13, UiTheme.COLOR_TEXT_SECONDARY, bold = true).apply {
        setPadding(dp(2), dp(8), 0, dp(8))
        accessibilityHeading()
    }

    fun screen(title: String?, back: (() -> Unit)? = null, body: LinearLayout.() -> Unit): View = column().apply {
        setBackgroundColor(UiTheme.COLOR_BG)
        if (title != null && back != null) addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(6))
            addView(iconAction(Symbol.BACK, "返回", back))
            addView(text(title, 19, bold = true).apply {
                setPadding(dp(12), 0, 0, 0)
                accessibilityHeading()
            })
        })
        addView(ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            addView(column().apply {
                setPadding(dp(20), dp(8), dp(20), dp(24))
                body()
            })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    }
}

/** Stores each screen with its parent so toolbar Back and Android Back use the same route. */
internal class ScreenRoutes<R : Enum<R>>(context: Context) {
    val host = FrameLayout(context)
    private data class Screen<R>(val view: View, val parent: R?)
    private val screens = mutableMapOf<R, Screen<R>>()
    var current: R? = null
        private set

    fun register(route: R, parent: R?, view: View) {
        screens[route] = Screen(view, parent)
    }

    fun show(route: R) {
        if (current == route) return
        val screen = screens.getValue(route)
        host.removeAllViews()
        host.addView(screen.view, FrameLayout.LayoutParams(-1, -1))
        current = route
    }

    fun back(): Boolean {
        val parent = screens[current]?.parent ?: return false
        show(parent)
        return true
    }
}

private class SymbolView(context: Context, private val symbol: Symbol, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = 1.65f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(width / 24f, height / 24f)
        fun line(vararg points: Float) {
            path.reset()
            path.moveTo(points[0], points[1])
            for (index in 2 until points.size step 2) path.lineTo(points[index], points[index + 1])
            canvas.drawPath(path, paint)
        }
        fun rect(left: Float, top: Float, right: Float, bottom: Float, radius: Float = 2f) =
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
        fun circle(x: Float, y: Float, radius: Float) = canvas.drawCircle(x, y, radius, paint)
        when (symbol) {
            Symbol.HOME -> {
                line(3f, 11f, 12f, 3f, 21f, 11f)
                line(5f, 10f, 5f, 21f, 19f, 21f, 19f, 10f)
                rect(10f, 14f, 14f, 21f, 0f)
            }
            Symbol.AIRDROP -> {
                canvas.drawArc(3f, 3f, 21f, 21f, 140f, 260f, false, paint)
                canvas.drawArc(6.5f, 6.5f, 17.5f, 17.5f, 155f, 230f, false, paint)
                line(7f, 21f, 12f, 13f, 17f, 21f, 7f, 21f)
            }
            Symbol.PERIPHERALS, Symbol.DISPLAY -> {
                rect(3f, 4f, 21f, 16f)
                line(12f, 16f, 12f, 21f)
                line(8f, 21f, 16f, 21f)
            }
            Symbol.KEYBOARD -> {
                rect(2.5f, 5.5f, 21.5f, 18.5f)
                for (x in 6..18 step 4) {
                    circle(x.toFloat(), 10f, .2f)
                    circle(x.toFloat(), 13f, .2f)
                }
                line(8f, 16f, 16f, 16f)
            }
            Symbol.BACK -> {
                line(11f, 5f, 4f, 12f, 11f, 19f)
                line(4f, 12f, 20f, 12f)
            }
            Symbol.NEXT -> line(9f, 6f, 15f, 12f, 9f, 18f)
            Symbol.BLUETOOTH -> line(6f, 6f, 17f, 17f, 12f, 22f, 12f, 2f, 17f, 7f, 6f, 18f)
            Symbol.USB -> {
                line(12f, 21f, 12f, 4f)
                line(9f, 7f, 12f, 4f, 15f, 7f)
                line(12f, 16f, 6f, 12f, 6f, 8f)
                line(12f, 18f, 18f, 13f, 18f, 10f)
            }
            Symbol.SOUND -> {
                line(3f, 9f, 7f, 9f, 12f, 5f, 12f, 19f, 7f, 15f, 3f, 15f, 3f, 9f)
                canvas.drawArc(10f, 6f, 21f, 18f, -60f, 120f, false, paint)
            }
            Symbol.NETWORK -> {
                canvas.drawArc(2f, 6f, 22f, 23f, 220f, 100f, false, paint)
                canvas.drawArc(6f, 10f, 18f, 22f, 220f, 100f, false, paint)
                circle(12f, 17f, 1f)
            }
            Symbol.SLIDERS, Symbol.SETTINGS -> {
                line(4f, 6f, 20f, 6f); circle(9f, 6f, 2f)
                line(4f, 12f, 20f, 12f); circle(15f, 12f, 2f)
                line(4f, 18f, 20f, 18f); circle(9f, 18f, 2f)
            }
            Symbol.FILES -> {
                line(3f, 6f, 10f, 6f, 12f, 9f, 21f, 9f, 21f, 19f, 3f, 19f, 3f, 6f)
            }
            Symbol.SEND -> {
                line(3f, 10f, 21f, 3f, 15f, 21f, 11f, 13f, 3f, 10f)
                line(11f, 13f, 21f, 3f)
            }
            Symbol.LINK -> {
                rect(3f, 7f, 11f, 17f, 3f); rect(13f, 7f, 21f, 17f, 3f)
                line(9f, 12f, 15f, 12f)
            }
            Symbol.CAMERA -> {
                rect(3f, 5f, 15f, 19f)
                line(15f, 9f, 21f, 6f, 21f, 18f, 15f, 15f)
            }
            Symbol.POWER -> {
                line(12f, 3f, 12f, 11f)
                canvas.drawArc(4f, 4f, 20f, 20f, -55f, 290f, false, paint)
            }
        }
        canvas.restore()
    }
}
