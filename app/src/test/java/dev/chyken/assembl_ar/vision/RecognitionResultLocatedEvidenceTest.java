package dev.chyken.assembl_ar.vision;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** The shared camera-result boundary cannot invent a located identity from diagnostics. */
public final class RecognitionResultLocatedEvidenceTest {
    private static final List<VisionPoint> QUAD = Arrays.asList(new VisionPoint(20, 20),
            new VisionPoint(120, 20), new VisionPoint(120, 70), new VisionPoint(20, 70));

    @Test public void descriptorIdentityNeverAcceptsOrClaimsWrongPartEvenWithInventedCorners() {
        for (RecognitionResult.ValidationState state : RecognitionResult.ValidationState.values()) {
            RecognitionResult result = result(RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY,
                    state, QUAD);
            assertFalse(result.hasLocatedComponent());
            assertEquals(RecognitionResult.ValidationState.NO_COMPONENT, result.getValidationState());
            assertEquals("driver", result.getComponentId()); // diagnostic identity is retained
            assertEquals(RecognitionResult.ValidationState.NO_COMPONENT,
                    result.withLatency(new RecognitionResult.Latency(1, 2, 3, 4, 10))
                            .getValidationState());
        }
    }

    @Test public void absentCollapsedConcaveAndCrossedOutlinesCannotClaimAnIdentity() {
        for (List<VisionPoint> corners : Arrays.asList(Collections.<VisionPoint>emptyList(),
                Arrays.asList(QUAD.get(0), QUAD.get(1), QUAD.get(2)),
                Arrays.asList(QUAD.get(0), QUAD.get(0), QUAD.get(2), QUAD.get(3)),
                Arrays.asList(QUAD.get(0), QUAD.get(2), QUAD.get(1), QUAD.get(3)),
                Arrays.asList(QUAD.get(0), QUAD.get(1), new VisionPoint(25, 25), QUAD.get(3)))) {
            for (RecognitionResult.DetectionMethod method : RecognitionResult.DetectionMethod.values()) {
                RecognitionResult result = result(method,
                        RecognitionResult.ValidationState.COMPONENT_MISMATCH, corners);
                assertFalse(result.hasLocatedComponent());
                assertEquals(RecognitionResult.ValidationState.NO_COMPONENT, result.getValidationState());
            }
        }
    }

    @Test public void locatedHomographyAndAppearanceKeepWrongPartFeedbackAndExpectedValidation() {
        for (RecognitionResult.DetectionMethod method : Arrays.asList(
                RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,
                RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK,
                RecognitionResult.DetectionMethod.MODEL_EDGE_MATCH,
                RecognitionResult.DetectionMethod.CANNY_HOUGH_FALLBACK)) {
            for (RecognitionResult.ValidationState state : Arrays.asList(
                    RecognitionResult.ValidationState.COMPONENT_MISMATCH,
                    RecognitionResult.ValidationState.EXPECTED_COMPONENT)) {
                RecognitionResult result = result(method, state, QUAD);
                assertTrue(result.hasLocatedComponent());
                assertEquals(state, result.getValidationState());
            }
        }
    }

    @Test public void unnamedCircularEvidenceCannotBecomeComponentFeedback() {
        RecognitionResult result = result(RecognitionResult.DetectionMethod.CANNY_HOUGH_FALLBACK,
                RecognitionResult.ValidationState.COMPONENT_MISMATCH, Collections.emptyList());
        assertFalse(result.hasLocatedComponent());
        assertEquals(RecognitionResult.ValidationState.NO_COMPONENT, result.getValidationState());
    }

    @Test public void countedSetOwnsLocatedInstanceOutlinesEvenWithoutAnAggregateQuad() {
        RecognitionResult.InstanceObservation located = new RecognitionResult.InstanceObservation(
                "driver-ref", "driver", RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY, QUAD, .9);
        RecognitionResult result = RecognitionResult.builder(1).identity("driver-ref", "driver", "Driver")
                .method(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY)
                .validation(RecognitionResult.ValidationState.EXPECTED_COMPONENT)
                .instances(Collections.singletonList(located)).build();
        assertTrue(result.hasLocatedComponent());
        assertEquals(1, result.getDetectedInstanceCount("driver"));
        assertEquals(RecognitionResult.ValidationState.EXPECTED_COMPONENT, result.getValidationState());
        RecognitionResult unrelated = RecognitionResult.builder(1).identity("other-ref", "other", "Other")
                .method(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY)
                .validation(RecognitionResult.ValidationState.COMPONENT_MISMATCH)
                .instances(Collections.singletonList(located)).build();
        assertFalse(unrelated.hasLocatedComponent());
        assertEquals(RecognitionResult.ValidationState.NO_COMPONENT, unrelated.getValidationState());
    }

    private static RecognitionResult result(RecognitionResult.DetectionMethod method,
                                           RecognitionResult.ValidationState state,
                                           List<VisionPoint> corners) {
        return RecognitionResult.builder(1).identity("driver-ref", "driver", "Driver")
                .method(method).validation(state).corners(corners).build();
    }
}
