package dev.chyken.assembl_ar;

import org.junit.Test;

import dev.chyken.assembl_ar.workflow.AssemblySession;
import dev.chyken.assembl_ar.workflow.AssemblyState;
import dev.chyken.assembl_ar.workflow.AssemblyStateMachine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class AssemblyStateMachineTest {
    @Test
    public void lateCameraEventsCannotReopenACompletedAssembly() {
        AssemblyStateMachine machine = new AssemblyStateMachine(1, 0);
        machine.requestPhysicalConfirmationFromCheckpoint("main_board", true);
        machine.confirmPhysicalValidation();
        machine.onNotRecognized();
        machine.onComponentRecognized("wrong-part", false);
        machine.onOrientationUnconfirmed();
        machine.onWrongOrientation();
        machine.onPolarityUnconfirmed();
        machine.onPolarityError();
        machine.useManualReferenceMode();
        assertEquals(AssemblyState.COMPLETE, machine.snapshot().getState());
    }
    @Test
    public void manualSurfaceCheckRequiresRecordedBoardCheckpointAndPhysicalConfirmation() {
        AssemblyStateMachine machine = new AssemblyStateMachine(18, 0);
        machine.useManualReferenceMode();
        assertEquals(AssemblyState.SEEKING_COMPONENT,
                machine.requestPhysicalConfirmationFromCheckpoint("main_board", false).getState());
        assertEquals(AssemblyState.PHYSICAL_CONFIRMATION,
                machine.requestPhysicalConfirmationFromCheckpoint("main_board", true).getState());
        assertEquals(0, machine.snapshot().getStepIndex());
        assertEquals(1, machine.confirmPhysicalValidation().getStepIndex());
    }
    @Test
    public void mismatchNeverAdvancesTheStep() {
        AssemblyStateMachine machine = new AssemblyStateMachine(18, 4);
        AssemblySession result = machine.onComponentRecognized("wrong-part", false);
        assertEquals(4, result.getStepIndex());
        assertEquals(AssemblyState.COMPONENT_MISMATCH, result.getState());
    }

    @Test
    public void physicalConfirmationIsRequiredBeforeAdvancing() {
        AssemblyStateMachine machine = new AssemblyStateMachine(18, 0);
        machine.onComponentRecognized("main-board", true);
        machine.onPoseEstimated(true);
        machine.beginGuidance();
        AssemblySession visuallyValid = machine.onVisualValidation(true, true, true);
        assertEquals(0, visuallyValid.getStepIndex());
        assertEquals(AssemblyState.VISUALLY_VALIDATED, visuallyValid.getState());

        machine.requestPhysicalConfirmation();
        AssemblySession next = machine.confirmPhysicalValidation();
        assertEquals(1, next.getStepIndex());
        assertEquals(AssemblyState.SEEKING_COMPONENT, next.getState());
    }

    @Test
    public void orientationAndPolarityErrorsBlockProgress() {
        AssemblyStateMachine machine = new AssemblyStateMachine(18, 7);
        machine.onComponentRecognized("motor-driver", true);
        machine.onPoseEstimated(true);
        machine.beginGuidance();
        assertEquals(AssemblyState.WRONG_ORIENTATION,
                machine.onVisualValidation(true, false, true).getState());
        machine.retryCurrentStep();
        machine.onComponentRecognized("motor-driver", true);
        machine.onPoseEstimated(true);
        machine.beginGuidance();
        assertEquals(AssemblyState.POLARITY_ERROR,
                machine.onVisualValidation(true, true, false).getState());
        assertEquals(7, machine.snapshot().getStepIndex());
    }

    @Test
    public void openingManualReferenceCannotSatisfyVisualValidation() {
        AssemblyStateMachine machine = new AssemblyStateMachine(18, 3);
        AssemblySession manual = machine.useManualReferenceMode();

        assertEquals(AssemblyState.SEEKING_COMPONENT, manual.getState());
        assertEquals(3, manual.getStepIndex());
        assertEquals(AssemblyState.SEEKING_COMPONENT,
                machine.requestPhysicalConfirmation().getState());
        assertFalse(machine.snapshot().getState() == AssemblyState.PHYSICAL_CONFIRMATION);
    }

    @Test
    public void unconfirmedPolarityCannotReachPhysicalConfirmation() {
        AssemblyStateMachine machine = new AssemblyStateMachine(18, 6);
        machine.onComponentRecognized("motor-driver", true);
        machine.onPoseEstimated(true);
        machine.beginGuidance();
        assertEquals(AssemblyState.POLARITY_UNCONFIRMED,
                machine.onPolarityUnconfirmed().getState());
        assertEquals(AssemblyState.POLARITY_UNCONFIRMED,
                machine.requestPhysicalConfirmation().getState());
        assertEquals(6, machine.snapshot().getStepIndex());
    }
}
