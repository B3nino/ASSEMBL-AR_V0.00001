package dev.chyken.assembl_ar.model;

import java.util.List;
import java.util.Objects;

public final class ProjectProgress {
    private final String projectId;
    private final int completedSteps;
    private final int totalSteps;
    private final String currentStepId;
    private final List<StepProgress> steps;

    public ProjectProgress(
            String projectId,
            int completedSteps,
            int totalSteps,
            String currentStepId,
            List<StepProgress> steps) {
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.completedSteps = completedSteps;
        this.totalSteps = totalSteps;
        this.currentStepId = Objects.requireNonNull(currentStepId, "currentStepId");
        this.steps = ModelCollections.immutableCopy(steps);
    }

    public String getProjectId() { return projectId; }
    public int getCompletedSteps() { return completedSteps; }
    public int getTotalSteps() { return totalSteps; }
    public String getCurrentStepId() { return currentStepId; }
    public List<StepProgress> getSteps() { return steps; }
    public boolean isComplete() { return totalSteps > 0 && completedSteps == totalSteps; }
    public int getPercentComplete() {
        return totalSteps == 0 ? 0 : (int) Math.round((completedSteps * 100.0d) / totalSteps);
    }
}
