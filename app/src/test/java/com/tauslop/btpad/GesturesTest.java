package com.tauslop.btpad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GesturesTest {
    private static final long MS = 1_000_000;
    private static final float SLOP = 10;

    /** Records output; integrates strokes the way Motion does (deltas from the anchor). */
    private static final class Rec implements Gestures.Out {
        final List<Integer> buttons = new ArrayList<>();
        int pointerDowns, scrollDowns, pointerMoves, scrollMoves;
        float px, py, ptotalX, ptotalY, sx, sy, stotalX, stotalY;
        long lastDownTime;
        /** Largest single step between consecutive pointer outputs. */
        float maxStep;
        /** Button state while each pointer move happened. */
        final List<Integer> moveButtons = new ArrayList<>();

        int held() {
            return buttons.isEmpty() ? 0 : buttons.get(buttons.size() - 1);
        }

        @Override
        public void pointerDown(long time, float x, float y) {
            pointerDowns++;
            lastDownTime = time;
            px = x;
            py = y;
        }

        @Override
        public void pointerMove(long time, float x, float y) {
            pointerMoves++;
            maxStep = Math.max(maxStep, (float) Math.hypot(x - px, y - py));
            ptotalX += x - px;
            ptotalY += y - py;
            px = x;
            py = y;
            moveButtons.add(held());
        }

        @Override
        public void scrollDown(long time, float x, float y) {
            scrollDowns++;
            sx = x;
            sy = y;
        }

        @Override
        public void scrollMove(long time, float x, float y) {
            scrollMoves++;
            stotalX += x - sx;
            stotalY += y - sy;
            sx = x;
            sy = y;
        }

        @Override
        public void buttons(int b) {
            buttons.add(b);
        }
    }

    private final Rec out = new Rec();
    private final Gestures g = new Gestures(out, SLOP);

    private void assertButtons(Integer... expected) {
        assertEquals(Arrays.asList(expected), out.buttons);
    }

    /** One finger from (x, y) moving by (dx, dy) in steps every 8 ms; returns the end time. */
    private long drag(int id, long t, float x, float y, float dx, float dy, int steps) {
        for (int k = 1; k <= steps; k++) {
            g.move(t + k * 8 * MS, id, x + dx * k / steps, y + dy * k / steps);
        }
        return t + steps * 8 * MS;
    }

    private void tap(long t, float x, float y) {
        g.down(t, 0, x, y);
        g.move(t + 40 * MS, 0, x + 2, y - 1); // jitter within the slop
        g.up(t + 80 * MS, 0);
    }

    @Test
    public void tapClicksAndHoldsForTheDragWindow() {
        tap(0, 100, 100);
        assertButtons(Gestures.LEFT);
        assertEquals(80 * MS + Gestures.DRAG_NS, g.deadline());
        g.timeout(g.deadline() - 1);
        assertButtons(Gestures.LEFT);
        g.timeout(g.deadline());
        assertButtons(Gestures.LEFT, 0);
        assertEquals(0, g.deadline());
        assertEquals(0, out.pointerDowns + out.pointerMoves); // the jitter never moved the pointer
    }

    @Test
    public void longTouchIsNotATap() {
        g.down(0, 0, 100, 100);
        g.up(Gestures.TAP_NS + MS, 0);
        assertButtons();
    }

    @Test
    public void twoFingerTapRightClicks() {
        g.down(0, 0, 100, 100);
        g.down(20 * MS, 1, 200, 100);
        g.move(60 * MS, 1, 203, 102);
        g.up(100 * MS, 0);
        g.move(110 * MS, 1, 205, 104); // last finger jitters while lifting
        g.up(120 * MS, 1);
        assertButtons(Gestures.RIGHT, 0);
        assertEquals(0, out.pointerMoves + out.scrollMoves);
        assertEquals(0, g.deadline());
    }

    @Test
    public void threeFingerTapMiddleClicks() {
        g.down(0, 0, 100, 100);
        g.down(10 * MS, 1, 150, 100);
        g.down(20 * MS, 2, 200, 100);
        g.up(100 * MS, 1);
        g.up(110 * MS, 0);
        g.up(120 * MS, 2);
        assertButtons(Gestures.MIDDLE, 0);
    }

    @Test
    public void multiFingerTapNeedsAllLiftedInTime() {
        g.down(0, 0, 100, 100);
        g.down(10 * MS, 1, 200, 100);
        g.up(100 * MS, 0);
        g.up(Gestures.TAP_NS + MS, 1);
        assertButtons();
    }

    @Test
    public void multiFingerTapCancelledByMovement() {
        g.down(0, 0, 100, 100);
        g.down(10 * MS, 1, 200, 100);
        g.move(50 * MS, 0, 100 - SLOP - 1, 100); // pinch: one finger moves, centroid barely
        g.up(100 * MS, 0);
        g.up(110 * MS, 1);
        assertButtons();
        assertEquals(0, out.scrollMoves);
    }

    @Test
    public void tapThenDragHoldsLeftUntilLift() {
        tap(0, 100, 100);
        long t = 80 * MS + Gestures.DRAG_NS / 2;
        g.down(t, 0, 100, 100);
        assertEquals(0, g.deadline()); // the touch now owns the button
        t = drag(0, t, 100, 100, 60, 0, 20);
        assertTrue(out.pointerMoves > 0);
        for (int b : out.moveButtons) assertEquals(Gestures.LEFT, b);
        assertButtons(Gestures.LEFT);
        g.up(t + 8 * MS, 0);
        assertButtons(Gestures.LEFT, 0);
        assertEquals(60, out.ptotalX, 1e-3);
    }

    @Test
    public void tapThenTouchWithoutMovingReleasesOnLift() {
        tap(0, 100, 100);
        long t = 80 * MS + 50 * MS;
        g.down(t, 0, 100, 100);
        g.timeout(t + Gestures.DRAG_NS * 2); // timer would have fired: no effect mid-touch
        assertButtons(Gestures.LEFT);
        g.up(t + Gestures.TAP_NS * 2, 0);
        assertButtons(Gestures.LEFT, 0);
    }

    @Test
    public void doubleTapIsTwoClicks() {
        tap(0, 100, 100);
        tap(80 * MS + 100 * MS, 101, 100);
        assertButtons(Gestures.LEFT, 0, Gestures.LEFT);
        g.timeout(g.deadline());
        assertButtons(Gestures.LEFT, 0, Gestures.LEFT, 0);
    }

    @Test
    public void touchAfterDragWindowIsANewGesture() {
        tap(0, 100, 100);
        long t = 80 * MS + Gestures.DRAG_NS + 10 * MS;
        g.down(t, 0, 100, 100); // timer late: the down itself expires the hold
        assertButtons(Gestures.LEFT, 0);
        drag(0, t, 100, 100, 50, 0, 10);
        assertButtons(Gestures.LEFT, 0);
        assertEquals(50, out.ptotalX, 1e-3);
    }

    @Test
    public void dragPastSlopLosesNothingAndDoesNotJump() {
        g.down(0, 0, 100, 100);
        // Slow: 1 px per 8 ms, so the slop is crossed after 88 ms.
        long t = drag(0, 0, 100, 100, 40, 30, 50);
        assertEquals(1, out.pointerDowns);
        assertEquals(40, out.ptotalX, 1e-3); // measured from the down point
        assertEquals(30, out.ptotalY, 1e-3);
        // The held-back part is replayed over REPLAY_NS, not dumped at once.
        long crossed = 11 * 8 * MS; // the 11th sample is the first beyond the 10 px slop
        assertTrue(out.lastDownTime >= crossed - Gestures.REPLAY_NS - 8 * MS);
        assertTrue(out.lastDownTime > 0);
        g.up(t, 0);
        assertButtons();
    }

    @Test
    public void stillHoldThenMoveStartsWithoutSlop() {
        g.down(0, 0, 100, 100);
        g.move(Gestures.TAP_NS + 10 * MS, 0, 103, 100); // past tap time: precise move
        assertEquals(1, out.pointerDowns);
        assertEquals(3, out.ptotalX, 1e-3);
    }

    @Test
    public void twoFingerVerticalScroll() {
        g.down(0, 0, 100, 100);
        g.down(10 * MS, 1, 200, 100);
        for (int k = 1; k <= 20; k++) {
            long t = 10 * MS + k * 8 * MS;
            g.move(t, 0, 100, 100 + 3 * k);
            g.move(t, 1, 200, 100 + 3 * k);
        }
        assertEquals(1, out.scrollDowns);
        assertEquals(0, out.stotalX, 1e-3);
        assertEquals(60, out.stotalY, 1e-3);
        assertEquals(0, out.pointerMoves);
        g.up(200 * MS, 0);
        g.up(210 * MS, 1);
        assertButtons();
    }

    @Test
    public void twoFingerHorizontalScrollUsesCentroid() {
        g.down(0, 0, 100, 100);
        g.down(10 * MS, 1, 200, 300);
        // Only one finger moves: the centroid moves half as far.
        for (int k = 1; k <= 20; k++) g.move(10 * MS + k * 8 * MS, 1, 200 - 4 * k, 300);
        assertEquals(-40, out.stotalX, 1e-3);
        assertEquals(0, out.stotalY, 1e-3);
    }

    @Test
    public void fingerChangesNeverJump() {
        g.down(0, 0, 100, 100);
        long t = drag(0, 0, 100, 100, 50, 0, 10); // live pointer, at (150, 100)
        g.down(t + 8 * MS, 1, 600, 900); // far-away second finger
        g.move(t + 16 * MS, 0, 150, 120);
        g.move(t + 16 * MS, 1, 600, 920); // a bit of scroll
        g.up(t + 24 * MS, 0); // first finger lifts; the second keeps going
        float before = out.ptotalX;
        drag(1, t + 24 * MS, 600, 920, 30, 0, 10);
        assertEquals(2, out.pointerDowns); // re-anchored
        assertEquals(50 + 30, out.ptotalX, 1e-3); // only real finger travel
        assertEquals(30, out.ptotalX - before, 1e-3);
        assertEquals(0, out.ptotalY, 1e-3);
        assertTrue(out.maxStep < 20);
        assertEquals(20, out.stotalY, 1e-3);
    }

    @Test
    public void thirdFingerReanchorsScroll() {
        g.down(0, 0, 100, 100);
        g.down(0, 1, 200, 100);
        for (int k = 1; k <= 10; k++) {
            g.move(k * 8 * MS, 0, 100, 100 + 3 * k);
            g.move(k * 8 * MS, 1, 200, 100 + 3 * k);
        }
        g.down(90 * MS, 2, 900, 900);
        g.up(100 * MS, 0); // pair is now fingers 1 and 2: centroid far away
        for (int k = 1; k <= 10; k++) {
            g.move(100 * MS + k * 8 * MS, 1, 200, 130 + 3 * k);
            g.move(100 * MS + k * 8 * MS, 2, 900, 900 + 3 * k);
        }
        assertEquals(30 + 30, out.stotalY, 1e-3);
        assertEquals(0, out.stotalX, 1e-3);
    }

    @Test
    public void secondFingerEndsADrag() {
        tap(0, 100, 100);
        long t = 150 * MS;
        g.down(t, 0, 100, 100);
        t = drag(0, t, 100, 100, 50, 0, 10);
        g.down(t + 8 * MS, 1, 300, 100);
        assertButtons(Gestures.LEFT, 0);
    }

    @Test
    public void cancelReleasesHeldButton() {
        tap(0, 100, 100);
        g.down(150 * MS, 0, 100, 100);
        drag(0, 150 * MS, 100, 100, 50, 0, 10);
        g.cancel();
        assertButtons(Gestures.LEFT, 0);
        assertEquals(0, g.deadline());
        // Events for the forgotten finger are ignored.
        int moves = out.pointerMoves;
        g.move(300 * MS, 0, 300, 300);
        g.up(310 * MS, 0);
        assertEquals(moves, out.pointerMoves);
        assertButtons(Gestures.LEFT, 0);
    }

    @Test
    public void cancelDuringDragWindowReleases() {
        tap(0, 100, 100);
        g.cancel();
        assertButtons(Gestures.LEFT, 0);
        assertEquals(0, g.deadline());
    }

    @Test
    public void scrollMotionResamplesInDetents() {
        // 4 mm per detent at 10 px/mm, no gain: 40 px per detent; remainders carry over.
        Motion m = new Motion(10, 0.25f, 1);
        m.down(0, 0, 0);
        int total = 0;
        long t = 0;
        for (int k = 1; k <= 105; k++) {
            m.move(k * 16 * MS, 0, k * 2);
            while (t < k * 16 * MS) {
                m.tick(t);
                total += m.dy;
                t += Motion.TICK_NS;
            }
        }
        while (m.pending()) {
            m.tick(t);
            total += m.dy;
            t += Motion.TICK_NS;
        }
        assertEquals(5, total); // 210 px = 5.25 detents
        assertFalse(m.pending());
    }
}
