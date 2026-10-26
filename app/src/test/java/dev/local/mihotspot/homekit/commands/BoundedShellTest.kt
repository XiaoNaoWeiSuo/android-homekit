package dev.local.mihotspot.homekit.commands

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class BoundedShellTest {
    private fun shell(command: String, timeoutMs: Long = 3000L) =
        BoundedShell.run(listOf("/bin/sh", "-c", command), timeoutMs)

    @Test fun readsSuccessfulOutputWithoutStderrContamination() {
        val result = shell("printf '42'; printf 'warning' >&2")!!
        assertEquals(0, result.exitCode)
        assertEquals("42", result.output)
    }

    @Test fun preservesFailureExitStatus() {
        assertEquals(7, shell("printf 'denied' >&2; exit 7")!!.exitCode)
    }

    @Test fun hungCommandReleasesCallerAndNextCommandWorks() {
        val started = System.nanoTime()
        // exec keeps this process as the actual child, as su normally does.
        assertNull(shell("printf 'partial'; exec sleep 5", 200L))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000L)
        assertEquals("ready", shell("printf 'ready'")!!.output)
    }

    @Test fun drainsLargeStdoutAndStderrWithoutPipeDeadlock() {
        val result = shell("head -c 2097152 /dev/zero; head -c 2097152 /dev/zero >&2")!!
        assertEquals(0, result.exitCode)
        assertEquals(1024 * 1024, result.output.length)
    }

    @Test fun inheritedPipeDoesNotBypassDeadlineAfterParentExits() {
        val started = System.nanoTime()
        assertNull(shell("sleep 3 & printf 'parent done'; exit 0", 200L))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000L)
    }

    @Test fun interruptedWaitPreservesInterruptFlag() {
        val thread = Thread.currentThread()
        thread.interrupt()
        try {
            assertNull(shell("exec sleep 5"))
            assertTrue(thread.isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
