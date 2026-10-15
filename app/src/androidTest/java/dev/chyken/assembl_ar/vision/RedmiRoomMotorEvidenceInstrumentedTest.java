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
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import dev.chyken.assembl_ar.data.NeobotSeedData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Today's odd POOL scans: a gold USB shell cannot identify a motor. */
@RunWith(AndroidJUnit4.class)
public final class RedmiRoomMotorEvidenceInstrumentedTest {
    private static final String BASE = "references/redmi-room-20261008/";

    @BeforeClass public static void loadOpenCv() { assertTrue(OpenCVLoader.initLocal()); }

    @Test public void darkBlueNanoNeighbourVetoPreservesTheRealMotor() throws Exception {
        JSONObject manifest = new JSONObject(new String(readAsset("audit-provenance.json"),
                StandardCharsets.UTF_8));
        JSONArray fixtures = manifest.getJSONArray("fixtures");
        assertEquals(3, fixtures.length());
        int positives = 0, negatives = 0, bytes = 0;
        for (int index = 0; index < fixtures.length(); index++) {
            JSONObject fixture = fixtures.getJSONObject(index);
            String name = fixture.getString("file");
            assertEquals(name, "POOL", fixture.getString("split"));
            assertTrue(name, fixture.getInt("scan") % 2 == 1);
            byte[] encoded = readAsset(name);
            assertEquals(name, fixture.getInt("bytes"), encoded.length);
            assertEquals(name, fixture.getString("sha256"), sha256(encoded));
            bytes += encoded.length;
            MatOfByte buffer = new MatOfByte(encoded);
            Mat bgr = Imgcodecs.imdecode(buffer, Imgcodecs.IMREAD_COLOR);
            Mat rgb = new Mat(), gray = new Mat();
            try {
                assertTrue(name, !bgr.empty());
                assertEquals(name, 720, bgr.cols()); assertEquals(name, 500, bgr.rows());
                Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
                Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
                RecognitionRequest request = new RecognitionRequest(
                        RecognitionRequest.AssemblyExpectation.component(NeobotSeedData.GEAR_MOTOR),
                        CameraCalibration.pinhole(655.07794, 655.07788, 359, 250),
                        null, null, 1, 0, .001);
                RecognitionResult primary = RecognitionResult.builder(index + 1L).build();
                RecognitionResult result = new LoosePartShapeRecognizer().recognize(
                        gray, rgb, index + 1L, request, primary);
                if (fixture.getBoolean("expectedMotorCandidate")) {
                    assertEquals(name, NeobotSeedData.GEAR_MOTOR, fixture.getString("actual"));
                    assertEquals(name, NeobotSeedData.GEAR_MOTOR, result.getComponentId());
                    assertEquals(name, RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK,
                            result.getDetectionMethod());
                    assertEquals(name, 4, result.getProjectedCorners().size());
                    for (VisionPoint point : result.getProjectedCorners()) {
                        assertTrue(name, Double.isFinite(point.getX()) && Double.isFinite(point.getY()));
                    }
                    assertNotNull(name, result.getPose());
                    for (double value : result.getPose().getRotationVector()) {
                        assertTrue(name, Double.isFinite(value));
                    }
                    for (double value : result.getPose().getTranslationVector()) {
                        assertTrue(name, Double.isFinite(value));
                    }
                    assertTrue(name, result.getPose().getTranslationVector()[2] > 0);
                    positives++;
                } else {
                    assertEquals(name, NeobotSeedData.MICROCONTROLLER, fixture.getString("actual"));
                    assertSame(name, primary, result);
                    negatives++;
                }
            } finally { gray.release(); rgb.release(); bgr.release(); buffer.release(); }
        }
        assertEquals(1, positives); assertEquals(2, negatives);
        assertEquals(manifest.getInt("jpegBytes"), bytes);
        assertTrue("Audit's share of the new fixture budget", bytes + readAsset("audit-provenance.json").length <= 140_000);
    }

    private static byte[] readAsset(String name) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream input = context.getAssets().open(BASE + name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format(java.util.Locale.US, "%02x", value & 255));
        }
        return result.toString();
    }
}
