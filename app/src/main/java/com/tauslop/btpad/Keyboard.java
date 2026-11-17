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

        /** Send nothing for at least {@code ns} after the previous report. */
        default void pause(long ns) {}
    }

    /** Code flag: a consumer usage (report 3) rather than a keyboard usage. */
    static final int CONSUMER = 0x10000;

    /**
     * Unicode entry pauses. After Ctrl+Shift+U, IBus 1.5.26 hands the keys to its emoji/Unicode
     * extension (another process, over D-Bus): digits sent before it is ready leak to the app.
     */
    static final long UNICODE_START_NS = 120_000_000, UNICODE_COMMIT_NS = 30_000_000,
            UNICODE_AFTER_NS = 30_000_000;

    final Modifiers mods = new Modifiers();
    /** Host Caps Lock LED. */
    boolean caps;
    /**
     * Enter characters the US map can't type as Unicode (Ctrl+Shift+U; IBus/GTK on Linux).
     * Changing it changes what {@link #edit} thinks the host has: clear the type field.
     */
    boolean unicode;
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
     * Type the edit that turned {@code before} into {@code after}, by code point: Backspace for
     * each removed code point after the common prefix that the host has (plain, so a one-shot
     * Ctrl doesn't delete words), then the new ones. A code point a US keyboard can't type is
     * entered as Unicode when {@link #unicode} is on (one character on the host: one Backspace
     * later), else skipped (never sent: no Backspace). Variation selectors are always dropped.
     * Returns how many new code points were skipped (dropped variation selectors don't count).
     */
    int edit(CharSequence before, CharSequence after) {
        int p = 0, n = Math.min(before.length(), after.length());
        while (p < n && before.charAt(p) == after.charAt(p)) p++;
        // Don't split a surrogate pair: its high half matching doesn't make it common.
        if (p > 0 && Character.isHighSurrogate(before.charAt(p - 1))) p--;
        for (int i = p; i < before.length(); ) {
            int cp = Character.codePointAt(before, i);
            i += Character.charCount(cp);
            if (onHost(cp)) {
                out.send(Hid.ID_KEYBOARD, report(hw, Keys.BACKSPACE));
                emit();
            }
        }
        int skipped = 0;
        for (int i = p; i < after.length(); ) {
            int cp = Character.codePointAt(after, i);
            i += Character.charCount(cp);
            if (cp < Character.MIN_SUPPLEMENTARY_CODE_POINT && type((char) cp)) continue;
            if (unicode && Keys.unicodeEnterable(cp)) {
                unicode(cp);
            } else if (!Keys.isVariationSelector(cp)) {
                skipped++;
            }
        }
        return skipped;
    }

    /** Whether typing {@code cp} through {@link #edit} put a character on the host. */
    private boolean onHost(int cp) {
        if (cp < Character.MIN_SUPPLEMENTARY_CODE_POINT && Keys.forChar((char) cp) != 0) return true;
        return unicode && Keys.unicodeEnterable(cp);
    }

    /**
     * Enter one code point the IBus way: Ctrl+Shift+U, all up, a pause while IBus enters Unicode
     * mode, the lower-case hex digits (each released before the next, so repeats register), a
     * pause, then Space to commit and a pause before what follows. Sticky, held and hardware
     * modifiers and held keys are left out (they'd turn the digits into shortcuts), and a
     * one-shot modifier is not used up: it stays for the next mapped key.
     */
    private void unicode(int cp) {
        out.send(Hid.ID_KEYBOARD, Hid.keyboard(Keys.LCTRL | Keys.LSHIFT, Keys.U));
        out.send(Hid.ID_KEYBOARD, Hid.keyboard(0));
        out.pause(UNICODE_START_NS);
        for (int usage : Keys.hexUsages(cp)) {
            out.send(Hid.ID_KEYBOARD, Hid.keyboard(0, usage));
            out.send(Hid.ID_KEYBOARD, Hid.keyboard(0));
        }
        out.pause(UNICODE_COMMIT_NS);
        out.send(Hid.ID_KEYBOARD, Hid.keyboard(0, Keys.SPACE));
        out.send(Hid.ID_KEYBOARD, Hid.keyboard(0));
        out.pause(UNICODE_AFTER_NS);
        if (count > 0 || hw != 0) emit(); // back to whatever is held
    }

    /**
     * {@code s} without its composing span [start, end) (start &lt; 0: none): the committed text,
     * what a Chinese IME has really chosen (its composing span is pinyin, not text to type).
     */
    static String committed(CharSequence s, int start, int end) {
        String t = s.toString();
        if (start < 0 || end <= start || end > t.length()) return t;
        return t.substring(0, start) + t.substring(end);
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
