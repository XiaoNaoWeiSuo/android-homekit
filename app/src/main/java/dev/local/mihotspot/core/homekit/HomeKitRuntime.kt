package dev.local.mihotspot.core.homekit

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
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
import dev.local.mihotspot.ServiceControl
import dev.local.mihotspot.HomeKitUiEvents
import dev.local.mihotspot.BroadcastStrategy
import dev.local.mihotspot.homekit.commands.AndroidCommandExecutor
import dev.local.mihotspot.homekit.commands.HomeKitCommand
import dev.local.mihotspot.homekit.controls.HomeKitControlLifecycle
import dev.local.mihotspot.homekit.controls.HomeKitControlModel
import dev.local.mihotspot.homekit.controls.HomeKitControlModels
import dev.local.mihotspot.homekit.HomeKitSetupPayload
import dev.local.mihotspot.homekit.PairingBroadcastMode
import dev.local.mihotspot.core.homekit.protocol.HapCharacteristicDefinition
import dev.local.mihotspot.core.homekit.protocol.HapReadOnlyState
import dev.local.mihotspot.core.homekit.protocol.HapServiceDefinition
import dev.local.mihotspot.core.homekit.protocol.HapType
import dev.local.mihotspot.core.homekit.protocol.HapValueKind
import dev.local.mihotspot.core.homekit.protocol.HomeKitAccessoryCatalog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Stable BLE, GATT and HAP runtime. Android entry points subclass this type. */
open class HomeKitRuntime : Service() {
    private lateinit var manager: BluetoothManager
    private var server: BluetoothGattServer? = null
    private var advertising = false
    private var activeAdvertiseMode: Int? = null
    @Volatile private var starting = false
    @Volatile private var gattTrafficSinceConnection = false
    private var protocollessDisconnectStreak = 0
    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private val gattCharacteristicDefinitions = IdentityHashMap<BluetoothGattCharacteristic, HapCharacteristicDefinition>()
    private val gattCharacteristicsByIid = ConcurrentHashMap<Int, BluetoothGattCharacteristic>()
    private val pduResponses = ConcurrentHashMap<String, HapResponse>()
    private val pduRequests = ConcurrentHashMap<String, HapRequestAssembly>()
    private val peerMtu = ConcurrentHashMap<String, Int>()
    private val connectedDevices = ConcurrentHashMap.newKeySet<String>()
    private val connectedDeviceRefs = ConcurrentHashMap<String, BluetoothDevice>()
    /** HAP BLE event subscriptions, keyed by the controller address and IID. */
    private val eventSubscriptions = ConcurrentHashMap.newKeySet<String>()
    /** Pending zero-length Handle Value Indications, keyed by address and IID. */
    private val pendingEventNotifications = ConcurrentHashMap.newKeySet<String>()
    /** Android permits only one indication in flight per connection. */
    private val eventInFlight = ConcurrentHashMap<String, String>()
    private val lastObservedControlValues = ConcurrentHashMap<Int, ByteArray>()
    @Volatile private var stateNumberIncrementedForConnection = false
    private val pairSetupSessions = ConcurrentHashMap<String, PairSetupSession>()
    private val pairVerifySessions = ConcurrentHashMap<String, PairVerifySession>()
    private val pendingPairingRemovals = ConcurrentHashMap<String, ByteArray>()
    private val restartAfterPairingsResponse = ConcurrentHashMap.newKeySet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val statePoller = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "MiHotspotHap-StatePoller").apply { isDaemon = true }
    }
    @Volatile private var statePollFuture: ScheduledFuture<*>? = null
    @Volatile private var recoveryPending = false
    private val recoveryRunnable = Runnable {
        recoveryPending = false
        if (ServiceControl.isEnabled(this) && checkBluetoothPermissions()) {
            stopProbe()
            startProbe()
        }
    }
    @Volatile private var bluetoothReceiverRegistered = false
    private val bluetoothStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    log("BLUETOOTH_ON_RECOVERY")
                    mainHandler.postDelayed({
                        if (ServiceControl.isEnabled(this@HomeKitRuntime) && checkBluetoothPermissions()) {
                            startProbe()
                        }
                    }, BLUETOOTH_RECOVERY_DELAY_MS)
                }
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF -> stopProbe()
            }
        }
    }
    private val commandExecutor by lazy { AndroidCommandExecutor(this) }
    /**
     * One lifecycle per product control. HAP characteristics only adapt the
     * wire shape; all real reads and writes go through this registry.
     */
    private val controlLifecycles by lazy {
        HomeKitControlModels.all.associate { model ->
            model.command to HomeKitControlLifecycle(model, commandExecutor)
        }
    }
    private val commandPrefs by lazy { getSharedPreferences("homekit", MODE_PRIVATE) }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val accessoryPrivateKey: Ed25519PrivateKeyParameters by lazy { loadOrCreateAccessoryPrivateKey() }

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(BluetoothManager::class.java)
        if (!isUserUnlocked()) {
            log("SERVICE_WAITING_FOR_USER_UNLOCK")
            stopSelf()
            return
        }
        if (!ServiceControl.isEnabled(this)) {
            log("SERVICE_DISABLED")
            stopSelf()
            return
        }
        registerReceiver(bluetoothStateReceiver, android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        bluetoothReceiverRegistered = true
        publishRuntimeStatus()
        createNotificationChannel()
        startForeground(FOREGROUND_NOTIFICATION_ID, foregroundNotification("驱动常驻 · 正在启动 HomeKit BLE"))
        log("SERVICE_CREATED")
        // HAP disconnected events are driven by real Android state changes,
        // including changes made while Home is not connected. Keep the
        // observer alive for the service lifetime, not only for a GATT link.
        startStatePolling()
        ServiceControl.scheduleWatchdog(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isUserUnlocked()) {
            log("SERVICE_WAITING_FOR_USER_UNLOCK")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!ServiceControl.isEnabled(this)) {
            log("SERVICE_DISABLED")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ServiceControl.scheduleWatchdog(this)
        ServiceControl.scheduleBroadcastModeBoundary(this)
        if (intent?.action == ACTION_STOP) { stopProbe(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ServiceControl.ACTION_WATCHDOG ||
            intent?.action == ServiceControl.ACTION_BROADCAST_MODE_BOUNDARY) {
            reconcileBleState(intent.action)
            return START_STICKY
        }
        if (checkBluetoothPermissions()) startProbe() else log("SERVICE_WAITING_FOR_BLUETOOTH_PERMISSION")
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(recoveryRunnable)
        recoveryPending = false
        stopProbe()
        if (bluetoothReceiverRegistered) {
            try { unregisterReceiver(bluetoothStateReceiver) } catch (_: IllegalArgumentException) { }
            bluetoothReceiverRegistered = false
        }
        statePoller.shutdownNow()
        log("SERVICE_DESTROYED")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isUserUnlocked(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            getSystemService(UserManager::class.java)?.isUserUnlocked != false

    private fun loadOrCreateAccessoryPrivateKey(): Ed25519PrivateKeyParameters {
        val preferences = getSharedPreferences("homekit", MODE_PRIVATE)
        preferences.getString("accessory_seed", null)?.let {
            return Ed25519PrivateKeyParameters(Base64.decode(it, Base64.NO_WRAP), 0)
        }
        return Ed25519PrivateKeyParameters(secureRandom).also { key ->
            preferences.edit().putString("accessory_seed", Base64.encodeToString(key.encoded, Base64.NO_WRAP)).apply()
        }
    }

    private fun setupCodeForSrp(): String {
        val digits = commandPrefs.getString("setup_code_digits", DEFAULT_SETUP_CODE_DIGITS)
            ?.filter(Char::isDigit)?.takeIf { it.length == 8 } ?: DEFAULT_SETUP_CODE_DIGITS
        return "${digits.substring(0, 3)}-${digits.substring(3, 5)}-${digits.substring(5, 8)}"
    }

    private fun checkBluetoothPermissions(): Boolean =
        Build.VERSION.SDK_INT < 31 || (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(NotificationChannel(
                SERVICE_CHANNEL_ID, "HomeKit 后台服务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持 HomeKit BLE 配件在线"; setShowBadge(false) })
            notificationManager.createNotificationChannel(NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "HomeKit 控制", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) })
        }
    }

    private fun foregroundNotification(status: String): Notification {
        val paired = commandPrefs.contains("controller_key")
        val qrMode = pairingBroadcastMode() == PairingBroadcastMode.QR_SETUP_PAYLOAD
        val title = when {
            paired -> "HomeKit 驱动 · 已配对"
            qrMode -> "HomeKit 驱动 · 二维码配对"
            else -> "HomeKit 驱动 · 配对码 ${setupCodeForNotification()}"
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, SERVICE_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        builder.setSmallIcon(dev.local.mihotspot.R.drawable.ic_driver_resident)
            .setContentTitle(title)
            .setContentText(status)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (!paired && qrMode) {
            val qr = createSetupQrBitmap()
            builder.setLargeIcon(qr)
        }
        return builder.build()
    }

    private fun setupCodeForNotification(): String {
        val digits = commandPrefs.getString("setup_code_digits", DEFAULT_SETUP_CODE_DIGITS)
            ?.filter(Char::isDigit)?.takeIf { it.length == 8 } ?: DEFAULT_SETUP_CODE_DIGITS
        return "${digits.substring(0, 4)}-${digits.substring(4, 8)}"
    }

    private fun createSetupQrBitmap(): Bitmap {
        val digits = commandPrefs.getString("setup_code_digits", DEFAULT_SETUP_CODE_DIGITS)
            ?.filter(Char::isDigit)?.takeIf { it.length == 8 } ?: DEFAULT_SETUP_CODE_DIGITS
        val payload = HomeKitSetupPayload.create(digits, setupId())
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 512, 512, hints)
        return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }

    private fun pairingBroadcastMode(): PairingBroadcastMode = runCatching {
        PairingBroadcastMode.valueOf(commandPrefs.getString("pairing_broadcast_mode", null) ?: "QR_SETUP_PAYLOAD")
    }.getOrDefault(PairingBroadcastMode.QR_SETUP_PAYLOAD)

    private fun setupId(): String {
        commandPrefs.getString("setup_id", null)
            ?.takeIf { it.length == 4 && it.all { c -> c in '0'..'9' || c in 'A'..'Z' } }
            ?.let { return it }
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val generated = buildString(4) {
            repeat(4) { append(alphabet[secureRandom.nextInt(alphabet.length)]) }
        }
        commandPrefs.edit().putString("setup_id", generated).commit()
        return generated
    }

    private fun publishRuntimeStatus() {
        val paired = commandPrefs.contains("controller_key")
        val connected = connectedDevices.isNotEmpty()
        commandPrefs.edit()
            .putBoolean("runtime_connected", connected)
            .putBoolean("runtime_advertising", advertising)
            .apply()
        sendBroadcast(Intent(HomeKitUiEvents.ACTION_RUNTIME_STATUS).setPackage(packageName).apply {
            putExtra(HomeKitUiEvents.EXTRA_PAIRED, paired)
            putExtra(HomeKitUiEvents.EXTRA_CONNECTED, connected)
            putExtra(HomeKitUiEvents.EXTRA_ADVERTISING, advertising)
        })
    }

    private fun publishControlState(command: HomeKitCommand) {
        val lifecycle = controlLifecycles[command]
        val isInteger = HomeKitControlModels.byCommand[command]?.valueType == HomeKitControlModel.ValueType.PERCENTAGE
        val integerValue = lifecycle?.currentPercentage()
        sendBroadcast(Intent(HomeKitUiEvents.ACTION_STATE_CHANGED).setPackage(packageName).apply {
            putExtra(HomeKitUiEvents.EXTRA_COMMAND, command.name)
            putExtra(HomeKitUiEvents.EXTRA_IS_INTEGER, isInteger)
            if (isInteger) putExtra(HomeKitUiEvents.EXTRA_INTEGER_VALUE, integerValue)
            else putExtra(HomeKitUiEvents.EXTRA_BOOLEAN_VALUE, lifecycle?.currentBoolean() ?: false)
        })
    }

    private fun updateServiceNotification() {
        val paired = commandPrefs.contains("controller_key")
        val qrMode = pairingBroadcastMode() == PairingBroadcastMode.QR_SETUP_PAYLOAD
        val status = when {
            paired && advertising && connectedDevices.isNotEmpty() -> "已配对 · HomeKit 已连接 · ${connectedDevices.size} 个设备"
            paired && advertising -> "已配对 · HomeKit 广播中"
            paired -> "已配对 · HomeKit 服务待机"
            advertising && connectedDevices.isNotEmpty() -> if (qrMode) "二维码配对 · 已连接 · ${connectedDevices.size} 个设备" else "配对码 ${setupCodeForNotification()} · 已连接"
            advertising -> if (qrMode) "未配对 · 请打开应用查看二维码" else "未配对 · 配对码 ${setupCodeForNotification()}"
            starting -> "正在启动 HomeKit BLE"
            else -> "HomeKit 服务待机"
        }
        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, foregroundNotification(status))
    }

    private fun notifyCommand(command: HomeKitCommand, enabled: Boolean, success: Boolean, detail: String) {
        val label = HomeKitControlModels.byCommand[command]?.title ?: command.name
        val state = if (command == HomeKitCommand.SCREEN) "已按下" else if (enabled) "开启" else "关闭"
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, NOTIFICATION_CHANNEL_ID) else Notification.Builder(this)
        builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("HomeKit 指令")
            .setContentText("$label：$state${if (success) "" else "（失败）"}")
            .setStyle(Notification.BigTextStyle().bigText(detail)).setAutoCancel(true)
        notificationManager.notify(NOTIFICATION_BASE_ID + command.ordinal, builder.build())
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
        scheduleProbeRecovery("gatt_server_unavailable")
    }

    private fun scheduleProbeRecovery(reason: String) {
        if (recoveryPending || !ServiceControl.isEnabled(this)) return
        recoveryPending = true
        log("BLE_RECOVERY_SCHEDULED reason=$reason delayMs=$PROBE_RECOVERY_DELAY_MS")
        mainHandler.postDelayed(recoveryRunnable, PROBE_RECOVERY_DELAY_MS)
    }

    private fun reconcileBleState(reason: String?) {
        mainHandler.post {
            if (!ServiceControl.isEnabled(this) || !checkBluetoothPermissions()) return@post
            val paired = getSharedPreferences("homekit", MODE_PRIVATE).contains("controller_key")
            val desiredMode = desiredAdvertiseMode(this, paired)
            val modeChanged = advertising && activeAdvertiseMode != desiredMode
            log("BLE_HEALTH_CHECK reason=$reason advertising=$advertising server=${server != null} activeMode=$activeAdvertiseMode desiredMode=$desiredMode")
            val safeToRestart = connectedDevices.isEmpty()
            if (safeToRestart && (!advertising || server == null || modeChanged)) {
                stopProbe()
                startProbe()
            } else if (!safeToRestart && modeChanged) {
                log("BLE_HEALTH_CHECK mode_change_deferred connected=${connectedDevices.size}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingAfterGattPublished() {
        val adapter = manager.adapter ?: run { starting = false; return }
        val advertiser = adapter.bluetoothLeAdvertiser ?: run { starting = false; return }
        // HAP regular advertisement. QR mode appends the official Setup Hash so
        // Home can match a scanned setup payload to this BLE accessory.
        // Android addManufacturerData supplies Company ID and AD framing.
        val isPaired = getSharedPreferences("homekit", MODE_PRIVATE).contains("controller_key")
        val accessoryId = accessoryIdBytes()
        val regularData = byteArrayOf(
            0x06, 0x2D, (if (isPaired) 0x00 else 0x01).toByte(), // TY, STL, SF
            *accessoryId,
            0x08, 0x00,                               // ACID: Switches
            *currentStateNumber().leBytes(),           // GSN
            CURRENT_CONFIG_NUMBER.toByte(),           // CN: HAP database configuration number
            0x02                                      // CV
        )
        val setupHash = if (pairingBroadcastMode() == PairingBroadcastMode.QR_SETUP_PAYLOAD) {
            HomeKitSetupPayload.setupHash(setupId(), accessoryId)
        } else {
            byteArrayOf()
        }
        val hapData = regularData.copyOf().also {
            // STL is the HAP payload length after TY. Android adds the
            // manufacturer-data framing outside this byte array.
            it[1] = (0x2D + setupHash.size).toByte()
        } + setupHash
        val advertiseMode = desiredAdvertiseMode(this, isPaired)
        val settings = AdvertiseSettings.Builder()
            // Before pairing, discovery speed matters. After pairing, Home
            // already knows the accessory and balanced advertising saves radio
            // energy while retaining a connectable recovery beacon.
            .setAdvertiseMode(
                advertiseMode
            )
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
            adapter.name = commandPrefs.getString("device_name", "Marionette") ?: "Marionette"
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
            activeAdvertiseMode = advertiseMode
            log("BLE_ADVERTISING_REQUESTED paired=$isPaired advertiseMode=$advertiseMode mode=${pairingBroadcastMode()} payload=${hapData.hex()}")
        } catch (t: Throwable) {
            log("BLE_ADVERTISING_EXCEPTION ${t.stackTraceToString()}")
            stopProbe()
        }
    }

    /**
     * Android's BLE advertiser has no in-place payload update. HAP uses the
     * GSN in the regular advertisement for disconnected events, so restart
     * the advertisement after a state change while no controller is attached.
     */
    @SuppressLint("MissingPermission")
    private fun refreshAdvertisementWithCurrentState() {
        mainHandler.post {
            if (!advertising || connectedDevices.isNotEmpty() || !ServiceControl.isEnabled(this)) return@post
            val advertiser = manager.adapter?.bluetoothLeAdvertiser ?: return@post
            advertiser.stopAdvertising(advertiseCallback)
            advertising = false
            log("BLE_ADVERTISING_REFRESH gsn=${currentStateNumber()}")
            mainHandler.postDelayed({
                if (!advertising && !starting && connectedDevices.isEmpty() &&
                    ServiceControl.isEnabled(this) && checkBluetoothPermissions()
                ) {
                    startAdvertisingAfterGattPublished()
                }
            }, 150L)
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
        gattCharacteristicsByIid.clear()
        eventSubscriptions.clear()
        pendingEventNotifications.clear()
        eventInFlight.clear()
        pairSetupSessions.clear()
        pairVerifySessions.clear()
        pendingPairingRemovals.clear()
        restartAfterPairingsResponse.clear()
        connectedDevices.clear()
        connectedDeviceRefs.clear()
        stateNumberIncrementedForConnection = false
        advertising = false
        activeAdvertiseMode = null
        starting = false
        publishRuntimeStatus()
        log("PROBE_STOPPED")
        updateServiceNotification()
    }

    @SuppressLint("MissingPermission")
    private fun queueGattDatabase() {
        pendingServices.clear()
        pendingServices.addAll(HomeKitAccessoryCatalog.services.map(::service))
    }

    @SuppressLint("MissingPermission")
    private fun addNextService() {
        val next = pendingServices.pollFirst()
        if (next == null) {
            log("GATT_DATABASE_PUBLISHED services=${HomeKitAccessoryCatalog.services.size}")
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

    private fun service(definition: HapServiceDefinition): BluetoothGattService {
        return BluetoothGattService(hapUuid(definition.type), BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(BluetoothGattCharacteristic(
                SERVICE_IID_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply { value = definition.iid.leBytes() })
            definition.characteristics.forEach { characteristic ->
                // HAP-over-BLE 7.4.6 requires every HAP EV characteristic to
                // expose the BLE Indicate property and the standard CCCD. HAP
                // metadata alone is insufficient: Home otherwise enumerates an
                // E3 ConfiguredName signature but does not fetch its value,
                // then falls back to generated "Switch 1" / "Light 1" labels.
                val supportsEvents = characteristic.properties and HAP_PROPERTY_EVENT != 0
                val gattProperties = BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    if (supportsEvents) BluetoothGattCharacteristic.PROPERTY_INDICATE else 0
                val gattCharacteristic = BluetoothGattCharacteristic(
                    hapUuid(characteristic.type),
                    gattProperties,
                    BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
                ).apply {
                    value = initialCharacteristicValue(characteristic)
                    addDescriptor(BluetoothGattDescriptor(IID_DESCRIPTOR_UUID, BluetoothGattDescriptor.PERMISSION_READ).apply {
                        value = characteristic.iid.leBytes()
                    })
                    if (supportsEvents) {
                        addDescriptor(BluetoothGattDescriptor(
                            CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
                            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                        ).apply { value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE })
                    }
                }
                gattCharacteristicDefinitions[gattCharacteristic] = characteristic
                gattCharacteristicsByIid[characteristic.iid] = gattCharacteristic
                addCharacteristic(gattCharacteristic)
            }
        }
    }

    private fun initialCharacteristicValue(characteristic: HapCharacteristicDefinition): ByteArray {
        characteristic.control?.takeIf { it.valueKind == HapValueKind.INT32 }?.let { control ->
            return (controlLifecycles[control.model.command]?.currentPercentage() ?: 0).leBytes32()
        }
        characteristic.readOnlyState?.let {
            return batteryCharacteristicValue(characteristic) ?: byteArrayOf()
        }
        return when (characteristic.type) {
            0x14 -> byteArrayOf(0)
            0x20 -> "Xiaomi".encodeToByteArray()
            0x21 -> "Xiaomi 13".encodeToByteArray()
            0x23 -> when (characteristic.service.type) {
                0x3E -> (commandPrefs.getString("device_name", "Marionette") ?: "Marionette").encodeToByteArray()
                else -> serviceDisplayName(characteristic.service).encodeToByteArray()
            }
            CONFIGURED_NAME_TYPE -> configuredServiceName(characteristic.service).encodeToByteArray()
            0x30 -> accessoryPairingId().encodeToByteArray()
            0x52 -> "1.0".encodeToByteArray()
            0x68 -> byteArrayOf(batteryLevel())
            0x79, 0x8F -> byteArrayOf(batteryStatus(characteristic.type))
            0x4F -> byteArrayOf(0)
            0x25 -> byteArrayOf(0)
            0x11A -> byteArrayOf(0)
            else -> byteArrayOf()
        }
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
            activeAdvertiseMode = settingsInEffect.mode
            starting = false
            log("BLE_ADVERTISING_STARTED mode=${settingsInEffect.mode} connectable=${settingsInEffect.isConnectable}")
            publishRuntimeStatus()
            updateServiceNotification()
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            starting = false
            log("BLE_ADVERTISING_FAILED code=$errorCode")
            publishRuntimeStatus()
            updateServiceNotification()
            scheduleProbeRecovery("advertising_failed_$errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            log("BLE_CONNECTION address=${device.address} status=$status state=${stateName(newState)}")
            var gsnChanged = false
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices += device.address
                connectedDeviceRefs[device.address] = device
                stateNumberIncrementedForConnection = false
                gattTrafficSinceConnection = false
                startStatePolling()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val hadGattTraffic = gattTrafficSinceConnection
                gsnChanged = stateNumberIncrementedForConnection
                connectedDevices -= device.address
                connectedDeviceRefs.remove(device.address)
                stateNumberIncrementedForConnection = false
                gattTrafficSinceConnection = false
                if (hadGattTraffic) {
                    protocollessDisconnectStreak = 0
                } else {
                    protocollessDisconnectStreak += 1
                    log("BLE_PROTOCOLLESS_DISCONNECT streak=$protocollessDisconnectStreak")
                    if (protocollessDisconnectStreak >= PROTOCOLLESS_DISCONNECT_RESTART_THRESHOLD) {
                        protocollessDisconnectStreak = 0
                        scheduleProbeRecovery("protocolless_disconnect")
                    }
                }
            }
            updateServiceNotification()
            publishRuntimeStatus()
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                peerMtu.remove(device.address)
                pduResponses.keys.removeIf { it.startsWith("${device.address}|") }
                pduRequests.keys.removeIf { it.startsWith("${device.address}|") }
                eventSubscriptions.removeIf { it.startsWith("${device.address}|") }
                pendingEventNotifications.removeIf { it.startsWith("${device.address}|") }
                eventInFlight.remove(device.address)
                pairSetupSessions.remove(device.address)
                pairVerifySessions.remove(device.address)
                pendingPairingRemovals.remove(device.address)
                restartAfterPairingsResponse.remove(device.address)
                // Android's advertiser data is static after start. Rebuild it
                // only when the GSN actually changed while connected. A blind
                // refresh on every disconnect creates a reconnect storm on MIUI.
                if (gsnChanged) {
                    refreshAdvertisementWithCurrentState()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            peerMtu[device.address] = mtu
            log("BLE_MTU address=${device.address} mtu=$mtu")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val completed = eventInFlight.remove(device.address)
            log("HAP_EVENT_INDICATION_SENT address=${device.address} status=$status key=$completed")
            if (completed != null && status != BluetoothGatt.GATT_SUCCESS) {
                // Keep the event so a transient ATT busy/error condition does not
                // silently lose the state transition.
                pendingEventNotifications += completed
            }
            drainPendingEventNotifications(device.address)
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            log("GATT_SERVICE_ADDED status=$status uuid=${service.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addNextService()
            } else {
                stopProbe()
                scheduleProbeRecovery("gatt_service_add_$status")
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            gattTrafficSinceConnection = true
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
                        // An EV subscription may have been written before Pair
                        // Verify completed. Indications are only legal after the
                        // encrypted session is active, so drain now as well.
                        drainPendingEventNotifications(device.address)
                    }
                }
                if (response.isFinal &&
                    gattDefinition(characteristic)?.type == PAIRINGS_TYPE &&
                    restartAfterPairingsResponse.remove(device.address)
                ) {
                    log("PAIRINGS_REMOVE_RESPONSE_SENT restart_scheduled=true")
                    mainHandler.postDelayed({
                        log("PAIRINGS_REMOVE_RESTART advertising_with_sf_unpaired=true")
                        stopProbe()
                        if (checkBluetoothPermissions()) startProbe()
                    }, PAIRING_REMOVAL_RESTART_DELAY_MS)
                }
                if (response.isFinal) pduResponses.remove(key)
                return
            }
            val value = characteristic.value ?: byteArrayOf()
            log("GATT_READ uuid=${characteristic.uuid} offset=$offset value=${value.hex()}")
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.drop(offset).toByteArray())
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            gattTrafficSinceConnection = true
            val value = if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIGURATION_UUID) {
                val definition = gattDefinition(descriptor.characteristic)
                if (definition?.let {
                    it.properties and HAP_PROPERTY_EVENT != 0 &&
                        eventSubscriptions.contains(eventKey(device.address, it.iid))
                } == true) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
            } else descriptor.value ?: byteArrayOf()
            log("GATT_DESCRIPTOR_READ uuid=${descriptor.uuid} offset=$offset value=${value.hex()}")
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.drop(offset).toByteArray())
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            gattTrafficSinceConnection = true
            if (!preparedWrite && offset == 0 && descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIGURATION_UUID) {
                val definition = gattDefinition(descriptor.characteristic)
                val iid = definition?.iid
                val subscriptionKey = iid?.let { eventKey(device.address, it) }
                val validValue = value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                if (definition == null || definition.properties and HAP_PROPERTY_EVENT == 0 || !validValue) {
                    log("GATT_CCCD_REJECT characteristic=${descriptor.characteristic.uuid} iid=$iid value=${value.hex()}")
                    if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                    return
                }
                descriptor.value = value.copyOf()
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                    eventSubscriptions += subscriptionKey!!
                    log("HAP_EVENT_SUBSCRIBED address=${device.address} iid=0x%04X".format(iid))
                } else {
                    eventSubscriptions -= subscriptionKey!!
                    pendingEventNotifications -= subscriptionKey
                    log("HAP_EVENT_UNSUBSCRIBED address=${device.address} iid=0x%04X".format(iid))
                }
                drainPendingEventNotifications(device.address)
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            gattTrafficSinceConnection = true
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
            if (pending.body.size < pending.totalBodyBytes) return
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
        val totalBodyBytes = if (request.size >= 7) request[5].u8() or (request[6].u8() shl 8) else 0
        val body = if (request.size >= 7) request.copyOfRange(7, request.size) else byteArrayOf()
        if (body.size < totalBodyBytes) {
            // The HAP body length is authoritative. Pairing TLVs can cross ATT
            // fragments, so retain the bytes until exactly that body is present.
            pduRequests[key] = HapRequestAssembly(opcode, tid, iid, totalBodyBytes, body)
            return
        }
        processHapPdu(device, characteristic, opcode, tid, iid, body)
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
                    definition.control?.let { binding ->
                        val lifecycle = controlLifecycle(definition)
                        if (lifecycle == null) {
                            HapResponse(tid, HAP_STATUS_UNSUPPORTED_PDU)
                        } else if (binding.valueKind == HapValueKind.INT32) {
                            val value = lifecycle.currentPercentage()
                            log("CONTROL_STATE_READ command=${binding.command} value=$value service=0x%02X iid=0x%04X".format(definition.service.type, iid))
                            HapResponse(tid, HAP_STATUS_SUCCESS, tlv(HAP_TLV_VALUE, value.leBytes32()))
                        } else {
                            val enabled = lifecycle.currentBoolean()
                            log("CONTROL_STATE_READ command=${binding.command} enabled=$enabled service=0x%02X iid=0x%04X".format(definition.service.type, iid))
                            HapResponse(tid, HAP_STATUS_SUCCESS, tlv(HAP_TLV_VALUE, byteArrayOf(if (enabled) 1 else 0)))
                        }
                    } ?: batteryCharacteristicValue(definition)?.let { value ->
                        log("CONTROL_STATE_READ battery type=0x%02X value=${value.hex()} iid=0x%04X".format(definition.type, iid))
                        HapResponse(tid, HAP_STATUS_SUCCESS, tlv(HAP_TLV_VALUE, value))
                    } ?: readableCharacteristicValue(definition)?.let { value ->
                        if (definition.type == 0x23 || definition.type == CONFIGURED_NAME_TYPE) {
                            log(
                                "SERVICE_NAME_READ type=0x%02X service=0x%04X iid=0x%04X name=%s"
                                    .format(definition.type, definition.service.iid, iid, value.toString(Charsets.UTF_8))
                            )
                        }
                        HapResponse(tid, HAP_STATUS_SUCCESS, tlv(HAP_TLV_VALUE, value))
                    } ?: HapResponse(tid, HAP_STATUS_UNSUPPORTED_PDU)
                }
            }
            HAP_OPCODE_CHARACTERISTIC_CONFIGURATION -> {
                if (definition == null || iid != definition.iid) {
                    HapResponse(tid, HAP_STATUS_INVALID_INSTANCE_ID)
                } else {
                    characteristicConfigurationRequest(definition, requestBody, tid)
                }
            }
            HAP_OPCODE_PROTOCOL_CONFIGURATION -> {
                val service = definition?.service
                if (definition == null || definition.type != SERVICE_SIGNATURE_TYPE || service == null ||
                    !service.supportsConfiguration || iid != service.iid
                ) {
                    HapResponse(tid, HAP_STATUS_UNSUPPORTED_PDU)
                } else {
                    protocolConfigurationRequest(requestBody, tid)
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
                } else if (definition?.type == CONFIGURED_NAME_TYPE && iid == definition.iid) {
                    val rawValue = parseTlvs(requestBody)[HAP_TLV_VALUE]
                        ?.fold(byteArrayOf()) { acc, part -> acc + part }
                    val configuredName = rawValue?.toString(Charsets.UTF_8)?.trim()
                    if (rawValue == null || configuredName.isNullOrEmpty() || rawValue.size > MAX_CONFIGURED_NAME_BYTES) {
                        HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
                    } else {
                        if (saveConfiguredName(definition.service, configuredName)) {
                            log("CONFIGURED_NAME_UPDATED service=0x%04X iid=0x%04X name=$configuredName".format(definition.service.iid, iid))
                            HapResponse(tid, HAP_STATUS_SUCCESS)
                        } else {
                            // Home generated this category label itself. Acknowledging
                            // the write makes it the controller's final title, even
                            // though the accessory keeps returning its Chinese E3
                            // value. Reject it so Home retains the just-read E3 name.
                            log("CONFIGURED_NAME_FALLBACK_REJECTED service=0x%04X iid=0x%04X name=$configuredName".format(definition.service.iid, iid))
                            HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
                        }
                    }
                } else if (definition != null && iid == definition.iid) {
                    val control = definition.control
                    val command = control?.command
                    val lifecycle = controlLifecycle(definition)
                    val rawValue = parseTlvs(requestBody)[HAP_TLV_VALUE]?.fold(byteArrayOf()) { acc, part -> acc + part }
                    val numericValue = rawValue?.let { decodeWriteValue(definition, it) }
                    if (command == null || numericValue == null || lifecycle == null) {
                        HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
                    } else {
                        val enabled = numericValue != 0
                        val commandStartedAt = SystemClock.elapsedRealtime()
                        log("CONTROL_COMMAND_RECEIVED command=$command value=$numericValue service=0x%02X iid=0x%04X".format(definition.service.type, iid))
                        if (!commandPrefs.getBoolean("command_enabled_${command.name}", true)) {
                            log("COMMAND_BLOCKED command=$command value=$numericValue reason=disabled_by_user")
                            notifyCommand(command, enabled, false, "已在应用中禁用")
                            HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
                        } else {
                            val result = if (control?.valueKind == HapValueKind.INT32) {
                                lifecycle.executePercentage(numericValue)
                            } else {
                                lifecycle.executeBoolean(enabled)
                            }
                            val elapsedMs = SystemClock.elapsedRealtime() - commandStartedAt
                            log("COMMAND_EXECUTE command=$command value=$numericValue success=${result.success} elapsedMs=$elapsedMs message=${result.message}")
                            // Keep the GATT callback thread focused on completing the
                            // HAP transaction. Notification and state readback may
                            // involve Binder/root I/O and must not hold up the response.
                            mainHandler.post {
                                notifyCommand(command, enabled, result.success, result.message)
                                updateServiceNotification()
                            }
                            if (result.success) {
                                // ADK suppresses the indication while the same
                                // characteristic is being written. The state
                                // poller observes the committed Android state and
                                // raises the event after the HAP write completes.
                                mainHandler.post {
                                    refreshObservedControlState(definition)
                                    publishControlState(command)
                                }
                            }
                            HapResponse(tid, if (result.success) HAP_STATUS_SUCCESS else HAP_STATUS_INVALID_REQUEST)
                        }
                    }
                } else HapResponse(tid, HAP_STATUS_UNSUPPORTED_PDU)
            }
            HAP_OPCODE_CHARACTERISTIC_EXECUTE_WRITE -> {
                if (definition?.type == PAIRINGS_TYPE && iid == definition.iid) {
                    // HomeKit uses Timed Write followed by Execute Write for
                    // Pairings management. Commit a queued removal here, then
                    // keep the encrypted session alive until Home has read this
                    // final response.
                    commitPendingPairingRemoval(device)
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
        log("HAP_PDU_REQUEST opcode=0x%02X tid=$tid iid=0x%04X status=0x%02X responseBody=${response.body.hex()}".format(opcode, iid, response.status))
    }

    private fun serviceSignature(service: HapServiceDefinition): ByteArray {
        val properties = when {
            service.primary -> 0x0001
            service.supportsConfiguration -> 0x0004
            else -> 0
        }
        return if (properties == 0) byteArrayOf() else tlv(HAP_TLV_SERVICE_PROPERTIES, properties.leBytes())
    }

    /**
     * Implements the official HAP-Characteristic-Configuration procedure.
     * Normal EV subscriptions still use the GATT CCCD; this procedure only
     * configures optional BLE broadcast notifications, which this accessory
     * deliberately does not advertise.
     */
    private fun characteristicConfigurationRequest(
        characteristic: HapCharacteristicDefinition,
        body: ByteArray,
        tid: Int
    ): HapResponse {
        val tlvs = parseTlvs(body)
        val properties = tlvs[CONFIGURATION_TLV_PROPERTIES]?.firstOrNull()?.let { raw ->
            if (raw.size != 2) null else raw[0].u8() or (raw[1].u8() shl 8)
        }
        val broadcastInterval = tlvs[CONFIGURATION_TLV_BROADCAST_INTERVAL]
        if ((body.isNotEmpty() && tlvs.isEmpty()) || (tlvs.containsKey(CONFIGURATION_TLV_PROPERTIES) && properties == null) ||
            tlvs.keys.any { it != CONFIGURATION_TLV_PROPERTIES && it != CONFIGURATION_TLV_BROADCAST_INTERVAL }
        ) {
            log("HAP_CHARACTERISTIC_CONFIGURATION_REJECTED iid=0x%04X reason=malformed".format(characteristic.iid))
            return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        }
        val requestedProperties = properties ?: 0
        if (requestedProperties != 0 || (broadcastInterval != null && requestedProperties == 0)) {
            // No characteristic in this catalog has the ADK broadcast flag;
            // accepting enablement would produce an event configuration that
            // cannot be represented in the advertisement.
            log("HAP_CHARACTERISTIC_CONFIGURATION_REJECTED iid=0x%04X properties=0x%04X".format(characteristic.iid, requestedProperties))
            return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        }
        log("HAP_CHARACTERISTIC_CONFIGURATION iid=0x%04X properties=0x0000".format(characteristic.iid))
        return HapResponse(tid, HAP_STATUS_SUCCESS, tlv(CONFIGURATION_TLV_PROPERTIES, byteArrayOf(0, 0)))
    }

    /** Implements HAP-Protocol-Configuration/Get-All-Params for Protocol Information. */
    private fun protocolConfigurationRequest(body: ByteArray, tid: Int): HapResponse {
        val tlvs = parseTlvs(body)
        if ((body.isNotEmpty() && tlvs.isEmpty()) || tlvs.keys.any {
                it != PROTOCOL_CONFIGURATION_TLV_GENERATE_BROADCAST_KEY &&
                    it != PROTOCOL_CONFIGURATION_TLV_GET_ALL_PARAMS &&
                    it != PROTOCOL_CONFIGURATION_TLV_SET_ADVERTISING_ID
            }) {
            log("HAP_PROTOCOL_CONFIGURATION_REJECTED reason=malformed")
            return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        }
        val getAll = tlvs[PROTOCOL_CONFIGURATION_TLV_GET_ALL_PARAMS]?.all { it.isEmpty() } == true
        val generateKey = tlvs[PROTOCOL_CONFIGURATION_TLV_GENERATE_BROADCAST_KEY]
            ?.all { it.isEmpty() } == true
        val advertisingId = tlvs[PROTOCOL_CONFIGURATION_TLV_SET_ADVERTISING_ID]?.firstOrNull()
        if (tlvs[PROTOCOL_CONFIGURATION_TLV_GET_ALL_PARAMS]?.any { it.isNotEmpty() } == true ||
            tlvs[PROTOCOL_CONFIGURATION_TLV_GENERATE_BROADCAST_KEY]?.any { it.isNotEmpty() } == true ||
            tlvs[PROTOCOL_CONFIGURATION_TLV_SET_ADVERTISING_ID]?.any { it.size != 6 } == true ||
            (tlvs.containsKey(PROTOCOL_CONFIGURATION_TLV_GENERATE_BROADCAST_KEY) && !generateKey)
        ) {
            log("HAP_PROTOCOL_CONFIGURATION_REJECTED reason=invalid_parameters")
            return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        }
        // Broadcast encryption is intentionally not exposed: no catalog
        // characteristic advertises supportsBroadcastNotification. A request
        // to set the advertising identifier is accepted for protocol
        // compatibility, but the stable accessory ID remains authoritative.
        if (advertisingId != null) {
            log("HAP_PROTOCOL_CONFIGURATION_ADVERTISING_ID_IGNORED value=${advertisingId.hex()}")
        }
        if (generateKey) {
            log("HAP_PROTOCOL_CONFIGURATION_REJECTED reason=broadcast_not_supported")
            return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
        }
        if (!getAll) {
            log("HAP_PROTOCOL_CONFIGURATION acknowledged=true getAll=false")
            return HapResponse(tid, HAP_STATUS_SUCCESS)
        }
        val response = tlv(PROTOCOL_RESPONSE_TLV_CURRENT_STATE_NUMBER, currentStateNumber().leBytes()) +
            tlv(PROTOCOL_RESPONSE_TLV_CURRENT_CONFIG_NUMBER, byteArrayOf(CURRENT_CONFIG_NUMBER.toByte())) +
            tlv(PROTOCOL_RESPONSE_TLV_ADVERTISING_ID, accessoryIdBytes())
        log("HAP_PROTOCOL_CONFIGURATION getAll=true gsn=0x%04X cn=0x%02X".format(currentStateNumber(), CURRENT_CONFIG_NUMBER))
        return HapResponse(tid, HAP_STATUS_SUCCESS, response)
    }

    private fun characteristicSignature(characteristic: HapCharacteristicDefinition): ByteArray {
        val service = characteristic.service
        val unit = when (characteristic.type) {
            HapType.BRIGHTNESS, HapType.BATTERY_LEVEL -> byteArrayOf(0xAD.toByte(), 0x27)
            else -> byteArrayOf(0, 0x27)
        }
        val constraints = when (characteristic.type) {
            HapType.BRIGHTNESS -> tlv(HAP_TLV_GATT_VALID_RANGE, 0.leBytes32() + 100.leBytes32())
            HapType.BATTERY_LEVEL -> tlv(HAP_TLV_GATT_VALID_RANGE, byteArrayOf(0, 100))
            HapType.STATUS_LOW_BATTERY, HapType.CHARGING_STATE -> tlv(HAP_TLV_GATT_VALID_RANGE, byteArrayOf(0, 1))
            else -> byteArrayOf()
        }
        return tlv(HAP_TLV_CHARACTERISTIC_TYPE, hapPduUuidBytes(characteristic.type)) +
            tlv(HAP_TLV_SERVICE_INSTANCE_ID, service.iid.leBytes()) +
            tlv(HAP_TLV_SERVICE_TYPE, hapPduUuidBytes(service.type)) +
            tlv(HAP_TLV_CHARACTERISTIC_PROPERTIES, characteristic.properties.leBytes()) +
            tlv(HAP_TLV_PRESENTATION_FORMAT, byteArrayOf(characteristic.format, 0, unit[0], unit[1], 1, 0, 0)) +
            constraints
    }

    private fun batteryCharacteristicValue(characteristic: HapCharacteristicDefinition): ByteArray? = when (characteristic.readOnlyState) {
        HapReadOnlyState.BATTERY_LEVEL -> byteArrayOf(batteryLevel())
        HapReadOnlyState.BATTERY_LOW -> byteArrayOf(batteryStatus(0x79))
        HapReadOnlyState.BATTERY_CHARGING -> byteArrayOf(batteryStatus(0x8F))
        null -> null
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
            PAIRING_METHOD_REMOVE -> {
                if (identifier == null) return HapResponse(tid, HAP_STATUS_INVALID_REQUEST)
                // HAP Pairings is a timed-write control point. Preserve the
                // request until Execute Write commits it.
                pendingPairingRemovals[device.address] = identifier
                log("PAIRINGS_REMOVE_QUEUED controller=${identifier.decodeToString()}")
            }
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

    /** Commits HAP Remove Pairing for this single-controller implementation. */
    private fun commitPendingPairingRemoval(device: BluetoothDevice) {
        val requestedId = pendingPairingRemovals.remove(device.address) ?: return
        val encodedSavedId = commandPrefs.getString("controller_id", null)
        val savedId = try {
            encodedSavedId?.let { Base64.decode(it, Base64.NO_WRAP) }
        } catch (_: IllegalArgumentException) {
            null
        }

        // HAP requires success when the requested pairing does not exist.
        if (savedId == null || !savedId.contentEquals(requestedId)) {
            log("PAIRINGS_REMOVE_NOT_FOUND controller=${requestedId.decodeToString()} success=true")
            return
        }

        // This runtime stores one admin controller. Removing it therefore
        // removes all controller material, matching ADK cleanup semantics.
        val persisted = commandPrefs.edit()
            .remove("controller_id")
            .remove("controller_key")
            .commit()
        if (!persisted) {
            log("PAIRINGS_REMOVE_FAILED controller=${requestedId.decodeToString()} reason=persistence")
            return
        }
        restartAfterPairingsResponse += device.address
        log("PAIRINGS_REMOVE_APPLIED controller=${requestedId.decodeToString()} paired=false")
    }

    private fun readableCharacteristicValue(characteristic: HapCharacteristicDefinition): ByteArray? = when (characteristic.type) {
        0x14, 0x20, 0x21, 0x23, 0x30, 0x52, CONFIGURED_NAME_TYPE -> initialCharacteristicValue(characteristic)
        else -> null
    }

    /** Legacy Name value retained alongside the modern Configured Name value. */
    private fun serviceDisplayName(service: HapServiceDefinition): String = requireNotNull(service.defaultName) {
        "Only functional services may expose a service-level Name: iid=0x%04X".format(service.iid)
    }

    /**
     * Modern Home versions use Configured Name as the display name of each
     * functional service. Name remains on Accessory Information and as a
     * backwards-compatible service value, while 0xE3 is authoritative here.
     */
    private fun configuredServiceName(service: HapServiceDefinition): String {
        val defaultName = serviceDisplayName(service)
        val savedName = commandPrefs.getString("service_name_${service.iid}", null)?.trim()
        if (savedName != null && isGeneratedCategoryName(savedName, service.type)) {
            // Home may try to seed ConfiguredName with its generic category
            // label ("灯 1" / "开关 2"). It is not a user-selected name and must
            // never become the accessory's persisted default on the next pair.
            commandPrefs.edit().remove("service_name_${service.iid}").apply()
            log("CONFIGURED_NAME_FALLBACK_CLEARED service=0x%04X name=$savedName".format(service.iid))
            return defaultName
        }
        return savedName ?: defaultName
    }

    /** Returns false when Home attempts to replace a real name with its own category fallback. */
    private fun saveConfiguredName(service: HapServiceDefinition, name: String): Boolean {
        if (isGeneratedCategoryName(name, service.type)) {
            commandPrefs.edit().remove("service_name_${service.iid}").apply()
            log("CONFIGURED_NAME_FALLBACK_IGNORED service=0x%04X name=$name".format(service.iid))
            return false
        }
        commandPrefs.edit().putString("service_name_${service.iid}", name).apply()
        return true
    }

    private fun isGeneratedCategoryName(name: String, serviceType: Int): Boolean {
        val normalized = name.replace(" ", "")
        val category = when (serviceType) {
            0x43 -> "灯"
            0x49 -> "开关"
            else -> return false
        }
        return normalized == category || normalized.matches(Regex("^${category}\\d+$"))
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
        publishRuntimeStatus()
        updateServiceNotification()

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
        log("PAIR_SETUP_CODE_USED srp_format=3-2-3 display_format=4-4")
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
    private fun gattDefinition(characteristic: BluetoothGattCharacteristic): HapCharacteristicDefinition? {
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
        "${device.address}|iid=${characteristicIid(characteristic)?.toString(16) ?: "unknown-${characteristic.uuid}"}"

    private fun characteristicIid(characteristic: BluetoothGattCharacteristic): Int? =
        gattDefinition(characteristic)?.iid ?: characteristic.descriptors
            .firstOrNull { it.uuid == IID_DESCRIPTOR_UUID }
            ?.value
            ?.takeIf { it.size >= 2 }
            ?.let { it[0].u8() or (it[1].u8() shl 8) }

    private fun eventKey(address: String, iid: Int): String = "$address|iid=$iid"

    /**
     * HAP BLE connected events are zero-length Handle Value Indications. The
     * controller then performs a normal encrypted Characteristic Read to get
     * the value. This is the same model used by HAPBLEPeripheralManager in the
     * Apple ADK; the indication must not contain the value itself.
     */
    private fun queueEventForIid(iid: Int) {
        connectedDevices.forEach { address ->
            val key = eventKey(address, iid)
            if (eventSubscriptions.contains(key)) {
                pendingEventNotifications += key
                drainPendingEventNotifications(address)
            }
        }
    }

    private fun drainPendingEventNotifications(address: String) {
        mainHandler.post {
            if (!connectedDevices.contains(address) || eventInFlight.containsKey(address)) return@post
            // Apple ADK defers Handle Value Indications until Pair Verify has
            // established the encrypted control session.
            if (pairVerifySessions[address]?.active != true) return@post
            val device = connectedDeviceRefs[address] ?: return@post
            val key = pendingEventNotifications.firstOrNull {
                it.startsWith("$address|iid=") && eventSubscriptions.contains(it)
            } ?: return@post
            val iid = key.substringAfter("|iid=").toIntOrNull() ?: run {
                pendingEventNotifications.remove(key)
                return@post
            }
            val gattCharacteristic = gattCharacteristicsByIid[iid] ?: run {
                pendingEventNotifications.remove(key)
                log("HAP_EVENT_INDICATION_SKIPPED address=$address iid=$iid reason=gatt_characteristic_missing")
                return@post
            }
            pendingEventNotifications.remove(key)
            eventInFlight[address] = key
            val status = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    server?.notifyCharacteristicChanged(
                        device, gattCharacteristic, true, byteArrayOf()
                    ) ?: BluetoothStatusCodes.ERROR_UNKNOWN
                } else {
                    // On pre-33 Android the value is read from the mutable
                    // characteristic object. HAP requires an empty indication.
                    gattCharacteristic.value = byteArrayOf()
                    if (server?.notifyCharacteristicChanged(device, gattCharacteristic, true) == true) {
                        BluetoothGatt.GATT_SUCCESS
                    } else {
                        BluetoothGatt.GATT_FAILURE
                    }
                }
            } catch (t: Throwable) {
                log("HAP_EVENT_INDICATION_FAILED address=$address iid=$iid ${t.javaClass.simpleName}:${t.message}")
                BluetoothGatt.GATT_FAILURE
            }
            if (status != BluetoothGatt.GATT_SUCCESS && status != BluetoothStatusCodes.SUCCESS) {
                eventInFlight.remove(address)
                pendingEventNotifications += key
                log("HAP_EVENT_INDICATION_RETRY address=$address iid=$iid status=$status")
                mainHandler.postDelayed({ drainPendingEventNotifications(address) }, EVENT_RETRY_DELAY_MS)
            } else {
                log("HAP_EVENT_INDICATION_QUEUED address=$address iid=$iid")
            }
        }
    }

    private fun pollControlStates() {
        HomeKitAccessoryCatalog.services.asSequence()
            .flatMap { it.characteristics.asSequence() }
            .filter { it.control != null }
            .forEach(::refreshObservedControlState)
    }

    private fun startStatePolling() {
        if (statePollFuture?.isCancelled == false && statePollFuture?.isDone == false) return
        statePollFuture = statePoller.scheduleWithFixedDelay(
            ::pollControlStates,
            STATE_POLL_INITIAL_DELAY_SECONDS,
            STATE_POLL_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
        log("STATE_POLLING_STARTED intervalSeconds=$STATE_POLL_INTERVAL_SECONDS")
    }

    private fun stopStatePolling() {
        statePollFuture?.cancel(false)
        statePollFuture = null
        log("STATE_POLLING_STOPPED")
    }

    private fun refreshObservedControlState(definition: HapCharacteristicDefinition) {
        val control = definition.control ?: return
        val lifecycle = controlLifecycle(definition) ?: return
        val value = when (control.valueKind) {
            HapValueKind.BOOLEAN -> byteArrayOf(if (lifecycle.currentBoolean()) 1 else 0)
            HapValueKind.INT32 -> lifecycle.currentPercentage().leBytes32()
        }
        val previous = lastObservedControlValues.put(definition.iid, value)
        if (previous != null && !previous.contentEquals(value)) {
            incrementStateNumberForConnection()
            log("CONTROL_STATE_CHANGED command=${control.command} iid=0x%04X value=${value.hex()}".format(definition.iid))
            publishControlState(control.command)
            queueEventForIid(definition.iid)
        }
    }

    private fun currentStateNumber(): Int = commandPrefs.getInt("homekit_gsn", DEFAULT_GSN)

    private fun controlLifecycle(definition: HapCharacteristicDefinition): HomeKitControlLifecycle? =
        definition.control?.let { controlLifecycles[it.model.command] }

    private fun incrementStateNumberForConnection() {
        if (stateNumberIncrementedForConnection) return
        synchronized(this) {
            if (stateNumberIncrementedForConnection) return
            val next = if (currentStateNumber() >= 0xFFFF) 1 else currentStateNumber() + 1
            commandPrefs.edit().putInt("homekit_gsn", next).apply()
            stateNumberIncrementedForConnection = true
            log("HAP_GSN_INCREMENTED gsn=$next")
            refreshAdvertisementWithCurrentState()
        }
    }

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

    /** Decode numeric HAP values while tolerating older Home BLE clients that
     * encode the same 0..100 percentage as UInt8/UInt16 or Float32. The
     * characteristic remains declared as the official Brightness Int format;
     * this compatibility is only for incoming writes. */
    private fun decodeWriteValue(characteristic: HapCharacteristicDefinition, raw: ByteArray): Int? =
        when (characteristic.control?.valueKind) {
            HapValueKind.BOOLEAN -> raw.singleOrNull()?.u8()?.takeIf { it == 0 || it == 1 }
            HapValueKind.INT32 -> when (raw.size) {
                1 -> raw[0].u8()
                2 -> raw[0].u8() or (raw[1].u8() shl 8)
                4 -> {
                    val value = raw[0].u8() or (raw[1].u8() shl 8) or
                        (raw[2].u8() shl 16) or (raw[3].u8() shl 24)
                    if (value in 0..100) value
                    else Float.fromBits(value).takeIf { it.isFinite() && it in 0f..100f }?.toInt()
                }
                else -> null
            }?.takeIf { it in 0..100 }
            null -> null
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
        private const val ACTION_STOP = "dev.local.mihotspot.action.STOP"
        private const val SERVICE_CHANNEL_ID = "homekit_service"
        private const val FOREGROUND_NOTIFICATION_ID = 7299
        private const val REQUEST_BLUETOOTH = 100
        private const val REQUEST_NOTIFICATIONS = 101
        private const val REQUEST_CAMERA = 102
        private const val NOTIFICATION_CHANNEL_ID = "homekit_commands"
        private const val NOTIFICATION_BASE_ID = 7300
        private const val APPLE_COMPANY_ID = 0x004C
        private const val DEFAULT_ATT_MTU = 23
        private const val DEFAULT_GSN = 15
        private const val STATE_POLL_INITIAL_DELAY_SECONDS = 2L
        private const val STATE_POLL_INTERVAL_SECONDS = 10L
        private const val BLUETOOTH_RECOVERY_DELAY_MS = 1500L
        private fun desiredAdvertiseMode(context: android.content.Context, paired: Boolean): Int = when (
            ServiceControl.effectiveBroadcastStrategy(context, paired)
        ) {
            BroadcastStrategy.LOW_LATENCY -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            BroadcastStrategy.BALANCED -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
            BroadcastStrategy.LOW_POWER -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
            BroadcastStrategy.AUTO -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
        }
        private const val PROBE_RECOVERY_DELAY_MS = 5000L
        private const val EVENT_RETRY_DELAY_MS = 100L
        private const val GATT_OPEN_MAX_ATTEMPTS = 5
        private const val GATT_OPEN_RETRY_DELAY_MS = 1500L
        private const val PROTOCOLLESS_DISCONNECT_RESTART_THRESHOLD = 3
        private const val PAIRING_REMOVAL_RESTART_DELAY_MS = 750L
        private const val HAP_REQUEST_HEADER_BYTES = 5
        private const val HAP_OPCODE_CHARACTERISTIC_SIGNATURE_READ = 0x01
        private const val HAP_OPCODE_CHARACTERISTIC_READ = 0x03
        private const val HAP_OPCODE_CHARACTERISTIC_WRITE = 0x02
        private const val HAP_OPCODE_CHARACTERISTIC_TIMED_WRITE = 0x04
        private const val HAP_OPCODE_CHARACTERISTIC_EXECUTE_WRITE = 0x05
        private const val HAP_OPCODE_SERVICE_SIGNATURE_READ = 0x06
        private const val HAP_OPCODE_CHARACTERISTIC_CONFIGURATION = 0x07
        private const val HAP_OPCODE_PROTOCOL_CONFIGURATION = 0x08
        private const val HAP_STATUS_SUCCESS = 0x00
        private const val HAP_STATUS_UNSUPPORTED_PDU = 0x01
        private const val HAP_STATUS_INVALID_INSTANCE_ID = 0x04
        private const val HAP_STATUS_INVALID_REQUEST = 0x06
        private const val HAP_STATUS_AUTHENTICATION_ERROR = 0x02
        private const val CONFIGURED_NAME_TYPE = 0xE3
        private const val MAX_CONFIGURED_NAME_BYTES = 64
        private const val HAP_TLV_CHARACTERISTIC_TYPE = 0x04
        private const val HAP_TLV_VALUE = 0x01
        private const val HAP_TLV_SERVICE_TYPE = 0x06
        private const val HAP_TLV_SERVICE_INSTANCE_ID = 0x07
        private const val HAP_TLV_CHARACTERISTIC_PROPERTIES = 0x0A
        private const val HAP_TLV_PRESENTATION_FORMAT = 0x0C
        private const val HAP_TLV_GATT_VALID_RANGE = 0x0D
        private const val HAP_TLV_SERVICE_PROPERTIES = 0x0F
        private const val CONFIGURATION_TLV_PROPERTIES = 0x01
        private const val CONFIGURATION_TLV_BROADCAST_INTERVAL = 0x02
        private const val PROTOCOL_CONFIGURATION_TLV_GENERATE_BROADCAST_KEY = 0x01
        private const val PROTOCOL_CONFIGURATION_TLV_GET_ALL_PARAMS = 0x02
        private const val PROTOCOL_CONFIGURATION_TLV_SET_ADVERTISING_ID = 0x03
        private const val PROTOCOL_RESPONSE_TLV_CURRENT_STATE_NUMBER = 0x01
        private const val PROTOCOL_RESPONSE_TLV_CURRENT_CONFIG_NUMBER = 0x02
        private const val PROTOCOL_RESPONSE_TLV_ADVERTISING_ID = 0x03
        // Incremented after changing the GATT/HAP database so paired
        // controllers invalidate cached characteristic handles.
        private const val CURRENT_CONFIG_NUMBER = 0x18
        private const val HAP_PROPERTY_EVENT = 0x0080
        private val CLIENT_CHARACTERISTIC_CONFIGURATION_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
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
        // The ADK stores these UUIDs as 16-byte little-endian arrays. Its
        // Darwin PAL reverses the whole array before creating a CBUUID, so
        // these are the canonical UUID strings Android must expose.
        private val SERVICE_IID_UUID = UUID.fromString("E604E95D-A759-4817-87D3-AA005083A0D1")
        private val IID_DESCRIPTOR_UUID = UUID.fromString("DC46F0FE-81D2-4616-B5D9-6ABDD796939A")

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

        private val characteristicsByIid: Map<Int, HapCharacteristicDefinition> =
            HomeKitAccessoryCatalog.services.flatMap { it.characteristics }.associateBy { it.iid }

        private fun hapUuid(shortUuid: Int): UUID =
            UUID.fromString("%08X-0000-1000-8000-0026BB765291".format(shortUuid))

        private val secureRandom = SecureRandom()
        private val SRP_N_BYTES = "ffffffffffffffffc90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74020bbea63b139b22514a08798e3404ddef9519b3cd3a431b302b0a6df25f14374fe1356d6d51c245e485b576625e7ec6f44c42e9a637ed6b0bff5cb6f406b7edee386bfb5a899fa5ae9f24117c4b1fe649286651ece45b3dc2007cb8a163bf0598da48361c55d39a69163fa8fd24cf5f83655d23dca3ad961c62f356208552bb9ed529077096966d670c354e4abc9804f1746c08ca18217c32905e462e36ce3be39e772c180e86039b2783a2ec07a28fb5c55df06f4c52c9de2bcbf6955817183995497cea956ae515d2261898fa051015728e5a8aaac42dad33170d04507a33a85521abdf1cba64ecfb850458dbef0a8aea71575d060c7db3970f85a6e1e4c7abf5ae8cdb0933d71e8c94e04a25619dcee3d2261ad2ee6bf12ffa06d98a0864d87602733ec86a64521f2b18177b200cbbe117577a615d6c770988c0bad946e208e24fa074e5ab3143db5bfce0fd108e4b82d120a93ad2caffffffffffffffff"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private val SRP_N = BigInteger(1, SRP_N_BYTES)
        private val SRP_GENERATOR = BigInteger.valueOf(5)
    }
}
