package dev.chyken.assembl_ar.workflow;

import java.util.Objects;

public final class AssemblySession {
    private final int stepIndex;
    private final int totalSteps;
    private final AssemblyState state;
    private final String detectedComponentId;
    private final String message;
    private final boolean manualReferenceMode;

    AssemblySession(int stepIndex, int totalSteps, AssemblyState state,
                    String detectedComponentId, String message, boolean manualReferenceMode) {
        this.stepIndex = stepIndex;
        this.totalSteps = totalSteps;
        this.state = Objects.requireNonNull(state, "state");
        this.detectedComponentId = detectedComponentId == null ? "" : detectedComponentId;
        this.message = message == null ? "" : message;
        this.manualReferenceMode = manualReferenceMode;
    }

    public int getStepIndex() { return stepIndex; }
    public int getStepNumber() { return Math.min(totalSteps, stepIndex + 1); }
    public int getTotalSteps() { return totalSteps; }
    public AssemblyState getState() { return state; }
    public String getDetectedComponentId() { return detectedComponentId; }
    public String getMessage() { return message; }
    public boolean isManualReferenceMode() { return manualReferenceMode; }
    public boolean isComplete() { return state == AssemblyState.COMPLETE; }
}
