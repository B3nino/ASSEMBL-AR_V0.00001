package dev.chyken.assembl_ar.workflow.phase;

import org.junit.Test;
import static org.junit.Assert.*;
import dev.chyken.assembl_ar.workflow.phase.Phase10ExtensionBoardFlow.Screen;

public final class Phase10ExtensionBoardFlowTest {
    @Test public void allScreensHaveIsolatedEvidenceAndExplicitNext(){Phase10ExtensionBoardFlow f=new Phase10ExtensionBoardFlow(0);
        for(Screen s:Screen.values()){
            assertEquals(s,f.screen());assertFalse(f.complete());assertEquals(s.scan(),f.scanning());assertFalse(f.next(10));
            if(s.scan()){assertTrue(f.accepts(f.tag(),s.ordinal()*100+31));f.observe(true);assertFalse(f.complete());assertTrue(f.accepts(f.tag(),s.ordinal()*100+32));f.observe(true);}
            else if(s.check())f.answerSecure(true);else f.confirm();
            assertTrue(s.label(),f.complete());assertFalse(f.scanning());
            if(s!=Screen.CABLE_TOP_CHECK)assertTrue(f.next(20));
        }assertTrue(f.canFinish(false));
    }
    @Test public void staleDuplicatePausedAndWrongTagsCannotContribute(){Phase10ExtensionBoardFlow f=new Phase10ExtensionBoardFlow(20);Object tag=f.tag();assertFalse(f.accepts(tag,19));assertTrue(f.accepts(tag,21));assertFalse(f.accepts(tag,21));f.pause();assertFalse(f.accepts(tag,22));f.resume(30);assertFalse(f.accepts(tag,31));assertTrue(f.accepts(f.tag(),31));}
    @Test public void missedOrRejectedFrameClearsShortConfirmation(){Phase10ExtensionBoardFlow f=new Phase10ExtensionBoardFlow(0);f.observe(true);f.observe(false);f.observe(true);assertFalse(f.complete());f.observe(true);assertTrue(f.complete());}
    @Test public void scanHoldDoesNotAdvanceOrStartVideo(){Phase10ExtensionBoardFlow f=new Phase10ExtensionBoardFlow(0);f.observe(true);f.observe(true);assertEquals(Screen.CABLE_SCAN,f.screen());assertTrue(f.complete());}
    @Test public void videoConfirmationRequiresSeparateTapAndNeverScans(){Phase10ExtensionBoardFlow f=new Phase10ExtensionBoardFlow(0);for(Screen s:Screen.values())if(s.video()){f.devJump(s.ordinal()+1,0);assertFalse(f.scanning());f.observe(true);f.observe(true);assertFalse(f.complete());assertFalse(f.next(1));f.confirm();assertTrue(f.next(2));assertEquals(Screen.values()[s.ordinal()+1],f.screen());}}
    @Test public void hardwareCannotBeConfirmedByVision(){Phase10ExtensionBoardFlow f=new Phase10ExtensionBoardFlow(0);f.devJump(3,0);f.observe(true);f.observe(true);assertFalse(f.complete());f.confirm();assertTrue(f.complete());}
    @Test public void mandatoryPhysicalCheckRequiresItsOwnPriorScanOrDemo(){Phase10ExtensionBoardFlow f=new Phase10ExtensionBoardFlow(0);f.devJump(10,0);f.answerSecure(true);assertFalse(f.complete());f.previous(1);f.observe(true);f.observe(true);f.next(2);f.answerSecure(false);assertFalse(f.complete());f.answerSecure(true);assertTrue(f.complete());}
    @Test public void rescanOnlyInvalidatesCorrespondingPhysicalCheck(){Phase10ExtensionBoardFlow f=new Phase10ExtensionBoardFlow(0);f.devComplete();f.devJump(9,0);f.rescan(1);assertFalse(f.complete());f.next(2);assertEquals(Screen.PROTECTOR_WORK_SCAN,f.screen());f.devJump(10,2);assertFalse(f.complete());f.devJump(13,3);assertTrue(f.complete());}
    @Test public void checkpointRestoresExactScreenAndCompletionWithoutTransientEvidence(){Phase10ExtensionBoardFlow f=new Phase10ExtensionBoardFlow(0);f.devComplete();f.devJump(8,0);String state=f.snapshot();Phase10ExtensionBoardFlow restored=Phase10ExtensionBoardFlow.restore(state,100);assertEquals(state,restored.snapshot());assertEquals(Screen.PROTECTOR_DEMO,restored.screen());assertFalse(restored.scanning());}
    @Test public void legacySectionsRetainFinishedProtectorAndCableWork(){Phase10ExtensionBoardFlow f=Phase10ExtensionBoardFlow.restore("legacy:16",0);assertEquals(Screen.BOARD_DEMO,f.screen());assertFalse(f.canFinish(false));f.devJump(10,0);assertTrue(f.complete());f.devJump(15,0);assertTrue(f.complete());}
    @Test public void combinedPhasePreservesManualCoverageAndLaterContent(){assertEquals(11,AssemblyPhaseCatalog.phases().size());assertSame(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.OLED_PROTECTOR_FIT),AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.UPPER_BOARD));assertEquals(java.util.Arrays.asList(14,15,16),PhaseProgress.sectionsToSave(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.OLED_PROTECTOR_FIT),14));assertEquals(11,AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.OLED_FIT).getNumber());}
    @Test public void legacySessionAliasesRetainDestination(){PhaseSessionCheckpoint c=new PhaseSessionCheckpoint(AssemblyPhaseCatalog.UPPER_BOARD,false,PhaseFlow.Mode.ASSEMBLE,0,0,true,false,"");assertEquals(AssemblyPhaseCatalog.OLED_PROTECTOR_FIT,c.phaseId);assertEquals("legacy:16",c.gearState);}
    @Test public void revisitedScanCanRepeatItsChecklistAndReturnWithoutLosingOtherSteps(){Phase10ExtensionBoardFlow f=new Phase10ExtensionBoardFlow(0);f.devComplete();f.devJump(9,1);f.rescan(2);f.observe(true);f.observe(true);assertTrue(f.next(3));assertEquals(Screen.PROTECTOR_CHECK,f.screen());assertFalse(f.complete());f.answerSecure(true);assertTrue(f.complete());f.devJump(15,4);assertTrue(f.canFinish(false));}
}
