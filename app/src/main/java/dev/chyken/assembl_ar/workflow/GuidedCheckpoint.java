package dev.chyken.assembl_ar.workflow;

import androidx.annotation.Nullable;

import java.util.Objects;

/** Evidence required for one guided action, separate from the section's final validation. */
public final class GuidedCheckpoint {
    public enum Kind { IDENTITY, PLACEMENT, PHYSICAL }

    /** Installed motors use the board-relative motor inspector, not a loose-part photograph. */
    public enum MotorRequirement { NONE, FIRST, BOTH }

    private final Kind kind;
    private final String componentId;
    private final String anchorComponentId;
    private final ChassisRelativePlacementGate placementGate;
    private final MotorRequirement motorRequirement;
    private final String prompt;
    private final String physicalCheck;
    private final int bracketSlot;

    private GuidedCheckpoint(Kind kind, @Nullable String componentId,
                             @Nullable String anchorComponentId,
                             @Nullable ChassisRelativePlacementGate placementGate,
                             MotorRequirement motorRequirement, String prompt,
                             String physicalCheck, int bracketSlot) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.componentId = componentId;
        this.anchorComponentId = anchorComponentId;
        this.placementGate = placementGate;
        this.motorRequirement = Objects.requireNonNull(motorRequirement, "motorRequirement");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.physicalCheck = Objects.requireNonNull(physicalCheck, "physicalCheck");
        this.bracketSlot = bracketSlot;
    }

    static GuidedCheckpoint identity(String componentId, String prompt, String physicalCheck) {
        return new GuidedCheckpoint(Kind.IDENTITY, Objects.requireNonNull(componentId),
                null, null, MotorRequirement.NONE, prompt, physicalCheck, 0);
    }

    static GuidedCheckpoint placement(String componentId, String anchorComponentId,
                                      ChassisRelativePlacementGate gate,
                                      MotorRequirement motorRequirement,
                                      String prompt, String physicalCheck) {
        return new GuidedCheckpoint(Kind.PLACEMENT, Objects.requireNonNull(componentId),
                Objects.requireNonNull(anchorComponentId), Objects.requireNonNull(gate),
                motorRequirement, prompt, physicalCheck, 0);
    }

    GuidedCheckpoint forBracketSlot(int slot) {
        if (kind != Kind.PLACEMENT || slot < 1 || slot > 4) {
            throw new IllegalArgumentException("Invalid bracket placement slot");
        }
        return new GuidedCheckpoint(kind, componentId, anchorComponentId, placementGate,
                motorRequirement, prompt, physicalCheck, slot);
    }

    static GuidedCheckpoint physical(String physicalCheck) {
        return new GuidedCheckpoint(Kind.PHYSICAL, null, null, null, MotorRequirement.NONE,
                "Complete this action and check it directly: " + physicalCheck, physicalCheck, 0);
    }

    public Kind getKind() { return kind; }
    @Nullable public String getComponentId() { return componentId; }
    @Nullable public String getAnchorComponentId() { return anchorComponentId; }
    @Nullable public ChassisRelativePlacementGate getPlacementGate() { return placementGate; }
    public MotorRequirement getMotorRequirement() { return motorRequirement; }
    public String getPrompt() { return prompt; }
    public String getPhysicalCheck() { return physicalCheck; }
    public int getBracketSlot() { return bracketSlot; }
    public boolean requiresCamera() { return kind != Kind.PHYSICAL; }
    public int getExpectedInstanceCount() {
        return placementGate == null ? (requiresCamera() ? 1 : 0)
                : placementGate.getRequiredDetectionCount();
    }
}
