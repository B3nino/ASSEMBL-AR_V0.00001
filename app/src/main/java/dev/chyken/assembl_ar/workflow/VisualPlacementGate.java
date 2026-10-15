package dev.chyken.assembl_ar.workflow;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Stateful visual-placement gate for normalized homography corners and solvePnP translation.
 *
 * <p>Identity alone is intentionally insufficient. A frame must contain a visible, convex
 * quadrilateral of a useful size and (when the step requires spatial guidance) a finite camera
 * pose in front of the camera. The projected geometry and pose must then remain stable for the
 * configured number of accepted frames. One brief missing recognition/pose observation is
 * tolerated without advancing the streak, because a camera detector can flicker for one frame
 * even while the physical target has not moved.</p>
 */
public final class VisualPlacementGate {
    private static final float FRAME_MARGIN = 0.025f;
    private static final float CENTER_MARGIN = 0.10f;
    private static final float MAX_PROJECTED_AREA = 0.82f;
    // Allow modest hand motion and homography/solvePnP jitter between observations. These are
    // steadiness limits only; identity, orientation, polarity and board-relative placement are
    // validated separately by the workflow before it offers the physical confirmation.
    private static final float MAX_CENTER_DRIFT = 0.05f;
    private static final float MAX_RELATIVE_AREA_DRIFT = 0.25f;
    private static final float MAX_EDGE_ANGLE_DRIFT_DEGREES = 15f;
    private static final double MAX_RELATIVE_POSE_DRIFT = 0.20;
    // CameraX drops intermediate frames while the serial analyzer is busy. A valid result taking
    // longer than 750 ms is not evidence of movement. Allow a three-second capture interval for
    // slower sustained processing; each accepted frame must still carry fresh identity, geometry,
    // and pose evidence, and longer pauses expire the streak.
    private static final long MAX_TRANSIENT_GAP_NANOS = TimeUnit.MILLISECONDS.toNanos(3_000L);

    public enum Reason {
        READY,
        HOLD_STEADY,
        MISSING_CORNERS,
        OUTSIDE_GUIDE,
        NON_CONVEX,
        TOO_SMALL,
        TOO_LARGE,
        MISSING_POSE,
        INVALID_POSE
    }

    /** Diagnostic explanation for starting or clearing a streak; never an acceptance rule. */
    public enum ResetReason {
        NONE, INITIAL, IDENTITY, GEOMETRY, POSE, TIME_GAP, MISSING_EVIDENCE
    }

    public static final class Observation {
        private final Reason reason;
        private final int stableFrames;
        private final boolean frameValid;
        private final boolean ready;
        private final double frameGapMillis;
        private final ResetReason resetReason;
        private final double centerDrift;
        private final double relativeAreaDrift;
        private final double edgeAngleDriftDegrees;
        private final double relativePoseDrift;

        private Observation(Reason reason, int stableFrames, boolean frameValid, boolean ready,
                            double frameGapMillis, ResetReason resetReason,
                            double centerDrift, double relativeAreaDrift,
                            double edgeAngleDriftDegrees, double relativePoseDrift) {
            this.reason = reason;
            this.stableFrames = stableFrames;
            this.frameValid = frameValid;
            this.ready = ready;
            this.frameGapMillis = frameGapMillis;
            this.resetReason = resetReason;
            this.centerDrift = centerDrift;
            this.relativeAreaDrift = relativeAreaDrift;
            this.edgeAngleDriftDegrees = edgeAngleDriftDegrees;
            this.relativePoseDrift = relativePoseDrift;
        }

        public Reason getReason() {
            return reason;
        }

        public int getStableFrames() {
            return stableFrames;
        }

        public boolean isFrameValid() {
            return frameValid;
        }

        public boolean isReady() {
            return ready;
        }

        /** Camera timestamp gap since the prior observation, or NaN for the first observation. */
        public double getFrameGapMillis() { return frameGapMillis; }

        public ResetReason getResetReason() { return resetReason; }

