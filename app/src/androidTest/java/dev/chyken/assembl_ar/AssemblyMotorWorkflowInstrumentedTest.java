package dev.chyken.assembl_ar;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.MotorAssemblyObservation;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.workflow.AssemblyState;
import dev.chyken.assembl_ar.workflow.AssemblyStateMachine;
import dev.chyken.assembl_ar.workflow.MotorAssemblyGate;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;
import dev.chyken.assembl_ar.workflow.GuidedRecognitionCatalog;
import dev.chyken.assembl_ar.workflow.GuidedStepProgressStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Exercises installed-motor UI transitions without a camera or persisting assembly progress. */
@RunWith(AndroidJUnit4.class)
public final class AssemblyMotorWorkflowInstrumentedTest {
    private static final long MILLIS = 1_000_000L;

    @After public void clearFinalStageFixtureCheckpoints() {
        AssemblyStep section = NeobotSeedData.getAssemblySteps().get(1);
        GuidedStepProgressStore.clearStep(ApplicationProvider.getApplicationContext(),
                section.getProjectId(), section.getId());
    }

    @Test
    public void recognizedBoardBecomesTheBaseInsteadOfAWrongComponent() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                Fixture fixture = configureMotorStep(activity);
                fixture.lockEmptyBoard();
                assertNotEquals(AssemblyState.COMPONENT_MISMATCH, state(activity));
                assertFalse("The base must not be reported as the wrong motor",
                        text(activity, R.id.recognitionText).contains(" · use "));
                String status = text(activity, R.id.motorInspectionStatus);
                assertTrue(status.contains("Base"));
                assertTrue(status.contains("Left"));
                assertTrue(status.contains("Right"));
                assertTrue(activity.findViewById(R.id.motorInspectionStatus).isShown());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
            });
        }
    }

    @Test
    public void installedMotorsUseSixOfTenObservationsBeforeOfferingPhysicalCheck() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                Fixture fixture = configureMotorStep(activity);
                fixture.lockEmptyBoard();
                boolean[][] observations = {
                        {true, true}, {true, true}, {true, true}, {true, true},
                        {true, false}, {false, true}, {true, false}, {false, true},
                        {true, true}, {true, true}
                };
                for (int index = 0; index < observations.length; index++) {
                    fixture.deliver(observations[index][0], observations[index][1]);
                    assertEquals("Only six observations of the complete installed pair may unlock "
                                    + "the physical check; frame " + index,
                            index == observations.length - 1 ? View.VISIBLE : View.GONE,
                            activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                }
                assertEquals(AssemblyState.PHYSICAL_CONFIRMATION, state(activity));
                assertEquals("Visual readiness must not advance the saved or in-memory step",
                        1, getField(activity, "currentStepIndex"));
            });
        }
    }

    @Test
    public void sustainedSlowFramesKeepTheEmptyBoardLockedBeforeMotorConfirmation() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                Fixture fixture = configureMotorStep(activity);
                // Simulate more than thirty seconds of capture timestamps, without motors.
                fixture.frameIntervalMillis = 1200L;
                for (int index = 0; index < 30; index++) {
                    fixture.deliver(false, false);
                    assertEquals(Math.min(index + 1, 4),
                            getField(activity, "previousMotorAnchorFrames"));
                    assertEquals(View.GONE,
                            activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                }
                assertTrue(text(activity, R.id.motorInspectionStatus).contains("locked"));

                // Even at the supported two-second boundary, six current observations of
                // both motors together must remain attainable after the board has locked.
                fixture.frameIntervalMillis = 2000L;
                for (int index = 0; index < 6; index++) {
                    fixture.deliver(true, true);
                    assertEquals(4, getField(activity, "previousMotorAnchorFrames"));
                    assertEquals(index == 5 ? View.VISIBLE : View.GONE,
                            activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                }
                assertEquals(AssemblyState.PHYSICAL_CONFIRMATION, state(activity));
                assertEquals("A visual pass still requires the physical fastening check",
                        1, getField(activity, "currentStepIndex"));
            });
        }
    }

    @Test
    public void motorEvidenceTimeoutDoesNotResetBoardProgress() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                Fixture fixture = configureMotorStep(activity);
                fixture.makeReady();
                assertEquals(4, getField(activity, "previousMotorAnchorFrames"));
                ((Runnable) getField(activity, "expireMotorReadiness")).run();
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                assertNotEquals(AssemblyState.PHYSICAL_CONFIRMATION, state(activity));
                assertEquals(4, getField(activity, "previousMotorAnchorFrames"));

                // A current frame inside the board's own time bound retains its four-frame
                // lock, while expired motor evidence must start again at one observation.
                fixture.deliver(true, true);
                assertEquals(4, getField(activity, "previousMotorAnchorFrames"));
                assertTrue(text(activity, R.id.motorInspectionStatus).contains("locked"));
                assertTrue(text(activity, R.id.recognitionText).contains("1/6"));
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
            });
        }
    }

    @Test
    public void nextResultAfterOneSecondRetainsBoardAndMotorEvidence() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            Fixture[] fixture = new Fixture[1];
            scenario.onActivity(activity -> {
                fixture[0] = configureMotorStep(activity);
                fixture[0].makeReady();
            });
            // This interval used to run the one-second callback and clear the board gate.
            SystemClock.sleep(1300L);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                // A loaded emulator can delay this check past the two-second expiry. Judge the
                // expiry rule against the time that actually elapsed since the last result.
                long age = SystemClock.uptimeMillis() - (Long) getField(activity, "lastMotorResultAt");
                boolean withinWindow = age < MotorAssemblyGate.MAXIMUM_FRAME_GAP_MILLIS;
                assertEquals("Result age " + age + " ms", withinWindow ? View.VISIBLE : View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                if (!withinWindow) return;
                fixture[0].frameIntervalMillis = 1300L;
                fixture[0].deliver(true, true);
                assertEquals(4, getField(activity, "previousMotorAnchorFrames"));
                assertEquals(AssemblyState.PHYSICAL_CONFIRMATION, state(activity));
            });
        }
    }

    @Test
    public void losingOneMotorRevokesThePhysicalCheckBeforeItsDialogIsOpened() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                Fixture fixture = configureMotorStep(activity);
                fixture.makeReady();
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());

                fixture.deliver(true, false);
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                assertNotEquals(AssemblyState.PHYSICAL_CONFIRMATION, state(activity));
                assertEquals(1, getField(activity, "currentStepIndex"));
            });
        }
    }

    @Test
    public void acceptedSnapshotSurvivesTheWiggleCheckWhileItsDialogIsOpen() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            Fixture[] fixture = new Fixture[1];
            scenario.onActivity(activity -> {
                fixture[0] = configureMotorStep(activity);
                fixture[0].makeReady();
                activity.findViewById(R.id.confirmPlacementButton).performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                AlertDialog dialog = (AlertDialog) getField(activity, "physicalValidationDialog");
                assertNotNull("Ready motors should open the physical validation dialog", dialog);
                assertTrue(dialog.isShowing());
                TextView title = dialog.findViewById(R.id.validationTitle);
                assertNotNull(title);
                assertTrue(title.getText().toString().toLowerCase(Locale.ROOT).contains("wiggle"));
                View complete = dialog.findViewById(R.id.confirmNextButton);
                CheckBox checkbox = dialog.findViewById(R.id.validationCheckbox);
                assertNotNull(complete);
                assertNotNull(checkbox);
                assertFalse("A visual pass cannot check the physical-confirmation box",
                        checkbox.isChecked());
                assertFalse(complete.isEnabled());

                // Hands may cover or move the assembly during the requested physical test.
                fixture[0].deliver(false, false);
                assertTrue(dialog.isShowing());
                assertEquals(AssemblyState.PHYSICAL_CONFIRMATION, state(activity));
                checkbox.performClick();
                assertTrue(complete.isEnabled());
                assertEquals("Checking the box alone must not complete the step",
                        1, getField(activity, "currentStepIndex"));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                // Never click Confirm/Next: this test must not write project progress.
                ((AlertDialog) getField(activity, "physicalValidationDialog")).cancel();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertFalse((Boolean) getField(activity, "physicalCheckInProgress"));
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                assertNotEquals(AssemblyState.PHYSICAL_CONFIRMATION, state(activity));
                fixture[0].deliver(true, true);
                assertEquals("Cancelling must require fresh evidence before trying the check again",
                        View.GONE, activity.findViewById(R.id.confirmPlacementButton).getVisibility());
            });
        }
    }

    @Test
    public void opticalFlowAloneCannotOfferPhysicalConfirmation() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                Fixture fixture = configureMotorStep(activity);
                fixture.lockEmptyBoard();
                for (int index = 0; index < 10; index++) {
                    fixture.deliver(true, true, true);
                    assertEquals("Tracking must not independently confirm the board's identity",
                            View.GONE, activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                    assertNotEquals(AssemblyState.PHYSICAL_CONFIRMATION, state(activity));
                }
                fixture.deliver(true, false);
                assertEquals("Fresh board identity still requires both motors in the current frame",
                        View.GONE, activity.findViewById(R.id.confirmPlacementButton).getVisibility());
            });
        }
    }

    @Test
    public void cameraSilenceRevokesThePhysicalCheck() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            Fixture[] fixture = new Fixture[1];
            scenario.onActivity(activity -> {
                fixture[0] = configureMotorStep(activity);
                fixture[0].makeReady();
            });
            // Let the real posted expiry run; do not invoke its Runnable by reflection.
            assertEquals(2000L, MotorAssemblyGate.MAXIMUM_FRAME_GAP_MILLIS);
            SystemClock.sleep(2200L);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                assertNotEquals(AssemblyState.PHYSICAL_CONFIRMATION, state(activity));
                assertEquals("The progress row must not retain stale checked motors",
                        activity.getString(R.string.motor_inspection_initial),
                        text(activity, R.id.motorInspectionStatus));
                assertEquals(1, getField(activity, "currentStepIndex"));
                assertEquals("The motor timer must leave the board gate to evaluate its own age",
                        4, getField(activity, "previousMotorAnchorFrames"));
                fixture[0].frameIntervalMillis = 3001L;
                fixture[0].deliver(true, true);
                assertEquals("The next capture beyond three seconds must restart board stability",
                        1, getField(activity, "previousMotorAnchorFrames"));
                assertEquals("Camera silence must discard the earlier visual evidence",
                        View.GONE, activity.findViewById(R.id.confirmPlacementButton).getVisibility());
            });
        }
    }

    @Test
    public void pausedActivityRejectsLateMotorResultsAndResumesWithFreshEvidence() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            Fixture[] fixture = new Fixture[1];
            scenario.onActivity(activity -> {
                fixture[0] = configureMotorStep(activity);
                fixture[0].makeReady();
            });

            scenario.moveToState(Lifecycle.State.STARTED);
            scenario.onActivity(activity -> {
                assertFalse((Boolean) getField(activity, "activityResumed"));
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                // CameraX can deliver late frames while the Activity is still STARTED.
                for (int index = 0; index < 10; index++) fixture[0].deliver(true, true);
                assertNotEquals(AssemblyState.PHYSICAL_CONFIRMATION, state(activity));
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
            });

            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(activity -> {
                assertTrue((Boolean) getField(activity, "activityResumed"));
                assertEquals(activity.getString(R.string.motor_inspection_initial),
                        text(activity, R.id.motorInspectionStatus));
                // These represent NEW camera captures after resume. On a slower emulator the
                // fixture's +100ms clock can otherwise still predate the resume timestamp fence.
                fixture[0].timestamp = Math.max(fixture[0].timestamp, SystemClock.elapsedRealtimeNanos());
                fixture[0].deliver(true, true);
                assertEquals("Resuming must not inherit readiness from pre-pause or paused frames",
                        View.GONE, activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                fixture[0].makeReady();
                assertEquals(AssemblyState.PHYSICAL_CONFIRMATION, state(activity));
            });
        }
    }

    @Test
    public void stepTwoControlsRemainAvailableWhenInformationIsCollapsed() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                Fixture fixture = configureMotorStep(activity);
                fixture.lockEmptyBoard();
                for (int index = 0; index < 6; index++) fixture.deliver(true, false);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            captureScreenshot(scenario, "motor-step-expanded");
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.stepInfoScroll).isShown());
                activity.findViewById(R.id.toggleStepInfoButton).performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertFalse(activity.findViewById(R.id.stepInfoScroll).isShown());
                assertTrue(activity.findViewById(R.id.motorInspectionStatus).isShown());
                assertTrue(activity.findViewById(R.id.relockBaseButton).isShown());
                assertTrue(activity.findViewById(R.id.toggleStepInfoButton).isShown());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
            });
            captureScreenshot(scenario, "motor-step-collapsed");
        }
    }

    @Test
    public void relockClearsThePreviousMotorReadiness() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                Fixture fixture = configureMotorStep(activity);
                fixture.makeReady();
                View relock = activity.findViewById(R.id.relockBaseButton);
                assertTrue(relock.isShown());
                assertTrue(relock.performClick());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                assertNotEquals(AssemblyState.PHYSICAL_CONFIRMATION, state(activity));

                fixture.deliver(true, true);
                assertEquals("One fresh frame must not inherit the previous visual pass",
                        View.GONE, activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                assertNotEquals(AssemblyState.PHYSICAL_CONFIRMATION, state(activity));
            });
        }
    }

    private static ActivityScenario<AssemblyActivity> launch() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, AssemblyActivity.class)
                .putExtra(AssemblyActivity.EXTRA_SIMULATED_STATE, AssemblyActivity.SIMULATE_MISMATCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return ActivityScenario.launch(intent);
    }

    private static void awaitLoaded(ActivityScenario<AssemblyActivity> scenario) {
        AssertionError lastFailure = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                scenario.onActivity(activity -> {
                    assertTrue(text(activity, R.id.stepCounter).length() > 0);
                    assertTrue(text(activity, R.id.recognitionText).contains("use"));
                    assertTrue(activity.findViewById(R.id.previewView).getWidth() > 0);
                    assertNotNull(getField(activity, "stateMachine"));
                });
                return;
            } catch (AssertionError failure) {
                lastFailure = failure;
                SystemClock.sleep(100L);
            }
        }
        throw lastFailure == null ? new AssertionError("Assembly did not load") : lastFailure;
    }

    @SuppressWarnings("unchecked")
    private static Fixture configureMotorStep(AssemblyActivity activity) {
        List<AssemblyStep> steps = (List<AssemblyStep>) getField(activity, "steps");
        AssemblyStep step = steps.get(1);
        assertEquals(2, step.getStepNumber());
        assertTrue(step.getTitle().toLowerCase(Locale.ROOT).contains("motor"));
        setField(activity, "currentStepIndex", 1);
        setField(activity, "currentStep", step);
        setField(activity, "stateMachine", new AssemblyStateMachine(steps.size(), 1));
        call(activity, "renderStep", new Class<?>[0]);
        List<GuidedStep> guidedSteps = (List<GuidedStep>) getField(activity, "currentGuidedSteps");
        // These tests begin at the section's final inspection. Its earlier action checks are
        // separate prerequisites, not evidence supplied by the synthetic motor frames below.
        for (GuidedStep card : guidedSteps) {
            if (GuidedRecognitionCatalog.checkpointFor(step, card) != null) {
                GuidedStepProgressStore.markCheckpointComplete(activity,
                        step.getProjectId(), step.getId(), card);
            }
        }
        setField(activity, "currentGuidedStepIndex", guidedSteps.size() - 1);
        call(activity, "renderGuidedStep", new Class<?>[0]);
        View preview = activity.findViewById(R.id.previewView);
        setField(activity, "analyzedWidth", preview.getWidth());
        setField(activity, "analyzedHeight", preview.getHeight());
        return new Fixture(activity, step, preview.getWidth(), preview.getHeight());
    }

    private static final class Fixture {
        final AssemblyActivity activity;
        final String boardId;
        final RecognitionResult.SpatialAnchor anchor;
        long timestamp = SystemClock.elapsedRealtimeNanos();
        long frameIntervalMillis = 100L;

        Fixture(AssemblyActivity activity, AssemblyStep step, int width, int height) {
            this.activity = activity;
            boardId = (String) call(activity, "spatialAnchorComponentId",
                    new Class<?>[] { AssemblyStep.class }, step);
            List<VisionPoint> corners = Arrays.asList(
                    new VisionPoint(width * 0.20, height * 0.15),
                    new VisionPoint(width * 0.80, height * 0.15),
                    new VisionPoint(width * 0.80, height * 0.80),
                    new VisionPoint(width * 0.20, height * 0.80));
            RecognitionResult.Pose pose = new RecognitionResult.Pose(new double[] {0, 0, 0},
                    new double[] {0, 0, 0.5}, new float[] {
                    1, 0, 0, 0, 0, -1, 0, 0, 0, 0, -1, 0, 0, 0, -0.5f, 1
            });
            anchor = new RecognitionResult.SpatialAnchor(
                    "fixture-main-board", boardId, corners, pose);
        }

        void lockEmptyBoard() {
            for (int index = 0; index < 4; index++) {
                deliver(false, false);
            }
        }

        void makeReady() {
            lockEmptyBoard();
            for (int index = 0; index < 6; index++) {
                deliver(true, true);
            }
            assertEquals(AssemblyState.PHYSICAL_CONFIRMATION, state(activity));
        }

        void deliver(boolean left, boolean right) {
            deliver(left, right, false);
        }

        void deliver(boolean left, boolean right, boolean opticalFlowOnly) {
            timestamp += frameIntervalMillis * MILLIS;
            MotorAssemblyObservation observation = new MotorAssemblyObservation(
                    MotorAssemblyObservation.Quality.GOOD, slot(left), slot(right));
            Object builder = call(RecognitionResult.class, "builder",
                    new Class<?>[] {long.class}, timestamp);
            call(builder, "identity", new Class<?>[] {String.class, String.class, String.class},
                    anchor.getReferenceId(), boardId, "Personal Robot Kit main board");
            call(builder, "method", new Class<?>[] {RecognitionResult.DetectionMethod.class},
                    RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY);
            call(builder, "validation", new Class<?>[] {RecognitionResult.ValidationState.class},
                    RecognitionResult.ValidationState.EXPECTED_COMPONENT);
            call(builder, "orientation", new Class<?>[] {
                    RecognitionResult.OrientationClassification.class, double.class},
                    RecognitionResult.OrientationClassification.UPRIGHT, 0.0);
            call(builder, "confidence", new Class<?>[] {double.class}, 0.95);
            call(builder, "corners", new Class<?>[] {List.class}, anchor.getProjectedCorners());
            call(builder, "pose", new Class<?>[] {RecognitionResult.Pose.class}, anchor.getPose());
            RecognitionResult base = (RecognitionResult) call(builder, "build", new Class<?>[0]);
            RecognitionResult result = (RecognitionResult) call(base, "withAssemblyInspection",
                    new Class<?>[] {int.class, RecognitionResult.SpatialAnchor.class, boolean.class,
                            MotorAssemblyObservation.class}, 2, anchor, opticalFlowOnly, observation);
            call(activity, "handleRecognition", new Class<?>[] {RecognitionResult.class}, result);
        }

        private static MotorAssemblyObservation.Slot slot(boolean present) {
            return present ? new MotorAssemblyObservation.Slot(
                    MotorAssemblyObservation.SlotState.PRESENT, 0.9,
                    "Motor installed in expected position", Collections.emptyList())
                    : MotorAssemblyObservation.Slot.missing("Motor not yet visible");
        }
    }

    private static void captureScreenshot(ActivityScenario<AssemblyActivity> scenario, String name) {
        awaitRenderedFrame(scenario);
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().takeScreenshot();
        assertNotNull("Instrumentation screenshot must be available for visual review", screenshot);
        Context context = ApplicationProvider.getApplicationContext();
        File directory = context.getExternalFilesDir("motor-workflow-previews");
        assertNotNull(directory);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File output = new File(directory, name + "-" + screenshot.getWidth()
                + "x" + screenshot.getHeight() + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } catch (IOException failure) {
            throw new AssertionError("Could not save UI preview " + output, failure);
        } finally {
            screenshot.recycle();
        }
    }

    private static void awaitRenderedFrame(ActivityScenario<AssemblyActivity> scenario) {
        CountDownLatch presented = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            View decor = activity.getWindow().getDecorView();
            // Main-thread idleness only proves the View tree changed. UiAutomation captures the
            // compositor, which can still show the previous expanded panel until RenderThread
            // commits the replacement frame. Then allow two display pulses for presentation.
            Runnable afterCommit = () -> decor.postOnAnimation(
                    () -> decor.postOnAnimation(presented::countDown));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && decor.isHardwareAccelerated()) {
                decor.getViewTreeObserver().registerFrameCommitCallback(afterCommit);
                decor.invalidate();
            } else {
                decor.invalidate();
                decor.postOnAnimation(afterCommit);
            }
        });
        try {
            assertTrue("Updated assembly layout must reach the display before capture",
                    presented.await(5L, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the assembly frame", interrupted);
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static AssemblyState state(AssemblyActivity activity) {
        return ((AssemblyStateMachine) getField(activity, "stateMachine")).snapshot().getState();
    }

    private static String text(AssemblyActivity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private static Object getField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not read fixture field " + name, failure);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not set fixture field " + name, failure);
        }
    }

    private static Object call(Object target, String name, Class<?>[] parameterTypes,
                               Object... arguments) {
        try {
            Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
            Method method = type.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target instanceof Class<?> ? null : target, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Fixture invocation failed: " + name, failure);
        }
    }
}
