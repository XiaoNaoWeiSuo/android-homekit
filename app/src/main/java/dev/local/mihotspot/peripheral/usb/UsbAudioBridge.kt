package dev.local.mihotspot.peripheral.usb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridges the phone's microphone and speaker to the UAC2 gadget through the
 * root daemon. PCM is raw S16LE, 48 kHz, mono on the mic side and stereo on the
 * host-playback side.
 */
class UsbAudioBridge(private val token: String) {

    companion object {
        private const val TAG = "MiHotspotUsb"
        private const val RATE = 48000
        private const val MIC_PORT = UsbGadgetController.MIC_PORT
        private const val SPK_PORT = UsbGadgetController.SPEAKER_PORT
        private const val MIC_CHUNK = 960 * 2      // 20 ms mono S16
        private const val SPK_CHUNK = 1920 * 4     // 20 ms stereo S16
    }

    private val running = AtomicBoolean(false)
    private var micThread: Thread? = null
    private var spkThread: Thread? = null
    @Volatile private var speaker = true
    private var audioManager: AudioManager? = null

    val isRunning: Boolean get() = running.get()

    fun start(context: Context, speaker: Boolean) {
        if (!running.compareAndSet(false, true)) return
        this.speaker = speaker
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "USB_AUDIO_NO_RECORD_PERMISSION")
            running.set(false)
            return
        }
        audioManager = context.getSystemService(AudioManager::class.java)
        applyRoute()
        micThread = Thread({ micLoop(context) }, "usb-mic").apply { isDaemon = true; start() }
        spkThread = Thread({ spkLoop() }, "usb-spk").apply { isDaemon = true; start() }
        Log.i(TAG, "USB_AUDIO_START speaker=$speaker")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            micThread?.join(600)
            spkThread?.join(600)
        } catch (_: InterruptedException) {
        }
        micThread = null
        spkThread = null
        try {
            audioManager?.mode = AudioManager.MODE_NORMAL
            audioManager?.isSpeakerphoneOn = false
        } catch (_: Exception) {
        }
        audioManager = null
        Log.i(TAG, "USB_AUDIO_STOP")
    }

    private fun applyRoute() {
        val am = audioManager ?: return
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = speaker
        } catch (e: Exception) {
            Log.i(TAG, "USB_AUDIO_ROUTE_FAILED ${e.message}")
        }
    }

    private fun micLoop(context: Context) {
        var record: AudioRecord? = null
        var socket: Socket? = null
        var out: BufferedOutputStream? = null
        try {
            val min = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, MIC_CHUNK * 4)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "USB_MIC_INIT_FAILED")
                return
            }
            record.startRecording()
            val buffer = ByteArray(MIC_CHUNK)
            while (running.get()) {
                if (socket == null) {
                    socket = connect(MIC_PORT) ?: run {
                        Thread.sleep(400); null
                    }
                    out = socket?.let { BufferedOutputStream(it.getOutputStream()) }
                }
                val n = record.read(buffer, 0, buffer.size)
                if (n > 0 && out != null) {
                    try {
                        out.write(buffer, 0, n)
                        out.flush()
                    } catch (e: Exception) {
                        Log.i(TAG, "USB_MIC_SOCKET_LOST ${e.message}")
                        socket?.close()
                        socket = null
                        out = null
                    }
                } else if (n < 0) {
                    Thread.sleep(20)
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "USB_MIC_LOOP_FAILED ${e.message}")
        } finally {
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            record?.release()
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun spkLoop() {
        var track: AudioTrack? = null
        var socket: Socket? = null
        var input: BufferedInputStream? = null
        try {
            val min = AudioTrack.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(min, SPK_CHUNK * 4))
                .build()
            track.play()
            val buffer = ByteArray(SPK_CHUNK)
            while (running.get()) {
                if (socket == null) {
                    socket = connect(SPK_PORT) ?: run {
                        Thread.sleep(400); null
                    }
                    input = socket?.let { BufferedInputStream(it.getInputStream()) }
                }
                val stream = input
                if (stream == null) {
                    Thread.sleep(100)
                    continue
                }
                var read = 0
                while (read < buffer.size && running.get()) {
                    val n = stream.read(buffer, read, buffer.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read > 0) track.write(buffer, 0, read)
            }
        } catch (e: Exception) {
            Log.i(TAG, "USB_SPK_LOOP_FAILED ${e.message}")
        } finally {
            try {
                track?.stop()
            } catch (_: Exception) {
            }
            track?.release()
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun connect(port: Int): Socket? = try {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress("127.0.0.1", port), 800)
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write("HELLO $token\n".toByteArray())
        out.flush()
        socket
    } catch (_: Exception) {
        null
    }
}
