package com.tauslop.btpad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Keyboard state → reports, written as "mods:key,key" strings ("0:" = all up). */
public class KeyboardTest {
    private final List<String> out = new ArrayList<>();
    private final Keyboard kb = new Keyboard((id, r) -> out.add(format(id, r)));

    private static String format(int id, byte[] r) {
        if (id == Hid.ID_CONSUMER) return "c" + Integer.toHexString((r[0] & 0xFF) | r[1] << 8);
        StringBuilder s = new StringBuilder(Integer.toHexString(r[0] & 0xFF)).append(':');
        for (int i = 2; i < r.length; i++) {
            if (r[i] != 0) s.append(s.charAt(s.length() - 1) == ':' ? "" : ",")
                    .append(Integer.toHexString(r[i] & 0xFF));
        }
        return s.toString();
    }

    private String take() {
        String s = String.join(" ", out);
        out.clear();
        return s;
    }

    private void tapMod(int bit, long ms) {
        kb.modDown(bit);
        kb.modUp(bit, ms);
    }

    // a=4 b=5 c=6 e=8 h=b l=f o=12 t=17 Enter=28 Backspace=2a space=2c Left=50 Right=4f

    @Test
    public void typeWithShift() {
        kb.edit("", "aB!");
        assertEquals("0:4 0: 2:5 0: 2:1e 0:", take());
    }

    @Test
    public void appendTypesOnlyTheNewText() {
        kb.edit("he", "hel");
        assertEquals("0:f 0:", take());
    }

    @Test
    public void repeatedCharsGetAReleaseBetween() {
        kb.edit("", "ll");
        assertEquals("0:f 0: 0:f 0:", take());
    }

    @Test
    public void backspace() {
        kb.edit("hello", "hell");
        assertEquals("0:2a 0:", take());
        kb.edit("hel", "");
        assertEquals("0:2a 0: 0:2a 0: 0:2a 0:", take());
    }

    @Test
    public void autocorrectReplace() {
        kb.edit("I saw teh", "I saw the ");
        // Common prefix "I saw t": delete "eh", type "he ".
        assertEquals("0:2a 0: 0:2a 0: 0:b 0: 0:8 0: 0:2c 0:", take());
    }

    @Test
    public void unmappableCharsAreSkippedAndCostNoBackspace() {
        assertEquals(1, kb.edit("", "a😀")); // one code point
        assertEquals("0:4 0:", take());
        assertEquals(0, kb.edit("a😀", "a"));
        assertEquals("", take());
        assertEquals(1, kb.edit("caf", "café"));
        assertEquals("", take());
    }

    @Test
    public void newlineIsEnter() {
        kb.edit("", "a\n");
        assertEquals("0:4 0: 0:28 0:", take());
    }

    @Test
    public void capsLockInvertsShiftForLetters() {
        kb.caps = true;
        kb.edit("", "aA1!");
        assertEquals("2:4 0: 0:4 0: 0:1e 0: 2:1e 0:", take());
    }

    @Test
    public void oneShotCtrlAppliesToNextTypedCharOnly() {
        tapMod(Keys.LCTRL, 1000);
        kb.edit("", "cc");
        assertEquals("1:6 0: 0:6 0:", take());
    }

    @Test
    public void oneShotCtrlWithStripKey() {
        tapMod(Keys.LCTRL, 1000);
        kb.press(Keys.LEFT);
        kb.release(Keys.LEFT);
        kb.press(Keys.LEFT);
        kb.release(Keys.LEFT);
        assertEquals("1:50 0: 0:50 0:", take());
    }

    @Test
    public void oneShotStaysLatchedWhileItsKeyIsHeld() {
        tapMod(Keys.LCTRL, 1000);
        kb.press(Keys.LEFT); // held: the host repeats Ctrl+Left
        kb.press(Keys.UP);
        kb.release(Keys.UP);
        kb.release(Keys.LEFT);
        assertEquals("1:50 1:50,52 1:50 0:", take());
    }

    @Test
    public void lockedModifierAppliesUntilUnlocked() {
        tapMod(Keys.LSHIFT, 1000);
        tapMod(Keys.LSHIFT, 1100);
        kb.tap(Keys.RIGHT);
        kb.tap(Keys.RIGHT);
        tapMod(Keys.LSHIFT, 3000);
        kb.tap(Keys.RIGHT);
        assertEquals("2:4f 0: 2:4f 0: 0:4f 0:", take());
    }

    @Test
    public void heldModifierChord() {
        kb.modDown(Keys.LCTRL);
        kb.modDown(Keys.LALT);
        assertEquals("", take()); // modifiers alone are never sent
        kb.tap(Keys.F1);
        kb.modUp(Keys.LALT, 1000);
        kb.modUp(Keys.LCTRL, 1000);
        kb.tap(Keys.F1);
        assertEquals("5:3a 0: 0:3a 0:", take());
    }

