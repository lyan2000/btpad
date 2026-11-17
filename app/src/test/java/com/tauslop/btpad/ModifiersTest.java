package com.tauslop.btpad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModifiersTest {
    private static final int CTRL = Keys.LCTRL, SHIFT = Keys.LSHIFT;
    private final Modifiers m = new Modifiers();

    private void tap(int bit, long ms) {
        m.down(bit);
        m.up(bit, ms);
    }

    @Test
    public void tapIsOneShotForTheNextKey() {
        tap(CTRL, 1000);
        assertEquals(Modifiers.ONE_SHOT, m.state(CTRL));
        assertEquals(CTRL, m.mask());
        m.consume();
        assertEquals(Modifiers.OFF, m.state(CTRL));
        assertEquals(0, m.mask());
    }

    @Test
    public void doubleTapLocksUntilTappedAgain() {
        tap(CTRL, 1000);
        tap(CTRL, 1000 + Modifiers.DOUBLE_TAP_MS);
        assertEquals(Modifiers.LOCKED, m.state(CTRL));
        m.consume();
        m.consume();
        assertEquals(CTRL, m.mask());
        tap(CTRL, 5000);
        assertEquals(Modifiers.OFF, m.state(CTRL));
        assertEquals(0, m.mask());
    }

    @Test
    public void slowSecondTapCancelsOneShot() {
        tap(CTRL, 1000);
        tap(CTRL, 1001 + Modifiers.DOUBLE_TAP_MS);
        assertEquals(Modifiers.OFF, m.state(CTRL));
        assertEquals(0, m.mask());
    }

    @Test
    public void heldModifierChordsAndStaysOffAfterRelease() {
        m.down(CTRL);
        assertTrue(m.held(CTRL));
        assertEquals(CTRL, m.mask());
        m.consume(); // Ctrl+C
        assertEquals(CTRL, m.mask()); // still held: Ctrl+V next works too
        m.consume();
        m.up(CTRL, 1000);
        assertFalse(m.held(CTRL));
        assertEquals(Modifiers.OFF, m.state(CTRL)); // a chord is not a tap
        assertEquals(0, m.mask());
    }

    @Test
    public void heldChordKeepsLock() {
        tap(SHIFT, 1000);
        tap(SHIFT, 1100);
        m.down(SHIFT);
        m.consume();
        m.up(SHIFT, 3000);
        assertEquals(Modifiers.LOCKED, m.state(SHIFT));
    }

    @Test
    public void heldChordUsesUpOneShot() {
        tap(CTRL, 1000);
        m.down(CTRL);
        m.consume();
        m.up(CTRL, 1100);
        assertEquals(Modifiers.OFF, m.state(CTRL));
    }

    @Test
    public void modifiersAreIndependentAndCombine() {
        tap(CTRL, 1000);
        tap(SHIFT, 1010);
        assertEquals(CTRL | SHIFT, m.mask());
        m.consume();
        assertEquals(0, m.mask());
    }

    @Test
    public void reset() {
        tap(CTRL, 1000);
        tap(CTRL, 1100);
        m.down(SHIFT);
        m.reset();
        assertEquals(0, m.mask());
        assertFalse(m.held(SHIFT));
        m.up(SHIFT, 2000); // stray up after reset: ignored
        assertEquals(Modifiers.OFF, m.state(SHIFT));
    }
}
