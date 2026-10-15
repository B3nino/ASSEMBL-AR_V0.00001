package dev.chyken.assembl_ar.vision;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public final class RecognitionResultRejectionEvidenceTest {
    @Test
    public void rejectedCandidateMetricsRemainStructuredOnNoDetection() {
        RecognitionResult.RejectionEvidence evidence = new RecognitionResult.RejectionEvidence(
                RecognitionResult.RejectionReason.INSUFFICIENT_INLIERS_OR_RATIO,
                "motor-side", "gear_motor", "Gear motor", "INVERTED",
                317, 542, 18, 17, 6, 6.0 / 17.0, 12, 8, 0.45);

        RecognitionResult result = RecognitionResult.builder(42L)
                .method(RecognitionResult.DetectionMethod.NONE)
                .rejectionEvidence(evidence)
                .build();

        assertSame(evidence, result.getRejectionEvidence());
        assertEquals(RecognitionResult.RejectionReason.INSUFFICIENT_INLIERS_OR_RATIO,
                evidence.getReason());
        assertEquals("motor-side", evidence.getReferenceId());
        assertEquals("gear_motor", evidence.getComponentId());
        assertEquals("INVERTED", evidence.getPhotometricVariant());
        assertEquals(317, evidence.getFrameKeypointCount());
        assertEquals(542, evidence.getReferenceKeypointCount());
        assertEquals(18, evidence.getGoodMatchCount());
        assertEquals(17, evidence.getValidMatchCount());
        assertEquals(6, evidence.getInlierCount());
        assertEquals(6.0 / 17.0, evidence.getInlierRatio(), 1e-12);
        assertEquals(12, evidence.getRequiredGoodMatchCount());
        assertEquals(8, evidence.getRequiredInlierCount());
        assertEquals(0.45, evidence.getRequiredInlierRatio(), 0.0);
    }

    @Test
    public void acceptedDetectionCannotCarryRejectedCandidateEvidence() {
        RecognitionResult.RejectionEvidence evidence = new RecognitionResult.RejectionEvidence(
                RecognitionResult.RejectionReason.INSUFFICIENT_GOOD_MATCHES,
                "motor-side", "gear_motor", "Gear motor", "NORMALIZED",
                100, 200, 4, -1, -1, Double.NaN, 12, 8, 0.45);

        RecognitionResult result = RecognitionResult.builder(43L)
                .identity("motor-side", "gear_motor", "Gear motor")
                .method(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY)
                .rejectionEvidence(evidence)
                .build();

        assertNull(result.getRejectionEvidence());
    }

    @Test
    public void descriptorOnlyIdentityRetainsTheGeometryRejection() {
        RecognitionResult.RejectionEvidence evidence = new RecognitionResult.RejectionEvidence(
                RecognitionResult.RejectionReason.INVALID_PROJECTED_GEOMETRY,
                "motor-side", "gear_motor", "Gear motor", "NORMALIZED",
                100, 200, 9, 9, 5, 5.0 / 9.0, 6, 4, 0.45);

        RecognitionResult result = RecognitionResult.builder(44L)
                .identity("motor-side", "gear_motor", "Gear motor")
                .method(RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY)
                .rejectionEvidence(evidence)
                .build();

        assertSame(evidence, result.getRejectionEvidence());
    }
}
