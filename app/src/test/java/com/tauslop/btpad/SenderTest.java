package com.tauslop.btpad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Gestures driving Sender on a virtual main-thread clock, checking what the link gets: never
 * more than one mouse report per tick (no phone-side bursts after a pause or at stroke start),
 * a wake-up report at touch-down, keepalives while a finger rests and none after lift.
 */
public class SenderTest {
    private static final long MS = 1_000_000;
    /** Reports closer than this would share a link anchor (BUTTON_GAP_NS allows 1 ms jitter). */
    private static final long MIN_GAP = Motion.TICK_NS - MS;

    /** Handler stand-in: runs posted callbacks in time order, rounded up to ms like postAtTime. */
    private static final class FakeTimer implements Sender.Timer {
        long now = 1_000 * MS;
        final List<Runnable> rs = new ArrayList<>();
        final List<Long> ats = new ArrayList<>();

        @Override
        public long now() {
            return now;
        }

        @Override
        public void at(Runnable r, long ns) {
            cancel(r);
            rs.add(r);
            ats.add((ns + MS - 1) / MS * MS);
        }

        @Override
        public void cancel(Runnable r) {
            for (int i = rs.size() - 1; i >= 0; i--) {
                if (rs.get(i) == r) {
                    rs.remove(i);
                    ats.remove(i);
                }
            }
        }

        /** Run everything due up to {@code t}, then set the clock to {@code t}. */
        void advance(long t) {
            while (true) {
                int k = -1;
                for (int i = 0; i < rs.size(); i++) {
                    if (ats.get(i) <= t && (k < 0 || ats.get(i) < ats.get(k))) k = i;
                }
                if (k < 0) break;
                Runnable r = rs.remove(k);
                now = Math.max(now, ats.remove(k));
                r.run();
            }
            now = Math.max(now, t);
        }
    }

    private static final class Report {
        final long time;
        final int buttons, dx, dy, wheel, pan;

        Report(long time, byte[] r) {
            this.time = time;
            buttons = r[0];
            dx = (short) ((r[1] & 0xFF) | r[2] << 8);
            dy = (short) ((r[3] & 0xFF) | r[4] << 8);
            wheel = r[5];
            pan = r[6];
        }

        boolean zero() {
            return dx == 0 && dy == 0;
        }
    }

    private final FakeTimer timer = new FakeTimer();
    private final List<Report> sent = new ArrayList<>();
    /** Keyboard/consumer reports: report ID, then the bytes. */
    private final List<byte[]> keys = new ArrayList<>();
    private final List<Long> keyTimes = new ArrayList<>();
    private final Sender sender = new Sender((id, r) -> {
        if (id == Hid.ID_MOUSE) {
            sent.add(new Report(timer.now, r));
        } else {
            byte[] e = new byte[r.length + 1];
            e[0] = (byte) id;
            System.arraycopy(r, 0, e, 1, r.length);
            keys.add(e);
            keyTimes.add(timer.now);
        }
    }, 10, timer);
    private final Gestures g = new Gestures(sender, 10);

    /** Input event at {@code t} (ms timestamps, as MotionEvent gives them). */
    private long at(long t) {
        timer.advance(t);
        return t / MS * MS;
    }

    /** One finger moving by (vx, vy) px per 8 ms sample from {@code t} for {@code n} samples. */
    private long drag(long t, float[] pos, float vx, float vy, int n) {
        for (int k = 0; k < n; k++) {
            t += 8 * MS;
            pos[0] += vx;
            pos[1] += vy;
            g.move(at(t), 0, pos[0], pos[1]);
        }
        return t;
    }

    private void assertSpacing() {
        for (int i = 1; i < sent.size(); i++) {
            long gap = sent.get(i).time - sent.get(i - 1).time;
            assertTrue("reports " + (i - 1) + "," + i + " only " + gap / 1e6 + " ms apart",
                    gap >= MIN_GAP);
        }
    }

    private int sumDy(long from) {
        int s = 0;
        for (Report r : sent) if (r.time >= from) s += r.dy;
        return s;
    }

    @Test
    public void wakeAtTouchDownThenOneReportPerTick() {
        long t = 1_000 * MS;
        float[] p = {100, 100};
        g.down(at(t), 0, p[0], p[1]);
        assertEquals(1, sent.size()); // the wake-up report, before any motion
        assertTrue(sent.get(0).zero());
        assertEquals(0, sent.get(0).buttons);

        t = drag(t, p, 0, 6, 40); // past the slop at once: replay + live motion
        timer.advance(t + 200 * MS);
        assertSpacing();
        assertTrue(sumDy(0) >= 240);
    }

