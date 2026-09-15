package dev.local.mihotspot.domain

import dev.local.mihotspot.homekit.commands.HomeKitCommand

/** Product-level feature metadata shared by the ViewModel and HomeKit runtime. */
enum class HomeKitFeature(
    val command: HomeKitCommand,
    val title: String,
    val enabledLabel: String,
    val disabledLabel: String
) {
    SCREEN(HomeKitCommand.SCREEN, "屏幕电源", "亮屏", "熄屏"),
    BRIGHTNESS(HomeKitCommand.BRIGHTNESS, "屏幕亮度", "开启", "关闭"),
    HOTSPOT(HomeKitCommand.HOTSPOT, "热点", "开启", "关闭"),
    MUTE(HomeKitCommand.MUTE, "媒体静音", "已静音", "未静音"),
    FLASHLIGHT(HomeKitCommand.FLASHLIGHT, "手电筒", "开启", "关闭");

    companion object {
        val tileFeatures = listOf(SCREEN, HOTSPOT, MUTE, FLASHLIGHT)
        fun from(command: HomeKitCommand): HomeKitFeature = entries.first { it.command == command }
    }
}
