package dev.chyken.assembl_ar.workflow;

/** Requires the same frame-scoped policy to remain accepted for consecutive observations. */
public final class ConsecutiveAcceptanceGate {
    private final int requiredFrames;
    private int acceptedFrames;

    public ConsecutiveAcceptanceGate(int requiredFrames) {
        if (requiredFrames <= 0) {
            throw new IllegalArgumentException("Required frame count must be positive");
        }
        this.requiredFrames = requiredFrames;
    }

    /** A rejected or missing observation immediately discards all prior progress. */
    public boolean observe(boolean accepted) {
        if (!accepted) {
            acceptedFrames = 0;
            return false;
        }
        acceptedFrames = Math.min(requiredFrames, acceptedFrames + 1);
        return acceptedFrames >= requiredFrames;
    }

    public void reset() {
        acceptedFrames = 0;
    }

    public int getAcceptedFrames() {
        return acceptedFrames;
    }

    public int getRequiredFrames() {
        return requiredFrames;
    }
}
