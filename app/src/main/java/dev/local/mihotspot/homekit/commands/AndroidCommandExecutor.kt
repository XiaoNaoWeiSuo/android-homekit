package dev.local.mihotspot.homekit.commands

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.Manifest
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** Android implementation kept independent from the HAP GATT server. */
class AndroidCommandExecutor(private val context: Context) : HomeKitCommandExecutor {
    private val values = mutableMapOf(
        HomeKitCommand.SCREEN to false,
        HomeKitCommand.BRIGHTNESS to false,
        HomeKitCommand.HOTSPOT to false,
        HomeKitCommand.MUTE to false,
        HomeKitCommand.FLASHLIGHT to false
    )
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var rootHotspotActive = false
    private var mediaVolumeBeforeMute: Int? = null

    @Synchronized
    override fun execute(command: HomeKitCommand, enabled: Boolean): CommandResult {
        val result = when (command) {
            HomeKitCommand.SCREEN -> toggleScreen()
            HomeKitCommand.BRIGHTNESS -> setScreenBrightness(if (enabled) 100 else 0)
            HomeKitCommand.HOTSPOT -> setHotspot(enabled)
            HomeKitCommand.MUTE -> setMediaMuted(enabled)
            HomeKitCommand.FLASHLIGHT -> setFlashlight(enabled)
        }
        if (result.success) values[command] = enabled
        return result
    }

    override fun executeValue(command: HomeKitCommand, value: Int): CommandResult = synchronized(this) {
        if (command != HomeKitCommand.BRIGHTNESS) return@synchronized execute(command, value != 0)
        setScreenBrightness(value.coerceIn(0, 100)).also { result ->
            if (result.success) values[command] = value > 0
        }
    }

    override fun currentValueInt(command: HomeKitCommand): Int? = synchronized(this) {
        if (command != HomeKitCommand.BRIGHTNESS) return@synchronized null
        val raw = try { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Throwable) {
            rootOutput("settings get system screen_brightness")?.trim()?.toIntOrNull() ?: return@synchronized null
        }
        (raw * 100 / 255).coerceIn(0, 100)
    }

    override fun currentValue(command: HomeKitCommand): Boolean = synchronized(this) {
        // HAP reads must describe the phone's current state, even when the
        // change was made outside HomeKit or before this process was started.
        when (command) {
            HomeKitCommand.SCREEN -> context.getSystemService(PowerManager::class.java)?.isInteractive
                ?: (values[command] == true)
            HomeKitCommand.BRIGHTNESS -> (currentValueInt(command) ?: 0) > 0
            HomeKitCommand.HOTSPOT -> isHotspotActive()
            HomeKitCommand.MUTE -> {
                val audio = context.getSystemService(AudioManager::class.java)
                audio?.isStreamMute(AudioManager.STREAM_MUSIC) == true ||
                    audio?.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 ||
                    audioMuteFromDump() == true
            }
            HomeKitCommand.FLASHLIGHT -> torchEnabled
        }
    }

    @Volatile private var torchEnabled = false
    private var torchCameraId: String? = null
    private var torchCallbackRegistered = false

