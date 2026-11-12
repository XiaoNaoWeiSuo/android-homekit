package dev.local.mihotspot.peripheral.usb

import android.content.Context
import android.util.Base64
import android.util.Log
import dev.local.mihotspot.peripheral.HidProtocol
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

object UsbSession {
    const val PREFS = "usb_peripheral"
    const val KEY_TOKEN = "usb_token"
    const val KEY_ENABLED = "usb_enabled"
    const val KEY_CAMERA = "usb_camera"
    const val KEY_AUDIO = "usb_audio"
    const val KEY_CAMERA_SOURCE = "usb_camera_source"

    /**
     * Persisted record of the last published session state. The kernel gadget and
     * the root daemon outlive this process, so this is the only thing the UI can
     * consult after a process restart; [UsbPeripheralStatus] verifies it against
     * the real daemon before trusting it.
     */
    const val KEY_STATE = "usb_state"
    const val KEY_STATE_DETAIL = "usb_state_detail"
    const val KEY_STATE_HOST = "usb_state_host_attached"
    const val KEY_STATE_AT = "usb_state_at"
    /** Short user-facing reason shown on the home card (e.g. "拔线后自动开启"). */
    const val KEY_STATE_HINT = "usb_state_hint"

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(context: Context): String =
        prefs(context).getString(KEY_TOKEN, null) ?: newToken(context)

    fun newToken(context: Context): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun state(context: Context): String =
        prefs(context).getString(KEY_STATE, null) ?: "off"

    fun stateDetail(context: Context): String? =
        prefs(context).getString(KEY_STATE_DETAIL, null)

    fun hostAttached(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STATE_HOST, false)

    fun stateUpdatedAt(context: Context): Long =
        prefs(context).getLong(KEY_STATE_AT, 0L)

    fun stateHint(context: Context): String? =
        prefs(context).getString(KEY_STATE_HINT, null)

    fun setState(
        context: Context,
        state: String,
        hostAttached: Boolean,
        detail: String?,
        hint: String? = null
    ) {
        prefs(context).edit()
            .putString(KEY_STATE, state)
            .putBoolean(KEY_STATE_HOST, hostAttached)
            .putString(KEY_STATE_DETAIL, detail)
            .putString(KEY_STATE_HINT, hint)
            .putLong(KEY_STATE_AT, System.currentTimeMillis())
            .apply()
    }
}

/**
 * Owns the configfs composition. The daemon binary is packaged as a native
 * library (so the APK extracts it with exec permission) and copied to
 * /data/local/tmp before the root shell starts it.
 */
class UsbGadgetController(private val context: Context) {

    companion object {
        private const val TAG = "MiHotspotUsb"
        const val HID_PORT = 47831
        const val VIDEO_PORT = 47832
        const val MIC_PORT = 47833
        const val SPEAKER_PORT = 47834
        const val DAEMON = "/data/local/tmp/marionette_usbd"
        const val HID_REPORT = "/data/local/tmp/marionette_hid.bin"
        const val START_SCRIPT = "/data/local/tmp/marionette_usb_start.sh"
        const val STOP_SCRIPT = "/data/local/tmp/marionette_usb_stop.sh"
        const val UDC = "/sys/class/udc/a600000.dwc3/state"
    }

    data class Options(val camera: Boolean = false, val audio: Boolean = false)

    data class Status(
        val state: String = "unknown",
        val hidNode: String? = null,
        val videoNode: String? = null,
        val card: String? = null,
        val daemonPid: Int? = null,
        val raw: String = "",
        /**
         * VBUS is present even when only a charger (or a charge-only cable) is
         * attached. A charging cable and no cable look identical to the UDC,
         * which is why "waiting" used to be the only thing the UI could say.
         */
        val vbusPresent: Boolean = false,
        /** start.sh refused to run because a host currently holds the gadget. */
        val preflightAttached: Boolean = false,
        /** stop.sh refused to run because a host currently holds the gadget. */
        val preflightStop: Boolean = false,
        /** The controller never attached while VBUS was live: port is stuck. */
        val portWedged: Boolean = false,
        /** True while our gadget still owns the UDC binding (g1/UDC non-empty). */
        val gadgetBound: Boolean = false
    ) {
        val bound: Boolean
            get() = state == "configured" || state == "addressed" || state == "powered"
        // A bound gadget with no host attached is still a valid session: the
        // daemon stays up and the host can attach later. Only the daemon and the
        // HID function node are required for the session to be usable.
        val ready: Boolean get() = hidNode != null && daemonPid != null
    }

