package dev.chyken.assembl_ar.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate;

/**
 * Pool photos (never the held-out fixtures) with production board anchors: the Java slot check
 * must agree with the calibrated prototype, and every accepted outline must satisfy the authored
 * section placement target.
 */
@RunWith(AndroidJUnit4.class)
public final class InstalledModuleInspectorInstrumentedTest {
    private static final String DIRECTORY = "references/installed-modules-pool/";

    @BeforeClass public static void loadOpenCv() { assertTrue(OpenCVLoader.initLocal()); }

    @Test public void installedModulesAreFoundInTheirSlotsAndEmptySlotsStayEmpty() throws Exception {
        Context assets = InstrumentationRegistry.getInstrumentation().getContext();
        JSONArray cases;
        try (InputStream input = assets.getAssets().open(DIRECTORY + "cases.json")) {
            cases = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getJSONArray("photos");
        }
        InstalledModuleInspector inspector = new InstalledModuleInspector();
        int checked = 0;
        for (int index = 0; index < cases.length(); index++) {
            JSONObject entry = cases.getJSONObject(index);
            String name = entry.getString("photo");
            Mat rgb = analysisFrame(assets, DIRECTORY + name, entry.getInt("analysisWidth"),
                    entry.getInt("analysisHeight"));
            try {
                List<VisionPoint> board = corners(entry.getJSONArray("boardCorners"));
                checked += verify(inspector, rgb, board, name, NeobotSeedData.MOTOR_DRIVER, 3,
                        entry.getBoolean("driverInstalled"));
                checked += verify(inspector, rgb, board, name, NeobotSeedData.MICROCONTROLLER, 6,
                        entry.getBoolean("nanoInstalled"));
            } finally {
                rgb.release();
            }
        }
        assertEquals(cases.length() * 2, checked);
    }

    private static int verify(InstalledModuleInspector inspector, Mat rgb, List<VisionPoint> board,
                              String name, String componentId, int section, boolean installed) {
        InstalledModuleInspector.Observation observation =
                inspector.inspect(rgb, board, componentId);
        if (!installed) {
            assertNull(name + " " + componentId + " empty slot accepted", observation);
            return 1;
        }
        assertNotNull(name + " " + componentId + " installed module missed", observation);
        ChassisRelativePlacementGate.Decision placement = ChassisRelativePlacementGate
                .forNeobotStep(section).evaluateSingle(board, observation.corners);
        assertTrue(name + " " + componentId + " outside its slot: " + placement.getMessage(),
                placement.isAccepted());
        return 1;
    }

    /** Same decode and INTER_AREA downscale as AssemblyReplayHarness.analysisFrame. */
    private static Mat analysisFrame(Context assets, String path, int width, int height)
            throws Exception {
        byte[] bytes;
        try (InputStream input = assets.getAssets().open(path)) { bytes = input.readAllBytes(); }
        MatOfByte encoded = new MatOfByte(bytes);
        Mat bgr = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
        Mat rgb = new Mat();
        Mat resized = new Mat();
        try {
            Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
            Imgproc.resize(rgb, resized, new Size(width, height), 0, 0, Imgproc.INTER_AREA);
            return resized;
        } finally {
            rgb.release();
            bgr.release();
            encoded.release();
        }
    }

    private static List<VisionPoint> corners(JSONArray values) throws Exception {
        List<VisionPoint> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONArray point = values.getJSONArray(index);
            result.add(new VisionPoint(point.getDouble(0), point.getDouble(1)));
        }
        return result;
    }
}
