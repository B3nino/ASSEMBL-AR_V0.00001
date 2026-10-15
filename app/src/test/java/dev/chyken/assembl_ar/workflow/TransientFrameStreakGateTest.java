package dev.chyken.assembl_ar.workflow;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TransientFrameStreakGateTest {
    private static final long MILLIS = 1_000_000L;
    private static final long TRANSIENT_GRACE = 500L * MILLIS;

    @Test
    public void oneTransientMissPreservesFourAcceptedFrameStreak() {
        TransientFrameStreakGate gate = new TransientFrameStreakGate(4, TRANSIENT_GRACE);

        assertFalse(gate.observe(true, 0L));
        assertFalse(gate.observe(true, 100L * MILLIS));
        assertFalse(gate.observe(false, 200L * MILLIS));
        assertEquals("A short first miss must retain prior lock progress",
                2, gate.getAcceptedFrames());
        assertFalse(gate.observe(true, 300L * MILLIS));
        assertTrue(gate.observe(true, 400L * MILLIS));
        assertEquals(4, gate.getAcceptedFrames());
    }

    @Test
    public void gapOverFiveHundredMillisecondsExpiresPreservedStreak() {
        TransientFrameStreakGate gate = new TransientFrameStreakGate(4, TRANSIENT_GRACE);

        assertFalse(gate.observe(true, 0L));
        assertFalse(gate.observe(true, 100L * MILLIS));
        assertFalse(gate.observe(true, 200L * MILLIS));
        assertFalse(gate.observe(false, 300L * MILLIS));
        assertEquals(3, gate.getAcceptedFrames());

        assertFalse(gate.observe(true, 701L * MILLIS));
        assertEquals("The accepted frame after an expired dropout starts a new streak",
                1, gate.getAcceptedFrames());
    }

    @Test
    public void secondConsecutiveMissDiscardsTheStreak() {
        TransientFrameStreakGate gate = new TransientFrameStreakGate(4, TRANSIENT_GRACE);

        assertFalse(gate.observe(true, 0L));
        assertFalse(gate.observe(true, 100L * MILLIS));
        assertFalse(gate.observe(false, 200L * MILLIS));
        assertFalse(gate.observe(false, 250L * MILLIS));

        assertEquals(0, gate.getAcceptedFrames());
        assertFalse(gate.observe(true, 300L * MILLIS));
        assertEquals(1, gate.getAcceptedFrames());
    }
}
