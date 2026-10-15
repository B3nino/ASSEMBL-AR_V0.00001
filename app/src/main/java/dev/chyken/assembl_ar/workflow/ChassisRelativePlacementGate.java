package dev.chyken.assembl_ar.workflow;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.vision.PlanarCoordinateMapper;
import dev.chyken.assembl_ar.vision.VisionPoint;

/**
 * Stateless chassis-relative placement gate for the manuscript's 18 NEOBOT assembly sections.
 *
 * <p>The gate projects authored unit-square targets through the currently detected chassis (or
 * extension-board) quadrilateral. It then compares component center, in-plane orientation, and
 * projected linear scale. Dual-motor, dual-tracker, and dual-wheel profiles require both distinct
 * detections and perform one-to-one target assignment. Invalid or incomplete data always fails
 * closed with a UI-ready explanation.</p>
 */
public final class ChassisRelativePlacementGate {
    public enum Reason {
        ACCEPTED,
        MISSING_ANCHOR,
        DEGENERATE_ANCHOR,
        MISSING_COMPONENTS,
        UNEXPECTED_COMPONENT_COUNT,
        INVALID_COMPONENT_GEOMETRY,
        POSITION_MISMATCH,
        ORIENTATION_MISMATCH,
        SCALE_MISMATCH
    }

    /** Projected authored target, useful for both tests and HUD target outlines. */
    public static final class TargetProjection {
        private final String id;
        private final String label;
        private final VisionPoint center;
        private final List<VisionPoint> corners;
        private final double orientationDegrees;
        private final double area;

        private TargetProjection(String id, String label, VisionPoint center,
                                 List<VisionPoint> corners,
                                 double orientationDegrees, double area) {
            this.id = id;
            this.label = label;
            this.center = center;
            this.corners = Collections.unmodifiableList(new ArrayList<>(corners));
            this.orientationDegrees = orientationDegrees;
            this.area = area;
        }

        public String getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public VisionPoint getCenter() {
            return center;
        }

        public List<VisionPoint> getCorners() {
            return corners;
        }

        public double getOrientationDegrees() {
            return orientationDegrees;
        }

        public double getArea() {
            return area;
        }
    }

    /** Per-target measurement included even when the overall decision fails. */
    public static final class TargetMatch {
        private final String targetId;
        private final String targetLabel;
        private final VisionPoint expectedCenter;
        private final VisionPoint detectedCenter;
        private final double normalizedPositionError;
        private final double orientationErrorDegrees;
        private final double scaleRatio;
        private final boolean positionAccepted;
        private final boolean orientationAccepted;
        private final boolean scaleAccepted;

        private TargetMatch(String targetId, String targetLabel,
                            VisionPoint expectedCenter, VisionPoint detectedCenter,
                            double normalizedPositionError,
                            double orientationErrorDegrees, double scaleRatio,
                            boolean positionAccepted, boolean orientationAccepted,
                            boolean scaleAccepted) {
            this.targetId = targetId;
            this.targetLabel = targetLabel;
            this.expectedCenter = expectedCenter;
            this.detectedCenter = detectedCenter;
            this.normalizedPositionError = normalizedPositionError;
            this.orientationErrorDegrees = orientationErrorDegrees;
            this.scaleRatio = scaleRatio;
            this.positionAccepted = positionAccepted;
            this.orientationAccepted = orientationAccepted;
            this.scaleAccepted = scaleAccepted;
        }

        public String getTargetId() {
            return targetId;
        }

        public String getTargetLabel() {
            return targetLabel;
        }

        public VisionPoint getExpectedCenter() {
            return expectedCenter;
        }

        public VisionPoint getDetectedCenter() {
            return detectedCenter;
        }

        /** Center error divided by the square root of the projected anchor area. */
        public double getNormalizedPositionError() {
            return normalizedPositionError;
        }

        public double getOrientationErrorDegrees() {
            return orientationErrorDegrees;
        }

