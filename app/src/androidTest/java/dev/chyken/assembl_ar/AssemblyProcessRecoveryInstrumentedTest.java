package dev.chyken.assembl_ar;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.vision.MotorAssemblyObservation;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.workflow.AssemblyState;
import dev.chyken.assembl_ar.workflow.AssemblyStateMachine;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;
import dev.chyken.assembl_ar.workflow.GuidedCheckpoint;
import dev.chyken.assembl_ar.workflow.GuidedRecognitionCatalog;
import dev.chyken.assembl_ar.workflow.GuidedStepProgressStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Reviews are read-only; redoing an action deliberately invalidates its dependent checks. */
@RunWith(AndroidJUnit4.class)
public final class AssemblyProcessRecoveryInstrumentedTest {
    @After public void clearCheckpoints() {
        GuidedStepProgressStore.clearProject(
                ApplicationProvider.getApplicationContext(), NeobotSeedData.PROJECT_ID);
    }

    @Test public void relockingCannotStrandAnyManualFinalCheck() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                for (int number : new int[]{4, 5, 7, 8, 15}) {
                    AssemblyStep section = configureSection(activity, number);
                    List<GuidedStep> cards = GuidedAssemblySequence.forManualStep(section);
                    markAllCheckpoints(activity, section, cards);
                    showCard(activity, cards.size() - 1);
                    assertEquals(AssemblyState.PHYSICAL_CONFIRMATION, state(activity));
                    assertEquals(View.VISIBLE,
                            activity.findViewById(R.id.confirmPlacementButton).getVisibility());

                    // Call the actual toolbar handler even if this surface hides its button.
                    invoke(activity, "relockAssemblyBase");
                    assertEquals("Re-lock cannot revoke a manual final in section " + number,
                            AssemblyState.PHYSICAL_CONFIRMATION, state(activity));
                    assertEquals(View.VISIBLE,
                            activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                    assertFalse((Boolean) field(activity, "guidedStepRecognitionEnabled"));
                }
            });
        }
    }

    @Test public void relockingThePreparationCardKeepsIdentityRecognitionWithoutAPhysicalCheck() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                AssemblyStep section = configureSection(activity, 1);
                List<GuidedStep> cards = GuidedAssemblySequence.forManualStep(section);
                assertEquals(1, cards.size());
                showCard(activity, 0);
                assertEquals(GuidedCheckpoint.Kind.IDENTITY,
                        GuidedRecognitionCatalog.checkpointFor(section, cards.get(0)).getKind());
                assertTrue((Boolean) field(activity, "guidedStepRecognitionEnabled"));
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());

                invoke(activity, "relockAssemblyBase");

                assertEquals(AssemblyState.SEEKING_COMPONENT, state(activity));
                assertTrue((Boolean) field(activity, "guidedStepRecognitionEnabled"));
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                assertFalse((Boolean) field(activity, "physicalCheckInProgress"));
            });
        }
    }

    @Test public void redoReopensCameraAndPhysicalActionsAndInvalidatesOnlyDependentCheckpoints() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                for (GuidedCheckpoint.Kind kind : new GuidedCheckpoint.Kind[]{
                        GuidedCheckpoint.Kind.PLACEMENT, GuidedCheckpoint.Kind.PHYSICAL}) {
                    AssemblyStep section = configureSection(activity, 2);
                    List<GuidedStep> cards = GuidedAssemblySequence.forManualStep(section);
                    int redoIndex = -1;
                    for (int index = 1; index < cards.size() - 1; index++) {
                        GuidedCheckpoint checkpoint = GuidedRecognitionCatalog.checkpointFor(
                                section, cards.get(index));
                        if (checkpoint != null && checkpoint.getKind() == kind) {
                            redoIndex = index;
                            break;
                        }
                    }
                    assertTrue("Fixture needs an action with earlier dependencies", redoIndex > 0);
                    markAllCheckpoints(activity, section, cards);
                    showCard(activity, redoIndex);
                    assertEquals(View.VISIBLE,
                            activity.findViewById(R.id.redoGuidedStepButton).getVisibility());
                    assertTrue(activity.findViewById(R.id.nextGuidedStepButton).isEnabled());

                    activity.findViewById(R.id.redoGuidedStepButton).performClick();
                    assertEquals(redoIndex, field(activity, "currentGuidedStepIndex"));
                    assertFalse(activity.findViewById(R.id.nextGuidedStepButton).isEnabled());
                    assertFalse((Boolean) field(activity, "currentCheckpointComplete"));
                    for (int index = 0; index < cards.size() - 1; index++) {
                        assertEquals("Redo must clear the current and every later action " + index,
                                index < redoIndex, GuidedStepProgressStore.isCheckpointComplete(
                                        activity, section.getProjectId(), section.getId(), cards.get(index)));
                    }
                    assertEquals(redoIndex, GuidedStepProgressStore.readForCheckpoints(
                            activity, section, cards));
                    assertEquals(kind == GuidedCheckpoint.Kind.PLACEMENT,
                            field(activity, "guidedStepRecognitionEnabled"));
                    assertEquals(kind == GuidedCheckpoint.Kind.PHYSICAL
                                    ? AssemblyState.PHYSICAL_CONFIRMATION : AssemblyState.SEEKING_COMPONENT,
                            state(activity));
                    assertEquals(kind == GuidedCheckpoint.Kind.PHYSICAL ? View.VISIBLE : View.GONE,
                            activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                }
            });
        }
    }

    @Test public void previousReviewPreservesConfirmedProgressAndCheckpoints() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                AssemblyStep section = configureSection(activity, 2);
                List<GuidedStep> cards = GuidedAssemblySequence.forManualStep(section);
                for (int index = 0; index < 8; index++) {
                    GuidedStepProgressStore.markCheckpointComplete(activity,
                            section.getProjectId(), section.getId(), cards.get(index));
                }
                showCard(activity, 8);
                int percent = confirmedPercent(activity);
                assertTrue(percent > 0);
                activity.findViewById(R.id.previousGuidedStepButton).performClick();
                assertEquals(7, field(activity, "currentGuidedStepIndex"));
                assertEquals("Reading an earlier card must not reduce completed work",
                        percent, confirmedPercent(activity));
                assertTrue(activity.findViewById(R.id.nextGuidedStepButton).isEnabled());
                for (int index = 0; index < 8; index++) {
                    assertTrue(GuidedStepProgressStore.isCheckpointComplete(activity,
                            section.getProjectId(), section.getId(), cards.get(index)));
                }
                activity.findViewById(R.id.nextGuidedStepButton).performClick();
                assertEquals(8, field(activity, "currentGuidedStepIndex"));
                assertEquals(percent, confirmedPercent(activity));
            });
        }
    }

    @Test public void cameraFinalNeedsEveryActionBeforeTheSameInstalledMotorEvidenceCanUnlockIt() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                AssemblyStep section = configureSection(activity, 2);
                List<GuidedStep> cards = GuidedAssemblySequence.forManualStep(section);
                markAllCheckpoints(activity, section, cards);
                int missingIndex = 3;
                GuidedStepProgressStore.clearCheckpointsFrom(activity, section, cards, missingIndex);
                // Leave exactly one earlier action incomplete, including after a persisted cursor
                // or an accidental jump to the final card. Recognition cannot fill this gap.
                for (int index = missingIndex + 1; index < cards.size() - 1; index++) {
                    GuidedStepProgressStore.markCheckpointComplete(activity,
                            section.getProjectId(), section.getId(), cards.get(index));
                }
                showCard(activity, cards.size() - 1);
                MotorFrames frames = new MotorFrames(activity);
                frames.deliverReadySequence();
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                assertFalse(state(activity) == AssemblyState.PHYSICAL_CONFIRMATION);
                invoke(activity, "showPhysicalValidation");
                assertFalse((Boolean) field(activity, "physicalCheckInProgress"));

                GuidedStepProgressStore.markCheckpointComplete(activity,
                        section.getProjectId(), section.getId(), cards.get(missingIndex));
                invoke(activity, "renderGuidedStep");
                frames.deliverReadySequence();
                assertEquals("The identical camera evidence is accepted after all actions are checked",
                        AssemblyState.PHYSICAL_CONFIRMATION, state(activity));
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
            });
        }
    }

    private static int confirmedPercent(AssemblyActivity activity) {
        String label = ((TextView) activity.findViewById(R.id.progressLabel)).getText().toString();
        Matcher match = Pattern.compile("(\\d+)%").matcher(label);
        assertTrue("Progress label needs a completion percentage: " + label, match.find());
        return Integer.parseInt(match.group(1));
    }

    private static void markAllCheckpoints(
            AssemblyActivity activity, AssemblyStep section, List<GuidedStep> cards) {
        for (GuidedStep card : cards) {
            if (GuidedRecognitionCatalog.checkpointFor(section, card) != null) {
                GuidedStepProgressStore.markCheckpointComplete(activity,
                        section.getProjectId(), section.getId(), card);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static AssemblyStep configureSection(AssemblyActivity activity, int number) {
        List<AssemblyStep> steps = (List<AssemblyStep>) field(activity, "steps");
        AssemblyStep section = steps.get(number - 1);
        GuidedStepProgressStore.clearStep(activity, section.getProjectId(), section.getId());
        setField(activity, "currentStepIndex", number - 1);
        setField(activity, "currentStep", section);
        setField(activity, "stateMachine", new AssemblyStateMachine(steps.size(), number - 1));
        invoke(activity, "renderStep");
        return section;
    }

    private static void showCard(AssemblyActivity activity, int index) {
        setField(activity, "currentGuidedStepIndex", index);
        invoke(activity, "renderGuidedStep");
    }

    private static final class MotorFrames {
        final AssemblyActivity activity;
        final RecognitionResult.SpatialAnchor anchor;
        long timestamp = SystemClock.elapsedRealtimeNanos();

        MotorFrames(AssemblyActivity activity) {
            this.activity = activity;
            View preview = activity.findViewById(R.id.previewView);
            int width = preview.getWidth(), height = preview.getHeight();
            setField(activity, "analyzedWidth", width);
            setField(activity, "analyzedHeight", height);
            List<VisionPoint> corners = Arrays.asList(
                    new VisionPoint(width * .20, height * .15),
                    new VisionPoint(width * .80, height * .15),
                    new VisionPoint(width * .80, height * .80),
                    new VisionPoint(width * .20, height * .80));
            RecognitionResult.Pose pose = new RecognitionResult.Pose(new double[]{0, 0, 0},
                    new double[]{0, 0, .5},
                    new float[]{1, 0, 0, 0, 0, -1, 0, 0, 0, 0, -1, 0, 0, 0, -.5f, 1});
            anchor = new RecognitionResult.SpatialAnchor(
                    "fixture-main-board", NeobotSeedData.MAIN_BOARD, corners, pose);
        }

        void deliverReadySequence() {
            for (int index = 0; index < 4; index++) deliver(false);
            for (int index = 0; index < 6; index++) deliver(true);
        }

        void deliver(boolean present) {
            timestamp = Math.max(timestamp + 100_000_000L, SystemClock.elapsedRealtimeNanos() + 1L);
            MotorAssemblyObservation.Slot slot = present ? new MotorAssemblyObservation.Slot(
                    MotorAssemblyObservation.SlotState.PRESENT, .95,
                    "Motor installed in expected position", Collections.emptyList())
                    : MotorAssemblyObservation.Slot.missing("Motor not yet visible");
            MotorAssemblyObservation motors = new MotorAssemblyObservation(
                    MotorAssemblyObservation.Quality.GOOD, slot, slot);
            Object builder = invoke(RecognitionResult.class, "builder", new Class<?>[]{long.class}, timestamp);
            invoke(builder, "identity", new Class<?>[]{String.class, String.class, String.class},
                    anchor.getReferenceId(), NeobotSeedData.MAIN_BOARD, "Main board");
            invoke(builder, "method", new Class<?>[]{RecognitionResult.DetectionMethod.class},
                    RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY);
            invoke(builder, "validation", new Class<?>[]{RecognitionResult.ValidationState.class},
                    RecognitionResult.ValidationState.EXPECTED_COMPONENT);
            invoke(builder, "orientation", new Class<?>[]{
                    RecognitionResult.OrientationClassification.class, double.class},
                    RecognitionResult.OrientationClassification.UPRIGHT, 0.0);
            invoke(builder, "confidence", new Class<?>[]{double.class}, .95);
            invoke(builder, "corners", new Class<?>[]{List.class}, anchor.getProjectedCorners());
            invoke(builder, "pose", new Class<?>[]{RecognitionResult.Pose.class}, anchor.getPose());
            RecognitionResult base = (RecognitionResult) invoke(builder, "build");
            RecognitionResult result = (RecognitionResult) invoke(base, "withAssemblyInspection",
                    new Class<?>[]{int.class, RecognitionResult.SpatialAnchor.class,
                            boolean.class, MotorAssemblyObservation.class}, 2, anchor, false, motors);
            invoke(activity, "handleRecognition", new Class<?>[]{RecognitionResult.class}, result);
        }
    }

    private static ActivityScenario<AssemblyActivity> launch() {
        Context context = ApplicationProvider.getApplicationContext();
        return ActivityScenario.launch(new Intent(context, AssemblyActivity.class)
                .putExtra(AssemblyActivity.EXTRA_SIMULATED_STATE, AssemblyActivity.SIMULATE_MISMATCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static void awaitLoaded(ActivityScenario<AssemblyActivity> scenario) {
        AssertionError failure = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                scenario.onActivity(activity -> {
                    assertNotNull(field(activity, "stateMachine"));
                    assertTrue(activity.findViewById(R.id.previewView).getWidth() > 0);
                    assertTrue(activity.findViewById(R.id.previewView).getHeight() > 0);
                });
                return;
            } catch (AssertionError pending) {
                failure = pending;
                SystemClock.sleep(100L);
            }
        }
        throw failure == null ? new AssertionError("Assembly activity did not load") : failure;
    }

    private static AssemblyState state(AssemblyActivity activity) {
        return ((AssemblyStateMachine) field(activity, "stateMachine")).snapshot().getState();
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(name, failure);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(name, failure);
        }
    }

    private static Object invoke(Object target, String name) {
        return invoke(target, name, new Class<?>[0]);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) {
        try {
            Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
            Method method = type.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(target instanceof Class<?> ? null : target, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(name, failure);
        }
    }
}
