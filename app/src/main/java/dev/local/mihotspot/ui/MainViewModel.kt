package dev.local.mihotspot.ui

import android.content.Context
import dev.local.mihotspot.data.AccessorySettingsRepository
import dev.local.mihotspot.data.HomeKitRecoveryResult
import dev.local.mihotspot.data.HotspotConfiguration
import dev.local.mihotspot.data.HotspotSettingsRepository
import dev.local.mihotspot.domain.HomeKitFeature
import dev.local.mihotspot.homekit.commands.AndroidCommandExecutor
import dev.local.mihotspot.homekit.commands.CommandResult
import dev.local.mihotspot.homekit.controls.HomeKitControlLifecycle
import dev.local.mihotspot.homekit.controls.HomeKitControlModels
import dev.local.mihotspot.homekit.HomeKitSetupPayload
import dev.local.mihotspot.homekit.PairingBroadcastMode

/**
 * Presentation state and user-initiated actions for MainActivity.
 *
 * This is deliberately framework-free: the project does not currently depend
 * on AndroidX Lifecycle, so Activity owns its lifetime and renders snapshots
 * posted from its background work.
 */
class MainViewModel(context: Context) {
    private val executor = AndroidCommandExecutor(context.applicationContext)
    private val controlLifecycles = HomeKitControlModels.all.associate { model ->
        model.command to HomeKitControlLifecycle(model, executor)
    }
    private val settings = AccessorySettingsRepository(context.applicationContext)
    private val hotspotSettings = HotspotSettingsRepository(context.applicationContext)

    data class State(
        val featureStates: Map<HomeKitFeature, Boolean>,
        val brightness: Int,
        val volume: Int
    )

    fun loadState(): State = State(
        featureStates = HomeKitFeature.tileFeatures.associateWith { lifecycle(it).currentBoolean() },
        brightness = lifecycle(HomeKitFeature.BRIGHTNESS).currentPercentage(),
        volume = controlLifecycles.getValue(HomeKitControlModels.VOLUME.command).currentPercentage()
    )

    fun toggle(feature: HomeKitFeature): CommandResult {
        val target = !lifecycle(feature).currentBoolean()
        return lifecycle(feature).executeBoolean(target)
    }

    fun currentValue(feature: HomeKitFeature): Boolean = lifecycle(feature).currentBoolean()

    fun toggleFromCurrent(feature: HomeKitFeature, current: Boolean): CommandResult =
        lifecycle(feature).executeBoolean(!current)

    fun setBrightness(value: Int): CommandResult =
        lifecycle(HomeKitFeature.BRIGHTNESS).executePercentage(value)

    fun setVolume(value: Int): CommandResult =
        controlLifecycles.getValue(HomeKitControlModels.VOLUME.command).executePercentage(value)

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
        return HomeKitSetupPayload.formatSetupCode(digits)
    }

    val deviceName: String get() = settings.deviceName
    val setupCodeDigits: String get() = settings.setupCodeDigits
    val setupId: String get() = settings.setupId
    val pairingBroadcastMode: PairingBroadcastMode get() = settings.pairingBroadcastMode
    val isConnected: Boolean get() = settings.isRuntimeConnected
    val setupPayload: String get() = HomeKitSetupPayload.create(setupCodeDigits, setupId)
    val isPaired: Boolean get() = settings.isPaired
    fun saveDeviceName(name: String) = settings.saveDeviceName(name)
    fun saveSetupCode(digits: String) = settings.saveSetupCode(digits)
    fun setPairingBroadcastMode(mode: PairingBroadcastMode) = settings.setPairingBroadcastMode(mode)
    fun clearPairing() = settings.clearPairing()
    fun resetAccessoryId(): String = settings.resetAccessoryId()
    fun recoverHomeKitIdentity(): HomeKitRecoveryResult = settings.recoverHomeKitIdentity()
    fun accessoryId(): String = settings.accessoryId()
    fun isFeatureEnabled(feature: HomeKitFeature): Boolean = settings.isFeatureEnabled(feature)
    fun setFeatureEnabled(feature: HomeKitFeature, enabled: Boolean) = settings.setFeatureEnabled(feature, enabled)
    fun hotspotConfiguration(): HotspotConfiguration = hotspotSettings.configuration()
    fun saveHotspotConfiguration(configuration: HotspotConfiguration) = hotspotSettings.save(configuration)
    fun restartHotspotIfActive(): CommandResult {
        val hotspot = lifecycle(HomeKitFeature.HOTSPOT)
        if (!hotspot.currentBoolean()) return CommandResult(true, "hotspot=not-running")
        hotspot.executeBoolean(false)
        return hotspot.executeBoolean(true)
    }

    private fun lifecycle(feature: HomeKitFeature): HomeKitControlLifecycle =
        controlLifecycles.getValue(feature.command)
}
