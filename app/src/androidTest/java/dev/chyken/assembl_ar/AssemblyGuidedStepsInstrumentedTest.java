package dev.chyken.assembl_ar;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.workflow.AssemblyState;
import dev.chyken.assembl_ar.workflow.AssemblyStateMachine;
import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;
import dev.chyken.assembl_ar.workflow.GuidedCheckpoint;
import dev.chyken.assembl_ar.workflow.GuidedRecognitionCatalog;
import dev.chyken.assembl_ar.workflow.GuidedStepProgressStore;
import dev.chyken.assembl_ar.vision.AssemblyVisionAnalyzer;
import dev.chyken.assembl_ar.vision.MotorAssemblyObservation;
import dev.chyken.assembl_ar.vision.RecognitionRequest;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.VisionPoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Verifies that distinct assembly actions have independent recognition and confirmation gates. */
@RunWith(AndroidJUnit4.class)
public final class AssemblyGuidedStepsInstrumentedTest {
    @Test
    public void stableLooseScanStartsDemoWithoutCompletingInstallationOrNeedingNext() {
        Context context = ApplicationProvider.getApplicationContext();
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                CheckpointFixture fixture = configureScanCheckpoint(activity, 2, 1);
                assertTrue((Boolean) field(activity, "awaitingPartScan"));
                assertNull(recognitionRequest(activity).getSpatialAnchorComponentId());
                assertEquals(View.GONE, activity.findViewById(R.id.assemblyDetails).getVisibility());
                fixture.deliverMatchingFrames(3);
                assertTrue((Boolean) field(activity, "awaitingPartScan"));
                fixture.deliverMatchingFrames(1);
                assertFalse((Boolean) field(activity, "awaitingPartScan"));
                assertEquals(activity.getString(R.string.demo_stage_follow), text(activity, R.id.demoStage));
                assertEquals(NeobotSeedData.MAIN_BOARD,
                        recognitionRequest(activity).getSpatialAnchorComponentId());
                assertEquals(0, field(activity, "currentGuidedStepIndex"));
                assertEquals(View.GONE, activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                assertFalse(activity.findViewById(R.id.nextGuidedStepButton).isEnabled());
                assertFalse(GuidedStepProgressStore.isCheckpointComplete(activity,
                        fixture.section.getProjectId(), fixture.section.getId(), fixture.card));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            java.util.concurrent.CountDownLatch presented = new java.util.concurrent.CountDownLatch(1);
            scenario.onActivity(activity -> {
                View decor = activity.getWindow().getDecorView();
                decor.getViewTreeObserver().registerFrameCommitCallback(() ->
                        decor.postOnAnimation(() -> decor.postOnAnimation(presented::countDown)));
                decor.invalidate();
            });
            try { assertTrue(presented.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            // ActivityScenario transitions can leave the empty invoker surface in the
            // compositor for a few frames even after the app's own frame is committed.
            for (int attempt = 0; attempt < 20 && !hasDarkAssemblyPanel(screenshot); attempt++) {
                screenshot.recycle();
                SystemClock.sleep(150);
                screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                assertNotNull(screenshot);
            }
            assertTrue("Capture must show the assembly panel", hasDarkAssemblyPanel(screenshot));
            File directory = context.getExternalFilesDir("demo-previews");
            assertNotNull(directory);
            assertTrue(directory.isDirectory() || directory.mkdirs());
            try (FileOutputStream output = new FileOutputStream(new File(directory, "scan-to-demo-screen.png"))) {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
            } catch (java.io.IOException failure) { throw new AssertionError(failure); }
            finally { screenshot.recycle(); }
        } finally {
            GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        }
    }

    @Test
    public void wrongMissingAndStaleScansCannotStartDemoAndRelockDiscardsScanStreak() {
        Context context = ApplicationProvider.getApplicationContext();
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                CheckpointFixture fixture = configureScanCheckpoint(activity, 2, 1);
                fixture.deliverMatchingFrames(3);
                fixture.deliver(NeobotSeedData.GEAR_MOTOR, true);
                fixture.deliverMatchingFrames(3);
                assertTrue((Boolean) field(activity, "awaitingPartScan"));
                fixture.deliver(null, false);
                fixture.deliverMatchingFrames(3);
                assertTrue((Boolean) field(activity, "awaitingPartScan"));
                invoke(activity, "relockAssemblyBase");
                fixture.deliverMatchingFrames(3);
                assertTrue((Boolean) field(activity, "awaitingPartScan"));
                assertFalse((Boolean) invoke(activity, "shouldHandleRecognition",
                        new Class<?>[]{RecognitionResult.class},
                        assemblyResult((Long) field(activity, "acceptRecognitionAfterNanos") - 1, 2)));
                fixture.deliverMatchingFrames(1);
                assertFalse((Boolean) field(activity, "awaitingPartScan"));
            });
        } finally {
            GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        }
    }

    @Test
    public void replayAndDetailsNeverChangeScanOrCompletionRequirements() {
        Context context = ApplicationProvider.getApplicationContext();
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                CheckpointFixture fixture = configureScanCheckpoint(activity, 2, 1);
                activity.findViewById(R.id.replayDemoButton).performClick();
                assertTrue((Boolean) field(activity, "awaitingPartScan"));
                activity.findViewById(R.id.assemblyDetailsButton).performClick();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.assemblyDetails).getVisibility());
                assertTrue(text(activity, R.id.actionFullInstructions)
                        .contains(fixture.card.getPrimaryInstruction().getText()));
                fixture.deliverMatchingFrames(4);
                activity.findViewById(R.id.replayDemoButton).performClick();
                assertFalse((Boolean) field(activity, "awaitingPartScan"));
                assertFalse(GuidedStepProgressStore.isCheckpointComplete(activity,
                        fixture.section.getProjectId(), fixture.section.getId(), fixture.card));
                assertEquals(View.GONE, activity.findViewById(R.id.confirmPlacementButton).getVisibility());

                fixture = configureScanCheckpoint(activity, 2, 8);
                assertFalse("Physical operations show their illustration immediately",
                        (Boolean) field(activity, "awaitingPartScan"));
                assertEquals(activity.getString(R.string.demo_stage_follow), text(activity, R.id.demoStage));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                activity.findViewById(R.id.replayDemoButton).performClick();
                assertFalse(GuidedStepProgressStore.isCheckpointComplete(activity,
                        fixture.section.getProjectId(), fixture.section.getId(), fixture.card));
            });
        } finally {
            GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        }
    }

    @Test
    public void boardIdentityAutomaticallyCompletesPreparationWithoutDialog() {
        Context context = ApplicationProvider.getApplicationContext();
        AssemblArRepository.getInstance(context).resetProjectProgress(NeobotSeedData.PROJECT_ID);
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                CheckpointFixture fixture = configureScanCheckpoint(activity, 1, 1);
                assertEquals(1, GuidedAssemblySequence.forManualStep(fixture.section).size());
                fixture.deliverMatchingFrames(4);
                assertTrue((Boolean) field(activity, "completionInFlight"));
                fixture.deliverMatchingFrames(1);
                assertNull(field(activity, "physicalValidationDialog"));
                assertEquals(View.GONE, activity.findViewById(R.id.confirmPlacementButton).getVisibility());
            });
            awaitSection(scenario, 2);
        } finally {
            GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
            AssemblArRepository.getInstance(context).resetProjectProgress(NeobotSeedData.PROJECT_ID);
        }
    }

    @Test
    public void savedBoardIdentityFinishesPreparationWithoutRepeatingScanOrSurfaceCheck() {
        Context context = ApplicationProvider.getApplicationContext();
        AssemblArRepository.getInstance(context).resetProjectProgress(NeobotSeedData.PROJECT_ID);
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                CheckpointFixture fixture = configureScanCheckpoint(activity, 1, 1);
                GuidedStepProgressStore.markCheckpointComplete(activity,
                        fixture.section.getProjectId(), fixture.section.getId(), fixture.card);
                invoke(activity, "renderGuidedStep");
                assertTrue((Boolean) field(activity, "completionInFlight"));
                assertNull(field(activity, "physicalValidationDialog"));
            });
            awaitSection(scenario, 2);
        } finally {
            GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
            AssemblArRepository.getInstance(context).resetProjectProgress(NeobotSeedData.PROJECT_ID);
        }
    }

    @Test
    public void requiredReferenceAndQuantityAreVisibleAndSingleBracketCannotCompleteSet() {
        Context context = ApplicationProvider.getApplicationContext();
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                CheckpointFixture fixture = configureScanCheckpoint(activity, 2, 1);
                assertEquals(View.VISIBLE, activity.findViewById(R.id.requiredPartReference).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.assemblyDetails).getVisibility());
                assertEquals("Gear-motor bracket", text(activity, R.id.requiredPartName));
                assertEquals("references/instructional/gray/motor-bracket-half-gray.webp",
                        activity.findViewById(R.id.requiredPartThumbnail).getTag());
                assertTrue(text(activity, R.id.requiredPartQuantity).contains("4"));
                assertEquals(4, recognitionRequest(activity).getExpectedInstanceCount());
                fixture.looseBracketCount = 1;
                fixture.deliverMatchingFrames(8);
                assertTrue((Boolean) field(activity, "awaitingPartScan"));
                fixture.looseBracketCount = 3;
                fixture.deliverMatchingFrames(4);
                assertTrue((Boolean) field(activity, "awaitingPartScan"));
                fixture.looseBracketCount = 4;
                fixture.deliverMatchingFrames(3);
                assertTrue((Boolean) field(activity, "awaitingPartScan"));
                fixture.deliverMatchingFrames(1);
                assertFalse((Boolean) field(activity, "awaitingPartScan"));
                assertFalse(GuidedStepProgressStore.isCheckpointComplete(activity,
                        fixture.section.getProjectId(), fixture.section.getId(), fixture.card));
            });
        } finally {
            GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        }
    }

    private static void awaitSection(ActivityScenario<AssemblyActivity> scenario, int expected) {
        boolean[] ready = {false};
        for (int attempt = 0; attempt < 60; attempt++) {
            scenario.onActivity(activity -> ready[0] =
                    ((AssemblyStep) field(activity, "currentStep")).getStepNumber() == expected);
            if (ready[0]) return;
            SystemClock.sleep(100);
        }
        fail("Automatic board recognition did not advance the section");
    }

    @Test
    public void motorSectionStartsWithBracketAndAdvancesToFinalMotorValidation() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, AssemblyActivity.class)
                .putExtra(AssemblyActivity.EXTRA_SIMULATED_STATE, AssemblyActivity.SIMULATE_MISMATCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<AssemblyActivity> scenario = ActivityScenario.launch(intent)) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                @SuppressWarnings("unchecked")
                List<AssemblyStep> steps = (List<AssemblyStep>) field(activity, "steps");
                AssemblyStep motorSection = steps.get(1);
                GuidedStepProgressStore.clearStep(activity,
                        motorSection.getProjectId(), motorSection.getId());
                setField(activity, "currentStepIndex", 1);
                setField(activity, "currentStep", motorSection);
                setField(activity, "stateMachine", new AssemblyStateMachine(steps.size(), 1));
                invoke(activity, "renderStep");

                assertEquals("Guided step 2 of 128", text(activity, R.id.stepCounter));
                assertEquals("Insert bracket 1 · left outer", text(activity, R.id.stepTitle));
                assertTrue(text(activity, R.id.manualPageLabel).contains("3"));
                assertEquals("references/instructional/gray/motor-bracket-half-gray.webp",
                        activity.findViewById(R.id.manualThumbnail).getTag());
                assertTrue(activity.findViewById(R.id.guidedStepNavigationRow).isShown());
                assertFalse(activity.findViewById(R.id.previousGuidedStepButton).isEnabled());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.motorInspectionRow).getVisibility());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                assertEquals(NeobotSeedData.MOTOR_BRACKET,
                        field(activity, "activeCheckpointComponentId"));
                assertEquals(NeobotSeedData.MOTOR_BRACKET,
                        recognitionRequest(activity).getExpectation().getComponentId());
                assertFalse(activity.findViewById(R.id.nextGuidedStepButton).isEnabled());
                activity.findViewById(R.id.nextGuidedStepButton).performClick();
                invoke(activity, "moveGuidedStep", new Class<?>[] {int.class}, 1);
                assertEquals("Guided step 2 of 128", text(activity, R.id.stepCounter));

                // A completion commit in flight must make navigation a no-op.
                markCurrentCheckpointComplete(activity);
                setField(activity, "completionInFlight", true);
                clickNext(activity, 1);
                assertEquals("Guided step 2 of 128", text(activity, R.id.stepCounter));
                setField(activity, "completionInFlight", false);

                activity.findViewById(R.id.nextGuidedStepButton).performClick();
                assertEquals("Guided step 3 of 128", text(activity, R.id.stepCounter));
                assertEquals("Insert bracket 2 · left inner", text(activity, R.id.stepTitle));
                assertTrue(text(activity, R.id.manualPageLabel).contains("3"));

                clickNext(activity, 3);
                assertEquals("Guided step 6 of 128", text(activity, R.id.stepCounter));
                assertEquals("Place the left motor", text(activity, R.id.stepTitle));
                assertTrue(text(activity, R.id.manualPageLabel).contains("4"));
                assertEquals("references/instructional/gray/gear-motor-gray.webp",
                        activity.findViewById(R.id.manualThumbnail).getTag());

                // Re-rendering the parent section restores the same stable authored card.
                invoke(activity, "renderStep");
                assertEquals("Guided step 6 of 128", text(activity, R.id.stepCounter));
                assertEquals("Place the left motor", text(activity, R.id.stepTitle));

                clickNext(activity, 10);
                assertEquals("Guided step 16 of 128", text(activity, R.id.stepCounter));
                assertEquals("Place the right motor", text(activity, R.id.stepTitle));
                assertTrue(text(activity, R.id.actionFullInstructions)
                        .contains("following cards expand that repeat"));
                assertTrue(text(activity, R.id.manualPageLabel).contains("4"));
                assertTrue(text(activity, R.id.manualPageLabel).contains("8"));

                clickNext(activity, 10);
                assertEquals("Guided step 26 of 128", text(activity, R.id.stepCounter));
                assertTrue(text(activity, R.id.actionFullInstructions)
                        .startsWith("Compare both completed motor assemblies"));
                assertTrue(text(activity, R.id.manualPageLabel).contains("8"));
                assertEquals("references/instructional/gray/s02-05-both-motors-gray.webp",
                        activity.findViewById(R.id.manualThumbnail).getTag());
                assertEquals(View.INVISIBLE,
                        activity.findViewById(R.id.nextGuidedStepButton).getVisibility());
                assertTrue(activity.findViewById(R.id.motorInspectionRow).isShown());
                assertTrue(recognitionRequest(activity).inspectsInstalledMotors());
            });
        } finally {
            GuidedStepProgressStore.clearProject(context,
                    dev.chyken.assembl_ar.data.NeobotSeedData.PROJECT_ID);
        }
    }

    @Test
    public void savedCursorFollowsStableCardIdentityAndResetsWhenCardIsRemoved() {
        Context context = ApplicationProvider.getApplicationContext();
        AssemblyStep motorSection = NeobotSeedData.getAssemblySteps().get(1);
        List<GuidedStep> cards = GuidedAssemblySequence.forManualStep(motorSection);
        String projectId = motorSection.getProjectId();
        String stepId = motorSection.getId();
        GuidedStepProgressStore.clearStep(context, projectId, stepId);
        try {
            GuidedStep saved = cards.get(5);
            GuidedStepProgressStore.write(context, projectId, stepId, saved);
            assertEquals(5, GuidedStepProgressStore.read(
                    context, projectId, stepId, cards));

            List<GuidedStep> shifted = new ArrayList<>(cards.subList(1, cards.size()));
            assertEquals(4, GuidedStepProgressStore.read(
                    context, projectId, stepId, shifted));

            List<GuidedStep> withoutSavedCard = new ArrayList<>(cards.subList(0, 5));
            assertEquals(0, GuidedStepProgressStore.read(
                    context, projectId, stepId, withoutSavedCard));
        } finally {
            GuidedStepProgressStore.clearStep(context, projectId, stepId);
        }
    }

    @Test
    public void recognitionGateRejectsFramesCapturedBeforeEachGuidedCheckpoint() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, AssemblyActivity.class)
                .putExtra(AssemblyActivity.EXTRA_SIMULATED_STATE, AssemblyActivity.SIMULATE_MISMATCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try (ActivityScenario<AssemblyActivity> scenario = ActivityScenario.launch(intent)) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                @SuppressWarnings("unchecked")
                List<AssemblyStep> steps = (List<AssemblyStep>) field(activity, "steps");
                AssemblyStep motorSection = steps.get(1);
                GuidedStepProgressStore.clearStep(activity,
                        motorSection.getProjectId(), motorSection.getId());
                setField(activity, "currentStepIndex", 1);
                setField(activity, "currentStep", motorSection);
                setField(activity, "stateMachine", new AssemblyStateMachine(steps.size(), 1));
                invoke(activity, "renderStep");
                long staleTimestamp = SystemClock.elapsedRealtimeNanos();

                @SuppressWarnings("unchecked")
                List<GuidedStep> cards = (List<GuidedStep>) field(activity, "currentGuidedSteps");
                setField(activity, "currentGuidedStepIndex", 1);
                invoke(activity, "renderGuidedStep");
                long threshold = (Long) field(activity, "acceptRecognitionAfterNanos");

                RecognitionResult stale = assemblyResult(staleTimestamp, 2);
                assertFalse((Boolean) invoke(activity, "shouldHandleRecognition",
                        new Class<?>[] {RecognitionResult.class}, stale));
                assertEquals(NeobotSeedData.MOTOR_BRACKET,
                        field(activity, "activeCheckpointComponentId"));

                RecognitionResult fresh = assemblyResult(
                        Math.max(SystemClock.elapsedRealtimeNanos(), threshold) + 1L, 2);
                assertTrue((Boolean) invoke(activity, "shouldHandleRecognition",
                        new Class<?>[] {RecognitionResult.class}, fresh));
                assertFalse((Boolean) invoke(activity, "shouldHandleRecognition",
                        new Class<?>[] {RecognitionResult.class},
                        assemblyResult(fresh.getFrameTimestampNanos(), 0)));

                long beforeFinal = SystemClock.elapsedRealtimeNanos();
                markPrecedingCheckpointsComplete(activity, motorSection, cards);
                setField(activity, "currentGuidedStepIndex", cards.size() - 1);
                invoke(activity, "renderGuidedStep");
                assertFalse((Boolean) invoke(activity, "shouldHandleRecognition",
                        new Class<?>[] {RecognitionResult.class},
                        assemblyResult(beforeFinal, 2)));
                threshold = (Long) field(activity, "acceptRecognitionAfterNanos");
                assertTrue((Boolean) invoke(activity, "shouldHandleRecognition",
                        new Class<?>[] {RecognitionResult.class},
                        assemblyResult(threshold + 1L, 2)));
            });
        } finally {
            GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        }
    }

    @Test
    public void bracketInsertionRequiresTheBoardAndFourStableFramesInItsOwnSlot() {
        Context context = ApplicationProvider.getApplicationContext();
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                CheckpointFixture fixture = configureMotorCheckpoint(activity, 1);
                // Identifying the loose bracket cannot prove that it was inserted in the board.
                fixture.deliverMatchingFrames(8);
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());

                fixture.deliverBoardFrames(4);
                fixture.deliverTargetFrames(6);
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                fixture.deliverWrongSlot();
                fixture.deliverTargetFrames(6);
                assertEquals("A bracket in another slot must discard the placement streak",
                        View.GONE, activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                fixture.deliverTargetFrames(1);

                assertEquals(AssemblyState.PHYSICAL_CONFIRMATION,
                        ((AssemblyStateMachine) field(activity, "stateMachine")).snapshot().getState());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                assertFalse(activity.findViewById(R.id.nextGuidedStepButton).isEnabled());
                assertEquals(0, field(activity, "currentGuidedStepIndex"));
                assertFalse(GuidedStepProgressStore.isCheckpointComplete(activity,
                        fixture.section.getProjectId(), fixture.section.getId(), fixture.card));

                fixture.deliver(null, false);
                assertEquals("Losing the installed bracket revokes readiness before confirmation",
                        View.GONE, activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                assertFalse(((AssemblyStateMachine) field(activity, "stateMachine"))
                        .snapshot().getState() == AssemblyState.PHYSICAL_CONFIRMATION);
            });
        } finally {
            GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        }
    }

    @Test
    public void slowDeviceAnalysisStillCompletesAPlacementButCameraSilenceDoesNot() {
        Context context = ApplicationProvider.getApplicationContext();
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                // Each frame is captured 2.6 s after the last one because analysis took 2.55 s.
                CheckpointFixture slow = configureMotorCheckpoint(activity, 1);
                slow.frameIntervalNanos = 2_600_000_000L;
                slow.processingNanos = 2_550_000_000L;
                slow.deliverBoardFrames(4);
                slow.deliverTargetFrames(7);
                assertEquals("A live but slow camera must still complete the check", View.VISIBLE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());

                // The same capture spacing with no processing is camera silence and resets.
                CheckpointFixture silent = configureMotorCheckpoint(activity, 1);
                silent.frameIntervalNanos = 2_600_000_000L;
                silent.deliverBoardFrames(4);
                silent.deliverTargetFrames(7);
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
            });
        } finally {
            GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        }
    }

    @Test
    public void missingBracketReferenceHasExplicitCoverageGapAndCannotUnlockPlacement() {
        Context context = ApplicationProvider.getApplicationContext();
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                CheckpointFixture fixture = configureMotorCheckpoint(activity, 1);
                setField(activity, "availableRecognitionIds", new java.util.HashSet<>(Arrays.asList(
                        NeobotSeedData.MAIN_BOARD, NeobotSeedData.GEAR_MOTOR)));
                invoke(activity, "renderGuidedStep");
                assertNull(recognitionRequest(activity).getExpectation());
                fixture.deliverBoardFrames(4);
                fixture.deliverTargetFrames(7);
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                assertFalse(activity.findViewById(R.id.nextGuidedStepButton).isEnabled());
                assertTrue(text(activity, R.id.recognitionText).contains("No usable camera reference"));
            });
        } finally {
            GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        }
    }

    @Test
    public void sectionReadinessExpiresUntilPhysicalCheckActuallyStarts() {
        AssemblyStep section = NeobotSeedData.getAssemblySteps().get(2);
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                setField(activity, "currentStepIndex", 2);
                setField(activity, "currentStep", section);
                AssemblyStateMachine machine = new AssemblyStateMachine(18, 2);
                setField(activity, "stateMachine", machine);
                invoke(activity, "renderStep");
                markPrecedingCheckpointsComplete(activity, section,
                        GuidedAssemblySequence.forManualStep(section));
                setField(activity, "currentGuidedStepIndex",
                        GuidedAssemblySequence.forManualStep(section).size() - 1);
                invoke(activity, "renderGuidedStep");
                machine.onComponentRecognized(NeobotSeedData.MOTOR_DRIVER, true);
                machine.onPoseEstimated(true);
                machine.beginGuidance();
                machine.onVisualValidation(true, true, true);
                machine.requestPhysicalConfirmation();
                ((Runnable) field(activity, "expireSectionReadiness")).run();
                assertEquals(AssemblyState.SEEKING_COMPONENT, machine.snapshot().getState());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());

                machine.onComponentRecognized(NeobotSeedData.MOTOR_DRIVER, true);
                machine.onPoseEstimated(true);
                machine.beginGuidance();
                machine.onVisualValidation(true, true, true);
                machine.requestPhysicalConfirmation();
                setField(activity, "physicalCheckInProgress", true);
                ((Runnable) field(activity, "expireSectionReadiness")).run();
                assertEquals(AssemblyState.PHYSICAL_CONFIRMATION, machine.snapshot().getState());
            });
        } finally {
            GuidedStepProgressStore.clearStep(ApplicationProvider.getApplicationContext(),
                    section.getProjectId(), section.getId());
        }
    }

    @Test
    public void physicalConfirmationCompletesOnlyThisBracketAndNextBracketNeedsFreshRecognition() {
        Context context = ApplicationProvider.getApplicationContext();
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                CheckpointFixture fixture = configureMotorCheckpoint(activity, 1);
                fixture.deliverBoardFrames(4);
                fixture.deliverTargetFrames(7);
                activity.findViewById(R.id.confirmPlacementButton).performClick();
                AlertDialog dialog = (AlertDialog) field(activity, "physicalValidationDialog");
                assertNotNull(dialog);
                assertTrue(dialog.isShowing());
                assertTrue(((TextView) dialog.findViewById(R.id.validationBody)).getText()
                        .toString().contains(fixture.card.getPrimaryInstruction().getText()));
                assertFalse(dialog.findViewById(R.id.confirmNextButton).isEnabled());
                ((CheckBox) dialog.findViewById(R.id.validationCheckbox)).setChecked(true);
                dialog.findViewById(R.id.confirmNextButton).performClick();

                assertEquals(fixture.section, field(activity, "currentStep"));
                assertEquals(1, field(activity, "currentStepIndex"));
                assertEquals(1, field(activity, "currentGuidedStepIndex"));
                assertEquals("Guided step 3 of 128", text(activity, R.id.stepCounter));
                assertTrue(GuidedStepProgressStore.isCheckpointComplete(activity,
                        fixture.section.getProjectId(), fixture.section.getId(), fixture.card));
                assertFalse((Boolean) field(activity, "currentCheckpointComplete"));
                assertFalse(activity.findViewById(R.id.nextGuidedStepButton).isEnabled());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                assertEquals(NeobotSeedData.MOTOR_BRACKET,
                        field(activity, "activeCheckpointComponentId"));

                // The second insertion needs a new board lock and its own distinct slot.
                fixture.deliverBoardFrames(4);
                fixture.deliverTargetFrames(6);
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                fixture.deliverTargetFrames(1);
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());

                activity.findViewById(R.id.previousGuidedStepButton).performClick();
                assertEquals(0, field(activity, "currentGuidedStepIndex"));
                assertTrue((Boolean) field(activity, "currentCheckpointComplete"));
                assertTrue(activity.findViewById(R.id.nextGuidedStepButton).isEnabled());
                activity.findViewById(R.id.nextGuidedStepButton).performClick();
                assertEquals(1, field(activity, "currentGuidedStepIndex"));
                assertFalse(activity.findViewById(R.id.nextGuidedStepButton).isEnabled());
            });
        } finally {
            GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        }
    }

    @Test
    public void screwInsertionRequiresAnExplicitActionCheckWithoutScanningALooseScrew() {
        Context context = ApplicationProvider.getApplicationContext();
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                CheckpointFixture fixture = configureMotorCheckpoint(activity, 8);
                assertEquals(GuidedCheckpoint.Kind.PHYSICAL,
                        ((GuidedCheckpoint) field(activity, "activeCheckpoint")).getKind());
                setField(activity, "availableRecognitionIds", new java.util.HashSet<>(Arrays.asList(
                        NeobotSeedData.MAIN_BOARD, NeobotSeedData.GEAR_MOTOR)));
                invoke(activity, "renderGuidedStep");
                RecognitionRequest request = recognitionRequest(activity);
                assertNull(request.getExpectation());
                assertFalse(request.inspectsInstalledMotors());
                assertFalse((Boolean) field(activity, "guidedStepRecognitionEnabled"));
                assertEquals(View.GONE,
                        activity.findViewById(R.id.motorInspectionRow).getVisibility());
                assertFalse(activity.findViewById(R.id.nextGuidedStepButton).isEnabled());
                int cardIndex = (Integer) field(activity, "currentGuidedStepIndex");
                activity.findViewById(R.id.nextGuidedStepButton).performClick();
                invoke(activity, "moveGuidedStep", new Class<?>[] {int.class}, 1);
                assertEquals(cardIndex, field(activity, "currentGuidedStepIndex"));
                invoke(activity, "relockAssemblyBase");
                assertEquals("Relocking the camera cannot disable an authored physical check",
                        AssemblyState.PHYSICAL_CONFIRMATION,
                        ((AssemblyStateMachine) field(activity, "stateMachine")).snapshot().getState());
                fixture.deliverMatchingFrames(4);
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                assertFalse(GuidedStepProgressStore.isCheckpointComplete(activity,
                        fixture.section.getProjectId(), fixture.section.getId(), fixture.card));

                activity.findViewById(R.id.confirmPlacementButton).performClick();
                AlertDialog dialog = (AlertDialog) field(activity, "physicalValidationDialog");
                assertNotNull(dialog);
                assertTrue(((TextView) dialog.findViewById(R.id.validationBody)).getText()
                        .toString().contains(fixture.card.getPrimaryInstruction().getText()));
                assertFalse(dialog.findViewById(R.id.confirmNextButton).isEnabled());
                ((CheckBox) dialog.findViewById(R.id.validationCheckbox)).setChecked(true);
                assertFalse(GuidedStepProgressStore.isCheckpointComplete(activity,
                        fixture.section.getProjectId(), fixture.section.getId(), fixture.card));
                dialog.findViewById(R.id.confirmNextButton).performClick();
                assertTrue(GuidedStepProgressStore.isCheckpointComplete(activity,
                        fixture.section.getProjectId(), fixture.section.getId(), fixture.card));
                assertEquals(cardIndex + 1, field(activity, "currentGuidedStepIndex"));
            });
        } finally {
            GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        }
    }

    @Test
    public void wireConnectionRequiresItsInstructionToBePhysicallyConfirmed() {
        Context context = ApplicationProvider.getApplicationContext();
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                CheckpointFixture fixture = configureCheckpoint(activity, 5, 3);
                assertNull(recognitionRequest(activity).getExpectation());
                assertFalse((Boolean) field(activity, "guidedStepRecognitionEnabled"));
                assertFalse(activity.findViewById(R.id.nextGuidedStepButton).isEnabled());
                for (int index = 0; index < 4; index++) {
                    fixture.deliver(NeobotSeedData.MOTOR_DRIVER, true);
                }
                assertFalse(GuidedStepProgressStore.isCheckpointComplete(activity,
                        fixture.section.getProjectId(), fixture.section.getId(), fixture.card));
                activity.findViewById(R.id.confirmPlacementButton).performClick();
                AlertDialog dialog = (AlertDialog) field(activity, "physicalValidationDialog");
                assertNotNull(dialog);
                assertTrue(((TextView) dialog.findViewById(R.id.validationBody)).getText()
                        .toString().contains(fixture.card.getPrimaryInstruction().getText()));
                assertFalse(dialog.findViewById(R.id.confirmNextButton).isEnabled());
                ((CheckBox) dialog.findViewById(R.id.validationCheckbox)).setChecked(true);
                dialog.findViewById(R.id.confirmNextButton).performClick();
                assertTrue(GuidedStepProgressStore.isCheckpointComplete(activity,
                        fixture.section.getProjectId(), fixture.section.getId(), fixture.card));
            });
        } finally {
            GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        }
    }

    @Test
    public void firstMotorPlacementRequiresTheFirstInstalledSlotAndRevokesOnLoss() {
        Context context = ApplicationProvider.getApplicationContext();
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                CheckpointFixture fixture = configureMotorCheckpoint(activity, 5);
                RecognitionRequest request = recognitionRequest(activity);
                assertEquals(NeobotSeedData.GEAR_MOTOR, request.getExpectation().getComponentId());
                assertEquals(1, request.getExpectedInstanceCount());
                assertTrue(request.inspectsInstalledMotors());
                fixture.deliverMatchingFrames(10);
                assertEquals("A loose motor is insufficient for the insertion action", View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                fixture.deliverMotorFrames(false, false, 4);
                fixture.deliverMotorFrames(false, true, 6);
                assertEquals("The opposite motor cannot satisfy the first motor instruction", View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                fixture.deliverMotorFrames(true, false, 6);
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                fixture.deliverMotorFrames(false, true, 1);
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                assertFalse(GuidedStepProgressStore.isCheckpointComplete(activity,
                        fixture.section.getProjectId(), fixture.section.getId(), fixture.card));
            });
        } finally {
            GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        }
    }

    @Test
    public void secondMotorPlacementRequiresBothInstalledMotorsInTheSameFrame() {
        Context context = ApplicationProvider.getApplicationContext();
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                CheckpointFixture fixture = configureMotorCheckpoint(activity, 17);
                assertEquals(2, recognitionRequest(activity).getExpectedInstanceCount());
                assertTrue(recognitionRequest(activity).inspectsInstalledMotors());
                fixture.deliverMotorFrames(false, false, 4);
                for (int index = 0; index < 10; index++) {
                    fixture.deliverMotorFrames(index % 2 == 0, index % 2 != 0, 1);
                }
                assertEquals("Alternating single motors must never accumulate a complete pair", View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                fixture.deliverMotorFrames(true, true, 6);
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                fixture.deliverMotorFrames(false, true, 1);
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
            });
        } finally {
            GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        }
    }

    private static void awaitLoaded(ActivityScenario<AssemblyActivity> scenario) {
        AssertionError lastFailure = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                scenario.onActivity(activity -> {
                    assertTrue(text(activity, R.id.stepCounter).length() > 0);
                    assertTrue(activity.findViewById(R.id.previewView).getWidth() > 0);
                    assertTrue(activity.findViewById(R.id.previewView).getHeight() > 0);
                });
                return;
            } catch (AssertionError failure) {
                lastFailure = failure;
                SystemClock.sleep(100L);
            }
        }
        throw lastFailure == null ? new AssertionError("Assembly did not load") : lastFailure;
    }

    private static String text(AssemblyActivity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private static boolean hasDarkAssemblyPanel(Bitmap screenshot) {
        int dark = 0, sampled = 0;
        for (int y = screenshot.getHeight() / 4; y < screenshot.getHeight() * 3 / 4; y += 20) {
            for (int x = 10; x < screenshot.getWidth() - 10; x += 20) {
                int color = screenshot.getPixel(x, y);
                if (android.graphics.Color.red(color) < 60 && android.graphics.Color.green(color) < 60
                        && android.graphics.Color.blue(color) < 60) dark++;
                sampled++;
            }
        }
        return sampled > 0 && dark > sampled / 3;
    }

    private static void clickNext(AssemblyActivity activity, int count) {
        for (int index = 0; index < count; index++) {
            // This test covers authored card order; recognition gates are exercised separately.
            markCurrentCheckpointComplete(activity);
            activity.findViewById(R.id.nextGuidedStepButton).performClick();
        }
    }

    private static void markPrecedingCheckpointsComplete(
            AssemblyActivity activity, AssemblyStep section, List<GuidedStep> cards) {
        for (GuidedStep card : cards) {
            if (GuidedRecognitionCatalog.checkpointFor(section, card) != null) {
                GuidedStepProgressStore.markCheckpointComplete(activity,
                        section.getProjectId(), section.getId(), card);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void markCurrentCheckpointComplete(AssemblyActivity activity) {
        AssemblyStep section = (AssemblyStep) field(activity, "currentStep");
        List<GuidedStep> cards = (List<GuidedStep>) field(activity, "currentGuidedSteps");
        GuidedStep card = cards.get((Integer) field(activity, "currentGuidedStepIndex"));
        if (GuidedRecognitionCatalog.checkpointFor(section, card) != null) {
            GuidedStepProgressStore.markCheckpointComplete(activity,
                    section.getProjectId(), section.getId(), card);
            invoke(activity, "renderGuidedStep");
        }
    }

    private static ActivityScenario<AssemblyActivity> launch() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, AssemblyActivity.class)
                .putExtra(AssemblyActivity.EXTRA_SIMULATED_STATE, AssemblyActivity.SIMULATE_MISMATCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return ActivityScenario.launch(intent);
    }

    private static RecognitionRequest recognitionRequest(AssemblyActivity activity) {
        View preview = activity.findViewById(R.id.previewView);
        int width = preview.getWidth();
        int height = preview.getHeight();
        AssemblyVisionAnalyzer.FrameMetadata metadata = new AssemblyVisionAnalyzer.FrameMetadata(
                SystemClock.elapsedRealtimeNanos(), width, height, 0, 0, width, height,
                width, height, 0, true, new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1});
        return (RecognitionRequest) invoke(activity, "recognitionRequestFor",
                new Class<?>[] {AssemblyVisionAnalyzer.FrameMetadata.class}, metadata);
    }

    private static CheckpointFixture configureMotorCheckpoint(
            AssemblyActivity activity, int instructionOrder) {
        return configureCheckpoint(activity, 2, instructionOrder);
    }

    @SuppressWarnings("unchecked")
    private static CheckpointFixture configureCheckpoint(
            AssemblyActivity activity, int sectionNumber, int instructionOrder) {
        CheckpointFixture fixture = configureScanCheckpoint(activity, sectionNumber, instructionOrder);
        // Placement regressions begin after the new automatic loose-part introduction.
        if ((Boolean) field(activity, "awaitingPartScan")) fixture.deliverMatchingFrames(4);
        return fixture;
    }

    @SuppressWarnings("unchecked")
    private static CheckpointFixture configureScanCheckpoint(
            AssemblyActivity activity, int sectionNumber, int instructionOrder) {
        List<AssemblyStep> steps = (List<AssemblyStep>) field(activity, "steps");
        AssemblyStep section = steps.get(sectionNumber - 1);
        GuidedStepProgressStore.clearStep(activity, section.getProjectId(), section.getId());
        setField(activity, "currentStepIndex", sectionNumber - 1);
        setField(activity, "currentStep", section);
        setField(activity, "stateMachine", new AssemblyStateMachine(steps.size(), sectionNumber - 1));
        invoke(activity, "renderStep");
        List<GuidedStep> cards = (List<GuidedStep>) field(activity, "currentGuidedSteps");
        for (int index = 0; index < cards.size(); index++) {
            GuidedStep card = cards.get(index);
            if (card.getPrimaryInstruction().getOrder() == instructionOrder) {
                setField(activity, "currentGuidedStepIndex", index);
                invoke(activity, "renderGuidedStep");
                return new CheckpointFixture(activity, section, card);
            }
        }
        throw new AssertionError("Missing section " + sectionNumber + " instruction " + instructionOrder);
    }

    private static final class CheckpointFixture {
        final AssemblyActivity activity;
        final AssemblyStep section;
        final GuidedStep card;
        final List<VisionPoint> corners;
        final RecognitionResult.Pose pose;
        final RecognitionResult.SpatialAnchor anchor;
        long timestamp;
        int looseBracketCount = 4;
        long frameIntervalNanos = 100_000_000L;
        long processingNanos;

        CheckpointFixture(AssemblyActivity activity, AssemblyStep section, GuidedStep card) {
            this.activity = activity;
            this.section = section;
            this.card = card;
            View preview = activity.findViewById(R.id.previewView);
            int width = preview.getWidth();
            int height = preview.getHeight();
            setField(activity, "analyzedWidth", width);
            setField(activity, "analyzedHeight", height);
            corners = Arrays.asList(
                    new VisionPoint(width * 0.25, height * 0.25),
                    new VisionPoint(width * 0.75, height * 0.25),
                    new VisionPoint(width * 0.75, height * 0.75),
                    new VisionPoint(width * 0.25, height * 0.75));
            pose = new RecognitionResult.Pose(new double[] {0, 0, 0},
                    new double[] {0, 0, 0.5}, new float[] {
                    1, 0, 0, 0, 0, -1, 0, 0, 0, 0, -1, 0, 0, 0, -0.5f, 1
            });
            anchor = new RecognitionResult.SpatialAnchor(
                    "fixture-main-board", NeobotSeedData.MAIN_BOARD, corners, pose);
        }

        void deliverMatchingFrames(int count) {
            for (int index = 0; index < count; index++) {
                deliver(GuidedRecognitionCatalog.componentIdFor(section, card), true);
            }
        }

        void deliver(String componentId, boolean withPose) {
            deliver(componentId, withPose, corners, null, null);
        }

        void deliverBoardFrames(int count) {
            for (int index = 0; index < count; index++) {
                deliver(NeobotSeedData.MAIN_BOARD, true, corners, anchor, null);
            }
        }

        void deliverTargetFrames(int count) {
            for (int index = 0; index < count; index++) {
                deliver((String) field(activity, "activeCheckpointComponentId"), true,
                        targetCorners(), anchor, null);
            }
        }

        void deliverWrongSlot() {
            List<VisionPoint> wrongSlot = new ArrayList<>();
            double shift = (corners.get(1).getX() - corners.get(0).getX()) * 0.65;
            for (VisionPoint point : targetCorners()) {
                wrongSlot.add(new VisionPoint(point.getX() + shift, point.getY()));
            }
            deliver(NeobotSeedData.MOTOR_BRACKET, true, wrongSlot, anchor, null);
        }

        private List<VisionPoint> targetCorners() {
            GuidedCheckpoint checkpoint = (GuidedCheckpoint) field(activity, "activeCheckpoint");
            ChassisRelativePlacementGate gate = checkpoint.getPlacementGate();
            assertNotNull(gate);
            return gate.projectTargets(corners).get(0).getCorners();
        }

        void deliverMotorFrames(boolean first, boolean second, int count) {
            MotorAssemblyObservation observation = new MotorAssemblyObservation(
                    MotorAssemblyObservation.Quality.GOOD, slot(first), slot(second));
            for (int index = 0; index < count; index++) {
                deliver(NeobotSeedData.MAIN_BOARD, true, corners, anchor, observation);
            }
        }

        private static MotorAssemblyObservation.Slot slot(boolean present) {
            return present ? new MotorAssemblyObservation.Slot(
                    MotorAssemblyObservation.SlotState.PRESENT, 0.95,
                    "Motor installed in expected position", Collections.emptyList())
                    : MotorAssemblyObservation.Slot.missing("Motor not yet visible");
        }

        private void deliver(String componentId, boolean withPose, List<VisionPoint> detectedCorners,
                             RecognitionResult.SpatialAnchor spatialAnchor,
                             MotorAssemblyObservation motors) {
            timestamp = Math.max(SystemClock.elapsedRealtimeNanos() + 1L, timestamp + frameIntervalNanos);
            Object builder = invoke(RecognitionResult.class, "builder",
                    new Class<?>[] {long.class}, timestamp);
            if (componentId != null) {
                invoke(builder, "identity", new Class<?>[] {String.class, String.class, String.class},
                        "fixture-" + componentId, componentId, componentId);
                invoke(builder, "method", new Class<?>[] {RecognitionResult.DetectionMethod.class},
                        RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY);
                invoke(builder, "validation", new Class<?>[] {RecognitionResult.ValidationState.class},
                        componentId.equals(field(activity, "activeCheckpointComponentId"))
                                ? RecognitionResult.ValidationState.EXPECTED_COMPONENT
                                : RecognitionResult.ValidationState.COMPONENT_MISMATCH);
                invoke(builder, "confidence", new Class<?>[] {double.class}, 0.95);
                invoke(builder, "corners", new Class<?>[] {List.class}, detectedCorners);
                invoke(builder, "orientation", new Class<?>[] {
                        RecognitionResult.OrientationClassification.class, double.class},
                        RecognitionResult.OrientationClassification.UPRIGHT, 0.0);
                if (withPose) {
                    invoke(builder, "pose", new Class<?>[] {RecognitionResult.Pose.class}, pose);
                }
            }
            if (NeobotSeedData.MOTOR_BRACKET.equals(componentId)
                    && (Boolean) field(activity, "awaitingPartScan")) {
                List<RecognitionResult.InstanceObservation> pieces = new ArrayList<>();
                double width = activity.findViewById(R.id.previewView).getWidth();
                double height = activity.findViewById(R.id.previewView).getHeight();
                for (int index = 0; index < looseBracketCount; index++) {
                    double x = width * (.26 + index * .13);
                    pieces.add(new RecognitionResult.InstanceObservation("fixture-bracket-" + index,
                            NeobotSeedData.MOTOR_BRACKET, RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,
                            Arrays.asList(new VisionPoint(x, height*.35),
                                    new VisionPoint(x + width*.1, height*.35),
                                    new VisionPoint(x + width*.1, height*.45),
                                    new VisionPoint(x, height*.45)), .95));
                }
                invoke(builder, "instances", new Class<?>[]{List.class}, pieces);
            }
            RecognitionResult base = (RecognitionResult) invoke(builder, "build", new Class<?>[0]);
            RecognitionResult result = (RecognitionResult) invoke(base, "withAssemblyInspection",
                    new Class<?>[] {int.class, RecognitionResult.SpatialAnchor.class,
                            boolean.class, MotorAssemblyObservation.class},
                    section.getStepNumber(), spatialAnchor, false, motors);
            if (processingNanos > 0) {
                invoke(activity, "handleRecognition", new Class<?>[] {RecognitionResult.class, long.class},
                        result, timestamp + processingNanos);
            } else {
                invoke(activity, "handleRecognition", new Class<?>[] {RecognitionResult.class}, result);
            }
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not read " + name, failure);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not set " + name, failure);
        }
    }

    private static void invoke(Object target, String name) {
        invoke(target, name, new Class<?>[0]);
    }

    private static Object invoke(
            Object target,
            String name,
            Class<?>[] parameterTypes,
            Object... arguments) {
        try {
            Class<?> targetType = target instanceof Class<?> ? (Class<?>) target : target.getClass();
            Method method = targetType.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target instanceof Class<?> ? null : target, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not invoke " + name, failure);
        }
    }

    private static RecognitionResult assemblyResult(long timestampNanos, int manualStep) {
        Object builder = invoke(RecognitionResult.class, "builder",
                new Class<?>[] {long.class}, timestampNanos);
        RecognitionResult base = (RecognitionResult) invoke(
                builder, "build", new Class<?>[0]);
        return (RecognitionResult) invoke(base, "withAssemblyInspection",
                new Class<?>[] {int.class, RecognitionResult.SpatialAnchor.class,
                        boolean.class, dev.chyken.assembl_ar.vision.MotorAssemblyObservation.class},
                manualStep, null, false, null);
    }
}
