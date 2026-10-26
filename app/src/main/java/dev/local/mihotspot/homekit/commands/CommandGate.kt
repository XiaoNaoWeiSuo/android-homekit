package dev.local.mihotspot.homekit.commands

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

internal class ControlUnavailable(message: String) : IllegalStateException(message)

/** One deadline includes lock wait and every shell operation in an action. */
internal object CommandBudget {
    private val deadline = ThreadLocal<Long>()
    fun remainingMs(defaultMs: Long): Long {
        val end = deadline.get() ?: return defaultMs
        return minOf(defaultMs, TimeUnit.NANOSECONDS.toMillis((end - System.nanoTime()).coerceAtLeast(0)))
    }
    fun <T> within(timeoutMs: Long, action: () -> T): T {
        val previous = deadline.get()
        val next = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        deadline.set(previous?.let { minOf(it, next) } ?: next)
        try { return action() } finally {
            if (previous == null) deadline.remove() else deadline.set(previous)
        }
    }
}

/** Fair writes; background reads yield whenever a control is waiting. */
internal class CommandGate {
    private val lock = ReentrantLock(true)
    private val waitingWrites = AtomicInteger()
    @Volatile private var activeSince = 0L
    val stalled: Boolean get() = activeSince != 0L && System.nanoTime() - activeSince > TimeUnit.SECONDS.toNanos(5)
    fun <T> write(action: () -> T): T {
        waitingWrites.incrementAndGet()
        try { return acquire(2500L, action) } finally { waitingWrites.decrementAndGet() }
    }
    fun <T> read(action: () -> T): T {
        if (!lock.isHeldByCurrentThread && waitingWrites.get() > 0) {
            throw ControlUnavailable("control waiting; state read deferred")
        }
        return acquire(600L, action)
    }
    private fun <T> acquire(timeoutMs: Long, action: () -> T): T = CommandBudget.within(timeoutMs) {
        val acquired = try {
            lock.tryLock(CommandBudget.remainingMs(timeoutMs), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ControlUnavailable("control interrupted")
        }
        if (!acquired) throw ControlUnavailable("control queue deadline exceeded")
        if (lock.holdCount == 1) activeSince = System.nanoTime()
        try { action() } finally {
            if (lock.holdCount == 1) activeSince = 0L
            lock.unlock()
        }
    }
}
