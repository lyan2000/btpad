package com.tauslop.btpad;

/**
 * The HID report descriptor and the report encoders/decoders. Pure Java (no Android
 * imports) so it is unit-tested on the JVM.
 *
 * <p>The descriptor is final: hosts cache it at pairing time, so changing it later would
 * force every user to re-pair. Feature report <i>values</i> (pad type, blob) may change.
 *
 * <p>Report bytes passed to {@code sendReport}/{@code replyReport} exclude the report ID.
 */
final class Hid {
    static final int ID_KEYBOARD = 1;       // in: modifiers, reserved, 6 keys; out: LEDs
    static final int ID_MOUSE = 2;          // in: 5 buttons, X/Y int16, wheel, AC Pan
    static final int ID_CONSUMER = 3;       // in: one 16-bit consumer usage
    static final int ID_TOUCHPAD = 4;       // in: Windows Precision Touchpad frame
    static final int ID_MAX_CONTACTS = 5;   // feature: max contacts (4 bits) + pad type (4 bits)
    static final int ID_PTPHQA = 6;         // feature: 256-byte certification blob
    static final int ID_INPUT_MODE = 7;     // feature: 0 = mouse, 3 = touchpad
    static final int ID_FUNCTION_SWITCH = 8; // feature: surface switch, button switch

    static final int MAX_CONTACTS = 5;
    /** Touchpad X/Y logical range is 0..PAD_MAX on both axes (10.00 cm square). */
    static final int PAD_MAX = 4095;
    static final int PAD_TYPE_CLICKPAD = 0;
    static final int INPUT_MODE_MOUSE = 0;
    static final int INPUT_MODE_TOUCHPAD = 3;

    static final int KEYBOARD_LEN = 8;
    static final int MOUSE_LEN = 7;
    static final int CONSUMER_LEN = 2;
    static final int FINGER_LEN = 5;
    static final int TOUCHPAD_LEN = MAX_CONTACTS * FINGER_LEN + 4;
    static final int PTPHQA_LEN = 256;

    static final int LED_NUM_LOCK = 1, LED_CAPS_LOCK = 2, LED_SCROLL_LOCK = 4;

    private static final int[] KEYBOARD_MOUSE_CONSUMER = {
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
            0x95, 0x01, 0x75, 0x08, //   1 x 8 bit
            0x81, 0x01,             //   Input (Const): reserved
            0x05, 0x08,             //   Usage Page (LEDs)
            0x19, 0x01, 0x29, 0x05, //   Num Lock..Kana
            0x95, 0x05, 0x75, 0x01, //   5 x 1 bit
            0x91, 0x02,             //   Output (Data,Var,Abs)
            0x95, 0x01, 0x75, 0x03, //   1 x 3 bit
            0x91, 0x01,             //   Output (Const): padding
            0x05, 0x07,             //   Usage Page (Keyboard)
            0x19, 0x00, 0x2A, 0xFF, 0x00, // Usage Min/Max 0..255
            0x15, 0x00, 0x26, 0xFF, 0x00, // Logical 0..255
            0x95, 0x06, 0x75, 0x08, //   6 x 8 bit
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
            0x15, 0x00, 0x25, 0x01,
            0x95, 0x05, 0x75, 0x01,
            0x81, 0x02,             //     Input (Data,Var,Abs)
            0x95, 0x01, 0x75, 0x03,
            0x81, 0x01,             //     Input (Const): padding
            0x05, 0x01,             //     Usage Page (Generic Desktop)
            0x09, 0x30, 0x09, 0x31, //     X, Y
            0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, // Logical -32767..32767
            0x95, 0x02, 0x75, 0x10,
            0x81, 0x06,             //     Input (Data,Var,Rel)
            0x09, 0x38,             //     Wheel
            0x15, 0x81, 0x25, 0x7F, //     Logical -127..127
            0x95, 0x01, 0x75, 0x08,
            0x81, 0x06,             //     Input (Data,Var,Rel)
            0x05, 0x0C,             //     Usage Page (Consumer)
            0x0A, 0x38, 0x02,       //     Usage (AC Pan)
            0x95, 0x01,
            0x81, 0x06,             //     Input (Data,Var,Rel)
            0xC0,
            0xC0,

            // ---- Consumer control (report 3) ----
            0x05, 0x0C,             // Usage Page (Consumer)
            0x09, 0x01,             // Usage (Consumer Control)
            0xA1, 0x01,             // Collection (Application)
            0x85, ID_CONSUMER,
            0x15, 0x00, 0x26, 0xFF, 0x03, // Logical 0..1023
            0x19, 0x00, 0x2A, 0xFF, 0x03, // Usage 0..1023
            0x95, 0x01, 0x75, 0x10,
            0x81, 0x00,             //   Input (Data,Array)
            0xC0,

            // ---- Precision touchpad (report 4, features 5 and 6) ----
            0x05, 0x0D,             // Usage Page (Digitizer)
            0x09, 0x05,             // Usage (Touch Pad)
            0xA1, 0x01,             // Collection (Application)
            0x85, ID_TOUCHPAD,
    };

