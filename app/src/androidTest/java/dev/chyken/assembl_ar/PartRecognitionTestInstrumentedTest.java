package dev.chyken.assembl_ar;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Size;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.vision.RecognitionRequest;
import dev.chyken.assembl_ar.vision.AssemblyVisionAnalyzer;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.workflow.PartRecognitionTestSession;
import dev.chyken.assembl_ar.workflow.PartTestSampleArchive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Camera-free fixtures for the standalone tester's lifecycle and visible diagnostics. */
@RunWith(AndroidJUnit4.class)
public final class PartRecognitionTestInstrumentedTest {
    @Test
    public void requestedPartOpensPausedWithAllPartsAndAnIndividualRequest() {
        try (ActivityScenario<PartRecognitionTestActivity> scenario =
                     launch(NeobotSeedData.MOTOR_BRACKET)) {
            scenario.onActivity(activity -> {
                assertEquals(33, components(activity).size());
                assertEquals(NeobotSeedData.MOTOR_BRACKET, selectedPart(activity).getId());
                assertFalse(isTesting(activity));
                assertTrue(activity.findViewById(R.id.partTestStartButton).isEnabled());
                assertNotNull(activity.findViewById(R.id.partTestSelectButton));
                assertNotNull(activity.findViewById(R.id.partTestExportButton));
                assertNotNull(activity.findViewById(R.id.partTestCaptureButton));
                assertNotNull(activity.findViewById(R.id.partTestReplayButton));
                assertEquals(0, analyzed(activity, NeobotSeedData.MOTOR_BRACKET));

                invoke(activity, "startTest");
                assertTrue(isTesting(activity));
                View preview = activity.findViewById(R.id.partTestPreview);
                int width = preview.getWidth(), height = preview.getHeight();
                AssemblyVisionAnalyzer.FrameMetadata metadata = new AssemblyVisionAnalyzer.FrameMetadata(
                        SystemClock.elapsedRealtimeNanos(), width, height, 0, 0, width, height,
                        width, height, 0, true, new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1});
                RecognitionRequest request = (RecognitionRequest) invoke(activity, "requestForFrame",
                        new Class<?>[]{AssemblyVisionAnalyzer.FrameMetadata.class}, metadata);
                assertEquals(NeobotSeedData.MOTOR_BRACKET,
                        request.getExpectation().getComponentId());
                assertNull(request.getSpatialAnchorComponentId());
                assertEquals(0, request.getAssemblyStepNumber());
                assertEquals(1, request.getExpectedInstanceCount());
                assertEquals(0.001, request.getMinimumCornerAreaRatioOverride(), 1e-9);
                assertNull(request.getExpectation().getOrientationDegrees());
                assertNull(request.getExpectation().getPolarityRule());
                assertFalse(request.inspectsInstalledMotors());
            });
        }
    }

    @Test
    public void switchingPartsPausesAndRejectsResultsFromThePreviousRequest() {
        try (ActivityScenario<PartRecognitionTestActivity> scenario =
                     launch(NeobotSeedData.MOTOR_BRACKET)) {
            scenario.onActivity(activity -> {
                Fixture frames = new Fixture(activity);
                String firstId = selectedPart(activity).getId();
                invoke(activity, "startTest");
                long oldToken = token(activity);
                frames.deliver(oldToken, firstId, true);
                assertEquals(1, analyzed(activity, firstId));

                activity.findViewById(R.id.partTestNextButton).performClick();
                String secondId = selectedPart(activity).getId();
                assertFalse(firstId.equals(secondId));
                assertFalse(isTesting(activity));
                frames.deliver(oldToken, firstId, true);
                assertEquals(1, analyzed(activity, firstId));
                assertEquals(0, analyzed(activity, secondId));

                invoke(activity, "startTest");
                long newToken = token(activity);
                assertTrue(newToken != oldToken);
                frames.deliver(oldToken, secondId, true);
                assertEquals(0, analyzed(activity, secondId));
                frames.deliver(newToken, secondId, true);
                assertEquals(1, analyzed(activity, secondId));
                assertEquals(1, analyzed(activity, firstId));
            });
        }
    }

    @Test
    public void cameraTimestampCanUseADifferentClockFromActivityUptime() {
        try (ActivityScenario<PartRecognitionTestActivity> scenario = launch(NeobotSeedData.MOTOR_BRACKET)) {
            scenario.onActivity(activity -> {
                invoke(activity, "startTest");
                Object builder = invoke(RecognitionResult.class, "builder", new Class<?>[]{long.class}, 1L);
                RecognitionResult frame = (RecognitionResult) invoke(builder, "build");
                View preview = activity.findViewById(R.id.partTestPreview);
                invoke(activity, "handleTestResult", new Class<?>[]{long.class,
                        RecognitionResult.class, int.class, int.class}, token(activity), frame,
                        preview.getWidth(), preview.getHeight());
                assertEquals(1, analyzed(activity, NeobotSeedData.MOTOR_BRACKET));
            });
        }
    }

    @Test
    public void fixturesShowRecognitionEvidenceAndRequireFourPoseFramesForLock() {
        try (ActivityScenario<PartRecognitionTestActivity> scenario =
                     launch(NeobotSeedData.MOTOR_BRACKET)) {
            scenario.onActivity(activity -> {
                Fixture frames = new Fixture(activity);
                String expectedId = selectedPart(activity).getId();
                invoke(activity, "startTest");
                long generation = token(activity);

                frames.deliver(generation, expectedId, false);
                assertEquals(1, stats(activity, expectedId).getExpectedFrames());
                assertEquals(0, stats(activity, expectedId).getPoseFrames());
                assertFalse(stats(activity, expectedId).isLocked());

                for (int index = 0; index < 3; index++) {
                    frames.deliver(generation, expectedId, true);
                }
                assertFalse(stats(activity, expectedId).isLocked());
                frames.deliver(generation, expectedId, true);
                PartRecognitionTestSession.Snapshot locked = stats(activity, expectedId);
                assertTrue(locked.isLocked());
                assertEquals(4, locked.getPoseFrames());
                assertEquals(1, locked.getStableLockCount());
                assertEquals(5, locked.getExpectedFrames());
                assertEquals(5, locked.getOrbFrames());
                assertEquals(12.0, locked.getAverageLatencyMs(), 0.0001);
                String diagnostics = text(activity, R.id.partTestStatus) + " "
                        + text(activity, R.id.partTestLiveMetrics);
                assertTrue(diagnostics.contains(selectedPart(activity).getName()));
                assertTrue(diagnostics.contains("27"));
                assertTrue(diagnostics.contains("30"));
                assertFalse(text(activity, R.id.partTestStatus).isEmpty());
                assertFalse(text(activity, R.id.partTestTotals).isEmpty());

                frames.deliver(generation, NeobotSeedData.MAIN_BOARD, true);
                frames.deliver(generation, null, false);
                PartRecognitionTestSession.Snapshot mixed = stats(activity, expectedId);
                assertEquals(7, mixed.getAnalyzedFrames());
                assertEquals(5, mixed.getExpectedFrames());
                assertEquals(1, mixed.getWrongComponentFrames());
                assertEquals(1, mixed.getNoDetectionFrames());
                assertFalse(mixed.isLocked());
                assertEquals(Long.valueOf(1),
                        mixed.getWrongComponents().get(NeobotSeedData.MAIN_BOARD));
            });
        }
    }

    @Test
    public void resettingCurrentPartKeepsOtherPartResultsAndInvalidatesFrames() {
        try (ActivityScenario<PartRecognitionTestActivity> scenario =
                     launch(NeobotSeedData.MOTOR_BRACKET)) {
            scenario.onActivity(activity -> {
                Fixture frames = new Fixture(activity);
                int firstIndex = (Integer) field(activity, "selectedIndex");
                String firstId = selectedPart(activity).getId();
                invoke(activity, "startTest");
                frames.deliver(token(activity), firstId, true);

                activity.findViewById(R.id.partTestNextButton).performClick();
                String secondId = selectedPart(activity).getId();
                invoke(activity, "startTest");
                long secondToken = token(activity);
                frames.deliver(secondToken, secondId, true);
                frames.deliver(secondToken, secondId, true);

                activity.findViewById(R.id.partTestResetButton).performClick();
                assertFalse(isTesting(activity));
                assertEquals(0, analyzed(activity, secondId));
                assertEquals(1, analyzed(activity, firstId));
                frames.deliver(secondToken, secondId, true);
                assertEquals(0, analyzed(activity, secondId));

                invoke(activity, "selectPart", new Class<?>[] {int.class}, firstIndex);
                assertFalse(isTesting(activity));
                assertEquals(1, analyzed(activity, firstId));
                assertTrue(session(activity).toCsv().contains(firstId));
            });
        }
    }

    @Test
    public void recreationKeepsSelectionAndCountersButRequiresAnExplicitRestart() {
        String[] stagedFile = {null};
        try (ActivityScenario<PartRecognitionTestActivity> scenario =
                     launch(NeobotSeedData.MOTOR_BRACKET)) {
            scenario.onActivity(activity -> {
                Fixture frames = new Fixture(activity);
                invoke(activity, "startTest");
                frames.deliver(token(activity), NeobotSeedData.MOTOR_BRACKET, true);
                frames.deliver(token(activity), NeobotSeedData.MOTOR_BRACKET, true);
                stagedFile[0] = (String) invoke(activity, "stageSampleExport",
                        new Class<?>[]{byte[].class}, new byte[]{4, 8, 15, 16, 23, 42});
                setField(activity, "pendingSampleExportFile", stagedFile[0]);
                assertTrue(isTesting(activity));
            });

            scenario.recreate();

            scenario.onActivity(activity -> {
                assertEquals(NeobotSeedData.MOTOR_BRACKET, selectedPart(activity).getId());
                assertFalse(isTesting(activity));
                assertEquals(2, analyzed(activity, NeobotSeedData.MOTOR_BRACKET));
                assertEquals(0,
                        stats(activity, NeobotSeedData.MOTOR_BRACKET).getStableFrames());
                assertEquals(stagedFile[0], field(activity, "pendingSampleExportFile"));
                assertTrue(((java.io.File) invoke(activity, "pendingSampleFile",
                        new Class<?>[]{String.class}, stagedFile[0])).isFile());
                assertTrue(activity.findViewById(R.id.partTestStartButton).isEnabled());
                assertFalse(text(activity, R.id.partTestTotals).isEmpty());

                invoke(activity, "startTest");
                assertTrue(isTesting(activity));
                assertEquals(2, analyzed(activity, NeobotSeedData.MOTOR_BRACKET));
                assertEquals(0,
                        stats(activity, NeobotSeedData.MOTOR_BRACKET).getStableFrames());
            });
        }
    }

    @Test
    public void replayDoesNotPresentDescriptorOnlyIdentityAsSpatialSuccess() {
        try (ActivityScenario<PartRecognitionTestActivity> scenario =
                     launch(NeobotSeedData.MOTOR_BRACKET)) {
            scenario.onActivity(activity -> {
                Component expectedPart = selectedPart(activity);
                PartTestSampleArchive sample = new PartTestSampleArchive(
                        expectedPart.getId(), 91L, 640, 480, 0,
                        "recorded", pngHeader(640, 480));
                Object builder = invoke(RecognitionResult.class, "builder",
                        new Class<?>[]{long.class}, 91L);
                invoke(builder, "identity",
                        new Class<?>[]{String.class, String.class, String.class},
                        "descriptor-only", expectedPart.getId(), expectedPart.getName());
                invoke(builder, "method",
                        new Class<?>[]{RecognitionResult.DetectionMethod.class},
                        RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY);
                invoke(builder, "validation",
                        new Class<?>[]{RecognitionResult.ValidationState.class},
                        RecognitionResult.ValidationState.EXPECTED_COMPONENT);
                RecognitionResult descriptorOnly = (RecognitionResult) invoke(builder, "build");

                invoke(activity, "renderReplayResult",
                        new Class<?>[]{Component.class, PartTestSampleArchive.class,
                                RecognitionResult.class},
                        expectedPart, sample, descriptorOnly);

                assertTrue(text(activity, R.id.partTestStatus)
                        .contains("did not establish corners and pose"));
                assertTrue(text(activity, R.id.partTestLiveMetrics).contains("pose=missing"));
                assertTrue(text(activity, R.id.partTestLiveMetrics).contains("corners=0"));
            });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void analysisResolutionFilterOnlyKeepsFramesThatFullColorReplayCanBound() {
        List<Size> filtered = (List<Size>) invoke(PartRecognitionTestActivity.class,
                "boundedSampleResolutions", new Class<?>[]{List.class, int.class},
                Arrays.asList(new Size(1280, 720), new Size(1920, 1080),
                        new Size(2560, 1440), new Size(3840, 2160)), 90);
        assertEquals(Arrays.asList(new Size(1280, 720), new Size(1920, 1080)), filtered);
        for (Size size : filtered) {
            assertTrue(PartTestSampleArchive.supportsFullColorFrameGeometry(
                    size.getWidth(), size.getHeight()));
        }
    }

    private static ActivityScenario<PartRecognitionTestActivity> launch(String componentId) {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, PartRecognitionTestActivity.class)
                .putExtra(PartRecognitionTestActivity.EXTRA_COMPONENT_ID, componentId)
                .putExtra(PartRecognitionTestActivity.EXTRA_TEST_MODE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityScenario<PartRecognitionTestActivity> scenario = ActivityScenario.launch(intent);
        for (int attempt = 0; attempt < 50; attempt++) {
            boolean[] laidOut = {false};
            scenario.onActivity(activity -> {
                View preview = activity.findViewById(R.id.partTestPreview);
                laidOut[0] = preview.getWidth() > 0 && preview.getHeight() > 0;
            });
            if (laidOut[0]) return scenario;
            SystemClock.sleep(100L);
        }
        scenario.close();
        throw new AssertionError("Tester camera panel did not lay out");
    }

    @SuppressWarnings("unchecked")
    private static List<Component> components(PartRecognitionTestActivity activity) {
        return (List<Component>) field(activity, "components");
    }

    private static Component selectedPart(PartRecognitionTestActivity activity) {
        return components(activity).get((Integer) field(activity, "selectedIndex"));
    }

    private static boolean isTesting(PartRecognitionTestActivity activity) {
        return (Boolean) field(activity, "testing");
    }

    private static long token(PartRecognitionTestActivity activity) {
        Object active = field(activity, "activeTest");
        assertNotNull(active);
        return (Long) field(active, "generation");
    }

    private static PartRecognitionTestSession session(PartRecognitionTestActivity activity) {
        return (PartRecognitionTestSession) field(activity, "session");
    }

    private static PartRecognitionTestSession.Snapshot stats(
            PartRecognitionTestActivity activity, String componentId) {
        PartRecognitionTestSession.Snapshot snapshot = session(activity).getSnapshot(componentId);
        assertNotNull(snapshot);
        return snapshot;
    }

    private static long analyzed(PartRecognitionTestActivity activity, String componentId) {
        PartRecognitionTestSession.Snapshot snapshot = session(activity).getSnapshot(componentId);
        return snapshot == null ? 0 : snapshot.getAnalyzedFrames();
    }

    private static String text(PartRecognitionTestActivity activity, int viewId) {
        return ((TextView) activity.findViewById(viewId)).getText().toString();
    }

    private static byte[] pngHeader(int width, int height) {
        byte[] png = new byte[24];
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, png, 0, signature.length);
        putInt(png, 8, 13);
        png[12] = 'I'; png[13] = 'H'; png[14] = 'D'; png[15] = 'R';
        putInt(png, 16, width);
        putInt(png, 20, height);
        return png;
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static final class Fixture {
        private final PartRecognitionTestActivity activity;
        private final int width;
        private final int height;
        private final List<VisionPoint> corners;
        private long timestamp = SystemClock.elapsedRealtimeNanos();

        Fixture(PartRecognitionTestActivity activity) {
            this.activity = activity;
            View preview = activity.findViewById(R.id.partTestPreview);
            width = Math.max(1, preview.getWidth());
            height = Math.max(1, preview.getHeight());
            corners = Arrays.asList(new VisionPoint(width * .25, height * .25),
                    new VisionPoint(width * .75, height * .25),
                    new VisionPoint(width * .75, height * .75),
                    new VisionPoint(width * .25, height * .75));
        }

        void deliver(long generation, String componentId, boolean withPose) {
            timestamp += 33_000_000L;
            Object builder = invoke(RecognitionResult.class, "builder",
                    new Class<?>[] {long.class}, timestamp);
            if (componentId != null) {
                String displayName = componentId;
                for (Component component : components(activity)) {
                    if (component.getId().equals(componentId)) displayName = component.getName();
                }
                invoke(builder, "identity", new Class<?>[] {String.class, String.class, String.class},
                        "fixture-" + componentId, componentId, displayName);
                invoke(builder, "method", new Class<?>[] {RecognitionResult.DetectionMethod.class},
                        RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY);
                invoke(builder, "validation", new Class<?>[] {RecognitionResult.ValidationState.class},
                        componentId.equals(selectedPart(activity).getId())
                                ? RecognitionResult.ValidationState.EXPECTED_COMPONENT
                                : RecognitionResult.ValidationState.COMPONENT_MISMATCH);
                invoke(builder, "confidence", new Class<?>[] {double.class}, .95);
                invoke(builder, "matches", new Class<?>[] {int.class, int.class, int.class, double.class},
                        80, 30, 27, .90);
                invoke(builder, "corners", new Class<?>[] {List.class}, corners);
                if (withPose) {
                    RecognitionResult.Pose pose = new RecognitionResult.Pose(
                            new double[] {0, 0, 0}, new double[] {0, 0, .5}, new float[] {
                            1, 0, 0, 0, 0, -1, 0, 0, 0, 0, -1, 0, 0, 0, -.5f, 1
                    });
                    invoke(builder, "pose", new Class<?>[] {RecognitionResult.Pose.class}, pose);
                }
            }
            invoke(builder, "latency", new Class<?>[] {RecognitionResult.Latency.class},
                    new RecognitionResult.Latency(4, 5, 3, 0, 12));
            RecognitionResult result = (RecognitionResult) invoke(builder, "build");
            invoke(activity, "handleTestResult", new Class<?>[] {long.class,
                    RecognitionResult.class, int.class, int.class}, generation, result, width, height);
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
            throw new AssertionError("Could not write " + name, failure);
        }
    }

    private static Object invoke(Object target, String name) {
        return invoke(target, name, new Class<?>[0]);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
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
}
