package com.tauslop.btpad;

/**
 * Turns touch samples into pointer deltas on the Bluetooth link's report grid.
 *
 * <p>The link delivers about one report per 11.25 ms anchor. Sending at the touch rate
 * (60 Hz) beats against that grid, so equal deltas arrive at alternating 11/22 ms gaps and the
 * cursor speed oscillates. Instead, each tick reports how far the finger moved over the last
 * tick interval: the position interpolated at {@code tick - LAG} minus the previous one. LAG of
 * about one touch frame keeps the target between real samples (no extrapolation).
 *
 * <p>Pointer acceleration scales each tick's motion by a gain that rises smoothly with finger
 * speed (in mm/s, so it is device independent). Sub-count remainders carry over, so slow moves
 * still add up. Pure Java; times are nanoseconds on one monotonic clock.
 */
final class Motion {
    /** Link anchor interval: the BT grid measured on the host. */
    static final long TICK_NS = 11_250_000;
    /** How far behind real time the resampled position runs: about one 60 Hz touch frame. */
    static final long LAG_NS = 20_000_000;
    /** Base sensitivity: mouse counts per mm of finger travel at slow speed (~1 per px here). */
    static final float COUNTS_PER_MM = 10;
    /** Gain approaches MAX_GAIN for fast flicks; it is halfway there at HALF_GAIN_MM_S. */
    static final float MAX_GAIN = 3, HALF_GAIN_MM_S = 150;
    /** Longest touch frame (60 Hz); a longer gap between samples means the finger was still. */
    static final long FRAME_NS = 17_000_000;

    private static final int CAPACITY = 32;

    private final float pxPerMm, countsPerMm, maxGain;
    private final long[] t = new long[CAPACITY];
    private final float[] x = new float[CAPACITY], y = new float[CAPACITY];
    private int n;
    /** Time and position already reported. */
    private long cursor;
    private float curX, curY;
    private float remX, remY;

    /** Deltas produced by the last {@link #tick}. */
    int dx, dy;

    /** Pointer motion: COUNTS_PER_MM with acceleration up to MAX_GAIN. */
    Motion(float pxPerMm) {
        this(pxPerMm, COUNTS_PER_MM, MAX_GAIN);
    }

    /** Any other sensitivity and maximum gain (e.g. scrolling in wheel detents). */
    Motion(float pxPerMm, float countsPerMm, float maxGain) {
        this.pxPerMm = pxPerMm;
        this.countsPerMm = countsPerMm;
        this.maxGain = maxGain;
    }

    /** Gain for a finger speed: 1 when slow, rising smoothly toward MAX_GAIN. */
    static float gain(float mmPerS) {
        return gain(mmPerS, MAX_GAIN);
    }

    static float gain(float mmPerS, float max) {
        float s = mmPerS / HALF_GAIN_MM_S, s2 = s * s;
        return 1 + (max - 1) * s2 / (1 + s2);
    }

    /** Finger down: start a new stroke. Motion still draining from the last one is kept. */
    void down(long time, float px, float py) {
        if (pending()) {
            float k = countsPerMm / pxPerMm;
            remX += (x[n - 1] - curX) * k;
            remY += (y[n - 1] - curY) * k;
        }
        n = 0;
        add(time, px, py);
        cursor = time;
        curX = px;
        curY = py;
    }

    void move(long time, float px, float py) {
        if (n == 0) return;
        if (time <= t[n - 1]) { // same timestamp (ms resolution): keep the newest position
            if (time == t[n - 1]) {
                x[n - 1] = px;
                y[n - 1] = py;
            }
            return;
        }
        if (time - t[n - 1] > 2 * FRAME_NS) {
            // A resting finger (no samples while still) starts moving: it was still until about
            // a frame ago. Interpolating across the whole pause would report its first step at
            // once, ahead of the lag, and then stall.
            if (n == CAPACITY) drop(1);
            add(time - FRAME_NS, x[n - 1], y[n - 1]);
        }
        if (n == CAPACITY) drop(1);
        add(time, px, py);
    }

    /** True while there is motion not yet reported; ticks are needed only then. */
    boolean pending() {
        return n > 0 && cursor < t[n - 1];
    }

    void reset() {
        n = 0;
        remX = remY = 0;
        dx = dy = 0;
    }

    /** Advance to {@code now}: sets {@link #dx}/{@link #dy} for one report. */
    void tick(long now) {
        dx = dy = 0;
        if (n == 0) return;
        long target = Math.min(now - LAG_NS, t[n - 1]);
        if (target <= cursor) return;

        int i = 0; // last sample at or before target
        while (i + 1 < n && t[i + 1] <= target) i++;
        float px = x[i], py = y[i];
        if (i + 1 < n && t[i] < target) {
            float f = (float) (target - t[i]) / (t[i + 1] - t[i]);
            px += (x[i + 1] - px) * f;
            py += (y[i + 1] - py) * f;
        }

        float mx = px - curX, my = py - curY;
        float mmPerS = (float) Math.hypot(mx, my) / pxPerMm / ((target - cursor) * 1e-9f);
        float k = countsPerMm / pxPerMm * gain(mmPerS, maxGain);
        remX += mx * k;
        remY += my * k;
        dx = (int) remX;
        dy = (int) remY;
        remX -= dx;
        remY -= dy;

        cursor = target;
        curX = px;
        curY = py;
        drop(i); // samples before the bracket are no longer needed
    }

    private void add(long time, float px, float py) {
        t[n] = time;
        x[n] = px;
        y[n] = py;
        n++;
    }

    private void drop(int k) {
        if (k <= 0) return;
        n -= k;
        System.arraycopy(t, k, t, 0, n);
        System.arraycopy(x, k, x, 0, n);
        System.arraycopy(y, k, y, 0, n);
    }
}