        /** Detected linear scale divided by authored projected linear scale. */
        public double getScaleRatio() {
            return scaleRatio;
        }

        public boolean isPositionAccepted() {
            return positionAccepted;
        }

        public boolean isOrientationAccepted() {
            return orientationAccepted;
        }

        public boolean isScaleAccepted() {
            return scaleAccepted;
        }

        public boolean isAccepted() {
            return positionAccepted && orientationAccepted && scaleAccepted;
        }
    }

    public static final class Decision {
        private final boolean accepted;
        private final Reason reason;
        private final String message;
        private final int expectedCount;
        private final int detectedCount;
        private final List<TargetMatch> matches;

        private Decision(boolean accepted, Reason reason, String message,
                         int expectedCount, int detectedCount, List<TargetMatch> matches) {
            this.accepted = accepted;
            this.reason = reason;
            this.message = message;
            this.expectedCount = expectedCount;
            this.detectedCount = detectedCount;
            this.matches = Collections.unmodifiableList(new ArrayList<>(matches));
        }

        public boolean isAccepted() {
            return accepted;
        }

        public Reason getReason() {
            return reason;
        }

        public String getMessage() {
            return message;
        }

        public int getExpectedCount() {
            return expectedCount;
        }

        public int getDetectedCount() {
            return detectedCount;
        }

        public List<TargetMatch> getMatches() {
            return matches;
        }
    }

    private static final double GEOMETRY_EPSILON = 1e-8;

    private final int stepNumber;
    private final String subject;
    private final List<AuthoredTarget> targets;

    private ChassisRelativePlacementGate(int stepNumber, String subject,
                                         AuthoredTarget... targets) {
        this.stepNumber = stepNumber;
        this.subject = subject;
        this.targets = Collections.unmodifiableList(Arrays.asList(targets.clone()));
    }

    public static ChassisRelativePlacementGate forStep(AssemblyStep step) {
        Objects.requireNonNull(step, "step");
        return forNeobotStep(step.getStepNumber());
    }

    /**
     * A guided action can require only the target that has been installed so far. Selecting
     * targets preserves the authored tolerances and fails on misspelled or duplicated IDs.
     */
    public ChassisRelativePlacementGate selectTargets(String... targetIds) {
        if (targetIds == null || targetIds.length == 0) {
            throw new IllegalArgumentException("Select at least one authored target");
        }
        List<AuthoredTarget> selected = new ArrayList<>();
        for (String id : targetIds) {
            AuthoredTarget found = null;
            for (AuthoredTarget target : targets) {
                if (target.id.equals(id)) {
                    found = target;
                    break;
                }
            }
            if (found == null || selected.contains(found)) {
                throw new IllegalArgumentException("Unknown or repeated placement target: " + id);
            }
            selected.add(found);
        }
        return new ChassisRelativePlacementGate(stepNumber, subject,
                selected.toArray(new AuthoredTarget[0]));
    }

    /**
     * Approximate visible bracket regions for the four guide slots, in canonical board axes.
     * The main-board reference and the mounted-bracket illustration establish the side and
     * slot order. Upright brackets are not coplanar with the board: this checks the visible
     * position/size only, not tab insertion, contact depth, or the bracket's out-of-plane angle.
     * Each action scans its own half because adjacent upright halves can occlude one another.
     * Detections on fixed board features in the lanes are rejected; see
     * {@link BracketSlotSurroundings}.
     */
    public static ChassisRelativePlacementGate forMotorBracketSlot(int half) {
        switch (half) {
            case 1:
                return bracketSlot("left_outer_bracket", "first bracket at the left outer slot",
                        motorBracketSlotX(half));
            case 2:
                return bracketSlot("left_inner_bracket", "matching bracket at the left inner slot",
                        motorBracketSlotX(half));
            case 3:
                return bracketSlot("right_inner_bracket", "third bracket at the right inner slot",
                        motorBracketSlotX(half));
            case 4:
                return bracketSlot("right_outer_bracket", "fourth bracket at the right outer slot",
                        motorBracketSlotX(half));
            default:
                throw new IllegalArgumentException("Bracket half must be from 1 through 4");
        }
    }

