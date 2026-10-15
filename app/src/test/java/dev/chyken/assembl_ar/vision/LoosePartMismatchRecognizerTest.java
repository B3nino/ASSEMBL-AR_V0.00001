package dev.chyken.assembl_ar.vision;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import dev.chyken.assembl_ar.data.NeobotSeedData;

import static org.junit.Assert.*;

public final class LoosePartMismatchRecognizerTest {
    @Test public void positiveOtherPartNamesMismatchWithoutAcceptingTheExpectedPart() {
        RecognitionRequest request = request(NeobotSeedData.MICROCONTROLLER, null);
        RecognitionResult primary = RecognitionResult.builder(1).latency(
                new RecognitionResult.Latency(12, 70, 5, 3, 90)).build();
        RecognitionResult result = LoosePartMismatchRecognizer.select(request, primary,
                Collections.singletonList(shape(NeobotSeedData.DISTANCE_SENSOR)), 1, 18);
        assertEquals(NeobotSeedData.DISTANCE_SENSOR, result.getComponentId());
        assertEquals(RecognitionResult.ValidationState.COMPONENT_MISMATCH, result.getValidationState());
        assertEquals(RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK, result.getDetectionMethod());
        assertEquals(108, result.getLatency().getTotalMs(), 0);
        assertEquals(70, result.getLatency().getMatchingMs(), 0);
    }

    @Test public void expectedPartOrTwoCompetingIdentitiesDoNotInventMismatch() {
        RecognitionRequest request = request(NeobotSeedData.MICROCONTROLLER, null);
        RecognitionResult primary = RecognitionResult.builder(1).build();
        for (java.util.List<RecognitionResult> evidence : Arrays.asList(
                Arrays.asList(shape(NeobotSeedData.DISTANCE_SENSOR), shape(NeobotSeedData.MICROCONTROLLER)),
                Arrays.asList(shape(NeobotSeedData.DISTANCE_SENSOR), shape(NeobotSeedData.LINE_TRACKER)))) {
            RecognitionResult result = LoosePartMismatchRecognizer.select(request, primary, evidence, 1, 9);
            assertNull(result.getComponentId());
            assertEquals(RecognitionResult.ValidationState.NO_COMPONENT, result.getValidationState());
            assertEquals(9, result.getLatency().getTotalMs(), 0);
        }
    }

    @Test public void strictAssemblyRequestsAndKnownExpectedIdentityAreNeverProbed() {
        RecognitionResult primary = RecognitionResult.builder(1).build();
        assertFalse(LoosePartMismatchRecognizer.shouldAttempt(
                request(NeobotSeedData.MICROCONTROLLER, NeobotSeedData.MAIN_BOARD), primary));
        RecognitionRequest oriented = new RecognitionRequest(new RecognitionRequest.AssemblyExpectation(
                NeobotSeedData.MICROCONTROLLER, 0.0, 10.0, null), null, null);
        assertFalse(LoosePartMismatchRecognizer.shouldAttempt(oriented, primary));
        assertFalse(LoosePartMismatchRecognizer.shouldAttempt(request(NeobotSeedData.MICROCONTROLLER, null),
                RecognitionResult.builder(1).identity("orb", NeobotSeedData.MICROCONTROLLER, "Nano")
                        .method(RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY).build()));
        RecognitionResult ambiguous = primary.withRejectionEvidence(new RecognitionResult.RejectionEvidence(
                RecognitionResult.RejectionReason.IDENTITY_AMBIGUOUS, "ref", NeobotSeedData.MICROCONTROLLER,
                "Nano", "NORMALIZED", 200, 100, 20, 20, 16, .8, 12, 8, .45));
        assertFalse(LoosePartMismatchRecognizer.shouldAttempt(request(NeobotSeedData.MICROCONTROLLER, null), ambiguous));
    }

    @Test public void newElectronicsFallbacksAreLooseOnly() {
        for (String id : Arrays.asList(NeobotSeedData.MICROCONTROLLER,
                NeobotSeedData.DISTANCE_SENSOR, NeobotSeedData.LINE_TRACKER)) {
            assertTrue(LoosePartShapeRecognizer.supports(request(id, null)));
            assertFalse(LoosePartShapeRecognizer.supports(request(id, NeobotSeedData.MAIN_BOARD)));
        }
    }

    private static RecognitionRequest request(String id, String anchor) {
        return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id), null, null, anchor);
    }

    private static RecognitionResult shape(String id) {
        return RecognitionResult.builder(1).identity("shape:" + id, id, id)
                .method(RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK)
                .validation(RecognitionResult.ValidationState.EXPECTED_COMPONENT)
                .corners(Arrays.asList(new VisionPoint(20, 20), new VisionPoint(120, 20),
                        new VisionPoint(120, 70), new VisionPoint(20, 70))).confidence(.9).build();
    }
}
