package dev.chyken.assembl_ar.vision;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;

/** When the board-relative driver/Nano slot check may replace the whole-frame ORB result. */
public final class InstalledModuleInspectorPolicyTest {
    private static final List<VisionPoint> QUAD = Arrays.asList(new VisionPoint(10, 10),
            new VisionPoint(200, 10), new VisionPoint(200, 200), new VisionPoint(10, 200));
    private static final RecognitionResult.Pose POSE = new RecognitionResult.Pose(
            new double[]{0, 0, 0}, new double[]{0, 0, 300},
            new float[]{1, 0, 0, 0, 0, -1, 0, 0, 0, 0, -1, 0, 0, 0, -300, 1});
    private static final RecognitionResult.SpatialAnchor BOARD =
            new RecognitionResult.SpatialAnchor("board-ref", NeobotSeedData.MAIN_BOARD, QUAD, POSE);

    @Test public void onlyTheTwoFlatBlueModulesAreSupported() {
        assertTrue(InstalledModuleInspector.supports(NeobotSeedData.MOTOR_DRIVER));
        assertTrue(InstalledModuleInspector.supports(NeobotSeedData.MICROCONTROLLER));
        assertFalse(InstalledModuleInspector.supports(NeobotSeedData.MOTOR_BRACKET));
        assertFalse(InstalledModuleInspector.supports(NeobotSeedData.GEAR_MOTOR));
        assertFalse(InstalledModuleInspector.supports(NeobotSeedData.MAIN_BOARD));
    }

    @Test public void missingModuleBesideTheRequestedBoardIsInspected() {
        for (String id : Arrays.asList(NeobotSeedData.MOTOR_DRIVER, NeobotSeedData.MICROCONTROLLER)) {
            assertTrue(id, InstalledModuleInspector.shouldAttempt(request(id, 3),
                    observation(NeobotSeedData.MAIN_BOARD), BOARD));
            assertTrue(id, InstalledModuleInspector.shouldAttempt(request(id, 3),
                    RecognitionResult.builder(1L).build(), BOARD));
        }
    }

    @Test public void slotCheckNeedsTheLocalizedMainBoard() {
        RecognitionRequest request = request(NeobotSeedData.MOTOR_DRIVER, 3);
        RecognitionResult none = RecognitionResult.builder(1L).build();
        assertFalse(InstalledModuleInspector.shouldAttempt(request, none, null));
        assertFalse(InstalledModuleInspector.shouldAttempt(request, none,
                new RecognitionResult.SpatialAnchor("ext", NeobotSeedData.EXTENSION_BOARD, QUAD, POSE)));
        RecognitionRequest loose = new RecognitionRequest(request.getExpectation(), null, null);
        assertFalse(InstalledModuleInspector.shouldAttempt(loose, none, BOARD));
    }

    @Test public void anotherIdentifiedPartOrTheModuleItselfIsNeverReplaced() {
        RecognitionRequest request = request(NeobotSeedData.MICROCONTROLLER, 6);
        assertFalse(InstalledModuleInspector.shouldAttempt(request,
                observation(NeobotSeedData.MOTOR_DRIVER), BOARD));
        assertFalse(InstalledModuleInspector.shouldAttempt(request,
                observation(NeobotSeedData.MICROCONTROLLER), BOARD));
    }

    @Test public void ambiguousTexturedIdentityIsNotResolvedByColour() {
        RecognitionResult ambiguous = RecognitionResult.builder(1L).rejectionEvidence(
                new RecognitionResult.RejectionEvidence(
                        RecognitionResult.RejectionReason.IDENTITY_AMBIGUOUS, "reference",
                        NeobotSeedData.MOTOR_DRIVER, "Driver", "NORMALIZED",
                        200, 100, 30, 30, 25, .83, 10, 8, .45)).build();
        assertFalse(InstalledModuleInspector.shouldAttempt(
                request(NeobotSeedData.MOTOR_DRIVER, 3), ambiguous, BOARD));
    }

    private static RecognitionRequest request(String id, int step) {
        return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id),
                null, null, NeobotSeedData.MAIN_BOARD, 1, step);
    }

    private static RecognitionResult observation(String id) {
        return RecognitionResult.builder(1L).identity("part-ref", id, id)
                .method(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY)
                .corners(QUAD).pose(POSE).spatialAnchor(BOARD).build();
    }
}
