package dev.chyken.assembl_ar.vision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Visible, board-relative motor evidence; it does not certify fasteners or electrical polarity. */
public final class MotorAssemblyObservation {
    public enum Quality { GOOD, BLURRED, TOO_DARK, GLARE, VIEW_TOO_OBLIQUE, ANCHOR_UNAVAILABLE }
    public enum SlotState { PRESENT, MISSING, MISALIGNED, ORIENTATION_UNCONFIRMED }

    public static final class Slot {
        private final SlotState state;
        private final double confidence;
        private final String message;
        private final List<VisionPoint> projectedCorners;

        public Slot(SlotState state, double confidence, String message,
                    List<VisionPoint> projectedCorners) {
            this.state = Objects.requireNonNull(state, "state");
            if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("Confidence must be between zero and one");
            }
            this.confidence = confidence;
            this.message = Objects.requireNonNull(message, "message");
            this.projectedCorners = Collections.unmodifiableList(new ArrayList<>(
                    Objects.requireNonNull(projectedCorners, "projectedCorners")));
        }

        public static Slot missing(String message) {
            return new Slot(SlotState.MISSING, 0.0, message, Collections.emptyList());
        }

        public SlotState getState() { return state; }
        public double getConfidence() { return confidence; }
        public String getMessage() { return message; }
        public List<VisionPoint> getProjectedCorners() { return projectedCorners; }
        public boolean isAccepted() { return state == SlotState.PRESENT; }
    }

    private final Quality quality;
    private final Slot left;
    private final Slot right;

    public MotorAssemblyObservation(Quality quality, Slot left, Slot right) {
        this.quality = Objects.requireNonNull(quality, "quality");
        this.left = Objects.requireNonNull(left, "left");
        this.right = Objects.requireNonNull(right, "right");
    }

    public static MotorAssemblyObservation unavailable(Quality quality) {
        return new MotorAssemblyObservation(quality, Slot.missing("Waiting for a clear board view"),
                Slot.missing("Waiting for a clear board view"));
    }

    public Quality getQuality() { return quality; }
    public Slot getLeft() { return left; }
    public Slot getRight() { return right; }
}
