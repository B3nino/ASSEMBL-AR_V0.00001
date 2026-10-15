package dev.chyken.assembl_ar.vision;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import dev.chyken.assembl_ar.data.NeobotSeedData;

/**
 * Conservative, expectation-scoped localization for loose parts that are poor planar
 * ORB targets. The printed T bracket is identified by its silhouette and three staggered slots,
 * across dark and reflective appearances;
 * the gear motor requires both a yellow gearbox and an adjacent neutral, textured metal can; the
 * motor driver requires its blue PCB together with the paired green terminal-block region;
 * the ROB-231 caster requires a reflective ball and two opposite bores in a pale mounting base.
 *
 * <p>This is intentionally a fallback, not an unconstrained classifier. A black slotted object or
 * a yellow object is not sufficient evidence on its own. Callers must first run normal catalog
 * recognition and retain usable part observations and ambiguous identity evidence. An independently
 * verified workplane may remain visible while the missing part is localized. The returned
 * pose is a coarse pose of the detected full-body rectangle; it is suitable for the four-frame
 * loose-part/guided-card stability gate, not precision alignment of a non-planar motor.</p>
 */
public final class LoosePartShapeRecognizer {
    private static final int MAX_WORKING_DIMENSION = 1280;
    private static final double DEFAULT_MINIMUM_AREA_RATIO = 0.002;
    private static final double MAXIMUM_AREA_RATIO = 0.70;
    private static final long MAX_SMOOTHING_GAP_NANOS = 300_000_000L;
    private static final double SMOOTHING_ALPHA = 0.35;
    private static final double MAX_SMOOTHING_MOTION_RATIO = 0.20;
    private static final double MOTOR_LENGTH_MM = 70.0;
    private static final double MOTOR_WIDTH_MM = 25.0;
    private static final double DRIVER_WIDTH_MM = 45.0;
    private static final double DRIVER_HEIGHT_MM = 35.0;
    private static final String MOTOR_REFERENCE_ID = "shape:loose-gear-motor-v1";
    private static final String DRIVER_REFERENCE_ID = "shape:loose-motor-driver-v1";

    private String previousComponentId;
    private long previousTimestampNanos;
    private List<VisionPoint> previousCorners;
    private int previousFrameWidth;
    private int previousFrameHeight;

    public synchronized void reset() {
        previousComponentId = null;
        previousCorners = null;
        previousTimestampNanos = 0L;
        previousFrameWidth = previousFrameHeight = 0;
    }

    /** True only for requests whose missing pose can safely use this bounded fallback. */
    public static boolean supports(RecognitionRequest request) {
        if (request == null || request.getExpectation() == null
                || request.getExpectedInstanceCount() != 1 && !request.inspectsLooseBracketSet()) {
            return false;
        }
        RecognitionRequest.AssemblyExpectation expectation = request.getExpectation();
        if (expectation.getOrientationDegrees() != null || expectation.getPolarityRule() != null) {
            return false;
        }
        String id = expectation.getComponentId();
        return NeobotSeedData.MOTOR_BRACKET.equals(id)
                || NeobotSeedData.GEAR_MOTOR.equals(id)
                || NeobotSeedData.MOTOR_DRIVER.equals(id)
                || NeobotSeedData.CASTER_WHEEL.equals(id)
                || request.getSpatialAnchorComponentId() == null
                && (NeobotSeedData.MICROCONTROLLER.equals(id)
                || NeobotSeedData.DISTANCE_SENSOR.equals(id)
                || NeobotSeedData.LINE_TRACKER.equals(id));
    }

    /**
     * A valid part observation always wins over color/shape. The separately verified assembly
     * workplane is context, so seeing that board must not suppress a missing mounted part.
     * Descriptor-only identity has no corners or pose and may be replaced for the expected part.
     */
    public static boolean shouldAttempt(RecognitionRequest request, RecognitionResult primary) {
        if (request != null && request.inspectsLooseBracketSet()) {
            // A partial ORB set may be supplemented by full silhouette evidence. A conflicting
            // identity or ambiguous textured candidate always retains the original rejection.
            return (primary == null || primary.getRejectionEvidence() == null
                    || primary.getRejectionEvidence().getReason()
                    != RecognitionResult.RejectionReason.IDENTITY_AMBIGUOUS)
                    && (primary == null || primary.getComponentId() == null
                    || NeobotSeedData.MOTOR_BRACKET.equals(primary.getComponentId()))
                    && (primary == null || primary.getDetectedInstanceCount(
                    NeobotSeedData.MOTOR_BRACKET) < request.getExpectedInstanceCount());
        }
        return supports(request)
                && (!hasUsableGeometry(primary) || isRequestedAssemblyAnchor(request, primary))
                && (primary == null || primary.getRejectionEvidence() == null
                || primary.getRejectionEvidence().getReason()
                != RecognitionResult.RejectionReason.IDENTITY_AMBIGUOUS);
    }

    static boolean shouldAttempt(RecognitionRequest request, RecognitionResult primary,
                                 Predicate<List<VisionPoint>> bracketPlacement) {
        if (bracketPlacement == null) return shouldAttempt(request, primary);
        if (!supports(request) || !NeobotSeedData.MOTOR_BRACKET.equals(
                request.getExpectation().getComponentId())) return false;
        if (primary != null && primary.getRejectionEvidence() != null
                && primary.getRejectionEvidence().getReason()
                == RecognitionResult.RejectionReason.IDENTITY_AMBIGUOUS) return false;
        // A matching bracket elsewhere is not the current slot. Preserve conflicting identities.
        return !hasUsableGeometry(primary) || isRequestedAssemblyAnchor(request, primary)
                || NeobotSeedData.MOTOR_BRACKET.equals(primary.getComponentId())
                && !bracketPlacement.test(primary.getProjectedCorners());
    }

