package dev.local.mihotspot.homekit.commands

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Drain output concurrently so a stalled su/Binder call cannot hold a control forever. */
internal object BoundedShell {
    data class Result(val exitCode: Int, val output: String)

    fun run(arguments: List<String>, timeoutMs: Long = 3000L): Result? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val process = ProcessBuilder(arguments).start()
        val output = ByteArrayOutputStream()
        val drained = CountDownLatch(2)
        fun drain(stream: java.io.InputStream, capture: Boolean) {
            Thread({
                try {
                    stream.use {
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = it.read(buffer)
                            if (count < 0) break
                            if (capture) {
                                // Continue draining even beyond the capture limit.
                                val remaining = MAX_OUTPUT_BYTES - output.size()
                                if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                            }
                        }
                    }
                } catch (_: java.io.IOException) {
                    // Closing a timed-out process also closes its pipe.
                } finally {
                    drained.countDown()
                }
            }, "MiHotspotHap-ShellDrain").apply { isDaemon = true; start() }
        }
        try {
            process.outputStream.close()
            drain(process.inputStream, true)
            drain(process.errorStream, false)
            fun remainingNanos() = (deadline - System.nanoTime()).coerceAtLeast(0L)
            if (!process.waitFor(remainingNanos(), TimeUnit.NANOSECONDS)) return null
            // A child can inherit stdout after its parent exits. Bound that wait too.
            if (!drained.await(remainingNanos(), TimeUnit.NANOSECONDS)) return null
            return Result(process.exitValue(), output.toString("UTF-8"))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            process.destroyForcibly()
            // Pipe close can wait for a concurrent read on some runtimes. Keep
            // cleanup off the caller so the deadline still releases its lock.
            Thread({
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            }, "MiHotspotHap-ShellClose").apply { isDaemon = true; start() }
        }
    }

    private const val MAX_OUTPUT_BYTES = 1024 * 1024
}