    /** Canonical lane center shared with the installed bracket silhouette search. */
    public static double motorBracketSlotX(int half) {
        switch (half) {
            case 1: return 0.02;
            case 2: return 0.27;
            case 3: return 0.73;
            case 4: return 0.98;
            default: throw new IllegalArgumentException("Bracket half must be from 1 through 4");
        }
    }

    private static ChassisRelativePlacementGate bracketSlot(String id, String label, double x) {
        // Upright plates project across the board in depth. Their image center can move far
        // vertically as the camera tilts, while the four slot lanes stay distinct across its
        // width. The bracket comparison below checks the narrow projected lane separately.
        return single(2, "bracket half", target(id, label,
                x, 0.58, 0.12, 0.28, 0.0, 0.11, 180.0, 0.82));
    }

    /** Returns the fixed manuscript profile for a NEOBOT step. */
    public static ChassisRelativePlacementGate forNeobotStep(int stepNumber) {
        switch (stepNumber) {
            case 1:
                return single(1, "main board", target("main_board", "main board",
                        0.50, 0.50, 1.00, 1.00, 0.0, 0.04, 10.0, 0.18));
            case 2:
                return dual(2, "gear motors",
                        target("left_motor", "left motor", 0.14, 0.58,
                                0.46, 0.20, 90.0, 0.09, 22.0, 0.45),
                        target("right_motor", "right motor", 0.86, 0.58,
                                0.46, 0.20, -90.0, 0.09, 22.0, 0.45));
            case 3:
            case 4:
            case 5:
            case 7:
            case 8:
                // Centre measured on the installed driver in 9 rectified pool photos of the
                // 5 October kit (median v 0.35, range 0.33-0.40); v 0.40 was a manual estimate.
                return single(stepNumber, "motor driver",
                        target("motor_driver", "motor driver", 0.50, 0.35,
                                0.30, 0.22, 0.0, 0.075, 18.0, 0.38));
            case 6:
                return single(6, "microcontroller",
                        target("microcontroller", "microcontroller", 0.50, 0.72,
                                0.44, 0.17, 90.0, 0.065, 16.0, 0.34));
            case 9:
                return single(9, "caster",
                        target("caster", "front caster", 0.50, 0.14,
                                0.23, 0.18, 0.0, 0.085, 28.0, 0.50));
            case 10:
                return dual(10, "line trackers",
                        target("left_tracker", "left line tracker", 0.07, 0.23,
                                0.14, 0.27, 0.0, 0.08, 25.0, 0.48),
                        target("right_tracker", "right line tracker", 0.93, 0.23,
                                0.14, 0.27, 0.0, 0.08, 25.0, 0.48));
            case 11:
                return single(11, "distance sensor",
                        target("distance_sensor", "front distance sensor", 0.50, 0.12,
                                0.30, 0.17, 0.0, 0.08, 20.0, 0.44));
            case 12:
                return single(12, "standoff",
                        target("center_standoff", "center standoff", 0.50, 0.50,
                                0.10, 0.10, 0.0, 0.055, 180.0, 0.55));
            case 13:
                return dual(13, "wheels",
                        target("left_wheel", "left wheel", 0.02, 0.58,
                                0.16, 0.45, 0.0, 0.10, 180.0, 0.58),
                        target("right_wheel", "right wheel", 0.98, 0.58,
                                0.16, 0.45, 0.0, 0.10, 180.0, 0.58));
            case 14:
                return single(14, "OLED protector",
                        target("oled_protector", "OLED protector", 0.43, 0.42,
                                0.40, 0.32, 0.0, 0.075, 18.0, 0.40));
            case 15:
                return single(15, "data cable",
                        target("ribbon_connector", "main-board ribbon connector", 0.28, 0.42,
                                0.12, 0.25, 0.0, 0.08, 180.0, 0.52));
            case 16:
                return single(16, "extension board",
                        target("extension_board", "extension board", 0.50, 0.50,
                                0.90, 0.72, 0.0, 0.055, 12.0, 0.24));
            case 17:
                return single(17, "OLED display",
                        target("oled_display", "OLED display", 0.43, 0.42,
                                0.34, 0.28, 0.0, 0.065, 15.0, 0.34));
            case 18:
                return single(18, "battery",
                        target("battery", "battery pack", 0.22, 0.62,
                                0.52, 0.34, 0.0, 0.09, 24.0, 0.48));
            default:
                throw new IllegalArgumentException(
                        "NEOBOT placement requires a step number from 1 through 18");
        }
    }

