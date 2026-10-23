package dev.chyken.assembl_ar.vision;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;

import androidx.camera.core.ImageInfo;
import androidx.camera.core.ImageProxy;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.workflow.VisualPlacementGate;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Native pipeline regressions; synthetic registered targets isolate step changes from scene changes. */
@RunWith(AndroidJUnit4.class)
public final class AssemblyBoardContinuityInstrumentedTest {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final String BOARD = "main_board";
    private static final String EXTENSION = NeobotSeedData.EXTENSION_BOARD;
    private static final String[] EXPECTED = {
            BOARD, NeobotSeedData.GEAR_MOTOR, NeobotSeedData.MOTOR_DRIVER,
            NeobotSeedData.MOTOR_DRIVER, NeobotSeedData.MOTOR_DRIVER,
            NeobotSeedData.MICROCONTROLLER, NeobotSeedData.MOTOR_DRIVER,
            NeobotSeedData.MOTOR_DRIVER, NeobotSeedData.CASTER_WHEEL,
            NeobotSeedData.LINE_TRACKER, NeobotSeedData.DISTANCE_SENSOR,
            NeobotSeedData.STANDOFF, NeobotSeedData.WHEEL, NeobotSeedData.OLED_PROTECTOR,
            NeobotSeedData.DATA_CABLE, EXTENSION, NeobotSeedData.OLED_DISPLAY, NeobotSeedData.BATTERY_PACK
    };
    private static final List<VisionPoint> CORNERS = Arrays.asList(
            new VisionPoint(177, 75), new VisionPoint(463, 75),
            new VisionPoint(463, 405), new VisionPoint(177, 405));

    private Context context;
    private Mat board;
    private File boardFile;
    private File otherFile;
    private final List<ComponentReferenceSpec> references = new ArrayList<>();
    private CameraCalibration calibration;

    @Before public void setUp() throws Exception {
        assertTrue(OpenCVLoader.initLocal());
        context = ApplicationProvider.getApplicationContext();
        calibration = CameraCalibration.pinhole(720, 720, WIDTH / 2.0, HEIGHT / 2.0);
        board = texturedBoard(7319L);
        Mat other = texturedBoard(481L);
        try {
            boardFile = File.createTempFile("board-continuity-", ".png", context.getCacheDir());
            otherFile = File.createTempFile("board-continuity-absent-", ".png", context.getCacheDir());
            assertTrue(Imgcodecs.imwrite(boardFile.getAbsolutePath(), board));
            assertTrue(Imgcodecs.imwrite(otherFile.getAbsolutePath(), other));
        } finally { other.release(); }
        Set<String> componentIds = new LinkedHashSet<>(Arrays.asList(EXPECTED));
        for (String id : componentIds) {
            references.add(ComponentReferenceSpec.builder("continuity_" + id, id, id, 130, 150)
                    .localImageFilePath((BOARD.equals(id) ? boardFile : otherFile).getAbsolutePath())
                    .planarReferenceCornersNormalized(normalized(CORNERS.get(0)),
                            normalized(CORNERS.get(1)), normalized(CORNERS.get(2)),
                            normalized(CORNERS.get(3)))
                    .build());
        }
    }

    @After public void tearDown() {
        if (board != null) board.release();
        if (boardFile != null) assertTrue(boardFile.delete());
        if (otherFile != null) assertTrue(otherFile.delete());
    }

