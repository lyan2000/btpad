package com.tauslop.btpad;

/**
 * When to end a session (pure logic, injectable clock). A session starts on user intent
 * (connect or pair) and always ends by disconnecting; nothing here ever reconnects.
 *
 * <p>Deadlines: the connect/pair attempt timeout, {@link #BACKGROUND_MS} after the activity
 * stops, and the idle timeout (no input). Screen-off ends the session immediately.
 * The owner calls {@link #delay()} after every event and re-arms one timer for it.
 */
final class ConnectionPolicy {
    interface Clock {
        long now();
    }

    static final long CONNECT_TIMEOUT_MS = 15_000;
    static final long PAIR_TIMEOUT_MS = 120_000;
    static final long BACKGROUND_MS = 30_000;
    static final long DEFAULT_IDLE_MS = 5 * 60_000;

    /** Why a session should end. */
    static final int KEEP = 0, TIMEOUT = 1, BACKGROUND = 2, IDLE = 3, SCREEN_OFF = 4;

    private static final long NONE = Long.MAX_VALUE;

    private final Clock clock;
    private long idleMs;
    private boolean active, connected, visible = true, screenOff;
    private long attemptDeadline = NONE, hiddenAt, lastInput;

    ConnectionPolicy(Clock clock, long idleMs) {
        this.clock = clock;
        this.idleMs = idleMs;
    }

    /** idleMs <= 0 disables the idle timeout. */
    void setIdleMs(long idleMs) {
        this.idleMs = idleMs;
    }

    void onSessionStart(long attemptTimeoutMs) {
        active = true;
        connected = false;
        screenOff = false;
        attemptDeadline = clock.now() + attemptTimeoutMs;
        lastInput = clock.now();
    }

    void onConnected() {
        connected = true;
        attemptDeadline = NONE;
        lastInput = clock.now();
    }

    void onSessionEnd() {
        active = false;
        connected = false;
        attemptDeadline = NONE;
    }

    void onInput() {
        lastInput = clock.now();
    }

    void onVisible(boolean v) {
        if (v == visible) return;
        visible = v;
        if (!v) hiddenAt = clock.now();
    }

    void onScreenOff() {
        screenOff = true;
    }

    boolean isActive() {
        return active;
    }

    /** Reason the session must end now, or {@link #KEEP}. */
    int check() {
        if (!active) return KEEP;
        long now = clock.now();
        if (screenOff) return SCREEN_OFF;
        if (now >= attemptDeadline) return TIMEOUT;
        if (!visible && now >= hiddenAt + BACKGROUND_MS) return BACKGROUND;
        if (connected && idleMs > 0 && now >= lastInput + idleMs) return IDLE;
        return KEEP;
    }

    /** Milliseconds until the next deadline (0 = check now), or -1 if none is pending. */
    long delay() {
        if (!active) return -1;
        if (check() != KEEP) return 0;
        long next = attemptDeadline;
        if (!visible) next = Math.min(next, hiddenAt + BACKGROUND_MS);
        if (connected && idleMs > 0) next = Math.min(next, lastInput + idleMs);
        return next == NONE ? -1 : next - clock.now();
    }
}
