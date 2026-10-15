package dev.chyken.assembl_ar.workflow;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MotorAssemblyGateTest {
    private static final long MILLIS = 1_000_000L;

    @Test
    public void sixSimultaneousObservationsUnlockTheVisualCheck() {
        MotorAssemblyGate gate = new MotorAssemblyGate();
        for (int index = 0; index < 5; index++) {
            MotorAssemblyGate.Decision decision = observe(gate, index * 100L, true, true);
            assertFalse(decision.isReady());
            assertTrue(decision.isBaseLocked());
        }

        MotorAssemblyGate.Decision decision = observe(gate, 500L, true, true);
        assertTrue(decision.isReady());
        assertTrue(decision.isLeftConfirmed());
        assertTrue(decision.isRightConfirmed());
        assertEquals(6, decision.getAcceptedFrames());
        assertEquals(6, decision.getRequiredFrames());
    }

    @Test
    public void sixOfTenAllowsRealMissesWithoutRequiringAConsecutiveStreak() {
        MotorAssemblyGate gate = new MotorAssemblyGate();
        for (int index = 0; index < 4; index++) {
            observe(gate, index * 100L, true, true);
        }
        observe(gate, 400L, true, false);
        observe(gate, 500L, false, true);
        observe(gate, 600L, true, false);
        observe(gate, 700L, false, true);
        assertFalse(observe(gate, 800L, true, true).isReady());
        assertTrue(observe(gate, 900L, true, true).isReady());
    }

    @Test
    public void intermittentBlurIsIgnoredButCannotTriggerReadiness() {
        MotorAssemblyGate gate = new MotorAssemblyGate();
        for (int index = 0; index < 6; index++) {
            MotorAssemblyGate.Decision accepted = observe(gate, index * 200L, true, true);
            MotorAssemblyGate.Decision blurred = gate.observe(
                    (index * 200L + 100L) * MILLIS, true, false, true, true);
            assertEquals(index + 1, blurred.getAcceptedFrames());
            assertFalse(blurred.isReady());
            assertEquals(index == 5, accepted.isReady());
        }
        assertTrue(observe(gate, 1200L, true, true).isReady());
    }

    @Test
    public void oneSideCanBeConfirmedWithoutCompletingThePair() {
        MotorAssemblyGate gate = new MotorAssemblyGate();
        MotorAssemblyGate.Decision decision = null;
        for (int index = 0; index < 6; index++) {
            decision = observe(gate, index * 100L, true, false);
        }
        assertTrue(decision.isLeftConfirmed());
        assertFalse(decision.isRightConfirmed());
        assertFalse(decision.isReady());
        assertEquals(0, decision.getAcceptedFrames());
    }

    @Test
    public void firstMotorCheckpointAcceptsSixLeftObservationsWithNoRightMotor() {
        MotorAssemblyGate gate = new MotorAssemblyGate();
        for (int index = 0; index < 6; index++) {
            MotorAssemblyGate.Decision decision = gate.observe(
                    index * 100L * MILLIS, true, true, true, false, false);
            assertEquals(index == 5, decision.isReady());
            assertEquals(index == 5, decision.isLeftConfirmed());
            assertFalse(decision.isRightConfirmed());
            assertEquals(index + 1, decision.getAcceptedFrames());
        }
    }

    @Test
    public void rightMotorCannotSatisfyTheAuthoredFirstLeftMotorCheckpoint() {
        MotorAssemblyGate gate = new MotorAssemblyGate();
        for (int index = 0; index < 10; index++) {
            MotorAssemblyGate.Decision decision = gate.observe(
                    index * 100L * MILLIS, true, true, false, true, false);
            assertFalse(decision.isReady());
            assertFalse(decision.isLeftConfirmed());
            assertEquals(0, decision.getAcceptedFrames());
        }
    }

    @Test
    public void changingFromFirstMotorToPairRequiresNewEvidence() {
        MotorAssemblyGate gate = new MotorAssemblyGate();
        for (int index = 0; index < 6; index++) {
            // Even earlier frames that happened to include both motors belong to another card.
            gate.observe(index * 100L * MILLIS, true, true, true, true, false);
        }

        // The original overload must still select the complete-pair contract.
        MotorAssemblyGate.Decision switched = observe(gate, 600L, true, true);
        assertEquals(1, switched.getAcceptedFrames());
        assertFalse(switched.isReady());
        assertFalse(switched.isLeftConfirmed());
        assertFalse(switched.isRightConfirmed());
        for (int index = 1; index < 6; index++) {
            MotorAssemblyGate.Decision decision = gate.observe(
                    (600L + index * 100L) * MILLIS, true, true, true, true, true);
            assertEquals(index + 1, decision.getAcceptedFrames());
            assertEquals(index == 5, decision.isReady());
        }
    }

    @Test
    public void alternatingSidesCannotBeCombinedIntoACompletedPair() {
        MotorAssemblyGate gate = new MotorAssemblyGate();
        for (int index = 0; index < 30; index++) {
            MotorAssemblyGate.Decision decision = observe(
                    gate, index * 80L, index % 2 == 0, index % 2 != 0);
            assertFalse(decision.isReady());
            assertEquals(0, decision.getAcceptedFrames());
        }
        MotorAssemblyGate.Decision firstPair = observe(gate, 2400L, true, true);
        assertFalse(firstPair.isReady());
        assertEquals(1, firstPair.getAcceptedFrames());
    }

    @Test
    public void currentMissingMotorImmediatelySuspendsReadinessAndLosesItsCheckmark() {
        MotorAssemblyGate gate = readyGate();
        MotorAssemblyGate.Decision missing = observe(gate, 600L, true, false);
        assertFalse(missing.isReady());
        assertTrue(missing.isLeftConfirmed());
        assertFalse(missing.isRightConfirmed());
        assertEquals(6, missing.getAcceptedFrames());
        assertTrue(observe(gate, 700L, true, true).isReady());
    }

    @Test
    public void persistentRealFailuresDisplaceEarlierPositiveEvidence() {
        MotorAssemblyGate gate = readyGate();
        for (int index = 6; index < 11; index++) {
            assertFalse(observe(gate, index * 100L, true, false).isReady());
        }
        MotorAssemblyGate.Decision recoveredFrame = observe(gate, 1100L, true, true);
        assertEquals(5, recoveredFrame.getAcceptedFrames());
        assertFalse(recoveredFrame.isReady());
    }

    @Test
    public void briefAnchorLossSuspendsReadinessAndAllowsFreshReacquisition() {
        MotorAssemblyGate gate = readyGate();
        MotorAssemblyGate.Decision lost = gate.observe(700L * MILLIS, false, true, true, true);
        assertFalse(lost.isBaseLocked());
        assertFalse(lost.isReady());
        assertEquals(6, lost.getAcceptedFrames());
        assertTrue(observe(gate, 900L, true, true).isReady());
    }

    @Test
    public void longAnchorLossExpiresEvidenceEvenWithFramesStillArriving() {
        MotorAssemblyGate gate = readyGate();
        gate.observe(900L * MILLIS, false, true, true, true);
        gate.observe(1300L * MILLIS, false, true, true, true);
        assertEquals(6, gate.observe(2500L * MILLIS, false, true, true, true).getAcceptedFrames());
        MotorAssemblyGate.Decision lost = gate.observe(
                2501L * MILLIS, false, true, true, true);
        assertEquals(0, lost.getAcceptedFrames());
        assertFalse(lost.isBaseLocked());
        MotorAssemblyGate.Decision reacquired = observe(gate, 2600L, true, true);
        assertFalse(reacquired.isReady());
        assertEquals(1, reacquired.getAcceptedFrames());
    }

    @Test
    public void continuousBlurAlsoExpiresOldEvidence() {
        MotorAssemblyGate gate = readyGate();
        gate.observe(900L * MILLIS, true, false, true, true);
        gate.observe(1300L * MILLIS, true, false, true, true);
        assertEquals(6, gate.observe(2500L * MILLIS, true, false, true, true).getAcceptedFrames());
        MotorAssemblyGate.Decision blurred = gate.observe(
                2501L * MILLIS, true, false, true, true);
        assertFalse(blurred.isReady());
        assertEquals(0, blurred.getAcceptedFrames());
    }

    @Test
    public void cameraPauseStartsNewEvidenceDespiteCurrentValidPose() {
        MotorAssemblyGate gate = readyGate();
        MotorAssemblyGate.Decision resumed = observe(gate, 2501L, true, true);
        assertTrue(resumed.isBaseLocked());
        assertFalse(resumed.isReady());
        assertEquals(1, resumed.getAcceptedFrames());
    }

    @Test
    public void slowFreshFramesCanCompleteThePairIncludingTheTwoSecondBoundary() {
        assertEquals(2000L, MotorAssemblyGate.MAXIMUM_FRAME_GAP_MILLIS);
        for (long cadence : new long[] {700L, 1200L, 1600L, 2000L}) {
            MotorAssemblyGate gate = new MotorAssemblyGate();
            for (int index = 0; index < 6; index++) {
                MotorAssemblyGate.Decision decision = observe(gate, index * cadence, true, true);
                assertEquals("cadence=" + cadence + " frame=" + index,
                        index + 1, decision.getAcceptedFrames());
                assertEquals(index == 5, decision.isReady());
            }
        }
    }

    @Test
    public void oldSamplesExpireEvenWhenTheAnchorIsContinuouslyVisible() {
        MotorAssemblyGate gate = readyGate();
        for (long timestamp = 2500L; timestamp <= 10500L; timestamp += 2000L) {
            assertFalse(observe(gate, timestamp, false, false).isReady());
        }
        assertEquals("The last positive is exactly ten seconds old", 1,
                observe(gate, 10500L, false, false).getAcceptedFrames());
        assertEquals("Evidence older than ten seconds must expire", 0,
                observe(gate, 10501L, false, false).getAcceptedFrames());
    }

    @Test
    public void duplicateAndOutOfOrderTimestampsNeverCountOrTriggerReadiness() {
        MotorAssemblyGate gate = new MotorAssemblyGate();
        observe(gate, 100L, true, true);
        for (int index = 0; index < 10; index++) {
            MotorAssemblyGate.Decision duplicate = observe(gate, 100L, true, true);
            assertEquals(1, duplicate.getAcceptedFrames());
            assertFalse(duplicate.isReady());
        }
        assertEquals(1, observe(gate, 50L, true, true).getAcceptedFrames());
        for (int index = 2; index <= 6; index++) {
            observe(gate, index * 100L, true, true);
        }
        assertFalse(observe(gate, 600L, true, true).isReady());
        assertFalse(observe(gate, 300L, true, true).isReady());
        assertTrue(observe(gate, 700L, true, true).isReady());
    }

    @Test
    public void resetClearsEvidenceAndAllowsANewTimestampSequence() {
        MotorAssemblyGate gate = readyGate();
        gate.reset();
        MotorAssemblyGate.Decision restarted = observe(gate, 0L, true, false);
        assertFalse(restarted.isReady());
        assertFalse(restarted.isLeftConfirmed());
        assertFalse(restarted.isRightConfirmed());
        assertEquals(0, restarted.getAcceptedFrames());
    }

    @Test
    public void returnedDecisionDoesNotChangeWhenMoreFramesArrive() {
        MotorAssemblyGate gate = readyGate();
        MotorAssemblyGate.Decision accepted = observe(gate, 600L, true, true);
        observe(gate, 700L, false, false);
        gate.reset();
        assertTrue(accepted.isReady());
        assertTrue(accepted.isLeftConfirmed());
        assertEquals(7, accepted.getAcceptedFrames());
    }

    private static MotorAssemblyGate readyGate() {
        MotorAssemblyGate gate = new MotorAssemblyGate();
        for (int index = 0; index < 6; index++) {
            observe(gate, index * 100L, true, true);
        }
        return gate;
    }

    private static MotorAssemblyGate.Decision observe(MotorAssemblyGate gate, long millis,
                                                       boolean left, boolean right) {
        return gate.observe(millis * MILLIS, true, true, left, right);
    }
}
