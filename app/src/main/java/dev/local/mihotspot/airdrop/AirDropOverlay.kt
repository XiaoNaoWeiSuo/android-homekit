package dev.local.mihotspot.airdrop

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.local.mihotspot.ui.UiTheme
import java.io.File

/**
 * 系统级悬浮窗：收到 AirDrop 时在任意应用之上弹出，带接收进度。
 *
 * 这是"和 iPhone 一样"体验的核心 —— 文件来的时候用户可能正在别的应用里，
 * 通知栏提示太弱，必须像系统 AirDrop 那样直接浮在最上层。
 *
 * 三种形态共用同一个窗口，内容整体重建（视图层级很简单，重建比做 diff 更不容易出错）：
 *
 * * **确认** —— 谁发的、几个文件、接收/拒绝；
 * * **进度** —— 进度条 + 已接收字节（chunked 上传没有总长，用不确定进度条）；
 * * **结果** —— 已保存的文件，点一下直接打开。
 *
 * 没有悬浮窗权限时 [isAvailable] 为 false，调用方必须退回通知路径 —— 不能静默失败。
 */
object AirDropOverlay {
    private const val TAG = "AirDropOverlay"

    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var dismissAt = 0L
    private var lastRenderKey = ""

    /** 是否有权限画在其他应用之上。 */
    fun isAvailable(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 预览悬浮窗：用一条示例数据把"收到文件"的卡片显示几秒。
     *
     * 存在的理由很实际 —— 悬浮窗权限只能在系统设置里授予，用户（和调试者）需要一个
     * 不依赖真机传输就能确认"权限生效了、样式正常"的入口。它不读写 [AirDropState]，
     * 所以不会污染真实的传输状态。
     *
     * @return false 表示没有权限，调用方应引导用户去授权。
     */
    fun preview(context: Context): Boolean {
        if (!isAvailable(context)) return false
        val sample = AirDropEvents.Approval(
            token = "preview",
            sender = "Andrew's MacBook Pro",
            files = listOf(AirDropEvents.RequestFile("IMG_4821.HEIC", 3_400_000)),
            total = 3_400_000
        )
        main.post {
            show(context, buildApproval(context, sample, preview = true))
            // 预览不阻塞任何东西，几秒后自己收起。
            main.postDelayed({ if (lastRenderKey.isEmpty()) hide() }, 6_000)
        }
        return true
    }

    /**
     * 按当前 [AirDropState] 刷新悬浮窗。没有可展示的内容时自动收起。
     *
     * 每次状态变化都调用（`AirDropState.addListener`），内部用渲染键去重，
     * 避免每 250 ms 的进度事件都重建视图。
     */
    fun render(context: Context) {
        if (!AirDropStore.overlayEnabled(context) || !isAvailable(context)) {
            hide()
            return
        }
        val phase = AirDropState.phase
        val approval = AirDropState.approval
        val progress = AirDropState.progress
        val received = AirDropState.lastReceived
        val error = AirDropState.error

        val key = when {
            approval != null -> "ask:${approval.token}"
            phase == AirDropState.Phase.RECEIVING && progress != null ->
                "rx:${progress.done / 4096}:${progress.total}"
            phase == AirDropState.Phase.SENDING && progress != null ->
                "tx:${progress.done / 4096}:${progress.total}"
            phase == AirDropState.Phase.DONE -> "done:${received.size}:${received.firstOrNull()?.path}"
            phase == AirDropState.Phase.ERROR -> "err:$error"
            else -> ""
        }
        if (key.isEmpty()) {
            hide()
            return
        }
        if (key == lastRenderKey && root != null) return
        lastRenderKey = key

        main.post {
            val view = when {
                approval != null -> buildApproval(context, approval)
                phase == AirDropState.Phase.RECEIVING -> buildProgress(context, "正在接收", progress)
                phase == AirDropState.Phase.SENDING -> buildProgress(context, sendLabel(), progress)
                phase == AirDropState.Phase.DONE -> buildResult(context, received)
                else -> buildError(context, error ?: "传输失败")
            }
            show(context, view)
            scheduleDismiss(context)
        }
    }

    private fun sendLabel(): String = when (AirDropState.sendStage) {
        "discover" -> "正在查找对方…"
        "ask" -> "等待对方接受…"
        else -> "正在发送"
    }

    private fun show(context: Context, content: View) {
        val wm = windowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {
            windowManager = it
        }
        val layoutParams = params ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // API 26+ 必须用 APPLICATION_OVERLAY；旧常量在 O 上会被拒。
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = UiTheme.dp(context, 56)
        }.also { params = it }

        val wrapper = (root as? LinearLayout) ?: LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UiTheme.dp(context, 12), 0, UiTheme.dp(context, 12), 0)
        }.also { root = it }

        wrapper.removeAllViews()
        wrapper.addView(content)

        try {
            if (wrapper.parent == null) {
                wm.addView(wrapper, layoutParams)
            } else {
                wm.updateViewLayout(wrapper, layoutParams)
            }
        } catch (error: Exception) {
            // 权限在运行中被撤销，或者窗口已被系统回收：静默降级到通知。
            android.util.Log.w(TAG, "overlay add/update failed: ${error.message}")
            root = null
            windowManager = null
            params = null
        }
    }

    fun hide() {
        main.post {
            lastRenderKey = ""
            val view = root ?: return@post
            runCatching { windowManager?.removeView(view) }
            root = null
            windowManager = null
            params = null
        }
    }

    /** 结果/错误自动消失；确认和进度不设超时（进度可能跑很久）。 */
    private fun scheduleDismiss(context: Context) {
        val phase = AirDropState.phase
        val keep = AirDropState.approval != null ||
            phase == AirDropState.Phase.RECEIVING ||
            phase == AirDropState.Phase.SENDING
        val now = System.currentTimeMillis()
        if (keep) {
            dismissAt = 0
            return
        }
        if (dismissAt == 0L) {
            dismissAt = now + 4_000
            main.postDelayed({ if (System.currentTimeMillis() >= dismissAt) hide() }, 4_000)
        }
    }

    // ---- 三种内容 ----

    private fun buildApproval(
        context: Context,
        approval: AirDropEvents.Approval,
        preview: Boolean = false
    ): View {
        val card = card(context, UiTheme.COLOR_ACCENT)
        card.addView(label(context, if (preview) "AirDrop · 预览" else "AirDrop", 12f, UiTheme.COLOR_ACCENT, bold = true))
        card.addView(label(context, approval.sender, 17f, UiTheme.COLOR_TEXT_PRIMARY, bold = true, topDp = 2))

        val summary = if (approval.files.size == 1) {
            approval.files.first().name
        } else {
            "${approval.files.size} 个文件 · ${AirDropStore.formatSize(approval.total)}"
        }
        card.addView(label(context, summary, 13f, UiTheme.COLOR_TEXT_SECONDARY, topDp = 4))
        if (approval.files.size > 1) {
            val names = approval.files.take(3).joinToString("、") { it.name }
            card.addView(label(context, names, 12f, UiTheme.COLOR_TEXT_MUTED, topDp = 2))
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, UiTheme.dp(context, 12), 0, 0)
        }
        val reject = Button(context).apply {
            text = "拒绝"
            textSize = 14f
            UiTheme.styleButton(this, UiTheme.COLOR_BTN_SECONDARY_BG, UiTheme.COLOR_TEXT_SECONDARY, radiusDp = 10f)
            setOnClickListener {
                if (preview) hide() else decide(context, approval.token, false)
            }
        }
        val accept = Button(context).apply {
            text = "接收"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            UiTheme.styleButton(this, UiTheme.COLOR_ACCENT, Color.WHITE, radiusDp = 10f)
            setOnClickListener {
                if (preview) hide() else decide(context, approval.token, true)
            }
        }
        row.addView(reject, LinearLayout.LayoutParams(UiTheme.dp(context, 84), UiTheme.dp(context, 40)).apply {
            marginEnd = UiTheme.dp(context, 8)
        })
        row.addView(accept, LinearLayout.LayoutParams(UiTheme.dp(context, 96), UiTheme.dp(context, 40)))
        card.addView(row)
        return card
    }

    private fun buildProgress(
        context: Context,
        title: String,
        snapshot: AirDropEvents.Progress?
    ): View {
        val card = card(context, UiTheme.COLOR_ACCENT)
        card.addView(label(context, title, 15f, UiTheme.COLOR_TEXT_PRIMARY, bold = true))
        val subtitle = AirDropState.peerName.ifBlank { AirDropState.sendingName }
        if (subtitle.isNotBlank()) {
            card.addView(label(context, subtitle, 12f, UiTheme.COLOR_TEXT_SECONDARY, topDp = 2))
        }
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            val fraction = snapshot?.fraction
            if (fraction == null) {
                // chunked 上传没有总长 —— 不确定进度条比一条假进度诚实。
                isIndeterminate = true
            } else {
                progress = (fraction * 1000).toInt()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                UiTheme.dp(context, 6)
            ).apply { topMargin = UiTheme.dp(context, 10) }
        }
        card.addView(bar)
        card.addView(label(context, progressText(snapshot), 12f, UiTheme.COLOR_TEXT_MUTED, topDp = 6))
        return card
    }

    private fun progressText(progress: AirDropEvents.Progress?): String {
        if (progress == null) return "准备中…"
        return if (progress.indeterminate) {
            "已传输 ${AirDropStore.formatSize(progress.done)}"
        } else {
            "${AirDropStore.formatSize(progress.done)} / ${AirDropStore.formatSize(progress.total)}"
        }
    }

    private fun buildResult(context: Context, files: List<AirDropEvents.ReceivedFile>): View {
        val card = card(context, UiTheme.COLOR_SUCCESS)
        card.addView(label(context, "已接收", 15f, UiTheme.COLOR_SUCCESS, bold = true))
        files.take(3).forEach { file ->
            val row = TextView(context).apply {
                text = file.name
                textSize = 13f
                setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(0, UiTheme.dp(context, 4), 0, 0)
                setOnClickListener { open(context, file) }
            }
            card.addView(row)
        }
        if (files.size > 3) {
            card.addView(label(context, "等 ${files.size} 个文件", 12f, UiTheme.COLOR_TEXT_MUTED, topDp = 2))
        }
        val open = Button(context).apply {
            text = "查看"
            textSize = 13f
            UiTheme.styleButton(this, UiTheme.COLOR_SUCCESS_LIGHT, UiTheme.COLOR_SUCCESS, radiusDp = 10f)
            setOnClickListener {
                context.startActivity(Intent(context, AirDropActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                hide()
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, UiTheme.dp(context, 10), 0, 0)
        }
        row.addView(open, LinearLayout.LayoutParams(UiTheme.dp(context, 96), UiTheme.dp(context, 38)))
        card.addView(row)
        return card
    }

    private fun buildError(context: Context, message: String): View {
        val card = card(context, UiTheme.COLOR_DANGER)
        card.addView(label(context, "AirDrop 失败", 15f, UiTheme.COLOR_DANGER, bold = true))
        card.addView(label(context, message.take(200), 12f, UiTheme.COLOR_TEXT_SECONDARY, topDp = 4))
        return card
    }

    // ---- 视图小工具 ----

    private fun card(context: Context, accent: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = UiTheme.dp(context, 18).toFloat()
            setColor(UiTheme.COLOR_CARD_BG)
            setStroke(UiTheme.dp(context, 1), UiTheme.COLOR_CARD_BORDER)
        }
        setPadding(UiTheme.dp(context, 18), UiTheme.dp(context, 14), UiTheme.dp(context, 18), UiTheme.dp(context, 12))
        // 左侧一道强调色，让悬浮窗在任意背景上都能被一眼认出来。
        val stripe = View(context).apply { setBackgroundColor(accent) }
        addView(stripe, LinearLayout.LayoutParams(UiTheme.dp(context, 40), UiTheme.dp(context, 3)).apply {
            bottomMargin = UiTheme.dp(context, 8)
        })
    }

    private fun label(
        context: Context,
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        topDp: Int = 0
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (topDp > 0) setPadding(0, UiTheme.dp(context, topDp), 0, 0)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun decide(context: Context, token: String, accepted: Boolean) {
        runCatching { AirDropStore.decision(context, token, accepted) }
        // 立刻收起确认卡片：后端会在 100 ms 内看到 decision 文件并开始收数据。
        AirDropState.onDecision(token, accepted)
        hide()
    }

    private fun open(context: Context, file: AirDropEvents.ReceivedFile) {
        runCatching {
            val local = File(file.path)
            val uri = Uri.Builder().scheme("content")
                .authority("dev.local.mihotspot.airdrop.files")
                .appendPath(local.name)
                .build()
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, AirDropFilesProvider.mime(local.name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        hide()
    }
}
