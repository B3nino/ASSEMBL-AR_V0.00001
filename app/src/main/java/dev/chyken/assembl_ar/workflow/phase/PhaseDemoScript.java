package dev.chyken.assembl_ar.workflow.phase;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import dev.chyken.assembl_ar.model.AssemblyInstruction;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.InstructionType;
import dev.chyken.assembl_ar.model.PinConnection;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;
import dev.chyken.assembl_ar.workflow.GuidedCheckpoint;
import dev.chyken.assembl_ar.workflow.GuidedDemonstrationCatalog;
import dev.chyken.assembl_ar.workflow.GuidedRecognitionCatalog;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase.VisualTarget;
import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession.Outcome;
import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession.TargetResult;

/**
 * The complete demonstration of a phase: every action and check of its source instructions, in
 * manual order, as narrated chapters. Chapters reuse the per-action schematic and full authored
 * sentence (with warnings), so nothing is lost when per-action screens are no longer mandatory.
 * Playing a chapter never creates recognition evidence or completes anything.
 */
public final class PhaseDemoScript {
    /** Minimum on-screen time of each chapter: the schematic's full animation. */
    public static final long ANIMATION_MILLIS = 5_400L;
    private static final long MILLIS_PER_WORD = 420L;
    private static final long MAXIMUM_SILENT_CHAPTER_MILLIS = 16_000L;

    public static final class Chapter {
        private final int index;
        private final AssemblyStep section;
        private final GuidedStep card;
        private final GuidedDemonstrationCatalog.Demo demo;

        Chapter(int index, AssemblyStep section, GuidedStep card, GuidedDemonstrationCatalog.Demo demo) {
            this.index = index;
            this.section = section;
            this.card = card;
            this.demo = demo;
        }

        /** Zero-based position within the script that contains it. */
        public int getIndex() { return index; }
        public AssemblyStep getSection() { return section; }
        public int getSectionNumber() { return section.getStepNumber(); }
        public int getOrder() { return card.getPrimaryInstruction().getOrder(); }
        public GuidedStep getCard() { return card; }
        public GuidedDemonstrationCatalog.Demo getDemo() { return demo; }
        public String getTitle() { return demo.getShortTitle(); }
        public String getCaption() { return demo.getCaption(); }
        public String getNarration() { return demo.getSpokenInstruction(); }

        /** Time to show a chapter when no voice will report completion (muted or unavailable). */
        public long getSilentDurationMillis() { return silentDurationMillis(getNarration()); }
    }

    private final AssemblyPhase phase;
    private final List<Chapter> chapters;
    private final String introduction;

    private PhaseDemoScript(AssemblyPhase phase, List<Chapter> chapters, String introduction) {
        this.phase = phase;
        this.chapters = Collections.unmodifiableList(new ArrayList<>(chapters));
        this.introduction = introduction;
    }

    public static PhaseDemoScript forPhase(AssemblyPhase phase) {
        Objects.requireNonNull(phase, "phase");
        List<Chapter> chapters = new ArrayList<>();
        for (AssemblyPhase.SourceRange range : phase.getSources()) {
            AssemblyStep section = AssemblyPhaseCatalog.section(range.getSection());
            for (GuidedStep card : GuidedAssemblySequence.forManualStep(section)) {
                if (!range.contains(card.getPrimaryInstruction().getOrder())) continue;
                GuidedCheckpoint checkpoint = GuidedRecognitionCatalog.checkpointFor(section, card);
                chapters.add(new Chapter(chapters.size(), section, card,
                        GuidedDemonstrationCatalog.create(section, card, checkpoint)));
            }
        }
        return new PhaseDemoScript(phase, chapters, introductionFor(phase, chapters));
    }

