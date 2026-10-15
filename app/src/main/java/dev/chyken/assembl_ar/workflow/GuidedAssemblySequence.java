package dev.chyken.assembl_ar.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.chyken.assembl_ar.model.AssemblyInstruction;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.InstructionType;

/**
 * Turns a manual section into focused, learner-sized guided steps.
 *
 * <p>The source manual mixes actions, checks, notes and warnings inside each of its 18 numbered
 * sections. Actions and checks become individual cards. Notes and warnings stay attached to the
 * closest relevant card so important context is visible without becoming a tap-through step of
 * its own.</p>
 */
public final class GuidedAssemblySequence {
    private GuidedAssemblySequence() {
    }

    public static List<GuidedStep> forManualStep(AssemblyStep step) {
        Objects.requireNonNull(step, "step");
        List<MutableGuidedStep> cards = new ArrayList<>();
        List<AssemblyInstruction> pendingContext = new ArrayList<>();

        for (AssemblyInstruction instruction : step.getInstructions()) {
            if (!isGuidedStep(instruction)) {
                pendingContext.add(instruction);
                continue;
            }

            MutableGuidedStep previous = cards.isEmpty() ? null : cards.get(cards.size() - 1);
            MutableGuidedStep current = new MutableGuidedStep(instruction);
            for (AssemblyInstruction context : pendingContext) {
                if (belongsToPrevious(context, previous, instruction)) {
                    previous.trailingContext.add(context);
                } else {
                    current.leadingContext.add(context);
                }
            }
            pendingContext.clear();
            cards.add(current);
        }

        if (!pendingContext.isEmpty()) {
            if (cards.isEmpty()) {
                // Defensive fallback for future authored sections containing only advisory copy.
                AssemblyInstruction primary = pendingContext.remove(0);
                MutableGuidedStep card = new MutableGuidedStep(primary);
                card.trailingContext.addAll(pendingContext);
                cards.add(card);
            } else {
                cards.get(cards.size() - 1).trailingContext.addAll(pendingContext);
            }
        }

        List<GuidedStep> result = new ArrayList<>(cards.size());
        for (int index = 0; index < cards.size(); index++) {
            MutableGuidedStep card = cards.get(index);
            result.add(new GuidedStep(index + 1, card.primary,
                    card.leadingContext, card.trailingContext));
        }
        return Collections.unmodifiableList(result);
    }

    public static int countGuidedSteps(List<AssemblyStep> manualSteps) {
        Objects.requireNonNull(manualSteps, "manualSteps");
        int count = 0;
        for (AssemblyStep step : manualSteps) {
            count += forManualStep(step).size();
        }
        return count;
    }

    private static boolean isGuidedStep(AssemblyInstruction instruction) {
        return instruction.getType() == InstructionType.ACTION
                || instruction.getType() == InstructionType.CHECK;
    }

    private static boolean belongsToPrevious(
            AssemblyInstruction context,
            MutableGuidedStep previous,
            AssemblyInstruction nextPrimary) {
        return previous != null
                && context.getManualPage() == previous.primary.getManualPage()
                && context.getManualPage() != nextPrimary.getManualPage();
    }

    public static final class GuidedStep {
        private final int numberWithinManualStep;
        private final AssemblyInstruction primaryInstruction;
        private final List<AssemblyInstruction> leadingContext;
        private final List<AssemblyInstruction> trailingContext;
        private final int pageStart;
        private final int pageEnd;
        private final List<String> referenceImagePaths;

        private GuidedStep(
                int numberWithinManualStep,
                AssemblyInstruction primaryInstruction,
                List<AssemblyInstruction> leadingContext,
                List<AssemblyInstruction> trailingContext) {
            this.numberWithinManualStep = numberWithinManualStep;
            this.primaryInstruction = Objects.requireNonNull(
                    primaryInstruction, "primaryInstruction");
            this.leadingContext = Collections.unmodifiableList(new ArrayList<>(leadingContext));
            this.trailingContext = Collections.unmodifiableList(new ArrayList<>(trailingContext));

            List<AssemblyInstruction> referenced = new ArrayList<>(
                    leadingContext.size() + trailingContext.size() + 1);
            referenced.addAll(leadingContext);
            referenced.add(primaryInstruction);
            referenced.addAll(trailingContext);
            referenced.sort(Comparator.comparingInt(AssemblyInstruction::getManualPage));

            Map<Integer, String> pathByPage = new LinkedHashMap<>();
            for (AssemblyInstruction instruction : referenced) {
                pathByPage.putIfAbsent(
                        instruction.getManualPage(), instruction.getReferenceImagePath());
            }
            pageStart = referenced.get(0).getManualPage();
            pageEnd = referenced.get(referenced.size() - 1).getManualPage();
            referenceImagePaths = Collections.unmodifiableList(
                    new ArrayList<>(pathByPage.values()));
        }

        public int getNumberWithinManualStep() {
            return numberWithinManualStep;
        }

        public AssemblyInstruction getPrimaryInstruction() {
            return primaryInstruction;
        }

        public List<AssemblyInstruction> getLeadingContext() {
            return leadingContext;
        }

        public List<AssemblyInstruction> getTrailingContext() {
            return trailingContext;
        }

        public List<AssemblyInstruction> getContext() {
            List<AssemblyInstruction> context = new ArrayList<>(
                    leadingContext.size() + trailingContext.size());
            context.addAll(leadingContext);
            context.addAll(trailingContext);
            return Collections.unmodifiableList(context);
        }

        /** Stable enough to restore the same authored card after cards are inserted before it. */
        public String getStableId() {
            return primaryInstruction.getManualPage() + "|"
                    + primaryInstruction.getType().name() + "|"
                    + primaryInstruction.getText();
        }

        public int getPageStart() {
            return pageStart;
        }

        public int getPageEnd() {
            return pageEnd;
        }

        public List<String> getReferenceImagePaths() {
            return referenceImagePaths;
        }
    }

    private static final class MutableGuidedStep {
        private final AssemblyInstruction primary;
        private final List<AssemblyInstruction> leadingContext = new ArrayList<>();
        private final List<AssemblyInstruction> trailingContext = new ArrayList<>();

        private MutableGuidedStep(AssemblyInstruction primary) {
            this.primary = primary;
        }
    }
}
