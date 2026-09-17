package dev.local.mihotspot

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
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

private class SliderWriteState {
    val lock = Any()
    var queuedValue: Int? = null
    var pendingRunnable: Runnable? = null
    @Volatile var tracking = false
    @Volatile var workerRunning = false
    @Volatile var holdUntil = 0L
}

class MainActivity : Activity() {
    private lateinit var titleView: TextView
    private lateinit var statusInfo: TextView
    private lateinit var serviceSwitch: Switch
    private var brightnessSeek: SeekBar? = null
    private var volumeSeek: SeekBar? = null
    private var brightnessValueText: TextView? = null
    private var volumeValueText: TextView? = null
    /** Whether HomeKit is allowed to invoke each command; independent from live state. */
    private val commandEnableToggles = mutableMapOf<HomeKitFeature, CheckBox>()
    private val featureButtons = mutableMapOf<HomeKitFeature, Button>()
    private val featureLoading = mutableSetOf<HomeKitFeature>()
    private val refreshingState = AtomicBoolean(false)
    private var rootStatus = "Root检测中"
    private val stateRefreshHandler = Handler(Looper.getMainLooper())
    private val stateRefreshRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                updateConnectionStatus(refreshRoot = false)
                refreshDeviceState()
                stateRefreshHandler.postDelayed(this, 3_000L)
            }
        }
    }
    private val brightnessDebounce = Handler(Looper.getMainLooper())
    private val volumeDebounce = Handler(Looper.getMainLooper())
    private val brightnessSliderState = SliderWriteState()
    private val volumeSliderState = SliderWriteState()
    private val viewModel by lazy { MainViewModel(this) }
    private var pairingCard: LinearLayout? = null
    private var pairingQrView: ImageView? = null
    private var pairingCardTitle: TextView? = null
    private var pairingCardHint: TextView? = null
    private var pairingCardKeyLabel: TextView? = null
    private var pairingCardKey: TextView? = null
    private var pairingModeSummary: TextView? = null
    private var pairingPayloadText: TextView? = null
    private var mainPage: View? = null
    private var settingsPage: View? = null
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
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        updatePairingCard()
        updateConnectionStatus()
        log("PROBE_CREATED source=Apple_HomeKitADK_f201f98")
        if (ServiceControl.isEnabled(this)) ensurePermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        registerUiEvents()
        if (nfcWriteArmed) enableNfcForegroundDispatch()
        updateConnectionStatus(refreshRoot = false)
        updateNfcStatus()
        refreshDeviceState()
        stateRefreshHandler.removeCallbacks(stateRefreshRunnable)
        stateRefreshHandler.postDelayed(stateRefreshRunnable, 3_000L)
    }

    override fun onPause() {
        stateRefreshHandler.removeCallbacks(stateRefreshRunnable)
        disableNfcForegroundDispatch()
        unregisterUiEvents()
        super.onPause()
    }

    override fun onDestroy() {
        stateRefreshHandler.removeCallbacks(stateRefreshRunnable)
        disableNfcForegroundDispatch()
        unregisterUiEvents()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (settingsPage?.visibility == View.VISIBLE) {
            showMainPage()
        } else {
            super.onBackPressed()
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(v: Int): Int = dp(v.toFloat())

    private fun divider(): View = View(this).apply {
        setBackgroundColor(0xFFE0E0E0.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFF888888.toInt())
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // === Top header: clickable status title + service switch ===
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        titleView = TextView(this).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), dp(8), dp(4))
            setOnClickListener { showSettingsPage() }
            contentDescription = "打开设备设置"
        }
        headerRow.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        serviceSwitch = Switch(this).apply {
            text = "服务"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isChecked = ServiceControl.isEnabled(this@MainActivity)
            setTextColor(if (isChecked) 0xFF00AA00.toInt() else 0xFF333333.toInt())
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    setTextColor(0xFF00AA00.toInt())
                    ensurePermissionsAndStart()
                } else {
                    setTextColor(0xFF333333.toInt())
                    stopHomeKitService()
                }
            }
        }
        headerRow.addView(serviceSwitch)
        root.addView(headerRow)

        // Status + accessory info
        statusInfo = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF666666.toInt())
        }
        root.addView(statusInfo)

        pairingCard = buildPairingCard()
        root.addView(pairingCard)
        root.addView(divider())

        // === Device name row ===
        root.addView(sectionHeader("设备配置"))
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        val nameEdit = EditText(this).apply {
            hint = "设备名称"
            setText(viewModel.deviceName)
            setSingleLine(true)
            textSize = 13f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(0xFFF5F5F5.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameSaveBtn = Button(this).apply {
            text = "保存"
            textSize = 11f
            isAllCaps = false
            setBackgroundColor(0xFF333333.toInt())
            setTextColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(6), 0, 0, 0)
            layoutParams = lp
            onHapticClick {
                val name = nameEdit.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.saveDeviceName(name)
                    log("DEVICE_NAME_UPDATED name=$name")
                    restartHomeKitService()
                }
            }
        }
        nameRow.addView(nameEdit)
        nameRow.addView(nameSaveBtn)
        root.addView(nameRow)
        root.addView(divider())

        // === Feature list: each feature = [toggle | label | test button] ===
        root.addView(sectionHeader("功能控制"))
        for (feature in HomeKitFeature.tileFeatures) {
            root.addView(buildFeatureRow(feature))
            root.addView(divider())
        }

        // === Sliders ===
        root.addView(sectionHeader("连续调节"))

        // Brightness
        val bHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bLabel = TextView(this).apply {
            text = "屏幕亮度"
            textSize = 13f
            setTextColor(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        brightnessValueText = TextView(this).apply {
            text = "0%"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF111111.toInt())
        }
        bHeader.addView(bLabel)
        bHeader.addView(brightnessValueText)
        root.addView(bHeader)

        brightnessSeek = SeekBar(this).apply {
            max = 100
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            p.setMargins(0, dp(4), 0, dp(12))
            layoutParams = p
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    brightnessValueText?.text = "$progress%"
                    scheduleSliderWrite(brightnessSliderState, brightnessDebounce, progress) {
                        setBrightnessFromUi(it)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar) {
                    brightnessSliderState.tracking = true
                    brightnessSliderState.pendingRunnable?.let(brightnessDebounce::removeCallbacks)
                    brightnessSliderState.pendingRunnable = null
                }
                override fun onStopTrackingTouch(s: SeekBar) {
                    brightnessSliderState.pendingRunnable?.let(brightnessDebounce::removeCallbacks)
                    brightnessSliderState.pendingRunnable = null
                    brightnessSliderState.tracking = false
                    setBrightnessFromUi(s.progress)
                }
            })
        }
        root.addView(brightnessSeek)

        // Media volume remains available as a continuous local control.
        val vHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val vLabel = TextView(this).apply {
            text = "媒体音量"
            textSize = 13f
            setTextColor(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        volumeValueText = TextView(this).apply {
            text = "0%"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF111111.toInt())
        }
        vHeader.addView(vLabel)
        vHeader.addView(volumeValueText)
        root.addView(vHeader)

        volumeSeek = SeekBar(this).apply {
            max = 100
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            p.setMargins(0, dp(4), 0, dp(12))
            layoutParams = p
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    volumeValueText?.text = "$progress%"
                    scheduleSliderWrite(volumeSliderState, volumeDebounce, progress) {
                        setVolumeFromUi(it)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar) {
                    volumeSliderState.tracking = true
                    volumeSliderState.pendingRunnable?.let(volumeDebounce::removeCallbacks)
                    volumeSliderState.pendingRunnable = null
                }
                override fun onStopTrackingTouch(s: SeekBar) {
                    volumeSliderState.pendingRunnable?.let(volumeDebounce::removeCallbacks)
                    volumeSliderState.pendingRunnable = null
                    volumeSliderState.tracking = false
                    setVolumeFromUi(s.progress)
                }
            })
        }
        root.addView(volumeSeek)

        val mainScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        mainPage = mainScroll

        val settingsRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        settingsRoot.addView(buildSettingsHeader())
        settingsRoot.addView(buildPairingSettings())
        settingsRoot.addView(buildHotspotSettings())
        settingsRoot.addView(buildNfcSettings())
        settingsRoot.addView(buildDeviceManagement())
        val settingsScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(settingsRoot)
            visibility = View.GONE
        }
        settingsPage = settingsScroll

        return FrameLayout(this).apply {
            addView(mainScroll, FrameLayout.LayoutParams(-1, -1))
            addView(settingsScroll, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun buildSettingsHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val back = Button(this@MainActivity).apply {
            text = "‹"
            textSize = 28f
            isAllCaps = false
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(0xFF333333.toInt())
            onHapticClick { showMainPage() }
        }
        addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(this@MainActivity).apply {
            text = "设备设置"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF222222.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun buildPairingSettings(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(sectionHeader("配对方式"))
        addView(TextView(this@MainActivity).apply {
            text = "二维码：4 位 Setup ID，扫码配对\n8 位：输入配对码；切换只重启广播，不清除配对"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, dp(8))
        })
        val tabs = RadioGroup(this@MainActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            val qr = RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                text = "二维码 · 4位"
                textSize = 13f
                isChecked = viewModel.pairingBroadcastMode == PairingBroadcastMode.QR_SETUP_PAYLOAD
                layoutParams = RadioGroup.LayoutParams(0, -2, 1f)
            }
            val code = RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                text = "配对码 · 8位"
                textSize = 13f
                isChecked = viewModel.pairingBroadcastMode == PairingBroadcastMode.LEGACY_SETUP_CODE
                layoutParams = RadioGroup.LayoutParams(0, -2, 1f)
            }
            addView(qr)
            addView(code)
            setOnCheckedChangeListener { _, checkedId ->
                val selected = when (checkedId) {
                    qr.id -> PairingBroadcastMode.QR_SETUP_PAYLOAD
                    code.id -> PairingBroadcastMode.LEGACY_SETUP_CODE
                    else -> return@setOnCheckedChangeListener
                }
                if (selected == viewModel.pairingBroadcastMode) return@setOnCheckedChangeListener
                viewModel.setPairingBroadcastMode(selected)
                updatePairingCard()
                log("PAIRING_BROADCAST_MODE_UPDATED mode=$selected")
                restartHomeKitService()
                Toast.makeText(this@MainActivity, "已切换配对广播：${pairingModeLabel(selected)}", Toast.LENGTH_SHORT).show()
            }
        }
        addView(tabs)
        pairingModeSummary = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(0xFF555555.toInt())
            setPadding(0, dp(8), 0, dp(4))
        }
        addView(pairingModeSummary)
        addView(Button(this@MainActivity).apply {
            text = "修改 8 位配对码"
            textSize = 12f
            isAllCaps = false
            onHapticClick { showSetupCodeDialog() }
        })
        pairingPayloadText = TextView(this@MainActivity).apply {
            text = "Setup ID：${viewModel.setupId}\nPayload：${viewModel.setupPayload}"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF777777.toInt())
            setPadding(0, dp(8), 0, dp(12))
        }
        addView(pairingPayloadText)
    }

    private fun buildHotspotSettings(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val current = viewModel.hotspotConfiguration()
        addView(sectionHeader("热点设置（唯一 SoftAP）"))
        addView(TextView(this@MainActivity).apply {
            text = "HomeKit 与系统共用这一套热点配置；启动前会先停止已有 SoftAP，避免出现两个个人热点。"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, dp(6))
        })

        fun field(label: String, value: String, password: Boolean = false): EditText {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                setTextColor(0xFF444444.toInt())
            }, LinearLayout.LayoutParams(dp(76), -2))
            val editor = EditText(this@MainActivity).apply {
                setText(value)
                textSize = 13f
                setSingleLine(true)
                if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setBackgroundColor(0xFFF5F5F5.toInt())
            }
            row.addView(editor, LinearLayout.LayoutParams(0, -2, 1f))
            addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            return editor
        }

        val ssid = field("名称", current.ssid)
        val password = field("密码", current.password, password = true)

        addView(TextView(this@MainActivity).apply {
            text = "频段"
            textSize = 13f
            setTextColor(0xFF444444.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        val band = RadioGroup(this@MainActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            val options = listOf("2" to "2.4 GHz", "5" to "5 GHz", "bridged" to "双频")
            options.forEach { (value, label) ->
                addView(RadioButton(this@MainActivity).apply {
                    id = View.generateViewId()
                    tag = value
                    text = label
                    textSize = 12f
                    isChecked = current.band == value
                    layoutParams = RadioGroup.LayoutParams(0, -2, 1f)
                })
            }
        }
        addView(band)

        fun option(text: String, checked: Boolean): Switch = Switch(this@MainActivity).apply {
            this.text = text
            textSize = 13f
            isChecked = checked
            setTextColor(0xFF444444.toInt())
        }
        val wifi6 = option("启用 Wi-Fi 6（802.11ax）", current.wifi6Enabled)
        val autoShutdown = option("无设备连接时自动关闭", current.autoShutdownEnabled)
        addView(wifi6)
        addView(autoShutdown)

        val timeoutLabel = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            text = "自动关闭等待：${(current.shutdownTimeoutMinutes / 5).coerceIn(1, 24) * 5} 分钟"
        }
        addView(timeoutLabel)
        val timeout = SeekBar(this@MainActivity).apply {
            max = 23
            progress = ((current.shutdownTimeoutMinutes / 5).coerceIn(1, 24) - 1)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    timeoutLabel.text = "自动关闭等待：${(progress + 1) * 5} 分钟"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        addView(timeout)

        addView(TextView(this@MainActivity).apply {
            text = "功耗策略"
            textSize = 13f
            setTextColor(0xFF444444.toInt())
        })
        val power = RadioGroup(this@MainActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf("省电" to "省电", "balanced" to "平衡", "性能" to "性能").forEach { (value, label) ->
                addView(RadioButton(this@MainActivity).apply {
                    id = View.generateViewId()
                    tag = value
                    text = label
                    textSize = 12f
                    isChecked = current.powerMode == value
                    layoutParams = RadioGroup.LayoutParams(0, -2, 1f)
                })
            }
        }
        addView(power)
        addView(TextView(this@MainActivity).apply {
            text = "省电：优先 2.4 GHz；性能：优先双频。SSID、密码和频段可由本页直接应用；Wi-Fi 6、自动关闭时长等由 MIUI 系统热点页最终落地。"
            textSize = 11f
            setTextColor(0xFF777777.toInt())
            setPadding(0, dp(2), 0, dp(6))
        })
        addView(Button(this@MainActivity).apply {
            text = "保存并应用热点配置"
            textSize = 12f
            isAllCaps = false
            onHapticClick {
                val ssidText = ssid.text.toString().trim()
                val passwordText = password.text.toString()
                if (ssidText.isEmpty() || ssidText.toByteArray().size > 32) {
                    Toast.makeText(this@MainActivity, "热点名称必须为 1～32 字节", Toast.LENGTH_SHORT).show()
                    return@onHapticClick
                }
                if (passwordText.toByteArray().size !in 8..63) {
                    Toast.makeText(this@MainActivity, "热点密码必须为 8～63 字节", Toast.LENGTH_SHORT).show()
                    return@onHapticClick
                }
                val selectedBand = band.findViewById<RadioButton>(band.checkedRadioButtonId)?.tag as? String ?: "2"
                val selectedPower = power.findViewById<RadioButton>(power.checkedRadioButtonId)?.tag as? String ?: "balanced"
                val finalBand = when (selectedPower) {
                    "省电" -> "2"
                    "性能" -> "bridged"
                    else -> selectedBand
                }
                val finalWifi6 = when (selectedPower) {
                    "省电" -> false
                    "性能" -> true
                    else -> wifi6.isChecked
                }
                viewModel.saveHotspotConfiguration(HotspotConfiguration(
                    ssid = ssidText,
                    password = passwordText,
                    band = finalBand,
                    wifi6Enabled = finalWifi6,
                    autoShutdownEnabled = autoShutdown.isChecked,
                    shutdownTimeoutMinutes = (timeout.progress + 1) * 5,
                    powerMode = selectedPower
                ))
                Thread {
                    val result = viewModel.restartHotspotIfActive()
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, if (result.success) "热点配置已保存" else "配置已保存：${result.message}", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
        })
        addView(Button(this@MainActivity).apply {
            text = "打开系统热点设置"
            textSize = 12f
            isAllCaps = false
            onHapticClick {
                try {
                    startActivity(Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"))
                } catch (_: Throwable) {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                }
            }
        })
    }

    private fun buildNfcSettings(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(sectionHeader("NFC 配对（外置标签）"))
        nfcStatusText = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, dp(4))
        }
        addView(nfcStatusText)
        addView(Button(this@MainActivity).apply {
            text = "写入 HomeKit 二维码到 NFC 标签"
            textSize = 12f
            isAllCaps = false
            onHapticClick { armNfcWriter() }
        })
        updateNfcStatus()
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

    private fun buildDeviceManagement(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(divider())
        addView(sectionHeader("设备管理"))
        val dangerRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        dangerRow.addView(Button(this@MainActivity).apply {
            text = "重置配件ID"
            textSize = 11f
            isAllCaps = false
            setBackgroundColor(0xFFCC0000.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(4), 0) }
            onHapticClick { resetAccessoryId() }
        })
        dangerRow.addView(Button(this@MainActivity).apply {
            text = "清除配对"
            textSize = 11f
            isAllCaps = false
            setBackgroundColor(0xFFCC6600.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, 0, 0) }
            onHapticClick { clearPairingState() }
        })
        addView(dangerRow)
        addView(Button(this@MainActivity).apply {
            text = "一键修复 HomeKit 配对/命名"
            textSize = 12f
            isAllCaps = false
            setBackgroundColor(0xFF8B1E3F.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            onHapticClick { confirmHomeKitRecovery() }
        })
    }

    private fun buildPairingCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        setBackgroundColor(Color.WHITE)
        addView(LinearLayout(this@MainActivity).apply {
            tag = "pairing_qr_column"
            gravity = Gravity.CENTER
            pairingQrView = ImageView(this@MainActivity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = LinearLayout.LayoutParams(dp(174), dp(174))
            }
            addView(pairingQrView)
        }, LinearLayout.LayoutParams(dp(198), dp(198)))
        addView(View(this@MainActivity).apply {
            tag = "pairing_divider"
            setBackgroundColor(0xFFE2E2E2.toInt())
        }, LinearLayout.LayoutParams(dp(1), dp(150)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(8), 0)
            pairingCardTitle = TextView(this@MainActivity).apply {
                tag = "pairing_card_title"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF222222.toInt())
            }
            addView(pairingCardTitle)
            pairingCardHint = TextView(this@MainActivity).apply {
                tag = "pairing_card_hint"
                textSize = 13f
                setTextColor(0xFF666666.toInt())
                setPadding(0, dp(6), 0, dp(12))
            }
            addView(pairingCardHint)
            pairingCardKeyLabel = TextView(this@MainActivity).apply {
                tag = "pairing_card_key_label"
                textSize = 12f
                setTextColor(0xFF888888.toInt())
            }
            addView(pairingCardKeyLabel)
            pairingCardKey = TextView(this@MainActivity).apply {
                tag = "pairing_card_key"
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF245AA8.toInt())
                setPadding(0, dp(2), 0, 0)
            }
            addView(pairingCardKey)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        updatePairingCard()
    }

    private fun pairingModeLabel(mode: PairingBroadcastMode): String = when (mode) {
        PairingBroadcastMode.QR_SETUP_PAYLOAD -> "二维码 / Setup ID ${viewModel.setupId}"
        PairingBroadcastMode.LEGACY_SETUP_CODE -> "8 位配对码"
    }

    private fun updatePairingCard() {
        val card = pairingCard ?: return
        val qrColumn = card.findViewWithTag<View>("pairing_qr_column") ?: return
        val divider = card.findViewWithTag<View>("pairing_divider") ?: return
        val paired = viewModel.isPaired
        val qrMode = viewModel.pairingBroadcastMode == PairingBroadcastMode.QR_SETUP_PAYLOAD
        card.visibility = if (paired) View.GONE else View.VISIBLE
        pairingModeSummary?.text = "当前广播：${pairingModeLabel(viewModel.pairingBroadcastMode)}"
        pairingPayloadText?.text = if (paired) {
            "Setup ID：${viewModel.setupId}\nPayload：已隐藏"
        } else {
            "Setup ID：${viewModel.setupId}\nPayload：${viewModel.setupPayload}"
        }
        if (paired) return
        qrColumn.visibility = if (qrMode) View.VISIBLE else View.GONE
        divider.visibility = if (qrMode) View.VISIBLE else View.GONE
        if (qrMode) {
            pairingQrView?.setImageBitmap(createQrBitmap(viewModel.setupPayload))
            pairingCardTitle?.text = "扫码配对"
            pairingCardHint?.text = "打开 iPhone 家庭 App\n扫描左侧二维码"
            pairingCardKeyLabel?.text = "Setup ID"
            pairingCardKey?.text = viewModel.setupId
            pairingCardKey?.setTextColor(0xFF245AA8.toInt())
        } else {
            pairingCardTitle?.text = "输入配对码"
            pairingCardHint?.text = "在 iPhone 家庭 App 选择“更多选项”输入"
            pairingCardKeyLabel?.text = "配对码"
            pairingCardKey?.text = viewModel.formatSetupCode(viewModel.setupCodeDigits)
            pairingCardKey?.setTextColor(0xFF245AA8.toInt())
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

    private fun showSettingsPage() {
        updatePairingCard()
        mainPage?.visibility = View.GONE
        settingsPage?.visibility = View.VISIBLE
    }

    private fun showMainPage() {
        settingsPage?.visibility = View.GONE
        mainPage?.visibility = View.VISIBLE
        updateConnectionStatus(refreshRoot = false)
    }

    private fun buildFeatureRow(feature: HomeKitFeature): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        // Command enable switch. This is configuration, not the live Android state.
        val toggle = CheckBox(this).apply {
            isChecked = viewModel.isFeatureEnabled(feature)
            setOnCheckedChangeListener { _, checked ->
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                viewModel.setFeatureEnabled(feature, checked)
                log("COMMAND_TOGGLE feature=$feature enabled=$checked")
            }
        }
        commandEnableToggles[feature] = toggle
        row.addView(toggle)

        // Label
        val label = TextView(this).apply {
            text = getFeatureTitle(feature)
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            setPadding(dp(4), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(label)

        // Test button (tap to execute)
        val testBtn = Button(this).apply {
            text = "测试"
            textSize = 11f
            isAllCaps = false
            setBackgroundColor(0xFFEEEEEE.toInt())
            setTextColor(0xFF333333.toInt())
            onHapticClick { runCommandFromUi(feature) }
        }
        featureButtons[feature] = testBtn
        row.addView(testBtn)

        return row
    }

    private fun showSetupCodeDialog() {
        val digits = viewModel.setupCodeDigits.padEnd(8, '0').take(8)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }

        val inputViews = mutableListOf<EditText>()
        val numInputType = android.text.InputType.TYPE_CLASS_NUMBER

        for (i in 0 until 8) {
            if (i == 4) {
                val dash = TextView(this).apply {
                    text = "-"
                    textSize = 17f
                    typeface = Typeface.MONOSPACE
                    setTextColor(0xFF333333.toInt())
                    gravity = Gravity.CENTER
                    setPadding(dp(3), 0, dp(3), 0)
                }
                container.addView(dash)
            }

            val et = EditText(this).apply {
                textSize = 17f
                typeface = Typeface.MONOSPACE
                inputType = numInputType
                setTextColor(0xFF111111.toInt())
                setPadding(dp(2), dp(4), dp(2), dp(4))
                setBackgroundColor(0xFFF0F0F0.toInt())
                gravity = Gravity.CENTER
                filters = arrayOf(android.text.InputFilter.LengthFilter(1))
                val lp = LinearLayout.LayoutParams(dp(26), dp(36))
                lp.setMargins(dp(1), 0, dp(1), 0)
                layoutParams = lp
                setText(if (i < digits.length) digits[i].toString() else "")
                setSelection(text.length)
            }
            inputViews.add(et)
            container.addView(et)
        }

        // Wire up auto-advance and backspace
        for (i in inputViews.indices) {
            val et = inputViews[i]
            val idx = i
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString() ?: ""
                    if (text.length == 1 && idx < 7) {
                        inputViews[idx + 1].requestFocus()
                    }
                }
            })
            et.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DEL && event.action == android.view.KeyEvent.ACTION_DOWN) {
                    if (et.text.isEmpty() && idx > 0) {
                        val prev = inputViews[idx - 1]
                        prev.setSelection(prev.text.length)
                        prev.requestFocus()
                        true
                    } else false
                } else false
            }
        }

        AlertDialog.Builder(this)
            .setTitle("设置配对码")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val code = inputViews.joinToString("") { it.text.toString() }.filter { it.isDigit() }
                if (!viewModel.isValidSetupCode(code)) {
                    Toast.makeText(this, "无效：不能全相同或连续递增/递减", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.saveSetupCode(code)
                    log("SETUP_CODE_UPDATED format=4-4")
                    updatePairingCard()
                    Toast.makeText(this, "配对码已更新", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()

        // Focus first empty box
        val firstEmpty = inputViews.firstOrNull { it.text.isEmpty() } ?: inputViews[0]
        firstEmpty.requestFocus()
    }

    private fun getFeatureTitle(feature: HomeKitFeature): String = feature.title

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
        runOnUiThread {
            val paired = viewModel.isPaired
            val connected = viewModel.isConnected
            if (refreshRoot) rootStatus = if (rootAvailable()) "Root可用" else "无Root"
            titleView.text = when {
                connected -> "Marionette · 已连接"
                paired -> "Marionette · 已配对"
                else -> "Marionette"
            }
            titleView.setTextColor(when {
                connected -> 0xFF168447.toInt()
                paired -> 0xFF245AA8.toInt()
                else -> 0xFF333333.toInt()
            })
            statusInfo.text = "配对：${if (paired) "已配对" else "未配对"}  |  连接：${if (connected) "HomeKit已连接" else "等待连接"}  |  $rootStatus\nID：${viewModel.accessoryId()}"
            updatePairingCard()
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
        btn.text = "处理中…"
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

    private fun Button.onHapticClick(action: () -> Unit) {
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }
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
        btn.text = if (value) "ON" else "OFF"
        btn.setBackgroundColor(if (value) 0xFF333333.toInt() else 0xFFEEEEEE.toInt())
        btn.setTextColor(if (value) Color.WHITE else 0xFF333333.toInt())
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

    private fun registerUiEvents() {
        if (eventsRegistered) return
        val filter = IntentFilter().apply {
            addAction(HomeKitUiEvents.ACTION_RUNTIME_STATUS)
            addAction(HomeKitUiEvents.ACTION_STATE_CHANGED)
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
    }

    private fun stopHomeKitService() { ServiceControl.stop(this) }

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
        if (requestCode == REQUEST_BLUETOOTH && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            ensurePermissionsAndStart()
        } else if (requestCode == REQUEST_CAMERA) {
            if (requestNotificationPermissionIfNeeded()) startHomeKitService()
        } else if (requestCode == REQUEST_NOTIFICATIONS && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startHomeKitService()
        }
    }

    private fun log(message: String) {
        android.util.Log.i(TAG, message)
    }

    companion object {
        private const val TAG = "MiHotspotHap"
        private const val REQUEST_BLUETOOTH = 100
        private const val REQUEST_NOTIFICATIONS = 101
        private const val REQUEST_CAMERA = 102
        private const val SLIDER_UI_HOLD_MS = 1_200L
        private const val SLIDER_UI_SETTLE_MS = 700L
    }
}
