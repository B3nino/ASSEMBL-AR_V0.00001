package dev.chyken.assembl_ar;

import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.util.SizeF;
import android.widget.TextView;

import androidx.camera.core.ImageInfo;
import androidx.camera.core.ImageProxy;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import dev.chyken.assembl_ar.camera.CameraCalibrationProvider;
import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.ui.ArHudView;
import dev.chyken.assembl_ar.vision.AssemblyVisionAnalyzer;
import dev.chyken.assembl_ar.vision.ComponentRecognizer;
import dev.chyken.assembl_ar.vision.ModelBasedPartRecognizer;
import dev.chyken.assembl_ar.vision.RecognitionRequest;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.ReferenceDatabase;
import dev.chyken.assembl_ar.vision.VisionConfig;
import dev.chyken.assembl_ar.workflow.AssemblyStateMachine;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;
import dev.chyken.assembl_ar.workflow.GuidedRecognitionCatalog;
import dev.chyken.assembl_ar.workflow.GuidedCheckpoint;
import dev.chyken.assembl_ar.workflow.GuidedStepProgressStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Replays photographs of the real kit through the production camera pipeline and the live
 * assembly gates: the packaged reference catalog, {@link AssemblyVisionAnalyzer} at CameraX's
 * default 640x480 analysis size, the activity's own per-card recognition request, and its
 * {@code handleRecognition} decisions. A still photo replayed as consecutive frames tests
 * recognition and gating, not hand motion. Fixtures are held-out captures that are not
 * registered references; see tools/prepare-assembly-replay-fixtures.py.
 */
final class AssemblyReplayHarness implements AutoCloseable {
    static final String TAG = "AssemblyReplay";
    static final String REPLAY = "references/assembly-replay/";
    /** CameraX ImageAnalysis default target resolution when no resolution selector is set. */
    static final int ANALYSIS_LONG_EDGE = 640;
    /** Source phone intrinsics: 3182.6 px focal length at a 4080 px long edge. */
    static final double KIT_PHONE_FOCAL_RATIO = 3182.592375842252 / 4080.0;

    final Context context;
    final ActivityScenario<AssemblyActivity> scenario;
    final AssemblyActivity activity;
    private final ReferenceDatabase database;
    private final ComponentRecognizer recognizer;
    private final ModelBasedPartRecognizer models;

