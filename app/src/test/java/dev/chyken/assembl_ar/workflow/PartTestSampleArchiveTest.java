package dev.chyken.assembl_ar.workflow;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import dev.chyken.assembl_ar.vision.CameraCalibration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class PartTestSampleArchiveTest {
    @Test
    public void versionTwoRoundTripsGrayRgbCalibrationAndDiagnosticMetadata() throws Exception {
        byte[] png = pngHeader(1280, 720, (byte) 1);
        byte[] rgbPng = pngHeader(1280, 720, (byte) 2);
        CameraCalibration calibration = CameraCalibration.withSkew(
                910.0, 905.0, 0.25, 640.0, 360.0,
                0.01, -0.02, 0.001, -0.001, 0.003);
        PartTestSampleArchive source = new PartTestSampleArchive(
                "gear_motor_6v", 9001L, 1280, 720, 90,
                "reason=TOO_FEW_GOOD_MATCHES matches=7", png, rgbPng, calibration);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        source.writeTo(output);

        PartTestSampleArchive decoded = PartTestSampleArchive.readFrom(
                new ByteArrayInputStream(output.toByteArray()));
        assertEquals("gear_motor_6v", decoded.getComponentId());
        assertEquals(9001L, decoded.getFrameTimestampNanos());
        assertEquals(1280, decoded.getFrameWidth());
        assertEquals(720, decoded.getFrameHeight());
        assertEquals(90, decoded.getRotationDegrees());
        assertEquals("reason=TOO_FEW_GOOD_MATCHES matches=7", decoded.getRecordedOutcome());
        assertArrayEquals(png, decoded.getFramePng());
        assertTrue(decoded.hasFullColorReplay());
        assertArrayEquals(rgbPng, decoded.getRgbFramePng());
        CameraCalibration decodedCalibration = decoded.getCameraCalibration();
        assertNotNull(decodedCalibration);
        assertArrayEquals(calibration.getCameraMatrixRowMajor(),
                decodedCalibration.getCameraMatrixRowMajor(), 0.0);
        assertArrayEquals(calibration.getDistortionCoefficients(),
                decodedCalibration.getDistortionCoefficients(), 0.0);

        byte[] returned = decoded.getFramePng();
        returned[0] = 0;
        assertArrayEquals(png, decoded.getFramePng());
        byte[] returnedRgb = decoded.getRgbFramePng();
        returnedRgb[0] = 0;
        assertArrayEquals(rgbPng, decoded.getRgbFramePng());
    }

    @Test
    public void legacyVersionOneSampleRemainsReadableAsOrbOnly() throws Exception {
        byte[] png = pngHeader(640, 480, (byte) 3);
        PartTestSampleArchive source = new PartTestSampleArchive(
                "motor_bracket", 72L, 640, 480, 0, "legacy", png);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        source.writeTo(output);

        PartTestSampleArchive decoded = PartTestSampleArchive.readFrom(
                new ByteArrayInputStream(output.toByteArray()));
        assertEquals("motor_bracket", decoded.getComponentId());
        assertArrayEquals(png, decoded.getFramePng());
        assertFalse(decoded.hasFullColorReplay());
        assertEquals(0, decoded.getRgbFramePng().length);
        assertNull(decoded.getCameraCalibration());
    }

    @Test
    public void legacyHighResolutionSampleKeepsItsOriginalCompatibilityEnvelope() throws Exception {
        byte[] png = pngHeader(3840, 2160, (byte) 6);
        PartTestSampleArchive source = new PartTestSampleArchive(
                "motor_driver", 73L, 3840, 2160, 270, "legacy-high-resolution", png);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        source.writeTo(output);

        PartTestSampleArchive decoded = PartTestSampleArchive.readFrom(
                new ByteArrayInputStream(output.toByteArray()));
        assertEquals(3840, decoded.getFrameWidth());
        assertEquals(2160, decoded.getFrameHeight());
        assertArrayEquals(png, decoded.getFramePng());
        assertFalse(decoded.hasFullColorReplay());
    }

    @Test(expected = IOException.class)
    public void arbitraryFileIsRejected() throws Exception {
        PartTestSampleArchive.readFrom(new ByteArrayInputStream(new byte[]{1, 2, 3}));
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyFrameIsRejectedBeforeWriting() {
        new PartTestSampleArchive("motor", 1, 1, 1, 0, "", new byte[0]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rgbFrameWithoutCalibrationIsRejected() {
        new PartTestSampleArchive("motor", 1, 1, 1, 0, "",
                new byte[]{1}, new byte[]{2}, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void pngDimensionsMustMatchBoundedMetadataBeforeNativeDecode() {
        new PartTestSampleArchive("motor", 1, 640, 480, 0, "",
                pngHeader(4096, 4096, (byte) 4));
    }

    @Test(expected = IllegalArgumentException.class)
    public void oversizedFullColorAnalyzedFrameIsRejectedBeforeNativeDecode() {
        new PartTestSampleArchive("motor", 1, 3000, 1000, 0, "",
                pngHeader(3000, 1000, (byte) 5),
                pngHeader(3000, 1000, (byte) 7),
                CameraCalibration.pinhole(1000, 1000, 1500, 500));
    }

    private static byte[] pngHeader(int width, int height, byte marker) {
        byte[] png = new byte[25];
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, png, 0, signature.length);
        putInt(png, 8, 13);
        png[12] = 'I'; png[13] = 'H'; png[14] = 'D'; png[15] = 'R';
        putInt(png, 16, width);
        putInt(png, 20, height);
        png[24] = marker;
        return png;
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }
}