    private static boolean isRequestedAssemblyAnchor(RecognitionRequest request,
                                                       RecognitionResult primary) {
        if (request.getAssemblyStepNumber() == 0 || primary == null
                || request.getSpatialAnchorComponentId() == null
                || request.getSpatialAnchorComponentId().equals(
                        request.getExpectation().getComponentId())) return false;
        RecognitionResult.SpatialAnchor anchor = primary.getSpatialAnchor();
        return request.getSpatialAnchorComponentId().equals(primary.getComponentId())
                && anchor != null
                && request.getSpatialAnchorComponentId().equals(anchor.getComponentId())
                && anchor.getProjectedCorners().size() == 4 && anchor.getPose() != null;
    }

    static boolean hasUsableGeometry(RecognitionResult result) {
        return result != null
                && result.getDetectionMethod() != RecognitionResult.DetectionMethod.NONE
                && result.getComponentId() != null
                && result.getProjectedCorners().size() == 4
                && result.getPose() != null;
    }

    /**
     * Attempts the expected loose part and returns {@code primary} when shape evidence is absent.
     * {@code grayscale} uses analyzed-frame coordinates. RGB is required for the gear motor and
     * driver and caster, and enables colored-electronics rejection for the bracket; it must use the same
     * coordinates and {@link CvType#CV_8UC3 RGB} channel order.
     */
    public RecognitionResult recognize(Mat grayscale, Mat rgb, long frameTimestampNanos,
                                       RecognitionRequest request, RecognitionResult primary) {
        return recognize(grayscale, rgb, frameTimestampNanos, request, primary, null);
    }

    RecognitionResult recognize(Mat grayscale, Mat rgb, long frameTimestampNanos,
                                RecognitionRequest request, RecognitionResult primary,
                                Predicate<List<VisionPoint>> bracketPlacement) {
        if (!shouldAttempt(request, primary, bracketPlacement) || grayscale == null || grayscale.empty()
                || grayscale.type() != CvType.CV_8UC1) {
            reset();
            return primary;
        }
        String componentId = request.getExpectation().getComponentId();
        long started = System.nanoTime();
        if (request.inspectsLooseBracketSet()) {
            reset();
            return recognizeBracketSet(grayscale, rgb, frameTimestampNanos, request, primary, started);
        }
        ShapeCandidate candidate;
        if (NeobotSeedData.MOTOR_BRACKET.equals(componentId)) {
            Mat compatibleRgb = rgb != null && !rgb.empty() && rgb.type() == CvType.CV_8UC3
                    && rgb.rows() == grayscale.rows() && rgb.cols() == grayscale.cols()
                    ? rgb : null;
            candidate = findBracket(grayscale, compatibleRgb, minimumAreaRatio(request),
                    bracketPlacement, request);
        } else {
            if (rgb == null || rgb.empty() || rgb.type() != CvType.CV_8UC3
                    || rgb.rows() != grayscale.rows() || rgb.cols() != grayscale.cols()) {
                reset();
                return primary;
            }
            if (NeobotSeedData.CASTER_WHEEL.equals(componentId)) {
                BallCasterShapeRecognizer.Candidate caster = BallCasterShapeRecognizer.find(rgb, minimumAreaRatio(request));
                candidate = caster == null ? null : new ShapeCandidate("shape:rob-231-ball-caster-v1",
                        "Ball caster wheel", caster.confidence, caster.corners, 50.0, 25.0);
            } else if (NeobotSeedData.MICROCONTROLLER.equals(componentId)) {
                LooseNanoShapeRecognizer.Candidate nano = LooseNanoShapeRecognizer.find(
                        rgb, minimumAreaRatio(request));
                candidate = nano == null ? null : new ShapeCandidate("shape:loose-nano-v1",
                        "Arduino Nano", nano.confidence, nano.corners, 45.0, 18.0);
            } else if (NeobotSeedData.DISTANCE_SENSOR.equals(componentId)
                    || NeobotSeedData.LINE_TRACKER.equals(componentId)) {
                LooseSensorShapeRecognizer.Candidate sensor = NeobotSeedData.DISTANCE_SENSOR.equals(componentId)
                        ? LooseSensorShapeRecognizer.findUltrasonic(rgb, minimumAreaRatio(request))
                        : LooseSensorShapeRecognizer.findLineTracker(rgb, minimumAreaRatio(request));
                candidate = sensor == null ? null : new ShapeCandidate(
                        NeobotSeedData.DISTANCE_SENSOR.equals(componentId)
                                ? "shape:loose-ultrasonic-v1" : "shape:loose-line-tracker-v1",
                        NeobotSeedData.DISTANCE_SENSOR.equals(componentId)
                                ? "Ultrasonic distance sensor" : "Line tracker",
                        sensor.confidence, sensor.corners, sensor.widthMm, sensor.heightMm);
            } else candidate = NeobotSeedData.GEAR_MOTOR.equals(componentId)
                    ? findGearMotor(rgb, minimumAreaRatio(request))
                    : findMotorDriver(rgb, minimumAreaRatio(request));
        }
        if (candidate == null) {
            reset();
            return primary;
        }

        List<VisionPoint> stableCorners = smoothCorners(
            componentId, frameTimestampNanos, candidate.corners, grayscale.cols(), grayscale.rows());
        RecognitionResult.Pose pose = estimatePose(stableCorners,
                candidate.physicalWidthMm, candidate.physicalHeightMm,
                request.getCameraCalibration());
        // When intrinsics exist, do not turn an invalid coarse pose into identity-only success.
        if (request.getCameraCalibration() != null && pose == null) {
            reset();
            return primary;
        }

        long elapsed = System.nanoTime() - started;
        RecognitionResult.Latency base = primary == null
                ? RecognitionResult.Latency.zero() : primary.getLatency();
        double shapeMs = elapsed / 1_000_000.0;
        RecognitionResult.Latency latency = new RecognitionResult.Latency(
                base.getFeatureExtractionMs(), base.getMatchingMs(),
                base.getSpatialValidationMs(), base.getCircularFallbackMs() + shapeMs,
                base.getTotalMs() + shapeMs);
        int frameFeatures = primary == null ? 0 : primary.getFrameKeypointCount();
        RecognitionResult.InstanceObservation observation =
                new RecognitionResult.InstanceObservation(candidate.referenceId, componentId,
                        RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK,
                stableCorners, candidate.confidence);
        return RecognitionResult.builder(frameTimestampNanos)
                .identity(candidate.referenceId, componentId, candidate.displayName)
                .method(RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK)
                .validation(RecognitionResult.ValidationState.EXPECTED_COMPONENT)
                .orientation(RecognitionResult.OrientationClassification.UNKNOWN, Double.NaN)
                .polarity(RecognitionResult.PolarityState.NOT_REQUIRED)
                .matches(frameFeatures, 0, 0, 0.0)
                .confidence(candidate.confidence)
                .corners(stableCorners)
                .instances(Collections.singletonList(observation))
                .pose(pose)
                .spatialAnchor(primary == null ? null : primary.getSpatialAnchor())
                .latency(latency)
                .build();
    }

