package com.tauslop.btpad;

/**
 * Keyboard state behind the key strip, the type field and a hardware keyboard (pure,
 * unit-tested): held keys, sticky modifiers, hardware modifiers and the host's Caps Lock,
 * turned into keyboard and consumer reports for {@link Out} (Sender paces them).
 *
 * <p>Sticky modifiers never go out on their own (a lone Super tap would open the host's
 * launcher): they ride on the reports of the keys they modify, and stay latched while those
 * keys are held.
 */
final class Keyboard {
    interface Out {
        void send(int id, byte[] report);
    }

    /** Code flag: a consumer usage (report 3) rather than a keyboard usage. */
    static final int CONSUMER = 0x10000;

    final Modifiers mods = new Modifiers();
    /** Host Caps Lock LED. */
    boolean caps;
    /** Called when the sticky modifier state changes (redraw the strip). */
    Runnable changed;

    private final Out out;
    private final int[] held = new int[6];
    private int count;
    /** Hardware keyboard modifier bits, and sticky bits latched by the held keys. */
    private int hw, latched;

    Keyboard(Out out) {
        this.out = out;
    }

    // ---- Sticky modifiers (key strip) ----

    void modDown(int bit) {
        mods.down(bit);
        changed();
    }

    void modUp(int bit, long ms) {
        mods.up(bit, ms);
        changed();
    }

    // ---- Keys ----

    /** Key down: a keyboard usage (sticky modifiers apply) or CONSUMER | usage. */
    void press(int code) {
        if ((code & CONSUMER) != 0) {
            out.send(Hid.ID_CONSUMER, Hid.consumer(code & 0xFFFF));
            return;
        }
        if (count == held.length || isHeld(code)) return;
        latched |= mods.mask();
        held[count++] = code;
        emit();
        consume();
    }

    void release(int code) {
        if ((code & CONSUMER) != 0) {
            out.send(Hid.ID_CONSUMER, Hid.consumer(0));
            return;
        }
        for (int i = 0; i < count; i++) {
            if (held[i] == code) {
                System.arraycopy(held, i + 1, held, i, --count - i);
                if (count == 0) latched = 0;
                emit();
                return;
            }
        }
    }

    /** Hardware keyboard key: modifiers go out as themselves, other keys like press(). */
    void hardware(int usage, boolean down) {
        if (Keys.isModifier(usage)) {
            int bit = 1 << (usage - Keys.MOD_FIRST);
            int was = hw;
            hw = down ? hw | bit : hw & ~bit;
            if (hw != was) emit();
        } else if (down) {
            press(usage);
        } else {
            release(usage);
        }
    }

    /** Press and release a key (sticky modifiers apply). */
    void tap(int usage) {
        press(usage);
        release(usage);
    }

    /**
     * Type one character: press and release with Shift as the US layout (and Caps Lock) needs
     * and the sticky modifiers. Returns false if a US keyboard can't type it.
     */
    boolean type(char c) {
        int k = Keys.forChar(c, caps);
        if (k == 0) return false;
        int usage = k & 0xFF;
        if (isHeld(usage)) return true;
        int m = hw | latched | mods.mask() | ((k & Keys.SHIFT) != 0 ? Keys.LSHIFT : 0);
        out.send(Hid.ID_KEYBOARD, report(m, usage));
        emit();
        consume();
        return true;
    }

    /**
     * Type the edit that turned {@code before} into {@code after}: Backspace for each removed
     * character after the common prefix (plain, so a one-shot Ctrl doesn't delete words), then
     * the new characters. Characters a US keyboard can't type were never sent, so they cost no
     * Backspace. Returns how many new characters were skipped.
     */
    int edit(CharSequence before, CharSequence after) {
        int p = 0, n = Math.min(before.length(), after.length());
        while (p < n && before.charAt(p) == after.charAt(p)) p++;
        for (int i = p; i < before.length(); i++) {
            if (Keys.forChar(before.charAt(i)) != 0) {
                out.send(Hid.ID_KEYBOARD, report(hw, Keys.BACKSPACE));
                emit();
            }
        }
        int skipped = 0;
        for (int i = p; i < after.length(); i++) if (!type(after.charAt(i))) skipped++;
        return skipped;
    }

    /** Forget everything held (Sender.reset releases it on the host). */
    void reset() {
        count = hw = latched = 0;
        mods.reset();
        changed();
    }

    private boolean isHeld(int usage) {
        for (int i = 0; i < count; i++) if (held[i] == usage) return true;
        return false;
    }

    /** Current state: modifiers apply only while a key is held. */
    private void emit() {
        out.send(Hid.ID_KEYBOARD, report(hw | (count > 0 ? latched | mods.mask() : 0), 0));
    }

    /** Held keys plus {@code extra} (0 for none). */
    private byte[] report(int modifiers, int extra) {
        int[] keys = new int[extra != 0 && count < held.length ? count + 1 : count];
        System.arraycopy(held, 0, keys, 0, count);
        if (keys.length > count) keys[count] = extra;
        return Hid.keyboard(modifiers, keys);
    }

    private void consume() {
        int was = mods.mask();
        mods.consume();
        if (mods.mask() != was) changed();
    }

    private void changed() {
        if (changed != null) changed.run();
    }
}