    public int getStepNumber() {
        return stepNumber;
    }

    public int getRequiredDetectionCount() {
        return targets.size();
    }

    /**
     * Projects all authored target rectangles into the current image. Invalid anchor geometry
     * returns an empty list; {@link #evaluate(List, List)} provides the explanatory failure.
     */
    public List<TargetProjection> projectTargets(List<VisionPoint> anchorCorners) {
        QuadGeometry anchor = QuadGeometry.from(anchorCorners);
        if (anchor == null) {
            return Collections.emptyList();
        }
        List<TargetProjection> projected = new ArrayList<>(targets.size());
        for (AuthoredTarget target : targets) {
            TargetProjection projection = project(anchorCorners, target);
            if (projection == null) {
                return Collections.emptyList();
            }
            projected.add(projection);
        }
        return Collections.unmodifiableList(projected);
    }

    /** Convenience for single-target profiles; dual profiles fail closed until both are supplied. */
    public Decision evaluateSingle(List<VisionPoint> anchorCorners,
                                   List<VisionPoint> detectedComponentCorners) {
        return evaluate(anchorCorners,
                detectedComponentCorners == null
                        ? Collections.emptyList()
                        : Collections.singletonList(detectedComponentCorners));
    }

    /**
     * Evaluates the complete expected set. Each inner list is one detected component quad in
     * reference-corner order (top-left, top-right, bottom-right, bottom-left).
     */
    public Decision evaluate(List<VisionPoint> anchorCorners,
                             List<? extends List<VisionPoint>> detectedComponentCorners) {
        if (anchorCorners == null || anchorCorners.size() != 4) {
            return failure(Reason.MISSING_ANCHOR,
                    "Keep the complete chassis reference visible before checking placement.",
                    count(detectedComponentCorners));
        }
        QuadGeometry anchor = QuadGeometry.from(anchorCorners);
        if (anchor == null) {
            return failure(Reason.DEGENERATE_ANCHOR,
                    "The chassis outline is incomplete or distorted; reframe it and hold steady.",
                    count(detectedComponentCorners));
        }
        int detectedCount = count(detectedComponentCorners);
        if (detectedCount < targets.size()) {
            return failure(Reason.MISSING_COMPONENTS,
                    missingMessage(detectedCount), detectedCount);
        }
        if (detectedCount > targets.size()) {
            return failure(Reason.UNEXPECTED_COMPONENT_COUNT,
                    "Expected " + targets.size() + " " + subject + " detection"
                            + (targets.size() == 1 ? "" : "s") + " but found " + detectedCount
                            + "; isolate the required component(s) and rescan.", detectedCount);
        }

        List<QuadGeometry> detections = new ArrayList<>(detectedCount);
        for (List<VisionPoint> corners : detectedComponentCorners) {
            QuadGeometry geometry = QuadGeometry.from(corners);
            if (geometry == null) {
                return failure(Reason.INVALID_COMPONENT_GEOMETRY,
                        "The " + subject + " outline is incomplete; keep every corner visible.",
                        detectedCount);
            }
            detections.add(geometry);
        }

        List<TargetProjection> projections = projectTargets(anchorCorners);
        if (projections.size() != targets.size()) {
            return failure(Reason.DEGENERATE_ANCHOR,
                    "The chassis perspective cannot produce stable placement targets; reframe it.",
                    detectedCount);
        }

        Assignment assignment = bestAssignment(anchor, anchorCorners, detections, projections);
        if (assignment == null) {
            return failure(Reason.INVALID_COMPONENT_GEOMETRY,
                    "The detected components could not be assigned to chassis targets.", detectedCount);
        }
        Violation violation = worstViolation(assignment.matches);
        if (violation == null) {
            String acceptedMessage = targets.size() == 1
                    ? "Placement matches the authored " + targets.get(0).label + " target."
                    : "All " + subject + " match their distinct authored chassis targets.";
            return new Decision(true, Reason.ACCEPTED, acceptedMessage,
                    targets.size(), detectedCount, assignment.matches);
        }
        TargetMatch match = violation.match;
        switch (violation.reason) {
            case POSITION_MISMATCH:
                return rejected(Reason.POSITION_MISMATCH,
                        "Move the " + match.targetLabel + " toward its highlighted chassis target"
                                + formatError(match.normalizedPositionError, " of chassis scale") + ".",
                        detectedCount, assignment.matches);
            case ORIENTATION_MISMATCH:
                return rejected(Reason.ORIENTATION_MISMATCH,
                        "Rotate the " + match.targetLabel + " by about "
                                + Math.round(match.orientationErrorDegrees)
                                + "° to match the target orientation.",
                        detectedCount, assignment.matches);
            case SCALE_MISMATCH:
            default:
                String size = match.scaleRatio < 1.0 ? "too small" : "too large";
                return rejected(Reason.SCALE_MISMATCH,
                        "The " + match.targetLabel + " appears " + size
                                + " relative to its chassis target; align the complete outline.",
                        detectedCount, assignment.matches);
        }
    }

