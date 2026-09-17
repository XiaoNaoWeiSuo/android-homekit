package dev.local.mihotspot.homekit.commands

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.Manifest
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import dev.local.mihotspot.data.HotspotSettingsRepository

/** Android implementation kept independent from the HAP GATT server. */
class AndroidCommandExecutor(private val context: Context) : HomeKitCommandExecutor {
    private val values = mutableMapOf(
        HomeKitCommand.SCREEN to false,
        HomeKitCommand.BRIGHTNESS to false,
        HomeKitCommand.HOTSPOT to false,
        HomeKitCommand.MUTE to false,
        HomeKitCommand.FLASHLIGHT to false,
        HomeKitCommand.GPS to false,
        HomeKitCommand.LOW_POWER_MODE to false,
        HomeKitCommand.DO_NOT_DISTURB to false,
        HomeKitCommand.MOBILE_DATA to false,
        HomeKitCommand.VOLUME to false
    )
    private val hotspotSettings = HotspotSettingsRepository(context.applicationContext)
    private var rootHotspotActive = false
    private var mediaVolumeBeforeMute: Int? = null
    private var volumeBeforeControl: Int? = null

    @Synchronized
    override fun execute(command: HomeKitCommand, enabled: Boolean): CommandResult {
        val result = when (command) {
            HomeKitCommand.SCREEN -> toggleScreen()
            HomeKitCommand.BRIGHTNESS -> setScreenBrightness(if (enabled) 100 else 0)
            HomeKitCommand.HOTSPOT -> setHotspot(enabled)
            HomeKitCommand.MUTE -> setMediaMuted(enabled)
            HomeKitCommand.FLASHLIGHT -> setFlashlight(enabled)
            HomeKitCommand.GPS -> setGps(enabled)
            HomeKitCommand.LOW_POWER_MODE -> setLowPowerMode(enabled)
            HomeKitCommand.DO_NOT_DISTURB -> setDoNotDisturb(enabled)
            HomeKitCommand.MOBILE_DATA -> setMobileData(enabled)
            HomeKitCommand.VOLUME -> setVolume(if (enabled) 50 else 0)
        }
        if (result.success) values[command] = enabled
        return result
    }

    override fun executeValue(command: HomeKitCommand, value: Int): CommandResult = synchronized(this) {
        when (command) {
            HomeKitCommand.BRIGHTNESS -> setScreenBrightness(value.coerceIn(0, 100)).also { result ->
                if (result.success) values[command] = value > 0
            }
            HomeKitCommand.VOLUME -> setVolume(value.coerceIn(0, 100)).also { result ->
                if (result.success) values[command] = value > 0
            }
            else -> execute(command, value != 0)
        }
    }

