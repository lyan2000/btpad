package com.tauslop.btpad;

import android.view.KeyEvent;

/**
 * HID keyboard and consumer usages, the US-layout character map and the Android keycode map.
 * Pure Java: the KeyEvent constants are compile-time constants inlined by javac, so this runs
 * on the JVM in unit tests.
 */
final class Keys {
    // Modifier bits of the keyboard report's first byte.
    static final int LCTRL = 0x01, LSHIFT = 0x02, LALT = 0x04, LGUI = 0x08;

    // Keyboard page (0x07) usages.
    static final int A = 0x04, N1 = 0x1E, N0 = 0x27;
    static final int ENTER = 0x28, ESC = 0x29, BACKSPACE = 0x2A, TAB = 0x2B, SPACE = 0x2C;
    static final int CAPS_LOCK = 0x39, F1 = 0x3A, F12 = 0x45, PRINT_SCREEN = 0x46;
    static final int SCROLL_LOCK = 0x47, PAUSE = 0x48, INSERT = 0x49, HOME = 0x4A;
    static final int PAGE_UP = 0x4B, DELETE = 0x4C, END = 0x4D, PAGE_DOWN = 0x4E;
    static final int RIGHT = 0x4F, LEFT = 0x50, DOWN = 0x51, UP = 0x52, NUM_LOCK = 0x53;
    static final int KP_SLASH = 0x54, KP_1 = 0x59, KP_0 = 0x62, MENU = 0x65;
    /** LeftControl; the eight modifiers are 0xE0..0xE7 (bit = 1 << (usage - 0xE0)). */
    static final int MOD_FIRST = 0xE0, MOD_LAST = 0xE7;

    // Consumer page (0x0C) usages (report 3).
    static final int VOLUME_UP = 0xE9, VOLUME_DOWN = 0xEA, MUTE = 0xE2;
    static final int PLAY_PAUSE = 0xCD, NEXT_TRACK = 0xB5, PREV_TRACK = 0xB6;

    /** Flag in {@link #forChar} results: the character needs Shift. */
    static final int SHIFT = 0x100;

    /** US layout, ASCII 0x20..0x7E: usage | SHIFT. */
    private static final int[] ASCII = new int[0x7F];

    static {
        String plain = " -=[]\\;'`,./";
        int[] plainUsage = {SPACE, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38};
        String shifted = " _+{}|:\"~<>?";
        for (int i = 0; i < plain.length(); i++) {
            ASCII[plain.charAt(i)] = plainUsage[i];
            if (i > 0) ASCII[shifted.charAt(i)] = plainUsage[i] | SHIFT;
        }
        for (int i = 0; i < 26; i++) {
            ASCII['a' + i] = A + i;
            ASCII['A' + i] = (A + i) | SHIFT;
        }
        String digitsShifted = "!@#$%^&*(";
        for (int i = 0; i < 9; i++) {
            ASCII['1' + i] = N1 + i;
            ASCII[digitsShifted.charAt(i)] = (N1 + i) | SHIFT;
        }
        ASCII['0'] = N0;
        ASCII[')'] = N0 | SHIFT;
    }

    private Keys() {}

    /** usage | SHIFT for a character a US keyboard can type, or 0. */
    static int forChar(char c) {
        if (c == '\n') return ENTER;
        if (c == '\t') return TAB;
        return c < ASCII.length ? ASCII[c] : 0;
    }

    /**
     * Like {@link #forChar}, for a host with Caps Lock {@code caps}: letters then need Shift
     * inverted (Shift+letter gives lower case under Caps Lock on Windows and Linux).
     */
    static int forChar(char c, boolean caps) {
        int k = forChar(c);
        if (caps && ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) k ^= SHIFT;
        return k;
    }

    /** Usage of the U key (Ctrl+Shift+U starts IBus/GTK Unicode entry). */
    static final int U = A + ('u' - 'a');

    /** U+FE0E/U+FE0F pick text or emoji style: dropped, never typed (they cost no Backspace). */
    static boolean isVariationSelector(int cp) {
        return cp == 0xFE0E || cp == 0xFE0F;
    }

    /**
     * Whether a code point the US map can't type may be entered as Unicode (Ctrl+Shift+U, hex,
     * Space): any scalar from U+00A0 on except surrogates and the variation selectors.
     */
    static boolean unicodeEnterable(int cp) {
        return cp >= 0xA0 && cp <= Character.MAX_CODE_POINT
                && !(cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE)
                && !isVariationSelector(cp);
    }