    private Assignment bestAssignment(QuadGeometry anchor, List<VisionPoint> anchorCorners,
                                      List<QuadGeometry> detections,
                                      List<TargetProjection> projections) {
        AssignmentSearch search = new AssignmentSearch();
        assignRecursively(anchor, anchorCorners, detections, projections, 0,
                new boolean[detections.size()], new ArrayList<>(), 0.0, search);
        return search.best;
    }

    private void assignRecursively(QuadGeometry anchor, List<VisionPoint> anchorCorners,
                                   List<QuadGeometry> detections,
                                   List<TargetProjection> projections,
                                   int targetIndex, boolean[] used,
                                   List<TargetMatch> matches, double score,
                                   AssignmentSearch search) {
        if (targetIndex == targets.size()) {
            if (search.best == null || score < search.best.score) {
                search.best = new Assignment(score, new ArrayList<>(matches));
            }
            return;
        }
        AuthoredTarget target = targets.get(targetIndex);
        TargetProjection projection = projections.get(targetIndex);
        for (int detectionIndex = 0; detectionIndex < detections.size(); detectionIndex++) {
            if (used[detectionIndex]) {
                continue;
            }
            TargetMatch match = compare(anchor, anchorCorners, target, projection,
                    detections.get(detectionIndex));
            double nextScore = score + assignmentScore(target, match);
            if (search.best != null && nextScore >= search.best.score) {
                continue;
            }
            used[detectionIndex] = true;
            matches.add(match);
            assignRecursively(anchor, anchorCorners, detections, projections, targetIndex + 1,
                    used, matches, nextScore, search);
            matches.remove(matches.size() - 1);
            used[detectionIndex] = false;
        }
    }