    override fun currentValueInt(command: HomeKitCommand): Int? = synchronized(this) {
        when (command) {
            HomeKitCommand.BRIGHTNESS -> {
                // HomeKit's percentage is the Android user brightness level,
                // not the panel driver's raw PWM value. MIUI applies a
                // device-specific gamma curve between the two, so reading the
                // backlight node linearly makes a requested 40% come back as
                // roughly 80% (or 100%).
                val raw = try {
                    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                } catch (_: Throwable) {
                    rootOutput("settings get system screen_brightness")?.trim()?.toIntOrNull()
                }
                raw?.let { return@synchronized (((it - 1).coerceAtLeast(0) * 100) / 254).coerceIn(0, 100) }
                val backlight = backlightNode() ?: return@synchronized null
                val current = rootOutput("cat ${backlight.first}")?.trim()?.toIntOrNull() ?: return@synchronized null
                val normalMax = normalBacklightMax(backlight.second)
                return@synchronized ((current.coerceAtLeast(0) * 100) / normalMax).coerceIn(0, 100)
            }
            HomeKitCommand.VOLUME -> {
                val volumeState = mediaVolumeState()
                if (volumeState != null) {
                    val (current, max) = volumeState
                    return@synchronized (current * 100 / max).coerceIn(0, 100)
                }
                null
            }
            else -> null
        }
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
            HomeKitCommand.FLASHLIGHT -> torchEnabled || isTorchSysfsEnabled()
            HomeKitCommand.GPS -> isGpsActive()
            HomeKitCommand.LOW_POWER_MODE -> isLowPowerModeActive()
            HomeKitCommand.DO_NOT_DISTURB -> isDoNotDisturbActive()
            HomeKitCommand.MOBILE_DATA -> isMobileDataEnabled()
            HomeKitCommand.VOLUME -> (currentValueInt(command) ?: 0) > 0
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

    /** Read the kernel torch switch so external changes are reflected in the UI. */
    private fun isTorchSysfsEnabled(): Boolean {
        val switches = rootOutput("ls /sys/class/leds/").orEmpty()
            .lineSequence().map { it.trim() }
            .filter { it.startsWith("led:switch_") }
            .toList()
        return switches.any { switch ->
            rootOutput("cat /sys/class/leds/$switch/brightness")
                ?.trim()?.toIntOrNull()?.let { it > 0 } == true
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
        val pct = percent.coerceIn(0, 100)
        // Android exposes a documented 1..255 settings range, but on MIUI a
        // successful settings write can leave the actual panel unchanged.
        // The Android user setting is the canonical HomeKit percentage. MIUI
        // maps it to the panel using its own calibration/gamma curve; writing
        // a linear raw panel value makes the slider and readback disagree.
        val level = 1 + (pct * 254 + 50) / 100
        return try {
            val backlight = backlightNode()

            // canWrite()/putInt() may throw when WRITE_SETTINGS is not declared
            // or its AppOp is denied. That is only a failed optional route; it
            // must never prevent the rooted route from running.
            val settingsApiUpdated = try {
                Settings.System.canWrite(context) &&
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) &&
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
            } catch (_: SecurityException) {
                false
            } catch (_: Throwable) {
                false
            }
            val settingUpdated = settingsApiUpdated || runRoot(
                "settings put system screen_brightness_mode 0; settings put system screen_brightness $level"
            )

            val settingActual = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Throwable) {
                rootOutput("settings get system screen_brightness")?.trim()?.toIntOrNull()
            }
            if (settingUpdated) {
                val panelActual = backlight?.let { rootOutput("cat ${it.first}")?.trim()?.toIntOrNull() }
                return CommandResult(
                    true,
                    "screen-brightness=$pct (settings level=$level readback=$settingActual panel=$panelActual)"
                )
            }

            // Only use a raw panel write when the Android setting route is not
            // available at all. This is a fallback for unusual rooted ROMs;
            // it is deliberately not used together with MIUI's setting path.
            val hardwareTarget = backlight?.let {
                val normalMax = normalBacklightMax(it.second)
                if (pct == 0) 0 else 1 + ((normalMax - 1).toLong() * pct / 100L).toInt()
            }
            val hardwareCommandOk = if (backlight != null && hardwareTarget != null) {
                runRoot("echo $hardwareTarget > ${backlight.first}")
            } else false
            val hardwareActual = backlight?.let { rootOutput("cat ${it.first}")?.trim()?.toIntOrNull() }
            val hardwareUpdated = hardwareCommandOk && hardwareActual != null

            when {
                hardwareUpdated -> CommandResult(true, "screen-brightness=$pct (panel fallback target=$hardwareTarget actual=$hardwareActual)")
                else -> CommandResult(false, "screen brightness requires WRITE_SETTINGS permission (or root)")
            }
        } catch (t: Throwable) {
            CommandResult(false, "screen brightness failed: ${t.message}")
        }
    }

