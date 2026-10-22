package dev.chyken.assembl_ar.vision;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public final class Phase8RecognitionTest {
    @Test public void inwardSensorNeverCompletesEitherScanGate(){Phase8Observation inward=new Phase8Observation(Phase8Observation.State.INWARD,100);assertFalse(inward.accepted(1));assertFalse(inward.accepted(2));}
    @Test public void requestCopiesPreserveStageAndTag(){RecognitionRequest r=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("ultrasonic_distance_sensor"),null,null,null,1,8).withPhase8Stage(2).withRequestTag("generation");assertEquals(2,r.getPhase8Stage());assertEquals("generation",r.getRequestTag());assertEquals(0,r.getPhase7Stage());}
    @Test(expected=IllegalArgumentException.class)public void otherComponentCannotEnterSensorMatcher(){new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("main_board"),null,null,null,1,8).withPhase8Stage(2);}
    @Test public void ambiguousOrMissingEvidenceNeverUnlocks(){assertEquals(Phase8Observation.State.SEARCHING,Phase8Observation.classify(2,0,0,12,0,12));assertEquals(Phase8Observation.State.INVALID,Phase8Observation.classify(2,0,0,16,0,64));assertEquals(Phase8Observation.State.PLACED,Phase8Observation.classify(2,0,0,14,0,0));assertFalse(new Phase8Observation(Phase8Observation.State.FRONT,100).accepted(2));}
    @Test public void boardRelativeCornerCheckAcceptsAlignedAndRejectsReversed(){List<VisionPoint> c=Arrays.asList(new VisionPoint(0,0),new VisionPoint(100,0),new VisionPoint(100,40),new VisionPoint(0,40));assertEquals(Phase8Observation.State.PLACED,Phase8Observation.placement(c,c));assertEquals(Phase8Observation.State.INVALID,Phase8Observation.placement(c,Arrays.asList(c.get(2),c.get(3),c.get(0),c.get(1))));}
}
