package dev.chyken.assembl_ar.vision;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;

import static org.junit.Assert.*;

/** Native parity for odd room-light POOL scans; it does not score held-out phone cards. */
@RunWith(AndroidJUnit4.class)
public final class RedmiRoomCasterInstrumentedTest {
    private static final String BASE = "references/redmi-room-20261008/";
    private static final String[] PARTS = {NeobotSeedData.MICROCONTROLLER, NeobotSeedData.DISTANCE_SENSOR,
            NeobotSeedData.LINE_TRACKER, NeobotSeedData.MOTOR_DRIVER, NeobotSeedData.MOTOR_BRACKET,
            NeobotSeedData.GEAR_MOTOR, NeobotSeedData.CASTER_WHEEL};

    @BeforeClass public static void loadOpenCv() { assertTrue(OpenCVLoader.initLocal()); }

    @Test public void fourPoolScansLocateOnlyTheCasterAndProduceFiniteCoarsePose() throws Exception {
        JSONObject provenance = provenance(); JSONArray fixtures = provenance.getJSONArray("fixtures");
        assertEquals(4, fixtures.length()); int bytes = 0, positives = 0, negatives = 0;
        for (int i = 0; i < fixtures.length(); i++) {
            JSONObject fixture = fixtures.getJSONObject(i); String name = fixture.getString("file");
            assertEquals("POOL", fixture.getString("split"));
            assertEquals(1, fixture.getInt("scan") % 2);
            Mat rgb = decode(fixture), gray = new Mat();
            try {
                bytes += fixture.getInt("bytes");
                BallCasterShapeRecognizer.Candidate candidate = BallCasterShapeRecognizer.find(rgb, .001);
                assertNotNull(name, candidate); assertCorners(name, candidate.corners, rgb);
                Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
                double focal = Math.max(rgb.cols(), rgb.rows()) * 1.15;
                CameraCalibration calibration = CameraCalibration.pinhole(focal, focal, rgb.cols()*.5, rgb.rows()*.5);
                for (String part : PARTS) {
                    RecognitionRequest request = new RecognitionRequest(
                            RecognitionRequest.AssemblyExpectation.component(part), calibration, null, null, 1, 0, .001);
                    RecognitionResult primary = RecognitionResult.builder(i + 1).build();
                    RecognitionResult found = new LoosePartShapeRecognizer().recognize(gray, rgb, i + 1, request, primary);
                    if (part.equals(NeobotSeedData.CASTER_WHEEL)) {
                        assertEquals(name, part, found.getComponentId());
                        assertEquals(name, RecognitionResult.ValidationState.EXPECTED_COMPONENT, found.getValidationState());
                        assertEquals(name, RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK, found.getDetectionMethod());
                        assertCorners(name, found.getProjectedCorners(), rgb); assertFinitePose(name, found.getPose()); positives++;
                    } else { assertSame(name + " requested=" + part, primary, found); negatives++; }
                }
            } finally { gray.release(); rgb.release(); }
        }
        assertEquals(4, positives); assertEquals(24, negatives);
        assertEquals(provenance.getInt("jpegBytes"), bytes); assertTrue(bytes <= 120_000);
    }

    @Test public void horizontalVerticalAndCheckerboardFixturesRequireBothMountingBores() throws Exception {
        JSONArray fixtures = provenance().getJSONArray("fixtures");
        for (int i = 0; i < fixtures.length(); i++) {
            JSONObject fixture = fixtures.getJSONObject(i);
            if (fixture.getInt("scan") == 7) continue; // broad shadow contains a second plausible edge after this ablation
            Mat rgb = decode(fixture);
            try {
                assertNotNull(fixture.getString("file"), BallCasterShapeRecognizer.find(rgb, .001));
                JSONArray bores = fixture.getJSONArray("boreCenters");
                for (int bore = 0; bore < bores.length(); bore++) {
                    JSONArray center = bores.getJSONArray(bore); Mat ablated = rgb.clone();
                    try {
                        eraseBoreByRowInterpolation(rgb, ablated, (int)center.getDouble(0), (int)center.getDouble(1), 15);
                        assertNull(fixture.getString("file") + " bore=" + bore, BallCasterShapeRecognizer.find(ablated, .001));
                    } finally { ablated.release(); }
                }
            } finally { rgb.release(); }
        }
    }

