package dev.chyken.assembl_ar.workflow.phase;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase.VisualTarget;

/**
 * One bounded Scan of a finished phase. Each visual target receives a truthful outcome.
 *
 * <p>Targets are probed one group at a time because each group uses its own recognition request;
 * a pair is always judged from the same frames. A target is accepted only when its existing
 * gate is ready on fresh frames of this session. A wrong or missing result must repeat on
 * consecutive fresh frames before it is established, so one noisy frame cannot trigger a
 * correction. An established error also revokes an earlier acceptance of that target, because a
 * remembered green target cannot pass after it was removed. Unclear views are never called
 * wrong: a group that stays unresolved for its evidence budget leaves the session inconclusive.</p>
 */
public final class PhaseVerificationSession {
    public enum Outcome {
        PENDING, ACCEPTED, MISSING, WRONG_IDENTITY, WRONG_POSITION, WRONG_ORIENTATION,
        AMBIGUOUS, UNOBSERVABLE;

        public boolean isError() {
            return this == MISSING || this == WRONG_IDENTITY || this == WRONG_POSITION
                    || this == WRONG_ORIENTATION;
        }
    }

    public enum Verdict { IN_PROGRESS, PASSED, CORRECTION_NEEDED, INCONCLUSIVE }

    /** One frame's classification of one target. */
    public static final class Observation {
        private final String targetId;
        private final Outcome outcome;
        private final boolean gateReady;
        private final String message;
        @Nullable private final String detectedComponentId;

        public Observation(String targetId, Outcome outcome, boolean gateReady, String message,
                           @Nullable String detectedComponentId) {
            this.targetId = Objects.requireNonNull(targetId, "targetId");
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            if (gateReady && outcome != Outcome.ACCEPTED) {
                throw new IllegalArgumentException("Only an accepted observation can be gate-ready");
            }
            this.gateReady = gateReady;
            this.message = message == null ? "" : message;
            this.detectedComponentId = detectedComponentId;
        }

        public static Observation of(String targetId, Outcome outcome, String message) {
            return new Observation(targetId, outcome, false, message, null);
        }

        public static Observation ready(String targetId) {
            return new Observation(targetId, Outcome.ACCEPTED, true, "", null);
        }

        public String getTargetId() { return targetId; }
        public Outcome getOutcome() { return outcome; }
        public boolean isGateReady() { return gateReady; }
        public String getMessage() { return message; }
        @Nullable public String getDetectedComponentId() { return detectedComponentId; }
    }

    /** The learner-facing state of one target. */
    public static final class TargetResult {
        private final VisualTarget target;
        private Outcome outcome = Outcome.PENDING;
        private String message = "";
        @Nullable private String detectedComponentId;
        private boolean exhausted;
        private Outcome streakOutcome;
        private int streak;

        TargetResult(VisualTarget target) { this.target = target; }

        public VisualTarget getTarget() { return target; }
        public Outcome getOutcome() { return outcome; }
        public String getMessage() { return message; }
        @Nullable public String getDetectedComponentId() { return detectedComponentId; }
        public boolean isAccepted() { return outcome == Outcome.ACCEPTED; }
        public boolean isEstablishedError() { return outcome.isError(); }
        /** The session stopped probing this target without a verdict. */
        public boolean isExhausted() { return exhausted; }
    }

    /** Consecutive fresh frames that establish a wrong position, orientation or identity. */
    public static final int ERROR_FRAMES = 3;
    /** Absence is claimed more cautiously than a measured misplacement. */
    public static final int MISSING_FRAMES = 5;
    /** Evidence time each probe group may take before it is reported as unclear. */
    public static final long GROUP_BUDGET_NANOS = 20_000_000_000L;

    private final Map<String, TargetResult> results = new LinkedHashMap<>();
    private final List<String> groups;
    private final long groupBudgetNanos;
    private int groupIndex;
    private long groupStartNanos;
    private long lastEvidenceNanos = Long.MIN_VALUE;
    private Verdict verdict = Verdict.IN_PROGRESS;
    @Nullable private TargetResult correction;

    public PhaseVerificationSession(AssemblyPhase phase, long startNanos) {
        this(phase, startNanos, GROUP_BUDGET_NANOS);
    }

    public PhaseVerificationSession(AssemblyPhase phase, long startNanos, long groupBudgetNanos) {
        Objects.requireNonNull(phase, "phase");
        if (!phase.hasVisualTargets()) {
            throw new IllegalArgumentException("A phase without camera targets has no Scan session");
        }
        if (groupBudgetNanos <= 0) throw new IllegalArgumentException("Budget must be positive");
        for (VisualTarget target : phase.getTargets()) {
            results.put(target.getId(), new TargetResult(target));
        }
        groups = phase.getProbeGroups();
        this.groupBudgetNanos = groupBudgetNanos;
        groupStartNanos = startNanos;
    }

    /** The group whose recognition request should be issued now; null once the session ended. */
    @Nullable
    public String getActiveGroup() {
        return verdict == Verdict.IN_PROGRESS && groupIndex < groups.size()
                ? groups.get(groupIndex) : null;
    }

