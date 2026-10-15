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
import org.opencv.core.Mat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.VisionPoint;

import static dev.chyken.assembl_ar.AssemblyReplayHarness.KIT_PHONE_FOCAL_RATIO;
import static org.junit.Assert.assertTrue;

/** Held-out steps 1-8 baseline: only false acceptance is asserted; positive misses are recorded.
 * Evaluates the native per-frame decision, before the live activity's timing/evidence gates.
 */
@RunWith(AndroidJUnit4.class)
public final class OneToEightCheckpointInstrumentedTest {
    private static final String TAG = "OneToEightEval";
    private static final String DIRECTORY = "onetoeight/";
    // SOURCE_INVENTORY.json: Galaxy A26 5G, 27 mm equivalent, 4:3 photos.
    // Equivalent focal length uses the 43.27 mm diagonal; long edge = diagonal * 4/5.
    // 27 / (43.27 * 4/5) = 0.779986, vs KIT_PHONE_FOCAL_RATIO = 0.780047:
    // difference 0.0078% (<2%), so reuse the existing constant for this capture.
    private static final Card[] CARDS = {
            new Card(1, 1, "board-identity"),
            new Card(2, 1, "bracket-1"), new Card(2, 2, "bracket-2"),
            new Card(2, 3, "bracket-3"), new Card(2, 4, "bracket-4"),
            new Card(2, 5, "first-motor"), new Card(2, 17, "both-motors"),
            new Card(3, 1, "motor-driver"), new Card(6, 2, "microcontroller")};

    private AssemblyReplayHarness harness;

    @Before public void setUp() throws Exception { harness = new AssemblyReplayHarness(); }

    @After public void tearDown() { if (harness != null) harness.close(); }

