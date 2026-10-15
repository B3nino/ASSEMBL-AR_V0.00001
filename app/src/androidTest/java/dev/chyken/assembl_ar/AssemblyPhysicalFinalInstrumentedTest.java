package dev.chyken.assembl_ar;

import android.content.Context;
import android.content.Intent;
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
import java.util.Arrays;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.vision.AssemblyVisionAnalyzer;
import dev.chyken.assembl_ar.vision.MotorAssemblyObservation;
import dev.chyken.assembl_ar.vision.RecognitionRequest;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.workflow.AssemblyState;
import dev.chyken.assembl_ar.workflow.AssemblyStateMachine;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;
import dev.chyken.assembl_ar.workflow.GuidedStepProgressStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Physical wiring checks cannot be replaced by recognizing a driver or loose cable. */
@RunWith(AndroidJUnit4.class)
public final class AssemblyPhysicalFinalInstrumentedTest {
    @Test public void finalWiringCheckRequiresEveryPrecedingActionEvenAfterComponentRecognition() {
        Context context = ApplicationProvider.getApplicationContext();
        try (ActivityScenario<AssemblyActivity> scenario = launch(context)) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                for (int number : Arrays.asList(5, 7, 8, 15)) {
                    AssemblyStep section = configureFinal(activity, number);
                    List<GuidedStep> cards = GuidedAssemblySequence.forManualStep(section);
                    assertNull(request(activity).getExpectation());
                    assertFalse((Boolean) field(activity, "guidedStepRecognitionEnabled"));
                    assertEquals(View.GONE, activity.findViewById(R.id.confirmPlacementButton).getVisibility());

                    String component = number == 15 ? NeobotSeedData.DATA_CABLE : NeobotSeedData.MOTOR_DRIVER;
                    for (int frame = 0; frame < 10; frame++) deliverPart(activity, number, component);
                    assertEquals(number - 1, field(activity, "currentStepIndex"));
                    assertEquals(AssemblyState.SEEKING_COMPONENT, state(activity));
                    assertEquals(View.GONE, activity.findViewById(R.id.confirmPlacementButton).getVisibility());

                    for (int index = 0; index < cards.size() - 1; index++) {
                        GuidedStepProgressStore.markCheckpointComplete(activity,
                                section.getProjectId(), section.getId(), cards.get(index));
                        invoke(activity, "renderGuidedStep");
                        assertEquals("Every action must be confirmed in section " + number,
                                index == cards.size() - 2 ? View.VISIBLE : View.GONE,
                                activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                    }
                    assertEquals(AssemblyState.PHYSICAL_CONFIRMATION, state(activity));
                    assertEquals("Manual readiness must not fabricate component recognition", "",
                            ((AssemblyStateMachine) field(activity, "stateMachine"))
                                    .snapshot().getDetectedComponentId());
                    assertEquals(number - 1, field(activity, "currentStepIndex"));
                }
            });
        } finally {
            GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        }
    }

    @Test public void allPhysicalFinalsRequireAnUncheckedActionDialogAndDoNotAdvanceOnCameraOrCheckbox() {
        Context context = ApplicationProvider.getApplicationContext();
        try (ActivityScenario<AssemblyActivity> scenario = launch(context)) {
            awaitLoaded(scenario);
            for (int number : Arrays.asList(4, 5, 7, 8, 15)) {
                scenario.onActivity(activity -> {
                    AssemblyStep section = configureFinal(activity, number);
                    List<GuidedStep> cards = GuidedAssemblySequence.forManualStep(section);
                    for (int index = 0; index < cards.size() - 1; index++) {
                        GuidedStepProgressStore.markCheckpointComplete(activity,
                                section.getProjectId(), section.getId(), cards.get(index));
                    }
                    invoke(activity, "renderGuidedStep");
                    assertNull(request(activity).getExpectation());
                    assertEquals(View.VISIBLE, activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                    activity.findViewById(R.id.confirmPlacementButton).performClick();
                    AlertDialog dialog = (AlertDialog) field(activity, "physicalValidationDialog");
                    assertNotNull("Final check dialog for section " + number, dialog);
                    assertTrue(dialog.isShowing());
                    assertTrue(((TextView) dialog.findViewById(R.id.validationBody)).getText()
                            .toString().contains(section.getValidationPrompt()));
                    CheckBox checkbox = dialog.findViewById(R.id.validationCheckbox);
                    assertFalse(checkbox.isChecked());
                    assertFalse(dialog.findViewById(R.id.confirmNextButton).isEnabled());
                    deliverPart(activity, number,
                            number == 15 ? NeobotSeedData.DATA_CABLE : NeobotSeedData.MOTOR_DRIVER);
                    assertFalse(checkbox.isChecked());
                    assertFalse(dialog.findViewById(R.id.confirmNextButton).isEnabled());
                    checkbox.setChecked(true);
                    assertTrue(dialog.findViewById(R.id.confirmNextButton).isEnabled());
                    assertEquals("Checkbox alone does not complete the section", number - 1,
                            field(activity, "currentStepIndex"));
                    assertFalse((Boolean) field(activity, "completionInFlight"));
                    dialog.cancel();
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    assertEquals(number - 1, field(activity, "currentStepIndex"));
                    assertFalse((Boolean) field(activity, "physicalCheckInProgress"));
                    // Reopening cannot retain the prior checkbox acknowledgement.
                    activity.findViewById(R.id.confirmPlacementButton).performClick();
                    AlertDialog reopened = (AlertDialog) field(activity, "physicalValidationDialog");
                    assertNotNull(reopened);
                    assertFalse(((CheckBox) reopened.findViewById(R.id.validationCheckbox)).isChecked());
                    assertFalse(reopened.findViewById(R.id.confirmNextButton).isEnabled());
                    reopened.cancel();
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            }
        } finally {
            GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        }
    }

    private static ActivityScenario<AssemblyActivity> launch(Context context) {
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

    @SuppressWarnings("unchecked")
    private static AssemblyStep configureFinal(AssemblyActivity activity, int number) {
        List<AssemblyStep> steps = (List<AssemblyStep>) field(activity, "steps");
        AssemblyStep section = steps.get(number - 1);
        GuidedStepProgressStore.clearStep(activity, section.getProjectId(), section.getId());
        setField(activity, "currentStepIndex", number - 1);
        setField(activity, "currentStep", section);
        setField(activity, "stateMachine", new AssemblyStateMachine(steps.size(), number - 1));
        invoke(activity, "renderStep");
        setField(activity, "currentGuidedStepIndex", GuidedAssemblySequence.forManualStep(section).size() - 1);
        invoke(activity, "renderGuidedStep");
        return section;
    }

    private static RecognitionRequest request(AssemblyActivity activity) {
        View preview = activity.findViewById(R.id.previewView);
        int width = preview.getWidth(), height = preview.getHeight();
        AssemblyVisionAnalyzer.FrameMetadata metadata = new AssemblyVisionAnalyzer.FrameMetadata(
                SystemClock.elapsedRealtimeNanos(), width, height, 0, 0, width, height,
                width, height, 0, true, new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1});
        return (RecognitionRequest) invoke(activity, "recognitionRequestFor",
                new Class<?>[]{AssemblyVisionAnalyzer.FrameMetadata.class}, metadata);
    }

    private static void deliverPart(AssemblyActivity activity, int section, String component) {
        Object builder = invoke(RecognitionResult.class, "builder", new Class<?>[]{long.class},
                SystemClock.elapsedRealtimeNanos() + 1L);
        invoke(builder, "identity", new Class<?>[]{String.class, String.class, String.class},
                "test-loose-part", component, component);
        invoke(builder, "method", new Class<?>[]{RecognitionResult.DetectionMethod.class},
                RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY);
        invoke(builder, "validation", new Class<?>[]{RecognitionResult.ValidationState.class},
                RecognitionResult.ValidationState.EXPECTED_COMPONENT);
        invoke(builder, "confidence", new Class<?>[]{double.class}, 0.99);
        invoke(builder, "corners", new Class<?>[]{List.class}, Arrays.asList(
                new VisionPoint(100, 100), new VisionPoint(300, 100),
                new VisionPoint(300, 300), new VisionPoint(100, 300)));
        invoke(builder, "pose", new Class<?>[]{RecognitionResult.Pose.class},
                new RecognitionResult.Pose(new double[]{0, 0, 0}, new double[]{0, 0, 0.5},
                        new float[]{1, 0, 0, 0, 0, -1, 0, 0, 0, 0, -1, 0, 0, 0, -0.5f, 1}));
        RecognitionResult base = (RecognitionResult) invoke(builder, "build");
        RecognitionResult result = (RecognitionResult) invoke(base, "withAssemblyInspection",
                new Class<?>[]{int.class, RecognitionResult.SpatialAnchor.class, boolean.class,
                        MotorAssemblyObservation.class}, section, null, false, null);
        invoke(activity, "handleRecognition", new Class<?>[]{RecognitionResult.class}, result);
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

    private static Object invoke(Object target, String name) {
        return invoke(target, name, new Class<?>[0]);
    }
}
