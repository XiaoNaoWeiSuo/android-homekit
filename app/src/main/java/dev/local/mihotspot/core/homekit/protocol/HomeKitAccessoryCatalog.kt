package dev.local.mihotspot.core.homekit.protocol

import dev.local.mihotspot.homekit.commands.HomeKitCommand

/**
 * Immutable HomeKit accessory database.
 *
 * Functional services must be created with [functionalService]. The factory
 * always emits the required Service Signature, legacy Name and modern
 * ConfiguredName characteristics, so a new visible tile cannot accidentally
 * fall back to Home's generated "灯/开关 1" name.
 */
object HomeKitAccessoryCatalog {
    val services: List<HapServiceDefinition> = listOf(
        HapServiceDefinition(HapType.ACCESSORY_INFORMATION, 0x0001, characteristics = listOf(
            characteristic(HapType.IDENTIFY, 0x0002, HapProperties.WRITE, HapFormat.BOOL),
            characteristic(HapType.MANUFACTURER, 0x0003, HapProperties.READ, HapFormat.STRING),
            characteristic(HapType.MODEL, 0x0004, HapProperties.READ, HapFormat.STRING),
            characteristic(HapType.NAME, 0x0005, HapProperties.READ, HapFormat.STRING),
            characteristic(HapType.SERIAL_NUMBER, 0x0006, HapProperties.READ, HapFormat.STRING),
            characteristic(HapType.FIRMWARE_REVISION, 0x0007, HapProperties.READ, HapFormat.STRING)
        )),
        HapServiceDefinition(HapType.PROTOCOL_INFORMATION, 0x0010, supportsConfiguration = true, characteristics = listOf(
            characteristic(HapType.SERVICE_SIGNATURE, 0x0011, HapProperties.READ, HapFormat.TLV8),
            characteristic(0x37, 0x0012, HapProperties.READ, HapFormat.STRING)
        )),
        HapServiceDefinition(HapType.PAIRING, 0x0020, characteristics = listOf(
            characteristic(HapType.PAIR_SETUP, 0x0022, 0x0003, HapFormat.TLV8),
            characteristic(HapType.PAIR_VERIFY, 0x0023, 0x0003, HapFormat.TLV8),
            characteristic(HapType.PAIRING_FEATURES, 0x0024, 0x0001, HapFormat.UINT32),
            characteristic(HapType.PAIRINGS, 0x0025, HapProperties.READ_WRITE, HapFormat.TLV8)
        )),
        functionalService(
            serviceType = HapType.LIGHTBULB,
            serviceIid = 0x0030,
            defaultName = "屏幕电源",
            signatureIid = 0x0031,
            nameIid = 0x0032,
            configuredNameIid = 0x0034,
            primary = true,
            values = listOf(booleanControl(0x0033, HomeKitCommand.SCREEN))
        ),
        functionalService(
            serviceType = HapType.LIGHTBULB,
            serviceIid = 0x0080,
            defaultName = "屏幕亮度",
            signatureIid = 0x0081,
            nameIid = 0x0082,
            configuredNameIid = 0x0085,
            values = listOf(
                booleanControl(0x0083, HomeKitCommand.BRIGHTNESS),
                uint32Control(HapType.BRIGHTNESS, 0x0084, HomeKitCommand.BRIGHTNESS)
            )
        ),
        functionalService(
            serviceType = HapType.LIGHTBULB,
            serviceIid = 0x0070,
            defaultName = "手机手电筒",
            signatureIid = 0x0071,
            nameIid = 0x0072,
            configuredNameIid = 0x0074,
            values = listOf(booleanControl(0x0073, HomeKitCommand.FLASHLIGHT))
        ),
        functionalService(
            serviceType = HapType.SWITCH,
            serviceIid = 0x0040,
            defaultName = "手机热点",
            signatureIid = 0x0041,
            nameIid = 0x0042,
            configuredNameIid = 0x0044,
            values = listOf(booleanControl(0x0043, HomeKitCommand.HOTSPOT))
        ),
        functionalService(
            serviceType = HapType.SWITCH,
            serviceIid = 0x0050,
            defaultName = "媒体静音",
            signatureIid = 0x0051,
            nameIid = 0x0052,
            configuredNameIid = 0x0054,
            values = listOf(booleanControl(0x0053, HomeKitCommand.MUTE))
        ),
        functionalService(
            serviceType = HapType.BATTERY,
            serviceIid = 0x0060,
            defaultName = "手机电池",
            signatureIid = 0x0061,
            nameIid = 0x0062,
            configuredNameIid = 0x0066,
            values = listOf(
                legacyBatteryState(HapType.BATTERY_LEVEL, 0x0063, HapReadOnlyState.BATTERY_LEVEL),
                legacyBatteryState(HapType.STATUS_LOW_BATTERY, 0x0064, HapReadOnlyState.BATTERY_LOW),
                legacyBatteryState(HapType.CHARGING_STATE, 0x0065, HapReadOnlyState.BATTERY_CHARGING)
            )
        ),
        functionalService(
            serviceType = HapType.SWITCH,
            serviceIid = 0x0090,
            defaultName = "GPS定位",
            signatureIid = 0x0091,
            nameIid = 0x0092,
            configuredNameIid = 0x0094,
            values = listOf(booleanControl(0x0093, HomeKitCommand.GPS))
        ),
        functionalService(
            serviceType = HapType.SWITCH,
            serviceIid = 0x00A0,
            defaultName = "低电量模式",
            signatureIid = 0x00A1,
            nameIid = 0x00A2,
            configuredNameIid = 0x00A4,
            values = listOf(booleanControl(0x00A3, HomeKitCommand.LOW_POWER_MODE))
        ),
        functionalService(
            serviceType = HapType.SWITCH,
            serviceIid = 0x00B0,
            defaultName = "免打扰模式",
            signatureIid = 0x00B1,
            nameIid = 0x00B2,
            configuredNameIid = 0x00B4,
            values = listOf(booleanControl(0x00B3, HomeKitCommand.DO_NOT_DISTURB))
        ),
        functionalService(
            serviceType = HapType.LIGHTBULB,
            serviceIid = 0x00C0,
            defaultName = "音量控制",
            signatureIid = 0x00C1,
            nameIid = 0x00C2,
            configuredNameIid = 0x00C5,
            values = listOf(
                booleanControl(0x00C3, HomeKitCommand.VOLUME),
                uint32Control(HapType.BRIGHTNESS, 0x00C4, HomeKitCommand.VOLUME)
            )
        )
    ).also(::validate)

