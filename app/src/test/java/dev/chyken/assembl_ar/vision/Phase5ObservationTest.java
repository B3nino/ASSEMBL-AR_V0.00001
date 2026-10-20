package dev.chyken.assembl_ar.vision;
import org.junit.Test;
import java.util.*;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.camera.Phase5AssetCatalog;
import static org.junit.Assert.*;

public final class Phase5ObservationTest {
    private List<VisionPoint> quad(double angle){
        List<VisionPoint> points=new ArrayList<>();for(double[] p:new double[][]{{100,100},{160,100},{160,260},{100,260}})points.add(new VisionPoint(p[0]*Math.cos(angle)-p[1]*Math.sin(angle),p[0]*Math.sin(angle)+p[1]*Math.cos(angle)));return points;
    }
    @Test public void tabletRotationDoesNotChangePlacementButReversingOnlyTheNanoDoes(){
        for(double angle:new double[]{0,.4,Math.PI/2,Math.PI,4.5}){
            List<VisionPoint> expected=quad(angle),reversed=new ArrayList<>(expected);Collections.rotate(reversed,2);
            assertEquals(Phase5Observation.State.PLACED,Phase5Observation.placement(expected,expected));assertEquals(Phase5Observation.State.BACKWARD,Phase5Observation.placement(expected,reversed));
        }
    }
    @Test public void aShiftedNanoCannotUseTheDriversLooserFootprintTolerance(){
        List<VisionPoint> moved=new ArrayList<>();for(VisionPoint point:quad(0))moved.add(new VisionPoint(point.getX()+20,point.getY()));
        assertEquals(Phase5Observation.State.MISALIGNED,Phase5Observation.placement(quad(0),moved));
        assertFalse(new Phase5Observation(Phase5Observation.State.MISALIGNED,100).accepted(3));
    }
    @Test public void conflictingOrWeakPhotoEvidenceNeverBecomesGreen(){
        assertEquals(Phase5Observation.State.SEARCHING,Phase5Observation.classify(1,11,0,0,0,0));
        assertEquals(Phase5Observation.State.FRONT,Phase5Observation.classify(1,25,2,0,0,0));assertEquals(Phase5Observation.State.FLIPPED,Phase5Observation.classify(1,2,25,0,0,0));
        assertEquals(Phase5Observation.State.SEARCHING,Phase5Observation.classify(3,0,0,20,19,0));
        assertEquals(Phase5Observation.State.PLACED,Phase5Observation.classify(3,0,0,30,10,0));assertEquals(Phase5Observation.State.BACKWARD,Phase5Observation.classify(3,0,0,10,30,0));
        assertEquals(Phase5Observation.State.MISALIGNED,Phase5Observation.classify(3,0,0,10,0,30));
        assertFalse(new Phase5Observation(Phase5Observation.State.FRONT,30).accepted(3));assertFalse(new Phase5Observation(Phase5Observation.State.PLACED,30).accepted(4));
    }
    @Test public void requestAndResultCopiesKeepTheirNanoStageEvidenceAndTag(){
        RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(NeobotSeedData.MICROCONTROLLER),null,null,null,1,5).withPhase5Stage(3).withRequestTag("fresh");
        assertEquals(3,request.getPhase5Stage());assertEquals("fresh",request.getRequestTag());assertNull(request.getSpatialAnchorComponentId());
        Phase5Observation observation=new Phase5Observation(Phase5Observation.State.PLACED,30);
        RecognitionResult result=RecognitionResult.builder(100).build().withPhase5Observation(observation).withAssemblyInspection(5,null,false,null).withRequestTag("fresh");
        assertSame(observation,result.getPhase5Observation());assertEquals("fresh",result.getRequestTag());assertNull(result.getPose());
    }
    @Test public void negativeDatasetsAreNeverEligibleForGenericIdentityOrBoardFallback(){
        for(String role:new String[]{"front","back","assembly","invalid"}){
            ComponentReferenceSpec spec=ComponentReferenceSpec.builder(Phase5AssetCatalog.PREFIX+role+"_photo",NeobotSeedData.MICROCONTROLLER,"Nano",18,45).jpegAssetPath("references/Phase5/example.jpg").assemblyStepNumber(5).build();
            assertFalse(ComponentRecognizer.isReferenceEligibleForRequest(spec,RecognitionRequest.unconstrained(null)));assertFalse(ComponentRecognizer.isAssemblyReferenceEligibleForBoardFallback(spec));
        }
    }
    @Test(expected=IllegalArgumentException.class)public void wrongStageCannotStartAScan(){new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(NeobotSeedData.MICROCONTROLLER),null,null,null,1,5).withPhase5Stage(2);}
}
