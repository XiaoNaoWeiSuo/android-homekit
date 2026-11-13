package dev.local.mihotspot.peripheral.usb

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.local.mihotspot.ui.ProductUi
import dev.local.mihotspot.ui.ScreenRoutes
import dev.local.mihotspot.ui.StatusTone
import dev.local.mihotspot.ui.Symbol
import dev.local.mihotspot.ui.UiStatus
import dev.local.mihotspot.ui.UiTheme
import dev.local.mihotspot.ui.renderStatus

class UsbPeripheralActivity : Activity() {

    private lateinit var master: Switch
    private lateinit var audioSwitch: Switch
    private lateinit var statusBadge: TextView
    private lateinit var statusDetail: TextView
    private var suppressSwitch = false
    private var pendingAudio = false
    private var cameraSource = UsbCameraSource.OFF
    private var screenResultCode = 0
    private var screenData: Intent? = null
    private var pendingCamera: UsbCameraSource? = null
    private enum class Route { CONNECTION, VIDEO, AUDIO }
    private val ui by lazy { ProductUi(this) }
    private lateinit var routes: ScreenRoutes<Route>
    private val feedbackViews = mutableListOf<Pair<TextView, TextView>>()
    private lateinit var videoChoice: TextView
    private lateinit var audioChoice: TextView
    private lateinit var cameraSourceValue: TextView

    private fun setSwitchSilently(view: Switch, checked: Boolean) {
        suppressSwitch = true
        view.isChecked = checked
        suppressSwitch = false
    }

    /** True while a root probe is running, so resumes cannot pile up su calls. */
    private var probing = false

    /**
     * The switch must show the real session, not the in-memory static: after a
     * process restart the gadget can still be bound while UsbPeripheralService is
     * gone. The cheap snapshot paints instantly, then the worker probe corrects
     * it — and adopts a session that outlived the process instead of tearing it
     * down (unbinding the gadget can wedge the vendor kernel).
     */
    private fun refreshFromRealState() {
        renderStatus(UsbPeripheralStatus.snapshot(this))
        if (probing) return
        probing = true
        Thread {
            val snapshot = try {
                UsbPeripheralStatus.verify(this)
            } catch (e: Throwable) {
                UsbPeripheralStatus.snapshot(this)
            }
            runOnUiThread {
                probing = false
                if (!isFinishing && !isDestroyed) renderStatus(snapshot)
            }
        }.start()
    }

