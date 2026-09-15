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
        val backlight = backlightNode()
        if (backlight != null) {
            val current = rootOutput("cat ${backlight.first}")?.trim()?.toIntOrNull()
            if (current != null && current > 0) return@synchronized (current * 100 / backlight.second).coerceIn(0, 100)
        }
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
                    mediaVolumeIndex() == 0 ||
                    audioMuteFromDump() == true
            }
            HomeKitCommand.FLASHLIGHT -> torchEnabled
        }
    }

    @Volatile private var torchEnabled = false
    private var torchCameraId: String? = null
    private var torchCallbackRegistered = false
    private var cachedBacklight: Pair<String, Int>? = null

    private fun setFlashlight(enabled: Boolean): CommandResult {
        if (android.os.Build.VERSION.SDK_INT < 23) return CommandResult(false, "torch API unavailable")
        // On Qualcomm/MIUI devices the torch is gated by led:switch_N and driven
        // by led:torch_N. This sysfs path is more reliable than CameraManager,
        // which MIUI may ignore for third-party UIDs; CameraManager stays as the
        // non-root fallback below.
        if (setTorchSysfs(enabled)) {
            torchEnabled = enabled
            return CommandResult(true, "flashlight=${if (enabled) "on" else "off"} (sysfs)")
        }
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

    private fun setTorchSysfs(enabled: Boolean): Boolean {
        val leds = rootOutput("ls /sys/class/leds/").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val torches = leds.filter { it.startsWith("led:torch_") }
        val switches = leds.filter { it.startsWith("led:switch_") }
        if (torches.isEmpty()) return false
        var ok = true
        // PM8550 flash driver latches the torch current at the moment the switch
        // is enabled: writing switch=1 with a zero current lights nothing, so the
        // current must be programmed before the switch (and cleared after it).
        if (enabled) {
            for (torch in torches) {
                val max = rootOutput("cat /sys/class/leds/$torch/max_brightness")?.trim()?.toIntOrNull() ?: 500
                ok = runRoot("echo $max > /sys/class/leds/$torch/brightness") && ok
            }
            for (switch in switches) ok = runRoot("echo 1 > /sys/class/leds/$switch/brightness") && ok
        } else {
            for (switch in switches) ok = runRoot("echo 0 > /sys/class/leds/$switch/brightness") && ok
            for (torch in torches) ok = runRoot("echo 0 > /sys/class/leds/$torch/brightness") && ok
        }
        return ok
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
        val pct = percent.coerceIn(0, 100)
        // Write the hardware backlight through sysfs first: on MIUI this is the
        // only path that is both immediate and unclamped. The Settings write is a
        // best-effort fallback for devices without a backlight node.
        val backlight = backlightNode()
        if (backlight != null) {
            val hw = ((maxOf(pct, 1).toLong() * backlight.second) / 100).toInt().coerceIn(0, backlight.second)
            if (runRoot("echo $hw > ${backlight.first}")) {
                return CommandResult(true, "screen-brightness=$pct (sysfs)")
            }
        }
        val level = pct * 255 / 100
        return try {
            val wrote = Settings.System.canWrite(context) &&
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
            if (wrote || runRoot("settings put system screen_brightness $level")) {
                CommandResult(true, "screen-brightness=$pct")
            } else {
                CommandResult(false, "screen brightness requires WRITE_SETTINGS permission (or root)")
            }
        } catch (t: Throwable) {
            CommandResult(false, "screen brightness failed: ${t.message}")
        }
    }

    private fun backlightNode(): Pair<String, Int>? {
        cachedBacklight?.let { return it }
        val dirs = rootOutput("ls /sys/class/backlight/ 2>/dev/null").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotBlank() }
        for (dir in dirs) {
            val max = rootOutput("cat /sys/class/backlight/$dir/max_brightness")?.trim()?.toIntOrNull()
            if (max != null && max > 0) {
                cachedBacklight = "/sys/class/backlight/$dir/brightness" to max
                return cachedBacklight
            }
        }
        return null
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
        // rootHotspotActive flag has naturally been lost). Only a live manager
        // reports curState=StartedState — stopped ones show curState=<QUIT>, and
        // always-present fields like "client_control_is_enabled=false" must not
        // be treated as proof that the AP is up.
        val dump = rootOutput("dumpsys wifi | grep curState=") ?: return rootHotspotActive
        return dump.lineSequence().any { it.trim().startsWith("curState=StartedState") }
    }

    private fun setMediaMuted(enabled: Boolean): CommandResult {
        // AudioManager.setStreamMute is deprecated and ignored by MIUI for
        // third-party UIDs, so drive the platform media-session volume directly
        // and read/restore the real index on MIUI's own 0..N scale.
        @Suppress("DEPRECATION")
        context.getSystemService(AudioManager::class.java)?.setStreamMute(AudioManager.STREAM_MUSIC, enabled)
        return if (enabled) {
            mediaVolumeBeforeMute = mediaVolumeState()?.first?.takeIf { it > 0 } ?: mediaVolumeBeforeMute
            if (runRoot("cmd media_session volume --stream 3 --set 0")) {
                CommandResult(true, "media-muted=true (root)")
            } else {
                CommandResult(false, "media mute is blocked by the device audio policy")
            }
        } else {
            val restore = mediaVolumeBeforeMute?.coerceAtLeast(1)
                ?: mediaVolumeState()?.let { (it.second / 2).coerceAtLeast(1) }
                ?: ((context.getSystemService(AudioManager::class.java)?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15) / 2).coerceAtLeast(1)
            if (runRoot("cmd media_session volume --stream 3 --set $restore")) {
                mediaVolumeBeforeMute = null
                CommandResult(true, "media-muted=false (root)")
            } else {
                CommandResult(false, "media unmute is blocked by the device audio policy")
            }
        }
    }

    /** Returns (current index, max index) for the media stream in MIUI's native scale. */
    private fun mediaVolumeState(): Pair<Int, Int>? {
        val out = rootOutput("cmd media_session volume --stream 3 --get") ?: return null
        val match = Regex("""volume is (\d+) in range \[0\.\.(\d+)]""").find(out) ?: return null
        val current = match.groupValues[1].toIntOrNull() ?: return null
        val max = match.groupValues[2].toIntOrNull() ?: return null
        return current to max
    }

    private fun mediaVolumeIndex(): Int? = mediaVolumeState()?.first

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