    @Test public void identicalCameraFrameKeepsBoardMetricsAcrossEveryAssemblyStep() throws Exception {
        VisionConfig config = config(12);
        try (ReferenceDatabase database = ReferenceDatabase.load(context.getAssets(), references, config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database);
             Harness pipeline = new Harness(recognizer, config)) {
            RecognitionResult baseline = null;
            for (int step = 1; step <= 18; step++) {
                RecognitionResult result = pipeline.analyze(board, request(step, true));
                String label = "Step " + step;
                assertTrue(label + ": missing current component must not hide the main board",
                        result.isBoardDetected());
                assertNotNull(label, result.getBoardObservation());
                assertFalse(label + ": identical fixture has fresh evidence",
                        result.getBoardObservation().isTracked());
                RecognitionResult detection = result.getBoardObservation().getDetection();
                assertNotNull(label, detection);
                assertEquals(label, BOARD, detection.getComponentId());
                assertNotNull(label, result.getBoardObservation().getAnchor().getPose());
                if (baseline == null) baseline = detection;
                assertEquals(label, baseline.getFrameKeypointCount(), detection.getFrameKeypointCount());
                assertEquals(label, baseline.getGoodMatchCount(), detection.getGoodMatchCount());
                assertEquals(label, baseline.getInlierCount(), detection.getInlierCount());
                assertEquals(label, baseline.getConfidence(), detection.getConfidence(), 1e-9);
                assertCornersEqual(label, baseline.getProjectedCorners(), detection.getProjectedCorners());
                if (step == 2) {
                    assertNotNull(result.getMotorAssembly());
                    assertFalse("Bare grayscale board is not a left motor",
                            result.getMotorAssembly().getLeft().isAccepted());
                    assertFalse("Bare grayscale board is not a right motor",
                            result.getMotorAssembly().getRight().isAccepted());
                }
                if (step == 14 || step >= 17) {
                    assertTrue("Main board must not impersonate the missing extension workplane",
                            result.getSpatialAnchor() == null
                                    || !BOARD.equals(result.getSpatialAnchor().getComponentId()));
                }
            }
        }
    }

    @Test public void intermediateCardsKeepActualStepAndBoardWithoutComponentInspection() throws Exception {
        VisionConfig config = config(12);
        try (ReferenceDatabase database = ReferenceDatabase.load(context.getAssets(), references, config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database);
             Harness pipeline = new Harness(recognizer, config)) {
            for (int step : new int[]{1, 2, 3, 14, 17, 18}) {
                RecognitionResult result = pipeline.analyze(board, request(step, false));
                assertEquals(step, result.getAssemblyStepNumber());
                assertTrue("Board stays available while reading card in step " + step,
                        result.isBoardDetected());
                assertNull("Intermediate cards do not inspect installed motors", result.getMotorAssembly());
            }
        }
    }

    @Test public void componentOrientationFailureDoesNotChangeBoardIdentity() throws Exception {
        VisionConfig config = config(12);
        try (ReferenceDatabase database = ReferenceDatabase.load(context.getAssets(), references, config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database);
             Harness pipeline = new Harness(recognizer, config)) {
            RecognitionRequest request = new RecognitionRequest(
                    new RecognitionRequest.AssemblyExpectation(BOARD, 180.0, 30.0, null),
                    calibration, null, null, 1, 1);
            RecognitionResult result = pipeline.analyze(board, request);
            assertTrue(result.isBoardDetected());
            assertNotNull(result.getBoardObservation().getAnchor());
            assertEquals(RecognitionResult.ValidationState.WRONG_ORIENTATION, result.getValidationState());
            assertSame("Orientation validation must reuse the existing board pass",
                    result.getBoardObservation().getDetection().getLatency(), result.getLatency());
        }
    }

    @Test public void stepOneOrientationReusesBoardDetectionWithoutAnotherFramePass() throws Exception {
        VisionConfig config = config(12);
        try (ReferenceDatabase database = ReferenceDatabase.load(context.getAssets(), references, config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database);
             Harness pipeline = new Harness(recognizer, config)) {
            RecognitionResult result = pipeline.analyze(board, request(1, true));
            assertTrue(result.isBoardDetected());
            assertEquals(RecognitionResult.ValidationState.EXPECTED_COMPONENT, result.getValidationState());
            assertSame("Step 1 orientation must reuse the same measured detector pass",
                    result.getBoardObservation().getDetection().getLatency(), result.getLatency());
            assertSame("Step 1 orientation must preserve the already estimated board pose",
                    result.getBoardObservation().getDetection().getPose(), result.getPose());
        }
    }

