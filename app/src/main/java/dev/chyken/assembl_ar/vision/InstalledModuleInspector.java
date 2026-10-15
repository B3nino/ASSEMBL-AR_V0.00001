package dev.chyken.assembl_ar.vision;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;

/**
 * Board-relative check for the two blue modules mounted flat on the main board: the dual motor
 * driver (section 3) and the Arduino Nano (section 6).
 *
 * <p>Whole-frame ORB cannot identify these modules in a 640x480 analysis frame: an installed
 * module spans roughly 75x95 px, so no reference reaches the twelve ratio-test matches the
 * recognizer requires, and repeated header pins defeat the ratio test. The loose-part colour
 * fallback cannot isolate them either: its permissive HSV blue range also covers the white PCB's
 * cool shadows and merges the module into one board-sized blob.
 *
 * <p>Once the board itself is localized, the module's slot is known. Like
 * {@link MotorAssemblyInspector}, this rectifies the board and inspects that slot only. Solder
 * mask is found as pixels bluer than the board's own white in CIE Lab, which cancels the light
 * source: in the step 1-8 kit photos no empty slot had such pixels, while a lightly lit driver
 * still did. The driver additionally needs its green screw terminals below its blue body
 * (toward the Nano); the Nano needs its elongated body and gold USB shell at the
 * board-bottom end. The returned outline must still pass the authored
 * {@code ChassisRelativePlacementGate} target and every multi-frame gate. This proves visible
 * placement only; it cannot certify seated header pins, bent pins or wiring.
 */
public final class InstalledModuleInspector {
    static final int WIDTH = 384;
    static final int HEIGHT = 456;
    static final int PAD = 96;
    private static final double BOARD_WIDTH_MM = MainBoardDimensions.WIDTH_MM;
    private static final double BOARD_HEIGHT_MM = MainBoardDimensions.HEIGHT_MM;

    /** Solder mask is at least this much bluer (Lab b*) than the board's white. */
    private static final double BLUE_OFFSET = 8.0;
    private static final double A_TOLERANCE = 20.0;
    private static final int MINIMUM_LIGHTNESS = 41;
    /** Board-white estimate: low-chroma pixels at or above their median lightness. */
    private static final double NEUTRAL_CHROMA = 25.0;
    private static final Scalar GREEN_LOW = new Scalar(40, 90, 50);
    private static final Scalar GREEN_HIGH = new Scalar(85, 255, 255);
    /** The Nano's gold USB shell, just beyond the board-bottom end of its blue PCB. */
    private static final Scalar GOLD_LOW = new Scalar(12, 80, 35);
    private static final Scalar GOLD_HIGH = new Scalar(35, 255, 255);
    private static final double USB_BAND_HEIGHT = 0.10;
    private static final double MIN_USB_GOLD = 0.25;
    private static final double USB_GOLD_MARGIN = 0.20;

    /** Canonical search window and footprint limits, in board-width / board-height units. */
    static final class Profile {
        final String componentId;
        final String referenceId;
        final String displayName;
        final double minU, maxU, minV, maxV;
        final double minWidth, maxWidth, minHeight, maxHeight;
        final double minBlueArea;
        final double minGreenArea;
        final double minGreenBelowBlue;
        final boolean longAxisAlongBoard;

        Profile(String componentId, String referenceId, String displayName,
                double minU, double maxU, double minV, double maxV,
                double minWidth, double maxWidth, double minHeight, double maxHeight,
                double minBlueArea, double minGreenArea, double minGreenBelowBlue,
                boolean longAxisAlongBoard) {
            this.componentId = componentId;
            this.referenceId = referenceId;
            this.displayName = displayName;
            this.minU = minU; this.maxU = maxU; this.minV = minV; this.maxV = maxV;
            this.minWidth = minWidth; this.maxWidth = maxWidth;
            this.minHeight = minHeight; this.maxHeight = maxHeight;
            this.minBlueArea = minBlueArea;
            this.minGreenArea = minGreenArea;
            this.minGreenBelowBlue = minGreenBelowBlue;
            this.longAxisAlongBoard = longAxisAlongBoard;
        }
    }