    private fun functionalService(
        serviceType: Int,
        serviceIid: Int,
        defaultName: String,
        signatureIid: Int,
        nameIid: Int,
        configuredNameIid: Int,
        primary: Boolean = false,
        values: List<HapCharacteristicDefinition>
    ): HapServiceDefinition {
        require(defaultName.isNotBlank()) { "Functional service 0x%04X must have a name".format(serviceIid) }
        return HapServiceDefinition(
            type = serviceType,
            iid = serviceIid,
            primary = primary,
            defaultName = defaultName,
            characteristics = listOf(
                characteristic(HapType.SERVICE_SIGNATURE, signatureIid, HapProperties.READ, HapFormat.TLV8),
                characteristic(HapType.NAME, nameIid, HapProperties.READ, HapFormat.STRING)
            ) + values + characteristic(
                HapType.CONFIGURED_NAME,
                configuredNameIid,
                HapProperties.CONFIGURED_NAME,
                HapFormat.STRING
            )
        )
    }

    private fun booleanControl(iid: Int, command: HomeKitCommand) = characteristic(
        HapType.ON, iid, HapProperties.CONTROL, HapFormat.BOOL,
        control = HapControlBinding(command, HapValueKind.BOOLEAN)
    )

    private fun uint32Control(type: Int, iid: Int, command: HomeKitCommand) = characteristic(
        type, iid, HapProperties.CONTROL, HapFormat.UINT32,
        control = HapControlBinding(command, HapValueKind.UINT32)
    )

    /**
     * Current iOS/HAP-over-BLE interoperability baseline. The standard IP HAP
     * battery signature is UInt8 + Notify, but advertising it here makes the
     * controller remove the pairing immediately after M6. Keep this isolated
     * until the BLE event path is implemented and verified end-to-end.
     */
    private fun legacyBatteryState(type: Int, iid: Int, state: HapReadOnlyState) = characteristic(
        type, iid, HapProperties.READ, HapFormat.UINT32, readOnlyState = state
    )

    private fun characteristic(
        type: Int,
        iid: Int,
        properties: Int,
        format: Byte,
        control: HapControlBinding? = null,
        readOnlyState: HapReadOnlyState? = null
    ) = HapCharacteristicDefinition(type, iid, properties, format, control, readOnlyState)

