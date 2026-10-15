package dev.chyken.assembl_ar.workflow;

/** A component image cannot establish completed wiring, continuity, or screw tension. */
public final class SectionEvidencePolicy {
    private SectionEvidencePolicy() { }

    public static boolean isPhysicalFinal(int section) {
        return section == 4 || section == 5 || section == 7 || section == 8 || section == 15;
    }
}