    @Test public void poolBlueElectronicsStayRejectedAsCaster() throws Exception {
        // Reuse existing, independently labelled POOL fixtures: no extra bitmap budget.
        JSONObject dim = new JSONObject(new String(read("references/dim-light-20261008/provenance.json"), StandardCharsets.UTF_8));
        JSONArray fixtures = dim.getJSONArray("fixtures"); int tested = 0;
        for (int i = 0; i < fixtures.length(); i++) {
            JSONObject fixture = fixtures.getJSONObject(i);
            if (!NeobotSeedData.DISTANCE_SENSOR.equals(fixture.getString("actual"))) continue;
            Mat rgb = decodePath("references/dim-light-20261008/" + fixture.getString("file"));
            try { assertNull(fixture.getString("file"), BallCasterShapeRecognizer.find(rgb, .001)); tested++; }
            finally { rgb.release(); }
        }
        assertTrue("Existing POOL ultrasonic fixture is exercised", tested > 0);
    }

    private static void eraseBoreByRowInterpolation(Mat source, Mat output, int cx, int cy, int radius) {
        for (int y = Math.max(0, cy-radius); y < Math.min(source.rows(), cy+radius); y++) {
            double[] left = source.get(y, cx-radius-1), right = source.get(y, cx+radius);
            for (int x = cx-radius; x < cx+radius; x++) {
                double alpha = (x-(cx-radius))/(double)(2*radius-1);
                byte[] value = new byte[3];
                for (int c = 0; c < 3; c++) value[c] = (byte)Math.rint(left[c]*(1-alpha)+right[c]*alpha);
                output.put(y, x, value);
            }
        }
    }

    private static void assertCorners(String name, List<VisionPoint> corners, Mat image) {
        assertEquals(name, 4, corners.size()); double twiceArea = 0;
        for (int i = 0; i < corners.size(); i++) {
            VisionPoint p = corners.get(i), next = corners.get((i+1)%corners.size());
            assertTrue(name, Double.isFinite(p.getX()) && Double.isFinite(p.getY()));
            assertTrue(name, p.getX() >= 0 && p.getX() < image.cols() && p.getY() >= 0 && p.getY() < image.rows());
            twiceArea += p.getX()*next.getY()-p.getY()*next.getX();
        }
        assertTrue(name, Math.abs(twiceArea)/(2*image.total()) >= .001);
    }

    private static void assertFinitePose(String name, RecognitionResult.Pose pose) {
        assertNotNull(name, pose);
        for (double value : pose.getRotationVector()) assertTrue(name, Double.isFinite(value));
        for (double value : pose.getTranslationVector()) assertTrue(name, Double.isFinite(value));
        for (float value : pose.getOpenGlModelViewMatrix()) assertTrue(name, Float.isFinite(value));
        assertTrue(name, pose.getTranslationVector()[2] > 0);
    }

    private static JSONObject provenance() throws Exception {
        return new JSONObject(new String(read(BASE + "caster-provenance.json"), StandardCharsets.UTF_8));
    }
    private static Mat decode(JSONObject fixture) throws Exception {
        byte[] bytes = read(BASE + fixture.getString("file"));
        assertEquals(fixture.getString("file"), fixture.getInt("bytes"), bytes.length);
        StringBuilder digest = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) digest.append(String.format("%02x", value&255));
        assertEquals(fixture.getString("file"), fixture.getString("sha256"), digest.toString());
        Mat rgb = decodeBytes(bytes);
        assertEquals(720, rgb.cols()); assertEquals(500, rgb.rows()); return rgb;
    }
    private static Mat decodePath(String path) throws Exception { return decodeBytes(read(path)); }
    private static Mat decodeBytes(byte[] bytes) {
        MatOfByte encoded = new MatOfByte(bytes); Mat bgr = null, rgb = new Mat(); boolean returned = false;
        try {
            bgr = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR); assertFalse(bgr.empty());
            Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB); returned = true; return rgb;
        } finally { if (!returned) rgb.release(); if (bgr != null) bgr.release(); encoded.release(); }
    }
    private static byte[] read(String path) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer,0,count);
            return output.toByteArray();
        }
    }
}
