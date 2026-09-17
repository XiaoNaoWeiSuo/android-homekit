package dev.local.mihotspot

/** User-selectable BLE advertising policy. */
enum class BroadcastStrategy(val storageValue: String, val title: String) {
    AUTO("auto", "自动"),
    LOW_LATENCY("low_latency", "低延迟"),
    BALANCED("balanced", "均衡"),
    LOW_POWER("low_power", "低功耗");

    companion object {
        fun fromStorage(value: String?): BroadcastStrategy =
            entries.firstOrNull { it.storageValue == value } ?: AUTO
    }
}