    @Test public void stepOneRejectsOneHundredTwentyDegreeOrientationWithoutAnotherFramePass() throws Exception {
        VisionConfig config = config(12);
        try (ReferenceDatabase database = ReferenceDatabase.load(context.getAssets(), references, config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database);
             Harness pipeline = new Harness(recognizer, config)) {
            RecognitionRequest request = new RecognitionRequest(
                    new RecognitionRequest.AssemblyExpectation(BOARD, 120.0, 30.0, null),
                    calibration, null, null, 1, 1);
            RecognitionResult result = pipeline.analyze(board, request);
            assertTrue("Wrong installation orientation does not erase board identity", result.isBoardDetected());
            assertEquals(RecognitionResult.ValidationState.WRONG_ORIENTATION, result.getValidationState());
            assertSame("A failing orientation check must not perform another detector pass",
                    result.getBoardObservation().getDetection().getLatency(), result.getLatency());
        }
    }

    @Test public void stepOneLockAdvancesThroughFourFramesAtRealAnalyzerCadence() throws Exception {
        VisionConfig config = config(12);
        try (ReferenceDatabase database = ReferenceDatabase.load(context.getAssets(), references, config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database);
             Harness pipeline = new Harness(recognizer, config, true)) {
            VisualPlacementGate gate = new VisualPlacementGate(4);
            long previousTimestamp = 0L;
            for (int frame = 1; frame <= 4; frame++) {
                RecognitionResult result = pipeline.analyze(board, request(1, true));
                assertEquals(RecognitionResult.ValidationState.EXPECTED_COMPONENT, result.getValidationState());
                assertNotNull("Step 1 locking needs a fresh pose", result.getPose());
                float[] normalizedCorners = new float[8];
                for (int index = 0; index < 4; index++) {
                    normalizedCorners[index * 2] = (float) (result.getProjectedCorners().get(index).getX() / WIDTH);
                    normalizedCorners[index * 2 + 1] = (float) (result.getProjectedCorners().get(index).getY() / HEIGHT);
                }
                // Capture timestamps advance by actual native analysis time, matching CameraX's
                // serial KEEP_ONLY_LATEST analyzer. Synthetic +100ms timestamps hide slow frames
                // that repeatedly expire the placement gate's 750ms interval at 1/4.
                VisualPlacementGate.Observation lock = gate.observe(result.getComponentId(),
                        normalizedCorners, result.getPose().getTranslationVector(), true, .020f,
                        result.getFrameTimestampNanos());
                double gapMs = previousTimestamp == 0L ? 0.0
                        : (result.getFrameTimestampNanos() - previousTimestamp) / 1_000_000.0;
                assertEquals("Frame " + frame + ": stable board lock reset at gap=" + gapMs
                                + "ms, detector=" + result.getLatency().getTotalMs() + "ms",
                        frame, lock.getStableFrames());
                assertEquals(frame == 4, lock.isReady());
                previousTimestamp = result.getFrameTimestampNanos();
            }
        }
    }

