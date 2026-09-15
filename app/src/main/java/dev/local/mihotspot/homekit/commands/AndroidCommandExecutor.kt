package dev.local.mihotspot.homekit.commands

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.TimeUnit

/** Android implementation kept independent from the HAP GATT server. */
class AndroidCommandExecutor(private val context: Context) : HomeKitCommandExecutor {
    private val values = mutableMapOf(
        HomeKitCommand.SWITCH to false,
        HomeKitCommand.SCREEN to false,
        HomeKitCommand.HOTSPOT to false
    )
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var rootHotspotActive = false

    @Synchronized
    override fun execute(command: HomeKitCommand, enabled: Boolean): CommandResult {
        val result = when (command) {
            HomeKitCommand.SWITCH -> CommandResult(true, "switch=${if (enabled) "on" else "off"}")
            HomeKitCommand.SCREEN -> setScreen(enabled)
            HomeKitCommand.HOTSPOT -> setHotspot(enabled)
        }
        if (result.success) values[command] = enabled
        return result
    }

    override fun currentValue(command: HomeKitCommand): Boolean = synchronized(this) {
        values[command] == true
    }

    @SuppressLint("WakelockTimeout")
    private fun setScreen(enabled: Boolean): CommandResult {
        if (enabled) {
            val power = context.getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            val lock = power?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "MiHotspotHap:HomeKitScreen"
            ) ?: return CommandResult(false, "PowerManager unavailable")
            lock.acquire(1500L)
            lock.release()
            return CommandResult(true, "screen=on")
        }

        val policy = context.getSystemService(DevicePolicyManager::class.java)
            ?: return CommandResult(false, "DevicePolicyManager unavailable")
        return try {
            // lockNow() enforces device-admin activation and throws when the user
            // has not granted it; the transport reports that as a normal command
            // failure instead of dropping the HAP session.
            policy.lockNow()
            CommandResult(true, "screen=off")
        } catch (security: SecurityException) {
            if (runRoot("input keyevent 223")) {
                CommandResult(true, "screen=off (root)")
            } else {
                CommandResult(false, "screen=off requires device-admin permission (or root)")
            }
        }
    }

    private fun runRoot(command: String): Boolean = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        process.inputStream.close()
        process.errorStream.close()
        val finished = process.waitFor(3, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        finished && process.exitValue() == 0
    } catch (_: Throwable) {
        false
    }

    private fun setHotspot(enabled: Boolean): CommandResult {
        if (Build.VERSION.SDK_INT < 26) return CommandResult(false, "hotspot API unavailable")
        // On rooted devices prefer the platform shell SoftAP control. This is
        // the closest public equivalent to the Settings hotspot toggle; OEMs
        // may still restrict internet tethering separately.
        if (enabled && !rootHotspotActive && runRoot("cmd wifi start-softap MiHotspot wpa2 MiHotspot1234")) {
            rootHotspotActive = true
            return CommandResult(true, "hotspot=on (root SoftAP)")
        }
        if (!enabled && rootHotspotActive) {
            val stopped = runRoot("cmd wifi stop-softap")
            rootHotspotActive = false
            if (stopped) return CommandResult(true, "hotspot=off (root SoftAP)")
        }
        val wifi = context.getSystemService(WifiManager::class.java)
            ?: return CommandResult(false, "WifiManager unavailable")
        return try {
            if (enabled) {
                if (hotspotReservation != null) return CommandResult(true, "hotspot=on")
                wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        synchronized(this@AndroidCommandExecutor) { hotspotReservation = reservation }
                    }
                    override fun onStopped() {
                        synchronized(this@AndroidCommandExecutor) { hotspotReservation = null }
                    }
                    override fun onFailed(reason: Int) {
                        synchronized(this@AndroidCommandExecutor) { hotspotReservation = null }
                    }
                }, null)
                CommandResult(true, "hotspot=start-requested")
            } else {
                hotspotReservation?.close()
                hotspotReservation = null
                CommandResult(true, "hotspot=off")
            }
        } catch (security: SecurityException) {
            CommandResult(false, "hotspot permission denied: ${security.message}")
        }
    }
}
