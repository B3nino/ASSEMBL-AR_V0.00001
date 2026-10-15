package dev.chyken.assembl_ar.model;

import java.util.Objects;

public final class StepProgress {
    private final String projectId;
    private final String stepId;
    private final StepStatus status;
    private final boolean validationConfirmed;
    private final long updatedAtEpochMillis;

    public StepProgress(
            String projectId,
            String stepId,
            StepStatus status,
            boolean validationConfirmed,
            long updatedAtEpochMillis) {
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.stepId = Objects.requireNonNull(stepId, "stepId");
        this.status = Objects.requireNonNull(status, "status");
        this.validationConfirmed = validationConfirmed;
        this.updatedAtEpochMillis = updatedAtEpochMillis;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getStepId() {
        return stepId;
    }

    public StepStatus getStatus() {
        return status;
    }

    public boolean isValidationConfirmed() {
        return validationConfirmed;
    }

    public long getUpdatedAtEpochMillis() {
        return updatedAtEpochMillis;
    }
}
