package com.tauslop.btpad;

/**
 * The HID report descriptor and the report encoders/decoders. Pure Java (no Android
 * imports) so it is unit-tested on the JVM.
 *
 * <p>Hosts cache the descriptor at pairing time, so changing it forces every user to re-pair.
 *
 * <p><b>Size limit.</b> Android's HID device stack (system/bt stack/hid/hidd_api.cc,
 * HID_DevAddRecord, Android 9 through 15) wraps the descriptor in the HIDDescriptorList
 * SDP attribute using one-byte lengths: {@code uint8_t seq_len = 4 + desc_len} and
 * {@code UINT8_TO_BE_STREAM(p, desc_len)}. Anything over {@link #SDP_MAX_DESCRIPTOR} bytes
 * wraps the lengths, and on top of that the whole record must fit the 600-byte
 * SDP_MAX_PAD_LEN attribute pad, or SDP_AddAttribute silently drops the attribute (the
 * host then sees no HIDDescriptorList and creates no HID device). That leaves no room for a
 * Windows Precision Touchpad collection (its 256-byte PTPHQA feature report, device
 * configuration collection and finger collections need about 170 bytes on their own), so
 * the touchpad is driven through the mouse report.
 *
 * <p>Report bytes passed to {@code sendReport}/{@code replyReport} exclude the report ID.
 */
final class Hid {
    static final int ID_KEYBOARD = 1;       // in: modifiers, reserved, 6 keys; out: LEDs
    static final int ID_MOUSE = 2;          // in: 5 buttons, X/Y int16, wheel, AC Pan
    static final int ID_CONSUMER = 3;       // in: one 16-bit consumer usage

    static final int KEYBOARD_LEN = 8;
    static final int MOUSE_LEN = 7;
    static final int CONSUMER_LEN = 2;

    static final int LED_NUM_LOCK = 1, LED_CAPS_LOCK = 2, LED_SCROLL_LOCK = 4;

    /** SDP record strings (BluetoothHidDeviceAppSdpSettings); they share the record pad. */
    static final String SDP_NAME = "BTPad";
    static final String SDP_DESCRIPTION = "Keyboard and touchpad";
    static final String SDP_PROVIDER = "tauslop";

    /** Largest descriptor whose 4 + length still fits HID_DevAddRecord's uint8_t seq_len. */
    static final int SDP_MAX_DESCRIPTOR = 255 - 4;

