package dev.local.mihotspot.homekit

import java.security.MessageDigest

/**
 * Small, dependency-free implementation of the public HomeKit ADK setup
 * payload algorithm. The payload is the value encoded by the pairing QR code.
 */
object HomeKitSetupPayload {
    private const val PREFIX = "X-HM://"
    private const val CATEGORY_SWITCH = 8

    fun formatSetupCode(digits: String): String {
        val clean = digits.filter(Char::isDigit).padEnd(8, '0').take(8)
        // Keep the project's human-readable representation consistent everywhere.
        return "${clean.substring(0, 4)}-${clean.substring(4, 8)}"
    }

    fun create(digits: String, setupId: String, category: Int = CATEGORY_SWITCH): String {
        val clean = digits.filter(Char::isDigit).takeIf { it.length == 8 }
            ?: error("HomeKit setup code must contain 8 digits")
        val id = setupId.takeIf { it.length == 4 && it.all { c -> c in '0'..'9' || c in 'A'..'Z' } }
            ?: error("HomeKit setup ID must contain four uppercase alphanumeric characters")

        var value = (category.toLong() and 0xFFL) shl 31
        value = value or (1L shl 29) // BLE supported.
        value = value or (
            clean[0].digitToInt().toLong() * 10_000_000L +
                clean[1].digitToInt().toLong() * 1_000_000L +
                clean[2].digitToInt().toLong() * 100_000L +
                clean[3].digitToInt().toLong() * 10_000L +
                clean[4].digitToInt().toLong() * 1_000L +
                clean[5].digitToInt().toLong() * 100L +
                clean[6].digitToInt().toLong() * 10L +
                clean[7].digitToInt().toLong()
            )

        return PREFIX + value.toString(36).uppercase().padStart(9, '0') + id
    }

    /** First four bytes of SHA-512(setupID + uppercase colon-separated Device ID). */
    fun setupHash(setupId: String, deviceId: ByteArray): ByteArray {
        require(deviceId.size == 6)
        val deviceIdString = deviceId.joinToString(":") { "%02X".format(it) }
        return MessageDigest.getInstance("SHA-512")
            .digest((setupId + deviceIdString).toByteArray(Charsets.UTF_8))
            .copyOf(4)
    }
}
