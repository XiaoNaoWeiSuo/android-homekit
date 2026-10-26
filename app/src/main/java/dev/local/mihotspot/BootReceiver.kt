package dev.local.mihotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** BOOT_COMPLETED is delivered after first unlock; USER_UNLOCKED is dynamic-only. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                ServiceControl.ACTION_WATCHDOG,
                ServiceControl.ACTION_BROADCAST_MODE_BOUNDARY,
                "com.htc.intent.action.QUICKBOOT_POWERON"
            )) return
        try {
            if (context.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == false) {
                android.util.Log.i("MiHotspotHap", "SERVICE_RECOVERY_WAITING_FOR_UNLOCK action=${intent.action}")
                return
            }
            // AirDrop recovery does not depend on the HomeKit user switch.
            if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                try {
                    dev.local.mihotspot.airdrop.AirDropResidentService.recover(context)
                } catch (error: Exception) {
                    android.util.Log.e("MarionetteAirDrop", "RESIDENT_RECOVERY_FAILED", error)
                }
            }
            if (!ServiceControl.isEnabled(context)) return
            android.util.Log.i("MiHotspotHap", "SERVICE_RECOVERY_REQUEST action=${intent.action}")
            ServiceControl.ensureRunning(context, intent.action)
        } catch (error: Throwable) {
            android.util.Log.e("MiHotspotHap", "SERVICE_RECOVERY_FAILED action=${intent.action}", error)
        }
    }
}
