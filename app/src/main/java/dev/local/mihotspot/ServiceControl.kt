package dev.local.mihotspot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import java.util.Calendar

/** Single source of truth for the HomeKit driver switch. */
object ServiceControl {
    private const val PREFS = "homekit"
    private const val KEY_ENABLED = "driver_service_enabled"
    private const val KEY_BROADCAST_STRATEGY = "broadcast_strategy"
    private const val WATCHDOG_INTERVAL_MS = 30L * 60L * 1000L
    private const val NIGHT_START_HOUR = 23
    private const val DAY_START_HOUR = 7

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun broadcastStrategy(context: Context): BroadcastStrategy =
        BroadcastStrategy.fromStorage(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_BROADCAST_STRATEGY, BroadcastStrategy.AUTO.storageValue)
        )

    fun setBroadcastStrategy(context: Context, strategy: BroadcastStrategy) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BROADCAST_STRATEGY, strategy.storageValue).apply()
    }

    fun effectiveBroadcastStrategy(context: Context, paired: Boolean): BroadcastStrategy {
        val configured = broadcastStrategy(context)
        if (configured != BroadcastStrategy.AUTO) return configured
        return if (paired && isNightQuietWindow()) {
            BroadcastStrategy.LOW_POWER
        } else {
            // Before pairing and during daytime, prioritize quick discovery and
            // reconnects. Auto still enters low power overnight after pairing.
            BroadcastStrategy.LOW_LATENCY
        }
    }

    fun start(context: Context) {
        setEnabled(context, true)
        ensureRunning(context)
    }

    /** Start/re-arm without changing the user's persistent switch. */
    fun ensureRunning(context: Context, action: String? = null) {
        if (!isEnabled(context)) return
        scheduleWatchdog(context)
        scheduleBroadcastModeBoundary(context)
        val appContext = context.applicationContext
        val intent = Intent(appContext, HomeKitService::class.java).apply {
            action?.let { this.action = it }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    fun stop(context: Context) {
        setEnabled(context, false)
        cancelWatchdog(context)
        context.applicationContext.stopService(Intent(context.applicationContext, HomeKitService::class.java))
    }

    /**
     * Inexact repeating alarm: a low-frequency crash-recovery net, not a
     * polling loop. Android may batch it with other maintenance work.
     */
    fun scheduleWatchdog(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
            WATCHDOG_INTERVAL_MS,
            watchdogPendingIntent(context)
        )
    }

    /** Returns true during the quiet window. BLE remains connectable; only its
     * advertising interval is relaxed after pairing. */
    fun isNightQuietWindow(now: Calendar = Calendar.getInstance()): Boolean {
        val hour = now.get(Calendar.HOUR_OF_DAY)
        return hour >= NIGHT_START_HOUR || hour < DAY_START_HOUR
    }

    fun scheduleBroadcastModeBoundary(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (isNightQuietWindow(now)) {
                set(Calendar.HOUR_OF_DAY, DAY_START_HOUR)
                if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
            } else {
                set(Calendar.HOUR_OF_DAY, NIGHT_START_HOUR)
                if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val pending = modeBoundaryPendingIntent(context)
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
        } catch (_: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
        }
    }

    private fun modeBoundaryPendingIntent(context: Context): PendingIntent {
        val appContext = context.applicationContext
        val intent = Intent(appContext, BootReceiver::class.java).setAction(ACTION_BROADCAST_MODE_BOUNDARY)
        return PendingIntent.getBroadcast(
            appContext,
            MODE_BOUNDARY_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun Calendar.after(other: Calendar): Boolean = timeInMillis > other.timeInMillis

    fun cancelWatchdog(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(watchdogPendingIntent(context))
    }

    private fun watchdogPendingIntent(context: Context): PendingIntent {
        val appContext = context.applicationContext
        val intent = Intent(appContext, BootReceiver::class.java).setAction(ACTION_WATCHDOG)
        return PendingIntent.getBroadcast(
            appContext,
            WATCHDOG_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    const val ACTION_WATCHDOG = "dev.local.mihotspot.action.WATCHDOG"
    const val ACTION_BROADCAST_MODE_BOUNDARY = "dev.local.mihotspot.action.BROADCAST_MODE_BOUNDARY"

    private const val WATCHDOG_REQUEST_CODE = 7298
    private const val MODE_BOUNDARY_REQUEST_CODE = 7297
}
