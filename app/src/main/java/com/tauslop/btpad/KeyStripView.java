package com.tauslop.btpad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * One row of special keys under the touchpad, drawn on a Canvas. The four sticky modifiers sit
 * at the left on every page and the page key at the right cycles through the pages in between
 * (keys, navigation, F1–F6, F7–F12, media). Multitouch: a modifier can be held while another key
 * is pressed. Keys press on touch down and release on touch up; the host repeats held keys.
 */
public final class KeyStripView extends View {
    interface Listener {
        /** A non-modifier key went down (the host's cursor may move). */
        void onKey();
    }

    private static final int MOD = 0x20000, PAGE = -1;
    private static final int[] MODS = {MOD | Keys.LCTRL, MOD | Keys.LALT, MOD | Keys.LGUI,
            MOD | Keys.LSHIFT};
    private static final String[] MOD_LABELS = {"Ctrl", "Alt", "❖", "⇧"};
    private static final int C = Keyboard.CONSUMER;
    private static final int[][] PAGES = {
            {Keys.ESC, Keys.TAB, Keys.LEFT, Keys.UP, Keys.DOWN, Keys.RIGHT},
            {Keys.HOME, Keys.END, Keys.PAGE_UP, Keys.PAGE_DOWN, Keys.INSERT, Keys.DELETE,
                    Keys.PRINT_SCREEN},
            {Keys.F1, Keys.F1 + 1, Keys.F1 + 2, Keys.F1 + 3, Keys.F1 + 4, Keys.F1 + 5},
            {Keys.F1 + 6, Keys.F1 + 7, Keys.F1 + 8, Keys.F1 + 9, Keys.F1 + 10, Keys.F12},
            {C | Keys.VOLUME_DOWN, C | Keys.VOLUME_UP, C | Keys.MUTE, C | Keys.PREV_TRACK,
                    C | Keys.PLAY_PAUSE, C | Keys.NEXT_TRACK},
    };
    private static final String[][] LABELS = {
            {"Esc", "Tab", "←", "↑", "↓", "→"},
            {"Home", "End", "PgUp", "PgDn", "Ins", "Del", "PrtSc"},
            {"F1", "F2", "F3", "F4", "F5", "F6"},
            {"F7", "F8", "F9", "F10", "F11", "F12"},
            {"Vol−", "Vol+", "Mute", "Prev", "Play", "Next"},
    };
    /** The page key is this fraction of a key wide. */
    private static final float PAGE_KEY_WIDTH = 0.7f;
    private static final int MAX_POINTERS = 10;

    private final float dp = getResources().getDisplayMetrics().density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int surface, accent, onAccent, textColor;

    private Keyboard keyboard;
    private Listener listener;
    private int page;
    /** Codes and left edges (plus the right edge) of the keys on the current page. */
    private int[] codes = new int[0];
    private String[] labels = new String[0];
    private float[] edges = new float[1];
    /** Key code under each pointer ID, or 0. */
    private final int[] down = new int[MAX_POINTERS];

    public KeyStripView(Context c, AttributeSet attrs) {
        super(c, attrs);
        surface = c.getColor(R.color.surface);
        accent = c.getColor(R.color.accent);
        onAccent = c.getColor(R.color.on_accent);
        textColor = c.getColor(R.color.text);
        text.setTextAlign(Paint.Align.CENTER);
        setPage(0);
    }

    void setKeyboard(Keyboard k, Listener l) {
        keyboard = k;
        listener = l;
    }

    /** Release every key held on the strip (Keyboard.reset forgets them). */
    void cancel() {
        for (int i = 0; i < MAX_POINTERS; i++) down[i] = 0;
        invalidate();
    }

    private void setPage(int p) {
        page = p;
        int n = MODS.length + PAGES[p].length + 1;
        codes = new int[n];
        labels = new String[n];
        for (int i = 0; i < MODS.length; i++) {
            codes[i] = MODS[i];
            labels[i] = MOD_LABELS[i];
        }
        System.arraycopy(PAGES[p], 0, codes, MODS.length, PAGES[p].length);
        System.arraycopy(LABELS[p], 0, labels, MODS.length, PAGES[p].length);
        codes[n - 1] = PAGE;
        labels[n - 1] = "";
        layoutKeys();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutKeys();
    }

