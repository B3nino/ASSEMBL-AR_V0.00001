package dev.chyken.assembl_ar.vision;

import org.junit.Test;
import org.opencv.core.DMatch;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public final class IndependentMatchEvidenceTest {
    @Test
    public void repeatedReferenceFeaturesCannotInflateOneObservedFeature() {
        DMatch weaker = new DMatch(0, 7, 32.0f);
        DMatch strongest = new DMatch(1, 7, 12.0f);
        DMatch independent = new DMatch(2, 9, 24.0f);
        List<DMatch> evidence = ComponentRecognizer.uniqueFrameMatches(
                Arrays.asList(weaker, strongest, independent, new DMatch(3, 7, 28.0f)));
        assertEquals(2, evidence.size());
        assertSame(strongest, evidence.get(0));
        assertSame(independent, evidence.get(1));
    }
}
