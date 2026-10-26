package dev.local.mihotspot.airdrop

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.local.mihotspot.R
import dev.local.mihotspot.homekit.commands.BoundedShell
import dev.local.mihotspot.ui.UiTheme
import dev.local.mihotspot.ui.createCard
import dev.local.mihotspot.ui.createCardDivider
import dev.local.mihotspot.ui.createHelpBubble
import dev.local.mihotspot.ui.createPageHeader
import dev.local.mihotspot.ui.createSectionHeader
import dev.local.mihotspot.ui.createStatusBadge
import dev.local.mihotspot.ui.onHapticClick
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AirDropActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var showingRequest: String? = null
    private var dialog: AlertDialog? = null
    private var fileStamp = ""

    // UI elements
    private lateinit var residentSwitch: Switch
    private lateinit var residentBadge: TextView
    private lateinit var residentDetailText: TextView
    private lateinit var overlaySwitch: Switch

    private lateinit var sessionStatusBadge: TextView
    private lateinit var sessionStatusText: TextView
    private lateinit var sessionStartBtn: Button
    private lateinit var sendFileBtn: Button

    private lateinit var filesContainer: LinearLayout
    private lateinit var emptyFilesView: View

    private var updatingResidentSwitch = false
    private var updatingOverlaySwitch = false
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private val refresh = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                updateResidentUi()
                updateSessionUi()
                showPendingRequest()
                refreshFiles()
                handler.postDelayed(this, 1000)
            }
        }
    }

    private fun dp(v: Float): Int = UiTheme.dp(this, v)
    private fun dp(v: Int): Int = dp(v.toFloat())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        updateResidentUi()
        updateSessionUi()
        refreshFiles()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.COLOR_BG)
        }

        // Top Header (pinned at top)
        val header = createPageHeader(
            context = this,
            titleText = "AirDrop 文件收发",
            onBackClick = { finish() },
            subtitleBadge = createStatusBadge(this, "实验版", UiTheme.COLOR_ACCENT, UiTheme.COLOR_ACCENT_LIGHT)
        )
        root.addView(header)

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.COLOR_BG)
            setPadding(0, 0, 0, dp(32))
        }

        // Section 1: 常驻待机服务
        val residentHelp = "常驻服务会一直保持 AirDrop 就绪：开机后自动恢复，收到文件时可直接确认接收，无需先打开本应用。"
        scrollContent.addView(createSectionHeader(this, "常驻待机服务", "常驻服务说明", residentHelp))

        val residentCard = createCard(this, verticalPaddingDp = 14f, horizontalPaddingDp = 16f)

        val residentTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val residentTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val residentTitle = TextView(this).apply {
            text = "开机常驻待机"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
        }
        residentTextCol.addView(residentTitle)

        val residentSub = TextView(this).apply {
            text = "开机自动恢复 · 收到文件时弹出确认与进度"
            textSize = 12f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            setPadding(0, dp(2), 0, 0)
        }
        residentTextCol.addView(residentSub)
        residentTopRow.addView(residentTextCol)

        residentSwitch = Switch(this).apply {
            isChecked = AirDropResidentService.isEnabled(this@AirDropActivity)
            setOnCheckedChangeListener { _, isChecked ->
                if (updatingResidentSwitch) return@setOnCheckedChangeListener
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (isChecked) {
                    // 常驻 = 引擎一直活着；顺带提示悬浮窗权限，否则收到文件时
                    // 只能退回通知（功能可用，但体验不像 AirDrop）。
                    AirDropResidentService.enable(this@AirDropActivity)
                    if (!AirDropOverlay.isAvailable(this@AirDropActivity)) requestOverlayPermission()
                } else {
                    AirDropResidentService.disable(this@AirDropActivity)
                }
                handler.postDelayed({ updateResidentUi() }, 200)
            }
        }
        residentTopRow.addView(residentSwitch)
        residentCard.addView(residentTopRow)

        residentCard.addView(createCardDivider(this))

        // 悬浮窗开关：这是"收到文件时全家弹出"的体验开关。
        val overlayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val overlayCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        overlayCol.addView(TextView(this@AirDropActivity).apply {
            text = "接收时弹出悬浮窗"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
        })
        overlayCol.addView(TextView(this@AirDropActivity).apply {
            text = "在任何应用之上显示发送方与接收进度"
            textSize = 12f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            setPadding(0, dp(2), 0, 0)
        })
        overlayRow.addView(overlayCol)
        overlaySwitch = Switch(this).apply {
            isChecked = AirDropStore.overlayEnabled(this@AirDropActivity) &&
                AirDropOverlay.isAvailable(this@AirDropActivity)
            setOnCheckedChangeListener { _, checked ->
                if (updatingOverlaySwitch) return@setOnCheckedChangeListener
                if (checked && !AirDropOverlay.isAvailable(this@AirDropActivity)) {
                    requestOverlayPermission()
                    return@setOnCheckedChangeListener
                }
                AirDropStore.overlayEnabled(this@AirDropActivity, checked)
                if (!checked) AirDropOverlay.hide()
            }
        }
        overlayRow.addView(overlaySwitch)
        residentCard.addView(overlayRow)

        // 悬浮窗权限只能去系统设置里授予，所以给一个"现在就看看效果"的入口：
        // 用户不必等真有人发文件才知道有没有配对。
        val previewOverlayBtn = Button(this).apply {
            text = "预览悬浮窗样式"
            textSize = 13f
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply {
                topMargin = dp(4)
            }
            layoutParams = lp
            UiTheme.styleButton(this, UiTheme.COLOR_INPUT_BG, UiTheme.COLOR_TEXT_SECONDARY, radiusDp = 10f)
            onHapticClick {
                if (!AirDropOverlay.preview(this@AirDropActivity)) requestOverlayPermission()
            }
        }
        residentCard.addView(previewOverlayBtn)

        residentCard.addView(createCardDivider(this))

        val residentStatusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(UiTheme.COLOR_INPUT_BG)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
            layoutParams = lp
        }

        val residentStatusHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val residentStatusLabel = TextView(this).apply {
            text = "待机状态："
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_TEXT_SECONDARY)
        }
        residentStatusHeader.addView(residentStatusLabel)

        residentBadge = createStatusBadge(this, "○ 未开启", UiTheme.COLOR_TEXT_MUTED, UiTheme.COLOR_INPUT_BG)
        residentStatusHeader.addView(residentBadge)
        residentStatusBox.addView(residentStatusHeader)

        residentDetailText = TextView(this).apply {
            text = AirDropResidentService.status(this@AirDropActivity)
            textSize = 12f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            setPadding(0, dp(6), 0, 0)
        }
        residentStatusBox.addView(residentDetailText)
        residentCard.addView(residentStatusBox)
        scrollContent.addView(residentCard)

        // Section 2: 限时会话
        val sessionHelp = "启动 10 分钟临时会话，到点自动停止。\n\n• 与普通 Wi-Fi 共存，不会断开你正在用的网络（需要先连上一个 Wi-Fi）\n• 热点与 AirDrop 争用同一颗芯片，开启前请先关闭热点\n• 苹果设备需开启「隔空投送」并设置为「所有人」\n• 每次接收文件需在此确认"
        scrollContent.addView(createSectionHeader(this, "限时会话", "限时会话说明", sessionHelp))

        val sessionCard = createCard(this, verticalPaddingDp = 14f, horizontalPaddingDp = 16f)

        val sessionStatusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val sessionTitleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val sessionTitle = TextView(this).apply {
            text = "传输会话状态"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
        }
        sessionTitleCol.addView(sessionTitle)

        val sessionSub = TextView(this).apply {
            text = "最长 10 分钟 · 与普通 Wi-Fi 共存"
            textSize = 12f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            setPadding(0, dp(2), 0, 0)
        }
        sessionTitleCol.addView(sessionSub)
        sessionStatusRow.addView(sessionTitleCol)

        sessionStatusBadge = createStatusBadge(this, "○ 空闲", UiTheme.COLOR_TEXT_MUTED, UiTheme.COLOR_INPUT_BG)
        sessionStatusRow.addView(sessionStatusBadge)
        sessionCard.addView(sessionStatusRow)

        val sessionFeedbackBox = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(UiTheme.COLOR_INPUT_BG)
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
                bottomMargin = dp(4)
            }
            layoutParams = lp
        }

        sessionStatusText = TextView(this).apply {
            text = AirDropStore.status(this@AirDropActivity)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(UiTheme.COLOR_TEXT_SECONDARY)
        }
        sessionFeedbackBox.addView(sessionStatusText)
        sessionCard.addView(sessionFeedbackBox)

        sessionCard.addView(createCardDivider(this))

        // Session Action Buttons
        sessionStartBtn = Button(this).apply {
            text = "开启 AirDrop · 10 分钟"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
                topMargin = dp(6)
            }
            layoutParams = lp
            UiTheme.styleButton(this, UiTheme.COLOR_ACCENT, Color.WHITE, radiusDp = 12f)
            onHapticClick {
                val isSessionActive = AirDropService.isRunning(this@AirDropActivity)
                if (isSessionActive) {
                    AirDropService.stop(this@AirDropActivity)
                    AirDropStore.status(this@AirDropActivity, "正在停止会话…")
                    updateSessionUi()
                } else {
                    AlertDialog.Builder(this@AirDropActivity)
                        .setTitle("开启 AirDrop 收发")
                        .setMessage("AirDrop 与普通 Wi-Fi 共存：你正在用的网络不会断开，但需要手机已经连上一个 Wi-Fi。每次接收文件都需要你确认。\n\n请先关闭手机热点；苹果设备请设为「所有人，10 分钟」。")
                        .setPositiveButton("开启") { _, _ ->
                            AirDropStore.status(this@AirDropActivity, "正在启动…")
                            AirDropService.startWindow(this@AirDropActivity)
                            updateSessionUi()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
        sessionCard.addView(sessionStartBtn)

        sendFileBtn = Button(this).apply {
            text = "选择文件发送"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
                topMargin = dp(8)
            }
            layoutParams = lp
            UiTheme.styleButton(this, UiTheme.COLOR_BTN_SECONDARY_BG, UiTheme.COLOR_TEXT_PRIMARY, radiusDp = 12f)
            onHapticClick {
                // 不在这里判断会话是否开启：分享页会自己把引擎拉起来，
                // 和系统分享面板走的是同一条路径。
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    },
                    7410
                )
            }
        }
        sessionCard.addView(sendFileBtn)

        val diagnosticsBtn = Button(this).apply {
            text = "检测设备 / 查看诊断日志"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply {
                topMargin = dp(8)
            }
            layoutParams = lp
            UiTheme.styleButton(this, UiTheme.COLOR_BTN_SECONDARY_BG, UiTheme.COLOR_TEXT_SECONDARY, radiusDp = 12f)
            onHapticClick { diagnostics() }
        }
        sessionCard.addView(diagnosticsBtn)

        scrollContent.addView(sessionCard)

        // Section 3: 收到的文件
        val filesHelp = "收到的文件存放在内部存储「下载/AirDrop」目录下。停止会话后仍可随时查看和调用应用打开。"
        scrollContent.addView(createSectionHeader(this, "收到的文件", "文件说明", filesHelp))

        val receivedCard = createCard(this, verticalPaddingDp = 10f, horizontalPaddingDp = 16f)
        filesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        emptyFilesView = buildEmptyFilesView()
        filesContainer.addView(emptyFilesView)
        receivedCard.addView(filesContainer)
        scrollContent.addView(receivedCard)

        // Section 4: 关于与开源协议
        scrollContent.addView(createSectionHeader(this, "关于与协议"))

        val aboutCard = createCard(this, verticalPaddingDp = 14f, horizontalPaddingDp = 16f)
        val aboutText = TextView(this).apply {
            text = "后端核心：opendrop-rs / OWL（GPLv3 协议）\n" +
                "单次发送上限 ${AirDropStore.formatSize(AirDropStore.MAX_SEND_BYTES)}，" +
                "单次接收上限 128 MiB；\n" +
                "文件接收至本地后停止会话仍可永久保留与打开。\n" +
                "分享面板与悬浮窗由 Marionette 提供。"
            textSize = 12f
            setLineSpacing(dp(4).toFloat(), 1.0f)
            setTextColor(UiTheme.COLOR_TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(10))
        }
        aboutCard.addView(aboutText)
        aboutCard.addView(createCardDivider(this))

        val licenseBtn = Button(this).apply {
            text = "开源后端与许可证"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply {
                topMargin = dp(8)
            }
            layoutParams = lp
            UiTheme.styleButton(this, UiTheme.COLOR_BTN_SECONDARY_BG, UiTheme.COLOR_TEXT_PRIMARY, radiusDp = 12f)
            onHapticClick {
                val licenseText = assets.open("airdrop/LICENSE.txt").bufferedReader().use { it.readText() }
                AlertDialog.Builder(this@AirDropActivity)
                    .setTitle("opendrop-rs · GPLv3")
                    .setMessage("来源：https://github.com/ayourtch-llm/opendrop-rs\n版本：dccc798e244363eb92d35e3c52e9a913188dda91\n本项目修改后的完整源码：native/airdrop\n\n$licenseText")
                    .setPositiveButton("关闭", null)
                    .show()
            }
        }
        aboutCard.addView(licenseBtn)
        scrollContent.addView(aboutCard)

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(UiTheme.COLOR_BG)
            addView(scrollContent)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(scrollView)

        return root
    }

    private fun updateResidentUi() {
        val enabled = AirDropResidentService.isEnabled(this)
        val status = AirDropResidentService.status(this)

        updatingResidentSwitch = true
        if (residentSwitch.isChecked != enabled) {
            residentSwitch.isChecked = enabled
        }
        updatingResidentSwitch = false

        val overlayOk = AirDropOverlay.isAvailable(this)
        updatingOverlaySwitch = true
        val wantOverlay = AirDropStore.overlayEnabled(this) && overlayOk
        if (overlaySwitch.isChecked != wantOverlay) overlaySwitch.isChecked = wantOverlay
        updatingOverlaySwitch = false

        val phase = AirDropState.phase
        when {
            !enabled -> {
                residentBadge.text = "○ 未开启"
                residentBadge.setTextColor(UiTheme.COLOR_TEXT_MUTED)
                residentBadge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_INPUT_BG)
            }
            phase == AirDropState.Phase.READY || phase == AirDropState.Phase.SEEN ||
                phase == AirDropState.Phase.RECEIVING || phase == AirDropState.Phase.AWAITING -> {
                residentBadge.text = "● 运行中"
                residentBadge.setTextColor(UiTheme.COLOR_SUCCESS)
                residentBadge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_SUCCESS_LIGHT)
            }
            phase == AirDropState.Phase.ERROR -> {
                residentBadge.text = "● 异常"
                residentBadge.setTextColor(UiTheme.COLOR_DANGER)
                residentBadge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_DANGER_LIGHT)
            }
            else -> {
                residentBadge.text = "● 启动中"
                residentBadge.setTextColor(UiTheme.COLOR_WARNING)
                residentBadge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_WARNING_LIGHT)
            }
        }
        residentDetailText.text = buildString {
            append(status)
            if (enabled && !overlayOk) append("\n未授予悬浮窗权限，接收确认将改用通知。")
        }
    }

    /**
     * 申请「在其他应用上层显示」权限。
     *
     * 这个权限只能由用户在系统设置里授予，没有运行时弹窗；返回后由每秒刷新的
     * [refresh] 自动同步开关状态。
     */
    private fun requestOverlayPermission() {
        if (AirDropOverlay.isAvailable(this)) return
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
            )
            message("请允许「在其他应用上层显示」，这样收到文件时才能像系统 AirDrop 一样弹出确认与进度。")
        } catch (_: Exception) {
            message("当前系统无法打开悬浮窗权限设置，接收确认将改用通知。")
        }
    }

    private fun updateSessionUi() {
        val isSessionActive = AirDropService.isRunning(this)
        val status = AirDropStore.status(this)

        sessionStatusText.text = status

        when {
            isSessionActive -> {
                sessionStatusBadge.text = "● 实验中"
                sessionStatusBadge.setTextColor(UiTheme.COLOR_SUCCESS)
                sessionStatusBadge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_SUCCESS_LIGHT)

                sessionStartBtn.text = "停止会话"
                UiTheme.styleButton(sessionStartBtn, UiTheme.COLOR_DANGER_LIGHT, UiTheme.COLOR_DANGER, radiusDp = 12f)
            }
            status.startsWith("正在") || status.startsWith("准备中") -> {
                sessionStatusBadge.text = "● 启动中"
                sessionStatusBadge.setTextColor(UiTheme.COLOR_WARNING)
                sessionStatusBadge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_WARNING_LIGHT)

                sessionStartBtn.text = "正在启动中…"
                UiTheme.styleButton(sessionStartBtn, UiTheme.COLOR_BTN_SECONDARY_BG, UiTheme.COLOR_TEXT_MUTED, radiusDp = 12f)
            }
            status.contains("失败") || status.contains("异常") -> {
                sessionStatusBadge.text = "● 启动异常"
                sessionStatusBadge.setTextColor(UiTheme.COLOR_DANGER)
                sessionStatusBadge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_DANGER_LIGHT)

                sessionStartBtn.text = "开启 AirDrop · 10 分钟"
                UiTheme.styleButton(sessionStartBtn, UiTheme.COLOR_ACCENT, Color.WHITE, radiusDp = 12f)
            }
            else -> {
                sessionStatusBadge.text = "○ 空闲"
                sessionStatusBadge.setTextColor(UiTheme.COLOR_TEXT_MUTED)
                sessionStatusBadge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_INPUT_BG)

                sessionStartBtn.text = "开启 AirDrop · 10 分钟"
                UiTheme.styleButton(sessionStartBtn, UiTheme.COLOR_ACCENT, Color.WHITE, radiusDp = 12f)
            }
        }
    }

    private fun showPendingRequest() {
        // 悬浮窗可用时由它负责确认；这里只作为退路，避免同一请求弹两次。
        val overlayActive = AirDropStore.overlayEnabled(this) && AirDropOverlay.isAvailable(this)
        if (overlayActive) {
            if (dialog != null) {
                dialog?.dismiss()
                dialog = null
                showingRequest = null
            }
            return
        }
        val pending = AirDropStore.approvals(this).listFiles()?.sortedBy { it.name }
            ?.firstOrNull { it.extension == "json" && !File(it.parentFile, "${it.nameWithoutExtension}.decision").exists() }
        if (showingRequest != null && pending?.name != showingRequest) {
            dialog?.dismiss()
            dialog = null
            showingRequest = null
        }
        if (dialog != null || pending == null) return
        try {
            val data = JSONObject(pending.readText())
            val list = data.optJSONArray("files") ?: JSONArray()
            val names = (0 until list.length()).joinToString("\n") {
                list.getJSONObject(it).optString("name").take(180)
            }
            showingRequest = pending.name
            fun decide(accepted: Boolean) {
                AirDropStore.decision(this, pending.nameWithoutExtension, accepted)
                showingRequest = null
                dialog = null
            }
            dialog = AlertDialog.Builder(this)
                .setTitle("接收 AirDrop 文件？")
                .setMessage("来自：${data.optString("sender", "附近设备").take(120)}\n\n${names.take(1500)}")
                .setPositiveButton("接收") { _, _ -> decide(true) }
                .setNegativeButton("拒绝") { _, _ -> decide(false) }
                .setOnCancelListener { decide(false) }
                .show()
        } catch (_: Exception) {
            // An expired/removed request is refreshed next tick.
        }
    }

    private fun refreshFiles() {
        val entries = AirDropStore.inbox(this).listFiles()?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        val stamp = entries.joinToString { "${it.name}:${it.length()}:${it.lastModified()}" }
        if (stamp == fileStamp && (entries.isEmpty() || filesContainer.childCount > 0)) return
        fileStamp = stamp

        filesContainer.removeAllViews()

        if (entries.isEmpty()) {
            filesContainer.addView(emptyFilesView)
            return
        }

        entries.take(100).forEachIndexed { index, file ->
            if (index > 0) {
                filesContainer.addView(createCardDivider(this@AirDropActivity, insetHorizontalDp = 48f))
            }
            filesContainer.addView(buildFileItemView(file))
        }
    }

    private fun buildEmptyFilesView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(24), dp(16), dp(24))

        val titleTv = TextView(this@AirDropActivity).apply {
            text = "暂无接收到的文件"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            gravity = Gravity.CENTER
        }
        addView(titleTv)

        val hintTv = TextView(this@AirDropActivity).apply {
            text = "附近苹果设备通过 AirDrop 发送并确认接收后，将在此展示"
            textSize = 12f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        addView(hintTv)
    }

    private fun buildFileItemView(file: File): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            background = UiTheme.createButtonRipple(
                this@AirDropActivity,
                Color.TRANSPARENT,
                UiTheme.adjustAlpha(Color.BLACK, 0.06f),
                radiusDp = 10f
            )
            isClickable = true
            isFocusable = true
            onHapticClick {
                try {
                    val uri = Uri.Builder().scheme("content").authority("dev.local.mihotspot.airdrop.files")
                        .appendPath(file.name).build()
                    startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, AirDropFilesProvider.mime(file.name))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                } catch (_: Exception) {
                    message("没有可打开此类型文件的应用。")
                }
            }
        }

        val iconBox = FrameLayout(this).apply {
            val size = dp(36)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(12)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(UiTheme.COLOR_ACCENT_LIGHT)
            }
            val icon = ImageView(this@AirDropActivity).apply {
                setImageResource(R.drawable.ic_file)
                val isize = dp(20)
                layoutParams = FrameLayout.LayoutParams(isize, isize).apply {
                    gravity = Gravity.CENTER
                }
            }
            addView(icon)
        }
        row.addView(iconBox)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameTv = TextView(this).apply {
            text = file.name
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        textCol.addView(nameTv)

        val metaTv = TextView(this).apply {
            val sizeStr = formatFileSize(file.length())
            val dateStr = dateFormat.format(Date(file.lastModified()))
            text = "$sizeStr · $dateStr"
            textSize = 12f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            setPadding(0, dp(2), 0, 0)
        }
        textCol.addView(metaTv)
        row.addView(textCol)

        val arrow = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            val asize = dp(20)
            layoutParams = LinearLayout.LayoutParams(asize, asize)
        }
        row.addView(arrow)

        return row
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun diagnostics() = work {
        val binary = AirDropStore.binary(this, true)
        val result = if (binary.canExecute()) {
            BoundedShell.run(
                listOf("su", "-c", "${AirDropStore.quote(binary.absolutePath)} -i wlan0 --check 2>&1"),
                5000
            )?.output
        } else {
            "安装包缺少 ARM64 后端"
        }
        val logs = listOf(File(AirDropStore.radio(this), "backend.log"), AirDropStore.log(this))
            .filter { it.isFile }
            .joinToString("\n\n") { "${it.name}\n${it.readText().takeLast(8000)}" }
        handler.post {
            message((result.orEmpty() + "\n\n" + logs).replace(Regex("\u001B\\[[;\\d]*m"), ""))
        }
    }

    @Deprecated("Legacy Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7410 && resultCode == RESULT_OK) {
            data?.data?.let { uri -> prepareSend(uri) }
        }
    }

    private fun prepareSend(uri: Uri) {
        // 统一走 [AirDropShareActivity]：它负责实时扫描、点按即发、进度展示，
        // 也是系统分享面板的入口，两条路径行为一致。
        val type = contentResolver.getType(uri) ?: "*/*"
        startActivity(
            Intent(this, AirDropShareActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType(type)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun work(action: () -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            message("正在处理，请稍候")
            return
        }
        Thread({
            try {
                action()
            } catch (error: Exception) {
                handler.post { message(error.message ?: "操作失败") }
            } finally {
                busy.set(false)
            }
        }, "Marionette-AirDropUI").start()
    }

    private fun message(text: String) {
        if (!isFinishing && !isDestroyed) {
            AlertDialog.Builder(this)
                .setTitle("AirDrop")
                .setMessage(text)
                .setPositiveButton("好", null)
                .show()
        }
    }
}
