package dev.chyken.assembl_ar.vision;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Canonical capture guides shared by the administrator camera, descriptor mask, and AR pose
 * mapping. Aligning the physical corners to these guides keeps incidental background out of ORB
 * evidence and gives solvePnP the component plane rather than the full camera frame.
 */
public final class AdminReferenceGeometry {
    public static final double FRONT_AZIMUTH_DEGREES = 0.0;
    public static final double LEFT_OBLIQUE_AZIMUTH_DEGREES = -20.0;
    public static final double RIGHT_OBLIQUE_AZIMUTH_DEGREES = 20.0;

    private static final double MAXIMUM_NORMALIZED_SPAN = 0.84;
    private static final double OBLIQUE_FAR_EDGE_TAPER = 0.08;

    private AdminReferenceGeometry() {
    }

    public static double azimuthForCaptureIndex(int index) {
        switch (index) {
            case 0:
                return FRONT_AZIMUTH_DEGREES;
            case 1:
                return LEFT_OBLIQUE_AZIMUTH_DEGREES;
            case 2:
                return RIGHT_OBLIQUE_AZIMUTH_DEGREES;
            default:
                return index % 2 == 1
                        ? LEFT_OBLIQUE_AZIMUTH_DEGREES
                        : RIGHT_OBLIQUE_AZIMUTH_DEGREES;
        }
    }

    /**
     * Fits the component's physical width:height ratio inside the camera frame, then applies a
     * small authored trapezoid for the two oblique captures. Returned coordinates are normalized
     * to the exact PreviewView/ImageCapture viewport and are persisted with each capture.
     */
    public static List<VisionPoint> cornersForCapture(
            double azimuthDegrees,
            double componentAspectRatio,
            double frameAspectRatio) {
        if (!(componentAspectRatio > 0.0) || !Double.isFinite(componentAspectRatio)
                || !(frameAspectRatio > 0.0) || !Double.isFinite(frameAspectRatio)) {
            throw new IllegalArgumentException("Component and frame aspect ratios must be positive");
        }
        double normalizedAspectRatio = componentAspectRatio / frameAspectRatio;
        double width;
        double height;
        if (normalizedAspectRatio >= 1.0) {
            width = MAXIMUM_NORMALIZED_SPAN;
            height = MAXIMUM_NORMALIZED_SPAN / normalizedAspectRatio;
        } else {
            height = MAXIMUM_NORMALIZED_SPAN;
            width = MAXIMUM_NORMALIZED_SPAN * normalizedAspectRatio;
        }
        double left = (1.0 - width) * 0.5;
        double right = 1.0 - left;
        double top = (1.0 - height) * 0.5;
        double bottom = 1.0 - top;
        double taper = height * OBLIQUE_FAR_EDGE_TAPER;

        if (azimuthDegrees <= -10.0) {
            return corners(
                    left, top,
                    right, top + taper,
                    right, bottom - taper,
                    left, bottom);
        }
        if (azimuthDegrees >= 10.0) {
            return corners(
                    left, top + taper,
                    right, top,
                    right, bottom,
                    left, bottom - taper);
        }
        return corners(
                left, top,
                right, top,
                right, bottom,
                left, bottom);
    }

    public static void applyPlanarBounds(ComponentReferenceSpec.Builder builder,
                                         List<VisionPoint> points) {
        validateCorners(points);
        builder.planarReferenceCornersNormalized(
                points.get(0), points.get(1), points.get(2), points.get(3));
    }

    /** Returns a caller-owned CV_8UC1 mask covering only the authored component guide. */
    public static Mat createDescriptorMask(int width, int height, List<VisionPoint> points) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Reference dimensions must be positive");
        }
        validateCorners(points);
        Point[] pixels = new Point[points.size()];
        for (int index = 0; index < points.size(); index++) {
            VisionPoint point = points.get(index);
            pixels[index] = new Point(
                    point.getX() * (width - 1.0),
                    point.getY() * (height - 1.0));
        }
        Mat mask = Mat.zeros(height, width, CvType.CV_8UC1);
        MatOfPoint polygon = new MatOfPoint(pixels);
        try {
            Imgproc.fillConvexPoly(mask, polygon, new Scalar(255.0));
            return mask;
        } catch (RuntimeException failure) {
            mask.release();
            throw failure;
        } finally {
            polygon.release();
        }
    }

    private static List<VisionPoint> corners(double... coordinates) {
        List<VisionPoint> points = new ArrayList<>(4);
        points.add(new VisionPoint(coordinates[0], coordinates[1]));
        points.add(new VisionPoint(coordinates[2], coordinates[3]));
        points.add(new VisionPoint(coordinates[4], coordinates[5]));
        points.add(new VisionPoint(coordinates[6], coordinates[7]));
        return Collections.unmodifiableList(points);
    }

    public static void validateCorners(List<VisionPoint> points) {
        if (points == null || points.size() != 4) {
            throw new IllegalArgumentException("Exactly four component corners are required");
        }
        for (VisionPoint point : points) {
            if (point == null || point.getX() < 0.0 || point.getX() > 1.0
                    || point.getY() < 0.0 || point.getY() > 1.0) {
                throw new IllegalArgumentException(
                        "Component corners must be normalized to the camera frame");
            }
        }
        double winding = 0.0;
        for (int index = 0; index < points.size(); index++) {
            VisionPoint first = points.get(index);
            VisionPoint second = points.get((index + 1) % points.size());
            VisionPoint third = points.get((index + 2) % points.size());
            double cross = (second.getX() - first.getX())
                    * (third.getY() - second.getY())
                    - (second.getY() - first.getY())
                    * (third.getX() - second.getX());
            if (Math.abs(cross) <= 1e-8) {
                throw new IllegalArgumentException("Component corner polygon is degenerate");
            }
            double sign = Math.signum(cross);
            if (winding == 0.0) {
                winding = sign;
            } else if (sign != winding) {
                throw new IllegalArgumentException("Component corner polygon must be convex");
            }
        }
        if (winding < 0.0) {
            throw new IllegalArgumentException(
                    "Component corners must use canonical clockwise image order");
        }
    }
}
