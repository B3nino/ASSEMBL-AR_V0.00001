package dev.chyken.assembl_ar.model;

import java.util.List;
import java.util.Objects;

public final class LearningModule {
    private final String id;
    private final int order;
    private final String title;
    private final String overview;
    private final String prerequisiteModuleId;
    private final ManualReference manualReference;
    private final List<LearningStep> steps;

    public LearningModule(
            String id,
            int order,
            String title,
            String overview,
            String prerequisiteModuleId,
            ManualReference manualReference,
            List<LearningStep> steps) {
        if (order < 1) {
            throw new IllegalArgumentException("order must be positive");
        }
        this.id = Objects.requireNonNull(id, "id");
        this.order = order;
        this.title = Objects.requireNonNull(title, "title");
        this.overview = Objects.requireNonNull(overview, "overview");
        this.prerequisiteModuleId = Objects.requireNonNull(prerequisiteModuleId, "prerequisiteModuleId");
        this.manualReference = Objects.requireNonNull(manualReference, "manualReference");
        this.steps = ModelCollections.immutableCopy(steps);
    }

    public String getId() {
        return id;
    }

    public int getOrder() {
        return order;
    }

    public String getTitle() {
        return title;
    }

    public String getOverview() {
        return overview;
    }

    public String getPrerequisiteModuleId() {
        return prerequisiteModuleId;
    }

    public ManualReference getManualReference() {
        return manualReference;
    }

    public List<LearningStep> getSteps() {
        return steps;
    }
}
