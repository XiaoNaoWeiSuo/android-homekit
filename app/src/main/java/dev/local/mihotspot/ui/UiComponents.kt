package dev.local.mihotspot.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.local.mihotspot.R

/**
 * Minimalist design system and reusable UI components aligned with modern Android / HyperOS system UI.
 */
object UiTheme {
    // Canvas & Surface
    const val COLOR_BG = 0xFFF5F6F8.toInt()
    const val COLOR_CARD_BG = 0xFFFFFFFF.toInt()
    const val COLOR_CARD_BORDER = 0xFFEBECEF.toInt()
    const val COLOR_DIVIDER = 0xFFF0F1F4.toInt()

    // Typography
    const val COLOR_TEXT_PRIMARY = 0xFF1F2329.toInt()
    const val COLOR_TEXT_SECONDARY = 0xFF646A73.toInt()
    const val COLOR_TEXT_MUTED = 0xFF8F959E.toInt()

    // Accent & Status
    const val COLOR_ACCENT = 0xFF0D84FF.toInt()
    const val COLOR_ACCENT_LIGHT = 0xFFEBF3FF.toInt()

    const val COLOR_SUCCESS = 0xFF00B578.toInt()
    const val COLOR_SUCCESS_LIGHT = 0xFFE6F8F0.toInt()

    const val COLOR_WARNING = 0xFFFF8F1F.toInt()
    const val COLOR_WARNING_LIGHT = 0xFFFFF4E6.toInt()

    const val COLOR_DANGER = 0xFFF54A45.toInt()
    const val COLOR_DANGER_LIGHT = 0xFFFEECEB.toInt()

    // Controls
    const val COLOR_INPUT_BG = 0xFFF5F6F8.toInt()
    const val COLOR_INPUT_BORDER = 0xFFE2E4E8.toInt()
    const val COLOR_BTN_SECONDARY_BG = 0xFFF0F2F5.toInt()
    const val COLOR_BTN_SECONDARY_TEXT = 0xFF1F2329.toInt()

    fun dp(context: Context, v: Float): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()

    fun dp(context: Context, v: Int): Int = dp(context, v.toFloat())

    fun createCardDrawable(
        context: Context,
        radiusDp: Float = 16f,
        bgColor: Int = COLOR_CARD_BG,
        strokeColor: Int = COLOR_CARD_BORDER,
        strokeWidthDp: Float = 0.8f
    ): Drawable = GradientDrawable().apply {
        cornerRadius = dp(context, radiusDp).toFloat()
        setColor(bgColor)
        setStroke(dp(context, strokeWidthDp), strokeColor)
    }

    fun createPillDrawable(
        context: Context,
        bgColor: Int,
        strokeColor: Int = 0,
        strokeWidthDp: Float = 0f,
        radiusDp: Float = 999f
    ): Drawable = GradientDrawable().apply {
        cornerRadius = dp(context, radiusDp).toFloat()
        setColor(bgColor)
        if (strokeColor != 0 && strokeWidthDp > 0f) {
            setStroke(dp(context, strokeWidthDp), strokeColor)
        }
    }

    fun createButtonRipple(
        context: Context,
        normalColor: Int,
        pressedColor: Int,
        radiusDp: Float = 10f,
        strokeColor: Int = 0,
        strokeWidthDp: Float = 0f
    ): Drawable {
        val content = GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(normalColor)
            if (strokeColor != 0 && strokeWidthDp > 0f) {
                setStroke(dp(context, strokeWidthDp), strokeColor)
            }
        }
        val mask = GradientDrawable().apply {
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(pressedColor), content, mask)
    }

    fun styleButton(
        button: Button,
        bgColor: Int,
        textColor: Int,
        radiusDp: Float = 10f,
        strokeColor: Int = 0,
        strokeWidthDp: Float = 0f,
        horizontalPaddingDp: Float = 10f,
        verticalPaddingDp: Float = 0f
    ) {
        val pressedColor = adjustAlpha(if (textColor == Color.WHITE) Color.WHITE else Color.BLACK, 0.15f)
        button.background = createButtonRipple(button.context, bgColor, pressedColor, radiusDp, strokeColor, strokeWidthDp)
        button.setTextColor(textColor)
        button.isAllCaps = false
        button.minHeight = 0
        button.minWidth = 0
        button.minimumHeight = 0
        button.minimumWidth = 0
        button.stateListAnimator = null
        button.includeFontPadding = true
        button.gravity = Gravity.CENTER
        val pH = dp(button.context, horizontalPaddingDp)
        val pV = dp(button.context, verticalPaddingDp)
        button.setPadding(pH, pV, pH, pV)
    }

    fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = Math.round(Color.alpha(color) * factor)
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return Color.argb(alpha, red, green, blue)
    }
}

/**
 * Creates the minimalist "?" question-mark help bubble.
 * Tapping it pops up a system dialog with the explanation text.
 */