        public double getCenterDrift() { return centerDrift; }
        public double getRelativeAreaDrift() { return relativeAreaDrift; }
        public double getEdgeAngleDriftDegrees() { return edgeAngleDriftDegrees; }
        public double getRelativePoseDrift() { return relativePoseDrift; }

        /** Limits in the same order as the four motion metrics; evaluated only for diagnostics. */
        public String getStabilityLimits() {
            return MAX_CENTER_DRIFT + "/" + MAX_RELATIVE_AREA_DRIFT + "/"
                    + MAX_EDGE_ANGLE_DRIFT_DEGREES + "/" + MAX_RELATIVE_POSE_DRIFT;
        }

        public long getMaximumFrameGapMillis() {
            return TimeUnit.NANOSECONDS.toMillis(MAX_TRANSIENT_GAP_NANOS);
        }
    }

    private final TransientFrameStreakGate stableFrameGate;
    private String previousComponentId = "";
    private float previousCenterX = Float.NaN;
    private float previousCenterY = Float.NaN;
    private float previousArea = Float.NaN;
    private float previousAngleDegrees = Float.NaN;
    private double[] previousTranslation;
    private boolean hasObservedTimestamp;
    private long previousObservedTimestampNanos;
    private long lastAcceptedTimestampNanos;

    public VisualPlacementGate(int requiredStableFrames) {
        if (requiredStableFrames < 1) {
            throw new IllegalArgumentException("requiredStableFrames must be positive");
        }
        stableFrameGate = new TransientFrameStreakGate(
                requiredStableFrames, MAX_TRANSIENT_GAP_NANOS);
    }

    /**
     * Evaluates one frame. Corners are x/y pairs normalized to [0,1] in reference-corner order.
     * The minimum projected area is supplied by the active step so small pin/wire targets can use
     * a more permissive threshold than larger mechanical parts.
     */
    public synchronized Observation observe(
            String componentId,
            float[] corners,
            double[] translation,
            boolean poseRequired,
            float minimumProjectedArea) {
        return observe(componentId, corners, translation, poseRequired,
                minimumProjectedArea, System.nanoTime());
    }

