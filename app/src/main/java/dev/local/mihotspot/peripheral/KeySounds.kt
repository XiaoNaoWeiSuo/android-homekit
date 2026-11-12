package dev.local.mihotspot.peripheral

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import kotlin.math.exp
import kotlin.math.sin

/**
 * Optional mechanical-keyboard click for the on-screen keys.
 *
 * The click is synthesized once into a small WAV in the cache directory, then
 * played through SoundPool (low latency, several overlapping presses). No audio
 * asset ships in the APK and no permission is required.
 */
object KeySounds {

    const val KEY_ENABLED = "key_sound_enabled"
    private const val VOLUME = 0.42f

    private var soundPool: SoundPool? = null
    private var soundId = 0
    private var loaded = false

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun play(context: Context) {
        if (!isEnabled(context)) return
        val pool = ensurePool(context) ?: return
        if (loaded && soundId != 0) {
            pool.play(soundId, VOLUME, VOLUME, 1, 0, 1f)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(BtHidPeripheral.PREFS, Context.MODE_PRIVATE)

    private fun ensurePool(context: Context): SoundPool? = synchronized(this) {
        soundPool?.let { return it }
        return try {
            val pool = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            pool.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
            val file = File(context.cacheDir, "key_click.wav")
            if (!file.exists() || file.length() < 44) writeClickWav(file)
            soundId = pool.load(file.absolutePath, 1)
            soundPool = pool
            pool
        } catch (e: Exception) {
            null
        }
    }

    /** 12 ms decaying click: 1.75 kHz body plus a little noise for "thock". */
    private fun writeClickWav(file: File) {
        val sampleRate = 44100
        val samples = sampleRate * 12 / 1000
        val pcm = ByteArray(samples * 2)
        var seed = 0x1234567
        for (i in 0 until samples) {
            val t = i.toDouble() / sampleRate
            val envelope = exp(-t * 260.0)
            seed = seed * 1103515245 + 12345
            val noise = (((seed shr 16) and 0x7FFF) / 32768.0) - 0.5
            val value = (sin(2 * Math.PI * 1750.0 * t) * 0.75 + noise * 0.35) *
                envelope * 0.6
            val sample = (value * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767)
            pcm[i * 2] = (sample and 0xFF).toByte()
            pcm[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }

        val header = ByteArray(44)
        fun putString(offset: Int, text: String) {
            text.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }
        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
            header[offset + 2] = ((value shr 16) and 0xFF).toByte()
            header[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xFF).toByte()
            header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        putString(0, "RIFF")
        putInt(4, 36 + pcm.size)
        putString(8, "WAVE")
        putString(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)            // PCM
        putShort(22, 1)            // mono
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2) // byte rate
        putShort(32, 2)            // block align
        putShort(34, 16)           // bits per sample
        putString(36, "data")
        putInt(40, pcm.size)

        file.outputStream().use { out ->
            out.write(header)
            out.write(pcm)
        }
    }
}
