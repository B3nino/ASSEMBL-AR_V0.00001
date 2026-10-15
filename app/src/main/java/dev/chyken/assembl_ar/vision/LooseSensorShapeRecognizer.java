package dev.chyken.assembl_ar.vision;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Loose HC-SR04 and HW-311 evidence, calibrated on odd phone scans only. The ultrasonic module
 * needs two equally sized textured disks, bright neutral rims and the blue PCB between them.
 * The line tracker needs a narrow dark body and two separated blue regions, including a neutral
 * potentiometer face near its center. A dark strip or a blue object alone is insufficient.
 * Callers retain the expected-part, finite-pose and stability gates.
 */
final class LooseSensorShapeRecognizer {
    static final class Candidate {
        final List<VisionPoint> corners;
        final double confidence;
        final double widthMm;
        final double heightMm;

        Candidate(List<VisionPoint> corners, double confidence, double widthMm, double heightMm) {
            this.corners = corners;
            this.confidence = confidence;
            this.widthMm = widthMm;
            this.heightMm = heightMm;
        }
    }

    static Candidate findUltrasonic(Mat rgb, double minimumAreaRatio) {
        minimumAreaRatio = Math.max(.002, minimumAreaRatio);
        if (!compatible(rgb)) return null;
        Mat working = LooseLighting.normalizeExposure(rgb);
        Mat gray = new Mat(), hsv = new Mat(), blur = new Mat(), blue = new Mat(), contrastBlue = new Mat();
        Mat threshold = new Mat(), hierarchy = new Mat(), mask = new Mat(), selected = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        List<MatOfPoint> contours = new ArrayList<>();
        List<Disk> disks = new ArrayList<>();
        try {
            Imgproc.cvtColor(working, gray, Imgproc.COLOR_RGB2GRAY);
            Imgproc.cvtColor(working, hsv, Imgproc.COLOR_RGB2HSV);
            Imgproc.GaussianBlur(gray, blur, new Size(3, 3), .7);
            Core.inRange(hsv, new Scalar(75, 55, 24), new Scalar(135, 255, 255), blue);
            Pixels pixels = new Pixels(gray, hsv);
            for (double level : new double[]{65, 90, 115, 140}) {
                Imgproc.threshold(blur, threshold, level - 1, 255, Imgproc.THRESH_BINARY_INV);
                Imgproc.morphologyEx(threshold, threshold, Imgproc.MORPH_CLOSE, kernel);
                Imgproc.findContours(threshold, contours, hierarchy,
                        Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
                for (MatOfPoint contour : contours) {
                    double area = Imgproc.contourArea(contour);
                    if (area < 180 || area > gray.total() * .025) continue;
                    MatOfPoint2f points = new MatOfPoint2f(contour.toArray());
                    Point center = new Point();
                    float[] radius = new float[1];
                    try {
                        double perimeter = Imgproc.arcLength(points, true);
                        if (perimeter <= 0 || 4 * Math.PI * area / (perimeter * perimeter) < .73) continue;
                        Imgproc.minEnclosingCircle(points, center, radius);
                    } finally {
                        points.release();
                    }
                    double r = radius[0];
                    if (r < 8 || area / (Math.PI * r * r) < .71) continue;
                    double[] stats = pixels.circleStats(center, r);
                    if (stats == null || stats[0] > stats[2] * .80 || stats[1] < 13 || stats[2] < 90
                            || stats[3] > 110 || stats[2] - stats[0] < 20) continue;
                    boolean duplicate = false;
                    for (Disk disk : disks) {
                        if (distance(center, disk.center) < Math.min(r, disk.radius) * .5) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (!duplicate) disks.add(new Disk(center, r, stats[2]));
                }
                releaseContours(contours);
            }
            Candidate best = null;
            Point bestCenter = null;
            for (int i = 0; i < disks.size(); i++) {
                Disk a = disks.get(i);
                for (int j = i + 1; j < disks.size(); j++) {
                    Disk b = disks.get(j);
                    double radius = (a.radius + b.radius) * .5;
                    double separation = distance(a.center, b.center);
                    if (Math.max(a.radius, b.radius) / Math.min(a.radius, b.radius) > 1.35
                            || separation / radius < 3.2 || separation / radius > 5.0) continue;
                    Point direction = positiveAxis(new Point((b.center.x - a.center.x) / separation,
                            (b.center.y - a.center.y) / separation));
                    Point center = new Point((a.center.x + b.center.x) * .5,
                            (a.center.y + b.center.y) * .5);
                    double major = separation + radius * 2.65;
                    double minor = radius * 3.0;
                    List<VisionPoint> corners = corners(center, direction, major, minor);
                    if (!inside(corners, rgb.cols(), rgb.rows())) continue;
                    polygonMask(mask, corners, gray.rows(), gray.cols());
                    // Blue PCB must be darker than the two steel rims. Cool white board
                    // silkscreen around mounting holes is not transducer/PCB evidence.
                    Imgproc.threshold(gray, contrastBlue, Math.ceil(Math.min(a.rimMean, b.rimMean) * .72) - 1,
                            255, Imgproc.THRESH_BINARY_INV);
                    Core.bitwise_and(blue, contrastBlue, contrastBlue);
                    Core.bitwise_and(contrastBlue, mask, selected);
                    double ratio = Core.countNonZero(selected) / (double) Math.max(1, Core.countNonZero(mask));
                    if (ratio < .075 || ratio > .48 || major * minor < gray.total() * minimumAreaRatio
                            || major * minor > gray.total() * .70) continue;
                    if (bestCenter != null && distance(bestCenter, center) > radius * 2) return null;
                    if (best == null) {
                        best = new Candidate(corners, .85, 45, 20);
                        bestCenter = center;
                    }
                }
            }
            return best;
        } finally {
            releaseContours(contours);
            selected.release(); mask.release(); hierarchy.release(); threshold.release(); kernel.release();
            contrastBlue.release(); blue.release(); blur.release(); hsv.release(); gray.release(); working.release();
        }
    }

    static Candidate findLineTracker(Mat rgb, double minimumAreaRatio) {
        minimumAreaRatio = Math.max(.002, minimumAreaRatio);
        if (!compatible(rgb)) return null;
        Mat working = LooseLighting.normalizeExposure(rgb);
        Mat gray = new Mat(), hsv = new Mat(), blue = new Mat(), foreground = new Mat();
        Mat hierarchy = new Mat(), blueHierarchy = new Mat(), mask = new Mat(), localBlue = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        List<MatOfPoint> contours = new ArrayList<>(), clusters = new ArrayList<>();
        try {
            Imgproc.cvtColor(working, gray, Imgproc.COLOR_RGB2GRAY);
            Imgproc.cvtColor(working, hsv, Imgproc.COLOR_RGB2HSV);
            Core.inRange(hsv, new Scalar(80, 65, 32), new Scalar(130, 255, 255), blue);
            Imgproc.threshold(gray, foreground, 109, 255, Imgproc.THRESH_BINARY_INV);
            Imgproc.morphologyEx(foreground, foreground, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.findContours(foreground, contours, hierarchy,
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            Pixels pixels = new Pixels(gray, hsv);
            Candidate best = null;
            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);
                if (area < gray.total() * minimumAreaRatio || area > gray.total() * .15) continue;
                MatOfPoint2f points = new MatOfPoint2f(contour.toArray());
                Axis axis;
                try {
                    axis = Axis.from(Imgproc.minAreaRect(points));
                } finally {
                    points.release();
                }
                if (axis.minor < 10 || axis.major / axis.minor < 3.15
                        || axis.major / axis.minor > 5.25 || area / (axis.major * axis.minor) < .56
                        || axis.major * axis.minor > gray.total() * .70) continue;
                List<VisionPoint> corners = corners(axis.center, axis.direction, axis.major, axis.minor);
                if (!inside(corners, rgb.cols(), rgb.rows())) continue;
                polygonMask(mask, corners, gray.rows(), gray.cols());
                Core.bitwise_and(blue, mask, localBlue);
                double blueRatio = Core.countNonZero(localBlue) / (double) Math.max(1, Core.countNonZero(mask));
                if (blueRatio < .018 || blueRatio > .55) continue;
                Imgproc.findContours(localBlue, clusters, blueHierarchy,
                        Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
                List<BlueRegion> regions = new ArrayList<>();
                for (MatOfPoint cluster : clusters) {
                    double clusterArea = Imgproc.contourArea(cluster);
                    if (clusterArea < Math.max(5, axis.minor * axis.minor * .008)) continue;
                    Moments moments = Imgproc.moments(cluster);
                    if (moments.get_m00() == 0) continue;
                    double dx = moments.get_m10() / moments.get_m00() - axis.center.x;
                    double dy = moments.get_m01() / moments.get_m00() - axis.center.y;
                    double offset = (dx * axis.direction.x + dy * axis.direction.y) / axis.major;
                    double side = Math.abs(axis.direction.x * dy - axis.direction.y * dx) / axis.minor;
                    double neutral = pixels.neutralRatio(Imgproc.boundingRect(cluster));
                    regions.add(new BlueRegion(offset, side,
                            clusterArea / (axis.minor * axis.minor), neutral));
                }
                releaseContours(clusters);
                boolean verified = false;
                for (int i = 0; i < regions.size(); i++) {
                    BlueRegion a = regions.get(i);
                    for (int j = i + 1; j < regions.size(); j++) {
                        BlueRegion b = regions.get(j);
                        double separation = Math.abs(a.offset - b.offset);
                        if (separation < .14 || separation > .42 || Math.max(a.side, b.side) > .44) continue;
                        if (a.potentiometer() || b.potentiometer()) verified = true;
                    }
                }
                if (!verified) continue;
                if (best != null) return null;
                best = new Candidate(corners, .86, 50, 14);
            }
            return best;
        } finally {
            releaseContours(clusters); releaseContours(contours);
            kernel.release(); localBlue.release(); mask.release(); blueHierarchy.release(); hierarchy.release();
            foreground.release(); blue.release(); hsv.release(); gray.release(); working.release();
        }
    }

    private static boolean compatible(Mat rgb) {
        return rgb != null && !rgb.empty() && rgb.type() == CvType.CV_8UC3;
    }

    private static boolean inside(List<VisionPoint> corners, int width, int height) {
        for (VisionPoint point : corners) {
            if (point.getX() < 0 || point.getY() < 0
                    || point.getX() >= width || point.getY() >= height) return false;
        }
        return true;
    }

    private static void polygonMask(Mat mask, List<VisionPoint> corners, int rows, int cols) {
        mask.create(rows, cols, CvType.CV_8UC1);
        mask.setTo(Scalar.all(0));
        Point[] values = new Point[corners.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = new Point((int) corners.get(i).getX(), (int) corners.get(i).getY());
        }
        MatOfPoint polygon = new MatOfPoint(values);
        try {
            Imgproc.fillConvexPoly(mask, polygon, Scalar.all(255));
        } finally {
            polygon.release();
        }
    }

    private static List<VisionPoint> corners(Point center, Point u, double major, double minor) {
        Point v = new Point(-u.y, u.x);
        return Collections.unmodifiableList(Arrays.asList(
                corner(center, u, v, -major * .5, -minor * .5),
                corner(center, u, v, major * .5, -minor * .5),
                corner(center, u, v, major * .5, minor * .5),
                corner(center, u, v, -major * .5, minor * .5)));
    }

    private static VisionPoint corner(Point center, Point u, Point v, double a, double b) {
        return new VisionPoint(center.x + u.x * a + v.x * b, center.y + u.y * a + v.y * b);
    }

    private static Point positiveAxis(Point direction) {
        if (direction.x < -1e-6 || Math.abs(direction.x) <= 1e-6 && direction.y < 0) {
            direction.x = -direction.x;
            direction.y = -direction.y;
        }
        return direction;
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static void releaseContours(List<MatOfPoint> contours) {
        for (MatOfPoint contour : contours) contour.release();
        contours.clear();
    }

    private static final class Axis {
        final Point center;
        final Point direction;
        final double major;
        final double minor;

        Axis(Point center, Point direction, double major, double minor) {
            this.center = center; this.direction = direction; this.major = major; this.minor = minor;
        }

        static Axis from(RotatedRect box) {
            Point[] points = new Point[4]; box.points(points);
            double first = distance(points[0], points[1]), second = distance(points[1], points[2]);
            Point a = first >= second ? points[0] : points[1];
            Point b = first >= second ? points[1] : points[2];
            double major = Math.max(first, second);
            return new Axis(box.center, positiveAxis(new Point((b.x - a.x) / major,
                    (b.y - a.y) / major)), major, Math.min(box.size.width, box.size.height));
        }
    }

    private static final class Disk {
        final Point center;
        final double radius;
        final double rimMean;
        Disk(Point center, double radius, double rimMean) {
            this.center = center; this.radius = radius; this.rimMean = rimMean;
        }
    }

    private static final class BlueRegion {
        final double offset, side, areaRatio, neutral;
        BlueRegion(double offset, double side, double areaRatio, double neutral) {
            this.offset = offset; this.side = side; this.areaRatio = areaRatio; this.neutral = neutral;
        }
        boolean potentiometer() { return Math.abs(offset) <= .23 && areaRatio >= .055 && neutral >= .06; }
    }

    private static final class Pixels {
        final int width, height;
        final byte[] gray, hsv;
        Pixels(Mat grayMat, Mat hsvMat) {
            width = grayMat.cols(); height = grayMat.rows();
            gray = new byte[width * height]; hsv = new byte[width * height * 3];
            grayMat.get(0, 0, gray); hsvMat.get(0, 0, hsv);
        }

        double[] circleStats(Point center, double radius) {
            int left = Math.max(0, (int) Math.floor(center.x - radius * 1.4));
            int right = Math.min(width, (int) Math.ceil(center.x + radius * 1.4) + 1);
            int top = Math.max(0, (int) Math.floor(center.y - radius * 1.4));
            int bottom = Math.min(height, (int) Math.ceil(center.y + radius * 1.4) + 1);
            double sum = 0, square = 0, ring = 0, saturation = 0;
            int count = 0, ringCount = 0;
            for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
                double dx = x - center.x, dy = y - center.y;
                double normalized = (dx * dx + dy * dy) / (radius * radius);
                int index = y * width + x, value = gray[index] & 255;
                if (normalized <= .75 * .75) {
                    sum += value; square += value * value; count++;
                } else if (normalized >= 1.02 * 1.02 && normalized <= 1.35 * 1.35) {
                    ring += value; saturation += hsv[index * 3 + 1] & 255; ringCount++;
                }
            }
            if (count < 20 || ringCount < 20) return null;
            double mean = sum / count;
            return new double[]{mean, Math.sqrt(Math.max(0, square / count - mean * mean)),
                    ring / ringCount, saturation / ringCount};
        }

        double neutralRatio(Rect bounds) {
            int neutral = 0;
            for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
                for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                    int index = (y * width + x) * 3;
                    if ((hsv[index + 1] & 255) < 95 && (hsv[index + 2] & 255) > 105) neutral++;
                }
            }
            return neutral / (double) Math.max(1, bounds.width * bounds.height);
        }
    }
}
