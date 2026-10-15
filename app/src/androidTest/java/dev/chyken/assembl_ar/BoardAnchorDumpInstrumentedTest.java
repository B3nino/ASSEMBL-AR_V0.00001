package dev.chyken.assembl_ar;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.VisionPoint;

/**
 * Diagnostic only: records the production board anchor for photos pushed to
 * files/board-anchor-input, so offline calibration never needs the held-out fixtures.
 * Optional argument {@code -e board_anchor_card 3,1} selects the guided card (default 3/1).
 */
@RunWith(AndroidJUnit4.class)
public final class BoardAnchorDumpInstrumentedTest {
    private static final String TAG = "BoardAnchorDump";
    private AssemblyReplayHarness harness;

    @Before public void setUp() throws Exception { harness = new AssemblyReplayHarness(); }

    @After public void tearDown() { if (harness != null) harness.close(); }

    @Test public void dumpBoardAnchors() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File input = context.getExternalFilesDir("board-anchor-input");
        File output = context.getExternalFilesDir("board-anchor-output");
        File[] files = input == null ? null
                : input.listFiles((dir, name) -> name.toLowerCase().endsWith(".jpg"));
        // Diagnostic only: skipped unless photos were pushed for this run.
        assumeTrue("No photos in files/board-anchor-input", files != null && files.length > 0);
        assertTrue(output != null);
        String[] card = InstrumentationRegistry.getArguments()
                .getString("board_anchor_card", "3,1").split(",");
        int section = Integer.parseInt(card[0].trim());
        int order = Integer.parseInt(card[1].trim());
        List<File> photos = Arrays.asList(files);
        photos.sort((a, b) -> a.getName().compareTo(b.getName()));
        JSONArray rows = new JSONArray();
        for (File file : photos) {
            Mat bgr = Imgcodecs.imread(file.getAbsolutePath(), Imgcodecs.IMREAD_COLOR);
            Mat rgb = new Mat();
            try {
                Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
                Mat frame = AssemblyReplayHarness.analysisFrame(rgb);
                int width = frame.cols(), height = frame.rows();
                frame.release();
                harness.showCheckpoint(section, order);
                RecognitionResult result = harness.analyze(rgb,
                        AssemblyReplayHarness.KIT_PHONE_FOCAL_RATIO, 2);
                RecognitionResult.SpatialAnchor anchor = result.getSpatialAnchor();
                JSONObject row = new JSONObject().put("photo", file.getName())
                        .put("analysisWidth", width).put("analysisHeight", height)
                        .put("boardObserved", result.getBoardObservation() != null
                                && result.getBoardObservation().isDetected())
                        .put("anchorCorners", anchor == null ? JSONObject.NULL
                                : quad(anchor.getProjectedCorners()))
                        .put("componentId", result.getComponentId() == null
                                ? JSONObject.NULL : result.getComponentId())
                        .put("validationState", result.getValidationState().name());
                rows.put(row);
                Log.i(TAG, "row " + row);
            } finally {
                rgb.release();
                bgr.release();
            }
        }
        File json = new File(output, "anchors.json");
        try (FileOutputStream stream = new FileOutputStream(json)) {
            stream.write(new JSONObject().put("card", section + "/" + order)
                    .put("rows", rows).toString(1).getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "wrote " + json + " rows=" + rows.length());
    }

    private static JSONArray quad(List<VisionPoint> corners) throws Exception {
        JSONArray result = new JSONArray();
        for (VisionPoint point : corners) result.put(new JSONArray().put(point.getX()).put(point.getY()));
        return result;
    }
}
