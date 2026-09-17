package dev.local.mihotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the BLE runtime after boot, unlock, update, or a rare process death. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_USER_UNLOCKED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                ServiceControl.ACTION_WATCHDOG,
                ServiceControl.ACTION_BROADCAST_MODE_BOUNDARY,
                "com.htc.intent.action.QUICKBOOT_POWERON"
            )) return
        if (!ServiceControl.isEnabled(context)) return
        try {
            ServiceControl.ensureRunning(context, intent.action)
        } catch (error: Throwable) {
            android.util.Log.e("MiHotspotHap", "SERVICE_RECOVERY_FAILED action=${intent.action}", error)
        }
    }
}
