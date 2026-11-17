package com.tauslop.btpad;

/**
 * Mouse-mode touchpad gestures: turns finger down/move/up events into pointer strokes, scroll
 * strokes and button changes.
 *
 * <ul>
 * <li>One finger moves the pointer. Two fingers scroll (their centroid). Three or more do
 *     nothing but tap.
 * <li>A tap (all fingers lifted within {@link #TAP_NS}, none moved beyond the slop) clicks:
 *     one finger left, two right, three middle.
 * <li>A left tap presses the button and holds it for {@link #DRAG_NS}: touching again within
 *     that window keeps it held, so moving drags and lifting releases. Tapping again releases
 *     and presses anew, which makes a double click.
 * </ul>
 *
 * <p>Movement is held back until it passes the slop (so taps don't nudge the pointer), then
 * replayed from the anchor point, so nothing is lost and nothing jumps. Every change in finger
 * count re-anchors the stroke at the current position. Pure Java; times are nanoseconds on one
 * monotonic clock; positions are px.
 */
final class Gestures {
    interface Out {
        /** Start a pointer stroke at this position (no motion). */
        void pointerDown(long time, float x, float y);

        void pointerMove(long time, float x, float y);

        /** Start a scroll stroke at this position (no scrolling). */
        void scrollDown(long time, float x, float y);

        void scrollMove(long time, float x, float y);

        /** New button state: bit 0 left, 1 right, 2 middle. */
        void buttons(int buttons);

        /** A touch sequence starts (first finger down) or ends (all fingers up or cancelled). */
        default void touch(boolean down) {
        }
    }

    static final int LEFT = 1, RIGHT = 2, MIDDLE = 4;
    /** Longest touch that still counts as a tap. */
    static final long TAP_NS = 200_000_000;
    /** After a left tap, how long the button stays held waiting for a touch that drags. */
    static final long DRAG_NS = 200_000_000;
    /**
     * Held-back motion is replayed over at most this span: about the resampling lag, so it
     * catches up within a couple of ticks instead of in one.
     */
    static final long REPLAY_NS = Motion.LAG_NS;

    private static final int NONE = 0, POINTER = 1, SCROLL = 2;
    private static final int MAX_FINGERS = 10;

    private final Out out;
    private final float slop;

    private final int[] id = new int[MAX_FINGERS];
    /** Current and initial position of each finger. */
    private final float[] x = new float[MAX_FINGERS], y = new float[MAX_FINGERS];
    private final float[] x0 = new float[MAX_FINGERS], y0 = new float[MAX_FINGERS];
    private int n;

    /** This touch sequence (first finger down to last up). */
    private long downTime;
    private int maxFingers;
    private boolean tap;

    /** Current stroke: NONE, POINTER or SCROLL; live once past the slop. */
    private int mode;
    private boolean live;
    private long anchorTime;
    private float anchorX, anchorY;

    private int buttons;
    /** When the button held after a left tap is released, or 0. */
    private long releaseAt;

    Gestures(Out out, float slopPx) {
        this.out = out;
        this.slop = slopPx;
    }

    /** When {@link #timeout} next needs to run, or 0 for never. */
    long deadline() {
        return releaseAt;
    }

    /** Run timers due at {@code now}. */
    void timeout(long now) {
        if (releaseAt != 0 && now >= releaseAt) {
            releaseAt = 0;
            setButtons(0);
        }
    }

    void down(long time, int pointer, float px, float py) {
        timeout(time);
        if (n == MAX_FINGERS || find(pointer) >= 0) return;
        if (n == 0) {
            downTime = time;
            maxFingers = 0;
            tap = true;
            releaseAt = 0; // a held tap button now stays held until this touch ends
            out.touch(true);
        }
        id[n] = pointer;
        x[n] = x0[n] = px;
        y[n] = y0[n] = py;
        n++;
        maxFingers = Math.max(maxFingers, n);
        if (n == 2) setButtons(0); // a second finger ends a drag
        anchor(time);
    }

    void move(long time, int pointer, float px, float py) {
        timeout(time);
        int i = find(pointer);
        if (i < 0) return;
        x[i] = px;
        y[i] = py;
        if (Math.hypot(px - x0[i], py - y0[i]) > slop || time - downTime > TAP_NS) tap = false;

        if (mode == NONE) return;
        float cx = centerX(), cy = centerY();
        if (!live) {
            boolean go = Math.hypot(cx - anchorX, cy - anchorY) > slop
                    || (mode == POINTER && maxFingers == 1 && time - downTime > TAP_NS);
            if (!go) return;
            live = true;
            tap = false;
            long start = Math.max(anchorTime, time - REPLAY_NS);
            if (mode == POINTER) {
                out.pointerDown(start, anchorX, anchorY);
            } else {
                out.scrollDown(start, anchorX, anchorY);
            }
        }
        if (mode == POINTER) {
            out.pointerMove(time, cx, cy);
        } else {
            out.scrollMove(time, cx, cy);
        }
    }

    void up(long time, int pointer) {
        timeout(time);
        int i = find(pointer);
        if (i < 0) return;
        n--;
        for (int k = i; k < n; k++) {
            id[k] = id[k + 1];
            x[k] = x[k + 1];
            y[k] = y[k + 1];
            x0[k] = x0[k + 1];
            y0[k] = y0[k + 1];
        }
        if (time - downTime > TAP_NS) tap = false;
        if (n > 0) {
            anchor(time);
            return;
        }
        mode = NONE;
        if (!tap) {
            setButtons(0); // end of a drag
        } else if (maxFingers == 1) {
            setButtons(0); // a second tap: release first, so the host sees two clicks
            setButtons(LEFT);
            releaseAt = time + DRAG_NS;
        } else if (maxFingers <= 3) {
            setButtons(0);
            setButtons(maxFingers == 2 ? RIGHT : MIDDLE);
            setButtons(0);
        }
        out.touch(false);
    }

    /** Touch stream cancelled (or input must stop): forget all fingers, release buttons. */
    void cancel() {
        n = 0;
        mode = NONE;
        tap = false;
        releaseAt = 0;
        setButtons(0);
        out.touch(false);
    }

    /** New stroke for the current finger count, starting where the fingers are now. */
    private void anchor(long time) {
        mode = n == 1 ? POINTER : n == 2 ? SCROLL : NONE;
        live = false;
        anchorTime = time;
        anchorX = centerX();
        anchorY = centerY();
    }

    /** Position the stroke follows: the finger, or the centroid of the first two. */
    private float centerX() {
        return n == 1 ? x[0] : (x[0] + x[1]) / 2;
    }

    private float centerY() {
        return n == 1 ? y[0] : (y[0] + y[1]) / 2;
    }

    private int find(int pointer) {
        for (int i = 0; i < n; i++) if (id[i] == pointer) return i;
        return -1;
    }

    private void setButtons(int b) {
        if (b == buttons) return;
        buttons = b;
        out.buttons(b);
    }
}