    @Test
    public void diffBackspacesIgnoreStickyModifiers() {
        tapMod(Keys.LCTRL, 1000);
        kb.edit("ab", "a");
        assertEquals("0:2a 0:", take());
        assertEquals(Modifiers.ONE_SHOT, kb.mods.state(Keys.LCTRL));
    }

    @Test
    public void consumerKeys() {
        tapMod(Keys.LCTRL, 1000);
        kb.press(Keyboard.CONSUMER | Keys.VOLUME_UP);
        kb.release(Keyboard.CONSUMER | Keys.VOLUME_UP);
        assertEquals("ce9 c0", take());
        assertEquals(Modifiers.ONE_SHOT, kb.mods.state(Keys.LCTRL)); // not used up
    }

    @Test
    public void hardwareModifiersGoOutAsThemselves() {
        kb.hardware(0xE0, true); // Ctrl
        kb.hardware(Keys.A + 2, true);
        kb.hardware(Keys.A + 2, false);
        kb.hardware(0xE0, false);
        assertEquals("1: 1:6 1: 0:", take());
    }

    @Test
    public void typingWhileAKeyIsHeldKeepsIt() {
        kb.press(Keys.LEFT);
        kb.edit("", "a");
        kb.release(Keys.LEFT);
        assertEquals("0:50 0:50,4 0:50 0:", take());
    }

    @Test
    public void stickyStateChangesNotify() {
        int[] n = {0};
        kb.changed = () -> n[0]++;
        tapMod(Keys.LCTRL, 1000);
        assertEquals(2, n[0]);
        kb.tap(Keys.A);
        assertEquals(3, n[0]); // one-shot used up
        kb.reset();
        assertEquals(4, n[0]);
    }

    // ---- Unicode entry (Ctrl+Shift+U, hex, Space) ----

    /** Like {@link #kb}, with pauses recorded as "p<ms>". */
    private final Keyboard uk = new Keyboard(new Keyboard.Out() {
        @Override
        public void send(int id, byte[] r) {
            out.add(format(id, r));
        }

        @Override
        public void pause(long ns) {
            out.add("p" + ns / 1_000_000);
        }
    });

    {
        uk.unicode = true;
    }

    // u=18 1=1e 4=21 6=23 9=26 0=27 e=8 f=9

    @Test
    public void unicodeEntrySequence() {
        assertEquals(0, uk.edit("", "你")); // U+4F60
        assertEquals("3:18 0: p120 0:21 0: 0:9 0: 0:23 0: 0:27 0: p30 0:2c 0: p30", take());
    }

    @Test
    public void unicodeRepeatedDigitsAreSeparatePresses() {
        uk.edit("", "一"); // U+4E00
        assertEquals("3:18 0: p120 0:21 0: 0:8 0: 0:27 0: 0:27 0: p30 0:2c 0: p30", take());
    }

    @Test
    public void unicodeSupplementaryIsOneEntry() {
        uk.edit("", "😀"); // U+1F600
        assertEquals("3:18 0: p120 0:1e 0: 0:9 0: 0:23 0: 0:27 0: 0:27 0: p30 0:2c 0: p30",
                take());
    }

    @Test
    public void unicodeCharCostsOneBackspace() {
        uk.edit("", "a你😀");
        take();
        uk.edit("a你😀", "a");
        assertEquals("0:2a 0: 0:2a 0:", take());
    }

    @Test
    public void unicodeMixedWithAscii() {
        uk.edit("", "é!");
        assertEquals("3:18 0: p120 0:8 0: 0:26 0: p30 0:2c 0: p30 2:1e 0:", take()); // U+E9
    }

    @Test
    public void variationSelectorsAreDropped() {
        assertEquals(0, uk.edit("", "\uFE0F"));
        assertEquals("", take());
        assertEquals(0, kb.edit("", "\uFE0F"));
    }

    @Test
    public void unicodeLeavesOneShotModifierForTheNextKey() {
        uk.modDown(Keys.LCTRL);
        uk.modUp(Keys.LCTRL, 0);
        take();
        uk.edit("", "你c");
        String s = take();
        assertTrue(s, s.startsWith("3:18 0: p120 "));
        assertTrue(s, s.endsWith("p30 1:6 0:"));
    }

    @Test
    public void committedDropsTheComposingSpan() {
        assertEquals("你", Keyboard.committed("你ni", 1, 3));
        assertEquals("你ni", Keyboard.committed("你ni", -1, -1));
        assertEquals("", Keyboard.committed("nihao", 0, 5));
    }

    @Test
    public void pinyinCommitTypesOnlyTheCharacters() {
        // A Chinese IME's composing pinyin is stripped (committed), so the host sees no letters.
        String before = Keyboard.committed("ni", 0, 2), after = Keyboard.committed("你", -1, -1);
        assertEquals("", before);
        uk.edit(before, after);
        assertEquals("3:18 0: p120 0:21 0: 0:9 0: 0:23 0: 0:27 0: p30 0:2c 0: p30", take());
    }
}
