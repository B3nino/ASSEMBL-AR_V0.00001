package dev.chyken.assembl_ar.vision;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate;

/**
 * Shape evidence for the printed motor bracket, independent of its changing surface highlights.
 * The nominal Blender model and original photos establish a T profile with three parallel slots:
 * two on one column and a third staggered between them on the other. Proportions tolerate the
 * observed model/photo difference and moderate foreshortening. No rendered texture is a reference.
 * Supplies single-part or same-frame set localization; orientation remains unverified.
 */
final class MotorBracketShapeRecognizer {
    static final double WIDTH_MM = 32.0;
    static final double HEIGHT_MM = 48.0;
    private static final int MAX_DIMENSION = 1280;
    private static final int PROFILE_WIDTH = 100;
    private static final int PROFILE_HEIGHT = 150;
    private static final double[] THRESHOLDS = {50, 62, 84, 108, 135, 160, 185, 210};

    static final class Candidate {
        final List<VisionPoint> corners;
        final double confidence;
        final Point center;
        final double height;
        final boolean installedProfile;

        Candidate(Axes axes, double scale, double confidence) {
            this(axes, scale, confidence, false);
        }

        Candidate(Axes axes, double scale, double confidence, boolean installedProfile) {
            this.confidence = confidence;
            this.installedProfile = installedProfile;
            center = axes.center;
            height = axes.height;
            corners = Arrays.asList(axes.corner(0, 0, scale), axes.corner(1, 0, scale),
                    axes.corner(1, 1, scale), axes.corner(0, 1, scale));
        }
    }

    static Candidate find(Mat grayscale, Mat rgb, double minimumAreaRatio) {
        return find(grayscale, rgb, minimumAreaRatio, null);
    }

