package com.tauslop.btpad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class MotionTest {
    private static final long MS = 1_000_000;
    /** 10 px per mm with COUNTS_PER_MM = 10: one count per px at slow speed. */
    private final Motion m = new Motion(10);

    /**
     * A steady drag: 60 Hz samples with ms timestamps (as MotionEvent gives them), each
     * delivered when it happens, ticks on the 11.25 ms grid. Returns per-tick dx, including the
     * drain after lift.
     */
    private List<Integer> drag(float pxPerFrame, int frames) {
        List<Integer> out = new ArrayList<>();
        long start = 1_000 * MS;
        m.down(start, 0, 0);
        long tick = start;
        for (int k = 1; k <= frames; k++) {
            long sample = start + (long) (k * 16.6667) * MS;
            while (tick < sample) {
                m.tick(tick);
                out.add(m.dx);
                tick += Motion.TICK_NS;
            }
            m.move(sample, k * pxPerFrame, 0);
        }
        while (m.pending()) {
            m.tick(tick);
            out.add(m.dx);
            tick += Motion.TICK_NS;
        }
        return out;
    }

    private static int sum(List<Integer> v) {
        int s = 0;
        for (int d : v) s += d;
        return s;
    }

    @Test
    public void gainCurve() {
        assertEquals(1, Motion.gain(0), 1e-6);
        assertEquals(1, Motion.gain(20), 0.05); // slow: precise
        assertEquals((1 + Motion.MAX_GAIN) / 2, Motion.gain(Motion.HALF_GAIN_MM_S), 1e-4);
        assertTrue(Motion.gain(2000) > 2.9 && Motion.gain(2000) < Motion.MAX_GAIN);
        for (int s = 0; s < 1000; s += 10) assertTrue(Motion.gain(s + 10) > Motion.gain(s));
    }

    @Test
    public void steadyDragGivesEvenTicks() {
        // 8 px per 16.7 ms frame = 48 mm/s.
        List<Integer> d = drag(8, 120);
        float expected = 8 * 11.25f / 16.6667f * Motion.gain(48);
        // Steady state: skip the start-up and drain ticks.
        for (int d0 : d.subList(4, d.size() - 4)) {
            assertEquals(expected, d0, 1.2f);
        }
    }

    @Test
    public void totalDistanceScalesByGain() {
        int total = sum(drag(8, 120));
        assertEquals(8 * 120 * Motion.gain(48), total, 3);
        // Slow enough that the gain is ~1: distance is preserved.
        int slow = sum(drag(0.5f, 240));
        assertEquals(0.5 * 240, slow, 1.5);
    }

    @Test
    public void slowMovesAccumulate() {
        // 0.1 px per frame: no single tick reaches a count, the remainder does.
        assertEquals(30, sum(drag(0.1f, 300)), 1);
    }

    @Test
    public void liftDrainsToFinalPosition() {
        // Slow (gain ~1) so the drained total is the finger's travel.
        m.down(0, 0, 0);
        m.move(50 * MS, 2, 0);
        m.move(100 * MS, 4, 0);
        assertTrue(m.pending());
        int total = 0, ticks = 0;
        for (long t = 100 * MS; m.pending(); t += Motion.TICK_NS, ticks++) {
            m.tick(t);
            total += m.dx;
        }
        assertEquals(4, total);
        assertTrue(ticks <= (Motion.LAG_NS + Motion.TICK_NS - 1) / Motion.TICK_NS + 1);
        m.tick(1000 * MS); // done: no further motion
        assertEquals(0, m.dx);
        assertFalse(m.pending());
    }

    @Test
    public void noExtrapolationPastNewestSample() {
        m.down(0, 0, 0);
        m.move(100 * MS, 3, 0);
        m.tick(100 * MS + Motion.LAG_NS + 50 * MS);
        assertEquals(3, m.dx);
        assertFalse(m.pending());
    }

    @Test
    public void newStrokeKeepsUndrainedMotion() {
        m.down(0, 0, 0);
        m.move(16 * MS, 10, 0);
        m.down(20 * MS, 500, 500); // before any tick
        m.move(36 * MS, 500, 500);
        m.tick(1000 * MS);
        assertEquals(10, m.dx);
        assertEquals(0, m.dy);
    }

    @Test
    public void firstStepAfterRestKeepsTheLag() {
        long t0 = 1_000 * MS;
        m.down(t0, 0, 0);
        long resume = t0 + 350 * MS; // still, no samples, then moving 10 px per 16 ms frame
        for (int k = 0; k < 10; k++) m.move(resume + k * 16 * MS, (k + 1) * 10, 0);
        m.tick(resume); // the first sample is 20 ms (LAG) from being due
        assertEquals(0, m.dx);
        m.tick(resume + Motion.TICK_NS);
        assertTrue("dx " + m.dx, m.dx <= 6); // ramping in, not the whole first step
    }
}
