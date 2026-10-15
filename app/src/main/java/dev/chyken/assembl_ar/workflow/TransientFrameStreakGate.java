package dev.chyken.assembl_ar.workflow;

/**
 * Counts accepted camera frames while tolerating one short recognition dropout.
 *
 * <p>A single rejected frame preserves the accepted-frame count when it arrives within the
 * configured interval after the last accepted frame. A second consecutive rejection, or an
 * accepted frame arriving after that interval, starts a new streak. Rejected frames never make
 * the gate ready by themselves.</p>
 */
public final class TransientFrameStreakGate {
    private final int requiredFrames;
    private final long maximumTransientGapNanos;

    private int acceptedFrames;
    private boolean transientMissPending;
    private boolean hasAcceptedFrame;
    private boolean hasObservedFrame;
    private long lastAcceptedTimestampNanos;
    private long lastObservedTimestampNanos;

    public TransientFrameStreakGate(int requiredFrames, long maximumTransientGapNanos) {
        if (requiredFrames < 1) {
            throw new IllegalArgumentException("requiredFrames must be positive");
        }
        if (maximumTransientGapNanos < 0L) {
            throw new IllegalArgumentException("maximumTransientGapNanos cannot be negative");
        }
        this.requiredFrames = requiredFrames;
        this.maximumTransientGapNanos = maximumTransientGapNanos;
    }

    /**
     * Observes one camera result at its monotonic frame timestamp.
     *
     * @return true only when this accepted observation completes or maintains the required streak
     */
    public synchronized boolean observe(boolean accepted, long frameTimestampNanos) {
        if (hasObservedFrame && frameTimestampNanos < lastObservedTimestampNanos) {
            throw new IllegalArgumentException("Frame timestamps must be monotonic");
        }
        hasObservedFrame = true;
        lastObservedTimestampNanos = frameTimestampNanos;

        if (!accepted) {
            if (!hasAcceptedFrame) {
                return false;
            }
            long gap = frameTimestampNanos - lastAcceptedTimestampNanos;
            if (transientMissPending || gap > maximumTransientGapNanos) {
                clearStreak();
            } else {
                transientMissPending = true;
            }
            return false;
        }

        if (hasAcceptedFrame
                && frameTimestampNanos - lastAcceptedTimestampNanos
                > maximumTransientGapNanos) {
            clearStreak();
        }
        acceptedFrames = Math.min(requiredFrames, acceptedFrames + 1);
        lastAcceptedTimestampNanos = frameTimestampNanos;
        hasAcceptedFrame = true;
        transientMissPending = false;
        return acceptedFrames >= requiredFrames;
    }

    public synchronized void reset() {
        clearStreak();
        hasObservedFrame = false;
        lastObservedTimestampNanos = 0L;
    }

    public synchronized int getAcceptedFrames() {
        return acceptedFrames;
    }

    public int getRequiredFrames() {
        return requiredFrames;
    }

    private void clearStreak() {
        acceptedFrames = 0;
        transientMissPending = false;
        hasAcceptedFrame = false;
        lastAcceptedTimestampNanos = 0L;
    }
}
