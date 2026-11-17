package com.tauslop.btpad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class ConnectionPolicyTest {
    private long now = 1_000_000;
    private final ConnectionPolicy p = new ConnectionPolicy(() -> now, 300_000);

    private void connect() {
        p.onSessionStart(ConnectionPolicy.CONNECT_TIMEOUT_MS);
        now += 1000;
        p.onConnected();
    }

    @Test
    public void idleWithoutSession() {
        assertEquals(ConnectionPolicy.KEEP, p.check());
        assertEquals(-1, p.delay());
        p.onVisible(false);
        now += 3_600_000;
        assertEquals(ConnectionPolicy.KEEP, p.check());
        assertFalse(p.isActive());
    }

    @Test
    public void connectTimeout() {
        p.onSessionStart(ConnectionPolicy.CONNECT_TIMEOUT_MS);
        assertEquals(15_000, p.delay());
        now += 14_999;
        assertEquals(ConnectionPolicy.KEEP, p.check());
        now += 1;
        assertEquals(ConnectionPolicy.TIMEOUT, p.check());
        assertEquals(0, p.delay());
    }

    @Test
    public void connectedHasNoAttemptTimeout() {
        connect();
        assertEquals(300_000, p.delay()); // only the idle deadline remains
        now += 20_000;
        assertEquals(ConnectionPolicy.KEEP, p.check());
    }

    @Test
    public void idleTimeoutResetByInput() {
        connect();
        now += 299_000;
        p.onInput();
        now += 299_000;
        assertEquals(ConnectionPolicy.KEEP, p.check());
        assertEquals(1000, p.delay());
        now += 1000;
        assertEquals(ConnectionPolicy.IDLE, p.check());
    }

    @Test
    public void idleTimeoutDisabled() {
        p.setIdleMs(0);
        connect();
        assertEquals(-1, p.delay());
        now += 10 * 3_600_000L;
        assertEquals(ConnectionPolicy.KEEP, p.check());
    }

    @Test
    public void backgroundThirtySeconds() {
        connect();
        p.onVisible(false);
        assertEquals(30_000, p.delay());
        now += 29_999;
        p.onInput(); // input does not extend the background deadline
        assertEquals(ConnectionPolicy.KEEP, p.check());
        now += 1;
        assertEquals(ConnectionPolicy.BACKGROUND, p.check());
    }

    @Test
    public void returningCancelsBackground() {
        connect();
        p.onVisible(false);
        now += 20_000;
        p.onVisible(true);
        now += 60_000;
        assertEquals(ConnectionPolicy.KEEP, p.check());
        p.onVisible(false); // countdown restarts from zero
        now += 29_000;
        assertEquals(ConnectionPolicy.KEEP, p.check());
    }

    @Test
    public void screenOffEndsImmediately() {
        connect();
        p.onScreenOff();
        assertEquals(ConnectionPolicy.SCREEN_OFF, p.check());
        assertEquals(0, p.delay());
    }

    @Test
    public void newSessionClearsScreenOff() {
        connect();
        p.onScreenOff();
        p.onSessionEnd();
        assertEquals(ConnectionPolicy.KEEP, p.check());
        connect();
        assertEquals(ConnectionPolicy.KEEP, p.check());
    }

    @Test
    public void pairingTimeout() {
        p.onSessionStart(ConnectionPolicy.PAIR_TIMEOUT_MS);
        now += 119_999;
        assertEquals(ConnectionPolicy.KEEP, p.check());
        now += 1;
        assertEquals(ConnectionPolicy.TIMEOUT, p.check());
    }

    @Test
    public void endedSessionNeverAsksForAnything() {
        connect();
        p.onSessionEnd();
        p.onVisible(false);
        now += 3_600_000;
        assertEquals(ConnectionPolicy.KEEP, p.check());
        assertEquals(-1, p.delay());
    }
}
