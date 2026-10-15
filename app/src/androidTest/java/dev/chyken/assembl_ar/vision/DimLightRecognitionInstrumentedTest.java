package dev.chyken.assembl_ar.vision;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** POOL-only iPhone still-photo native regressions; this is not a phone workflow measurement. */
@RunWith(AndroidJUnit4.class)
public final class DimLightRecognitionInstrumentedTest {
    private static final String BASE = "references/dim-light-20261008/";
    private static final String[] LOOSE_PARTS = {
            NeobotSeedData.MICROCONTROLLER, NeobotSeedData.DISTANCE_SENSOR,
            NeobotSeedData.LINE_TRACKER, NeobotSeedData.CASTER_WHEEL,
            NeobotSeedData.MOTOR_DRIVER, NeobotSeedData.MOTOR_BRACKET,
            NeobotSeedData.GEAR_MOTOR
    };

    @BeforeClass public static void loadOpenCv() {
        assertTrue(OpenCVLoader.initLocal());
    }

    @Test public void everyPoolFixtureHasOnlyItsExpectedLooseIdentityAndFinitePose() throws Exception {
        JSONObject provenance = new JSONObject(new String(readAsset(BASE + "provenance.json"),
                StandardCharsets.UTF_8));
        JSONArray fixtures = provenance.getJSONArray("fixtures");
        assertEquals("Five positive photos, board and six untracked component types", 12, fixtures.length());
        int positives = 0, negatives = 0, flashlightNegatives = 0, jpegBytes = 0;
        for (int index = 0; index < fixtures.length(); index++) {
            JSONObject fixture = fixtures.getJSONObject(index);
            String name = fixture.getString("file");
            assertEquals(name, "POOL", fixture.getString("split"));
            JSONArray expected = fixture.getJSONArray("expectedLooseCandidates");
            assertTrue(name, expected.length() <= 1);
            String target = expected.length() == 0 ? null : expected.getString(0);
            if (target != null) {
                assertEquals(name, fixture.getString("actual"), target);
                assertTrue(name, Arrays.asList(LOOSE_PARTS).contains(target));
            } else if ("flashlight".equals(fixture.getString("lighting"))) {
                flashlightNegatives++;
            }
            String framing = fixture.getString("framing");
            assertTrue(name, "640".equals(framing) || "720x500".equals(framing));
            double minimumArea = "640".equals(framing) ? .002 : .001;
            Mat rgb = decodeFixture(fixture), gray = new Mat();
            try {
                jpegBytes += fixture.getInt("bytes"); // decodeFixture verified this against the actual asset.
                Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
                CameraCalibration calibration = calibration(gray);
                if (target != null) assertRawPositive(name, target, rgb, calibration, minimumArea);
                for (String requested : LOOSE_PARTS) {
                    String label = name + " requested=" + requested;
                    RecognitionRequest request = new RecognitionRequest(
                            RecognitionRequest.AssemblyExpectation.component(requested), calibration,
                            null, null, 1, 0, minimumArea);
                    RecognitionResult primary = RecognitionResult.builder(100L + index).build();
                    // Fresh per request: an earlier fixture cannot supply smoothed geometry.
                    RecognitionResult result = new LoosePartShapeRecognizer().recognize(
                            gray, rgb, 100L + index, request, primary);
                    if (requested.equals(target)) {
                        assertEquals(label, requested, result.getComponentId());
                        assertEquals(label, RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK,
                                result.getDetectionMethod());
                        assertEquals(label, RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                                result.getValidationState());
                        assertCorners(label, result.getProjectedCorners(), rgb, minimumArea);
                        assertFinitePose(label, result.getPose());
                        assertEquals(label, 1, result.getDetectedInstanceCount(requested));
                        positives++;
                    } else {
                        // Includes every other recognizer on positives and all seven on negatives.
                        assertSame(label, primary, result);
                        negatives++;
                    }
                }
            } finally { gray.release(); rgb.release(); }
        }
        assertEquals("Nano, ultrasonic, tracker and two caster poses", 5, positives);
        assertEquals("Full 12-by-7 matrix minus the five target cells", 79, negatives);
        assertEquals("Board, OLED and OLED protector under point lighting", 3, flashlightNegatives);
        assertEquals("Manifest aggregate matches verified fixture bytes", provenance.getInt("jpegBytes"), jpegBytes);
        assertTrue("JPEG-only fixture budget", jpegBytes <= 300_000);
    }

