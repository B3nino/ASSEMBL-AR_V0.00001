package dev.chyken.assembl_ar.camera;

import android.graphics.Rect;
import android.util.Size;
import android.util.SizeF;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import dev.chyken.assembl_ar.vision.AssemblyVisionAnalyzer;
import dev.chyken.assembl_ar.vision.CameraCalibration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** Verifies Camera2 intrinsics through CameraX sensor crop and analyzer rotation coordinates. */
@RunWith(AndroidJUnit4.class)
public final class CameraCalibrationInstrumentedTest {
    private static final float[] SENSOR_TO_BUFFER = {
            0.5f, 0f, -50f,
            0f, 0.5f, -25f,
            0f, 0f, 1f
    };

    @Test
    public void intrinsicMatrixUsesPreCorrectionOriginSensorTransformAndCrop() {
        CameraCalibrationProvider provider = provider();
        CameraCalibration calibration = provider.forFrame(metadata(
                1800, 1400, 0, false));

        assertArrayEquals(new double[]{
                600.0, 0.0, 900.0,
                0.0, 590.0, 700.0,
                0.0, 0.0, 1.0
        }, calibration.getCameraMatrixRowMajor(), 0.001);
        assertEquals(CameraCalibration.Source.CAMERA2_INTRINSICS, calibration.getSource());
        assertEquals(calibration.getSource(), provider.getLastSource());
    }

    @Test
    public void clockwiseAnalyzerRotationSwapsAxesAndRotatesPrincipalPoint() {
        CameraCalibration calibration = provider().forFrame(metadata(
                1400, 1800, 90, true));

        assertArrayEquals(new double[]{
                590.0, 0.0, 699.0,
                0.0, 600.0, 900.0,
                0.0, 0.0, 1.0
        }, calibration.getCameraMatrixRowMajor(), 0.001);
    }

    @Test
    public void halfTurnRotatesPrincipalPointWithoutChangingFocalAxes() {
        CameraCalibration calibration = provider().forFrame(metadata(
                1800, 1400, 180, true));

        assertArrayEquals(new double[]{
                600.0, 0.0, 899.0,
                0.0, 590.0, 699.0,
                0.0, 0.0, 1.0
        }, calibration.getCameraMatrixRowMajor(), 0.001);
    }

    @Test
    public void counterClockwiseAnalyzerRotationSwapsAxesAndRotatesPrincipalPoint() {
        CameraCalibration calibration = provider().forFrame(metadata(
                1400, 1800, 270, true));

        assertArrayEquals(new double[]{
                590.0, 0.0, 700.0,
                0.0, 600.0, 899.0,
                0.0, 0.0, 1.0
        }, calibration.getCameraMatrixRowMajor(), 0.001);
    }

    @Test
    public void camera2SkewSurvivesSensorToBufferScaling() {
        CameraCalibrationProvider skewed = CameraCalibrationProvider.fromCharacteristics(
                new float[]{1200f, 1180f, 2000f, 1500f, 20f},
                new Rect(100, 50, 4100, 3050),
                new Rect(100, 50, 4100, 3050),
                null, null, null);

        CameraCalibration calibration = skewed.forFrame(metadata(
                1800, 1400, 0, false));

        assertArrayEquals(new double[]{
                600.0, 10.0, 900.0,
                0.0, 590.0, 700.0,
                0.0, 0.0, 1.0
        }, calibration.getCameraMatrixRowMajor(), 0.001);
    }

    @Test
    public void focalAndSensorMetadataSourceIsAttachedToTheReturnedFrame() {
        CameraCalibrationProvider provider = CameraCalibrationProvider.fromCharacteristics(
                null, new Rect(100, 50, 4100, 3050), null,
                4.0f, new SizeF(8.0f, 6.0f), new Size(4000, 3000));
        assertEquals(CameraCalibration.Source.UNSPECIFIED, provider.getLastSource());

        CameraCalibration calibration = provider.forFrame(metadata(1800, 1400, 0, false));

        assertArrayEquals(new double[]{
                1000.0, 0.0, 900.0,
                0.0, 1000.0, 700.0,
                0.0, 0.0, 1.0
        }, calibration.getCameraMatrixRowMajor(), 0.001);
        assertEquals(CameraCalibration.Source.FOCAL_SENSOR_METADATA, calibration.getSource());
        assertEquals(calibration.getSource(), provider.getLastSource());
    }

    @Test
    public void missingMetadataAndInvalidTransformedMatrixHaveDistinctFallbackSources() {
        CameraCalibrationProvider missing = CameraCalibrationProvider.approximate();
        CameraCalibration noMetadata = missing.forFrame(metadata(1800, 1400, 0, false));
        assertArrayEquals(new double[]{
                2124.0, 0.0, 900.0,
                0.0, 2124.0, 700.0,
                0.0, 0.0, 1.0
        }, noMetadata.getCameraMatrixRowMajor(), 0.001);
        assertEquals(CameraCalibration.Source.APPROXIMATE_MISSING_METADATA,
                noMetadata.getSource());
        assertEquals(noMetadata.getSource(), missing.getLastSource());

        CameraCalibrationProvider provider = provider();
        float[] reflectedTransform = {
                -0.5f, 0f, 2000f,
                0f, 0.5f, -25f,
                0f, 0f, 1f
        };
        CameraCalibration invalid = provider.forFrame(metadata(
                1800, 1400, 0, false, reflectedTransform));
        assertEquals(CameraCalibration.Source.APPROXIMATE_INVALID_TRANSFORM,
                invalid.getSource());
        assertEquals(invalid.getSource(), provider.getLastSource());
        assertArrayEquals(noMetadata.getCameraMatrixRowMajor(),
                invalid.getCameraMatrixRowMajor(), 0.001);

        CameraCalibration next = provider.forFrame(metadata(1800, 1400, 0, false));
        assertEquals(CameraCalibration.Source.CAMERA2_INTRINSICS, next.getSource());
        assertEquals(next.getSource(), provider.getLastSource());
        // Provenance belongs to each immutable calibration, even after a later frame arrives.
        assertEquals(CameraCalibration.Source.APPROXIMATE_INVALID_TRANSFORM,
                invalid.getSource());
        assertEquals(invalid.getSource(), invalid.scaled(.5, .5).cropped(5, 7).getSource());
    }

    private static CameraCalibrationProvider provider() {
        return CameraCalibrationProvider.fromCharacteristics(
                new float[]{1200f, 1180f, 2000f, 1500f, 0f},
                new Rect(100, 50, 4100, 3050),
                new Rect(100, 50, 4100, 3050),
                null, null, null);
    }

    private static AssemblyVisionAnalyzer.FrameMetadata metadata(
            int analyzedWidth, int analyzedHeight,
            int rotationDegrees, boolean rotated) {
        return metadata(analyzedWidth, analyzedHeight, rotationDegrees,
                rotated, SENSOR_TO_BUFFER);
    }

    private static AssemblyVisionAnalyzer.FrameMetadata metadata(
            int analyzedWidth, int analyzedHeight,
            int rotationDegrees, boolean rotated, float[] transform) {
        return new AssemblyVisionAnalyzer.FrameMetadata(
                1L,
                2000,
                1500,
                100,
                50,
                1800,
                1400,
                analyzedWidth,
                analyzedHeight,
                rotationDegrees,
                rotated,
                transform);
    }
}