    @Test public void sustainedStepOneThenSlowerStepTwoWithoutMotorsKeepLocking() throws Exception {
        VisionConfig config = config(12);
        try (ReferenceDatabase database = ReferenceDatabase.load(context.getAssets(), references, config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database);
             Harness pipeline = new Harness(recognizer, config, true)) {
            VisualPlacementGate gate = new VisualPlacementGate(4);
            long start = SystemClock.uptimeMillis();
            int frames = 0;
            do {
                RecognitionResult result = pipeline.analyze(board, request(1, true));
                assertEquals(RecognitionResult.ValidationState.EXPECTED_COMPONENT, result.getValidationState());
                assertSame(result.getBoardObservation().getDetection().getLatency(), result.getLatency());
                VisualPlacementGate.Observation lock = observeBoard(gate, result);
                assertEquals("Sustained Step 1 reset: " + lock.getResetReason()
                                + ", gap=" + lock.getFrameGapMillis(),
                        Math.min(++frames, 4), lock.getStableFrames());
            } while (SystemClock.uptimeMillis() - start < 32_000L || frames < 4);

            for (int step : new int[]{1, 2}) {
                // Advancing a guided card starts a new UI lock; the board analyzer stays alive.
                if (step == 2) gate.reset();
                for (int frame = 1; frame <= 4; frame++) {
                    // Deliberately reduce capture cadence AFTER >30s of real native analysis.
                    // This is controlled load simulation, not a claim about device temperature.
                    SystemClock.sleep(800L);
                    RecognitionResult result = pipeline.analyze(board, request(step, true));
                    VisualPlacementGate.Observation lock = observeBoard(gate, result);
                    assertTrue("Delay must exercise the former 750ms limit", lock.getFrameGapMillis() > 750);
                    assertEquals("Step " + step + ", reset=" + lock.getResetReason()
                                    + ", gap=" + lock.getFrameGapMillis(),
                            step == 1 ? 4 : frame, lock.getStableFrames());
                    assertEquals(step == 1 || frame == 4, lock.isReady());
                    assertFalse(result.getBoardObservation().isTracked());
                    if (step == 2) {
                        assertNotNull(result.getMotorAssembly());
                        assertFalse(result.getMotorAssembly().getLeft().isAccepted());
                        assertFalse(result.getMotorAssembly().getRight().isAccepted());
                    }
                    Log.i("AssemblArBoard", "sustainedRegression step=" + step
                            + " elapsedMs=" + (SystemClock.uptimeMillis() - start)
                            + " frameGapMs=" + lock.getFrameGapMillis()
                            + " stable=" + lock.getStableFrames() + "/4"
                            + " resetReason=" + lock.getResetReason());
                }
            }
        }
    }

    private static VisualPlacementGate.Observation observeBoard(
            VisualPlacementGate gate, RecognitionResult result) {
        assertTrue(result.isBoardDetected());
        RecognitionResult.SpatialAnchor anchor = result.getBoardObservation().getAnchor();
        assertNotNull(anchor);
        assertNotNull(anchor.getPose());
        float[] normalizedCorners = new float[8];
        for (int index = 0; index < 4; index++) {
            normalizedCorners[index * 2] = (float) (anchor.getProjectedCorners().get(index).getX() / WIDTH);
            normalizedCorners[index * 2 + 1] = (float) (anchor.getProjectedCorners().get(index).getY() / HEIGHT);
        }
        return gate.observe(anchor.getComponentId(), normalizedCorners,
                anchor.getPose().getTranslationVector(), true, .020f, result.getFrameTimestampNanos());
    }

    @Test public void componentProcessingExceptionStillDeliversAlreadyDetectedBoard() throws Exception {
        VisionConfig config = config(12);
        try (ReferenceDatabase database = ReferenceDatabase.load(context.getAssets(), references, config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database);
             Harness pipeline = new Harness(recognizer, config)) {
            pipeline.allowComponentError = true;
            RecognitionResult result = pipeline.analyze(board, new RecognitionRequest(
                    RecognitionRequest.AssemblyExpectation.component("missing_registration"),
                    calibration, null, BOARD, 1, 3));
            assertTrue("Expected-component validation really failed", pipeline.failure instanceof IllegalArgumentException);
            assertTrue(result.isBoardDetected());
            assertNotNull(result.getBoardObservation().getAnchor());
            assertEquals(RecognitionResult.ValidationState.NO_COMPONENT, result.getValidationState());
        }
    }

