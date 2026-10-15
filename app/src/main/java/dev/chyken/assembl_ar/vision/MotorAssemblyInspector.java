package dev.chyken.assembl_ar.vision;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static dev.chyken.assembl_ar.vision.MotorAssemblyObservation.Quality;
import static dev.chyken.assembl_ar.vision.MotorAssemblyObservation.Slot;
import static dev.chyken.assembl_ar.vision.MotorAssemblyObservation.SlotState;

/**
 * A deliberately narrow validator for the two yellow NEOBOT chassis gear motors in step two.
 * The caller supplies the main-board top-face corners in canonical TL, TR, BR, BL order.
 * Rectification supplies assembly coordinates, not a planar motor pose: the raised motors may
 * protrude outside the board, and their visible footprint is allowed a modest perspective margin.
 *
 * This heuristic checks the yellow housing, footprint and adjacent textured neutral metal can.
 * It cannot establish screw tension, correct shaft side, terminal polarity or wire attachment.
 * Those properties must remain in the physical check. An unfamiliar motor color needs its own
 * calibrated appearance model; lowering the color threshold is not a safe substitute.
 */
public final class MotorAssemblyInspector {
    private static final int WIDTH = 384;
    private static final int HEIGHT = 456;
    private static final int PAD = 96;
    // Calibrated projected-edge view gate, independent of physical pose dimensions. Keep its
    // original numerical window when correcting the board's millimetre registration.
    private static final double CALIBRATED_VIEW_ASPECT = 0.8433333333333334;

    /** Input must be RGB (not OpenCV's conventional BGR), with corners in the same pixel space. */
    public MotorAssemblyObservation inspect(Mat rgb, List<VisionPoint> boardCorners) {
        Quality geometry = checkGeometry(rgb, boardCorners);
        if (geometry != Quality.GOOD) return MotorAssemblyObservation.unavailable(geometry);

        MatOfPoint2f source = new MatOfPoint2f(toPoints(boardCorners));
        MatOfPoint2f target = new MatOfPoint2f(new Point(PAD, PAD), new Point(PAD+WIDTH - 1, PAD),
                new Point(PAD+WIDTH - 1, PAD+HEIGHT - 1), new Point(PAD, PAD+HEIGHT - 1));
        Mat homography = new Mat();
        Mat inverse = new Mat();
        Mat board = new Mat();
        Mat gray = new Mat();
        Mat hsv = new Mat();
        Mat yellow = new Mat();
        Mat kernel = new Mat();
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            homography.release();
            homography = Imgproc.getPerspectiveTransform(source, target);
            if (Core.invert(homography, inverse) == 0) {
                return MotorAssemblyObservation.unavailable(Quality.ANCHOR_UNAVAILABLE);
            }
            // Raised housings extend beyond the projected PCB edge in angled views. Keep
            // those real camera pixels instead of clipping the motor at the board boundary.
            Imgproc.warpPerspective(rgb, board, homography, new Size(WIDTH+2*PAD, HEIGHT+2*PAD),
                    Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar.all(0));
            Imgproc.cvtColor(board, gray, Imgproc.COLOR_RGB2GRAY);
            Quality quality = checkImageQuality(gray);
            if (quality != Quality.GOOD) return MotorAssemblyObservation.unavailable(quality);
            Imgproc.cvtColor(board, hsv, Imgproc.COLOR_RGB2HSV);
            // Green AR reticles and the red/black leads must not become motor evidence.
            Core.inRange(hsv, new Scalar(17, 90, 70), new Scalar(42, 255, 255), yellow);
            kernel.release();
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Imgproc.morphologyEx(yellow, yellow, Imgproc.MORPH_OPEN, kernel);
            Imgproc.morphologyEx(yellow, yellow, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.findContours(yellow, contours, hierarchy, Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE);
            return new MotorAssemblyObservation(Quality.GOOD,
                    inspectSlot(true, contours, hsv, gray, inverse),
                    inspectSlot(false, contours, hsv, gray, inverse));
        } finally {
            for (MatOfPoint contour : contours) contour.release();
            hierarchy.release(); kernel.release(); yellow.release(); hsv.release(); gray.release();
            board.release(); inverse.release(); homography.release(); target.release(); source.release();
        }
    }

