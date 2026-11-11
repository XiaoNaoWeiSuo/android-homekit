package dev.local.mihotspot.peripheral

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log

/**
 * The phone acting as a standard Bluetooth HID device (keyboard + relative
 * mouse). Uses Android's public BluetoothHidDevice profile (API 28+), i.e. the
 * same SDP record and HID report format as any retail Bluetooth keyboard — no
 * host-side driver, no custom protocol, no root.
 */
class BtHidPeripheral(context: Context) {

    enum class State { STOPPED, UNSUPPORTED, REGISTERING, READY, CONNECTED, FAILED }

    interface Listener {
        fun onStateChanged(state: State, detail: String?)
    }

    companion object {
        private const val TAG = "MiHotspotHid"

        // HID device subclass 0xC0 = keyboard (0x40) | pointing device (0x80).
        private const val SUBCLASS_COMBO = 0xC0

        const val PREFS = "peripheral"
        const val KEY_DEVICE_NAME = "hid_device_name"
        const val KEY_LAST_HOST = "hid_last_host"
        const val KEY_ENABLED = "hid_enabled"
        const val KEY_KEEP_ALIVE = "hid_keep_alive"
        const val DEFAULT_DEVICE_NAME = "Magic Keyboard"

        /**
         * A classic HID link is only scheduled while it carries traffic. When
         * the host sees no input it may move the ACL link into sniff mode; the
         * next report then waits for the wake-up, which shows up as "the mouse
         * is instant but the keyboard lags" (mouse motion is continuous).
         * A zero-delta mouse report is semantically inert for the host and
         * keeps the link scheduled.
         */
        private const val KEEP_ALIVE_INTERVAL_MS = 100L
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("bt-hid-device").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    @Volatile private var proxy: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var registered = false
    @Volatile private var running = false
    @Volatile private var receiverRegistered = false
    /** A getProfileProxy() request is in flight; prevents duplicate callbacks. */
    @Volatile private var profileRequested = false

    @Volatile var state: State = State.STOPPED
        private set
    @Volatile var hostName: String? = null
        private set
    @Volatile var lastError: String? = null
        private set

    /** Caps Lock / Num Lock LED state reported by the host (output report). */
    @Volatile var ledState: Int = 0
        private set

    var listener: Listener?
        get() = listeners.firstOrNull()
        set(value) {
            listeners.clear()
            if (value != null) listeners.add(value)
        }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** Keep the ACL link out of idle/sniff while the peripheral is in use. */
    var keepAliveEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ALIVE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_KEEP_ALIVE, value).apply()
            if (value) startKeepAlive() else stopKeepAlive()
        }

    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (!keepAliveEnabled) return
            if (host != null && proxy != null) {
                send(HidProtocol.REPORT_ID_MOUSE, HidProtocol.mouseReport(buttons, 0, 0, 0))
            }
            worker.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
        }
    }

    private fun startKeepAlive() {
        if (!keepAliveEnabled) return
        worker.removeCallbacks(keepAliveRunnable)
        worker.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL_MS)
    }

    private fun stopKeepAlive() {
        worker.removeCallbacks(keepAliveRunnable)
    }

    // Keyboard state: modifiers + up to six simultaneously held usages.
    private val pressedKeys = ArrayList<Int>(6)
    @Volatile private var modifiers = 0
    // Mouse state.
    @Volatile private var buttons = 0
    private var registerAttempts = 0
    @Volatile private var registering = false
    @Volatile private var pendingReRegister = false
    private var registerRetry = 0
    private var profileRetry = 0

    val isRunning: Boolean get() = running
    val isConnected: Boolean get() = host != null

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_DEVICE_NAME
        set(value) {
            val trimmed = value.trim().ifEmpty { DEFAULT_DEVICE_NAME }
            prefs.edit().putString(KEY_DEVICE_NAME, trimmed).apply()
            if (!running) return
            // The SDP record carries the name, so applying it needs a fresh
            // registerApp(). The stack finishes unregisterApp() asynchronously;
            // registering in the same tick returns false and leaves the phone
            // with no HID record at all — which is exactly what made the UI
            // report "HID 失败" and hosts fail to reconnect.
            pendingReRegister = true
            worker.post {
                if (registered) {
                    registering = false
                    unregisterLocked()
                } else {
                    registering = false
                    registerLocked()
                }
            }
            worker.postDelayed({
                if (pendingReRegister) {
                    pendingReRegister = false
                    if (running && !registered) {
                        registering = false
                        registerLocked()
                    }
                }
            }, 1500L)
        }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private fun publish(newState: State, detail: String? = null) {
        state = newState
        hostName = if (newState == State.CONNECTED) detail else null
        if (newState == State.FAILED) lastError = detail
        val snapshot = detail
        main.post { listeners.forEach { it.onStateChanged(newState, snapshot) } }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    log("BT_STATE_OFF")
                    worker.post {
                        proxy = null
                        registered = false
                        registering = false
                        profileRequested = false
                        host = null
                        registerRetry = 0
                        profileRetry = 0
                    }
                    publish(State.STOPPED)
                }
                BluetoothAdapter.STATE_ON -> {
                    log("BT_STATE_ON reacquire_profile")
                    // The stack restarts its HID service asynchronously; calling
                    // registerApp a few milliseconds after STATE_ON returns a
                    // silent false. Give it a moment, then re-acquire.
                    worker.postDelayed({
                        if (!running) return@postDelayed
                        val adapter = bluetoothManager?.adapter ?: return@postDelayed
                        if (!adapter.isEnabled) return@postDelayed
                        if (proxy != null || profileRequested) return@postDelayed
                        start()
                    }, 2000L)
                }
            }
        }
    }

    private fun registerBtReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(btStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(btStateReceiver, filter)
            }
            receiverRegistered = true
        } catch (e: Exception) {
            log("BT_RECEIVER_REGISTER_FAILED ${e.message}")
        }
    }

    private fun unregisterBtReceiver() {
        if (!receiverRegistered) return
        try {
            appContext.unregisterReceiver(btStateReceiver)
        } catch (e: Exception) {
            log("BT_RECEIVER_UNREGISTER_FAILED ${e.message}")
        }
        receiverRegistered = false
    }

    private val proxyListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val hid = service as? BluetoothHidDevice
            profileRequested = false
            log("HID_PROFILE_CONNECTED proxy=${hid != null}")
            worker.post {
                proxy = hid
                if (hid == null) {
                    publish(State.FAILED, "设备不支持 HID Device profile")
                    return@post
                }
                if (!registered) registerLocked()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            log("HID_PROFILE_DISCONNECTED")
            profileRequested = false
            worker.post {
                proxy = null
                registered = false
                publish(State.STOPPED)
                if (running) {
                    // Re-acquire; the profile service restarts with the adapter.
                    worker.postDelayed({ if (running && proxy == null) start() }, 1500L)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            publish(State.UNSUPPORTED, "无蓝牙硬件")
            return
        }
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            publish(State.UNSUPPORTED, "蓝牙不可用")
            return
        }
        // Idempotent, but a lost proxy must still trigger a re-acquire: after a
        // Bluetooth restart the old proxy is dead while `running` stays true.
        if (running && (proxy != null || profileRequested)) return
        running = true
        registerBtReceiver()
        publish(State.REGISTERING)
        profileRequested = true
        val requested = try {
            adapter.getProfileProxy(appContext, proxyListener, BluetoothProfile.HID_DEVICE)
        } catch (e: SecurityException) {
            log("HID_PROFILE_PROXY_DENIED ${e.message}")
            false
        }
        if (!requested) {
            profileRequested = false
            // Adapter off or profile unavailable: keep `running` so the
            // Bluetooth state receiver can retry once the adapter is back.
            publish(State.FAILED, "无法获取 HID profile（蓝牙未开启或系统未开放）")
            if (profileRetry < 5) {
                profileRetry++
                worker.postDelayed({
                    if (running && proxy == null && !profileRequested &&
                        bluetoothManager?.adapter?.isEnabled == true
                    ) {
                        log("HID_PROFILE_RETRY attempt=$profileRetry")
                        start()
                    }
                }, 2500L)
            }
        } else {
            profileRetry = 0
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running && proxy == null) return
        running = false
        publish(State.STOPPED)
        stopKeepAlive()
        // Unregister inline: the service calls this from onDestroy, and an
        // async post could lose the race with process teardown — a leaked
        // registration then blocks every future registerApp on this phone.
        val hid = proxy
        if (hid != null && registered) {
            try {
                hid.unregisterApp()
                log("HID_UNREGISTER_APP")
            } catch (e: Exception) {
                log("HID_UNREGISTER_FAILED ${e.message}")
            }
            registered = false
            registering = false
        }
        worker.post {
            val adapter = bluetoothManager?.adapter
            proxy = null
            host = null
            profileRequested = false
            if (hid != null && adapter != null) {
                try {
                    adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
                } catch (e: Exception) {
                    log("HID_PROFILE_CLOSE_FAILED ${e.message}")
                }
            }
        }
        unregisterBtReceiver()
    }

    @SuppressLint("MissingPermission")
    private fun registerLocked() {
        val hid = proxy ?: return
        if (registered || registering) return
        registering = true
        val sdp = BluetoothHidDeviceAppSdpSettings(
            deviceName,
            // Keep SDP strings ASCII: some Windows stacks are picky about
            // non-ASCII service attributes even though the spec allows UTF-8.
            "Marionette HID keyboard and touchpad",
            "Marionette",
            SUBCLASS_COMBO.toByte(),
            HidProtocol.reportDescriptor
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            0,
            11250,
            BluetoothHidDeviceAppQosSettings.MAX
        )
        val ok = try {
            registerAppCompat(hid, sdp, qos)
        } catch (e: Exception) {
            log("HID_REGISTER_EXCEPTION ${e.message}")
            false
        }
        log("HID_REGISTER_APP name=${deviceName} result=$ok")
        if (!ok) {
            // HyperOS/AOSP may return false while still delivering
            // onAppStatusChanged(true) asynchronously. Trust the callback; if
            // nothing arrives (typical right after a Bluetooth restart), retry
            // a few times before surfacing a failure.
            worker.postDelayed({
                if (running && !registered) {
                    registering = false
                    if (registerRetry < 3) {
                        registerRetry++
                        log("HID_REGISTER_RETRY attempt=$registerRetry")
                        worker.postDelayed({ if (running && !registered) registerLocked() }, 1500L)
                    } else {
                        publish(
                            State.FAILED,
                            "HID 注册失败：蓝牙 HID 服务未就绪，请再点一次「重置蓝牙」或重新打开 HID"
                        )
                    }
                }
            }, 2500)
        }
    }

    /**
     * Compile SDK 36 only exposes the Executor overload (API 33+). Older
     * releases (API 28..32) only have the plain four-argument form, so call it
     * reflectively there instead of raising the minimum SDK.
     */
    private fun registerAppCompat(
        hid: BluetoothHidDevice,
        sdp: BluetoothHidDeviceAppSdpSettings,
        qos: BluetoothHidDeviceAppQosSettings
    ): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            return hid.registerApp(sdp, qos, qos, java.util.concurrent.Executor { runnable -> worker.post(runnable) }, callback)
        }
        val method = BluetoothHidDevice::class.java.getMethod(
            "registerApp",
            BluetoothHidDeviceAppSdpSettings::class.java,
            BluetoothHidDeviceAppQosSettings::class.java,
            BluetoothHidDeviceAppQosSettings::class.java,
            BluetoothHidDevice.Callback::class.java
        )
        return method.invoke(hid, sdp, qos, qos, callback) as Boolean
    }

    @SuppressLint("MissingPermission")
    private fun unregisterLocked() {
        val hid = proxy ?: return
        if (!registered) return
        try {
            hid.unregisterApp()
            log("HID_UNREGISTER_APP")
        } catch (e: Exception) {
            log("HID_UNREGISTER_FAILED ${e.message}")
        }
        registered = false
        registering = false
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            registering = false
            log("HID_APP_STATUS registered=$isRegistered plugged=${pluggedDevice?.address}")
            if (!isRegistered) {
                host = null
                if (pendingReRegister && running) {
                    // Deliberate rename: wait for the stack to finish the
                    // unregister, then register with the new SDP name.
                    pendingReRegister = false
                    registerAttempts = 0
                    publish(State.REGISTERING)
                    worker.postDelayed({ if (running && !registered) registerLocked() }, 250L)
                    return
                }
                if (running && registerAttempts < 3) {
                    // The stack can drop our app (e.g. after a Bluetooth reset)
                    // while the service is still meant to be on. Re-register,
                    // but only a bounded number of times.
                    registerAttempts++
                    publish(State.REGISTERING)
                    worker.postDelayed({ if (running && !registered) registerLocked() }, 1500L)
                } else {
                    publish(State.STOPPED)
                }
                return
            }
            registerAttempts = 0
            registerRetry = 0
            pendingReRegister = false
            host = null
            publish(State.READY)
            maybeReconnectLocked(hid = proxy)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, newState: Int) {
            log("HID_CONNECTION state=$newState device=${device?.address} name=${device?.name}")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    device?.address?.let {
                        prefs.edit().putString(KEY_LAST_HOST, it).apply()
                    }
                    publish(State.CONNECTED, safeName(device))
                    startKeepAlive()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    host = null
                    stopKeepAlive()
                    publish(State.READY)
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            // Host asks for the current report (e.g. after reconnect). Windows
            // probes GET_REPORT during enumeration and stalls if the device
            // never answers, so always reply — unknown IDs get a zeroed report
            // of the requested size instead of silence.
            val reportId = id.toInt() and 0xFF
            val payload = when (reportId) {
                HidProtocol.REPORT_ID_KEYBOARD -> synchronized(pressedKeys) {
                    HidProtocol.keyboardReport(modifiers, pressedKeys.toIntArray())
                }
                HidProtocol.REPORT_ID_MOUSE -> HidProtocol.mouseReport(buttons, 0, 0, 0)
                HidProtocol.REPORT_ID_CONSUMER -> HidProtocol.consumerReport(0)
                else -> ByteArray(bufferSize.coerceIn(0, 64))
            }
            if (bufferSize <= 0) return
            val data = if (payload.size <= bufferSize) payload else payload.copyOf(bufferSize)
            try {
                proxy?.replyReport(device, type, id, data)
            } catch (e: Exception) {
                log("HID_REPLY_REPORT_FAILED ${e.message}")
            }
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            // LED output report (Caps Lock / Num Lock).
            val payload = data
            if (id.toInt() and 0xFF == HidProtocol.REPORT_ID_KEYBOARD && payload != null && payload.isNotEmpty()) {
                ledState = payload[0].toInt() and 0xFF
                log("HID_LED_STATE $ledState")
            }
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            log("HID_VIRTUAL_CABLE_UNPLUG ${device?.address}")
            host = null
            publish(State.READY)
        }
    }

    private fun safeName(device: BluetoothDevice?): String = try {
        device?.name ?: device?.address ?: "未知主机"
    } catch (e: SecurityException) {
        device?.address ?: "未知主机"
    }

    /** Reconnect to the host that paired with us before, if it is still bonded. */
    @SuppressLint("MissingPermission")
    fun connectBonded(): Boolean {
        val adapter = bluetoothManager?.adapter ?: return false
        val hid = proxy ?: return false
        val last = prefs.getString(KEY_LAST_HOST, null)
        var attempted = false
        try {
            val bonded = adapter.bondedDevices ?: emptySet()
            // Prefer the last host; only fall back to every bonded device when
            // nothing was remembered yet.
            val targets = bonded.filter { last == null || it.address == last }
            for (device in targets) {
                attempted = true
                try {
                    val ok = hid.connect(device)
                    log("HID_CONNECT device=${device.address} ok=$ok")
                } catch (e: Exception) {
                    log("HID_CONNECT_FAILED ${device.address} ${e.message}")
                }
            }
        } catch (e: SecurityException) {
            log("HID_BONDED_DENIED ${e.message}")
        }
        return attempted
    }

    @SuppressLint("MissingPermission")
    private fun maybeReconnectLocked(hid: BluetoothHidDevice?) {
        if (hid == null) return
        val last = prefs.getString(KEY_LAST_HOST, null) ?: return
        try {
            val device = bluetoothManager?.adapter?.getRemoteDevice(last) ?: return
            if (bluetoothManager?.adapter?.bondedDevices?.any { it.address == last } != true) return
            val ok = hid.connect(device)
            log("HID_AUTO_RECONNECT device=$last ok=$ok")
        } catch (e: Exception) {
            log("HID_AUTO_RECONNECT_FAILED ${e.message}")
        }
    }

    /** Drop the active host so another machine (e.g. Windows) can take over. */
    @SuppressLint("MissingPermission")
    fun disconnectHost(): Boolean {
        val hid = proxy ?: return false
        val device = host ?: return false
        return try {
            val ok = hid.disconnect(device)
            log("HID_DISCONNECT host=${device.address} ok=$ok")
            ok
        } catch (e: Exception) {
            log("HID_DISCONNECT_FAILED ${e.message}")
            false
        }
    }

    /**
     * Clear a wedged HID registration.
     *
     * The Bluetooth stack holds exactly one HID device app. If a previous app
     * process died without unregistering (force-stop, crash), every later
     * registerApp fails with "another app is registered" until the adapter is
     * restarted. This device is rooted (KernelSU), so `svc bluetooth` works.
     */
    fun resetBluetoothStack(): Boolean {
        fun run(command: String): Boolean = try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
            val code = process.exitValue()
            log("BT_RESET command=$command exit=$code")
            code == 0
        } catch (e: Exception) {
            log("BT_RESET_FAILED command=$command ${e.message}")
            false
        }

        val off = run("svc bluetooth disable")
        Thread.sleep(2500)
        val on = run("svc bluetooth enable")
        return off && on
    }

    // ---- Input injection -------------------------------------------------

    private fun sendKeyboardLocked() {
        val data = HidProtocol.keyboardReport(modifiers, synchronized(pressedKeys) { pressedKeys.toIntArray() })
        send(HidProtocol.REPORT_ID_KEYBOARD, data)
    }

    private fun send(reportId: Int, data: ByteArray) {
        val hid = proxy ?: return
        val device = host ?: return
        try {
            val ok = hid.sendReport(device, reportId, data)
            if (!ok) log("HID_SEND_REJECTED report=$reportId")
        } catch (e: Exception) {
            log("HID_SEND_FAILED report=$reportId ${e.message}")
        }
    }

    /**
     * Send a complete keyboard state. The on-screen keyboard owns the chord
     * state (multiple pointers, held modifiers), so it hands us the full set
     * rather than individual taps: holding a key keeps it in the report and the
     * host does the repeating.
     */
    fun sendKeyboardState(modifiers: Int, usages: IntArray) {
        worker.post {
            synchronized(pressedKeys) {
                pressedKeys.clear()
                usages.take(6).forEach { if (!pressedKeys.contains(it)) pressedKeys.add(it) }
            }
            this.modifiers = modifiers and 0xFF
            sendKeyboardLocked()
        }
    }

    fun keyDown(usage: Int, withModifiers: Int = 0) {
        worker.post {
            synchronized(pressedKeys) {
                if (pressedKeys.size < 6 && !pressedKeys.contains(usage)) pressedKeys.add(usage)
            }
            modifiers = withModifiers
            sendKeyboardLocked()
        }
    }

    fun keyUp(usage: Int, withModifiers: Int = 0) {
        worker.post {
            synchronized(pressedKeys) { pressedKeys.remove(usage) }
            modifiers = withModifiers
            sendKeyboardLocked()
        }
    }

    /** Tap a key: press then release shortly after, so the host sees a click. */
    fun tap(usage: Int, withModifiers: Int = 0, holdMs: Long = 40L) {
        keyDown(usage, withModifiers)
        worker.postDelayed({ keyUp(usage, 0) }, holdMs)
    }

    fun typeStroke(stroke: HidProtocol.Stroke) {
        tap(stroke.usage, if (stroke.shift) HidProtocol.MOD_LEFT_SHIFT else 0)
    }

    fun releaseAll() {
        worker.post {
            synchronized(pressedKeys) { pressedKeys.clear() }
            modifiers = 0
            buttons = 0
            sendKeyboardLocked()
            sendMouse(0, 0, 0)
        }
    }

    fun mouseMove(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        worker.post { sendMouse(dx, dy, 0) }
    }

    fun mouseScroll(steps: Int) {
        if (steps == 0) return
        worker.post { sendMouse(0, 0, steps) }
    }

    fun mouseButton(button: Int, down: Boolean) {
        worker.post {
            buttons = if (down) buttons or button else buttons and button.inv()
            sendMouse(0, 0, 0)
        }
    }

    fun click(button: Int) {
        mouseButton(button, true)
        worker.postDelayed({ mouseButton(button, false) }, 30L)
    }

    /** Split large deltas so each report stays within the -127..127 range. */
    private fun sendMouse(dx: Int, dy: Int, wheel: Int) {
        var x = dx
        var y = dy
        var w = wheel
        var guard = 0
        do {
            val cx = x.coerceIn(-127, 127)
            val cy = y.coerceIn(-127, 127)
            val cw = w.coerceIn(-127, 127)
            send(HidProtocol.REPORT_ID_MOUSE, HidProtocol.mouseReport(buttons, cx, cy, cw))
            x -= cx
            y -= cy
            w -= cw
            guard++
        } while ((x != 0 || y != 0 || w != 0) && guard < 32)
    }

    fun sendConsumer(usage: Int, holdMs: Long = 24L) {
        worker.post { send(HidProtocol.REPORT_ID_CONSUMER, HidProtocol.consumerReport(usage)) }
        worker.postDelayed(
            { send(HidProtocol.REPORT_ID_CONSUMER, HidProtocol.consumerReport(0)) },
            holdMs
        )
    }

    fun shutdown() {
        stop()
        try {
            workerThread.quitSafely()
        } catch (e: Exception) {
            Log.i(TAG, "HID_THREAD_QUIT_FAILED ${e.message}")
        }
    }
}
