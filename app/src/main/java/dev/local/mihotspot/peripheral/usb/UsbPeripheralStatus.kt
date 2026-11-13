package dev.local.mihotspot.peripheral.usb

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Single source of truth for "can this phone type into a host over USB right now".
 *
 * The old code asked [UsbPeripheralService.running], a process-local static. That
 * boolean says nothing after a process restart: the service dies with the process
 * while the gadget (configfs) and the root daemon keep running, so the home card
 * hid the peripheral entry and the pad fell back to Bluetooth — a live USB session
 * that silently did nothing.
 *
 * Resolution order:
 *  1. the in-process service, authoritative while this process owns it;
 *  2. the persisted [UsbSession] record (written on every state change);
 *  3. a kernel/daemon probe ([UsbGadgetController.status], root, off the main
 *     thread) that either adopts a surviving session or retires a stale record.
 *
 * Reads from the UI are cheap and non-blocking; only [verify] shells out.
 */
object UsbPeripheralStatus {

    private const val TAG = "MiHotspotUsb"

    /** A verified snapshot is reused for this long; avoids a root shell per resume. */
    private const val VERIFY_CACHE_MS = 4_000L

    /**
     * A start was requested but the service has not published yet. Within this
     * window [verify] must not declare the session dead — provisioning routinely
     * takes several seconds and the UI may resume in the middle of it.
     */
    private const val START_GRACE_MS = 30_000L

    data class Snapshot(
        val active: Boolean,
        val hostAttached: Boolean,
        val pending: Boolean,
        val enabled: Boolean,
        val state: String,
        val detail: String?,
        /** Short user-facing reason, when the state needs explaining. */
        val hint: String? = null
    )

    @Volatile private var liveState: String? = null
    @Volatile private var liveDetail: String? = null
    @Volatile private var liveHint: String? = null
    @Volatile private var liveHostAttached = false
    @Volatile private var liveReady = false
    @Volatile private var startRequestedAt = 0L
    @Volatile private var cached: Snapshot? = null
    @Volatile private var cachedAt = 0L

    fun off(): Snapshot = Snapshot(
        active = false,
        hostAttached = false,
        pending = false,
        enabled = false,
        state = "off",
        detail = null
    )

    /** Recorded by [UsbPeripheralService.start] before it touches configfs. */
    fun markStartRequested() {
        startRequestedAt = SystemClock.elapsedRealtime()
    }

    private fun startingRecently(): Boolean =
        startRequestedAt != 0L && SystemClock.elapsedRealtime() - startRequestedAt < START_GRACE_MS

    /**
     * Published by [UsbPeripheralService] whenever it learns a new session state.
     * [hostAttached] is left untouched when null (e.g. a camera-only error).
     */
    fun onServiceState(
        context: Context,
        state: String,
        detail: String?,
        hostAttached: Boolean?,
        hint: String? = null
    ) {
        startRequestedAt = 0L
        liveState = state
        liveDetail = detail
        liveHint = hint
        if (hostAttached != null) liveHostAttached = hostAttached
        liveReady = state == "ready" || state == "waiting" ||
            state == "armed" || state == "armed_stop"
        UsbSession.setState(context, state, liveHostAttached, detail, hint)
        if (liveReady && state != "armed_stop") UsbSession.setEnabled(context, true)
        cache(serviceSnapshot(context))
    }

    /** Published by [UsbPeripheralService.onDestroy] once the session is gone. */
    fun onServiceStopped(context: Context) {
        startRequestedAt = 0L
        liveState = "off"
        liveDetail = null
        liveHint = null
        liveReady = false
        liveHostAttached = false
        UsbSession.setState(context, "off", false, null)
        cache(off().copy(enabled = UsbSession.isEnabled(context)))
        // The home entry listens for this: a stop must hide it immediately
        // instead of waiting for the next refresh tick.
        context.sendBroadcast(
            Intent(UsbPeripheralService.ACTION_STATE)
                .setPackage(context.packageName)
                .putExtra(UsbPeripheralService.EXTRA_STATE, "off")
        )
    }

