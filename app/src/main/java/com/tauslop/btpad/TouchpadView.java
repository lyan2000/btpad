package com.tauslop.btpad;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * The touchpad surface (mouse mode). Every finger's samples, historical ones included, go to
 * Gestures, which drives the output (see Sender). Clicks give a haptic tick. Native multitouch
 * frames do not fit the descriptor size limit (see Hid).
 */
public final class TouchpadView extends View {
    private static final long NS_PER_MS = 1_000_000;

    private Gestures gestures;
    private final Runnable timeout = () -> {
        gestures.timeout(SystemClock.uptimeMillis() * NS_PER_MS);
        scheduleTimeout();
    };
    /** False after cancel() until the next touch sequence starts. */
    private boolean live;
    private int buttons;

    public TouchpadView(Context c, AttributeSet attrs) {
        super(c, attrs);
    }

    void setOutput(Gestures.Out out) {
        gestures = new Gestures(new Gestures.Out() {
            @Override
            public void pointerDown(long time, float x, float y) {
                out.pointerDown(time, x, y);
            }

            @Override
            public void pointerMove(long time, float x, float y) {
                out.pointerMove(time, x, y);
            }

            @Override
            public void scrollDown(long time, float x, float y) {
                out.scrollDown(time, x, y);
            }

            @Override
            public void scrollMove(long time, float x, float y) {
                out.scrollMove(time, x, y);
            }

            @Override
            public void buttons(int b) {
                if ((b & ~buttons) != 0) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                }
                buttons = b;
                out.buttons(b);
            }

            @Override
            public void touch(boolean down) {
                out.touch(down);
            }
        }, ViewConfiguration.get(getContext()).getScaledTouchSlop());
    }

    /** Stop the current gesture and release its buttons; ignores fingers still down. */
    void cancel() {
        live = false;
        if (gestures == null) return;
        gestures.cancel();
        scheduleTimeout();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            requestUnbufferedDispatch(e); // every sample as it comes, not batched per frame
            live = true;
        }
        if (!live || gestures == null) return true;
        // Event times are ms at API 30 (the ns getters are API 34).
        long time = e.getEventTime() * NS_PER_MS;
        int i = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                gestures.down(time, e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_MOVE:
                for (int h = 0; h < e.getHistorySize(); h++) {
                    long ht = e.getHistoricalEventTime(h) * NS_PER_MS;
                    for (int p = 0; p < e.getPointerCount(); p++) {
                        gestures.move(ht, e.getPointerId(p),
                                e.getHistoricalX(p, h), e.getHistoricalY(p, h));
                    }
                }
                for (int p = 0; p < e.getPointerCount(); p++) {
                    gestures.move(time, e.getPointerId(p), e.getX(p), e.getY(p));
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                gestures.move(time, e.getPointerId(i), e.getX(i), e.getY(i));
                gestures.up(time, e.getPointerId(i));
                if (action == MotionEvent.ACTION_UP) performClick(); // accessibility
                break;
            case MotionEvent.ACTION_CANCEL:
                cancel();
                return true;
        }
        scheduleTimeout();
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void scheduleTimeout() {
        removeCallbacks(timeout);
        long at = gestures.deadline();
        if (at != 0) {
            postDelayed(timeout, Math.max(0, (at + NS_PER_MS - 1) / NS_PER_MS
                    - SystemClock.uptimeMillis()));
        }
    }
}
