package dev.local.mihotspot.homekit.controls

import dev.local.mihotspot.homekit.commands.CommandResult
import dev.local.mihotspot.homekit.commands.HomeKitCommand
import dev.local.mihotspot.homekit.commands.HomeKitCommandExecutor

/**
 * The product-level meaning of a HomeKit control.  This class deliberately
 * knows nothing about HAP UUIDs, IIDs, BLE properties or TLV encoding.
 */
data class HomeKitControlModel(
    val command: HomeKitCommand,
    val title: String,
    val enabledLabel: String,
    val disabledLabel: String,
    val valueType: ValueType,
    val interaction: Interaction = Interaction.SET_STATE
) {
    enum class ValueType { BOOLEAN, PERCENTAGE }
    enum class Interaction { SET_STATE, PRESS }
}

/** A protocol-independent snapshot used by polling and change detection. */
sealed interface HomeKitControlSnapshot {
    data class BooleanValue(val value: Boolean) : HomeKitControlSnapshot
    data class PercentageValue(val value: Int) : HomeKitControlSnapshot
}

/**
 * Owns one control's complete runtime lifecycle: read the real Android
 * state, execute a requested value, and expose a canonical snapshot.
 *
 * HAP can expose one model through multiple characteristics (for example,
 * Brightness has both an On boolean and a Brightness Int32).  Both routes
 * must come through this object so reads, writes and notifications cannot
 * drift apart.
 */
class HomeKitControlLifecycle(
    val model: HomeKitControlModel,
    private val executor: HomeKitCommandExecutor
) {
    fun readSnapshot(): HomeKitControlSnapshot = when (model.valueType) {
        HomeKitControlModel.ValueType.BOOLEAN ->
            HomeKitControlSnapshot.BooleanValue(executor.currentValue(model.command))
        HomeKitControlModel.ValueType.PERCENTAGE ->
            HomeKitControlSnapshot.PercentageValue(currentPercentage())
    }

    fun currentBoolean(): Boolean = when (model.valueType) {
        HomeKitControlModel.ValueType.BOOLEAN -> executor.currentValue(model.command)
        HomeKitControlModel.ValueType.PERCENTAGE -> currentPercentage() > 0
    }

    fun currentPercentage(): Int = (executor.currentValueInt(model.command) ?: 0).coerceIn(0, 100)

    fun executeBoolean(enabled: Boolean): CommandResult = executor.execute(
        model.command,
        if (model.interaction == HomeKitControlModel.Interaction.PRESS) true else enabled
    )

    fun executePercentage(value: Int): CommandResult =
        executor.executeValue(model.command, value.coerceIn(0, 100))
}

/** Single source of truth for every user-facing control exposed by the app. */
object HomeKitControlModels {
    val SCREEN = HomeKitControlModel(
        HomeKitCommand.SCREEN, "屏幕电源", "亮屏", "熄屏",
        HomeKitControlModel.ValueType.BOOLEAN, HomeKitControlModel.Interaction.PRESS
    )
    val BRIGHTNESS = HomeKitControlModel(
        HomeKitCommand.BRIGHTNESS, "屏幕亮度", "开启", "关闭",
        HomeKitControlModel.ValueType.PERCENTAGE
    )
    val HOTSPOT = HomeKitControlModel(
        HomeKitCommand.HOTSPOT, "手机热点", "开启", "关闭",
        HomeKitControlModel.ValueType.BOOLEAN
    )
    val MUTE = HomeKitControlModel(
        HomeKitCommand.MUTE, "媒体静音", "已静音", "未静音",
        HomeKitControlModel.ValueType.BOOLEAN
    )
    val FLASHLIGHT = HomeKitControlModel(
        HomeKitCommand.FLASHLIGHT, "手电筒", "开启", "关闭",
        HomeKitControlModel.ValueType.BOOLEAN
    )
    val GPS = HomeKitControlModel(
        HomeKitCommand.GPS, "GPS定位", "开启", "关闭",
        HomeKitControlModel.ValueType.BOOLEAN
    )
    val LOW_POWER_MODE = HomeKitControlModel(
        HomeKitCommand.LOW_POWER_MODE, "低电量模式", "开启", "关闭",
        HomeKitControlModel.ValueType.BOOLEAN
    )
    val DO_NOT_DISTURB = HomeKitControlModel(
        HomeKitCommand.DO_NOT_DISTURB, "免打扰模式", "开启", "关闭",
        HomeKitControlModel.ValueType.BOOLEAN
    )
    val MOBILE_DATA = HomeKitControlModel(
        HomeKitCommand.MOBILE_DATA, "移动数据", "开启", "关闭",
        HomeKitControlModel.ValueType.BOOLEAN
    )
    val VOLUME = HomeKitControlModel(
        HomeKitCommand.VOLUME, "媒体音量", "开启", "关闭",
        HomeKitControlModel.ValueType.PERCENTAGE
    )

    val all: List<HomeKitControlModel> = listOf(
        SCREEN, BRIGHTNESS, HOTSPOT, MUTE, FLASHLIGHT, GPS,
        LOW_POWER_MODE, DO_NOT_DISTURB, MOBILE_DATA, VOLUME
    )

    val byCommand: Map<HomeKitCommand, HomeKitControlModel> = all.associateBy { it.command }
}
