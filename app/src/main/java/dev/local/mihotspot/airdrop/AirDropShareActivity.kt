package dev.local.mihotspot.airdrop

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.local.mihotspot.ui.ProductUi
import dev.local.mihotspot.ui.Symbol
import dev.local.mihotspot.ui.UiTheme
import dev.local.mihotspot.ui.createCard
import dev.local.mihotspot.ui.createCardDivider
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
    private val ui by lazy { ProductUi(this) }
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

    private companion object {
        const val REQUEST_PICK_FILE = 41
    }

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
            payloadText.text = "未选择文件"
            statusText.text = "先选择文件，再点按附近设备。"
            statusBadge.text = "○ 无内容"
        }
        // 即使还没选文件也要起引擎：设备列表来自常驻接收端，先扫描才能选中设备。
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
        stageUris(uris)
    }

    /** 直接打开时没有分享内容：让用户自己选文件，而不是只能从分享面板进入。 */
    private fun pickFile() {
        if (sending) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQUEST_PICK_FILE) }
            .onFailure { statusText.text = "没有可用的文件选择器" }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_FILE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { stageUris(listOf(it)) }
        }
    }

    /** 把一个或多个 URI 复制到缓存，供后续点击设备发送。 */
    private fun stageUris(uris: List<Uri>) {
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
                    ensureEngine()
                    awaitBrowsing()
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
        val peers = AirDropState.peers()
        val stamp = peers.joinToString { "${it.name}/${it.displayName}" } + "|" + AirDropState.phase + "|" + AirDropState.status
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
                if (index > 0) peersContainer.addView(createCardDivider(this))
                peersContainer.addView(buildPeerRow(peer))
            }
        }


    }

    private fun buildEmptyPeers(): View = ui.column().apply {
        addView(ui.text("还没有发现设备", 15, bold = true))
        addView(ui.text("请在对方设备打开 AirDrop，并将接收范围设为「所有人」。", 13, UiTheme.COLOR_TEXT_SECONDARY))
    }

    private fun buildPeerRow(peer: AirDropEvents.Peer): View =
        ui.row(peer.displayName, symbol = Symbol.DISPLAY) { sendTo(peer) }

    private fun buildUi(): View = ui.screen("发送文件", { finish() }) {
        payloadText = ui.text(describePayload(), 15, bold = true)
        statusText = ui.text("正在查找附近设备…", 13, UiTheme.COLOR_TEXT_SECONDARY)
        statusBadge = ui.status("扫描中")
        progressSection = ui.column().apply {
            visibility = View.GONE
            progressBar = ProgressBar(this@AirDropShareActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                contentDescription = "发送进度"
            }
            progressText = ui.text("", 13, UiTheme.COLOR_TEXT_SECONDARY)
            addView(progressBar)
            addView(progressText)
        }
        addView(ui.surface().apply {
            addView(ui.row("要发送的文件", trailing = statusBadge, symbol = Symbol.SEND))
            addView(payloadText)
            addView(statusText)
            addView(progressSection)
            addView(ui.button("选择文件", action = ::pickFile))
        })
        addView(ui.section("附近设备"))
        peersContainer = ui.surface().apply { addView(buildEmptyPeers()) }
        addView(peersContainer)
        addView(ui.button("刷新设备列表") {
            AirDropState.clearPeers()
            ensureEngine()
            awaitBrowsing()
        })
    }
}
