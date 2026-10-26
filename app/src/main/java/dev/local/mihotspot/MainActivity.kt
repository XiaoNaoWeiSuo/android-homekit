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
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import dev.local.mihotspot.ui.UiTheme
import dev.local.mihotspot.ui.createHelpBubble
import dev.local.mihotspot.ui.createSectionHeader
import dev.local.mihotspot.ui.createCard
import dev.local.mihotspot.ui.createCardDivider
import dev.local.mihotspot.ui.createStatusBadge
import dev.local.mihotspot.ui.createPageHeader
import dev.local.mihotspot.ui.onHapticClick

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
    private lateinit var statusBadge: TextView
    private lateinit var rootBadge: TextView
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
    private var strategySummary: TextView? = null
    private var strategyRadioGroup: RadioGroup? = null
    private var pairingPayloadText: TextView? = null
    private var airDropStatusBadge: TextView? = null
    private var airDropSummaryText: TextView? = null
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
        viewModel.close()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (settingsPage?.visibility == View.VISIBLE) {
            showMainPage()
        } else {
            super.onBackPressed()
        }
    }

    private fun dp(v: Float): Int = UiTheme.dp(this, v)
    private fun dp(v: Int): Int = dp(v.toFloat())

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.COLOR_BG)
            setPadding(0, dp(12), 0, dp(24))
        }

        // === Header Card: Title, Status Pills, Service Switch, Settings Button ===
        val headerCard = createCard(this, verticalPaddingDp = 14f, horizontalPaddingDp = 16f)
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        titleView = TextView(this).apply {
            text = "Marionette"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
        }
        titleCol.addView(titleView)

        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        statusBadge = createStatusBadge(this, "● 等待连接", UiTheme.COLOR_TEXT_MUTED, UiTheme.COLOR_INPUT_BG)
        badgeRow.addView(statusBadge)

        val badgeSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), 1)
        }
        badgeRow.addView(badgeSpacer)

        rootBadge = createStatusBadge(this, "Root检测中", UiTheme.COLOR_TEXT_MUTED, UiTheme.COLOR_INPUT_BG)
        badgeRow.addView(rootBadge)

        titleCol.addView(badgeRow)
        topRow.addView(titleCol)

        serviceSwitch = Switch(this).apply {
            text = "服务"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isChecked = ServiceControl.isEnabled(this@MainActivity)
            setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    ensurePermissionsAndStart()
                } else {
                    stopHomeKitService()
                }
            }
        }
        topRow.addView(serviceSwitch)

        val settingsBtn = Button(this).apply {
            text = "设置"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(dp(64), dp(38)).apply {
                setMargins(dp(10), 0, 0, 0)
            }
            layoutParams = lp
            UiTheme.styleButton(this, UiTheme.COLOR_BTN_SECONDARY_BG, UiTheme.COLOR_TEXT_PRIMARY, radiusDp = 19f)
            onHapticClick { showSettingsPage() }
            contentDescription = "打开设备设置"
        }
        topRow.addView(settingsBtn)

        headerCard.addView(topRow)

        statusInfo = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            setPadding(0, dp(10), 0, 0)
        }
        headerCard.addView(statusInfo)
        root.addView(headerCard)

        pairingCard = buildPairingCard()
        root.addView(pairingCard)

        // === Device name card ===
        val nameCard = createCard(this, verticalPaddingDp = 10f, horizontalPaddingDp = 16f)
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameLabel = TextView(this).apply {
            text = "设备名称"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
        }
        nameRow.addView(nameLabel)

        val nameHelp = createHelpBubble(
            this,
            "设备名称说明",
            "此名称将作为 BLE 广播名称和 HomeKit 配件在家庭 App 中显示的设备名称。保存后服务将自动重启广播以应用新名称。"
        )
        nameRow.addView(nameHelp)

        val nameEdit = EditText(this).apply {
            hint = "设置设备名称"
            setText(viewModel.deviceName)
            setSingleLine(true)
            textSize = 14f
            setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(UiTheme.COLOR_INPUT_BG)
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(8), 0, dp(8), 0)
            }
            layoutParams = lp
        }
        nameRow.addView(nameEdit)

        val nameSaveBtn = Button(this).apply {
            text = "保存"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(dp(64), dp(38))
            layoutParams = lp
            UiTheme.styleButton(this, UiTheme.COLOR_ACCENT, Color.WHITE, radiusDp = 12f)
            onHapticClick {
                val name = nameEdit.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.saveDeviceName(name)
                    log("DEVICE_NAME_UPDATED name=$name")
                    restartHomeKitService()
                    Toast.makeText(this@MainActivity, "设备名称已保存", Toast.LENGTH_SHORT).show()
                }
            }
        }
        nameRow.addView(nameSaveBtn)
        nameCard.addView(nameRow)
        root.addView(nameCard)

        // === Feature Controls ===
        val featuresHelp = "• 左侧开关：设置是否允许 HomeKit 远程读取与控制该功能。\n• 右侧按钮：当前设备真实状态（ON/OFF），点击可立即在本地切换并测试该功能。"
        root.addView(createSectionHeader(this, "功能控制", "功能控制说明", featuresHelp))

        val featuresCard = createCard(this, verticalPaddingDp = 4f, horizontalPaddingDp = 16f)
        HomeKitFeature.tileFeatures.forEachIndexed { index, feature ->
            if (index > 0) {
                featuresCard.addView(createCardDivider(this))
            }
            featuresCard.addView(buildFeatureRow(feature))
        }
        root.addView(featuresCard)

        // === Continuous Adjustments ===
        val slidersHelp = "支持屏幕亮度和媒体音量的平滑调节。\n• 亮度档位已映射为 Android 系统真值。\n• 滑块操作自带防抖与延迟平抑，避免与 HomeKit 定时轮询互相冲突。"
        root.addView(createSectionHeader(this, "连续调节", "连续调节说明", slidersHelp))

        val slidersCard = createCard(this, verticalPaddingDp = 14f, horizontalPaddingDp = 16f)

        // Brightness
        val bHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bLabel = TextView(this).apply {
            text = "屏幕亮度"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        brightnessValueText = TextView(this).apply {
            text = "0%"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_ACCENT)
        }
        bHeader.addView(bLabel)
        bHeader.addView(brightnessValueText)
        slidersCard.addView(bHeader)

        brightnessSeek = SeekBar(this).apply {
            max = 100
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(8), 0, dp(12))
            }
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
        slidersCard.addView(brightnessSeek)

        slidersCard.addView(createCardDivider(this))

        // Volume
        val vHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val vLabel = TextView(this).apply {
            text = "媒体音量"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        volumeValueText = TextView(this).apply {
            text = "0%"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_ACCENT)
        }
        vHeader.addView(vLabel)
        vHeader.addView(volumeValueText)
        slidersCard.addView(vHeader)

        volumeSeek = SeekBar(this).apply {
            max = 100
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(8), 0, dp(4))
            }
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
        slidersCard.addView(volumeSeek)

        root.addView(slidersCard)

        // === AirDrop Feature Entry ===
        val airDropHelp = "• 原生实验扩展：基于点对点 AWDL 协议，面向苹果设备的「所有人」模式。\n• 常驻待机：开机自启并保持正常 Wi-Fi 联网，兼容驱动就绪后可实现无缝后台互传。\n• 独占实验：10 分钟临时会话，用于当前硬件的收发协议与功能验证。"
        root.addView(createSectionHeader(this, "AirDrop 互传", "AirDrop 说明", airDropHelp))

        val airDropCard = createCard(this, verticalPaddingDp = 14f, horizontalPaddingDp = 16f).apply {
            background = UiTheme.createButtonRipple(
                this@MainActivity,
                UiTheme.COLOR_CARD_BG,
                UiTheme.adjustAlpha(Color.BLACK, 0.05f),
                radiusDp = 16f,
                strokeColor = UiTheme.COLOR_CARD_BORDER,
                strokeWidthDp = 0.8f
            )
            isClickable = true
            isFocusable = true
            onHapticClick {
                startActivity(Intent(this@MainActivity, dev.local.mihotspot.airdrop.AirDropActivity::class.java))
            }
        }

        val airDropRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val airDropTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val airDropTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val airDropTitle = TextView(this).apply {
            text = "AirDrop 文件收发"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
        }
        airDropTitleRow.addView(airDropTitle)

        val airDropTag = createStatusBadge(this, "实验版", UiTheme.COLOR_ACCENT, UiTheme.COLOR_ACCENT_LIGHT).apply {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            }
            layoutParams = lp
        }
        airDropTitleRow.addView(airDropTag)
        airDropTextCol.addView(airDropTitleRow)

        airDropSummaryText = TextView(this).apply {
            text = "面向苹果设备 · 点击进入配置与收发"
            textSize = 12f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            setPadding(0, dp(4), 0, 0)
        }
        airDropTextCol.addView(airDropSummaryText)
        airDropRow.addView(airDropTextCol)

        airDropStatusBadge = createStatusBadge(this, "○ 未开启", UiTheme.COLOR_TEXT_MUTED, UiTheme.COLOR_INPUT_BG)
        airDropRow.addView(airDropStatusBadge)

        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            val lp = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                marginStart = dp(6)
            }
            layoutParams = lp
        }
        airDropRow.addView(chevron)

        airDropCard.addView(airDropRow)
        root.addView(airDropCard)

        val mainScroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(UiTheme.COLOR_BG)
            addView(root)
        }
        mainPage = mainScroll

        // === Settings Page Layout ===
        val settingsScrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.COLOR_BG)
            setPadding(0, 0, 0, dp(24))
        }
        settingsScrollContent.addView(buildBroadcastStrategySettings())
        settingsScrollContent.addView(buildPairingSettings())
        settingsScrollContent.addView(buildHotspotSettings())
        settingsScrollContent.addView(buildNfcSettings())
        settingsScrollContent.addView(buildDeviceManagement())
        val settingsScroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(UiTheme.COLOR_BG)
            addView(settingsScrollContent)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val settingsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.COLOR_BG)
            addView(buildSettingsHeader())
            addView(settingsScroll)
            visibility = View.GONE
        }
        settingsPage = settingsContainer

        return FrameLayout(this).apply {
            addView(mainScroll, FrameLayout.LayoutParams(-1, -1))
            addView(settingsContainer, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun buildSettingsHeader(): View = createPageHeader(
        context = this,
        titleText = "设备设置",
        onBackClick = { showMainPage() }
    )

    private fun buildBroadcastStrategySettings(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val help = "控制 BLE 广播间隔与重连优先级：\n\n• 自动：白天低延迟响应，夜间（23:00~07:00）自动切入低功耗静默。\n• 低延迟：保持高频广播，优先保证从家庭 App 打开时的秒级响应。\n• 均衡：响应速度与待机功耗兼顾。\n• 低功耗：增大广播间隔，最大程度减少电量消耗。\n\n各档位互斥，修改后立即重启广播生效。"
        addView(createSectionHeader(this@MainActivity, "广播与响应策略", "广播策略说明", help))

        val card = createCard(this@MainActivity)
        strategySummary = TextView(this@MainActivity).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_ACCENT)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = UiTheme.createPillDrawable(this@MainActivity, UiTheme.COLOR_ACCENT_LIGHT)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            layoutParams = lp
        }
        card.addView(strategySummary)

        val group = RadioGroup(this@MainActivity).apply {
            orientation = RadioGroup.VERTICAL
        }
        BroadcastStrategy.entries.forEachIndexed { index, strategy ->
            if (index > 0) {
                group.addView(createCardDivider(this@MainActivity))
            }
            group.addView(RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                tag = strategy.storageValue
                text = when (strategy) {
                    BroadcastStrategy.AUTO -> "自动 · 白天低延迟，夜间低功耗"
                    BroadcastStrategy.LOW_LATENCY -> "低延迟 · 优先响应速度"
                    BroadcastStrategy.BALANCED -> "均衡 · 响应与功耗折中"
                    BroadcastStrategy.LOW_POWER -> "低功耗 · 优先降低待机耗电"
                }
                textSize = 14f
                setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
                isChecked = ServiceControl.broadcastStrategy(this@MainActivity) == strategy
                setPadding(dp(8), dp(8), 0, dp(8))
            })
        }
        strategyRadioGroup = group
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val selected = radioGroup.findViewById<RadioButton>(checkedId)
                ?.tag?.toString()?.let(BroadcastStrategy::fromStorage) ?: return@setOnCheckedChangeListener
            if (selected == ServiceControl.broadcastStrategy(this@MainActivity)) return@setOnCheckedChangeListener
            ServiceControl.setBroadcastStrategy(this@MainActivity, selected)
            updateBroadcastStrategySummary()
            log("BROADCAST_STRATEGY_CHANGED configured=${selected.storageValue}")
            if (ServiceControl.isEnabled(this@MainActivity)) restartHomeKitService()
            else Toast.makeText(this@MainActivity, "已保存，启动服务后生效", Toast.LENGTH_SHORT).show()
        }
        card.addView(group)
        addView(card)
        updateBroadcastStrategySummary()
    }

    private fun updateBroadcastStrategySummary() {
        val configured = ServiceControl.broadcastStrategy(this)
        val paired = getSharedPreferences("homekit", MODE_PRIVATE).contains("controller_key")
        val effective = ServiceControl.effectiveBroadcastStrategy(this, paired)
        val reason = when {
            configured != BroadcastStrategy.AUTO -> "手动指定"
            paired && ServiceControl.isNightQuietWindow() -> "自动挡 · 夜间静默时段"
            else -> "自动挡 · 当前优先响应"
        }
        strategySummary?.text = "当前生效：${effective.title}（$reason）"
    }

    private fun buildPairingSettings(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val help = "• 二维码：4 位 Setup ID，使用家庭 App 扫码即连。\n• 配对码：8 位数字配对码，在家庭 App「更多选项」中手动输入。\n\n切换广播模式仅重启广播，不会破坏已有的配对关系。"
        addView(createSectionHeader(this@MainActivity, "配对方式", "配对方式说明", help))

        val card = createCard(this@MainActivity)
        val tabs = RadioGroup(this@MainActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            val qr = RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                text = "二维码 · 4位"
                textSize = 14f
                setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
                isChecked = viewModel.pairingBroadcastMode == PairingBroadcastMode.QR_SETUP_PAYLOAD
                layoutParams = RadioGroup.LayoutParams(0, -2, 1f)
            }
            val code = RadioButton(this@MainActivity).apply {
                id = View.generateViewId()
                text = "配对码 · 8位"
                textSize = 14f
                setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
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
        card.addView(tabs)

        pairingModeSummary = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            setPadding(0, dp(6), 0, dp(6))
        }
        card.addView(pairingModeSummary)

        pairingPayloadText = TextView(this@MainActivity).apply {
            text = "Setup ID：${viewModel.setupId}\nPayload：${viewModel.setupPayload}"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(UiTheme.COLOR_TEXT_SECONDARY)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(UiTheme.COLOR_INPUT_BG)
            }
            val lp = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(10)
            }
            layoutParams = lp
        }
        card.addView(pairingPayloadText)

        val editCodeBtn = Button(this@MainActivity).apply {
            text = "修改 8 位配对码"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(-1, dp(46))
            layoutParams = lp
            UiTheme.styleButton(this, UiTheme.COLOR_BTN_SECONDARY_BG, UiTheme.COLOR_TEXT_PRIMARY, radiusDp = 12f)
            onHapticClick { showSetupCodeDialog() }
        }
        card.addView(editCodeBtn)

        addView(card)
    }

    private fun buildHotspotSettings(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val current = viewModel.hotspotConfiguration()
        val help = "Marionette 与系统共用这一套热点配置；启动前会自动停用已有 SoftAP，避免出现两个个人热点冲突。\n\n• 功耗策略说明：\n  - 省电：优先 2.4 GHz 频段\n  - 平衡：保持选定频段\n  - 性能：优先双频加速\n\n• SSID、密码和频段由本页直接应用；Wi-Fi 6、自动关闭时长等由 MIUI 系统热点页最终落地。"
        addView(createSectionHeader(this@MainActivity, "热点设置", "热点设置说明", help))

        val card = createCard(this@MainActivity)

        fun field(label: String, value: String, password: Boolean = false): EditText {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
            }, LinearLayout.LayoutParams(dp(76), -2))
            val editor = EditText(this@MainActivity).apply {
                setText(value)
                textSize = 14f
                setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
                setSingleLine(true)
                if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(UiTheme.COLOR_INPUT_BG)
                }
            }
            row.addView(editor, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(row)
            return editor
        }

        val ssid = field("名称", current.ssid)
        card.addView(createCardDivider(this@MainActivity))
        val password = field("密码", current.password, password = true)
        card.addView(createCardDivider(this@MainActivity))

        val bandRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(this@MainActivity).apply {
                text = "频段"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
            }, LinearLayout.LayoutParams(dp(76), -2))
        }
        val band = RadioGroup(this@MainActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            val options = listOf("2" to "2.4G", "5" to "5G", "bridged" to "双频")
            options.forEach { (value, label) ->
                addView(RadioButton(this@MainActivity).apply {
                    id = View.generateViewId()
                    tag = value
                    text = label
                    textSize = 13f
                    setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
                    isChecked = current.band == value
                    layoutParams = RadioGroup.LayoutParams(0, -2, 1f)
                })
            }
        }
        bandRow.addView(band, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(bandRow)
        card.addView(createCardDivider(this@MainActivity))

        fun option(text: String, checked: Boolean): Switch = Switch(this@MainActivity).apply {
            this.text = text
            textSize = 14f
            isChecked = checked
            setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
            setPadding(0, dp(8), 0, dp(8))
        }
        val wifi6 = option("启用 Wi-Fi 6（802.11ax）", current.wifi6Enabled)
        card.addView(wifi6)
        card.addView(createCardDivider(this@MainActivity))

        val autoShutdown = option("无设备连接时自动关闭", current.autoShutdownEnabled)
        card.addView(autoShutdown)
        card.addView(createCardDivider(this@MainActivity))

        val timeoutLabel = TextView(this@MainActivity).apply {
            textSize = 13f
            setTextColor(UiTheme.COLOR_TEXT_SECONDARY)
            text = "自动关闭等待：${(current.shutdownTimeoutMinutes / 5).coerceIn(1, 24) * 5} 分钟"
            setPadding(0, dp(8), 0, dp(2))
        }
        card.addView(timeoutLabel)
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
        card.addView(timeout)
        card.addView(createCardDivider(this@MainActivity))

        val powerRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(this@MainActivity).apply {
                text = "功耗策略"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
            }, LinearLayout.LayoutParams(dp(76), -2))
        }
        val power = RadioGroup(this@MainActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf("省电" to "省电", "balanced" to "平衡", "性能" to "性能").forEach { (value, label) ->
                addView(RadioButton(this@MainActivity).apply {
                    id = View.generateViewId()
                    tag = value
                    text = label
                    textSize = 13f
                    setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
                    isChecked = current.powerMode == value
                    layoutParams = RadioGroup.LayoutParams(0, -2, 1f)
                })
            }
        }
        powerRow.addView(power, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(powerRow)

        val saveBtn = Button(this@MainActivity).apply {
            text = "保存并应用热点配置"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(14)
            }
            layoutParams = lp
            UiTheme.styleButton(this, UiTheme.COLOR_ACCENT, Color.WHITE, radiusDp = 12f)
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
        }
        card.addView(saveBtn)

        val sysBtn = Button(this@MainActivity).apply {
            text = "打开系统热点设置"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(-1, dp(46)).apply {
                topMargin = dp(8)
            }
            layoutParams = lp
            UiTheme.styleButton(this, UiTheme.COLOR_BTN_SECONDARY_BG, UiTheme.COLOR_TEXT_PRIMARY, radiusDp = 12f)
            onHapticClick {
                try {
                    startActivity(Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"))
                } catch (_: Throwable) {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                }
            }
        }
        card.addView(sysBtn)

        addView(card)
    }

    private fun buildNfcSettings(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val help = "可将 HomeKit 配对 Payload 写入到外置 NFC Forum Type 2 标签中。靠近支持 NFC 的 iPhone 即可直接唤起家庭 App 配对流程。\n\n注意：仅在未配对且为二维码广播模式时可用。"
        addView(createSectionHeader(this@MainActivity, "NFC 配对", "NFC 配对说明", help))

        val card = createCard(this@MainActivity)
        nfcStatusText = TextView(this@MainActivity).apply {
            textSize = 13f
            setTextColor(UiTheme.COLOR_TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(10))
        }
        card.addView(nfcStatusText)

        val nfcBtn = Button(this@MainActivity).apply {
            text = "写入 HomeKit 二维码到 NFC 标签"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(-1, dp(46))
            layoutParams = lp
            UiTheme.styleButton(this, UiTheme.COLOR_BTN_SECONDARY_BG, UiTheme.COLOR_TEXT_PRIMARY, radiusDp = 12f)
            onHapticClick { armNfcWriter() }
        }
        card.addView(nfcBtn)
        addView(card)
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
        val help = "• 重置配件 ID：生成全新的配件身份与密钥，清除旧配对，用于彻底模拟新设备。\n• 清除配对：保留现有配件 ID，仅清除绑定的家庭 App 控制器密钥。\n• 一键修复：如果家庭 App 出现无法连接、旧卡片残留或服务名称混乱，使用此功能可重建配件身份并重新发布服务。"
        addView(createSectionHeader(this@MainActivity, "设备管理", "设备管理说明", help))

        val card = createCard(this@MainActivity)
        val dangerRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val resetBtn = Button(this@MainActivity).apply {
            text = "重置配件ID"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(0, 0, dp(4), 0) }
            UiTheme.styleButton(this, UiTheme.COLOR_DANGER_LIGHT, UiTheme.COLOR_DANGER, radiusDp = 12f)
            onHapticClick { resetAccessoryId() }
        }
        dangerRow.addView(resetBtn)

        val clearBtn = Button(this@MainActivity).apply {
            text = "清除配对"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(4), 0, 0, 0) }
            UiTheme.styleButton(this, UiTheme.COLOR_WARNING_LIGHT, UiTheme.COLOR_WARNING, radiusDp = 12f)
            onHapticClick { clearPairingState() }
        }
        dangerRow.addView(clearBtn)
        card.addView(dangerRow)

        val repairBtn = Button(this@MainActivity).apply {
            text = "一键修复 HomeKit 配对/命名"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) }
            UiTheme.styleButton(this, UiTheme.COLOR_DANGER, Color.WHITE, radiusDp = 12f)
            onHapticClick { confirmHomeKitRecovery() }
        }
        card.addView(repairBtn)
        addView(card)
    }

    private fun buildPairingCard(): LinearLayout = createCard(this, verticalPaddingDp = 16f, horizontalPaddingDp = 16f).apply {
        tag = "pairing_card_root"

        val header = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val cardTitle = TextView(this@MainActivity).apply {
            tag = "pairing_card_title"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
        }
        header.addView(cardTitle)

        val help = createHelpBubble(
            this@MainActivity,
            "配对说明",
            "打开 iPhone 原生「家庭」App：\n• 若为二维码模式：点击右上角「+」→「添加配件」，扫描二维码即可。\n• 若为配对码模式：在家庭 App 选择「更多选项」，点击配件并输入 8 位配对码。\n• 配对成功后，此卡片将自动隐藏。"
        )
        header.addView(help)

        val spacer = View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        header.addView(spacer)
        addView(header)
        addView(createCardDivider(this@MainActivity))

        val contentRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }

        val qrCol = FrameLayout(this@MainActivity).apply {
            tag = "pairing_qr_column"
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), UiTheme.COLOR_CARD_BORDER)
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            pairingQrView = ImageView(this@MainActivity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = FrameLayout.LayoutParams(dp(130), dp(130))
            }
            addView(pairingQrView)
        }
        contentRow.addView(qrCol, LinearLayout.LayoutParams(dp(146), dp(146)))

        val dividerView = View(this@MainActivity).apply {
            tag = "pairing_divider"
            setBackgroundColor(UiTheme.COLOR_DIVIDER)
            val lp = LinearLayout.LayoutParams(dp(1), dp(120)).apply {
                setMargins(dp(16), 0, dp(16), 0)
            }
            layoutParams = lp
        }
        contentRow.addView(dividerView)

        val infoCol = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            pairingCardKeyLabel = TextView(this@MainActivity).apply {
                tag = "pairing_card_key_label"
                textSize = 12f
                setTextColor(UiTheme.COLOR_TEXT_MUTED)
            }
            addView(pairingCardKeyLabel)

            pairingCardKey = TextView(this@MainActivity).apply {
                tag = "pairing_card_key"
                textSize = 22f
                typeface = Typeface.MONOSPACE
                setTextColor(UiTheme.COLOR_ACCENT)
                setPadding(0, dp(2), 0, dp(6))
            }
            addView(pairingCardKey)

            pairingCardHint = TextView(this@MainActivity).apply {
                tag = "pairing_card_hint"
                textSize = 12f
                setTextColor(UiTheme.COLOR_TEXT_SECONDARY)
            }
            addView(pairingCardHint)
        }
        contentRow.addView(infoCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        addView(contentRow)
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
            pairingCardKey?.setTextColor(UiTheme.COLOR_ACCENT)
        } else {
            pairingCardTitle?.text = "输入配对码"
            pairingCardHint?.text = "在 iPhone 家庭 App 选择“更多选项”输入"
            pairingCardKeyLabel?.text = "配对码"
            pairingCardKey?.text = viewModel.formatSetupCode(viewModel.setupCodeDigits)
            pairingCardKey?.setTextColor(UiTheme.COLOR_ACCENT)
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
        updateBroadcastStrategySummary()
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
            setPadding(0, dp(8), 0, dp(8))
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val label = TextView(this).apply {
            text = getFeatureTitle(feature)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
        }
        textCol.addView(label)

        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }

        val toggle = CheckBox(this).apply {
            isChecked = viewModel.isFeatureEnabled(feature)
            buttonTintList = ColorStateList.valueOf(UiTheme.COLOR_ACCENT)
            setOnCheckedChangeListener { _, checked ->
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                viewModel.setFeatureEnabled(feature, checked)
                log("COMMAND_TOGGLE feature=$feature enabled=$checked")
            }
        }
        commandEnableToggles[feature] = toggle
        toggleRow.addView(toggle)

        val toggleLabel = TextView(this).apply {
            text = "允许 HomeKit 控制"
            textSize = 12f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            setOnClickListener { toggle.toggle() }
        }
        toggleRow.addView(toggleLabel)

        textCol.addView(toggleRow)
        row.addView(textCol)

        // Live state toggle button (tap to execute)
        val testBtn = Button(this).apply {
            text = "OFF"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(dp(72), dp(36))
            layoutParams = lp
            UiTheme.styleButton(this, UiTheme.COLOR_BTN_SECONDARY_BG, UiTheme.COLOR_TEXT_SECONDARY, radiusDp = 18f)
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
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }

        val inputViews = mutableListOf<EditText>()
        val numInputType = android.text.InputType.TYPE_CLASS_NUMBER

        for (i in 0 until 8) {
            if (i == 4) {
                val dash = TextView(this).apply {
                    text = "-"
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(UiTheme.COLOR_TEXT_MUTED)
                    gravity = Gravity.CENTER
                    setPadding(dp(4), 0, dp(4), 0)
                }
                container.addView(dash)
            }

            val et = EditText(this).apply {
                textSize = 18f
                typeface = Typeface.MONOSPACE
                inputType = numInputType
                setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
                setPadding(dp(2), dp(6), dp(2), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(UiTheme.COLOR_INPUT_BG)
                    setStroke(dp(1), UiTheme.COLOR_CARD_BORDER)
                }
                gravity = Gravity.CENTER
                filters = arrayOf(android.text.InputFilter.LengthFilter(1))
                val lp = LinearLayout.LayoutParams(dp(30), dp(42))
                lp.setMargins(dp(2), 0, dp(2), 0)
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

            titleView.text = "Marionette"

            if (connected) {
                statusBadge.text = "● 已连接"
                statusBadge.setTextColor(UiTheme.COLOR_SUCCESS)
                statusBadge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_SUCCESS_LIGHT)
            } else if (paired) {
                statusBadge.text = "● 已配对"
                statusBadge.setTextColor(UiTheme.COLOR_ACCENT)
                statusBadge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_ACCENT_LIGHT)
            } else {
                statusBadge.text = "● 等待连接"
                statusBadge.setTextColor(UiTheme.COLOR_TEXT_MUTED)
                statusBadge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_INPUT_BG)
            }

            rootBadge.text = rootStatus
            if (rootStatus == "Root可用") {
                rootBadge.setTextColor(0xFF6B46C1.toInt())
                rootBadge.background = UiTheme.createPillDrawable(this, 0xFFF3E8FD.toInt())
            } else {
                rootBadge.setTextColor(UiTheme.COLOR_TEXT_MUTED)
                rootBadge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_INPUT_BG)
            }

            statusInfo.text = "配件 ID：${viewModel.accessoryId()}"
            updatePairingCard()
            updateAirDropCardStatus()
        }
    }

    private fun updateAirDropCardStatus() {
        val badge = airDropStatusBadge ?: return
        val summary = airDropSummaryText ?: return
        val isSessionActive = java.io.File(dev.local.mihotspot.airdrop.AirDropStore.radio(this), "active").exists()
        val isResident = dev.local.mihotspot.airdrop.AirDropResidentService.isEnabled(this)

        when {
            isSessionActive -> {
                badge.text = "● 实验中"
                badge.setTextColor(UiTheme.COLOR_SUCCESS)
                badge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_SUCCESS_LIGHT)
                summary.text = "独占无线传输正在运行 · 点此进入收发"
            }
            isResident -> {
                badge.text = "● 待机中"
                badge.setTextColor(UiTheme.COLOR_ACCENT)
                badge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_ACCENT_LIGHT)
                summary.text = "常驻服务已开启 · Wi-Fi 正常"
            }
            else -> {
                badge.text = "○ 未开启"
                badge.setTextColor(UiTheme.COLOR_TEXT_MUTED)
                badge.background = UiTheme.createPillDrawable(this, UiTheme.COLOR_INPUT_BG)
                summary.text = "面向苹果设备 · 点击进入配置与收发"
            }
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
        btn.text = if (value) "ON" else "OFF"
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
