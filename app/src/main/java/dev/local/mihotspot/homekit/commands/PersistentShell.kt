package dev.local.mihotspot.homekit.commands

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reuse su, frame every result, and discard the session after uncertain I/O. Never replay an action. */
internal class PersistentShell(private val arguments: List<String>) : AutoCloseable {
    private class Request(val marker: String) {
        val finished = CountDownLatch(1)
        val output = StringBuilder()
        var exitCode: Int? = null
    }
    private class Session(val process: Process) {
        @Volatile var request: Request? = null
        val input = process.outputStream.bufferedWriter()
    }
    @Volatile private var session: Session? = null
    @Volatile private var closed = false

    @Synchronized fun run(command: String, timeoutMs: Long = 3000L): BoundedShell.Result? {
        if (closed || timeoutMs <= 0) return null
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        session?.takeIf { !it.process.isAlive }?.let(::discard)
        val current = session ?: startSession().also { session = it }
        if (closed) { discard(current); return null }
        val request = Request("__MARIONETTE_${UUID.randomUUID().toString().replace("-", "")}")
        current.request = request
        try {
            // A subshell prevents exit/cd/environment changes from poisoning the
            // next command. The status belongs to this exact command, not printf.
            current.input.write("(\n$command\n)\nprintf '\\n${request.marker}:%s\\n' \"\$?\"\n")
            current.input.flush()
            val remaining = (end - System.nanoTime()).coerceAtLeast(0)
            if (!request.finished.await(remaining, TimeUnit.NANOSECONDS) || request.exitCode == null) {
                discard(current)
                return null
            }
            return BoundedShell.Result(request.exitCode!!, request.output.toString().removeSuffix("\n"))
        } catch (_: java.io.IOException) {
            discard(current)
            return null
        } catch (_: InterruptedException) {
            discard(current)
            Thread.currentThread().interrupt()
            return null
        } finally {
            current.request = null
        }
    }

    private fun startSession(): Session {
        val current = Session(ProcessBuilder(arguments).start())
        Thread({
            try {
                BufferedReader(InputStreamReader(current.process.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        val request = current.request ?: continue
                        if (line.startsWith("${request.marker}:")) {
                            request.exitCode = line.substringAfterLast(':').toIntOrNull()
                            request.finished.countDown()
                        } else {
                            val remaining = MAX_OUTPUT_CHARS - request.output.length
                            if (remaining > 0) request.output.append(line.take(remaining)).append('\n')
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                // Session retirement closes stdout.
            } finally { current.request?.finished?.countDown() }
        }, "MiHotspotHap-RootOutput").apply { isDaemon = true; start() }
        Thread({
            runCatching { current.process.errorStream.use { stream ->
                val bytes = ByteArray(4096)
                while (stream.read(bytes) >= 0) { /* Never let stderr backpressure stop su. */ }
            } }
        }, "MiHotspotHap-RootError").apply { isDaemon = true; start() }
        return current
    }

    private fun discard(current: Session) {
        if (session === current) session = null
        runCatching { current.process.destroyForcibly() }
        current.request?.finished?.countDown()
        Thread({
            runCatching { current.input.close() }
            runCatching { current.process.inputStream.close() }
            runCatching { current.process.errorStream.close() }
        }, "MiHotspotHap-RootClose").apply { isDaemon = true; start() }
    }

    override fun close() {
        closed = true
        session?.let(::discard)
    }
    private companion object { const val MAX_OUTPUT_CHARS = 1024 * 1024 }
}
