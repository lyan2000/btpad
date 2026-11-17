package com.tauslop.btpad;

/**
 * Sticky modifiers of the key strip (pure, unit-tested). Each modifier bit is off, one-shot or
 * locked, and may also be held by a finger:
 * <ul>
 * <li>tap: off → one-shot (applies to the next key, then off again);
 * <li>tap again within {@link #DOUBLE_TAP_MS}: one-shot → locked; later: one-shot → off;
 * <li>tap while locked: off;
 * <li>held while another key is pressed: applies to that key, and the release is not a tap
 * (the state it had before stays, except a one-shot is used up).
 * </ul>
 */
final class Modifiers {
    static final int OFF = 0, ONE_SHOT = 1, LOCKED = 2;
    static final long DOUBLE_TAP_MS = 350;

    private final int[] state = new int[8];
    private final long[] tapped = new long[8];
    private int held, used;

    /** Finger down on modifier {@code bit}. */
    void down(int bit) {
        held |= bit;
        used &= ~bit;
    }

    /** Finger up at {@code ms}. */
    void up(int bit, long ms) {
        if ((held & bit) == 0) return;
        held &= ~bit;
        if ((used & bit) != 0) { // part of a chord: not a tap
            used &= ~bit;
            return;
        }
        int i = Integer.numberOfTrailingZeros(bit);
        switch (state[i]) {
            case OFF:
                state[i] = ONE_SHOT;
                tapped[i] = ms;
                break;
            case ONE_SHOT:
                state[i] = ms - tapped[i] <= DOUBLE_TAP_MS ? LOCKED : OFF;
                break;
            default:
                state[i] = OFF;
        }
    }

    /** Modifier bits that apply to the next key. */
    int mask() {
        int m = held;
        for (int i = 0; i < 8; i++) if (state[i] != OFF) m |= 1 << i;
        return m;
    }

    /** A non-modifier key was pressed: one-shots are used up, held ones become chords. */
    void consume() {
        used |= held;
        for (int i = 0; i < 8; i++) if (state[i] == ONE_SHOT) state[i] = OFF;
    }

    int state(int bit) {
        return state[Integer.numberOfTrailingZeros(bit)];
    }

    boolean held(int bit) {
        return (held & bit) != 0;
    }

    void reset() {
        held = used = 0;
        for (int i = 0; i < 8; i++) state[i] = OFF;
    }
}
