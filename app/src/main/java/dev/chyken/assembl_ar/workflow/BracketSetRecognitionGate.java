package dev.chyken.assembl_ar.workflow;

import dev.chyken.assembl_ar.vision.RecognitionResult;

/** Verifies loose bracket inventory with simultaneous, distinct observations in each frame. */
public final class BracketSetRecognitionGate {
    // Shares the live-evidence limit of the other assembly gates. A one-second limit reset the
    // count whenever analysis of a four-bracket frame took about a second on a device.
    private static final long MAXIMUM_FRAME_GAP_NANOS =
            MotorAssemblyGate.MAXIMUM_FRAME_GAP_MILLIS * 1_000_000L;
    private final int requiredCount;
    private final int stableFrames;
    private int visibleCount;
    private int acceptedFrames;
    private long lastTimestamp = Long.MIN_VALUE;
    private boolean ready;

    public BracketSetRecognitionGate() { this(4, 4); }

    public BracketSetRecognitionGate(int requiredCount, int stableFrames) {
        if (requiredCount < 2 || requiredCount > 4 || stableFrames < 1) {
            throw new IllegalArgumentException("Bracket set requires 2-4 pieces and positive frame count");
        }
        this.requiredCount = requiredCount;
        this.stableFrames = stableFrames;
    }

    public boolean observe(RecognitionResult result) {
        return observe(result, result == null ? 0L : result.getFrameTimestampNanos());
    }

    /** @param timestamp the frame's evidence time, which may exclude analyzer processing time */
    public boolean observe(RecognitionResult result, long timestamp) {
        if (result == null) { reset(); return false; }
        // Re-delivered results and old frames cannot contribute additional evidence.
        if (timestamp <= lastTimestamp) return ready;
        if (lastTimestamp != Long.MIN_VALUE && timestamp - lastTimestamp > MAXIMUM_FRAME_GAP_NANOS) {
            acceptedFrames = 0;
        }
        lastTimestamp = timestamp;
        visibleCount = result.getDetectedInstanceCount("motor_bracket");
        boolean accepted = visibleCount >= requiredCount;
        acceptedFrames = accepted ? Math.min(stableFrames, acceptedFrames + 1) : 0;
        ready = accepted && acceptedFrames >= stableFrames;
        return ready;
    }

    public boolean isReady() { return ready; }
    public int getVisibleCount() { return visibleCount; }
    public int getRequiredCount() { return requiredCount; }

    public void reset() {
        visibleCount = acceptedFrames = 0;
        lastTimestamp = Long.MIN_VALUE;
        ready = false;
    }
}
