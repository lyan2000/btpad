package com.tauslop.btpad;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayDeque;

/**
 * Rate-limited report sender on the main thread. Pointer deltas and wheel ticks are
 * accumulated and sent as one mouse report per tick; touchpad frames are latest-wins;
 * ordered reports (keys, button changes) go out in order before them. Work is posted only
 * when something is pending, so an idle pad costs nothing.
 */
final class Sender {
    interface Sink {
        void send(int id, byte[] report);
    }

    /** ~125 Hz. */
    private static final long INTERVAL_MS = 8;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable flush = this::flush;
    private final Sink sink;
    private final ArrayDeque<byte[]> ordered = new ArrayDeque<>(); // [id, report...]

    private int buttons, dx, dy, wheel, pan;
    private byte[] frame;
    private boolean posted;
    private long lastFlush;

    Sender(Sink sink) {
        this.sink = sink;
    }

    void move(int x, int y) {
        dx += x;
        dy += y;
        schedule();
    }

    void scroll(int vertical, int horizontal) {
        wheel += vertical;
        pan += horizontal;
        schedule();
    }

    /** Button state change: flushed in order with the motion that preceded it. */
    void buttons(int b) {
        if (b == buttons) return;
        buttons = b;
        queue(Hid.ID_MOUSE, takeMouse());
    }

    /** A report that must not be merged or dropped (keyboard, consumer). */
    void queue(int id, byte[] report) {
        byte[] e = new byte[report.length + 1];
        e[0] = (byte) id;
        System.arraycopy(report, 0, e, 1, report.length);
        ordered.add(e);
        schedule();
    }

    /** Touchpad frame; only the newest pending one is sent. */
    void frame(byte[] report) {
        frame = report;
        schedule();
    }

    /** Drop pending work and release held buttons (activity paused). */
    void reset() {
        handler.removeCallbacks(flush);
        posted = false;
        ordered.clear();
        frame = null;
        dx = dy = wheel = pan = 0;
        if (buttons != 0) {
            buttons = 0;
            sink.send(Hid.ID_MOUSE, Hid.mouse(0, 0, 0, 0, 0));
        }
    }

    private void schedule() {
        if (posted) return;
        posted = true;
        long wait = lastFlush + INTERVAL_MS - SystemClock.uptimeMillis();
        handler.postDelayed(flush, Math.max(0, wait));
    }

    private void flush() {
        posted = false;
        lastFlush = SystemClock.uptimeMillis();
        while (!ordered.isEmpty()) {
            byte[] e = ordered.poll();
            byte[] r = new byte[e.length - 1];
            System.arraycopy(e, 1, r, 0, r.length);
            sink.send(e[0], r);
        }
        if (dx != 0 || dy != 0 || wheel != 0 || pan != 0) {
            sink.send(Hid.ID_MOUSE, takeMouse());
        }
        if (frame != null) {
            sink.send(Hid.ID_TOUCHPAD, frame);
            frame = null;
        }
        if (dx != 0 || dy != 0 || wheel != 0 || pan != 0) schedule(); // clamped remainder
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
}
