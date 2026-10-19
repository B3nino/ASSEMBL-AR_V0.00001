package dev.chyken.assembl_ar.workflow;

/**
 * Counts accepted camera frames while tolerating a configured number of short dropouts.
 *
 * <p>The two-argument constructor preserves the original one-miss behavior. Rejected frames
 * never make the gate ready by themselves, and the time window still bounds every streak.</p>
 */
public final class TransientFrameStreakGate {
    private final int requiredFrames;
    private final long maximumTransientGapNanos;
    private final int maximumConsecutiveMisses;

    private int acceptedFrames;
    private int consecutiveMisses;
    private boolean hasAcceptedFrame;
    private boolean hasObservedFrame;
    private long lastAcceptedTimestampNanos;
    private long lastObservedTimestampNanos;

    public TransientFrameStreakGate(int requiredFrames, long maximumTransientGapNanos) {
        this(requiredFrames, maximumTransientGapNanos, 1);
    }

    public TransientFrameStreakGate(int requiredFrames, long maximumTransientGapNanos,
                                    int maximumConsecutiveMisses) {
        if (requiredFrames < 1) {
            throw new IllegalArgumentException("requiredFrames must be positive");
        }
        if (maximumTransientGapNanos < 0L) {
            throw new IllegalArgumentException("maximumTransientGapNanos cannot be negative");
        }
        if (maximumConsecutiveMisses < 0) {
            throw new IllegalArgumentException("maximumConsecutiveMisses cannot be negative");
        }
        this.requiredFrames = requiredFrames;
        this.maximumTransientGapNanos = maximumTransientGapNanos;
        this.maximumConsecutiveMisses = maximumConsecutiveMisses;
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
            if (++consecutiveMisses > maximumConsecutiveMisses
                    || gap > maximumTransientGapNanos) {
                clearStreak();
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
        consecutiveMisses = 0;
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
        consecutiveMisses = 0;
        hasAcceptedFrame = false;
        lastAcceptedTimestampNanos = 0L;
    }
}
