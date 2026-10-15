package dev.chyken.assembl_ar;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.MatchingAccuracyProbe;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.VisionConfig;

import static dev.chyken.assembl_ar.AssemblyReplayHarness.KIT_PHONE_FOCAL_RATIO;
import static org.junit.Assert.assertTrue;

/**
 * Section 3.3.1 Tests A and E on held-out photos of the real kit, replayed at the camera's
 * analysis size through each loose-part card of the live workflow. Each card receives four
 * correct-part trials and four wrong-part trials. Results are logged under the
 * {@code AssemblyEvaluation} tag; lighting and viewing angle were not varied in these captures.
 */
@RunWith(AndroidJUnit4.class)
public final class AssemblyEvaluationInstrumentedTest {
    private static final String TAG = "AssemblyEvaluation";
    private static final int SCAN_FRAMES = 6;
    private static final String[] NANO = {"095135", "095402", "095424", "095449"};
    private static final String[] TRACKER = {"095507", "095513", "095519", "095522"};
    private static final String[] ULTRASONIC = {"095548", "095553", "095602", "095636"};
    private static final String[] CASTER = {"095653", "095658", "095707", "095713"};
    private static final String[] BRACKET = {"100026", "100038", "100105", "100111"};

    private AssemblyReplayHarness harness;

    @Before public void setUp() throws Exception { harness = new AssemblyReplayHarness(); }

    @After public void tearDown() { if (harness != null) harness.close(); }

