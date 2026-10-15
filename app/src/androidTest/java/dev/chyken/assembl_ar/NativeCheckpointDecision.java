package dev.chyken.assembl_ar;

import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.MotorAssemblyObservation;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate;
import dev.chyken.assembl_ar.workflow.GuidedCheckpoint;

/** The guided camera decision for one frame, before the activity's multi-frame evidence gates.
 * This baseline measures identity, board framing and authored placement/motor evidence. It does
 * not predict an end-to-end unlock (pose, tracking, timing and physical confirmation still apply).
 */
final class NativeCheckpointDecision {
    final boolean accepted;
    final String failureReason;
    final List<List<VisionPoint>> acceptedQuads;

    private NativeCheckpointDecision(boolean accepted, String failureReason,
                                     List<List<VisionPoint>> acceptedQuads) {
        this.accepted = accepted;
        this.failureReason = failureReason;
        this.acceptedQuads = acceptedQuads;
    }

    private static NativeCheckpointDecision fail(String reason) {
        return new NativeCheckpointDecision(false, reason, Collections.emptyList());
    }

    private static NativeCheckpointDecision accept(List<List<VisionPoint>> quads) {
        return new NativeCheckpointDecision(true, "", quads);
    }

    static NativeCheckpointDecision evaluate(RecognitionResult result, GuidedCheckpoint checkpoint,
                                             int width, int height) {
        if (checkpoint == null) throw new IllegalArgumentException("Active checkpoint required");
        if (checkpoint.getKind() == GuidedCheckpoint.Kind.IDENTITY) {
            // AssemblyActivity.handleGuidedCheckpointRecognition frames the identified part's own
            // outline (the board itself here); an identity request carries no separate anchor.
            if (!identified(result, checkpoint)) return fail("EXPECTED_COMPONENT_NOT_IDENTIFIED");
            String framing = boardFailure(result.getProjectedCorners(), width, height);
            return framing.isEmpty()
                    ? accept(Collections.singletonList(result.getProjectedCorners())) : fail(framing);
        }
        RecognitionResult.SpatialAnchor anchor = result.getSpatialAnchor();
        if (anchor == null || !NeobotSeedData.MAIN_BOARD.equals(anchor.getComponentId())) {
            return fail("MAIN_BOARD_ANCHOR_MISSING");
        }
        String framing = boardFailure(anchor.getProjectedCorners(), width, height);
        if (!framing.isEmpty()) return fail(framing);

        if (checkpoint.getMotorRequirement() != GuidedCheckpoint.MotorRequirement.NONE) {
            // AssemblyActivity.handleGuidedMotorPlacement uses motor evidence, not loose identity.
            MotorAssemblyObservation motors = result.getMotorAssembly();
            if (motors == null) return fail("MOTOR_OBSERVATION_MISSING");
            if (motors.getQuality() != MotorAssemblyObservation.Quality.GOOD) {
                return fail("MOTOR_QUALITY_" + motors.getQuality());
            }
            if (!motors.getLeft().isAccepted()) {
                return fail("LEFT_MOTOR_" + motors.getLeft().getState());
            }
            if (checkpoint.getMotorRequirement() == GuidedCheckpoint.MotorRequirement.BOTH
                    && !motors.getRight().isAccepted()) {
                return fail("RIGHT_MOTOR_" + motors.getRight().getState());
            }
            return accept(Collections.emptyList());
        }

        if (!identified(result, checkpoint)) return fail("EXPECTED_COMPONENT_NOT_IDENTIFIED");
        if (checkpoint.getKind() != GuidedCheckpoint.Kind.PLACEMENT) {
            throw new IllegalArgumentException("Camera checkpoint required");
        }
        List<List<VisionPoint>> observed =
                AssemblyActivity.expectedInstanceQuads(result, checkpoint.getComponentId());
        if (observed.isEmpty() && result.getProjectedCorners().size() == 4) {
            observed.add(result.getProjectedCorners());
        }
        // The old installed-bracket matrix required exactly one quad. Its slot gate requires
        // the same count, so evaluating the active checkpoint preserves that decision.
        ChassisRelativePlacementGate.Decision placement = checkpoint.getPlacementGate()
                .evaluate(anchor.getProjectedCorners(), observed);
        return placement.isAccepted() ? accept(observed)
                : fail("PLACEMENT_" + placement.getReason() + ": " + placement.getMessage());
    }

    private static boolean identified(RecognitionResult result, GuidedCheckpoint checkpoint) {
        return checkpoint.getComponentId().equals(result.getComponentId())
                && (result.getValidationState() == RecognitionResult.ValidationState.EXPECTED_COMPONENT
                || result.getValidationState() == RecognitionResult.ValidationState.RECOGNIZED);
    }

    /** VisualPlacementGate FRAME_MARGIN=2.5%, CENTER_MARGIN=10%; use real analysis dimensions. */
    private static String boardFailure(List<VisionPoint> corners, int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid frame dimensions");
        if (corners.size() != 4) return "BOARD_QUAD_MISSING";
        double centerX = 0.0, centerY = 0.0;
        for (VisionPoint corner : corners) {
            double x = corner.getX() / width, y = corner.getY() / height;
            if (x < 0.025 || x > 0.975 || y < 0.025 || y > 0.975) {
                return "BOARD_OUTSIDE_FRAME_MARGIN";
            }
            centerX += x / 4.0;
            centerY += y / 4.0;
        }
        return centerX >= 0.10 && centerX <= 0.90 && centerY >= 0.10 && centerY <= 0.90
                ? "" : "BOARD_OUTSIDE_CENTER_MARGIN";
    }
}
