package dev.chyken.assembl_ar.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Verifies calibrated OpenGL registration against PreviewView's fill-center transform. */
public final class ArCameraProjectionTest {
    @Test
    public void fillCenterProjectionAppliesTheSameCenteredCropAsPreviewView() {
        ArCameraProjection projection = ArCameraProjection.pinhole(
                640, 480, 500.0, 510.0, 320.0, 240.0);

        float[] matrix = projection.createOpenGlMatrix(1080, 1920);

        assertEquals(2.0 * 2000.0 / 1080.0, matrix[0], 0.0001);
        assertEquals(2.0 * 2040.0 / 1920.0, matrix[5], 0.0001);
        assertEquals(0.0, matrix[8], 0.0001);
        assertEquals(0.0, matrix[9], 0.0001);
        assertEquals(-1.0, matrix[11], 0.0001);
    }

    @Test
    public void camera2SkewIsRepresentedInTheOpenGlProjection() {
        ArCameraProjection projection = ArCameraProjection.fromCameraMatrix(
                640, 480, new double[]{
                        500.0, 12.0, 320.0,
                        0.0, 510.0, 240.0,
                        0.0, 0.0, 1.0
                });

        float[] matrix = projection.createOpenGlMatrix(640, 480);

        assertEquals(-2.0 * 12.0 / 640.0, matrix[4], 0.0001);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidCameraMatrixIsRejected() {
        ArCameraProjection.fromCameraMatrix(640, 480, new double[]{
                500.0, 0.0, 320.0,
                1.0, 500.0, 240.0,
                0.0, 0.0, 1.0
        });
    }
}