    private RecognitionResult recognizeBracketSet(Mat gray, Mat rgb, long timestamp,
                                                  RecognitionRequest request,
                                                  RecognitionResult primary, long started) {
        // RGB is required for set evidence: retain the veto of blue/green PCBs and yellow motors.
        if (rgb == null || rgb.empty() || rgb.type() != CvType.CV_8UC3
                || rgb.rows() != gray.rows() || rgb.cols() != gray.cols()) return primary;
        List<MotorBracketShapeRecognizer.Candidate> candidates = MotorBracketShapeRecognizer.findAll(
                gray, rgb, minimumAreaRatio(request), null);
        if (candidates.isEmpty()) return primary;
        List<RecognitionResult.InstanceObservation> observations = new ArrayList<>();
        // Preserve stronger ORB observations; overlapping threshold silhouettes count once.
        if (primary != null && NeobotSeedData.MOTOR_BRACKET.equals(primary.getComponentId())
                && primary.getValidationState() == RecognitionResult.ValidationState.EXPECTED_COMPONENT) {
            observations.addAll(primary.getInstanceObservations());
        }
        String referenceId = "shape:loose-motor-bracket-v2";
        MotorBracketShapeRecognizer.Candidate first = null;
        RecognitionResult.Pose firstPose = null;
        for (MotorBracketShapeRecognizer.Candidate candidate : candidates) {
            RecognitionResult.Pose pose = estimatePose(candidate.corners,
                    MotorBracketShapeRecognizer.WIDTH_MM, MotorBracketShapeRecognizer.HEIGHT_MM,
                    request.getCameraCalibration());
            if (request.getCameraCalibration() != null && pose == null) continue;
            if (first == null) { first = candidate; firstPose = pose; }
            boolean distinct = true;
            for (RecognitionResult.InstanceObservation prior : observations) {
                if (!ComponentRecognizer.areDistinctInstanceQuads(
                        prior.getProjectedCorners(), candidate.corners)) {
                    distinct = false;
                    break;
                }
            }
            if (distinct) observations.add(new RecognitionResult.InstanceObservation(referenceId,
                    NeobotSeedData.MOTOR_BRACKET, RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK,
                    candidate.corners, candidate.confidence));
        }
        if (first == null || observations.isEmpty() || primary != null && observations.size()
                <= primary.getDetectedInstanceCount(NeobotSeedData.MOTOR_BRACKET)) return primary;
        double shapeMs = (System.nanoTime() - started) / 1_000_000.0;
        RecognitionResult.Latency base = primary == null ? RecognitionResult.Latency.zero()
                : primary.getLatency();
        return RecognitionResult.builder(timestamp)
                .identity(referenceId, NeobotSeedData.MOTOR_BRACKET, "Gear-motor bracket set")
                .method(RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK)
                .validation(RecognitionResult.ValidationState.EXPECTED_COMPONENT)
                .confidence(first.confidence).corners(first.corners).pose(firstPose)
                .instances(observations)
                .matches(primary == null ? 0 : primary.getFrameKeypointCount(), 0, 0, 0)
                .latency(new RecognitionResult.Latency(base.getFeatureExtractionMs(),
                        base.getMatchingMs(), base.getSpatialValidationMs(),
                        base.getCircularFallbackMs() + shapeMs, base.getTotalMs() + shapeMs))
                .build();
    }

