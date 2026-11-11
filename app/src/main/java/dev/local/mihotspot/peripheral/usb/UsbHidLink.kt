package dev.local.mihotspot.peripheral.usb

import android.util.Log
import dev.local.mihotspot.peripheral.HidProtocol
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Loopback transport to the root daemon. Reports are framed as
 * `[length:u8][report id:u8][payload...]`; the report id is part of the USB HID
 * wire format and is not present in the Bluetooth transport's byte arrays.
 */
class UsbHidLink(private val port: Int, private val token: String) {

    interface Listener {
        fun onStateChanged(connected: Boolean, detail: String?)
    }

    companion object {
        private const val TAG = "MiHotspotUsb"
    }

    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<ByteArray>(64)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "usb-hid-timer").apply { isDaemon = true }
    }

    @Volatile private var worker: Thread? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var output: BufferedOutputStream? = null
    @Volatile private var buttons = 0
    @Volatile private var connected = false
    private val keyboardGeneration = AtomicInteger()

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    val isConnected: Boolean get() = connected

    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ loop() }, "usb-hid").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        if (!running.get()) return
        // Send the release while the worker is still alive. Setting running to
        // false first would make enqueue() drop the release, leaving the host
        // with a held key that auto-repeats forever.
        enqueue(releaseReport())
        try {
            Thread.sleep(120)
        } catch (_: InterruptedException) {
        }
        if (!running.compareAndSet(true, false)) return
        try {
            worker?.join(350)
        } catch (_: InterruptedException) {
        }
        closeSocket()
        worker = null
    }

    fun releaseAll() {
        enqueue(releaseReport())
    }

    fun sendKeyboardState(modifiers: Int, usages: IntArray) {
        val generation = keyboardGeneration.incrementAndGet()
        enqueue(byteArrayOf(HidProtocol.REPORT_ID_KEYBOARD.toByte()) +
            HidProtocol.keyboardReport(modifiers, usages))
        // The on-screen panel is tap-driven. If ACTION_UP is lost (focus loss,
        // OEM gesture interception), a held key would auto-repeat forever; a
        // short release watchdog makes that failure self-healing.
        if (usages.isNotEmpty() || modifiers != 0) {
            scheduler.schedule({
                if (keyboardGeneration.get() == generation) {
                    enqueue(byteArrayOf(HidProtocol.REPORT_ID_KEYBOARD.toByte(), 0, 0, 0, 0, 0, 0, 0, 0))
                }
            }, 1500, TimeUnit.MILLISECONDS)
        }
    }

    fun mouseMove(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        var x = dx
        var y = dy
        var guard = 0
        do {
            val cx = x.coerceIn(-127, 127)
            val cy = y.coerceIn(-127, 127)
            enqueue(byteArrayOf(HidProtocol.REPORT_ID_MOUSE.toByte()) +
                HidProtocol.mouseReport(buttons, cx, cy, 0))
            x -= cx
            y -= cy
            guard++
        } while ((x != 0 || y != 0) && guard < 32)
    }

    fun mouseScroll(steps: Int) {
        if (steps == 0) return
        var w = steps
        var guard = 0
        do {
            val cw = w.coerceIn(-127, 127)
            enqueue(byteArrayOf(HidProtocol.REPORT_ID_MOUSE.toByte()) +
                HidProtocol.mouseReport(buttons, 0, 0, cw))
            w -= cw
            guard++
        } while (w != 0 && guard < 32)
    }

    fun mouseButton(button: Int, down: Boolean) {
        buttons = if (down) buttons or button else buttons and button.inv()
        enqueue(byteArrayOf(HidProtocol.REPORT_ID_MOUSE.toByte()) +
            HidProtocol.mouseReport(buttons, 0, 0, 0))
    }

    fun click(button: Int) {
        mouseButton(button, true)
        scheduler.schedule({ mouseButton(button, false) }, 30, TimeUnit.MILLISECONDS)
    }

    fun sendConsumer(usage: Int, holdMs: Long = 24L) {
        enqueue(byteArrayOf(HidProtocol.REPORT_ID_CONSUMER.toByte()) +
            HidProtocol.consumerReport(usage))
        scheduler.schedule({
            enqueue(byteArrayOf(HidProtocol.REPORT_ID_CONSUMER.toByte()) +
                HidProtocol.consumerReport(0))
        }, holdMs, TimeUnit.MILLISECONDS)
    }

    private fun releaseReport(): ByteArray = byteArrayOf(
        HidProtocol.REPORT_ID_KEYBOARD.toByte(), 0, 0, 0, 0, 0, 0, 0, 0
    )

    private fun enqueue(report: ByteArray) {
        if (!running.get()) return
        if (report.size > 63) return
        if (queue.size >= 48) queue.poll()
        queue.offer(report)
    }

    private fun loop() {
        while (running.get()) {
            val out = ensureConnected()
            if (out == null) {
                Thread.sleep(400)
                continue
            }
            val report = try {
                queue.poll(500, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            if (report == null) continue
            try {
                out.write(report.size)
                out.write(report)
                out.flush()
            } catch (e: Exception) {
                Log.i(TAG, "USB_HID_WRITE_FAILED ${e.message}")
                markDisconnected(e.message)
                closeSocket()
            }
        }
    }

    private fun ensureConnected(): BufferedOutputStream? {
        val existing = socket
        val existingOut = output
        if (existing != null && existingOut != null && existing.isConnected && !existing.isClosed) {
            return existingOut
        }
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", port), 800)
            val out = BufferedOutputStream(s.getOutputStream())
            out.write("HELLO $token\n".toByteArray())
            out.flush()
            socket = s
            output = out
            connected = true
            // Never inherit a held key/button from a previous session.
            val release = releaseReport()
            out.write(release.size); out.write(release)
            out.write(5); out.write(byteArrayOf(2, 0, 0, 0, 0))
            out.flush()
            notifyState(true, null)
            out
        } catch (e: Exception) {
            markDisconnected(e.message)
            null
        }
    }

    private fun markDisconnected(detail: String?) {
        if (connected) notifyState(false, detail)
        connected = false
    }

    private fun notifyState(connected: Boolean, detail: String?) {
        listeners.forEach { it.onStateChanged(connected, detail) }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        output = null
        socket = null
        markDisconnected(null)
    }
}
