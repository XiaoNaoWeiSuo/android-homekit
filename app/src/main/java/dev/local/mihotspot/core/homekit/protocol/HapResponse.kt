package dev.local.mihotspot.core.homekit.protocol

internal class HapResponse(val tid: Int, val status: Int, body: ByteArray = byteArrayOf()) {
    val body = body.copyOf()
    private var offset = 0
    private var first = true
    val isFinal get() = !first && offset == body.size

    fun nextFragmentForMtu(mtu: Int, encrypted: Boolean): ByteArray =
        nextFragment(mtu - 1 - if (encrypted) 16 else 0)

    private fun nextFragment(maxBytes: Int): ByteArray {
        val header = if (first && body.isNotEmpty()) 5 else if (first) 3 else 2
        val count = minOf(body.size - offset, maxBytes - header)
        require(count >= 0 && (body.isEmpty() || count > 0)) { "ATT MTU too small for HAP response" }
        val fragment = if (first) {
            if (body.isEmpty()) byteArrayOf(0x02, tid.toByte(), status.toByte())
            else byteArrayOf(0x02, tid.toByte(), status.toByte(), body.size.toByte(), (body.size ushr 8).toByte()) +
                body.copyOfRange(offset, offset + count)
        } else byteArrayOf(0x82.toByte(), tid.toByte()) + body.copyOfRange(offset, offset + count)
        offset += count
        first = false
        return fragment
    }
}