    AssemblyReplayHarness() throws Exception {
        assertTrue(OpenCVLoader.initLocal());
        context = ApplicationProvider.getApplicationContext();
        GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        AssemblArRepository.getInstance(context).resetProjectProgress(NeobotSeedData.PROJECT_ID);
        VisionConfig config = VisionConfig.defaults();
        // Same catalog, configuration and cache location as AssemblyActivity.initializeVision.
        database = ReferenceDatabase.load(context.getAssets(),
                VisionReferenceFactory.build(AssemblArRepository.getInstance(context)), config,
                new File(context.getNoBackupFilesDir(), "orb_reference_cache_v3"));
        recognizer = new ComponentRecognizer(config, database);
        models = new ModelBasedPartRecognizer(context.getAssets());
        // The simulated extra keeps the emulator's virtual camera from delivering its own frames.
        scenario = ActivityScenario.launch(new Intent(context, AssemblyActivity.class)
                .putExtra(AssemblyActivity.EXTRA_SIMULATED_STATE, AssemblyActivity.SIMULATE_MISMATCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AssemblyActivity[] loaded = new AssemblyActivity[1];
        for (int attempt = 0; attempt < 80 && loaded[0] == null; attempt++) {
            scenario.onActivity(a -> {
                if (field(a, "stateMachine") != null && a.findViewById(R.id.previewView).getWidth() > 0) {
                    loaded[0] = a;
                }
            });
            if (loaded[0] == null) SystemClock.sleep(100L);
        }
        assertNotNull("Assembly did not load", loaded[0]);
        activity = loaded[0];
    }

    @Override public void close() {
        scenario.close();
        recognizer.close();
        database.close();
        GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        AssemblArRepository.getInstance(context).resetProjectProgress(NeobotSeedData.PROJECT_ID);
    }

    /** Opens one authored card with no completed checkpoints in its section. */
    void showCard(int sectionNumber, int instructionOrder) {
        scenario.onActivity(a -> {
            @SuppressWarnings("unchecked")
            List<AssemblyStep> steps = (List<AssemblyStep>) field(a, "steps");
            AssemblyStep section = steps.get(sectionNumber - 1);
            GuidedStepProgressStore.clearStep(a, section.getProjectId(), section.getId());
            setField(a, "currentStepIndex", sectionNumber - 1);
            setField(a, "currentStep", section);
            setField(a, "stateMachine", new AssemblyStateMachine(steps.size(), sectionNumber - 1));
            invoke(a, "renderStep");
            @SuppressWarnings("unchecked")
            List<GuidedStep> cards = (List<GuidedStep>) field(a, "currentGuidedSteps");
            for (int index = 0; index < cards.size(); index++) {
                if (cards.get(index).getPrimaryInstruction().getOrder() == instructionOrder) {
                    setField(a, "currentGuidedStepIndex", index);
                    invoke(a, "renderGuidedStep");
                    return;
                }
            }
            throw new AssertionError("Missing section " + sectionNumber + " order " + instructionOrder);
        });
    }

    /** Opens a bracket card after its loose inventory and every earlier bracket action. */
    void showBracketSlot(int slot) {
        showCard(2, slot);
        scenario.onActivity(a -> {
            AssemblyStep section = (AssemblyStep) field(a, "currentStep");
            @SuppressWarnings("unchecked")
            List<GuidedStep> cards = (List<GuidedStep>) field(a, "currentGuidedSteps");
            int index = (Integer) field(a, "currentGuidedStepIndex");
            for (int earlier = 0; earlier < index; earlier++) {
                if (GuidedRecognitionCatalog.checkpointFor(section, cards.get(earlier)) != null) {
                    GuidedStepProgressStore.markCheckpointComplete(a, section.getProjectId(),
                            section.getId(), cards.get(earlier));
                }
            }
            invoke(a, "renderGuidedStep");
            // Slot 1 opens with the four-piece inventory, which is covered by its own replay.
            if ((Boolean) field(a, "awaitingPartScan")) {
                setField(a, "awaitingPartScan", false);
                invoke(a, "startCurrentDemonstration");
            }
            assertEquals(NeobotSeedData.MOTOR_BRACKET, field(a, "activeCheckpointComponentId"));
        });
    }

    boolean awaitingPartScan() { return (Boolean) field(activity, "awaitingPartScan"); }

    /** Opens an installation checkpoint after the section's earlier camera/physical cards. */
    void showCheckpoint(int sectionNumber, int instructionOrder) {
        showCard(sectionNumber, instructionOrder);
        scenario.onActivity(a -> {
            AssemblyStep section = (AssemblyStep) field(a, "currentStep");
            @SuppressWarnings("unchecked")
            List<GuidedStep> cards = (List<GuidedStep>) field(a, "currentGuidedSteps");
            int index = (Integer) field(a, "currentGuidedStepIndex");
            for (int earlier = 0; earlier < index; earlier++) {
                if (GuidedRecognitionCatalog.checkpointFor(section, cards.get(earlier)) != null) {
                    GuidedStepProgressStore.markCheckpointComplete(a, section.getProjectId(),
                            section.getId(), cards.get(earlier));
                }
            }
            invoke(a, "renderGuidedStep");
            // As showBracketSlot does: inventory scans have separate replay coverage.
            if ((Boolean) field(a, "awaitingPartScan")) {
                setField(a, "awaitingPartScan", false);
                invoke(a, "startCurrentDemonstration");
            }
            assertNotNull("Missing checkpoint for " + sectionNumber + "/" + instructionOrder,
                    field(a, "activeCheckpoint"));
        });
    }

    GuidedCheckpoint activeCheckpoint() {
        GuidedCheckpoint[] checkpoint = new GuidedCheckpoint[1];
        scenario.onActivity(a -> checkpoint[0] = (GuidedCheckpoint) field(a, "activeCheckpoint"));
        return checkpoint[0];
    }

    ReferenceDatabase database() { return database; }

    static boolean placementChecked(AssemblyActivity activity) {
        return activity.findViewById(R.id.confirmPlacementButton).getVisibility()
                == android.view.View.VISIBLE;
    }

    static boolean showsMismatch(AssemblyActivity activity) {
        return ((ArHudView) activity.findViewById(R.id.arHudView)).getHudState()
                == ArHudView.HudState.MISMATCH;
    }

    Mat decodeFixture(String name) throws Exception {
        return decode(InstrumentationRegistry.getInstrumentation().getContext(), REPLAY + name);
    }

    static Mat decode(Context assets, String path) throws Exception {
        byte[] bytes;
        try (InputStream input = assets.getAssets().open(path)) { bytes = input.readAllBytes(); }
        MatOfByte encoded = new MatOfByte(bytes);
        Mat bgr = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
        Mat rgb = new Mat();
        Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
        bgr.release();
        encoded.release();
        return rgb;
    }

    /**
     * Sends the same photograph as consecutive live frames until {@code done} holds. Capture
     * timestamps advance with the real native analysis time, as with CameraX KEEP_ONLY_LATEST.
     */
    Outcome replay(String label, Mat rgb, double focalRatio, int maxFrames,
                   Predicate<AssemblyActivity> done) {
        Mat frame = analysisFrame(rgb);
        Outcome outcome = new Outcome(label);
        scenario.onActivity(a -> {
            setField(a, "calibrationProvider", calibration(frame.cols(), frame.rows(), focalRatio));
            matchPreviewAspect(a, frame.cols(), frame.rows());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        RecognitionResult[] delivered = new RecognitionResult[1];
        Throwable[] failure = new Throwable[1];
        AssemblyVisionAnalyzer analyzer = analyzer(delivered, failure);
        long replayStarted = SystemClock.elapsedRealtimeNanos();
        try {
            for (int index = 1; index <= maxFrames && !outcome.reached; index++) {
                delivered[0] = null;
                long started = SystemClock.elapsedRealtimeNanos();
                analyzer.analyzeForReplay(image(frame, started));
                if (failure[0] != null) throw new AssertionError(label + " analyzer failed", failure[0]);
                RecognitionResult result = delivered[0];
                assertNotNull(label + " frame " + index + " produced no result", result);
                long completedAt = SystemClock.elapsedRealtimeNanos();
                double analysisMs = (completedAt - started) / 1e6;
                boolean[] state = new boolean[2];
                String[] banner = {""};
                long[] gateState = new long[2];
                scenario.onActivity(a -> {
                    // As AssemblyActivity's camera callback does, report when analysis finished.
                    invoke(a, "handleRecognition", new Class<?>[]{RecognitionResult.class, long.class},
                            result, completedAt);
                    state[0] = done.test(a);
                    state[1] = showsMismatch(a);
                    banner[0] = String.valueOf(((TextView) a.findViewById(R.id.recognitionText)).getText());
                    gateState[0] = (Long) field(a, "frameEvidenceNanos");
                    gateState[1] = (Integer) field(field(a, "bracketSetGate"), "acceptedFrames");
                });
                double elapsedMs = (SystemClock.elapsedRealtimeNanos() - replayStarted) / 1e6;
                outcome.add(new FrameRecord(index, result, analysisMs, elapsedMs, state[1], banner[0]));
                if (state[0]) {
                    outcome.reached = true;
                    outcome.reachedMs = elapsedMs;
                }
                Log.i(TAG, String.format(Locale.US,
                        "%s frame=%d size=%dx%d id=%s state=%s method=%s instances=%d board=%s "
                                + "good=%d inliers=%d ratio=%.2f featureMs=%.1f matchingMs=%.1f "
                                + "analysisMs=%.1f evidenceMs=%.1f setFrames=%d reached=%s mismatch=%s banner=\"%s\"",
                        label, index, frame.cols(), frame.rows(), result.getComponentId(),
                        result.getValidationState(), result.getDetectionMethod(),
                        result.getDetectedInstanceCount(NeobotSeedData.MOTOR_BRACKET),
                        result.getSpatialAnchor() == null ? "none" : result.getSpatialAnchor().getComponentId(),
                        result.getGoodMatchCount(), result.getInlierCount(), result.getInlierRatio(),
                        result.getLatency().getFeatureExtractionMs(), result.getLatency().getMatchingMs(),
                        analysisMs, gateState[0] / 1e6, gateState[1], state[0], state[1], banner[0]));
            }
        } finally {
            analyzer.close();
            frame.release();
        }
        Log.i(TAG, label + " outcome " + outcome);
        return outcome;
    }

    /**
     * The camera pipeline's last result for the open card, without the activity's evidence
     * gates: the same request, analyzer and catalog as {@link #replay}, for fast per-photo checks.
     */
    RecognitionResult analyze(Mat rgb, double focalRatio, int frames) {
        Mat frame = analysisFrame(rgb);
        scenario.onActivity(a -> {
            setField(a, "calibrationProvider", calibration(frame.cols(), frame.rows(), focalRatio));
            matchPreviewAspect(a, frame.cols(), frame.rows());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        RecognitionResult[] delivered = new RecognitionResult[1];
        Throwable[] failure = new Throwable[1];
        AssemblyVisionAnalyzer analyzer = analyzer(delivered, failure);
        try {
            for (int index = 0; index < frames; index++) {
                delivered[0] = null;
                analyzer.analyzeForReplay(image(frame, SystemClock.elapsedRealtimeNanos()));
                if (failure[0] != null) throw new AssertionError("Analyzer failed", failure[0]);
                assertNotNull("Frame " + (index + 1) + " produced no result", delivered[0]);
            }
            return delivered[0];
        } finally {
            analyzer.close();
            frame.release();
        }
    }

    private AssemblyVisionAnalyzer analyzer(RecognitionResult[] delivered, Throwable[] failure) {
        return new AssemblyVisionAnalyzer(recognizer,
                metadata -> (RecognitionRequest) invoke(activity, "recognitionRequestFor",
                        new Class<?>[]{AssemblyVisionAnalyzer.FrameMetadata.class}, metadata),
                new AssemblyVisionAnalyzer.Callback() {
                    @Override public void onResult(RecognitionResult result) { delivered[0] = result; }
                    @Override public void onError(Throwable cause) { failure[0] = cause; }
                }, VisionConfig.builder().minimumFrameIntervalMs(0).build(), true, models);
    }

    static final class FrameRecord {
        final int index;
        final RecognitionResult result;
        final double analysisMs;
        final double elapsedMs;
        final boolean mismatchShown;
        final String banner;

        FrameRecord(int index, RecognitionResult result, double analysisMs, double elapsedMs,
                    boolean mismatchShown, String banner) {
            this.index = index;
            this.result = result;
            this.analysisMs = analysisMs;
            this.elapsedMs = elapsedMs;
            this.mismatchShown = mismatchShown;
            this.banner = banner;
        }
    }

    static final class Outcome {
        final String label;
        final List<FrameRecord> frames = new ArrayList<>();
        boolean reached;
        double reachedMs = Double.NaN;

        Outcome(String label) { this.label = label; }

        void add(FrameRecord record) { frames.add(record); }

        /** Elapsed time until the mismatch indicator first appeared, or NaN if it never did. */
        double firstMismatchMs() {
            for (FrameRecord frame : frames) if (frame.mismatchShown) return frame.elapsedMs;
            return Double.NaN;
        }

        boolean anyMismatch() { return !Double.isNaN(firstMismatchMs()); }

        double meanAnalysisMs() {
            double total = 0.0;
            for (FrameRecord frame : frames) total += frame.analysisMs;
            return frames.isEmpty() ? 0.0 : total / frames.size();
        }

        String lastBanner() { return frames.isEmpty() ? "" : frames.get(frames.size() - 1).banner; }

        @Override public String toString() {
            return String.format(Locale.US,
                    "reached=%s frames=%d reachedMs=%.0f meanAnalysisMs=%.0f mismatch=%s banner=\"%s\"",
                    reached, frames.size(), reachedMs, meanAnalysisMs(), anyMismatch(), lastBanner());
        }
    }

    /**
     * CameraX's shared ViewPort crops every analysis frame to the preview's aspect ratio, so the
     * activity maps frame coordinates onto the preview without cropping. Portrait phone photos on
     * a landscape tablet would otherwise lose their top and bottom in that mapping.
     */
    private static void matchPreviewAspect(AssemblyActivity activity, int frameWidth, int frameHeight) {
        android.view.View preview = activity.findViewById(R.id.previewView);
        android.view.View parent = (android.view.View) preview.getParent();
        double scale = Math.min(parent.getWidth() / (double) frameWidth,
                parent.getHeight() / (double) frameHeight);
        android.view.ViewGroup.LayoutParams params = preview.getLayoutParams();
        int width = (int) Math.round(frameWidth * scale), height = (int) Math.round(frameHeight * scale);
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            preview.setLayoutParams(params);
        }
    }

    /** Downscales a photo to the default analysis size, preserving its 4:3 aspect. */
    static Mat analysisFrame(Mat rgb) {
        double scale = ANALYSIS_LONG_EDGE / (double) Math.max(rgb.cols(), rgb.rows());
        int width = (int) Math.round(rgb.cols() * scale) & ~1;
        int height = (int) Math.round(rgb.rows() * scale) & ~1;
        Mat resized = new Mat();
        Imgproc.resize(rgb, resized, new org.opencv.core.Size(width, height), 0, 0, Imgproc.INTER_AREA);
        return resized;
    }

    static CameraCalibrationProvider calibration(int width, int height, double focalRatio) {
        double focalPx = focalRatio * Math.max(width, height);
        try {
            Method factory = CameraCalibrationProvider.class.getDeclaredMethod("fromCharacteristics",
                    float[].class, Rect.class, Rect.class, Float.class, SizeF.class, Size.class);
            factory.setAccessible(true);
            // 1 px = 1 um sensor makes the focal-length metadata equal the intended pixel focal length.
            return (CameraCalibrationProvider) factory.invoke(null, null, new Rect(0, 0, width, height),
                    null, (float) (focalPx / 1000.0), new SizeF(width / 1000f, height / 1000f),
                    new Size(width, height));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not build camera calibration", failure);
        }
    }

    /** A YUV_420_888 CameraX frame carrying the photo's real chroma for colour-based fallbacks. */
    static ImageProxy image(Mat rgb, long timestampNanos) {
        int width = rgb.cols(), height = rgb.rows();
        Mat i420 = new Mat();
        Imgproc.cvtColor(rgb, i420, Imgproc.COLOR_RGB2YUV_I420);
        byte[] packed = new byte[(int) (i420.total() * i420.channels())];
        i420.get(0, 0, packed);
        i420.release();
        int lumaBytes = width * height, chromaBytes = lumaBytes / 4;
        ImageProxy.PlaneProxy[] planes = {
                plane(ByteBuffer.wrap(packed, 0, lumaBytes).slice(), width),
                plane(ByteBuffer.wrap(packed, lumaBytes, chromaBytes).slice(), width / 2),
                plane(ByteBuffer.wrap(packed, lumaBytes + chromaBytes, chromaBytes).slice(), width / 2)};
        ImageInfo info = (ImageInfo) Proxy.newProxyInstance(ImageInfo.class.getClassLoader(),
                new Class<?>[]{ImageInfo.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getTimestamp": return timestampNanos;
                        case "getRotationDegrees": return 0;
                        case "getSensorToBufferTransformMatrix": return new Matrix();
                        case "getTagBundle": return androidx.camera.core.impl.TagBundle.emptyBundle();
                        case "populateExifData": return null;
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
        return (ImageProxy) Proxy.newProxyInstance(ImageProxy.class.getClassLoader(),
                new Class<?>[]{ImageProxy.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getFormat": return ImageFormat.YUV_420_888;
                        case "getWidth": return width;
                        case "getHeight": return height;
                        case "getPlanes": return planes;
                        case "getCropRect": return new Rect(0, 0, width, height);
                        case "getImageInfo": return info;
                        case "close": return null;
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private static ImageProxy.PlaneProxy plane(ByteBuffer buffer, int rowStride) {
        return new ImageProxy.PlaneProxy() {
            @Override public int getRowStride() { return rowStride; }
            @Override public int getPixelStride() { return 1; }
            @Override public ByteBuffer getBuffer() { return buffer; }
        };
    }

    static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not read " + name, failure);
        }
    }

    static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not set " + name, failure);
        }
    }

    static void invoke(Object target, String name) {
        invoke(target, name, new Class<?>[0]);
    }

    static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError("Could not invoke " + name, cause);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not invoke " + name, failure);
        }
    }
}