    private synchronized List<VisionPoint> smoothCorners(String componentId,
                                                          long timestampNanos,
                                                          List<VisionPoint> corners,
                                                          int frameWidth,
                                                          int frameHeight) {
        boolean compatible = componentId.equals(previousComponentId)
                && frameWidth == previousFrameWidth && frameHeight == previousFrameHeight
                && timestampNanos > previousTimestampNanos
                && timestampNanos - previousTimestampNanos <= MAX_SMOOTHING_GAP_NANOS
                && previousCorners != null
                && corners.size() == previousCorners.size()
                && cornerMotion(corners, previousCorners)
                <= Math.hypot(frameWidth, frameHeight) * MAX_SMOOTHING_MOTION_RATIO;
        List<VisionPoint> smoothed = new ArrayList<>(corners.size());
        for (int index = 0; index < corners.size(); index++) {
            VisionPoint current = corners.get(index);
            VisionPoint prior = compatible ? previousCorners.get(index) : current;
            smoothed.add(new VisionPoint(
                    prior.getX() + SMOOTHING_ALPHA * (current.getX() - prior.getX()),
                    prior.getY() + SMOOTHING_ALPHA * (current.getY() - prior.getY())));
        }
        previousComponentId = componentId;
        previousFrameWidth = frameWidth;
        previousFrameHeight = frameHeight;
        previousTimestampNanos = timestampNanos;
        previousCorners = Collections.unmodifiableList(new ArrayList<>(smoothed));
        return previousCorners;
    }

    private static double cornerMotion(List<VisionPoint> first, List<VisionPoint> second) {
        double total = 0.0;
        for (int index = 0; index < first.size(); index++) {
            total += first.get(index).distanceTo(second.get(index));
        }
        return total / Math.max(1, first.size());
    }

    private static double minimumAreaRatio(RecognitionRequest request) {
        Double override = request.getMinimumCornerAreaRatioOverride();
        return override == null ? DEFAULT_MINIMUM_AREA_RATIO : override;
    }

