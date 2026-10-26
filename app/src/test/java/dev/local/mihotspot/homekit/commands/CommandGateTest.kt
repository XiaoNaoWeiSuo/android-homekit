package dev.local.mihotspot.homekit.commands

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CommandGateTest {
    @Test fun nestedOperationsShareDeadlineAndRestoreOuterBudget() {
        CommandBudget.within(200L) {
            val outer = CommandBudget.remainingMs(3000L)
            CommandBudget.within(1000L) { assertTrue(CommandBudget.remainingMs(3000L) <= outer) }
            CommandBudget.within(20L) { assertTrue(CommandBudget.remainingMs(3000L) <= 20L) }
            assertTrue(CommandBudget.remainingMs(3000L) > 20L)
        }
        assertEquals(3000L, CommandBudget.remainingMs(3000L))
    }
    @Test fun readCannotStarveWaitingControl() {
        val gate = CommandGate()
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = CountDownLatch(1)
        val reader = Thread { gate.read { holding.countDown(); release.await() } }
        reader.start()
        assertTrue(holding.await(1, TimeUnit.SECONDS))
        val writer = Thread { gate.write { written.countDown() } }
        writer.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (writer.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield()
            assertThrows(ControlUnavailable::class.java) { gate.read { fail("poll stole the lock") } }
        } finally { release.countDown(); reader.join(1000); writer.join(1000) }
        assertEquals(0L, written.count)
    }
    @Test fun exceptionsReleaseLockForNextCommand() {
        val gate = CommandGate()
        assertThrows(IllegalStateException::class.java) { gate.write { error("fault") } }
        assertEquals("ok", gate.write { "ok" })
    }
    @Test fun interruptedQueuedControlDoesNotExecute() {
        val gate = CommandGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread { gate.read { entered.countDown(); release.await() } }
        holder.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        var executed = false
        var interrupted = false
        val writer = Thread {
            try { gate.write { executed = true } }
            catch (_: ControlUnavailable) { interrupted = Thread.currentThread().isInterrupted }
        }
        writer.start(); writer.interrupt(); writer.join(1000)
        release.countDown(); holder.join(1000)
        assertFalse(executed); assertTrue(interrupted)
    }
}
