package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ShapeFallbackAmbiguityTest {
    @Test public void shapeCannotResolveAmbiguousTexturedIdentity() {
        RecognitionRequest request = new RecognitionRequest(
                RecognitionRequest.AssemblyExpectation.component(NeobotSeedData.GEAR_MOTOR),
                null, null);
        RecognitionResult ambiguous = RecognitionResult.builder(1L).rejectionEvidence(
                new RecognitionResult.RejectionEvidence(RecognitionResult.RejectionReason.IDENTITY_AMBIGUOUS,
                        "reference", NeobotSeedData.GEAR_MOTOR, "Motor", "NORMALIZED",
                        200, 100, 30, 30, 25, .83, 10, 8, .45)).build();
        assertFalse(LoosePartShapeRecognizer.shouldAttempt(request, ambiguous));
        RecognitionRequest anchored = assemblyRequest(NeobotSeedData.MOTOR_BRACKET);
        assertFalse(LoosePartShapeRecognizer.shouldAttempt(anchored, ambiguous));
        assertFalse(LoosePartShapeRecognizer.shouldAttempt(anchored, ambiguous, corners -> false));
    }

    @Test public void verifiedAssemblyAnchorDoesNotCompeteWithTheMissingPart() {
        RecognitionResult board = observation(NeobotSeedData.MAIN_BOARD, NeobotSeedData.MAIN_BOARD);
        for (String id : Arrays.asList(NeobotSeedData.MOTOR_BRACKET, NeobotSeedData.GEAR_MOTOR,
                NeobotSeedData.MOTOR_DRIVER, NeobotSeedData.CASTER_WHEEL)) {
            assertTrue(id, LoosePartShapeRecognizer.shouldAttempt(assemblyRequest(id), board));
        }
        assertTrue(LoosePartShapeRecognizer.shouldAttempt(
                assemblyRequest(NeobotSeedData.MOTOR_BRACKET), board, corners -> false));
    }

    @Test public void anchorExemptionRequiresAnIndependentValidWorkplane() {
        RecognitionRequest request = assemblyRequest(NeobotSeedData.MOTOR_BRACKET);
        assertFalse(LoosePartShapeRecognizer.shouldAttempt(request,
                observation(NeobotSeedData.MAIN_BOARD, null)));
        assertFalse(LoosePartShapeRecognizer.shouldAttempt(request,
                observation(NeobotSeedData.MAIN_BOARD, NeobotSeedData.EXTENSION_BOARD)));
        RecognitionRequest loose = new RecognitionRequest(request.getExpectation(), null, null);
        assertFalse(LoosePartShapeRecognizer.shouldAttempt(loose,
                observation(NeobotSeedData.MAIN_BOARD, NeobotSeedData.MAIN_BOARD)));
    }

    @Test public void actualConflictingPartCannotBeReplacedEvenBesideTheRequestedBoard() {
        RecognitionRequest request = assemblyRequest(NeobotSeedData.MOTOR_BRACKET);
        RecognitionResult differentPart = observation(NeobotSeedData.GEAR_MOTOR, NeobotSeedData.MAIN_BOARD);
        assertFalse(LoosePartShapeRecognizer.shouldAttempt(request, differentPart));
        assertFalse(LoosePartShapeRecognizer.shouldAttempt(request, differentPart, corners -> false));
    }

    @Test public void expectedPartGeometryIsRetainedExceptForAnotherBracketSlot() {
        RecognitionRequest request = assemblyRequest(NeobotSeedData.MOTOR_BRACKET);
        RecognitionResult bracket = observation(NeobotSeedData.MOTOR_BRACKET, NeobotSeedData.MAIN_BOARD);
        assertFalse(LoosePartShapeRecognizer.shouldAttempt(request, bracket));
        assertFalse(LoosePartShapeRecognizer.shouldAttempt(request, bracket, corners -> true));
        assertTrue(LoosePartShapeRecognizer.shouldAttempt(request, bracket, corners -> false));
    }

    private static RecognitionRequest assemblyRequest(String id) {
        return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id),
                null, null, NeobotSeedData.MAIN_BOARD, 1, 2);
    }

    private static RecognitionResult observation(String id, String anchorId) {
        List<VisionPoint> corners = Arrays.asList(new VisionPoint(10, 10), new VisionPoint(200, 10),
                new VisionPoint(200, 200), new VisionPoint(10, 200));
        RecognitionResult.Pose pose = new RecognitionResult.Pose(new double[]{0, 0, 0},
                new double[]{0, 0, 300},
                new float[]{1, 0, 0, 0, 0, -1, 0, 0, 0, 0, -1, 0, 0, 0, -300, 1});
        return RecognitionResult.builder(1).identity("part-ref", id, id)
                .method(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY)
                .corners(corners).pose(pose)
                .spatialAnchor(anchorId == null ? null : new RecognitionResult.SpatialAnchor(
                        "anchor-ref", anchorId, corners, pose)).build();
    }
}