    private static ShapeCandidate findBracket(Mat source, Mat rgb, double minimumAreaRatio,
                                              Predicate<List<VisionPoint>> placement,
                                              RecognitionRequest request) {
        MotorBracketShapeRecognizer.Candidate bracket = MotorBracketShapeRecognizer.find(
                source, rgb, minimumAreaRatio, placement);
        if (bracket == null && placement != null && request.getGuidedBracketSlot() != 0) {
            bracket = MotorBracketShapeRecognizer.findInstalledProfile(source, rgb,
                    minimumAreaRatio, request.getGuidedBracketWorkplane(),
                    request.getGuidedBracketSlot(), placement);
        }
        if (bracket == null) return null;
        return bracket.installedProfile
                ? new ShapeCandidate("shape:installed-motor-bracket-side-v1",
                        "Installed gear-motor bracket", bracket.confidence, bracket.corners,
                        9.0, MotorBracketShapeRecognizer.HEIGHT_MM)
                : new ShapeCandidate("shape:loose-motor-bracket-v2",
                        "Gear-motor bracket", bracket.confidence, bracket.corners,
                        MotorBracketShapeRecognizer.WIDTH_MM,
                        MotorBracketShapeRecognizer.HEIGHT_MM);
    }
    private static ShapeCandidate findGearMotor(Mat rgb, double minimumAreaRatio) {
        WorkingImage working = WorkingImage.rgb(rgb);
        Mat hsv = new Mat();
        Mat gray = new Mat();
        Mat yellow = new Mat();
        Mat kernel = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            Imgproc.cvtColor(working.rgb, hsv, Imgproc.COLOR_RGB2HSV);
            Imgproc.cvtColor(working.rgb, gray, Imgproc.COLOR_RGB2GRAY);
            Core.inRange(hsv, new Scalar(17, 92, 62), new Scalar(43, 255, 255), yellow);
            kernel.release();
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
            Imgproc.morphologyEx(yellow, yellow, Imgproc.MORPH_OPEN, kernel);
            Imgproc.morphologyEx(yellow, yellow, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.findContours(yellow, contours, hierarchy, Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE);

            ShapeCandidate best = null;
            double frameArea = working.rgb.rows() * (double) working.rgb.cols();
            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);
                double areaRatio = area / frameArea;
                if (areaRatio < minimumAreaRatio || areaRatio > MAXIMUM_AREA_RATIO) continue;
                MatOfPoint2f points = new MatOfPoint2f(contour.toArray());
                try {
                    RotatedRect box = Imgproc.minAreaRect(points);
                    AxisGeometry axis = AxisGeometry.from(box);
                    if (axis.minor < 28.0 || axis.major / axis.minor < 1.08
                            || axis.major / axis.minor > 4.60) {
                        continue;
                    }
                    double fill = area / Math.max(1.0, box.size.area());
                    if (fill < 0.48) continue;

                    double positiveMetal = metalCanEvidence(hsv, gray, axis, 1.0);
                    double negativeMetal = metalCanEvidence(hsv, gray, axis, -1.0);
                    double metal = Math.max(positiveMetal, negativeMetal);
                    if (metal < 0.52) continue;
                    if (roundedHubWithTyre(hsv, points, axis)) continue;
                    boolean extendPositive = positiveMetal >= negativeMetal;
                    double confidence = Math.min(0.96,
                            0.61 + 0.14 * clamp01((fill - 0.48) / 0.40)
                                    + 0.22 * metal);
                    List<VisionPoint> corners = orientedCorners(box,
                            working.inverseScale, axis.minor * 0.58,
                            extendPositive);
                    if (!LooseGearMotorEvidence.accepts(hsv, orientedCorners(box,
                            1.0, axis.minor * .58, extendPositive))) continue;
                    ShapeCandidate found = new ShapeCandidate(MOTOR_REFERENCE_ID,
                            "6 V DC gear motor", confidence, corners,
                            MOTOR_LENGTH_MM, MOTOR_WIDTH_MM);
                    if (best == null || found.confidence > best.confidence) best = found;
                } finally {
                    points.release();
                }
            }
            return best;
        } finally {
            for (MatOfPoint contour : contours) contour.release();
            hierarchy.release();
            kernel.release();
            yellow.release();
            gray.release();
            hsv.release();
            working.close();
        }
    }

    /** A round yellow hub surrounded by dark rubber is a wheel, including tilted views. */
    private static boolean roundedHubWithTyre(Mat hsv, MatOfPoint2f contour, AxisGeometry axis) {
        Point[] points = contour.toArray();
        Point[] projected = new Point[points.length];
        for (int i = 0; i < points.length; i++) {
            double dx = points[i].x - axis.center.x, dy = points[i].y - axis.center.y;
            projected[i] = new Point((dx * axis.majorAxis.x + dy * axis.majorAxis.y) / axis.major,
                    (-dx * axis.majorAxis.y + dy * axis.majorAxis.x) / axis.minor);
        }
        MatOfPoint2f normalized = new MatOfPoint2f(projected);
        try {
            double perimeter = Imgproc.arcLength(normalized, true);
            if (perimeter <= 0 || 4 * Math.PI * Imgproc.contourArea(normalized)
                    / (perimeter * perimeter) < .80) return false;
        } finally {
            normalized.release();
        }
        double reach = .66 * (axis.major + axis.minor);
        int left = Math.max(0, (int) Math.floor(axis.center.x - reach));
        int right = Math.min(hsv.cols() - 1, (int) Math.ceil(axis.center.x + reach));
        int top = Math.max(0, (int) Math.floor(axis.center.y - reach));
        int bottom = Math.min(hsv.rows() - 1, (int) Math.ceil(axis.center.y + reach));
        byte[] pixels = new byte[(int) hsv.total() * 3]; hsv.get(0, 0, pixels);
        int ring = 0, dark = 0;
        for (int y = top; y <= bottom; y++) for (int x = left; x <= right; x++) {
            double dx = x - axis.center.x, dy = y - axis.center.y;
            double a = (dx * axis.majorAxis.x + dy * axis.majorAxis.y) / (axis.major * .5);
            double b = (-dx * axis.majorAxis.y + dy * axis.majorAxis.x) / (axis.minor * .5);
            double radius = a * a + b * b;
            if (radius < 1.10 * 1.10 || radius > 1.32 * 1.32) continue;
            ring++;
            int i = (y * hsv.cols() + x) * 3;
            if ((pixels[i + 1] & 255) <= 150 && (pixels[i + 2] & 255) <= 92) dark++;
        }
        return ring > 0 && dark / (double) ring >= .40;
    }

    /**
     * Locates the driver's complete colored body. Blue PCB evidence alone is insufficient because
     * other kit electronics use blue solder mask; the candidate must also contain a substantial
     * green terminal region and internal edge texture from screws, ICs, holes, and silk screen.
     */
    private static ShapeCandidate findMotorDriver(Mat rgb, double minimumAreaRatio) {
        WorkingImage working = WorkingImage.rgb(rgb);
        Mat hsv = new Mat();
        Mat gray = new Mat();
        Mat blue = new Mat();
        Mat green = new Mat();
        Mat coloredBody = new Mat();
        Mat kernel = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            Imgproc.cvtColor(working.rgb, hsv, Imgproc.COLOR_RGB2HSV);
            Imgproc.cvtColor(working.rgb, gray, Imgproc.COLOR_RGB2GRAY);
            Core.inRange(hsv, new Scalar(91, 48, 24), new Scalar(137, 255, 255), blue);
            // Cyan solder mask is common on sensor PCBs; only the greener terminal
            // block region supplies driver evidence. Keep the saturation/value floors.
            Core.inRange(hsv, new Scalar(35, 62, 28), new Scalar(80, 255, 255), green);
            Core.bitwise_or(blue, green, coloredBody);
            kernel.release();
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(9, 9));
            Imgproc.morphologyEx(coloredBody, coloredBody, Imgproc.MORPH_CLOSE, kernel);
            kernel.release();
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3));
            Imgproc.morphologyEx(coloredBody, coloredBody, Imgproc.MORPH_OPEN, kernel);
            Imgproc.findContours(coloredBody, contours, hierarchy, Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE);

            ShapeCandidate best = null;
            double frameArea = working.rgb.rows() * (double) working.rgb.cols();
            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);
                double areaRatio = area / frameArea;
                if (areaRatio < minimumAreaRatio || areaRatio > MAXIMUM_AREA_RATIO) continue;
                MatOfPoint2f points = new MatOfPoint2f(contour.toArray());
                try {
                    RotatedRect box = Imgproc.minAreaRect(points);
                    double major = Math.max(box.size.width, box.size.height);
                    double minor = Math.min(box.size.width, box.size.height);
                    double aspect = major / Math.max(1.0, minor);
                    double fill = area / Math.max(1.0, box.size.area());
                    if (minor < 28.0 || aspect < 1.03 || aspect > 2.35 || fill < 0.24) {
                        continue;
                    }
                    DriverEvidence evidence = inspectDriverEvidence(blue, green, gray, box);
                    if (evidence.blueRatio < 0.075 || evidence.greenRatio < 0.055
                            || evidence.edgeRatio < 0.025
                            || evidence.blueRatio + evidence.greenRatio < 0.20) {
                        continue;
                    }
                    double confidence = Math.min(0.96,
                            0.57 + 0.12 * clamp01((fill - 0.24) / 0.42)
                                    + 0.11 * clamp01(evidence.blueRatio / 0.34)
                                    + 0.11 * clamp01(evidence.greenRatio / 0.30)
                                    + 0.09 * clamp01(evidence.edgeRatio / 0.12));
                    ShapeCandidate found = new ShapeCandidate(DRIVER_REFERENCE_ID,
                            "Dual motor driver", confidence,
                            orientedCorners(box, working.inverseScale, 0.0, false),
                            DRIVER_WIDTH_MM, DRIVER_HEIGHT_MM);
                    if (best == null || found.confidence > best.confidence) best = found;
                } finally {
                    points.release();
                }
            }
            return best;
        } finally {
            for (MatOfPoint contour : contours) contour.release();
            hierarchy.release();
            kernel.release();
            coloredBody.release();
            green.release();
            blue.release();
            gray.release();
            hsv.release();
            working.close();
        }
    }

    private static DriverEvidence inspectDriverEvidence(
            Mat blue, Mat green, Mat gray, RotatedRect box) {
        Point[] polygon = new Point[4];
        box.points(polygon);
        Rect bounds = boundingRect(polygon, gray.cols(), gray.rows());
        if (bounds == null) return DriverEvidence.NONE;
        Mat localBlue = blue.submat(bounds);
        Mat localGreen = green.submat(bounds);
        Mat localGray = gray.submat(bounds);
        Mat mask = Mat.zeros(bounds.height, bounds.width, CvType.CV_8UC1);
        Mat selected = new Mat();
        Mat edges = new Mat();
        MatOfPoint localPolygon = new MatOfPoint();
        try {
            Point[] translated = new Point[polygon.length];
            for (int index = 0; index < polygon.length; index++) {
                translated[index] = new Point(
                        polygon[index].x - bounds.x, polygon[index].y - bounds.y);
            }
            localPolygon.fromArray(translated);
            Imgproc.fillConvexPoly(mask, localPolygon, Scalar.all(255));
            double pixels = Math.max(1.0, Core.countNonZero(mask));
            Core.bitwise_and(localBlue, mask, selected);
            double blueRatio = Core.countNonZero(selected) / pixels;
            Core.bitwise_and(localGreen, mask, selected);
            double greenRatio = Core.countNonZero(selected) / pixels;
            Imgproc.Canny(localGray, edges, 35.0, 105.0);
            Core.bitwise_and(edges, mask, selected);
            double edgeRatio = Core.countNonZero(selected) / pixels;
            return new DriverEvidence(blueRatio, greenRatio, edgeRatio);
        } finally {
            localPolygon.release();
            edges.release();
            selected.release();
            mask.release();
            localGray.release();
            localGreen.release();
            localBlue.release();
        }
    }

    /** Rejects a yellow block unless the region beyond one long-axis end resembles a motor can. */
    private static double metalCanEvidence(Mat hsv, Mat gray, AxisGeometry axis, double sign) {
        double length = Math.max(14.0, axis.minor * 0.68);
        double width = Math.max(12.0, axis.minor * 0.82);
        Point center = new Point(
                axis.center.x + sign * axis.majorAxis.x * (axis.major * 0.5 + length * 0.42),
                axis.center.y + sign * axis.majorAxis.y * (axis.major * 0.5 + length * 0.42));
        Point[] polygon = rectangle(center, axis.majorAxis, length, width);
        Rect bounds = boundingRect(polygon, hsv.cols(), hsv.rows());
        if (bounds == null || bounds.width < 8 || bounds.height < 8) return 0.0;

        Mat localHsv = hsv.submat(bounds);
        Mat localGray = gray.submat(bounds);
        Mat mask = Mat.zeros(bounds.height, bounds.width, CvType.CV_8UC1);
        Mat neutral = new Mat();
        Mat dark = new Mat();
        Mat edges = new Mat();
        Mat combined = new Mat();
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble deviation = new MatOfDouble();
        MatOfPoint localPolygon = new MatOfPoint();
        try {
            Point[] translated = new Point[polygon.length];
            for (int index = 0; index < polygon.length; index++) {
                translated[index] = new Point(polygon[index].x - bounds.x,
                        polygon[index].y - bounds.y);
            }
            localPolygon.fromArray(translated);
            Imgproc.fillConvexPoly(mask, localPolygon, Scalar.all(255));
            double pixels = Math.max(1.0, Core.countNonZero(mask));
            Core.inRange(localHsv, new Scalar(0, 0, 42), new Scalar(179, 94, 228), neutral);
            Core.inRange(localHsv, new Scalar(0, 0, 0), new Scalar(179, 150, 92), dark);
            Imgproc.Canny(localGray, edges, 38, 105);
            Core.bitwise_and(neutral, mask, combined);
            double neutralRatio = Core.countNonZero(combined) / pixels;
            Core.bitwise_and(dark, mask, combined);
            double darkRatio = Core.countNonZero(combined) / pixels;
            Core.bitwise_and(edges, mask, combined);
            double edgeRatio = Core.countNonZero(combined) / pixels;
            Core.meanStdDev(localGray, mean, deviation, mask);
            double contrast = deviation.toArray()[0];
            if (neutralRatio < 0.16 || darkRatio < 0.012 || darkRatio > 0.78
                    || edgeRatio < 0.018 || contrast < 13.0) {
                return 0.0;
            }
            return Math.min(1.0, 0.30
                    + 0.24 * Math.min(1.0, neutralRatio / 0.48)
                    + 0.18 * Math.min(1.0, darkRatio / 0.16)
                    + 0.16 * Math.min(1.0, edgeRatio / 0.075)
                    + 0.16 * Math.min(1.0, contrast / 48.0));
        } finally {
            localPolygon.release();
            deviation.release();
            mean.release();
            combined.release();
            edges.release();
            dark.release();
            neutral.release();
            mask.release();
            localGray.release();
            localHsv.release();
        }
    }

    private static List<VisionPoint> orientedCorners(RotatedRect box, double inverseScale,
                                                     double extension, boolean extendPositive) {
        AxisGeometry axis = AxisGeometry.from(box);
        double negative = axis.major * 0.5 + (!extendPositive ? extension : 0.0);
        double positive = axis.major * 0.5 + (extendPositive ? extension : 0.0);
        double halfMinor = axis.minor * 0.5;
        Point u = axis.majorAxis;
        Point v = new Point(-u.y, u.x);
        Point first = add(axis.center, u, -negative, v, -halfMinor);
        Point second = add(axis.center, u, positive, v, -halfMinor);
        Point third = add(axis.center, u, positive, v, halfMinor);
        Point fourth = add(axis.center, u, -negative, v, halfMinor);
        List<VisionPoint> result = Arrays.asList(
                scaled(first, inverseScale), scaled(second, inverseScale),
                scaled(third, inverseScale), scaled(fourth, inverseScale));
        if (signedArea(result) < 0.0) {
            result = Arrays.asList(result.get(0), result.get(3), result.get(2), result.get(1));
        }
        return Collections.unmodifiableList(result);
    }

    private static Point add(Point center, Point u, double uScale, Point v, double vScale) {
        return new Point(center.x + u.x * uScale + v.x * vScale,
                center.y + u.y * uScale + v.y * vScale);
    }

    private static VisionPoint scaled(Point point, double scale) {
        return new VisionPoint(point.x * scale, point.y * scale);
    }

    private static Point[] rectangle(Point center, Point majorAxis,
                                     double length, double width) {
        Point minorAxis = new Point(-majorAxis.y, majorAxis.x);
        double halfLength = length * 0.5;
        double halfWidth = width * 0.5;
        return new Point[]{
                add(center, majorAxis, -halfLength, minorAxis, -halfWidth),
                add(center, majorAxis, halfLength, minorAxis, -halfWidth),
                add(center, majorAxis, halfLength, minorAxis, halfWidth),
                add(center, majorAxis, -halfLength, minorAxis, halfWidth)
        };
    }

    private static Rect boundingRect(Point[] points, int width, int height) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Point point : points) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }
        int left = Math.max(0, (int) Math.floor(minX));
        int top = Math.max(0, (int) Math.floor(minY));
        int right = Math.min(width, (int) Math.ceil(maxX) + 1);
        int bottom = Math.min(height, (int) Math.ceil(maxY) + 1);
        return right - left < 2 || bottom - top < 2
                ? null : new Rect(left, top, right - left, bottom - top);
    }

    static RecognitionResult.Pose estimatePose(List<VisionPoint> corners,
                                                       double widthMm, double heightMm,
                                                       CameraCalibration calibration) {
        if (calibration == null || corners == null || corners.size() != 4) return null;
        double halfWidth = widthMm * 0.5;
        double halfHeight = heightMm * 0.5;
        Point3[] objectValues = {
                new Point3(-halfWidth, halfHeight, 0.0),
                new Point3(halfWidth, halfHeight, 0.0),
                new Point3(halfWidth, -halfHeight, 0.0),
                new Point3(-halfWidth, -halfHeight, 0.0)
        };
        Point[] imageValues = new Point[4];
        for (int index = 0; index < imageValues.length; index++) {
            VisionPoint point = calibration.deskew(corners.get(index));
            imageValues[index] = new Point(point.getX(), point.getY());
        }
        MatOfPoint3f objectPoints = new MatOfPoint3f(objectValues);
        MatOfPoint2f imagePoints = new MatOfPoint2f(imageValues);
        Mat camera = calibration.toOpenCvCameraMatrix();
        MatOfDouble distortion = calibration.toDistortionMatrix();
        Mat rvec = new Mat();
        Mat tvec = new Mat();
        Mat rotation = new Mat();
        try {
            boolean solved = Calib3d.solvePnP(objectPoints, imagePoints, camera, distortion,
                    rvec, tvec, false, Calib3d.SOLVEPNP_ITERATIVE);
            if (!solved || rvec.empty() || tvec.empty()) return null;
            Calib3d.Rodrigues(rvec, rotation);
            double[] rotationValues = new double[9];
            double[] rotationVector = new double[3];
            double[] translation = new double[3];
            rotation.get(0, 0, rotationValues);
            rvec.get(0, 0, rotationVector);
            tvec.get(0, 0, translation);
            if (!allFinite(rotationValues) || !allFinite(rotationVector)
                    || !allFinite(translation) || !allFloatRepresentable(translation)
                    || !hasPositiveDepth(rotationValues, translation,
                    halfWidth, halfHeight)) {
                return null;
            }
            // These corners bound a non-planar silhouette rather than four measured coplanar
            // landmarks, so the planar recognizer's precision reprojection gate is inapplicable.
            // Finite translation and positive depth for every nominal body corner still prevent
            // an invalid solution from entering the stability gate.
            return new RecognitionResult.Pose(rotationVector, translation,
                    toOpenGlModelView(rotationValues, translation));
        } finally {
            rotation.release();
            tvec.release();
            rvec.release();
            distortion.release();
            camera.release();
            imagePoints.release();
            objectPoints.release();
        }
    }

    private static float[] toOpenGlModelView(double[] rotation, double[] translation) {
        return new float[]{
                (float) rotation[0], (float) -rotation[3], (float) -rotation[6], 0.0f,
                (float) rotation[1], (float) -rotation[4], (float) -rotation[7], 0.0f,
                (float) rotation[2], (float) -rotation[5], (float) -rotation[8], 0.0f,
                (float) translation[0], (float) -translation[1],
                (float) -translation[2], 1.0f
        };
    }

    private static boolean allFinite(double[] values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static boolean allFloatRepresentable(double[] values) {
        for (double value : values) if (Math.abs(value) > Float.MAX_VALUE) return false;
        return true;
    }

    private static boolean hasPositiveDepth(double[] rotation, double[] translation,
                                            double halfWidth, double halfHeight) {
        double[][] points = {{-halfWidth, -halfHeight}, {halfWidth, -halfHeight},
                {halfWidth, halfHeight}, {-halfWidth, halfHeight}};
        for (double[] point : points) {
            double depth = rotation[6] * point[0]
                    + rotation[7] * point[1] + translation[2];
            if (!(depth > 0.0) || !Double.isFinite(depth)) return false;
        }
        return true;
    }

    private static double signedArea(List<VisionPoint> points) {
        double area = 0.0;
        for (int index = 0; index < points.size(); index++) {
            VisionPoint a = points.get(index);
            VisionPoint b = points.get((index + 1) % points.size());
            area += a.getX() * b.getY() - a.getY() * b.getX();
        }
        return area * 0.5;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static final class WorkingImage implements AutoCloseable {
        private final Mat gray;
        private final Mat rgb;
        private final double inverseScale;
        private final boolean ownsGray;
        private final boolean ownsRgb;

        private WorkingImage(Mat gray, Mat rgb, double inverseScale,
                             boolean ownsGray, boolean ownsRgb) {
            this.gray = gray;
            this.rgb = rgb;
            this.inverseScale = inverseScale;
            this.ownsGray = ownsGray;
            this.ownsRgb = ownsRgb;
        }

        static WorkingImage gray(Mat source) {
            double scale = workingScale(source);
            if (scale >= 1.0) return new WorkingImage(source, null, 1.0, false, false);
            Mat resized = new Mat();
            Imgproc.resize(source, resized,
                    new Size(Math.round(source.cols() * scale), Math.round(source.rows() * scale)),
                    0.0, 0.0, Imgproc.INTER_AREA);
            return new WorkingImage(resized, null, 1.0 / scale, true, false);
        }

        static WorkingImage rgb(Mat source) {
            double scale = workingScale(source);
            if (scale >= 1.0) return new WorkingImage(null, source, 1.0, false, false);
            Mat resized = new Mat();
            Imgproc.resize(source, resized,
                    new Size(Math.round(source.cols() * scale), Math.round(source.rows() * scale)),
                    0.0, 0.0, Imgproc.INTER_AREA);
            return new WorkingImage(null, resized, 1.0 / scale, false, true);
        }

        private static double workingScale(Mat source) {
            int maximum = Math.max(source.cols(), source.rows());
            return maximum <= MAX_WORKING_DIMENSION ? 1.0
                    : MAX_WORKING_DIMENSION / (double) maximum;
        }

        @Override public void close() {
            if (ownsGray && gray != null) gray.release();
            if (ownsRgb && rgb != null) rgb.release();
        }
    }

    private static final class AxisGeometry {
        private final Point center;
        private final Point majorAxis;
        private final double major;
        private final double minor;

        private AxisGeometry(Point center, Point majorAxis, double major, double minor) {
            this.center = center;
            this.majorAxis = majorAxis;
            this.major = major;
            this.minor = minor;
        }

        static AxisGeometry from(RotatedRect box) {
            Point[] corners = new Point[4];
            box.points(corners);
            double first = distance(corners[0], corners[1]);
            double second = distance(corners[1], corners[2]);
            Point start;
            Point end;
            double major;
            double minor;
            if (first >= second) {
                start = corners[0];
                end = corners[1];
                major = first;
                minor = second;
            } else {
                start = corners[1];
                end = corners[2];
                major = second;
                minor = first;
            }
            Point direction = new Point((end.x - start.x) / Math.max(1e-9, major),
                    (end.y - start.y) / Math.max(1e-9, major));
            // Resolve the 180-degree ambiguity so corners and pose do not flip between frames.
            if (direction.x < -1e-6 || Math.abs(direction.x) <= 1e-6 && direction.y < 0.0) {
                direction.x = -direction.x;
                direction.y = -direction.y;
            }
            return new AxisGeometry(box.center, direction, major, minor);
        }

        private static double distance(Point first, Point second) {
            return Math.hypot(first.x - second.x, first.y - second.y);
        }
    }

    private static final class ShapeCandidate {
        private final String referenceId;
        private final String displayName;
        private final double confidence;
        private final List<VisionPoint> corners;
        private final double physicalWidthMm;
        private final double physicalHeightMm;

        private ShapeCandidate(String referenceId, String displayName, double confidence,
                               List<VisionPoint> corners, double physicalWidthMm,
                               double physicalHeightMm) {
            this.referenceId = referenceId;
            this.displayName = displayName;
            this.confidence = confidence;
            this.corners = corners;
            this.physicalWidthMm = physicalWidthMm;
            this.physicalHeightMm = physicalHeightMm;
        }
    }

    private static final class DriverEvidence {
        private static final DriverEvidence NONE = new DriverEvidence(0.0, 0.0, 0.0);
        private final double blueRatio;
        private final double greenRatio;
        private final double edgeRatio;

        private DriverEvidence(double blueRatio, double greenRatio, double edgeRatio) {
            this.blueRatio = blueRatio;
            this.greenRatio = greenRatio;
            this.edgeRatio = edgeRatio;
        }
    }

}
