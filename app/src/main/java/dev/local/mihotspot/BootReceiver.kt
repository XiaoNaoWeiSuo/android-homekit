package dev.local.mihotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Starts the BLE runtime after boot or package replacement without opening UI. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED
            )) return
        val service = Intent(context, HomeKitService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service)
            else context.startService(service)
        } catch (error: Throwable) {
            android.util.Log.e("MiHotspotHap", "BOOT_SERVICE_START_FAILED", error)
        }
    }
}
