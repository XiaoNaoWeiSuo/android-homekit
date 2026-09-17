package dev.local.mihotspot.domain

import dev.local.mihotspot.homekit.commands.HomeKitCommand
import dev.local.mihotspot.homekit.controls.HomeKitControlModel
import dev.local.mihotspot.homekit.controls.HomeKitControlModels

/** Product-level feature metadata shared by the ViewModel and HomeKit runtime. */
enum class HomeKitFeature(
    val control: HomeKitControlModel
) {
    SCREEN(HomeKitControlModels.SCREEN),
    BRIGHTNESS(HomeKitControlModels.BRIGHTNESS),
    HOTSPOT(HomeKitControlModels.HOTSPOT),
    MUTE(HomeKitControlModels.MUTE),
    FLASHLIGHT(HomeKitControlModels.FLASHLIGHT),
    GPS(HomeKitControlModels.GPS),
    LOW_POWER_MODE(HomeKitControlModels.LOW_POWER_MODE),
    DO_NOT_DISTURB(HomeKitControlModels.DO_NOT_DISTURB),
    MOBILE_DATA(HomeKitControlModels.MOBILE_DATA),
    ;

    val command: HomeKitCommand get() = control.command
    val title: String get() = control.title
    val enabledLabel: String get() = control.enabledLabel
    val disabledLabel: String get() = control.disabledLabel

    companion object {
        val tileFeatures = listOf(SCREEN, HOTSPOT, MUTE, FLASHLIGHT, GPS, LOW_POWER_MODE, DO_NOT_DISTURB, MOBILE_DATA)
        fun from(command: HomeKitCommand): HomeKitFeature = entries.first { it.command == command }
    }
}