    @Test public void partialOcclusionTracksAcrossStepChangesAndExplicitResetClearsIt() throws Exception {
        Mat occluded = board.clone();
        try {
            // Cover the mounting zones while retaining the exposed center and top/bottom PCB.
            Imgproc.rectangle(occluded, new Point(177, 175), new Point(257, 365),
                    new Scalar(115), Imgproc.FILLED);
            Imgproc.rectangle(occluded, new Point(383, 175), new Point(463, 365),
                    new Scalar(115), Imgproc.FILLED);
            int cleanMatches;
            int occludedMatches;
            VisionConfig ordinaryConfig = config(12);
            try (ReferenceDatabase database = ReferenceDatabase.load(
                    context.getAssets(), references, ordinaryConfig);
                 ComponentRecognizer recognizer = new ComponentRecognizer(ordinaryConfig, database)) {
                RecognitionResult clean = recognizer.recognizeBoard(board, 1L, calibration, 1);
                RecognitionResult covered = recognizer.recognizeBoard(occluded, 2L, calibration, 2);
                assertNotNull("Fixture must produce a calibrated initial anchor", clean.getSpatialAnchor());
                cleanMatches = clean.getGoodMatchCount();
                occludedMatches = covered.getGoodMatchCount();
                assertTrue("Covering side texture must reduce fixture matches", cleanMatches > occludedMatches + 12);
            }
            // Deliberately require the clean fixture's evidence in this test alone. This induces
            // a real ORB dropout without mocking the final recognizer or relaxing production rules.
            VisionConfig strictConfig = config(cleanMatches);
            try (ReferenceDatabase database = ReferenceDatabase.load(
                    context.getAssets(), references, strictConfig);
                 ComponentRecognizer recognizer = new ComponentRecognizer(strictConfig, database);
                 Harness pipeline = new Harness(recognizer, strictConfig)) {
                assertTrue(pipeline.analyze(board, request(1, true)).isBoardDetected());
                RecognitionResult tracked = pipeline.analyze(occluded, request(2, true));
                assertNull("Fresh detector intentionally fails under partial occlusion",
                        tracked.getBoardObservation().getDetection().getSpatialAnchor());
                assertTrue("Step 1 must seed the shared tracker for Step 2", tracked.isBoardDetected());
                assertTrue(tracked.getBoardObservation().isTracked());
                RecognitionResult next = pipeline.analyze(occluded, request(3, false));
                assertTrue("Intermediate next step retains the optical-flow bridge", next.isBoardDetected());
                assertTrue(next.getBoardObservation().isTracked());
                pipeline.analyzer.resetAssemblyTracking();
                RecognitionResult reset = pipeline.analyze(occluded, request(3, false));
                assertFalse("Explicit relock must discard the prior board bridge", reset.isBoardDetected());
            }
        } finally { occluded.release(); }
    }

    private RecognitionRequest request(int step, boolean inspectComponent) {
        String anchor = step == 1 ? null : step == 14 || step >= 17 ? EXTENSION : BOARD;
        RecognitionRequest.AssemblyExpectation expectation = !inspectComponent ? null : step == 1
                ? new RecognitionRequest.AssemblyExpectation(BOARD, 0.0, 30.0, null)
                : RecognitionRequest.AssemblyExpectation.component(EXPECTED[step - 1]);
        return new RecognitionRequest(expectation, calibration, null, anchor,
                inspectComponent && (step == 2 || step == 10 || step == 13) ? 2 : 1, step);
    }

    private static VisionConfig config(int minimumGoodMatches) {
        return VisionConfig.builder().minimumFrameIntervalMs(0)
                .matching(.75f, 64f, minimumGoodMatches).build();
    }

    private static VisionPoint normalized(VisionPoint point) {
        return new VisionPoint(point.getX() / (WIDTH - 1), point.getY() / (HEIGHT - 1));
    }

    private static void assertCornersEqual(String label, List<VisionPoint> expected,
                                           List<VisionPoint> actual) {
        assertEquals(label, expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertEquals(label, expected.get(index).getX(), actual.get(index).getX(), .001);
            assertEquals(label, expected.get(index).getY(), actual.get(index).getY(), .001);
        }
    }