    /** One finger slot: confidence, tip, 6-bit contact ID, X, Y (0.01 cm units). */
    private static final int[] FINGER = {
            0x05, 0x0D,             // Usage Page (Digitizer)
            0x09, 0x22,             // Usage (Finger)
            0xA1, 0x02,             // Collection (Logical)
            0x15, 0x00, 0x25, 0x01,
            0x09, 0x47, 0x09, 0x42, //   Confidence, Tip Switch
            0x95, 0x02, 0x75, 0x01,
            0x81, 0x02,
            0x25, 0x3F,             //   Logical max 63
            0x09, 0x51,             //   Contact Identifier
            0x95, 0x01, 0x75, 0x06,
            0x81, 0x02,
            0x05, 0x01,             //   Usage Page (Generic Desktop)
            0x26, 0xFF, 0x0F,       //   Logical max 4095
            0x75, 0x10,
            0x55, 0x0E,             //   Unit Exponent (-2)
            0x65, 0x11,             //   Unit (SI Linear: cm)
            0x35, 0x00, 0x46, 0xE8, 0x03, // Physical 0..1000 (10.00 cm)
            0x09, 0x30, 0x81, 0x02, //   X
            0x09, 0x31, 0x81, 0x02, //   Y
            0xC0,
    };

    private static final int[] TOUCHPAD_TAIL = {
            0x05, 0x0D,             //   Usage Page (Digitizer)
            0x55, 0x0C,             //   Unit Exponent (-4)
            0x66, 0x01, 0x10,       //   Unit (seconds) -> 100 us
            0x47, 0xFF, 0xFF, 0x00, 0x00, // Physical max 65535
            0x27, 0xFF, 0xFF, 0x00, 0x00, // Logical max 65535
            0x75, 0x10, 0x95, 0x01,
            0x09, 0x56, 0x81, 0x02, //   Scan Time
            0x55, 0x00, 0x65, 0x00, //   (reset unit)
            0x45, 0x00,             //   (reset physical)
            0x09, 0x54,             //   Contact Count
            0x25, 0x7F, 0x75, 0x08,
            0x81, 0x02,
            0x05, 0x09,             //   Usage Page (Button)
            0x09, 0x01,             //   Button 1
            0x25, 0x01, 0x75, 0x01,
            0x81, 0x02,
            0x95, 0x07,
            0x81, 0x03,             //   padding
            0x05, 0x0D,             //   Usage Page (Digitizer)
            0x85, ID_MAX_CONTACTS,
            0x09, 0x55, 0x09, 0x59, //   Contact Count Maximum, Pad Type
            0x75, 0x04, 0x95, 0x02,
            0x25, 0x0F,
            0xB1, 0x02,             //   Feature (Data,Var,Abs)
            0x06, 0x00, 0xFF,       //   Usage Page (Vendor 0xFF00)
            0x85, ID_PTPHQA,
            0x09, 0xC5,             //   Usage (0xC5: PTPHQA certification)
            0x15, 0x00, 0x26, 0xFF, 0x00,
            0x75, 0x08, 0x96, 0x00, 0x01, // 256 x 8 bit
            0xB1, 0x02,
            0xC0,

            // ---- Device configuration (features 7 and 8) ----
            0x05, 0x0D,             // Usage Page (Digitizer)
            0x09, 0x0E,             // Usage (Device Configuration)
            0xA1, 0x01,             // Collection (Application)
            0x85, ID_INPUT_MODE,
            0x09, 0x22,             //   Usage (Finger)
            0xA1, 0x02,             //   Collection (Logical)
            0x09, 0x52,             //     Input Mode
            0x15, 0x00, 0x25, 0x0A,
            0x75, 0x08, 0x95, 0x01,
            0xB1, 0x02,
            0xC0,
            0x09, 0x22,             //   Usage (Finger)
            0xA1, 0x00,             //   Collection (Physical)
            0x85, ID_FUNCTION_SWITCH,
            0x09, 0x57, 0x09, 0x58, //     Surface Switch, Button Switch
            0x75, 0x01, 0x95, 0x02,
            0x25, 0x01,
            0xB1, 0x02,
            0x95, 0x06,
            0xB1, 0x03,             //     padding
            0xC0,
            0xC0,
    };

