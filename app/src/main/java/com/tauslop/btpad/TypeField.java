package com.tauslop.btpad;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

/**
 * The "Type here" field: whatever the system keyboard (typing, swipe, voice, autocorrect) does
 * to its text is typed on the host. Each edit is diffed against the text the host already has
 * (see Keyboard.edit). The cursor is kept at the end so the field mirrors the host's text
 * before its cursor.
 *
 * <p>Keys the IME sends as key events (Enter, arrows, Backspace on an empty field) go straight
 * to the host, through an InputConnection wrapper: an empty EditText has nothing to delete, so
 * without it Backspace would be lost. After a key that moves the host's cursor (Enter, arrows,
 * strip keys) the field is cleared without sending deletes, and so is a long text at a word
 * break, so it never grows without bound.
 */
public final class TypeField extends EditText {
    /** Clear at the next word break once the text is this long. */
    private static final int MAX_CHARS = 40;

    private Keyboard keyboard;
    private Runnable onUnmappable;
    /** The text the host has (typed so far). */
    private String sent = "";
    private final Runnable sync = this::sync;

    public TypeField(Context c, AttributeSet attrs) {
        super(c, attrs);
        addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                // Once per batch of IME edits (autocorrect = delete + insert), not per change.
                removeCallbacks(sync);
                post(sync);
            }
        });
    }

    void setKeyboard(Keyboard k, Runnable onUnmappable) {
        keyboard = k;
        this.onUnmappable = onUnmappable;
    }

    /** Forget the text without deleting it on the host (its cursor moved, or the link changed). */
    void clear() {
        removeCallbacks(sync);
        sent = "";
        Editable t = getText();
        if (t.length() == 0) return;
        t.clear();
        // Drop the IME's composing state and its idea of the text before the cursor.
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.restartInput(this);
    }

    /** Type what changed since the last sync. */
    private void sync() {
        removeCallbacks(sync);
        if (keyboard == null) return;
        Editable t = getText();
        String now = t.toString();
        if (now.equals(sent)) return;
        int skipped = keyboard.edit(sent, now);
        sent = now;
        if (skipped > 0 && onUnmappable != null) onUnmappable.run();
        if (now.indexOf('\n') >= 0 || (now.length() >= MAX_CHARS && now.endsWith(" ")
                && BaseInputConnection.getComposingSpanStart(t) < 0)) {
            clear();
        }
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        int end = length();
        if (selStart != end || selEnd != end) setSelection(end);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo info) {
        InputConnection ic = super.onCreateInputConnection(info);
        info.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_ENTER_ACTION
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return ic == null ? null : new Bridge(ic);
    }

    /** IME key events and deletes that the text alone can't express. */
    private final class Bridge extends InputConnectionWrapper {
        Bridge(InputConnection target) {
            super(target, true);
        }

        @Override
        public boolean sendKeyEvent(KeyEvent e) {
            int code = e.getKeyCode();
            int usage = Keys.forKeyCode(code);
            boolean direct = keyboard != null && usage != 0 && !Keys.isModifier(usage)
                    && (code == KeyEvent.KEYCODE_DEL ? length() == 0 : !isText(code));
            if (!direct) return super.sendKeyEvent(e);
            sync();
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) keyboard.press(usage);
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                keyboard.release(usage);
                if (usage != Keys.BACKSPACE) clear(); // the host's cursor moved
            }
            return true;
        }

        @Override
        public boolean performEditorAction(int action) {
            if (keyboard == null) return super.performEditorAction(action);
            sync();
            keyboard.tap(Keys.ENTER);
            clear();
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int before, int after) {
            int extra = Math.max(0, before - length());
            boolean ok = super.deleteSurroundingText(before - extra, after);
            backspaces(extra);
            return ok;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int before, int after) {
            int cps = Character.codePointCount(getText(), 0, length());
            int extra = Math.max(0, before - cps);
            boolean ok = super.deleteSurroundingTextInCodePoints(before - extra, after);
            backspaces(extra);
            return ok;
        }

        /** Deletes past the start of the field: the host has text there we don't know about. */
        private void backspaces(int n) {
            if (keyboard == null || n == 0) return;
            sync();
            for (int i = 0; i < n; i++) keyboard.tap(Keys.BACKSPACE);
        }
    }

    /** Keys that produce text: typed through the field's text, not as key events. */
    private static boolean isText(int code) {
        return (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9)
                || (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z)
                || (code >= KeyEvent.KEYCODE_COMMA && code <= KeyEvent.KEYCODE_PERIOD)
                || code == KeyEvent.KEYCODE_SPACE
                || (code >= KeyEvent.KEYCODE_GRAVE && code <= KeyEvent.KEYCODE_SLASH)
                || code == KeyEvent.KEYCODE_DEL
                || (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_EQUALS
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER);
    }
}
