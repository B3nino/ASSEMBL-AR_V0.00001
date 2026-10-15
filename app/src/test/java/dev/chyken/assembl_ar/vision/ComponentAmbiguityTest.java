package dev.chyken.assembl_ar.vision;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ComponentAmbiguityTest {
    @Test
    public void sharedBoardOutlineCompetesButMountedSmallModuleDoesNot() {
        List<VisionPoint> board = corners(0, 0, 130, 150);
        assertTrue(ComponentRecognizer.hasConflictingFootprint(
                board, corners(3, 2, 133, 152)));
        assertFalse(ComponentRecognizer.hasConflictingFootprint(
                board, corners(45, 40, 85, 75)));
        assertFalse(ComponentRecognizer.hasConflictingFootprint(
                board, corners(150, 0, 280, 150)));
    }

    @Test
    public void expectedIdentityNeedsMeaningfullyMoreIndependentEvidence() {
        assertFalse(ComponentRecognizer.hasDominantIdentityEvidence(80, 80));
        assertFalse(ComponentRecognizer.hasDominantIdentityEvidence(87, 80));
        assertFalse(ComponentRecognizer.hasDominantIdentityEvidence(18, 12));
        assertTrue(ComponentRecognizer.hasDominantIdentityEvidence(120, 80));
    }

    private static List<VisionPoint> corners(double left, double top, double right, double bottom) {
        return Arrays.asList(new VisionPoint(left, top), new VisionPoint(right, top),
                new VisionPoint(right, bottom), new VisionPoint(left, bottom));
    }
}
