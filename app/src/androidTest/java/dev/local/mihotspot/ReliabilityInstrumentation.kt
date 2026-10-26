package dev.local.mihotspot

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import dev.local.mihotspot.homekit.commands.*

/** Device checks use read-only shell queries and restore the exact brightness setting. */
class ReliabilityInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val results = Bundle()
        val executor = AndroidCommandExecutor(targetContext)
        val original = Settings.System.getInt(targetContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        val originalMode = Settings.System.getInt(targetContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        var resultCode = 0
        try {
            val query = "settings get global low_power"
            val cold = (0 until 5).map {
                val at = SystemClock.elapsedRealtime()
                check(BoundedShell.run(listOf("su", "-c", query))?.exitCode == 0)
                SystemClock.elapsedRealtime() - at
            }
            val warm = PersistentShell(listOf("su", "-c", "/system/bin/sh")).use { shell ->
                check(shell.run(query)?.exitCode == 0)
                (0 until 30).map {
                    val at = SystemClock.elapsedRealtime()
                    check(shell.run(query)?.exitCode == 0)
                    SystemClock.elapsedRealtime() - at
                }
            }
            val cached = SnapshotCommandExecutor(executor)
            for (command in HomeKitCommand.entries) cached.refresh(command)
            val writes = ArrayList<Long>()
            repeat(30) { index ->
                val at = SystemClock.elapsedRealtime()
                check(cached.executeValue(HomeKitCommand.BRIGHTNESS, 30 + index % 2).success)
                val observed = cached.currentValueInt(HomeKitCommand.BRIGHTNESS)!!
                check(kotlin.math.abs(observed - (30 + index % 2)) <= 1)
                check(executor.execute(HomeKitCommand.BRIGHTNESS, true).success)
                check(kotlin.math.abs(executor.currentValueInt(HomeKitCommand.BRIGHTNESS)!! - observed) <= 1)
                writes.add(SystemClock.elapsedRealtime() - at)
            }
            val cacheAt = SystemClock.elapsedRealtime()
            repeat(100) { check(cached.currentValueInt(HomeKitCommand.BRIGHTNESS) != null) }
            results.putString("stream", "coldRootMedianMs=${cold.sorted()[2]} warmRootMedianMs=${warm.sorted()[15]} warmRootP95Ms=${warm.sorted()[28]} brightnessWrites=30 failures=0 writeP95Ms=${writes.sorted()[28]} cachedReads100Ms=${SystemClock.elapsedRealtime() - cacheAt}")
        } catch (error: Throwable) {
            results.putString("stream", "FAILED ${error.stackTraceToString()}")
            resultCode = -1
        } finally {
            runCatching { BoundedShell.run(listOf("su", "-c", "settings put system screen_brightness $original; settings put system screen_brightness_mode $originalMode")) }
            executor.close()
        }
        finish(resultCode, results)
    }
}
