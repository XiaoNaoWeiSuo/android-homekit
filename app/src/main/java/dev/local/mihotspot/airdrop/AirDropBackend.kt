package dev.local.mihotspot.airdrop

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 后端子进程的唯一入口：拉起 `luftlift`、把它的 stdout 事件流翻译成 [AirDropState]。
 *
 * 两个子命令都走这里（`receive` / `send`），因为它们的输出格式是同一套 JSON Lines
 * 契约，区别只在事件语义 —— 用 [Mode] 区分。
 *
 * "扫描附近设备"不是一个子命令：它并进 `receive` 的事件流（`--browse`），这样一个
 * 进程宣告 + 搜索，事件不必跨进程合并，也能过滤掉本机自己的宣告。
 *
 * 所有进程都通过 `su -c` 启动：AWDL 需要 raw socket 与网卡控制权。环境变量**直接写进
 * shell 命令**而不是 `ProcessBuilder.environment()`，因为 Android 的 `su` 不保证把父进程
 * 环境透传给子 shell —— 早先的 approval 目录就是靠这一点才可能悄悄失效。
 */
object AirDropBackend {
    private const val TAG = "MarionetteAirDrop"

    /** 事件流的语义。同一个 `progress` 事件在接收和发送里含义不同。 */
    enum class Mode { RECEIVE, SEND }

    /** 一个受管子进程。 */
    class Handle internal constructor(
        val process: Process,
        private val pump: Thread,
        private val log: Thread?
    ) {
        fun isAlive(): Boolean = process.isAlive
        fun stop() {
            // Closing the owner's pipe asks the root backend itself to exit.
            // Killing only su can leave an orphan listening on 8771.
            runCatching { process.outputStream.close() }
            pump.interrupt()
            log?.interrupt()
        }
    }

    /**
     * 启动接收端。
     *
     * `--browse` 让接收端**在自己的 mDNS 守护进程里**同时搜索附近设备，并把
     * `browsing`/`peer`/`lost` 事件混进同一条 stdout 事件流。不另起一个
     * `find --watch` 进程的原因：那样两个进程各收一份包、各建一份缓存，而且
     * 外部进程分不清哪个宣告是自己，会把本机当成"附近设备"列出来（实测
     * `find -d 3` 确实把本机 `luftlift-<pid>` 报成了 peer）。进程内还能直接
     * 复用宣告用的接口作用域和自过滤。
     *
     * @param onExit 进程退出时回调（在后台线程），由调用方决定是否重启。
     */
    fun startReceiver(
        context: Context,
        outputDir: File,
        name: String,
        onExit: (exitCode: Int) -> Unit
    ): Handle {
        val command = buildString {
            append("MARIONETTE_APPROVAL_DIR=").append(AirDropStore.quote(AirDropStore.approvals(context).absolutePath))
            append(' ')
            append(AirDropStore.quote(AirDropStore.binary(context).absolutePath))
            append(" -i awdl0 receive --progress --browse")
            append(" --output ").append(AirDropStore.quote(outputDir.absolutePath))
            append(" --name ").append(AirDropStore.quote(name))
            append(" --http-addr off")
        }
        return spawn(command, Mode.RECEIVE, AirDropStore.log(context), onExit)
    }

    /**
     * 发送单个文件。进度通过 [AirDropState] 回传。
     *
     * [peer] 的地址来自接收端的事件流，所以这里用 `--address` 直连，跳过 mDNS：
     * 地址和端口已经由接收端解析，避免再次搜索造成不必要等待。
     */
    fun send(context: Context, peer: AirDropEvents.Peer, file: File, onExit: (exitCode: Int) -> Unit): Handle {
        val command = buildString {
            append(AirDropStore.quote(AirDropStore.binary(context).absolutePath))
            append(" -i awdl0 send --progress")
            append(' ').append(AirDropStore.quote(peer.name))
            append(' ').append(AirDropStore.quote(file.absolutePath))
            socketAddress(peer)?.let { append(" --address ").append(AirDropStore.quote(it)) }
        }
        return spawn(command, Mode.SEND, null, onExit)
    }

    /**
     * `--address` 的 `host:port` 形式。IPv6（AWDL 上必然是链路本地 IPv6）必须加
     * 方括号，否则后端 `rsplit_once(':')` 会从地址中间切开。
     */
    private fun socketAddress(peer: AirDropEvents.Peer): String? {
        val host = peer.address.trim()
        if (host.isEmpty() || peer.port <= 0) return null
        return if (host.contains(':')) "[$host]:${peer.port}" else "$host:${peer.port}"
    }