    private static Mat texturedBoard(long seed) {
        Mat image = new Mat(HEIGHT, WIDTH, CvType.CV_8UC1, new Scalar(24));
        Imgproc.rectangle(image, new Point(177, 75), new Point(463, 405),
                new Scalar(190), Imgproc.FILLED);
        Random random = new Random(seed);
        for (int index = 0; index < 320; index++) {
            int x = 184 + random.nextInt(271);
            int y = 82 + random.nextInt(315);
            int shade = 25 + random.nextInt(225);
            int radius = 2 + random.nextInt(6);
            if (random.nextBoolean()) {
                Imgproc.circle(image, new Point(x, y), radius, new Scalar(shade), Imgproc.FILLED);
            } else {
                Imgproc.rectangle(image, new Point(x, y), new Point(x + radius, y + radius),
                        new Scalar(shade), Imgproc.FILLED);
            }
        }
        return image;
    }

    private static final class Harness implements AutoCloseable {
        final AssemblyVisionAnalyzer analyzer;
        private RecognitionRequest currentRequest;
        private RecognitionResult lastResult;
        private Throwable failure;
        private boolean allowComponentError;
        private final boolean realtimeTimestamps;
        private long timestampNanos = 2_000_000_000L;

        Harness(ComponentRecognizer recognizer, VisionConfig config) {
            this(recognizer, config, false);
        }

        Harness(ComponentRecognizer recognizer, VisionConfig config, boolean realtimeTimestamps) {
            this.realtimeTimestamps = realtimeTimestamps;
            analyzer = new AssemblyVisionAnalyzer(recognizer, metadata -> currentRequest,
                    new AssemblyVisionAnalyzer.Callback() {
                        @Override public void onResult(RecognitionResult result) { lastResult = result; }
                        @Override public void onError(Throwable cause) { failure = cause; }
                    }, config);
        }

        RecognitionResult analyze(Mat camera, RecognitionRequest request) {
            currentRequest = request;
            lastResult = null;
            failure = null;
            timestampNanos = realtimeTimestamps ? SystemClock.elapsedRealtimeNanos()
                    : timestampNanos + 100_000_000L;
            byte[] y = new byte[WIDTH * HEIGHT];
            camera.get(0, 0, y);
            byte[] original = y.clone();
            boolean[] closed = {false};
            analyzer.analyzeForReplay(image(y, timestampNanos, closed));
            assertTrue("Every camera frame must close", closed[0]);
            assertArrayEquals("Board/component processing must leave original camera bytes untouched", original, y);
            if (failure != null && !allowComponentError) throw new AssertionError("Native analyzer failed", failure);
            assertNotNull("Unthrottled frame must deliver a result", lastResult);
            return lastResult;
        }

        @Override public void close() { analyzer.close(); }
    }

    private static ImageProxy image(byte[] y, long timestamp, boolean[] closed) {
        byte[] chroma = new byte[WIDTH * HEIGHT / 4];
        Arrays.fill(chroma, (byte) 128);
        ImageProxy.PlaneProxy[] planes = {plane(y, WIDTH), plane(chroma, WIDTH / 2),
                plane(chroma.clone(), WIDTH / 2)};
        ImageInfo info = (ImageInfo) Proxy.newProxyInstance(ImageInfo.class.getClassLoader(),
                new Class<?>[]{ImageInfo.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getTimestamp": return timestamp;
                        case "getRotationDegrees": return 0;
                        case "getSensorToBufferTransformMatrix": return new Matrix();
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
        return (ImageProxy) Proxy.newProxyInstance(ImageProxy.class.getClassLoader(),
                new Class<?>[]{ImageProxy.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getFormat": return ImageFormat.YUV_420_888;
                        case "getWidth": return WIDTH;
                        case "getHeight": return HEIGHT;
                        case "getPlanes": return planes;
                        case "getCropRect": return new Rect(0, 0, WIDTH, HEIGHT);
                        case "getImageInfo": return info;
                        case "close": closed[0] = true; return null;
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private static ImageProxy.PlaneProxy plane(byte[] bytes, int stride) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new ImageProxy.PlaneProxy() {
            @Override public int getRowStride() { return stride; }
            @Override public int getPixelStride() { return 1; }
            @Override public ByteBuffer getBuffer() { return buffer; }
        };
    }
}
