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
import dev.local.mihotspot.ui.MainViewModel
import dev.local.mihotspot.domain.HomeKitFeature

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
    private val stateButtons = mutableMapOf<HomeKitFeature, Button>()
    private var brightnessSeek: SeekBar? = null
    private val refreshingState = AtomicBoolean(false)
    private val brightnessDebounce = Handler(Looper.getMainLooper())
    private var brightnessPending: Runnable? = null
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
    }

    override fun onDestroy() {
        super.onDestroy()
        // BLE is owned by HomeKitService; closing/recreating the UI must not stop it.
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
            setText(viewModel.deviceName)
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
                    viewModel.saveDeviceName(name)
                    log("DEVICE_NAME_UPDATED name=$name")
                    restartHomeKitService()
                }
            }
        })
        val setupCode = EditText(this).apply {
            hint = "配对码（8位，4-4）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(viewModel.formatSetupCode(viewModel.setupCodeDigits))
            setSingleLine(true)
        }
        column.addView(setupCode)
        column.addView(Button(this).apply {
            text = "保存配对码"
            setOnClickListener {
                val digits = setupCode.text.toString().filter(Char::isDigit)
                if (!viewModel.isValidSetupCode(digits)) {
                    Toast.makeText(this@MainActivity, "请输入8位数字，不能全相同或连续递增/递减", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.saveSetupCode(digits)
                    setupCode.setText(viewModel.formatSetupCode(digits))
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
            text = "配件 ID：${viewModel.accessoryId()}\nRoot：${if (rootAvailable()) "可用" else "不可用"}"
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
        for (feature in HomeKitFeature.tileFeatures) {
            val button = Button(this).apply {
                text = viewModel.buttonLabel(feature, null)
                setOnClickListener { runCommandFromUi(feature) }
            }
            stateButtons[feature] = button
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
        addCommandToggle(column, HomeKitFeature.SCREEN, "屏幕电源键（按钮）", "模拟一次实体电源键，自动切换亮屏/熄屏")
        addCommandToggle(column, HomeKitFeature.HOTSPOT, "手机热点", "Root 优先控制系统 SoftAP；免 Root 仅支持本地热点")
        addSectionHeader(column, "免 Root / 系统授权")
        addCommandToggle(column, HomeKitFeature.MUTE, "媒体静音", "系统媒体静音；若被 MIUI 拦截则使用 Root 音量兜底")
        addCommandToggle(column, HomeKitFeature.FLASHLIGHT, "手机手电筒", "需要相机权限，不需要 Root")
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
            setOnClickListener { stopHomeKitService() }
        })
        return ScrollView(this).apply { addView(column) }
    }

    private fun addCommandToggle(column: LinearLayout, feature: HomeKitFeature, label: String, permission: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6, 0, 8)
        }
        val check = CheckBox(this).apply {
            text = label
            minWidth = 0
            setPadding(0, 0, 0, 0)
            isChecked = viewModel.isFeatureEnabled(feature)
            setOnCheckedChangeListener { _, checked ->
                viewModel.setFeatureEnabled(feature, checked)
                log("COMMAND_TOGGLE feature=$feature enabled=$checked")
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
        val id = viewModel.resetAccessoryId()
        updateConnectionStatus()
        if (::accessoryInfo.isInitialized) {
            accessoryInfo.text = "配件 ID：${viewModel.accessoryId()}\nRoot：${if (rootAvailable()) "可用" else "不可用"}"
        }
        log("ACCESSORY_ID_RESET id=$id pairing_cleared=true")
        restartHomeKitService()
    }

    /** Clears a failed / old controller pairing without changing the accessory identity. */
    private fun clearPairingState() {
        viewModel.clearPairing()
        updateConnectionStatus()
        log("PAIRING_STATE_CLEARED accessory_id_preserved=true")
        restartHomeKitService()
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
        val paired = viewModel.isPaired
        runOnUiThread {
            statusInfo.text = "配对状态：${if (paired) "已配对" else "未配对"}\n连接状态：由 HomeKit 后台服务维护"
        }
    }

    /** Runs on a background thread: every state read spawns root shells on MIUI. */
    private fun refreshDeviceState() {
        if (!refreshingState.compareAndSet(false, true)) return
        Thread {
            try {
                val state = viewModel.loadState()
                runOnUiThread {
                    state.featureStates.forEach { (feature, value) ->
                        stateButtons[feature]?.let { it.text = viewModel.buttonLabel(feature, value) }
                    }
                    // Don't fight the user's thumb while the SeekBar is dragged.
                    brightnessSeek?.takeIf { !it.isPressed }?.progress = state.brightness
                }
            } catch (_: Throwable) {
            } finally {
                refreshingState.set(false)
            }
        }.start()
    }

    /** In-app toggle: same executor path as HomeKit writes, then resync the UI. */
    private fun runCommandFromUi(feature: HomeKitFeature) {
        val button = stateButtons[feature] ?: return
        button.isEnabled = false
        Thread {
            val result = viewModel.toggle(feature)
            log("UI_COMMAND feature=$feature success=${result.success} message=${result.message}")
            runOnUiThread {
                button.isEnabled = true
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
            }
            refreshDeviceState()
        }.start()
    }

    private fun setBrightnessFromUi(value: Int) {
        Thread {
            val result = viewModel.setBrightness(value)
            log("UI_BRIGHTNESS value=$value success=${result.success} message=${result.message}")
        }.start()
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
        val intent = android.content.Intent(this, HomeKitService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (error: Throwable) {
            log("SERVICE_START_FAILED ${error.stackTraceToString()}")
        }
    }

    private fun stopHomeKitService() {
        stopService(android.content.Intent(this, HomeKitService::class.java))
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
        if (requestCode == REQUEST_BLUETOOTH && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            ensurePermissionsAndStart()
        } else if (requestCode == REQUEST_CAMERA) {
            if (requestNotificationPermissionIfNeeded()) startHomeKitService()
        } else if (requestCode == REQUEST_NOTIFICATIONS && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startHomeKitService()
        } else if (requestCode == REQUEST_BLUETOOTH) {
            log("PERMISSION_DENIED")
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