    private fun validate(services: List<HapServiceDefinition>) {
        val allIids = services.flatMap { service -> listOf(service.iid) + service.characteristics.map { it.iid } }
        require(allIids.size == allIids.distinct().size) { "HAP service and characteristic IIDs must be globally unique" }
        services.filter { it.defaultName != null }.forEach { service ->
            require(!service.defaultName.isNullOrBlank()) { "Functional service 0x%04X has an empty default name".format(service.iid) }
            require(service.characteristics.count { it.type == HapType.SERVICE_SIGNATURE && it.properties == HapProperties.READ && it.format == HapFormat.TLV8 } == 1) {
                "Functional service 0x%04X must have exactly one Service Signature".format(service.iid)
            }
            require(service.characteristics.count { it.type == HapType.NAME && it.properties == HapProperties.READ && it.format == HapFormat.STRING } == 1) {
                "Functional service 0x%04X is missing legacy Name".format(service.iid)
            }
            require(service.characteristics.count {
                it.type == HapType.CONFIGURED_NAME && it.properties == HapProperties.CONFIGURED_NAME && it.format == HapFormat.STRING
            } == 1) { "Functional service 0x%04X is missing protocol-correct ConfiguredName".format(service.iid) }
        }
        services.flatMap { it.characteristics }.forEach { characteristic ->
            require(characteristic.control == null || characteristic.readOnlyState == null) {
                "Characteristic IID 0x%04X cannot be both writable and read-only".format(characteristic.iid)
            }
            characteristic.control?.let { control ->
                require(characteristic.properties == HapProperties.CONTROL) { "Control IID 0x%04X must be readable/writable/notifiable".format(characteristic.iid) }
                require(characteristic.format == control.valueKind.format) { "Control IID 0x%04X has wrong value format".format(characteristic.iid) }
            }
            characteristic.readOnlyState?.let {
                require(characteristic.properties == HapProperties.READ) { "Battery IID 0x%04X must use the verified BLE read profile".format(characteristic.iid) }
                require(characteristic.format == HapFormat.UINT32) { "Battery IID 0x%04X must use the verified BLE value profile".format(characteristic.iid) }
            }
        }
    }
}

object HapType {
    const val ACCESSORY_INFORMATION = 0x3E
    const val PROTOCOL_INFORMATION = 0xA2
    const val PAIRING = 0x55
    const val LIGHTBULB = 0x43
    const val SWITCH = 0x49
    const val BATTERY = 0x96
    const val IDENTIFY = 0x14
    const val MANUFACTURER = 0x20
    const val MODEL = 0x21
    const val NAME = 0x23
    const val ON = 0x25
    const val SERIAL_NUMBER = 0x30
    const val FIRMWARE_REVISION = 0x52
    const val PAIR_SETUP = 0x4C
    const val PAIR_VERIFY = 0x4E
    const val PAIRING_FEATURES = 0x4F
    const val PAIRINGS = 0x50
    const val BATTERY_LEVEL = 0x68
    const val STATUS_LOW_BATTERY = 0x79
    const val CHARGING_STATE = 0x8F
    const val SERVICE_SIGNATURE = 0xA5
    const val BRIGHTNESS = 0x08
    const val CONFIGURED_NAME = 0xE3
}

object HapProperties {
    const val READ = 0x0010
    const val WRITE = 0x0020
    const val READ_WRITE = READ or WRITE
    const val CONTROL = 0x00B0
    const val CONFIGURED_NAME = CONTROL
}

object HapFormat {
    const val BOOL: Byte = 0x01
    const val UINT32: Byte = 0x04
    const val STRING: Byte = 0x19
    const val TLV8: Byte = 0x1B
}

enum class HapValueKind(val format: Byte) { BOOLEAN(HapFormat.BOOL), UINT32(HapFormat.UINT32) }
enum class HapReadOnlyState { BATTERY_LEVEL, BATTERY_LOW, BATTERY_CHARGING }
data class HapControlBinding(val command: HomeKitCommand, val valueKind: HapValueKind)

class HapServiceDefinition(
    val type: Int,
    val iid: Int,
    val primary: Boolean = false,
    val supportsConfiguration: Boolean = false,
    val defaultName: String? = null,
    characteristics: List<HapCharacteristicDefinition>
) {
    val characteristics = characteristics.onEach { it.service = this }
}

class HapCharacteristicDefinition(
    val type: Int,
    val iid: Int,
    val properties: Int,
    val format: Byte,
    val control: HapControlBinding? = null,
    val readOnlyState: HapReadOnlyState? = null
) {
    lateinit var service: HapServiceDefinition
}
