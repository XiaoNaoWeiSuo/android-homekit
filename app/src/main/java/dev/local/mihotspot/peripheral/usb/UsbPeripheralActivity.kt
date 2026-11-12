package dev.local.mihotspot.peripheral.usb

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.local.mihotspot.R

import dev.local.mihotspot.ui.UiTheme
import dev.local.mihotspot.ui.createCard
import dev.local.mihotspot.ui.createCardDivider
import dev.local.mihotspot.ui.createPageHeader
import dev.local.mihotspot.ui.createSectionHeader
import dev.local.mihotspot.ui.createStatusBadge
import dev.local.mihotspot.ui.onHapticClick

/**
 * USB peripheral settings. HID and UAC2 share one standard gadget composition;
 * UVC is shown as in-progress until its capture pipeline is wired.
 */
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
    private val cameraButtons = mutableMapOf<UsbCameraSource, Button>()

    private fun setSwitchSilently(view: Switch, checked: Boolean) {
        suppressSwitch = true
        view.isChecked = checked
        suppressSwitch = false
    }

    /** True while a root probe is running, so resumes cannot pile up su calls. */
    private var probing = false
    /** Last confirmed session state; beginStop() needs it before the intent is cleared. */
    private var lastStatus: UsbPeripheralStatus.Snapshot? = null

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
                if (!isFinishing) renderStatus(snapshot)
            }
        }.start()
    }

    private fun renderStatus(snapshot: UsbPeripheralStatus.Snapshot) {
        lastStatus = snapshot
        when {
            snapshot.state == "error" -> {
                setSwitchSilently(master, false)
                showStatus("● 绑定失败", UiTheme.COLOR_DANGER, UiTheme.COLOR_DANGER_LIGHT)
                statusDetail.text = snapshot.hint ?: "请重新插拔数据线后重试"
            }
            snapshot.state == "armed" -> {
                setSwitchSilently(master, true)
                showStatus(
                    "● 插线后自动开启",
                    UiTheme.COLOR_ACCENT,
                    UiTheme.COLOR_ACCENT_LIGHT
                )
                statusDetail.text = snapshot.hint
                    ?: "主机接上后自动接管，无需再点开关"
            }
            snapshot.state == "armed_stop" -> {
                setSwitchSilently(master, false)
                showStatus(
                    "● 拔线后自动关闭",
                    UiTheme.COLOR_WARNING,
                    UiTheme.COLOR_WARNING_LIGHT
                )
                statusDetail.text = snapshot.hint ?: "数据线已插着 · 拔掉后自动关闭"
            }
            snapshot.active -> {
                setSwitchSilently(master, true)
                if (snapshot.hostAttached) {
                    showStatus("● 运行中", UiTheme.COLOR_SUCCESS, UiTheme.COLOR_SUCCESS_LIGHT)
                    statusDetail.text = "主机已识别 USB 键盘与触控板"
                } else {
                    showStatus(
                        "● 已就绪，等待主机",
                        UiTheme.COLOR_ACCENT,
                        UiTheme.COLOR_ACCENT_LIGHT
                    )
                    statusDetail.text = "插上数据线后自动生效，无需再次开启"
                }
            }
            snapshot.pending -> {
                setSwitchSilently(master, true)
                showStatus("● 正在绑定", UiTheme.COLOR_WARNING, UiTheme.COLOR_WARNING_LIGHT)
                statusDetail.text = "正在重组 USB gadget，请稍候…"
            }
            else -> {
                setSwitchSilently(master, false)
                showStatus("● 未开启", UiTheme.COLOR_TEXT_MUTED, UiTheme.COLOR_INPUT_BG)
                statusDetail.text = "USB 外设已关闭"
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(UsbPeripheralService.EXTRA_STATE)) {
                "ready" -> {
                    setSwitchSilently(master, true)
                    showStatus(
                        "● 键鼠已就绪",
                        UiTheme.COLOR_SUCCESS,
                        UiTheme.COLOR_SUCCESS_LIGHT
                    )
                    statusDetail.text = "主机可直接使用 USB 键盘与触控板"
                }
                "armed" -> {
                    setSwitchSilently(master, true)
                    showStatus(
                        "● 插线后自动开启",
                        UiTheme.COLOR_ACCENT,
                        UiTheme.COLOR_ACCENT_LIGHT
                    )
                    statusDetail.text = intent.getStringExtra(UsbPeripheralService.EXTRA_HINT)
                        ?: "主机接上后自动接管，无需再点开关"
                }
                "armed_stop" -> {
                    setSwitchSilently(master, false)
                    showStatus(
                        "● 拔线后自动关闭",
                        UiTheme.COLOR_WARNING,
                        UiTheme.COLOR_WARNING_LIGHT
                    )
                    statusDetail.text = intent.getStringExtra(UsbPeripheralService.EXTRA_HINT)
                        ?: "数据线已插着 · 拔掉后自动关闭"
                }
                "waiting" -> {
                    setSwitchSilently(master, true)
                    showStatus(
                        "● 等待主机",
                        UiTheme.COLOR_WARNING,
                        UiTheme.COLOR_WARNING_LIGHT
                    )
                    statusDetail.text = intent.getStringExtra(UsbPeripheralService.EXTRA_HINT)
                        ?: "请将 USB 数据线连接到主机，连接后会自动生效"
                }
                "error" -> {
                    setSwitchSilently(master, false)
                    showStatus(
                        "● 绑定失败",
                        UiTheme.COLOR_DANGER,
                        UiTheme.COLOR_DANGER_LIGHT
                    )
                    statusDetail.text = intent.getStringExtra(UsbPeripheralService.EXTRA_HINT)
                        ?: "请重新插拔数据线后重试"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraSource = runCatching {
            UsbCameraSource.valueOf(
                UsbSession.prefs(this).getString(
                    UsbSession.KEY_CAMERA_SOURCE, UsbCameraSource.OFF.name
                ) ?: UsbCameraSource.OFF.name
            )
        }.getOrDefault(UsbCameraSource.OFF)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.COLOR_BG)
        }
        root.addView(createPageHeader(this, "USB 外设", { finish() }))

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)

        content.addView(
            createSectionHeader(
                this,
                "连接模式",
                "USB 外设说明",
                "手机通过标准 USB 类协议（HID / UAC2 / UVC）作为主机外设。" +
                    "主机使用系统自带驱动，无需安装任何软件。"
            )
        )

        val modeCard = createCard(this)
        statusBadge = createStatusBadge(
            this, "● 未开启", UiTheme.COLOR_TEXT_MUTED, UiTheme.COLOR_INPUT_BG
        )
        modeCard.addView(statusBadge)

        master = Switch(this).apply {
            contentDescription = "USB 外设模式"
            isChecked = UsbSession.isEnabled(this@UsbPeripheralActivity)
            setOnCheckedChangeListener { _, checked ->
                if (suppressSwitch) return@setOnCheckedChangeListener
                if (checked) {
                    UsbSession.setEnabled(this@UsbPeripheralActivity, true)
                    showStatus(
                        "● 正在绑定",
                        UiTheme.COLOR_WARNING,
                        UiTheme.COLOR_WARNING_LIGHT
                    )
                    statusDetail.text = "正在重组 USB gadget，请稍候…"
                    startUsb()
                } else {
                    UsbSession.setEnabled(this@UsbPeripheralActivity, false)
                    beginStop()
                }
            }
        }
        modeCard.addView(settingRow(
            "USB 外设模式",
            "手机作为标准 USB 键盘 / 触控板接入主机",
            master
        ))

        audioSwitch = Switch(this).apply {
            contentDescription = "USB 麦克风与扬声器"
            isChecked = UsbSession.prefs(this@UsbPeripheralActivity)
                .getBoolean(UsbSession.KEY_AUDIO, false)
            setOnCheckedChangeListener { _, checked ->
                if (suppressSwitch) return@setOnCheckedChangeListener
                UsbSession.prefs(this@UsbPeripheralActivity).edit()
                    .putBoolean(UsbSession.KEY_AUDIO, checked).apply()
                if (master.isChecked) {
                    showStatus(
                        "● 正在重启",
                        UiTheme.COLOR_WARNING,
                        UiTheme.COLOR_WARNING_LIGHT
                    )
                    statusDetail.text = "正在应用音频设置…"
                    UsbPeripheralService.stop(this@UsbPeripheralActivity)
                    Handler(Looper.getMainLooper()).postDelayed({ startUsb() }, 1000)
                }
            }
        }
        modeCard.addView(createCardDivider(this))
        modeCard.addView(settingRow(
            "麦克风 + 扬声器",
            "UAC2 标准 USB 声卡：主机录音使用手机麦克风，主机播放从手机扬声器输出",
            audioSwitch
        ))
        content.addView(modeCard)

        statusDetail = TextView(this).apply {
            text = if (UsbSession.isEnabled(this@UsbPeripheralActivity))
                "USB 外设已开启" else "USB 外设已关闭"
            textSize = 12f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            setPadding(dp(22), dp(8), dp(22), dp(4))
        }
        content.addView(statusDetail)

        content.addView(
            createSectionHeader(
                this,
                "摄像头",
                "摄像头说明",
                "标准 USB Video 类（UVC）摄像头。前置 / 后置来自 Camera2，屏幕来自 MediaProjection；" +
                    "主机会看到一个普通摄像头设备。"
            )
        )
        val cameraCard = createCard(this)
        cameraCard.addView(TextView(this).apply {
            text = "画面来源"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
        })
        val cameraRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(4))
        }
        val cameraLabels = mapOf(
            UsbCameraSource.OFF to "关闭",
            UsbCameraSource.FRONT to "前置",
            UsbCameraSource.BACK to "后置",
            UsbCameraSource.SCREEN to "屏幕"
        )
        for ((source, label) in cameraLabels) {
            val button = Button(this).apply {
                text = label
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    marginEnd = dp(6)
                }
                onHapticClick { chooseCamera(source) }
            }
            cameraButtons[source] = button
            cameraRow.addView(button)
        }
        cameraCard.addView(cameraRow)
        cameraCard.addView(TextView(this).apply {
            text = "640×480 · 30 fps · YUYV。屏幕模式需要系统录屏授权；切换来源会重启 USB 会话。"
            textSize = 12f
            setTextColor(UiTheme.COLOR_TEXT_SECONDARY)
            setPadding(0, dp(4), 0, 0)
        })
        content.addView(cameraCard)
        updateCameraButtons()

        content.addView(TextView(this).apply {
            text = "键鼠面板与蓝牙共用：开启 USB 外设后，从首页「外设模式」进入，" +
                "使用同一个键盘/触控板界面。这里不再单独提供 USB 键盘入口。"
            textSize = 12f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            setPadding(dp(22), dp(14), dp(22), dp(4))
        })

        val actionCard = createCard(this)
        val stop = Button(this).apply {
            text = "停止并恢复普通 USB"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { topMargin = dp(8) }
            UiTheme.styleButton(
                this,
                UiTheme.COLOR_BTN_SECONDARY_BG,
                UiTheme.COLOR_BTN_SECONDARY_TEXT,
                12f
            )
            onHapticClick {
                setSwitchSilently(master, false)
                UsbSession.setEnabled(this@UsbPeripheralActivity, false)
                beginStop()
            }
        }
        actionCard.addView(stop)
        content.addView(actionCard)

        content.addView(TextView(this).apply {
            text = "不安装软件、不伪造私有协议：HID 1.11、USB Audio Class 2、USB Video Class 均为标准设备类。"
            textSize = 11f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            setPadding(dp(22), dp(14), dp(22), 0)
        })

        refreshFromRealState()
    }

    private fun settingRow(title: String, desc: String, control: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            val col = LinearLayout(this@UsbPeripheralActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this@UsbPeripheralActivity).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
            })
            col.addView(TextView(this@UsbPeripheralActivity).apply {
                text = desc
                textSize = 12f
                setTextColor(UiTheme.COLOR_TEXT_SECONDARY)
                setPadding(0, dp(4), dp(8), 0)
            })
            addView(col)
            addView(control)
        }

    private fun navRow(title: String, desc: String, action: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            val col = LinearLayout(this@UsbPeripheralActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this@UsbPeripheralActivity).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
            })
            col.addView(TextView(this@UsbPeripheralActivity).apply {
                text = desc
                textSize = 12f
                setTextColor(UiTheme.COLOR_TEXT_SECONDARY)
                setPadding(0, dp(4), dp(8), 0)
            })
            addView(col)
            addView(TextView(this@UsbPeripheralActivity).apply {
                text = action
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(
                    if (action == "接入中") UiTheme.COLOR_WARNING else UiTheme.COLOR_ACCENT
                )
            })
            addView(ImageView(this@UsbPeripheralActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4) }
            })
            onHapticClick(onClick)
        }

    private fun showStatus(text: String, textColor: Int, bgColor: Int) {
        statusBadge.text = text
        statusBadge.setTextColor(textColor)
        statusBadge.background = UiTheme.createPillDrawable(this, bgColor)
    }

    private fun beginStop() {
        showStatus("● 未开启", UiTheme.COLOR_TEXT_MUTED, UiTheme.COLOR_INPUT_BG)
        statusDetail.text = "正在恢复普通 USB 模式…"
        val ownedByService = UsbPeripheralService.running || lastStatus?.pending == true
        // Clear the record first so the home entry and this switch agree at once.
        UsbPeripheralStatus.forget(this)
        UsbPeripheralService.stop(this)
        if (ownedByService) {
            statusDetail.text = "已恢复普通 USB 模式"
            return
        }
        // A live session with no service (it outlived the process) still has to
        // release configfs, otherwise the phone stays in gadget mode.
        val ctx = this
        Thread {
            UsbGadgetController(ctx).stop()
            runOnUiThread {
                if (!isFinishing) statusDetail.text = "已恢复普通 USB 模式"
            }
        }.start()
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
        updateCameraButtons()
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
                showStatus("● 正在切换", UiTheme.COLOR_WARNING, UiTheme.COLOR_WARNING_LIGHT)
                statusDetail.text = "正在切换画面源…"
                UsbPeripheralService.switchCamera(this, source, screenResultCode, screenData)
            } else {
                showStatus("● 正在应用", UiTheme.COLOR_WARNING, UiTheme.COLOR_WARNING_LIGHT)
                statusDetail.text = "正在应用摄像头设置…"
                UsbPeripheralService.stop(this)
                Handler(Looper.getMainLooper()).postDelayed({ startUsb() }, 1000)
            }
        } else {
            statusDetail.text = when (source) {
                UsbCameraSource.OFF -> "摄像头已关闭"
                UsbCameraSource.FRONT -> "下次开启 USB 时使用前置摄像头"
                UsbCameraSource.BACK -> "下次开启 USB 时使用后置摄像头"
                UsbCameraSource.SCREEN -> "下次开启 USB 时共享屏幕画面"
            }
        }
    }

    private fun requestScreenConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), 61)
    }

    private fun updateCameraButtons() {
        cameraButtons.forEach { (source, button) ->
            val selected = source == cameraSource
            UiTheme.styleButton(
                button,
                if (selected) UiTheme.COLOR_ACCENT else UiTheme.COLOR_BTN_SECONDARY_BG,
                if (selected) android.graphics.Color.WHITE else UiTheme.COLOR_BTN_SECONDARY_TEXT,
                10f
            )
        }
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
                    statusDetail.text = "未授予录音权限，音频关闭"
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
