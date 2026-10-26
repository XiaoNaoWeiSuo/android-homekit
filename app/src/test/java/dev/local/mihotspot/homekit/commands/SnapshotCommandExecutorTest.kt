package dev.local.mihotspot.homekit.commands

import org.junit.Assert.*
import org.junit.Test

class SnapshotCommandExecutorTest {
    private class Fake : HomeKitCommandExecutor {
        var reads = 0; var writes = 0; var value = 40; var fail = false
        override fun currentValue(command: HomeKitCommand): Boolean { reads++; if (fail) throw ControlUnavailable("offline"); return value > 0 }
        override fun currentValueInt(command: HomeKitCommand): Int { reads++; if (fail) throw ControlUnavailable("offline"); return value }
        override fun execute(command: HomeKitCommand, enabled: Boolean): CommandResult { writes++; value = if (enabled) 40 else 0; return CommandResult(true, "applied") }
        override fun executeValue(command: HomeKitCommand, value: Int): CommandResult { writes++; this.value = value; return CommandResult(true, "applied") }
    }
    @Test fun percentageAndBooleanShareOneRealRead() {
        val backend = Fake(); val cached = SnapshotCommandExecutor(backend)
        assertEquals(40, cached.currentValueInt(HomeKitCommand.BRIGHTNESS))
        assertTrue(cached.currentValue(HomeKitCommand.BRIGHTNESS)); assertEquals(1, backend.reads)
    }
    @Test fun expiredSnapshotRefreshesInsteadOfStayingStale() {
        var now = 0L; val backend = Fake(); val cached = SnapshotCommandExecutor(backend) { now }
        assertEquals(40, cached.currentValueInt(HomeKitCommand.BRIGHTNESS))
        backend.value = 80; now = 2100L
        assertEquals(80, cached.currentValueInt(HomeKitCommand.BRIGHTNESS))
    }
    @Test fun writeInvalidatesRealStateAndExecutesOnce() {
        val backend = Fake(); val cached = SnapshotCommandExecutor(backend)
        assertEquals(40, cached.currentValueInt(HomeKitCommand.BRIGHTNESS))
        cached.executeValue(HomeKitCommand.BRIGHTNESS, 70)
        assertEquals(70, cached.currentValueInt(HomeKitCommand.BRIGHTNESS)); assertEquals(1, backend.writes)
    }
    @Test fun muteInvalidatesBothAudioControls() {
        val backend = Fake(); val cached = SnapshotCommandExecutor(backend)
        cached.currentValueInt(HomeKitCommand.VOLUME); cached.currentValue(HomeKitCommand.MUTE)
        cached.execute(HomeKitCommand.MUTE, false)
        assertEquals(0, cached.currentValueInt(HomeKitCommand.VOLUME))
        assertFalse(cached.currentValue(HomeKitCommand.MUTE))
    }
    @Test fun failedRefreshPreservesRecentKnownStateAndDoesNotInventZero() {
        var now = 0L; val backend = Fake(); val cached = SnapshotCommandExecutor(backend) { now }
        assertEquals(40, cached.currentValueInt(HomeKitCommand.BRIGHTNESS))
        backend.fail = true
        assertThrows(ControlUnavailable::class.java) { cached.refresh(HomeKitCommand.BRIGHTNESS) }
        assertEquals(40, cached.currentValueInt(HomeKitCommand.BRIGHTNESS))
        now = 3000L
        assertThrows(ControlUnavailable::class.java) { cached.currentValueInt(HomeKitCommand.BRIGHTNESS) }
    }
}
