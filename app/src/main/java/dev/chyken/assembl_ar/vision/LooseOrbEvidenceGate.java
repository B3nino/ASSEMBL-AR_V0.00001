package dev.chyken.assembl_ar.vision;

/** A standalone loose-part homography must meet production evidence and calibrated pose gates.
 * The diagnostic matcher still gathers weaker candidates for rejection telemetry. Neither a
 * sparse coincidental quad nor an unsolved pose can identify a physical loose part to a learner.
 */
final class LooseOrbEvidenceGate {
    private static final VisionConfig STRICT = VisionConfig.defaults();

    static boolean applies(RecognitionRequest request) {
        return LoosePartShapeRecognizer.supports(request)
                && request.getSpatialAnchorComponentId() == null
                && request.getExpectedInstanceCount() == 1
                && !request.requestsInstalledMotorInspection();
    }

    static RecognitionResult validate(RecognitionRequest request, RecognitionResult primary) {
        if (!applies(request) || primary == null
                || primary.getDetectionMethod() != RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY) {
            return primary;
        }
        boolean consensus = primary.getGoodMatchCount() >= STRICT.getMinimumGoodMatches()
                && ComponentRecognizer.hasSufficientHomographyEvidence(primary.getGoodMatchCount(),
                primary.getInlierCount(), STRICT);
        RecognitionResult.Pose pose = primary.getPose();
        boolean geometry = primary.hasLocatedComponent() && pose != null
                && pose.getTranslationVector()[2] > 0
                && Double.isFinite(pose.getReprojectionErrorPx())
                && pose.getReprojectionErrorPx() <= STRICT.getMaximumPoseReprojectionErrorPx();
        if (consensus && geometry) return primary;
        RecognitionResult.RejectionEvidence rejected = new RecognitionResult.RejectionEvidence(
                consensus ? RecognitionResult.RejectionReason.INVALID_PROJECTED_GEOMETRY
                        : RecognitionResult.RejectionReason.INSUFFICIENT_INLIERS_OR_RATIO,
                primary.getReferenceId(), primary.getComponentId(), primary.getDisplayName(), null,
                primary.getFrameKeypointCount(), 0, primary.getGoodMatchCount(),
                primary.getGoodMatchCount(), primary.getInlierCount(), primary.getInlierRatio(),
                STRICT.getMinimumGoodMatches(), STRICT.getMinimumInliers(),
                STRICT.getMinimumInlierRatio());
        return primary.withoutComponentClaim(rejected);
    }
}
