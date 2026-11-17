package com.tauslop.btpad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class KeysTest {
    /** What a US keyboard types for usage + Shift (the reverse map, written out by hand). */
    private static char typed(int usage, boolean shift) {
        if (usage >= 0x04 && usage <= 0x1D) {
            char c = (char) ('a' + usage - 0x04);
            return shift ? Character.toUpperCase(c) : c;
        }
        String row = shift ? "!@#$%^&*()" : "1234567890";
        if (usage >= 0x1E && usage <= 0x27) return row.charAt(usage - 0x1E);
        String plain = " -=[]\\?;'`,./", shifted = " _+{}|?:\"~<>?";
        if (usage >= 0x2C && usage <= 0x38 && usage != 0x32) {
            return (shift ? shifted : plain).charAt(usage - 0x2C);
        }
        throw new AssertionError("unexpected usage " + usage);
    }

    @Test
    public void everyPrintableAsciiRoundTrips() {
        Map<Integer, Character> seen = new HashMap<>();
        for (char c = 0x20; c < 0x7F; c++) {
            int k = Keys.forChar(c);
            assertNotEquals("unmapped '" + c + "'", 0, k);
            boolean shift = (k & Keys.SHIFT) != 0;
            assertEquals(c, typed(k & 0xFF, shift));
            if (c != ' ') assertEquals(null, seen.put(k, c)); // one char per usage+shift
        }
        assertEquals(Keys.SPACE, Keys.forChar(' '));
    }

    @Test
    public void shiftRules() {
        assertEquals(Keys.A, Keys.forChar('a'));
        assertEquals(Keys.A | Keys.SHIFT, Keys.forChar('A'));
        assertEquals(Keys.N1, Keys.forChar('1'));
        assertEquals(Keys.N1 | Keys.SHIFT, Keys.forChar('!'));
        assertEquals(Keys.N0 | Keys.SHIFT, Keys.forChar(')'));
        assertEquals(0x38 | Keys.SHIFT, Keys.forChar('?'));
        assertEquals(0x35 | Keys.SHIFT, Keys.forChar('~'));
        assertEquals(Keys.ENTER, Keys.forChar('\n'));
        assertEquals(Keys.TAB, Keys.forChar('\t'));
    }

    @Test
    public void unmappableCharacters() {
        for (char c : new char[]{'é', 'ß', '€', ' ', '\uD83D', '\uDE00', '\r', 0x7F, 0}) {
            assertEquals(0, Keys.forChar(c));
            assertEquals(0, Keys.forChar(c, true));
        }
    }

    @Test
    public void capsLockInvertsShiftForLettersOnly() {
        assertEquals(Keys.A | Keys.SHIFT, Keys.forChar('a', true));
        assertEquals(Keys.A, Keys.forChar('A', true));
        assertEquals(Keys.A + 25, Keys.forChar('Z', true));
        assertEquals(Keys.N1, Keys.forChar('1', true));
        assertEquals(Keys.N1 | Keys.SHIFT, Keys.forChar('!', true));
        assertEquals(0x36, Keys.forChar(',', true));
        for (char c = 0x20; c < 0x7F; c++) {
            if (!Character.isLetter(c)) assertEquals(Keys.forChar(c), Keys.forChar(c, true));
        }
    }

    @Test
    public void keyCodes() {
        assertEquals(Keys.A, Keys.forKeyCode(KeyEvent.KEYCODE_A));
        assertEquals(Keys.A + 25, Keys.forKeyCode(KeyEvent.KEYCODE_Z));
        assertEquals(Keys.N1, Keys.forKeyCode(KeyEvent.KEYCODE_1));
        assertEquals(Keys.N0, Keys.forKeyCode(KeyEvent.KEYCODE_0));
        assertEquals(Keys.F12, Keys.forKeyCode(KeyEvent.KEYCODE_F12));
        assertEquals(Keys.BACKSPACE, Keys.forKeyCode(KeyEvent.KEYCODE_DEL));
        assertEquals(Keys.DELETE, Keys.forKeyCode(KeyEvent.KEYCODE_FORWARD_DEL));
        assertEquals(Keys.LEFT, Keys.forKeyCode(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(0xE0, Keys.forKeyCode(KeyEvent.KEYCODE_CTRL_LEFT));
        assertEquals(0xE7, Keys.forKeyCode(KeyEvent.KEYCODE_META_RIGHT));
        assertTrue(Keys.isModifier(Keys.forKeyCode(KeyEvent.KEYCODE_SHIFT_RIGHT)));
        assertEquals(0, Keys.forKeyCode(KeyEvent.KEYCODE_BACK));
        assertEquals(0, Keys.forKeyCode(KeyEvent.KEYCODE_VOLUME_UP));
        // Every character key on a US keyboard maps to the usage forChar gives it.
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789 -=[]" + '\\' + ";'`,./";
        int[] codes = new int[chars.length()];
        for (int i = 0; i < 26; i++) codes[i] = KeyEvent.KEYCODE_A + i;
        for (int i = 0; i < 10; i++) codes[26 + i] = KeyEvent.KEYCODE_0 + i;
        int[] rest = {KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS,
                KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET,
                KeyEvent.KEYCODE_BACKSLASH, KeyEvent.KEYCODE_SEMICOLON,
                KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_GRAVE, KeyEvent.KEYCODE_COMMA,
                KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH};
        System.arraycopy(rest, 0, codes, 36, rest.length);
        for (int i = 0; i < chars.length(); i++) {
            assertEquals("'" + chars.charAt(i) + "'", Keys.forChar(chars.charAt(i)),
                    Keys.forKeyCode(codes[i]));
        }
    }
}
