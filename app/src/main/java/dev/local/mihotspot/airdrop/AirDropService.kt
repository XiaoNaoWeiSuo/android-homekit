package dev.local.mihotspot.airdrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AirDrop 会话引擎：拉起 AWDL 网卡 + 接收后端，并把后端的事件流转成界面状态。
 *
 * 两种模式共用同一个引擎：
 *
 * * **常驻**（`EXTRA_RESIDENT`）—— 由 [AirDropResidentService] 拉起，不设时限，
 *   后端异常退出会自动重启，这是"和 iPhone 一样常驻"的那部分；
 * * **限时实验** —— 用户在主界面手动开启，最长 10 分钟，到点自动停止。
 *
 * 状态更新不再靠轮询 approvals 目录：后端的 stdout 就是权威事件源（见
 * `native/airdrop/PROGRESS.md`），收到 `request` 就弹悬浮窗，收到 `progress` 就推进度条。
 */
class AirDropService : Service() {
    private val stop = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile private var radioProcess: java.lang.Process? = null
    @Volatile private var receiver: AirDropBackend.Handle? = null
    @Volatile private var resident = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private val notices by lazy { getSystemService(NotificationManager::class.java) }
    private var lastNotifiedText = ""
    private var lastStatus = ""

    /**
     * 状态订阅：通知栏 + 悬浮窗 + 主界面文字都从这里刷新。回调已在主线程。
     */
    private val stateListener: () -> Unit = {
        syncStatus()
        refreshNotification()
        AirDropOverlay.render(this)
    }

    /** 把实时状态同步到 SharedPreferences，主界面的轮询读的就是它。 */
    private fun syncStatus() {
        val text = AirDropState.status
        if (text != lastStatus) {
            lastStatus = text
            AirDropStore.status(this, text)
        }
    }

