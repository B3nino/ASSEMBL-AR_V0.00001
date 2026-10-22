package dev.chyken.assembl_ar.workflow.phase;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
import static dev.chyken.assembl_ar.workflow.phase.Phase9StandoffWheelsFlow.Screen.*;

public final class Phase9StandoffWheelsFlowTest {
    private void scan(Phase9StandoffWheelsFlow f){f.observe(true);assertFalse(f.complete());f.observe(true);assertTrue(f.complete());}
    private Phase9StandoffWheelsFlow standoffCheck(){
        Phase9StandoffWheelsFlow f=new Phase9StandoffWheelsFlow(1);
        scan(f);assertTrue(f.next(2));f.confirm();assertTrue(f.next(3));scan(f);
        assertTrue(f.next(4));f.confirm();assertTrue(f.next(5));f.confirm();assertTrue(f.next(6));scan(f);assertTrue(f.next(7));
        assertEquals(STANDOFF_CHECK,f.screen());return f;
    }
    private Phase9StandoffWheelsFlow wheelsCheck(){
        Phase9StandoffWheelsFlow f=standoffCheck();f.answerNut(true);f.answerTopScrew(true);assertTrue(f.next(8));
        f.confirm();assertTrue(f.next(9));scan(f);assertTrue(f.next(10));return f;
    }
    @Test public void everyDemoHasItsOwnScreenAndExplicitTransition(){
        Phase9StandoffWheelsFlow f=standoffCheck();f.previous(20);assertEquals(STANDOFF_WORK_SCAN,f.screen());
        f.previous(21);assertEquals(STANDOFF_DEMO,f.screen());assertFalse(f.scanning());
        f.rescan(22);assertFalse(f.scanning());assertEquals(STANDOFF_DEMO,f.screen());
        Phase9StandoffWheelsFlow fresh=new Phase9StandoffWheelsFlow(1);fresh.devJump(5,2);
        fresh.observe(true);fresh.observe(true);assertFalse(fresh.next(3));
        fresh.confirm();assertEquals(STANDOFF_DEMO,fresh.screen());assertTrue(fresh.next(4));assertTrue(fresh.scanning());
    }
    @Test public void twoCompleteFramesAreRequiredAndAPartialFrameBreaksTheStreak(){
        Phase9StandoffWheelsFlow f=new Phase9StandoffWheelsFlow(1);f.devJump(3,2);
        f.observe(true);f.observe(false);f.observe(true);assertFalse(f.complete());f.observe(true);assertTrue(f.complete());
    }
    @Test public void staleDuplicateAndPreResumeFramesCannotUnlock(){
        Phase9StandoffWheelsFlow f=new Phase9StandoffWheelsFlow(100);Object old=f.tag();
        assertFalse(f.accepts(old,99));assertTrue(f.accepts(old,101));f.observe(true);assertFalse(f.accepts(old,101));
        f.pause();assertFalse(f.accepts(old,102));f.resume(200);
        assertFalse(f.accepts(old,201));assertFalse(f.accepts(f.tag(),199));assertTrue(f.accepts(f.tag(),201));
        f.observe(true);assertFalse(f.complete());assertTrue(f.accepts(f.tag(),202));f.observe(true);assertTrue(f.complete());
    }
    @Test public void bothNutAndRemovedTopScrewAreMandatoryAndPersistIndividually(){
        Phase9StandoffWheelsFlow f=standoffCheck();f.answerNut(true);assertFalse(f.complete());
        Phase9StandoffWheelsFlow restored=Phase9StandoffWheelsFlow.restore(f.snapshot(),100);
        assertEquals(STANDOFF_CHECK,restored.screen());assertTrue(restored.nutLocked());assertFalse(restored.topScrewRemoved());
        restored.answerTopScrew(false);assertFalse(restored.next(101));restored.answerTopScrew(true);assertTrue(restored.complete());
    }
    @Test public void finalScanAloneNeverCompletesThePhase(){
        Phase9StandoffWheelsFlow f=wheelsCheck();assertFalse(f.canFinish(false));
        f.answerWheels(false);assertFalse(f.complete());f.answerWheels(true);assertTrue(f.canFinish(false));
    }
    @Test public void previousFromEachScanReturnsToItsOwnDemoAndKeepsCompletedEvidence(){
        Phase9StandoffWheelsFlow f=wheelsCheck();f.previous(30);assertEquals(WHEELS_WORK_SCAN,f.screen());assertTrue(f.complete());
        f.previous(31);assertEquals(WHEELS_DEMO,f.screen());assertTrue(f.complete());assertTrue(f.next(32));
        assertEquals(WHEELS_WORK_SCAN,f.screen());assertTrue(f.complete());
    }
    @Test public void guardedWheelRescanClearsOnlyItsVerificationAndPhysicalAnswer(){
        Phase9StandoffWheelsFlow f=wheelsCheck();f.answerWheels(true);f.previous(30);Object old=f.tag();f.rescan(31);
        assertTrue(f.scanning());assertFalse(f.accepts(old,32));assertTrue(f.nutLocked());assertTrue(f.topScrewRemoved());
        scan(f);f.next(40);assertFalse(f.complete());assertFalse(f.canFinish(false));f.answerWheels(true);assertTrue(f.canFinish(false));
    }
    @Test public void standoffRescanRequiresBothPhysicalAnswersAgain(){
        Phase9StandoffWheelsFlow f=standoffCheck();f.answerNut(true);f.answerTopScrew(true);f.previous(20);f.rescan(21);
        assertFalse(f.nutLocked());assertFalse(f.topScrewRemoved());scan(f);f.next(22);assertFalse(f.complete());
    }
    @Test public void developerBypassesRemainSeparateAndRoundTrip(){
        Phase9StandoffWheelsFlow f=new Phase9StandoffWheelsFlow(1);f.devJump(7,2);f.devSkip();
        Phase9StandoffWheelsFlow restored=Phase9StandoffWheelsFlow.restore(f.snapshot(),3);assertTrue(restored.complete());assertTrue(restored.topScrewRemoved());
        restored.devJump(10,4);restored.devSkip();assertTrue(restored.canFinish(true));assertFalse(restored.canFinish(false));
        restored.devComplete();assertTrue(Phase9StandoffWheelsFlow.restore(restored.snapshot(),5).canFinish(false));
    }
    @Test public void legacyWheelSessionKeepsStandoffButRequiresNewTopScrewCheck(){
        PhaseSessionCheckpoint s=new PhaseSessionCheckpoint(AssemblyPhaseCatalog.WHEEL_PAIR,false,PhaseFlow.Mode.DEMO,0,0,false,false,null);
        assertEquals(AssemblyPhaseCatalog.CENTER_STANDOFF,s.phaseId);assertEquals("legacyStandoff",s.gearState);
        Phase9StandoffWheelsFlow f=Phase9StandoffWheelsFlow.restore(s.gearState,1);assertEquals(WHEELS_SCAN,f.screen());
        assertTrue(f.nutLocked());assertFalse(f.topScrewRemoved());f.devJump(7,2);assertFalse(f.complete());f.answerTopScrew(true);assertTrue(f.complete());
    }
    @Test public void alreadyIdentifiedLegacyWheelsDoNotHaveToBeRemovedAndScannedAgain(){
        PhaseSessionCheckpoint s=new PhaseSessionCheckpoint(AssemblyPhaseCatalog.WHEEL_PAIR,false,PhaseFlow.Mode.DEMO,0,0,true,false,null);
        Phase9StandoffWheelsFlow f=Phase9StandoffWheelsFlow.restore(s.gearState,1);assertEquals(STANDOFF_CHECK,f.screen());assertTrue(f.inventoryComplete());assertFalse(f.complete());
        f.answerTopScrew(true);assertTrue(f.next(2));assertEquals(WHEELS_DEMO,f.screen());
    }
    @Test public void catalogCombinesOnlyStandoffAndWheelsAndPreservesLaterContent(){
        AssemblyPhase p=AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.CENTER_STANDOFF);
        assertSame(p,AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.WHEEL_PAIR));assertEquals(9,p.getNumber());
        assertEquals(Arrays.asList(12,13),PhaseProgress.sectionsToSave(p,12));assertEquals(Arrays.asList(13),PhaseProgress.sectionsToSave(p,13));
        assertEquals(p,PhaseProgress.resume(13,new PhaseProgress.Records(){public boolean isPhaseComplete(String id){return false;}public boolean isLegacyCheckpointComplete(dev.chyken.assembl_ar.model.AssemblyStep s,dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep g){return false;}}));
        assertEquals(10,AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.OLED_PROTECTOR_FIT).getNumber());
        assertEquals(10,PhaseGuideMetadata.subSteps(p).size());assertEquals(11,AssemblyPhaseCatalog.ROADMAP_PHASE_COUNT);
    }
    @Test public void specializedRootCannotStartLegacyScannerDuringADemo(){
        Phase9StandoffWheelsFlow f=new Phase9StandoffWheelsFlow(1);PhaseFlow root=new PhaseFlow(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.CENTER_STANDOFF),1);
        root.attachStandoffWheelsFlow(f);assertTrue(root.collectsEvidence());assertFalse(root.canScan());f.devJump(5,2);
        assertFalse(root.collectsEvidence());assertFalse(root.canScan());assertEquals(PhaseFlow.Mode.DEMO,root.getMode());
        f.confirm();f.next(3);assertTrue(root.collectsEvidence());root.interrupt(4);assertFalse(root.collectsEvidence());root.resume(5);assertTrue(root.collectsEvidence());
    }
    @Test public void corruptSnapshotsReturnToFreshScan(){
        for(String bad:new String[]{null,"","1:10,111,11","1:99,1111111111,11","1:10,1111111111,no"}){
            Phase9StandoffWheelsFlow f=Phase9StandoffWheelsFlow.restore(bad,1);assertEquals(STANDOFF_SCAN,f.screen());assertFalse(f.complete());
        }
    }
}
