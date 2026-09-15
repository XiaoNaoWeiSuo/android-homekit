package dev.local.mihotspot.ui

import android.content.Context
import dev.local.mihotspot.data.AccessorySettingsRepository
import dev.local.mihotspot.domain.HomeKitFeature
import dev.local.mihotspot.homekit.commands.AndroidCommandExecutor
import dev.local.mihotspot.homekit.commands.CommandResult
import dev.local.mihotspot.homekit.commands.HomeKitCommand

/**
 * Presentation state and user-initiated actions for MainActivity.
 *
 * This is deliberately framework-free: the project does not currently depend
 * on AndroidX Lifecycle, so Activity owns its lifetime and renders snapshots
 * posted from its background work.
 */
class MainViewModel(context: Context) {
    private val executor = AndroidCommandExecutor(context.applicationContext)
    private val settings = AccessorySettingsRepository(context.applicationContext)

    data class State(
        val featureStates: Map<HomeKitFeature, Boolean>,
        val brightness: Int
    )

    fun loadState(): State = State(
        featureStates = HomeKitFeature.tileFeatures.associateWith { executor.currentValue(it.command) },
        brightness = executor.currentValueInt(HomeKitCommand.BRIGHTNESS) ?: 0
    )

    fun toggle(feature: HomeKitFeature): CommandResult {
        val target = !executor.currentValue(feature.command)
        return executor.execute(feature.command, target)
    }

    fun setBrightness(value: Int): CommandResult =
        executor.executeValue(HomeKitCommand.BRIGHTNESS, value.coerceIn(0, 100))

    fun buttonLabel(feature: HomeKitFeature, state: Boolean?): String {
        val suffix = when (state) {
            null -> "读取中…"
            true -> feature.enabledLabel
            false -> feature.disabledLabel
        }
        return "${feature.title}：$suffix"
    }

    fun isValidSetupCode(digits: String): Boolean {
        if (digits.length != 8 || digits.all { it == digits.first() }) return false
        val values = digits.map { it - '0' }
        val ascending = values.zipWithNext().all { (a, b) -> b == a + 1 }
        val descending = values.zipWithNext().all { (a, b) -> b == a - 1 }
        return !ascending && !descending
    }

    fun formatSetupCode(digits: String): String {
        val clean = digits.filter(Char::isDigit).padEnd(8, '0').take(8)
        return "${clean.substring(0, 4)}-${clean.substring(4, 8)}"
    }

    val deviceName: String get() = settings.deviceName
    val setupCodeDigits: String get() = settings.setupCodeDigits
    val isPaired: Boolean get() = settings.isPaired
    fun saveDeviceName(name: String) = settings.saveDeviceName(name)
    fun saveSetupCode(digits: String) = settings.saveSetupCode(digits)
    fun clearPairing() = settings.clearPairing()
    fun resetAccessoryId(): String = settings.resetAccessoryId()
    fun accessoryId(): String = settings.accessoryId()
    fun isFeatureEnabled(feature: HomeKitFeature): Boolean = settings.isFeatureEnabled(feature)
    fun setFeatureEnabled(feature: HomeKitFeature, enabled: Boolean) = settings.setFeatureEnabled(feature, enabled)
}