    override fun onCreate() {
        super.onCreate()
        alive = true
        notices.createNotificationChannel(
            NotificationChannel(CHANNEL, "AirDrop 文件收发", NotificationManager.IMPORTANCE_LOW)
        )
        AirDropState.addListener(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop()
            stopSelf()
            return START_NOT_STICKY
        }
        resident = intent?.getBooleanExtra(EXTRA_RESIDENT, false) ?: false
        startForeground(NOTIFICATION_ID, notification("正在启动 AirDrop"))
        if (worker?.isAlive != true) {
            stop.set(false)
            worker = Thread({ runSession() }, "Marionette-AirDrop").apply { start() }
        }
        // 常驻模式必须能被系统重建；限时模式则不需要。
        return if (resident) START_STICKY else START_NOT_STICKY
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID, Intent(this, AirDropActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val end = PendingIntent.getService(
            this, NOTIFICATION_ID + 1,
            Intent(this, AirDropService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Marionette · AirDrop")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)

        val approval = AirDropState.approval
        if (approval != null) {
            // 没有悬浮窗权限时的退路：通知上直接给接收/拒绝，不必先打开应用。
            builder.addAction(
                Notification.Action.Builder(null, "接收", decisionIntent(approval.token, true)).build()
            )
            builder.addAction(
                Notification.Action.Builder(null, "拒绝", decisionIntent(approval.token, false)).build()
            )
        } else {
            builder.addAction(Notification.Action.Builder(null, "停止 AirDrop", end).build())
        }
        return builder.build()
    }

    private fun decisionIntent(token: String, accepted: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            token.hashCode() xor if (accepted) 1 else 0,
            Intent(this, AirDropActionReceiver::class.java)
                .setAction(AirDropActionReceiver.ACTION_DECIDE)
                .putExtra(AirDropActionReceiver.EXTRA_TOKEN, token)
                .putExtra(AirDropActionReceiver.EXTRA_ACCEPT, accepted),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** 状态变化时刷新常驻通知；文字没变就不打扰系统。 */
    private fun refreshNotification() {
        val approval = AirDropState.approval
        val text = when {
            approval != null -> "${approval.sender} 想发送 ${approval.files.size} 个文件 · 点此或下拉确认"
            else -> AirDropState.status
        }
        if (text == lastNotifiedText) return
        lastNotifiedText = text
        runCatching { notices.notify(NOTIFICATION_ID, notification(text)) }
    }

    private fun runSession() {
        val radio = AirDropStore.radio(this)
        // 失败原因必须活着走到 finally 之后：它会决定 finally 能不能覆盖状态。
        var failed = false
        try {
            check(AirDropStore.binary(this).canExecute() && AirDropStore.binary(this, true).canExecute()) {
                "安装包缺少 ARM64 AirDrop 后端，请先运行 build-airdrop-backend.sh"
            }
            AirDropState.onStarting("正在准备 AirDrop")
            ensureDriver(radio)?.let { throw IllegalStateException(it) }
            var attempt = 0
            while (!stop.get()) {
                attempt++
                if (!startRadio(radio)) {
                    // 失败原因就在脚本的输出里（"请先连接一个 Wi-Fi 网络"等）。
                    // 把它原样交给界面，比"网卡未就绪"有用得多：用户看一眼就知道
                    // 该去连网、还是该关热点。
                    val reason = File(radio, "backend.log").takeIf { it.exists() }
                        ?.readText()?.takeLast(600)?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "AirDrop 网卡启动未完成，详情见诊断日志。"
                    // 常驻模式不能因为一次网卡失败就永久放弃：链路断开、热点占用、
                    // 上一次会话残留都会让 radio.sh 失败，隔一会儿再试即可。
                    if (!resident || attempt >= 20) throw IllegalStateException(reason)
                    AirDropState.onStarting("$reason（${RETRY_SECONDS}s 后重试）")
                    sleepInterruptible(RETRY_SECONDS * 1000L)
                    continue
                }
                AirDropState.onStarting("已进入点对点模式，正在宣告")
                try {
                    runReceiver(radio)
                } finally {
                    receiver?.stop()
                    receiver = null
                }
                if (stop.get()) return
                // 后端挂了：限时模式直接结束，常驻模式重新来一轮（含网卡）。
                if (!resident) {
                    AirDropState.onError("文件收发后端已退出，请查看诊断日志")
                    return
                }
                AirDropState.onStarting("后端退出，正在重启…")
                sleepInterruptible(RETRY_SECONDS * 1000L)
                stopRadio(radio)
            }
        } catch (error: Exception) {
            failed = true
            AirDropStore.status(this, "启动失败：${error.message}")
            AirDropState.onError(error.message ?: "启动失败")
            android.util.Log.e("MarionetteAirDrop", "SESSION_FAILED", error)
        } finally {
            receiver?.stop()
            receiver = null
            stopRadio(radio)
            if (multicastLock?.isHeld == true) multicastLock?.release()
            multicastLock = null
            // 只有没失败时才写"空闲"。否则 onIdle 会把上面那句具体原因覆盖成
            // "会话结束"，用户就只看到一个没有信息量的结果 —— 上一版正是这样。
            if (!failed) AirDropState.onIdle(if (stop.get()) "已停止" else "会话结束")
            notices.cancel(TRANSFER_NOTIFICATION_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * 确保已加载实验版（打了 marionette 补丁的）WLAN 驱动。
     *
     * 驱动不在开机镜像里，而 radio.sh 硬性要求 `marionette_awdl_*` 参数，所以在
     * 起 radio.sh 之前，如果当前还是原厂驱动，就用随包的模块 + driver-load.sh
     * 自行 insmod 并切换框架 Wi-Fi。已打过补丁时直接返回（快速路）。
     *
     * 失败时[driver-load.sh] 会回滚到原厂，Wi-Fi 仍可用，所以这里只把错误信息
     * 抛回给界面，而不是把手机搞成没网。返回 null 表示驱动已就绪。
     */
    private fun ensureDriver(radio: File): String? {
        // 快速路：补丁驱动已在位，无需再动 Wi-Fi。
        if (File("/sys/module/kiwi_v2/parameters/marionette_awdl_capture_hold").exists()) return null
        val dir = File(AirDropStore.base(this), "driver").apply { mkdirs() }
        val cfg = File(dir, "cfg80211.ko")
        val wlan = File(dir, "kiwi_v2.ko")
        val script = File(dir, "driver-load.sh")
        runCatching {
            extractAsset("airdrop/marionette-cfg80211.ko", cfg)
            extractAsset("airdrop/marionette-kiwi.ko", wlan)
            extractAsset("airdrop/driver-load.sh", script)
        }.onFailure { return "无法解压 AirDrop 驱动：${it.message}" }
        AirDropState.onStarting("正在加载 AirDrop 驱动（Wi-Fi 会短暂切换，约 10 秒）")
        val log = File(dir, "driver-load.log")
        val cmd = listOf("/system/bin/sh", script.absolutePath, cfg.absolutePath, wlan.absolutePath)
            .joinToString(" ", transform = AirDropStore::quote)
        val code = runCatching {
            ProcessBuilder("su", "-c", cmd).redirectErrorStream(true)
                .redirectOutput(log).start().waitFor()
        }.getOrElse { return "无法以 root 运行驱动加载：${it.message}" }
        val out = log.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        if (code != 0) return "AirDrop 驱动加载失败：${out.takeLast(400)}"
        return null
    }

    private fun extractAsset(name: String, target: File) {
        target.parentFile?.mkdirs()
        assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
        runCatching { target.setExecutable(true) }
    }

    /** 起网卡，最多等 25 s；返回是否可用。 */
    private fun startRadio(radio: File): Boolean {
        if (radioProcess?.isAlive == true && radioReady()) return true
        reapStaleReceiver()
        if (File(radio, "active").exists()) {
            // 上一次会话的 guardian 还没把网卡还回去。
            stopRadio(radio)
            sleepInterruptible(2_000)
        }
        radio.mkdirs()
        File(radio, "stop").delete()
        AirDropStore.approvals(this).listFiles()?.forEach { it.delete() }
        val script = File(AirDropStore.base(this), "radio.sh")
        assets.open("airdrop/radio.sh").use { input -> script.outputStream().use { input.copyTo(it) } }
        val command = listOf(
            "/system/bin/sh", script.absolutePath, AirDropStore.binary(this, true).absolutePath,
            radio.absolutePath, Process.myPid().toString()
        ).joinToString(" ", transform = AirDropStore::quote)
        val radioLog = File(radio, "backend.log")
        radioProcess = ProcessBuilder("su", "-c", command).redirectErrorStream(true)
            .redirectOutput(radioLog).start()

        val deadline = SystemClock.elapsedRealtime() + 25_000L
        while (!stop.get() && SystemClock.elapsedRealtime() < deadline && radioProcess?.isAlive == true) {
            if (radioReady()) {
                multicastLock = getSystemService(WifiManager::class.java)
                    ?.createMulticastLock("Marionette-AirDrop")?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                return true
            }
            sleepInterruptible(250)
        }
        return radioReady()
    }

    /**
     * 收掉上一次会话遗留的接收端。
     *
     * 进程被强杀（`am force-stop`、系统回收、安装新包）时，接收端是 su 之外的
     * 子进程，会变成孤儿并继续占着 AirDrop 的 HTTPS 监听 `:8771`（mDNS 的 5353
     * 有 SO_REUSEPORT，不是瓶颈；实测第二个接收端正是死在 8771 的 `EADDRINUSE`）。
     * 下一次拉起的接收端绑定失败，16 ms 就以 1 退出；runSession 认为是"后端退出"，
     * 20 s 后重启**整条链路**，于是 radio.sh 反复建删监视 vdev —— 每次删都是一条
     * QDF ASSERT，几分钟就能把设备带下去（2026-10-23 实测：断言 25 条，杀掉孤儿后
     * 立刻停止增长）。
     */
    private fun reapStaleReceiver() {
        runCatching {
            ProcessBuilder("su", "-c", "pkill -9 -f libmarionette_airdrop.so")
                .redirectErrorStream(true)
                .start()
                .waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun stopRadio(radio: File) {
        File(radio.apply { mkdirs() }, "stop").writeText("stop")
        // 等 guardian 的 EXIT trap 把监视接口和滤镜还回去，否则下一次 startRadio 会撞上残留状态。
        try {
            radioProcess?.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        radioProcess = null
        if (multicastLock?.isHeld == true) multicastLock?.release()
        multicastLock = null
    }

    /** 跑接收端直到进程退出或用户停止。 */
    private fun runReceiver(radio: File) {
        val output = AirDropStore.inbox(this)
        val name = receiverName()
        val handle = AirDropBackend.startReceiver(this, output, name) { code ->
            // 回调在后台线程；这里只记录，重启决策交给 runSession 的循环。
            android.util.Log.i("MarionetteAirDrop", "receiver exited: $code")
        }
        receiver = handle

        val deadline = if (resident) Long.MAX_VALUE else SystemClock.elapsedRealtime() + WINDOW_MS
        while (!stop.get() && handle.isAlive() && radioProcess?.isAlive == true) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                AirDropState.onIdle("实验窗口结束")
                return
            }
            sleepInterruptible(500)
        }
    }

    /** 显示名：默认「Marionette 小米 13」，和主界面保持一致。 */
    private fun receiverName(): String {
        val model = android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        return "Marionette $model"
    }

    private fun radioReady(): Boolean = try {
        val connection = URL("http://127.0.0.1:9930/status").openConnection() as HttpURLConnection
        connection.connectTimeout = 300
        connection.readTimeout = 300
        try {
            connection.responseCode == 200
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        false
    }

    private fun sleepInterruptible(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun requestStop() {
        stop.set(true)
        File(AirDropStore.radio(this).apply { mkdirs() }, "stop").writeText("stop")
        receiver?.stop()
        worker?.interrupt()
    }

    override fun onDestroy() {
        alive = false
        AirDropState.removeListener(stateListener)
        AirDropOverlay.hide()
        requestStop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "dev.local.mihotspot.airdrop.STOP"
        const val EXTRA_RESIDENT = "resident"
        private const val CHANNEL = "airdrop"
        private const val NOTIFICATION_ID = 7400
        private const val TRANSFER_NOTIFICATION_ID = 7402
        private const val WINDOW_MS = 570_000L
        private const val RETRY_SECONDS = 20

        /** 引擎是否在跑。常驻服务的看门狗靠它决定要不要重新拉起。 */
        @Volatile private var alive = false

        fun isAlive(): Boolean = alive

        fun isRunning(context: Context): Boolean = File(AirDropStore.radio(context), "active").exists()

        /** 启动限时会话（用户在主界面手动开启）。 */
        fun startWindow(context: Context) {
            context.startForegroundService(
                Intent(context, AirDropService::class.java).putExtra(EXTRA_RESIDENT, false)
            )
        }

        /** 启动常驻会话（由常驻服务拉起）。 */
        fun startResident(context: Context) {
            context.startForegroundService(
                Intent(context, AirDropService::class.java).putExtra(EXTRA_RESIDENT, true)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AirDropService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