    public synchronized Observation observe(
            String componentId,
            float[] corners,
            double[] translation,
            boolean poseRequired,
            float minimumProjectedArea,
            long frameTimestampNanos) {
        if (componentId == null || componentId.trim().isEmpty()
                || corners == null || corners.length != 8) {
            return transientInvalid(Reason.MISSING_CORNERS, frameTimestampNanos);
        }
        if (!(minimumProjectedArea > 0f) || minimumProjectedArea >= MAX_PROJECTED_AREA) {
            throw new IllegalArgumentException("minimumProjectedArea is outside the valid range");
        }
        for (float coordinate : corners) {
            if (!Float.isFinite(coordinate)) {
                return transientInvalid(Reason.MISSING_CORNERS, frameTimestampNanos);
            }
        }

        float centerX = 0f;
        float centerY = 0f;
        for (int index = 0; index < 4; index++) {
            float x = corners[index * 2];
            float y = corners[index * 2 + 1];
            if (x < FRAME_MARGIN || x > 1f - FRAME_MARGIN
                    || y < FRAME_MARGIN || y > 1f - FRAME_MARGIN) {
                reset();
                return observation(Reason.OUTSIDE_GUIDE, 0, false, false,
                        frameTimestampNanos, ResetReason.GEOMETRY);
            }
            centerX += x;
            centerY += y;
        }
        centerX /= 4f;
        centerY /= 4f;
        if (centerX < CENTER_MARGIN || centerX > 1f - CENTER_MARGIN
                || centerY < CENTER_MARGIN || centerY > 1f - CENTER_MARGIN) {
            reset();
            return observation(Reason.OUTSIDE_GUIDE, 0, false, false,
                    frameTimestampNanos, ResetReason.GEOMETRY);
        }
        if (!isConvex(corners)) {
            reset();
            return observation(Reason.NON_CONVEX, 0, false, false,
                    frameTimestampNanos, ResetReason.GEOMETRY);
        }

        float area = projectedArea(corners);
        if (area < minimumProjectedArea) {
            reset();
            return observation(Reason.TOO_SMALL, 0, false, false,
                    frameTimestampNanos, ResetReason.GEOMETRY);
        }
        if (area > MAX_PROJECTED_AREA) {
            reset();
            return observation(Reason.TOO_LARGE, 0, false, false,
                    frameTimestampNanos, ResetReason.GEOMETRY);
        }

        if (poseRequired && translation == null) {
            return transientInvalid(Reason.MISSING_POSE, frameTimestampNanos);
        }
        if (translation != null && !isValidPose(translation)) {
            return transientInvalid(Reason.INVALID_POSE, frameTimestampNanos);
        }

        float angleDegrees = (float) Math.toDegrees(Math.atan2(
                corners[3] - corners[1], corners[2] - corners[0]));
        boolean sameTarget = componentId.equals(previousComponentId);
        double centerDrift = distance(centerX, centerY, previousCenterX, previousCenterY);
        double areaDrift = relativeDifference(area, previousArea);
        double angleDrift = circularDifference(angleDegrees, previousAngleDegrees);
        double poseDrift = relativePoseDrift(translation, previousTranslation);
        boolean cornersStable = centerDrift <= MAX_CENTER_DRIFT
                && areaDrift <= MAX_RELATIVE_AREA_DRIFT
                && angleDrift <= MAX_EDGE_ANGLE_DRIFT_DEGREES;
        boolean translationStable = poseDrift <= MAX_RELATIVE_POSE_DRIFT;
        boolean geometryStable = sameTarget && cornersStable && translationStable;
        ResetReason resetReason = previousComponentId.isEmpty() ? ResetReason.INITIAL
                : !sameTarget ? ResetReason.IDENTITY
                : !cornersStable ? ResetReason.GEOMETRY
                : !translationStable ? ResetReason.POSE
                : stableFrameGate.getAcceptedFrames() > 0
                && frameTimestampNanos - lastAcceptedTimestampNanos > MAX_TRANSIENT_GAP_NANOS
                ? ResetReason.TIME_GAP : ResetReason.NONE;

        if (!geometryStable) {
            stableFrameGate.reset();
        }
        boolean ready = stableFrameGate.observe(true, frameTimestampNanos);
        lastAcceptedTimestampNanos = frameTimestampNanos;
        previousComponentId = componentId;
        previousCenterX = centerX;
        previousCenterY = centerY;
        previousArea = area;
        previousAngleDegrees = angleDegrees;
        previousTranslation = translation == null ? null : Arrays.copyOf(translation, 3);

        return observation(ready ? Reason.READY : Reason.HOLD_STEADY,
                stableFrameGate.getAcceptedFrames(), true, ready, frameTimestampNanos, resetReason,
                centerDrift, areaDrift, angleDrift, poseDrift);
    }

    /** Records a detector dropout when no trustworthy identity/corners reached this gate. */
    public synchronized Observation observeUnavailable(long frameTimestampNanos) {
        return transientInvalid(Reason.MISSING_CORNERS, frameTimestampNanos);
    }

    public synchronized void reset() {
        previousComponentId = "";
        previousCenterX = Float.NaN;
        previousCenterY = Float.NaN;
        previousArea = Float.NaN;
        previousAngleDegrees = Float.NaN;
        previousTranslation = null;
        stableFrameGate.reset();
    }

    private Observation transientInvalid(Reason reason, long frameTimestampNanos) {
        int previousFrames = stableFrameGate.getAcceptedFrames();
        stableFrameGate.observe(false, frameTimestampNanos);
        int retainedFrames = stableFrameGate.getAcceptedFrames();
        ResetReason resetReason = ResetReason.NONE;
        if (retainedFrames == 0) {
            if (previousFrames > 0) {
                resetReason = frameTimestampNanos - lastAcceptedTimestampNanos > MAX_TRANSIENT_GAP_NANOS
                        ? ResetReason.TIME_GAP
                        : reason == Reason.MISSING_POSE || reason == Reason.INVALID_POSE
                        ? ResetReason.POSE : ResetReason.MISSING_EVIDENCE;
            }
            clearPreviousObservation();
        }
        return observation(reason, retainedFrames, false, false, frameTimestampNanos, resetReason);
    }

