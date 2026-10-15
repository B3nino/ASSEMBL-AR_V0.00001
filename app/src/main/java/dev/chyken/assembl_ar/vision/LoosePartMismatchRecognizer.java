package dev.chyken.assembl_ar.vision;

import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;

/** Named feedback from a part's own appearance evidence; never a placement acceptance. */
final class LoosePartMismatchRecognizer {
    private static final List<String> PARTS = Arrays.asList(NeobotSeedData.MICROCONTROLLER,
            NeobotSeedData.DISTANCE_SENSOR, NeobotSeedData.LINE_TRACKER,
            NeobotSeedData.MOTOR_DRIVER, NeobotSeedData.GEAR_MOTOR,
            NeobotSeedData.MOTOR_BRACKET, NeobotSeedData.CASTER_WHEEL);

    static boolean shouldAttempt(RecognitionRequest request, RecognitionResult primary) {
        return request != null && request.getExpectation() != null
                && request.getSpatialAnchorComponentId() == null
                && request.getExpectedInstanceCount() == 1
                && request.getExpectation().getOrientationDegrees() == null
                && request.getExpectation().getPolarityRule() == null
                && !request.requestsInstalledMotorInspection()
                && !LoosePartShapeRecognizer.hasUsableGeometry(primary)
                && (primary == null || !request.getExpectation().getComponentId().equals(
                primary.getComponentId()))
                && (primary == null || primary.getRejectionEvidence() == null
                || primary.getRejectionEvidence().getReason()
                != RecognitionResult.RejectionReason.IDENTITY_AMBIGUOUS);
    }

    static RecognitionResult recognize(Mat gray, Mat rgb, long timestamp,
                                        RecognitionRequest request, RecognitionResult primary) {
        if (!shouldAttempt(request, primary)) return primary;
        long started = System.nanoTime();
        List<RecognitionResult> evidence = new ArrayList<>();
        for (String id : PARTS) {
            RecognitionRequest probe = new RecognitionRequest(
                    RecognitionRequest.AssemblyExpectation.component(id),
                    request.getCameraCalibration(), null, null, 1, 0,
                    request.getMinimumCornerAreaRatioOverride());
            // Each probe is stateless: neither alternate identities nor their outlines are
            // carried across frames, and expected-part corner smoothing is left untouched.
            RecognitionResult found = new LoosePartShapeRecognizer().recognize(
                    gray, rgb, timestamp, probe, null);
            if (found != null && found.getComponentId() != null) {
                evidence.add(found);
                if (id.equals(request.getExpectation().getComponentId())) break;
            }
        }
        return select(request, primary, evidence, timestamp,
                (System.nanoTime() - started) / 1_000_000.0);
    }

    static RecognitionResult select(RecognitionRequest request, RecognitionResult primary,
                                     List<RecognitionResult> evidence, long timestamp,
                                     double extraMs) {
        if (!shouldAttempt(request, primary)) return primary;
        String expected = request.getExpectation().getComponentId();
        RecognitionResult other = null;
        for (RecognitionResult found : evidence) {
            if (found == null || !found.hasLocatedComponent()
                    || found.getDetectionMethod() != RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK
                    || request.getCameraCalibration() != null && found.getPose() == null) continue;
            // A visible expected part or two competing identities cannot name a unique wrong part.
            if (expected.equals(found.getComponentId())) return withLatency(primary, extraMs);
            if (other != null && !other.getComponentId().equals(found.getComponentId())) {
                return withLatency(primary, extraMs);
            }
            other = found;
        }
        if (other == null) return withLatency(primary, extraMs);
        RecognitionResult.Latency base = primary == null ? RecognitionResult.Latency.zero()
                : primary.getLatency();
        return RecognitionResult.builder(timestamp)
                .identity(other.getReferenceId(), other.getComponentId(), other.getDisplayName())
                .method(RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK)
                .validation(RecognitionResult.ValidationState.COMPONENT_MISMATCH)
                .confidence(other.getConfidence()).corners(other.getProjectedCorners())
                .pose(other.getPose()).instances(other.getInstanceObservations())
                .matches(primary == null ? 0 : primary.getFrameKeypointCount(), 0, 0, 0)
                .latency(add(base, extraMs)).build().withSceneDetectionsFrom(primary);
    }

    private static RecognitionResult withLatency(RecognitionResult primary, double extraMs) {
        return primary == null ? null : primary.withLatency(add(primary.getLatency(), extraMs));
    }

    private static RecognitionResult.Latency add(RecognitionResult.Latency base, double extraMs) {
        return new RecognitionResult.Latency(base.getFeatureExtractionMs(), base.getMatchingMs(),
                base.getSpatialValidationMs(), base.getCircularFallbackMs() + extraMs,
                base.getTotalMs() + extraMs);
    }
}
