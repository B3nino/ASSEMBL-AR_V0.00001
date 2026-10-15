package dev.chyken.assembl_ar.workflow;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import dev.chyken.assembl_ar.vision.PlanarCoordinateMapper;
import dev.chyken.assembl_ar.vision.VisionPoint;

/**
 * Fixed main-board features that look like an installed bracket to the camera. An empty slot
 * lane still contains dark, upright-looking silhouettes: the microcontroller's female socket
 * rows, the cable slots beside the inner bracket slots, the DC jack, empty tab holes, shadow
 * slivers, and the background beyond the board's outer edge. Held-out kit replays (3 and 6
 * October 2026; 89 photos) showed each of them passing the bracket profile, so an empty slot
 * could offer physical-fit confirmation and a filled slot could be rejected as ambiguous.
 *
 * <p>These are board-plane features, so their canonical position does not depend on the camera
 * angle; an upright bracket's silhouette does, which is why the slot lanes stay wide. A
 * detection is rejected only when it coincides with one of these features. This never admits
 * anything the slot gate would otherwise reject.</p>
 *
 * <p>Canonical axes: u = 0 at the LEFT MOTOR edge and 1 at the right edge; v = 0 at the LED /
 * "BOARD VERSION" edge and 1 at the RESET / DC-jack edge. Measured on rectified kit photos of
 * the measured 100 × 120 mm board.</p>
 */
final class BracketSlotSurroundings {
    /** The socket rows themselves; no installed plate reaches them from its slot. */
    private static final double[][] SOCKET_FOOTPRINTS = {
            {0.39, 0.46, 0.59, 0.97},
            {0.585, 0.645, 0.59, 0.97}};
    /** Detections centred here are a socket row together with its cast shadow. */
    private static final double[][] SOCKET_ROWS = {
            {0.33, 0.47, 0.55, 1.05},
            {0.57, 0.70, 0.55, 1.05}};
    /** Cable slots (u 0.28–0.32 and 0.70–0.73, v 0.62–0.81); only slot-thin detections. */
    private static final double[][] CABLE_SLOTS = {
            {0.265, 0.335, 0.58, 0.85},
            {0.685, 0.745, 0.58, 0.85}};
    /** Cable slots are about 4 mm wide; an installed plate shows its thickness plus its face. */
    private static final double THIN_WIDTH_FRACTION = 0.06;
    /**
     * Narrower than 5% of the board width (6 mm), a silhouette cannot be told from an empty tab
     * hole or a shadow sliver (measured 0.038–0.041); a seated plate seen at an angle is wider.
     */
    private static final double MIN_WIDTH_FRACTION = 0.05;
    /**
     * Seated brackets stand on their tabs at v 0.62–0.70, so a plate's outline always spans this
     * line (every real detection did; the closest missed by 0.04). Shadows below it do not.
     */
    private static final double SEATING_LINE_TOP = 0.58;
    private static final double SEATING_LINE_BOTTOM = 0.68;
    /** DC jack body (u 0.77–0.88, v 0.87–1.0) and its overhang past the bottom edge. */
    private static final double[] DC_JACK = {0.77, 0.88, 0.87, 1.05};
    /** Only the right outer lane (slot 4) reaches the jack from its own slot. */
    private static final double DC_JACK_LANE_MIN = 0.90;
    /**
     * Fraction of the board's length (75 mm). Brackets are at most 48 mm long; outer plates
     * projected beyond the edge measured at most 0.40, background strips 0.57–0.59.
     */
    private static final double EDGE_STRIP_LENGTH = 0.50;

    private BracketSlotSurroundings() {
    }

