package dev.local.mihotspot.peripheral

/**
 * Standard USB HID 1.11 wire format for a composite keyboard + relative mouse +
 * consumer-control device.
 *
 * The report descriptor below is the widely shared composite descriptor used by
 * the Linux kernel's HID examples and the Arduino/Adafruit HID libraries
 * (MIT/BSD, no attribution required beyond this note). It is kept byte-for-byte
 * standard so macOS / Windows / Linux bind their in-box HID drivers — the host
 * needs no software, no driver and no custom protocol.
 *
 * References:
 *  - USB Device Class Definition for HID 1.11, §6.2.2 (report descriptors)
 *  - USB HID Usage Tables 1.12 (Generic Desktop, Keyboard/Keypad, LED, Consumer)
 *
 * Transport is classic Bluetooth HID (BluetoothHidDevice, API 28+), which is
 * what the host sees as "a Bluetooth keyboard / mouse".
 */
object HidProtocol {

    const val REPORT_ID_KEYBOARD = 1
    const val REPORT_ID_MOUSE = 2
    const val REPORT_ID_CONSUMER = 3

    // Keyboard modifier byte (HID usage 0xE0..0xE7), bit 0 = leftmost.
    const val MOD_LEFT_CTRL = 0x01
    const val MOD_LEFT_SHIFT = 0x02
    const val MOD_LEFT_ALT = 0x04
    const val MOD_LEFT_GUI = 0x08
    const val MOD_RIGHT_CTRL = 0x10
    const val MOD_RIGHT_SHIFT = 0x20
    const val MOD_RIGHT_ALT = 0x40
    const val MOD_RIGHT_GUI = 0x80

    // Mouse button bits.
    const val BUTTON_LEFT = 0x01
    const val BUTTON_RIGHT = 0x02
    const val BUTTON_MIDDLE = 0x04

    // Keyboard/Keypad usages used by the on-screen keyboard.
    const val KEY_ENTER = 0x28
    const val KEY_ESC = 0x29
    const val KEY_BACKSPACE = 0x2A
    const val KEY_TAB = 0x2B
    const val KEY_SPACE = 0x2C
    const val KEY_CAPS_LOCK = 0x39
    const val KEY_DELETE = 0x4C
    const val KEY_RIGHT_ARROW = 0x4F
    const val KEY_LEFT_ARROW = 0x50
    const val KEY_DOWN_ARROW = 0x51
    const val KEY_UP_ARROW = 0x52

    private val DESCRIPTOR = intArrayOf(
        // ---- Keyboard (report id 1, 8 bytes) ----
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x06,             // Usage (Keyboard)
        0xA1, 0x01,             // Collection (Application)
        0x85, 0x01,             //   Report ID (1)
        0x05, 0x07,             //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0,             //   Usage Minimum (Left Control)
        0x29, 0xE7,             //   Usage Maximum (Right GUI)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1)
        0x95, 0x08,             //   Report Count (8)
        0x81, 0x02,             //   Input (Data,Var,Abs) -> modifiers
        0x75, 0x08,             //   Report Size (8)
        0x95, 0x01,             //   Report Count (1)
        0x81, 0x03,             //   Input (Const,Var,Abs) -> reserved byte
        0x05, 0x07,             //   Usage Page (Keyboard/Keypad)
        0x19, 0x00,             //   Usage Minimum (0)
        0x29, 0x65,             //   Usage Maximum (101)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x65,             //   Logical Maximum (101)
        0x75, 0x08,             //   Report Size (8)
        0x95, 0x06,             //   Report Count (6)
        0x81, 0x00,             //   Input (Data,Array,Abs) -> 6 key slots
        0x05, 0x08,             //   Usage Page (LEDs)
        0x19, 0x01,             //   Usage Minimum (Num Lock)
        0x29, 0x05,             //   Usage Maximum (Kana)
        0x95, 0x05,             //   Report Count (5)
        0x75, 0x01,             //   Report Size (1)
        0x91, 0x02,             //   Output (Data,Var,Abs)
        0x95, 0x01,             //   Report Count (1)
        0x75, 0x03,             //   Report Size (3)
        0x91, 0x01,             //   Output (Const,Ary,Abs) -> LED padding
        0xC0,                   // End Collection

        // ---- Relative mouse (report id 2, 4 bytes) ----
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x02,             // Usage (Mouse)
        0xA1, 0x01,             // Collection (Application)
        0x85, 0x02,             //   Report ID (2)
        0x09, 0x01,             //   Usage (Pointer)
        0xA1, 0x00,             //   Collection (Physical)
        0x05, 0x09,             //     Usage Page (Button)
        0x19, 0x01,             //     Usage Minimum (Button 1)
        0x29, 0x03,             //     Usage Maximum (Button 3)
        0x15, 0x00,             //     Logical Minimum (0)
        0x25, 0x01,             //     Logical Maximum (1)
        0x95, 0x03,             //     Report Count (3)
        0x75, 0x01,             //     Report Size (1)
        0x81, 0x02,             //     Input (Data,Var,Abs)
        0x95, 0x01,             //     Report Count (1)
        0x75, 0x05,             //     Report Size (5)
        0x81, 0x03,             //     Input (Const,Var,Abs) -> padding
        0x05, 0x01,             //     Usage Page (Generic Desktop)
        0x09, 0x30,             //     Usage (X)
        0x09, 0x31,             //     Usage (Y)
        0x09, 0x38,             //     Usage (Wheel)
        0x15, 0x81,             //     Logical Minimum (-127)
        0x25, 0x7F,             //     Logical Maximum (127)
        0x75, 0x08,             //     Report Size (8)
        0x95, 0x03,             //     Report Count (3)
        0x81, 0x06,             //     Input (Data,Var,Rel)
        0xC0,                   //   End Collection
        0xC0,                   // End Collection

        // ---- Consumer control (report id 3, 2 bytes) ----
        0x05, 0x0C,             // Usage Page (Consumer)
        0x09, 0x01,             // Usage (Consumer Control)
        0xA1, 0x01,             // Collection (Application)
        0x85, 0x03,             //   Report ID (3)
        0x15, 0x00,             //   Logical Minimum (0)
        0x26, 0x3C, 0x02,       //   Logical Maximum (572)
        0x19, 0x00,             //   Usage Minimum (0)
        0x2A, 0x3C, 0x02,       //   Usage Maximum (572)
        0x75, 0x10,             //   Report Size (16)
        0x95, 0x01,             //   Report Count (1)
        0x81, 0x00,             //   Input (Data,Array,Abs)
        0xC0                    // End Collection
    )

