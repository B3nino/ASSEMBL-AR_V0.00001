package dev.chyken.assembl_ar.workflow.phase;
import dev.chyken.assembl_ar.model.MotorWireColorConfig;
import dev.chyken.assembl_ar.model.MotorWireColorConfig.Swatch;
import dev.chyken.assembl_ar.model.MotorWireColorConfig.Terminal;
import org.junit.Test;
import static org.junit.Assert.*;

public final class Phase4MotorWiringFlowTest {
    @Test public void applyingColorsAdvancesButWiringAndPullTestRequireTheirOwnConfirmations(){
        Phase4MotorWiringFlow f=new Phase4MotorWiringFlow();assertEquals(1,f.step());assertFalse(f.next());
        f.applyColors(MotorWireColorConfig.defaults());assertEquals(2,f.step());assertFalse(f.next());
        f.confirmPullTest(true);assertFalse(f.complete());f.confirmWired();assertTrue(f.complete());assertEquals(2,f.step());
        assertTrue(f.next());assertEquals(3,f.step());assertFalse(f.canFinish(false));
        f.confirmPullTest(false);assertFalse(f.canFinish(false));f.confirmPullTest(true);assertTrue(f.canFinish(false));assertFalse(f.next());
    }
    @Test public void looseWireDoesNotCompletePhase(){
        Phase4MotorWiringFlow f=finished();f.confirmPullTest(false);assertFalse(f.complete());assertFalse(f.canFinish(false));
    }
    @Test public void editingColorsInvalidatesChecksButReapplyingSameColorsPreservesThem(){
        Phase4MotorWiringFlow f=finished();MotorWireColorConfig changed=f.colors().with(Terminal.RIGHT_NEGATIVE,Swatch.PURPLE);
        f.applyColors(changed);assertEquals(2,f.step());assertFalse(f.complete());f.next();assertEquals(2,f.step());
        f.confirmWired();f.next();assertFalse(f.complete());f.confirmPullTest(true);f.applyColors(changed);assertTrue(f.complete());assertTrue(f.next());assertTrue(f.canFinish(false));
    }
    @Test public void lookingBackAndPausingPreserveVerifiedWiringAndSelectedColors(){
        Phase4MotorWiringFlow f=finished();String state=f.snapshot();int generation=f.tag().getGeneration();f.interrupt();assertTrue(f.tag().getGeneration()>generation);
        assertTrue(f.previous());assertTrue(f.previous());assertEquals(1,f.step());assertTrue(f.complete());assertTrue(f.allComplete());
        Phase4MotorWiringFlow restored=Phase4MotorWiringFlow.restore(state);assertTrue(restored.canFinish(false));assertEquals(f.colors(),restored.colors());
    }
    @Test public void everySubstepAndTheCompleteUnlockSurviveReopening(){
        Phase4MotorWiringFlow f=new Phase4MotorWiringFlow();
        assertEquals(f.snapshot(),Phase4MotorWiringFlow.restore(f.snapshot()).snapshot());
        f.applyColors(f.colors().with(Terminal.LEFT_NEGATIVE,Swatch.BLUE));
        assertEquals(f.snapshot(),Phase4MotorWiringFlow.restore(f.snapshot()).snapshot());
        f.confirmWired();assertTrue(Phase4MotorWiringFlow.restore(f.snapshot()).complete());f.next();
        assertEquals(f.snapshot(),Phase4MotorWiringFlow.restore(f.snapshot()).snapshot());f.confirmPullTest(true);assertTrue(Phase4MotorWiringFlow.restore(f.snapshot()).canFinish(false));
    }
    @Test public void devCompletionDoesNotSubstituteForLearnerCheckpoints(){
        Phase4MotorWiringFlow f=new Phase4MotorWiringFlow();f.devJump(3);f.devSkip();assertTrue(f.canFinish(true));assertFalse(f.canFinish(false));
        f.restart();assertEquals(1,f.step());assertFalse(f.complete());
    }
    @Test public void parentFlowCannotAcceptCameraEvidenceForAnyWiringSubstep(){
        PhaseFlow parent=new PhaseFlow(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_LEADS),0);Phase4MotorWiringFlow f=new Phase4MotorWiringFlow();parent.attachWiringFlow(f);
        for(int step=1;step<=3;step++){f.devJump(step);assertEquals(f.mode(),parent.getMode());assertEquals(f.tag(),parent.tag());assertFalse(parent.collectsEvidence());assertFalse(parent.canScan());assertFalse(parent.accept(parent.tag(),1000));}
    }
    @Test public void corruptSnapshotsNeverUnlockARealStep(){
        for(String bad:new String[]{null,"","4,111|ff000000/ff000000/ff000000/ff000000","2,zzz|bad","1,111|ff123456/ff000000/ffff0000/ff000000"}){
            Phase4MotorWiringFlow f=Phase4MotorWiringFlow.restore(bad);assertEquals(1,f.step());assertFalse(f.complete());
        }
    }
    private Phase4MotorWiringFlow finished(){Phase4MotorWiringFlow f=new Phase4MotorWiringFlow();f.applyColors(f.colors().with(Terminal.RIGHT_POSITIVE,Swatch.YELLOW));f.confirmWired();f.next();f.confirmPullTest(true);return f;}
}
