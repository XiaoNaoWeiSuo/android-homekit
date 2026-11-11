package dev.local.mihotspot.peripheral.usb

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.local.mihotspot.peripheral.PeripheralPadActivity

/**
 * Entry page for USB peripheral mode. HID is the first fully wired transport;
 * UVC/UAC2 share the same gadget composition and are enabled as their capture
 * pipelines are completed.
 */
class UsbPeripheralActivity : Activity() {

    private lateinit var master: Switch
    private lateinit var audioSwitch: Switch
    private lateinit var status: TextView
    private var suppressSwitch = false
    private var pendingAudio = false

    private fun setSwitchSilently(checked: Boolean) {
        suppressSwitch = true
        master.isChecked = checked
        suppressSwitch = false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(UsbPeripheralService.EXTRA_STATE)) {
                "ready" -> {
                    status.text = "USB 键鼠已就绪，主机可直接使用"
                    setSwitchSilently(true)
                }
                "error" -> {
                    status.text = "USB 绑定失败，请重新插拔数据线后重试"
                    setSwitchSilently(false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.WHITE) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(28))
            setBackgroundColor(Color.WHITE)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "USB 外设"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF111111.toInt())
        })
        root.addView(TextView(this).apply {
            text = "手机通过标准 USB 协议变成主机的键鼠、摄像头与音频设备，主机无需安装软件。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(6), 0, dp(18))
        })

        master = Switch(this).apply {
            text = "USB 外设模式"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isChecked = UsbSession.isEnabled(this@UsbPeripheralActivity)
            setOnCheckedChangeListener { _, checked ->
                if (suppressSwitch) return@setOnCheckedChangeListener
                if (checked) {
                    UsbSession.setEnabled(this@UsbPeripheralActivity, true)
                    status.text = "正在绑定 USB gadget…"
                    startUsb()
                } else {
                    UsbSession.setEnabled(this@UsbPeripheralActivity, false)
                    status.text = "正在恢复普通 USB 模式…"
                    if (UsbPeripheralService.running) {
                        UsbPeripheralService.stop(this@UsbPeripheralActivity)
                    } else {
                        // The service may have been killed by a reinstall; the
                        // root daemon can still be alive. Always run recovery.
                        val ctx = this@UsbPeripheralActivity
                        Thread {
                            UsbGadgetController(ctx).stop()
                            runOnUiThread { status.text = "USB 外设已关闭" }
                        }.start()
                    }
                }
            }
        }
        root.addView(master)

        audioSwitch = Switch(this).apply {
            text = "USB 麦克风 + 扬声器（UAC2）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isChecked = UsbSession.prefs(this@UsbPeripheralActivity)
                .getBoolean(UsbSession.KEY_AUDIO, false)
            setOnCheckedChangeListener { _, checked ->
                UsbSession.prefs(this@UsbPeripheralActivity).edit()
                    .putBoolean(UsbSession.KEY_AUDIO, checked).apply()
                if (master.isChecked) {
                    status.text = "正在重启 USB 以应用音频设置…"
                    UsbPeripheralService.stop(this@UsbPeripheralActivity)
                    Handler(Looper.getMainLooper()).postDelayed({ startUsb() }, 1000)
                }
            }
        }
        root.addView(audioSwitch)

        status = TextView(this).apply {
            text = if (UsbSession.isEnabled(this@UsbPeripheralActivity)) "USB 外设已开启" else "USB 外设已关闭"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(8), 0, dp(18))
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "打开 USB 键鼠界面"
            setOnClickListener {
                startActivity(
                    PeripheralPadActivity.intent(
                        this@UsbPeripheralActivity,
                        immersive = false,
                        transport = PeripheralPadActivity.TRANSPORT_USB
                    )
                )
            }
        })

        root.addView(Button(this).apply {
            text = "停止并恢复普通 USB"
            setOnClickListener {
                master.isChecked = false
            }
        })

        root.addView(TextView(this).apply {
            text = "摄像头 / 麦克风 / 扬声器\n" +
                "UVC 与 UAC2 的标准协议通路已在真机打通；前置/后置/屏幕画面与音频采集管线正在接入，\n" +
                "完成前不会向主机暴露无画面的摄像头设备。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(24), 0, 0)
        })

        setContentView(scroll)

        if (UsbSession.isEnabled(this) && UsbPeripheralService.running) {
            // The service survived a configuration change; reflect the real state.
            setSwitchSilently(true)
        } else if (UsbSession.isEnabled(this) && !UsbPeripheralService.running) {
            setSwitchSilently(false)
            status.text = "检测到上次 USB 会话未清理，正在恢复…"
            val ctx = this
            Thread {
                UsbGadgetController(ctx).stop()
                runOnUiThread { status.text = "USB 外设已关闭" }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(UsbPeripheralService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    override fun onPause() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        super.onPause()
    }

    private fun startUsb() {
        val wantAudio = audioSwitch.isChecked
        if (wantAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingAudio = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 51)
            return
        }
        UsbPeripheralService.start(this, false, wantAudio)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 51) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            audioSwitch.isChecked = false
            status.text = "未授予录音权限，只启用 USB 键鼠"
        }
        UsbPeripheralService.start(this, false, granted)
        pendingAudio = false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
