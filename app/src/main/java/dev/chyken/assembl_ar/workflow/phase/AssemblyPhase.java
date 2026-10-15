package dev.chyken.assembl_ar.workflow.phase;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One learner-visible assembly group: scan its parts, watch its complete demonstration, assemble,
 * then verify the finished group once. Atomic manual instructions stay addressable as chapters,
 * details and correction segments; they are no longer separate mandatory scan/confirm screens.
 */
public final class AssemblyPhase {
    /** Separates newly handled parts from installed work and from tools the camera never counts. */
    public enum PartRole { NEW_PART, INSTALLED_CONTEXT, TOOL_OR_CONSUMABLE }

    /**
     * How inventory is established. Only {@link #CAMERA_COUNT} counts instances in one frame;
     * {@link #CAMERA_IDENTITY} checks one sample's identity; {@link #CHECKLIST} is an honestly
     * labelled "have this ready" item that the camera cannot yet confirm.
     */
    public enum InventoryEvidence { CAMERA_COUNT, CAMERA_IDENTITY, CHECKLIST }

    /** Existing recognition paths reused as phase verification targets. */
    public enum TargetKind { MOTOR_SLOT, BRACKET_SLOT, SECTION_PLACEMENT }

    public static final class Part {
        private final String componentId;
        private final int quantity;
        private final PartRole role;
        private final InventoryEvidence evidence;
        private final List<String> alternatives;
        private final String note;

        Part(String componentId, int quantity, PartRole role, InventoryEvidence evidence,
             List<String> alternatives, String note) {
            this.componentId = requireText(componentId, "componentId");
            if (quantity < 1) throw new IllegalArgumentException("Quantity must be positive");
            this.quantity = quantity;
            this.role = Objects.requireNonNull(role, "role");
            this.evidence = Objects.requireNonNull(evidence, "evidence");
            if (role != PartRole.NEW_PART && evidence != InventoryEvidence.CHECKLIST) {
                throw new IllegalArgumentException("Only new parts are camera-scanned for inventory");
            }
            if (evidence == InventoryEvidence.CAMERA_COUNT && quantity < 2) {
                throw new IllegalArgumentException("A counted camera inventory needs several pieces");
            }
            this.alternatives = immutable(alternatives);
            this.note = note == null ? "" : note;
        }

        public String getComponentId() { return componentId; }
        public int getQuantity() { return quantity; }
        public PartRole getRole() { return role; }
        public InventoryEvidence getEvidence() { return evidence; }
        /** Permitted substitutes from the manual, such as stacked nuts for a plastic spacer. */
        public List<String> getAlternatives() { return alternatives; }
        public String getNote() { return note; }
        public boolean isCameraScanned() { return evidence != InventoryEvidence.CHECKLIST; }
    }

    /**
     * One observable result of the finished group. Targets sharing a probe group are observed by
     * the same recognition request, so a pair is always judged from the same frames.
     */
    public static final class VisualTarget {
        private final String id;
        private final TargetKind kind;
        private final String probeGroup;
        private final int section;
        private final String componentId;
        private final String anchorComponentId;
        private final String label;
        private final int bracketSlot;
        @Nullable private final String placementTargetId;
        private final List<Integer> correctionOrders;

        VisualTarget(String id, TargetKind kind, String probeGroup, int section,
                     String componentId, String anchorComponentId, String label, int bracketSlot,
                     @Nullable String placementTargetId, List<Integer> correctionOrders) {
            this.id = requireText(id, "id");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.probeGroup = requireText(probeGroup, "probeGroup");
            if (section < 1 || section > 18) throw new IllegalArgumentException("Invalid section");
            this.section = section;
            this.componentId = requireText(componentId, "componentId");
            this.anchorComponentId = requireText(anchorComponentId, "anchorComponentId");
            this.label = requireText(label, "label");
            if (kind == TargetKind.BRACKET_SLOT ? bracketSlot < 1 || bracketSlot > 4 : bracketSlot != 0) {
                throw new IllegalArgumentException("Only bracket targets have a slot from 1 through 4");
            }
            this.bracketSlot = bracketSlot;
            this.placementTargetId = placementTargetId;
            if (correctionOrders == null || correctionOrders.isEmpty()) {
                throw new IllegalArgumentException("Every target needs a corrective demonstration");
            }
            this.correctionOrders = immutable(correctionOrders);
        }

        public String getId() { return id; }
        public TargetKind getKind() { return kind; }
        public String getProbeGroup() { return probeGroup; }
        public int getSection() { return section; }
        public String getComponentId() { return componentId; }
        public String getAnchorComponentId() { return anchorComponentId; }
        public String getLabel() { return label; }
        public int getBracketSlot() { return bracketSlot; }
        /** Authored {@code ChassisRelativePlacementGate} target ID for section placements. */
        @Nullable public String getPlacementTargetId() { return placementTargetId; }
        /** Instruction orders, within {@link #getSection()}, replayed to correct this target. */
        public List<Integer> getCorrectionOrders() { return correctionOrders; }
    }

