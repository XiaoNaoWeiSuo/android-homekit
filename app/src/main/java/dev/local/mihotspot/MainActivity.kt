package dev.local.mihotspot

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
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
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import dev.local.mihotspot.domain.HomeKitFeature
import dev.local.mihotspot.ui.MainViewModel

class MainActivity : Activity() {
    private lateinit var statusInfo: TextView
    private lateinit var serviceSwitch: Switch
    private var brightnessSeek: SeekBar? = null
    private var volumeSeek: SeekBar? = null
    private var brightnessValueText: TextView? = null
    private var volumeValueText: TextView? = null
    private val featureToggles = mutableMapOf<HomeKitFeature, CheckBox>()
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
                stateRefreshHandler.postDelayed(this, 1_000L)
            }
        }
    }
    private val brightnessDebounce = Handler(Looper.getMainLooper())
    private var brightnessPending: Runnable? = null
    private val volumeDebounce = Handler(Looper.getMainLooper())
    private var volumePending: Runnable? = null
    private val viewModel by lazy { MainViewModel(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        updateConnectionStatus()
        log("PROBE_CREATED source=Apple_HomeKitADK_f201f98")
        ensurePermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        refreshDeviceState()
        stateRefreshHandler.removeCallbacks(stateRefreshRunnable)
        stateRefreshHandler.postDelayed(stateRefreshRunnable, 1_000L)
    }

    override fun onPause() {
        stateRefreshHandler.removeCallbacks(stateRefreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        stateRefreshHandler.removeCallbacks(stateRefreshRunnable)
        super.onDestroy()
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

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // === Top header: service switch + pairing code ===
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        serviceSwitch = Switch(this).apply {
            text = "Marionette"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isChecked = true
            setTextColor(0xFF333333.toInt())
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
        headerRow.addView(serviceSwitch, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val codeBtn = Button(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            isAllCaps = false
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(0xFF333333.toInt())
            text = viewModel.formatSetupCode(viewModel.setupCodeDigits)
            onHapticClick { showSetupCodeDialog() }
        }
        headerRow.addView(codeBtn)
        root.addView(headerRow)

        // Status + accessory info
        statusInfo = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF666666.toInt())
        }
        root.addView(statusInfo)
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
                    brightnessPending?.let { brightnessDebounce.removeCallbacks(it) }
                    brightnessPending = Runnable { setBrightnessFromUi(progress) }.also { brightnessDebounce.postDelayed(it, 300L) }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    brightnessPending?.let { brightnessDebounce.removeCallbacks(it) }
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
                    volumePending?.let { volumeDebounce.removeCallbacks(it) }
                    volumePending = Runnable { setVolumeFromUi(progress) }.also { volumeDebounce.postDelayed(it, 300L) }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    volumePending?.let { volumeDebounce.removeCallbacks(it) }
                    setVolumeFromUi(s.progress)
                }
            })
        }
        root.addView(volumeSeek)

        // === Danger zone ===
        root.addView(sectionHeader("设备管理"))
        val dangerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val resetBtn = Button(this).apply {
            text = "重置配件ID"
            textSize = 11f
            isAllCaps = false
            setBackgroundColor(0xFFCC0000.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(4), 0) }
            onHapticClick { resetAccessoryId() }
        }
        val clearBtn = Button(this).apply {
            text = "清除配对"
            textSize = 11f
            isAllCaps = false
            setBackgroundColor(0xFFCC6600.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, 0, 0) }
            onHapticClick { clearPairingState() }
        }
        dangerRow.addView(resetBtn)
        dangerRow.addView(clearBtn)
        root.addView(dangerRow)
        root.addView(Button(this).apply {
            text = "一键修复 HomeKit 配对/命名"
            textSize = 12f
            isAllCaps = false
            setBackgroundColor(0xFF8B1E3F.toInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            onHapticClick { confirmHomeKitRecovery() }
        })

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
    }

    private fun buildFeatureRow(feature: HomeKitFeature): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        // Toggle switch
        val toggle = CheckBox(this).apply {
            isChecked = viewModel.isFeatureEnabled(feature)
            setOnCheckedChangeListener { _, checked ->
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                viewModel.setFeatureEnabled(feature, checked)
                log("COMMAND_TOGGLE feature=$feature enabled=$checked")
            }
        }
        featureToggles[feature] = toggle
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
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }

        val inputViews = mutableListOf<EditText>()
        val numInputType = android.text.InputType.TYPE_CLASS_NUMBER

        for (i in 0 until 8) {
            if (i == 4) {
                val dash = TextView(this).apply {
                    text = "-"
                    textSize = 20f
                    typeface = Typeface.MONOSPACE
                    setTextColor(0xFF333333.toInt())
                    gravity = Gravity.CENTER
                    setPadding(dp(6), 0, dp(6), 0)
                }
                container.addView(dash)
            }

            val et = EditText(this).apply {
                textSize = 20f
                typeface = Typeface.MONOSPACE
                inputType = numInputType
                setTextColor(0xFF111111.toInt())
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setBackgroundColor(0xFFF0F0F0.toInt())
                gravity = Gravity.CENTER
                filters = arrayOf(android.text.InputFilter.LengthFilter(1))
                val lp = LinearLayout.LayoutParams(dp(32), dp(42))
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
                    Toast.makeText(this, "配对码已更新", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()

        // Focus first empty box
        val firstEmpty = inputViews.firstOrNull { it.text.isEmpty() } ?: inputViews[0]
        firstEmpty.requestFocus()
    }

    private fun getFeatureTitle(feature: HomeKitFeature): String = when (feature) {
        HomeKitFeature.SCREEN -> "屏幕电源"
        HomeKitFeature.HOTSPOT -> "手机热点"
        HomeKitFeature.GPS -> "GPS 定位"
        HomeKitFeature.LOW_POWER_MODE -> "低电量模式"
        HomeKitFeature.DO_NOT_DISTURB -> "勿扰模式"
        HomeKitFeature.FLASHLIGHT -> "手电筒"
        HomeKitFeature.MUTE -> "媒体静音"
        HomeKitFeature.BRIGHTNESS -> "屏幕亮度"
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
        runOnUiThread {
            val paired = viewModel.isPaired
            if (refreshRoot) rootStatus = if (rootAvailable()) "Root可用" else "无Root"
            statusInfo.text = "配对: ${if (paired) "已配对" else "未配对"}  |  $rootStatus  |  ID: ${viewModel.accessoryId()}"
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
                        featureButtons[feature]?.let { btn ->
                            btn.text = if (value == true) "ON" else "OFF"
                            btn.setBackgroundColor(if (value == true) 0xFF333333.toInt() else 0xFFEEEEEE.toInt())
                            btn.setTextColor(if (value == true) Color.WHITE else 0xFF333333.toInt())
                        }
                    }
                    brightnessSeek?.takeIf { !it.isPressed }?.let { seek ->
                        seek.progress = state.brightness
                        brightnessValueText?.text = "${state.brightness}%"
                    }
                    volumeSeek?.takeIf { !it.isPressed }?.let { seek ->
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
        featureToggles[feature]?.isEnabled = false
        btn.isEnabled = false
        btn.text = "处理中…"
        Thread {
            try {
                val result = viewModel.toggle(feature)
                log("UI_COMMAND feature=$feature success=${result.success} message=${result.message}")
            } finally {
                runOnUiThread {
                    featureLoading.remove(feature)
                    featureToggles[feature]?.isEnabled = true
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

    private fun setBrightnessFromUi(value: Int) {
        Thread { viewModel.setBrightness(value) }.start()
    }

    private fun setVolumeFromUi(value: Int) {
        Thread { viewModel.setVolume(value) }.start()
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
    }
}