    private void clearPreviousObservation() {
        previousComponentId = "";
        previousCenterX = Float.NaN;
        previousCenterY = Float.NaN;
        previousArea = Float.NaN;
        previousAngleDegrees = Float.NaN;
        previousTranslation = null;
    }

    private Observation observation(Reason reason, int stableFrames, boolean frameValid,
                                    boolean ready, long timestampNanos, ResetReason resetReason) {
        return observation(reason, stableFrames, frameValid, ready, timestampNanos, resetReason,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    private Observation observation(Reason reason, int stableFrames, boolean frameValid,
                                    boolean ready, long timestampNanos, ResetReason resetReason,
                                    double centerDrift, double areaDrift, double angleDrift,
                                    double poseDrift) {
        double gapMillis = hasObservedTimestamp
                ? (timestampNanos - previousObservedTimestampNanos) / 1_000_000.0 : Double.NaN;
        hasObservedTimestamp = true;
        previousObservedTimestampNanos = timestampNanos;
        return new Observation(reason, stableFrames, frameValid, ready, gapMillis, resetReason,
                centerDrift, areaDrift, angleDrift, poseDrift);
    }

    private static boolean isConvex(float[] corners) {
        float sign = 0f;
        for (int index = 0; index < 4; index++) {
            int next = (index + 1) % 4;
            int after = (index + 2) % 4;
            float ax = corners[next * 2] - corners[index * 2];
            float ay = corners[next * 2 + 1] - corners[index * 2 + 1];
            float bx = corners[after * 2] - corners[next * 2];
            float by = corners[after * 2 + 1] - corners[next * 2 + 1];
            float cross = ax * by - ay * bx;
            // Reject degenerate turns, not legitimate small screw outlines. The separately
            // configured projected-area floor determines whether a convex part is large enough.
            if (Math.abs(cross) < 0.00000001f) {
                return false;
            }
            if (sign == 0f) {
                sign = Math.signum(cross);
            } else if (Math.signum(cross) != sign) {
                return false;
            }
        }
        return true;
    }

    private static float projectedArea(float[] corners) {
        float doubledArea = 0f;
        for (int index = 0; index < 4; index++) {
            int next = (index + 1) % 4;
            doubledArea += corners[index * 2] * corners[next * 2 + 1]
                    - corners[next * 2] * corners[index * 2 + 1];
        }
        return Math.abs(doubledArea) * 0.5f;
    }

    private static boolean isValidPose(double[] translation) {
        if (translation.length != 3) {
            return false;
        }
        for (double coordinate : translation) {
            if (!Double.isFinite(coordinate)) {
                return false;
            }
        }
        // solvePnP uses the camera coordinate system; a valid object lies at positive Z.
        return translation[2] > 0.0001 && vectorLength(translation) > 0.0001;
    }

    private static double relativePoseDrift(double[] current, double[] previous) {
        if (current == null && previous == null) {
            return 0.0;
        }
        if (current == null || previous == null) {
            return Double.NaN;
        }
        double deltaX = current[0] - previous[0];
        double deltaY = current[1] - previous[1];
        double deltaZ = current[2] - previous[2];
        double delta = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        double scale = Math.max(0.0001, Math.max(vectorLength(current), vectorLength(previous)));
        return delta / scale;
    }

    private static double vectorLength(double[] vector) {
        return Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1]
                + vector[2] * vector[2]);
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float deltaX = x1 - x2;
        float deltaY = y1 - y2;
        return (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
    }

    private static float relativeDifference(float first, float second) {
        return Math.abs(first - second) / Math.max(0.0001f, Math.max(first, second));
    }

    private static float circularDifference(float first, float second) {
        float difference = Math.abs(first - second) % 360f;
        return Math.min(difference, 360f - difference);
    }
}
