package dev.chyken.assembl_ar.workflow;

import java.util.Objects;

/**
 * Enforces the manuscript's two gates: automated visual validation must succeed,
 * then the user must explicitly confirm the physical wiggle/continuity check.
 */
public final class AssemblyStateMachine {
    private final int totalSteps;
    private int stepIndex;
    private AssemblyState state;
    private String detectedComponentId = "";
    private String message = "";
    private boolean manualReferenceMode;

    public AssemblyStateMachine(int totalSteps, int initialStepIndex) {
        if (totalSteps < 1) {
            throw new IllegalArgumentException("totalSteps must be positive");
        }
        this.totalSteps = totalSteps;
        this.stepIndex = Math.max(0, Math.min(initialStepIndex, totalSteps - 1));
        this.state = AssemblyState.SEEKING_COMPONENT;
    }

    public synchronized AssemblySession snapshot() {
        return new AssemblySession(stepIndex, totalSteps, state,
                detectedComponentId, message, manualReferenceMode);
    }

    public synchronized AssemblySession onNotRecognized() {
        if (state == AssemblyState.COMPLETE || isAwaitingPhysicalConfirmation()) {
            return snapshot();
        }
        return setState(AssemblyState.COMPONENT_NOT_RECOGNIZED, "");
    }

    public synchronized AssemblySession onComponentRecognized(
            String componentId, boolean isExpectedComponent) {
        Objects.requireNonNull(componentId, "componentId");
        if (state == AssemblyState.COMPLETE || isAwaitingPhysicalConfirmation()) {
            return snapshot();
        }
        detectedComponentId = componentId;
        manualReferenceMode = false;
        return setState(isExpectedComponent ? AssemblyState.COMPONENT_MATCHED
                : AssemblyState.COMPONENT_MISMATCH, componentId);
    }

    public synchronized AssemblySession onPoseEstimated(boolean poseStable) {
        if (state != AssemblyState.COMPONENT_MATCHED && state != AssemblyState.POSE_LOCKED
                && state != AssemblyState.GUIDING) {
            return snapshot();
        }
        return setState(poseStable ? AssemblyState.POSE_LOCKED : AssemblyState.COMPONENT_MATCHED,
                detectedComponentId);
    }

    public synchronized AssemblySession beginGuidance() {
        if (state == AssemblyState.POSE_LOCKED) {
            return setState(AssemblyState.GUIDING, detectedComponentId);
        }
        return snapshot();
    }

    public synchronized AssemblySession onVisualValidation(
            boolean placementValid, boolean orientationValid, boolean polarityValid) {
        if (state != AssemblyState.GUIDING && state != AssemblyState.POSE_LOCKED) {
            return snapshot();
        }
        if (!orientationValid) {
            return setState(AssemblyState.WRONG_ORIENTATION, detectedComponentId);
        }
        if (!polarityValid) {
            return setState(AssemblyState.POLARITY_ERROR, detectedComponentId);
        }
        return setState(placementValid ? AssemblyState.VISUALLY_VALIDATED
                : AssemblyState.GUIDING, detectedComponentId);
    }

    /** Records that a required orientation check could not be established from this frame. */
    public synchronized AssemblySession onOrientationUnconfirmed() {
        if (state == AssemblyState.COMPLETE || isAwaitingPhysicalConfirmation()) {
            return snapshot();
        }
        return setState(AssemblyState.ORIENTATION_UNCONFIRMED, detectedComponentId);
    }

    public synchronized AssemblySession onWrongOrientation() {
        if (state == AssemblyState.COMPLETE || isAwaitingPhysicalConfirmation()) {
            return snapshot();
        }
        return setState(AssemblyState.WRONG_ORIENTATION, detectedComponentId);
    }

    /** Records that a polarity-sensitive step has no conclusive polarity observation. */
    public synchronized AssemblySession onPolarityUnconfirmed() {
        if (state == AssemblyState.COMPLETE || isAwaitingPhysicalConfirmation()) {
            return snapshot();
        }
        return setState(AssemblyState.POLARITY_UNCONFIRMED, detectedComponentId);
    }

    public synchronized AssemblySession onPolarityError() {
        if (state == AssemblyState.COMPLETE || isAwaitingPhysicalConfirmation()) {
            return snapshot();
        }
        return setState(AssemblyState.POLARITY_ERROR, detectedComponentId);
    }

    public synchronized AssemblySession requestPhysicalConfirmation() {
        if (state == AssemblyState.VISUALLY_VALIDATED) {
            return setState(AssemblyState.PHYSICAL_CONFIRMATION, detectedComponentId);
        }
        return snapshot();
    }

    /** Opens an authored manual action check without recording any camera validation. */
    public synchronized AssemblySession requestManualActionConfirmation() {
        if (state == AssemblyState.SEEKING_COMPONENT) {
            return setState(AssemblyState.PHYSICAL_CONFIRMATION, "manual-action-check");
        }
        return snapshot();
    }

    /**
     * A manual-only check can reuse an explicitly recorded visual checkpoint from this section.
     * The caller must verify the persisted checkpoint; merely viewing an instruction or manual
     * page cannot satisfy it. This does not create a new recognition or placement observation.
     */
    public synchronized AssemblySession requestPhysicalConfirmationFromCheckpoint(
            String componentId, boolean checkpointComplete) {
        Objects.requireNonNull(componentId, "componentId");
        if (checkpointComplete && !componentId.trim().isEmpty()
                && state == AssemblyState.SEEKING_COMPONENT) {
            detectedComponentId = componentId;
            return setState(AssemblyState.PHYSICAL_CONFIRMATION, "recorded-visual-checkpoint");
        }
        return snapshot();
    }

    public synchronized AssemblySession useManualReferenceMode() {
        if (state == AssemblyState.COMPLETE || isAwaitingPhysicalConfirmation()) {
            return snapshot();
        }
        manualReferenceMode = true;
        message = "manual-reference-viewed";
        return snapshot();
    }

    public synchronized AssemblySession confirmPhysicalValidation() {
        if (state != AssemblyState.PHYSICAL_CONFIRMATION) {
            throw new IllegalStateException("Physical confirmation is not currently allowed");
        }
        if (stepIndex + 1 >= totalSteps) {
            state = AssemblyState.COMPLETE;
            message = "";
            return snapshot();
        }
        stepIndex++;
        state = AssemblyState.SEEKING_COMPONENT;
        detectedComponentId = "";
        message = "";
        manualReferenceMode = false;
        return snapshot();
    }

    public synchronized AssemblySession retryCurrentStep() {
        if (state != AssemblyState.COMPLETE) {
            state = AssemblyState.SEEKING_COMPONENT;
            detectedComponentId = "";
            message = "";
            manualReferenceMode = false;
        }
        return snapshot();
    }

    private boolean isAwaitingPhysicalConfirmation() {
        return state == AssemblyState.VISUALLY_VALIDATED
                || state == AssemblyState.PHYSICAL_CONFIRMATION;
    }

    private AssemblySession setState(AssemblyState newState, String newMessage) {
        state = newState;
        message = newMessage == null ? "" : newMessage;
        return snapshot();
    }
}