    private fun renderStatus(snapshot: UsbPeripheralStatus.Snapshot) {
        setSwitchSilently(master, snapshot.isSwitchOn())
        master.isEnabled = snapshot.state !in listOf("stopping", "armed_stop", "starting")
        val detail = snapshot.hint ?: when {
            snapshot.state == "error" -> "请重新插拔数据线后重试。"
            snapshot.state == "stopping" -> "正在恢复普通 USB 模式。"
            snapshot.state == "armed_stop" -> "拔出数据线后恢复普通 USB 模式。"
            snapshot.hostAttached && snapshot.state == "ready" -> "主机可使用 USB 键盘与触控板。"
            snapshot.active || snapshot.state == "armed" -> "用数据线连接主机后自动启用。"
            snapshot.pending -> "正在准备 USB 外设。"
            else -> "USB 外设已关闭。"
        }
        showStatus(usbStatus(snapshot), detail)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val snapshot = UsbPeripheralStatus.snapshot(this@UsbPeripheralActivity)
            renderStatus(snapshot)
            if (intent?.getStringExtra(UsbPeripheralService.EXTRA_STATE) == "camera_error") {
                showStatus(UiStatus("画面异常", StatusTone.ERROR),
                    intent.getStringExtra(UsbPeripheralService.EXTRA_DETAIL) ?: "无法启动画面输入。")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraSource = runCatching {
            UsbCameraSource.valueOf(UsbSession.prefs(this).getString(
                UsbSession.KEY_CAMERA_SOURCE, UsbCameraSource.OFF.name
            ) ?: UsbCameraSource.OFF.name)
        }.getOrDefault(UsbCameraSource.OFF)
        routes = ScreenRoutes(this)
        routes.register(Route.CONNECTION, null, ui.screen("USB 外设", ::back) {
            statusBadge = ui.status()
            statusDetail = ui.text("", 13, UiTheme.COLOR_TEXT_SECONDARY)
            master = Switch(this@UsbPeripheralActivity).apply {
                contentDescription = "启用 USB 外设"
                isChecked = UsbSession.isEnabled(this@UsbPeripheralActivity)
                setOnCheckedChangeListener { _, checked ->
                    if (suppressSwitch) return@setOnCheckedChangeListener
                    UsbSession.setEnabled(this@UsbPeripheralActivity, checked)
                    if (checked) {
                        showStatus(UiStatus("正在启动", StatusTone.BUSY), "正在准备 USB 外设。")
                        startUsb()
                    } else beginStop()
                }
            }
            addView(ui.surface().apply {
                addView(ui.row("USB 外设模式", "用数据线连接主机后，可使用键盘与触控板", master, Symbol.USB))
                addView(statusBadge, LinearLayout.LayoutParams(-2, -2))
                addView(statusDetail)
            })
            addView(ui.surface().apply {
                videoChoice = ui.text(cameraLabel(cameraSource))
                audioChoice = ui.text(audioLabel())
                addView(ui.row("画面输入", "手机摄像头或屏幕", videoChoice, Symbol.CAMERA) { routes.show(Route.VIDEO) })
                addView(ui.row("声音输入与输出", "麦克风和扬声器", audioChoice, Symbol.SOUND) { routes.show(Route.AUDIO) })
            })
        })
        routes.register(Route.VIDEO, Route.CONNECTION, ui.screen("画面输入", ::back) {
            val value = ui.text(cameraLabel(cameraSource))
            cameraSourceValue = value
            addView(ui.surface().apply {
                addView(ui.row("画面来源", "主机将手机识别为 USB 摄像头", value, Symbol.CAMERA) {
                    val sources = UsbCameraSource.entries
                    android.app.AlertDialog.Builder(this@UsbPeripheralActivity)
                        .setTitle("选择画面来源")
                        .setSingleChoiceItems(sources.map(::cameraLabel).toTypedArray(), sources.indexOf(cameraSource)) { dialog, index ->
                            dialog.dismiss()
                            val source = sources[index]
                            if (source != cameraSource || source == UsbCameraSource.SCREEN && screenData == null) chooseCamera(source)
                        }
                        .setNegativeButton("取消", null).show()
                })
                addView(ui.text("选择屏幕时，Android 会请求录屏权限。", 13, UiTheme.COLOR_TEXT_SECONDARY))
            })
            addView(usbFeedback())
        })
        routes.register(Route.AUDIO, Route.CONNECTION, ui.screen("声音输入与输出", ::back) {
            audioSwitch = Switch(this@UsbPeripheralActivity).apply {
                contentDescription = "麦克风与扬声器"
                isChecked = UsbSession.prefs(this@UsbPeripheralActivity).getBoolean(UsbSession.KEY_AUDIO, false)
                setOnCheckedChangeListener { _, enabled ->
                    if (suppressSwitch) return@setOnCheckedChangeListener
                    UsbSession.prefs(this@UsbPeripheralActivity).edit().putBoolean(UsbSession.KEY_AUDIO, enabled).apply()
                    audioChoice.text = audioLabel()
                    if (master.isChecked) {
                        showStatus(UiStatus("正在应用", StatusTone.BUSY), "正在应用音频设置。")
                        UsbPeripheralService.stop(this@UsbPeripheralActivity)
                        Handler(Looper.getMainLooper()).postDelayed({ startUsb() }, 1000)
                    }
                }
            }
            addView(ui.surface().apply {
                addView(ui.row("麦克风与扬声器", "主机录音使用手机麦克风，主机声音从手机扬声器输出", audioSwitch, Symbol.SOUND))
            })
            addView(usbFeedback())
        })
        routes.show(Route.entries.firstOrNull { it.name == savedInstanceState?.getString("route") } ?: Route.CONNECTION)
        setContentView(routes.host)
    }

    private fun usbFeedback() = ui.surface().apply {
        val badge = ui.status()
        val detail = ui.text("", 13, UiTheme.COLOR_TEXT_SECONDARY)
        addView(badge, LinearLayout.LayoutParams(-2, -2))
        addView(detail)
        feedbackViews.add(badge to detail)
    }

    private fun cameraLabel(source: UsbCameraSource): String = when (source) {
        UsbCameraSource.OFF -> "关闭"
        UsbCameraSource.FRONT -> "前置摄像头"
        UsbCameraSource.BACK -> "后置摄像头"
        UsbCameraSource.SCREEN -> "屏幕"
    }

    private fun audioLabel() = if (UsbSession.prefs(this).getBoolean(UsbSession.KEY_AUDIO, false)) "开启" else "关闭"

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

    private fun showStatus(status: UiStatus, detail: String) {
        statusBadge.renderStatus(status)
        statusDetail.text = detail
        feedbackViews.forEach { (badge, message) ->
            badge.renderStatus(status)
            message.text = detail
        }
    }

    private fun beginStop() {
        showStatus(UiStatus("正在停止", StatusTone.BUSY), "正在恢复普通 USB 模式。")
        master.isEnabled = false
        UsbPeripheralService.stopFromUi(this)
    }

    private fun chooseCamera(source: UsbCameraSource) {
        when (source) {
            UsbCameraSource.OFF -> applyCamera(UsbCameraSource.OFF)
            UsbCameraSource.SCREEN -> requestScreenConsent()
            UsbCameraSource.FRONT, UsbCameraSource.BACK -> {
                if (checkSelfPermission(Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    pendingCamera = source
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), 52)
                } else {
                    applyCamera(source)
                }
            }
        }
    }

