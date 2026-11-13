package dev.local.mihotspot.peripheral.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.local.mihotspot.R
import java.util.concurrent.Executors

enum class UsbCameraSource { OFF, FRONT, BACK, SCREEN }

/**
 * Foreground owner of USB peripheral mode. Provisioning touches configfs, so it
 * runs on a worker thread; the service itself only publishes state and owns the
 * HID, UVC and UAC2 bridges.
 */
class UsbPeripheralService : Service() {

    companion object {
        private const val TAG = "MiHotspotUsb"
        private const val CHANNEL = "usb_peripheral"
        private const val NOTIFICATION_ID = 4210
        /** Fallback cadence for hosts whose cable events never reach us. */
        private const val HOST_POLL_MS = 4000L
        /** While nothing is attached, look harder: this is what the user is waiting for. */
        private const val HOST_SEARCH_MS = 1500L
        /** Cable events arrive in pairs when the connector is loose. */
        private const val HOST_DEBOUNCE_MS = 300L
        /*
         * UsbManager.ACTION_USB_STATE / USB_CONNECTED are hidden from the public
         * SDK (they compile to the literals below), so spell them out.
         */
        private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        private const val USB_CONNECTED = "connected"

        const val ACTION_START = "dev.local.mihotspot.usb.START"
        const val ACTION_STOP = "dev.local.mihotspot.usb.STOP"
        /** Re-own a session whose gadget/daemon survived a process restart. */
        const val ACTION_ATTACH = "dev.local.mihotspot.usb.ATTACH"
        /** Swap the capture source without touching configfs or the daemon. */
        const val ACTION_SWITCH_CAMERA = "dev.local.mihotspot.usb.SWITCH_CAMERA"
        const val ACTION_STATE = "dev.local.mihotspot.usb.STATE"
        const val EXTRA_CAMERA_SOURCE = "camera_source"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_SCREEN_RESULT = "screen_result"
        const val EXTRA_SCREEN_DATA = "screen_data"
        const val EXTRA_STATE = "state"
        const val EXTRA_DETAIL = "detail"
        /** Short, user-facing explanation of why the host is missing. */
        const val EXTRA_HINT = "hint"

        @Volatile var running: Boolean = false
            private set

        @Volatile internal var servicePresent = false
            private set

        /** What the live session is actually streaming; decides in-place swaps. */
        @Volatile private var activeCamera = UsbCameraSource.OFF
        @Volatile private var activeAudio = false

        fun streamingCamera(): UsbCameraSource = activeCamera
        internal fun streamingAudio(): Boolean = activeAudio

        fun start(
            context: Context,
            camera: UsbCameraSource,
            audio: Boolean,
            screenResultCode: Int = 0,
            screenData: Intent? = null
        ) {
            // Tells UsbPeripheralStatus.verify() that an off state is not a
            // stale record but provisioning in flight.
            UsbPeripheralStatus.markStartRequested()
            val intent = Intent(context, UsbPeripheralService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CAMERA_SOURCE, camera.name)
                putExtra(EXTRA_AUDIO, audio)
                putExtra(EXTRA_SCREEN_RESULT, screenResultCode)
                if (screenData != null) putExtra(EXTRA_SCREEN_DATA, screenData)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Adopt an already-provisioned session (daemon + HID node still alive)
         * without touching configfs. Used after a process restart, when the
         * gadget outlived the service that created it.
         */
        fun attach(context: Context) {
            val intent = Intent(context, UsbPeripheralService::class.java)
                .setAction(ACTION_ATTACH)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.i(TAG, "USB_ATTACH_FAILED ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsbPeripheralService::class.java))
        }

        /** Stop through the serialized session path, including its cable safety check. */
        internal fun stopFromUi(context: Context) {
            context.startForegroundService(Intent(context, UsbPeripheralService::class.java).setAction(ACTION_STOP))
        }

        /**
         * Change the capture source of a running session. The UVC function stays
         * linked and the daemon keeps running; only the app-side pipeline swaps.
         * Re-provisioning for this used to unbind/rebind the gadget, and this
         * vendor kernel panics on that path often enough that switching between
         * front/back/screen could reboot the phone.
         */
        fun switchCamera(
            context: Context,
            camera: UsbCameraSource,
            screenResultCode: Int = 0,
            screenData: Intent? = null
        ) {
            val intent = Intent(context, UsbPeripheralService::class.java).apply {
                action = ACTION_SWITCH_CAMERA
                putExtra(EXTRA_CAMERA_SOURCE, camera.name)
                putExtra(EXTRA_SCREEN_RESULT, screenResultCode)
                if (screenData != null) putExtra(EXTRA_SCREEN_DATA, screenData)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.i(TAG, "USB_SWITCH_FAILED ${e.message}")
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var controller: UsbGadgetController
    private var audioBridge: UsbAudioBridge? = null
    private var videoLink: UsbVideoLink? = null
    private var videoSource: UsbVideoSource? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Last published host-attachment value; null means "not known yet". */
    @Volatile private var hostAttached: Boolean? = null

    /** Cable state seen in UsbManager.ACTION_USB_STATE (null = never seen). */
    @Volatile private var cableConnected: Boolean? = null

    /** Drops superseded debounced cable events. */
    private val attachGeneration = java.util.concurrent.atomic.AtomicInteger()

    /** Consecutive failed probes; one root-shell hiccup must not kill the session. */
    private var lostStreak = 0

    /** Throttles the probe heartbeat so logcat stays readable. */
    private var lastProbeLogAt = 0L

    /** Rapid switch flips are the fastest way to a kernel panic; space them out. */
    private var lastProvisionAt = 0L

    /** Last user-facing explanation, so a VBUS-only change still reaches the UI. */
    private var lastHint: String? = null

    /**
     * A start request that was refused because the host already holds the
     * gadget. Provisioning in that state unbinds the vendor gadget under a live
     * host and has both panicked this kernel and left the port unable to attach
     * until a reboot, so wait for the cable to come out instead.
     */
    private data class PendingStart(
        val camera: UsbCameraSource,
        val audio: Boolean,
        val screenResult: Int,
        val screenData: Intent?
    )

    @Volatile private var pendingStart: PendingStart? = null

    /** True while a provisioning/teardown root script is in flight. */
    @Volatile private var provisioning = false

    /** True once this service actually owns the configfs composition. */
    @Volatile private var provisioned = false

    /** Consecutive probes that saw no host while we were provisioned. */
    private var detachStreak = 0

    /** Last start parameters, replayed after handing the port back to the vendor. */
    @Volatile private var lastCamera = UsbCameraSource.OFF
    @Volatile private var lastAudio = false
    private var lastScreenResult = 0
    private var lastScreenData: Intent? = null

    /** Guards against a restore/takeover loop turning into a rebind storm. */
    private var restoreAttempts = 0
    private var restoreFirstAt = 0L

    /** How long the vendor gadget has been continuously attached after a restore. */
    private var vendorAttachedSince = 0L
    private var vendorAttachStreak = 0

    /** When we last handed the port back to the vendor; only then is the HAL busy. */
    @Volatile private var vendorRestoredAt = 0L

    /** One free retry for a bind that lost a race with the vendor HAL. */
    private var wedgedRetryUsed = false

    /**
     * When the takeover finished. The composite (especially with UVC's
     * bind_deactivated) needs a few seconds to reach "configured", and treating
     * that window as "host gone" used to tear a healthy start back down.
     */
    private var provisionedAt = 0L

    /** Stop requested while a host was attached; runs once the cable is out. */
    @Volatile private var pendingStop = false

    /** Set in onDestroy so an in-flight provisioning task cannot re-publish "running". */
    @Volatile private var destroyed = false

    /** False when this service never owned the configfs composition (attach probe only). */
    @Volatile private var teardownOnDestroy = true

    override fun onCreate() {
        super.onCreate()
        controller = UsbGadgetController(this)
        servicePresent = true
        ensureChannel()
        registerCableReceiver()
        startForegroundCompat(buildNotification("正在准备 USB 外设…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ATTACH) {
            attachSession()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SWITCH_CAMERA) {
            val camera = runCatching {
                UsbCameraSource.valueOf(
                    intent.getStringExtra(EXTRA_CAMERA_SOURCE) ?: UsbCameraSource.OFF.name
                )
            }.getOrDefault(UsbCameraSource.OFF)
            val screenResult = intent.getIntExtra(EXTRA_SCREEN_RESULT, 0)
            @Suppress("DEPRECATION")
            val screenData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_SCREEN_DATA, Intent::class.java)
            } else {
                intent.getParcelableExtra(EXTRA_SCREEN_DATA)
            }
            executor.execute { switchCameraInPlace(camera, screenResult, screenData) }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) {
            // A start supersedes a stop that was waiting for the cable to come out.
            pendingStop = false
            mainHandler.removeCallbacks(pendingStopPoll)
            if (provisioning) {
                Log.i(TAG, "USB_START_IGNORED provisioning in flight")
                return START_NOT_STICKY
            }
            val camera = runCatching {
                UsbCameraSource.valueOf(
                    intent.getStringExtra(EXTRA_CAMERA_SOURCE) ?: UsbCameraSource.OFF.name
                )
            }.getOrDefault(UsbCameraSource.OFF)
            val audio = intent.getBooleanExtra(EXTRA_AUDIO, false)
            val screenResult = intent.getIntExtra(EXTRA_SCREEN_RESULT, 0)
            @Suppress("DEPRECATION")
            val screenData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_SCREEN_DATA, Intent::class.java)
            } else {
                intent.getParcelableExtra(EXTRA_SCREEN_DATA)
            }
            startSession(camera, audio, screenResult, screenData)
        }
        return START_NOT_STICKY
    }

    private fun startSession(
        camera: UsbCameraSource,
        audio: Boolean,
        screenResult: Int,
        screenData: Intent?
    ) {
        val effectiveCamera = if (camera == UsbCameraSource.SCREEN && screenData == null) {
            UsbCameraSource.OFF
        } else {
            camera
        }
        /*
         * Persist what actually came up, not what was requested. A SCREEN request
         * without MediaProjection consent degrades to OFF, and storing SCREEN made
         * every later start silently come up with no camera while the UI still
         * claimed it was selected.
         */
        UsbSession.prefs(this).edit()
            .putString(UsbSession.KEY_CAMERA_SOURCE, effectiveCamera.name)
            .putBoolean(UsbSession.KEY_AUDIO, audio)
            .apply()
        executor.execute {
            provisioning = true
            try {
                /*
                 * Binding while no host is attached never attaches on this
                 * vendor kernel (the PHY wakes on VBUS but the gadget never
                 * connects). Wait until the host is actually on the port, then
                 * take the gadget over immediately - that is the order that has
                 * always enumerated.
                 */
                val pre = try {
                    controller.status()
                } catch (e: Throwable) {
                    null
                }
                if (pre == null || !pre.bound) {
                    pendingStart = PendingStart(effectiveCamera, audio, screenResult, screenData)
                    running = false
                    teardownOnDestroy = false
                    updateNotification("插上数据线后自动开启")
                    publish(
                        "armed",
                        pre?.raw ?: "no host",
                        hostAttached = false,
                        hint = "等主机接上后自动接管，无需再点开关"
                    )
                    Log.i(TAG, "USB_START_PENDING_HOST state=${pre?.state}")
                    mainHandler.removeCallbacks(pendingPoll)
                    mainHandler.postDelayed(pendingPoll, HOST_SEARCH_MS)
                    return@execute
                }
                /*
                 * Rapid flips are the fastest route to a panic, so space real
                 * provisioning runs out. Never drop the queued request here:
                 * pendingPoll clears it before calling us, and dropping left the
                 * UI stuck on "waiting" with no host ever coming up.
                 */
                val since = SystemClock.elapsedRealtime() - lastProvisionAt
                if (lastProvisionAt != 0L && since < 4000L) {
                    pendingStart = PendingStart(effectiveCamera, audio, screenResult, screenData)
                    mainHandler.removeCallbacks(pendingPoll)
                    mainHandler.postDelayed(pendingPoll, 4000L - since)
                    Log.i(TAG, "USB_START_RATE_LIMITED since=${since}ms re-armed")
                    return@execute
                }
                lastProvisionAt = SystemClock.elapsedRealtime()
                val token = UsbSession.token(this)
                val status = controller.start(
                    token,
                    UsbGadgetController.Options(
                        camera = effectiveCamera != UsbCameraSource.OFF,
                        audio = audio
                    )
                )
            if (status.preflightAttached) {
                /*
                 * The host is already holding the gadget. Re-provisioning here
                 * unbinds it under a live host: that has panicked this kernel and
                 * wedged the Type-C port until a reboot. Keep the request and
                 * provision as soon as the cable is out.
                 */
                pendingStart = PendingStart(effectiveCamera, audio, screenResult, screenData)
                running = false
                teardownOnDestroy = false
                updateNotification("数据线已插着 · 拔线后自动开启")
                publish(
                    "armed",
                    status.raw,
                    hostAttached = true,
                    hint = "数据线已插着 · 拔掉后自动开启，插回即可使用"
                )
                Log.i(TAG, "USB_START_PENDING_UNPLUG ${status.raw}")
                mainHandler.removeCallbacks(pendingPoll)
                mainHandler.postDelayed(pendingPoll, HOST_SEARCH_MS)
                return@execute
            }
            if (status.portWedged) {
                /*
                 * A bind that loses the race with the vendor HAL also shows up as
                 * an empty UDC right after BIND=0. Give it one delayed retry
                 * before declaring the port dead and stopping the session.
                 */
                if (!wedgedRetryUsed) {
                    wedgedRetryUsed = true
                    Log.i(TAG, "USB_PORT_WEDGED_RETRY ${status.raw}")
                    mainHandler.postDelayed({
                        startSession(effectiveCamera, audio, screenResult, screenData)
                    }, 3000L)
                    return@execute
                }
                wedgedRetryUsed = false
                /*
                 * VBUS is live but the controller never attached, and the script's
                 * bounded rebind did not help. Hand the port back to the vendor
                 * stack so wired adb/USB is not left dead until a reboot.
                 */
                controller.stop()
                running = false
                teardownOnDestroy = false
                UsbSession.setEnabled(this, false)
                UsbHub.link(this).stop()
                updateNotification("端口异常 · 已退回厂商 USB")
                publish(
                    "error",
                    status.raw,
                    hostAttached = false,
                    hint = "端口未附着 · 已退回厂商 USB，请拔线后重试或重启手机"
                )
                Log.i(TAG, "USB_PORT_WEDGED ${status.raw}")
                stopSelf()
                return@execute
            }
            if (status.ready || status.bound) {
                if (destroyed) return@execute
                teardownOnDestroy = true
                provisioned = true
                provisionedAt = SystemClock.elapsedRealtime()
                lastCamera = effectiveCamera
                lastAudio = audio
                lastScreenResult = screenResult
                lastScreenData = screenData
                detachStreak = 0
                UsbSession.setEnabled(this, true)
                UsbHub.link(this).start()
                running = true
                hostAttached = status.bound
                startHostWatch()

                audioBridge?.stop()
                audioBridge = null
                videoSource?.stop()
                videoSource = null
                videoLink?.stop()
                videoLink = null

                updateForegroundTypes(effectiveCamera, audio)
                activeCamera = effectiveCamera
                activeAudio = audio
                if (effectiveCamera != UsbCameraSource.OFF) {
                    startVideo(effectiveCamera, token, screenResult, screenData)
                }
                if (audio) {
                    audioBridge = UsbAudioBridge(token).also { it.start(this, speaker = true) }
                }

                updateNotification(
                    if (!status.bound) {
                        "等待主机连接 · 插入数据线后自动生效"
                    } else when (effectiveCamera) {
                        UsbCameraSource.OFF -> "USB 键鼠已就绪 · 点按进入设置"
                        else -> "USB 外设运行中 · 摄像头已开启"
                    }
                )
                publish(
                    if (status.bound) "ready" else "waiting",
                    status.raw,
                    hostAttached = status.bound
                )
                Log.i(
                    TAG,
                    "USB_SESSION_READY state=${status.state} hid=${status.hidNode} " +
                        "camera=$effectiveCamera audio=$audio"
                )
            } else {
                running = false
                UsbSession.setEnabled(this, false)
                updateNotification("USB 外设绑定失败")
                publish("error", status.raw, hostAttached = false)
                Log.i(TAG, "USB_SESSION_FAILED ${status.raw}")
                stopSelf()
            }
            } finally {
                provisioning = false
            }
        }
    }

    /**
     * Adopt a session whose gadget is still bound and whose daemon is still
     * running (the process was killed, the root-side session was not). Only
     * reads state: re-provisioning would unbind the gadget, which can panic the
     * vendor kernel, and it would interrupt a working host connection.
     */
    private fun attachSession() {
        executor.execute {
            val status = controller.status()
            if (destroyed) return@execute
            if (!status.ready) {
                running = false
                UsbSession.setEnabled(this, false)
                // Nothing of ours to tear down: the session expired before we
                // could adopt it, and unbinding a gadget can wedge the vendor
                // kernel, so never write configfs from this path.
                teardownOnDestroy = false
                publish("error", "USB 会话已结束：${status.raw}", hostAttached = false)
                Log.i(TAG, "USB_ATTACH_FAILED ${status.raw}")
                stopSelf()
                return@execute
            }
            UsbSession.setEnabled(this, true)
            UsbHub.link(this).start()
            running = true
            hostAttached = status.bound
            /*
             * Adopting means this service now owns the composition - but only if
             * the gadget is still bound. After a physical unplug with UVC active
             * the kernel unbinds the whole composite on its own, and claiming
             * ownership of a gadget that is already gone is what left the UI
             * reporting a session that no longer existed.
             */
            lastCamera = runCatching {
                UsbCameraSource.valueOf(
                    UsbSession.prefs(this).getString(
                        UsbSession.KEY_CAMERA_SOURCE, UsbCameraSource.OFF.name
                    ) ?: UsbCameraSource.OFF.name
                )
            }.getOrDefault(UsbCameraSource.OFF)
            lastAudio = UsbSession.prefs(this).getBoolean(UsbSession.KEY_AUDIO, false)
            provisioned = status.gadgetBound
            if (provisioned) {
                provisionedAt = SystemClock.elapsedRealtime()
                teardownOnDestroy = true
            }
            startHostWatch()
            // The video/audio pipelines cannot be restored without re-running the
            // provisioning script, so say so instead of leaving a silent black
            // camera on the host.
            val cameraWanted = UsbSession.prefs(this).getString(
                UsbSession.KEY_CAMERA_SOURCE, UsbCameraSource.OFF.name
            ) != UsbCameraSource.OFF.name
            val audioWanted = UsbSession.prefs(this).getBoolean(UsbSession.KEY_AUDIO, false)
            updateNotification(
                when {
                    cameraWanted || audioWanted -> "键鼠已接管 · 摄像头/音频需重新开启"
                    status.bound -> "USB 键鼠已就绪 · 点按进入设置"
                    else -> "等待主机连接 · 插入数据线后自动生效"
                }
            )
            publish(
                if (status.bound) "ready" else "waiting",
                status.raw,
                hostAttached = status.bound
            )
            Log.i(TAG, "USB_SESSION_ATTACHED state=${status.state} hid=${status.hidNode}")
        }
    }

    private fun startVideo(
        source: UsbCameraSource,
        token: String,
        screenResult: Int,
        screenData: Intent?
    ) {
        val link = videoLink ?: UsbVideoLink(token).also { it.start() }
        videoLink = link
        /*
         * Reuse one source for the whole session. Its worker serialises "release
         * the old camera, then open the new one"; a fresh instance per switch
         * raced the previous camera's asynchronous release, failed with
         * "camera in use", and left the host frozen on the last frame.
         */
        val video = videoSource ?: UsbVideoSource(this) { data, width, height, format, pts ->
            link.send(data, width, height, format, pts)
        }.also { videoSource = it }
        // The daemon reports the frame the host committed; capture and encode
        // have to match it or the picture is the wrong size on the host.
        link.onMode = { width, height -> video.setTargetSize(width, height) }
        video.onError = { message ->
            Log.i(TAG, "USB_CAMERA_ERROR $message")
            publish("camera_error", message)
        }
        when (source) {
            UsbCameraSource.FRONT -> video.startCamera(UsbVideoSource.Source.FRONT)
            UsbCameraSource.BACK -> video.startCamera(UsbVideoSource.Source.BACK)
            UsbCameraSource.SCREEN -> if (screenData != null) {
                video.startScreen(screenResult, screenData)
            } else {
                Log.i(TAG, "USB_CAMERA_SCREEN_NO_CONSENT")
                video.stop()
                return
            }
            UsbCameraSource.OFF -> {
                video.stop()
                return
            }
        }
    }

    /**
     * Swap the capture source of a live session. Nothing here touches configfs,
     * the daemon or the gadget: the UVC function stays linked and only the
     * app-side pipeline changes. Re-provisioning on a source change used to
     * unbind/rebind the gadget, and this vendor kernel panics on that path often
     * enough that switching front/back/screen could reboot the phone.
     */
    private fun switchCameraInPlace(
        camera: UsbCameraSource,
        screenResult: Int,
        screenData: Intent?
    ) {
        if (!running || destroyed) {
            Log.i(TAG, "USB_SWITCH_SKIPPED running=$running destroyed=$destroyed")
            return
        }
        val source = if (camera == UsbCameraSource.SCREEN && screenData == null) {
            UsbCameraSource.OFF
        } else {
            camera
        }
        if (source == UsbCameraSource.OFF) {
            videoSource?.stop()
        }
        /*
         * Keep the source and the daemon link: starting the new capture on the
         * same instance makes its worker release the old camera before opening
         * the new one. The link stays up so the host never sees a gap.
         */
        activeCamera = source
        if (source != UsbCameraSource.OFF) {
            updateForegroundTypes(source, activeAudio)
            startVideo(source, UsbSession.token(this), screenResult, screenData)
        }
        updateNotification(
            if (source == UsbCameraSource.OFF) "USB 键鼠已就绪 · 点按进入设置"
            else "USB 外设运行中 · 摄像头已开启"
        )
        publish(
            if (hostAttached == true) "ready" else "waiting",
            "camera=$source",
            hostAttached = null
        )
        Log.i(TAG, "USB_CAMERA_SWITCHED source=$source")
    }

    private fun updateForegroundTypes(camera: UsbCameraSource, audio: Boolean) {
        if (Build.VERSION.SDK_INT < 29) return
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (camera == UsbCameraSource.SCREEN) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        if (camera == UsbCameraSource.FRONT || camera == UsbCameraSource.BACK) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (audio) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification("USB 外设运行中"), types)
        } catch (e: Exception) {
            Log.i(TAG, "USB_FGS_TYPE_FAILED ${e.message}")
            startForegroundCompat(buildNotification("USB 外设运行中"))
        }
    }

