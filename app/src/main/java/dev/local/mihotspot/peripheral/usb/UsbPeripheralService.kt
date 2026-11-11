package dev.local.mihotspot.peripheral.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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

        const val ACTION_START = "dev.local.mihotspot.usb.START"
        const val ACTION_STOP = "dev.local.mihotspot.usb.STOP"
        /** Re-own a session whose gadget/daemon survived a process restart. */
        const val ACTION_ATTACH = "dev.local.mihotspot.usb.ATTACH"
        const val ACTION_STATE = "dev.local.mihotspot.usb.STATE"
        const val EXTRA_CAMERA_SOURCE = "camera_source"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_SCREEN_RESULT = "screen_result"
        const val EXTRA_SCREEN_DATA = "screen_data"
        const val EXTRA_STATE = "state"
        const val EXTRA_DETAIL = "detail"

        @Volatile var running: Boolean = false
            private set

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
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var controller: UsbGadgetController
    private var audioBridge: UsbAudioBridge? = null
    private var videoLink: UsbVideoLink? = null
    private var videoSource: UsbVideoSource? = null

    /** Set in onDestroy so an in-flight provisioning task cannot re-publish "running". */
    @Volatile private var destroyed = false

    /** False when this service never owned the configfs composition (attach probe only). */
    @Volatile private var teardownOnDestroy = true

    override fun onCreate() {
        super.onCreate()
        controller = UsbGadgetController(this)
        ensureChannel()
        startForegroundCompat(buildNotification("正在准备 USB 外设…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ATTACH) {
            attachSession()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) {
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
        executor.execute {
            val token = UsbSession.token(this)
            val status = controller.start(
                token,
                UsbGadgetController.Options(
                    camera = effectiveCamera != UsbCameraSource.OFF,
                    audio = audio
                )
            )
            if (status.ready || status.bound) {
                if (destroyed) return@execute
                UsbSession.setEnabled(this, true)
                UsbHub.link(this).start()
                running = true

                audioBridge?.stop()
                audioBridge = null
                videoSource?.stop()
                videoSource = null
                videoLink?.stop()
                videoLink = null

                updateForegroundTypes(effectiveCamera, audio)
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
        val link = UsbVideoLink(token).also { it.start() }
        val video = UsbVideoSource(this) { data, width, height, format, pts ->
            link.send(data, width, height, format, pts)
        }
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
                link.stop()
                return
            }
            UsbCameraSource.OFF -> {
                link.stop()
                return
            }
        }
        videoLink = link
        videoSource = video
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

    private fun publish(state: String, detail: String, hostAttached: Boolean? = null) {
        // Only session-lifecycle states define "is USB usable"; ancillary
        // broadcasts (camera_error) must not hide the peripheral entry.
        if (state == "ready" || state == "waiting" || state == "error") {
            UsbPeripheralStatus.onServiceState(this, state, detail, hostAttached)
        }
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_DETAIL, detail)
        )
    }

    override fun onDestroy() {
        destroyed = true
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
