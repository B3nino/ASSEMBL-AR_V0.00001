package dev.chyken.assembl_ar.workflow.phase;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;
import dev.chyken.assembl_ar.workflow.GuidedRecognitionCatalog;

/**
 * Resolves where a learner resumes in the phase guide from the saved manual-section progress,
 * phase records and older per-card checkpoints.
 *
 * <p>Completed manual sections stay completed. A partially finished section resumes at its
 * earliest unfinished phase: an earlier scanned bracket or a single confirmed card never implies
 * that a whole group is installed. The only per-card evidence carried forward is a split
 * section's earlier phase whose every checkpoint was already validated by the per-card guide.</p>
 */
public final class PhaseProgress {
    /** Reads persisted records; implemented over SharedPreferences in the app and fakes in tests. */
    public interface Records {
        boolean isPhaseComplete(String phaseId);
        /** Per-card checkpoint completion from the previous step-by-step guide. */
        boolean isLegacyCheckpointComplete(AssemblyStep section, GuidedStep card);
    }

    private PhaseProgress() { }

    /**
     * @param currentSection the first incomplete manual section from the database, or 0 when the
     *                       whole project is complete
     * @return the phase to resume, or null when every phase is complete
     */
    @Nullable
    public static AssemblyPhase resume(int currentSection, Records records) {
        Objects.requireNonNull(records, "records");
        if (currentSection <= 0) return null;
        for (AssemblyPhase phase : AssemblyPhaseCatalog.phases()) {
            if (phase.getLastSection() < currentSection) continue; // saved as complete sections
            if (phase.getFirstSection() == currentSection
                    && phase.getLastSection() == currentSection
                    && !AssemblyPhaseCatalog.sectionsCompletedBy(phase).contains(currentSection)
                    && (records.isPhaseComplete(phase.getId()) || legacyComplete(phase, records))) {
                continue; // an earlier part of a split section that was already fully finished
            }
            return phase;
        }
        return null;
    }

    /** Sections that must be saved as complete, in order, when this phase finishes. */
    public static List<Integer> sectionsToSave(AssemblyPhase phase, int currentSection) {
        List<Integer> result = new ArrayList<>();
        for (int section : AssemblyPhaseCatalog.sectionsCompletedBy(phase)) {
            if (section >= currentSection) result.add(section);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Redo invalidates the chosen phase and every later phase, because saved manual progress is
     * sequential. The result is the first manual section to reopen and the phases to clear.
     */
    public static Redo redoFrom(AssemblyPhase phase) {
        List<String> cleared = new ArrayList<>();
        boolean started = false;
        for (AssemblyPhase candidate : AssemblyPhaseCatalog.phases()) {
            started |= candidate == phase;
            if (started) cleared.add(candidate.getId());
        }
        if (!started) throw new IllegalArgumentException("Unknown phase " + phase.getId());
        return new Redo(phase.getFirstSection(), cleared);
    }

    public static final class Redo {
        private final int reopenSection;
        private final List<String> clearedPhaseIds;

        Redo(int reopenSection, List<String> clearedPhaseIds) {
            this.reopenSection = reopenSection;
            this.clearedPhaseIds = Collections.unmodifiableList(new ArrayList<>(clearedPhaseIds));
        }

        public int getReopenSection() { return reopenSection; }
        public List<String> getClearedPhaseIds() { return clearedPhaseIds; }
    }

    private static boolean legacyComplete(AssemblyPhase phase, Records records) {
        boolean anyCheckpoint = false;
        for (AssemblyPhase.SourceRange range : phase.getSources()) {
            AssemblyStep section = AssemblyPhaseCatalog.section(range.getSection());
            for (GuidedStep card : GuidedAssemblySequence.forManualStep(section)) {
                if (!range.contains(card.getPrimaryInstruction().getOrder())) continue;
                if (GuidedRecognitionCatalog.checkpointFor(section, card) == null) continue;
                anyCheckpoint = true;
                if (!records.isLegacyCheckpointComplete(section, card)) return false;
            }
        }
        return anyCheckpoint;
    }
}
