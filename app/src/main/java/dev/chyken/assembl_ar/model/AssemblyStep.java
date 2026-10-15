package dev.chyken.assembl_ar.model;

import java.util.List;
import java.util.Objects;

public final class AssemblyStep {
    private final String id;
    private final String projectId;
    private final int stepNumber;
    private final String title;
    private final String summary;
    private final ValidationType validationType;
    private final String validationPrompt;
    private final ManualReference manualReference;
    private final List<AssemblyInstruction> instructions;
    private final List<RequiredComponent> requiredComponents;
    private final List<PinConnection> pinConnections;
    private final List<OverlayType> overlayTypes;

    public AssemblyStep(
            String id,
            String projectId,
            int stepNumber,
            String title,
            String summary,
            ValidationType validationType,
            String validationPrompt,
            ManualReference manualReference,
            List<AssemblyInstruction> instructions,
            List<RequiredComponent> requiredComponents,
            List<PinConnection> pinConnections,
            List<OverlayType> overlayTypes) {
        if (stepNumber < 1) {
            throw new IllegalArgumentException("stepNumber must be positive");
        }
        this.id = Objects.requireNonNull(id, "id");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.stepNumber = stepNumber;
        this.title = Objects.requireNonNull(title, "title");
        this.summary = Objects.requireNonNull(summary, "summary");
        this.validationType = Objects.requireNonNull(validationType, "validationType");
        this.validationPrompt = Objects.requireNonNull(validationPrompt, "validationPrompt");
        this.manualReference = Objects.requireNonNull(manualReference, "manualReference");
        this.instructions = ModelCollections.immutableCopy(instructions);
        this.requiredComponents = ModelCollections.immutableCopy(requiredComponents);
        this.pinConnections = ModelCollections.immutableCopy(pinConnections);
        this.overlayTypes = ModelCollections.immutableCopy(overlayTypes);
    }

    public String getId() {
        return id;
    }

    public String getProjectId() {
        return projectId;
    }

    public int getStepNumber() {
        return stepNumber;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public ValidationType getValidationType() {
        return validationType;
    }

    public String getValidationPrompt() {
        return validationPrompt;
    }

    public ManualReference getManualReference() {
        return manualReference;
    }

    public List<AssemblyInstruction> getInstructions() {
        return instructions;
    }

    public List<RequiredComponent> getRequiredComponents() {
        return requiredComponents;
    }

    public List<PinConnection> getPinConnections() {
        return pinConnections;
    }

    public List<OverlayType> getOverlayTypes() {
        return overlayTypes;
    }
}
