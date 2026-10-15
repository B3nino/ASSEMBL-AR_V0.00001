package dev.chyken.assembl_ar.vision;

import androidx.annotation.Nullable;

import java.util.List;

/** Shared, OpenCV-free projective map from a canonical unit square into a registered quad. */
public final class PlanarCoordinateMapper {
    private PlanarCoordinateMapper() {
    }

    @Nullable
    public static VisionPoint projectUnitSquare(List<VisionPoint> corners, VisionPoint point) {
        double[] m = coefficients(corners);
        if (m == null || point == null) {
            return null;
        }
        double divisor = m[6] * point.getX() + m[7] * point.getY() + 1.0;
        if (Math.abs(divisor) < 1e-9) {
            return null;
        }
        double x = (m[0] * point.getX() + m[1] * point.getY() + m[2]) / divisor;
        double y = (m[3] * point.getX() + m[4] * point.getY() + m[5]) / divisor;
        return Double.isFinite(x) && Double.isFinite(y) ? new VisionPoint(x, y) : null;
    }

    /**
     * Inverse of {@link #projectUnitSquare}: the canonical unit-square coordinates of a point in
     * the quad's image plane. Points beyond the plane's horizon have no canonical position and
     * return null, as do degenerate quads.
     */
    @Nullable
    public static VisionPoint unprojectUnitSquare(List<VisionPoint> corners, VisionPoint point) {
        double[] m = coefficients(corners);
        if (m == null || point == null) {
            return null;
        }
        double x = point.getX();
        double y = point.getY();
        double a11 = m[0] - m[6] * x;
        double a12 = m[1] - m[7] * x;
        double a21 = m[3] - m[6] * y;
        double a22 = m[4] - m[7] * y;
        double determinant = a11 * a22 - a12 * a21;
        if (Math.abs(determinant) < 1e-12) {
            return null;
        }
        double u = ((x - m[2]) * a22 - a12 * (y - m[5])) / determinant;
        double v = (a11 * (y - m[5]) - (x - m[2]) * a21) / determinant;
        if (!Double.isFinite(u) || !Double.isFinite(v)
                || m[6] * u + m[7] * v + 1.0 < 1e-9) {
            return null;
        }
        return new VisionPoint(u, v);
    }

    /** Homography coefficients a..h for corners ordered top-left, top-right, bottom-right, bottom-left. */
    @Nullable
    private static double[] coefficients(List<VisionPoint> corners) {
        if (corners == null || corners.size() != 4) {
            return null;
        }
        VisionPoint topLeft = corners.get(0);
        VisionPoint topRight = corners.get(1);
        VisionPoint bottomRight = corners.get(2);
        VisionPoint bottomLeft = corners.get(3);
        double dx1 = topRight.getX() - bottomRight.getX();
        double dx2 = bottomLeft.getX() - bottomRight.getX();
        double dy1 = topRight.getY() - bottomRight.getY();
        double dy2 = bottomLeft.getY() - bottomRight.getY();
        double sx = topLeft.getX() - topRight.getX()
                + bottomRight.getX() - bottomLeft.getX();
        double sy = topLeft.getY() - topRight.getY()
                + bottomRight.getY() - bottomLeft.getY();
        double denominator = dx1 * dy2 - dx2 * dy1;

        double g;
        double h;
        if (Math.abs(denominator) < 1e-9) {
            g = 0.0;
            h = 0.0;
        } else {
            g = (sx * dy2 - dx2 * sy) / denominator;
            h = (dx1 * sy - sx * dy1) / denominator;
        }
        double a = topRight.getX() - topLeft.getX() + g * topRight.getX();
        double b = bottomLeft.getX() - topLeft.getX() + h * bottomLeft.getX();
        double c = topLeft.getX();
        double d = topRight.getY() - topLeft.getY() + g * topRight.getY();
        double e = bottomLeft.getY() - topLeft.getY() + h * bottomLeft.getY();
        double f = topLeft.getY();
        return new double[] {a, b, c, d, e, f, g, h};
    }
}
