package dev.local.mihotspot

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.text.TextUtils
import android.widget.ProgressBar
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import dev.local.mihotspot.domain.HomeKitFeature
import dev.local.mihotspot.peripheral.usb.UsbPeripheralActivity
import dev.local.mihotspot.peripheral.usb.UsbPeripheralService
import dev.local.mihotspot.peripheral.usb.UsbPeripheralStatus
import dev.local.mihotspot.ui.MainViewModel
import dev.local.mihotspot.data.HotspotConfiguration
import dev.local.mihotspot.homekit.commands.CommandResult
import dev.local.mihotspot.homekit.PairingBroadcastMode
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.app.PendingIntent
import dev.local.mihotspot.ui.UiTheme
import dev.local.mihotspot.peripheral.BtHidPeripheral
import dev.local.mihotspot.peripheral.KeyHaptics
import dev.local.mihotspot.peripheral.KeySounds
import dev.local.mihotspot.peripheral.PeripheralHub
import dev.local.mihotspot.peripheral.PeripheralPadActivity
import dev.local.mihotspot.peripheral.PeripheralService
import dev.local.mihotspot.airdrop.AirDropState
import dev.local.mihotspot.airdrop.AirDropActivity
import dev.local.mihotspot.airdrop.AirDropResidentService
import dev.local.mihotspot.airdrop.AirDropService
import dev.local.mihotspot.airdrop.AirDropStore
import dev.local.mihotspot.airdrop.confirmAirDropReception
import dev.local.mihotspot.peripheral.usb.UsbCameraSource
import dev.local.mihotspot.peripheral.usb.UsbSession
import dev.local.mihotspot.peripheral.usb.isSwitchOn
import dev.local.mihotspot.airdrop.airDropStatus
import dev.local.mihotspot.peripheral.usb.usbStatus
import dev.local.mihotspot.ui.ProductUi
import dev.local.mihotspot.ui.ScreenRoutes
import dev.local.mihotspot.ui.StatusTone
import dev.local.mihotspot.ui.Symbol
import dev.local.mihotspot.ui.UiStatus
import dev.local.mihotspot.ui.renderStatus

private class SliderWriteState {
    val lock = Any()
    var queuedValue: Int? = null
    var pendingRunnable: Runnable? = null
    @Volatile var tracking = false
    @Volatile var workerRunning = false
    @Volatile var holdUntil = 0L
}

class MainActivity : Activity() {
    private enum class Route {
        HOME, HOMEKIT, PAIRING, DEVICE, MEDIA, NETWORK, HOTSPOT, SYSTEM, ACCESS, BROADCAST, RECOVERY,
        PERIPHERALS, BLUETOOTH, INPUT, FEEDBACK
    }

