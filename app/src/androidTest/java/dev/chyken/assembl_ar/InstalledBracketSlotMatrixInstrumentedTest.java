package dev.chyken.assembl_ar;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.VisionPoint;

import static dev.chyken.assembl_ar.AssemblyReplayHarness.KIT_PHONE_FOCAL_RATIO;
import static org.junit.Assert.assertTrue;

/**
 * Every held-out photo of the installed-bracket sequence against every slot it can answer: the
 * slots whose brackets are installed, and the next, still empty slot. This checks the camera
 * pipeline's slot decision (identity plus chassis slot gate, with the board inside the camera
 * guide) for each photo, without the activity's multi-frame evidence gates, so the 33-photo
 * matrix runs in minutes.
 *
 * <p>Safety is asserted: an empty slot must never be accepted, and an accepted detection must
 * overlap the hand-labelled bracket wherever a label exists. Missed slots are recorded, not
 * asserted, apart from the photos the 3 October 2026 board-feature fix is known to recover.
 * Still photos test recognition and slot geometry, not hand motion or phone timing.</p>
 */
@RunWith(AndroidJUnit4.class)
public final class InstalledBracketSlotMatrixInstrumentedTest {
    private static final String TAG = "BracketSlotMatrix";
    private static final int FRAMES = 2;
    /** Previously ambiguous with the socket row and cable slot; must now pass. */
    private static final String[][] RECOVERED = {
            {"brackets-3-133541.jpg", "3"}, {"brackets-3-133543.jpg", "3"},
            {"brackets-3-133552.jpg", "3"}, {"brackets-4-133630.jpg", "3"}};

    private AssemblyReplayHarness harness;

    @Before public void setUp() throws Exception { harness = new AssemblyReplayHarness(); }

    @After public void tearDown() { if (harness != null) harness.close(); }

    @Test public void emptySlotsStayLockedAndAcceptedSlotsAreTheRealBracket() throws Exception {
        JSONObject labels = asset("installed-bracket-labels.json").getJSONObject("fixtures");
        JSONArray fixtures = asset("manifest.json").getJSONArray("fixtures");
        List<String> unsafe = new ArrayList<>();
        List<String> misplaced = new ArrayList<>();
        List<String> accepted = new ArrayList<>();
        int[] passed = new int[5];
        int[] trials = new int[5];
        for (int index = 0; index < fixtures.length(); index++) {
            JSONObject fixture = fixtures.getJSONObject(index);
            if (!fixture.has("installedBrackets")) continue;
            String name = fixture.getString("fixture");
            int installed = fixture.getInt("installedBrackets");
            // AssemblyReplayHarness.analysisFrame halves the 1280 px fixtures; some are landscape.
            int width = fixture.getInt("width") / 2;
            int height = fixture.getInt("height") / 2;
            Mat photo = harness.decodeFixture(name);
            try {
                for (int slot = 1; slot <= Math.min(installed + 1, 4); slot++) {
                    harness.showBracketSlot(slot);
                    RecognitionResult result = harness.analyze(photo, KIT_PHONE_FOCAL_RATIO, FRAMES);
                    NativeCheckpointDecision decision = NativeCheckpointDecision.evaluate(
                            result, harness.activeCheckpoint(), width, height);
                    List<VisionPoint> quad = decision.accepted ? decision.acceptedQuads.get(0) : null;
                    boolean present = slot <= installed;
                    String trial = name + " slot " + slot;
                    Log.i(TAG, String.format(Locale.US, "trial fixture=%s slot=%d kind=%s verdict=%s",
                            name, slot, present ? "positive" : "negative",
                            quad == null ? (present ? "FN" : "TN") : (present ? "TP" : "FP")));
                    if (!present) {
                        if (quad != null) unsafe.add(trial);
                        continue;
                    }
                    trials[slot]++;
                    if (quad == null) continue;
                    passed[slot]++;
                    accepted.add(name + "#" + slot);
                    JSONObject outlines = labels.optJSONObject(name);
                    JSONArray outline = outlines == null ? null : outlines.optJSONArray(String.valueOf(slot));
                    if (outline != null && !overlaps(quad, outline, width, height)) misplaced.add(trial);
                }
            } finally {
                photo.release();
            }
        }
        Log.i(TAG, String.format(Locale.US, "summary slot1=%d/%d slot2=%d/%d slot3=%d/%d slot4=%d/%d "
                        + "unsafe=%s misplaced=%s", passed[1], trials[1], passed[2], trials[2],
                passed[3], trials[3], passed[4], trials[4], unsafe, misplaced));
        assertTrue("Empty slots were accepted: " + unsafe, unsafe.isEmpty());
        assertTrue("Accepted detections missed the labelled bracket: " + misplaced, misplaced.isEmpty());
        for (String[] recovered : RECOVERED) {
            assertTrue(recovered[0] + " slot " + recovered[1] + " must pass",
                    accepted.contains(recovered[0] + "#" + recovered[1]));
        }
    }

    /** At least a fifth of the smaller shape is shared; labels are fixture pixels (2× analysis). */
    private static boolean overlaps(List<VisionPoint> quad, JSONArray outline, int width, int height)
            throws Exception {
        List<Point> detected = new ArrayList<>();
        for (VisionPoint point : quad) detected.add(new Point(point.getX(), point.getY()));
        List<Point> labelled = new ArrayList<>();
        for (int index = 0; index < outline.length(); index++) {
            JSONArray point = outline.getJSONArray(index);
            labelled.add(new Point(point.getDouble(0) * 0.5, point.getDouble(1) * 0.5));
        }
        Mat first = mask(detected, width, height);
        Mat second = mask(labelled, width, height);
        Mat shared = new Mat();
        try {
            Core.bitwise_and(first, second, shared);
            int smaller = Math.min(Core.countNonZero(first), Core.countNonZero(second));
            return smaller > 0 && Core.countNonZero(shared) >= smaller * 0.2;
        } finally {
            first.release();
            second.release();
            shared.release();
        }
    }

    private static Mat mask(List<Point> polygon, int width, int height) {
        Mat mask = Mat.zeros(height, width, CvType.CV_8UC1);
        MatOfPoint points = new MatOfPoint(polygon.toArray(new Point[0]));
        try {
            Imgproc.fillPoly(mask, Collections.singletonList(points), Scalar.all(255));
        } finally {
            points.release();
        }
        return mask;
    }

    private static JSONObject asset(String name) throws Exception {
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open(AssemblyReplayHarness.REPLAY + name)) {
            return new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