    private fun setFlashlight(enabled: Boolean): CommandResult {
        if (android.os.Build.VERSION.SDK_INT < 23) return CommandResult(false, "torch API unavailable")
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return CommandResult(false, "camera permission required for flashlight")
        }
        val camera = context.getSystemService(CameraManager::class.java)
            ?: return CommandResult(false, "CameraManager unavailable")
        return try {
            val id = torchCameraId ?: camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return CommandResult(false, "no camera flashlight available")
            torchCameraId = id
            camera.setTorchMode(id, enabled)
            torchEnabled = enabled
            registerTorchCallback(camera)
            CommandResult(true, "flashlight=${if (enabled) "on" else "off"}")
        } catch (security: SecurityException) {
            CommandResult(false, "flashlight permission denied: ${security.message}")
        } catch (t: Throwable) {
            CommandResult(false, "flashlight failed: ${t.message}")
        }
    }

    private fun registerTorchCallback(camera: CameraManager) {
        if (torchCallbackRegistered) return
        torchCallbackRegistered = true
        camera.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == torchCameraId) torchEnabled = enabled
            }
            override fun onTorchModeUnavailable(cameraId: String) {
                if (cameraId == torchCameraId) torchEnabled = false
            }
        }, Handler(Looper.getMainLooper()))
    }

    @SuppressLint("WakelockTimeout")
    /** Emulates one physical power-button press; the current display state is
     * intentionally ignored so a Home tap always performs a toggle. */
    private fun toggleScreen(): CommandResult {
        if (runRoot("input keyevent 26")) return CommandResult(true, "screen=toggle (root power key)")
        val power = context.getSystemService(PowerManager::class.java)
            ?: return CommandResult(false, "PowerManager unavailable")
        if (power.isInteractive) {
            val policy = context.getSystemService(DevicePolicyManager::class.java)
                ?: return CommandResult(false, "DevicePolicyManager unavailable")
            return try {
                policy.lockNow()
                CommandResult(true, "screen=off (device admin)")
            } catch (security: SecurityException) {
                CommandResult(false, "screen toggle requires root or device-admin permission")
            }
        }
        @Suppress("DEPRECATION")
        val lock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "MiHotspotHap:HomeKitScreen"
        )
        lock.acquire(1500L)
        lock.release()
        return CommandResult(true, "screen=on")
    }

    private fun setScreenBrightness(percent: Int): CommandResult {
        val level = (percent.coerceIn(0, 100) * 255 / 100).coerceIn(0, 255)
        return try {
            val resolver = context.contentResolver
            val wrote = Settings.System.canWrite(context) &&
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) &&
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level)
            if (wrote || (runRoot("settings put system screen_brightness_mode ${Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL}") &&
                runRoot("settings put system screen_brightness $level"))) {
                CommandResult(true, "screen-brightness=$percent")
            } else {
                CommandResult(false, "screen brightness requires WRITE_SETTINGS permission (or root)")
            }
        } catch (security: SecurityException) {
            if (runRoot("settings put system screen_brightness $level")) {
                CommandResult(true, "screen-brightness=$percent (root)")
            } else {
                CommandResult(false, "screen brightness permission denied: ${security.message}")
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

    private fun rootOutput(command: String): String? = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        process.errorStream.close()
        val finished = process.waitFor(3, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        output.takeIf { finished && process.exitValue() == 0 }
    } catch (_: Throwable) {
        null
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

    /** Returns actual SoftAP state when root can query it, otherwise the API reservation state. */
    private fun isHotspotActive(): Boolean {
        if (hotspotReservation != null) return true
        // Xiaomi's `cmd wifi status` only describes client mode, even while a
        // SoftAP is up. dumpsys wifi exposes the SoftApManager instead, and is
        // also available after the process has been restarted (when our local
        // rootHotspotActive flag has naturally been lost).
        val softApDump = rootOutput("dumpsys wifi | grep -i -E 'soft.?ap|softapmanager'")
            ?.lowercase()
        if (!softApDump.isNullOrBlank()) {
            return softApDump.contains("started") || softApDump.contains("enabled") ||
                softApDump.contains("running") || softApDump.contains("active")
        }
        return rootHotspotActive
    }

    private fun setMediaMuted(enabled: Boolean): CommandResult {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return CommandResult(false, "AudioManager unavailable")
        return try {
            @Suppress("DEPRECATION")
            audio.setStreamMute(AudioManager.STREAM_MUSIC, enabled)
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC) == enabled || audioMuteFromDump() == enabled) {
                return CommandResult(true, "media-muted=$enabled")
            }
            // MIUI may ignore AudioManager.setStreamMute for third-party UIDs.
            // Root can use the platform media-session command; preserve the
            // previous index so turning mute off restores the user's volume.
            if (enabled) {
                mediaVolumeBeforeMute = audio.getStreamVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 }
                if (runRoot("cmd media_session volume --stream 3 --set 0")) {
                    CommandResult(true, "media-muted=true (root)")
                } else {
                    CommandResult(false, "media mute is blocked by the device audio policy")
                }
            } else {
                val restore = mediaVolumeBeforeMute ?: (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2).coerceAtLeast(1)
                if (runRoot("cmd media_session volume --stream 3 --set $restore")) {
                    mediaVolumeBeforeMute = null
                    CommandResult(true, "media-muted=false (root)")
                } else {
                    CommandResult(false, "media unmute is blocked by the device audio policy")
                }
            }
        } catch (security: SecurityException) {
            if (runRoot("cmd media_session volume --stream 3 --set ${if (enabled) 0 else 1}")) {
                CommandResult(true, "media-muted=$enabled (root)")
            } else {
                CommandResult(false, "media mute permission denied: ${security.message}")
            }
        }
    }

    private fun audioMuteFromDump(): Boolean? {
        val dump = rootOutput("dumpsys audio") ?: return null
        val music = dump.substringAfter("STREAM_MUSIC", "").substringBefore("- STREAM_")
        return when {
            music.contains("Muted: true", ignoreCase = true) -> true
            music.contains("Muted: false", ignoreCase = true) -> false
            else -> null
        }
    }
}
