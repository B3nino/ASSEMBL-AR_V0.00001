package dev.chyken.assembl_ar.vision;

import android.util.Log;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.core.TermCriteria;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Short-lived optical-flow bridge between trustworthy board detections.
 *
 * <p>The tracker never establishes identity on its own. A fresh {@link
 * RecognitionResult.SpatialAnchor} initializes Shi-Tomasi features on the planar board surface;
 * subsequent frames use forward/backward pyramidal LK and a RANSAC homography to carry that
 * anchor for at most {@value #MAX_TRACKING_AGE_MILLIS} ms. Failure, implausible camera motion, a
 * changed frame size or expiry clears optical flow. A short, bounded visual hold bridges blur.</p>
 *
 * <p>Features are collected inside the board perimeter while excluding the two side motor zones.
 * This prevents the moving, non-planar gear motors from becoming accidental anchor evidence.
 * Completion logic must still require a fresh detector observation; flow output is intended for
 * continuous guidance only.</p>
 */
public final class AssemblyAnchorTracker implements AutoCloseable {
    static final long MAX_TRACKING_AGE_MILLIS = 1500L;
    private static final String DIAGNOSTIC_TAG = "AssemblArBoard";

    private static final long MAX_TRACKING_AGE_NANOS =
            MAX_TRACKING_AGE_MILLIS * 1_000_000L;
    private static final int MAX_CANDIDATE_FEATURES = 240;
    private static final int MAX_FEATURES_PER_GRID_CELL = 14;
    private static final int GRID_SIZE = 4;
    private static final int MINIMUM_INITIAL_FEATURES = 18;
    private static final int MINIMUM_TRACKED_FEATURES = 12;
    private static final int MINIMUM_COVERAGE_CELLS = 4;
    private static final double MINIMUM_COVERAGE_SPAN = 0.24;
    private static final double MINIMUM_INLIER_RATIO = 0.50;
    private static final double MAXIMUM_LK_ERROR = 55.0;
    private static final double MAXIMUM_FORWARD_BACKWARD_ERROR_PX = 2.75;
    private static final double MINIMUM_BOARD_AREA_RATIO = 0.004;
    private static final double MAXIMUM_BOARD_AREA_RATIO = 0.90;
    private static final double MAXIMUM_CENTER_MOTION_RATIO = 0.24;
    private static final double MAXIMUM_CORNER_MOTION_RATIO = 0.32;
    private static final double MINIMUM_FRAME_AREA_SCALE = 0.50;
    private static final double MAXIMUM_FRAME_AREA_SCALE = 1.90;
    private static final double MAXIMUM_FRAME_ROTATION_DEGREES = 35.0;
    private static final double MAXIMUM_REPROJECTION_ERROR_PX = 10.0;
    private static final int LK_PYRAMID_LEVEL = 4;
    private static final int MAX_POSE_HOLD_FRAMES = 60;
    private static final long MAX_POSE_HOLD_NANOS = 1_500_000_000L;
    private static final double SMOOTHING_TIME_CONSTANT_NANOS = 38_000_000.0;
    private static final Size LK_WINDOW = new Size(21.0, 21.0);
    private static final TermCriteria LK_CRITERIA = new TermCriteria(
            TermCriteria.COUNT | TermCriteria.EPS, 30, 0.01);

    // Initial dimensions use metres. A detector seed replaces both extents using its own pose,
    // preserving the reference plane's aspect ratio and unit (normally millimetres).
    private final double nominalWidthMeters;
    private final double nominalHeightMeters;

    private Mat previousGray;
    private MatOfPoint2f previousPoints;
    private List<VisionPoint> previousCorners = Collections.emptyList();
    private String referenceId;
    private String componentId;
    private long lastDetectionTimestampNanos = Long.MIN_VALUE;
    private long previousTimestampNanos = Long.MIN_VALUE;
    private int frameRows;
    private int frameColumns;
    private double effectiveWidth;
    private double effectiveHeight;
    private boolean mostRecentResultTracked;
    private boolean mostRecentResultHeld;
    private RecognitionResult.SpatialAnchor guidanceAnchor;
    private long guidanceTimestampNanos = Long.MIN_VALUE;
    private long lastOutputTimestampNanos = Long.MIN_VALUE;
    private int heldFrames;
    private boolean closed;
    // Temporary, opt-in diagnostics. The frame timestamp joins these details to analyzer logs.
    private int diagnosticInitialFeatures;
    private int diagnosticGoodFlowPoints;
    private int diagnosticInliers;
    private String diagnosticHomography;
    private String diagnosticReason;

    /** Uses the measured main-board plane, 100 x 120 mm. */
    public AssemblyAnchorTracker() {
        this(MainBoardDimensions.WIDTH_MM / 1000.0, MainBoardDimensions.HEIGHT_MM / 1000.0);
    }

    /**
     * @param widthMeters board-plane width in metres
     * @param heightMeters board-plane height in metres
     */
    public AssemblyAnchorTracker(double widthMeters, double heightMeters) {
        if (!(widthMeters > 0.0) || !(heightMeters > 0.0)
                || !Double.isFinite(widthMeters) || !Double.isFinite(heightMeters)) {
            throw new IllegalArgumentException("Board dimensions must be finite and positive");
        }
        nominalWidthMeters = widthMeters;
        nominalHeightMeters = heightMeters;
        effectiveWidth = widthMeters;
        effectiveHeight = heightMeters;
    }

    /**
     * Updates the tracker with one CV_8UC1 analyzed frame.
     *
     * @param detected a fresh, trustworthy detector anchor, or {@code null} on detector dropout
     * @return the fresh detector anchor, a newly flow-tracked anchor, or {@code null} when tracking
     *         is unavailable or unsafe
     */
    public synchronized RecognitionResult.SpatialAnchor update(
            Mat gray,
            long timestampNanos,
            RecognitionResult.SpatialAnchor detected,
            CameraCalibration calibration) {
        ensureOpen();
        validateFrame(gray);
        mostRecentResultTracked = false;
        mostRecentResultHeld = false;
        diagnosticInitialFeatures = 0;
        diagnosticGoodFlowPoints = 0;
        diagnosticInliers = 0;
        diagnosticHomography = "not_attempted";
        diagnosticReason = "not_available";

        try {
            if (detected != null) {
                return acceptDetection(gray, timestampNanos, detected, calibration);
            }
            if (!hasTrackingState()
                    || calibration == null
                    || timestampNanos <= previousTimestampNanos
                    || timestampNanos < lastDetectionTimestampNanos
                    || timestampNanos - lastDetectionTimestampNanos > MAX_TRACKING_AGE_NANOS
                    || gray.rows() != frameRows
                    || gray.cols() != frameColumns) {
                diagnosticReason = !hasTrackingState() ? "no_tracking_state"
                        : calibration == null ? "no_calibration"
                        : gray.rows() != frameRows || gray.cols() != frameColumns
                        ? "frame_size_changed"
                        : timestampNanos - lastDetectionTimestampNanos > MAX_TRACKING_AGE_NANOS
                        ? "tracking_expired" : "invalid_timestamp";
                if (gray.rows() != frameRows && frameRows != 0
                        || gray.cols() != frameColumns && frameColumns != 0
                        || timestampNanos <= lastOutputTimestampNanos) {
                    resetInternal();
                    return null;
                }
                return holdOrReset(timestampNanos);
            }
            try {
                RecognitionResult.SpatialAnchor tracked = track(gray, timestampNanos, calibration);
                if (tracked == null) {
                    return holdOrReset(timestampNanos);
                }
                mostRecentResultTracked = true;
                diagnosticReason = "flow_accepted";
                return smooth(tracked, timestampNanos);
            } catch (RuntimeException unsafeFlow) {
                // OpenCV rejects degenerate pyramids/homographies with RuntimeException. Treat
                // every such case as lost tracking; callers must reacquire with the detector.
                diagnosticReason = "flow_exception_" + unsafeFlow.getClass().getSimpleName();
                return holdOrReset(timestampNanos);
            }
        } finally {
            if (Log.isLoggable(DIAGNOSTIC_TAG, Log.DEBUG)) {
                Log.d(DIAGNOSTIC_TAG, "frame=" + timestampNanos
                        + " trackerReason=" + diagnosticReason
                        + " freshDetected=" + (detected != null)
                        + " tracked=" + mostRecentResultTracked
                        + " initialFeatures=" + diagnosticInitialFeatures
                        + " goodFlowPoints=" + diagnosticGoodFlowPoints
                        + " flowHomography=" + diagnosticHomography
                        + " flowInliers=" + diagnosticInliers
                        + " maxTrackingAgeMs=" + MAX_TRACKING_AGE_MILLIS);
            }
        }
    }

    /** True only when the most recent non-null update result was produced by optical flow. */
    public synchronized boolean isTracked() {
        return mostRecentResultTracked;
    }

    /** A held anchor is visual continuity only, never fresh recognition evidence. */
    public synchronized boolean isHeld() { return mostRecentResultHeld; }

    /** Drops all retained images, features, identity, dimensions, and timestamps. */
    public synchronized void reset() {
        resetInternal();
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            resetInternal();
            closed = true;
        }
    }

    private RecognitionResult.SpatialAnchor acceptDetection(
            Mat gray,
            long timestampNanos,
            RecognitionResult.SpatialAnchor detected,
            CameraCalibration calibration) {
        RecognitionResult.SpatialAnchor previousGuidance = guidanceAnchor;
        long previousGuidanceTimestamp = guidanceTimestampNanos;
        long previousOutputTimestamp = lastOutputTimestampNanos;
        resetInternal();
        if (previousGuidance != null && timestampNanos > previousOutputTimestamp
                && detected.getReferenceId().equals(previousGuidance.getReferenceId())
                && detected.getComponentId().equals(previousGuidance.getComponentId())
                && timestampNanos - previousGuidanceTimestamp <= MAX_POSE_HOLD_NANOS) {
            guidanceAnchor = previousGuidance;
            guidanceTimestampNanos = previousGuidanceTimestamp;
            lastOutputTimestampNanos = previousOutputTimestamp;
        }
        List<VisionPoint> corners = detected.getProjectedCorners();
        if (!validCorners(corners, gray.cols(), gray.rows())) {
            // Flow propagation requires a fully visible, well-sized perimeter. The recognizer
            // has already validated this fresh observation using its own detection geometry;
            // inability to seed optical flow must never erase a valid detector result.
            diagnosticReason = "fresh_detection_unseedable_corners";
            guidanceAnchor = detected;
            guidanceTimestampNanos = timestampNanos;
            lastOutputTimestampNanos = timestampNanos;
            return detected;
        }
        RecognitionResult.SpatialAnchor display = smooth(detected, timestampNanos);
        if (calibration == null) {
            // The supplied detector pose is still authoritative, but without matching frame
            // intrinsics we cannot safely recompute it on a later optical-flow frame.
            diagnosticReason = "fresh_detection_no_calibration";
            return display;
        }

        double[] detectedDimensions = detectedPlaneDimensions(corners, detected.getPose(), calibration);
        if (detectedDimensions == null) {
            diagnosticReason = "fresh_detection_unseedable_plane";
            return display;
        }

        MatOfPoint2f features = detectBoardFeatures(gray, corners);
        diagnosticInitialFeatures = features == null ? 0 : features.rows();
        if (features == null || features.rows() < MINIMUM_INITIAL_FEATURES) {
            if (features != null) {
                features.release();
            }
            diagnosticReason = "fresh_detection_insufficient_distributed_features";
            return display;
        }

        previousGray = gray.clone();
        previousPoints = features;
        previousCorners = immutableCorners(corners);
        referenceId = detected.getReferenceId();
        componentId = detected.getComponentId();
        lastDetectionTimestampNanos = timestampNanos;
        previousTimestampNanos = timestampNanos;
        frameRows = gray.rows();
        frameColumns = gray.cols();
        effectiveWidth = detectedDimensions[0];
        effectiveHeight = detectedDimensions[1];
        diagnosticReason = "fresh_detection_seeded";
        return display;
    }

    private RecognitionResult.SpatialAnchor track(
            Mat gray, long timestampNanos, CameraCalibration calibration) {
        MatOfPoint2f forwardPoints = new MatOfPoint2f();
        MatOfPoint2f backwardPoints = new MatOfPoint2f();
        MatOfByte forwardStatus = new MatOfByte();
        MatOfByte backwardStatus = new MatOfByte();
        MatOfFloat forwardError = new MatOfFloat();
        MatOfFloat backwardError = new MatOfFloat();
        MatOfPoint2f source = new MatOfPoint2f();
        MatOfPoint2f destination = new MatOfPoint2f();
        Mat inlierMask = new Mat();
        Mat homography = null;
        try {
            Video.calcOpticalFlowPyrLK(
                    previousGray, gray, previousPoints, forwardPoints,
                    forwardStatus, forwardError, LK_WINDOW, LK_PYRAMID_LEVEL, LK_CRITERIA, 0, 1e-4);
            if (forwardPoints.empty()) {
                diagnosticReason = "empty_forward_flow";
                return null;
            }
            Video.calcOpticalFlowPyrLK(
                    gray, previousGray, forwardPoints, backwardPoints,
                    backwardStatus, backwardError, LK_WINDOW, LK_PYRAMID_LEVEL, LK_CRITERIA, 0, 1e-4);

            Point[] previous = previousPoints.toArray();
            Point[] forward = forwardPoints.toArray();
            Point[] backward = backwardPoints.toArray();
            byte[] forwardOk = forwardStatus.toArray();
            byte[] backwardOk = backwardStatus.toArray();
            float[] errors = forwardError.toArray();
            int count = Math.min(previous.length,
                    Math.min(forward.length,
                            Math.min(backward.length,
                                    Math.min(forwardOk.length,
                                            Math.min(backwardOk.length, errors.length)))));
            List<Point> sourcePoints = new ArrayList<>();
            List<Point> destinationPoints = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                if (forwardOk[index] == 0 || backwardOk[index] == 0
                        || !finite(previous[index]) || !finite(forward[index])
                        || !finite(backward[index]) || !Float.isFinite(errors[index])
                        || errors[index] > MAXIMUM_LK_ERROR
                        || distance(previous[index], backward[index])
                        > MAXIMUM_FORWARD_BACKWARD_ERROR_PX
                        || !insideFrame(forward[index], gray.cols(), gray.rows())) {
                    continue;
                }
                sourcePoints.add(previous[index]);
                destinationPoints.add(forward[index]);
            }
            diagnosticGoodFlowPoints = sourcePoints.size();
            if (sourcePoints.size() < MINIMUM_TRACKED_FEATURES) {
                diagnosticReason = "insufficient_flow_points";
                return null;
            }

            source.fromList(sourcePoints);
            destination.fromList(destinationPoints);
            double diagonal = Math.hypot(gray.cols(), gray.rows());
            double ransacThreshold = Math.max(3.0, Math.min(6.0, diagonal * 0.004));
            homography = Calib3d.findHomography(
                    source, destination, Calib3d.RANSAC, ransacThreshold,
                    inlierMask, 2_000, 0.995);
            if (homography.empty() || inlierMask.empty()) {
                diagnosticHomography = "failed";
                diagnosticReason = "flow_homography_failed";
                return null;
            }
            diagnosticHomography = "success";

            byte[] inlierValues = new byte[(int) inlierMask.total()];
            inlierMask.get(0, 0, inlierValues);
            List<Point> sourceInliers = new ArrayList<>();
            List<Point> destinationInliers = new ArrayList<>();
            for (int index = 0;
                 index < inlierValues.length && index < sourcePoints.size(); index++) {
                if (inlierValues[index] != 0) {
                    sourceInliers.add(sourcePoints.get(index));
                    destinationInliers.add(destinationPoints.get(index));
                }
            }
            diagnosticInliers = sourceInliers.size();
            if (sourceInliers.size() < MINIMUM_TRACKED_FEATURES
                    || sourceInliers.size() / (double) sourcePoints.size()
                    < MINIMUM_INLIER_RATIO
                    || !hasDistributedCoverage(sourceInliers, previousCorners)) {
                diagnosticReason = "insufficient_distributed_flow_inliers";
                return null;
            }

            List<VisionPoint> nextCorners = project(previousCorners, homography);
            if (!validCorners(nextCorners, gray.cols(), gray.rows())
                    || !plausibleFrameMotion(previousCorners, nextCorners,
                            gray.cols(), gray.rows())) {
                diagnosticReason = "unsafe_flow_corners_or_motion";
                return null;
            }
            RecognitionResult.Pose pose = estimatePose(
                    nextCorners, calibration, effectiveWidth, effectiveHeight,
                    gray.cols(), gray.rows());
            if (pose == null) {
                diagnosticReason = "flow_pose_failed";
                return null;
            }

            MatOfPoint2f retainedPoints = new MatOfPoint2f();
            retainedPoints.fromList(destinationInliers);
            replaceFrameState(gray, retainedPoints, nextCorners, timestampNanos);
            return new RecognitionResult.SpatialAnchor(
                    referenceId, componentId, nextCorners, pose);
        } finally {
            if (homography != null) homography.release();
            inlierMask.release();
            destination.release();
            source.release();
            backwardError.release();
            forwardError.release();
            backwardStatus.release();
            forwardStatus.release();
            backwardPoints.release();
            forwardPoints.release();
        }
    }

    private void replaceFrameState(
            Mat gray,
            MatOfPoint2f points,
            List<VisionPoint> corners,
            long timestampNanos) {
        Mat retainedGray = gray.clone();
        previousGray.release();
        previousPoints.release();
        previousGray = retainedGray;
        previousPoints = points;
        previousCorners = immutableCorners(corners);
        previousTimestampNanos = timestampNanos;
    }

    private static MatOfPoint2f detectBoardFeatures(
            Mat gray, List<VisionPoint> corners) {
        Mat mask = new Mat(gray.rows(), gray.cols(), CvType.CV_8UC1, new Scalar(0.0));
        MatOfPoint rawCorners = new MatOfPoint();
        Mat canonicalToImage = null;
        Mat imageToCanonical = null;
        MatOfPoint2f rawAsFloat = new MatOfPoint2f();
        MatOfPoint2f canonicalPoints = new MatOfPoint2f();
        try {
            canonicalToImage = canonicalTransform(corners);
            fillCanonicalPolygon(mask, canonicalToImage, new Scalar(255.0),
                    0.05, 0.05, 0.95, 0.95);
            // The two motors occupy the long side zones after Step 2. Their housings, brackets,
            // and movable leads must never seed planar board tracking.
            fillCanonicalPolygon(mask, canonicalToImage, new Scalar(0.0),
                    0.00, 0.28, 0.30, 0.90);
            fillCanonicalPolygon(mask, canonicalToImage, new Scalar(0.0),
                    0.70, 0.28, 1.00, 0.90);

            Imgproc.goodFeaturesToTrack(
                    gray, rawCorners, MAX_CANDIDATE_FEATURES,
                    0.01, 6.0, mask, 7, false, 0.04);
            Point[] detected = rawCorners.toArray();
            if (detected.length < MINIMUM_INITIAL_FEATURES) {
                return null;
            }
            rawAsFloat.fromArray(detected);
            imageToCanonical = canonicalToImage.inv();
            Core.perspectiveTransform(rawAsFloat, canonicalPoints, imageToCanonical);
            Point[] normalized = canonicalPoints.toArray();

            int[] perCell = new int[GRID_SIZE * GRID_SIZE];
            List<Point> selected = new ArrayList<>();
            int count = Math.min(detected.length, normalized.length);
            for (int index = 0; index < count; index++) {
                Point canonical = normalized[index];
                if (!finite(canonical)
                        || canonical.x < 0.04 || canonical.x > 0.96
                        || canonical.y < 0.04 || canonical.y > 0.96
                        || excludedMotorZone(canonical)) {
                    continue;
                }
                int column = Math.min(GRID_SIZE - 1,
                        Math.max(0, (int) (canonical.x * GRID_SIZE)));
                int row = Math.min(GRID_SIZE - 1,
                        Math.max(0, (int) (canonical.y * GRID_SIZE)));
                int cell = row * GRID_SIZE + column;
                if (perCell[cell] >= MAX_FEATURES_PER_GRID_CELL) {
                    continue;
                }
                perCell[cell]++;
                selected.add(detected[index]);
            }
            if (selected.size() < MINIMUM_INITIAL_FEATURES
                    || !hasCanonicalCoverage(normalizedPoints(selected, imageToCanonical))) {
                return null;
            }
            MatOfPoint2f result = new MatOfPoint2f();
            result.fromList(selected);
            return result;
        } finally {
            canonicalPoints.release();
            rawAsFloat.release();
            if (imageToCanonical != null) imageToCanonical.release();
            if (canonicalToImage != null) canonicalToImage.release();
            rawCorners.release();
            mask.release();
        }
    }

    private static List<Point> normalizedPoints(List<Point> imagePoints, Mat imageToCanonical) {
        MatOfPoint2f source = new MatOfPoint2f();
        MatOfPoint2f destination = new MatOfPoint2f();
        try {
            source.fromList(imagePoints);
            Core.perspectiveTransform(source, destination, imageToCanonical);
            return Arrays.asList(destination.toArray());
        } finally {
            destination.release();
            source.release();
        }
    }

    private static boolean hasDistributedCoverage(
            List<Point> imagePoints, List<VisionPoint> boardCorners) {
        Mat canonicalToImage = null;
        Mat transform = null;
        MatOfPoint2f source = new MatOfPoint2f();
        MatOfPoint2f destination = new MatOfPoint2f();
        try {
            canonicalToImage = canonicalTransform(boardCorners);
            transform = canonicalToImage.inv();
            source.fromList(imagePoints);
            Core.perspectiveTransform(source, destination, transform);
            return hasCanonicalCoverage(Arrays.asList(destination.toArray()));
        } finally {
            destination.release();
            source.release();
            if (transform != null) transform.release();
            if (canonicalToImage != null) canonicalToImage.release();
        }
    }

    private static boolean hasCanonicalCoverage(List<Point> points) {
        boolean[] cells = new boolean[GRID_SIZE * GRID_SIZE];
        int populated = 0;
        double minimumX = Double.POSITIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        for (Point point : points) {
            if (!finite(point) || point.x < 0.0 || point.x > 1.0
                    || point.y < 0.0 || point.y > 1.0) {
                continue;
            }
            int column = Math.min(GRID_SIZE - 1, (int) (point.x * GRID_SIZE));
            int row = Math.min(GRID_SIZE - 1, (int) (point.y * GRID_SIZE));
            int cell = row * GRID_SIZE + column;
            if (!cells[cell]) {
                cells[cell] = true;
                populated++;
            }
            minimumX = Math.min(minimumX, point.x);
            maximumX = Math.max(maximumX, point.x);
            minimumY = Math.min(minimumY, point.y);
            maximumY = Math.max(maximumY, point.y);
        }
        return populated >= MINIMUM_COVERAGE_CELLS
                && maximumX - minimumX >= MINIMUM_COVERAGE_SPAN
                && maximumY - minimumY >= MINIMUM_COVERAGE_SPAN;
    }

    private static boolean excludedMotorZone(Point point) {
        return point.y >= 0.28 && point.y <= 0.90
                && (point.x <= 0.30 || point.x >= 0.70);
    }

    private static void fillCanonicalPolygon(
            Mat mask, Mat canonicalToImage, Scalar color,
            double left, double top, double right, double bottom) {
        MatOfPoint2f canonical = new MatOfPoint2f(
                new Point(left, top), new Point(right, top),
                new Point(right, bottom), new Point(left, bottom));
        MatOfPoint2f projected = new MatOfPoint2f();
        MatOfPoint polygon = new MatOfPoint();
        try {
            Core.perspectiveTransform(canonical, projected, canonicalToImage);
            polygon.fromArray(projected.toArray());
            Imgproc.fillConvexPoly(mask, polygon, color);
        } finally {
            polygon.release();
            projected.release();
            canonical.release();
        }
    }

    private static Mat canonicalTransform(List<VisionPoint> corners) {
        MatOfPoint2f canonical = new MatOfPoint2f(
                new Point(0.0, 0.0), new Point(1.0, 0.0),
                new Point(1.0, 1.0), new Point(0.0, 1.0));
        MatOfPoint2f image = new MatOfPoint2f(
                toPoint(corners.get(0)), toPoint(corners.get(1)),
                toPoint(corners.get(2)), toPoint(corners.get(3)));
        try {
            return Imgproc.getPerspectiveTransform(canonical, image);
        } finally {
            image.release();
            canonical.release();
        }
    }

    private static List<VisionPoint> project(List<VisionPoint> points, Mat homography) {
        MatOfPoint2f source = new MatOfPoint2f();
        MatOfPoint2f destination = new MatOfPoint2f();
        try {
            Point[] values = new Point[points.size()];
            for (int index = 0; index < points.size(); index++) {
                values[index] = toPoint(points.get(index));
            }
            source.fromArray(values);
            Core.perspectiveTransform(source, destination, homography);
            List<VisionPoint> result = new ArrayList<>();
            for (Point point : destination.toArray()) {
                result.add(new VisionPoint(point.x, point.y));
            }
            return result;
        } finally {
            destination.release();
            source.release();
        }
    }

    private static boolean validCorners(List<VisionPoint> corners, int width, int height) {
        if (corners == null || corners.size() != 4 || width <= 1 || height <= 1) {
            return false;
        }
        double sign = 0.0;
        for (int index = 0; index < corners.size(); index++) {
            VisionPoint current = corners.get(index);
            VisionPoint next = corners.get((index + 1) % corners.size());
            VisionPoint after = corners.get((index + 2) % corners.size());
            if (current == null || !Double.isFinite(current.getX())
                    || !Double.isFinite(current.getY())
                    || current.getX() < 1.0 || current.getX() > width - 2.0
                    || current.getY() < 1.0 || current.getY() > height - 2.0
                    || distance(current, next) < Math.min(width, height) * 0.035) {
                return false;
            }
            double cross = (next.getX() - current.getX())
                    * (after.getY() - next.getY())
                    - (next.getY() - current.getY())
                    * (after.getX() - next.getX());
            if (Math.abs(cross) < 1e-5) {
                return false;
            }
            if (sign == 0.0) {
                sign = Math.signum(cross);
            } else if (Math.signum(cross) != sign) {
                return false;
            }
        }
        double areaRatio = polygonArea(corners) / (width * (double) height);
        return areaRatio >= MINIMUM_BOARD_AREA_RATIO
                && areaRatio <= MAXIMUM_BOARD_AREA_RATIO;
    }

    private static boolean plausibleFrameMotion(
            List<VisionPoint> previous,
            List<VisionPoint> next,
            int width,
            int height) {
        double diagonal = Math.hypot(width, height);
        if (distance(center(previous), center(next))
                > diagonal * MAXIMUM_CENTER_MOTION_RATIO) {
            return false;
        }
        for (int index = 0; index < 4; index++) {
            if (distance(previous.get(index), next.get(index))
                    > diagonal * MAXIMUM_CORNER_MOTION_RATIO) {
                return false;
            }
        }
        double areaScale = polygonArea(next) / polygonArea(previous);
        if (areaScale < MINIMUM_FRAME_AREA_SCALE || areaScale > MAXIMUM_FRAME_AREA_SCALE) {
            return false;
        }
        return circularDifference(edgeAngle(previous), edgeAngle(next))
                <= MAXIMUM_FRAME_ROTATION_DEGREES;
    }

    private static RecognitionResult.Pose estimatePose(
            List<VisionPoint> corners,
            CameraCalibration calibration,
            double width,
            double height,
            int frameWidth,
            int frameHeight) {
        double halfWidth = width * 0.5;
        double halfHeight = height * 0.5;
        MatOfPoint3f objectPoints = new MatOfPoint3f(
                new Point3(-halfWidth, halfHeight, 0.0),
                new Point3(halfWidth, halfHeight, 0.0),
                new Point3(halfWidth, -halfHeight, 0.0),
                new Point3(-halfWidth, -halfHeight, 0.0));
        MatOfPoint2f imagePoints = new MatOfPoint2f(
                toPoint(calibration.deskew(corners.get(0))),
                toPoint(calibration.deskew(corners.get(1))),
                toPoint(calibration.deskew(corners.get(2))),
                toPoint(calibration.deskew(corners.get(3))));
        Mat cameraMatrix = calibration.toOpenCvCameraMatrix();
        MatOfDouble distortion = calibration.toDistortionMatrix();
        Mat rotationVector = new Mat();
        Mat translationVector = new Mat();
        Mat rotationMatrix = new Mat();
        MatOfPoint2f reprojected = new MatOfPoint2f();
        try {
            boolean solved = Calib3d.solvePnP(
                    objectPoints, imagePoints, cameraMatrix, distortion,
                    rotationVector, translationVector, false, Calib3d.SOLVEPNP_ITERATIVE);
            if (!solved || rotationVector.empty() || translationVector.empty()) {
                return null;
            }
            Calib3d.Rodrigues(rotationVector, rotationMatrix);
            double[] rotation = new double[9];
            double[] rvec = new double[3];
            double[] tvec = new double[3];
            rotationMatrix.get(0, 0, rotation);
            rotationVector.get(0, 0, rvec);
            translationVector.get(0, 0, tvec);
            if (!finite(rvec) || !finite(tvec) || !finite(rotation)
                    || tvec[2] <= 0.0 || !hasPositiveDepth(
                    rotation, tvec, halfWidth, halfHeight)) {
                return null;
            }
            Calib3d.projectPoints(
                    objectPoints, rotationVector, translationVector,
                    cameraMatrix, distortion, reprojected);
            double allowedError = Math.max(MAXIMUM_REPROJECTION_ERROR_PX,
                    Math.hypot(frameWidth, frameHeight) * 0.006);
            double error = reprojectionError(reprojected.toArray(), imagePoints.toArray());
            if (error > allowedError) {
                return null;
            }
            return new RecognitionResult.Pose(
                    rvec, tvec, toOpenGlModelView(rotation, tvec), error);
        } finally {
            reprojected.release();
            rotationMatrix.release();
            translationVector.release();
            rotationVector.release();
            distortion.release();
            cameraMatrix.release();
            imagePoints.release();
            objectPoints.release();
        }
    }

    /**
     * Intersects calibrated corner rays with the detector's plane. Rescaling a fixed nominal
     * rectangle by translation length alone changes the aspect ratio of white-board and custom
     * references, producing an artificial pose jump as soon as ORB drops out.
     */
    private static double[] detectedPlaneDimensions(List<VisionPoint> corners,
            RecognitionResult.Pose pose, CameraCalibration calibration) {
        MatOfPoint2f imagePoints = new MatOfPoint2f();
        MatOfPoint2f rays = new MatOfPoint2f();
        Mat cameraMatrix = calibration.toOpenCvCameraMatrix();
        MatOfDouble distortion = calibration.toDistortionMatrix();
        Mat rotationVector = new Mat(3, 1, CvType.CV_64FC1);
        Mat rotationMatrix = new Mat();
        try {
            Point[] image = new Point[4];
            for (int index = 0; index < image.length; index++) {
                image[index] = toPoint(calibration.deskew(corners.get(index)));
            }
            imagePoints.fromArray(image);
            Calib3d.undistortPoints(imagePoints, rays, cameraMatrix, distortion);
            rotationVector.put(0, 0, pose.getRotationVector());
            Calib3d.Rodrigues(rotationVector, rotationMatrix);
            double[] rotation = new double[9];
            rotationMatrix.get(0, 0, rotation);
            double[] translation = pose.getTranslationVector();
            // The third column of R is the local plane normal in camera coordinates.
            double planeOffset = rotation[2] * translation[0]
                    + rotation[5] * translation[1] + rotation[8] * translation[2];
            Point[] normalized = rays.toArray();
            if (normalized.length != 4 || !Double.isFinite(planeOffset)) return null;
            double[][] cameraCorners = new double[4][3];
            for (int index = 0; index < normalized.length; index++) {
                Point ray = normalized[index];
                double denominator = rotation[2] * ray.x + rotation[5] * ray.y + rotation[8];
                if (!finite(ray) || Math.abs(denominator) < 1e-8) return null;
                double depth = planeOffset / denominator;
                if (!(depth > 0.0) || !Double.isFinite(depth)) return null;
                cameraCorners[index] = new double[]{ray.x * depth, ray.y * depth, depth};
            }
            double width = (distance3d(cameraCorners[0], cameraCorners[1])
                    + distance3d(cameraCorners[3], cameraCorners[2])) * 0.5;
            double height = (distance3d(cameraCorners[0], cameraCorners[3])
                    + distance3d(cameraCorners[1], cameraCorners[2])) * 0.5;
            return width > 0.0 && height > 0.0
                    && Double.isFinite(width) && Double.isFinite(height)
                    && width <= Float.MAX_VALUE && height <= Float.MAX_VALUE
                    ? new double[]{width, height} : null;
        } catch (RuntimeException invalidPlane) {
            // A fresh detector result remains authoritative even if flow cannot seed its plane.
            return null;
        } finally {
            rotationMatrix.release(); rotationVector.release(); distortion.release();
            cameraMatrix.release(); rays.release(); imagePoints.release();
        }
    }

    private static double distance3d(double[] first, double[] second) {
        return Math.hypot(Math.hypot(first[0] - second[0], first[1] - second[1]),
                first[2] - second[2]);
    }

    private static float[] toOpenGlModelView(double[] rotation, double[] translation) {
        return new float[] {
                (float) rotation[0], (float) -rotation[3], (float) -rotation[6], 0.0f,
                (float) rotation[1], (float) -rotation[4], (float) -rotation[7], 0.0f,
                (float) rotation[2], (float) -rotation[5], (float) -rotation[8], 0.0f,
                (float) translation[0], (float) -translation[1],
                (float) -translation[2], 1.0f
        };
    }

    private static boolean hasPositiveDepth(
            double[] rotation, double[] translation,
            double halfWidth, double halfHeight) {
        double[][] corners = {
                {-halfWidth, -halfHeight}, {halfWidth, -halfHeight},
                {halfWidth, halfHeight}, {-halfWidth, halfHeight}
        };
        for (double[] corner : corners) {
            double depth = rotation[6] * corner[0]
                    + rotation[7] * corner[1] + translation[2];
            if (!(depth > 0.0) || !Double.isFinite(depth)) {
                return false;
            }
        }
        return true;
    }

    private static double reprojectionError(Point[] actual, Point[] expected) {
        if (actual.length != expected.length || actual.length == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double sum = 0.0;
        for (int index = 0; index < actual.length; index++) {
            double error = distance(actual[index], expected[index]);
            sum += error * error;
        }
        return Math.sqrt(sum / actual.length);
    }

    private static void validateFrame(Mat gray) {
        if (gray == null || gray.empty()
                || gray.type() != CvType.CV_8UC1
                || gray.rows() < 32 || gray.cols() < 32) {
            throw new IllegalArgumentException(
                    "Assembly anchor tracking requires a non-empty CV_8UC1 frame");
        }
    }

    private boolean hasTrackingState() {
        return previousGray != null && !previousGray.empty()
                && previousPoints != null
                && previousPoints.rows() >= MINIMUM_TRACKED_FEATURES
                && previousCorners.size() == 4
                && referenceId != null && componentId != null;
    }

    private RecognitionResult.SpatialAnchor holdOrReset(long timestampNanos) {
        clearFlowState();
        if (guidanceAnchor == null || timestampNanos <= lastOutputTimestampNanos
                || timestampNanos - guidanceTimestampNanos > MAX_POSE_HOLD_NANOS
                || heldFrames >= MAX_POSE_HOLD_FRAMES) {
            resetInternal();
            return null;
        }
        heldFrames++;
        lastOutputTimestampNanos = timestampNanos;
        mostRecentResultHeld = true;
        diagnosticReason = "pose_hold_" + heldFrames;
        return guidanceAnchor;
    }

    /** Smooth display geometry using capture timestamps, without feeding it back into LK. */
    private RecognitionResult.SpatialAnchor smooth(RecognitionResult.SpatialAnchor raw,
                                                   long timestampNanos) {
        RecognitionResult.SpatialAnchor prior = guidanceAnchor;
        RecognitionResult.SpatialAnchor output = raw;
        if (prior != null && timestampNanos > guidanceTimestampNanos
                && raw.getReferenceId().equals(prior.getReferenceId())
                && raw.getComponentId().equals(prior.getComponentId())) {
            double alpha = 1.0 - Math.exp(-(timestampNanos - guidanceTimestampNanos)
                    / SMOOTHING_TIME_CONSTANT_NANOS);
            alpha = Math.max(0.65, Math.min(1.0, alpha));
            RecognitionResult.Pose oldPose = prior.getPose();
            RecognitionResult.Pose newPose = raw.getPose();
            double[] oldRotation = oldPose.getRotationVector();
            double[] newRotation = newPose.getRotationVector();
            double[] oldTranslation = oldPose.getTranslationVector();
            double[] newTranslation = newPose.getTranslationVector();
            List<VisionPoint> corners = new ArrayList<>(4);
            for (int index = 0; index < 4; index++) {
                VisionPoint before = prior.getProjectedCorners().get(index);
                VisionPoint after = raw.getProjectedCorners().get(index);
                corners.add(new VisionPoint(before.getX() + alpha * (after.getX() - before.getX()),
                        before.getY() + alpha * (after.getY() - before.getY())));
            }
            double[] rvec = PoseSmoothing.rotation(oldRotation,newRotation,alpha);
            double[] tvec = new double[3];
            for (int index = 0; index < 3; index++) {
                tvec[index] = oldTranslation[index]
                        + alpha * (newTranslation[index] - oldTranslation[index]);
            }
            Mat rotationVector = new Mat(3, 1, CvType.CV_64FC1);
            Mat rotationMatrix = new Mat();
            try {
                rotationVector.put(0, 0, rvec);
                Calib3d.Rodrigues(rotationVector, rotationMatrix);
                double[] matrix = new double[9];
                rotationMatrix.get(0, 0, matrix);
                output = new RecognitionResult.SpatialAnchor(raw.getReferenceId(),
                        raw.getComponentId(), corners,
                        new RecognitionResult.Pose(rvec, tvec,
                                toOpenGlModelView(matrix, tvec),
                                newPose.getReprojectionErrorPx()));
            } finally {
                rotationMatrix.release();
                rotationVector.release();
            }
        }
        guidanceAnchor = output;
        guidanceTimestampNanos = timestampNanos;
        lastOutputTimestampNanos = timestampNanos;
        heldFrames = 0;
        return output;
    }

    private void clearFlowState() {
        if (previousGray != null) {
            previousGray.release();
            previousGray = null;
        }
        if (previousPoints != null) {
            previousPoints.release();
            previousPoints = null;
        }
        previousCorners = Collections.emptyList();
        referenceId = null;
        componentId = null;
        lastDetectionTimestampNanos = Long.MIN_VALUE;
        previousTimestampNanos = Long.MIN_VALUE;
        frameRows = 0;
        frameColumns = 0;
        effectiveWidth = nominalWidthMeters;
        effectiveHeight = nominalHeightMeters;
        mostRecentResultTracked = false;
    }

    private void resetInternal() {
        clearFlowState();
        guidanceAnchor = null;
        guidanceTimestampNanos = Long.MIN_VALUE;
        lastOutputTimestampNanos = Long.MIN_VALUE;
        heldFrames = 0;
        mostRecentResultHeld = false;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("AssemblyAnchorTracker is closed");
        }
    }

    private static List<VisionPoint> immutableCorners(List<VisionPoint> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    private static Point toPoint(VisionPoint point) {
        return new Point(point.getX(), point.getY());
    }

    private static boolean finite(Point point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y);
    }

    private static boolean finite(double[] values) {
        for (double value : values) {
            if (!Double.isFinite(value)) return false;
        }
        return true;
    }

    private static boolean insideFrame(Point point, int width, int height) {
        return point.x >= 1.0 && point.x <= width - 2.0
                && point.y >= 1.0 && point.y <= height - 2.0;
    }

    private static VisionPoint center(List<VisionPoint> corners) {
        double x = 0.0;
        double y = 0.0;
        for (VisionPoint corner : corners) {
            x += corner.getX();
            y += corner.getY();
        }
        return new VisionPoint(x / corners.size(), y / corners.size());
    }

    private static double polygonArea(List<VisionPoint> corners) {
        double doubledArea = 0.0;
        for (int index = 0; index < corners.size(); index++) {
            VisionPoint current = corners.get(index);
            VisionPoint next = corners.get((index + 1) % corners.size());
            doubledArea += current.getX() * next.getY()
                    - next.getX() * current.getY();
        }
        return Math.abs(doubledArea) * 0.5;
    }

    private static double edgeAngle(List<VisionPoint> corners) {
        VisionPoint first = corners.get(0);
        VisionPoint second = corners.get(1);
        return Math.toDegrees(Math.atan2(
                second.getY() - first.getY(), second.getX() - first.getX()));
    }

    private static double circularDifference(double first, double second) {
        double difference = Math.abs(first - second) % 360.0;
        return Math.min(difference, 360.0 - difference);
    }

    private static double distance(Point first, Point second) {
        return Math.hypot(first.x - second.x, first.y - second.y);
    }

    private static double distance(VisionPoint first, VisionPoint second) {
        return Math.hypot(first.getX() - second.getX(),
                first.getY() - second.getY());
    }

}
