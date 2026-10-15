package dev.chyken.assembl_ar.render;

/** Thread-safe, Android-free camera orbit state shared by touch input and the GL thread. */
final class OrbitController {
    static final float DEFAULT_YAW_DEGREES = -32f;
    static final float DEFAULT_PITCH_DEGREES = 30f;
    static final float DEFAULT_DISTANCE = 6.9f;
    static final float MIN_PITCH_DEGREES = -25f;
    static final float MAX_PITCH_DEGREES = 70f;
    static final float MIN_DISTANCE = 3.7f;
    static final float MAX_DISTANCE = 9.2f;

    private float yawDegrees;
    private float pitchDegrees;
    private float distance;

    OrbitController() {
        reset();
    }

    synchronized void orbitBy(float yawDeltaDegrees, float pitchDeltaDegrees) {
        if (Float.isFinite(yawDeltaDegrees)) {
            yawDegrees = wrapDegrees(yawDegrees + yawDeltaDegrees);
        }
        if (Float.isFinite(pitchDeltaDegrees)) {
            pitchDegrees = clamp(
                    pitchDegrees + pitchDeltaDegrees,
                    MIN_PITCH_DEGREES,
                    MAX_PITCH_DEGREES);
        }
    }

    /** A scale greater than one moves the camera closer to the robot. */
    synchronized void zoomBy(float scaleFactor) {
        if (Float.isFinite(scaleFactor) && scaleFactor > 0f) {
            distance = clamp(distance / scaleFactor, MIN_DISTANCE, MAX_DISTANCE);
        }
    }

    synchronized void reset() {
        yawDegrees = DEFAULT_YAW_DEGREES;
        pitchDegrees = DEFAULT_PITCH_DEGREES;
        distance = DEFAULT_DISTANCE;
    }

    synchronized void restore(float restoredYaw, float restoredPitch, float restoredDistance) {
        yawDegrees = Float.isFinite(restoredYaw)
                ? wrapDegrees(restoredYaw) : DEFAULT_YAW_DEGREES;
        pitchDegrees = Float.isFinite(restoredPitch)
                ? clamp(restoredPitch, MIN_PITCH_DEGREES, MAX_PITCH_DEGREES)
                : DEFAULT_PITCH_DEGREES;
        distance = Float.isFinite(restoredDistance)
                ? clamp(restoredDistance, MIN_DISTANCE, MAX_DISTANCE)
                : DEFAULT_DISTANCE;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(yawDegrees, pitchDegrees, distance);
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) {
            wrapped -= 360f;
        } else if (wrapped < -180f) {
            wrapped += 360f;
        }
        return wrapped;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static final class Snapshot {
        final float yawDegrees;
        final float pitchDegrees;
        final float distance;

        Snapshot(float yawDegrees, float pitchDegrees, float distance) {
            this.yawDegrees = yawDegrees;
            this.pitchDegrees = pitchDegrees;
            this.distance = distance;
        }
    }
}