    private static TargetMatch compare(QuadGeometry anchor, List<VisionPoint> anchorCorners,
                                       AuthoredTarget target,
                                       TargetProjection projection, QuadGeometry detected) {
        double positionError = projection.center.distanceTo(detected.center)
                / anchor.characteristicLength;
        boolean positionAccepted = positionError <= target.positionTolerance;
        if (target.id.endsWith("_bracket")) {
            VisionPoint top = PlanarCoordinateMapper.projectUnitSquare(anchorCorners,
                    new VisionPoint(target.centerX, 0.0));
            VisionPoint bottom = PlanarCoordinateMapper.projectUnitSquare(anchorCorners,
                    new VisionPoint(target.centerX, 1.0));
            double boardWidth = (anchorCorners.get(0).distanceTo(anchorCorners.get(1))
                    + anchorCorners.get(3).distanceTo(anchorCorners.get(2))) * 0.5;
            double dx = bottom.getX() - top.getX();
            double dy = bottom.getY() - top.getY();
            double lengthSquared = dx * dx + dy * dy;
            double along = ((detected.center.getX() - top.getX()) * dx
                    + (detected.center.getY() - top.getY()) * dy) / lengthSquared;
            positionError = Math.abs((detected.center.getX() - top.getX()) * dy
                    - (detected.center.getY() - top.getY()) * dx)
                    / Math.sqrt(lengthSquared) / boardWidth;
            if (along < 0.10) {
                positionError = Math.max(positionError, target.positionTolerance + 0.10 - along);
            } else if (along > 1.05) {
                positionError = Math.max(positionError, target.positionTolerance + along - 1.05);
            }
            positionAccepted = positionError <= target.positionTolerance
                    && along >= 0.10 && along <= 1.05;
            // Socket rows, cable slots and the off-board background also lie in the lanes.
            if (positionAccepted && BracketSlotSurroundings.featureAt(
                    anchorCorners, detected.corners, boardWidth, target.centerX) != null) {
                positionAccepted = false;
                positionError = Math.max(positionError, target.positionTolerance + 0.10);
            }
        }
        double orientationError = circularDifference(
                projection.orientationDegrees, detected.orientationDegrees);
        double scaleRatio = Math.sqrt(detected.area / projection.area);
        double scaleError = Math.abs(scaleRatio - 1.0);
        return new TargetMatch(target.id, target.label,
                projection.center, detected.center,
                positionError, orientationError, scaleRatio,
                positionAccepted,
                orientationError <= target.orientationToleranceDegrees,
                scaleError <= target.scaleTolerance);
    }

    private static double assignmentScore(AuthoredTarget target, TargetMatch match) {
        return square(match.normalizedPositionError / target.positionTolerance)
                + square(match.orientationErrorDegrees
                / Math.max(1.0, target.orientationToleranceDegrees))
                + square(Math.abs(match.scaleRatio - 1.0) / target.scaleTolerance);
    }

    @Nullable
    private Violation worstViolation(List<TargetMatch> matches) {
        Violation worst = null;
        for (int index = 0; index < matches.size(); index++) {
            TargetMatch match = matches.get(index);
            AuthoredTarget target = targets.get(index);
            worst = selectWorse(worst, match, Reason.POSITION_MISMATCH,
                    match.normalizedPositionError / target.positionTolerance);
            worst = selectWorse(worst, match, Reason.ORIENTATION_MISMATCH,
                    match.orientationErrorDegrees
                            / Math.max(1.0, target.orientationToleranceDegrees));
            worst = selectWorse(worst, match, Reason.SCALE_MISMATCH,
                    Math.abs(match.scaleRatio - 1.0) / target.scaleTolerance);
        }
        return worst != null && worst.severity > 1.0 ? worst : null;
    }

    private static Violation selectWorse(@Nullable Violation current, TargetMatch match,
                                         Reason reason, double severity) {
        return current == null || severity > current.severity
                ? new Violation(match, reason, severity) : current;
    }