fun createHelpBubble(
    context: Context,
    title: String,
    message: String
): View {
    val size = UiTheme.dp(context, 18)
    val touchSize = UiTheme.dp(context, 34)

    val frame = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(touchSize, touchSize).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        isClickable = true
        isFocusable = true
        contentDescription = "$title 说明"
    }

    val bubble = TextView(context).apply {
        text = "?"
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(UiTheme.COLOR_TEXT_SECONDARY)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFE8EAEE.toInt())
        }
        val lp = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER
        }
        layoutParams = lp
    }
    frame.addView(bubble)

    frame.setOnClickListener {
        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("我知道了", null)
            .show()
    }
    return frame
}

/**
 * Creates a section header row with optional "?" help bubble and optional right-aligned view.
 */
fun createSectionHeader(
    context: Context,
    title: String,
    helpTitle: String? = null,
    helpMessage: String? = null,
    rightView: View? = null
): View = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    val lp = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        setMargins(UiTheme.dp(context, 20), UiTheme.dp(context, 16), UiTheme.dp(context, 20), UiTheme.dp(context, 6))
    }
    layoutParams = lp

    val titleTv = TextView(context).apply {
        text = title
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(UiTheme.COLOR_TEXT_MUTED)
    }
    addView(titleTv)

    if (helpMessage != null) {
        val bubble = createHelpBubble(context, helpTitle ?: "$title 说明", helpMessage)
        addView(bubble)
    }

    if (rightView != null) {
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        addView(spacer)
        addView(rightView)
    }
}

/**
 * Creates a rounded card container with standard system padding and margins.
 */
fun createCard(
    context: Context,
    verticalPaddingDp: Float = 14f,
    horizontalPaddingDp: Float = 16f
): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = UiTheme.createCardDrawable(context, radiusDp = 16f)
    val pV = UiTheme.dp(context, verticalPaddingDp)
    val pH = UiTheme.dp(context, horizontalPaddingDp)
    setPadding(pH, pV, pH, pV)
    val lp = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        val mH = UiTheme.dp(context, 16)
        val mV = UiTheme.dp(context, 4)
        setMargins(mH, mV, mH, mV)
    }
    layoutParams = lp
}

/**
 * Creates a subtle card divider.
 */
fun createCardDivider(
    context: Context,
    insetHorizontalDp: Float = 0f
): View = View(context).apply {
    setBackgroundColor(UiTheme.COLOR_DIVIDER)
    val lp = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        UiTheme.dp(context, 0.8f).coerceAtLeast(1)
    ).apply {
        val inset = UiTheme.dp(context, insetHorizontalDp)
        setMargins(inset, 0, inset, 0)
    }
    layoutParams = lp
}

/**
 * Creates a status badge pill (e.g. "已连接", "已配对").
 */
fun createStatusBadge(
    context: Context,
    text: String,
    textColor: Int,
    bgColor: Int
): TextView = TextView(context).apply {
    this.text = text
    textSize = 11f
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(textColor)
    includeFontPadding = true
    val pH = UiTheme.dp(context, 8)
    val pV = UiTheme.dp(context, 4)
    setPadding(pH, pV, pH, pV)
    background = UiTheme.createPillDrawable(context, bgColor)
}

/**
 * Attaches a click listener with standard virtual key haptic feedback.
 */
fun View.onHapticClick(action: () -> Unit) {
    setOnClickListener {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        action()
    }
}

/**
 * Standard top header bar with back button, title, optional status pill, and optional right action view.
 */
fun createPageHeader(
    context: Context,
    titleText: String,
    onBackClick: () -> Unit,
    subtitleBadge: View? = null,
    rightView: View? = null
): View = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    val pV = UiTheme.dp(context, 12)
    val pH = UiTheme.dp(context, 16)
    setPadding(pH, pV, pH, UiTheme.dp(context, 8))

    val backBtn = FrameLayout(context).apply {
        val size = UiTheme.dp(context, 40)
        layoutParams = LinearLayout.LayoutParams(size, size)
        background = UiTheme.createButtonRipple(
            context,
            UiTheme.COLOR_CARD_BG,
            UiTheme.adjustAlpha(Color.BLACK, 0.15f),
            radiusDp = 20f
        )
        isClickable = true
        isFocusable = true
        val icon = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            val iconSize = UiTheme.dp(context, 22)
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
        }
        addView(icon)
        onHapticClick { onBackClick() }
        contentDescription = "返回"
    }
    addView(backBtn)

    val titleCol = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = UiTheme.dp(context, 12)
        }
        layoutParams = lp
    }

    val titleTv = TextView(context).apply {
        text = titleText
        textSize = 19f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
    }
    titleCol.addView(titleTv)

    if (subtitleBadge != null) {
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(UiTheme.dp(context, 8), 1)
        }
        titleCol.addView(spacer)
        titleCol.addView(subtitleBadge)
    }
    addView(titleCol)

    if (rightView != null) {
        addView(rightView)
    }
}

