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

/**
 * Foreground owner of USB peripheral mode. Provisioning touches configfs, so it
 * runs on a worker thread; the service itself only publishes state.
 */
class UsbPeripheralService : Service() {

    companion object {
        private const val TAG = "MiHotspotUsb"
        private const val CHANNEL = "usb_peripheral"
        private const val NOTIFICATION_ID = 4210

        const val ACTION_START = "dev.local.mihotspot.usb.START"
        const val ACTION_STOP = "dev.local.mihotspot.usb.STOP"
        const val ACTION_STATE = "dev.local.mihotspot.usb.STATE"
        const val EXTRA_CAMERA = "camera"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_STATE = "state"
        const val EXTRA_DETAIL = "detail"

        @Volatile var running: Boolean = false
            private set

        fun start(context: Context, camera: Boolean, audio: Boolean) {
            val intent = Intent(context, UsbPeripheralService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CAMERA, camera)
                putExtra(EXTRA_AUDIO, audio)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsbPeripheralService::class.java))
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var controller: UsbGadgetController
    private var audioBridge: UsbAudioBridge? = null

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
        if (intent?.action == ACTION_START) {
            val camera = intent.getBooleanExtra(EXTRA_CAMERA, false)
            val audio = intent.getBooleanExtra(EXTRA_AUDIO, false)
            startSession(camera, audio)
        }
        return START_NOT_STICKY
    }

    private fun startSession(camera: Boolean, audio: Boolean) {
        executor.execute {
            val token = UsbSession.token(this)
            val status = controller.start(token, UsbGadgetController.Options(camera, audio))
            if (status.ready || status.bound) {
                UsbSession.setEnabled(this, true)
                UsbHub.link(this).start()
                running = true
                audioBridge?.stop()
                audioBridge = if (audio) {
                    UsbAudioBridge(token).also { it.start(this, speaker = true) }
                } else null
                updateNotification("USB 键鼠已就绪 · 点按进入设置")
                publish("ready", status.raw)
                Log.i(TAG, "USB_SESSION_READY state=${status.state} hid=${status.hidNode} audio=$audio")
            } else {
                running = false
                UsbSession.setEnabled(this, false)
                updateNotification("USB 外设绑定失败")
                publish("error", status.raw)
                Log.i(TAG, "USB_SESSION_FAILED ${status.raw}")
                stopSelf()
            }
        }
    }

    private fun publish(state: String, detail: String) {
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_DETAIL, detail)
        )
    }

    override fun onDestroy() {
        audioBridge?.stop()
        audioBridge = null
        UsbHub.link(this).stop()
        executor.execute { controller.stop() }
        executor.shutdown()
        running = false
        UsbSession.setEnabled(this, false)
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
