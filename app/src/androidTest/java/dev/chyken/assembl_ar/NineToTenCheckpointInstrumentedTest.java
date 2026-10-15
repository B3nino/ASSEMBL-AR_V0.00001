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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.workflow.GuidedCheckpoint;

import static dev.chyken.assembl_ar.AssemblyReplayHarness.KIT_PHONE_FOCAL_RATIO;
import static org.junit.Assert.assertTrue;

/** Held-out steps 9-10: uses the current native card decision without changing recognition.
 * Presence labels come only from the manifest. Positives are measured; absent parts must not pass.
 * Card 10/12 retains its current BOTH-tracker requirement, rather than an independent right gate.
 */
@RunWith(AndroidJUnit4.class)
public final class NineToTenCheckpointInstrumentedTest {
    private static final String TAG = "NineToTenEval";
    private static final String DIRECTORY = "ninetoten/";
    private static final long MAX_FIXTURE_BYTES = 8_000_000L;
    // Source inventory: Galaxy A26 5G, 4 mm / 27 mm equivalent, 4:3 captures.
    // Same phone/EXIF intrinsics as OneToEight: reuse KIT_PHONE_FOCAL_RATIO.
    private static final String[] TARGET_KEYS = {
            "caster", "leftTracker", "rightTracker", "boardIdentity"};
    private static final Card[] CARDS = {
            new Card(9, 7, "caster", "caster", "caster"),
            new Card(10, 6, "left-tracker", "leftTracker", "left_tracker"),
            new Card(10, 12, "right-tracker", "rightTracker", "left_tracker", "right_tracker"),
            new Card(1, 1, "board-identity", "boardIdentity", "main_board")};

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
        validateManifest(fixtures, manifest.getLong("totalBytes"));
        String filter = InstrumentationRegistry.getArguments().getString("ninetoten_fixtures", "");
        List<String> only = filter.isEmpty() ? new ArrayList<>() : Arrays.asList(filter.split(","));
        JSONArray trials = new JSONArray();
        List<String> unsafe = new ArrayList<>();
        List<String> unsafeRequiredParts = new ArrayList<>();
        Summary[] summaries = new Summary[CARDS.length];
        for (int i = 0; i < summaries.length; i++) summaries[i] = new Summary();
        int evaluatedFixtures = 0;
        for (int index = 0; index < fixtures.length(); index++) {
            JSONObject fixture = fixtures.getJSONObject(index);
            String name = fixture.getString("fixture");
            if (!only.isEmpty() && !only.contains(name)) continue;
            verifyFixture(fixture);
            Mat photo = harness.decodeFixture(DIRECTORY + name);
            try {
                if (photo.cols() != fixture.getInt("width") || photo.rows() != fixture.getInt("height")) {
                    throw new IllegalArgumentException("Fixture dimensions differ from manifest: " + name);
                }
                Mat frame = AssemblyReplayHarness.analysisFrame(photo);
                int width, height;
                try { width = frame.cols(); height = frame.rows(); }
                finally { frame.release(); }
                evaluatedFixtures++;
                JSONObject targets = fixture.getJSONObject("targets");
                for (int cardIndex = 0; cardIndex < CARDS.length; cardIndex++) {
                    Card card = CARDS[cardIndex];
                    JSONObject label = targets.getJSONObject(card.targetKey);
                    Kind kind = kindFor(label.getString("state"));
                    // This is the actual authored checkpoint: the right card requires both trackers.
                    harness.showCheckpoint(card.section, card.order);
                    GuidedCheckpoint checkpoint = harness.activeCheckpoint();
                    RecognitionResult result = harness.analyze(photo, KIT_PHONE_FOCAL_RATIO, 2);
                    NativeCheckpointDecision decision = NativeCheckpointDecision.evaluate(
                            result, checkpoint, width, height);
                    String verdict = kind == Kind.IGNORED
                            ? (decision.accepted ? "IGNORED_ACCEPTED" : "IGNORED_REJECTED")
                            : kind == Kind.POSITIVE ? (decision.accepted ? "TP" : "FN")
                            : (decision.accepted ? "FP" : "TN");
                    JSONArray requiredAbsent = requiredAbsentTargets(targets, card);
                    boolean requiredPartAbsent = requiredAbsent.length() != 0;
                    RecognitionResult.BoardObservation board = result.getBoardObservation();
                    RecognitionResult.SpatialAnchor boardAnchor = mainBoardAnchor(result);
                    JSONObject trial = new JSONObject().put("fixture", name).put("card", card.id())
                            .put("section", card.section).put("order", card.order)
                            .put("target", card.targetKey).put("targetState", label.getString("state"))
                            .put("targetLabels", targets)
                            .put("kind", kind.name().toLowerCase(Locale.US)).put("verdict", verdict)
                            .put("accepted", decision.accepted).put("failureReason", decision.failureReason)
                            .put("requiredTargetIds", new JSONArray(Arrays.asList(card.requiredTargetIds)))
                            .put("requiredInstanceCount", checkpoint.getExpectedInstanceCount())
                            .put("requiredAbsentTargets", requiredAbsent)
                            .put("requiredPartAbsent", requiredPartAbsent)
                            .put("componentId", nullable(result.getComponentId()))
                            .put("referenceId", nullable(result.getReferenceId()))
                            .put("validationState", result.getValidationState().name())
                            .put("detectionMethod", result.getDetectionMethod().name())
                            .put("orientationClassification", result.getOrientationClassification().name())
                            .put("orientationDegrees", Double.isFinite(result.getOrientationDegrees())
                                    ? result.getOrientationDegrees() : JSONObject.NULL)
                            .put("spatialAnchorPresent", result.getSpatialAnchor() != null)
                            .put("placementAnchorFound", isMainBoardAnchor(result.getSpatialAnchor()))
                            .put("anchorFound", boardAnchor != null)
                            .put("boardObserved", board != null && board.isDetected())
                            .put("boardTracked", board != null && board.isTracked())
                            .put("boardReferenceId", board == null ? JSONObject.NULL
                                    : nullable(board.getDetection().getReferenceId()))
                            .put("boardDetectionCorners", board == null ? JSONObject.NULL
                                    : quad(board.getDetection().getProjectedCorners()))
                            .put("boardCorners", boardAnchor == null ? JSONObject.NULL
                                    : quad(boardAnchor.getProjectedCorners()))
                            .put("analysisWidth", width).put("analysisHeight", height)
                            .put("analysisLongEdge", AssemblyReplayHarness.ANALYSIS_LONG_EDGE)
                            .put("approxAnalysisPixels", pixelSize(label, width, height))
                            .put("bboxNormalized", label.opt("bboxNormalized") == null
                                    ? JSONObject.NULL : label.opt("bboxNormalized"))
                            .put("acceptedQuads", quads(decision.acceptedQuads))
                            .put("anchorCorners", result.getSpatialAnchor() == null ? JSONObject.NULL
                                    : quad(result.getSpatialAnchor().getProjectedCorners()));
                    for (String key : new String[]{"boardFace", "viewpoint", "framing", "quality",
                            "subStage", "checkpoint", "casterPosts", "cluster", "notes"}) {
                        if (fixture.has(key)) trial.put(key, fixture.get(key));
                    }
                    if (fixture.has("section")) trial.put("sceneSection", fixture.get("section"));
                    trials.put(trial);
                    summaries[cardIndex].record(kind, decision.accepted, requiredPartAbsent);
                    String trialId = name + " " + card.id();
                    if (kind == Kind.NEGATIVE && decision.accepted) unsafe.add(trialId);
                    if (requiredPartAbsent && decision.accepted) unsafeRequiredParts.add(trialId);
                    Log.i(TAG, String.format(Locale.US,
                            "trial fixture=%s card=%s state=%s verdict=%s anchorFound=%s failureReason=%s",
                            name, card.id(), label.getString("state"), verdict, boardAnchor != null,
                            decision.failureReason));
                }
            } finally { photo.release(); }
        }
        if (evaluatedFixtures == 0) throw new IllegalArgumentException("No fixtures matched: " + filter);
        JSONArray summaryJson = new JSONArray();
        for (int index = 0; index < CARDS.length; index++) {
            JSONObject summary = summaries[index].json(CARDS[index]);
            summaryJson.put(summary);
            Log.i(TAG, "summary " + summary);
        }
        JSONObject output = new JSONObject().put("schemaVersion", 1)
                .put("fixtureCount", fixtures.length()).put("evaluatedFixtureCount", evaluatedFixtures)
                .put("fixtureBytes", manifest.getLong("totalBytes")).put("fixtureFilter", filter)
                .put("focalRatio", KIT_PHONE_FOCAL_RATIO).put("framesPerTrial", 2)
                .put("analysisLongEdge", AssemblyReplayHarness.ANALYSIS_LONG_EDGE)
                .put("scope", "native checkpoint decision before multi-frame evidence gates")
                .put("canonicalFrame", "top-face board coordinates; underside anchors preserve handedness")
                .put("rightTrackerCardSemantics", "10/12 requires BOTH left_tracker and right_tracker; "
                        + "presence scoring uses rightTracker, and any required absent part is safety-checked")
                .put("boardIdentitySemantics", "Physical board presence is independent of face; "
                        + "step 1 intentionally rejects an underside observation as mirrored")
                .put("trials", trials).put("summaries", summaryJson)
                .put("unsafeTrials", new JSONArray(unsafe))
                .put("unsafeRequiredPartTrials", new JSONArray(unsafeRequiredParts));
        // Write before either safety assertion, so a failed run still has reviewable results.
        File directory = harness.context.getExternalFilesDir("ninetoten-eval");
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IllegalStateException("Cannot create external ninetoten-eval directory");
        }
        File results = new File(directory, "results.json");
        try (FileOutputStream stream = new FileOutputStream(results)) {
            stream.write((output.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "results=" + results.getAbsolutePath());
        assertTrue("Absent target trials accepted: " + unsafe, unsafe.isEmpty());
        assertTrue("Required absent part trials accepted: " + unsafeRequiredParts,
                unsafeRequiredParts.isEmpty());
    }

    private static void validateManifest(JSONArray fixtures, long totalBytes) throws Exception {
        if (fixtures.length() == 0 || totalBytes <= 0 || totalBytes > MAX_FIXTURE_BYTES) {
            throw new IllegalArgumentException("Expected nonempty fixtures within the 8 MB budget");
        }
        Set<String> names = new HashSet<>();
        long bytes = 0;
        for (int i = 0; i < fixtures.length(); i++) {
            JSONObject fixture = fixtures.getJSONObject(i);
            String name = fixture.getString("fixture");
            if (!name.endsWith(".jpg") || name.contains("/") || name.contains("\\") || !names.add(name)) {
                throw new IllegalArgumentException("Invalid or duplicate fixture: " + name);
            }
            if (fixture.getLong("bytes") <= 0 || fixture.getInt("width") <= 0
                    || fixture.getInt("height") <= 0) {
                throw new IllegalArgumentException("Invalid fixture dimensions/bytes: " + name);
            }
            bytes += fixture.getLong("bytes");
            JSONObject targets = fixture.getJSONObject("targets");
            for (String key : TARGET_KEYS) {
                JSONObject target = targets.getJSONObject(key);
                Kind kind = kindFor(target.getString("state"));
                if (kind == Kind.POSITIVE && !"boardIdentity".equals(key)) {
                    if (target.isNull("bboxNormalized")) {
                        throw new IllegalArgumentException("Present part requires a labeled bbox: " + name + " " + key);
                    }
                    pixelSize(target, fixture.getInt("width"), fixture.getInt("height"));
                } else if (kind != Kind.POSITIVE && !target.isNull("bboxNormalized")) {
                    throw new IllegalArgumentException("Absent/not-visible target has a bbox: " + name + " " + key);
                }
            }
        }
        if (bytes != totalBytes) throw new IllegalArgumentException("Fixture byte total does not reconcile");
    }

    private static void verifyFixture(JSONObject fixture) throws Exception {
        byte[] data;
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                .open(AssemblyReplayHarness.REPLAY + DIRECTORY + fixture.getString("fixture"))) {
            data = input.readAllBytes();
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) {
            hex.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        if (data.length != fixture.getLong("bytes")
                || !hex.toString().equalsIgnoreCase(fixture.getString("sha256"))) {
            throw new IllegalArgumentException("Fixture bytes/SHA differ from manifest: " + fixture.getString("fixture"));
        }
    }

    private static Kind kindFor(String state) {
        switch (state) {
            case "present": return Kind.POSITIVE;
            case "absent": return Kind.NEGATIVE;
            case "not_visible": return Kind.IGNORED;
            default: throw new IllegalArgumentException("Unknown target state: " + state);
        }
    }

    /** Pixel footprint is authored label geometry, never the recognizer's candidate outline. */
    private static Object pixelSize(JSONObject label, int width, int height) throws Exception {
        if (!"present".equals(label.getString("state")) || label.isNull("bboxNormalized")) {
            return JSONObject.NULL;
        }
        JSONArray bbox = label.getJSONArray("bboxNormalized");
        if (bbox.length() != 4) throw new IllegalArgumentException("Expected [x0,y0,x1,y1] bbox");
        double x0 = bbox.getDouble(0), y0 = bbox.getDouble(1);
        double x1 = bbox.getDouble(2), y1 = bbox.getDouble(3);
        if (!Double.isFinite(x0) || !Double.isFinite(y0) || !Double.isFinite(x1) || !Double.isFinite(y1)
                || x0 < 0 || y0 < 0 || x1 > 1 || y1 > 1 || x1 <= x0 || y1 <= y0) {
            throw new IllegalArgumentException("Invalid normalized bbox");
        }
        return new JSONObject().put("width", (x1 - x0) * width).put("height", (y1 - y0) * height)
                .put("source", "authored normalized bbox scaled to actual analysis dimensions");
    }

    private static JSONArray requiredAbsentTargets(JSONObject targets, Card card) throws Exception {
        JSONArray absent = new JSONArray();
        for (String id : card.requiredTargetIds) {
            String key = "left_tracker".equals(id) ? "leftTracker"
                    : "right_tracker".equals(id) ? "rightTracker"
                    : "main_board".equals(id) ? "boardIdentity" : "caster";
            if ("absent".equals(targets.getJSONObject(key).getString("state"))) absent.put(id);
        }
        return absent;
    }

    private static boolean isMainBoardAnchor(RecognitionResult.SpatialAnchor anchor) {
        return anchor != null && NeobotSeedData.MAIN_BOARD.equals(anchor.getComponentId());
    }

    private static RecognitionResult.SpatialAnchor mainBoardAnchor(RecognitionResult result) {
        if (isMainBoardAnchor(result.getSpatialAnchor())) return result.getSpatialAnchor();
        RecognitionResult.BoardObservation board = result.getBoardObservation();
        return board != null && isMainBoardAnchor(board.getAnchor()) ? board.getAnchor() : null;
    }

    private static Object nullable(String value) { return value == null ? JSONObject.NULL : value; }

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

    private static final class Card {
        final int section, order;
        final String name, targetKey;
        final String[] requiredTargetIds;
        Card(int section, int order, String name, String targetKey, String... requiredTargetIds) {
            this.section = section; this.order = order; this.name = name; this.targetKey = targetKey;
            this.requiredTargetIds = requiredTargetIds;
        }
        String id() { return section + "/" + order + " " + name; }
    }

    private static final class Summary {
        int positives, negatives, ignored, tp, fn, tn, fp, ignoredAccepted;
        int requiredAbsentTrials, requiredAbsentAccepted;
        void record(Kind kind, boolean accepted, boolean requiredPartAbsent) {
            switch (kind) {
                case POSITIVE: positives++; if (accepted) tp++; else fn++; break;
                case NEGATIVE: negatives++; if (accepted) fp++; else tn++; break;
                case IGNORED: ignored++; if (accepted) ignoredAccepted++; break;
            }
            if (requiredPartAbsent) { requiredAbsentTrials++; if (accepted) requiredAbsentAccepted++; }
        }
        JSONObject json(Card card) throws Exception {
            return new JSONObject().put("card", card.id()).put("target", card.targetKey)
                    .put("requiredTargetIds", new JSONArray(Arrays.asList(card.requiredTargetIds)))
                    .put("positiveTrials", positives).put("negativeTrials", negatives)
                    .put("ignoredTrials", ignored).put("TP", tp).put("FN", fn).put("TN", tn).put("FP", fp)
                    .put("ignoredAccepted", ignoredAccepted).put("requiredAbsentTrials", requiredAbsentTrials)
                    .put("requiredAbsentAccepted", requiredAbsentAccepted);
        }
    }
}