    static final byte[] DESCRIPTOR = buildDescriptor();

    /**
     * Default Windows PTP certification blob, as used by open-source PTP drivers
     * (imbushuo/mac-precision-touchpad). A placeholder; Windows does not verify it.
     */
    private static final String PTPHQA_HEX =
            "fc28fe8440cb9a870dbe573cb670098807972d2be33834b66cedb0f7e59cf6c2"
            + "2e841be8b45178431f284b7c2d53affc47701b596f7443c4f34718531aa2a171"
            + "c7950e315521d3b51ee90cbaecb889193eb3af75819d53b94157f46d3925297c"
            + "87d9b498457da7269c653b856889d73bbdff1467f22bf02a4154f0fd2c667cf8"
            + "c08f331303f1d3c10b89d91b62cd51b780b8af3a10c18a5be88a56f08caafa35"
            + "e942c4d855c338cc2b535c6952d5c87302387c73b641e7ff05d82b799ae23460"
            + "8fa3321f097862bc80e30fbd65200813c1e2ee532d867ea75ac5d37d98be3148"
            + "1ffbdaafa2a86a89d6bff2d3322a9ae4cf17b7b8f4e13308248bc443a5e524c2";

    private Hid() {}

    private static byte[] buildDescriptor() {
        int len = KEYBOARD_MOUSE_CONSUMER.length + MAX_CONTACTS * FINGER.length
                + TOUCHPAD_TAIL.length;
        byte[] d = new byte[len];
        int p = copy(KEYBOARD_MOUSE_CONSUMER, d, 0);
        for (int i = 0; i < MAX_CONTACTS; i++) p = copy(FINGER, d, p);
        copy(TOUCHPAD_TAIL, d, p);
        return d;
    }

    private static int copy(int[] src, byte[] dst, int at) {
        for (int v : src) dst[at++] = (byte) v;
        return at;
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
     * Precision touchpad frame. Slots {@code 0..count-1} are reported; the rest are zero.
     * {@code tip[i]} false means that finger has just lifted (report it once).
     * Contact count is {@code count}; scan time is in 100 us units (wraps at 65536).
     */
    static byte[] touchpad(int count, int[] id, int[] x, int[] y, boolean[] tip,
                           int scanTime, boolean button) {
        byte[] r = new byte[TOUCHPAD_LEN];
        count = Math.min(count, MAX_CONTACTS);
        for (int i = 0; i < count; i++) {
            int o = i * FINGER_LEN;
            int px = clamp(x[i], 0, PAD_MAX), py = clamp(y[i], 0, PAD_MAX);
            r[o] = (byte) (1 | (tip[i] ? 2 : 0) | (id[i] & 0x3F) << 2); // confidence always set
            r[o + 1] = (byte) px;
            r[o + 2] = (byte) (px >> 8);
            r[o + 3] = (byte) py;
            r[o + 4] = (byte) (py >> 8);
        }
        int o = MAX_CONTACTS * FINGER_LEN;
        r[o] = (byte) scanTime;
        r[o + 1] = (byte) (scanTime >> 8);
        r[o + 2] = (byte) count;
        r[o + 3] = (byte) (button ? 1 : 0);
        return r;
    }

    // ---- Feature reports (host GET_REPORT / SET_REPORT) ----

    /** Reply for a GET_REPORT(feature, id), or null if the ID is unknown. */
    static byte[] feature(int id, int inputMode) {
        switch (id) {
            case ID_MAX_CONTACTS:
                return new byte[]{(byte) (MAX_CONTACTS | PAD_TYPE_CLICKPAD << 4)};
            case ID_PTPHQA:
                return ptphqa();
            case ID_INPUT_MODE:
                return new byte[]{(byte) inputMode};
            case ID_FUNCTION_SWITCH:
                return new byte[]{3}; // surface and button reporting on
            default:
                return null;
        }
    }

    static byte[] ptphqa() {
        byte[] b = new byte[PTPHQA_LEN];
        for (int i = 0; i < PTPHQA_LEN; i++) {
            b[i] = (byte) Integer.parseInt(PTPHQA_HEX.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    /**
     * Value byte of a one-byte SET_REPORT / output report (input mode, LEDs). The stack may
     * or may not include the report ID byte, so a 2-byte payload starting with the ID
     * is unwrapped. Returns -1 for an empty payload.
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
