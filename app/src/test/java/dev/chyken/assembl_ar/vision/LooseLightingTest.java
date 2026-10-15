package dev.chyken.assembl_ar.vision;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Pure exposure policy checks; these require no OpenCV native library or camera. */
public final class LooseLightingTest {
    @Test public void dimMatWinsOverSmallFlashlightHotspotAndBrightColoredPart() {
        Frame frame = new Frame(100);
        for (int p = 0; p < 20; p++) frame.neutral(p, 80);
        for (int p = 20; p < 23; p++) frame.neutral(p, 255);
        // The remaining 77 pixels belong to a bright saturated component.
        double background = LooseLighting.neutralMedian(frame.gray, frame.hsv);
        assertEquals(80, background, 0);
        assertEquals(2.5, LooseLighting.exposureGain(background), 0);
    }

    @Test public void evenNeutralPopulationAveragesTheMiddlePair() {
        Frame frame = new Frame(10);
        frame.neutral(0, 80);
        frame.neutral(1, 101);
        assertEquals(90.5, LooseLighting.neutralMedian(frame.gray, frame.hsv), 0);
    }

    @Test public void sparseNeutralPatchCannotDetermineWholeFrameExposure() {
        Frame frame = new Frame(100);
        for (int p = 0; p < 9; p++) frame.neutral(p, 100);
        assertTrue(Double.isNaN(LooseLighting.neutralMedian(frame.gray, frame.hsv)));
        frame.neutral(9, 100);
        assertEquals(100, LooseLighting.neutralMedian(frame.gray, frame.hsv), 0);
    }

    @Test public void blackPartPixelsCannotSupplyMissingNeutralBackground() {
        Frame frame = new Frame(20);
        for (int p = 0; p < 20; p++) frame.neutral(p, 30);
        double background = LooseLighting.neutralMedian(frame.gray, frame.hsv);
        assertTrue(Double.isNaN(background));
        assertEquals(1, LooseLighting.exposureGain(background), 0);
    }

    @Test public void exposureLiftIsBoundedAndNeverDimsBrightFrames() {
        assertEquals(2, LooseLighting.exposureGain(100), 0);
        assertEquals(3, LooseLighting.exposureGain(20), 0);
        assertEquals(1, LooseLighting.exposureGain(200), 0);
        assertEquals(1, LooseLighting.exposureGain(245), 0);
    }

    @Test public void missingOrInvalidBackgroundLeavesExposureUnchanged() {
        Frame coloredPartOnly = new Frame(20);
        double missing = LooseLighting.neutralMedian(coloredPartOnly.gray, coloredPartOnly.hsv);
        assertTrue(Double.isNaN(missing));
        assertEquals(1, LooseLighting.exposureGain(missing), 0);
        assertTrue(Double.isNaN(LooseLighting.neutralMedian(new byte[0], new byte[0])));
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 0, -1}) {
            assertEquals(1, LooseLighting.exposureGain(invalid), 0);
        }
    }

    private static final class Frame {
        final byte[] gray, hsv;

        Frame(int count) {
            gray = new byte[count];
            hsv = new byte[count * 3];
            for (int p = 0; p < count; p++) {
                gray[p] = (byte) 220;
                hsv[p * 3] = 30;
                hsv[p * 3 + 1] = (byte) 180;
                hsv[p * 3 + 2] = (byte) 240;
            }
        }

        void neutral(int pixel, int luminance) {
            gray[pixel] = (byte) luminance;
            hsv[pixel * 3] = 0;
            hsv[pixel * 3 + 1] = 0;
            hsv[pixel * 3 + 2] = (byte) luminance;
        }
    }
}