    /**
     * Pulling the cable does not unbind the gadget: the UDC merely drops from
     * `configured` to `not attached`, so nothing in configfs changes and no
     * existing code re-published state. The UI therefore stayed on "ready"
     * forever after an unplug.
     */
    private val cableReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_STATE) return
            val connected = intent.getBooleanExtra(USB_CONNECTED, false)
            if (cableConnected == connected) return
            cableConnected = connected
            Log.i(TAG, "USB_CABLE_STATE connected=$connected")
            if (!running || destroyed) return
            scheduleHostState(connected)
        }
    }

    private val hostPoll = object : Runnable {
        override fun run() {
            if (!running || destroyed) return
            probeHostAttachment()
            scheduleNextProbe()
        }
    }

    /** Waits for the cable to come out before provisioning a refused start. */
    private val pendingPoll: Runnable = object : Runnable {
        override fun run() {
            if (destroyed || pendingStart == null) return
            executor.execute {
                if (destroyed || pendingStart == null) return@execute
                val status: UsbGadgetController.Status? = try {
                    controller.status()
                } catch (e: Throwable) {
                    Log.i(TAG, "USB_PENDING_PROBE_FAILED ${e.javaClass.simpleName}")
                    null
                }
                if (status == null || !status.bound) {
                    vendorAttachStreak = 0
                    vendorAttachedSince = 0L
                    mainHandler.postDelayed(pendingPoll, HOST_SEARCH_MS)
                    return@execute
                }
                /*
                 * The vendor HAL is still rewriting configfs right after a
                 * restore; taking over inside that window loses the bind race and
                 * looks like a wedged port. Require a stably attached vendor
                 * gadget before provisioning.
                 */
                val now2 = SystemClock.elapsedRealtime()
                if (vendorAttachedSince == 0L) vendorAttachedSince = now2
                vendorAttachStreak++
                /*
                 * Only the restore path races the vendor HAL's own rebind; a real
                 * plug arrives with the HAL idle, so one probe is enough there.
                 * This keeps the extra settle time where it protects us and
                 * removes it from the common plug-in path.
                 */
                val freshRestore = vendorRestoredAt != 0L && now2 - vendorRestoredAt < 5000L
                val needsSettle = freshRestore && now2 - vendorAttachedSince < 1800L
                if (vendorAttachStreak < 2 || needsSettle) {
                    mainHandler.postDelayed(pendingPoll, HOST_SEARCH_MS)
                    return@execute
                }
                val pending = pendingStart ?: return@execute
                pendingStart = null
                Log.i(TAG, "USB_START_RESUME_AFTER_UNPLUG")
                startSession(pending.camera, pending.audio, pending.screenResult, pending.screenData)
            }
        }
    }

    /**
     * ACTION_USB_STATE never reaches us on this ROM (no USB_CABLE_STATE line has
     * ever appeared in logcat), so the root probe is the only source of truth.
     * Poll fast while the host is missing and back off once it is confirmed.
     */
    private fun scheduleNextProbe() {
        if (!running || destroyed) return
        mainHandler.removeCallbacks(hostPoll)
        val delay = if (hostAttached == true) HOST_POLL_MS else HOST_SEARCH_MS
        mainHandler.postDelayed(hostPoll, delay)
    }

    private fun registerCableReceiver() {
        val filter = IntentFilter(ACTION_USB_STATE)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(cableReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(cableReceiver, filter)
            }
        } catch (e: Exception) {
            Log.i(TAG, "USB_CABLE_RECEIVER_FAILED ${e.message}")
        }
    }

    private fun startHostWatch() {
        scheduleNextProbe()
    }

    /**
     * A loose connector produces connect/disconnect pairs within milliseconds.
     * Act only once the newest event has been quiet for a moment, and drop the
     * stale ones so two overlapping probes cannot fight over the session state.
     */
    private fun scheduleHostState(connected: Boolean) {
        val generation = attachGeneration.incrementAndGet()
        mainHandler.postDelayed({
            if (generation != attachGeneration.get() || !running || destroyed) {
                return@postDelayed
            }
            if (connected) probeHostAttachment() else markHostDetached()
        }, HOST_DEBOUNCE_MS)
    }

    private fun markHostDetached() {
        if (hostAttached == false) return
        hostAttached = false
        // Release before the link goes idle: otherwise the host keeps whatever
        // key was held when the cable came out and auto-repeats it.
        UsbHub.link(this).releaseAll()
        updateNotification("等待主机连接 · 插入数据线后自动生效")
        publish("waiting", "主机已断开", hostAttached = false)
    }

    /** Re-reads the gadget state (root shell, so it runs off the main thread). */
    private fun probeHostAttachment() {
        if (!running || destroyed) return
        executor.execute {
            if (!running || destroyed) return@execute
            val status = try {
                controller.status()
            } catch (e: Throwable) {
                Log.i(TAG, "USB_HOST_PROBE_FAILED ${e.javaClass.simpleName}: ${e.message}")
                return@execute
            }
            if (!status.ready) {
                if (++lostStreak < 2) {
                    Log.i(TAG, "USB_HOST_PROBE_UNREADY ${status.raw}")
                    return@execute
                }
                Log.i(TAG, "USB_SESSION_LOST ${status.raw}")
                running = false
                hostAttached = false
                mainHandler.removeCallbacks(hostPoll)
                UsbHub.link(this).stop()
                UsbSession.setEnabled(this, false)
                // Nothing of ours is left to unbind, and re-running the script
                // on a half-dead gadget is exactly what panics this kernel.
                teardownOnDestroy = false
                publish("error", "USB 会话已结束：${status.raw}", hostAttached = false)
                stopSelf()
                return@execute
            }
            val attached = status.bound
            lostStreak = 0
            /*
             * Our gadget cannot attach when it was bound while the cable was
             * out, and soft_connect does not rescue it (verified on device: the
             * vendor gadget attaches instantly, ours never does). So on detach,
             * hand the port back to the vendor gadget: it is the only reliable
             * "host plugged in" detector, and the next attach triggers a fresh
             * takeover - the order that has always worked.
             */
            if (!attached && (provisioned || !status.gadgetBound) && !provisioning) {
                val settled = SystemClock.elapsedRealtime() - provisionedAt
                if (settled < 3500L) {
                    Log.i(TAG, "USB_DETACH_IGNORED_SETTLING settled=${settled}ms")
                    return@execute
                }
                if (++detachStreak < 2) return@execute
                restoreVendorAndArm()
                return@execute
            }
            detachStreak = 0
            if (SystemClock.uptimeMillis() - lastProbeLogAt > 15_000) {
                lastProbeLogAt = SystemClock.uptimeMillis()
                Log.i(
                    TAG,
                    "USB_HOST_PROBE state=${status.state} bound=$attached " +
                        "ready=${status.ready} vbus=${status.vbusPresent} cable=$cableConnected"
                )
            }
            /*
             * A charging cable and no cable look identical to the UDC, so VBUS
             * is what separates "nothing plugged in" from "power only, no data
             * link" - the case behind "I plugged it in and nothing happened".
             */
            val hint = when {
                attached -> null
                status.vbusPresent ->
                    "检测到数据线供电，但没有 USB 数据连接 · 换一根数据线或换一个 USB 口"
                else -> "未检测到数据线 · 插入数据线后自动生效"
            }
            if (attached == hostAttached && hint == lastHint) return@execute
            hostAttached = attached
            lastHint = hint
            updateNotification(
                if (attached) {
                    "USB 键鼠已就绪 · 点按进入设置"
                } else {
                    "等待主机连接 · 插入数据线后自动生效"
                }
            )
            publish(if (attached) "ready" else "waiting", status.raw, hostAttached = attached)
            Log.i(TAG, "USB_HOST_ATTACH changed=$attached state=${status.state}")
        }
    }

    /** Waits for the cable to come out before tearing the gadget down. */
    private val pendingStopPoll: Runnable = object : Runnable {
        override fun run() {
            if (destroyed || !pendingStop) return
            executor.execute {
                if (destroyed || !pendingStop) return@execute
                val status: UsbGadgetController.Status? = try {
                    controller.status()
                } catch (e: Throwable) {
                    Log.i(TAG, "USB_PENDING_STOP_PROBE_FAILED ${e.javaClass.simpleName}")
                    null
                }
                if (status == null || status.bound) {
                    mainHandler.postDelayed(pendingStopPoll, HOST_SEARCH_MS)
                    return@execute
                }
                Log.i(TAG, "USB_STOP_RESUME_AFTER_UNPLUG")
                pendingStop = false
                doStopNow()
            }
        }
    }

    /** Public stop entry: serialises against a provisioning run. */
    private fun requestStop() {
        UsbSession.setEnabled(this, false)
        publish("stopping", "正在停止 USB 外设", hint = "正在恢复普通 USB 模式")
        pendingStart = null
        mainHandler.removeCallbacks(pendingPoll)
        if (provisioning) {
            Log.i(TAG, "USB_STOP_DEFERRED provisioning")
            mainHandler.postDelayed({ requestStop() }, 800)
            return
        }
        executor.execute { doStopNow() }
    }

    /**
     * Runs stop.sh. While a host holds the gadget the script refuses, because
     * unbinding there frees the UVC function before its deactivate percpu_ref
     * has left RCU - the resulting Oops is what rebooted the phone.
     */
    private fun doStopNow() {
        if (provisioning) {
            mainHandler.postDelayed({ requestStop() }, 800)
            return
        }
        /*
         * Nothing of ours is bound (e.g. the start was still armed): running
         * stop.sh here would unbind the vendor gadget for no reason.
         */
        if (!provisioned) {
            // A request can reach a fresh service after the app process died.
            // Verify that the composition belongs to Marionette before touching it.
            teardownOnDestroy = false
            val survivingSession = runCatching { controller.status() }.getOrNull()
            provisioned = survivingSession?.let { it.ready && it.gadgetBound } == true
            if (!provisioned) {
                stopSelf()
                return
            }
        }
        provisioning = true
        try {
            val status: UsbGadgetController.Status? = try {
                controller.stop()
            } catch (e: Throwable) {
                Log.i(TAG, "USB_STOP_FAILED ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (status != null && status.preflightStop) {
                pendingStop = true
                updateNotification("数据线已插着 · 拔掉后自动关闭")
                publish(
                    "armed_stop",
                    status.raw,
                    hostAttached = true,
                    hint = "数据线已插着 · 拔掉后自动关闭"
                )
                Log.i(TAG, "USB_STOP_PENDING_UNPLUG")
                mainHandler.postDelayed(pendingStopPoll, HOST_SEARCH_MS)
                return
            }
            // stop.sh already restored the vendor composition, so onDestroy must
            // not run it again (that would unbind the fresh vendor gadget).
            teardownOnDestroy = false
            provisioned = false
            stopSelf()
        } finally {
            provisioning = false
        }
    }

    /**
     * Hand the port back to the vendor gadget after the host went away.
     *
     * Safe direction: nothing is attached, so this cannot unbind a live host.
     * The vendor gadget is then the only reliable "cable plugged in" detector
     * (ours never attaches when it was bound while the cable was out), and the
     * pending poll takes over again the moment it attaches.
     */
    private fun restoreVendorAndArm() {
        val now = SystemClock.elapsedRealtime()
        if (now - restoreFirstAt > 60_000L) {
            restoreFirstAt = now
            restoreAttempts = 0
        }
        if (++restoreAttempts > 3) {
            // Three restores in a minute means the takeover never attaches.
            // Stop instead of endless rebinding - that path is what panicked.
            Log.i(TAG, "USB_TAKEOVER_LOOP giving up")
            running = false
            provisioned = false
            pendingStart = null
            teardownOnDestroy = false
            updateNotification("端口无法附着 · 已停用外设")
            publish(
                "error",
                "takeover loop",
                hostAttached = false,
                hint = "端口无法附着 · 已停用外设，请重启手机后重试"
            )
            stopSelf()
            return
        }
        Log.i(TAG, "USB_HOST_GONE restoring vendor gadget (attempt=$restoreAttempts)")
        running = false
        hostAttached = false
        provisioned = false
        detachStreak = 0
        teardownOnDestroy = false
        UsbHub.link(this).stop()
        videoSource?.stop()
        videoSource = null
        videoLink?.stop()
        videoLink = null
        audioBridge?.stop()
        audioBridge = null
        pendingStart = PendingStart(lastCamera, lastAudio, lastScreenResult, lastScreenData)
        try {
            controller.stop()
        } catch (e: Throwable) {
            Log.i(TAG, "USB_HOST_GONE_STOP_FAILED ${e.javaClass.simpleName}: ${e.message}")
        }
        vendorRestoredAt = SystemClock.elapsedRealtime()
        updateNotification("插上数据线后自动接管")
        publish(
            "armed",
            "vendor restored",
            hostAttached = false,
            hint = "主机接上后自动接管，无需再点开关"
        )
        mainHandler.removeCallbacks(pendingPoll)
        mainHandler.postDelayed(pendingPoll, HOST_SEARCH_MS)
    }

    private fun publish(
        state: String,
        detail: String,
        hostAttached: Boolean? = null,
        hint: String? = null
    ) {
        // Session-lifecycle states define "is USB usable"; ancillary broadcasts
        // (camera_error) must not hide the peripheral entry. `armed` and
        // `armed_stop` are lifecycle states too - dropping them here left the UI
        // reading a stale "ready" after a real unplug.
        if (state == "ready" || state == "waiting" || state == "error" ||
            state == "armed" || state == "armed_stop" || state == "stopping"
        ) {
            UsbPeripheralStatus.onServiceState(this, state, detail, hostAttached, hint)
        }
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_DETAIL, detail)
                .putExtra(EXTRA_HINT, hint)
        )
    }

    override fun onDestroy() {
        destroyed = true
        pendingStart = null
        pendingStop = false
        mainHandler.removeCallbacks(hostPoll)
        mainHandler.removeCallbacks(pendingPoll)
        mainHandler.removeCallbacks(pendingStopPoll)
        try {
            unregisterReceiver(cableReceiver)
        } catch (_: IllegalArgumentException) {
        }
        videoSource?.stop()
        videoSource = null
        videoLink?.stop()
        videoLink = null
        audioBridge?.stop()
        audioBridge = null
        UsbHub.link(this).stop()
        if (teardownOnDestroy) executor.execute { controller.stop() }
        executor.shutdown()
        running = false
        servicePresent = false
        activeCamera = UsbCameraSource.OFF
        activeAudio = false
        UsbSession.setEnabled(this, false)
        UsbPeripheralStatus.onServiceStopped(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "USB 外设", NotificationManager.IMPORTANCE_LOW).apply {
                description = "手机作为 USB 键盘 / 触控板 / 摄像头 / 音频设备"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            4211,
            Intent(this, UsbPeripheralActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_driver_resident)
            .setContentTitle("USB 外设")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}

/** Process-wide USB HID link shared by the service and the on-screen pad. */
object UsbHub {
    @Volatile private var link: UsbHidLink? = null
    @Volatile private var token: String? = null

    fun link(context: Context): UsbHidLink {
        val current = UsbSession.token(context)
        synchronized(this) {
            val existing = link
            if (existing != null && token == current) return existing
            existing?.stop()
            return UsbHidLink(UsbGadgetController.HID_PORT, current).also {
                link = it
                token = current
            }
        }
    }
}
