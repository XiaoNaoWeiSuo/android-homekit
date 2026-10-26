package dev.local.mihotspot.core.homekit.protocol

/** Only an identical write transaction in the same session can reuse its outcome. */
internal class HapReplayCache(private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private data class Key(val address: String, val iid: Int, val tid: Int)
    private data class Entry(val opcode: Int, val request: ByteArray, val status: Int, val response: ByteArray, val at: Long)
    private val entries = LinkedHashMap<Key, Entry>()
    @Synchronized fun find(address: String, iid: Int, opcode: Int, tid: Int, body: ByteArray): HapResponse? {
        val key = Key(address, iid, tid)
        val entry = entries[key] ?: return null
        if (clockMs() - entry.at >= 10_000L) { entries.remove(key); return null }
        if (entry.opcode != opcode || !entry.request.contentEquals(body)) return null
        return HapResponse(tid, entry.status, entry.response)
    }
    @Synchronized fun remember(address: String, iid: Int, opcode: Int, tid: Int, body: ByteArray, result: HapResponse) {
        entries[Key(address, iid, tid)] = Entry(opcode, body.copyOf(), result.status, result.body.copyOf(), clockMs())
        while (entries.size > 64) entries.remove(entries.keys.first())
    }
    @Synchronized fun clear(address: String? = null) {
        if (address == null) entries.clear() else entries.keys.removeAll { it.address == address }
    }
    @Synchronized fun complete(address: String, iid: Int, tid: Int) { entries.remove(Key(address, iid, tid)) }
}