    @Test public void heldoutAbsentComponentsNeverAccept() throws Exception {
        JSONObject manifest;
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open(AssemblyReplayHarness.REPLAY + DIRECTORY + "manifest.json")) {
            manifest = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        JSONArray fixtures = manifest.getJSONArray("fixtures");
        String filter = InstrumentationRegistry.getArguments().getString("onetoeight_fixtures", "");
        List<String> only = filter.isEmpty() ? new ArrayList<>()
                : java.util.Arrays.asList(filter.split(","));
        JSONArray trials = new JSONArray();
        List<String> unsafe = new ArrayList<>();
        Summary[] summaries = new Summary[CARDS.length];
        for (int i = 0; i < summaries.length; i++) summaries[i] = new Summary();
        for (int index = 0; index < fixtures.length(); index++) {
            JSONObject fixture = fixtures.getJSONObject(index);
            String name = fixture.getString("fixture");
            // Optional focused rerun: -e onetoeight_fixtures name1.jpg,name2.jpg
            if (!only.isEmpty() && !only.contains(name)) continue;
            Mat photo = harness.decodeFixture(DIRECTORY + name);
            try {
                // Use the exact resizing helper, including rounding/even dimensions and landscape.
                Mat frame = AssemblyReplayHarness.analysisFrame(photo);
                int width, height;
                try { width = frame.cols(); height = frame.rows(); }
                finally { frame.release(); }
                for (int cardIndex = 0; cardIndex < CARDS.length; cardIndex++) {
                    Card card = CARDS[cardIndex];
                    Kind kind = kindFor(fixture, card);
                    if (kind == null) {
                        summaries[cardIndex].skipped++;
                        continue;
                    }
                    harness.showCheckpoint(card.section, card.order);
                    RecognitionResult result = harness.analyze(photo, KIT_PHONE_FOCAL_RATIO, 2);
                    NativeCheckpointDecision decision = NativeCheckpointDecision.evaluate(
                            result, harness.activeCheckpoint(), width, height);
                    String verdict = kind == Kind.IGNORED
                            ? (decision.accepted ? "IGNORED_ACCEPTED" : "IGNORED_REJECTED")
                            : kind == Kind.POSITIVE ? (decision.accepted ? "TP" : "FN")
                            : (decision.accepted ? "FP" : "TN");
                    String cardId = card.id();
                    Log.i(TAG, String.format(Locale.US,
                            "trial fixture=%s card=%s kind=%s verdict=%s failureReason=%s",
                            name, cardId, kind.name().toLowerCase(Locale.US), verdict,
                            decision.failureReason));
                    trials.put(new JSONObject().put("fixture", name).put("card", cardId)
                            .put("section", card.section).put("order", card.order)
                            .put("kind", kind.name().toLowerCase(Locale.US)).put("verdict", verdict)
                            .put("accepted", decision.accepted).put("failureReason", decision.failureReason)
                            .put("componentId", result.getComponentId() == null
                                    ? JSONObject.NULL : result.getComponentId())
                            .put("validationState", result.getValidationState().name())
                            .put("spatialAnchorPresent", result.getSpatialAnchor() != null)
                            .put("boardObserved", result.getBoardObservation() != null
                                    && result.getBoardObservation().isDetected())
                            .put("analysisWidth", width).put("analysisHeight", height)
                            .put("acceptedQuads", quads(decision.acceptedQuads))
                            .put("anchorCorners", result.getSpatialAnchor() == null ? JSONObject.NULL
                                    : quad(result.getSpatialAnchor().getProjectedCorners())));
                    summaries[cardIndex].record(kind, decision.accepted);
                    if (kind == Kind.NEGATIVE && decision.accepted) unsafe.add(name + " " + cardId);
                }
            } finally { photo.release(); }
        }
        JSONArray summaryJson = new JSONArray();
        for (int index = 0; index < CARDS.length; index++) {
            JSONObject summary = summaries[index].json(CARDS[index]);
            summaryJson.put(summary);
            Log.i(TAG, "summary " + summary);
        }
        JSONObject output = new JSONObject().put("schemaVersion", 1)
                .put("fixtureCount", fixtures.length()).put("fixtureBytes", manifest.getLong("totalBytes"))
                .put("focalRatio", KIT_PHONE_FOCAL_RATIO).put("framesPerTrial", 2)
                .put("scope", "native checkpoint decision before multi-frame evidence gates")
                .put("trials", trials).put("summaries", summaryJson)
                .put("unsafeTrials", new JSONArray(unsafe));
        // Write before the safety assertion so even a failing run can be pulled with adb.
        File directory = harness.context.getExternalFilesDir("onetoeight-eval");
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IllegalStateException("Cannot create external onetoeight-eval directory");
        }
        File results = new File(directory, "results.json");
        try (FileOutputStream stream = new FileOutputStream(results)) {
            stream.write((output.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "results=" + results.getAbsolutePath());
        assertTrue("Negative trials accepted: " + unsafe, unsafe.isEmpty());
    }

    private static JSONArray quads(List<List<VisionPoint>> quads) throws Exception {
        JSONArray array = new JSONArray();
        for (List<VisionPoint> corners : quads) array.put(quad(corners));
        return array;
    }

    private static JSONArray quad(List<VisionPoint> corners) throws Exception {
        JSONArray array = new JSONArray();
        for (VisionPoint point : corners) array.put(new JSONArray().put(point.getX()).put(point.getY()));
        return array;
    }

    private enum Kind { POSITIVE, NEGATIVE, IGNORED }

    /** Null labels never establish presence or absence; ineligible states are skipped. */
    private static Kind kindFor(JSONObject fixture, Card card) throws Exception {
        JSONObject components = fixture.getJSONObject("components");
        if (card.section == 1) {
            return fixture.getJSONObject("framing").getBoolean("boardFullyInFrame")
                    ? Kind.POSITIVE : Kind.IGNORED;
        }
        JSONArray slots = components.getJSONArray("bracketSlots");
        if (card.section == 2 && card.order <= 4) {
            return slots.isNull(card.order - 1) ? null
                    : slots.getBoolean(card.order - 1) ? Kind.POSITIVE : Kind.NEGATIVE;
        }
        boolean left = present(components, "leftMotor");
        boolean right = present(components, "rightMotor");
        if (card.section == 2 && card.order == 5) {
            if (left) return Kind.POSITIVE;
            int installed = 0;
            for (int i = 0; i < slots.length(); i++) {
                if (!slots.isNull(i) && slots.getBoolean(i)) installed++;
            }
            return absent(components, "leftMotor") && installed >= 2 ? Kind.NEGATIVE : null;
        }
        if (card.section == 2) {
            if (left && right) return Kind.POSITIVE;
            return left && absent(components, "rightMotor")
                    || right && absent(components, "leftMotor") ? Kind.NEGATIVE : null;
        }
        if (card.section == 3) {
            if (present(components, "motorDriver")) return Kind.POSITIVE;
            return absent(components, "motorDriver") && left && right ? Kind.NEGATIVE : null;
        }
        if (present(components, "microcontroller")) return Kind.POSITIVE;
        return absent(components, "microcontroller") && present(components, "motorDriver")
                ? Kind.NEGATIVE : null;
    }

    private static boolean present(JSONObject components, String key) throws Exception {
        return !components.isNull(key) && !"absent".equals(components.getString(key));
    }

    private static boolean absent(JSONObject components, String key) throws Exception {
        return !components.isNull(key) && "absent".equals(components.getString(key));
    }

    private static final class Card {
        final int section, order;
        final String name;
        Card(int section, int order, String name) {
            this.section = section; this.order = order; this.name = name;
        }
        String id() { return section + "/" + order + " " + name; }
    }

    private static final class Summary {
        int positives, negatives, ignored, skipped, tp, fn, tn, fp, ignoredAccepted;
        void record(Kind kind, boolean accepted) {
            switch (kind) {
                case POSITIVE: positives++; if (accepted) tp++; else fn++; break;
                case NEGATIVE: negatives++; if (accepted) fp++; else tn++; break;
                case IGNORED: ignored++; if (accepted) ignoredAccepted++; break;
            }
        }
        JSONObject json(Card card) throws Exception {
            return new JSONObject().put("card", card.id()).put("positiveTrials", positives)
                    .put("negativeTrials", negatives).put("ignoredTrials", ignored)
                    .put("skippedFixtures", skipped).put("TP", tp).put("FN", fn)
                    .put("TN", tn).put("FP", fp).put("ignoredAccepted", ignoredAccepted);
        }
    }
}