    private void layoutKeys() {
        int n = codes.length;
        edges = new float[n + 1];
        float unit = getWidth() / (n - 1 + PAGE_KEY_WIDTH);
        for (int i = 0; i <= n; i++) edges[i] = Math.min(i, n - 1) * unit;
        edges[n] = getWidth();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float gap = 2 * dp, radius = 10 * dp, h = getHeight();
        for (int i = 0; i < codes.length; i++) {
            int code = codes[i];
            r.set(edges[i] + gap, 0, edges[i + 1] - gap, h);
            boolean pressed = isDown(code);
            int state = Modifiers.OFF;
            if ((code & MOD) != 0 && keyboard != null) {
                int bit = code & 0xFF;
                state = keyboard.mods.held(bit) ? Modifiers.LOCKED : keyboard.mods.state(bit);
            }
            boolean filled = pressed || state == Modifiers.LOCKED;
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(filled ? accent : surface);
            canvas.drawRoundRect(r, radius, radius, fill);
            if (state == Modifiers.ONE_SHOT) {
                fill.setStyle(Paint.Style.STROKE);
                fill.setStrokeWidth(2 * dp);
                fill.setColor(accent);
                r.inset(dp, dp);
                canvas.drawRoundRect(r, radius, radius, fill);
                r.inset(-dp, -dp);
            }
            int fg = filled ? onAccent : state == Modifiers.ONE_SHOT ? accent : textColor;
            if (code == PAGE) {
                drawPageDots(canvas, fg);
            } else {
                drawLabel(canvas, labels[i], fg);
            }
            if (code == (MOD | Keys.LSHIFT) && keyboard != null && keyboard.caps) {
                // Host Caps Lock LED.
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(filled ? onAccent : accent);
                canvas.drawCircle(r.right - 7 * dp, r.top + 7 * dp, 3 * dp, fill);
            }
        }
    }

    private void drawLabel(Canvas canvas, String label, int color) {
        float size = 14 * getResources().getConfiguration().fontScale * dp;
        text.setTextSize(size);
        float w = text.measureText(label), max = r.width() - 4 * dp;
        if (w > max) text.setTextSize(size * max / w);
        text.setColor(color);
        canvas.drawText(label, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2,
                text);
    }

    /** One dot per page, the current one solid. */
    private void drawPageDots(Canvas canvas, int color) {
        int n = PAGES.length;
        float d = Math.min(5 * dp, r.height() / (n * 2.2f)), cx = r.centerX();
        float y0 = r.centerY() - (n - 1) * d;
        fill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            fill.setColor(color);
            fill.setAlpha(i == page ? 255 : 80);
            canvas.drawCircle(cx, y0 + i * 2 * d, d * 0.6f, fill);
        }
        fill.setAlpha(255);
    }

    private boolean isDown(int code) {
        for (int c : down) if (c == code && code != 0) return true;
        return false;
    }

    private int keyAt(float x) {
        for (int i = 0; i < codes.length; i++) if (x < edges[i + 1]) return codes[i];
        return codes[codes.length - 1];
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int i = e.getActionIndex(), id = e.getPointerId(i);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (id < MAX_POINTERS) keyDown(id, keyAt(e.getX(i)));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (id < MAX_POINTERS) keyUp(id);
                if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick(); // accessibility
                break;
            case MotionEvent.ACTION_CANCEL:
                for (int p = 0; p < MAX_POINTERS; p++) keyUp(p);
                break;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void keyDown(int id, int code) {
        if (keyboard == null || isDown(code)) return;
        down[id] = code;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (code == PAGE) {
            setPage((page + 1) % PAGES.length);
        } else if ((code & MOD) != 0) {
            keyboard.modDown(code & 0xFF);
        } else {
            if ((code & Keyboard.CONSUMER) == 0 && listener != null) listener.onKey();
            keyboard.press(code);
        }
        invalidate();
    }

    private void keyUp(int id) {
        int code = down[id];
        if (code == 0) return;
        down[id] = 0;
        if ((code & MOD) != 0) {
            keyboard.modUp(code & 0xFF, SystemClock.uptimeMillis());
        } else if (code != PAGE) {
            keyboard.release(code);
        }
        invalidate();
    }
}
