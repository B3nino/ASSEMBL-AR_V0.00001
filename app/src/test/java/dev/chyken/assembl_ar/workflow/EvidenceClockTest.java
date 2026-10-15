package dev.chyken.assembl_ar.workflow;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class EvidenceClockTest {
    private static final long MS = 1_000_000L;

    @Test public void unknownCompletionKeepsCaptureTimestamps() {
        EvidenceClock clock = new EvidenceClock();
        assertEquals(1_000 * MS, clock.observe(1_000 * MS, Long.MIN_VALUE));
        assertEquals(4_001 * MS, clock.observe(4_001 * MS, Long.MIN_VALUE));
        assertEquals(0L, clock.getLastProcessingMillis());
    }

    @Test public void slowAnalysisOfALiveCameraIsNotCameraSilence() {
        EvidenceClock clock = new EvidenceClock();
        long capture = 10_000 * MS;
        long previous = clock.observe(capture, capture + 2_500 * MS);
        for (int frame = 1; frame <= 5; frame++) {
            // The next frame is captured 30 ms before the previous analysis finished.
            capture += 2_470 * MS;
            long evidence = clock.observe(capture, capture + 2_500 * MS);
            assertTrue(evidence > previous);
            assertTrue("Evidence gap must stay far below the gates' limits",
                    evidence - previous <= 2 * EvidenceClock.MINIMUM_STEP_NANOS);
            previous = evidence;
        }
        assertEquals(2_500L, clock.getLastProcessingMillis());
    }

    @Test public void realCameraSilenceRemainsVisibleToTheGates() {
        EvidenceClock clock = new EvidenceClock();
        long first = clock.observe(0L, 400 * MS);
        // No frame for 3.5 s after processing finished: the camera was covered or stalled.
        long second = clock.observe(3_900 * MS, 4_300 * MS);
        assertEquals(3_500 * MS, second - first);
        assertFalse(second - first <= MotorAssemblyGate.MAXIMUM_FRAME_GAP_MILLIS * MS);
    }

    @Test public void redeliveredFramesKeepTheirEvidenceTimestamp() {
        EvidenceClock clock = new EvidenceClock();
        clock.observe(0L, 900 * MS);
        long second = clock.observe(1_000 * MS, 1_900 * MS);
        assertEquals(second, clock.observe(1_000 * MS, 1_900 * MS));
        assertEquals(second, clock.observe(1_000 * MS, Long.MIN_VALUE));
    }
}