    /** Chapters that demonstrate how to fix one target, in manual order. */
    public List<Chapter> correctionChapters(VisualTarget target) {
        List<Chapter> result = new ArrayList<>();
        for (Chapter chapter : chapters) {
            if (chapter.getSectionNumber() == target.getSection()
                    && target.getCorrectionOrders().contains(chapter.getOrder())) {
                result.add(chapter);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public AssemblyPhase getPhase() { return phase; }
    public List<Chapter> getChapters() { return chapters; }
    /** Spoken once before chapter one: what the phase builds and its notes. */
    public String getIntroduction() { return introduction; }
    /** The last chapter's view stays on screen as the finished-phase reference. */
    @Nullable public Chapter getFinalChapter() {
        return chapters.isEmpty() ? null : chapters.get(chapters.size() - 1);
    }

    /** Warnings in the phase, each stated once. */
    public List<String> getWarnings() {
        Set<String> warnings = new LinkedHashSet<>();
        for (AssemblyInstruction instruction : AssemblyPhaseCatalog.instructionsOf(phase)) {
            if (instruction.getType() == InstructionType.WARNING) warnings.add(instruction.getText());
        }
        return Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    /** Short, specific narration for an established error that precedes its correction chapters. */
    public static String correctionNarration(TargetResult result) {
        VisualTarget target = result.getTarget();
        String label = target.getLabel();
        String detail = result.getMessage().isEmpty() ? "" : " " + result.getMessage();
        switch (result.getOutcome()) {
            case WRONG_IDENTITY:
                String detected = result.getDetectedComponentId() == null ? "a different part"
                        : "the " + AssemblyPhaseCatalog.componentName(result.getDetectedComponentId());
                return "That is " + detected + ", not the "
                        + AssemblyPhaseCatalog.componentName(target.getComponentId())
                        + ". Watch where the " + label + " goes, then tap Scan again.";
            case WRONG_ORIENTATION:
                return "The " + label + " is facing the wrong way." + detail
                        + " Watch the correct direction, then tap Scan again.";
            case MISSING:
                return "The " + label + " is missing." + detail
                        + " Watch how to fit it, then tap Scan again.";
            case WRONG_POSITION:
            default:
                return "The " + label + " is not in its position." + detail
                        + " Watch how it is fitted, then tap Scan again.";
        }
    }

    /** A one-line result for the target list shown after a Scan. */
    public static String resultLine(TargetResult result) {
        String label = capitalize(result.getTarget().getLabel());
        switch (result.getOutcome()) {
            case ACCEPTED: return "✓ " + label;
            case MISSING: return "✗ " + label + " — missing";
            case WRONG_IDENTITY: return "✗ " + label + " — wrong part";
            case WRONG_POSITION: return "✗ " + label + " — wrong position";
            case WRONG_ORIENTATION: return "✗ " + label + " — wrong direction";
            case AMBIGUOUS:
            case UNOBSERVABLE:
                return "? " + label + " — not confirmed"
                        + (result.getMessage().isEmpty() ? "" : ": " + result.getMessage());
            case PENDING:
            default:
                return "… " + label + (result.getMessage().isEmpty() ? "" : " — " + result.getMessage());
        }
    }

    public static boolean isCorrectable(Outcome outcome) { return outcome.isError(); }

    /** Every source instruction and exact pin mapping of the phase, for Details. */
    public String detailsText() {
        StringBuilder text = new StringBuilder();
        for (AssemblyPhase.SourceRange range : phase.getSources()) {
            AssemblyStep section = AssemblyPhaseCatalog.section(range.getSection());
            if (text.length() > 0) text.append("\n\n");
            text.append("Manual section ").append(section.getStepNumber()).append(" · ")
                    .append(section.getTitle());
            for (AssemblyInstruction instruction : section.getInstructions()) {
                if (!range.contains(instruction.getOrder())) continue;
                text.append("\n").append(instruction.getOrder()).append(". ");
                if (instruction.getType() == InstructionType.WARNING) text.append("Warning: ");
                else if (instruction.getType() == InstructionType.NOTE) text.append("Note: ");
                else if (instruction.getType() == InstructionType.CHECK) text.append("Check: ");
                text.append(instruction.getText()).append(" (page ")
                        .append(instruction.getManualPage()).append(')');
            }
            boolean headed = false;
            for (PinConnection pin : section.getPinConnections()) {
                if (!pinBelongsTo(range, section, pin)) continue;
                if (!headed) {
                    text.append("\nExact pin mapping");
                    headed = true;
                }
                text.append("\n• ").append(AssemblyPhaseCatalog.componentName(pin.getSourceComponentId()))
                        .append(' ').append(pin.getSourcePin()).append(" → ")
                        .append(AssemblyPhaseCatalog.componentName(pin.getTargetComponentId()))
                        .append(' ').append(pin.getTargetPin());
                if (!pin.getNotes().isEmpty()) text.append(" — ").append(pin.getNotes());
            }
        }
        return text.toString();
    }

    /** A split section's pin map belongs to the phase that finishes it (section 11's wiring). */
    private static boolean pinBelongsTo(AssemblyPhase.SourceRange range, AssemblyStep section,
                                        PinConnection pin) {
        List<AssemblyInstruction> instructions = section.getInstructions();
        return range.isWholeSection()
                || range.contains(instructions.get(instructions.size() - 1).getOrder());
    }

    /** Reading-time estimate used when narration cannot report completion. */
    public static long silentDurationMillis(String narration) {
        int words = narration.trim().isEmpty() ? 0 : narration.trim().split("\\s+").length;
        return Math.max(ANIMATION_MILLIS, Math.min(MAXIMUM_SILENT_CHAPTER_MILLIS,
                words * MILLIS_PER_WORD));
    }

    private static String introductionFor(AssemblyPhase phase, List<Chapter> chapters) {
        StringBuilder intro = new StringBuilder(phase.getTitle()).append(". ")
                .append(phase.getSummary());
        Set<String> spokenElsewhere = new LinkedHashSet<>();
        for (Chapter chapter : chapters) spokenElsewhere.add(chapter.getNarration());
        Set<String> notes = new LinkedHashSet<>();
        for (AssemblyInstruction instruction : AssemblyPhaseCatalog.instructionsOf(phase)) {
            if (instruction.getType() != InstructionType.NOTE) continue;
            boolean repeated = false;
            for (String narration : spokenElsewhere) repeated |= narration.contains(instruction.getText());
            // The manual repeats some notes (a spacer note per tracker); say each once.
            if (!repeated && !isEditorialNote(instruction)) notes.add(instruction.getText());
        }
        for (String note : notes) intro.append(' ').append(note);
        if (chapters.size() == 1) {
            intro.append(" This demo has one step.");
        } else if (!chapters.isEmpty()) {
            intro.append(String.format(Locale.US, " This demo has %d steps.", chapters.size()));
        }
        return intro.toString();
    }

    /** Section 2's note about how the app expands the manual's "repeat" is not an instruction. */
    private static boolean isEditorialNote(AssemblyInstruction instruction) {
        return instruction.getText().startsWith("Page 8 instructs you to repeat");
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value
                : value.substring(0, 1).toUpperCase(Locale.US) + value.substring(1);
    }
}
