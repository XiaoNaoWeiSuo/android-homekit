package dev.local.mihotspot.airdrop

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import dev.local.mihotspot.homekit.commands.BoundedShell
import dev.local.mihotspot.ui.ProductUi
import dev.local.mihotspot.ui.ScreenRoutes
import dev.local.mihotspot.ui.StatusTone
import dev.local.mihotspot.ui.Symbol
import dev.local.mihotspot.ui.UiStatus
import dev.local.mihotspot.ui.UiTheme
import dev.local.mihotspot.ui.createCardDivider
import dev.local.mihotspot.ui.renderStatus
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

    private enum class Route { TRANSFER, INBOX, RECEIVE, SUPPORT }

    private val ui by lazy { ProductUi(this) }
    private lateinit var routes: ScreenRoutes<Route>
    private lateinit var residentSwitch: Switch
    private lateinit var residentDetailText: TextView
    private lateinit var overlaySwitch: Switch
    private lateinit var sessionStatusBadge: TextView
    private lateinit var sessionStatusText: TextView
    private lateinit var sessionProgress: android.widget.ProgressBar
    private lateinit var sessionStartBtn: Button
    private lateinit var filesContainer: LinearLayout
    private lateinit var emptyFilesView: View
    private val stateListener: () -> Unit = {
        if (!isFinishing && !isDestroyed) {
            updateSessionUi()
            updateResidentUi()
            showPendingRequest()
            if (routes.current == Route.INBOX) refreshFiles()
        }
    }

    private var updatingResidentSwitch = false
    private var updatingOverlaySwitch = false
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private val refresh = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                updateResidentUi()
                updateSessionUi()
                showPendingRequest()
                if (routes.current == Route.INBOX) refreshFiles()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi(savedInstanceState))
        updateResidentUi()
        updateSessionUi()
        refreshFiles()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        AirDropState.addListener(stateListener)
        handler.post(refresh)
    }

    override fun onPause() {
        AirDropState.removeListener(stateListener)
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun buildUi(saved: Bundle?): View {
        routes = ScreenRoutes(this)
        routes.register(Route.TRANSFER, null, ui.screen("AirDrop", ::back) {
            sessionStatusBadge = ui.status()
            sessionStatusText = ui.text("", 14, UiTheme.COLOR_TEXT_SECONDARY)
            sessionProgress = android.widget.ProgressBar(
                this@AirDropActivity, null, android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 100
                visibility = View.GONE
                contentDescription = "文件传输进度"
            }
            sessionStartBtn = ui.button("接收 10 分钟", action = ::toggleSession)
            addView(ui.surface().apply {
                addView(ui.row("附近传输", trailing = sessionStatusBadge, symbol = Symbol.AIRDROP))
                addView(sessionStatusText)
                addView(sessionProgress)
                addView(ui.button("选择文件发送", primary = true, action = ::pickFile))
                addView(sessionStartBtn)
            })
            addView(ui.surface().apply {
                addView(ui.row("已收到的文件", symbol = Symbol.FILES) {
                    routes.show(Route.INBOX)
                    refreshFiles()
                })
                addView(ui.row("接收偏好", "常驻接收与悬浮窗", symbol = Symbol.SETTINGS) {
                    routes.show(Route.RECEIVE)
                    updateResidentUi()
                })
            })
        })
        routes.register(Route.INBOX, Route.TRANSFER, ui.screen("已收到的文件", ::back) {
            filesContainer = ui.surface()
            emptyFilesView = ui.column().apply {
                addView(ui.text("暂无文件", 16, bold = true))
                addView(ui.text("接收后可从这里打开文件。", 13, UiTheme.COLOR_TEXT_SECONDARY))
            }
            filesContainer.addView(emptyFilesView)
            addView(filesContainer)
        })
        routes.register(Route.RECEIVE, Route.TRANSFER, ui.screen("接收偏好", ::back) {
            residentSwitch = Switch(this@AirDropActivity).apply {
                contentDescription = "常驻接收"
                setOnCheckedChangeListener { _, enabled ->
                    if (updatingResidentSwitch) return@setOnCheckedChangeListener
                    if (enabled) {
                        AirDropResidentService.enable(this@AirDropActivity)
                        if (!AirDropOverlay.isAvailable(this@AirDropActivity)) requestOverlayPermission()
                    } else AirDropResidentService.disable(this@AirDropActivity)
                }
            }
            residentDetailText = ui.text("", 13, UiTheme.COLOR_TEXT_SECONDARY)
            overlaySwitch = Switch(this@AirDropActivity).apply {
                contentDescription = "用悬浮窗确认接收"
                setOnCheckedChangeListener { _, enabled ->
                    if (updatingOverlaySwitch) return@setOnCheckedChangeListener
                    if (enabled && !AirDropOverlay.isAvailable(this@AirDropActivity)) {
                        requestOverlayPermission()
                        return@setOnCheckedChangeListener
                    }
                    AirDropStore.overlayEnabled(this@AirDropActivity, enabled)
                    if (!enabled) AirDropOverlay.hide()
                }
            }
            addView(ui.surface().apply {
                addView(ui.row("常驻接收", "开机后恢复接收", residentSwitch, Symbol.POWER))
                addView(residentDetailText)
            })
            addView(ui.surface().apply {
                addView(ui.row("用悬浮窗确认接收", "在其他应用上方显示请求和进度", overlaySwitch))
                addView(ui.button("预览悬浮窗") {
                    if (!AirDropOverlay.preview(this@AirDropActivity)) requestOverlayPermission()
                })
            })
            addView(ui.surface().apply {
                addView(ui.row("诊断与关于", "设备检查、日志与许可证", symbol = Symbol.SETTINGS) {
                    routes.show(Route.SUPPORT)
                })
            })
        })
        routes.register(Route.SUPPORT, Route.RECEIVE, ui.screen("诊断与关于", ::back) {
            addView(ui.surface().apply {
                addView(ui.row("检测设备并查看日志", action = ::diagnostics))
                addView(ui.row("开源后端许可证") {
                    val license = assets.open("airdrop/LICENSE.txt").bufferedReader().use { it.readText() }
                    AlertDialog.Builder(this@AirDropActivity).setTitle("opendrop-rs · GPLv3")
                        .setMessage("来源：https://github.com/ayourtch-llm/opendrop-rs\n本项目修改后的完整源码：native/airdrop\n\n$license")
                        .setPositiveButton("关闭", null).show()
                })
            })
            addView(ui.text("单次发送上限 ${AirDropStore.formatSize(AirDropStore.MAX_SEND_BYTES)}，接收上限 128 MiB。",
                13, UiTheme.COLOR_TEXT_SECONDARY))
        })
        routes.show(Route.entries.firstOrNull { it.name == saved?.getString("route") } ?: Route.TRANSFER)
        return routes.host
    }

    private fun back() {
        if (!routes.back()) finish()
    }

    @Deprecated("Legacy Activity Back API")
    override fun onBackPressed() {
        if (!routes.back()) super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("route", routes.current?.name)
    }

    @Suppress("DEPRECATION")
    private fun pickFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, 7410)
    }

    private fun toggleSession() {
        when {
            AirDropResidentService.isEnabled(this) -> routes.show(Route.RECEIVE)
            AirDropService.isAlive() || AirDropService.isRunning(this) -> {
                AirDropService.stop(this)
                updateSessionUi()
            }
            else -> confirmAirDropReception(this) {
                AirDropStore.status(this, "正在启动…")
                AirDropService.startWindow(this)
                updateSessionUi()
            }
        }
    }

    private fun updateResidentUi() {
        val enabled = AirDropResidentService.isEnabled(this)
        updatingResidentSwitch = true
        residentSwitch.isChecked = enabled
        updatingResidentSwitch = false
        val overlayAvailable = AirDropOverlay.isAvailable(this)
        updatingOverlaySwitch = true
        overlaySwitch.isChecked = AirDropStore.overlayEnabled(this) && overlayAvailable
        updatingOverlaySwitch = false
        residentDetailText.text = if (enabled && !overlayAvailable)
            "${AirDropResidentService.status(this)}\n接收确认会显示在通知中。"
        else AirDropResidentService.status(this)
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
        val phase = AirDropState.phase
        val resident = AirDropResidentService.isEnabled(this)
        val running = AirDropService.isAlive() || AirDropService.isRunning(this)
        sessionStatusBadge.renderStatus(airDropStatus(this))
        sessionStatusText.text = when {
            phase != AirDropState.Phase.IDLE -> AirDropState.status
            resident -> AirDropResidentService.status(this)
            running -> "接收会话正在启动。"
            else -> "从附近 Apple 设备收发文件。"
        }
        val progress = AirDropState.progress
        sessionProgress.visibility = if (progress == null) View.GONE else View.VISIBLE
        if (progress != null) {
            sessionProgress.isIndeterminate = progress.indeterminate
            progress.fraction?.let { sessionProgress.progress = (it * 100).toInt() }
            sessionStatusText.text = "${AirDropState.status} · ${AirDropStore.formatSize(progress.done)}"
        }
        sessionStartBtn.text = when {
            resident -> "管理常驻接收"
            running -> "停止接收"
            else -> "接收 10 分钟"
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
                filesContainer.addView(createCardDivider(this@AirDropActivity))
            }
            filesContainer.addView(buildFileItemView(file))
        }
    }

    private fun buildFileItemView(file: File): View = ui.row(
        file.name,
        "${AirDropStore.formatSize(file.length())} · ${dateFormat.format(Date(file.lastModified()))}",
        symbol = Symbol.FILES
    ) {
        try {
            val uri = Uri.Builder().scheme("content").authority("dev.local.mihotspot.airdrop.files")
                .appendPath(file.name).build()
            startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, AirDropFilesProvider.mime(file.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            message("没有可打开此类型文件的应用。")
        }
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

internal fun airDropStatus(context: Context): UiStatus = when (AirDropState.phase) {
    AirDropState.Phase.STARTING -> UiStatus("启动中", StatusTone.BUSY)
    AirDropState.Phase.READY -> UiStatus("可接收", StatusTone.ACTIVE)
    AirDropState.Phase.SEEN -> UiStatus("发现设备", StatusTone.ACTIVE)
    AirDropState.Phase.AWAITING -> UiStatus("待确认", StatusTone.BUSY)
    AirDropState.Phase.RECEIVING -> UiStatus("正在接收", StatusTone.ACTIVE)
    AirDropState.Phase.SENDING -> UiStatus("正在发送", StatusTone.ACTIVE)
    AirDropState.Phase.DONE -> UiStatus("传输完成", StatusTone.ACTIVE)
    AirDropState.Phase.ERROR -> UiStatus("连接异常", StatusTone.ERROR)
    AirDropState.Phase.IDLE -> when {
        AirDropResidentService.isEnabled(context) || AirDropService.isAlive() -> UiStatus("准备接收", StatusTone.BUSY)
        else -> UiStatus("待启用")
    }
}

internal fun confirmAirDropReception(activity: Activity, onStart: () -> Unit) {
    AlertDialog.Builder(activity)
        .setTitle("接收 AirDrop · 10 分钟")
        .setMessage("请先连接 Wi-Fi 并关闭手机热点。附近 Apple 设备可以发起请求，每次接收都需要确认。")
        .setPositiveButton("开始接收") { _, _ -> onStart() }
        .setNegativeButton("取消", null)
        .show()
}