    /** Instruction orders of one manual section that belong to this phase. */
    public static final class SourceRange {
        private final int section;
        private final Set<Integer> orders;
        private final boolean wholeSection;

        SourceRange(int section, Set<Integer> orders, boolean wholeSection) {
            if (section < 1 || section > 18) throw new IllegalArgumentException("Invalid section");
            this.section = section;
            this.orders = Collections.unmodifiableSet(new LinkedHashSet<>(orders));
            this.wholeSection = wholeSection;
            if (!wholeSection && this.orders.isEmpty()) {
                throw new IllegalArgumentException("A partial source range needs instruction orders");
            }
        }

        public int getSection() { return section; }
        public boolean isWholeSection() { return wholeSection; }
        /** Empty when the whole section belongs to the phase. */
        public Set<Integer> getOrders() { return orders; }
        public boolean contains(int order) { return wholeSection || orders.contains(order); }
    }

    private final String id;
    private final int number;
    private final String title;
    private final String summary;
    private final List<SourceRange> sources;
    private final List<Part> parts;
    private final List<VisualTarget> targets;
    private final List<String> directChecks;
    private final List<String> dependsOn;
    private final boolean completesOnInventory;

    AssemblyPhase(String id, int number, String title, String summary, List<SourceRange> sources,
                  List<Part> parts, List<VisualTarget> targets, List<String> directChecks,
                  List<String> dependsOn, boolean completesOnInventory) {
        this.id = requireText(id, "id");
        if (number < 1) throw new IllegalArgumentException("Phase number must be positive");
        this.number = number;
        this.title = requireText(title, "title");
        this.summary = requireText(summary, "summary");
        this.sources = immutable(sources);
        if (this.sources.isEmpty()) throw new IllegalArgumentException("A phase needs source instructions");
        this.parts = immutable(parts);
        this.targets = immutable(targets);
        this.directChecks = immutable(directChecks);
        this.dependsOn = immutable(dependsOn);
        this.completesOnInventory = completesOnInventory;
        if (completesOnInventory && (!this.targets.isEmpty() || !this.directChecks.isEmpty())) {
            throw new IllegalArgumentException("An inventory-only phase cannot need later checks");
        }
        Set<String> targetIds = new LinkedHashSet<>();
        for (VisualTarget target : this.targets) {
            if (!targetIds.add(target.getId())) {
                throw new IllegalArgumentException("Repeated target " + target.getId());
            }
        }
    }

    public String getId() { return id; }
    /** One-based position in the default learner-visible sequence. */
    public int getNumber() { return number; }
    public String getTitle() { return title; }
    public String getSummary() { return summary; }
    public List<SourceRange> getSources() { return sources; }
    public List<Part> getParts() { return parts; }
    public List<VisualTarget> getTargets() { return targets; }
    /** The consolidated physical checklist: facts a camera image cannot certify. */
    public List<String> getDirectChecks() { return directChecks; }
    public List<String> getDependsOn() { return dependsOn; }
    /** Board preparation finishes as soon as its identity scan is stable. */
    public boolean completesOnInventory() { return completesOnInventory; }

    public boolean hasVisualTargets() { return !targets.isEmpty(); }

    public List<Part> getCameraInventory() {
        List<Part> scanned = new ArrayList<>();
        for (Part part : parts) {
            if (part.isCameraScanned()) scanned.add(part);
        }
        return Collections.unmodifiableList(scanned);
    }

    public List<Integer> getSections() {
        List<Integer> sections = new ArrayList<>();
        for (SourceRange range : sources) sections.add(range.getSection());
        return Collections.unmodifiableList(sections);
    }

    public int getFirstSection() { return sources.get(0).getSection(); }
    public int getLastSection() { return sources.get(sources.size() - 1).getSection(); }

    public boolean contains(int section, int order) {
        for (SourceRange range : sources) {
            if (range.getSection() == section && range.contains(order)) return true;
        }
        return false;
    }

    /** The probe groups in authored order, each observed with a single recognition request. */
    public List<String> getProbeGroups() {
        Set<String> groups = new LinkedHashSet<>();
        for (VisualTarget target : targets) groups.add(target.getProbeGroup());
        return Collections.unmodifiableList(new ArrayList<>(groups));
    }

    @Nullable
    public VisualTarget getTarget(String targetId) {
        for (VisualTarget target : targets) {
            if (target.getId().equals(targetId)) return target;
        }
        return null;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " cannot be blank");
        }
        return value.trim();
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
