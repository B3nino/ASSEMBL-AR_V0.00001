package dev.chyken.assembl_ar.workflow;

import java.util.Objects;

/** Presentation-only timer. It cannot supply recognition evidence or complete a step. */
public final class PlanFSession {
    public static final long DELAY_MS = 5_000;
    private String scope;
    private Object attempt;
    private long startedAt = -1;
    private boolean prompted, verified, rebindAttempt;

    /** Called on the UI thread with a monotonic clock, never from the detector loop. */
    public boolean update(String nextScope, Object nextAttempt, boolean enabled,
            boolean scanningReady, boolean scanVerified, long nowMs) {
        if (!Objects.equals(scope, nextScope)) {
            scope = nextScope; attempt = nextAttempt;
            prompted = false; verified = false; startedAt = -1; rebindAttempt = false;
        } else if (rebindAttempt) {
            // Pause/resume changes camera generations, but is not a new help attempt.
            attempt = nextAttempt; rebindAttempt = false;
        } else if (!Objects.equals(attempt, nextAttempt)) {
            attempt = nextAttempt; prompted = false; verified = false; startedAt = -1;
        }
        if (scanVerified) { verified = true; startedAt = -1; return false; }
        if (!enabled || !scanningReady || nextScope == null) { suspend(); return false; }
        if (verified) { // Explicit rescan can retain a screen identity.
            verified = false; prompted = false; startedAt = -1;
        }
        if (prompted) return false;
        if (startedAt < 0 || nowMs < startedAt) startedAt = nowMs;
        if (nowMs - startedAt < DELAY_MS) return false;
        prompted = true;
        return true;
    }

    public void markHelpOpened() { prompted = true; }
    public void suspend() { startedAt = -1; rebindAttempt = true; }
}
