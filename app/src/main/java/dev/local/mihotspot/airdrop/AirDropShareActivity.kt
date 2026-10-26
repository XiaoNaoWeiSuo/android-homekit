package dev.local.mihotspot.airdrop

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import dev.local.mihotspot.R
import dev.local.mihotspot.ui.UiTheme
import dev.local.mihotspot.ui.createCard
import dev.local.mihotspot.ui.createCardDivider
import dev.local.mihotspot.ui.createPageHeader
import dev.local.mihotspot.ui.createSectionHeader
import dev.local.mihotspot.ui.createStatusBadge
import dev.local.mihotspot.ui.onHapticClick
import java.io.File

/**
 * 系统分享面板里的「隔空投送」目标。
 *
 * 这是"和 iPhone 一样"的入口：在任意应用里点分享 → 选 AirDrop → 这里立刻列出附近设备 →
 * 点一下就发。三条关键行为：
 *
 * 1. **实时扫描**：设备列表来自常驻接收端自己的 mDNS browse（`receive --browse`），
 *    通过 `peer`/`lost` 事件流更新 —— 不是另起一个扫描进程（那样两个进程各收一份
 *    包、各建一份缓存，还会把本机当成附近设备），也不是 `find -d 5` 那样等 5 秒
 *    一次性列出来。
 * 2. **点击即发**：选中设备直接开始传输，不再弹二次确认。
 * 3. **进度可见**：传输阶段与字节进度都来自后端事件流，多文件按顺序逐个发送。
 *
 * 若 AirDrop 引擎没在跑，这里会先把它拉起来（网卡切换要十几秒），并明确告知正在准备，
 * 而不是让用户对着空列表发呆。
 */
class AirDropShareActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var peersContainer: LinearLayout
    /** 发送内容（文件名/大小）。由分享内容决定，引擎状态不会覆盖它。 */
    private lateinit var payloadText: TextView
    /** 引擎/扫描状态的一行提示，和"发什么"分开显示。 */
    private lateinit var statusText: TextView
    private lateinit var statusBadge: TextView
    private lateinit var progressSection: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    /** 待发送文件（已复制到缓存）。 */
    private val staged = ArrayList<File>()
    @Volatile private var sending = false
    private var lastPeerStamp = ""

    private val listener: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        AirDropState.addListener(listener)
        collectShare(intent)
        if (staged.isEmpty()) {
            payloadText.text = "没有收到可发送的文件"
            statusText.text = "从其他应用分享文件时选择「隔空投送」即可。"
            statusBadge.text = "○ 无内容"
            return
        }
        ensureEngine()
        awaitBrowsing()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 分享面板可能复用同一个实例（singleTop），补上新文件再扫描。
        collectShare(intent)
    }

    override fun onDestroy() {
        AirDropState.removeListener(listener)
        // 不再清空列表：设备列表由常驻接收端维护（`--browse`），关掉分享面板后
        // 它继续工作 —— 下次打开能立刻看到设备，而不是重新等一轮宣告。
        super.onDestroy()
    }

    // ---- 输入 ----

    /** 从 ACTION_SEND / ACTION_SEND_MULTIPLE 取出 URI 并复制到缓存。 */
    private fun collectShare(intent: Intent?) {
        if (intent == null) return
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> streamOf(intent)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { if (!uris.contains(it)) uris.add(it) }
            }
        }
        if (uris.isEmpty()) return

        statusText.text = "正在准备文件…"
        Thread({
            val failures = ArrayList<String>()
            for (uri in uris) {
                runCatching {
                    val name = displayName(uri)
                    val file = AirDropBackend.stage(this, name) {
                        contentResolver.openInputStream(uri)
                    }
                    synchronized(staged) { staged.add(file) }
                }.onFailure { failures.add(it.message ?: "读取失败") }
            }
            handler.post {
                if (staged.isEmpty()) {
                    payloadText.text = failures.firstOrNull() ?: "无法读取所选文件"
                } else {
                    payloadText.text = describePayload()
                    render()
                }
            }
        }, "AirDropShare-stage").start()
    }

    @Suppress("DEPRECATION")
    private fun streamOf(intent: Intent): Uri? =
        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    private fun describePayload(): String {
        val files = synchronized(staged) { staged.toList() }
        return when {
            files.isEmpty() -> "没有可发送的文件"
            files.size == 1 -> "${files[0].name} · ${AirDropStore.formatSize(files[0].length())}"
            else -> "${files.size} 个文件 · ${AirDropStore.formatSize(files.sumOf { it.length() })}"
        }
    }

    // ---- 引擎与扫描 ----

    /**
     * 确保 AWDL 引擎在跑。
     *
     * 引擎起来需要切换网卡（十几秒），期间 `awdl0` 不存在，扫描必然为空 —— 所以这里
     * 明确显示"正在准备"，而不是让用户以为附近没有设备。
     */
    private fun ensureEngine() {
        if (AirDropService.isRunning(this)) return
        if (AirDropResidentService.isEnabled(this)) {
            AirDropResidentService.enable(this)
        } else {
            AirDropService.startWindow(this)
        }
    }

    /**
     * 等引擎开始广播设备列表。
     *
     * 设备列表来自接收端自己的 `peer`/`lost` 事件（`receive --browse`），所以这里
     * 不需要（也不应该）另起 `find --watch` 进程：外部进程分不清哪个宣告是本机，
     * 会把自己列成"附近设备"。
     *
     * 引擎没起来时（网卡切换要十几秒）就重试，并明确显示"准备中"，而不是让用户
     * 对着空列表以为附近没有设备。
     */
    private fun awaitBrowsing() {
        if (AirDropService.isRunning(this)) {
            statusBadge.text = "● 扫描中"
            statusBadge.setTextColor(UiTheme.COLOR_WARNING)
            statusBadge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_WARNING_LIGHT)
            return
        }
        if (isFinishing || isDestroyed) return
        statusBadge.text = "● 准备中"
        statusBadge.setTextColor(UiTheme.COLOR_WARNING)
        statusBadge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_WARNING_LIGHT)
        handler.postDelayed({ awaitBrowsing() }, 1_000)
    }

    // ---- 发送 ----

    private fun sendTo(peer: AirDropEvents.Peer) {
        if (sending) return
        val files = synchronized(staged) { staged.toList() }
        if (files.isEmpty()) return
        sending = true
        progressSection.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressText.text = "准备发送…"
        statusText.text = "正在发送到 ${peer.displayName}"
        // 扫描由常驻接收端负责，发送不受影响：`send` 用 --address 直连对端，
        // 不再自己跑 mDNS，所以两个进程不会抢 5353。

        Thread({
            var sent = 0
            for (file in files) {
                handler.post {
                    AirDropState.setSendingName(file.name)
                    progressText.text = if (files.size > 1) {
                        "第 ${sent + 1}/${files.size} 个 · ${file.name}"
                    } else {
                        file.name
                    }
                }
                val handle = AirDropBackend.send(this, peer, file) { }
                val code = runCatching { handle.process.waitFor() }.getOrDefault(-1)
                if (code != 0) {
                    handler.post {
                        progressBar.isIndeterminate = false
                        progressBar.progress = 0
                        progressText.text = "发送失败（code $code）"
                        statusText.text = "发送失败。请确认对方已解锁并允许接收，或查看诊断日志。"
                        statusBadge.text = "● 失败"
                        statusBadge.setTextColor(UiTheme.COLOR_DANGER)
                        statusBadge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_DANGER_LIGHT)
                        sending = false
                    }
                    return@Thread
                }
                sent++
            }
            handler.post {
                progressBar.isIndeterminate = false
                progressBar.progress = 1000
                progressText.text = "已发送 ${files.size} 个文件"
                statusText.text = "已发送到 ${peer.displayName}"
                statusBadge.text = "● 已发送"
                statusBadge.setTextColor(UiTheme.COLOR_SUCCESS)
                statusBadge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_SUCCESS_LIGHT)
                sending = false
                // 发送完成后自动关闭，回到原来的应用 —— 和系统 AirDrop 一致。
                handler.postDelayed({ if (!isFinishing) finish() }, 1_400)
            }
        }, "AirDropShare-send").start()
    }

    // ---- 渲染 ----

    private fun render() {
        if (isFinishing || isDestroyed) return
        val peers = AirDropState.peers()
        val stamp = peers.joinToString { it.name } + "|" + AirDropState.phase + "|" + AirDropState.status
        if (stamp == lastPeerStamp) return
        lastPeerStamp = stamp

        // 状态行只描述"能不能发"，不覆盖"发什么"。
        if (!sending) {
            statusText.text = when (AirDropState.phase) {
                AirDropState.Phase.STARTING -> AirDropState.status
                AirDropState.Phase.ERROR -> AirDropState.error ?: "AirDrop 未就绪"
                else -> if (peers.isEmpty()) "正在查找附近设备…" else "点按设备即可发送"
            }
            val ready = AirDropState.phase != AirDropState.Phase.STARTING &&
                AirDropState.phase != AirDropState.Phase.ERROR
            statusBadge.text = when {
                !ready -> "● 准备中"
                peers.isEmpty() -> "● 扫描中"
                else -> "● ${peers.size} 台设备"
            }
            statusBadge.setTextColor(if (peers.isEmpty()) UiTheme.COLOR_WARNING else UiTheme.COLOR_SUCCESS)
            statusBadge.background = UiTheme.createPillDrawable(
                this,
                if (peers.isEmpty()) UiTheme.COLOR_WARNING_LIGHT else UiTheme.COLOR_SUCCESS_LIGHT
            )
        }

        peersContainer.removeAllViews()
        if (peers.isEmpty()) {
            peersContainer.addView(buildEmptyPeers())
        } else {
            peers.forEachIndexed { index, peer ->
                if (index > 0) peersContainer.addView(createCardDivider(this, insetHorizontalDp = 56f))
                peersContainer.addView(buildPeerRow(peer))
            }
        }

        // 传输中同步进度条（事件流驱动，节流由后端负责）。
        val progress = AirDropState.progress
        if (sending && progress != null) {
            val fraction = progress.fraction
            if (fraction == null) {
                progressBar.isIndeterminate = true
            } else {
                progressBar.isIndeterminate = false
                progressBar.progress = (fraction * 1000).toInt()
                progressText.text = "${AirDropStore.formatSize(progress.done)} / ${AirDropStore.formatSize(progress.total)}"
            }
        }
    }

    private fun buildEmptyPeers(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(28), dp(16), dp(28))
        addView(TextView(this@AirDropShareActivity).apply {
            text = "附近没有 AirDrop 设备"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            gravity = Gravity.CENTER
        })
        addView(TextView(this@AirDropShareActivity).apply {
            text = "请让对方打开控制中心 → 隔空投送 → 「所有人」，并保持屏幕解锁。"
            textSize = 12f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun buildPeerRow(peer: AirDropEvents.Peer): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            background = UiTheme.createButtonRipple(
                this@AirDropShareActivity,
                Color.TRANSPARENT,
                UiTheme.adjustAlpha(Color.BLACK, 0.06f),
                radiusDp = 12f
            )
            isClickable = true
            isFocusable = true
            onHapticClick { sendTo(peer) }
        }

        val iconBox = FrameLayout(this).apply {
            val size = dp(40)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(14) }
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(UiTheme.COLOR_ACCENT_LIGHT)
            }
            addView(ImageView(this@AirDropShareActivity).apply {
                setImageResource(R.drawable.ic_file)
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply { gravity = Gravity.CENTER }
            })
        }
        row.addView(iconBox)

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this@AirDropShareActivity).apply {
            text = peer.displayName
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        textCol.addView(TextView(this@AirDropShareActivity).apply {
            text = peer.address
            textSize = 11f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            setPadding(0, dp(2), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        row.addView(textCol)
        return row
    }

    // ---- 布局 ----

    private fun dp(v: Int): Int = UiTheme.dp(this, v)

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.COLOR_BG)
        }
        root.addView(
            createPageHeader(
                context = this,
                titleText = "隔空投送",
                onBackClick = { finish() },
                subtitleBadge = createStatusBadge(this, "● 扫描中", UiTheme.COLOR_WARNING, UiTheme.COLOR_WARNING_LIGHT)
                    .also { statusBadge = it }
            )
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }

        // 发送内容
        content.addView(createSectionHeader(this, "发送内容"))
        val payloadCard = createCard(this)
        payloadText = TextView(this).apply {
            text = describePayload()
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
            setLineSpacing(dp(3).toFloat(), 1.0f)
        }
        payloadCard.addView(payloadText)
        statusText = TextView(this).apply {
            text = "正在查找附近设备…"
            textSize = 12f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            setPadding(0, dp(4), 0, 0)
        }
        payloadCard.addView(statusText)
        progressSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(6)
            ).apply { topMargin = dp(10) }
        }
        progressSection.addView(progressBar)
        progressText = TextView(this).apply {
            textSize = 12f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            setPadding(0, dp(6), 0, 0)
        }
        progressSection.addView(progressText)
        payloadCard.addView(progressSection)
        content.addView(payloadCard)

        // 附近设备
        content.addView(createSectionHeader(this, "附近设备", "实时扫描说明",
            "苹果设备只在打开分享面板/隔空投送面板时才向网络宣告自己，因此列表可能随时增减。"))
        val peersCard = createCard(this, verticalPaddingDp = 6f, horizontalPaddingDp = 16f)
        peersContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        peersContainer.addView(buildEmptyPeers())
        peersCard.addView(peersContainer)
        content.addView(peersCard)

        val retry = Button(this).apply {
            text = "刷新设备列表"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply {
                setMargins(dp(16), dp(12), dp(16), 0)
            }
            UiTheme.styleButton(this, UiTheme.COLOR_BTN_SECONDARY_BG, UiTheme.COLOR_TEXT_PRIMARY, radiusDp = 12f)
            onHapticClick {
                // 列表由接收端的常驻 browse 维护，没有"重新起一个扫描进程"这回事。
                // 清空只是让界面立刻反映"正在重新确认"，对端每 10s 宣告一次，
                // 几秒内就会重新出现；引擎没起来则顺手拉起来。
                AirDropState.clearPeers()
                ensureEngine()
                awaitBrowsing()
            }
        }
        content.addView(retry)

        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        return root
    }
}
