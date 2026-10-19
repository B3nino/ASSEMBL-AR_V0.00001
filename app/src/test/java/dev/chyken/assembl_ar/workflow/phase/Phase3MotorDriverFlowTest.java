package dev.chyken.assembl_ar.workflow.phase;

import org.junit.Test;
import static org.junit.Assert.*;

public final class Phase3MotorDriverFlowTest {
    @Test public void everyCheckpointWaitsForEvidenceAndExplicitNext(){
        Phase3MotorDriverFlow f=new Phase3MotorDriverFlow(100);
        assertFalse(f.next(101));f.observe(false);assertFalse(f.complete());
        f.observe(true);assertTrue(f.complete());assertEquals(1,f.step());
        assertFalse(f.scanning());assertTrue(f.next(110));
        f.observe(true);assertFalse(f.complete());f.confirmHardware();assertTrue(f.next(120));
        f.confirmDemo(false);assertFalse(f.next(121));f.confirmDemo(true);assertTrue(f.next(130));
        assertTrue(f.scanning());f.observe(false);assertFalse(f.next(131));
        f.observe(true);assertTrue(f.next(140));assertEquals(5,f.step());assertFalse(f.complete());
        f.observe(true);assertTrue(f.next(150));assertFalse(f.scanning());
        f.confirmStability(false,true);assertFalse(f.canFinish(false));
        f.confirmStability(true,false);assertFalse(f.canFinish(false));
        f.confirmStability(true,true);assertTrue(f.canFinish(false));assertTrue(f.allComplete());
        assertFalse(f.next(160));
    }
    @Test public void oldDuplicateAndPausedFramesCannotVerifyNewSteps(){
        Phase3MotorDriverFlow f=new Phase3MotorDriverFlow(100);Object tag=f.tag();
        assertFalse(f.accepts(tag,99));assertTrue(f.accepts(tag,101));assertFalse(f.accepts(tag,101));
        f.pause();assertFalse(f.accepts(f.tag(),102));f.resume(200);
        assertFalse(f.accepts(tag,201));assertFalse(f.accepts(f.tag(),199));assertTrue(f.accepts(f.tag(),202));
        f.observe(true);f.next(210);f.confirmHardware();f.next(220);f.confirmDemo(true);f.next(230);
        assertFalse(f.accepts(tag,240));assertTrue(f.accepts(f.tag(),240));
    }
    @Test public void snapshotRestoresEveryStepAndRetainsCompletedScanUnlock(){
        Phase3MotorDriverFlow f=new Phase3MotorDriverFlow(100);
        for(int step=1;step<=6;step++){
            f.devSkip();Phase3MotorDriverFlow restored=Phase3MotorDriverFlow.restore(f.snapshot(),200);
            assertEquals(step,restored.step());assertTrue(restored.complete());assertFalse(restored.scanning());
            if(step<6)assertTrue(f.next(100+step));
        }
        assertTrue(Phase3MotorDriverFlow.restore(f.snapshot(),500).canFinish(false));
    }
    @Test public void lookBackDoesNotEraseProgressButConfirmedRescanInvalidatesDependents(){
        Phase3MotorDriverFlow f=new Phase3MotorDriverFlow(100);f.devComplete();
        assertTrue(f.previous(200));assertEquals(5,f.step());assertTrue(f.allComplete());
        f.rescan(210);assertFalse(f.complete());assertFalse(f.allComplete());assertTrue(f.scanning());
        assertTrue(f.previous(220));assertTrue(f.complete());
        assertTrue(f.next(230));f.observe(true);f.next(240);assertFalse(f.complete());
    }
    @Test public void devSkippingOneStepCannotCompleteALearnerPhase(){
        Phase3MotorDriverFlow f=new Phase3MotorDriverFlow(0);f.devJump(6,100);f.devSkip();
        assertTrue(f.canFinish(true));assertFalse(f.canFinish(false));f.restart(200);
        assertEquals(1,f.step());assertTrue(f.scanning());assertFalse(f.complete());
    }
    @Test public void malformedSnapshotsStartWithFreshScanning(){
        for(String bad:new String[]{null,"","7,111111","3,1","3,xyzxyz","a,111111"}){
            Phase3MotorDriverFlow f=Phase3MotorDriverFlow.restore(bad,100);
            assertEquals(1,f.step());assertFalse(f.complete());assertTrue(f.scanning());
        }
    }
    @Test public void parentStateMachineUsesTheSpecializedEvidenceWindow(){
        PhaseFlow root=new PhaseFlow(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT),0);
        Phase3MotorDriverFlow driver=new Phase3MotorDriverFlow(10);root.attachDriverFlow(driver);
        assertEquals(driver.tag(),root.tag());assertTrue(root.collectsEvidence());
        assertTrue(root.accept(root.tag(),20));driver.observe(true);driver.next(30);
        assertFalse(root.collectsEvidence());assertEquals(driver.mode(),root.getMode());
    }
}
