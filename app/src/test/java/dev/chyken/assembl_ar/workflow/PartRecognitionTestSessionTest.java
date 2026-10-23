package dev.chyken.assembl_ar.workflow;

import org.junit.Test;

import java.lang.reflect.Method;

import dev.chyken.assembl_ar.vision.RecognitionResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class PartRecognitionTestSessionTest {
    private static final long MS = 1_000_000L;
    private static final float[] QUAD = {.25f, .25f, .75f, .25f, .75f, .65f, .25f, .65f};
    private static final RecognitionResult.DetectionMethod ORB =
            RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY;
    private static final RecognitionResult.DetectionMethod FALLBACK =
            RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK;

    @Test public void crossPhaseRejectionCannotBeCountedAsExpectedOrLocked() throws Exception {
        PartRecognitionTestSession session=new PartRecognitionTestSession();long token=session.selectPart("motor","Motor",.001f);
        for(int i=0;i<5;i++)session.observe(token,frame(i*100,"motor",ORB,true).withDiagnosticMetrics(new RecognitionResult.DiagnosticMetrics(100,100,"Normal","Wrong assembly phase")),QUAD);
        PartRecognitionTestSession.Snapshot snapshot=session.getSnapshot("motor");assertFalse(snapshot.isLocked());assertEquals(5,snapshot.getWrongComponentFrames());assertEquals(0,snapshot.getExpectedOrbFrames());
    }

    @Test
    public void observedMatchAndConfusionCountsHaveExplicitDenominators() throws Exception {
        PartRecognitionTestSession session = new PartRecognitionTestSession();
        long token = session.selectPart("motor", "Motor", .001f);
        session.observe(token, frame(0, "motor", ORB, true), QUAD);
        session.observe(token, frame(100, "motor", FALLBACK, false), QUAD);
        session.observe(token, frame(200, "bracket", ORB, true), QUAD);
        session.observe(token, frame(300, "bracket", ORB, true), QUAD);
        session.observe(token, frame(400, null, RecognitionResult.DetectionMethod.NONE, false), null);

        PartRecognitionTestSession.Snapshot s = session.getSnapshot("motor");
        assertEquals(5, s.getAnalyzedFrames());
        assertEquals(2, s.getExpectedFrames());
        assertEquals(2, s.getWrongComponentFrames());
        assertEquals(1, s.getNoDetectionFrames());
        assertEquals(.4, s.getExpectedMatchRate(), 1e-9);
        assertEquals(Long.valueOf(2), s.getWrongComponents().get("bracket"));
        assertEquals(1, s.getPoseFrames());
        assertEquals(3, s.getOrbFrames());
        assertEquals(1, s.getFallbackFrames());
        assertEquals(1, s.getExpectedOrbFrames());
        assertEquals(1, s.getExpectedFallbackFrames());
        assertEquals(.75, s.getAverageConfidence(), 1e-9);
        assertEquals(9, s.getAverageInlierCount(), 1e-9);
        assertEquals(12, s.getAverageGoodMatchCount(), 1e-9);
        assertEquals(15, s.getAverageLatencyMs(), 1e-9);
        assertEquals(2, s.getAverageFeatureLatencyMs(), 1e-9);
        assertEquals(3, s.getAverageMatchingLatencyMs(), 1e-9);
        assertEquals(4, s.getAverageSpatialLatencyMs(), 1e-9);
        assertEquals(5, s.getAverageFallbackLatencyMs(), 1e-9);
    }

    @Test
    public void fourFramePoseLockIsCountedOnceAndReacquisitionIsSeparate() throws Exception {
        PartRecognitionTestSession session = new PartRecognitionTestSession();
        long token = session.selectPart("motor", "Motor", .001f);
        for (int frame = 0; frame < 6; frame++) {
            session.observe(token, frame(frame * 100, "motor", ORB, true), QUAD);
            assertEquals(frame >= 3, session.getSnapshot("motor").isLocked());
        }
        PartRecognitionTestSession.Snapshot first = session.getSnapshot("motor");
        assertEquals(4, first.getStableFrames());
        assertEquals(1, first.getStableLockCount());
        assertEquals(3, first.getLockedFrames());
        assertEquals(300, first.getFirstLockMillis(), 0);

        session.observe(token, frame(600, null, RecognitionResult.DetectionMethod.NONE, false), null);
        assertFalse(session.getSnapshot("motor").isLocked());
        session.observe(token, frame(700, "motor", ORB, true), QUAD);
        assertEquals(1, session.getSnapshot("motor").getStableLockCount());

        session.observe(token, frame(800, null, RecognitionResult.DetectionMethod.NONE, false), null);
        session.observe(token, frame(900, null, RecognitionResult.DetectionMethod.NONE, false), null);
        for (int frame = 10; frame < 14; frame++) {
            session.observe(token, frame(frame * 100, "motor", ORB, true), QUAD);
        }
        assertEquals(2, session.getSnapshot("motor").getStableLockCount());
        assertEquals(300, session.getSnapshot("motor").getFirstLockMillis(), 0);
    }

    @Test
    public void recognizedIdentityWithoutVisiblePoseCannotLock() throws Exception {
        PartRecognitionTestSession session = new PartRecognitionTestSession();
        long token = session.selectPart("short_screw", "Short screw", .00003f);
        for (int index = 0; index < 4; index++) {
            session.observe(token, frame(index * 100, "short_screw",
                    RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY, false), QUAD);
        }
        PartRecognitionTestSession.Snapshot s = session.getSnapshot("short_screw");
        assertEquals(0, s.getExpectedFrames());
        assertEquals(0, s.getOrbFrames());
        assertEquals(4, s.getNoDetectionFrames());
        assertEquals(0, s.getStableLockCount());
        assertTrue(Double.isNaN(s.getFirstLockMillis()));
        assertEquals(VisualPlacementGate.Reason.MISSING_CORNERS, s.getLastObservation().getReason());

        float[] outside = QUAD.clone();
        outside[0] = 0;
        session.observe(token, frame(400, "short_screw", ORB, true), outside);
        assertFalse(session.getSnapshot("short_screw").isLocked());
        assertEquals(VisualPlacementGate.Reason.OUTSIDE_GUIDE,
                session.getSnapshot("short_screw").getLastObservation().getReason());
    }

    @Test
    public void unlocatedWrongIdentityCannotNameAnotherPartOrBridgeALock() throws Exception {
        PartRecognitionTestSession session = new PartRecognitionTestSession();
        long token = session.selectPart("motor", "Motor", .001f);
        for (int index = 0; index < 4; index++) {
            session.observe(token, frame(index * 100, "bracket",
                    RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY, true), QUAD);
        }
        PartRecognitionTestSession.Snapshot s = session.getSnapshot("motor");
        assertEquals(0, s.getWrongComponentFrames());
        assertTrue(s.getWrongComponents().isEmpty());
        assertEquals(4, s.getNoDetectionFrames());
        assertEquals(0, s.getStableLockCount());
        for (int index = 4; index < 7; index++) {
            session.observe(token, frame(index * 100, "motor", ORB, true), QUAD);
        }
        assertFalse(session.getSnapshot("motor").isLocked());
        assertEquals(3, session.getSnapshot("motor").getStableFrames());
    }

    @Test
    public void componentConfusionBreaksTheLockAttemptImmediately() throws Exception {
        PartRecognitionTestSession session = new PartRecognitionTestSession();
        long token = session.selectPart("motor", "Motor", .001f);
        for (int index = 0; index < 3; index++) {
            session.observe(token, frame(index * 100, "motor", ORB, true), QUAD);
        }
        session.observe(token, frame(300, "bracket", ORB, true), QUAD);
        assertEquals(0, session.getSnapshot("motor").getStableFrames());
        session.observe(token, frame(400, "motor", ORB, true), QUAD);
        assertEquals(1, session.getSnapshot("motor").getStableFrames());
        assertEquals(0, session.getSnapshot("motor").getStableLockCount());
    }

    @Test
    public void targetSwitchAndPauseRejectInFlightResultsAndKeepIndependentStats() throws Exception {
        PartRecognitionTestSession session = new PartRecognitionTestSession();
        long motorToken = session.selectPart("motor", "Motor", .001f);
        session.observe(motorToken, frame(0, "motor", ORB, true), QUAD);
        long bracketToken = session.selectPart("bracket", "Bracket", .001f);
        assertFalse(session.observe(motorToken, frame(100, "motor", ORB, true), QUAD));
        session.observe(bracketToken, frame(200, "bracket", ORB, true), QUAD);
        session.pause();
        assertFalse(session.observe(bracketToken, frame(300, "bracket", ORB, true), QUAD));
        assertEquals(0, session.getSnapshot("bracket").getStableFrames());
        motorToken = session.selectPart("motor", "Motor", .001f);
        assertFalse(session.observe(bracketToken, frame(400, "bracket", ORB, true), QUAD));
        session.observe(motorToken, frame(500, "motor", ORB, true), QUAD);
        assertEquals(2, session.getSnapshot("motor").getAnalyzedFrames());
        assertEquals(1, session.getSnapshot("bracket").getAnalyzedFrames());
        assertEquals(1, session.getSnapshot("motor").getStableFrames());
    }

    @Test
    public void duplicateAndOutOfOrderTimestampsCannotInflateStatistics() throws Exception {
        PartRecognitionTestSession session = new PartRecognitionTestSession();
        long token = session.selectPart("motor", "Motor", .001f);
        assertTrue(session.observe(token, frame(200, "motor", ORB, true), QUAD));
        assertFalse(session.observe(token, frame(200, "motor", ORB, true), QUAD));
        assertFalse(session.observe(token, frame(100, "motor", ORB, true), QUAD));
        assertTrue(session.observe(token, frame(300, "motor", ORB, true), QUAD));
        assertEquals(2, session.getSnapshot("motor").getAnalyzedFrames());
        assertEquals(2, session.getSnapshot("motor").getStableFrames());
        assertEquals(100, session.getSnapshot("motor").getObservedDurationMillis(), 0);
    }

    @Test
    public void resetOnlyClearsItsPartAndInvalidatesItsPendingCallbacks() throws Exception {
        PartRecognitionTestSession session = new PartRecognitionTestSession();
        long token = session.selectPart("motor", "Motor", .001f);
        session.observe(token, frame(0, "motor", ORB, true), QUAD);
        token = session.selectPart("bracket", "Bracket", .001f);
        session.observe(token, frame(100, "bracket", ORB, true), QUAD);
        session.resetPart("bracket");
        assertFalse(session.observe(token, frame(200, "bracket", ORB, true), QUAD));
        assertNull(session.getSnapshot("bracket"));
        assertEquals(1, session.getSnapshot("motor").getAnalyzedFrames());
        token = session.selectPart("bracket", "Bracket", .001f);
        assertEquals(0, session.getSnapshot("bracket").getAnalyzedFrames());
        assertTrue(session.observe(token, frame(300, "bracket", ORB, true), QUAD));
    }

    @Test
    public void firstLockTimeExcludesTimePausedOrTestingOtherParts() throws Exception {
        PartRecognitionTestSession session = new PartRecognitionTestSession();
        long token = session.selectPart("motor", "Motor", .001f);
        session.observe(token, frame(0, "motor", ORB, true), QUAD);
        session.observe(token, frame(100, "motor", ORB, true), QUAD);
        session.pause();
        token = session.selectPart("motor", "Motor", .001f);
        for (int index = 0; index < 4; index++) {
            session.observe(token, frame(100_000 + index * 100, "motor", ORB, true), QUAD);
        }
        assertEquals(400, session.getSnapshot("motor").getFirstLockMillis(), 0);
        assertEquals(400, session.getSnapshot("motor").getObservedDurationMillis(), 0);
    }

    @Test
    public void emptySamplesStayUnknownAndSnapshotsDoNotChangeWithLaterFrames() throws Exception {
        PartRecognitionTestSession session = new PartRecognitionTestSession();
        assertTrue(session.getSnapshots().isEmpty());
        long token = session.selectPart("motor", "Motor", .001f);
        PartRecognitionTestSession.Snapshot empty = session.getSnapshot("motor");
        assertTrue(Double.isNaN(empty.getExpectedMatchRate()));
        assertTrue(Double.isNaN(empty.getAverageConfidence()));
        assertTrue(Double.isNaN(empty.getAverageLatencyMs()));
        assertNull(empty.getLastObservation());
        session.observe(token, frame(0, "bracket", ORB, true), QUAD);
        assertEquals(0, empty.getAnalyzedFrames());
        assertTrue(empty.getWrongComponents().isEmpty());
        assertNotNull(session.getSnapshot("motor").getLastObservation());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void snapshotConfusionMapCannotBeModified() throws Exception {
        PartRecognitionTestSession session = new PartRecognitionTestSession();
        long token = session.selectPart("motor", "Motor", .001f);
        session.observe(token, frame(0, "bracket", ORB, true), QUAD);
        session.getSnapshot("motor").getWrongComponents().put("motor", 100L);
    }

    @Test
    public void csvEscapesLabelsNeutralizesFormulasAndLeavesUnknownNumbersEmpty() throws Exception {
        PartRecognitionTestSession session = new PartRecognitionTestSession();
        long token = session.selectPart("motor", "Motor, \"A\"", .001f);
        session.observe(token, frame(0, "bracket", ORB, true), QUAD);
        session.selectPart("=formula", "  +formula", .001f);
        String csv = session.toCsv();
        assertTrue(csv.startsWith("component_id,display_name,analyzed_frames,"));
        assertTrue(csv.contains("\"motor\",\"Motor, \"\"A\"\"\",1,0,1,0,0.0,"));
        assertTrue(csv.contains("\"bracket:1\"\r\n"));
        assertTrue(csv.contains("\"'=formula\",\"'  +formula\",0,0,0,0,,"));
        assertFalse(csv.contains("NaN"));
        assertFalse(csv.contains("Infinity"));
        assertEquals(3, csv.split("\r\n").length);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidPartAreaIsRejectedBeforeStartingSampling() {
        new PartRecognitionTestSession().selectPart("motor", "Motor", Float.NaN);
    }

    @Test
    public void exportKeepsContextualReferenceBasisBesideItsMeasurements() {
        PartRecognitionTestSession session = new PartRecognitionTestSession();
        session.selectPart("screw", "Screw", .00003f);
        String csv = session.toCsv(java.util.Collections.singletonMap("screw", "legacy_contextual_baseline"));
        assertTrue(csv.contains("reference_basis,wrong_component_counts"));
        assertTrue(csv.contains("\"legacy_contextual_baseline\",\"\""));
    }

    /** Package-private detector builder remains internal to vision; tests only use reflection. */
    private static RecognitionResult frame(long timestampMs, String component,
                                            RecognitionResult.DetectionMethod method,
                                            boolean withPose) throws Exception {
        Method factory = RecognitionResult.class.getDeclaredMethod("builder", long.class);
        factory.setAccessible(true);
        Object builder = factory.invoke(null, timestampMs * MS);
        invoke(builder, "identity", new Class<?>[]{String.class, String.class, String.class},
                component == null ? null : component + "_front", component, component);
        invoke(builder, "method", new Class<?>[]{RecognitionResult.DetectionMethod.class}, method);
        // Spatial observations own their outline; passing a preview quad to observe() cannot
        // promote a descriptor-only identity into a physical localization.
        invoke(builder, "corners", new Class<?>[]{java.util.List.class}, java.util.Arrays.asList(
                new dev.chyken.assembl_ar.vision.VisionPoint(25, 25),
                new dev.chyken.assembl_ar.vision.VisionPoint(75, 25),
                new dev.chyken.assembl_ar.vision.VisionPoint(75, 65),
                new dev.chyken.assembl_ar.vision.VisionPoint(25, 65)));
        invoke(builder, "matches", new Class<?>[]{int.class, int.class, int.class, double.class},
                50, 12, 9, .75);
        invoke(builder, "confidence", new Class<?>[]{double.class}, .75);
        invoke(builder, "latency", new Class<?>[]{RecognitionResult.Latency.class},
                new RecognitionResult.Latency(2, 3, 4, 5, 15));
        if (withPose) {
            invoke(builder, "pose", new Class<?>[]{RecognitionResult.Pose.class},
                    new RecognitionResult.Pose(new double[]{0, 0, 0}, new double[]{0, 0, 500},
                            new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 500, 1}));
        }
        return (RecognitionResult) invoke(builder, "build", new Class<?>[0]);
    }

    private static Object invoke(Object builder, String name, Class<?>[] parameters,
                                 Object... values) throws Exception {
        Method method = builder.getClass().getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(builder, values);
    }
}