    // Pool photos (never the held-out fixtures): installed driver outline 0.22-0.29 x 0.21-0.31,
    // centre (0.49-0.53, 0.33-0.40); blue 0.004-0.015 and green 0.008-0.015 of the board area,
    // green centroid 0.07-0.12 below blue. Empty slots: green 0, blue at most 0.004.
    static final Profile DRIVER = new Profile(NeobotSeedData.MOTOR_DRIVER,
            "inspector:installed-motor-driver-v1", "Dual motor driver",
            0.22, 0.78, 0.08, 0.62,
            0.17, 0.36, 0.15, 0.38,
            0.001, 0.004, 0.03, false);
    // Pool photos: installed Nano outline 0.17-0.18 x 0.33, centre (0.49-0.51, 0.71-0.75),
    // blue 0.021-0.028 of the board area, gold USB 0.53-0.61 below and 0.00 above. No empty
    // slot (95 photos, including drivers directly above) produced any outline in this window.
    static final Profile NANO = new Profile(NeobotSeedData.MICROCONTROLLER,
            "inspector:installed-nano-v1", "Arduino Nano",
            0.30, 0.70, 0.50, 1.00,
            0.12, 0.26, 0.24, 0.45,
            0.010, 0.0, 0.0, true);

    /** One accepted module observation, in analyzed-frame pixel coordinates. */
    static final class Observation {
        final Profile profile;
        final List<VisionPoint> corners;
        final double widthMm;
        final double heightMm;
        final double confidence;

        Observation(Profile profile, List<VisionPoint> corners,
                    double widthMm, double heightMm, double confidence) {
            this.profile = profile;
            this.corners = corners;
            this.widthMm = widthMm;
            this.heightMm = heightMm;
            this.confidence = confidence;
        }
    }

    static Profile profileFor(String componentId) {
        if (NeobotSeedData.MOTOR_DRIVER.equals(componentId)) return DRIVER;
        if (NeobotSeedData.MICROCONTROLLER.equals(componentId)) return NANO;
        return null;
    }

    public static boolean supports(String componentId) {
        return profileFor(componentId) != null;
    }

    /**
     * Same precedence as the loose-part fallback: a usable ORB observation of any part wins,
     * except the requested board anchor itself; an ambiguous identity is never overridden.
     */
    static boolean shouldAttempt(RecognitionRequest request, RecognitionResult primary,
                                 RecognitionResult.SpatialAnchor board) {
        if (request == null || request.getExpectation() == null || board == null
                || board.getProjectedCorners().size() != 4
                || !NeobotSeedData.MAIN_BOARD.equals(board.getComponentId())
                || !NeobotSeedData.MAIN_BOARD.equals(request.getSpatialAnchorComponentId())
                || request.getExpectedInstanceCount() != 1
                || request.getExpectation().getPolarityRule() != null
                || !supports(request.getExpectation().getComponentId())) {
            return false;
        }
        if (primary != null && primary.getRejectionEvidence() != null
                && primary.getRejectionEvidence().getReason()
                == RecognitionResult.RejectionReason.IDENTITY_AMBIGUOUS) {
            return false;
        }
        return !LoosePartShapeRecognizer.hasUsableGeometry(primary)
                || NeobotSeedData.MAIN_BOARD.equals(primary.getComponentId());
    }

    /** Returns an expected-component result, or {@code primary} when the slot evidence is absent. */
    RecognitionResult recognize(Mat rgb, RecognitionResult.SpatialAnchor board,
                                RecognitionRequest request, RecognitionResult primary,
                                long frameTimestampNanos) {
        if (!shouldAttempt(request, primary, board)) return primary;
        long started = System.nanoTime();
        Observation observation = inspect(rgb, board.getProjectedCorners(),
                request.getExpectation().getComponentId());
        if (observation == null) return primary;
        RecognitionResult.Pose pose = LoosePartShapeRecognizer.estimatePose(observation.corners,
                observation.widthMm, observation.heightMm, request.getCameraCalibration());
        if (request.getCameraCalibration() != null && pose == null) return primary;
        double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
        RecognitionResult.Latency base = primary == null
                ? RecognitionResult.Latency.zero() : primary.getLatency();
        RecognitionResult.Latency latency = new RecognitionResult.Latency(
                base.getFeatureExtractionMs(), base.getMatchingMs(),
                base.getSpatialValidationMs(), base.getCircularFallbackMs() + elapsedMs,
                base.getTotalMs() + elapsedMs);
        Profile profile = observation.profile;
        return RecognitionResult.builder(frameTimestampNanos)
                .identity(profile.referenceId, profile.componentId, profile.displayName)
                .method(RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK)
                .validation(RecognitionResult.ValidationState.EXPECTED_COMPONENT)
                .orientation(RecognitionResult.OrientationClassification.UNKNOWN, Double.NaN)
                .polarity(RecognitionResult.PolarityState.NOT_REQUIRED)
                .matches(primary == null ? 0 : primary.getFrameKeypointCount(), 0, 0, 0.0)
                .confidence(observation.confidence)
                .corners(observation.corners)
                .instances(Collections.singletonList(new RecognitionResult.InstanceObservation(
                        profile.referenceId, profile.componentId,
                        RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK,
                        observation.corners, observation.confidence)))
                .pose(pose)
                .spatialAnchor(board)
                .latency(latency)
                .build();
    }