    /** Filters only fully recognized silhouettes; the target region cannot create identity evidence. */
    static Candidate find(Mat grayscale, Mat rgb, double minimumAreaRatio,
                          Predicate<List<VisionPoint>> placementAccepted) {
        List<Candidate> candidates = findAll(grayscale, rgb, minimumAreaRatio, placementAccepted);
        // Single-part tests retain their ambiguity rule; quantity requests explicitly opt in.
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * Edge-on plates expose no three-slot face. This path is available only while the guided
     * installation has both a board workplane and an active slot. It requires a long neutral
     * silhouette occupying the physical guide lane and its seating band; no unanchored dark
     * object can become a bracket identity. Loose inventory continues through {@link #findAll}.
     */
    static Candidate findInstalledProfile(Mat grayscale, Mat rgb, double minimumAreaRatio,
                                          List<VisionPoint> boardCorners, int slot,
                                          Predicate<List<VisionPoint>> placementAccepted) {
        if (grayscale == null || grayscale.empty() || rgb == null || rgb.empty()
                || boardCorners == null || boardCorners.size() != 4 || slot < 1 || slot > 4
                || placementAccepted == null) return null;
        double scale = Math.min(1.0, MAX_DIMENSION / (double)Math.max(grayscale.cols(), grayscale.rows()));
        double boardWidth = (boardCorners.get(0).distanceTo(boardCorners.get(1))
                + boardCorners.get(3).distanceTo(boardCorners.get(2))) * scale * .5;
        double boardHeight = (boardCorners.get(0).distanceTo(boardCorners.get(3))
                + boardCorners.get(1).distanceTo(boardCorners.get(2))) * scale * .5;
        double boardArea = Math.max(1.0, boardWidth * boardHeight);
        if (boardWidth < 50 || boardHeight < 50) return null;
        double laneX = ChassisRelativePlacementGate.motorBracketSlotX(slot);
        Mat gray = new Mat(), blurred = new Mat(), binary = new Mat(), hierarchy = new Mat();
        Mat laneMask = new Mat(), baseMask = new Mat(), colors = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3));
        List<MatOfPoint> contours = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        try {
            Imgproc.resize(grayscale, gray, new Size(), scale, scale, Imgproc.INTER_AREA);
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
            laneMask = projectedBand(gray.size(), boardCorners, scale,
                    laneX - .125, laneX + .125, -.25, 1.0);
            baseMask = projectedBand(gray.size(), boardCorners, scale,
                    laneX - .125, laneX + .125, .48, .85);
            if (Core.countNonZero(laneMask) == 0 || Core.countNonZero(baseMask) == 0) return null;
            colors = kitColorMask(rgb, gray.size());
            double minArea = Math.max(gray.total() * minimumAreaRatio, 160.0);
            for (double level : THRESHOLDS) {
                if (level > 108) break; // bright board printing merges separate objects at high levels
                Imgproc.threshold(blurred, binary, level, 255, Imgproc.THRESH_BINARY_INV);
                Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel);
                Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel);
                Core.bitwise_and(binary, laneMask, binary);
                Imgproc.findContours(binary, contours, hierarchy,
                        Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE);
                for (int index = 0; index < contours.size(); index++) {
                    double[] link = hierarchy.get(0, index);
                    if (link == null || link[3] >= 0) continue;
                    MatOfPoint contour = contours.get(index);
                    double area = Imgproc.contourArea(contour);
                    if (area < minArea || area > boardArea * .06) continue;
                    MatOfPoint2f points = new MatOfPoint2f(contour.toArray());
                    RotatedRect box;
                    try { box = Imgproc.minAreaRect(points); }
                    finally { points.release(); }
                    Axes axes = new Axes(box);
                    double aspect = axes.height / Math.max(1.0, axes.width);
                    double fill = area / Math.max(1.0, axes.height * axes.width);
                    if (axes.width < 6 || axes.width > boardWidth * .24
                            || axes.height < boardHeight * .16
                            || axes.height > boardHeight * .60
                            || aspect < 1.7 || aspect > 9.0 || fill < .35) continue;
                    Mat material = Mat.zeros(gray.size(), CvType.CV_8UC1);
                    int basePixels;
                    try {
                        Imgproc.drawContours(material, contours, index, Scalar.all(255), -1);
                        Core.bitwise_and(material, baseMask, material);
                        basePixels = Core.countNonZero(material);
                    } finally { material.release(); }
                    if (basePixels < Math.max(250.0, area * .35)) continue;
                    if (!neutralMaterial(colors, contour, children(contours, hierarchy, index))) continue;
                    Candidate found = new Candidate(axes, scale, .82, true);
                    if (!placementAccepted.test(found.corners)) continue;
                    int duplicate = -1;
                    for (int prior = 0; prior < candidates.size(); prior++) {
                        if (!ComponentRecognizer.areDistinctInstanceQuads(
                                candidates.get(prior).corners, found.corners)) {
                            duplicate = prior;
                            break;
                        }
                    }
                    if (duplicate < 0) candidates.add(found);
                }
                for (MatOfPoint contour : contours) contour.release();
                contours.clear();
            }
            return candidates.size() == 1 ? candidates.get(0) : null;
        } finally {
            for (MatOfPoint contour : contours) contour.release();
            kernel.release(); colors.release(); baseMask.release(); laneMask.release();
            hierarchy.release(); binary.release(); blurred.release(); gray.release();
        }
    }

    private static Mat projectedBand(Size size, List<VisionPoint> boardCorners, double scale,
                                     double left, double right, double top, double bottom) {
        Mat mask = Mat.zeros(size, CvType.CV_8UC1);
        VisionPoint[] projected = {
                PlanarCoordinateMapper.projectUnitSquare(boardCorners, new VisionPoint(left, top)),
                PlanarCoordinateMapper.projectUnitSquare(boardCorners, new VisionPoint(right, top)),
                PlanarCoordinateMapper.projectUnitSquare(boardCorners, new VisionPoint(right, bottom)),
                PlanarCoordinateMapper.projectUnitSquare(boardCorners, new VisionPoint(left, bottom))};
        for (VisionPoint point : projected) if (point == null) return mask;
        MatOfPoint polygon = new MatOfPoint(new Point(projected[0].getX() * scale,
                projected[0].getY() * scale), new Point(projected[1].getX() * scale,
                projected[1].getY() * scale), new Point(projected[2].getX() * scale,
                projected[2].getY() * scale), new Point(projected[3].getX() * scale,
                projected[3].getY() * scale));
        try { Imgproc.fillPoly(mask, Arrays.asList(polygon), Scalar.all(255)); }
        finally { polygon.release(); }
        return mask;
    }

    static List<Candidate> findAll(Mat grayscale, Mat rgb, double minimumAreaRatio,
                                  Predicate<List<VisionPoint>> placementAccepted) {
        double scale = Math.min(1.0, MAX_DIMENSION / (double)Math.max(grayscale.cols(), grayscale.rows()));
        Mat gray = new Mat(), blurred = new Mat(), binary = new Mat(), hierarchy = new Mat();
        Mat colors = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3));
        List<MatOfPoint> contours = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        try {
            Imgproc.resize(grayscale, gray, new Size(), scale, scale, Imgproc.INTER_AREA);
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);
            colors.release();
            colors = kitColorMask(rgb, gray.size());
            double frameArea = gray.total();
            // Scan every level even after success: multiple real brackets must not become an
            // arbitrary single-part lock just because one reflects more light than another.
            for (double level : THRESHOLDS) {
                Imgproc.threshold(blurred, binary, level, 255, Imgproc.THRESH_BINARY_INV);
                Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel);
                Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel);
                Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE);
                for (int index = 0; index < contours.size(); index++) {
                    double[] link = hierarchy.get(0, index);
                    if (link == null || link[3] >= 0) continue;
                    MatOfPoint contour = contours.get(index);
                    double area = Imgproc.contourArea(contour);
                    if (area < frameArea * minimumAreaRatio || area > frameArea * .70) continue;
                    MatOfPoint2f points = new MatOfPoint2f(contour.toArray());
                    RotatedRect box;
                    try { box = Imgproc.minAreaRect(points); }
                    finally { points.release(); }
                    Axes axes = new Axes(box);
                    double aspect = axes.height / Math.max(1, axes.width);
                    double fill = area / Math.max(1, axes.width * axes.height);
                    if (axes.width < 28 || aspect < 1.05 || aspect > 2.70 || fill < .42 || fill > .91) continue;
                    double solidity = solidity(contour, area);
                    if (solidity < .54 || solidity > .95) continue;
                    double shoulderEvidence = inspectProfile(contour, axes);
                    if (shoulderEvidence < 0) continue;
                    List<MatOfPoint> holes = children(contours, hierarchy, index);
                    double slots = inspectSlots(holes, axes);
                    if (slots < 0 || !neutralMaterial(colors, contour, holes)) continue;
                    Candidate found = new Candidate(axes, scale, Math.min(.95,
                            .78 + .10 * slots + .07 * shoulderEvidence));
                    if (placementAccepted != null && !placementAccepted.test(found.corners)) continue;
                    int duplicate = -1;
                    for (int prior = 0; prior < candidates.size(); prior++) {
                        if (!ComponentRecognizer.areDistinctInstanceQuads(
                                candidates.get(prior).corners, found.corners)) {
                            duplicate = prior;
                            break;
                        }
                    }
                    if (duplicate < 0) candidates.add(found);
                    else if (found.confidence > candidates.get(duplicate).confidence) {
                        candidates.set(duplicate, found);
                    }
                }
                for (MatOfPoint contour : contours) contour.release();
                contours.clear();
            }
            candidates.sort(Comparator.comparingDouble((Candidate part) -> part.center.x)
                    .thenComparingDouble(part -> part.center.y));
            return Collections.unmodifiableList(candidates);
        } finally {
            for (MatOfPoint contour : contours) contour.release();
            colors.release(); hierarchy.release(); kernel.release(); binary.release();
            blurred.release(); gray.release();
        }
    }

    private static double solidity(MatOfPoint contour, double area) {
        MatOfInt indices = new MatOfInt();
        MatOfPoint hull = new MatOfPoint();
        try {
            Imgproc.convexHull(contour, indices);
            Point[] source = contour.toArray();
            int[] selected = indices.toArray();
            Point[] values = new Point[selected.length];
            for (int i = 0; i < selected.length; i++) values[i] = source[selected[i]];
            hull.fromArray(values);
            return area / Math.max(1, Imgproc.contourArea(hull));
        } finally { hull.release(); indices.release(); }
    }

    /** Canonical profile contains one wide end and a narrower stem, not merely a concave plate. */
    private static double inspectProfile(MatOfPoint contour, Axes axes) {
        Point[] points = contour.toArray();
        for (int i = 0; i < points.length; i++) {
            Point p = axes.normalized(points[i]);
            points[i] = new Point(Math.round(p.x * (PROFILE_WIDTH - 1)), Math.round(p.y * (PROFILE_HEIGHT - 1)));
        }
        Mat silhouette = Mat.zeros(PROFILE_HEIGHT, PROFILE_WIDTH, CvType.CV_8UC1);
        MatOfPoint local = new MatOfPoint(points);
        try {
            Imgproc.fillPoly(silhouette, Arrays.asList(local), Scalar.all(255));
            byte[] pixels = new byte[PROFILE_WIDTH * PROFILE_HEIGHT];
            silhouette.get(0, 0, pixels);
            double[] widths = new double[PROFILE_HEIGHT];
            double[] left = new double[PROFILE_HEIGHT], right = new double[PROFILE_HEIGHT];
            for (int y = 0; y < PROFILE_HEIGHT; y++) {
                int count = 0;
                left[y] = 1;
                for (int x = 0; x < PROFILE_WIDTH; x++) if (pixels[y * PROFILE_WIDTH + x] != 0) {
                    count++;
                    left[y] = Math.min(left[y], x / (double)PROFILE_WIDTH);
                    right[y] = x / (double)PROFILE_WIDTH;
                }
                widths[y] = count / (double)PROFILE_WIDTH;
            }
            if (peakWidth(widths, 0, 42) < peakWidth(widths, 108, 150)) {
                axes.reverse();
                for (int i = 0; i < PROFILE_HEIGHT / 2; i++) {
                    double swap = widths[i]; widths[i] = widths[PROFILE_HEIGHT - 1 - i];
                    widths[PROFILE_HEIGHT - 1 - i] = swap;
                    int opposite = PROFILE_HEIGHT - 1 - i;
                    double priorLeft = left[i], priorRight = right[i];
                    left[i] = .99 - right[opposite]; right[i] = .99 - left[opposite];
                    left[opposite] = .99 - priorRight; right[opposite] = .99 - priorLeft;
                }
            }
            double head = peakWidth(widths, 0, 42);
            double stem = mean(widths, 60, 120);
            double tail = mean(widths, 128, 142);
            if (head < .78 || stem < .42 || stem > .85 || head - stem < .12 || tail < .36 || tail > .86) return -1;
            double headLeft = 1, headRight = 0;
            for (int row = 0; row < 42; row++) {
                headLeft = Math.min(headLeft, left[row]); headRight = Math.max(headRight, right[row]);
            }
            // A one-sided L plate can have the same row widths and holes. Both shoulders must
            // extend beyond the stem; use mean edges to tolerate a slanted crossbar in perspective.
            if (mean(left, 60, 120) - headLeft < .06 || headRight - mean(right, 60, 120) < .06) return -1;
            return Math.min(1, (head - stem) / .30);
        } finally { local.release(); silhouette.release(); }
    }

    private static double peakWidth(double[] widths, int from, int to) {
        double best = 0;
        for (int i = from; i <= to - 5; i++) best = Math.max(best, mean(widths, i, i + 5));
        return best;
    }

    private static double mean(double[] values, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += values[i];
        return sum / (to - from);
    }

    private static List<MatOfPoint> children(List<MatOfPoint> contours, Mat hierarchy, int parent) {
        List<MatOfPoint> holes = new ArrayList<>();
        int child = (int)hierarchy.get(0, parent)[2];
        int remaining=contours.size();
        while (child >= 0 && child < contours.size() && remaining-- > 0) {
            holes.add(contours.get(child));
            child = (int)hierarchy.get(0, child)[0];
        }
        // OpenCV normally emits a forest. Corrupted hierarchy links fail closed.
        return child>=0?Collections.emptyList():holes;
    }

    /** Select a valid triplet among extra highlight contours; never accept two holes as three. */
    private static double inspectSlots(List<MatOfPoint> holes, Axes axes) {
        List<Point> slots = new ArrayList<>();
        for (MatOfPoint hole : holes) {
            double areaRatio = Imgproc.contourArea(hole) / (axes.width * axes.height);
            if (areaRatio < .003 || areaRatio > .13) continue;
            MatOfPoint2f points = new MatOfPoint2f(hole.toArray());
            RotatedRect box;
            try { box = Imgproc.minAreaRect(points); }
            finally { points.release(); }
            double aspect = Math.max(box.size.width, box.size.height) / Math.max(1, Math.min(box.size.width, box.size.height));
            if (aspect < 1.65) continue;
            Point center = axes.normalized(box.center);
            double minX = 1, maxX = 0, minY = 1, maxY = 0;
            for (Point raw : hole.toArray()) {
                Point p = axes.normalized(raw);
                minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
                minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
            }
            double width = maxX - minX, height = maxY - minY;
            if (height < .12 || height > .48 || width > .30 || height < width * 1.3
                    || center.x < .15 || center.x > .85 || center.y < .19 || center.y > .91) continue;
            slots.add(center);
            if (slots.size() > 16) return -1;
        }
        double best = -1;
        for (int i = 0; i < slots.size(); i++) for (int j = i + 1; j < slots.size(); j++) {
            Point a = slots.get(i), b = slots.get(j);
            double drift = Math.abs(a.x - b.x), gap = Math.abs(a.y - b.y);
            if (drift > .12 || gap < .21 || gap > .60) continue;
            for (int k = 0; k < slots.size(); k++) {
                if (k == i || k == j) continue;
                Point c = slots.get(k);
                double separation = Math.abs(c.x - (a.x + b.x) / 2);
                double offset = Math.abs(c.y - (a.y + b.y) / 2);
                if (separation < .13 || separation > .48 || offset > .16
                        || c.y < Math.min(a.y, b.y) + .065 || c.y > Math.max(a.y, b.y) - .065) continue;
                best = Math.max(best, .5 * (1 - drift / .12) + .5 * (1 - offset / .16));
            }
        }
        return best;
    }

    private static Mat kitColorMask(Mat rgb, Size size) {
        Mat result = new Mat();
        if (rgb == null || rgb.empty()) return result;
        Mat resized = new Mat(), hsv = new Mat();
        try {
            Imgproc.resize(rgb, resized, size, 0, 0, Imgproc.INTER_AREA);
            Imgproc.cvtColor(resized, hsv, Imgproc.COLOR_RGB2HSV);
            byte[] pixels = new byte[(int)hsv.total() * 3];
            byte[] mask = new byte[(int)hsv.total()];
            hsv.get(0, 0, pixels);
            for (int i = 0; i < mask.length; i++) {
                int hue = pixels[i * 3] & 255, saturation = pixels[i * 3 + 1] & 255;
                int value = pixels[i * 3 + 2] & 255;
                // Relative saturation alone calls bluish-black reflections blue electronics.
                // S*V/255 approximates absolute RGB chroma: dim neutral plastic stays eligible,
                // while visibly blue/green PCBs and yellow gearboxes retain the existing veto.
                if (saturation * value < 40 * 255) continue;
                boolean blue = hue >= 91 && hue <= 137 && saturation >= 48 && value >= 24;
                boolean green = hue >= 35 && hue <= 90 && saturation >= 62 && value >= 28;
                boolean yellow = hue >= 17 && hue <= 43 && saturation >= 92 && value >= 62;
                if (blue || green || yellow) mask[i] = (byte)255;
            }
            result.create(hsv.rows(), hsv.cols(), CvType.CV_8UC1);
            result.put(0, 0, mask);
            return result;
        } catch (RuntimeException failure) { result.release(); throw failure; }
        finally { hsv.release(); resized.release(); }
    }

    /** Veto colors on the bracket silhouette, excluding its holes and surrounding background. */
    private static boolean neutralMaterial(Mat colors, MatOfPoint contour, List<MatOfPoint> holes) {
        if (colors.empty()) return true;
        Rect bounds = Imgproc.boundingRect(contour);
        Mat mask = Mat.zeros(bounds.height, bounds.width, CvType.CV_8UC1);
        Mat selected = new Mat(), localColors = colors.submat(bounds);
        List<MatOfPoint> translated = new ArrayList<>();
        try {
            translated.add(translate(contour, bounds));
            Imgproc.fillPoly(mask, translated, Scalar.all(255));
            for (MatOfPoint hole : holes) {
                MatOfPoint local = translate(hole, bounds);
                translated.add(local);
                Imgproc.fillPoly(mask, Arrays.asList(local), Scalar.all(0));
            }
            Core.bitwise_and(localColors, mask, selected);
            return Core.countNonZero(selected) / (double)Math.max(1, Core.countNonZero(mask)) < .065;
        } finally {
            for (MatOfPoint points : translated) points.release();
            localColors.release(); selected.release(); mask.release();
        }
    }

    private static MatOfPoint translate(MatOfPoint contour, Rect bounds) {
        Point[] points = contour.toArray();
        for (Point p : points) { p.x -= bounds.x; p.y -= bounds.y; }
        return new MatOfPoint(points);
    }

    private static final class Axes {
        final Point center;
        final double width, height;
        double x, y;

        Axes(RotatedRect box) {
            center = box.center;
            Point[] corners = new Point[4]; box.points(corners);
            Point a = new Point(corners[1].x - corners[0].x, corners[1].y - corners[0].y);
            Point b = new Point(corners[2].x - corners[1].x, corners[2].y - corners[1].y);
            Point major = Math.hypot(a.x, a.y) >= Math.hypot(b.x, b.y) ? a : b;
            height = Math.hypot(major.x, major.y);
            width = Math.min(box.size.width, box.size.height);
            x = major.x / Math.max(1, height); y = major.y / Math.max(1, height);
        }

        void reverse() { x = -x; y = -y; }
        Point normalized(Point p) {
            double dx = p.x - center.x, dy = p.y - center.y;
            return new Point((dx * y - dy * x) / width + .5, (dx * x + dy * y) / height + .5);
        }
        VisionPoint corner(double u, double v, double scale) {
            return new VisionPoint((center.x + (u - .5) * width * y + (v - .5) * height * x) / scale,
                    (center.y - (u - .5) * width * x + (v - .5) * height * y) / scale);
        }
    }
}