    fun start(token: String, options: Options): Status {
        pushHidDescriptor()
        pushDaemon()
        val script = asset("usb/start.sh")
            .replace("__TOKEN__", token)
            .replace("__ENABLE_CAMERA__", if (options.camera) "1" else "0")
            .replace("__ENABLE_AUDIO__", if (options.audio) "1" else "0")
        writeRootFile(START_SCRIPT, script)
        val out = runRoot("chmod 755 $START_SCRIPT; sh $START_SCRIPT")
        Log.i(TAG, "USB_START ${out.lineSequence().take(20).joinToString(" | ")}")
        return parse(out)
    }

    fun stop(): Status {
        val script = asset("usb/stop.sh")
        writeRootFile(STOP_SCRIPT, script)
        val out = runRoot("chmod 755 $STOP_SCRIPT; sh $STOP_SCRIPT")
        Log.i(TAG, "USB_STOP ${out.lineSequence().take(8).joinToString(" | ")}")
        return parse(out)
    }

    fun status(): Status {
        val command = """
            echo STATE=${'$'}(cat $UDC 2>/dev/null)
            echo VBUS=${'$'}(cat /sys/class/power_supply/usb/online 2>/dev/null)
            echo GBOUND=${'$'}(cat /config/usb_gadget/g1/UDC 2>/dev/null)
            echo HID=${'$'}(cat /config/usb_gadget/g1/functions/hid.marionette/dev 2>/dev/null)
            echo VIDEO=${'$'}(for v in /sys/class/video4linux/video*; do [ "${'$'}(cat ${'$'}v/name 2>/dev/null)" = "dwc3-gadget" ] && echo /dev/${'$'}(basename ${'$'}v); done | head -1)
            echo CARD=${'$'}(grep -q UAC2Gadget /proc/asound/cards 2>/dev/null && echo UAC2Gadget)
            echo PID=${'$'}(cat /data/local/tmp/marionette_usbd.pid 2>/dev/null)
        """.trimIndent()
        return parse(runRoot(command))
    }

    private fun parse(out: String): Status {
        fun value(key: String): String? = Regex("(?m)^$key=(.*)$")
            .find(out)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val hidDev = value("HID")
        val hidMinor = hidDev?.substringAfter(':', "")
        val hidNode = if (!hidMinor.isNullOrEmpty()) "/dev/hidg$hidMinor" else value("HID_NODE")
        return Status(
            state = value("STATE") ?: "unknown",
            hidNode = hidNode,
            videoNode = value("VIDEO"),
            card = value("CARD"),
            daemonPid = value("DAEMON")?.toIntOrNull() ?: value("PID")?.toIntOrNull(),
            raw = out,
            vbusPresent = value("VBUS") == "1",
            preflightAttached = out.contains("PREFLIGHT=host-attached"),
            preflightStop = out.contains("PREFLIGHT=host-attached-stop"),
            portWedged = out.contains("PORT=wedged"),
            gadgetBound = value("GBOUND") != null
        )
    }

    private fun pushHidDescriptor() {
        val b64 = Base64.encodeToString(HidProtocol.reportDescriptor, Base64.NO_WRAP)
        runRoot("printf %s '$b64' | base64 -d > $HID_REPORT")
    }

    private fun pushDaemon() {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libmarionette_usbd.so")
        require(binary.exists()) { "daemon binary missing: ${binary.absolutePath}" }
        // Never cp over a running executable: Linux returns ETXTBSY. Copy to a
        // sibling and rename atomically; the old process keeps its inode and the
        // start script kills it before launching the new binary.
        val tmp = "$DAEMON.new"
        val out = runRoot(
            "rm -f $tmp; cp '${binary.absolutePath}' $tmp && chmod 755 $tmp && " +
                "mv -f $tmp $DAEMON && echo COPIED"
        )
        require(out.contains("COPIED")) { "cannot copy daemon: $out" }
    }

    private fun asset(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    private fun writeRootFile(path: String, content: String) {
        val process = ProcessBuilder("su", "-c", "cat > $path").start()
        process.outputStream.use { it.write(content.toByteArray()) }
        process.waitFor(10, TimeUnit.SECONDS)
    }

    private fun runRoot(command: String): String = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(35, TimeUnit.SECONDS)
        text
    } catch (e: Exception) {
        "ERROR ${e.javaClass.simpleName}: ${e.message}"
    }
}
