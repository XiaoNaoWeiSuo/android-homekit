package dev.local.mihotspot.homekit.commands

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PersistentShellTest {
    @Test fun manyCommandsReuseOneProcessAndKeepTheirOutputSeparate() {
        PersistentShell(listOf("/bin/sh")).use { shell ->
            val pid = shell.run("printf '%s' \"\$\$\"")!!.output
            repeat(50) { assertEquals("$pid:$it", shell.run("printf '%s:$it' \"\$\$\"")!!.output) }
        }
    }
    @Test fun concurrentCallersNeverReceiveEachOthersFrames() {
        PersistentShell(listOf("/bin/sh")).use { shell ->
            val pool = Executors.newFixedThreadPool(6)
            try {
                val results = (0 until 60).map { value -> pool.submit<String> { shell.run("printf '$value'")!!.output } }
                results.forEachIndexed { index, result -> assertEquals(index.toString(), result.get(5, TimeUnit.SECONDS)) }
            } finally { pool.shutdownNow() }
        }
    }
    @Test fun preservesExitStatusAndDoesNotMixStderr() {
        PersistentShell(listOf("/bin/sh")).use { shell ->
            val result = shell.run("printf 'value'; printf 'warning' >&2; exit 7")!!
            assertEquals(7, result.exitCode)
            assertEquals("value", result.output)
            assertEquals("next", shell.run("printf 'next'")!!.output)
        }
    }
    @Test fun timeoutRebuildsSessionAndNeverReplaysSideEffect() {
        val path = Files.createTempFile("marionette-once", ".txt")
        try {
            PersistentShell(listOf("/bin/sh")).use { shell ->
                val started = System.nanoTime()
                assertNull(shell.run("echo once >> '$path'; exec sleep 2", 100L))
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000L)
                assertEquals("ready", shell.run("printf ready")!!.output)
                assertEquals(listOf("once"), Files.readAllLines(path))
            }
        } finally { Files.deleteIfExists(path) }
    }
    @Test fun largeOutputAndStderrCannotBlockFraming() {
        PersistentShell(listOf("/bin/sh")).use { shell ->
            assertEquals(0, shell.run("head -c 2097152 /dev/zero; head -c 2097152 /dev/zero >&2")!!.exitCode)
            assertEquals("ready", shell.run("printf ready")!!.output)
        }
    }
    @Test fun closedSessionRejectsNewActions() {
        val shell = PersistentShell(listOf("/bin/sh"))
        assertEquals("ready", shell.run("printf ready")!!.output)
        shell.close()
        assertNull(shell.run("printf unexpected"))
    }
}
