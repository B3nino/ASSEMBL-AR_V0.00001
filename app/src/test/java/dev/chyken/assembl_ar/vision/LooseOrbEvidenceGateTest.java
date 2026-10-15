package dev.chyken.assembl_ar.vision;

import org.junit.Test;
import java.util.Arrays;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import static org.junit.Assert.*;

public final class LooseOrbEvidenceGateTest {
    @Test public void sparseDiagnosticHomographyCannotAcceptOrNameAnotherPart() {
        for (String expected : Arrays.asList(NeobotSeedData.MICROCONTROLLER,
                NeobotSeedData.MOTOR_DRIVER)) {
            RecognitionResult original = orb(10, 5, .5, pose(1));
            RecognitionResult result = LooseOrbEvidenceGate.validate(request(expected, null), original);
            assertNull(result.getComponentId());
            assertEquals(RecognitionResult.ValidationState.NO_COMPONENT, result.getValidationState());
            assertFalse(result.hasLocatedComponent());
            assertSame(original.getLatency(), result.getLatency());
            assertEquals(10, result.getRejectionEvidence().getGoodMatchCount());
            assertEquals(12, result.getRejectionEvidence().getRequiredGoodMatchCount());
        }
    }

    @Test public void locatedStrongHomographyKeepsItsNamedMismatch() {
        RecognitionResult result = orb(18, 14, 14.0 / 18, pose(2));
        assertSame(result, LooseOrbEvidenceGate.validate(request(NeobotSeedData.MICROCONTROLLER, null), result));
        assertEquals(RecognitionResult.ValidationState.COMPONENT_MISMATCH, result.getValidationState());
    }

    @Test public void missingUnmeasuredAndBadPosesCannotClaimALooseIdentity() {
        for (RecognitionResult.Pose pose : Arrays.asList(null, pose(Double.NaN), pose(6.01))) {
            RecognitionResult result = LooseOrbEvidenceGate.validate(request(NeobotSeedData.MICROCONTROLLER, null),
                    orb(18, 14, 14.0 / 18, pose));
            assertNull(result.getComponentId());
            assertEquals(RecognitionResult.RejectionReason.INVALID_PROJECTED_GEOMETRY,
                    result.getRejectionEvidence().getReason());
        }
    }

    @Test public void installedAndOrientationRequestsRetainTheirExistingEvidencePath() {
        RecognitionResult result = orb(10, 5, .5, pose(1));
        assertSame(result, LooseOrbEvidenceGate.validate(request(NeobotSeedData.MICROCONTROLLER,
                NeobotSeedData.MAIN_BOARD), result));
        RecognitionRequest oriented = new RecognitionRequest(new RecognitionRequest.AssemblyExpectation(
                NeobotSeedData.MICROCONTROLLER, 0.0, 10.0, null), null, null);
        assertSame(result, LooseOrbEvidenceGate.validate(oriented, result));
    }

    private static RecognitionRequest request(String expected, String anchor) {
        return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(expected),
                null, null, anchor);
    }
    private static RecognitionResult.Pose pose(double error) {
        return new RecognitionResult.Pose(new double[]{0, 0, 0}, new double[]{0, 0, 300},
                new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 300, 1}, error);
    }
    private static RecognitionResult orb(int good, int inliers, double ratio, RecognitionResult.Pose pose) {
        return RecognitionResult.builder(1).identity("driver-ref", NeobotSeedData.MOTOR_DRIVER, "Driver")
                .method(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY)
                .validation(RecognitionResult.ValidationState.COMPONENT_MISMATCH)
                .corners(Arrays.asList(new VisionPoint(20, 20), new VisionPoint(120, 20),
                        new VisionPoint(120, 70), new VisionPoint(20, 70)))
                .matches(600, good, inliers, ratio).pose(pose).build();
    }
}
