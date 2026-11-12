package dev.local.mihotspot.peripheral

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Light per-key feedback for the on-screen keyboard.
 *
 * This phone's linear motor does not expose composition primitives, but it does
 * advertise the pre-baked transients CLICK / TICK / HEAVY_CLICK. Those are tuned
 * for this actuator and feel like a crisp collision, while a short
 * `createOneShot` amplitude ramp feels soft because the motor needs time to
 * spool up. So the preferred path is a pre-baked effect; a hard-attack waveform
 * is only the fallback.
 *
 * The strength setting maps to three transients:
 *   <= 20  TICK         (light)
 *   <= 70  CLICK        (crisp, default)
 *   >  70  HEAVY_CLICK  (firmer bottom-out)
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
        val strength = strength(context)
        if (playPredefined(vibrator, strength)) return
        playFallback(vibrator, strength)
    }

    private fun playPredefined(vibrator: Vibrator, strength: Int): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val effect = when {
                strength <= 20 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                strength <= 70 -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
                )
            } else {
                vibrator.vibrate(effect)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Pre-baked transients unavailable: a 4 ms attack still beats an 8 ms ramp. */
    private fun playFallback(vibrator: Vibrator, strength: Int) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && vibrator.hasAmplitudeControl()) {
                val amplitude = (255 * strength / 100).coerceIn(1, 255)
                val effect = VibrationEffect.createWaveform(
                    longArrayOf(0, 4, 3),
                    intArrayOf(0, amplitude, 0),
                    -1
                )
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(6L)
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
