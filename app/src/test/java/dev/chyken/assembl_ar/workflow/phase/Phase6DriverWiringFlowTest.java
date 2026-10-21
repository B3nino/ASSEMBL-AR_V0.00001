package dev.chyken.assembl_ar.workflow.phase;
import org.junit.Test;
import static org.junit.Assert.*;
import dev.chyken.assembl_ar.model.*;
import dev.chyken.assembl_ar.model.DriverWireColorConfig.Wire;
import dev.chyken.assembl_ar.model.MotorWireColorConfig.Swatch;
public final class Phase6DriverWiringFlowTest {
    private Phase6DriverWiringFlow throughSignals(){
        Phase6DriverWiringFlow flow=new Phase6DriverWiringFlow(10);
        assertEquals(1,flow.step());assertFalse(flow.scanning());assertFalse(flow.next(11));
        flow.applyColors(DriverWireColorConfig.defaults(),13);assertEquals(2,flow.step());
        assertFalse(flow.next(14));flow.confirmPower(true);assertTrue(flow.next(15));flow.confirmSignals();assertTrue(flow.next(16));return flow;
    }
    @Test public void finalCheckRequiresBothAnswersAndAllEarlierCheckpoints(){
        Phase6DriverWiringFlow flow=throughSignals();assertFalse(flow.canFinish(false));
        flow.confirmSeated(true);assertFalse(flow.canFinish(false));flow.confirmCorrect(false);
        assertFalse(flow.seated());assertFalse(flow.complete());flow.confirmSeated(true);flow.confirmCorrect(true);assertTrue(flow.canFinish(false));
    }
    @Test public void changedColorsReopenPowerAndSignalChecksAndSurviveResume(){
        Phase6DriverWiringFlow flow=throughSignals();flow.confirmSeated(true);flow.confirmCorrect(true);
        flow.previous(20);flow.previous(21);flow.previous(22);
        flow.applyColors(flow.colors().with(Wire.VCC,Swatch.BLUE),23);assertEquals(2,flow.step());assertFalse(flow.complete());
        flow.confirmPower(true);flow.next(24);assertFalse(flow.complete());flow.next(25);assertEquals(3,flow.step());
        Phase6DriverWiringFlow restored=Phase6DriverWiringFlow.restore(flow.snapshot(),30);
        assertEquals(3,restored.step());assertEquals(Swatch.BLUE,restored.colors().swatch(Wire.VCC));assertFalse(restored.seated());assertFalse(restored.correct());
    }
    @Test public void noCameraEvidenceCanAffectAnyWiringStep(){
        Phase6DriverWiringFlow flow=new Phase6DriverWiringFlow(100);
        for(int step=1;step<=4;step++){
            flow.devJump(step,101);assertFalse(flow.scanning());assertFalse(flow.accepts(flow.tag(),102));assertFalse(flow.complete());
            flow.pause();flow.resume(200);assertFalse(flow.scanning());assertFalse(flow.accepts(flow.tag(),201));
        }
    }
    @Test public void reviewerCannotFinishByOnlyCheckingLastStep(){
        Phase6DriverWiringFlow flow=new Phase6DriverWiringFlow(0);flow.devJump(4,1);flow.confirmSeated(true);flow.confirmCorrect(true);
        assertFalse(flow.canFinish(false));assertTrue(flow.canFinish(true));flow.restart(10);assertEquals(1,flow.step());assertFalse(flow.allComplete());
    }
    @Test public void legacySnapshotsDropOnlyTheScanAndKeepColorsAndConfirmations(){
        DriverWireColorConfig colors=DriverWireColorConfig.defaults().with(Wire.VCC,Swatch.BLUE);
        for(int oldStep=1;oldStep<=5;oldStep++){
            Phase6DriverWiringFlow restored=Phase6DriverWiringFlow.restore(oldStep+",11110,"+colors.encode()+",true,false",20);
            assertEquals(Math.max(1,oldStep-1),restored.step());assertEquals(colors,restored.colors());assertTrue(restored.seated());assertFalse(restored.correct());
        }
        Phase6DriverWiringFlow scanOnly=Phase6DriverWiringFlow.restore("2,10000,"+colors.encode()+",false,false",30);
        assertEquals(1,scanOnly.step());assertFalse(scanOnly.complete());
        Phase6DriverWiringFlow finished=Phase6DriverWiringFlow.restore("5,11111,"+colors.encode()+",true,true",30);
        assertTrue(finished.canFinish(false));assertTrue(Phase6DriverWiringFlow.restore(finished.snapshot(),40).canFinish(false));
    }
    @Test public void mergedPhaseOwnsBothSourceSectionsAndOldControlSessionAliasesIt(){
        AssemblyPhase phase=AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.DRIVER_WIRING);
        assertEquals(java.util.Arrays.asList(7,8),AssemblyPhaseCatalog.sectionsCompletedBy(phase));
        assertSame(phase,AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.DRIVER_CONTROL));assertEquals(6,phase.getNumber());
        PhaseSessionCheckpoint checkpoint=PhaseSessionCheckpoint.decode("1;driver_control_signals;false;DEMO;2;0;true;false;");
        assertNotNull(checkpoint);assertEquals(phase.getId(),checkpoint.phaseId);assertTrue(checkpoint.canRestore(phase));
        assertEquals(4,PhaseGuideMetadata.subSteps(phase).size());assertEquals(AssemblyPhaseCatalog.CASTER_FIT,AssemblyPhaseCatalog.phases().get(6).getId());
    }
}

