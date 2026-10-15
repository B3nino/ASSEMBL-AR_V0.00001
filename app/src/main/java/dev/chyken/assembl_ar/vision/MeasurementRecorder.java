package dev.chyken.assembl_ar.vision;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Section 3.3.1 phone-session measurements, off by default. One line per analysed frame under
 * {@code AssemblArMetrics} (Tests A-D: recognition, timing, board anchor, pose reprojection
 * error), enabled with {@code adb shell setprop log.tag.AssemblArMetrics DEBUG}. With
 * {@code log.tag.AssemblArCapture DEBUG} it also saves the analysed RGB frame (at most four per
 * second) under the app's external files {@code measure/} folder, so overlay positions can be
 * checked against the printed measurement mat afterwards. Neither changes any decision.
 */
public final class MeasurementRecorder {
    static final String METRICS_TAG = "AssemblArMetrics";
    static final String CAPTURE_TAG = "AssemblArCapture";
    private static final long CAPTURE_INTERVAL_NANOS = 250_000_000L;
    private static final int MAXIMUM_CAPTURES = 6000;
    private static final int MAXIMUM_PENDING_WRITES = 3;
    @Nullable private static volatile Context application;

    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "measurement-capture");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger pendingWrites = new AtomicInteger();
    private long lastCaptureNanos = Long.MIN_VALUE;
    private int captures;
    @Nullable private String session;
    @Nullable private String lastCalibration;

    /** Lets frame capture resolve the app's external files folder; safe to call repeatedly. */
    public static void install(Context context) {
        application = context.getApplicationContext();
    }

    static boolean enabled() {
        return Log.isLoggable(METRICS_TAG, Log.DEBUG);
    }

    /** Analysis thread only: whether this frame should be saved. */
    boolean captureDue(long frameNanos) {
        return application != null && Log.isLoggable(CAPTURE_TAG, Log.DEBUG)
                && captures < MAXIMUM_CAPTURES
                && (lastCaptureNanos == Long.MIN_VALUE
                || frameNanos - lastCaptureNanos >= CAPTURE_INTERVAL_NANOS)
                && pendingWrites.get() < MAXIMUM_PENDING_WRITES;
    }

    /** Analysis thread only. Returns the saved frame's name, or null when none was saved. */
    @Nullable
    String capture(Mat rgb, long frameNanos) {
        Context context = application;
        if (rgb == null || rgb.empty() || context == null) return null;
        if (session == null) {
            session = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        }
        lastCaptureNanos = frameNanos;
        captures++;
        String name = frameNanos + ".jpg";
        String folder = session;
        Mat bgr = new Mat();
        Imgproc.cvtColor(rgb, bgr, Imgproc.COLOR_RGB2BGR);
        pendingWrites.incrementAndGet();
        writer.execute(() -> {
            MatOfInt quality = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 92);
            try {
                File directory = new File(context.getExternalFilesDir("measure"), folder);
                if (!directory.isDirectory() && !directory.mkdirs()) return;
                Imgcodecs.imwrite(new File(directory, name).getAbsolutePath(), bgr, quality);
            } catch (RuntimeException failure) {
                Log.w(CAPTURE_TAG, "Frame capture failed", failure);
            } finally {
                quality.release();
                bgr.release();
                pendingWrites.decrementAndGet();
            }
        });
        return folder + "/" + name;
    }

    /** Analysis thread only. */
    void log(AssemblyVisionAnalyzer.FrameMetadata frame, @Nullable RecognitionRequest request,
             RecognitionResult result, double analysisMs, @Nullable String capture) {
        CameraCalibration calibration = request == null ? null : request.getCameraCalibration();
        if (calibration != null) {
            String described = "camera=" + Arrays.toString(calibration.getCameraMatrixRowMajor())
                    + " distortion=" + Arrays.toString(calibration.getDistortionCoefficients())
                    + " source=" + calibration.getSource();
            if (!described.equals(lastCalibration)) {
                lastCalibration = described;
                Log.d(METRICS_TAG, "calibration analyzed=" + frame.getAnalyzedWidth() + "x"
                        + frame.getAnalyzedHeight() + " " + described);
            }
        }
        RecognitionResult.Latency latency = result.getLatency();
        RecognitionResult.BoardObservation board = result.getBoardObservation();
        RecognitionResult.SpatialAnchor anchor = board == null ? null : board.getAnchor();
        String expected = request == null || request.getExpectation() == null
                ? null : request.getExpectation().getComponentId();
        Log.d(METRICS_TAG, String.format(Locale.US,
                "frame=%d analyzed=%dx%d step=%d expected=%s component=%s validation=%s method=%s "
                        + "reference=%s keypoints=%d good=%d inliers=%d inlierRatio=%.3f "
                        + "featureMs=%.2f matchingMs=%.2f spatialMs=%.2f recognizerMs=%.2f "
                        + "analysisMs=%.2f corners=%s posePx=%.3f board=%s boardSource=%s "
                        + "boardReference=%s boardCorners=%s boardPosePx=%.3f boardRvec=%s "
                        + "boardTvec=%s capture=%s",
                frame.getTimestampNanos(), frame.getAnalyzedWidth(), frame.getAnalyzedHeight(),
                result.getAssemblyStepNumber(), expected, result.getComponentId(),
                result.getValidationState(), result.getDetectionMethod(), result.getReferenceId(),
                result.getFrameKeypointCount(), result.getGoodMatchCount(), result.getInlierCount(),
                result.getInlierRatio(),
                latency == null ? Double.NaN : latency.getFeatureExtractionMs(),
                latency == null ? Double.NaN : latency.getMatchingMs(),
                latency == null ? Double.NaN : latency.getSpatialValidationMs(),
                latency == null ? Double.NaN : latency.getTotalMs(), analysisMs,
                points(result.getProjectedCorners()), poseError(result.getPose()),
                board == null ? "not_run" : Boolean.toString(board.isDetected()),
                board == null ? "none" : board.isTracked() ? "tracking"
                        : anchor != null ? "detection" : "none",
                anchor == null ? null : anchor.getReferenceId(),
                anchor == null ? "[]" : points(anchor.getProjectedCorners()),
                anchor == null ? Double.NaN : poseError(anchor.getPose()),
                anchor == null || anchor.getPose() == null ? "[]"
                        : Arrays.toString(anchor.getPose().getRotationVector()),
                anchor == null || anchor.getPose() == null ? "[]"
                        : Arrays.toString(anchor.getPose().getTranslationVector()),
                capture));
    }

    void close() {
        writer.shutdown();
    }

    private static double poseError(@Nullable RecognitionResult.Pose pose) {
        return pose == null ? Double.NaN : pose.getReprojectionErrorPx();
    }

    private static String points(@Nullable List<VisionPoint> points) {
        if (points == null || points.isEmpty()) return "[]";
        StringBuilder text = new StringBuilder("[");
        for (int index = 0; index < points.size(); index++) {
            if (index > 0) text.append(';');
            text.append(String.format(Locale.US, "%.2f,%.2f",
                    points.get(index).getX(), points.get(index).getY()));
        }
        return text.append(']').toString();
    }
}
