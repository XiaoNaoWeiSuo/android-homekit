package dev.local.mihotspot.homekit.commands

import dev.local.mihotspot.homekit.controls.HomeKitControlModel
import dev.local.mihotspot.homekit.controls.HomeKitControlModels
import java.util.concurrent.ConcurrentHashMap

/** HAP reads share one recent real snapshot across a control's characteristics. */
internal class SnapshotCommandExecutor(
    private val delegate: HomeKitCommandExecutor,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }
) : HomeKitCommandExecutor {
    private data class Snapshot(val boolean: Boolean, val percentage: Int?, val capturedAt: Long)
    private val snapshots = ConcurrentHashMap<HomeKitCommand, Snapshot>()
    private val revisions = HomeKitCommand.entries.associateWith { java.util.concurrent.atomic.AtomicLong() }

    fun refresh(command: HomeKitCommand) {
        val revision = revisions.getValue(command).get()
        val model = HomeKitControlModels.all.first { it.command == command }
        val state = try {
            val percentage = if (model.valueType == HomeKitControlModel.ValueType.PERCENTAGE) {
                delegate.currentValueInt(command) ?: throw ControlUnavailable("state unavailable: $command")
            } else null
            Snapshot(percentage?.let { it > 0 } ?: delegate.currentValue(command), percentage, clockMs())
        } catch (error: Exception) {
            throw ControlUnavailable("state unavailable: $command (${error.javaClass.simpleName}:${error.message})")
        }
        // A read started before a write cannot resurrect its obsolete snapshot.
        synchronized(snapshots) {
            if (revision == revisions.getValue(command).get()) snapshots[command] = state
        }
    }

    private fun snapshot(command: HomeKitCommand): Snapshot {
        snapshots[command]?.takeIf { clockMs() - it.capturedAt <= MAX_AGE_MS }?.let { return it }
        refresh(command)
        return snapshots[command] ?: throw ControlUnavailable("state invalidated: $command")
    }

    private fun invalidate(command: HomeKitCommand) = synchronized(snapshots) {
        val related = if (command == HomeKitCommand.MUTE || command == HomeKitCommand.VOLUME) {
            listOf(HomeKitCommand.MUTE, HomeKitCommand.VOLUME)
        } else listOf(command)
        related.forEach { revisions.getValue(it).incrementAndGet(); snapshots.remove(it) }
    }

    override fun execute(command: HomeKitCommand, enabled: Boolean): CommandResult {
        // Invalidate before and after: polling may overlap the queued write.
        invalidate(command)
        return try { delegate.execute(command, enabled) } finally { invalidate(command) }
    }
    override fun executeValue(command: HomeKitCommand, value: Int): CommandResult {
        invalidate(command)
        return try { delegate.executeValue(command, value) } finally { invalidate(command) }
    }
    override fun currentValue(command: HomeKitCommand): Boolean = snapshot(command).boolean
    override fun currentValueInt(command: HomeKitCommand): Int? = snapshot(command).percentage
    private companion object { const val MAX_AGE_MS = 2000L }
}
