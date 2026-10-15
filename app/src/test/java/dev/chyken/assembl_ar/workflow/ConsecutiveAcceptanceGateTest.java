package dev.chyken.assembl_ar.workflow;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ConsecutiveAcceptanceGateTest {
    @Test
    public void completeSetMustRemainAcceptedForEveryRequiredFrame() {
        ConsecutiveAcceptanceGate gate = new ConsecutiveAcceptanceGate(4);

        assertFalse(gate.observe(true));
        assertFalse(gate.observe(true));
        assertFalse(gate.observe(true));
        assertEquals(3, gate.getAcceptedFrames());
        assertTrue(gate.observe(true));
        assertEquals(4, gate.getAcceptedFrames());
    }

    @Test
    public void losingOneInstanceResetsTheWholeAtomicSet() {
        ConsecutiveAcceptanceGate gate = new ConsecutiveAcceptanceGate(3);

        assertFalse(gate.observe(true));
        assertFalse(gate.observe(true));
        assertFalse(gate.observe(false));
        assertEquals(0, gate.getAcceptedFrames());
        assertFalse(gate.observe(true));
        assertFalse(gate.observe(true));
        assertTrue(gate.observe(true));

        gate.reset();
        assertEquals(0, gate.getAcceptedFrames());
    }
}