    private val ui by lazy { ProductUi(this) }
    private lateinit var routes: ScreenRoutes<Route>
    private val inputPrefs by lazy { getSharedPreferences(BtHidPeripheral.PREFS, MODE_PRIVATE) }
    private lateinit var statusBadge: TextView
    private lateinit var rootBadge: TextView
    private lateinit var statusInfo: TextView
    private lateinit var serviceSwitch: Switch
    private var updatingServiceSwitch = false
    private lateinit var hidSwitch: Switch
    private lateinit var hidDetailText: TextView
    private var hidState: BtHidPeripheral.State = BtHidPeripheral.State.STOPPED
    private var lastHidFailure: String? = null
    private val usbStateProbe = AtomicBoolean(false)
    private var hidPageBadge: TextView? = null
    private var bondedHosts: LinearLayout? = null
    private var lastBondedHosts: List<Pair<String, String>>? = null
    private var updatingHidSwitch = false
    private var pendingHidEnable = false
    private var brightnessSeek: SeekBar? = null
    private var volumeSeek: SeekBar? = null
    private var brightnessValueText: TextView? = null
    private var volumeValueText: TextView? = null
    private val featureButtons = mutableMapOf<HomeKitFeature, Button>()
    private val featureLoading = mutableSetOf<HomeKitFeature>()
    private val refreshingState = AtomicBoolean(false)
    private val stateRefreshHandler = Handler(Looper.getMainLooper())
    private val stateRefreshRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                updateConnectionStatus(refreshRoot = false)
                refreshDeviceState()
                // Cheap re-read of the single USB source; heals a missed broadcast.
                refreshPeripheralEntry()
                stateRefreshHandler.postDelayed(this, 3_000L)
            }
        }
    }
    private val brightnessDebounce = Handler(Looper.getMainLooper())
    private val volumeDebounce = Handler(Looper.getMainLooper())
    private val brightnessSliderState = SliderWriteState()
    private val volumeSliderState = SliderWriteState()
    private val viewModel by lazy { MainViewModel(this) }
    private var pairingState: TextView? = null
    private var pairingQrView: ImageView? = null
    private var pairingCode: TextView? = null
    private var pairingHint: TextView? = null
    private var renderedSetupPayload: String? = null
    private var deviceNameValue: TextView? = null
    private var pairingModeSummary: TextView? = null
    private var strategySummary: TextView? = null
    private lateinit var overviewHomeKitStatus: TextView
    private lateinit var overviewHomeKitDetail: TextView
    private lateinit var overviewHomeKitSwitch: Switch
    private lateinit var overviewAirDropStatus: TextView
    private lateinit var overviewAirDropDetail: TextView
    private lateinit var overviewAirDropSwitch: Switch
    private lateinit var overviewAirDropProgress: ProgressBar
    private lateinit var overviewBluetoothStatus: TextView
    private lateinit var overviewBluetoothDetail: TextView
    private lateinit var overviewBluetoothSwitch: Switch
    private lateinit var overviewUsbStatus: TextView
    private lateinit var overviewUsbDetail: TextView
    private lateinit var overviewUsbSwitch: Switch
    private var updatingOverviewSwitches = false
    private var airDropTarget: Boolean? = null
    private var airDropRequestUntil = 0L
    private var usbStartRequested = false
    private var usbStopRequestedUntil = 0L
    private var peripheralHidStatus: TextView? = null
    private var peripheralUsbStatus: TextView? = null
    private var keyboardShortcut: View? = null
    private val airDropUiListener: () -> Unit = { if (!isDestroyed) refreshAirDropCard() }
    private var eventsRegistered = false
    private var nfcWriteArmed = false
    private var nfcStatusText: TextView? = null
    private val nfcAdapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(this) }
    private val nfcPendingIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this, 7001,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val uiEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                HomeKitUiEvents.ACTION_RUNTIME_STATUS -> {
                    updateConnectionStatus(refreshRoot = false)
                }
                HomeKitUiEvents.ACTION_STATE_CHANGED -> {
                    applyStateEvent(intent)
                }
                UsbPeripheralService.ACTION_STATE -> {
                    // The service just published into UsbPeripheralStatus, so the
                    // cheap snapshot is already up to date.
                    refreshPeripheralEntry()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbStartRequested = savedInstanceState?.getBoolean("usbStartRequested") == true
        pendingHidEnable = savedInstanceState?.getBoolean("pendingHidEnable") == true
        setContentView(buildUi(savedInstanceState))
        updatePairingCard()
        updateConnectionStatus()
        PeripheralHub.bridge(this).addListener(hidListener)
        // Restore before painting: applyHidState() retires a stored "on" intent
        // when the bridge is idle, and that must not race the restore.
        ensureHidRunningIfEnabled()
        applyHidState(
            PeripheralHub.bridge(this).state,
            PeripheralHub.bridge(this).hostName
        )
        refreshUsbPeripheralState(verify = true)
        log("PROBE_CREATED source=Apple_HomeKitADK_f201f98")
        if (ServiceControl.isEnabled(this)) ensurePermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        registerUiEvents()
        AirDropState.addListener(airDropUiListener)
        if (nfcWriteArmed) enableNfcForegroundDispatch()
        updateConnectionStatus(refreshRoot = false)
        updateNfcStatus()
        refreshDeviceState()
        // The USB session can have been started/changed while this activity was
        // paused (or the process restarted); re-derive both entries from state.
        ensureHidRunningIfEnabled()
        applyHidState(PeripheralHub.bridge(this).state, PeripheralHub.bridge(this).hostName)
        refreshUsbPeripheralState(verify = true)
        refreshOverviewStatuses()
        stateRefreshHandler.removeCallbacks(stateRefreshRunnable)
        stateRefreshHandler.postDelayed(stateRefreshRunnable, 3_000L)
    }

    override fun onPause() {
        stateRefreshHandler.removeCallbacks(stateRefreshRunnable)
        disableNfcForegroundDispatch()
        unregisterUiEvents()
        AirDropState.removeListener(airDropUiListener)
        super.onPause()
    }

    override fun onDestroy() {
        PeripheralHub.bridge(this).removeListener(hidListener)
        stateRefreshHandler.removeCallbacks(stateRefreshRunnable)
        disableNfcForegroundDispatch()
        unregisterUiEvents()
        super.onDestroy()
        viewModel.close()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("route", routes.current?.name)
        outState.putBoolean("usbStartRequested", usbStartRequested)
        outState.putBoolean("pendingHidEnable", pendingHidEnable)
    }

    @Deprecated("Legacy Activity Back API")
    override fun onBackPressed() {
        if (!routes.back()) super.onBackPressed()
    }

    private fun dp(v: Float): Int = UiTheme.dp(this, v)
    private fun dp(v: Int): Int = dp(v.toFloat())

    private fun buildUi(saved: Bundle?): View {
        routes = ScreenRoutes(this)
        routes.register(Route.HOME, null, buildOverviewPage())
        page(Route.HOMEKIT, Route.HOME, "HomeKit") {
            serviceSwitch = settingSwitch("启用 HomeKit", ServiceControl.isEnabled(this@MainActivity), ::changeHomeKitEnabled)
            statusBadge = ui.status()
            addView(ui.surface().apply {
                addView(ui.row("家庭连接", "通过蓝牙在 Apple 家庭中使用这台手机", serviceSwitch, Symbol.HOME))
                addView(statusBadge, LinearLayout.LayoutParams(-2, -2))
            })
            addView(ui.surface().apply {
                addView(ui.row("配对与设备名称", "扫码、输入配对码或写入 NFC 标签", symbol = Symbol.LINK) { open(Route.PAIRING) })
                addView(ui.row("本机控制", "显示与声音、网络、设备状态", symbol = Symbol.SLIDERS) { open(Route.DEVICE) })
                addView(ui.row("家庭控制权限", "选择 Apple 家庭可以操作的项目", symbol = Symbol.SETTINGS) { open(Route.ACCESS) })
            })
            addView(ui.surface().apply {
                addView(ui.row("广播与响应", "选择发现速度和功耗", symbol = Symbol.BLUETOOTH) { open(Route.BROADCAST) })
                addView(ui.row("配件维护", "查看配件 ID、修复或清除配对", symbol = Symbol.POWER) { open(Route.RECOVERY) })
            })
        }
        page(Route.PAIRING, Route.HOMEKIT, "配对与设备名称") {
            pairingState = ui.text("", 16, bold = true)
            pairingHint = ui.text("", 13, UiTheme.COLOR_TEXT_SECONDARY)
            pairingCode = ui.text("", 22, UiTheme.COLOR_ACCENT, bold = true)
            pairingQrView = ImageView(this@MainActivity).apply {
                contentDescription = "HomeKit 配对二维码"
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(188), dp(188)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            }
            addView(ui.surface().apply {
                addView(pairingState)
                addView(pairingQrView)
                addView(pairingCode)
                addView(pairingHint)
            })
            addView(ui.surface().apply {
                addView(ui.row("设备名称", trailing = ui.text(viewModel.deviceName).also { deviceNameValue = it }) {
                    editName("HomeKit 设备名称", viewModel.deviceName) {
                        viewModel.saveDeviceName(it)
                        deviceNameValue?.text = it
                        restartHomeKitIfEnabled()
                    }
                })
                addView(ui.row("配对方式", trailing = ui.text("").also { pairingModeSummary = it }) { choosePairingMode() })
                addView(ui.row("修改 8 位配对码") { showSetupCodeDialog() })
                addView(ui.row("写入 NFC 标签", "使用可写的 NFC Forum Type 2 标签") { armNfcWriter() })
                nfcStatusText = ui.text("", 12, UiTheme.COLOR_TEXT_SECONDARY)
                addView(nfcStatusText)
            })
        }
        page(Route.DEVICE, Route.HOMEKIT, "本机控制") {
            addView(ui.surface().apply {
                addView(ui.row("显示与声音", "屏幕、亮度、媒体音量、静音", symbol = Symbol.DISPLAY) { open(Route.MEDIA) })
                addView(ui.row("网络与定位", "热点、移动数据、定位", symbol = Symbol.NETWORK) { open(Route.NETWORK) })
                addView(ui.row("设备状态", "手电筒、省电和勿扰", symbol = Symbol.POWER) { open(Route.SYSTEM) })
            })
        }
        page(Route.MEDIA, Route.DEVICE, "显示与声音") {
            addView(ui.surface().apply {
                addView(featureControl(HomeKitFeature.SCREEN))
                addView(liveSlider("屏幕亮度", brightnessSliderState, brightnessDebounce, ::setBrightnessFromUi) { seek, value ->
                    brightnessSeek = seek
                    brightnessValueText = value
                })
            })
            addView(ui.surface().apply {
                addView(featureControl(HomeKitFeature.MUTE))
                addView(liveSlider("媒体音量", volumeSliderState, volumeDebounce, ::setVolumeFromUi) { seek, value ->
                    volumeSeek = seek
                    volumeValueText = value
                })
            })
        }
        page(Route.NETWORK, Route.DEVICE, "网络与定位") {
            addView(ui.surface().apply {
                addView(featureControl(HomeKitFeature.HOTSPOT))
                addView(ui.row("配置热点", "名称、密码、频段和自动关闭") { open(Route.HOTSPOT) })
            })
            addView(ui.surface().apply {
                addView(featureControl(HomeKitFeature.MOBILE_DATA))
                addView(featureControl(HomeKitFeature.GPS))
            })
        }
        page(Route.HOTSPOT, Route.NETWORK, "配置热点") { addView(buildHotspotSettings()) }
        page(Route.SYSTEM, Route.DEVICE, "设备状态") {
            addView(ui.surface().apply {
                addView(featureControl(HomeKitFeature.FLASHLIGHT))
                addView(featureControl(HomeKitFeature.LOW_POWER_MODE))
                addView(featureControl(HomeKitFeature.DO_NOT_DISTURB))
            })
        }
        page(Route.ACCESS, Route.HOMEKIT, "家庭控制权限") {
            addView(ui.text("关闭某项权限后，仍可在本机操作。", 14, UiTheme.COLOR_TEXT_SECONDARY))
            listOf(
                "显示与声音" to listOf(HomeKitFeature.SCREEN, HomeKitFeature.BRIGHTNESS, HomeKitFeature.MUTE),
                "网络与定位" to listOf(HomeKitFeature.HOTSPOT, HomeKitFeature.MOBILE_DATA, HomeKitFeature.GPS),
                "设备状态" to listOf(HomeKitFeature.FLASHLIGHT, HomeKitFeature.LOW_POWER_MODE, HomeKitFeature.DO_NOT_DISTURB)
            ).forEach { (label, features) ->
                addView(ui.section(label))
                addView(ui.surface().apply {
                    features.forEach { feature ->
                        addView(ui.row(feature.title, trailing = settingSwitch(feature.title, viewModel.isFeatureEnabled(feature)) {
                            viewModel.setFeatureEnabled(feature, it)
                        }))
                    }
                })
            }
        }
        page(Route.BROADCAST, Route.HOMEKIT, "广播与响应") {
            strategySummary = ui.status()
            addView(ui.surface().apply {
                addView(strategySummary, LinearLayout.LayoutParams(-2, -2))
                addView(ui.row("响应策略", "自动模式会在夜间降低功耗") { chooseBroadcastStrategy() })
            })
        }
        page(Route.RECOVERY, Route.HOMEKIT, "配件维护") {
            statusInfo = ui.text("")
            rootBadge = ui.status()
            addView(ui.surface().apply {
                addView(statusInfo)
                addView(rootBadge, LinearLayout.LayoutParams(-2, -2))
            })
            addView(ui.surface().apply {
                addView(ui.row("清除家庭配对", "保留配件 ID，之后可重新配对") {
                    confirmAction("清除家庭配对？", "需要在 Apple 家庭中重新添加配件。", ::clearPairingState)
                })
                addView(ui.row("重置配件 ID", "为这台手机生成新的配件身份") {
                    confirmAction("重置配件 ID？", "会同时清除现有配对。", ::resetAccessoryId)
                })
                addView(ui.row("修复配对与名称", "更换配件身份并清理缓存", action = ::confirmHomeKitRecovery))
            })
        }
        page(Route.PERIPHERALS, Route.HOME, "外设") {
            val bt = ui.status().also { peripheralHidStatus = it }
            val usb = ui.status().also { peripheralUsbStatus = it }
            addView(ui.surface().apply {
                addView(ui.row("蓝牙键鼠", "配对或切换主机", bt, Symbol.BLUETOOTH) { open(Route.BLUETOOTH) })
                addView(ui.row("USB 外设", "键鼠、摄像头、麦克风和扬声器", usb, Symbol.USB) {
                    startActivity(Intent(this@MainActivity, UsbPeripheralActivity::class.java))
                })
            })
            addView(ui.surface().apply {
                addView(ui.row("键盘与触控板", "调整显示方式、指针速度和按键反馈", symbol = Symbol.KEYBOARD) { open(Route.INPUT) })
            })
        }
        page(Route.BLUETOOTH, Route.PERIPHERALS, "蓝牙键鼠") { buildBluetoothSettings(this) }
        page(Route.INPUT, Route.PERIPHERALS, "键盘与触控板") { buildInputSettings(this) }
        page(Route.FEEDBACK, Route.INPUT, "按键反馈") { buildFeedbackSettings(this) }

        val restored = Route.entries.firstOrNull { it.name == saved?.getString("route") } ?: Route.HOME
        return routes.host.also { open(restored) }
    }

    private fun page(route: Route, parent: Route, title: String, content: LinearLayout.() -> Unit) {
        routes.register(route, parent, ui.screen(title, ::back, content))
    }

    private fun open(route: Route) {
        routes.show(route)
        if (route == Route.BLUETOOTH) refreshBondedHosts()
        if (route == Route.BROADCAST) updateBroadcastStrategySummary()
        if (route == Route.PAIRING) updatePairingCard()
        if (route == Route.MEDIA || route == Route.NETWORK || route == Route.SYSTEM) refreshDeviceState()
    }

    private fun back() {
        if (!routes.back()) finish()
    }

    private fun buildOverviewPage(): View = ui.screen(null) {
        addView(ui.heading("Marionette"))
        overviewHomeKitStatus = ui.status()
        overviewHomeKitDetail = overviewDetail()
        overviewHomeKitSwitch = settingSwitch("首页 HomeKit 开关", ServiceControl.isEnabled(this@MainActivity), ::changeHomeKitEnabled)
        addView(ui.featureCard("HomeKit", Symbol.HOME, 0xFF865410.toInt(), 0xFFFFEFD5.toInt(),
            action = { open(Route.HOMEKIT) }) {
            addView(ui.statusControl("家庭服务", overviewHomeKitStatus, overviewHomeKitDetail, overviewHomeKitSwitch))
        })

        overviewAirDropStatus = ui.status()
        overviewAirDropDetail = overviewDetail()
        overviewAirDropSwitch = settingSwitch("首页 AirDrop 接收开关", false, ::changeAirDropReception)
        overviewAirDropProgress = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            contentDescription = "AirDrop 传输进度"
            layoutParams = LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8) }
        }
        addView(ui.featureCard("AirDrop", Symbol.AIRDROP, 0xFF2D568D.toInt(), 0xFFE5EDFA.toInt(),
            action = { startActivity(Intent(this@MainActivity, AirDropActivity::class.java)) }) {
            addView(ui.statusControl("接收", overviewAirDropStatus, overviewAirDropDetail, overviewAirDropSwitch))
            addView(overviewAirDropProgress)
        })

        overviewBluetoothStatus = ui.status()
        overviewBluetoothDetail = overviewDetail()
        overviewBluetoothSwitch = settingSwitch("首页蓝牙键鼠开关", false, ::changeHidEnabled)
        overviewUsbStatus = ui.status()
        overviewUsbDetail = overviewDetail()
        overviewUsbSwitch = settingSwitch("首页 USB 外设开关", false, ::changeUsbEnabled)
        keyboardShortcut = ui.iconAction(Symbol.KEYBOARD, "打开键盘与触控板", ::openKeyboard).apply {
            visibility = View.GONE
        }
        addView(ui.featureCard("外设", Symbol.PERIPHERALS, UiTheme.COLOR_ACCENT, UiTheme.COLOR_ACCENT_LIGHT,
            shortcut = keyboardShortcut, action = { open(Route.PERIPHERALS) }) {
            addView(ui.statusControl("蓝牙键鼠", overviewBluetoothStatus, overviewBluetoothDetail, overviewBluetoothSwitch))
            addView(View(this@MainActivity).apply {
                setBackgroundColor(UiTheme.COLOR_DIVIDER)
                layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8) }
            })
            addView(ui.statusControl("USB 外设", overviewUsbStatus, overviewUsbDetail, overviewUsbSwitch))
        })
    }

    private fun overviewDetail() = ui.text("", 12, UiTheme.COLOR_TEXT_SECONDARY).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun changeHomeKitEnabled(enabled: Boolean) {
        if (updatingServiceSwitch) return
        if (enabled) ensurePermissionsAndStart() else stopHomeKitService()
        refreshOverviewStatuses()
    }

    private fun changeHidEnabled(enabled: Boolean) {
        if (updatingHidSwitch) return
        if (enabled) enableHidFromUi() else disableHidFromUi()
        refreshOverviewStatuses()
    }

    private fun changeAirDropReception(enabled: Boolean) {
        if (updatingOverviewSwitches || airDropTarget != null) return
        val resident = AirDropResidentService.isEnabled(this)
        refreshOverviewStatuses()
        when {
            enabled -> confirmAirDropReception(this) {
                if (!isAirDropReceptionEnabled()) {
                    requestAirDropReception(true) { AirDropService.startWindow(this) }
                }
            }
            resident -> AlertDialog.Builder(this)
                .setTitle("关闭 AirDrop 常驻接收？")
                .setMessage("关闭后也会停用开机自动接收。")
                .setPositiveButton("关闭") { _, _ ->
                    requestAirDropReception(false) { AirDropResidentService.disable(this) }
                }
                .setNegativeButton("取消", null).show()
            else -> requestAirDropReception(false) { AirDropService.stop(this) }
        }
    }

    private fun isAirDropReceptionEnabled(): Boolean =
        AirDropResidentService.isEnabled(this) || AirDropService.isAlive() || AirDropService.isRunning(this)

    private fun requestAirDropReception(enabled: Boolean, command: () -> Unit) {
        airDropTarget = enabled
        airDropRequestUntil = SystemClock.uptimeMillis() + 5_000L
        runCatching(command).onFailure {
            airDropTarget = null
            toast(if (enabled) "无法启动 AirDrop" else "无法停止 AirDrop")
        }
        refreshOverviewStatuses()
    }

    private fun changeUsbEnabled(enabled: Boolean) {
        if (updatingOverviewSwitches) return
        if (enabled) {
            usbStartRequested = true
            refreshOverviewStatuses()
            authorizeUsbStart()
        } else {
            runCatching { UsbPeripheralService.stopFromUi(this) }
                .onSuccess { usbStopRequestedUntil = SystemClock.uptimeMillis() + 5_000L }
                .onFailure { toast("无法停止 USB 外设") }
            refreshOverviewStatuses()
        }
    }

    private fun authorizeUsbStart() {
        val camera = usbCameraPreference()
        val audio = UsbSession.prefs(this).getBoolean(UsbSession.KEY_AUDIO, false)
        val missing = buildList {
            if (audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if ((camera == UsbCameraSource.FRONT || camera == UsbCameraSource.BACK) &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
        }
        when {
            missing.isNotEmpty() -> requestPermissions(missing.toTypedArray(), REQUEST_USB_PERMISSIONS)
            camera == UsbCameraSource.SCREEN -> {
                val projection = getSystemService(MediaProjectionManager::class.java)
                @Suppress("DEPRECATION")
                startActivityForResult(projection.createScreenCaptureIntent(), REQUEST_USB_SCREEN)
            }
            else -> startUsbFromOverview()
        }
    }

    private fun startUsbFromOverview(screenResult: Int = 0, screenData: Intent? = null) {
        usbStartRequested = false
        val camera = usbCameraPreference()
        val audio = UsbSession.prefs(this).getBoolean(UsbSession.KEY_AUDIO, false)
        runCatching {
            UsbPeripheralService.start(this, camera, audio, screenResult, screenData)
            UsbSession.setEnabled(this, true)
        }.onFailure { toast("无法启动 USB 外设") }
        refreshOverviewStatuses()
    }

    private fun usbCameraPreference(): UsbCameraSource =
        runCatching {
            UsbCameraSource.valueOf(UsbSession.prefs(this).getString(UsbSession.KEY_CAMERA_SOURCE, "OFF") ?: "OFF")
        }.getOrDefault(UsbCameraSource.OFF)

    @Deprecated("Legacy Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_USB_SCREEN || !usbStartRequested) return
        if (resultCode == RESULT_OK && data != null) startUsbFromOverview(resultCode, data)
        else {
            usbStartRequested = false
            refreshOverviewStatuses()
            toast("未授权屏幕录制")
        }
    }

    private fun openKeyboard() {
        val transport = keyboardTransport(UsbPeripheralStatus.snapshot(this))
        if (transport == null) {
            refreshPeripheralEntry()
            toast("主机已断开")
            return
        }
        startActivity(PeripheralPadActivity.intent(this, inputPrefs.getBoolean("immersive_keyboard", false), transport))
    }

    private fun keyboardTransport(usb: UsbPeripheralStatus.Snapshot): String? = when {
        usb.state == "ready" && usb.hostAttached && SystemClock.uptimeMillis() >= usbStopRequestedUntil ->
            PeripheralPadActivity.TRANSPORT_USB
        hidState == BtHidPeripheral.State.CONNECTED -> PeripheralPadActivity.TRANSPORT_BT
        else -> null
    }

    private fun refreshOverviewStatuses(usb: UsbPeripheralStatus.Snapshot = UsbPeripheralStatus.snapshot(this)) {
        val homeKitEnabled = ServiceControl.isEnabled(this)
        val paired = viewModel.isPaired
        val advertising = getSharedPreferences("homekit", MODE_PRIVATE).getBoolean("runtime_advertising", false)
        val homeKit = when {
            !homeKitEnabled -> UiStatus("已关闭")
            viewModel.isConnected -> UiStatus("家庭已连接", StatusTone.ACTIVE)
            advertising -> UiStatus("广播中", StatusTone.WAITING)
            else -> UiStatus("等待广播", StatusTone.BUSY)
        }
        overviewHomeKitStatus.renderStatus(homeKit)
        if (::statusBadge.isInitialized) statusBadge.renderStatus(homeKit)
        val pairing = if (paired) "已配对" else "未配对"
        val strategy = ServiceControl.effectiveBroadcastStrategy(this, paired).title
        overviewHomeKitDetail.text = if (homeKitEnabled) "${viewModel.deviceName} · $pairing · $strategy"
            else "${viewModel.deviceName} · $pairing"
        updatingServiceSwitch = true
        overviewHomeKitSwitch.isChecked = homeKitEnabled
        if (::serviceSwitch.isInitialized) serviceSwitch.isChecked = homeKitEnabled
        updatingServiceSwitch = false

        val bridge = PeripheralHub.bridge(this)
        val hid = when (hidState) {
            BtHidPeripheral.State.CONNECTED -> UiStatus("已连接", StatusTone.ACTIVE)
            BtHidPeripheral.State.READY -> UiStatus("等待主机", StatusTone.WAITING)
            BtHidPeripheral.State.REGISTERING -> UiStatus("注册中", StatusTone.BUSY)
            BtHidPeripheral.State.FAILED -> UiStatus("连接异常", StatusTone.ERROR)
            BtHidPeripheral.State.UNSUPPORTED -> UiStatus("不支持", StatusTone.ERROR)
            BtHidPeripheral.State.STOPPED -> if (bridge.isRunning)
                UiStatus("重连中", StatusTone.BUSY) else UiStatus("已关闭")
        }
        val hidEnabled = when (hidState) {
            BtHidPeripheral.State.CONNECTED, BtHidPeripheral.State.READY, BtHidPeripheral.State.REGISTERING -> true
            BtHidPeripheral.State.STOPPED -> bridge.isRunning
            else -> false
        }
        updatingHidSwitch = true
        overviewBluetoothSwitch.isChecked = hidEnabled || pendingHidEnable
        overviewBluetoothSwitch.isEnabled = !pendingHidEnable
        if (::hidSwitch.isInitialized) {
            hidSwitch.isChecked = hidEnabled || pendingHidEnable
            hidSwitch.isEnabled = !pendingHidEnable
        }
        updatingHidSwitch = false
        overviewBluetoothStatus.renderStatus(hid)
        overviewBluetoothDetail.text = when {
            pendingHidEnable -> "等待系统授权"
            hidState == BtHidPeripheral.State.CONNECTED -> bridge.hostName?.let { "主机 · $it" } ?: "主机已连接"
            hidState == BtHidPeripheral.State.FAILED -> lastHidFailure ?: "注册失败"
            else -> "设备名称 · ${bridge.deviceName}"
        }

        val usbSwitchOn = usb.isSwitchOn()
        if (!usbSwitchOn) usbStopRequestedUntil = 0L
        val usbStopRequested = SystemClock.uptimeMillis() < usbStopRequestedUntil
        val wired = when {
            usbStartRequested -> UiStatus("等待授权", StatusTone.BUSY)
            usbStopRequested -> UiStatus("正在停止", StatusTone.BUSY)
            else -> usbStatus(usb)
        }
        overviewUsbStatus.renderStatus(wired)
        overviewUsbDetail.text = if (usbStopRequested) "正在恢复普通 USB 模式" else usbOverviewDetail(usb)
        updatingOverviewSwitches = true
        overviewUsbSwitch.isChecked = usbStartRequested || (usbSwitchOn && !usbStopRequested)
        overviewUsbSwitch.isEnabled = !usbStartRequested && !usbStopRequested &&
            usb.state !in listOf("starting", "stopping", "armed_stop")
        updatingOverviewSwitches = false
        peripheralHidStatus?.renderStatus(hid)
        peripheralUsbStatus?.renderStatus(wired)
        hidPageBadge?.renderStatus(hid)
        keyboardShortcut?.visibility = if (keyboardTransport(usb) != null) View.VISIBLE else View.GONE
        refreshAirDropCard()
    }

    private fun usbOverviewDetail(usb: UsbPeripheralStatus.Snapshot): String {
        if (usbStartRequested) return "等待相机、麦克风或录屏授权"
        usb.hint?.takeIf { it.isNotBlank() }?.let { return it }
        val camera = usbCameraPreference()
        val activeCamera = UsbPeripheralService.streamingCamera()
        val wantAudio = UsbSession.prefs(this).getBoolean(UsbSession.KEY_AUDIO, false)
        return buildList {
            add("键盘与触控板")
            if (camera != UsbCameraSource.OFF || activeCamera != UsbCameraSource.OFF) {
                val label = when (if (activeCamera == UsbCameraSource.OFF) camera else activeCamera) {
                    UsbCameraSource.FRONT -> "前置摄像头"
                    UsbCameraSource.BACK -> "后置摄像头"
                    UsbCameraSource.SCREEN -> "屏幕"
                    UsbCameraSource.OFF -> ""
                }
                add(if (activeCamera != UsbCameraSource.OFF) label else "${label}待启用")
            }
            if (UsbPeripheralService.streamingAudio()) add("音频")
            else if (wantAudio) add("音频待启用")
        }.joinToString(" · ")
    }

    private fun refreshAirDropCard() {
        val resident = AirDropResidentService.isEnabled(this)
        val receiving = isAirDropReceptionEnabled()
        if (airDropTarget == receiving || SystemClock.uptimeMillis() >= airDropRequestUntil) airDropTarget = null
        val target = airDropTarget
        val status = when (target) {
            true -> UiStatus("启动中", StatusTone.BUSY)
            false -> UiStatus("正在停止", StatusTone.BUSY)
            null -> airDropStatus(this)
        }
        overviewAirDropStatus.renderStatus(status)
        updatingOverviewSwitches = true
        overviewAirDropSwitch.isChecked = target ?: receiving
        overviewAirDropSwitch.isEnabled = target == null
        updatingOverviewSwitches = false

        val phase = AirDropState.phase
        val progress = AirDropState.progress
        val transferring = phase == AirDropState.Phase.RECEIVING || phase == AirDropState.Phase.SENDING
        val mode = if (resident) "常驻接收" else "限时接收"
        overviewAirDropDetail.text = when {
            target == false -> "正在结束接收会话"
            target == true -> "正在准备附近发现"
            transferring && progress != null -> buildString {
                if (AirDropState.peerName.isNotBlank()) append("${AirDropState.peerName} · ")
                append(AirDropStore.formatSize(progress.done))
                if (progress.total > 0) append(" / ${AirDropStore.formatSize(progress.total)}")
            }
            phase == AirDropState.Phase.AWAITING || phase == AirDropState.Phase.ERROR ||
                phase == AirDropState.Phase.DONE || phase == AirDropState.Phase.STARTING -> AirDropState.status
            receiving -> "$mode · 附近 ${AirDropState.peers().size} 台设备"
            else -> "开启后接收 10 分钟"
        }
        overviewAirDropProgress.visibility = if (transferring) View.VISIBLE else View.GONE
        if (transferring) {
            val fraction = progress?.fraction
            overviewAirDropProgress.isIndeterminate = fraction == null
            if (fraction != null) overviewAirDropProgress.progress = (fraction * 100).toInt()
        }
    }

    private fun featureControl(feature: HomeKitFeature): View {
        val button = ui.button("读取中") { runCommandFromUi(feature) }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, -2)
            isEnabled = false
            contentDescription = feature.title
        }
        featureButtons[feature] = button
        return ui.row(feature.title, trailing = button)
    }

    private fun liveSlider(
        label: String, state: SliderWriteState, debounce: Handler, write: (Int) -> Unit,
        bind: (SeekBar, TextView) -> Unit
    ) = ui.column().apply {
        val value = ui.text("—", 14, UiTheme.COLOR_ACCENT, bold = true)
        val seek = SeekBar(this@MainActivity).apply {
            max = 100
            contentDescription = label
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    value.text = "$progress%"
                    scheduleSliderWrite(state, debounce, progress, write)
                }
                override fun onStartTrackingTouch(bar: SeekBar) {
                    state.tracking = true
                    state.pendingRunnable?.let(debounce::removeCallbacks)
                    state.pendingRunnable = null
                }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    state.pendingRunnable?.let(debounce::removeCallbacks)
                    state.pendingRunnable = null
                    state.tracking = false
                    write(bar.progress)
                }
            })
        }
        addView(ui.row(label, trailing = value))
        addView(seek)
        bind(seek, value)
    }

    private fun settingSwitch(label: String, enabled: Boolean, change: (Boolean) -> Unit) = Switch(this).apply {
        contentDescription = label
        isChecked = enabled
        setOnCheckedChangeListener { _, checked -> change(checked) }
    }

    private fun buildBluetoothSettings(content: LinearLayout) = with(content) {
        val bridge = PeripheralHub.bridge(this@MainActivity)
        hidSwitch = settingSwitch("启用蓝牙键鼠", bridge.isRunning, ::changeHidEnabled)
        hidPageBadge = ui.status()
        hidDetailText = ui.text("", 13, UiTheme.COLOR_DANGER).apply { visibility = View.GONE }
        addView(ui.surface().apply {
            addView(ui.row("蓝牙键鼠", trailing = hidSwitch, symbol = Symbol.BLUETOOTH))
            addView(hidPageBadge, LinearLayout.LayoutParams(-2, -2))
            addView(hidDetailText)
            addView(ui.button("让主机发现手机", primary = true, action = ::requestHidDiscoverable))
        })
        addView(ui.section("已配对主机"))
        bondedHosts = ui.surface()
        addView(bondedHosts)
        addView(ui.surface().apply {
            addView(ui.row("自动连接上次主机", trailing = settingSwitch("自动连接上次主机", bridge.autoReconnectLastHost) {
                bridge.autoReconnectLastHost = it
            }))
            addView(ui.row("低延迟保活", "空闲时保持连接活跃", settingSwitch("低延迟保活", bridge.keepAliveEnabled) {
                bridge.keepAliveEnabled = it
            }))
            val name = ui.text(bridge.deviceName)
            addView(ui.row("蓝牙名称", trailing = name) {
                editName("蓝牙名称", bridge.deviceName) { bridge.deviceName = it; name.text = it }
            })
        })
        addView(ui.surface().apply {
            addView(ui.row("重新连接已配对主机") {
                bridge.start()
                if (!bridge.connectBonded()) toast("没有可连接的已配对主机")
            })
            addView(ui.row("断开当前主机") {
                toast(if (bridge.disconnectHost()) "已请求断开" else "当前没有已连接主机")
            })
            addView(ui.row("重置蓝牙连接", "用于修复 HID 注册失败") {
                confirmAction("重置蓝牙连接？", "系统蓝牙连接会暂时中断。") {
                    Thread {
                        val success = bridge.resetBluetoothStack()
                        runOnUiThread { toast(if (success) "蓝牙已重置，正在重新注册" else "重置失败，请检查 Root 授权") }
                    }.start()
                }
            })
        })
    }

    private fun refreshBondedHosts() {
        val container = bondedHosts ?: return
        val bridge = PeripheralHub.bridge(this)
        val devices = bridge.bondedDevices()
        val hosts = devices.map { device ->
            device.address to (runCatching { device.name }.getOrNull()?.takeIf(String::isNotBlank) ?: device.address)
        }
        if (hosts == lastBondedHosts) return
        lastBondedHosts = hosts
        container.removeAllViews()
        if (hosts.isEmpty()) container.addView(ui.text("暂无已配对主机", 14, UiTheme.COLOR_TEXT_SECONDARY))
        hosts.forEach { (address, name) ->
            container.addView(ui.row(name, symbol = Symbol.DISPLAY) {
                bridge.start()
                toast(if (bridge.connectTo(address)) "正在连接 $name" else "连接请求未成功")
            })
        }
    }

    private fun buildInputSettings(content: LinearLayout) = with(content) {
        addView(ui.surface().apply {
            addView(ui.row("沉浸模式", "打开键盘时使用低亮度黑色界面",
                settingSwitch("沉浸模式", inputPrefs.getBoolean("immersive_keyboard", false)) {
                    inputPrefs.edit().putBoolean("immersive_keyboard", it).apply()
                }))
            addView(ui.row("按键反馈", "音效、震动和强度") { open(Route.FEEDBACK) })
        })
        addView(ui.section("指针"))
        addView(ui.surface().apply {
            addView(preferenceSlider("触控板灵敏度", 50, 300, 1, inputPrefs.getInt(KEY_HID_SENSITIVITY, 180),
                { "%.1f×".format(it / 100f) }) { inputPrefs.edit().putInt(KEY_HID_SENSITIVITY, it).apply() })
            addView(preferenceSlider("鼠标 DPI", 400, 3200, 100, inputPrefs.getInt(KEY_POINTER_DPI, 1000),
                { "$it DPI" }) { inputPrefs.edit().putInt(KEY_POINTER_DPI, it).apply() })
            addView(ui.row("显示触控轨迹", trailing = settingSwitch("显示触控轨迹", inputPrefs.getBoolean(KEY_TOUCHPAD_TRAIL, false)) {
                inputPrefs.edit().putBoolean(KEY_TOUCHPAD_TRAIL, it).apply()
            }))
            val rate = ui.text("${inputPrefs.getInt(KEY_USB_MOUSE_HZ, 1000)} Hz")
            addView(ui.row("USB 回报率", "仅适用于 USB 连接", rate) {
                val values = listOf(125, 250, 500, 1000)
                choose("USB 回报率", values.map { "$it Hz" }, values.indexOf(inputPrefs.getInt(KEY_USB_MOUSE_HZ, 1000))) {
                    val hz = values[it]
                    inputPrefs.edit().putInt(KEY_USB_MOUSE_HZ, hz).apply()
                    rate.text = "$hz Hz"
                }
            })
        })
    }

    private fun buildFeedbackSettings(content: LinearLayout) = with(content) {
        addView(ui.surface().apply {
            addView(ui.row("按键音效", trailing = settingSwitch("按键音效", KeySounds.isEnabled(this@MainActivity)) {
                KeySounds.setEnabled(this@MainActivity, it)
                if (it) KeySounds.play(this@MainActivity)
            }))
            addView(ui.row("按键震动", trailing = settingSwitch("按键震动", KeyHaptics.isEnabled(this@MainActivity)) {
                KeyHaptics.setEnabled(this@MainActivity, it)
                if (it) KeyHaptics.tap(this@MainActivity)
            }))
            addView(preferenceSlider("震动强度", 1, 100, 1, KeyHaptics.strength(this@MainActivity), { "$it%" }) {
                KeyHaptics.setStrength(this@MainActivity, it)
            })
            addView(ui.button("试一下") { KeyHaptics.tap(this@MainActivity) })
        })
    }

    private fun preferenceSlider(
        title: String, minimum: Int, maximum: Int, step: Int, initial: Int,
        label: (Int) -> String, change: (Int) -> Unit
    ) = ui.column().apply {
        val value = ui.text(label(initial), 14, UiTheme.COLOR_ACCENT)
        addView(ui.row(title, trailing = value))
        addView(SeekBar(this@MainActivity).apply {
            contentDescription = title
            max = (maximum - minimum) / step
            progress = ((initial - minimum) / step).coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val selected = minimum + progress * step
                    value.text = label(selected)
                    change(selected)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        })
    }

    private fun choose(title: String, options: List<String>, selected: Int, change: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, index ->
                dialog.dismiss()
                if (index != selected) change(index)
            }.setNegativeButton("取消", null).show()
    }

    private fun choosePairingMode() {
        val modes = PairingBroadcastMode.entries
        choose("配对方式", modes.map { pairingModeLabel(it) }, modes.indexOf(viewModel.pairingBroadcastMode)) {
            viewModel.setPairingBroadcastMode(modes[it])
            updatePairingCard()
            restartHomeKitIfEnabled()
        }
    }

    private fun chooseBroadcastStrategy() {
        val strategies = BroadcastStrategy.entries
        choose("响应策略", strategies.map { it.title }, strategies.indexOf(ServiceControl.broadcastStrategy(this))) {
            ServiceControl.setBroadcastStrategy(this, strategies[it])
            updateBroadcastStrategySummary()
            restartHomeKitIfEnabled()
        }
    }

    private fun updateBroadcastStrategySummary() {
        val effective = ServiceControl.effectiveBroadcastStrategy(this, viewModel.isPaired)
        strategySummary?.renderStatus(UiStatus("当前生效 · ${effective.title}", StatusTone.WAITING))
    }

    private fun editName(title: String, current: String, save: (String) -> Unit) {
        val edit = EditText(this).apply {
            setSingleLine(true)
            setText(current)
            selectAll()
        }
        val box = ui.column().apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(edit)
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("保存", null).setNegativeButton("取消", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = edit.text.toString().trim()
            if (name.isBlank()) edit.error = "请输入名称"
            else {
                save(name)
                dialog.dismiss()
            }
        }
    }

    private fun confirmAction(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("继续") { _, _ -> action() }.setNegativeButton("取消", null).show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun restartHomeKitIfEnabled() {
        if (ServiceControl.isEnabled(this)) restartHomeKitService()
    }

    private fun buildHotspotSettings(): View = ui.column().apply {
        val current = viewModel.hotspotConfiguration()
        fun field(label: String, value: String, secret: Boolean = false) = EditText(this@MainActivity).apply {
            hint = label
            contentDescription = label
            setSingleLine(true)
            if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(value)
        }
        val ssid = field("热点名称", current.ssid)
        val password = field("热点密码", current.password, secret = true)
        addView(ui.surface().apply {
            addView(ui.section("热点名称"))
            addView(ssid)
            addView(ui.section("热点密码"))
            addView(password)
        })

        var band = current.band
        var power = current.powerMode
        var waitMinutes = current.shutdownTimeoutMinutes
        val bands = listOf("2" to "2.4 GHz", "5" to "5 GHz", "bridged" to "双频")
        val modes = listOf("省电" to "省电", "balanced" to "平衡", "性能" to "性能")
        val wifi6 = settingSwitch("Wi-Fi 6", current.wifi6Enabled) {}
        val autoShutdown = settingSwitch("自动关闭", current.autoShutdownEnabled) {}
        addView(ui.surface().apply {
            val bandValue = ui.text(bands.find { it.first == band }?.second ?: band)
            val powerValue = ui.text(modes.find { it.first == power }?.second ?: power)
            addView(ui.row("频段", trailing = bandValue) {
                choose("热点频段", bands.map { it.second }, bands.indexOfFirst { it.first == band }) {
                    band = bands[it].first
                    bandValue.text = bands[it].second
                }
            })
            addView(ui.row("功耗策略", "省电优先 2.4 GHz，性能优先双频", powerValue) {
                choose("热点功耗策略", modes.map { it.second }, modes.indexOfFirst { it.first == power }) {
                    power = modes[it].first
                    powerValue.text = modes[it].second
                }
            })
            addView(ui.row("Wi-Fi 6", trailing = wifi6))
        })
        addView(ui.surface().apply {
            addView(ui.row("自动关闭", "没有设备连接时关闭热点", autoShutdown))
            addView(preferenceSlider("等待时长", 5, 120, 5, waitMinutes, { "$it 分钟" }) {
                waitMinutes = it
            })
        })
        addView(ui.button("保存并应用", primary = true) {
            val name = ssid.text.toString().trim()
            val key = password.text.toString()
            if (name.toByteArray().size !in 1..32) {
                ssid.error = "名称必须为 1～32 字节"
                return@button
            }
            if (key.toByteArray().size !in 8..63) {
                password.error = "密码必须为 8～63 字节"
                return@button
            }
            val configuredBand = when (power) {
                "省电" -> "2"
                "性能" -> "bridged"
                else -> band
            }
            val configuredWifi6 = when (power) {
                "省电" -> false
                "性能" -> true
                else -> wifi6.isChecked
            }
            viewModel.saveHotspotConfiguration(HotspotConfiguration(
                ssid = name, password = key, band = configuredBand,
                wifi6Enabled = configuredWifi6, autoShutdownEnabled = autoShutdown.isChecked,
                shutdownTimeoutMinutes = waitMinutes, powerMode = power
            ))
            Thread {
                val result = viewModel.restartHotspotIfActive()
                runOnUiThread { toast(if (result.success) "热点配置已保存" else "已保存，应用失败：${result.message}") }
            }.start()
        })
        addView(ui.button("打开系统热点设置") {
            try {
                startActivity(Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"))
            } catch (_: android.content.ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
        })
        addView(ui.text("Wi-Fi 6 和自动关闭最终以系统热点设置为准。", 13, UiTheme.COLOR_TEXT_SECONDARY).apply {
            setPadding(0, dp(12), 0, 0)
        })
    }

    private fun updateNfcStatus() {
        val adapter = nfcAdapter
        nfcStatusText?.text = when {
            adapter == null -> "本机没有 NFC 硬件"
            !adapter.isEnabled -> "本机支持 NFC，但系统 NFC 当前未开启"
            else -> "NFC 已开启：请准备可写的 NFC Forum Type 2 标签"
        }
    }

    private fun armNfcWriter() {
        if (viewModel.isPaired) {
            Toast.makeText(this, "已配对后不再展示可配对 NFC 内容", Toast.LENGTH_SHORT).show()
            return
        }
        if (viewModel.pairingBroadcastMode != PairingBroadcastMode.QR_SETUP_PAYLOAD) {
            Toast.makeText(this, "请先切换到二维码广播模式", Toast.LENGTH_SHORT).show()
            return
        }
        if (nfcAdapter == null) {
            Toast.makeText(this, "本机没有 NFC 硬件", Toast.LENGTH_SHORT).show()
            return
        }
        if (nfcAdapter?.isEnabled != true) {
            Toast.makeText(this, "请先在系统设置中开启 NFC", Toast.LENGTH_SHORT).show()
            return
        }
        nfcWriteArmed = true
        enableNfcForegroundDispatch()
        Toast.makeText(this, "请将可写 NFC Forum Type 2 标签贴近手机", Toast.LENGTH_LONG).show()
    }

    private fun enableNfcForegroundDispatch() {
        try {
            nfcAdapter?.enableForegroundDispatch(this, nfcPendingIntent, null, null)
        } catch (_: IllegalStateException) {
        } catch (_: SecurityException) {
        }
    }

    private fun disableNfcForegroundDispatch() {
        // Calling disable without a matching enable still reaches the NFC
        // service on some MIUI builds, where it throws if NFC is not declared
        // or permission is unavailable. Activity pause must never kill the
        // process that also hosts the HomeKit foreground service.
        if (!nfcWriteArmed) return
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (_: IllegalStateException) {
        } catch (_: SecurityException) {
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!nfcWriteArmed) return
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        nfcWriteArmed = false
        disableNfcForegroundDispatch()
        writeHomeKitNfcPayload(tag)
    }

    private fun writeHomeKitNfcPayload(tag: Tag) {
        val payload = viewModel.setupPayload
        Thread {
            try {
                val ndef = Ndef.get(tag)
                if (ndef == null) throw IllegalArgumentException("标签不是 NDEF 标签")
                val uriRecord = NdefRecord(
                    NdefRecord.TNF_WELL_KNOWN,
                    NdefRecord.RTD_URI,
                    byteArrayOf(),
                    byteArrayOf(0) + payload.toByteArray(Charsets.UTF_8)
                )
                val message = NdefMessage(arrayOf(uriRecord))
                val size = message.toByteArray().size
                if (!ndef.isWritable) throw IllegalArgumentException("标签不可写")
                if (ndef.maxSize < size) throw IllegalArgumentException("标签容量不足，需要至少 ${size} 字节")
                ndef.connect()
                ndef.writeNdefMessage(message)
                runOnUiThread { Toast.makeText(this, "NFC HomeKit setup payload 写入成功", Toast.LENGTH_LONG).show() }
                log("NFC_SETUP_PAYLOAD_WRITTEN bytes=$size setupId=${viewModel.setupId}")
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this, "NFC 写入失败：${t.message ?: "不兼容的标签"}", Toast.LENGTH_LONG).show() }
                log("NFC_SETUP_PAYLOAD_WRITE_FAILED ${t.javaClass.simpleName}:${t.message}")
            } finally {
                try { Ndef.get(tag)?.close() } catch (_: Throwable) { }
            }
        }.start()
    }

    private val hidListener = object : BtHidPeripheral.Listener {
        override fun onStateChanged(state: BtHidPeripheral.State, detail: String?) {
            runOnUiThread { applyHidState(state, detail) }
        }
    }

    private fun enableHidFromUi() {
        if (Build.VERSION.SDK_INT >= 31) {
            val missing = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                missing.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (missing.isNotEmpty()) {
                pendingHidEnable = true
                requestPermissions(missing.toTypedArray(), REQUEST_HID)
                return
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingHidEnable = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_HID_NOTIFICATIONS)
            return
        }
        startHid()
    }

    private fun startHid() {
        val bridge = PeripheralHub.bridge(this)
        // A failed registration leaves the bridge "running" without a HID
        // record, so the next start() returned early and the switch became a
        // no-op. Reset the session before asking for a new registration.
        if (bridge.state == BtHidPeripheral.State.FAILED) bridge.stop()
        PeripheralService.start(this)
        bridge.start()
        getSharedPreferences(BtHidPeripheral.PREFS, MODE_PRIVATE)
            .edit().putBoolean(BtHidPeripheral.KEY_ENABLED, true).apply()
        applyHidState(bridge.state, bridge.hostName)
    }

    private fun disableHidFromUi() {
        getSharedPreferences(BtHidPeripheral.PREFS, MODE_PRIVATE)
            .edit().putBoolean(BtHidPeripheral.KEY_ENABLED, false).apply()
        PeripheralHub.bridge(this).stop()
        PeripheralService.stop(this)
        applyHidState(BtHidPeripheral.State.STOPPED, null)
    }

    private fun applyHidState(state: BtHidPeripheral.State, detail: String?) {
        val bridge = PeripheralHub.bridge(this)
        val stillRunning = bridge.isRunning
        hidState = state
        // Keep the stored intent equal to the real bridge, so the switch value
        // after the next launch and the boot recovery do not lie to the user.
        val gaveUp = (state == BtHidPeripheral.State.STOPPED && !stillRunning) ||
            state == BtHidPeripheral.State.UNSUPPORTED
        if (gaveUp) {
            getSharedPreferences(BtHidPeripheral.PREFS, MODE_PRIVATE)
                .edit().putBoolean(BtHidPeripheral.KEY_ENABLED, false).apply()
        }

        val problem = when (state) {
            BtHidPeripheral.State.FAILED -> detail ?: "HID 注册失败：蓝牙 HID 服务未就绪"
            BtHidPeripheral.State.UNSUPPORTED -> detail ?: "系统不支持蓝牙 HID Device"
            else -> null
        }
        hidDetailText.text = problem.orEmpty()
        hidDetailText.visibility = if (problem == null) View.GONE else View.VISIBLE
        if (problem != null) {
            if (problem != lastHidFailure) {
                lastHidFailure = problem
                Toast.makeText(this, "HID 失败：$problem", Toast.LENGTH_LONG).show()
            }
        } else {
            lastHidFailure = null
        }

        if (routes.current == Route.BLUETOOTH) refreshBondedHosts()
        refreshPeripheralEntry()
    }

    private fun refreshPeripheralEntry(usb: UsbPeripheralStatus.Snapshot = UsbPeripheralStatus.snapshot(this)) {
        refreshOverviewStatuses(usb)
    }

    /**
     * Re-derives the USB half of the entry. [verify] probes the daemon on a
     * worker thread (and adopts a session that outlived the process); the plain
     * form only re-reads the cached/persisted snapshot on the caller thread.
     */
    private fun refreshUsbPeripheralState(verify: Boolean) {
        refreshPeripheralEntry()
        if (!verify || isFinishing) return
        if (!usbStateProbe.compareAndSet(false, true)) return
        Thread {
            val snapshot = try {
                UsbPeripheralStatus.verify(this)
            } catch (e: Throwable) {
                log("USB_STATE_VERIFY_FAILED ${e.javaClass.simpleName}: ${e.message}")
                UsbPeripheralStatus.snapshot(this)
            }
            runOnUiThread {
                usbStateProbe.set(false)
                if (!isFinishing) refreshPeripheralEntry(snapshot)
            }
        }.start()
    }

    /**
     * The HID switch is persisted, but nothing restored it after a process
     * restart: the switch said "on" while no registration existed. Start the
     * sticky service again when the stored intent is on but the bridge is idle.
     */
    private fun ensureHidRunningIfEnabled() {
        val wanted = getSharedPreferences(BtHidPeripheral.PREFS, MODE_PRIVATE)
            .getBoolean(BtHidPeripheral.KEY_ENABLED, false)
        val bridge = PeripheralHub.bridge(this)
        if (!wanted || bridge.isRunning) return
        log("HID_RESTORE_ENABLED state=${bridge.state}")
        runCatching { PeripheralService.start(this) }
            .onFailure { log("HID_RESTORE_START_FAILED ${it.javaClass.simpleName}") }
        bridge.start()
    }

    private fun requestHidDiscoverable() {
        try {
            startActivityForResult(
                Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120),
                REQUEST_DISCOVERABLE
            )
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开可发现设置：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pairingModeLabel(mode: PairingBroadcastMode): String = when (mode) {
        PairingBroadcastMode.QR_SETUP_PAYLOAD -> "二维码 / Setup ID ${viewModel.setupId}"
        PairingBroadcastMode.LEGACY_SETUP_CODE -> "8 位配对码"
    }

    private fun updatePairingCard() {
        val paired = viewModel.isPaired
        val qrMode = viewModel.pairingBroadcastMode == PairingBroadcastMode.QR_SETUP_PAYLOAD
        pairingModeSummary?.text = if (qrMode) "二维码" else "配对码"
        pairingState?.text = when {
            paired -> "已与 Apple 家庭配对"
            qrMode -> "扫描二维码"
            else -> "输入配对码"
        }
        pairingQrView?.visibility = if (!paired && qrMode) View.VISIBLE else View.GONE
        pairingCode?.visibility = if (!paired && !qrMode) View.VISIBLE else View.GONE
        pairingCode?.text = if (paired) "" else viewModel.formatSetupCode(viewModel.setupCodeDigits)
        pairingHint?.text = when {
            paired -> "可在 Apple 家庭中查看和控制这台设备。"
            qrMode -> "在 iPhone 的家庭 App 中选择「添加配件」，扫描上方二维码。"
            else -> "在家庭 App 中选择「更多选项」，然后输入上方配对码。"
        }
        if (!paired && qrMode && routes.current == Route.PAIRING) {
            val payload = viewModel.setupPayload
            if (renderedSetupPayload != payload) {
                pairingQrView?.setImageBitmap(createQrBitmap(payload))
                renderedSetupPayload = payload
            }
        }
    }

    private fun createQrBitmap(value: String): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 512, 512, hints)
        return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }

    private fun showSetupCodeDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(8))
            hint = "8 位数字"
            contentDescription = "8 位配对码"
            setSingleLine(true)
            setText(viewModel.setupCodeDigits)
            selectAll()
        }
        val container = ui.column().apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this).setTitle("修改配对码")
            .setView(container).setPositiveButton("保存", null).setNegativeButton("取消", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val code = input.text.toString()
            if (!viewModel.isValidSetupCode(code)) {
                input.error = "请输入 8 位数字，不能全相同或连续递增、递减"
                return@setOnClickListener
            }
            viewModel.saveSetupCode(code)
            updatePairingCard()
            toast("配对码已更新")
            dialog.dismiss()
        }
        input.requestFocus()
    }

    private fun resetAccessoryId() {
        val id = viewModel.resetAccessoryId()
        updateConnectionStatus()
        log("ACCESSORY_ID_RESET id=$id pairing_cleared=true")
        restartHomeKitService()
    }

    private fun clearPairingState() {
        viewModel.clearPairing()
        updateConnectionStatus()
        log("PAIRING_STATE_CLEARED accessory_id_preserved=true")
        restartHomeKitService()
    }

    private fun confirmHomeKitRecovery() {
        AlertDialog.Builder(this)
            .setTitle("修复 HomeKit 配对与名称")
            .setMessage("请先在家庭 App 删除所有 Marionette/开关/锁旧卡片。继续后会创建全新配件身份：轮换配件 ID 与密钥，清除本机配对和名称缓存，并重启为单一未配对广播。")
            .setNegativeButton("取消", null)
            .setPositiveButton("开始修复") { _, _ -> recoverHomeKitIdentity() }
            .show()
    }

    private fun recoverHomeKitIdentity() {
        // Stop the old advertiser before changing identity. This prevents the
        // short overlap that can make Home discover two incompatible HAP tiles.
        stopHomeKitService()
        Handler(Looper.getMainLooper()).postDelayed({
            val result = viewModel.recoverHomeKitIdentity()
            updateConnectionStatus()
            log("HOMEKIT_RECOVERY_COMPLETED id=${result.accessoryId} cleared_names=${result.clearedServiceNames} paired=false")
            startHomeKitService()
            Toast.makeText(this, "已创建新配件：${result.accessoryId}\n请在家庭 App 添加一次", Toast.LENGTH_LONG).show()
        }, 600L)
    }

    private fun rootAvailable(): Boolean = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val finished = process.waitFor(500, TimeUnit.MILLISECONDS)
        process.inputStream.close(); process.errorStream.close()
        if (!finished) process.destroyForcibly()
        finished && process.exitValue() == 0
    } catch (_: Throwable) { false }

    private fun updateConnectionStatus(refreshRoot: Boolean = true) {
        if (refreshRoot) Thread {
            val available = rootAvailable()
            runOnUiThread {
                if (!isDestroyed) rootBadge.renderStatus(
                    if (available) UiStatus("Root 可用", StatusTone.ACTIVE) else UiStatus("Root 未授权")
                )
            }
        }.start()
        runOnUiThread {
            statusInfo.text = "配件 ID · ${viewModel.accessoryId()}"
            updatePairingCard()
            updateBroadcastStrategySummary()
            refreshOverviewStatuses()
        }
    }

    private fun refreshDeviceState() {
        if (!refreshingState.compareAndSet(false, true)) return
        Thread {
            try {
                val state = viewModel.loadState()
                runOnUiThread {
                    state.featureStates.forEach { (feature, value) ->
                        if (featureLoading.contains(feature)) return@forEach
                        applyFeatureState(feature, value)
                    }
                    brightnessSeek?.takeIf { !shouldDeferSliderUpdate(brightnessSliderState) }?.let { seek ->
                        seek.progress = state.brightness
                        brightnessValueText?.text = "${state.brightness}%"
                    }
                    volumeSeek?.takeIf { !shouldDeferSliderUpdate(volumeSliderState) }?.let { seek ->
                        seek.progress = state.volume
                        volumeValueText?.text = "${state.volume}%"
                    }
                }
            } catch (_: Throwable) {
            } finally {
                refreshingState.set(false)
            }
        }.start()
    }

    private fun runCommandFromUi(feature: HomeKitFeature) {
        val btn = featureButtons[feature] ?: return
        if (!featureLoading.add(feature)) return
        btn.isEnabled = false
        btn.text = "…"
        UiTheme.styleButton(btn, UiTheme.COLOR_BTN_SECONDARY_BG, UiTheme.COLOR_TEXT_MUTED, radiusDp = 18f)
        Thread {
            try {
                val current = viewModel.currentValue(feature)
                val target = !current
                runOnUiThread { applyFeatureState(feature, target) }
                val result = viewModel.toggleFromCurrent(feature, current)
                log("UI_COMMAND feature=$feature success=${result.success} message=${result.message}")
                if (result.success) {
                    // Root/system commands can return before the framework has
                    // committed the new value. Re-read after short settling
                    // windows while the optimistic state keeps the UI snappy.
                    stateRefreshHandler.postDelayed({ refreshDeviceState() }, 120L)
                    stateRefreshHandler.postDelayed({ refreshDeviceState() }, 450L)
                }
            } finally {
                runOnUiThread {
                    featureLoading.remove(feature)
                    btn.isEnabled = true
                    refreshDeviceState()
                }
            }
        }.start()
    }

    private fun scheduleSliderWrite(
        state: SliderWriteState,
        handler: Handler,
        value: Int,
        submit: (Int) -> Unit
    ) {
        state.pendingRunnable?.let(handler::removeCallbacks)
        state.pendingRunnable = Runnable {
            state.pendingRunnable = null
            submit(value)
        }.also { handler.postDelayed(it, 300L) }
    }

    private fun shouldDeferSliderUpdate(state: SliderWriteState): Boolean =
        state.tracking || state.workerRunning || state.pendingRunnable != null ||
            SystemClock.uptimeMillis() < state.holdUntil

    /** Coalesce slider motion into one serialized latest-value writer. */
    private fun queueSliderWrite(
        state: SliderWriteState,
        value: Int,
        command: String,
        writer: (Int) -> CommandResult
    ) {
        val startWorker: Boolean
        synchronized(state.lock) {
            state.queuedValue = value.coerceIn(0, 100)
            state.holdUntil = SystemClock.uptimeMillis() + SLIDER_UI_HOLD_MS
            startWorker = !state.workerRunning
            if (startWorker) state.workerRunning = true
        }
        if (!startWorker) return

        Thread {
            try {
                while (true) {
                    val target = synchronized(state.lock) {
                        val next = state.queuedValue
                        state.queuedValue = null
                        next
                    } ?: break
                    val result = try {
                        writer(target)
                    } catch (t: Throwable) {
                        CommandResult(false, "$command UI write failed: ${t.message}")
                    }
                    log("UI_COMMAND feature=$command value=$target success=${result.success} message=${result.message}")
                }
            } finally {
                synchronized(state.lock) {
                    state.holdUntil = SystemClock.uptimeMillis() + SLIDER_UI_SETTLE_MS
                    state.workerRunning = false
                }
                stateRefreshHandler.postDelayed({ refreshDeviceState() }, SLIDER_UI_SETTLE_MS)
            }
        }.start()
    }

    private fun setBrightnessFromUi(value: Int) {
        queueSliderWrite(brightnessSliderState, value, "BRIGHTNESS") {
            viewModel.setBrightness(it)
        }
    }

    private fun setVolumeFromUi(value: Int) {
        queueSliderWrite(volumeSliderState, value, "VOLUME") {
            viewModel.setVolume(it)
        }
    }

    private fun applyFeatureState(feature: HomeKitFeature, value: Boolean) {
        val btn = featureButtons[feature] ?: return
        btn.text = if (value) feature.enabledLabel else feature.disabledLabel
        btn.isEnabled = feature !in featureLoading
        if (value) {
            UiTheme.styleButton(btn, UiTheme.COLOR_TEXT_PRIMARY, Color.WHITE, radiusDp = 18f)
        } else {
            UiTheme.styleButton(btn, UiTheme.COLOR_BTN_SECONDARY_BG, UiTheme.COLOR_TEXT_SECONDARY, radiusDp = 18f)
        }
    }

    private fun applyStateEvent(intent: Intent) {
        if (intent.getBooleanExtra(HomeKitUiEvents.EXTRA_IS_INTEGER, false)) {
            val value = intent.getIntExtra(HomeKitUiEvents.EXTRA_INTEGER_VALUE, 0).coerceIn(0, 100)
            when (intent.getStringExtra(HomeKitUiEvents.EXTRA_COMMAND)) {
                "BRIGHTNESS" -> if (!shouldDeferSliderUpdate(brightnessSliderState)) {
                    brightnessSeek?.progress = value
                    brightnessValueText?.text = "$value%"
                }
                "VOLUME" -> if (!shouldDeferSliderUpdate(volumeSliderState)) {
                    volumeSeek?.progress = value
                    volumeValueText?.text = "$value%"
                }
            }
        } else {
            val feature = HomeKitFeature.tileFeatures.firstOrNull {
                it.command.name == intent.getStringExtra(HomeKitUiEvents.EXTRA_COMMAND)
            } ?: return
            if (featureLoading.contains(feature)) return
            applyFeatureState(feature, intent.getBooleanExtra(HomeKitUiEvents.EXTRA_BOOLEAN_VALUE, false))
        }
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // API 33+ supplies NOT_EXPORTED; older API uses its legacy overload.
    private fun registerUiEvents() {
        if (eventsRegistered) return
        val filter = IntentFilter().apply {
            addAction(HomeKitUiEvents.ACTION_RUNTIME_STATUS)
            addAction(HomeKitUiEvents.ACTION_STATE_CHANGED)
            addAction(UsbPeripheralService.ACTION_STATE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(uiEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") registerReceiver(uiEventReceiver, filter)
        }
        eventsRegistered = true
    }

    private fun unregisterUiEvents() {
        if (!eventsRegistered) return
        try { unregisterReceiver(uiEventReceiver) } catch (_: IllegalArgumentException) { }
        eventsRegistered = false
    }

    private fun ensurePermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= 31) {
            val requested = mutableListOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.NEARBY_WIFI_DEVICES
            val missing = requested.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH)
                return
            }
        }
        if (requestNotificationPermissionIfNeeded()) startHomeKitService()
    }

    private fun startHomeKitService() {
        try { ServiceControl.start(this) } catch (e: Throwable) { log("SERVICE_START_FAILED ${e.message}") }
        updateConnectionStatus(refreshRoot = false)
    }

    private fun stopHomeKitService() {
        ServiceControl.stop(this)
        updateConnectionStatus(refreshRoot = false)
    }

    private fun restartHomeKitService() {
        stopHomeKitService()
        Handler(Looper.getMainLooper()).postDelayed({ startHomeKitService() }, 250)
    }

    private fun requestNotificationPermissionIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQUEST_BLUETOOTH -> if (granted) ensurePermissionsAndStart()
            REQUEST_CAMERA -> if (requestNotificationPermissionIfNeeded()) startHomeKitService()
            REQUEST_NOTIFICATIONS -> if (granted) startHomeKitService()
            REQUEST_HID -> {
                val shouldStart = pendingHidEnable && granted
                pendingHidEnable = false
                if (shouldStart) enableHidFromUi()
                else if (!granted) toast("需要蓝牙权限才能作为外设使用")
            }
            REQUEST_HID_NOTIFICATIONS -> {
                val shouldStart = pendingHidEnable && granted
                pendingHidEnable = false
                if (shouldStart) startHid()
            }
            REQUEST_USB_PERMISSIONS -> if (usbStartRequested) {
                if (granted) authorizeUsbStart()
                else {
                    usbStartRequested = false
                    toast("未授权相机或麦克风，USB 外设未启动")
                }
            }
        }
        refreshOverviewStatuses()
    }

    private fun log(message: String) {
        android.util.Log.i(TAG, message)
    }

    companion object {
        private const val TAG = "MiHotspotHap"
        private const val REQUEST_BLUETOOTH = 100
        private const val REQUEST_NOTIFICATIONS = 101
        private const val REQUEST_CAMERA = 102
        private const val REQUEST_HID = 103
        private const val REQUEST_DISCOVERABLE = 104
        private const val REQUEST_HID_NOTIFICATIONS = 105
        private const val REQUEST_USB_PERMISSIONS = 106
        private const val REQUEST_USB_SCREEN = 107
        private const val KEY_HID_SENSITIVITY = "touchpad_sensitivity"
        private const val KEY_TOUCHPAD_TRAIL = "touchpad_trail"
        private const val KEY_POINTER_DPI = "pointer_dpi"
        private const val KEY_USB_MOUSE_HZ = "usb_mouse_hz"
        private const val SLIDER_UI_HOLD_MS = 1_200L
        private const val SLIDER_UI_SETTLE_MS = 700L
    }
}