    private fun serviceSnapshot(context: Context): Snapshot = Snapshot(
        active = liveReady,
        hostAttached = liveHostAttached,
        pending = !liveReady && liveState != "error",
        enabled = UsbSession.isEnabled(context),
        state = liveState ?: "off",
        detail = liveDetail,
        hint = liveHint
    )

    /** Cheap, main-thread safe: memory and SharedPreferences only, never root. */
    fun snapshot(context: Context): Snapshot {
        if (UsbPeripheralService.servicePresent && liveState != null && liveState != "off") {
            return serviceSnapshot(context)
        }
        if (startingRecently() && UsbSession.isEnabled(context)) {
            return off().copy(pending = true, enabled = true, state = "starting")
        }
        val cachedValue = cached
        if (cachedValue != null &&
            SystemClock.elapsedRealtime() - cachedAt <= VERIFY_CACHE_MS
        ) {
            return cachedValue
        }
        // No in-process service: fall back to the last published record so a
        // still-live session keeps its entry and transport until [verify] runs.
        // Host attachment is deliberately NOT taken from that record: it is a
        // snapshot from before the process died (often before the cable was
        // pulled), and presenting it as current is what made the UI claim a
        // connected host with the cable out. It stays false until a real probe
        // confirms it.
        val enabled = UsbSession.isEnabled(context)
        val state = UsbSession.state(context)
        val active = enabled && (
            state == "ready" || state == "waiting" ||
                state == "armed" || state == "armed_stop"
            )
        return Snapshot(
            active = active,
            hostAttached = false,
            pending = enabled && !active,
            enabled = enabled,
            state = if (active) state else "off",
            detail = UsbSession.stateDetail(context),
            hint = UsbSession.stateHint(context)
        )
    }

    /**
     * Blocking: probes the real daemon/gadget state. Must not run on the main
     * thread. A surviving session is adopted (never re-provisioned); a dead one
     * only has its persisted record cleared — configfs is never written here,
     * because unbinding a gadget can wedge the vendor kernel.
     */
    fun verify(context: Context): Snapshot {
        if (UsbPeripheralService.servicePresent || startingRecently()) return snapshot(context)
        if (!UsbSession.isEnabled(context)) {
            UsbSession.setState(context, "off", false, null)
            return cache(off())
        }
        val status = try {
            UsbGadgetController(context).status()
        } catch (e: Throwable) {
            Log.i(TAG, "USB_STATUS_PROBE_FAILED ${e.javaClass.simpleName}: ${e.message}")
            // Probe failed: keep the remembered view instead of inventing "off".
            return cache(snapshot(context))
        }
        if (!status.ready) {
            Log.i(TAG, "USB_SESSION_STALE ${status.raw}")
            UsbSession.setEnabled(context, false)
            UsbSession.setState(context, "off", false, null)
            return cache(off())
        }
        val hostAttached = status.bound
        val state = if (hostAttached) "ready" else "waiting"
        Log.i(TAG, "USB_SESSION_ADOPTED state=$state hid=${status.hidNode}")
        UsbSession.setEnabled(context, true)
        UsbSession.setState(context, state, hostAttached, status.raw)
        liveState = state
        liveDetail = status.raw
        liveHostAttached = hostAttached
        liveReady = true
        // Re-own the surviving session with its foreground notification. This
        // path only reads configfs; it never re-runs the provisioning script.
        UsbPeripheralService.attach(context)
        return cache(
            Snapshot(
                active = true,
                hostAttached = hostAttached,
                pending = false,
                enabled = true,
                state = state,
                detail = status.raw
            )
        )
    }

    private fun cache(value: Snapshot): Snapshot {
        cached = value
        cachedAt = SystemClock.elapsedRealtime()
        return value
    }
}