    /**
     * Returns the board feature the detected quad coincides with, or null for a detection that
     * could be a bracket. A quad that cannot be placed on the board plane is also rejected.
     */
    @Nullable
    static String featureAt(List<VisionPoint> boardCorners, List<VisionPoint> detectedCorners,
                            double boardWidthPx, double laneX) {
        VisionPoint imageCenter = average(detectedCorners);
        VisionPoint center = PlanarCoordinateMapper.unprojectUnitSquare(boardCorners, imageCenter);
        if (center == null) {
            return "off-plane";
        }
        double u = center.getX();
        double v = center.getY();
        for (double[] zone : SOCKET_ROWS) {
            if (inside(zone, u, v)) {
                return "socket row";
            }
        }
        List<VisionPoint> canonical = new ArrayList<>(4);
        for (VisionPoint corner : detectedCorners) {
            canonical.add(PlanarCoordinateMapper.unprojectUnitSquare(boardCorners, corner));
        }
        if (!canonical.contains(null)) {
            // Dark regions merged with a socket row and its surroundings can centre elsewhere.
            for (double[] footprint : SOCKET_FOOTPRINTS) {
                if (intersects(canonical, footprint)) {
                    return "socket row";
                }
            }
        }
        double width = Math.min(
                (detectedCorners.get(0).distanceTo(detectedCorners.get(1))
                        + detectedCorners.get(3).distanceTo(detectedCorners.get(2))) * 0.5,
                (detectedCorners.get(0).distanceTo(detectedCorners.get(3))
                        + detectedCorners.get(1).distanceTo(detectedCorners.get(2))) * 0.5);
        if (width < boardWidthPx * MIN_WIDTH_FRACTION) {
            return "opening or shadow";
        }
        if (width < boardWidthPx * THIN_WIDTH_FRACTION) {
            for (double[] zone : CABLE_SLOTS) {
                if (inside(zone, u, v)) {
                    return "cable slot";
                }
            }
        }
        if (canonical.contains(null)) {
            return u < 0.0 || u > 1.0 ? "off-plane" : null;
        }
        double minV = Double.POSITIVE_INFINITY;
        double maxV = Double.NEGATIVE_INFINITY;
        for (VisionPoint corner : canonical) {
            minV = Math.min(minV, corner.getY());
            maxV = Math.max(maxV, corner.getY());
        }
        if (minV > SEATING_LINE_BOTTOM || maxV < SEATING_LINE_TOP) {
            return "not seated";
        }
        if (laneX >= DC_JACK_LANE_MIN && intersects(canonical, DC_JACK)) {
            return "DC jack";
        }
        if ((u < 0.0 || u > 1.0) && maxV - minV > EDGE_STRIP_LENGTH) {
            return "board edge";
        }
        return null;
    }

    private static boolean inside(double[] zone, double u, double v) {
        return u >= zone[0] && u <= zone[1] && v >= zone[2] && v <= zone[3];
    }

    /** Convex quad versus axis-aligned rectangle {uMin, uMax, vMin, vMax}. */
    private static boolean intersects(List<VisionPoint> quad, double[] rectangle) {
        VisionPoint[] box = {
                new VisionPoint(rectangle[0], rectangle[2]), new VisionPoint(rectangle[1], rectangle[2]),
                new VisionPoint(rectangle[1], rectangle[3]), new VisionPoint(rectangle[0], rectangle[3])};
        for (VisionPoint corner : quad) {
            if (inside(rectangle, corner.getX(), corner.getY())) return true;
        }
        for (VisionPoint corner : box) {
            if (insideConvex(quad, corner)) return true;
        }
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (segmentsCross(quad.get(i), quad.get((i + 1) % 4), box[j], box[(j + 1) % 4])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean insideConvex(List<VisionPoint> quad, VisionPoint point) {
        double sign = 0.0;
        for (int i = 0; i < quad.size(); i++) {
            double cross = cross(quad.get(i), quad.get((i + 1) % quad.size()), point);
            if (cross == 0.0) continue;
            if (sign == 0.0) {
                sign = Math.signum(cross);
            } else if (Math.signum(cross) != sign) {
                return false;
            }
        }
        return true;
    }

    private static boolean segmentsCross(VisionPoint a, VisionPoint b, VisionPoint c, VisionPoint d) {
        double first = cross(a, b, c);
        double second = cross(a, b, d);
        double third = cross(c, d, a);
        double fourth = cross(c, d, b);
        return first * second < 0.0 && third * fourth < 0.0;
    }

    private static double cross(VisionPoint origin, VisionPoint end, VisionPoint point) {
        return (end.getX() - origin.getX()) * (point.getY() - origin.getY())
                - (end.getY() - origin.getY()) * (point.getX() - origin.getX());
    }

    private static VisionPoint average(List<VisionPoint> corners) {
        double x = 0.0;
        double y = 0.0;
        for (VisionPoint corner : corners) {
            x += corner.getX();
            y += corner.getY();
        }
        return new VisionPoint(x / corners.size(), y / corners.size());
    }
}
