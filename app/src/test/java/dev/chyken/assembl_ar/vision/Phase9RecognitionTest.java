package dev.chyken.assembl_ar.vision;

import org.junit.Test;
import static org.junit.Assert.*;
import static dev.chyken.assembl_ar.data.NeobotSeedData.*;

public final class Phase9RecognitionTest {
    @Test public void boardContextOrOneWheelCannotCompleteInstalledScan(){
        assertFalse(new Phase9Observation(true,0,false,false,true,false,100).accepted(4));
        assertFalse(new Phase9Observation(true,0,true,false,true,false,100).accepted(4));
        assertFalse(new Phase9Observation(true,0,false,true,true,false,100).accepted(4));
        assertFalse(new Phase9Observation(false,0,true,true,true,false,100).accepted(4));
        assertTrue(new Phase9Observation(true,0,true,true,true,false,100).accepted(4));
        assertFalse(new Phase9Observation(true,0,true,true,true,true,100).accepted(4));
    }
    @Test public void standoffScanDoesNotPrematurelyRequireWheelsAndLooseCountNeedsTwo(){
        assertTrue(new Phase9Observation(true,0,false,false,true,false,30).accepted(3));
        assertFalse(new Phase9Observation(true,0,false,false,false,false,30).accepted(3));
        assertFalse(new Phase9Observation(false,1,false,false,false,false,30).accepted(2));
        assertTrue(new Phase9Observation(false,2,false,false,false,false,30).accepted(2));
    }
    @Test public void targetCountsNoAnchorAndStageSurviveRequestCopies(){
        for(int stage=1;stage<=4;stage++){
            String target=stage==1?STANDOFF:stage==2?WHEEL:MAIN_BOARD;
            RecognitionRequest r=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(target),null,null,null,stage==2?2:1,9).withPhase9Stage(stage).withRequestTag("fresh");
            assertEquals(stage,r.getPhase9Stage());assertNull(r.getSpatialAnchorComponentId());assertEquals("fresh",r.getRequestTag());
        }
    }
    @Test(expected=IllegalArgumentException.class) public void oneLooseWheelIsNotATwoWheelRequest(){new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(WHEEL),null,null,null,1,9).withPhase9Stage(2);}
    @Test public void observationSurvivesResultTagAndInspectionCopies(){
        Phase9Observation o=new Phase9Observation(true,0,true,true,true,false,30);
        RecognitionResult r=RecognitionResult.builder(10).build().withPhase9Observation(o).withAssemblyInspection(9,null,false,null).withRequestTag("fresh");
        assertSame(o,r.getPhase9Observation());assertEquals("fresh",r.getRequestTag());assertNull(r.getPose());
    }
}
