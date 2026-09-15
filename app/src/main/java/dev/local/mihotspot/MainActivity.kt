package dev.local.mihotspot

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.BatteryManager
import android.os.Bundle
import android.os.ParcelUuid
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import dev.local.mihotspot.homekit.commands.AndroidCommandExecutor
import dev.local.mihotspot.homekit.commands.HomeKitCommand

/**
 * Discovery and metadata-stage probe. Constants and GATT layout are taken from Apple's
 * HomeKitADK commit fb201f98f5fdc7fef6a455054f08b59cca5d1ec8.
 * Pair Setup and Pair Verify are intentionally not claimed as implemented: this
 * build only completes the unencrypted metadata transactions that precede them.
 */
class MainActivity : Activity() {
    private lateinit var accessoryInfo: TextView
    private lateinit var statusInfo: TextView
    /** In-app mirrors of the device state; tapping a button executes the same
     * command path HomeKit uses, so the app and Home stay in sync. */
    private val stateButtons = mutableMapOf<HomeKitCommand, Button>()
    private var brightnessSeek: SeekBar? = null
    private val refreshingState = AtomicBoolean(false)
    private val brightnessDebounce = Handler(Looper.getMainLooper())
    private var brightnessPending: Runnable? = null
    private lateinit var manager: BluetoothManager
    private var server: BluetoothGattServer? = null
    private var advertising = false
    @Volatile private var starting = false
    private val pendingServices = ArrayDeque<BluetoothGattService>()
    /** GATT characteristic objects are the unambiguous key when two service
     * instances share the same standard service/characteristic UUID pair. */
    private val gattCharacteristicDefinitions = IdentityHashMap<BluetoothGattCharacteristic, HapCharacteristic>()
    private val pduResponses = ConcurrentHashMap<String, HapResponse>()
    private val pduRequests = ConcurrentHashMap<String, HapRequestAssembly>()
    private val peerMtu = ConcurrentHashMap<String, Int>()
    private val connectedDevices = ConcurrentHashMap.newKeySet<String>()
    private val pairSetupSessions = ConcurrentHashMap<String, PairSetupSession>()
    private val pairVerifySessions = ConcurrentHashMap<String, PairVerifySession>()
    private val commandExecutor by lazy { AndroidCommandExecutor(this) }
    private val commandPrefs by lazy { getSharedPreferences("homekit", MODE_PRIVATE) }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val accessoryPrivateKey: Ed25519PrivateKeyParameters by lazy { loadOrCreateAccessoryPrivateKey() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(BluetoothManager::class.java)
        setContentView(buildUi())
        updateConnectionStatus()
        createNotificationChannel()
        log("PROBE_CREATED source=Apple_HomeKitADK_f201f98")
        ensurePermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        refreshDeviceState()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only tear down BLE when the Activity is genuinely finishing (back key /
        // finish()). MIUI destroys the Activity on screen-off and other transient
        // lifecycle events; stopping the probe there drops advertising and makes
        // the accessory show "No Response" in the Home app.
        if (isFinishing) stopProbe()
    }

    private fun loadOrCreateAccessoryPrivateKey(): Ed25519PrivateKeyParameters {
        val preferences = getSharedPreferences("homekit", MODE_PRIVATE)
        preferences.getString("accessory_seed", null)?.let { return Ed25519PrivateKeyParameters(Base64.decode(it, Base64.NO_WRAP), 0) }
        return Ed25519PrivateKeyParameters(secureRandom).also { key ->
            preferences.edit().putString("accessory_seed", Base64.encodeToString(key.encoded, Base64.NO_WRAP)).apply()
        }
    }

    private fun buildUi(): ScrollView {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = "Marionette"
            textSize = 25f
        })
        column.addView(TextView(this).apply {
            text = "通过 HomeKit 加密通道接收控制指令。配对码界面使用 4-4 格式，协议内部自动转换为 HomeKit 标准格式。"
            textSize = 16f
            setPadding(0, pad, 0, pad)
        })
        val deviceName = EditText(this).apply {
            hint = "设备名称"
            setText(commandPrefs.getString("device_name", "Android"))
            setSingleLine(true)
        }
        column.addView(deviceName)
        column.addView(Button(this).apply {
            text = "保存设备名称"
            setOnClickListener {
                val name = deviceName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this@MainActivity, "设备名称不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    commandPrefs.edit().putString("device_name", name).apply()
                    log("DEVICE_NAME_UPDATED name=$name")
                    if (advertising) { stopProbe(); ensurePermissionsAndStart() }
                }
            }
        })
        val setupCode = EditText(this).apply {
            hint = "配对码（8位，4-4）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(formatSetupCode44(commandPrefs.getString("setup_code_digits", DEFAULT_SETUP_CODE_DIGITS) ?: DEFAULT_SETUP_CODE_DIGITS))
            setSingleLine(true)
        }
        column.addView(setupCode)
        column.addView(Button(this).apply {
            text = "保存配对码"
            setOnClickListener {
                val digits = setupCode.text.toString().filter(Char::isDigit)
                if (!isValidSetupCodeDigits(digits)) {
                    Toast.makeText(this@MainActivity, "请输入8位数字，不能全相同或连续递增/递减", Toast.LENGTH_SHORT).show()
                } else {
                    commandPrefs.edit().putString("setup_code_digits", digits).apply()
                    setupCode.setText(formatSetupCode44(digits))
                    log("SETUP_CODE_UPDATED format=4-4")
                }
            }
        })
        column.addView(Button(this).apply {
            text = "重置配件 ID（需重新配对）"
            setOnClickListener { resetAccessoryId() }
        })
        column.addView(Button(this).apply {
            text = "清除配对状态（保留配件 ID）"
            setOnClickListener { clearPairingState() }
        })
        accessoryInfo = TextView(this).apply {
            text = "配件 ID：${accessoryPairingId()}\nRoot：${if (rootAvailable()) "可用" else "不可用"}"
            textSize = 14f
            setPadding(0, 0, 0, pad / 2)
        }
        column.addView(accessoryInfo)
        statusInfo = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, pad / 2)
        }
        column.addView(statusInfo)
        addSectionHeader(column, "当前状态（点击切换，与 HomeKit 同步）")
        for (command in listOf(HomeKitCommand.SCREEN, HomeKitCommand.HOTSPOT, HomeKitCommand.MUTE, HomeKitCommand.FLASHLIGHT)) {
            val button = Button(this).apply {
                text = stateButtonLabel(command, null)
                setOnClickListener { runCommandFromUi(command) }
            }
            stateButtons[command] = button
            column.addView(button)
        }
        column.addView(TextView(this).apply {
            text = "屏幕亮度（拖动调节）"
            textSize = 14f
        })
        brightnessSeek = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    // Debounce: MIUI sysfs writes take ~100-300 ms each; live-drag
                    // would otherwise queue dozens of root shells.
                    brightnessPending?.let { brightnessDebounce.removeCallbacks(it) }
                    val target = progress
                    brightnessPending = Runnable { setBrightnessFromUi(target) }
                        .also { brightnessDebounce.postDelayed(it, 300L) }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    brightnessPending?.let { brightnessDebounce.removeCallbacks(it) }
                    setBrightnessFromUi(seekBar.progress)
                }
            })
        }
        column.addView(brightnessSeek)
        column.addView(TextView(this).apply {
            text = "功能开关"
            textSize = 16f
        })
        addSectionHeader(column, "需要 Root")
        addCommandToggle(column, HomeKitCommand.SCREEN, "屏幕电源键（按钮）", "模拟一次实体电源键，自动切换亮屏/熄屏")
        addCommandToggle(column, HomeKitCommand.HOTSPOT, "手机热点", "Root 优先控制系统 SoftAP；免 Root 仅支持本地热点")
        addSectionHeader(column, "免 Root / 系统授权")
        addCommandToggle(column, HomeKitCommand.MUTE, "媒体静音", "系统媒体静音；若被 MIUI 拦截则使用 Root 音量兜底")
        addCommandToggle(column, HomeKitCommand.FLASHLIGHT, "手机手电筒", "需要相机权限，不需要 Root")
        column.addView(Button(this).apply {
            text = "授权手电筒相机权限"
            setOnClickListener {
                if (checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
                } else {
                    Toast.makeText(this@MainActivity, "相机权限已授权", Toast.LENGTH_SHORT).show()
                }
            }
        })
        column.addView(TextView(this).apply {
            text = "屏幕亮度\nHomeKit 中通过 Brightness 滑块控制（需 Root 或允许修改系统设置）"
            textSize = 15f
            setPadding(0, 8, 0, 12)
        })
        column.addView(Button(this).apply {
            text = "启动 HomeKit 服务"
            setOnClickListener { ensurePermissionsAndStart() }
        })
        column.addView(Button(this).apply {
            text = "停止 HomeKit 服务"
            setOnClickListener { stopProbe() }
        })
        return ScrollView(this).apply { addView(column) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(
                NotificationChannel(NOTIFICATION_CHANNEL_ID, "HomeKit 控制", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示 HomeKit 开关指令的简要状态"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun notifyCommand(command: HomeKitCommand, enabled: Boolean, success: Boolean, detail: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val label = when (command) {
            HomeKitCommand.SCREEN -> "屏幕电源键"
            HomeKitCommand.BRIGHTNESS -> "屏幕亮度"
            HomeKitCommand.HOTSPOT -> "热点"
            HomeKitCommand.MUTE -> "媒体静音"
            HomeKitCommand.FLASHLIGHT -> "手电筒"
        }
        val state = if (command == HomeKitCommand.SCREEN) "已按下" else if (enabled) "开启" else "关闭"
        val suffix = if (success) "" else "（未执行）"
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("HomeKit 指令")
            .setContentText("$label：$state$suffix")
            .setStyle(Notification.BigTextStyle().bigText("$label：$state$suffix\n$detail"))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        // One stable notification per command prevents HomeKit polling from
        // creating a notification flood.
        notificationManager.notify(NOTIFICATION_BASE_ID + command.ordinal, notification)
    }

    private fun addCommandToggle(column: LinearLayout, command: HomeKitCommand, label: String, permission: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6, 0, 8)
        }
        val check = CheckBox(this).apply {
            text = label
            minWidth = 0
            setPadding(0, 0, 0, 0)
            isChecked = commandPrefs.getBoolean("command_enabled_${command.name}", true)
            setOnCheckedChangeListener { _, checked ->
                commandPrefs.edit().putBoolean("command_enabled_${command.name}", checked).apply()
                log("COMMAND_TOGGLE command=$command enabled=$checked")
            }
        }
        row.addView(check, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(this).apply {
            text = permission
            textSize = 12f
            setTextColor(0xFF707070.toInt())
            setPadding((getResources().displayMetrics.density * 48).toInt(), 0, 0, 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(row)
    }

    private fun addSectionHeader(column: LinearLayout, title: String) {
        column.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setPadding(0, 12, 0, 2)
        })
    }

    private fun resetAccessoryId() {
        val id = ByteArray(6).also(secureRandom::nextBytes)
        commandPrefs.edit()
            .putString("device_id", Base64.encodeToString(id, Base64.NO_WRAP))
            .remove("controller_id")
            .remove("controller_key")
            .apply()
        pairSetupSessions.clear()
        pairVerifySessions.clear()
        updateConnectionStatus()
        if (::accessoryInfo.isInitialized) {
            accessoryInfo.text = "配件 ID：${accessoryPairingId()}\nRoot：${if (rootAvailable()) "可用" else "不可用"}"
        }
        log("ACCESSORY_ID_RESET id=${id.joinToString(":") { "%02X".format(it) }} pairing_cleared=true")
        if (advertising) { stopProbe(); ensurePermissionsAndStart() }
    }

    /** Clears a failed / old controller pairing without changing the accessory identity. */
    private fun clearPairingState() {
        commandPrefs.edit()
            .remove("controller_id")
            .remove("controller_key")
            .apply()
        pairSetupSessions.clear()
        pairVerifySessions.clear()
        updateConnectionStatus()
        log("PAIRING_STATE_CLEARED accessory_id_preserved=true")
        if (advertising) { stopProbe(); ensurePermissionsAndStart() }
    }

    private fun rootAvailable(): Boolean = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val finished = process.waitFor(500, TimeUnit.MILLISECONDS)
        process.inputStream.close(); process.errorStream.close()
        if (!finished) process.destroyForcibly()
        finished && process.exitValue() == 0
    } catch (_: Throwable) { false }

    private fun updateConnectionStatus() {
        if (!::statusInfo.isInitialized) return
        val paired = commandPrefs.contains("controller_key")
        val connection = when {
            connectedDevices.isNotEmpty() -> "已连接（${connectedDevices.size}）"
            advertising -> "等待 HomeKit 连接"
            else -> "未连接"
        }
        runOnUiThread {
            statusInfo.text = "配对状态：${if (paired) "已配对" else "未配对"}\n连接状态：$connection"
        }
    }

    private fun stateButtonLabel(command: HomeKitCommand, state: Boolean?): String {
        val name = when (command) {
            HomeKitCommand.SCREEN -> "屏幕电源"
            HomeKitCommand.HOTSPOT -> "热点"
            HomeKitCommand.MUTE -> "媒体静音"
            HomeKitCommand.FLASHLIGHT -> "手电筒"
            HomeKitCommand.BRIGHTNESS -> "屏幕亮度"
        }
        val suffix = when (state) {
            null -> "读取中…"
            true -> if (command == HomeKitCommand.SCREEN) "亮屏" else if (command == HomeKitCommand.MUTE) "已静音" else "开启"
            false -> if (command == HomeKitCommand.SCREEN) "熄屏" else if (command == HomeKitCommand.MUTE) "未静音" else "关闭"
        }
        return "$name：$suffix"
    }

    /** Runs on a background thread: every state read spawns root shells on MIUI. */
    private fun refreshDeviceState() {
        if (!refreshingState.compareAndSet(false, true)) return
        Thread {
            try {
                val states = listOf(
                    HomeKitCommand.SCREEN, HomeKitCommand.HOTSPOT,
                    HomeKitCommand.MUTE, HomeKitCommand.FLASHLIGHT
                ).associateWith { commandExecutor.currentValue(it) }
                val brightness = commandExecutor.currentValueInt(HomeKitCommand.BRIGHTNESS) ?: 0
                runOnUiThread {
                    states.forEach { (command, state) ->
                        stateButtons[command]?.let { it.text = stateButtonLabel(command, state) }
                    }
                    // Don't fight the user's thumb while the SeekBar is dragged.
                    brightnessSeek?.takeIf { !it.isPressed }?.progress = brightness
                }
            } catch (_: Throwable) {
            } finally {
                refreshingState.set(false)
            }
        }.start()
    }

    /** In-app toggle: same executor path as HomeKit writes, then resync the UI. */
    private fun runCommandFromUi(command: HomeKitCommand) {
        val button = stateButtons[command] ?: return
        button.isEnabled = false
        Thread {
            val target = !commandExecutor.currentValue(command)
            val result = commandExecutor.execute(command, target)
            log("UI_COMMAND command=$command target=$target success=${result.success} message=${result.message}")
            runOnUiThread {
                button.isEnabled = true
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
            }
            refreshDeviceState()
        }.start()
    }

    private fun setBrightnessFromUi(value: Int) {
        Thread {
            val result = commandExecutor.executeValue(HomeKitCommand.BRIGHTNESS, value)
            log("UI_BRIGHTNESS value=$value success=${result.success} message=${result.message}")
        }.start()
    }

    private fun formatSetupCode44(digits: String): String {
        val clean = digits.filter(Char::isDigit).padEnd(8, '0').take(8)
        return "${clean.substring(0, 4)}-${clean.substring(4, 8)}"
    }

    private fun isValidSetupCodeDigits(digits: String): Boolean {
        if (digits.length != 8 || digits.all { it == digits.first() }) return false
        val values = digits.map { it - '0' }
        val ascending = values.zipWithNext().all { (a, b) -> b == a + 1 }
        val descending = values.zipWithNext().all { (a, b) -> b == a - 1 }
        return !ascending && !descending
    }

    /** HomeKit SRP always uses the canonical 3-2-3 textual setup-code form. */
    private fun setupCodeForSrp(): String {
        val digits = commandPrefs.getString("setup_code_digits", DEFAULT_SETUP_CODE_DIGITS)
            ?.filter(Char::isDigit)?.takeIf { it.length == 8 } ?: DEFAULT_SETUP_CODE_DIGITS
        return "${digits.substring(0, 3)}-${digits.substring(3, 5)}-${digits.substring(5, 8)}"
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
        requestNotificationPermissionIfNeeded()
        startProbe()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            ensurePermissionsAndStart()
        } else if (requestCode == REQUEST_CAMERA) {
            requestNotificationPermissionIfNeeded()
            startProbe()
        } else if (requestCode == REQUEST_BLUETOOTH) {
            log("PERMISSION_DENIED")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startProbe() {
        if (advertising || starting) {
            log("PROBE_ALREADY_RUNNING")
            return
        }
        starting = true
        val adapter = manager.adapter
        if (adapter == null) {
            log("BLE_ERROR adapter=null")
            starting = false
            return
        }
        log("BLE_CAPABILITY enabled=${adapter.isEnabled} multiAdv=${adapter.isMultipleAdvertisementSupported} " +
            "2mPhy=${adapter.isLe2MPhySupported} codedPhy=${adapter.isLeCodedPhySupported}")
        if (!adapter.isEnabled) {
            log("BLE_ERROR bluetooth_disabled")
            starting = false
            return
        }
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            log("BLE_ERROR advertiser=null")
            starting = false
            return
        }

        // Open the GATT server off the main thread. Immediately after `install -r`
        // restarts the process, MIUI's Bluetooth stack can still be tearing down the
        // previous instance's server; on the main thread openGattServer() can then
        // block for several seconds and still return null. A background retry loop
        // keeps the Activity responsive and recovers once the stack is ready.
        Thread({
            openGattServerWithRetry()
        }, "MiHotspotHap-GattOpen").apply { isDaemon = true; start() }
    }

    private fun openGattServerWithRetry() {
        for (attempt in 1..GATT_OPEN_MAX_ATTEMPTS) {
            server = manager.openGattServer(this, gattCallback)
            if (server != null) {
                log("GATT_SERVER_OPENED attempt=$attempt")
                queueGattDatabase()
                addNextService()
                return
            }
            log("BLE_RETRY gatt_server=null attempt=$attempt")
            try {
                Thread.sleep(GATT_OPEN_RETRY_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        log("BLE_ERROR gatt_server=null attempts=$GATT_OPEN_MAX_ATTEMPTS")
        starting = false
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingAfterGattPublished() {
        val adapter = manager.adapter ?: run { starting = false; return }
        val advertiser = adapter.bluetoothLeAdvertiser ?: run { starting = false; return }
        // HAP regular advertisement, without optional Setup Hash. The SF bit must
        // agree with persistent pairing state or iOS will not resume Pair Verify.
        // Android addManufacturerData supplies Company ID and AD framing.
        val isPaired = getSharedPreferences("homekit", MODE_PRIVATE).contains("controller_key")
        val accessoryId = accessoryIdBytes()
        val hapData = byteArrayOf(
            0x06, 0x2D, (if (isPaired) 0x00 else 0x01).toByte(), // TY, STL, SF
            *accessoryId,
            0x08, 0x00,                               // ACID: Switches
            0x05, 0x00,                               // GSN: semantic service layout v5
            0x07,                                     // CN: force controllers to refresh cached metadata
            0x02                                      // CV
        )
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .addManufacturerData(APPLE_COMPANY_ID, hapData)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        try {
            adapter.name = commandPrefs.getString("device_name", "Mi Hotspot") ?: "Mi Hotspot"
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
            log("BLE_ADVERTISING_REQUESTED paired=$isPaired payload=${hapData.hex()}")
        } catch (t: Throwable) {
            log("BLE_ADVERTISING_EXCEPTION ${t.stackTraceToString()}")
            stopProbe()
        }
    }

    private fun accessoryIdBytes(): ByteArray {
        commandPrefs.getString("device_id", null)?.let { encoded ->
            try {
                Base64.decode(encoded, Base64.NO_WRAP).takeIf { it.size == 6 }?.let { return it }
            } catch (_: IllegalArgumentException) { }
        }
        val id = byteArrayOf(0x02, 0x13, 0x37, 0x42, 0x51, 0x6D)
        commandPrefs.edit().putString("device_id", Base64.encodeToString(id, Base64.NO_WRAP)).apply()
        return id
    }

    private fun accessoryPairingId(): String = accessoryIdBytes().joinToString(":") { "%02X".format(it) }

    @SuppressLint("MissingPermission")
    private fun stopProbe() {
        log("PROBE_STOPPED_TRACE ${Throwable().stackTraceToString().lines().drop(1).take(5).joinToString(" <- ")}")
        manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        server?.close()
        server = null
        pendingServices.clear()
        pduResponses.clear()
        pduRequests.clear()
        peerMtu.clear()
        gattCharacteristicDefinitions.clear()
        pairSetupSessions.clear()
        pairVerifySessions.clear()
        connectedDevices.clear()
        advertising = false
        starting = false
        log("PROBE_STOPPED")
        updateConnectionStatus()
    }

    @SuppressLint("MissingPermission")
    private fun queueGattDatabase() {
        pendingServices.clear()
        pendingServices.addAll(HAP_SERVICES.map(::service))
    }

    @SuppressLint("MissingPermission")
    private fun addNextService() {
        val next = pendingServices.pollFirst()
        if (next == null) {
            log("GATT_DATABASE_PUBLISHED services=${HAP_SERVICES.size}")
            startAdvertisingAfterGattPublished()
            return
        }
        val accepted = server?.addService(next) == true
        log("GATT_SERVICE_ADD uuid=${next.uuid} accepted=$accepted")
        if (!accepted) {
            log("GATT_DATABASE_FAILED uuid=${next.uuid}")
            stopProbe()
        }
    }

    private fun service(definition: HapService): BluetoothGattService {
        return BluetoothGattService(hapUuid(definition.type), BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(BluetoothGattCharacteristic(
                SERVICE_IID_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply { value = definition.iid.leBytes() })
            definition.characteristics.forEach { characteristic ->
                val gattCharacteristic = BluetoothGattCharacteristic(
                    hapUuid(characteristic.type),
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
                ).apply {
                    value = initialCharacteristicValue(characteristic)
                    addDescriptor(BluetoothGattDescriptor(IID_DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_READ).apply {
                        value = characteristic.iid.leBytes()
                    })
                }
                gattCharacteristicDefinitions[gattCharacteristic] = characteristic
                addCharacteristic(gattCharacteristic)
            }
        }
    }

    private fun initialCharacteristicValue(characteristic: HapCharacteristic): ByteArray = when (characteristic.type) {
        0x14 -> byteArrayOf(0)
        0x20 -> "Xiaomi".encodeToByteArray()
        0x21 -> "Xiaomi 13".encodeToByteArray()
        0x23 -> when (characteristic.service.type) {
            0x3E -> (commandPrefs.getString("device_name", "Mi Hotspot") ?: "Mi Hotspot").encodeToByteArray()
            0x43 -> when (characteristic.service.iid) {
                0x0070 -> "手机手电筒".encodeToByteArray()
                0x0080 -> "屏幕亮度".encodeToByteArray()
                else -> "屏幕电源".encodeToByteArray()
            }
            0x49 -> if (characteristic.service.iid == 0x0040) "手机热点".encodeToByteArray() else "媒体静音".encodeToByteArray()
            0x96 -> "手机电池".encodeToByteArray()
            else -> "HomeKit".encodeToByteArray()
        }
        0x30 -> accessoryPairingId().encodeToByteArray()
        0x52 -> "1.0".encodeToByteArray()
        0x08 -> byteArrayOf((commandExecutor.currentValueInt(HomeKitCommand.BRIGHTNESS) ?: 0).toByte())
        0x68 -> byteArrayOf(batteryLevel())
        0x79, 0x8F -> byteArrayOf(batteryStatus(characteristic.type))
        0x4F -> byteArrayOf(0)
        0x25 -> byteArrayOf(0)
        0x11A -> byteArrayOf(0)
        else -> byteArrayOf()
    }

    private fun batteryLevel(): Byte {
        val battery = getSystemService(BatteryManager::class.java)
        val capacity = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return capacity.coerceIn(0, 100).toByte()
    }

    private fun batteryStatus(type: Int): Byte {
        val intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        return when (type) {
            0x8F -> if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) 1 else 0
            0x79 -> if (batteryLevel().toInt() <= 20) 1 else 0
            else -> 0
        }.toByte()
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            starting = false
            log("BLE_ADVERTISING_STARTED mode=${settingsInEffect.mode} connectable=${settingsInEffect.isConnectable}")
            updateConnectionStatus()
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            starting = false
            log("BLE_ADVERTISING_FAILED code=$errorCode")
            updateConnectionStatus()
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            log("BLE_CONNECTION address=${device.address} status=$status state=${stateName(newState)}")
            if (newState == BluetoothProfile.STATE_CONNECTED) connectedDevices += device.address
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) connectedDevices -= device.address
            updateConnectionStatus()
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                peerMtu.remove(device.address)
                pduResponses.keys.removeIf { it.startsWith("${device.address}|") }
                pduRequests.keys.removeIf { it.startsWith("${device.address}|") }
                pairSetupSessions.remove(device.address)
                pairVerifySessions.remove(device.address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            peerMtu[device.address] = mtu
            log("BLE_MTU address=${device.address} mtu=$mtu")
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            log("GATT_SERVICE_ADDED status=$status uuid=${service.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS) addNextService() else stopProbe()
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val key = responseKey(device, characteristic)
            val response = pduResponses[key]
            if (response != null && offset == 0) {
                var pdu = response.nextFragment((peerMtu[device.address] ?: DEFAULT_ATT_MTU) - 1)
                val secure = pairVerifySessions[device.address]
                // Pair Verify M4 itself is the final plaintext response. Every
                // later HAP procedure uses the accessory-to-controller key.
                if (secure?.active == true) {
                    pdu = controlEncrypt(secure.readKey, secure.readNonce++, pdu)
                    log("CONTROL_ENCRYPT direction=read plain=${pdu.size - 16}")
                }
                log("HAP_PDU_RESPONSE uuid=${characteristic.uuid} value=${pdu.hex()} final=${response.isFinal}")
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, pdu)
                if (response.isFinal && gattDefinition(characteristic)?.type == PAIR_VERIFY_TYPE) {
                    val verify = pairVerifySessions[device.address]
                    if (verify?.activateAfterResponse == true) {
                        verify.active = true
                        verify.activateAfterResponse = false
                        log("CONTROL_SESSION_ACTIVE")
                    }
                }
                if (response.isFinal) pduResponses.remove(key)
                return
            }
            val value = characteristic.value ?: byteArrayOf()
            log("GATT_READ uuid=${characteristic.uuid} offset=$offset value=${value.hex()}")
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.drop(offset).toByteArray())
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val value = descriptor.value ?: byteArrayOf()
            log("GATT_DESCRIPTOR_READ uuid=${descriptor.uuid} offset=$offset value=${value.hex()}")
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.drop(offset).toByteArray())
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            log("GATT_WRITE uuid=${characteristic.uuid} prepared=$preparedWrite offset=$offset value=${value.hex()}")
            if (!preparedWrite && offset == 0) {
                handleHapPduWrite(device, characteristic, value)
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }

        private fun stateName(state: Int) = when (state) {
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> state.toString()
        }
    }

    private fun handleHapPduWrite(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val key = responseKey(device, characteristic)
        var request = value
        val secure = pairVerifySessions[device.address]
        if (secure?.active == true) {
            request = try {
                log("CONTROL_DECRYPT_START keyBytes=${secure.writeKey.size} nonce=${secure.writeNonce}")
                controlDecrypt(secure.writeKey, secure.writeNonce++, value)
            } catch (t: Exception) {
                log("CONTROL_DECRYPT_FAILED bytes=${value.size} ${t.javaClass.simpleName}:${t.message}")
                return
            }
            log("CONTROL_DECRYPT direction=write plain=${request.size} value=${request.hex()}")
        }
        if (request.isEmpty()) return
        val control = request[0].u8()
        if ((control and 0x80) != 0) {
            if (request.size < 2) return
            val pending = pduRequests[key] ?: return
            if (request[1].u8() != pending.tid) return
            pending.body += request.copyOfRange(2, request.size)
            if (pending.body.size < pending.totalBodyBytes || !isCompletePairingBody(pending.body)) return
            pduRequests.remove(key)
            processHapPdu(device, characteristic, pending.opcode, pending.tid, pending.iid, pending.body)
            return
        }
        // First-fragment HAP request: control, opcode, transaction id, instance id.
        if (request.size < HAP_REQUEST_HEADER_BYTES || control and 0x0E != 0) return
        val opcode = request[1].toInt() and 0xFF
        val tid = request[2].toInt() and 0xFF
        val iid = request[3].u8() or (request[4].u8() shl 8)
        val definition = gattDefinition(characteristic)
        val pairingControl = definition?.type == PAIR_SETUP_TYPE || definition?.type == PAIR_VERIFY_TYPE
        val totalBodyBytes = if (request.size >= 7) request[5].u8() or (request[6].u8() shl 8) else 0
        val body = if (request.size >= 7) request.copyOfRange(7, request.size) else byteArrayOf()
        if (body.size < totalBodyBytes || ((opcode == HAP_OPCODE_CHARACTERISTIC_WRITE || opcode == HAP_OPCODE_CHARACTERISTIC_TIMED_WRITE) && pairingControl && !isCompletePairingBody(body))) {
            // Some controllers split a long Pair Setup TLV before the advertised body
            // length; retain it until a continuation makes the TLV parseable.
            pduRequests[key] = HapRequestAssembly(opcode, tid, iid, maxOf(totalBodyBytes, 0xFFFF), body)
            return
        }
        processHapPdu(device, characteristic, opcode, tid, iid, body)
    }

    private fun isCompletePairingBody(body: ByteArray): Boolean {
        val outer = parseTlvs(body)
        val value = outer[HAP_TLV_VALUE]?.fold(byteArrayOf()) { acc, part -> acc + part } ?: return false
        return parseTlvs(value).isNotEmpty()
    }

    private fun processHapPdu(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, opcode: Int, tid: Int, iid: Int, requestBody: ByteArray) {
        val definition = gattDefinition(characteristic)
        val response = when (opcode) {
            HAP_OPCODE_SERVICE_SIGNATURE_READ -> {
                val service = definition?.service
                if (definition?.type != SERVICE_SIGNATURE_TYPE || service == null) {
                    HapResponse(tid, HAP_STATUS_UNSUPPORTED_PDU)
                } else if (iid != service.iid) {
                    // HAP specifies an empty-properties signature response for an invalid service IID.
                    HapResponse(tid, HAP_STATUS_SUCCESS, byteArrayOf())
                } else {
                    HapResponse(tid, HAP_STATUS_SUCCESS, serviceSignature(service))
                }
            }
            HAP_OPCODE_CHARACTERISTIC_SIGNATURE_READ -> {
                if (definition == null || iid != definition.iid) HapResponse(tid, HAP_STATUS_INVALID_INSTANCE_ID)
                else HapResponse(tid, HAP_STATUS_SUCCESS, characteristicSignature(definition))
            }
            HAP_OPCODE_CHARACTERISTIC_READ -> {
                if (definition == null || iid != definition.iid) HapResponse(tid, HAP_STATUS_INVALID_INSTANCE_ID)
                else if (definition.type == PAIRING_FEATURES_TYPE) {
                    // The ADK's default pairing-feature flags are zero: no hardware-authentication or
                    // transient-pairing feature is advertised by this probe.
                    HapResponse(tid, HAP_STATUS_SUCCESS, tlv(HAP_TLV_VALUE, byteArrayOf(0)))
                } else {
                    commandFor(definition)?.let { command ->
                        if (definition.type == 0x08) {
                            val brightness = commandExecutor.currentValueInt(command) ?: 0
                            log("CONTROL_STATE_READ command=$command value=$brightness service=0x%02X iid=0x%04X".format(definition.service.type, iid))
                            HapResponse(tid, HAP_STATUS_SUCCESS, tlv(HAP_TLV_VALUE, brightness.leBytes32()))
                        } else {
                            val enabled = commandExecutor.currentValue(command)
                            log("CONTROL_STATE_READ command=$command enabled=$enabled service=0x%02X iid=0x%04X".format(definition.service.type, iid))
                            HapResponse(tid, HAP_STATUS_SUCCESS, tlv(HAP_TLV_VALUE, byteArrayOf(if (enabled) 1 else 0)))
                        }
                    } ?: batteryCharacteristicValue(definition)?.let { value ->
                        log("CONTROL_STATE_READ battery type=0x%02X value=${value.u8()} iid=0x%04X".format(definition.type, iid))
                        HapResponse(tid, HAP_STATUS_SUCCESS, tlv(HAP_TLV_VALUE, byteArrayOf(value)))
                    } ?: readableCharacteristicValue(definition)?.let { value ->
                        HapResponse(tid, HAP_STATUS_SUCCESS, tlv(HAP_TLV_VALUE, value))
                    } ?: HapResponse(tid, HAP_STATUS_UNSUPPORTED_PDU)
                }
            }
            HAP_OPCODE_CHARACTERISTIC_WRITE, HAP_OPCODE_CHARACTERISTIC_TIMED_WRITE -> {
                if (definition?.type == PAIR_SETUP_TYPE && iid == definition.iid) {
                    val pdu = byteArrayOf(0, opcode.toByte(), tid.toByte(), iid.toByte(), (iid ushr 8).toByte(), requestBody.size.toByte(), (requestBody.size ushr 8).toByte()) + requestBody
                    pairSetupRequest(device, pdu, tid)
                } else if (definition?.type == PAIR_VERIFY_TYPE && iid == definition.iid) {
                    pairVerifyRequest(device, requestBody, tid)
                } else if (definition?.type == PAIRINGS_TYPE && iid == definition.iid) {
                    pairingsManagementRequest(device, requestBody, tid)
                } else if (definition != null && iid == definition.iid) {
                    val command = commandFor(definition)
                    val rawValue = parseTlvs(requestBody)[HAP_TLV_VALUE]?.fold(byteArrayOf()) { acc, part -> acc + part }
                    // Bool characteristics arrive as a single byte; numeric ones
                    // (e.g. Brightness, declared UInt32) arrive as little-endian
                    // 2/4-byte integers, so decode by length instead of size!=1.
                    val numericValue = rawValue?.let { decodeWriteValue(it) }
                    if (command == null || numericValue == null) {
                        HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
                    } else {
                        val enabled = numericValue != 0
                        log("CONTROL_COMMAND_RECEIVED command=$command value=$numericValue service=0x%02X iid=0x%04X".format(definition.service.type, iid))
                        if (!commandPrefs.getBoolean("command_enabled_${command.name}", true)) {
                            log("COMMAND_BLOCKED command=$command value=$numericValue reason=disabled_by_user")
                            notifyCommand(command, enabled, false, "已在应用中禁用")
                            HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
                        } else {
                            val result = if (definition.type == 0x08) {
                                commandExecutor.executeValue(command, numericValue)
                            } else {
                                commandExecutor.execute(command, enabled)
                            }
                            log("COMMAND_EXECUTE command=$command value=$numericValue success=${result.success} message=${result.message}")
                            notifyCommand(command, enabled, result.success, result.message)
                            refreshDeviceState()
                            HapResponse(tid, if (result.success) HAP_STATUS_SUCCESS else HAP_STATUS_INVALID_REQUEST)
                        }
                    }
                } else HapResponse(tid, HAP_STATUS_UNSUPPORTED_PDU)
            }
            HAP_OPCODE_CHARACTERISTIC_EXECUTE_WRITE -> {
                if (definition?.type == PAIRINGS_TYPE && iid == definition.iid) {
                    // HomeKit uses Timed Write followed by Execute Write for
                    // Pairings management. The management request has already
                    // been validated and acknowledged above; this PDU commits
                    // that transaction and carries no body.
                    log("PAIRINGS_EXECUTE_WRITE_ACK")
                    HapResponse(tid, HAP_STATUS_SUCCESS)
                } else {
                    HapResponse(tid, HAP_STATUS_UNSUPPORTED_PDU)
                }
            }
            else -> {
                log("HAP_PDU_UNIMPLEMENTED opcode=0x%02X tid=$tid iid=0x%04X uuid=${characteristic.uuid}".format(opcode, iid))
                HapResponse(tid, HAP_STATUS_UNSUPPORTED_PDU)
            }
        }
        pduResponses[responseKey(device, characteristic)] = response
        log("HAP_PDU_REQUEST opcode=0x%02X tid=$tid iid=0x%04X responseBody=${response.body.hex()}".format(opcode, iid))
    }

    private fun serviceSignature(service: HapService): ByteArray {
        val properties = when {
            service.primary -> 0x0001
            service.supportsConfiguration -> 0x0004
            else -> 0
        }
        return if (properties == 0) byteArrayOf() else tlv(HAP_TLV_SERVICE_PROPERTIES, properties.leBytes())
    }

    private fun characteristicSignature(characteristic: HapCharacteristic): ByteArray {
        val service = characteristic.service
        val unit = if (characteristic.type == 0x08) byteArrayOf(0xAD.toByte(), 0x27) else byteArrayOf(0, 0x27)
        return tlv(HAP_TLV_CHARACTERISTIC_TYPE, hapPduUuidBytes(characteristic.type)) +
            tlv(HAP_TLV_SERVICE_INSTANCE_ID, service.iid.leBytes()) +
            tlv(HAP_TLV_SERVICE_TYPE, hapPduUuidBytes(service.type)) +
            tlv(HAP_TLV_CHARACTERISTIC_PROPERTIES, characteristic.properties.leBytes()) +
            tlv(HAP_TLV_PRESENTATION_FORMAT, byteArrayOf(characteristic.format, 0, unit[0], unit[1], 1, 0, 0))
    }

    private fun commandFor(characteristic: HapCharacteristic): HomeKitCommand? = when {
        characteristic.service.type == 0x43 && characteristic.type == 0x25 && characteristic.service.iid == 0x0030 -> HomeKitCommand.SCREEN
        characteristic.service.type == 0x43 && characteristic.type == 0x25 && characteristic.service.iid == 0x0070 -> HomeKitCommand.FLASHLIGHT
        characteristic.service.type == 0x43 && characteristic.type == 0x25 && characteristic.service.iid == 0x0080 -> HomeKitCommand.BRIGHTNESS
        characteristic.service.type == 0x43 && characteristic.type == 0x08 && characteristic.service.iid == 0x0080 -> HomeKitCommand.BRIGHTNESS
        characteristic.service.type == 0x49 && characteristic.type == 0x25 && characteristic.service.iid == 0x0040 -> HomeKitCommand.HOTSPOT
        characteristic.service.type == 0x49 && characteristic.type == 0x25 && characteristic.service.iid == 0x0050 -> HomeKitCommand.MUTE
        else -> null
    }

    private fun batteryCharacteristicValue(characteristic: HapCharacteristic): Byte? = when (characteristic.service.type) {
        0x96 -> when (characteristic.type) {
            0x68 -> batteryLevel()
            0x79, 0x8F -> batteryStatus(characteristic.type)
            else -> null
        }
        else -> null
    }

    /** Handles the controller's post-pairing Pairings management transaction. */
    private fun pairingsManagementRequest(device: BluetoothDevice, body: ByteArray, tid: Int): HapResponse {
        // Characteristic writes carry the Pairings TLVs inside the standard
        // HAP-Param-Value (type 0x01) wrapper. The Pair Setup / Verify paths
        // already unwrap this wrapper, so do the same for management writes.
        val outer = parseTlvs(body)
        val pairingBody = outer[HAP_TLV_VALUE]?.fold(byteArrayOf()) { acc, part -> acc + part } ?: body
        val tlvs = parseTlvs(pairingBody)
        val method = tlvs[PAIRING_TLV_METHOD]?.firstOrNull()?.firstOrNull()?.u8()
        val state = tlvs[PAIRING_TLV_STATE]?.firstOrNull()?.firstOrNull()?.u8()
        val identifier = tlvs[PAIRING_TLV_IDENTIFIER]?.fold(byteArrayOf()) { acc, part -> acc + part }
        log("PAIRINGS_REQUEST method=$method state=$state identifier=${identifier?.decodeToString()}")
        if (state != 1) return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        when (method) {
            // Home may clean up a stale copy of the controller pairing directly
            // after Pair Setup. Keep the just-established key for this bridge.
            PAIRING_METHOD_REMOVE -> log("PAIRINGS_REMOVE_ACK preserve_current_pairing=true")
            PAIRING_METHOD_ADD -> {
                val key = tlvs[PAIRING_TLV_PUBLIC_KEY]?.fold(byteArrayOf()) { acc, part -> acc + part }
                if (identifier == null || key == null || key.size != 32) return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
                commandPrefs.edit()
                    .putString("controller_id", Base64.encodeToString(identifier, Base64.NO_WRAP))
                    .putString("controller_key", Base64.encodeToString(key, Base64.NO_WRAP))
                    .apply()
                log("PAIRINGS_ADD_ACK controller=${identifier.decodeToString()}")
            }
            else -> return HapResponse(tid, HAP_STATUS_UNSUPPORTED_PDU)
        }
        // The ADK Pairings write callback returns a successful HAP status; the
        // state TLV is part of the characteristic procedure, not a response
        // PDU body for this write-without-return-response transaction.
        return HapResponse(tid, HAP_STATUS_SUCCESS)
    }

    private fun readableCharacteristicValue(characteristic: HapCharacteristic): ByteArray? = when (characteristic.type) {
        0x14, 0x20, 0x21, 0x23, 0x30, 0x52 -> initialCharacteristicValue(characteristic)
        else -> null
    }

    /** Produces a real SRP-3072 Pair Setup M2 from the setup code shown in the UI. */
    private fun pairSetupRequest(device: BluetoothDevice, pdu: ByteArray, tid: Int): HapResponse {
        val outer = parseTlvs(pdu.copyOfRange(7, pdu.size))
        val inner = outer[HAP_TLV_VALUE]?.fold(byteArrayOf()) { acc, part -> acc + part } ?: return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        val tlvs = parseTlvs(inner)
        val state = tlvs[PAIRING_TLV_STATE]?.firstOrNull()?.firstOrNull()?.u8()
        log("PAIR_SETUP_PARSE body=${pdu.size - 7} outerTypes=${outer.keys} inner=${inner.size} innerTypes=${tlvs.keys} state=$state")
        return when (state) {
            PAIRING_STATE_M1 -> pairSetupM2(device, pdu, tid)
            PAIRING_STATE_M3 -> pairSetupM4(device, tlvs, tid)
            PAIRING_STATE_M5 -> pairSetupM6(device, tlvs, tid)
            else -> HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        }
    }

    private fun pairSetupM4(device: BluetoothDevice, tlvs: Map<Int, List<ByteArray>>, tid: Int): HapResponse {
        val session = pairSetupSessions[device.address] ?: return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        val aBytes = tlvs[PAIRING_TLV_PUBLIC_KEY]?.fold(byteArrayOf()) { acc, part -> acc + part } ?: return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        val proof = tlvs[PAIRING_TLV_PROOF]?.fold(byteArrayOf()) { acc, part -> acc + part } ?: return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        if (aBytes.size != SRP_PUBLIC_KEY_BYTES || proof.size != 64) return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        val a = BigInteger(1, aBytes)
        val u = BigInteger(1, sha512(a.toUnsignedFixed(SRP_PUBLIC_KEY_BYTES) + session.publicB))
        val s = (a * session.verifier.modPow(u, SRP_N)).modPow(session.privateB, SRP_N)
        val aFixed = a.toUnsignedFixed(SRP_PUBLIC_KEY_BYTES)
        val k = sha512(s.toUnsignedFixed(SRP_PUBLIC_KEY_BYTES).withoutLeadingZeros())
        val expected = sha512((sha512(SRP_N_BYTES) xorBytes sha512(byteArrayOf(SRP_GENERATOR.toByte()))) + sha512(SRP_USER.encodeToByteArray()) + session.salt + aFixed.withoutLeadingZeros() + session.publicB.withoutLeadingZeros() + k)
        if (!expected.contentEquals(proof)) return HapResponse(tid, HAP_STATUS_AUTHENTICATION_ERROR)
        session.srpKey = k
        session.sessionKey = hkdfSha512(k, "Pair-Setup-Encrypt-Salt".encodeToByteArray(), "Pair-Setup-Encrypt-Info".encodeToByteArray(), 32)
        val m2Proof = sha512(aFixed + proof + k)
        val m4 = tlv(PAIRING_TLV_STATE, byteArrayOf(PAIRING_STATE_M4.toByte())) + tlv(PAIRING_TLV_PROOF, m2Proof)
        return HapResponse(tid, HAP_STATUS_SUCCESS, tlvFragmented(HAP_TLV_VALUE, m4))
    }

    private fun pairSetupM6(device: BluetoothDevice, tlvs: Map<Int, List<ByteArray>>, tid: Int): HapResponse {
        val session = pairSetupSessions[device.address] ?: return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        val encrypted = tlvs[PAIRING_TLV_ENCRYPTED_DATA]?.fold(byteArrayOf()) { acc, part -> acc + part } ?: return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        if (encrypted.size < 16) return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        val plain = try { chachaDecrypt(session.sessionKey, "PS-Msg05", encrypted) } catch (_: Exception) { return HapResponse(tid, HAP_STATUS_AUTHENTICATION_ERROR) }
        val inner = parseTlvs(plain)
        val controllerId = inner[PAIRING_TLV_IDENTIFIER]?.fold(byteArrayOf()) { acc, part -> acc + part } ?: return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        val controllerKey = inner[PAIRING_TLV_PUBLIC_KEY]?.fold(byteArrayOf()) { acc, part -> acc + part } ?: return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        val controllerSignature = inner[PAIRING_TLV_SIGNATURE]?.fold(byteArrayOf()) { acc, part -> acc + part } ?: return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        if (controllerKey.size != 32 || controllerSignature.size != 64) return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        val controllerX = hkdfSha512(session.srpKey, "Pair-Setup-Controller-Sign-Salt".encodeToByteArray(), "Pair-Setup-Controller-Sign-Info".encodeToByteArray(), 32)
        if (!verifyEd25519(controllerKey, controllerX + controllerId + controllerKey, controllerSignature)) return HapResponse(tid, HAP_STATUS_AUTHENTICATION_ERROR)
        getSharedPreferences("homekit", MODE_PRIVATE).edit()
            .putString("controller_id", Base64.encodeToString(controllerId, Base64.NO_WRAP))
            .putString("controller_key", Base64.encodeToString(controllerKey, Base64.NO_WRAP))
            .apply()
        updateConnectionStatus()

        val accessoryId = accessoryPairingId().encodeToByteArray()
        val accessoryPublicKey = accessoryPrivateKey.generatePublicKey().encoded
        val accessoryX = hkdfSha512(session.srpKey, "Pair-Setup-Accessory-Sign-Salt".encodeToByteArray(), "Pair-Setup-Accessory-Sign-Info".encodeToByteArray(), 32)
        val responseSignature = signEd25519(accessoryX + accessoryId + accessoryPublicKey)
        val responseInner = tlv(PAIRING_TLV_IDENTIFIER, accessoryId) + tlv(PAIRING_TLV_PUBLIC_KEY, accessoryPublicKey) + tlv(PAIRING_TLV_SIGNATURE, responseSignature)
        val encryptedResponse = chachaEncrypt(session.sessionKey, "PS-Msg06", responseInner)
        val m6 = tlv(PAIRING_TLV_STATE, byteArrayOf(PAIRING_STATE_M6.toByte())) + tlvFragmented(PAIRING_TLV_ENCRYPTED_DATA, encryptedResponse)
        log("PAIR_SETUP_M6 controller=${controllerId.decodeToString()} responseBytes=${encryptedResponse.size}")
        return HapResponse(tid, HAP_STATUS_SUCCESS, tlvFragmented(HAP_TLV_VALUE, m6))
    }

    private fun pairVerifyRequest(device: BluetoothDevice, body: ByteArray, tid: Int): HapResponse {
        val outer = parseTlvs(body)
        val inner = outer[HAP_TLV_VALUE]?.fold(byteArrayOf()) { acc, part -> acc + part } ?: return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        val tlvs = parseTlvs(inner)
        return when (tlvs[PAIRING_TLV_STATE]?.firstOrNull()?.firstOrNull()?.u8()) {
            PAIRING_STATE_M1 -> pairVerifyM2(device, tlvs, tid)
            PAIRING_STATE_M3 -> pairVerifyM4(device, tlvs, tid)
            else -> HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        }
    }

    private fun pairVerifyM2(device: BluetoothDevice, tlvs: Map<Int, List<ByteArray>>, tid: Int): HapResponse {
        val controllerPublic = tlvs[PAIRING_TLV_PUBLIC_KEY]?.fold(byteArrayOf()) { acc, part -> acc + part } ?: return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        if (controllerPublic.size != 32) return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        val privateKey = X25519PrivateKeyParameters(secureRandom)
        val accessoryPublic = privateKey.generatePublicKey().encoded
        val sharedSecret = ByteArray(32).also { privateKey.generateSecret(X25519PublicKeyParameters(controllerPublic, 0), it, 0) }
        val sessionKey = hkdfSha512(sharedSecret, "Pair-Verify-Encrypt-Salt".encodeToByteArray(), "Pair-Verify-Encrypt-Info".encodeToByteArray(), 32)
        pairVerifySessions[device.address] = PairVerifySession(controllerPublic, accessoryPublic, sharedSecret, sessionKey)
        val accessoryId = accessoryPairingId().encodeToByteArray()
        val signature = signEd25519(accessoryPublic + accessoryId + controllerPublic)
        // Pair Verify M2 encrypts both the accessory pairing identifier and its
        // signature; the controller needs the identifier to select the LTPK it
        // received during Pair Setup.
        val encrypted = chachaEncrypt(
            sessionKey,
            "PV-Msg02",
            tlv(PAIRING_TLV_IDENTIFIER, accessoryId) + tlv(PAIRING_TLV_SIGNATURE, signature)
        )
        val m2 = tlv(PAIRING_TLV_STATE, byteArrayOf(PAIRING_STATE_M2.toByte())) + tlv(PAIRING_TLV_PUBLIC_KEY, accessoryPublic) + tlv(PAIRING_TLV_ENCRYPTED_DATA, encrypted)
        log("PAIR_VERIFY_M2")
        return HapResponse(tid, HAP_STATUS_SUCCESS, tlvFragmented(HAP_TLV_VALUE, m2))
    }

    private fun pairVerifyM4(device: BluetoothDevice, tlvs: Map<Int, List<ByteArray>>, tid: Int): HapResponse {
        val session = pairVerifySessions[device.address] ?: return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        val encrypted = tlvs[PAIRING_TLV_ENCRYPTED_DATA]?.fold(byteArrayOf()) { acc, part -> acc + part } ?: return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        val plain = try { chachaDecrypt(session.sessionKey, "PV-Msg03", encrypted) } catch (_: Exception) { return HapResponse(tid, HAP_STATUS_AUTHENTICATION_ERROR) }
        val inner = parseTlvs(plain)
        val controllerId = inner[PAIRING_TLV_IDENTIFIER]?.fold(byteArrayOf()) { acc, part -> acc + part } ?: return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        val signature = inner[PAIRING_TLV_SIGNATURE]?.fold(byteArrayOf()) { acc, part -> acc + part } ?: return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        val preferences = getSharedPreferences("homekit", MODE_PRIVATE)
        val savedId = preferences.getString("controller_id", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return HapResponse(tid, HAP_STATUS_AUTHENTICATION_ERROR)
        val savedKey = preferences.getString("controller_key", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return HapResponse(tid, HAP_STATUS_AUTHENTICATION_ERROR)
        if (!savedId.contentEquals(controllerId) || !verifyEd25519(savedKey, session.controllerPublic + controllerId + session.accessoryPublic, signature)) return HapResponse(tid, HAP_STATUS_AUTHENTICATION_ERROR)
        session.readKey = hkdfSha512(session.sharedSecret, "Control-Salt".encodeToByteArray(), "Control-Read-Encryption-Key".encodeToByteArray(), 32)
        session.writeKey = hkdfSha512(session.sharedSecret, "Control-Salt".encodeToByteArray(), "Control-Write-Encryption-Key".encodeToByteArray(), 32)
        session.activateAfterResponse = true
        log("PAIR_VERIFY_M4 controller=${controllerId.decodeToString()} readKey=${session.readKey.size} writeKey=${session.writeKey.size}")
        return HapResponse(tid, HAP_STATUS_SUCCESS, tlvFragmented(HAP_TLV_VALUE, tlv(PAIRING_TLV_STATE, byteArrayOf(PAIRING_STATE_M4.toByte()))))
    }

    private fun pairSetupM2(device: BluetoothDevice, pdu: ByteArray, tid: Int): HapResponse {
        val bodyLength = pdu[5].u8() or (pdu[6].u8() shl 8)
        if (pdu.size != 7 + bodyLength) return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        val outer = parseTlvs(pdu.copyOfRange(7, pdu.size))
        val pairingRequest = outer[HAP_TLV_VALUE]?.firstOrNull() ?: return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        val pairingTlvs = parseTlvs(pairingRequest)
        val method = pairingTlvs[PAIRING_TLV_METHOD]?.firstOrNull()?.firstOrNull()?.u8()
        val state = pairingTlvs[PAIRING_TLV_STATE]?.firstOrNull()?.firstOrNull()?.u8()
        if (method != PAIR_SETUP_METHOD || state != PAIRING_STATE_M1) {
            log("PAIR_SETUP_REJECTED method=$method state=$state")
            return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        }

        val salt = ByteArray(SRP_SALT_BYTES).also(secureRandom::nextBytes)
        val x = BigInteger(1, sha512(salt + sha512(("$SRP_USER:${setupCodeForSrp()}").encodeToByteArray())))
        log("PAIR_SETUP_CODE_USED format=3-2-3")
        val verifier = SRP_GENERATOR.modPow(x, SRP_N)
        val privateB = BigInteger(1, ByteArray(SRP_PRIVATE_KEY_BYTES).also(secureRandom::nextBytes))
        val multiplier = BigInteger(1, sha512(SRP_N_BYTES + ByteArray(SRP_PUBLIC_KEY_BYTES - 1) + SRP_GENERATOR.toByteArray()))
        val publicB = (multiplier * verifier + SRP_GENERATOR.modPow(privateB, SRP_N)).mod(SRP_N)
        val publicBBytes = publicB.toUnsignedFixed(SRP_PUBLIC_KEY_BYTES).dropWhile { it == 0.toByte() }.toByteArray()
        pairSetupSessions[device.address] = PairSetupSession(salt, verifier, privateB, publicB.toUnsignedFixed(SRP_PUBLIC_KEY_BYTES))

        val m2 = tlvFragmented(PAIRING_TLV_STATE, byteArrayOf(PAIRING_STATE_M2.toByte())) +
            tlvFragmented(PAIRING_TLV_PUBLIC_KEY, publicBBytes) +
            tlvFragmented(PAIRING_TLV_SALT, salt)
        log("PAIR_SETUP_M2 salt=${salt.hex()} publicKeyBytes=${publicBBytes.size}")
        return HapResponse(tid, HAP_STATUS_SUCCESS, tlvFragmented(HAP_TLV_VALUE, m2))
    }

    private fun tlv(type: Int, body: ByteArray): ByteArray = byteArrayOf(type.toByte(), body.size.toByte()) + body
    private fun tlvFragmented(type: Int, body: ByteArray): ByteArray {
        if (body.isEmpty()) return tlv(type, body)
        return body.asList().chunked(255).fold(byteArrayOf()) { result, chunk ->
            result + tlv(type, chunk.toByteArray())
        }
    }
    private fun parseTlvs(bytes: ByteArray): Map<Int, List<ByteArray>> {
        val result = linkedMapOf<Int, MutableList<ByteArray>>()
        var index = 0
        while (index < bytes.size) {
            if (index + 2 > bytes.size) return emptyMap()
            val type = bytes[index++].u8()
            val length = bytes[index++].u8()
            if (index + length > bytes.size) return emptyMap()
            result.getOrPut(type) { mutableListOf() }.add(bytes.copyOfRange(index, index + length))
            index += length
        }
        return result
    }
    private fun hapPduUuidBytes(shortUuid: Int): ByteArray = byteArrayOf(
        0x91.toByte(), 0x52, 0x76, 0xBB.toByte(), 0x26, 0x00, 0x00, 0x80.toByte(),
        0x00, 0x10, 0x00, 0x00,
        (shortUuid and 0xFF).toByte(), ((shortUuid ushr 8) and 0xFF).toByte(),
        ((shortUuid ushr 16) and 0xFF).toByte(), ((shortUuid ushr 24) and 0xFF).toByte()
    )
    private fun gattDefinition(characteristic: BluetoothGattCharacteristic): HapCharacteristic? {
        gattCharacteristicDefinitions[characteristic]?.let { return it }
        // MIUI can hand the callback a different BluetoothGattCharacteristic
        // instance than the one passed to addService(). A UUID-keyed lookup would
        // collide across the two Lightbulb / two Switch services, so fall back on
        // the globally unique instance ID carried by the IID descriptor.
        val iid = characteristic.descriptors
            .firstOrNull { it.uuid == IID_DESCRIPTOR_UUID }
            ?.value
            ?.takeIf { it.size >= 2 }
            ?.let { it[0].u8() or (it[1].u8() shl 8) }
        val definition = iid?.let { characteristicsByIid[it] }
        if (definition == null) log("GATT_DEFINITION_MISS uuid=${characteristic.uuid} iid=$iid")
        return definition
    }
    private fun responseKey(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic) =
        "${device.address}|${characteristic.service?.uuid}|${characteristic.uuid}"

    private fun log(message: String) {
        val line = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())} $message"
        android.util.Log.i(TAG, line)
    }

    private fun Int.leBytes() = byteArrayOf((this and 0xFF).toByte(), ((this ushr 8) and 0xFF).toByte())
    private fun Int.leBytes32() = byteArrayOf(
        (this and 0xFF).toByte(), ((this ushr 8) and 0xFF).toByte(),
        ((this ushr 16) and 0xFF).toByte(), ((this ushr 24) and 0xFF).toByte()
    )
    private fun Byte.u8() = toInt() and 0xFF

    /** Decodes a HAP BLE characteristic write value by its length: bool is one
     * byte, UInt16/UInt32 are little-endian, and a 4-byte value above 100 is
     * reinterpreted as float32 in case a controller honours the HAP float form. */
    private fun decodeWriteValue(raw: ByteArray): Int? = when (raw.size) {
        1 -> raw[0].u8()
        2 -> raw[0].u8() or (raw[1].u8() shl 8)
        4 -> {
            val le = raw[0].u8() or (raw[1].u8() shl 8) or (raw[2].u8() shl 16) or (raw[3].u8() shl 24)
            if (le in 0..100) le else Float.fromBits(le).takeIf { it.isFinite() }?.toInt()
        }
        else -> null
    }
    private fun BigInteger.toUnsignedFixed(size: Int): ByteArray {
        val raw = toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.drop(1).toByteArray() else it }
        require(raw.size <= size)
        return ByteArray(size - raw.size) + raw
    }
    private fun sha512(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(bytes)
    private infix fun ByteArray.xorBytes(other: ByteArray): ByteArray = ByteArray(size) { (this[it].toInt() xor other[it].toInt()).toByte() }
    private fun ByteArray.withoutLeadingZeros(): ByteArray {
        val trimmed = dropWhile { it == 0.toByte() }.toByteArray()
        return if (trimmed.isEmpty()) byteArrayOf(0) else trimmed
    }
    private fun hkdfSha512(key: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray {
        val extract = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(salt, "HmacSHA512")) }.doFinal(key)
        return Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(extract, "HmacSHA512")); update(info); update(1) }.doFinal().copyOf(size)
    }
    private fun chachaEncrypt(key: ByteArray, nonceText: String, plain: ByteArray): ByteArray = Cipher.getInstance("ChaCha20-Poly1305").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(ByteArray(4) + nonceText.encodeToByteArray()))
    }.doFinal(plain)
    private fun chachaDecrypt(key: ByteArray, nonceText: String, encrypted: ByteArray): ByteArray = Cipher.getInstance("ChaCha20-Poly1305").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(ByteArray(4) + nonceText.encodeToByteArray()))
    }.doFinal(encrypted)
    private fun controlEncrypt(key: ByteArray, counter: Long, plain: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, ByteArray(4) + counter.le64()))
        val out = ByteArray(cipher.getOutputSize(plain.size))
        val n = cipher.processBytes(plain, 0, plain.size, out, 0)
        cipher.doFinal(out, n)
        return out
    }
    private fun controlDecrypt(key: ByteArray, counter: Long, encrypted: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, ByteArray(4) + counter.le64()))
        val out = ByteArray(cipher.getOutputSize(encrypted.size))
        val n = cipher.processBytes(encrypted, 0, encrypted.size, out, 0)
        val final = cipher.doFinal(out, n)
        return out.copyOf(n + final)
    }
    private fun Long.le64() = ByteArray(8) { ((this ushr (it * 8)) and 0xFF).toByte() }
    private fun signEd25519(message: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, accessoryPrivateKey); update(message, 0, message.size); generateSignature()
    }
    private fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = Ed25519Signer().run {
        init(false, Ed25519PublicKeyParameters(publicKey, 0)); update(message, 0, message.size); verifySignature(signature)
    }
    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

    companion object {
        private const val TAG = "MiHotspotHap"
        private const val REQUEST_BLUETOOTH = 100
        private const val REQUEST_NOTIFICATIONS = 101
        private const val REQUEST_CAMERA = 102
        private const val NOTIFICATION_CHANNEL_ID = "homekit_commands"
        private const val NOTIFICATION_BASE_ID = 7300
        private const val APPLE_COMPANY_ID = 0x004C
        private const val DEFAULT_ATT_MTU = 23
        private const val GATT_OPEN_MAX_ATTEMPTS = 5
        private const val GATT_OPEN_RETRY_DELAY_MS = 1500L
        private const val HAP_REQUEST_HEADER_BYTES = 5
        private const val HAP_OPCODE_CHARACTERISTIC_SIGNATURE_READ = 0x01
        private const val HAP_OPCODE_CHARACTERISTIC_READ = 0x03
        private const val HAP_OPCODE_CHARACTERISTIC_WRITE = 0x02
        private const val HAP_OPCODE_CHARACTERISTIC_TIMED_WRITE = 0x04
        private const val HAP_OPCODE_CHARACTERISTIC_EXECUTE_WRITE = 0x05
        private const val HAP_OPCODE_SERVICE_SIGNATURE_READ = 0x06
        private const val HAP_STATUS_SUCCESS = 0x00
        private const val HAP_STATUS_UNSUPPORTED_PDU = 0x01
        private const val HAP_STATUS_INVALID_INSTANCE_ID = 0x04
        private const val HAP_STATUS_INVALID_REQUEST = 0x06
        private const val HAP_STATUS_AUTHENTICATION_ERROR = 0x02
        private const val HAP_TLV_CHARACTERISTIC_TYPE = 0x04
        private const val HAP_TLV_VALUE = 0x01
        private const val HAP_TLV_SERVICE_TYPE = 0x06
        private const val HAP_TLV_SERVICE_INSTANCE_ID = 0x07
        private const val HAP_TLV_CHARACTERISTIC_PROPERTIES = 0x0A
        private const val HAP_TLV_PRESENTATION_FORMAT = 0x0C
        private const val HAP_TLV_SERVICE_PROPERTIES = 0x0F
        private const val SERVICE_SIGNATURE_TYPE = 0xA5
        private const val PAIRING_FEATURES_TYPE = 0x4F
        private const val PAIR_SETUP_TYPE = 0x4C
        private const val PAIR_VERIFY_TYPE = 0x4E
        private const val PAIRINGS_TYPE = 0x50
        private const val PAIR_SETUP_METHOD = 0x00
        private const val PAIRING_METHOD_ADD = 0x03
        private const val PAIRING_METHOD_REMOVE = 0x04
        private const val PAIRING_STATE_M1 = 0x01
        private const val PAIRING_STATE_M2 = 0x02
        private const val PAIRING_STATE_M3 = 0x03
        private const val PAIRING_STATE_M4 = 0x04
        private const val PAIRING_STATE_M5 = 0x05
        private const val PAIRING_STATE_M6 = 0x06
        private const val PAIRING_TLV_METHOD = 0x00
        private const val PAIRING_TLV_SALT = 0x02
        private const val PAIRING_TLV_PUBLIC_KEY = 0x03
        private const val PAIRING_TLV_PROOF = 0x04
        private const val PAIRING_TLV_ENCRYPTED_DATA = 0x05
        private const val PAIRING_TLV_IDENTIFIER = 0x01
        private const val PAIRING_TLV_SIGNATURE = 0x0A
        private const val PAIRING_TLV_STATE = 0x06
        private const val SRP_SALT_BYTES = 16
        private const val SRP_PRIVATE_KEY_BYTES = 32
        private const val SRP_PUBLIC_KEY_BYTES = 384
        private const val SRP_USER = "Pair-Setup"
        // HomeKit disallows repeated and monotonically ascending / descending codes.
        private const val DEFAULT_SETUP_CODE_DIGITS = "47382915"
        private val ED25519_X509_PREFIX = byteArrayOf(0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00)
        private val SERVICE_IID_UUID = UUID.fromString("E604E95D-A759-4817-87D3-AA005083A0D1")
        private val IID_DESCRIPTOR_UUID = UUID.fromString("DC46F0FE-81D2-4616-B5D9-6ABDD796939A")

        private class HapService(
            val type: Int,
            val iid: Int,
            val primary: Boolean = false,
            val supportsConfiguration: Boolean = false,
            characteristics: List<HapCharacteristic>
        ) {
            val characteristics = characteristics.onEach { it.service = this }
        }

        private class HapCharacteristic(val type: Int, val iid: Int, val properties: Int, val format: Byte) {
            lateinit var service: HapService
        }

        private data class HapRequestAssembly(
            val opcode: Int,
            val tid: Int,
            val iid: Int,
            val totalBodyBytes: Int,
            var body: ByteArray
        )
        private data class PairSetupSession(
            val salt: ByteArray,
            val verifier: BigInteger,
            val privateB: BigInteger,
            val publicB: ByteArray,
            var srpKey: ByteArray = byteArrayOf(),
            var sessionKey: ByteArray = byteArrayOf()
        )
        private data class PairVerifySession(
            val controllerPublic: ByteArray,
            val accessoryPublic: ByteArray,
            val sharedSecret: ByteArray,
            val sessionKey: ByteArray,
            var readKey: ByteArray = byteArrayOf(),
            var writeKey: ByteArray = byteArrayOf(),
            var readNonce: Long = 0,
            var writeNonce: Long = 0,
            var active: Boolean = false,
            var activateAfterResponse: Boolean = false
        )

        private class HapResponse(val tid: Int, val status: Int, val body: ByteArray = byteArrayOf()) {
            private var offset = 0
            private var first = true
            val isFinal get() = !first && offset == body.size

            fun nextFragment(maxBytes: Int): ByteArray {
                val header = if (first && body.isNotEmpty()) 5 else if (first) 3 else 2
                val count = minOf(body.size - offset, maxBytes - header)
                require(count >= 0) { "ATT MTU too small for HAP response" }
                val fragment = if (first) {
                    if (body.isEmpty()) byteArrayOf(0x02, tid.toByte(), status.toByte())
                    else byteArrayOf(0x02, tid.toByte(), status.toByte(), body.size.toByte(), (body.size ushr 8).toByte()) +
                        body.copyOfRange(offset, offset + count)
                } else {
                    byteArrayOf(0x82.toByte(), tid.toByte()) + body.copyOfRange(offset, offset + count)
                }
                offset += count
                first = false
                return fragment
            }
        }

        private val HAP_SERVICES: List<HapService> = listOf(
            HapService(0x3E, 0x0001, characteristics = listOf(
                HapCharacteristic(0x14, 0x0002, 0x0020, 0x01),
                HapCharacteristic(0x20, 0x0003, 0x0010, 0x19),
                HapCharacteristic(0x21, 0x0004, 0x0010, 0x19),
                HapCharacteristic(0x23, 0x0005, 0x0010, 0x19),
                HapCharacteristic(0x30, 0x0006, 0x0010, 0x19),
                HapCharacteristic(0x52, 0x0007, 0x0010, 0x19)
            )),
            HapService(0xA2, 0x0010, supportsConfiguration = true, characteristics = listOf(
                HapCharacteristic(0xA5, 0x0011, 0x0010, 0x1B),
                HapCharacteristic(0x37, 0x0012, 0x0010, 0x19)
            )),
            HapService(0x55, 0x0020, characteristics = listOf(
                // Pair Setup / Pair Verify are the two HAP control points that permit
                // unauthenticated BLE transactions. These bits are what lets Home
                // advance from discovery to Pair Setup M1.
                HapCharacteristic(0x4C, 0x0022, 0x0003, 0x1B),
                HapCharacteristic(0x4E, 0x0023, 0x0003, 0x1B),
                HapCharacteristic(0x4F, 0x0024, 0x0001, 0x04),
                HapCharacteristic(0x50, 0x0025, 0x0030, 0x1B)
            )),
            // Screen power is a plain on/off light: every toggle is one physical
            // power-key press, with no brightness coupling in this service.
            HapService(0x43, 0x0030, primary = true, characteristics = listOf(
                HapCharacteristic(0xA5, 0x0031, 0x0010, 0x1B),
                HapCharacteristic(0x23, 0x0032, 0x0010, 0x19),
                HapCharacteristic(0x25, 0x0033, 0x00B0, 0x01)
            )),
            // Screen brightness lives in its own Lightbulb so iOS does not couple
            // the brightness slider with the power switch above. Both its On and
            // Brightness characteristics map to the phone's backlight level.
            HapService(0x43, 0x0080, characteristics = listOf(
                HapCharacteristic(0xA5, 0x0081, 0x0010, 0x1B),
                HapCharacteristic(0x23, 0x0082, 0x0010, 0x19),
                HapCharacteristic(0x25, 0x0083, 0x00B0, 0x01),
                HapCharacteristic(0x08, 0x0084, 0x00B0, 0x04)
            )),
            // A second Lightbulb service gives the phone flashlight its own
            // visible Home tile while keeping the screen power button separate.
            HapService(0x43, 0x0070, characteristics = listOf(
                HapCharacteristic(0xA5, 0x0071, 0x0010, 0x1B),
                HapCharacteristic(0x23, 0x0072, 0x0010, 0x19),
                HapCharacteristic(0x25, 0x0073, 0x00B0, 0x01)
            )),
            // Hotspot is a generic on/off capability, so use HomeKit Switch
            // rather than an unrelated outlet or light icon.
            HapService(0x49, 0x0040, characteristics = listOf(
                HapCharacteristic(0xA5, 0x0041, 0x0010, 0x1B),
                HapCharacteristic(0x23, 0x0042, 0x0010, 0x19),
                HapCharacteristic(0x25, 0x0043, 0x00B0, 0x01)
            )),
            // Home exposes a generic Switch reliably, while a standalone
            // Speaker service is treated as a media endpoint and hidden by
            // some Home versions. Keep the semantic label and mute behavior,
            // but use the visible Switch service for a Siri/Home toggle.
            HapService(0x49, 0x0050, characteristics = listOf(
                HapCharacteristic(0xA5, 0x0051, 0x0010, 0x1B),
                HapCharacteristic(0x23, 0x0052, 0x0010, 0x19),
                HapCharacteristic(0x25, 0x0053, 0x00B0, 0x01)
            )),
            // Standard Battery Service. HomeKit reads these values through the
            // encrypted HAP channel so the tile can show the phone's battery.
            HapService(0x96, 0x0060, characteristics = listOf(
                HapCharacteristic(0xA5, 0x0061, 0x0010, 0x1B),
                HapCharacteristic(0x23, 0x0062, 0x0010, 0x19),
                HapCharacteristic(0x68, 0x0063, 0x0010, 0x04),
                HapCharacteristic(0x79, 0x0064, 0x0010, 0x04),
                HapCharacteristic(0x8F, 0x0065, 0x0010, 0x04)
            ))
        )

        private val characteristicsByIid: Map<Int, HapCharacteristic> =
            HAP_SERVICES.flatMap { it.characteristics }.associateBy { it.iid }

        private fun hapUuid(shortUuid: Int): UUID =
            UUID.fromString("%08X-0000-1000-8000-0026BB765291".format(shortUuid))

        private val secureRandom = SecureRandom()
        private val SRP_N_BYTES = "ffffffffffffffffc90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74020bbea63b139b22514a08798e3404ddef9519b3cd3a431b302b0a6df25f14374fe1356d6d51c245e485b576625e7ec6f44c42e9a637ed6b0bff5cb6f406b7edee386bfb5a899fa5ae9f24117c4b1fe649286651ece45b3dc2007cb8a163bf0598da48361c55d39a69163fa8fd24cf5f83655d23dca3ad961c62f356208552bb9ed529077096966d670c354e4abc9804f1746c08ca18217c32905e462e36ce3be39e772c180e86039b2783a2ec07a28fb5c55df06f4c52c9de2bcbf6955817183995497cea956ae515d2261898fa051015728e5a8aaac42dad33170d04507a33a85521abdf1cba64ecfb850458dbef0a8aea71575d060c7db3970f85a6e1e4c7abf5ae8cdb0933d71e8c94e04a25619dcee3d2261ad2ee6bf12ffa06d98a0864d87602733ec86a64521f2b18177b200cbbe117577a615d6c770988c0bad946e208e24fa074e5ab3143db5bfce0fd108e4b82d120a93ad2caffffffffffffffff"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private val SRP_N = BigInteger(1, SRP_N_BYTES)
        private val SRP_GENERATOR = BigInteger.valueOf(5)
    }
}
