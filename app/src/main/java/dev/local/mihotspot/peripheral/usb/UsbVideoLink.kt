package dev.local.mihotspot.peripheral.usb

import android.util.Log
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends MRV1 frames to the root daemon (127.0.0.1:47832). Header layout:
 *
 *   "MRV1" | width:u16 | height:u16 | format:u8 | flags:u8 | length:u32 | pts:u64
 *
 * format 0 = NV21, 1 = RGBA8888. Frames are dropped rather than queued without
 * bound: the host only needs the latest frame, and stale frames add latency.
 */
class UsbVideoLink(private val token: String) {

    companion object {
        private const val TAG = "MiHotspotUsb"
        private const val HEADER_SIZE = 22
        private const val PORT = UsbGadgetController.VIDEO_PORT
    }

    private data class Frame(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val format: Int,
        val pts: Long
    )

    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<Frame>(6)
    @Volatile private var worker: Thread? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var output: BufferedOutputStream? = null
    @Volatile private var connected = false

    /**
     * Set from the daemon's `MODE <w> <h>` line: the frame size the host
     * committed in the UVC PROBE/COMMIT handshake. Frames must match it, since
     * the host has already told its decoder what to expect.
     */
    @Volatile var onMode: ((width: Int, height: Int) -> Unit)? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ loop() }, "usb-video").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        queue.clear()
        try {
            worker?.join(400)
        } catch (_: InterruptedException) {
        }
        closeSocket()
        worker = null
    }

    fun send(data: ByteArray, width: Int, height: Int, format: Int, pts: Long) {
        if (!running.get() || width <= 0 || height <= 0) return
        if (queue.size >= 4) queue.poll()
        queue.offer(Frame(data, width, height, format, pts))
    }

    private fun loop() {
        while (running.get()) {
            val out = ensureConnected()
            if (out == null) {
                Thread.sleep(400)
                continue
            }
            val frame = try {
                queue.poll(500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue
            try {
                val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                header.put('M'.code.toByte())
                header.put('R'.code.toByte())
                header.put('V'.code.toByte())
                header.put('1'.code.toByte())
                header.putShort(frame.width.toShort())
                header.putShort(frame.height.toShort())
                header.put(frame.format.toByte())
                header.put(0)
                header.putInt(frame.data.size)
                header.putLong(frame.pts)
                out.write(header.array())
                out.write(frame.data)
                out.flush()
            } catch (e: Exception) {
                Log.i(TAG, "USB_VIDEO_WRITE_FAILED ${e.message}")
                closeSocket()
            }
        }
    }

    private fun ensureConnected(): BufferedOutputStream? {
        val s = socket
        val o = output
        if (s != null && o != null && s.isConnected && !s.isClosed) return o
        return try {
            val newSocket = Socket()
            newSocket.tcpNoDelay = true
            newSocket.connect(InetSocketAddress("127.0.0.1", PORT), 800)
            val newOut = BufferedOutputStream(newSocket.getOutputStream())
            newOut.write("HELLO $token\n".toByteArray())
            newOut.flush()
            socket = newSocket
            output = newOut
            connected = true
            startReader(newSocket)
            Log.i(TAG, "USB_VIDEO_CONNECTED port=$PORT")
            newOut
        } catch (e: Exception) {
            if (connected) Log.i(TAG, "USB_VIDEO_CONNECT_FAILED ${e.message}")
            connected = false
            null
        }
    }

    /** Reads the daemon's one-way control lines (`MODE w h`). */
    private fun startReader(sock: Socket) {
        Thread({
            try {
                val reader = sock.getInputStream().bufferedReader(Charsets.US_ASCII)
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("MODE ")) continue
                    val parts = line.trim().split(' ')
                    if (parts.size < 3) continue
                    val width = parts[1].toIntOrNull() ?: continue
                    val height = parts[2].toIntOrNull() ?: continue
                    if (width <= 0 || height <= 0) continue
                    Log.i(TAG, "USB_VIDEO_MODE ${width}x${height}")
                    onMode?.invoke(width, height)
                }
            } catch (_: Exception) {
            }
        }, "usb-video-rx").apply {
            isDaemon = true
            start()
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        output = null
        connected = false
    }
}
