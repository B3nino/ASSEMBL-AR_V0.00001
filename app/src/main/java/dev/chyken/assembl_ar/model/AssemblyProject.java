package dev.chyken.assembl_ar.model;

import java.util.List;
import java.util.Objects;

public final class AssemblyProject {
    private final String id;
    private final String title;
    private final String kitName;
    private final String description;
    private final ManualReference manualReference;
    private final List<AssemblyStep> steps;

    public AssemblyProject(
            String id,
            String title,
            String kitName,
            String description,
            ManualReference manualReference,
            List<AssemblyStep> steps) {
        this.id = Objects.requireNonNull(id, "id");
        this.title = Objects.requireNonNull(title, "title");
        this.kitName = Objects.requireNonNull(kitName, "kitName");
        this.description = Objects.requireNonNull(description, "description");
        this.manualReference = Objects.requireNonNull(manualReference, "manualReference");
        this.steps = ModelCollections.immutableCopy(steps);
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getKitName() {
        return kitName;
    }

    public String getDescription() {
        return description;
    }

    public ManualReference getManualReference() {
        return manualReference;
    }

    public List<AssemblyStep> getSteps() {
        return steps;
    }

    public int getStepCount() {
        return steps.size();
    }
}
