package dev.chyken.assembl_ar.workflow.phase;
import org.junit.Test;
import static org.junit.Assert.*;

public final class Phase2GearMotorFlowTest {
    @Test public void countsStayVisibleButOnlyACompleteSimultaneousSetUnlocksNext(){
        Phase2GearMotorFlow f=new Phase2GearMotorFlow(0);
        f.observeCount(3,true);f.observeCount(2,true);assertEquals(3,f.bracketCount());assertFalse(f.next(1));
        f.observeCount(4,true);assertTrue("A validated 4/4 must unlock immediately",f.complete());
        f.observeCount(0,false);assertTrue("Motion blur cannot revoke a confirmed scan",f.complete());
        assertEquals(1,f.step());
        assertTrue(f.next(10));assertEquals(2,f.step());
        for(int i=0;i<8;i++)f.observeCount(1,true);assertFalse(f.complete());
        f.observeCount(2,true);assertTrue("A validated 2/2 must unlock immediately",f.complete());
    }
    @Test public void rejectedCountsCannotClaimProgressOrUnlockNext(){
        Phase2GearMotorFlow f=new Phase2GearMotorFlow(0);
        f.observeCount(4,false);assertEquals(0,f.bracketCount());assertFalse(f.next(1));
        f.observeCount(3,true);f.observeCount(1,true);assertFalse(f.complete());
        f.devJump(2,2);f.observeCount(2,false);assertEquals(0,f.motorCount());assertFalse(f.complete());
        f.observeCount(1,true);f.observeCount(1,true);assertFalse("One motor seen twice is still one motor",f.complete());
    }
    @Test public void staleDuplicateAndPausedFramesCannotAdvanceAScan(){
        Phase2GearMotorFlow f=new Phase2GearMotorFlow(100);PhaseFlow.ScanTag old=f.tag();
        assertFalse(f.accepts(old,99));assertTrue(f.accepts(old,101));assertFalse(f.accepts(old,101));
        f.pause();assertFalse(f.accepts(old,102));f.resume(200);assertFalse(f.accepts(old,201));
        assertFalse(f.accepts(f.tag(),199));assertTrue(f.accepts(f.tag(),202));
    }
    @Test public void eachHardwareMatchUnlocksNextAndWaitsForAnExplicitTap(){
        Phase2GearMotorFlow f=new Phase2GearMotorFlow(0);f.devJump(5,1);
        scan(f,"top");assertTrue(f.canNext());assertFalse(f.scanning());assertFalse(f.complete());
        assertEquals("top",f.expectedHardwareView());scan(f,"left");assertEquals(1,f.hardwareViews().size());
        assertTrue(f.next(2));assertEquals(5,f.step());assertEquals("left",f.expectedHardwareView());assertFalse(f.canNext());
        f.observeHardware("invalid",false);assertEquals(1,f.hardwareViews().size());
        scan(f,"left");assertTrue(f.canNext());assertFalse(f.complete());assertTrue(f.next(3));
        assertEquals("right",f.expectedHardwareView());assertFalse(f.canNext());
        scan(f,"right");assertTrue(f.complete());assertTrue(f.canNext());
        assertEquals(5,f.step());assertTrue(f.next(4));assertEquals(6,f.step());
    }
    @Test public void aDifferentViewCannotUnlockTheCurrentScan(){
        Phase2GearMotorFlow f=new Phase2GearMotorFlow(0);f.devJump(5,1);
        f.observeHardware("left",true);f.observeHardware("right",true);f.observeHardware("top",false);
        assertTrue(f.hardwareViews().isEmpty());assertFalse(f.canNext());assertFalse(f.next(2));
    }
    @Test public void rescanOnlyResetsChosenInventoryAndDependentFinalChecks(){
        Phase2GearMotorFlow f=new Phase2GearMotorFlow(0);f.devCompletePhase();
        f.devJump(1,1);f.rescan(2);assertEquals(0,f.bracketCount());assertFalse(f.complete());
        assertFalse(f.allComplete());f.devJump(2,3);assertTrue("Other inventory retained",f.complete());
        f.devJump(5,4);assertFalse(f.complete());f.devJump(6,5);assertFalse(f.complete());
    }
    @Test public void toolsVideoAndBothJointAnswersAreExplicitGates(){
        Phase2GearMotorFlow f=new Phase2GearMotorFlow(0);
        f.devJump(3,1);assertFalse(f.next(2));f.confirmTools();assertTrue(f.next(3));
        f.confirmAssembly(false);assertFalse(f.next(4));f.confirmAssembly(true);assertTrue(f.next(5));
        f.devJump(6,6);f.confirmFirmness(true,false);assertFalse(f.complete());
        f.confirmFirmness(false,true);assertFalse(f.complete());f.confirmFirmness(true,true);assertTrue(f.complete());
    }
    @Test public void developerFinishingASelectedSubStepCanContinueWithoutCertifyingSkippedCheckpoints(){
        Phase2GearMotorFlow flow=new Phase2GearMotorFlow(1);flow.devJump(6,2);
        assertFalse(flow.canFinish(true));flow.confirmFirmness(true,true);
        assertTrue(flow.canFinish(true));assertFalse(flow.canFinish(false));assertFalse(flow.allComplete());
    }
    @Test public void snapshotsRetainConfirmedStepsAndAnUnlockedViewWaitingForNext(){
        Phase2GearMotorFlow f=new Phase2GearMotorFlow(0);for(int i=0;i<3;i++)f.observeCount(4,true);
        f.next(1);f.observeCount(1,true);
        Phase2GearMotorFlow restored=Phase2GearMotorFlow.restore(f.snapshot(),2);
        assertEquals(2,restored.step());assertEquals(1,restored.motorCount());assertFalse(restored.complete());
        restored.previous(3);assertTrue(restored.complete());
        restored.devJump(5,4);scan(restored,"top");
        Phase2GearMotorFlow again=Phase2GearMotorFlow.restore(restored.snapshot(),5);
        assertEquals("top",again.expectedHardwareView());assertEquals(1,again.hardwareViews().size());
        assertTrue(again.canNext());assertFalse(again.scanning());assertFalse(again.complete());
        assertTrue(again.next(6));assertEquals("left",again.expectedHardwareView());assertFalse(again.canNext());
        assertEquals(1,Phase2GearMotorFlow.restore("broken",6).step());
    }
    @Test public void pauseKeepsConfirmedViewsAndRejectsFramesFromThePreviousView(){
        Phase2GearMotorFlow f=new Phase2GearMotorFlow(0);f.devJump(5,1);
        PhaseFlow.ScanTag top=f.tag();scan(f,"top");
        assertFalse(f.accepts(top,10));assertEquals("top",f.expectedHardwareView());
        f.next(11);f.pause();f.resume(20);assertEquals(1,f.hardwareViews().size());
        assertFalse(f.accepts(top,21));
        scan(f,"right");assertEquals("left",f.expectedHardwareView());
        scan(f,"left");assertEquals("left",f.expectedHardwareView());assertTrue(f.canNext());
        f.next(22);assertEquals("right",f.expectedHardwareView());
    }
    @Test public void oldSnapshotsAreReadableAndCorruptViewCountsCannotCertifyHardware(){
        assertEquals("top",Phase2GearMotorFlow.restore("5,4,2,111100",1).expectedHardwareView());
        assertEquals("left",Phase2GearMotorFlow.restore("5,4,2,111100,1",1).expectedHardwareView());
        assertEquals(3,Phase2GearMotorFlow.restore("5,4,2,111110",1).hardwareViews().size());
        assertEquals(1,Phase2GearMotorFlow.restore("5,4,2,111100,3",1).step());
    }
    @Test public void previousAndRescanPreserveEarlierConfirmedViews(){
        Phase2GearMotorFlow f=new Phase2GearMotorFlow(0);f.devJump(5,1);
        scan(f,"top");f.next(2);scan(f,"left");f.next(3);scan(f,"right");
        assertTrue(f.previous(4));assertEquals("left",f.expectedHardwareView());assertTrue(f.canNext());
        f.rescan(5);assertEquals(1,f.hardwareViews().size());assertFalse(f.canNext());assertFalse(f.complete());
        assertTrue(f.hardwareViews().contains("top"));
    }
    @Test public void attachedPhaseFlowUsesTheSameCameraGenerationAndMode(){
        Phase2GearMotorFlow gear=new Phase2GearMotorFlow(100);
        PhaseFlow phase=new PhaseFlow(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES),100);
        phase.attachGearMotorFlow(gear);assertEquals(gear.tag(),phase.tag());
        assertTrue(phase.accept(gear.tag(),101));gear.devJump(5,200);
        assertEquals(PhaseFlow.Mode.VERIFY,phase.getMode());
        phase.interrupt(201);assertFalse(phase.collectsEvidence());phase.resume(202);assertTrue(phase.collectsEvidence());
    }
    private static void scan(Phase2GearMotorFlow f,String view){f.observeHardware(view,true);}
}