    @Nullable
    private static TargetProjection project(List<VisionPoint> anchorCorners,
                                            AuthoredTarget target) {
        double radians = Math.toRadians(target.rotationDegrees);
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        double halfWidth = target.width * 0.5;
        double halfHeight = target.height * 0.5;
        double[][] offsets = {
                {-halfWidth, -halfHeight},
                {halfWidth, -halfHeight},
                {halfWidth, halfHeight},
                {-halfWidth, halfHeight}
        };
        List<VisionPoint> projectedCorners = new ArrayList<>(4);
        for (double[] offset : offsets) {
            VisionPoint local = new VisionPoint(
                    target.centerX + cosine * offset[0] - sine * offset[1],
                    target.centerY + sine * offset[0] + cosine * offset[1]);
            VisionPoint projected = PlanarCoordinateMapper.projectUnitSquare(anchorCorners, local);
            if (projected == null) {
                return null;
            }
            projectedCorners.add(projected);
        }
        QuadGeometry geometry = QuadGeometry.from(projectedCorners);
        return geometry == null ? null : new TargetProjection(
                target.id, target.label, geometry.center, projectedCorners,
                geometry.orientationDegrees, geometry.area);
    }

    private Decision failure(Reason reason, String message, int detectedCount) {
        return new Decision(false, reason, message,
                targets.size(), detectedCount, Collections.emptyList());
    }

    private Decision rejected(Reason reason, String message, int detectedCount,
                              List<TargetMatch> matches) {
        return new Decision(false, reason, message, targets.size(), detectedCount, matches);
    }

    private String missingMessage(int detectedCount) {
        if (targets.size() == 2) {
            return "Show both " + subject + " and the complete chassis together; detected "
                    + detectedCount + " of 2 required components.";
        }
        return "Keep the " + subject + " and complete chassis visible together.";
    }

    private static String formatError(double value, String suffix) {
        return String.format(Locale.US, " (%.0f%%%s)", value * 100.0, suffix);
    }

