package dev.local.mihotspot.core.homekit.protocol

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** ATT and HAP nonces are ordered; a bounded queue never executes an expired request. */
internal class SerialGattQueue(
    capacity: Int = 16,
    private val deadlineMs: Long = 5000L
) : AutoCloseable {
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(capacity),
        { task -> Thread(task, "MiHotspotHap-Protocol").apply { isDaemon = true } })
    @Volatile private var activeSince = 0L
    val stalled: Boolean get() = activeSince != 0L &&
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - activeSince) > deadlineMs

    fun submit(expired: () -> Unit, action: () -> Unit) {
        val queued = System.nanoTime()
        try {
            executor.execute {
                if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queued) >= deadlineMs) expired()
                else {
                    activeSince = System.nanoTime()
                    try { action() } finally { activeSince = 0L }
                }
            }
        } catch (_: RejectedExecutionException) { expired() }
    }
    override fun close() { executor.shutdownNow() }
}