    /**
     * Keyboard usages of the lower-case hex digits of {@code cp}, no leading zeros (plain keys:
     * IBus takes either case, so Caps Lock needs no Shift inversion).
     */
    static int[] hexUsages(int cp) {
        String hex = Integer.toHexString(cp);
        int[] u = new int[hex.length()];
        for (int i = 0; i < u.length; i++) u[i] = forChar(hex.charAt(i));
        return u;
    }

    static boolean isModifier(int usage) {
        return usage >= MOD_FIRST && usage <= MOD_LAST;
    }

    /** Keyboard usage for an Android keycode (hardware keyboard), or 0 if unmapped. */
    static int forKeyCode(int code) {
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) {
            return A + code - KeyEvent.KEYCODE_A;
        }
        if (code >= KeyEvent.KEYCODE_1 && code <= KeyEvent.KEYCODE_9) {
            return N1 + code - KeyEvent.KEYCODE_1;
        }
        if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12) {
            return F1 + code - KeyEvent.KEYCODE_F1;
        }
        if (code >= KeyEvent.KEYCODE_NUMPAD_1 && code <= KeyEvent.KEYCODE_NUMPAD_9) {
            return KP_1 + code - KeyEvent.KEYCODE_NUMPAD_1;
        }
        switch (code) {
            case KeyEvent.KEYCODE_0: return N0;
            case KeyEvent.KEYCODE_ENTER: return ENTER;
            case KeyEvent.KEYCODE_ESCAPE: return ESC;
            case KeyEvent.KEYCODE_DEL: return BACKSPACE;
            case KeyEvent.KEYCODE_TAB: return TAB;
            case KeyEvent.KEYCODE_SPACE: return SPACE;
            case KeyEvent.KEYCODE_MINUS: return 0x2D;
            case KeyEvent.KEYCODE_EQUALS: return 0x2E;
            case KeyEvent.KEYCODE_LEFT_BRACKET: return 0x2F;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return 0x30;
            case KeyEvent.KEYCODE_BACKSLASH: return 0x31;
            case KeyEvent.KEYCODE_SEMICOLON: return 0x33;
            case KeyEvent.KEYCODE_APOSTROPHE: return 0x34;
            case KeyEvent.KEYCODE_GRAVE: return 0x35;
            case KeyEvent.KEYCODE_COMMA: return 0x36;
            case KeyEvent.KEYCODE_PERIOD: return 0x37;
            case KeyEvent.KEYCODE_SLASH: return 0x38;
            case KeyEvent.KEYCODE_CAPS_LOCK: return CAPS_LOCK;
            case KeyEvent.KEYCODE_SYSRQ: return PRINT_SCREEN;
            case KeyEvent.KEYCODE_SCROLL_LOCK: return SCROLL_LOCK;
            case KeyEvent.KEYCODE_BREAK: return PAUSE;
            case KeyEvent.KEYCODE_INSERT: return INSERT;
            case KeyEvent.KEYCODE_MOVE_HOME: return HOME;
            case KeyEvent.KEYCODE_PAGE_UP: return PAGE_UP;
            case KeyEvent.KEYCODE_FORWARD_DEL: return DELETE;
            case KeyEvent.KEYCODE_MOVE_END: return END;
            case KeyEvent.KEYCODE_PAGE_DOWN: return PAGE_DOWN;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return RIGHT;
            case KeyEvent.KEYCODE_DPAD_LEFT: return LEFT;
            case KeyEvent.KEYCODE_DPAD_DOWN: return DOWN;
            case KeyEvent.KEYCODE_DPAD_UP: return UP;
            case KeyEvent.KEYCODE_NUM_LOCK: return NUM_LOCK;
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE: return KP_SLASH;
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return 0x55;
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: return 0x56;
            case KeyEvent.KEYCODE_NUMPAD_ADD: return 0x57;
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return 0x58;
            case KeyEvent.KEYCODE_NUMPAD_0: return KP_0;
            case KeyEvent.KEYCODE_NUMPAD_DOT: return 0x63;
            case KeyEvent.KEYCODE_MENU: return MENU;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 0xE0;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 0xE1;
            case KeyEvent.KEYCODE_ALT_LEFT: return 0xE2;
            case KeyEvent.KEYCODE_META_LEFT: return 0xE3;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 0xE4;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 0xE5;
            case KeyEvent.KEYCODE_ALT_RIGHT: return 0xE6;
            case KeyEvent.KEYCODE_META_RIGHT: return 0xE7;
            default: return 0;
        }
    }
}
