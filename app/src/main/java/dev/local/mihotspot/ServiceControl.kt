package dev.local.mihotspot

import android.content.Context
import android.content.Intent
import android.os.Build

/** Single source of truth for the HomeKit driver switch. */
object ServiceControl {
    private const val PREFS = "homekit"
    private const val KEY_ENABLED = "driver_service_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun start(context: Context) {
        setEnabled(context, true)
        val intent = Intent(context, HomeKitService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    fun stop(context: Context) {
        setEnabled(context, false)
        context.stopService(Intent(context, HomeKitService::class.java))
    }
}
