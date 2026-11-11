package dev.local.mihotspot.peripheral

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Very light per-key vibration for the on-screen keyboard.
 *
 * Uses a short one-shot with a configurable amplitude instead of the system
 * KEYBOARD_TAP constant, because the system feedback is either absent (when the
 * user disabled touch sounds/haptics) or far too strong on HyperOS.
 */
object KeyHaptics {
    const val KEY_ENABLED = "key_haptics_enabled"
    const val KEY_STRENGTH = "key_haptics_strength" // 0..100

    private fun prefs(context: Context) =
        context.getSharedPreferences(BtHidPeripheral.PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun strength(context: Context): Int = prefs(context).getInt(KEY_STRENGTH, 30)

    fun setStrength(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_STRENGTH, value.coerceIn(1, 100)).apply()
    }

    fun tap(context: Context) {
        if (!isEnabled(context)) return
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        val amplitude = (255 * strength(context) / 100).coerceIn(1, 255)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(8L, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(8L)
            }
        } catch (e: Exception) {
            // A missing VIBRATE permission or a vendor-restricted motor is not
            // fatal: the key still works, it just does not buzz.
        }
    }

    private fun vibrator(context: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}