    private static int count(@Nullable List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static double square(double value) {
        return value * value;
    }

    private static double circularDifference(double first, double second) {
        double difference = Math.abs(first - second) % 360.0;
        return Math.min(difference, 360.0 - difference);
    }

    private static double polygonArea(List<VisionPoint> corners) {
        double doubled = 0.0;
        for (int index = 0; index < corners.size(); index++) {
            VisionPoint current = corners.get(index);
            VisionPoint next = corners.get((index + 1) % corners.size());
            doubled += current.getX() * next.getY() - next.getX() * current.getY();
        }
        return Math.abs(doubled) * 0.5;
    }

    private static boolean isConvex(List<VisionPoint> corners) {
        double sign = 0.0;
        for (int index = 0; index < 4; index++) {
            VisionPoint a = corners.get(index);
            VisionPoint b = corners.get((index + 1) % 4);
            VisionPoint c = corners.get((index + 2) % 4);
            double cross = (b.getX() - a.getX()) * (c.getY() - b.getY())
                    - (b.getY() - a.getY()) * (c.getX() - b.getX());
            if (Math.abs(cross) <= GEOMETRY_EPSILON) {
                return false;
            }
            if (sign == 0.0) {
                sign = Math.signum(cross);
            } else if (Math.signum(cross) != sign) {
                return false;
            }
        }
        return true;
    }

    private static ChassisRelativePlacementGate single(int stepNumber, String subject,
                                                        AuthoredTarget target) {
        return new ChassisRelativePlacementGate(stepNumber, subject, target);
    }

    private static ChassisRelativePlacementGate dual(int stepNumber, String subject,
                                                      AuthoredTarget first,
                                                      AuthoredTarget second) {
        return new ChassisRelativePlacementGate(stepNumber, subject, first, second);
    }

    private static AuthoredTarget target(String id, String label,
                                         double centerX, double centerY,
                                         double width, double height, double rotationDegrees,
                                         double positionTolerance,
                                         double orientationToleranceDegrees,
                                         double scaleTolerance) {
        return new AuthoredTarget(id, label, centerX, centerY, width, height,
                rotationDegrees, positionTolerance,
                orientationToleranceDegrees, scaleTolerance);
    }

    private static final class AuthoredTarget {
        final String id;
        final String label;
        final double centerX;
        final double centerY;
        final double width;
        final double height;
        final double rotationDegrees;
        final double positionTolerance;
        final double orientationToleranceDegrees;
        final double scaleTolerance;

        AuthoredTarget(String id, String label, double centerX, double centerY,
                       double width, double height, double rotationDegrees,
                       double positionTolerance, double orientationToleranceDegrees,
                       double scaleTolerance) {
            this.id = id;
            this.label = label;
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
            this.rotationDegrees = rotationDegrees;
            this.positionTolerance = positionTolerance;
            this.orientationToleranceDegrees = orientationToleranceDegrees;
            this.scaleTolerance = scaleTolerance;
        }
    }

    private static final class QuadGeometry {
        final List<VisionPoint> corners;
        final VisionPoint center;
        final double orientationDegrees;
        final double area;
        final double characteristicLength;

        QuadGeometry(List<VisionPoint> corners, VisionPoint center, double orientationDegrees,
                     double area, double characteristicLength) {
            this.corners = Collections.unmodifiableList(new ArrayList<>(corners));
            this.center = center;
            this.orientationDegrees = orientationDegrees;
            this.area = area;
            this.characteristicLength = characteristicLength;
        }

        @Nullable
        static QuadGeometry from(@Nullable List<VisionPoint> corners) {
            if (corners == null || corners.size() != 4) {
                return null;
            }
            for (VisionPoint corner : corners) {
                if (corner == null || !Double.isFinite(corner.getX())
                        || !Double.isFinite(corner.getY())) {
                    return null;
                }
            }
            if (!isConvex(corners)) {
                return null;
            }
            double minX = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (VisionPoint corner : corners) {
                minX = Math.min(minX, corner.getX());
                maxX = Math.max(maxX, corner.getX());
                minY = Math.min(minY, corner.getY());
                maxY = Math.max(maxY, corner.getY());
            }
            double boundsArea = (maxX - minX) * (maxY - minY);
            double area = polygonArea(corners);
            if (!(boundsArea > GEOMETRY_EPSILON)
                    || !(area > Math.max(GEOMETRY_EPSILON, boundsArea * 1e-5))) {
                return null;
            }
            VisionPoint center = PlanarCoordinateMapper.projectUnitSquare(
                    corners, new VisionPoint(0.5, 0.5));
            VisionPoint left = PlanarCoordinateMapper.projectUnitSquare(
                    corners, new VisionPoint(0.25, 0.5));
            VisionPoint right = PlanarCoordinateMapper.projectUnitSquare(
                    corners, new VisionPoint(0.75, 0.5));
            if (center == null || left == null || right == null
                    || left.distanceTo(right) <= GEOMETRY_EPSILON) {
                return null;
            }
            double orientation = Math.toDegrees(Math.atan2(
                    right.getY() - left.getY(), right.getX() - left.getX()));
            return new QuadGeometry(corners, center, orientation, area, Math.sqrt(area));
        }
    }

    private static final class Assignment {
        final double score;
        final List<TargetMatch> matches;

        Assignment(double score, List<TargetMatch> matches) {
            this.score = score;
            this.matches = Collections.unmodifiableList(matches);
        }
    }

    private static final class AssignmentSearch {
        @Nullable Assignment best;
    }

    private static final class Violation {
        final TargetMatch match;
        final Reason reason;
        final double severity;

        Violation(TargetMatch match, Reason reason, double severity) {
            this.match = match;
            this.reason = reason;
            this.severity = severity;
        }
    }
}
