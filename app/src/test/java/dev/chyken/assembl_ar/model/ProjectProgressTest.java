package dev.chyken.assembl_ar.model;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProjectProgressTest {
    @Test public void unfinishedFirstSectionStillOffersResume(){
        ProjectProgress p=progress(StepStatus.IN_PROGRESS);
        assertEquals(0,p.getCompletedSteps());assertTrue(p.isStarted());assertFalse(p.isComplete());
    }
    @Test public void freshProjectOffersBegin(){assertFalse(progress(StepStatus.NOT_STARTED).isStarted());}
    private ProjectProgress progress(StepStatus status){
        return new ProjectProgress("p",0,11,"s",Collections.singletonList(new StepProgress("p","s",status,false,1)));
    }
}
