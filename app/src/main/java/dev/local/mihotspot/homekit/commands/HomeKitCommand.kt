package dev.local.mihotspot.homekit.commands

/** A command exposed through a HomeKit characteristic. */
enum class HomeKitCommand {
    SWITCH,
    SCREEN,
    HOTSPOT
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
}