    /** Keep normal HomeKit 0..100 brightness below Xiaomi's HBM range. */
    private fun normalBacklightMax(maxBrightness: Int): Int = (maxBrightness / 2).coerceAtLeast(1)

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
        // There must be one owner. The old LocalOnlyHotspot fallback created a
        // second, isolated AP beside the system tethered hotspot. Always stop
        // any existing SoftAP first, then start exactly one configured AP.
        if (!enabled) {
            val stopped = runRoot("cmd wifi stop-softap")
            rootHotspotActive = false
            return if (stopped) CommandResult(true, "hotspot=off (single SoftAP owner)")
            else CommandResult(false, "hotspot stop requires root")
        }
        val configuration = hotspotSettings.configuration()
        if (configuration.ssid.isBlank() || configuration.ssid.toByteArray().size > 32) {
            return CommandResult(false, "hotspot SSID must be 1..32 bytes")
        }
        if (configuration.password.toByteArray().size !in 8..63) {
            return CommandResult(false, "hotspot password must be 8..63 bytes")
        }
        if (!runRoot("cmd wifi stop-softap")) {
            return CommandResult(false, "hotspot cleanup requires root")
        }
        val band = configuration.band.takeIf { it in setOf("2", "5", "any", "bridged") } ?: "2"
        val command = "cmd wifi start-softap ${shellQuote(configuration.ssid)} wpa2 ${shellQuote(configuration.password)} -b $band"
        if (runRoot(command)) {
            rootHotspotActive = true
            return CommandResult(true, "hotspot=on ssid=${configuration.ssid} band=$band")
        }
        rootHotspotActive = false
        return CommandResult(false, "hotspot start failed; check system Wi-Fi tethering restrictions")
    }

    /** Returns actual SoftAP state when root can query it, otherwise the API reservation state. */
    private fun isHotspotActive(): Boolean {
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

    /**
     * Mobile-data writes are privileged on Android. On this rooted device the
     * platform-supported shell path is the reliable route: update the global
     * setting and ask telephony to apply it through svc data.
     */
    private fun setMobileData(enabled: Boolean): CommandResult {
        val value = if (enabled) 1 else 0
        val settingUpdated = runRoot("settings put global mobile_data $value")
        val serviceUpdated = runRoot("svc data ${if (enabled) "enable" else "disable"}")
        val actual = isMobileDataEnabled()
        return if (settingUpdated && serviceUpdated && actual == enabled) {
            CommandResult(true, "mobile-data=${if (enabled) "on" else "off"} (root)")
        } else if (serviceUpdated && actual == enabled) {
            CommandResult(true, "mobile-data=${if (enabled) "on" else "off"} (svc data)")
        } else {
            CommandResult(false, "mobile data requires root or MODIFY_PHONE_STATE (requested=$enabled actual=$actual)")
        }
    }

    /** Reads the user mobile-data setting, not merely whether a network is currently connected. */
    private fun isMobileDataEnabled(): Boolean {
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA)) {
            try {
                context.getSystemService(TelephonyManager::class.java)?.let { return it.isDataEnabled }
            } catch (_: SecurityException) {
                // Fall through to the rooted settings read below.
            } catch (_: UnsupportedOperationException) {
                // Fall through on devices that expose no usable data modem.
            }
        }
        return rootOutput("settings get global mobile_data")
            ?.trim()
            ?.let {
                when {
                    it == "1" || it.equals("true", ignoreCase = true) -> true
                    it == "0" || it.equals("false", ignoreCase = true) -> false
                    else -> null
                }
            }
            ?: (values[HomeKitCommand.MOBILE_DATA] == true)
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

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

    private fun setGps(enabled: Boolean): CommandResult {
        // GPS/Location toggle - requires root or location settings permission
        return if (enabled) {
            if (runRoot("settings put secure location_mode 3")) {
                CommandResult(true, "gps=on (root)")
            } else {
                // Try non-root approach
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    CommandResult(true, "gps=on (settings opened)")
                } catch (t: Throwable) {
                    CommandResult(false, "gps failed: ${t.message}")
                }
            }
        } else {
            if (runRoot("settings put secure location_mode 0")) {
                CommandResult(true, "gps=off (root)")
            } else {
                CommandResult(false, "gps requires root or location settings permission")
            }
        }
    }

    private fun isGpsActive(): Boolean {
        val mode = rootOutput("settings get secure location_mode")?.trim()?.toIntOrNull()
        return mode != null && mode > 0
    }

    private fun setLowPowerMode(enabled: Boolean): CommandResult {
        // Low power mode via settings global low_power (works on MIUI)
        return if (enabled) {
            if (runRoot("settings put global low_power 1")) {
                CommandResult(true, "low-power-mode=on")
            } else {
                CommandResult(false, "low-power-mode requires root")
            }
        } else {
            if (runRoot("settings put global low_power 0")) {
                CommandResult(true, "low-power-mode=off")
            } else {
                CommandResult(false, "low-power-mode requires root")
            }
        }
    }

    private fun isLowPowerModeActive(): Boolean {
        val mode = rootOutput("settings get global low_power")?.trim()
        return mode == "1"
    }

    private fun setDoNotDisturb(enabled: Boolean): CommandResult {
        // DND via cmd notification set_dnd (works on MIUI)
        return if (enabled) {
            if (runRoot("cmd notification set_dnd priority")) {
                CommandResult(true, "dnd=on")
            } else {
                CommandResult(false, "dnd requires root")
            }
        } else {
            if (runRoot("cmd notification set_dnd off")) {
                CommandResult(true, "dnd=off")
            } else {
                CommandResult(false, "dnd requires root")
            }
        }
    }

    private fun isDoNotDisturbActive(): Boolean {
        val dump = rootOutput("dumpsys notification") ?: return false
        return dump.contains("mZenMode=ZEN_MODE_IMPORTANT_INTERRUPTIONS") ||
            dump.contains("mZenMode=ZEN_MODE_NO_INTERRUPTIONS")
    }

    private fun setVolume(percent: Int): CommandResult {
        val pct = percent.coerceIn(0, 100)
        val volumeState = mediaVolumeState()
        val maxVolume = volumeState?.second ?: 15
        val targetVolume = (pct * maxVolume / 100).coerceIn(0, maxVolume)
        if (pct > 0 && volumeBeforeControl == null) volumeBeforeControl = volumeState?.first
        return if (runRoot("cmd media_session volume --stream 3 --set $targetVolume")) {
            if (pct == 0) volumeBeforeControl = null
            CommandResult(true, "volume=$pct (root)")
        } else {
            val audio = context.getSystemService(AudioManager::class.java)
            if (audio != null) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
                CommandResult(true, "volume=$pct (AudioManager)")
            } else CommandResult(false, "volume control failed")
        }
    }

}
