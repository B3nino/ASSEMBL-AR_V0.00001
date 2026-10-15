package dev.chyken.assembl_ar.workflow;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Accumulates recent, board-relative evidence for the two installed gear motors.
 *
 * <p>Six of the last ten usable observations must show both motors together. Each side also
 * accumulates its own evidence for progress feedback, but observations of different motors in
 * different frames cannot complete the assembly check. Readiness always requires fresh evidence
 * of the board and both motors in the current frame.</p>
 *
 * <p>Blurred frames and temporary anchor loss do not count as motor failures. They immediately
 * suspend readiness and may preserve evidence for at most two seconds without a usable anchor.
 * The caller must reset this gate when the tracked board changes, moves discontinuously, or the
 * assembly step changes.</p>
 */
public final class MotorAssemblyGate {
    private static final int WINDOW_SIZE = 10;
    private static final int REQUIRED_FRAMES = 6;
    /** Shared camera-cadence limit for evidence accumulation and the Activity's silence timer. */
    public static final long MAXIMUM_FRAME_GAP_MILLIS = 2_000L;
    private static final long MAXIMUM_GAP_NANOS = MAXIMUM_FRAME_GAP_MILLIS * 1_000_000L;
    // Six fresh observations must remain attainable at the slowest supported camera cadence.
    private static final long WINDOW_DURATION_NANOS = (REQUIRED_FRAMES - 1) * MAXIMUM_GAP_NANOS;

    private final Deque<Observation> observations = new ArrayDeque<>();
    private boolean hasObservedFrame;
    private boolean hasUsableAnchor;
    private long lastObservedTimestampNanos;
    private long lastUsableAnchorTimestampNanos;
    private Decision lastDecision = Decision.empty();
    private boolean requireBoth = true;

    /**
     * Observes one frame using its monotonic capture timestamp, in nanoseconds.
     *
     * <p>Duplicate or out-of-order frames leave the history untouched and never return ready.
     * Quality-rejected frames are ignored for counting, while time still expires old evidence.
     * A usable frame with a missing motor consumes a window slot and reduces confidence as
     * earlier observations leave the window.</p>
     */
    public synchronized Decision observe(long timestampNanos, boolean anchorValid,
                                         boolean frameQualityGood, boolean leftAccepted,
                                         boolean rightAccepted) {
        return observe(timestampNanos, anchorValid, frameQualityGood, leftAccepted, rightAccepted, true);
    }

    /** First-motor cards require the authored left slot; later cards require both in one frame. */
    public synchronized Decision observe(long timestampNanos, boolean anchorValid,
                                         boolean frameQualityGood, boolean leftAccepted,
                                         boolean rightAccepted, boolean bothRequired) {
        if (requireBoth != bothRequired) {
            reset();
            requireBoth = bothRequired;
        }
        if (hasObservedFrame && timestampNanos <= lastObservedTimestampNanos) {
            return new Decision(false, lastDecision.baseLocked, lastDecision.leftConfirmed,
                    lastDecision.rightConfirmed, lastDecision.acceptedFrames);
        }

        if (hasObservedFrame
                && timestampNanos - lastObservedTimestampNanos > MAXIMUM_GAP_NANOS) {
            clearEvidence();
        }
        hasObservedFrame = true;
        lastObservedTimestampNanos = timestampNanos;

        if (hasUsableAnchor
                && timestampNanos - lastUsableAnchorTimestampNanos > MAXIMUM_GAP_NANOS) {
            clearEvidence();
        }
        while (!observations.isEmpty()
                && timestampNanos - observations.peekFirst().timestampNanos
                > WINDOW_DURATION_NANOS) {
            observations.removeFirst();
        }

        boolean usable = anchorValid && frameQualityGood;
        if (usable) {
            hasUsableAnchor = true;
            lastUsableAnchorTimestampNanos = timestampNanos;
            observations.addLast(new Observation(timestampNanos, leftAccepted, rightAccepted));
            if (observations.size() > WINDOW_SIZE) {
                observations.removeFirst();
            }
        }

        int simultaneous = 0;
        int left = 0;
        int right = 0;
        for (Observation observation : observations) {
            if (observation.left) {
                left++;
            }
            if (observation.right) {
                right++;
            }
            if (observation.left && (!bothRequired || observation.right)) {
                simultaneous++;
            }
        }

        lastDecision = new Decision(
                usable && leftAccepted && (!bothRequired || rightAccepted)
                        && simultaneous >= REQUIRED_FRAMES,
                usable,
                usable && leftAccepted && left >= REQUIRED_FRAMES,
                usable && rightAccepted && right >= REQUIRED_FRAMES,
                simultaneous);
        return lastDecision;
    }

    /** Discards evidence, timestamps, and progress when beginning a new assembly observation. */
    public synchronized void reset() {
        clearEvidence();
        hasObservedFrame = false;
        lastObservedTimestampNanos = 0L;
        lastDecision = Decision.empty();
    }

    private void clearEvidence() {
        observations.clear();
        hasUsableAnchor = false;
        lastUsableAnchorTimestampNanos = 0L;
    }

    private static final class Observation {
        final long timestampNanos;
        final boolean left;
        final boolean right;

        Observation(long timestampNanos, boolean left, boolean right) {
            this.timestampNanos = timestampNanos;
            this.left = left;
            this.right = right;
        }
    }

    /** Immutable result for one observation; this never confirms the physical fastening check. */
    public static final class Decision {
        private final boolean ready;
        private final boolean baseLocked;
        private final boolean leftConfirmed;
        private final boolean rightConfirmed;
        private final int acceptedFrames;

        private Decision(boolean ready, boolean baseLocked, boolean leftConfirmed,
                         boolean rightConfirmed, int acceptedFrames) {
            this.ready = ready;
            this.baseLocked = baseLocked;
            this.leftConfirmed = leftConfirmed;
            this.rightConfirmed = rightConfirmed;
            this.acceptedFrames = acceptedFrames;
        }

        private static Decision empty() {
            return new Decision(false, false, false, false, 0);
        }

        public boolean isReady() {
            return ready;
        }

        public boolean isBaseLocked() {
            return baseLocked;
        }

        public boolean isLeftConfirmed() {
            return leftConfirmed;
        }

        public boolean isRightConfirmed() {
            return rightConfirmed;
        }

        /** Number of recent usable observations that showed both motors simultaneously. */
        public int getAcceptedFrames() {
            return acceptedFrames;
        }

        public int getRequiredFrames() {
            return REQUIRED_FRAMES;
        }
    }
}
