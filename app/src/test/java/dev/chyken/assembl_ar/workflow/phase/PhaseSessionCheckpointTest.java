package dev.chyken.assembl_ar.workflow.phase;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PhaseSessionCheckpointTest {
    @Test public void developerResumeRetainsTheViewWaitingForNextAndVideoPosition(){
        Phase2GearMotorFlow gear=new Phase2GearMotorFlow(1);gear.devJump(5,2);gear.observeHardware("top",true);
        String payload=gear.snapshot()+"|2|true|1|5750";
        PhaseSessionCheckpoint saved=new PhaseSessionCheckpoint(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES,true,
                PhaseFlow.Mode.VERIFY,0,0,false,false,payload);
        PhaseSessionCheckpoint restored=PhaseSessionCheckpoint.decode(saved.encode());
        assertNotNull(restored);assertEquals(payload,restored.gearState);assertTrue(restored.developer);
        assertTrue(restored.canRestore(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.BOARD_PREPARATION)));
        Phase2GearMotorFlow flow=Phase2GearMotorFlow.restore(restored.gearState.split("\\|")[0],3);
        assertEquals(5,flow.step());assertEquals("top",flow.expectedHardwareView());assertTrue(flow.canNext());
    }
    @Test public void normalBookmarkCannotOverrideEarlierUnfinishedLearnerWork(){
        PhaseSessionCheckpoint saved=new PhaseSessionCheckpoint(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES,false,
                PhaseFlow.Mode.VERIFY,0,0,true,false,"");
        assertFalse(saved.canRestore(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.BOARD_PREPARATION)));
        assertTrue(saved.canRestore(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES)));
        assertFalse(saved.canRestore(null));
    }
    @Test public void laterPhasePositionAndModeRoundTripWithoutASecondGearSnapshot(){
        PhaseSessionCheckpoint saved=new PhaseSessionCheckpoint(AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT,false,
                PhaseFlow.Mode.DEMO,4,2,true,false,null);
        PhaseSessionCheckpoint restored=PhaseSessionCheckpoint.decode(saved.encode());
        assertNotNull(restored);assertEquals(4,restored.chapter);assertEquals(2,restored.inventoryIndex);
        assertTrue(restored.inventoryComplete);assertEquals(PhaseFlow.Mode.DEMO,restored.mode);assertEquals("",restored.gearState);
    }
    @Test public void malformedSessionsFallBackToVerifiedProgress(){
        assertNull(PhaseSessionCheckpoint.decode(null));assertNull(PhaseSessionCheckpoint.decode("broken"));
        assertNull(PhaseSessionCheckpoint.decode("1;motor_assemblies;true;NOT_A_MODE;0;0;false;false;"));
        assertNull(PhaseSessionCheckpoint.decode("1;unknown_phase;true;VERIFY;0;0;false;false;"));
        assertNull(PhaseSessionCheckpoint.decode("1;motor_assemblies;true;VERIFY;-1;0;false;false;"));
    }
    @Test public void restoringCameraModeDiscardsOldFrameTags(){
        PhaseFlow flow=new PhaseFlow(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT),1,true);
        PhaseFlow.ScanTag old=flow.tag();flow.restoreSession(PhaseFlow.Mode.VERIFY,true,false,10);
        assertEquals(PhaseFlow.Mode.VERIFY,flow.getMode());assertFalse(flow.accept(old,11));
        assertFalse(flow.accept(flow.tag(),9));assertTrue(flow.accept(flow.tag(),12));
    }
    @Test public void aPreviewDoesNotBecomeInventoryCompletionOnRestore(){
        PhaseFlow flow=new PhaseFlow(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT),1);
        flow.restoreSession(PhaseFlow.Mode.DEMO,false,true,2);
        assertTrue(flow.isPreviewing());assertFalse(flow.isInventoryComplete());
        flow.demoFinished(3);assertEquals(PhaseFlow.Mode.SCAN_PARTS,flow.getMode());
    }
}
