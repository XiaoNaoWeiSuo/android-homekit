package dev.local.mihotspot.airdrop

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 解析 `luftlift --progress` 的 stdout：每行一个 JSON 对象。
 *
 * 协议见 `native/airdrop/PROGRESS.md`。这里刻意不依赖日志文本 —— 日志走 stderr 且格式自由，
 * 而进度事件是稳定契约。任何一行解析失败都被忽略（进程可能正在退出，最后一行不完整），
 * 而不是抛异常打断读取循环。
 */
object AirDropEvents {
    const val LISTENING = "listening"
    const val READY = "ready"
    const val DISCOVER = "discover"
    const val REQUEST = "request"
    const val DECISION = "decision"
    const val RECEIVING = "receiving"
    const val PROGRESS = "progress"
    const val RECEIVED = "received"
    const val BROWSING = "browsing"
    const val PEER = "peer"
    const val LOST = "lost"
    const val STAGE = "stage"
    const val SENT = "sent"
    const val ERROR = "error"

    fun parse(line: String): JSONObject? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            null
        }
    }

    /** 一条确认请求里的文件。 */
    data class RequestFile(val name: String, val size: Long)

    /** 一次接收确认请求（对应一个 token）。 */
    data class Approval(
        val token: String,
        val sender: String,
        val files: List<RequestFile>,
        val total: Long
    )

    /** 附近设备。 */
    data class Peer(val name: String, val address: String, val port: Int) {
        /** mDNS 全名很长（`xxx._airdrop._tcp.local.`），界面只显示前半段。 */
        val displayName: String
            get() = name.substringBefore("._airdrop").ifBlank { name }
    }

    /** 接收/发送进度。`total == 0` 表示总长未知（苹果固定用 chunked 上传）。 */
    data class Progress(val done: Long, val total: Long) {
        val indeterminate: Boolean get() = total <= 0
        /** 0..1，未知总长时返回 null。 */
        val fraction: Float?
            get() = if (total <= 0) null else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    /** 已落盘的文件。 */
    data class ReceivedFile(val name: String, val size: Long, val path: String)

    fun approval(json: JSONObject): Approval {
        val token = json.optString("token")
        val sender = json.optString("sender").ifBlank { "附近设备" }
        val array = json.optJSONArray("files")
        val files = ArrayList<RequestFile>(array?.length() ?: 0)
        if (array != null) {
            for (i in 0 until array.length()) {
                val f = array.optJSONObject(i) ?: continue
                files.add(RequestFile(f.optString("name"), f.optLong("size")))
            }
        }
        return Approval(token, sender, files, json.optLong("total"))
    }

    fun receivedFiles(json: JSONObject): List<ReceivedFile> {
        val array = json.optJSONArray("files") ?: return emptyList()
        val out = ArrayList<ReceivedFile>(array.length())
        for (i in 0 until array.length()) {
            val f = array.optJSONObject(i) ?: continue
            out.add(ReceivedFile(f.optString("name"), f.optLong("size"), f.optString("path")))
        }
        return out
    }
}

/**
 * 进程内的 AirDrop 实时状态，供悬浮窗、主界面、通知三处共享。
 *
 * 后端是一个独立进程，它的 stdout 由 [AirDropSession] 读取并写入这里；UI 只订阅
 * [addListener]。所有回调都派发到主线程，调用方不需要自己 post。
 */
object AirDropState {
    enum class Phase {
        /** 未开启或已停止。 */
        IDLE,

        /** 正在拉起网卡/后端。 */
        STARTING,

        /** 已宣告，等待苹果设备。 */
        READY,

        /** 有设备在浏览我们（收到 /Discover）。 */
        SEEN,

        /** 正在等待用户确认接收。 */
        AWAITING,

        /** 正在接收。 */
        RECEIVING,

        /** 正在发送。 */
        SENDING,

        /** 最近一次传输完成。 */
        DONE,

        /** 出错。 */
        ERROR
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val peerMap = LinkedHashMap<String, AirDropEvents.Peer>()

    @Volatile var phase: Phase = Phase.IDLE
        private set

    /** 一行人类可读状态，直接用于通知和主界面。 */
    @Volatile var status: String = "尚未开启"
        private set

    /** 待确认的请求；不为 null 时悬浮窗显示接收卡片。 */
    @Volatile var approval: AirDropEvents.Approval? = null
        private set

    @Volatile var progress: AirDropEvents.Progress? = null
        private set

    @Volatile var lastReceived: List<AirDropEvents.ReceivedFile> = emptyList()
        private set

    @Volatile var lastReceivedDir: String = ""
        private set

    /** 发送阶段的标签：`discover` / `ask` / `upload`。 */
    @Volatile var sendStage: String = ""
        private set

    @Volatile var error: String? = null
        private set

    /** 当前正在发送的文件名，仅用于界面展示。 */
    @Volatile var sendingName: String = ""
        private set

    /** 当前对端（发送方或接收方）的名字，用于悬浮窗副标题。 */
    @Volatile var peerName: String = ""
        private set