    val reportDescriptor: ByteArray = ByteArray(DESCRIPTOR.size) { DESCRIPTOR[it].toByte() }

    /** Keyboard input report: modifier byte + reserved byte + six key slots. */
    fun keyboardReport(modifiers: Int, keys: IntArray): ByteArray {
        val report = ByteArray(8)
        report[0] = (modifiers and 0xFF).toByte()
        val count = minOf(keys.size, 6)
        for (i in 0 until count) report[2 + i] = (keys[i] and 0xFF).toByte()
        return report
    }

    /** Mouse input report: buttons + signed dx/dy/wheel. */
    fun mouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(),
        dx.coerceIn(-127, 127).toByte(),
        dy.coerceIn(-127, 127).toByte(),
        wheel.coerceIn(-127, 127).toByte()
    )

    /** Consumer control input report (little endian 16-bit usage). */
    fun consumerReport(usage: Int): ByteArray = byteArrayOf(
        (usage and 0xFF).toByte(),
        ((usage shr 8) and 0xFF).toByte()
    )

    /** A single keystroke resolved from an ASCII character. */
    data class Stroke(val usage: Int, val shift: Boolean)

    /**
     * Map one ASCII character to a HID keyboard usage plus shift state.
     * Returns null for characters a plain HID keyboard cannot type (CJK, emoji,
     * composed IME output); those must be produced by the host IME.
     */
    fun strokeFor(char: Char): Stroke? = when (char) {
        in 'a'..'z' -> Stroke(0x04 + (char - 'a'), shift = false)
        in 'A'..'Z' -> Stroke(0x04 + (char - 'A'), shift = true)
        in '1'..'9' -> Stroke(0x1E + (char - '1'), shift = false)
        '0' -> Stroke(0x27, shift = false)
        ' ' -> Stroke(KEY_SPACE, shift = false)
        '\n' -> Stroke(KEY_ENTER, shift = false)
        '\t' -> Stroke(KEY_TAB, shift = false)
        '-' -> Stroke(0x2D, shift = false)
        '=' -> Stroke(0x2E, shift = false)
        '[' -> Stroke(0x2F, shift = false)
        ']' -> Stroke(0x30, shift = false)
        '\\' -> Stroke(0x31, shift = false)
        ';' -> Stroke(0x33, shift = false)
        '\'' -> Stroke(0x34, shift = false)
        '`' -> Stroke(0x35, shift = false)
        ',' -> Stroke(0x36, shift = false)
        '.' -> Stroke(0x37, shift = false)
        '/' -> Stroke(0x38, shift = false)
        '!' -> Stroke(0x1E, shift = true)
        '@' -> Stroke(0x1F, shift = true)
        '#' -> Stroke(0x20, shift = true)
        '$' -> Stroke(0x21, shift = true)
        '%' -> Stroke(0x22, shift = true)
        '^' -> Stroke(0x23, shift = true)
        '&' -> Stroke(0x24, shift = true)
        '*' -> Stroke(0x25, shift = true)
        '(' -> Stroke(0x26, shift = true)
        ')' -> Stroke(0x27, shift = true)
        '_' -> Stroke(0x2D, shift = true)
        '+' -> Stroke(0x2E, shift = true)
        '{' -> Stroke(0x2F, shift = true)
        '}' -> Stroke(0x30, shift = true)
        '|' -> Stroke(0x31, shift = true)
        ':' -> Stroke(0x33, shift = true)
        '"' -> Stroke(0x34, shift = true)
        '~' -> Stroke(0x35, shift = true)
        '<' -> Stroke(0x36, shift = true)
        '>' -> Stroke(0x37, shift = true)
        '?' -> Stroke(0x38, shift = true)
        else -> null
    }
}
