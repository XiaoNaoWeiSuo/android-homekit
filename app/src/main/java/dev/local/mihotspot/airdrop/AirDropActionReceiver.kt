package dev.local.mihotspot.airdrop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 通知栏上的「接收 / 拒绝」。
 *
 * 悬浮窗需要 `SYSTEM_ALERT_WINDOW` 权限，用户可能不给；那种情况下通知必须自带决定能力，
 * 否则收到文件后只能先打开应用才能确认 —— 那就不是 AirDrop 了。
 */
class AirDropActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECIDE) return
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val accepted = intent.getBooleanExtra(EXTRA_ACCEPT, false)
        runCatching { AirDropStore.decision(context, token, accepted) }
        // 状态机同步推进，通知/悬浮窗立刻收起。
        AirDropState.onDecision(token, accepted)
        if (accepted) {
            AirDropState.onStarting("已接受，正在接收…")
        }
    }

    companion object {
        const val ACTION_DECIDE = "dev.local.mihotspot.airdrop.DECIDE"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_ACCEPT = "accept"
    }
}