    private fun applyCamera(source: UsbCameraSource) {
        val previous = cameraSource
        cameraSource = source
        UsbSession.prefs(this).edit()
            .putString(UsbSession.KEY_CAMERA_SOURCE, source.name).apply()
        val label = cameraLabel(source)
        videoChoice.text = label
        cameraSourceValue.text = label
        if (master.isChecked) {
            // FRONT/BACK/SCREEN -> FRONT/BACK/SCREEN only swaps the app-side
            // pipeline: the UVC function stays linked and the daemon keeps
            // running. Re-provisioning here used to unbind/rebind the gadget,
            // and this vendor kernel turns that into a panic often enough that
            // switching sources could reboot the phone.
            val inPlace = UsbPeripheralService.running &&
                previous != UsbCameraSource.OFF &&
                source != UsbCameraSource.OFF
            if (inPlace) {
                showStatus(UiStatus("正在切换", StatusTone.BUSY), "正在切换画面来源。")
                UsbPeripheralService.switchCamera(this, source, screenResultCode, screenData)
            } else {
                showStatus(UiStatus("正在应用", StatusTone.BUSY), "正在应用摄像头设置。")
                UsbPeripheralService.stop(this)
                Handler(Looper.getMainLooper()).postDelayed({ startUsb() }, 1000)
            }
        } else {
            showStatus(usbStatus(UsbPeripheralStatus.snapshot(this)),
                if (source == UsbCameraSource.OFF) "画面输入已关闭。" else "下次连接 USB 时使用$label。")
        }
    }

    private fun requestScreenConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), 61)
    }

    private fun startUsb() {
        val wantAudio = audioSwitch.isChecked
        if (wantAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingAudio = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 51)
            return
        }
        if ((cameraSource == UsbCameraSource.FRONT || cameraSource == UsbCameraSource.BACK) &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCamera = cameraSource
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 52)
            return
        }
        if (cameraSource == UsbCameraSource.SCREEN && screenData == null) {
            requestScreenConsent()
            return
        }
        UsbPeripheralService.start(
            this, cameraSource, wantAudio, screenResultCode, screenData
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 61) return
        if (resultCode == RESULT_OK && data != null) {
            screenResultCode = resultCode
            screenData = data
            applyCamera(UsbCameraSource.SCREEN)
        } else {
            Toast.makeText(this, "未授权屏幕录制", Toast.LENGTH_SHORT).show()
            if (cameraSource == UsbCameraSource.SCREEN) applyCamera(UsbCameraSource.OFF)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            51 -> {
                if (!granted) {
                    setSwitchSilently(audioSwitch, false)
                    UsbSession.prefs(this).edit().putBoolean(UsbSession.KEY_AUDIO, false).apply()
                    audioChoice.text = audioLabel()
                    showStatus(UiStatus("录音未授权", StatusTone.ERROR), "授予权限后才能使用手机麦克风。")
                }
                pendingAudio = false
                if (granted) startUsb()
            }
            52 -> {
                val pending = pendingCamera
                pendingCamera = null
                if (granted && pending != null) {
                    applyCamera(pending)
                } else {
                    applyCamera(UsbCameraSource.OFF)
                    Toast.makeText(this, "未授予相机权限", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(UsbPeripheralService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        // Missed broadcasts (and a process restart) must not leave the switch
        // showing a state the daemon does not have.
        refreshFromRealState()
    }

    override fun onPause() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        super.onPause()
    }

}

internal fun usbStatus(snapshot: UsbPeripheralStatus.Snapshot): UiStatus = when {
    snapshot.state == "error" -> UiStatus("连接异常", StatusTone.ERROR)
    snapshot.state == "stopping" -> UiStatus("正在停止", StatusTone.BUSY)
    snapshot.state == "armed_stop" -> UiStatus("拔线后关闭", StatusTone.BUSY)
    snapshot.state == "ready" && snapshot.hostAttached -> UiStatus("已连接", StatusTone.ACTIVE)
    snapshot.state == "armed" || snapshot.active -> UiStatus("等待主机", StatusTone.WAITING)
    snapshot.pending || snapshot.enabled -> UiStatus("准备中", StatusTone.BUSY)
    else -> UiStatus("已关闭")
}

internal fun UsbPeripheralStatus.Snapshot.isSwitchOn(): Boolean =
    state != "armed_stop" && state != "stopping" && state != "error" && (active || pending || enabled)
