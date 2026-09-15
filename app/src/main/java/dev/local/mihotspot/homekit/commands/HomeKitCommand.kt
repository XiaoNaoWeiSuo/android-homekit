package dev.local.mihotspot.homekit.commands

/** A command exposed through a HomeKit characteristic. */
enum class HomeKitCommand {
    SCREEN,
    BRIGHTNESS,
    HOTSPOT,
    MUTE,
    FLASHLIGHT
}

data class CommandResult(
    val success: Boolean,
    val message: String
)

/**
 * Boundary between the HAP transport and Android actions. Implementations must
 * be side-effecting only after the HAP layer has authenticated the session.
 */
interface HomeKitCommandExecutor {
    fun execute(command: HomeKitCommand, enabled: Boolean): CommandResult
    fun currentValue(command: HomeKitCommand): Boolean

    /** Numeric characteristic path (for example HomeKit Brightness 0..100). */
    fun executeValue(command: HomeKitCommand, value: Int): CommandResult = execute(command, value != 0)
    fun currentValueInt(command: HomeKitCommand): Int? = null
}
