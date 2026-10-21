package dev.chyken.assembl_ar.workflow.phase;
import org.junit.Test;
import static org.junit.Assert.*;

public final class Phase5MicrocontrollerFlowTest {
    @Test public void fullSequenceUnlocksImmediatelyButAlwaysWaitsForExplicitNext(){
        Phase5MicrocontrollerFlow f=new Phase5MicrocontrollerFlow(100);
        assertFalse(f.next(101));f.observe(false);assertFalse(f.complete());
        f.observe(true);assertTrue(f.complete());assertEquals(1,f.step());assertFalse(f.scanning());assertTrue(f.next(110));
        f.observe(true);f.confirmDemo(false);assertFalse(f.next(111));f.confirmDemo(true);assertTrue(f.next(120));
        assertTrue(f.scanning());f.observe(false);assertFalse(f.next(121));f.observe(true);assertTrue(f.complete());assertEquals(3,f.step());
        assertTrue(f.next(130));assertFalse(f.scanning());f.confirmFirm(true);assertFalse(f.canFinish(false));f.confirmPins(true);
        assertTrue(f.canFinish(false));assertTrue(f.allComplete());assertFalse(f.next(140));
    }
    @Test public void looseOrOutsidePinsRequireBothChecksAgain(){
        Phase5MicrocontrollerFlow f=new Phase5MicrocontrollerFlow(0);f.devJump(4,1);f.confirmFirm(true);f.confirmPins(false);
        assertFalse(f.firmConfirmed());assertFalse(f.complete());f.confirmPins(true);assertFalse(f.complete());f.confirmFirm(false);
        assertFalse(f.pinsConfirmed());f.confirmFirm(true);assertFalse(f.complete());f.confirmPins(true);assertTrue(f.complete());
    }
    @Test public void allCheckpointsAndPartialPhysicalAnswersSurviveResume(){
        Phase5MicrocontrollerFlow f=new Phase5MicrocontrollerFlow(0);f.observe(true);f.next(1);f.confirmDemo(true);f.next(2);f.observe(true);f.next(3);f.confirmFirm(true);
        Phase5MicrocontrollerFlow r=Phase5MicrocontrollerFlow.restore(f.snapshot(),100);
        assertEquals(4,r.step());assertTrue(r.firmConfirmed());assertFalse(r.pinsConfirmed());assertFalse(r.complete());r.confirmPins(true);assertTrue(r.canFinish(false));
        r.previous(101);assertTrue(r.complete());assertFalse(r.scanning());assertTrue(r.allComplete());
        r.rescan(102);assertTrue(r.scanning());assertFalse(r.complete());assertFalse(r.allComplete());assertFalse(r.firmConfirmed());
        r.previous(103);assertTrue("Rescanning placement retains the reviewed demo",r.complete());
    }
    @Test public void staleDuplicateAndPausedFramesCannotUnlockAStep(){
        Phase5MicrocontrollerFlow f=new Phase5MicrocontrollerFlow(100);Object old=f.tag();
        assertFalse(f.accepts(old,99));assertTrue(f.accepts(old,101));assertFalse(f.accepts(old,101));f.pause();assertFalse(f.accepts(f.tag(),102));
        f.resume(200);assertFalse(f.accepts(old,201));assertFalse(f.accepts(f.tag(),199));assertTrue(f.accepts(f.tag(),202));
        f.observe(true);f.next(210);f.confirmDemo(true);f.next(220);assertFalse(f.accepts(old,230));assertTrue(f.accepts(f.tag(),230));
    }
    @Test public void devBypassDoesNotCertifyMissingLearnerCheckpoints(){
        Phase5MicrocontrollerFlow f=new Phase5MicrocontrollerFlow(0);f.devJump(4,1);f.devSkip();assertTrue(f.canFinish(true));assertFalse(f.canFinish(false));
        f.restart(2);assertEquals(1,f.step());assertTrue(f.scanning());assertFalse(f.complete());assertFalse(f.firmConfirmed());
    }
    @Test public void parentFlowUsesTheNanoEvidenceWindowAndLifecycle(){
        PhaseFlow root=new PhaseFlow(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MICROCONTROLLER_FIT),0);Phase5MicrocontrollerFlow nano=new Phase5MicrocontrollerFlow(10);root.attachMicrocontrollerFlow(nano);
        assertEquals(nano.tag(),root.tag());assertTrue(root.accept(root.tag(),20));root.interrupt(30);assertFalse(root.collectsEvidence());root.resume(40);assertTrue(root.collectsEvidence());
        nano.observe(true);nano.next(50);assertFalse(root.collectsEvidence());assertFalse(root.canScan());assertEquals(PhaseFlow.Mode.DEMO,root.getMode());assertTrue(root.isInventoryComplete());
    }
    @Test public void invalidSnapshotsReturnToAFreshScan(){
        for(String bad:new String[]{null,"","5,1111,true,true","4,1,true,true","a,1111,true,true","4,1111,yes,true"}){
            Phase5MicrocontrollerFlow f=Phase5MicrocontrollerFlow.restore(bad,10);assertEquals(1,f.step());assertTrue(f.scanning());assertFalse(f.complete());
        }
    }
    @Test public void projectMetadataListsTheOverhauledStepsForTheFirstSixPhases(){
        int[] counts={1,6,6,3,4,4};for(int i=0;i<counts.length;i++){AssemblyPhase p=AssemblyPhaseCatalog.phases().get(i);assertEquals(counts[i],PhaseGuideMetadata.subSteps(p).size());assertFalse(PhaseGuideMetadata.summary(p).isEmpty());}
        assertFalse(PhaseGuideMetadata.overhauled(AssemblyPhaseCatalog.phases().get(6)));
    }
}