    @Test
    public void restThenMoveHasNoBurst() {
        long t = 1_000 * MS;
        float[] p = {100, 100};
        g.down(at(t), 0, p[0], p[1]);
        t = drag(t, p, 0, 5, 30);
        // Finger rests 350 ms; the touchscreen sends no samples while it is still.
        timer.advance(t + 350 * MS);
        long resume = t + 350 * MS;
        int before = sent.size();
        t = drag(resume, p, 0, 8, 40);
        timer.advance(t + 200 * MS);
        assertSpacing();

        // Keepalives covered the rest: no gap near SSR's 100 ms while the finger was down.
        for (int i = 1; i < before; i++) {
            assertTrue(sent.get(i).time - sent.get(i - 1).time <= Sender.KEEPALIVE_NS + MS);
        }
        // Resumed motion ramps in on the tick grid: no tick carries more than ~2 ticks' worth
        // of the steady speed (8 px / 8 ms -> ~11 px/tick, up to 3x gain).
        for (Report r : sent) if (r.time >= resume) assertTrue("dy " + r.dy, r.dy <= 70);
        // ... and without a stall: once motion resumes, a report every tick.
        List<Report> moving = new ArrayList<>();
        for (Report r : sent) if (r.time >= resume && !r.zero()) moving.add(r);
        for (int i = 1; i < 10; i++) {
            assertTrue(moving.get(i).time - moving.get(i - 1).time <= Motion.TICK_NS + MS);
        }
    }

    @Test
    public void restWithStationarySamplesHasNoBurst() {
        long t = 1_000 * MS;
        float[] p = {100, 100};
        g.down(at(t), 0, p[0], p[1]);
        t = drag(t, p, 5, 0, 30);
        t = drag(t, p, 0, 0, 45); // resting finger that still reports (same position)
        t = drag(t, p, 7, 0, 40);
        timer.advance(t + 200 * MS);
        assertSpacing();
    }

    @Test
    public void noKeepaliveAfterLift() {
        long t = 1_000 * MS;
        float[] p = {100, 100};
        g.down(at(t), 0, p[0], p[1]);
        t = drag(t, p, 0, 5, 30);
        g.up(at(t + 8 * MS), 0);
        timer.advance(t + 300 * MS); // drain the stroke
        int n = sent.size();
        timer.advance(t + 3_000 * MS);
        assertEquals(n, sent.size());
        assertTrue(timer.rs.isEmpty()); // nothing scheduled: an idle pad costs nothing
    }

    @Test
    public void keepalivesWhileRestingKeepButtonsAndStopOnLift() {
        long t = 1_000 * MS;
        g.down(at(t), 0, 100, 100);
        g.up(at(t + 50 * MS), 0); // tap: left pressed, held for a drag
        t += 120 * MS;
        g.down(at(t), 0, 100, 100); // touch again: button stays held
        timer.advance(t + 500 * MS); // rest without moving
        int held = 0;
        for (Report r : sent) {
            assertTrue(r.zero());
            if (r.time > t) {
                assertEquals(Gestures.LEFT, r.buttons);
                held++;
            }
        }
        assertTrue("keepalives " + held, held >= 5);
        assertSpacing();
        g.up(at(t + 500 * MS), 0);
        timer.advance(t + 2_000 * MS);
        Report last = sent.get(sent.size() - 1);
        assertEquals(0, last.buttons); // lift releases; no keepalive after
        assertTrue(last.time <= t + 512 * MS);
    }

    @Test
    public void quickTapStillGivesSpacedPressAndRelease() {
        long t = 1_000 * MS;
        g.down(at(t), 0, 100, 100);
        g.up(at(t + 40 * MS), 0);
        g.down(at(t + 100 * MS), 0, 100, 100); // double tap
        g.up(at(t + 140 * MS), 0);
        g.timeout(at(t + 400 * MS)); // drag window over: release
        timer.advance(t + 1_000 * MS);
        assertSpacing();
        List<Integer> changes = new ArrayList<>();
        int b = 0;
        for (Report r : sent) {
            if (r.buttons != b) changes.add(r.buttons);
            b = r.buttons;
        }
        assertEquals(List.of(1, 0, 1, 0), changes);
    }

    /** Total {wheel, pan} for a scroll stroke moving (vx, vy) px per 8 ms sample. */
    private int[] scrollStroke(boolean natural, float vx, float vy) {
        sender.naturalScroll = natural;
        sent.clear();
        long t = timer.now / MS * MS + 1_000 * MS; // each stroke after the last one
        float x = 200, y = 200;
        sender.touch(true);
        sender.scrollDown(at(t), x, y);
        for (int k = 0; k < 50; k++) {
            t += 8 * MS;
            x += vx;
            y += vy;
            sender.scrollMove(at(t), x, y);
        }
        sender.touch(false);
        timer.advance(t + 300 * MS);
        int[] sum = new int[2];
        for (Report r : sent) {
            sum[0] += r.wheel;
            sum[1] += r.pan;
        }
        return sum;
    }

    @Test
    public void defaultIsWheelStyle() {
        assertTrue(!new Sender((id, r) -> { }, 10, timer).naturalScroll);
    }

