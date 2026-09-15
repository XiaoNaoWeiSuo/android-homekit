package dev.local.mihotspot.data

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import dev.local.mihotspot.domain.HomeKitFeature

/** Persistent accessory identity and configuration; no Android view dependency. */
class AccessorySettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences("homekit", Context.MODE_PRIVATE)

    val deviceName: String get() = preferences.getString("device_name", "Android") ?: "Android"
    val setupCodeDigits: String get() = preferences.getString("setup_code_digits", DEFAULT_SETUP_CODE_DIGITS) ?: DEFAULT_SETUP_CODE_DIGITS
    val isPaired: Boolean get() = preferences.contains("controller_key")

    fun saveDeviceName(name: String) = preferences.edit().putString("device_name", name).apply()
    fun saveSetupCode(digits: String) = preferences.edit().putString("setup_code_digits", digits).apply()

    fun clearPairing() = preferences.edit().remove("controller_id").remove("controller_key").apply()
    fun isFeatureEnabled(feature: HomeKitFeature): Boolean = preferences.getBoolean("command_enabled_${feature.command.name}", true)
    fun setFeatureEnabled(feature: HomeKitFeature, enabled: Boolean) = preferences.edit().putBoolean("command_enabled_${feature.command.name}", enabled).apply()

    fun resetAccessoryId(): String {
        val id = ByteArray(6).also(secureRandom::nextBytes)
        preferences.edit()
            .putString("device_id", Base64.encodeToString(id, Base64.NO_WRAP))
            .remove("controller_id")
            .remove("controller_key")
            .apply()
        return id.joinToString(":") { "%02X".format(it) }
    }

    fun accessoryId(): String {
        val encoded = preferences.getString("device_id", null)
        val bytes = try { encoded?.let { Base64.decode(it, Base64.NO_WRAP) } } catch (_: IllegalArgumentException) { null }
        return (bytes?.takeIf { it.size == 6 } ?: DEFAULT_ACCESSORY_ID).joinToString(":") { "%02X".format(it) }
    }

    private companion object {
        const val DEFAULT_SETUP_CODE_DIGITS = "47382915"
        val DEFAULT_ACCESSORY_ID = byteArrayOf(0x02, 0x13, 0x37, 0x42, 0x51, 0x6D)
        val secureRandom = SecureRandom()
    }
}