    private static Slot inspectSlot(boolean left, List<MatOfPoint> contours,
                                    Mat hsv, Mat gray, Mat inverse) {
        Rect bounds = null;
        double largestArea = WIDTH * HEIGHT * 0.009;
        for (MatOfPoint contour : contours) {
            Rect candidate = Imgproc.boundingRect(contour);
            double centerX = (candidate.x + candidate.width * 0.5 - PAD) / WIDTH;
            if (left != (centerX < 0.5)) continue;
            double area = Imgproc.contourArea(contour);
            if (area > largestArea) {
                largestArea = area;
                bounds = candidate;
            }
        }
        String side = left ? "Left" : "Right";
        if (bounds == null) return Slot.missing(side + " motor not yet visible");
        // The black mounting bracket divides one yellow housing into separate contours.
        // Join vertically adjacent, overlapping fragments on this side only. Do not treat
        // a single lower housing fragment as the entire motor or reuse the opposite motor.
        java.util.Set<MatOfPoint> included = new java.util.HashSet<>();
        for (MatOfPoint contour : contours) {
            if (Imgproc.boundingRect(contour).equals(bounds)) included.add(contour);
        }
        for (int pass=0;pass<3;pass++) for (MatOfPoint contour:contours) {
            if(included.contains(contour)) continue;
            Rect part=Imgproc.boundingRect(contour);
            double partArea=Imgproc.contourArea(contour);
            if(partArea<WIDTH*HEIGHT*.0025 || left!=((part.x+part.width*.5-PAD)/WIDTH<.5)) continue;
            int overlap=Math.min(bounds.x+bounds.width,part.x+part.width)-Math.max(bounds.x,part.x);
            int gap=Math.max(part.y-(bounds.y+bounds.height),bounds.y-(part.y+part.height));
            int x=Math.min(bounds.x,part.x),y=Math.min(bounds.y,part.y);
            int w=Math.max(bounds.x+bounds.width,part.x+part.width)-x;
            int h=Math.max(bounds.y+bounds.height,part.y+part.height)-y;
            if(overlap<Math.min(bounds.width,part.width)*.4 || gap>HEIGHT*.22
                    || w>WIDTH*.52 || h>HEIGHT*.70) continue;
            bounds=new Rect(x,y,w,h);largestArea+=partArea;included.add(contour);
        }
        double width = bounds.width / (double) WIDTH;
        double height = bounds.height / (double) HEIGHT;
        double centerX = (bounds.x + bounds.width * 0.5 - PAD) / WIDTH;
        double centerY = (bounds.y + bounds.height * 0.5 - PAD) / HEIGHT;
        double aspect = bounds.height / (double) bounds.width;
        double fill = largestArea / (bounds.width * (double) bounds.height);
        List<VisionPoint> corners = project(bounds, inverse);
        boolean position = left
                ? centerX >= -.20 && centerX <= 0.29 && centerY >= 0.38 && centerY <= 0.91
                : centerX >= 0.71 && centerX <= 1.20 && centerY >= 0.38 && centerY <= 0.91;
        boolean footprint = width >= 0.13 && width <= 0.50 && height >= 0.27 && height <= 0.70
                && aspect >= 1.2 && aspect <= 3.8 && fill >= 0.35;
        if (!position || !footprint) {
            return new Slot(SlotState.MISALIGNED, 0.25,
                    side + " motor is outside its expected mounting area", corners);
        }

        double above = metalCanEvidence(hsv, gray, bounds, true);
        if (above < 0.55) {
            double below = metalCanEvidence(hsv, gray, bounds, false);
            if (below >= 0.55) {
                return new Slot(SlotState.MISALIGNED, below,
                        side + " motor appears reversed; check the metal-can end", corners);
            }
            return new Slot(SlotState.ORIENTATION_UNCONFIRMED, 0.35,
                    "Show the silver metal can above the " + side.toLowerCase(java.util.Locale.ROOT)
                            + " yellow gearbox", corners);
        }
        Rect complete = new Rect(bounds.x, Math.max(0, bounds.y - (int) (bounds.width * 0.9)),
                bounds.width, bounds.height + Math.min(bounds.y, (int) (bounds.width * 0.9)));
        double confidence = Math.min(0.96, 0.65 + 0.15 * fill + 0.15 * above);
        return new Slot(SlotState.PRESENT, confidence,
                side + " motor body aligned; check shaft and wires physically", project(complete, inverse));
    }

