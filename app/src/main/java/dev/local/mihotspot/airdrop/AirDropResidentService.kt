package dev.local.mihotspot.airdrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 常驻控制面：让 AirDrop 引擎一直活着。
 *
 * 它自己不碰网卡 —— 那是 [AirDropService] 的职责。这里只做三件事：
 *
 * 1. 用户打开开关后拉起引擎（[AirDropService.startResident]）；
 * 2. 每 20 秒看一眼引擎还在不在，被系统杀掉就拉起来（前台服务在低内存下仍可能被杀）；
 * 3. 维持一条常驻通知，显示当前状态（就绪 / 收到请求 / 正在接收）。
 *
 * 为什么不把引擎逻辑塞进来：网卡切换、后端重启、事件流都要在同一个地方收敛，
 * 引擎只应该有一个实例。常驻和"限时实验"只是它的两种运行模式。
 */
class AirDropResidentService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val notices by lazy { getSystemService(NotificationManager::class.java) }
    private var lastText = ""

    private val stateListener: () -> Unit = { refresh() }

    override fun onCreate() {
        super.onCreate()
        notices.createNotificationChannel(
            NotificationChannel(CHANNEL, "AirDrop 常驻服务", NotificationManager.IMPORTANCE_LOW)
        )
        AirDropState.addListener(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ENABLE) preferences(this).edit().putBoolean(ENABLED, true).commit()
        if (intent?.action == ACTION_DISABLE) preferences(this).edit().putBoolean(ENABLED, false).commit()
        // 系统重建时必须尊重用户开关。
        if (!isEnabled(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION, notification())
        ensureEngine()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
        android.util.Log.i("MarionetteAirDrop", "RESIDENT_ACTIVE")
        return START_STICKY
    }

    /**
     * 看门狗：引擎没了就拉起来。
     *
     * 只看 [AirDropService.isAlive]，不看 `radio/active` —— 网卡还在建立中时后者本来就是
     * 不存在的，用它判断会导致重复拉起两个引擎抢同一块网卡。
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!isEnabled(this@AirDropResidentService)) {
                stopSelf()
                return
            }
            ensureEngine()
            refresh()
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun ensureEngine() {
        if (AirDropService.isAlive()) return
        runCatching { AirDropService.startResident(this) }
            .onFailure { android.util.Log.w("MarionetteAirDrop", "engine start failed: ${it.message}") }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, NOTIFICATION, Intent(this, AirDropActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val disable = PendingIntent.getService(
            this, NOTIFICATION + 1,
            Intent(this, AirDropResidentService::class.java).setAction(ACTION_DISABLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("AirDrop · 常驻")
            .setContentText(currentText())
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "关闭常驻", disable).build())
            .setOngoing(true).build()
    }

    private fun currentText(): String {
        val approval = AirDropState.approval
        return when {
            approval != null -> "${approval.sender} 想发送 ${approval.files.size} 个文件"
            else -> AirDropState.status
        }
    }

    private fun refresh() {
        if (!isEnabled(this)) return
        val text = currentText()
        if (text == lastText) return
        lastText = text
        runCatching { notices.notify(NOTIFICATION, notification()) }
    }

    override fun onDestroy() {
        AirDropState.removeListener(stateListener)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_ENABLE = "dev.local.mihotspot.airdrop.RESIDENT_ENABLE"
        const val ACTION_DISABLE = "dev.local.mihotspot.airdrop.RESIDENT_DISABLE"
        const val ACTION_RECOVER = "dev.local.mihotspot.airdrop.RESIDENT_RECOVER"
        private const val ENABLED = "resident_enabled"
        private const val CHANNEL = "airdrop_resident"
        private const val NOTIFICATION = 7403
        private const val WATCHDOG_MS = 20_000L

        private fun preferences(context: Context) =
            context.getSharedPreferences("airdrop", Context.MODE_PRIVATE)

        fun isEnabled(context: Context) = preferences(context).getBoolean(ENABLED, false)

        /** 主界面显示的常驻状态。引擎在跑就显示引擎的真实状态。 */
        fun status(context: Context): String = when {
            !isEnabled(context) -> "常驻服务已关闭"
            AirDropService.isAlive() -> AirDropState.status
            // 引擎刚失败过。这时说"正在启动"会和旁边的"异常"徽标自相矛盾，
            // 也会把真正的原因（比如没连 Wi-Fi）藏起来。
            AirDropState.phase == AirDropState.Phase.ERROR -> AirDropState.status
            else -> "正在启动常驻服务…"
        }

        fun enable(context: Context) {
            context.startForegroundService(
                Intent(context, AirDropResidentService::class.java).setAction(ACTION_ENABLE)
            )
        }

        fun disable(context: Context) {
            AirDropService.stop(context)
            context.startService(
                Intent(context, AirDropResidentService::class.java).setAction(ACTION_DISABLE)
            )
        }

        fun recover(context: Context) {
            if (isEnabled(context)) context.startForegroundService(
                Intent(context, AirDropResidentService::class.java).setAction(ACTION_RECOVER)
            )
        }
    }
}