    public List<VisualTarget> getActiveTargets() {
        String group = getActiveGroup();
        List<VisualTarget> active = new ArrayList<>();
        if (group == null) return active;
        for (TargetResult result : results.values()) {
            if (result.target.getProbeGroup().equals(group)) active.add(result.target);
        }
        return active;
    }

    public Verdict getVerdict() { return verdict; }

    @Nullable public TargetResult getCorrection() { return correction; }

    public List<TargetResult> getResults() {
        return Collections.unmodifiableList(new ArrayList<>(results.values()));
    }

    @Nullable public TargetResult getResult(String targetId) { return results.get(targetId); }

    /**
     * Applies one fresh frame. Observations may include targets outside the active group (for
     * example installed motors seen while a bracket slot is probed); those can revoke an earlier
     * acceptance or be accepted when their own gate is ready.
     */
    public Verdict observe(long evidenceNanos, List<Observation> observations) {
        if (verdict != Verdict.IN_PROGRESS || evidenceNanos <= lastEvidenceNanos) return verdict;
        lastEvidenceNanos = evidenceNanos;
        for (Observation observation : observations) {
            TargetResult result = results.get(observation.getTargetId());
            if (result == null) continue;
            apply(result, observation);
            if (result.isEstablishedError()) {
                correction = result;
                verdict = Verdict.CORRECTION_NEEDED;
                return verdict;
            }
        }
        advanceGroups(evidenceNanos);
        return verdict;
    }

    /**
     * Ends the session without a verdict for unresolved targets, for example when the camera
     * produced no frames at all. Nothing unresolved is accepted or called wrong.
     */
    public Verdict abandon(String reason) {
        if (verdict != Verdict.IN_PROGRESS) return verdict;
        for (TargetResult result : results.values()) {
            if (result.isAccepted()) continue;
            result.exhausted = true;
            if (!result.outcome.isError()) result.outcome = Outcome.UNOBSERVABLE;
            if (result.message.isEmpty()) result.message = reason == null ? "" : reason;
        }
        groupIndex = groups.size();
        advanceGroups(lastEvidenceNanos);
        return verdict;
    }

    /** Re-evaluates the evidence budget when no fresh frame arrives (camera covered, etc.). */
    public Verdict checkBudget(long evidenceNanos) {
        if (verdict == Verdict.IN_PROGRESS) advanceGroups(evidenceNanos);
        return verdict;
    }

    private void apply(TargetResult result, Observation observation) {
        Outcome outcome = observation.getOutcome();
        if (outcome == Outcome.ACCEPTED) {
            result.streak = 0;
            result.streakOutcome = null;
            if (observation.isGateReady()) {
                result.outcome = Outcome.ACCEPTED;
                result.message = observation.getMessage();
                result.exhausted = false;
            } else if (!result.isAccepted()) {
                result.message = observation.getMessage();
            }
            return;
        }
        if (outcome.isError()) {
            result.streak = outcome == result.streakOutcome ? result.streak + 1 : 1;
            result.streakOutcome = outcome;
            int required = outcome == Outcome.MISSING ? MISSING_FRAMES : ERROR_FRAMES;
            if (result.streak >= required) {
                result.outcome = outcome;
                result.message = observation.getMessage();
                result.detectedComponentId = observation.getDetectedComponentId();
            } else if (!result.isAccepted()) {
                result.message = observation.getMessage();
            }
            return;
        }
        // Unclear evidence neither accepts nor accuses; it only explains what to show next.
        result.streak = 0;
        result.streakOutcome = null;
        if (!result.isAccepted()) {
            if (outcome != Outcome.PENDING) result.outcome = outcome;
            result.message = observation.getMessage();
        }
    }

    private void advanceGroups(long evidenceNanos) {
        while (groupIndex < groups.size()) {
            String group = groups.get(groupIndex);
            if (groupAccepted(group)) {
                nextGroup(evidenceNanos);
                continue;
            }
            if (evidenceNanos - groupStartNanos > groupBudgetNanos) {
                for (TargetResult result : results.values()) {
                    if (result.target.getProbeGroup().equals(group) && !result.isAccepted()) {
                        result.exhausted = true;
                        if (result.outcome == Outcome.PENDING) result.outcome = Outcome.UNOBSERVABLE;
                    }
                }
                nextGroup(evidenceNanos);
                continue;
            }
            return;
        }
        boolean allAccepted = true;
        for (TargetResult result : results.values()) allAccepted &= result.isAccepted();
        verdict = allAccepted ? Verdict.PASSED : Verdict.INCONCLUSIVE;
    }

    private void nextGroup(long evidenceNanos) {
        groupIndex++;
        groupStartNanos = evidenceNanos;
    }

    private boolean groupAccepted(String group) {
        for (TargetResult result : results.values()) {
            if (result.target.getProbeGroup().equals(group) && !result.isAccepted()) return false;
        }
        return true;
    }
}
