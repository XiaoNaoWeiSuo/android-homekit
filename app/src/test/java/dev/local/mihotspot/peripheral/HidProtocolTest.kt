package dev.local.mihotspot.peripheral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HidProtocolTest {

    private class Summary(
        val inputBytes: Map<Int, Int>,
        val depthAtEnd: Int,
        val maxDepth: Int
    )

    /**
     * Minimal HID report-descriptor walker: verifies collection nesting and the
     * input report size each report ID declares, which is exactly what the host
     * will parse. A malformed descriptor would surface as a wrong size here.
     */
    private fun parse(descriptor: ByteArray): Summary {
        var i = 0
        var reportId = 0
        var reportSize = 0
        var reportCount = 0
        var depth = 0
        var maxDepth = 0
        val bits = mutableMapOf<Int, Int>()
        while (i < descriptor.size) {
            val b = descriptor[i].toInt() and 0xFF
            if (b == 0xFE) { // long item, unused here
                val n = descriptor[i + 1].toInt() and 0xFF
                i += 3 + n
                continue
            }
            val size = when (b and 0x03) {
                3 -> 4
                else -> b and 0x03
            }
            val type = (b shr 2) and 0x03
            val tag = (b shr 4) and 0x0F
            var data = 0
            for (k in 0 until size) {
                data = data or ((descriptor[i + 1 + k].toInt() and 0xFF) shl (8 * k))
            }
            i += 1 + size
            when {
                type == 1 && tag == 8 -> reportId = data
                type == 1 && tag == 7 -> reportSize = data
                type == 1 && tag == 9 -> reportCount = data
                type == 0 && tag == 10 -> {
                    depth++
                    maxDepth = maxOf(maxDepth, depth)
                }
                type == 0 && tag == 12 -> depth--
                type == 0 && tag == 8 ->
                    bits[reportId] = (bits[reportId] ?: 0) + reportSize * reportCount
            }
        }
        return Summary(
            inputBytes = bits.mapValues { (it.value + 7) / 8 },
            depthAtEnd = depth,
            maxDepth = maxDepth
        )
    }

    @Test
    fun reportDescriptorDeclaresExpectedReportSizes() {
        val summary = parse(HidProtocol.reportDescriptor)
        assertEquals(0, summary.depthAtEnd)
        assertEquals(2, summary.maxDepth)
        assertEquals(mapOf(1 to 8, 2 to 4, 3 to 2), summary.inputBytes)
    }

    @Test
    fun reportBuildersMatchDescriptorSizes() {
        assertEquals(8, HidProtocol.keyboardReport(0, intArrayOf()).size)
        assertEquals(4, HidProtocol.mouseReport(0, 0, 0, 0).size)
        assertEquals(2, HidProtocol.consumerReport(0).size)
    }

    @Test
    fun keyboardReportEncodesModifiersAndKeys() {
        val report = HidProtocol.keyboardReport(
            HidProtocol.MOD_LEFT_SHIFT,
            intArrayOf(0x04, 0x05)
        )
        assertEquals(HidProtocol.MOD_LEFT_SHIFT.toByte(), report[0])
        assertEquals(0.toByte(), report[1])
        assertEquals(0x04.toByte(), report[2])
        assertEquals(0x05.toByte(), report[3])
        assertEquals(0.toByte(), report[7])
    }

    @Test
    fun keyboardReportTruncatesToSixKeySlots() {
        val report = HidProtocol.keyboardReport(0, IntArray(9) { it + 4 })
        assertEquals(8, report.size)
        assertEquals((4 + 5).toByte(), report[7])
    }

    @Test
    fun mouseReportClampsDeltasToSignedByte() {
        val report = HidProtocol.mouseReport(0x07, 400, -400, 1)
        assertEquals(0x07.toByte(), report[0])
        assertEquals(127.toByte(), report[1])
        assertEquals((-127).toByte(), report[2])
        assertEquals(1.toByte(), report[3])
    }

    @Test
    fun asciiMappingCoversTypicalKeys() {
        assertEquals(HidProtocol.Stroke(0x04, false), HidProtocol.strokeFor('a'))
        assertEquals(HidProtocol.Stroke(0x04, true), HidProtocol.strokeFor('A'))
        assertEquals(HidProtocol.Stroke(0x27, false), HidProtocol.strokeFor('0'))
        assertEquals(HidProtocol.Stroke(0x2C, false), HidProtocol.strokeFor(' '))
        assertEquals(HidProtocol.Stroke(0x1E, true), HidProtocol.strokeFor('!'))
        assertEquals(HidProtocol.Stroke(0x38, true), HidProtocol.strokeFor('?'))
        assertEquals(HidProtocol.Stroke(0x28, false), HidProtocol.strokeFor('\n'))
        assertNull(HidProtocol.strokeFor('中'))
        assertNull(HidProtocol.strokeFor('\uD83D'))
    }
}
