package com.tauslop.btpad;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Report sender on the main thread. Pointer and scroll motion are resampled onto the Bluetooth
 * link's report grid (see Motion) and sent as at most one mouse report per tick, so reports
 * never queue up behind the link. Button changes carry the motion before them and are spaced at
 * least a tick apart, so a quick press and release still reach the host as two reports.
 * Keyboard and consumer reports go through their own ordered queue, also at most one per tick,
 * so a burst of typed text (voice dictation) is paced out without merging or dropping a press
 * or release. Pauses in that queue (Unicode entry, see Keyboard.unicode) hold the reports after
 * them. Ticks are posted only while something is pending, so an idle pad costs nothing.
 *
 * <p>Keys don't wake the link the way touches do: if it was idle, the first key waits for the
 * next sniff anchor (up to ~225 ms), and the reports after it follow in order.
 *
 * <p>Link wake-up: Android's HID device profile lets the host put an idle link into sniff
 * subrating (AOSP bta_dm_cfg.cc, HD entry: SSR3 = max latency 360 slots / 225 ms, min remote
 * timeout 160 slots / 100 ms). A report sent then waits for a subrated anchor, and the motion
 * produced meanwhile arrives in one burst. So while a finger is down the link is kept awake:
 * one zero-motion report when the touch starts (during the slop/tap window, before real
 * motion), then another whenever nothing was sent for {@link #KEEPALIVE_NS}. Nothing once all
 * fingers lift.
 */
final class Sender implements Gestures.Out, Keyboard.Out {
    interface Sink {
        void send(int id, byte[] report);
    }

    /** Main-thread timer, a seam for tests. Times are System.nanoTime ns. */
    interface Timer {
        long now();

        void at(Runnable r, long ns);

        void cancel(Runnable r);
    }

    /** No report for this long while a finger is down: send a zero one (under SSR3's 100 ms). */
    static final long KEEPALIVE_NS = 80_000_000;

    /** Finger travel per wheel detent (slow); faster swipes scroll up to SCROLL_MAX_GAIN more. */
    static final float MM_PER_DETENT = 4, SCROLL_MAX_GAIN = 2;
    /** Minimum time between two button reports (a tick, less some timer jitter). */
    private static final long BUTTON_GAP_NS = Motion.TICK_NS - 1_000_000;

    private final Timer timer;
    private final Runnable tick = this::tick, keepalive = this::keepalive, keyTick = this::keyTick;
    private final Sink sink;
    private final Motion motion, scroll;

    /**
     * true: content follows the fingers (touchscreen style). false (default): wheel style, a
     * two-finger swipe up scrolls the view up, like rolling a mouse wheel away from you.
     */
    boolean naturalScroll;

    private int buttons, dx, dy, wheel, pan;
    /** Button states waiting for their own report, oldest first. */
    private final int[] queue = new int[4];
    private int queued;
    private long lastButtons = Long.MIN_VALUE / 2;
    private boolean posted, touching;
    /** When the last mouse report went out, ns. */
    private long lastSent = Long.MIN_VALUE / 2;
    /** Nominal time of the next tick, ns. System.nanoTime and uptimeMillis share a clock. */
    private long next;

    /**
     * Keyboard/consumer reports waiting their turn: report ID, then the report bytes. A pause
     * is ID {@link #PAUSE}, then its length in ns (8 bytes, big-endian).
     */
    private final ArrayDeque<byte[]> keys = new ArrayDeque<>();
    private static final byte PAUSE = 0;
    /** When the last key report went out, ns. */
    private long lastKey = Long.MIN_VALUE / 2;
    /** No key report before this, ns (set by pauses). */
    private long keyNotBefore = Long.MIN_VALUE / 2;
    /** Last keyboard report queued (repeats of it are dropped). */
    private byte[] lastKeyboard = new byte[Hid.KEYBOARD_LEN];
    /** The host may have keys / a consumer usage down. */
    private boolean keyboardDown, consumerDown;

    Sender(Sink sink, float pxPerMm) {
        this(sink, pxPerMm, mainTimer());
    }

    Sender(Sink sink, float pxPerMm, Timer timer) {
        this.sink = sink;
        this.timer = timer;
        motion = new Motion(pxPerMm);
        scroll = new Motion(pxPerMm, 1 / MM_PER_DETENT, SCROLL_MAX_GAIN);
    }

    /** First finger down / last finger up. */
    @Override
    public void touch(boolean down) {
        touching = down;
        if (!down) {
            timer.cancel(keepalive);
            return;
        }
        long now = timer.now();
        // Wake the link now, unless a report went out this tick or one is about to.
        if (!posted && now - lastSent >= Motion.TICK_NS) sendOffGrid(Hid.mouse(buttons, 0, 0, 0, 0), now);
        timer.at(keepalive, Math.max(lastSent + KEEPALIVE_NS, now + Motion.TICK_NS));
    }

    @Override
    public void pointerDown(long time, float x, float y) {
        motion.down(time, x, y);
        schedule();
    }

    @Override
    public void pointerMove(long time, float x, float y) {
        motion.move(time, x, y);
        schedule();
    }

    @Override
    public void scrollDown(long time, float x, float y) {
        scroll.down(time, x, y);
        schedule();
    }

    @Override
    public void scrollMove(long time, float x, float y) {
        scroll.move(time, x, y);
        schedule();
    }

    /**
     * User speed settings, as multiples of the defaults: pointer scales COUNTS_PER_MM, scroll
     * scales detents per mm (1 / MM_PER_DETENT).
     */
    void setSpeed(float pointer, float scrolling) {
        motion.setCountsPerMm(Motion.COUNTS_PER_MM * pointer);
        scroll.setCountsPerMm(scrolling / MM_PER_DETENT);
    }

    /** Button state change: carries the motion that preceded it. */
    @Override
    public void buttons(int b) {
        if (b == (queued > 0 ? queue[queued - 1] : buttons)) return;
        long now = timer.now();
        if (queued == 0 && now - lastButtons >= BUTTON_GAP_NS) {
            buttons = b;
            lastButtons = now;
            takeMotion(now);
            sendOffGrid(takeMouse(), now);
            return;
        }
        if (queued == queue.length) queued--; // can't happen with gesture input; keep the newest
        queue[queued++] = b;
        schedule();
    }

    /**
     * A keyboard or consumer report: queued in order and sent at most one per tick, never
     * merged or dropped. A keyboard report equal to the previous one changes nothing and is
     * skipped.
     */
    @Override
    public void send(int id, byte[] report) {
        if (id == Hid.ID_KEYBOARD) {
            if (Arrays.equals(report, lastKeyboard)) return;
            lastKeyboard = report;
        }
        byte[] e = new byte[report.length + 1];
        e[0] = (byte) id;
        System.arraycopy(report, 0, e, 1, report.length);
        enqueue(e);
    }

    /** Hold the key queue: the next key report goes out at least {@code ns} after the last. */
    @Override
    public void pause(long ns) {
        byte[] e = new byte[9];
        e[0] = PAUSE;
        for (int i = 0; i < 8; i++) e[8 - i] = (byte) (ns >>> (8 * i));
        enqueue(e);
    }

    private void enqueue(byte[] e) {
        keys.add(e);
        if (keys.size() == 1) keyTick();
    }

    private void keyTick() {
        long now = timer.now();
        takePauses();
        // 1 ms of slack, like BUTTON_GAP_NS, for timer rounding.
        if (!keys.isEmpty() && now - lastKey >= BUTTON_GAP_NS && now >= keyNotBefore - 1_000_000) {
            byte[] e = keys.poll();
            byte[] r = Arrays.copyOfRange(e, 1, e.length);
            boolean down = false;
            for (byte b : r) down |= b != 0;
            if (e[0] == Hid.ID_KEYBOARD) keyboardDown = down; else consumerDown = down;
            lastKey = now;
            sink.send(e[0], r);
            takePauses();
        }
        if (!keys.isEmpty()) timer.at(keyTick, Math.max(lastKey + Motion.TICK_NS, keyNotBefore));
    }

    /** Pauses at the head of the queue start counting from the last key report. */
    private void takePauses() {
        while (!keys.isEmpty() && keys.peek()[0] == PAUSE) {
            byte[] e = keys.poll();
            long ns = 0;
            for (int i = 1; i < 9; i++) ns = ns << 8 | (e[i] & 0xFF);
            keyNotBefore = Math.max(keyNotBefore, lastKey + ns);
        }
    }

    /** Drop pending work and release held buttons and keys (activity paused, link changed). */
    void reset() {
        timer.cancel(tick);
        timer.cancel(keepalive);
        timer.cancel(keyTick);
        keys.clear();
        keyNotBefore = Long.MIN_VALUE / 2;
        lastKeyboard = new byte[Hid.KEYBOARD_LEN];
        if (keyboardDown) {
            keyboardDown = false;
            sink.send(Hid.ID_KEYBOARD, lastKeyboard.clone());
        }
        if (consumerDown) {
            consumerDown = false;
            sink.send(Hid.ID_CONSUMER, Hid.consumer(0));
        }
        posted = touching = false;
        motion.reset();
        scroll.reset();
        dx = dy = wheel = pan = 0;
        queued = 0;
        if (buttons != 0) {
            buttons = 0;
            sendMouse(Hid.mouse(0, 0, 0, 0, 0), timer.now());
        }
    }

    private boolean pending() {
        return motion.pending() || scroll.pending() || queued > 0
                || dx != 0 || dy != 0 || wheel != 0 || pan != 0;
    }

    private void schedule() {
        if (posted || !pending()) return;
        posted = true;
        long now = timer.now();
        if (next < now - Motion.TICK_NS) next = now; // idle or fell behind: restart the grid
        timer.at(tick, next);
    }

    private void tick() {
        posted = false;
        long t = next;
        next += Motion.TICK_NS;
        long now = timer.now();
        if (queued > 0 && now - lastButtons >= BUTTON_GAP_NS) {
            // Motion up to the tick, then the new buttons, in one report.
            buttons = queue[0];
            System.arraycopy(queue, 1, queue, 0, --queued);
            lastButtons = now;
            takeMotion(t);
            sendMouse(takeMouse(), now);
        } else {
            takeMotion(t);
            if (dx != 0 || dy != 0 || wheel != 0 || pan != 0) sendMouse(takeMouse(), now);
        }
        schedule();
    }

    /** While a finger rests: a zero-motion report (same buttons) if the link went quiet. */
    private void keepalive() {
        if (!touching) return;
        long now = timer.now();
        if (!posted && now - lastSent >= KEEPALIVE_NS - 1_000_000) {
            sendOffGrid(Hid.mouse(buttons, 0, 0, 0, 0), now);
        }
        timer.at(keepalive, Math.max(lastSent + KEEPALIVE_NS, now + Motion.TICK_NS));
    }

    /** A report outside the tick loop: the next tick waits a full tick after it. */
    private void sendOffGrid(byte[] report, long now) {
        sendMouse(report, now);
        if (next < now + Motion.TICK_NS) {
            next = now + Motion.TICK_NS;
            if (posted) timer.at(tick, next); // replaces the pending post
        }
    }

    private void sendMouse(byte[] report, long now) {
        lastSent = now;
        sink.send(Hid.ID_MOUSE, report);
    }

    private void takeMotion(long now) {
        if (motion.pending()) {
            motion.tick(now);
            dx += motion.dx;
            dy += motion.dy;
        }
        if (scroll.pending()) {
            scroll.tick(now);
            // HID wheel + scrolls the view up, AC Pan + scrolls it right; screen y grows down.
            // Natural: fingers up (dy < 0) move the content up, so the view scrolls down
            // (wheel -), and fingers left scroll the view right (pan +). Wheel style: reversed.
            int s = naturalScroll ? 1 : -1;
            wheel += s * scroll.dy;
            pan -= s * scroll.dx;
        }
    }

    /** Current buttons plus as much accumulated motion as fits one report. */
    private byte[] takeMouse() {
        int x = Hid.clamp(dx, -32767, 32767), y = Hid.clamp(dy, -32767, 32767);
        int w = Hid.clamp(wheel, -127, 127), p = Hid.clamp(pan, -127, 127);
        dx -= x;
        dy -= y;
        wheel -= w;
        pan -= p;
        return Hid.mouse(buttons, x, y, w, p);
    }

    /** Handler on the main looper; System.nanoTime and uptimeMillis share a clock. */
    private static Timer mainTimer() {
        Handler h = new Handler(Looper.getMainLooper());
        return new Timer() {
            @Override
            public long now() {
                return System.nanoTime();
            }

            @Override
            public void at(Runnable r, long ns) {
                h.removeCallbacks(r);
                h.postAtTime(r, (ns + 999_999) / 1_000_000);
            }

            @Override
            public void cancel(Runnable r) {
                h.removeCallbacks(r);
            }
        };
    }
}
