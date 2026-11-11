package dev.local.mihotspot.peripheral

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import dev.local.mihotspot.R

/**
 * Keeps the HID registration alive while the phone is used as a keyboard /
 * touchpad, and raises a heads-up notification when a host connects.
 */
class PeripheralService : Service() {

    companion object {
        private const val CHANNEL_ID = "peripheral_hid"
        private const val CONNECT_CHANNEL_ID = "peripheral_connected"
        private const val NOTIFICATION_ID = 4201
        private const val CONNECT_NOTIFICATION_ID = 4202

        fun start(context: Context) {
            val intent = Intent(context, PeripheralService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PeripheralService::class.java))
        }
    }

    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val bridge: BtHidPeripheral get() = PeripheralHub.bridge(this)

    private val connectionListener = object : BtHidPeripheral.Listener {
        override fun onStateChanged(state: BtHidPeripheral.State, detail: String?) {
            if (state == BtHidPeripheral.State.CONNECTED) postConnectedNotification(detail)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        bridge.start()
        bridge.addListener(connectionListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        bridge.start()
        return START_STICKY
    }

    override fun onDestroy() {
        bridge.removeListener(connectionListener)
        bridge.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "蓝牙键鼠外设",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "手机作为蓝牙键盘 / 触控板时保持连接"
                setShowBadge(false)
            }
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CONNECT_CHANNEL_ID,
                "蓝牙键鼠已连接",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "主机连接成功时弹出提示"
                setShowBadge(true)
            }
        )
    }

    private fun buildNotification(): Notification {
        ensureChannels()
        val open = PendingIntent.getActivity(
            this,
            4301,
            PeripheralPadActivity.intent(this, immersive = false)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_driver_resident)
            .setContentTitle("Marionette 外设")
            .setContentText("蓝牙键盘 / 触控板已就绪")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun postConnectedNotification(host: String?) {
        ensureChannels()
        val open = PendingIntent.getActivity(
            this,
            4302,
            PeripheralPadActivity.intent(this, immersive = true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CONNECT_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_driver_resident)
            .setContentTitle("蓝牙键鼠已连接")
            .setContentText("${host ?: "主机"} · 点按进入外设模式")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_EVENT)
            .build()
        notificationManager.notify(CONNECT_NOTIFICATION_ID, notification)
    }
}

/** Process-wide handle so activities and the service share one HID session. */
object PeripheralHub {
    @Volatile private var instance: BtHidPeripheral? = null

    fun bridge(context: Context): BtHidPeripheral {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            return BtHidPeripheral(context.applicationContext).also { instance = it }
        }
    }
}