    @Test
    public void scrollDirection() {
        // HID: wheel + scrolls the view up, AC Pan + scrolls it right. Screen y grows down.
        // Natural (touchscreen): content follows the fingers, so fingers up scroll the view down.
        int[] up = scrollStroke(true, 0, -8);
        assertTrue("wheel " + up[0], up[0] < 0);
        assertEquals(0, up[1]);
        int[] left = scrollStroke(true, -8, 0);
        assertTrue("pan " + left[1], left[1] > 0);
        assertEquals(0, left[0]);
        // Wheel style: the reverse on both axes.
        up = scrollStroke(false, 0, -8);
        assertTrue("wheel " + up[0], up[0] > 0);
        assertEquals(0, up[1]);
        left = scrollStroke(false, -8, 0);
        assertTrue("pan " + left[1], left[1] < 0);
        assertEquals(0, left[0]);
    }

    // ---- Key reports ----

    private void assertKeySpacing() {
        for (int i = 1; i < keyTimes.size(); i++) {
            long gap = keyTimes.get(i) - keyTimes.get(i - 1);
            assertTrue("key reports " + (i - 1) + "," + i + " only " + gap / 1e6 + " ms apart",
                    gap >= MIN_GAP);
        }
    }

    @Test
    public void keyReportsOnePerTickInOrder() {
        timer.advance(1_000 * MS);
        Keyboard kb = new Keyboard(sender::send);
        String text = "hello, world! dictated in one go";
        kb.edit("", text);
        assertEquals(1, keys.size()); // the first goes out at once
        timer.advance(timer.now + 2_000 * MS);
        assertKeySpacing();
        assertEquals(2 * text.length(), keys.size()); // press + release each, none merged
        for (int i = 0; i < text.length(); i++) {
            int k = Keys.forChar(text.charAt(i));
            byte[] press = keys.get(2 * i), release = keys.get(2 * i + 1);
            assertEquals(Hid.ID_KEYBOARD, press[0]);
            assertEquals(k & 0xFF, press[3] & 0xFF);
            assertEquals((k & Keys.SHIFT) != 0 ? Keys.LSHIFT : 0, press[1]);
            for (int j = 1; j < release.length; j++) assertEquals(0, release[j]);
        }
    }

    @Test
    public void keysAndConsumerShareOneOrderedQueue() {
        timer.advance(1_000 * MS);
        sender.send(Hid.ID_KEYBOARD, Hid.keyboard(0, Keys.A));
        sender.send(Hid.ID_CONSUMER, Hid.consumer(Keys.VOLUME_UP));
        sender.send(Hid.ID_CONSUMER, Hid.consumer(0));
        sender.send(Hid.ID_KEYBOARD, Hid.keyboard(0));
        timer.advance(timer.now + 100 * MS);
        assertKeySpacing();
        assertEquals(4, keys.size());
        assertEquals(Hid.ID_KEYBOARD, keys.get(0)[0]);
        assertEquals(Hid.ID_CONSUMER, keys.get(1)[0]);
        assertEquals((byte) Keys.VOLUME_UP, keys.get(1)[1]);
        assertEquals(Hid.ID_CONSUMER, keys.get(2)[0]);
        assertEquals(0, keys.get(2)[1]);
        assertEquals(Hid.ID_KEYBOARD, keys.get(3)[0]);
    }

    @Test
    public void spacedKeysGoOutImmediately() {
        timer.advance(1_000 * MS);
        sender.send(Hid.ID_KEYBOARD, Hid.keyboard(0, Keys.A));
        timer.advance(1_100 * MS);
        sender.send(Hid.ID_KEYBOARD, Hid.keyboard(0));
        assertEquals(2, keys.size());
        assertEquals(1_100 * MS, (long) keyTimes.get(1));
    }

    @Test
    public void repeatedKeyboardReportIsSkipped() {
        timer.advance(1_000 * MS);
        sender.send(Hid.ID_KEYBOARD, Hid.keyboard(0)); // all up already
        sender.send(Hid.ID_KEYBOARD, Hid.keyboard(0, Keys.A));
        sender.send(Hid.ID_KEYBOARD, Hid.keyboard(0, Keys.A));
        timer.advance(timer.now + 100 * MS);
        assertEquals(1, keys.size());
    }

    @Test
    public void resetDropsQueueAndReleasesHeldKeys() {
        timer.advance(1_000 * MS);
        sender.send(Hid.ID_KEYBOARD, Hid.keyboard(Keys.LCTRL, Keys.LEFT));
        sender.send(Hid.ID_CONSUMER, Hid.consumer(Keys.MUTE));
        sender.send(Hid.ID_KEYBOARD, Hid.keyboard(0, Keys.A));
        assertEquals(1, keys.size());
        sender.reset();
        timer.advance(timer.now + 100 * MS);
        assertEquals(2, keys.size()); // queue dropped, then one all-up keyboard report
        assertEquals(Hid.ID_KEYBOARD, keys.get(1)[0]);
        for (int j = 1; j < keys.get(1).length; j++) assertEquals(0, keys.get(1)[j]);
        sender.reset(); // nothing held: nothing sent
        assertEquals(2, keys.size());
        // The all-up state is known: a new press goes out.
        sender.send(Hid.ID_KEYBOARD, Hid.keyboard(0, Keys.A));
        assertEquals(3, keys.size());
    }
}
