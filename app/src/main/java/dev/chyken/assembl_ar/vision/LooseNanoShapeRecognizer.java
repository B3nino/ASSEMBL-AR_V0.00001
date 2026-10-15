package dev.chyken.assembl_ar.vision;

import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.RotatedRect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A blue elongated Nano PCB with its gold USB shell at one end, against a neutral mat. */
final class LooseNanoShapeRecognizer {
    private static final Candidate AMBIGUOUS = new Candidate(java.util.Collections.emptyList());
    static final class Candidate {
        final List<VisionPoint> corners;
        final double confidence = .86;
        Candidate(List<VisionPoint> corners) { this.corners = corners; }
    }

    static Candidate find(Mat rgb, double minimumAreaRatio) {
        Candidate original = find(rgb, minimumAreaRatio, false);
        if (original == AMBIGUOUS) return null;
        if (original != null) return original;
        Candidate dim = find(rgb, minimumAreaRatio, true);
        return dim == AMBIGUOUS ? null : dim;
    }

    private static Candidate find(Mat rgb, double minimumAreaRatio, boolean dimBody) {
        minimumAreaRatio = Math.max(.002, minimumAreaRatio);
        Mat hsv = new Mat(), lab = new Mat(), blue = new Mat(), closed = new Mat(), gray = new Mat();
        Mat hierarchy = new Mat(), kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, new Size(9, 9));
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV);
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab);
            int width = rgb.cols(), height = rgb.rows(), count = width * height;
            byte[] colors = new byte[count * 3], labs = new byte[count * 3], grayPixels = new byte[count];
            hsv.get(0, 0, colors); lab.get(0, 0, labs);
            if (dimBody) {
                Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
                gray.get(0, 0, grayPixels);
            }
            long whiteSum = 0;
            int whiteCount = 0;
            for (int p = 0; p < count; p++) {
                int i = p * 3;
                if ((labs[i] & 255) >= (dimBody ? 90 : 140) && (colors[i + 1] & 255) <= 45) {
                    whiteSum += labs[i + 2] & 255;
                    whiteCount++;
                }
            }
            if (whiteCount < count * .10) return null;
            double whiteB = whiteSum / (double) whiteCount;
            // The dim silhouette includes protruding header pins. Its blue chroma
            // is measured relative to the mat and its own luminance, before any lift.
            double background = Double.NaN;
            if (dimBody) {
                int[] histogram = new int[256];
                for (int p = 0; p < count; p++) {
                    if ((labs[p * 3] & 255) >= 90 && (colors[p * 3 + 1] & 255) <= 45) {
                        histogram[grayPixels[p] & 255]++;
                    }
                }
                background = LooseLighting.median(histogram, whiteCount);
                if (!Double.isFinite(background) || background >= 150) return null;
            }
            byte[] bluePixels = new byte[count], bodyPixels = new byte[count];
            boolean[] gold = new boolean[count];
            for (int p = 0; p < count; p++) {
                int i = p * 3, h = colors[i] & 255, s = colors[i + 1] & 255;
                int v = colors[i + 2] & 255, b = labs[i + 2] & 255;
                double blueDifference = dimBody ? Math.max(1, Math.min(12, (labs[i] & 255) * .08)) : 12;
                if (h >= 85 && h <= 135 && s >= 55 && v >= (dimBody ? 12 : 45)
                        && b <= whiteB - blueDifference) {
                    bluePixels[p] = (byte) 255;
                }
                gold[p] = h >= 12 && h <= 42 && s >= 65 && v >= 60 && b >= whiteB + 14;
                if (dimBody && ((grayPixels[p] & 255) < background * .60 || gold[p])) {
                    bodyPixels[p] = (byte) 255;
                }
            }
            blue.create(height, width, org.opencv.core.CvType.CV_8UC1);
            blue.put(0, 0, dimBody ? bodyPixels : bluePixels);
            Imgproc.morphologyEx(blue, closed, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE);
            Candidate found = null;
            for (MatOfPoint contour : contours) {
                if (Imgproc.contourArea(contour) < count * .001) continue;
                MatOfPoint2f points = new MatOfPoint2f(contour.toArray());
                RotatedRect rect;
                try { rect = Imgproc.minAreaRect(points); } finally { points.release(); }
                double length = rect.size.width, shortSide = rect.size.height, angle = rect.angle;
                if (shortSide > length) {
                    double swap = length; length = shortSide; shortSide = swap; angle += 90;
                }
                double area = length * shortSide, aspect = length / shortSide;
                if (shortSide < 12 || area < count * minimumAreaRatio || area > count * .12
                        || aspect < (dimBody ? 1.85 : 2.15) || aspect > 3.7) continue;
                double ax = Math.cos(Math.toRadians(angle)), ay = Math.sin(Math.toRadians(angle));
                double cx = rect.center.x, cy = rect.center.y;
                int blueCount = 0, shellCount = 0, centerGold = 0, shellLeft = 0, shellRight = 0;
                double shellU = 0;
                double reach = length * .52 + shortSide * .60;
                int left = Math.max(0, (int) Math.floor(cx - reach));
                int right = Math.min(width - 1, (int) Math.ceil(cx + reach));
                int top = Math.max(0, (int) Math.floor(cy - reach));
                int bottom = Math.min(height - 1, (int) Math.ceil(cy + reach));
                for (int y = top; y <= bottom; y++) for (int x = left; x <= right; x++) {
                    double u = ((x - cx) * ax + (y - cy) * ay) / length;
                    double v = (-(x - cx) * ay + (y - cy) * ax) / shortSide;
                    if (Math.abs(u) > .52 || Math.abs(v) > .60) continue;
                    int p = y * width + x;
                    if (bluePixels[p] != 0) blueCount++;
                    if (gold[p] && Math.abs(u) >= .25) {
                        shellCount++; shellU += u;
                        if (u < 0) shellLeft++; else shellRight++;
                    }
                    if (gold[p] && Math.abs(u) < .20) centerGold++;
                }
                double fill = blueCount / area;
                if (fill < (dimBody ? .10 : .22) || fill > .92 || shellCount / area < (dimBody ? .010 : .018)
                        || centerGold / area > .012) continue;
                if (dimBody && Math.max(shellLeft, shellRight) < shellCount * .80) continue;
                if (shellU > 0) { ax = -ax; ay = -ay; }
                List<VisionPoint> corners = Arrays.asList(
                        corner(cx, cy, ax, ay, -length / 2, -shortSide / 2),
                        corner(cx, cy, ax, ay, length / 2, -shortSide / 2),
                        corner(cx, cy, ax, ay, length / 2, shortSide / 2),
                        corner(cx, cy, ax, ay, -length / 2, shortSide / 2));
                if (corners.stream().anyMatch(p -> p.getX() < 0 || p.getY() < 0
                        || p.getX() >= width || p.getY() >= height)) continue;
                if (found != null) return AMBIGUOUS;
                found = new Candidate(corners);
            }
            return found;
        } finally {
            for (MatOfPoint contour : contours) contour.release();
            kernel.release(); hierarchy.release(); closed.release(); blue.release();
            gray.release(); lab.release(); hsv.release();
        }
    }

    private static VisionPoint corner(double cx, double cy, double ax, double ay,
                                      double x, double y) {
        return new VisionPoint(cx + ax * x - ay * y, cy + ay * x + ax * y);
    }
}
