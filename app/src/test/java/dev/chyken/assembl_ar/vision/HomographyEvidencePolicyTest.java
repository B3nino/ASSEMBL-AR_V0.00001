package dev.chyken.assembl_ar.vision;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HomographyEvidencePolicyTest {
    private final VisionConfig config = VisionConfig.defaults();

    @Test
    public void acceptsTheNormalConfiguredInlierRatio() {
        assertTrue(ComponentRecognizer.hasSufficientHomographyEvidence(20, 9, config));
    }

    @Test
    public void acceptsStrongConsensusForRepetitivePlanarTexture() {
        // The held-out white PCB produces many ambiguous hole-pattern matches, while 35 of them
        // still agree on one valid transform. That absolute consensus is much stronger than the
        // ordinary eight-inlier minimum.
        assertTrue(ComponentRecognizer.hasSufficientHomographyEvidence(86, 35, config));
    }

    @Test
    public void doesNotRelaxSmallOrWeakConsensus() {
        assertFalse(ComponentRecognizer.hasSufficientHomographyEvidence(50, 20, config));
        assertFalse(ComponentRecognizer.hasSufficientHomographyEvidence(100, 34, config));
        assertFalse(ComponentRecognizer.hasSufficientHomographyEvidence(20, 7, config));
    }

    @Test
    public void rejectsMalformedEvidenceCounts() {
        assertFalse(ComponentRecognizer.hasSufficientHomographyEvidence(0, 0, config));
        assertFalse(ComponentRecognizer.hasSufficientHomographyEvidence(8, 9, config));
        assertFalse(ComponentRecognizer.hasSufficientHomographyEvidence(12, -1, config));
        assertFalse(ComponentRecognizer.hasSufficientHomographyEvidence(12, 8, null));
    }
}