    private fun spawn(
        command: String,
        mode: Mode,
        logFile: File?,
        onExit: (Int) -> Unit
    ): Handle {
        Log.i(TAG, "spawn[$mode]: $command")
        val process = ProcessBuilder("su", "-c", "MARIONETTE_CONTROL_STDIN=1 $command")
            .redirectErrorStream(false)
            .start()

        val pump = Thread({ pumpEvents(process, mode) }, "AirDrop-${mode.name}-out").apply {
            isDaemon = true
            start()
        }
        val log = Thread({
                runCatching {
                    process.errorStream.bufferedReader().use { reader ->
                        logFile?.parentFile?.mkdirs()
                        logFile?.appendText("--- ${System.currentTimeMillis()} ${mode.name} ---\n")
                        val buffer = CharArray(8192)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            // Always drain stderr, including SEND; an unread
                            // diagnostic pipe otherwise blocks the backend.
                            logFile?.appendText(String(buffer, 0, count))
                        }
                    }
                }
            }, "AirDrop-${mode.name}-err").apply {
                isDaemon = true
                start()
            }
        Thread({
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            // Deliver the final received/sent event before the exit callback.
            runCatching { pump.join(2_000) }
            runCatching { process.outputStream.close() }
            Log.i(TAG, "exit[$mode]: $code")
            // 进程结束意味着事件流结束 —— UI 必须收敛，不能留一个转圈。
            if (mode == Mode.RECEIVE && !process.isAlive) {
                // 只有非正常退出才报错；正常停止由调用方自己写状态。
                if (code != 0 && code != 143 && code != 137) {
                    AirDropState.onError("AirDrop 后端已退出（code $code）")
                }
            }
            onExit(code)
        }, "AirDrop-${mode.name}-wait").apply {
            isDaemon = true
            start()
        }
        return Handle(process, pump, log)
    }

    /** 逐行解析 stdout 的 JSON 事件并写入状态。 */
    private fun pumpEvents(process: Process, mode: Mode) {
        try {
            BufferedReader(InputStreamReader(process.inputStream), 8192).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val json = AirDropEvents.parse(line) ?: continue
                    dispatch(json, mode)
                }
            }
        } catch (error: Exception) {
            if (error !is InterruptedException) Log.w(TAG, "event stream ended: ${error.message}")
        }
    }

    private fun dispatch(json: org.json.JSONObject, mode: Mode) {
        when (json.optString("event")) {
            AirDropEvents.LISTENING -> AirDropState.onListening(json.optString("name"), json.optInt("port"))
            AirDropEvents.READY -> AirDropState.onReady(json.optString("name"), json.optInt("port"))
            AirDropEvents.DISCOVER -> AirDropState.onDiscover(json.optString("sender"))
            AirDropEvents.REQUEST -> AirDropState.onApproval(AirDropEvents.approval(json))
            AirDropEvents.DECISION -> AirDropState.onDecision(json.optString("token"), json.optBoolean("accepted"))
            AirDropEvents.RECEIVING -> AirDropState.onReceiving(json.optLong("total"))
            AirDropEvents.RECEIVED ->
                AirDropState.onReceived(json.optString("dir"), AirDropEvents.receivedFiles(json))
            // 接收端也会 browse（`--browse`），每条流开始时都会发一次 browsing：
            // 那就是"列表从头开始"的信号（首次启动、或 awdl0 地址变了重建宣告）。
            AirDropEvents.BROWSING -> AirDropState.clearPeers()
            AirDropEvents.PEER -> {
                // 设备列表是"看不见的"：UI 里多一行没法用 logcat 验证，自过滤错了
                // （把自己的宣告列成附近设备）也不会报错。留一条日志，排障时一眼能看出来。
                val peer = AirDropEvents.Peer(
                    json.optString("name"),
                    json.optString("address"),
                    json.optInt("port")
                )
                Log.i(TAG, "PEER ${peer.name} @ ${peer.address}:${peer.port}")
                AirDropState.onPeer(peer)
            }
            AirDropEvents.LOST -> {
                Log.i(TAG, "PEER_LOST ${json.optString("name")}")
                AirDropState.onPeerLost(json.optString("name"))
            }
            AirDropEvents.STAGE -> {
                // 接收端的 browse 不参与发送阶段；只有发送进程会发 stage。
                if (mode == Mode.SEND) AirDropState.onSendStage(json.optString("stage"), "")
            }
            AirDropEvents.SENT -> AirDropState.onSent(json.optLong("bytes"), json.optString("peer"))
            AirDropEvents.ERROR -> AirDropState.onError(json.optString("message").ifBlank { "传输失败" })
            AirDropEvents.PROGRESS -> {
                val done = json.optLong("received").takeIf { it > 0 } ?: json.optLong("sent")
                val total = json.optLong("total")
                // 同一个事件名在两种方向里含义不同，靠启动时的 mode 区分。
                if (mode == Mode.SEND) AirDropState.onSendProgress(done, total)
                else AirDropState.onProgress(done, total)
            }
        }
    }

    /**
     * 把外部文件（分享/选择器给的 URI）复制到应用缓存，返回可发送的本地文件。
     *
     * 后端以 root 运行，但路径必须稳定：`ACTION_SEND` 给的 content:// URI 有时效性，
     * 复制一份到 cache 才能保证发送过程中不会被回收。
     */
    fun stage(context: Context, name: String, open: () -> java.io.InputStream?): File {
        val safe = name.replace(Regex("[/\\\\\\p{Cntrl}]"), "_").take(180).ifBlank { "file" }
        val stage = File(context.cacheDir, "airdrop-send").apply { mkdirs() }
        val target = File(stage, safe)
        open()?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= AirDropStore.MAX_SEND_BYTES) {
                        "单个文件最大 ${AirDropStore.formatSize(AirDropStore.MAX_SEND_BYTES)}"
                    }
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("无法读取所选文件")
        return target
    }
}