    @Test public void nativeExposureNormalizationKeepsHotspotsLocalAndOwnsItsOutput() {
        Mat dim = new Mat(80, 100, CvType.CV_8UC3, Scalar.all(100));
        Mat bright = new Mat(80, 100, CvType.CV_8UC3, Scalar.all(220));
        Mat normalizedDim = null, normalizedBright = null;
        try {
            Imgproc.rectangle(dim, new Point(5, 5), new Point(14, 14), Scalar.all(255), -1);
            dim.put(40, 40, new byte[]{60, 30, 15});
            normalizedDim = LooseLighting.normalizeExposure(dim);
            assertArrayEquals("Small hotspot does not set the background exposure",
                    new double[]{200, 200, 200}, normalizedDim.get(0, 0), 0);
            assertArrayEquals("Specular clipping stays local",
                    new double[]{255, 255, 255}, normalizedDim.get(10, 10), 0);
            assertArrayEquals("Exposure scaling preserves unsaturated RGB proportions",
                    new double[]{120, 60, 30}, normalizedDim.get(40, 40), 0);
            assertArrayEquals("The supplied frame is not modified",
                    new double[]{100, 100, 100}, dim.get(0, 0), 0);

            normalizedBright = LooseLighting.normalizeExposure(bright);
            assertArrayEquals("Bright neutral frames keep gain one",
                    new double[]{220, 220, 220}, normalizedBright.get(0, 0), 0);
            bright.setTo(Scalar.all(0));
            assertArrayEquals("Gain-one output owns separate native storage",
                    new double[]{220, 220, 220}, normalizedBright.get(0, 0), 0);
        } finally {
            if (normalizedBright != null) normalizedBright.release();
            if (normalizedDim != null) normalizedDim.release();
            bright.release(); dim.release();
        }
    }

    private static void assertRawPositive(String label, String target, Mat rgb,
            CameraCalibration calibration, double minimumArea) {
        List<VisionPoint> corners;
        double widthMm, heightMm;
        if (NeobotSeedData.MICROCONTROLLER.equals(target)) {
            LooseNanoShapeRecognizer.Candidate candidate = LooseNanoShapeRecognizer.find(rgb, minimumArea);
            assertNotNull(label, candidate);
            corners = candidate.corners; widthMm = 45; heightMm = 18;
        } else if (NeobotSeedData.CASTER_WHEEL.equals(target)) {
            BallCasterShapeRecognizer.Candidate candidate = BallCasterShapeRecognizer.find(rgb, minimumArea);
            assertNotNull(label, candidate);
            corners = candidate.corners; widthMm = 50; heightMm = 25;
        } else {
            LooseSensorShapeRecognizer.Candidate candidate = NeobotSeedData.DISTANCE_SENSOR.equals(target)
                    ? LooseSensorShapeRecognizer.findUltrasonic(rgb, minimumArea)
                    : LooseSensorShapeRecognizer.findLineTracker(rgb, minimumArea);
            assertNotNull(label, candidate);
            corners = candidate.corners; widthMm = candidate.widthMm; heightMm = candidate.heightMm;
        }
        assertCorners(label, corners, rgb, minimumArea);
        assertFinitePose(label, LoosePartShapeRecognizer.estimatePose(corners, widthMm, heightMm, calibration));
    }

    private static void assertCorners(String label, List<VisionPoint> corners, Mat rgb, double minimumArea) {
        assertEquals(label, 4, corners.size());
        double twiceArea = 0;
        for (int index = 0; index < corners.size(); index++) {
            VisionPoint p = corners.get(index), next = corners.get((index + 1) % corners.size());
            assertTrue(label, Double.isFinite(p.getX()) && Double.isFinite(p.getY()));
            assertTrue(label, p.getX() >= 0 && p.getX() < rgb.cols()
                    && p.getY() >= 0 && p.getY() < rgb.rows());
            twiceArea += p.getX() * next.getY() - p.getY() * next.getX();
        }
        assertTrue(label, Math.abs(twiceArea) / (2 * rgb.total()) >= minimumArea);
    }

    private static void assertFinitePose(String label, RecognitionResult.Pose pose) {
        assertNotNull(label, pose);
        for (double value : pose.getRotationVector()) assertTrue(label, Double.isFinite(value));
        for (double value : pose.getTranslationVector()) assertTrue(label, Double.isFinite(value));
        for (float value : pose.getOpenGlModelViewMatrix()) assertTrue(label, Float.isFinite(value));
        assertTrue(label, pose.getTranslationVector()[2] > 0);
    }

    private static CameraCalibration calibration(Mat frame) {
        double focal = Math.max(frame.cols(), frame.rows()) * 1.15;
        return CameraCalibration.pinhole(focal, focal, frame.cols() * .5, frame.rows() * .5);
    }

    private static Mat decodeFixture(JSONObject fixture) throws Exception {
        String name = fixture.getString("file");
        byte[] bytes = readAsset(BASE + name);
        assertEquals(name, fixture.getInt("bytes"), bytes.length);
        assertEquals(name, fixture.getString("sha256"), sha256(bytes));
        JSONArray size = fixture.getJSONObject("transform").getJSONArray("outputSize");
        MatOfByte encoded = new MatOfByte(bytes);
        Mat bgr = null, rgb = new Mat();
        boolean returned = false;
        try {
            bgr = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
            assertTrue(name, !bgr.empty());
            Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
            assertEquals(name, size.getInt(0), rgb.cols());
            assertEquals(name, size.getInt(1), rgb.rows());
            returned = true;
            return rgb;
        } finally {
            if (!returned) rgb.release();
            if (bgr != null) bgr.release();
            encoded.release();
        }
    }

    private static byte[] readAsset(String path) throws Exception {
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream input = tests.getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder(64);
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(Character.forDigit((value & 255) >>> 4, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }
}
