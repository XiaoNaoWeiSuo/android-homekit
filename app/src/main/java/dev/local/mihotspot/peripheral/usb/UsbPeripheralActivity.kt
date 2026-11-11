package dev.local.mihotspot.peripheral.usb

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.local.mihotspot.R
import dev.local.mihotspot.peripheral.PeripheralPadActivity
import dev.local.mihotspot.ui.UiTheme
import dev.local.mihotspot.ui.createCard
import dev.local.mihotspot.ui.createCardDivider
import dev.local.mihotspot.ui.createPageHeader
import dev.local.mihotspot.ui.createSectionHeader
import dev.local.mihotspot.ui.createStatusBadge
import dev.local.mihotspot.ui.onHapticClick

/**
 * USB peripheral settings. HID and UAC2 share one standard gadget composition;
 * UVC is shown as in-progress until its capture pipeline is wired.
 */
class UsbPeripheralActivity : Activity() {

    private lateinit var master: Switch
    private lateinit var audioSwitch: Switch
    private lateinit var statusBadge: TextView
    private lateinit var statusDetail: TextView
    private var suppressSwitch = false
    private var pendingAudio = false

    private fun setSwitchSilently(view: Switch, checked: Boolean) {
        suppressSwitch = true
        view.isChecked = checked
        suppressSwitch = false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(UsbPeripheralService.EXTRA_STATE)) {
                "ready" -> {
                    setSwitchSilently(master, true)
                    showStatus(
                        "● 键鼠已就绪",
                        UiTheme.COLOR_SUCCESS,
                        UiTheme.COLOR_SUCCESS_LIGHT
                    )
                    statusDetail.text = "主机可直接使用 USB 键盘与触控板"
                }
                "error" -> {
                    setSwitchSilently(master, false)
                    showStatus(
                        "● 绑定失败",
                        UiTheme.COLOR_DANGER,
                        UiTheme.COLOR_DANGER_LIGHT
                    )
                    statusDetail.text = "请重新插拔数据线后重试"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.COLOR_BG)
        }
        root.addView(createPageHeader(this, "USB 外设", { finish() }))

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)

        content.addView(
            createSectionHeader(
                this,
                "连接模式",
                "USB 外设说明",
                "手机通过标准 USB 类协议（HID / UAC2 / UVC）作为主机外设。" +
                    "主机使用系统自带驱动，无需安装任何软件。"
            )
        )

        val modeCard = createCard(this)
        statusBadge = createStatusBadge(
            this, "● 未开启", UiTheme.COLOR_TEXT_MUTED, UiTheme.COLOR_INPUT_BG
        )
        modeCard.addView(statusBadge)

        master = Switch(this).apply {
            contentDescription = "USB 外设模式"
            isChecked = UsbSession.isEnabled(this@UsbPeripheralActivity)
            setOnCheckedChangeListener { _, checked ->
                if (suppressSwitch) return@setOnCheckedChangeListener
                if (checked) {
                    UsbSession.setEnabled(this@UsbPeripheralActivity, true)
                    showStatus(
                        "● 正在绑定",
                        UiTheme.COLOR_WARNING,
                        UiTheme.COLOR_WARNING_LIGHT
                    )
                    statusDetail.text = "正在重组 USB gadget，请稍候…"
                    startUsb()
                } else {
                    UsbSession.setEnabled(this@UsbPeripheralActivity, false)
                    beginStop()
                }
            }
        }
        modeCard.addView(settingRow(
            "USB 外设模式",
            "手机作为标准 USB 键盘 / 触控板接入主机",
            master
        ))

        audioSwitch = Switch(this).apply {
            contentDescription = "USB 麦克风与扬声器"
            isChecked = UsbSession.prefs(this@UsbPeripheralActivity)
                .getBoolean(UsbSession.KEY_AUDIO, false)
            setOnCheckedChangeListener { _, checked ->
                if (suppressSwitch) return@setOnCheckedChangeListener
                UsbSession.prefs(this@UsbPeripheralActivity).edit()
                    .putBoolean(UsbSession.KEY_AUDIO, checked).apply()
                if (master.isChecked) {
                    showStatus(
                        "● 正在重启",
                        UiTheme.COLOR_WARNING,
                        UiTheme.COLOR_WARNING_LIGHT
                    )
                    statusDetail.text = "正在应用音频设置…"
                    UsbPeripheralService.stop(this@UsbPeripheralActivity)
                    Handler(Looper.getMainLooper()).postDelayed({ startUsb() }, 1000)
                }
            }
        }
        modeCard.addView(createCardDivider(this))
        modeCard.addView(settingRow(
            "麦克风 + 扬声器",
            "UAC2 标准 USB 声卡：主机录音使用手机麦克风，主机播放从手机扬声器输出",
            audioSwitch
        ))
        content.addView(modeCard)

        statusDetail = TextView(this).apply {
            text = if (UsbSession.isEnabled(this@UsbPeripheralActivity))
                "USB 外设已开启" else "USB 外设已关闭"
            textSize = 12f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            setPadding(dp(22), dp(8), dp(22), dp(4))
        }
        content.addView(statusDetail)

        content.addView(
            createSectionHeader(
                this,
                "使用",
                "USB 使用说明",
                "键鼠界面与蓝牙模式共用同一套 UI；USB 模式下不再依赖蓝牙连接。"
            )
        )
        val useCard = createCard(this)
        useCard.addView(navRow(
            "USB 键鼠界面",
            "键盘 + 触控板，点键盘上的 ✕ 退出",
            "打开"
        ) {
            startActivity(
                PeripheralPadActivity.intent(
                    this@UsbPeripheralActivity,
                    immersive = false,
                    transport = PeripheralPadActivity.TRANSPORT_USB
                )
            )
        })
        useCard.addView(createCardDivider(this))
        useCard.addView(navRow(
            "摄像头（UVC）",
            "前置 / 后置 / 屏幕画面，标准 USB Video 类",
            "接入中"
        ) {
            Toast.makeText(
                this@UsbPeripheralActivity,
                "UVC 取流管线正在接入，完成前不会向主机暴露无画面的摄像头",
                Toast.LENGTH_SHORT
            ).show()
        })
        content.addView(useCard)

        val actionCard = createCard(this)
        val open = Button(this).apply {
            text = "打开 USB 键鼠界面"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            )
            UiTheme.styleButton(this, UiTheme.COLOR_ACCENT, android.graphics.Color.WHITE, 12f)
            onHapticClick {
                startActivity(
                    PeripheralPadActivity.intent(
                        this@UsbPeripheralActivity,
                        immersive = false,
                        transport = PeripheralPadActivity.TRANSPORT_USB
                    )
                )
            }
        }
        actionCard.addView(open)
        val stop = Button(this).apply {
            text = "停止并恢复普通 USB"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { topMargin = dp(8) }
            UiTheme.styleButton(
                this,
                UiTheme.COLOR_BTN_SECONDARY_BG,
                UiTheme.COLOR_BTN_SECONDARY_TEXT,
                12f
            )
            onHapticClick {
                setSwitchSilently(master, false)
                UsbSession.setEnabled(this@UsbPeripheralActivity, false)
                beginStop()
            }
        }
        actionCard.addView(stop)
        content.addView(actionCard)

        content.addView(TextView(this).apply {
            text = "不安装软件、不伪造私有协议：HID 1.11、USB Audio Class 2、USB Video Class 均为标准设备类。"
            textSize = 11f
            setTextColor(UiTheme.COLOR_TEXT_MUTED)
            setPadding(dp(22), dp(14), dp(22), 0)
        })

        if (UsbSession.isEnabled(this) && UsbPeripheralService.running) {
            setSwitchSilently(master, true)
            showStatus("● 运行中", UiTheme.COLOR_SUCCESS, UiTheme.COLOR_SUCCESS_LIGHT)
        } else if (UsbSession.isEnabled(this) && !UsbPeripheralService.running) {
            setSwitchSilently(master, false)
            showStatus("● 正在恢复", UiTheme.COLOR_WARNING, UiTheme.COLOR_WARNING_LIGHT)
            statusDetail.text = "检测到上次 USB 会话未清理，正在恢复…"
            val ctx = this
            Thread {
                UsbGadgetController(ctx).stop()
                runOnUiThread {
                    showStatus("● 未开启", UiTheme.COLOR_TEXT_MUTED, UiTheme.COLOR_INPUT_BG)
                    statusDetail.text = "USB 外设已关闭"
                }
            }.start()
        }
    }

    private fun settingRow(title: String, desc: String, control: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            val col = LinearLayout(this@UsbPeripheralActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this@UsbPeripheralActivity).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
            })
            col.addView(TextView(this@UsbPeripheralActivity).apply {
                text = desc
                textSize = 12f
                setTextColor(UiTheme.COLOR_TEXT_SECONDARY)
                setPadding(0, dp(4), dp(8), 0)
            })
            addView(col)
            addView(control)
        }

    private fun navRow(title: String, desc: String, action: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            val col = LinearLayout(this@UsbPeripheralActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this@UsbPeripheralActivity).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(UiTheme.COLOR_TEXT_PRIMARY)
            })
            col.addView(TextView(this@UsbPeripheralActivity).apply {
                text = desc
                textSize = 12f
                setTextColor(UiTheme.COLOR_TEXT_SECONDARY)
                setPadding(0, dp(4), dp(8), 0)
            })
            addView(col)
            addView(TextView(this@UsbPeripheralActivity).apply {
                text = action
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(
                    if (action == "接入中") UiTheme.COLOR_WARNING else UiTheme.COLOR_ACCENT
                )
            })
            addView(ImageView(this@UsbPeripheralActivity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4) }
            })
            onHapticClick(onClick)
        }

    private fun showStatus(text: String, textColor: Int, bgColor: Int) {
        statusBadge.text = text
        statusBadge.setTextColor(textColor)
        statusBadge.background = UiTheme.createPillDrawable(this, bgColor)
    }

    private fun beginStop() {
        showStatus("● 未开启", UiTheme.COLOR_TEXT_MUTED, UiTheme.COLOR_INPUT_BG)
        statusDetail.text = "正在恢复普通 USB 模式…"
        if (UsbPeripheralService.running) {
            UsbPeripheralService.stop(this)
        } else {
            val ctx = this
            Thread {
                UsbGadgetController(ctx).stop()
                runOnUiThread { statusDetail.text = "已恢复普通 USB 模式" }
            }.start()
        }
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
            setSwitchSilently(audioSwitch, false)
            statusDetail.text = "未授予录音权限，只启用 USB 键鼠"
        }
        UsbPeripheralService.start(this, false, granted)
        pendingAudio = false
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
