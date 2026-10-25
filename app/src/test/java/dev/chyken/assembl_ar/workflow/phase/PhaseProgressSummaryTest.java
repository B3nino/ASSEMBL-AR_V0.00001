package dev.chyken.assembl_ar.workflow.phase;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.*;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PhaseProgressSummaryTest {
    private ProjectProgress progress(int sections){List<StepProgress> steps=new ArrayList<>();for(AssemblyStep s:NeobotSeedData.getAssemblySteps())steps.add(new StepProgress(NeobotSeedData.PROJECT_ID,s.getId(),s.getStepNumber()<=sections?StepStatus.COMPLETED:StepStatus.NOT_STARTED,s.getStepNumber()<=sections,1));return new ProjectProgress(NeobotSeedData.PROJECT_ID,sections,18,"",steps);}
    private PhaseProgress.Records records(String completed){return new PhaseProgress.Records(){public boolean isPhaseComplete(String id){return id.equals(completed);}public boolean isLegacyCheckpointComplete(AssemblyStep s,GuidedStep g){return false;}};}
    @Test public void currentCatalogDrivesFreshAndCompletedTotalsWithoutChangingLegacyProgress(){ProjectProgress old=progress(18);assertEquals(18,old.getTotalSteps());PhaseProgressSummary fresh=PhaseProgressSummary.from(progress(0),records("")),done=PhaseProgressSummary.from(old,records(""));assertEquals(11,fresh.total());assertEquals(0,fresh.completed());assertEquals(11,done.completed());assertEquals(100,done.percent());}
    @Test public void percentagesUsePhasesAndDoNotExposeTheLegacySectionDenominator(){PhaseProgressSummary s=PhaseProgressSummary.from(progress(3),records(""));assertEquals(3,s.completed());assertEquals(27,s.percent());assertTrue(s.completed(AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT));assertFalse(s.completed(AssemblyPhaseCatalog.MOTOR_LEADS));}
    @Test public void oneOldCertificateCannotCompleteTheCombinedExtensionPhase(){PhaseProgressSummary s=PhaseProgressSummary.from(progress(14),records(AssemblyPhaseCatalog.OLED_PROTECTOR_FIT));assertFalse(s.completed(AssemblyPhaseCatalog.OLED_PROTECTOR_FIT));assertFalse(s.completed(AssemblyPhaseCatalog.OLED_FIT));}
}