    private static final int[] ITEMS = {
            // ---- Keyboard (report 1) ----
            0x05, 0x01,             // Usage Page (Generic Desktop)
            0x09, 0x06,             // Usage (Keyboard)
            0xA1, 0x01,             // Collection (Application)
            0x85, ID_KEYBOARD,
            0x05, 0x07,             //   Usage Page (Keyboard)
            0x19, 0xE0, 0x29, 0xE7, //   Usage Min/Max (LeftControl..RightGUI)
            0x15, 0x00, 0x25, 0x01, //   Logical 0..1
            0x75, 0x01, 0x95, 0x08, //   8 x 1 bit
            0x81, 0x02,             //   Input (Data,Var,Abs): modifiers
            0x81, 0x01,             //   Input (Const): reserved byte (8 x 1 bit again)
            0x05, 0x08,             //   Usage Page (LEDs)
            0x19, 0x01, 0x29, 0x05, //   Num Lock..Kana
            0x95, 0x05,             //   5 x 1 bit
            0x91, 0x02,             //   Output (Data,Var,Abs)
            0x95, 0x03,             //   3 x 1 bit
            0x91, 0x01,             //   Output (Const): padding
            0x05, 0x07,             //   Usage Page (Keyboard)
            0x19, 0x00, 0x2A, 0xFF, 0x00, // Usage Min/Max 0..255
            0x26, 0xFF, 0x00,       //   Logical max 255
            0x75, 0x08, 0x95, 0x06, //   6 x 8 bit
            0x81, 0x00,             //   Input (Data,Array): keys
            0xC0,

            // ---- Mouse (report 2) ----
            0x05, 0x01,             // Usage Page (Generic Desktop)
            0x09, 0x02,             // Usage (Mouse)
            0xA1, 0x01,             // Collection (Application)
            0x85, ID_MOUSE,
            0x09, 0x01,             //   Usage (Pointer)
            0xA1, 0x00,             //   Collection (Physical)
            0x05, 0x09,             //     Usage Page (Button)
            0x19, 0x01, 0x29, 0x05, //     Buttons 1..5
            0x15, 0x00, 0x25, 0x01, //     Logical 0..1
            0x75, 0x01, 0x95, 0x05, //     5 x 1 bit
            0x81, 0x02,             //     Input (Data,Var,Abs)
            0x95, 0x03,             //     3 x 1 bit
            0x81, 0x01,             //     Input (Const): padding
            0x05, 0x01,             //     Usage Page (Generic Desktop)
            0x09, 0x30, 0x09, 0x31, //     X, Y
            0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, // Logical -32767..32767
            0x75, 0x10, 0x95, 0x02, //     2 x 16 bit
            0x81, 0x06,             //     Input (Data,Var,Rel)
            0x09, 0x38,             //     Wheel
            0x15, 0x81, 0x25, 0x7F, //     Logical -127..127
            0x75, 0x08, 0x95, 0x01, //     1 x 8 bit
            0x81, 0x06,             //     Input (Data,Var,Rel)
            0x05, 0x0C,             //     Usage Page (Consumer)
            0x0A, 0x38, 0x02,       //     Usage (AC Pan)
            0x81, 0x06,             //     Input (Data,Var,Rel): same size/range as wheel
            0xC0,
            0xC0,

            // ---- Consumer control (report 3) ----
            0x05, 0x0C,             // Usage Page (Consumer)
            0x09, 0x01,             // Usage (Consumer Control)
            0xA1, 0x01,             // Collection (Application)
            0x85, ID_CONSUMER,
            0x15, 0x00, 0x26, 0xFF, 0x03, // Logical 0..1023
            0x19, 0x00, 0x2A, 0xFF, 0x03, // Usage 0..1023
            0x75, 0x10, 0x95, 0x01, //   1 x 16 bit
            0x81, 0x00,             //   Input (Data,Array)
            0xC0,
    };

    static final byte[] DESCRIPTOR = buildDescriptor();

    private Hid() {}

    private static byte[] buildDescriptor() {
        byte[] d = new byte[ITEMS.length];
        for (int i = 0; i < d.length; i++) d[i] = (byte) ITEMS[i];
        return d;
    }

    // ---- Input reports ----

    /** Keyboard: modifier bits (0x01 LCtrl .. 0x80 RGUI) and up to 6 pressed usages. */
    static byte[] keyboard(int modifiers, int... keys) {
        byte[] r = new byte[KEYBOARD_LEN];
        r[0] = (byte) modifiers;
        for (int i = 0; i < keys.length && i < 6; i++) r[2 + i] = (byte) keys[i];
        return r;
    }

    /** Mouse: buttons bit 0 = left, 1 = right, 2 = middle, 3 = back, 4 = forward. */
    static byte[] mouse(int buttons, int dx, int dy, int wheel, int pan) {
        dx = clamp(dx, -32767, 32767);
        dy = clamp(dy, -32767, 32767);
        return new byte[]{
                (byte) (buttons & 0x1F),
                (byte) dx, (byte) (dx >> 8),
                (byte) dy, (byte) (dy >> 8),
                (byte) clamp(wheel, -127, 127),
                (byte) clamp(pan, -127, 127),
        };
    }

    /** Consumer control usage (e.g. 0xE9 volume up), or 0 for release. */
    static byte[] consumer(int usage) {
        return new byte[]{(byte) usage, (byte) (usage >> 8)};
    }

    /**
     * Value byte of a one-byte SET_REPORT / output report (LEDs). The stack may or may not
     * include the report ID byte, so a 2-byte payload starting with the ID is unwrapped.
     * Returns -1 for an empty payload.
     */
    static int oneByte(int id, byte[] data) {
        if (data == null || data.length == 0) return -1;
        if (data.length >= 2 && data[0] == id) return data[1] & 0xFF;
        return data[0] & 0xFF;
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
