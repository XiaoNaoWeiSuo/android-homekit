package dev.local.mihotspot.airdrop

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.Locale

object AirDropStore {
    /**
     * 单次发送的字节上限。
     *
     * 后端在发送前会把整份文件读进内存（`luftlift send` 的 `fs::read`），随后 dvzip 打包
     * 又会产生一份等量缓冲，所以这个值不是"产品策略"而是内存约束：100 MiB 对应约
     * 250 MiB 峰值。真正的上限要靠流式上传，见 ROADMAP。
     */
    const val MAX_SEND_BYTES = 100L * 1024 * 1024

    fun base(context: Context) = File(context.filesDir, "airdrop").apply { mkdirs() }
    fun inbox(context: Context) = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: context.filesDir, "AirDrop").apply { mkdirs() }
    fun approvals(context: Context) = File(base(context), "requests").apply { mkdirs() }
    fun radio(context: Context) = File(base(context), "radio")
    fun log(context: Context) = File(base(context), "transfer.log")
    fun binary(context: Context, radio: Boolean = false) =
        File(context.applicationInfo.nativeLibraryDir, if (radio) "libmarionette_awdl.so" else "libmarionette_airdrop.so")

    fun status(context: Context): String = context.getSharedPreferences("airdrop", 0)
        .getString("status", "尚未开启") ?: "尚未开启"
    fun status(context: Context, value: String) {
        context.getSharedPreferences("airdrop", 0).edit().putString("status", value).apply()
    }

    /**
     * 是否允许"收到文件时全屏上层悬浮窗"。
     *
     * 用户可能拒绝悬浮窗权限，所以这是一个显式开关：关掉时接收确认退回通知+应用内对话框，
     * 功能不受影响。默认开启（这正是"和 iPhone 一样"的那部分体验）。
     */
    fun overlayEnabled(context: Context): Boolean =
        context.getSharedPreferences("airdrop", 0).getBoolean("overlay", true)

    fun overlayEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("airdrop", 0).edit().putBoolean("overlay", value).apply()
    }

    fun decision(context: Context, token: String, accepted: Boolean) {
        require(token.matches(Regex("[0-9]+-[0-9]+")))
        val dir = approvals(context)
        if (!File(dir, "$token.json").isFile) return
        val temporary = File(dir, "$token.answer")
        temporary.writeText(if (accepted) "accept" else "reject")
        check(temporary.renameTo(File(dir, "$token.decision")))
    }

    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    /** 人类可读大小，和主界面 `formatFileSize` 保持一致的写法。 */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