    /**
     * Returns the expected module's outline, or {@code null} when the board view is unusable or
     * the slot does not hold the module. {@code rgb} must use RGB channel order and the same
     * pixel coordinates as {@code boardCorners} (canonical TL, TR, BR, BL of the top face).
     */
    Observation inspect(Mat rgb, List<VisionPoint> boardCorners, String componentId) {
        Profile profile = profileFor(componentId);
        if (profile == null) return null;
        if (MotorAssemblyInspector.checkGeometry(rgb, boardCorners)
                != MotorAssemblyObservation.Quality.GOOD) {
            return null;
        }
        MatOfPoint2f source = new MatOfPoint2f(toPoints(boardCorners));
        MatOfPoint2f target = new MatOfPoint2f(new Point(PAD, PAD),
                new Point(PAD + WIDTH - 1, PAD), new Point(PAD + WIDTH - 1, PAD + HEIGHT - 1),
                new Point(PAD, PAD + HEIGHT - 1));
        Mat homography = new Mat();
        Mat inverse = new Mat();
        Mat board = new Mat();
        Mat gray = new Mat();
        Mat lab = new Mat();
        Mat hsv = new Mat();
        Mat blue = new Mat();
        Mat green = new Mat();
        Mat candidate = new Mat();
        Mat windowed = new Mat();
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        Mat component = new Mat();
        Mat selected = new Mat();
        try {
            homography.release();
            homography = Imgproc.getPerspectiveTransform(source, target);
            if (Core.invert(homography, inverse) == 0) return null;
            Imgproc.warpPerspective(rgb, board, homography,
                    new Size(WIDTH + 2 * PAD, HEIGHT + 2 * PAD), Imgproc.INTER_LINEAR,
                    Core.BORDER_CONSTANT, Scalar.all(0));
            Imgproc.cvtColor(board, gray, Imgproc.COLOR_RGB2GRAY);
            if (MotorAssemblyInspector.checkImageQuality(gray)
                    != MotorAssemblyObservation.Quality.GOOD) {
                return null;
            }
            Imgproc.cvtColor(board, lab, Imgproc.COLOR_RGB2Lab);
            double[] white = boardWhite(lab);
            if (white == null) return null;
            relativeBlue(lab, white, blue);
            Imgproc.cvtColor(board, hsv, Imgproc.COLOR_RGB2HSV);
            Core.inRange(hsv, GREEN_LOW, GREEN_HIGH, green);
            if (profile.minGreenArea > 0.0) Core.bitwise_or(blue, green, candidate);
            else blue.copyTo(candidate);

            Rect window = canonicalRect(profile.minU, profile.minV,
                    profile.maxU - profile.minU, profile.maxV - profile.minV);
            windowed.create(candidate.size(), CvType.CV_8UC1);
            windowed.setTo(Scalar.all(0));
            Mat windowSource = candidate.submat(window);
            Mat destination = windowed.submat(window);
            try {
                windowSource.copyTo(destination);
            } finally {
                destination.release();
                windowSource.release();
            }
            // Closing re-joins the PCB around its black ICs, white labels and header pins.
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(9, 9));
            try {
                Imgproc.morphologyEx(windowed, windowed, Imgproc.MORPH_CLOSE, kernel);
            } finally {
                kernel.release();
            }
            int count = Imgproc.connectedComponentsWithStats(windowed, labels, stats,
                    centroids, 8, CvType.CV_32S);
            int best = -1;
            int bestArea = 0;
            for (int index = 1; index < count; index++) {
                int area = (int) stats.get(index, Imgproc.CC_STAT_AREA)[0];
                if (area > bestArea) {
                    bestArea = area;
                    best = index;
                }
            }
            if (best < 0) return null;
            Rect bounds = new Rect((int) stats.get(best, Imgproc.CC_STAT_LEFT)[0],
                    (int) stats.get(best, Imgproc.CC_STAT_TOP)[0],
                    (int) stats.get(best, Imgproc.CC_STAT_WIDTH)[0],
                    (int) stats.get(best, Imgproc.CC_STAT_HEIGHT)[0]);
            double width = bounds.width / (double) WIDTH;
            double height = bounds.height / (double) HEIGHT;
            if (width < profile.minWidth || width > profile.maxWidth
                    || height < profile.minHeight || height > profile.maxHeight) {
                return null;
            }
            if (profile.longAxisAlongBoard && height < width * 1.5) return null;

            Core.compare(labels, new Scalar(best), component, Core.CMP_EQ);
            Core.bitwise_and(blue, component, selected);
            double blueArea = Core.countNonZero(selected) / (double) (WIDTH * HEIGHT);
            if (blueArea < profile.minBlueArea) return null;
            double blueCentroidY = centroidY(selected);
            if (profile.minGreenArea > 0.0) {
                Core.bitwise_and(green, component, selected);
                double greenArea = Core.countNonZero(selected) / (double) (WIDTH * HEIGHT);
                // The driver's screw terminals face the bottom (Nano) edge of the board.
                if (greenArea < profile.minGreenArea
                        || !(centroidY(selected) - blueCentroidY
                        >= profile.minGreenBelowBlue * HEIGHT)) {
                    return null;
                }
            }
            // The Nano seats either way round in its symmetric socket rows; only its gold USB
            // shell at the board-bottom end distinguishes the instructed direction.
            if (profile.longAxisAlongBoard && !usbAtBottom(hsv, bounds)) return null;

            List<VisionPoint> corners = project(bounds, inverse);
            // Corner order follows the authored target's rotation: the Nano target is the
            // driver profile turned 90 degrees, so its first edge runs down the board.
            if (profile.longAxisAlongBoard) {
                corners = Arrays.asList(corners.get(1), corners.get(2),
                        corners.get(3), corners.get(0));
            }
            double widthMm = profile.longAxisAlongBoard
                    ? height * BOARD_HEIGHT_MM : width * BOARD_WIDTH_MM;
            double heightMm = profile.longAxisAlongBoard
                    ? width * BOARD_WIDTH_MM : height * BOARD_HEIGHT_MM;
            double fill = bestArea / (double) (bounds.width * bounds.height);
            double confidence = Math.min(0.95, 0.60 + 0.20 * Math.min(1.0, fill)
                    + 0.15 * Math.min(1.0, blueArea / (profile.minBlueArea * 4.0)));
            return new Observation(profile, Collections.unmodifiableList(corners),
                    widthMm, heightMm, confidence);
        } finally {
            selected.release(); component.release(); centroids.release(); stats.release();
            labels.release(); windowed.release(); candidate.release(); green.release();
            blue.release(); hsv.release(); lab.release(); gray.release(); board.release();
            inverse.release(); homography.release(); target.release(); source.release();
        }
    }

    /**
     * Lab a*, b* (offset by 128, as OpenCV stores 8-bit Lab) of the board's white: the median of
     * low-chroma board pixels at or above their median lightness. Null without enough pixels.
     */
    static double[] boardWhite(Mat lab) {
        Rect region = canonicalRect(0.05, 0.03, 0.90, 0.94);
        Mat local = lab.submat(region);
        try {
            int pixels = local.rows() * local.cols();
            byte[] data = new byte[pixels * 3];
            local.get(0, 0, data);
            int[] lightness = new int[256];
            int neutral = 0;
            for (int index = 0; index < pixels; index++) {
                int a = (data[index * 3 + 1] & 0xFF) - 128;
                int b = (data[index * 3 + 2] & 0xFF) - 128;
                if (Math.hypot(a, b) < NEUTRAL_CHROMA) {
                    lightness[data[index * 3] & 0xFF]++;
                    neutral++;
                }
            }
            if (neutral < pixels * 0.05) return null;
            int medianL = percentile(lightness, neutral, 0.5);
            int[] aHistogram = new int[256];
            int[] bHistogram = new int[256];
            int selected = 0;
            for (int index = 0; index < pixels; index++) {
                int l = data[index * 3] & 0xFF;
                int a = data[index * 3 + 1] & 0xFF;
                int b = data[index * 3 + 2] & 0xFF;
                if (l >= medianL && Math.hypot(a - 128, b - 128) < NEUTRAL_CHROMA) {
                    aHistogram[a]++;
                    bHistogram[b]++;
                    selected++;
                }
            }
            if (selected == 0) return null;
            return new double[]{percentile(aHistogram, selected, 0.5),
                    percentile(bHistogram, selected, 0.5)};
        } finally {
            local.release();
        }
    }

    /** Pixels bluer than the board's white with similar a*, excluding near-black shadows. */
    static void relativeBlue(Mat lab, double[] white, Mat destination) {
        int aLow = (int) Math.floor(white[0] - A_TOLERANCE) + 1;
        int aHigh = (int) Math.ceil(white[0] + A_TOLERANCE) - 1;
        int bHigh = (int) Math.ceil(white[1] - BLUE_OFFSET) - 1;
        Core.inRange(lab, new Scalar(MINIMUM_LIGHTNESS, aLow, 0),
                new Scalar(255, aHigh, Math.max(-1, bHigh)), destination);
    }

    private static int percentile(int[] histogram, int total, double fraction) {
        long target = (long) Math.ceil(total * fraction);
        long running = 0;
        for (int value = 0; value < histogram.length; value++) {
            running += histogram[value];
            if (running >= target) return value;
        }
        return histogram.length - 1;
    }

    /** Gold USB shell in a band just below the outline, and none in the band above it. */
    static boolean usbAtBottom(Mat hsv, Rect bounds) {
        int band = Math.max(4, (int) Math.round(HEIGHT * USB_BAND_HEIGHT));
        int x = bounds.x + (int) Math.round(bounds.width * 0.20);
        int width = Math.max(1, (int) Math.round(bounds.width * 0.60));
        double below = goldFraction(hsv, new Rect(x, bounds.y + bounds.height, width, band));
        double above = goldFraction(hsv, new Rect(x, bounds.y - band, width, band));
        return below >= MIN_USB_GOLD && below >= above + USB_GOLD_MARGIN;
    }

    private static double goldFraction(Mat hsv, Rect region) {
        int left = Math.max(0, region.x);
        int top = Math.max(0, region.y);
        int right = Math.min(hsv.cols(), region.x + region.width);
        int bottom = Math.min(hsv.rows(), region.y + region.height);
        if (right <= left || bottom <= top) return 0.0;
        Rect clipped = new Rect(left, top, right - left, bottom - top);
        Mat local = hsv.submat(clipped);
        Mat gold = new Mat();
        try {
            Core.inRange(local, GOLD_LOW, GOLD_HIGH, gold);
            return Core.countNonZero(gold) / (double) clipped.area();
        } finally {
            gold.release();
            local.release();
        }
    }

    private static Rect canonicalRect(double u, double v, double width, double height) {
        return new Rect((int) Math.round(PAD + u * WIDTH), (int) Math.round(PAD + v * HEIGHT),
                (int) Math.round(width * WIDTH), (int) Math.round(height * HEIGHT));
    }

    private static double centroidY(Mat mask) {
        Moments moments = Imgproc.moments(mask, true);
        return moments.m00 <= 0.0 ? Double.NaN : moments.m01 / moments.m00;
    }

    private static List<VisionPoint> project(Rect bounds, Mat inverse) {
        MatOfPoint2f source = new MatOfPoint2f(new Point(bounds.x, bounds.y),
                new Point(bounds.x + bounds.width, bounds.y),
                new Point(bounds.x + bounds.width, bounds.y + bounds.height),
                new Point(bounds.x, bounds.y + bounds.height));
        MatOfPoint2f target = new MatOfPoint2f();
        try {
            Core.perspectiveTransform(source, target, inverse);
            Point[] points = target.toArray();
            return Arrays.asList(new VisionPoint(points[0].x, points[0].y),
                    new VisionPoint(points[1].x, points[1].y),
                    new VisionPoint(points[2].x, points[2].y),
                    new VisionPoint(points[3].x, points[3].y));
        } finally {
            target.release();
            source.release();
        }
    }

    private static Point[] toPoints(List<VisionPoint> corners) {
        Point[] points = new Point[4];
        for (int i = 0; i < points.length; i++) {
            points[i] = new Point(corners.get(i).getX(), corners.get(i).getY());
        }
        return points;
    }
}