    /** A flat yellow patch alone has neither the neutral can nor its shaded edges/end cap. */
    private static double metalCanEvidence(Mat hsv, Mat gray, Rect gearbox, boolean above) {
        int height = (int) Math.round(gearbox.width * 0.9);
        int y = above ? gearbox.y - height : gearbox.y + gearbox.height;
        int x = gearbox.x + (int) (gearbox.width * 0.15);
        int width = Math.max(1, (int) (gearbox.width * 0.70));
        if (y < 0 || y + height > hsv.rows() || height < 12) return 0.0;
        Rect region = new Rect(x, y, Math.min(width, hsv.cols() - x), height);
        Mat localHsv = hsv.submat(region);
        Mat localGray = gray.submat(region);
        Mat neutral = new Mat();
        Mat dark = new Mat();
        Mat edges = new Mat();
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble deviation = new MatOfDouble();
        try {
            Core.inRange(localHsv, new Scalar(0, 0, 55), new Scalar(179, 90, 235), neutral);
            Imgproc.threshold(localGray, dark, 75, 255, Imgproc.THRESH_BINARY_INV);
            Imgproc.Canny(localGray, edges, 45, 110);
            Core.meanStdDev(localGray, mean, deviation);
            double pixels = region.area();
            double neutralRatio = Core.countNonZero(neutral) / pixels;
            double darkRatio = Core.countNonZero(dark) / pixels;
            double edgeRatio = Core.countNonZero(edges) / pixels;
            double contrast = deviation.toArray()[0];
            if (neutralRatio < 0.30 || darkRatio < 0.025 || darkRatio > 0.72
                    || edgeRatio < 0.045 || contrast < 22.0) return 0.0;
            return Math.min(1.0, 0.45 + 0.30 * neutralRatio
                    + Math.min(0.15, edgeRatio) + Math.min(0.10, contrast / 600.0));
        } finally {
            deviation.release(); mean.release(); edges.release(); dark.release(); neutral.release();
            localGray.release(); localHsv.release();
        }
    }

    static Quality checkImageQuality(Mat gray) {
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble deviation = new MatOfDouble();
        Mat laplacian = new Mat();
        // Central board details are more useful for focus than the high-contrast outer silhouette.
        Mat board = gray.submat(new Rect(PAD,PAD,WIDTH,HEIGHT));
        Mat center = gray.submat(new Rect(PAD+(int) (WIDTH * 0.36), PAD+(int) (HEIGHT * 0.08),
                (int) (WIDTH * 0.28), (int) (HEIGHT * 0.84)));
        try {
            Core.meanStdDev(board, mean, deviation);
            if (mean.toArray()[0] < 35.0) return Quality.TOO_DARK;
            if (mean.toArray()[0] > 238.0 && deviation.toArray()[0] < 24.0) return Quality.GLARE;
            Imgproc.Laplacian(center, laplacian, CvType.CV_64F);
            Core.meanStdDev(laplacian, mean, deviation);
            double sigma = deviation.toArray()[0];
            return sigma * sigma < 32.0 ? Quality.BLURRED : Quality.GOOD;
        } finally {
            board.release(); center.release(); laplacian.release(); deviation.release(); mean.release();
        }
    }

    static Quality checkGeometry(Mat rgb, List<VisionPoint> corners) {
        if (rgb == null || rgb.empty() || rgb.type() != CvType.CV_8UC3
                || corners == null || corners.size() != 4 || corners.contains(null)) {
            return Quality.ANCHOR_UNAVAILABLE;
        }
        double area = 0;
        double sign = 0;
        for (int i = 0; i < 4; i++) {
            VisionPoint a = corners.get(i), b = corners.get((i + 1) % 4), c = corners.get((i + 2) % 4);
            if (a.getX() < 0 || a.getY() < 0 || a.getX() >= rgb.cols() || a.getY() >= rgb.rows()) {
                return Quality.ANCHOR_UNAVAILABLE;
            }
            double cross = (b.getX() - a.getX()) * (c.getY() - b.getY())
                    - (b.getY() - a.getY()) * (c.getX() - b.getX());
            if (Math.abs(cross) < 1 || (i > 0 && cross * sign < 0)) return Quality.ANCHOR_UNAVAILABLE;
            sign = cross;
            area += a.getX() * b.getY() - a.getY() * b.getX();
        }
        // A mirrored underside is not the installed top face and must never swap left/right slots.
        if (area <= 0) return Quality.ANCHOR_UNAVAILABLE;
        double top = corners.get(0).distanceTo(corners.get(1));
        double bottom = corners.get(3).distanceTo(corners.get(2));
        double left = corners.get(0).distanceTo(corners.get(3));
        double right = corners.get(1).distanceTo(corners.get(2));
        double ratio = ((top + bottom) / (left + right)) / CALIBRATED_VIEW_ASPECT;
        if (Math.min(Math.min(top, bottom), Math.min(left, right)) < 96) return Quality.ANCHOR_UNAVAILABLE;
        if (ratio < 0.50 || ratio > 1.85 || Math.max(top, bottom) / Math.min(top, bottom) > 2.2
                || Math.max(left, right) / Math.min(left, right) > 2.2) return Quality.VIEW_TOO_OBLIQUE;
        return Quality.GOOD;
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
                    new VisionPoint(points[1].x, points[1].y), new VisionPoint(points[2].x, points[2].y),
                    new VisionPoint(points[3].x, points[3].y));
        } finally {
            target.release(); source.release();
        }
    }

    private static Point[] toPoints(List<VisionPoint> corners) {
        Point[] points = new Point[4];
        for (int i = 0; i < points.length; i++) points[i] = new Point(corners.get(i).getX(), corners.get(i).getY());
        return points;
    }
}
