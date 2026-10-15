package dev.chyken.assembl_ar.vision;

import java.util.Objects;

/** Immutable two-dimensional point in image pixels or normalized reference coordinates. */
public final class VisionPoint {
    private final double x;
    private final double y;

    public VisionPoint(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Point coordinates must be finite");
        }
        this.x = x == 0.0 ? 0.0 : x;
        this.y = y == 0.0 ? 0.0 : y;
    }

    public double getX() { return x; }
    public double getY() { return y; }

    public double distanceTo(VisionPoint other) {
        Objects.requireNonNull(other, "other");
        return Math.hypot(x - other.x, y - other.y);
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof VisionPoint)) return false;
        VisionPoint point = (VisionPoint) value;
        return Double.compare(x, point.x) == 0 && Double.compare(y, point.y) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y);
    }

    @Override
    public String toString() {
        return "VisionPoint{" + x + ", " + y + '}';
    }
}
