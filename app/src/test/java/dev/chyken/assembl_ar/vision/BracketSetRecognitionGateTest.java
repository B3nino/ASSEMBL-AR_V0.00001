package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.workflow.BracketSetRecognitionGate;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class BracketSetRecognitionGateTest {
    @Test public void fourDistinctPiecesInEachFrameCompleteTheSetAutomatically() {
        BracketSetRecognitionGate gate = new BracketSetRecognitionGate();
        for (int frame = 1; frame <= 3; frame++) assertFalse(gate.observe(set(frame, 4, 0)));
        assertTrue(gate.observe(set(4, 4, 0)));
        assertTrue(gate.isReady());
        assertEquals(4, gate.getVisibleCount());
        gate.reset();
        assertFalse(gate.isReady());
        assertEquals(0, gate.getVisibleCount());
    }

    @Test public void oneMovedPieceNeverAccumulatesFourAndThreePiecesRemainIncomplete() {
        BracketSetRecognitionGate gate = new BracketSetRecognitionGate();
        for (int frame = 1; frame <= 20; frame++) {
            assertFalse(gate.observe(set(frame, 1, frame * 80)));
            assertEquals(1, gate.getVisibleCount());
        }
        for (int frame = 21; frame <= 40; frame++) assertFalse(gate.observe(set(frame, 3, 0)));
        assertFalse(gate.isReady());
    }

    @Test public void duplicateReferenceViewsOfOneFootprintCountOnlyOnce() {
        List<RecognitionResult.InstanceObservation> duplicates = Arrays.asList(
                instance(0, .90), instance(1, .91), instance(2, .89), instance(3, .93));
        RecognitionResult result = result(1, "motor_bracket", duplicates,
                RecognitionResult.ValidationState.EXPECTED_COMPONENT);
        assertEquals(1, result.getDetectedInstanceCount("motor_bracket"));
        assertFalse(new BracketSetRecognitionGate(4, 1).observe(result));
    }

    @Test public void wrongIdentityAndLowConfidenceCannotSupplyQuantity() {
        RecognitionResult wrong = result(1, "gear_motor_6v", Arrays.asList(
                instance(0, .9), instance(80, .9), instance(160, .9), instance(240, .9)),
                RecognitionResult.ValidationState.COMPONENT_MISMATCH);
        assertEquals(0, wrong.getDetectedInstanceCount("motor_bracket"));
        RecognitionResult uncertain = result(2, "motor_bracket", Arrays.asList(
                instance(0, .9), instance(80, .9), instance(160, .9), instance(240, .5)),
                RecognitionResult.ValidationState.EXPECTED_COMPONENT);
        assertEquals(3, uncertain.getDetectedInstanceCount("motor_bracket"));
    }

    @Test public void staleFramesDropoutsAndLongGapsCannotCompleteTheStableSet() {
        BracketSetRecognitionGate gate = new BracketSetRecognitionGate(4, 2);
        RecognitionResult first = set(1, 4, 0);
        assertFalse(gate.observe(first));
        assertFalse(gate.observe(first));
        assertFalse(gate.observe(set(2, 3, 0)));
        assertFalse(gate.observe(set(3, 4, 0)));
        assertFalse(gate.observe(set(2_000_000_004L, 4, 0)));
        assertTrue(gate.observe(set(2_000_000_005L, 4, 0)));
        assertFalse(gate.observe(set(2_000_000_006L, 1, 0)));
        assertFalse(gate.isReady());
    }

    @Test public void completeSetSurvivesSlowButContinuousDeviceAnalysis() {
        BracketSetRecognitionGate gate = new BracketSetRecognitionGate();
        long frameGap = 1_200_000_000L;
        for (int frame = 1; frame <= 3; frame++) assertFalse(gate.observe(set(frame * frameGap, 4, 0)));
        assertTrue("Four consecutive complete frames 1.2 s apart must finish the scan",
                gate.observe(set(4 * frameGap, 4, 0)));
    }

    @Test public void partialOrbSetMayUseShapeButWrongPartRemainsRejected() {
        RecognitionRequest request = new RecognitionRequest(
                RecognitionRequest.AssemblyExpectation.component("motor_bracket"),
                null, null, null, 4, 2);
        assertTrue(LoosePartShapeRecognizer.supports(request));
        assertTrue(LoosePartShapeRecognizer.shouldAttempt(request, set(1, 2, 0)));
        assertFalse(LoosePartShapeRecognizer.shouldAttempt(request, set(1, 4, 0)));
        assertFalse(LoosePartShapeRecognizer.shouldAttempt(request, result(1, "gear_motor_6v",
                new ArrayList<>(), RecognitionResult.ValidationState.COMPONENT_MISMATCH)));
    }

    @Test public void degenerateFootprintsAndDescriptorOnlyIdentityHaveNoQuantityEvidence() {
        List<VisionPoint> line = Arrays.asList(new VisionPoint(0, 0), new VisionPoint(40, 0),
                new VisionPoint(40, 0), new VisionPoint(0, 0));
        RecognitionResult.InstanceObservation invalid = new RecognitionResult.InstanceObservation(
                "test-ref", "motor_bracket", RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY, line, .9);
        assertEquals(0, result(1, "motor_bracket", Arrays.asList(invalid),
                RecognitionResult.ValidationState.EXPECTED_COMPONENT)
                .getDetectedInstanceCount("motor_bracket"));
        RecognitionResult descriptor = RecognitionResult.builder(2)
                .identity("test-ref", "motor_bracket", "Bracket")
                .method(RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY)
                .validation(RecognitionResult.ValidationState.EXPECTED_COMPONENT)
                .instances(Arrays.asList(instance(0, .9), instance(80, .9),
                        instance(160, .9), instance(240, .9))).build();
        assertEquals(0, descriptor.getDetectedInstanceCount("motor_bracket"));
    }

    private static RecognitionResult set(long timestamp, int count, double movement) {
        List<RecognitionResult.InstanceObservation> observations = new ArrayList<>();
        for (int index = 0; index < count; index++) observations.add(instance(index * 80 + movement, .90));
        return result(timestamp, "motor_bracket", observations,
                RecognitionResult.ValidationState.EXPECTED_COMPONENT);
    }

    private static RecognitionResult result(long timestamp, String identity,
                                             List<RecognitionResult.InstanceObservation> observations,
                                             RecognitionResult.ValidationState state) {
        return RecognitionResult.builder(timestamp).identity("test-ref", identity, identity)
                .method(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY).validation(state)
                .instances(observations).build();
    }

    private static RecognitionResult.InstanceObservation instance(double x, double confidence) {
        return new RecognitionResult.InstanceObservation("test-ref", "motor_bracket",
                RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,
                Arrays.asList(new VisionPoint(x, 10), new VisionPoint(x + 40, 10),
                        new VisionPoint(x + 40, 70), new VisionPoint(x, 70)), confidence);
    }
}
