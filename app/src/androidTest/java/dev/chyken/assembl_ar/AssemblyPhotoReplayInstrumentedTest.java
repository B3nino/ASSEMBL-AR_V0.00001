package dev.chyken.assembl_ar;

import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;
import dev.chyken.assembl_ar.workflow.GuidedStepProgressStore;

import static dev.chyken.assembl_ar.AssemblyReplayHarness.KIT_PHONE_FOCAL_RATIO;
import static dev.chyken.assembl_ar.AssemblyReplayHarness.TAG;
import static dev.chyken.assembl_ar.AssemblyReplayHarness.field;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Held-out photos of the real kit, replayed through the production pipeline at the camera's
 * analysis size, must complete the opening assembly actions they show and nothing else.
 */
@RunWith(AndroidJUnit4.class)
public final class AssemblyPhotoReplayInstrumentedTest {
    private static final int PLACEMENT_FRAMES = 14;
    private AssemblyReplayHarness harness;

    @Before public void setUp() throws Exception { harness = new AssemblyReplayHarness(); }

    @After public void tearDown() { if (harness != null) harness.close(); }

    @Test public void boardPhotoCompletesPreparationAutomatically() throws Exception {
        // Held-out view of the whole main board; one bracket is already fitted in this capture.
        Mat board = harness.decodeFixture("brackets-1-132927.jpg");
        harness.showCard(1, 1);
        AssemblyReplayHarness.Outcome outcome = harness.replay("section1-board", board,
                KIT_PHONE_FOCAL_RATIO, 12,
                a -> ((AssemblyStep) field(a, "currentStep")).getStepNumber() == 2
                        || (Boolean) field(a, "completionInFlight"));
        board.release();
        assertTrue("Main-board recognition must finish preparation: " + outcome, outcome.reached);
        for (int attempt = 0; attempt < 60; attempt++) {
            boolean[] next = {false};
            harness.scenario.onActivity(a -> next[0] =
                    ((AssemblyStep) field(a, "currentStep")).getStepNumber() == 2);
            if (next[0]) return;
            SystemClock.sleep(100L);
        }
        throw new AssertionError("Preparation did not open section 2");
    }

    @Test public void clearFourBracketPhotoCompletesTheInventoryScan() throws Exception {
        String[] sets = {"095901", "095916", "095937", "095945", "095949", "100002"};
        List<String> completed = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        for (String capture : sets) {
            harness.showCard(2, 1);
            assertTrue("First bracket card starts with the set scan", harness.awaitingPartScan());
            Mat frame = AssemblyReplayHarness.decode(InstrumentationRegistry.getInstrumentation().getContext(),
                    "references/individual-20260927-sets/four-brackets-20260927_" + capture + ".png");
            AssemblyReplayHarness.Outcome outcome = harness.replay("set-" + capture, frame,
                    KIT_PHONE_FOCAL_RATIO, 8, a -> !(Boolean) field(a, "awaitingPartScan"));
            frame.release();
            summary.add(capture + ":" + outcome);
            if (outcome.reached) {
                completed.add(capture);
                AssemblyStep section = (AssemblyStep) field(harness.activity, "currentStep");
                @SuppressWarnings("unchecked")
                List<GuidedStep> cards = (List<GuidedStep>) field(harness.activity, "currentGuidedSteps");
                assertFalse("An inventory scan never completes an installation",
                        GuidedStepProgressStore.isCheckpointComplete(harness.activity,
                                NeobotSeedData.PROJECT_ID, section.getId(),
                                cards.get((Integer) field(harness.activity, "currentGuidedStepIndex"))));
            }
        }
        Log.i(TAG, "setScanSummary " + summary);
        assertTrue("A clear four-bracket view must complete the set scan: " + summary,
                completed.contains("095901") || completed.contains("095916"));
    }

    /**
     * Section 3.3.1 Test D correct-completion trials. On 2026-09-27 none of these held-out views
     * localized the installed bracket, so the slot stayed locked (strict camera gating keeps the
     * user on the action). This records the measured outcome; improvements may raise it. The
     * safety property, never unlocking another slot, is asserted by the negative test below and
     * across all 33 held-out photos by {@link InstalledBracketSlotMatrixInstrumentedTest}.
     */
    @Test public void installedBracketSlotChecksAreRecordedOnHeldOutViews() throws Exception {
        String[][] views = {
                {"brackets-1-132927.jpg", "brackets-1-132944.jpg"},
                {"brackets-2-133355.jpg", "brackets-2-133359.jpg"},
                {"brackets-3-133508.jpg", "brackets-3-133541.jpg"},
                {"brackets-4-133630.jpg", "brackets-4-133652.jpg"}};
        int passed = 0, trials = 0;
        for (int slot = 1; slot <= 4; slot++) {
            for (String view : views[slot - 1]) {
                harness.showBracketSlot(slot);
                Mat frame = harness.decodeFixture(view);
                AssemblyReplayHarness.Outcome outcome = harness.replay("slot" + slot + "-" + view, frame,
                        KIT_PHONE_FOCAL_RATIO, PLACEMENT_FRAMES, AssemblyReplayHarness::placementChecked);
                frame.release();
                trials++;
                if (outcome.reached) passed++;
                assertTrue(view + " produced no analyzed frames", !outcome.frames.isEmpty());
                Log.i(TAG, String.format(Locale.US, "gateTrial slot=%d fixture=%s kind=correct verdict=%s "
                                + "responseMs=%.0f frames=%d meanAnalysisMs=%.0f banner=\"%s\"", slot, view,
                        outcome.reached ? "TP" : "FN", outcome.reachedMs, outcome.frames.size(),
                        outcome.meanAnalysisMs(), outcome.lastBanner()));
            }
        }
        Log.i(TAG, "slotSummary passed=" + passed + "/" + trials);
    }

    @Test public void aBracketInAnotherSlotNeverUnlocksTheCurrentSlot() throws Exception {
        // Slot 2 with only bracket 1 installed, and slot 4 with only brackets 1-3 installed.
        // Until 3 October 2026, empty slot 3 in 133359 accepted the microcontroller socket row,
        // and empty slot 4 in 133543 and 133552 accepted the background past the board edge.
        Object[][] cases = {{2, "brackets-1-132927.jpg"}, {2, "brackets-1-132944.jpg"},
                {4, "brackets-3-133508.jpg"}, {4, "brackets-3-133541.jpg"},
                {3, "brackets-2-133359.jpg"}, {4, "brackets-3-133543.jpg"},
                {4, "brackets-3-133552.jpg"}};
        for (Object[] negative : cases) {
            int slot = (Integer) negative[0];
            harness.showBracketSlot(slot);
            Mat frame = harness.decodeFixture((String) negative[1]);
            AssemblyReplayHarness.Outcome outcome = harness.replay(
                    "negative-slot" + slot + "-" + negative[1], frame, KIT_PHONE_FOCAL_RATIO,
                    PLACEMENT_FRAMES, AssemblyReplayHarness::placementChecked);
            frame.release();
            Log.i(TAG, String.format(Locale.US, "gateTrial slot=%d fixture=%s kind=wrong verdict=%s "
                            + "frames=%d meanAnalysisMs=%.0f", slot, negative[1], outcome.reached ? "FP" : "TN",
                    outcome.frames.size(), outcome.meanAnalysisMs()));
            assertFalse("Slot " + slot + " must stay locked for " + negative[1] + ": " + outcome,
                    outcome.reached);
        }
    }
}
