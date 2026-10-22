package dev.chyken.assembl_ar.workflow.phase;

import org.junit.Test;
import static org.junit.Assert.*;
import dev.chyken.assembl_ar.vision.Phase7Observation;

public final class Phase7CasterTrackerFlowTest {
    @Test public void eachComponentScanIsFollowedByItsOwnMandatoryHardwarePage(){
        Phase7CasterTrackerFlow f=new Phase7CasterTrackerFlow(100);
        for(int step=1;step<=2;step++){
            assertEquals(step,f.step());assertTrue(f.scanning());assertFalse(f.preparingHardware());
            f.confirmHardware();assertFalse(f.hardwareReady());assertFalse(f.nextStage(101+step));
            Object tag=f.tag();f.observe(found(step==1?1:2));
            assertTrue(f.stageComplete());assertFalse(f.complete());assertFalse(f.hardwareReady());
            assertTrue(f.nextStage(110+step));assertEquals(step,f.step());assertTrue(f.preparingHardware());
            assertFalse(f.scanning());assertFalse(f.accepts(tag,120+step));assertFalse(f.nextStage(120+step));
            f.confirmHardware();assertTrue(f.stageComplete());assertTrue(f.complete());
            assertTrue(f.nextStage(130+step));
        }
        assertEquals(3,f.step());assertTrue(f.viewingDemo());assertFalse(f.scanning());
    }
    @Test public void hardwarePageResumesAndPreviousKeepsBothGates(){
        Phase7CasterTrackerFlow f=new Phase7CasterTrackerFlow(100);f.observe(found(1));f.nextStage(101);
        Phase7CasterTrackerFlow r=Phase7CasterTrackerFlow.restore(f.snapshot(),200);
        assertTrue(r.preparingHardware());assertTrue(r.scanned());assertFalse(r.hardwareReady());assertFalse(r.scanning());
        r.confirmHardware();assertTrue(r.previous(201));assertFalse(r.preparingHardware());assertTrue(r.scanned());
        assertTrue(r.nextStage(202));assertTrue(r.preparingHardware());assertTrue(r.hardwareReady());
        r.nextStage(203);assertEquals(2,r.step());r.previous(204);assertEquals(1,r.step());assertTrue(r.preparingHardware());
    }
    @Test public void oldSavedInventoryScansMigrateToHardwareWithoutScanningAgain(){
        for(String version:new String[]{"1,1,10000000000,0,false","2,1,10000000000,0,false,false"}){
            Phase7CasterTrackerFlow f=Phase7CasterTrackerFlow.restore(version,100);
            assertTrue(f.scanned());assertTrue(f.preparingHardware());assertFalse(f.hardwareReady());assertFalse(f.scanning());
            f.confirmHardware();assertTrue(f.nextStage(101));assertEquals(2,f.step());
        }
        Phase7CasterTrackerFlow f=Phase7CasterTrackerFlow.restore("2,2,10001000000,0,false,false",100);
        assertEquals(2,f.step());assertFalse(f.preparingHardware());assertTrue(f.scanning());
    }
    @Test public void debugNavigationAndSkippingAddressAllSixScreensIndependently(){
        Phase7CasterTrackerFlow f=new Phase7CasterTrackerFlow(100);
        for(int screen=0;screen<6;screen++){f.devJumpScreen(screen,101+screen);assertEquals(screen,f.screenIndex());}
        f.restart(110);f.devSkipStage(111);assertTrue(f.preparingHardware());assertTrue(f.scanned());assertFalse(f.hardwareReady());
        f.devSkipStage(112);assertTrue(f.hardwareReady());assertTrue(f.nextStage(113));assertEquals(2,f.step());
    }
    @Test public void replayedAssemblyStepsOfferRescanEvenBeforeTheirFirstSuccessfulMatch(){
        for(int step:new int[]{3,4}){
            Phase7CasterTrackerFlow f=new Phase7CasterTrackerFlow(100);
            f.devJump(step,101);assertFalse(f.canRescan());
            f.demoFinished();f.armScan(102);assertTrue(f.canRescan());
            f.previous(103);assertTrue(f.viewingDemo());assertFalse(f.scanned());assertTrue(f.canRescan());
            f.rescan(104);assertEquals(step,f.step());assertFalse(f.viewingDemo());assertTrue(f.scanning());
        }
    }
    @Test public void rescanningAReviewedAssemblyStepKeepsTheOtherCompletedSteps(){
        for(int step:new int[]{3,4}){
            Phase7CasterTrackerFlow f=new Phase7CasterTrackerFlow(100);f.devComplete();
            f.devJump(step,101);assertTrue(f.canRescan());Object old=f.tag();
            f.rescan(102);assertFalse(f.complete());assertFalse(f.accepts(old,103));
            assertTrue(f.scanning());assertTrue(f.demoReady());
            if(step==3){assertFalse(f.sandwiched());assertFalse(f.swivels());}
            f.devJump(step==3?4:3,104);assertTrue(f.complete());
            f.devJump(1,105);assertTrue(f.complete());f.devJump(2,106);assertTrue(f.complete());
        }
    }
    @Test public void separateSingleTrackerFramesCannotCompleteTheInstalledPairScan(){
        Phase7CasterTrackerFlow f=new Phase7CasterTrackerFlow(100);
        f.devJump(4,101);f.demoFinished();f.armScan(102);
        f.observe(Phase7Observation.installedTrackers(1,1,true,40));
        f.observe(Phase7Observation.installedTrackers(1,1,true,45));
        assertFalse(f.scanned());assertTrue(f.scanning());
        f.observe(Phase7Observation.installedTrackers(2,2,true,90));
        assertTrue(f.scanned());assertFalse(f.complete());
        f.confirmStable(true);assertTrue(f.complete());
    }
    @Test public void twoLooseModulesNeverSubstituteForTwoVerifiedBoardMounts(){
        Phase7CasterTrackerFlow f=new Phase7CasterTrackerFlow(100);
        f.devJump(4,101);f.demoFinished();f.armScan(102);
        f.observe(Phase7Observation.installedTrackers(2,0,false,90));assertFalse(f.scanned());
        f.observe(Phase7Observation.installedTrackers(2,1,true,90));assertFalse(f.scanned());
    }
    private static Phase7Observation found(int count){return new Phase7Observation(count,false,30);}
    private Phase7CasterTrackerFlow throughInventory(){
        Phase7CasterTrackerFlow f=new Phase7CasterTrackerFlow(100);
        assertFalse(f.next(101));f.observe(found(1));assertFalse(f.complete());
        assertTrue(f.nextStage(101));assertTrue(f.preparingHardware());f.confirmHardware();assertTrue(f.complete());assertTrue(f.next(102));
        f.confirmHardware();assertFalse(f.hardwareReady());assertFalse(f.complete());
        f.observe(found(1));f.observe(found(1));assertEquals(1,f.trackerCount());assertFalse(f.complete());
        f.observe(new Phase7Observation(2,true,40));assertFalse(f.complete());
        f.observe(found(2));assertFalse(f.complete());assertTrue(f.nextStage(102));f.confirmHardware();assertTrue(f.complete());assertTrue(f.next(103));return f;
    }
    @Test public void installedScanRequiresDemoAndExplicitScanWorkThenBothCasterAnswers(){
        Phase7CasterTrackerFlow f=throughInventory();assertFalse(f.scanning());
        f.armScan(104);f.observe(found(1));assertFalse(f.scanned());
        f.demoFinished();assertFalse(f.scanning());f.observe(found(1));assertFalse(f.scanned());
        f.armScan(105);assertTrue(f.scanning());f.observe(found(1));assertTrue(f.scanned());assertFalse(f.complete());
        f.confirmSandwiched(true);assertFalse(f.complete());f.confirmSwivels(false);assertFalse(f.sandwiched());
        f.confirmSandwiched(true);f.confirmSwivels(true);assertTrue(f.next(106));
        f.confirmStable(true);assertFalse(f.complete());f.demoFinished();f.armScan(107);f.observe(found(1));
        assertFalse(f.canFinish(false));f.confirmStable(false);assertFalse(f.canFinish(false));
        f.confirmStable(true);assertTrue(f.allComplete());assertTrue(f.canFinish(false));
    }
    @Test public void staleFramesAndFramesFromBeforeModalOrReviewCannotUnlock(){
        Phase7CasterTrackerFlow f=new Phase7CasterTrackerFlow(100);Object tag=f.tag();
        assertFalse(f.accepts(tag,99));assertTrue(f.accepts(tag,101));assertFalse(f.accepts(tag,101));
        f.pause();assertFalse(f.accepts(tag,102));f.resume(200);assertFalse(f.accepts(tag,201));
        assertFalse(f.accepts(f.tag(),199));assertTrue(f.accepts(f.tag(),201));
        Object fresh=f.tag();f.devJump(2,300);assertFalse(f.accepts(fresh,301));
    }
    @Test public void resumeKeepsScanHardwareVideoAndFirstPhysicalAnswer(){
        Phase7CasterTrackerFlow f=throughInventory();f.demoFinished();f.armScan(110);f.observe(found(1));f.confirmSandwiched(true);
        Phase7CasterTrackerFlow r=Phase7CasterTrackerFlow.restore(f.snapshot(),200);
        assertEquals(3,r.step());assertTrue(r.demoReady());assertTrue(r.scanned());assertTrue(r.sandwiched());assertFalse(r.swivels());
        assertFalse(r.scanning());r.confirmSwivels(true);assertTrue(r.next(201));assertFalse(r.scanning());
        r.previous(202);r.rescan(203);assertTrue(r.scanning());assertFalse(r.sandwiched());assertTrue(r.demoReady());
        r.previous(204);assertEquals(3,r.step());assertTrue(r.viewingDemo());r.previous(205);assertTrue(r.complete());assertEquals(2,r.trackerCount());
        r.previous(205);r.previous(206);assertTrue(r.complete());assertTrue(r.hardwareReady());
    }
    @Test public void anArmedScanCanResumeButUsesANewCaptureWindow(){
        Phase7CasterTrackerFlow f=throughInventory();f.demoFinished();f.armScan(110);Object old=f.tag();
        Phase7CasterTrackerFlow r=Phase7CasterTrackerFlow.restore(f.snapshot(),200);
        assertTrue(r.scanning());assertFalse(r.accepts(old,150));assertFalse(r.accepts(r.tag(),199));
        assertTrue(r.accepts(r.tag(),201));r.suspendScan(202);assertFalse(r.scanning());
    }
    @Test public void jumpingToFinalChecklistDoesNotCompleteUnverifiedEarlierSteps(){
        Phase7CasterTrackerFlow f=new Phase7CasterTrackerFlow(0);f.devJump(4,1);f.demoFinished();f.armScan(2);f.observe(found(1));f.confirmStable(true);
        assertTrue(f.complete());assertFalse(f.canFinish(false));assertTrue(f.canFinish(true));
        f.restart(3);assertEquals(1,f.step());assertFalse(f.complete());assertTrue(f.scanning());
    }
    @Test public void legacyCasterCompletionDoesNotEraseTrackerWorkOrManualInstructions(){
        Phase7CasterTrackerFlow f=Phase7CasterTrackerFlow.restore("legacyCaster",100);
        assertEquals(2,f.step());assertTrue(f.scanning());f.previous(101);assertTrue(f.complete());
        f.devJump(3,102);assertTrue(f.complete());assertFalse(f.allComplete());
        AssemblyPhase p=AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.CASTER_FIT);
        assertSame(p,AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.LINE_TRACKERS));
        assertEquals(java.util.Arrays.asList(9,10),AssemblyPhaseCatalog.sectionsCompletedBy(p));
        assertEquals(6,PhaseGuideMetadata.subSteps(p).size());assertEquals(7,p.getNumber());
        assertEquals(AssemblyPhaseCatalog.DISTANCE_SENSOR_MOUNT,AssemblyPhaseCatalog.phases().get(7).getId());
        PhaseSessionCheckpoint checkpoint=PhaseSessionCheckpoint.decode("1;line_trackers;false;DEMO;2;0;true;false;");
        assertNotNull(checkpoint);assertEquals(p.getId(),checkpoint.phaseId);
    }
    @Test public void previousFromVerificationReturnsToTheSameDemoAndPreservesItsEvidence(){
        Phase7CasterTrackerFlow f=throughInventory();f.demoFinished();f.armScan(110);Object old=f.tag();
        assertTrue(f.previous(111));assertEquals(3,f.step());assertTrue(f.viewingDemo());assertFalse(f.scanning());assertTrue(f.demoReady());assertFalse(f.accepts(old,112));
        Phase7CasterTrackerFlow restored=Phase7CasterTrackerFlow.restore(f.snapshot(),120);assertTrue(restored.viewingDemo());assertFalse(restored.scanning());
        restored.armScan(121);restored.observe(found(1));restored.confirmSandwiched(true);restored.confirmSwivels(true);assertTrue(restored.next(122));
        restored.demoFinished();restored.armScan(123);assertEquals(4,restored.step());assertTrue(restored.scanning());
        restored.previous(124);assertEquals(4,restored.step());assertTrue(restored.viewingDemo());assertFalse(restored.scanned());
        restored.armScan(125);restored.observe(Phase7Observation.installedTrackers(2,2,true,90));assertTrue(restored.scanned());
        restored.previous(126);assertEquals(4,restored.step());assertTrue(restored.viewingDemo());assertTrue(restored.scanned());
        restored.armScan(127);assertFalse(restored.scanning());assertTrue(restored.scanned());assertFalse(restored.viewingDemo());
        restored.previous(128);restored.previous(129);assertEquals(3,restored.step());assertTrue(restored.viewingDemo());assertTrue(restored.complete());
    }
    @Test public void anImageCanLocateTheTrackersButOnlyTheUserCanConfirmTheirHardwareAndStability(){
        Phase7CasterTrackerFlow f=throughInventory();f.demoFinished();f.armScan(110);f.observe(found(1));f.confirmSandwiched(true);f.confirmSwivels(true);f.next(111);
        f.demoFinished();f.armScan(112);Phase7Observation camera=Phase7Observation.installedTrackers(2,2,true,90,0);
        assertEquals(0,camera.imageConfirmedNuts);f.observe(camera);assertTrue(f.scanned());assertFalse(f.complete());assertFalse(f.canFinish(false));
        f.confirmStable(false);assertFalse(f.canFinish(false));f.confirmStable(true);assertTrue(f.canFinish(false));
    }
    @Test public void previousSnapshotVersionKeepsItsScanAndPhysicalAnswers(){
        Phase7CasterTrackerFlow legacy=Phase7CasterTrackerFlow.restore("1,3,11101110110,2,false",100);
        assertEquals(3,legacy.step());assertTrue(legacy.scanned());assertTrue(legacy.sandwiched());assertTrue(legacy.swivels());assertFalse(legacy.viewingDemo());
        assertTrue(legacy.previous(101));assertEquals(3,legacy.step());assertTrue(legacy.viewingDemo());assertTrue(legacy.scanned());
    }
    @Test public void malformedStateCannotUnlockAnything(){
        for(String bad:new String[]{null,"","1,5,11111111111,2,true","1,1,111,2,true","1,2,11111111111,3,true","1,1,11111111111,2,yes"}){
            Phase7CasterTrackerFlow f=Phase7CasterTrackerFlow.restore(bad,100);assertEquals(1,f.step());assertFalse(f.complete());assertFalse(f.allComplete());
        }
    }
    @Test public void rootFlowDelegatesOnlyTheCurrentScanWindow(){
        PhaseFlow root=new PhaseFlow(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.CASTER_FIT),100);
        Phase7CasterTrackerFlow f=new Phase7CasterTrackerFlow(100);root.attachCasterTrackerFlow(f);
        assertTrue(root.collectsEvidence());assertFalse(root.canScan());assertTrue(root.accept(f.tag(),101));
        root.interrupt(150);assertFalse(root.collectsEvidence());root.resume(200);assertTrue(root.collectsEvidence());
        f.devJump(3,300);assertFalse(root.collectsEvidence());f.demoFinished();f.armScan(301);assertTrue(root.collectsEvidence());
    }
}