    fun peers(): List<AirDropEvents.Peer> = synchronized(peerMap) { peerMap.values.toList() }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
        main.post(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 通知所有监听者（主线程）。 */
    private fun bump() {
        main.post { listeners.forEach { runCatching { it() } } }
    }

    private fun set(next: Phase, text: String) {
        phase = next
        status = text
        bump()
    }

    // ---- 由 AirDropSession 调用的事件入口 ----

    fun onStarting(text: String) = set(Phase.STARTING, text)

    /**
     * 监听已建立，但还没被宣告。
     *
     * 这个状态很重要：网卡没有 IPv6 地址时后端会一直停在这里 —— 能收数据，
     * 苹果设备却看不到我们。如果把它当成"启动中"，用户就分不清"还在准备"和
     * "已经起来了但不可见"。
     */
    fun onListening(name: String, port: Int) {
        error = null
        set(Phase.READY, "已启动 · $name:$port · 正在宣告")
    }

    fun onReady(name: String, port: Int) {
        approval = null
        progress = null
        error = null
        set(Phase.READY, "本地服务已启动 · 等待验证苹果设备发现")
    }

    fun onDiscover(sender: String) {
        peerName = sender
        // 只在没在传输时更新，避免覆盖「正在接收」这类更重要的状态。
        if (phase == Phase.READY || phase == Phase.SEEN) {
            set(Phase.SEEN, "$sender 正在查看")
        }
    }

    fun onApproval(request: AirDropEvents.Approval) {
        approval = request
        peerName = request.sender
        val count = request.files.size
        set(Phase.AWAITING, "${request.sender} 想发送 $count 个文件")
    }

    fun onDecision(token: String, accepted: Boolean) {
        if (approval?.token == token) approval = null
        if (!accepted) {
            progress = null
            set(Phase.READY, "已拒绝这次接收")
        }
    }

    fun onReceiving(total: Long) {
        progress = AirDropEvents.Progress(0, total)
        set(Phase.RECEIVING, if (total > 0) "正在接收…" else "正在接收…")
        bump()
    }

    fun onProgress(done: Long, total: Long) {
        val current = progress
        // `receiving` 先到、`progress` 后到；若总长已知就沿用 receiving 报的那个，
        // 因为 chunked 的 progress 事件 total 恒为 0。
        val effectiveTotal = when {
            total > 0 -> total
            current != null && current.total > 0 -> current.total
            else -> 0L
        }
        progress = AirDropEvents.Progress(done, effectiveTotal)
        if (phase != Phase.RECEIVING) set(Phase.RECEIVING, "正在接收…") else bump()
    }

    fun onReceived(dir: String, files: List<AirDropEvents.ReceivedFile>) {
        lastReceived = files
        lastReceivedDir = dir
        progress = null
        approval = null
        val bytes = files.sumOf { it.size }
        set(Phase.DONE, "已接收 ${files.size} 个文件 · ${AirDropStore.formatSize(bytes)}")
    }

    fun onSendStage(stage: String, fileName: String) {
        sendStage = stage
        if (fileName.isNotBlank()) sendingName = fileName
        val text = when (stage) {
            "discover" -> "正在查找对方…"
            "ask" -> "等待对方接受…"
            "upload" -> "正在发送 ${sendingName}…"
            else -> "正在发送…"
        }
        if (stage == "upload") progress = AirDropEvents.Progress(0, 0)
        set(Phase.SENDING, text)
    }

    /** 发送端在拉起进程前就能知道文件名（后端事件里没有），由调用方补上。 */
    fun setSendingName(name: String) {
        sendingName = name
        if (phase == Phase.SENDING) status = "正在发送 $name…"
        bump()
    }

    fun onSendProgress(done: Long, total: Long) {
        progress = AirDropEvents.Progress(done, total)
        if (phase != Phase.SENDING) set(Phase.SENDING, "正在发送…") else bump()
    }

    fun onSent(bytes: Long, peer: String) {
        progress = null
        if (peer.isNotBlank()) peerName = peer
        set(Phase.DONE, "已发送到 $peer · ${AirDropStore.formatSize(bytes)}")
    }

    fun onError(message: String) {
        progress = null
        approval = null
        error = message
        set(Phase.ERROR, message)
    }

    fun onIdle(text: String) {
        approval = null
        progress = null
        sendStage = ""
        sendingName = ""
        // 引擎停了，附近设备列表就没有意义了：awdl0 已经不存在，点任何一个都会失败。
        // 下次启动接收端会重新发 `browsing`，列表自然重建。
        synchronized(peerMap) { peerMap.clear() }
        set(Phase.IDLE, text)
    }

    // ---- 附近设备 ----

    fun onPeer(peer: AirDropEvents.Peer) {
        synchronized(peerMap) { peerMap[peer.name] = peer }
        bump()
    }

    fun onPeerLost(name: String) {
        synchronized(peerMap) { peerMap.remove(name) }
        bump()
    }

    fun clearPeers() {
        synchronized(peerMap) { peerMap.clear() }
        bump()
    }
}
