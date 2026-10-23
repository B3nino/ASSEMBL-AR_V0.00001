package dev.chyken.assembl_ar.workflow.phase;
import org.junit.Test;import static org.junit.Assert.*;
public final class Phase2MotorBracketFlowTest {
    @Test public void everyMotorAndBracketHasAnIndependentEvidenceFloor(){assertTrue(Phase2MotorBracketFlow.acceptsTop(6,6,4,4));assertFalse(Phase2MotorBracketFlow.acceptsTop(40,0,40,0));assertFalse(Phase2MotorBracketFlow.acceptsTop(40,40,0,40));assertFalse(Phase2MotorBracketFlow.acceptsTop(40,40,40,3));}
    @Test public void rejectedLocalEvidenceCannotUnlockTheViewOrAdvance(){Phase2GearMotorFlow f=new Phase2GearMotorFlow(1);f.devJump(5,2);f.observeHardware("top",false);assertFalse(f.currentHardwareViewComplete());assertFalse(f.canNext());assertFalse(f.next(3));f.observeHardware("top",true);assertTrue(f.currentHardwareViewComplete());assertTrue(f.next(4));assertEquals("left",f.expectedHardwareView());}
}
