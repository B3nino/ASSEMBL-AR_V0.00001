package dev.chyken.assembl_ar;

import org.junit.Test;

import dev.chyken.assembl_ar.workflow.VisualPlacementGate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VisualPlacementGateTest {
    private static final long MILLIS = 1_000_000L;
    private static final float[] VISIBLE_QUAD = {
            0.25f, 0.25f,
            0.75f, 0.25f,
            0.75f, 0.65f,
            0.25f, 0.65f
    };

    @Test
    public void identityAndOneFrameAreInsufficient() {
        VisualPlacementGate gate = new VisualPlacementGate(4);
        VisualPlacementGate.Observation first = gate.observe(
                "main-board", VISIBLE_QUAD, new double[]{0.0, 0.0, 500.0}, true, 0.02f);

        assertFalse(first.isReady());
        assertEquals(VisualPlacementGate.Reason.HOLD_STEADY, first.getReason());
    }

    @Test
    public void stableVisibleGeometryAndPosePassTogether() {
        VisualPlacementGate gate = new VisualPlacementGate(4);
        VisualPlacementGate.Observation observation = null;
        for (int frame = 0; frame < 4; frame++) {
            observation = gate.observe("main-board", VISIBLE_QUAD,
                    new double[]{0.0, 0.0, 500.0}, true, 0.02f);
        }

        assertTrue(observation != null && observation.isReady());
        assertEquals(VisualPlacementGate.Reason.READY, observation.getReason());
    }

    @Test
    public void tinyConvexScrewOutlinePassesOnlyItsConfiguredAreaAndFourFrameGate() {
        // An 0.008 by 0.008 normalized square has area and corner cross products of 0.000064:
        // above the hardware floor (0.00003), below the module floor (0.001).
        float[] screwQuad = {
                0.496f, 0.496f, 0.504f, 0.496f,
                0.504f, 0.504f, 0.496f, 0.504f
        };
        VisualPlacementGate gate = new VisualPlacementGate(4);
        for (int frame = 0; frame < 4; frame++) {
            VisualPlacementGate.Observation observation = gate.observe(
                    "short_screw", screwQuad, new double[]{0, 0, 500}, true,
                    0.00003f, frame * 120L * MILLIS);
            assertTrue(observation.isFrameValid());
            assertEquals(frame + 1, observation.getStableFrames());
            assertEquals(frame == 3, observation.isReady());
        }
        VisualPlacementGate.Observation moduleThreshold = gate.observe(
                "short_screw", screwQuad, new double[]{0, 0, 500}, true,
                0.001f, 480L * MILLIS);
        assertEquals(VisualPlacementGate.Reason.TOO_SMALL, moduleThreshold.getReason());
        assertFalse(moduleThreshold.isReady());
    }

    @Test
    public void smallHardwareAllowanceStillRejectsCollinearOutline() {
        float[] collinear = {
                0.496f, 0.50f, 0.499f, 0.50f,
                0.501f, 0.50f, 0.504f, 0.50f
        };
        VisualPlacementGate gate = new VisualPlacementGate(4);
        VisualPlacementGate.Observation observation = gate.observe(
                "short_screw", collinear, new double[]{0, 0, 500}, true, 0.00003f, 0L);

        assertEquals(VisualPlacementGate.Reason.NON_CONVEX, observation.getReason());
        assertEquals(0, observation.getStableFrames());
        assertFalse(observation.isFrameValid());
        assertFalse(observation.isReady());
    }

    @Test
    public void missingPoseAndOffscreenOutlineAreRejected() {
        VisualPlacementGate gate = new VisualPlacementGate(4);
        VisualPlacementGate.Observation missingPose = gate.observe(
                "main-board", VISIBLE_QUAD, null, true, 0.02f);
        float[] offscreen = {
                0.0f, 0.25f, 0.75f, 0.25f, 0.75f, 0.65f, 0.0f, 0.65f
        };
        VisualPlacementGate.Observation outside = gate.observe(
                "main-board", offscreen, new double[]{0.0, 0.0, 500.0}, true, 0.02f);

        assertEquals(VisualPlacementGate.Reason.MISSING_POSE, missingPose.getReason());
        assertEquals(VisualPlacementGate.Reason.OUTSIDE_GUIDE, outside.getReason());
    }

    @Test
    public void unstableProjectionRestartsTheConsecutiveFrameCount() {
        VisualPlacementGate gate = new VisualPlacementGate(3);
        gate.observe("main-board", VISIBLE_QUAD,
                new double[]{0.0, 0.0, 500.0}, true, 0.02f);
        float[] shifted = {
                0.40f, 0.25f, 0.90f, 0.25f, 0.90f, 0.65f, 0.40f, 0.65f
        };
        VisualPlacementGate.Observation moved = gate.observe(
                "main-board", shifted, new double[]{100.0, 0.0, 500.0}, true, 0.02f);

        assertFalse(moved.isReady());
        assertEquals(1, moved.getStableFrames());
    }

    @Test
    public void oneBriefDetectorDropoutPreservesButDoesNotAdvanceLockProgress() {
        VisualPlacementGate gate = new VisualPlacementGate(4);
        gate.observe("main-board", VISIBLE_QUAD,
                new double[]{0.0, 0.0, 500.0}, true, 0.02f, 0L);
        gate.observe("main-board", VISIBLE_QUAD,
                new double[]{0.0, 0.0, 500.0}, true, 0.02f, 120L * MILLIS);

        VisualPlacementGate.Observation missed =
                gate.observeUnavailable(240L * MILLIS);
        VisualPlacementGate.Observation third = gate.observe(
                "main-board", VISIBLE_QUAD, new double[]{0.0, 0.0, 500.0},
                true, 0.02f, 360L * MILLIS);
        VisualPlacementGate.Observation fourth = gate.observe(
                "main-board", VISIBLE_QUAD, new double[]{0.0, 0.0, 500.0},
                true, 0.02f, 480L * MILLIS);

        assertFalse(missed.isFrameValid());
        assertEquals(2, missed.getStableFrames());
        assertEquals(3, third.getStableFrames());
        assertTrue(fourth.isReady());
    }

    @Test
    public void secondDropoutOrLongGapExpiresTheLockAttempt() {
        VisualPlacementGate gate = new VisualPlacementGate(4);
        gate.observe("main-board", VISIBLE_QUAD,
                new double[]{0.0, 0.0, 500.0}, true, 0.02f, 0L);
        gate.observe("main-board", VISIBLE_QUAD,
                new double[]{0.0, 0.0, 500.0}, true, 0.02f, 120L * MILLIS);
        gate.observeUnavailable(240L * MILLIS);

        VisualPlacementGate.Observation secondMiss =
                gate.observeUnavailable(300L * MILLIS);
        assertEquals(0, secondMiss.getStableFrames());

        gate.observe("main-board", VISIBLE_QUAD,
                new double[]{0.0, 0.0, 500.0}, true, 0.02f, 400L * MILLIS);
        VisualPlacementGate.Observation afterGap = gate.observe(
                "main-board", VISIBLE_QUAD, new double[]{0.0, 0.0, 500.0},
                true, 0.02f, 3_401L * MILLIS);
        assertEquals(1, afterGap.getStableFrames());
        assertFalse(afterGap.isReady());
    }

    @Test
    public void oneMissingPoseFrameKeepsAValidGeometricStreak() {
        VisualPlacementGate gate = new VisualPlacementGate(3);
        gate.observe("main-board", VISIBLE_QUAD,
                new double[]{0.0, 0.0, 500.0}, true, 0.02f, 0L);
        VisualPlacementGate.Observation missingPose = gate.observe(
                "main-board", VISIBLE_QUAD, null, true, 0.02f, 120L * MILLIS);
        VisualPlacementGate.Observation secondValid = gate.observe(
                "main-board", VISIBLE_QUAD, new double[]{0.0, 0.0, 500.0},
                true, 0.02f, 240L * MILLIS);
        VisualPlacementGate.Observation ready = gate.observe(
                "main-board", VISIBLE_QUAD, new double[]{0.0, 0.0, 500.0},
                true, 0.02f, 360L * MILLIS);

        assertEquals(VisualPlacementGate.Reason.MISSING_POSE, missingPose.getReason());
        assertEquals(1, missingPose.getStableFrames());
        assertEquals(2, secondValid.getStableFrames());
        assertTrue(ready.isReady());
    }

    @Test
    public void slowValidFramesStillReachFourInsteadOfLoopingAtOne() {
        VisualPlacementGate gate = new VisualPlacementGate(4);
        VisualPlacementGate.Observation first = gate.observe("main-board", VISIBLE_QUAD,
                new double[]{0, 0, 500}, true, 0.02f, 0L);
        assertTrue(Double.isNaN(first.getFrameGapMillis()));
        assertEquals(VisualPlacementGate.ResetReason.INITIAL, first.getResetReason());
        for (int frame = 1; frame <= 4; frame++) {
            VisualPlacementGate.Observation observation = gate.observe("main-board", VISIBLE_QUAD,
                    new double[]{0, 0, 500}, true, 0.02f, frame * 1_000L * MILLIS);
            assertTrue(observation.isFrameValid());
            assertEquals(frame >= 3, observation.isReady());
            assertEquals(Math.min(frame + 1, 4), observation.getStableFrames());
            assertEquals(1_000.0, observation.getFrameGapMillis(), 0.0);
            assertEquals(3_000L, observation.getMaximumFrameGapMillis());
            assertEquals(VisualPlacementGate.ResetReason.NONE, observation.getResetReason());
        }
    }

    @Test
    public void lockSurvivesCadenceSlowdownAfterThirtySecondsAndCanBeReacquired() {
        VisualPlacementGate gate = new VisualPlacementGate(4);
        long timestamp = 0;
        // Thirty seconds of normal operation followed by a full minute of slower processing.
        // All observations are fresh detections; time passing alone must not produce 1/4.
        for (int frame = 0; frame < 120; frame++) {
            VisualPlacementGate.Observation lock = gate.observe("main-board", VISIBLE_QUAD,
                    new double[]{0, 0, 500}, true, .02f, timestamp);
            assertEquals(Math.min(frame + 1, 4), lock.getStableFrames());
            timestamp += 250L * MILLIS;
        }
        long[] slowGaps = {1_000, 2_000, 3_000};
        for (int frame = 0; frame < 42; frame++) {
            timestamp += slowGaps[frame % slowGaps.length] * MILLIS;
            VisualPlacementGate.Observation lock = gate.observe("main-board", VISIBLE_QUAD,
                    new double[]{0, 0, 500}, true, .02f, timestamp);
            assertTrue("Slow frame " + frame + " reset: " + lock.getResetReason(), lock.isReady());
        }
        gate.reset();
        for (int frame = 1; frame <= 4; frame++) {
            timestamp += 1_500L * MILLIS;
            VisualPlacementGate.Observation lock = gate.observe("main-board", VISIBLE_QUAD,
                    new double[]{0, 0, 500}, true, .02f, timestamp);
            assertEquals(frame, lock.getStableFrames());
            assertEquals(frame == 4, lock.isReady());
        }
    }

    @Test
    public void modestHandMotionAndPoseJitterAllowLocking() {
        VisualPlacementGate gate = new VisualPlacementGate(4);
        float[] jittered = transformedQuad(0.04f, 1.13f, 12f);
        for (int frame = 0; frame < 8; frame++) {
            boolean jitter = frame % 2 != 0;
            VisualPlacementGate.Observation lock = gate.observe("main-board",
                    jitter ? jittered : VISIBLE_QUAD,
                    new double[]{0, 0, jitter ? 600 : 500}, true, .02f, frame * 1_200L * MILLIS);
            assertEquals(Math.min(frame + 1, 4), lock.getStableFrames());
            assertEquals(frame >= 3, lock.isReady());
        }
    }

    @Test
    public void largeRotationAndScaleChangesStillRestartLocking() {
        for (float[] changed : new float[][]{
                transformedQuad(0, 1f, 25f), transformedQuad(0, 1.3f, 0)}) {
            VisualPlacementGate gate = new VisualPlacementGate(4);
            gate.observe("main-board", VISIBLE_QUAD, new double[]{0, 0, 500}, true, .02f, 0L);
            VisualPlacementGate.Observation lock = gate.observe("main-board", changed,
                    new double[]{0, 0, 500}, true, .02f, 1_200L * MILLIS);
            assertFalse(lock.isReady());
            assertEquals(1, lock.getStableFrames());
            assertEquals(VisualPlacementGate.ResetReason.GEOMETRY, lock.getResetReason());
        }
    }

    @Test
    public void longGapAndMissingFramesCannotPreserveAReadyLockIndefinitely() {
        VisualPlacementGate gate = new VisualPlacementGate(4);
        for (int frame = 0; frame < 4; frame++) {
            gate.observe("main-board", VISIBLE_QUAD, new double[]{0, 0, 500},
                    true, .02f, frame * 250L * MILLIS);
        }
        VisualPlacementGate.Observation restart = gate.observe("main-board", VISIBLE_QUAD,
                new double[]{0, 0, 500}, true, .02f, 3_751L * MILLIS);
        assertEquals(1, restart.getStableFrames());
        assertEquals(VisualPlacementGate.ResetReason.TIME_GAP, restart.getResetReason());
        assertFalse(restart.isReady());
        VisualPlacementGate.Observation miss = gate.observeUnavailable(4_000L * MILLIS);
        assertEquals(1, miss.getStableFrames());
        assertFalse(miss.isReady());
        VisualPlacementGate.Observation lost = gate.observeUnavailable(4_100L * MILLIS);
        assertEquals(0, lost.getStableFrames());
        assertFalse(lost.isReady());
    }

    private static float[] transformedQuad(float shiftX, float scale, float angleDegrees) {
        float[] corners = VISIBLE_QUAD.clone();
        double angle = Math.toRadians(angleDegrees);
        for (int index = 0; index < 8; index += 2) {
            double x = (corners[index] - .5f) * scale;
            double y = (corners[index + 1] - .45f) * scale;
            corners[index] = (float) (.5 + shiftX + x * Math.cos(angle) - y * Math.sin(angle));
            corners[index + 1] = (float) (.45 + x * Math.sin(angle) + y * Math.cos(angle));
        }
        return corners;
    }

    @Test
    public void normalCadenceReachesFourWithoutDiagnosticResets() {
        VisualPlacementGate gate = new VisualPlacementGate(4);
        for (int frame = 0; frame < 4; frame++) {
            VisualPlacementGate.Observation observation = gate.observe("main-board", VISIBLE_QUAD,
                    new double[]{0, 0, 500}, true, 0.02f, frame * 250L * MILLIS);
            assertEquals(frame + 1, observation.getStableFrames());
            assertEquals(frame == 3, observation.isReady());
            if (frame > 0) {
                assertEquals(250.0, observation.getFrameGapMillis(), 0.0);
                assertEquals(VisualPlacementGate.ResetReason.NONE, observation.getResetReason());
            }
        }
    }

    @Test
    public void geometryPoseAndIdentityRestartsHaveDistinctDiagnostics() {
        VisualPlacementGate gate = new VisualPlacementGate(4);
        gate.observe("main-board", VISIBLE_QUAD, new double[]{0, 0, 500}, true, 0.02f, 0L);
        float[] shifted = VISIBLE_QUAD.clone();
        for (int index = 0; index < shifted.length; index += 2) shifted[index] += 0.10f;
        VisualPlacementGate.Observation geometry = gate.observe("main-board", shifted,
                new double[]{0, 0, 500}, true, 0.02f, 120L * MILLIS);
        VisualPlacementGate.Observation pose = gate.observe("main-board", shifted,
                new double[]{0, 0, 700}, true, 0.02f, 240L * MILLIS);
        VisualPlacementGate.Observation identity = gate.observe("another-board", shifted,
                new double[]{0, 0, 700}, true, 0.02f, 360L * MILLIS);
        assertEquals(VisualPlacementGate.ResetReason.GEOMETRY, geometry.getResetReason());
        assertEquals(VisualPlacementGate.ResetReason.POSE, pose.getResetReason());
        assertEquals(VisualPlacementGate.ResetReason.IDENTITY, identity.getResetReason());
        assertEquals(1, geometry.getStableFrames());
        assertEquals(1, pose.getStableFrames());
        assertEquals(1, identity.getStableFrames());
    }

    @Test
    public void frameGapMeasuresPreviousObservationEvenWhenAcceptedEvidenceIsOlder() {
        VisualPlacementGate gate = new VisualPlacementGate(4);
        gate.observe("main-board", VISIBLE_QUAD, new double[]{0, 0, 500}, true, 0.02f, 0L);
        gate.observeUnavailable(2_900L * MILLIS);
        VisualPlacementGate.Observation observation = gate.observe("main-board", VISIBLE_QUAD,
                new double[]{0, 0, 500}, true, 0.02f, 3_300L * MILLIS);
        assertEquals(400.0, observation.getFrameGapMillis(), 0.0);
        assertEquals(VisualPlacementGate.ResetReason.TIME_GAP, observation.getResetReason());
        assertEquals(1, observation.getStableFrames());
    }
}