    @Test public void looseScanCardsAcceptTheRequiredPartAndFlagEveryOtherPart() throws Exception {
        Map<String, String> fixtures = new LinkedHashMap<>();
        Map<String, int[]> cards = new LinkedHashMap<>();
        fixtures.put(NeobotSeedData.MICROCONTROLLER, "nano");
        cards.put(NeobotSeedData.MICROCONTROLLER, new int[]{6, 2});
        fixtures.put(NeobotSeedData.CASTER_WHEEL, "caster");
        cards.put(NeobotSeedData.CASTER_WHEEL, new int[]{9, 7});
        fixtures.put(NeobotSeedData.LINE_TRACKER, "line-tracker");
        cards.put(NeobotSeedData.LINE_TRACKER, new int[]{10, 6});
        fixtures.put(NeobotSeedData.DISTANCE_SENSOR, "ultrasonic-sensor");
        cards.put(NeobotSeedData.DISTANCE_SENSOR, new int[]{11, 3});
        Map<String, String[]> captures = new LinkedHashMap<>();
        captures.put("nano", NANO);
        captures.put("line-tracker", TRACKER);
        captures.put("ultrasonic-sensor", ULTRASONIC);
        captures.put("caster", CASTER);
        captures.put("motor-bracket-half", BRACKET);

        // Keep the report's original TP/FP/TN/FN labels for comparison, but also count
        // the actual trial outcomes explicitly. A wrong part that gets no feedback is
        // not a dangerous acceptance, even though it still fails the mismatch criterion.
        int tp = 0, tn = 0, fp = 0, fn = 0;
        int wrongAccepted = 0, wrongUnflagged = 0, correctFalseMismatch = 0;
        List<Double> mismatchFeedbackMs = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> card : fixtures.entrySet()) {
            String expectedId = card.getKey();
            String expectedFixture = card.getValue();
            int[] location = cards.get(expectedId);
            // Five parts, four wrong-part trials: one capture of each other loose part.
            List<String[]> trials = new ArrayList<>();
            for (String capture : captures.get(expectedFixture)) {
                trials.add(new String[]{"correct", "loose-" + expectedFixture + "-" + capture + ".jpg"});
            }
            int wrongIndex = 0;
            for (Map.Entry<String, String[]> other : captures.entrySet()) {
                if (other.getKey().equals(expectedFixture)) continue;
                String capture = other.getValue()[wrongIndex++ % other.getValue().length];
                trials.add(new String[]{"wrong", "loose-" + other.getKey() + "-" + capture + ".jpg"});
            }
            for (String[] trial : trials) {
                harness.showCard(location[0], location[1]);
                assertTrue(expectedId + " card must open with its loose-part scan", harness.awaitingPartScan());
                Mat photo = harness.decodeFixture(trial[1]);
                AssemblyReplayHarness.Outcome outcome = harness.replay(
                        "scan-" + expectedId + "-" + trial[1], photo, KIT_PHONE_FOCAL_RATIO, SCAN_FRAMES,
                        a -> !(Boolean) AssemblyReplayHarness.field(a, "awaitingPartScan")
                                || AssemblyReplayHarness.showsMismatch(a));
                boolean accepted = outcome.reached && !harness.awaitingPartScan();
                String verdict;
                if ("correct".equals(trial[0])) {
                    verdict = accepted && !outcome.anyMismatch() ? "TP" : "FP";
                    if ("TP".equals(verdict)) tp++; else fp++;
                    if (outcome.anyMismatch()) correctFalseMismatch++;
                    logRecognition(expectedId, trial[1], photo, outcome);
                } else {
                    verdict = outcome.anyMismatch() && !accepted ? "TN" : "FN";
                    if ("TN".equals(verdict)) {
                        tn++;
                        mismatchFeedbackMs.add(outcome.firstMismatchMs());
                    } else {
                        fn++;
                        if (accepted) {
                            wrongAccepted++;
                            failures.add(trial[1] + " was accepted as " + expectedId);
                        } else {
                            wrongUnflagged++;
                        }
                    }
                }
                photo.release();
                Log.i(TAG, String.format(Locale.US, "verificationTrial card=%s fixture=%s kind=%s verdict=%s "
                                + "accepted=%s mismatch=%s mismatchMs=%.0f frames=%d meanAnalysisMs=%.0f banner=\"%s\"",
                        expectedId, trial[1], trial[0], verdict, accepted, outcome.anyMismatch(),
                        outcome.firstMismatchMs(), outcome.frames.size(), outcome.meanAnalysisMs(),
                        outcome.lastBanner()));
            }
        }
        double meanFeedback = 0.0;
        for (double value : mismatchFeedbackMs) meanFeedback += value;
        meanFeedback = mismatchFeedbackMs.isEmpty() ? Double.NaN : meanFeedback / mismatchFeedbackMs.size();
        Log.i(TAG, String.format(Locale.US, "verificationSummary TP=%d TN=%d FP=%d FN=%d "
                        + "accuracy=%.3f sensitivity=%.3f falseMismatch=%.3f meanMismatchFeedbackMs=%.0f",
                tp, tn, fp, fn, (tp + tn) / (double) (tp + tn + fp + fn),
                tn / (double) Math.max(1, tn + fn), fp / (double) Math.max(1, fp + tn), meanFeedback));
        Log.i(TAG, String.format(Locale.US, "trialOutcomeSummary correctAccepted=%d correctRejected=%d "
                        + "correctFalseMismatch=%d wrongFlagged=%d wrongUnflagged=%d "
                        + "wrongAccepted=%d correctAcceptance=%.3f mismatchFeedback=%.3f "
                        + "falseMismatch=%.3f unsafeAcceptance=%.3f",
                tp, fp, correctFalseMismatch, tn, wrongUnflagged, wrongAccepted,
                tp / (double) Math.max(1, tp + fp),
                tn / (double) Math.max(1, tn + wrongUnflagged + wrongAccepted),
                correctFalseMismatch / (double) Math.max(1, tp + fp),
                wrongAccepted / (double) Math.max(1, tn + wrongUnflagged + wrongAccepted)));
        // A wrong loose part must never start the installation demonstration for another part.
        assertTrue("Wrong parts accepted: " + failures, failures.isEmpty());
    }

    /** Per-frame Test A evidence for a correct-part trial, plus ratio-test matching accuracy. */
    private void logRecognition(String expectedId, String fixture, Mat photo,
                                AssemblyReplayHarness.Outcome outcome) {
        int correct = 0;
        double feature = 0.0, matching = 0.0;
        for (AssemblyReplayHarness.FrameRecord frame : outcome.frames) {
            RecognitionResult result = frame.result;
            if (expectedId.equals(result.getComponentId())
                    && result.getValidationState() == RecognitionResult.ValidationState.EXPECTED_COMPONENT) {
                correct++;
            }
            feature += result.getLatency().getFeatureExtractionMs();
            matching += result.getLatency().getMatchingMs();
        }
        RecognitionResult first = outcome.frames.get(0).result;
        String accuracy = "n/a";
        if (first.getReferenceId() != null && first.getDetectionMethod()
                == RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY) {
            Mat frame = AssemblyReplayHarness.analysisFrame(photo);
            Mat gray = new Mat();
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_RGB2GRAY);
            MatchingAccuracyProbe.Counts counts = MatchingAccuracyProbe.measure(harness.database(),
                    VisionConfig.defaults(), gray, first.getReferenceId());
            accuracy = String.format(Locale.US, "%d/%d=%.3f", counts.good, counts.attempted, counts.accuracy());
            frame.release();
            gray.release();
        }
        int frames = outcome.frames.size();
        Log.i(TAG, String.format(Locale.US, "recognitionTrial component=%s fixture=%s framesCorrect=%d/%d "
                        + "method=%s reference=%s good=%d inliers=%d inlierRatio=%.3f ratioTestAccuracy=%s "
                        + "meanFeatureMs=%.1f meanMatchingMs=%.1f",
                expectedId, fixture, correct, frames, first.getDetectionMethod(), first.getReferenceId(),
                first.getGoodMatchCount(), first.getInlierCount(), first.getInlierRatio(), accuracy,
                feature / frames, matching / frames));
    }
}
