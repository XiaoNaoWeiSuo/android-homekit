package dev.local.mihotspot.data

import android.content.Context

data class HotspotConfiguration(
    val ssid: String,
    val password: String,
    val band: String,
    val wifi6Enabled: Boolean,
    val autoShutdownEnabled: Boolean,
    val shutdownTimeoutMinutes: Int,
    val powerMode: String
)

/** Single persisted source for the SoftAP configuration used by HomeKit. */
class HotspotSettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences("homekit", Context.MODE_PRIVATE)

    fun configuration(): HotspotConfiguration = HotspotConfiguration(
        ssid = preferences.getString(KEY_SSID, DEFAULT_SSID) ?: DEFAULT_SSID,
        password = preferences.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD,
        band = preferences.getString(KEY_BAND, DEFAULT_BAND) ?: DEFAULT_BAND,
        wifi6Enabled = preferences.getBoolean(KEY_WIFI6, true),
        autoShutdownEnabled = preferences.getBoolean(KEY_AUTO_SHUTDOWN, true),
        shutdownTimeoutMinutes = preferences.getInt(KEY_TIMEOUT_MINUTES, 10),
        powerMode = preferences.getString(KEY_POWER_MODE, POWER_BALANCED) ?: POWER_BALANCED
    )

    fun save(configuration: HotspotConfiguration) {
        preferences.edit()
            .putString(KEY_SSID, configuration.ssid)
            .putString(KEY_PASSWORD, configuration.password)
            .putString(KEY_BAND, configuration.band)
            .putBoolean(KEY_WIFI6, configuration.wifi6Enabled)
            .putBoolean(KEY_AUTO_SHUTDOWN, configuration.autoShutdownEnabled)
            .putInt(KEY_TIMEOUT_MINUTES, configuration.shutdownTimeoutMinutes)
            .putString(KEY_POWER_MODE, configuration.powerMode)
            .apply()
    }

    private companion object {
        const val KEY_SSID = "hotspot_ssid"
        const val KEY_PASSWORD = "hotspot_password"
        const val KEY_BAND = "hotspot_band"
        const val KEY_WIFI6 = "hotspot_wifi6"
        const val KEY_AUTO_SHUTDOWN = "hotspot_auto_shutdown"
        const val KEY_TIMEOUT_MINUTES = "hotspot_shutdown_timeout_minutes"
        const val KEY_POWER_MODE = "hotspot_power_mode"
        const val DEFAULT_SSID = "MiHotspot"
        const val DEFAULT_PASSWORD = "123987654"
        const val DEFAULT_BAND = "2"
        const val POWER_BALANCED = "balanced"
    }
}
