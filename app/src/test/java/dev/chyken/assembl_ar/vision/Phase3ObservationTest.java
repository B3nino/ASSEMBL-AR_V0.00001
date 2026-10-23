package dev.chyken.assembl_ar.vision;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class Phase3ObservationTest {
    private List<VisionPoint> quad(){return Arrays.asList(new VisionPoint(100,100),new VisionPoint(220,100),new VisionPoint(220,260),new VisionPoint(100,260));}
    private List<VisionPoint> rotate(List<VisionPoint> points,double angle){
        List<VisionPoint> out=new ArrayList<>();double c=Math.cos(angle),s=Math.sin(angle);
        for(VisionPoint p:points)out.add(new VisionPoint(p.getX()*c-p.getY()*s,p.getX()*s+p.getY()*c));return out;
    }
    @Test public void rotatingCameraDoesNotReverseCorrectModule(){
        for(double a:new double[]{0,.4,Math.PI/2,Math.PI,Math.PI*1.5}){
            List<VisionPoint> corners=rotate(quad(),a);
            assertEquals(Phase3Observation.State.PLACED,Phase3Observation.placement(corners,corners));
        }
    }
    @Test public void moduleReversedRelativeToBoardIsRejectedAtAnyCameraAngle(){
        List<VisionPoint> reversed=new ArrayList<>(quad());Collections.rotate(reversed,2);
        for(double a:new double[]{0,.6,Math.PI/2,Math.PI})assertEquals(Phase3Observation.State.BACKWARD,Phase3Observation.placement(rotate(quad(),a),rotate(reversed,a)));
    }
    @Test public void missingOrDisplacedModuleCannotPass(){
        assertEquals(Phase3Observation.State.SEARCHING,Phase3Observation.placement(quad(),Collections.emptyList()));
        List<VisionPoint> moved=new ArrayList<>();for(VisionPoint p:quad())moved.add(new VisionPoint(p.getX()+200,p.getY()));
        assertEquals(Phase3Observation.State.MISALIGNED,Phase3Observation.placement(quad(),moved));
    }
    @Test public void evidenceTypesCannotBeReusedInOtherSteps(){
        assertTrue(new Phase3Observation(Phase3Observation.State.FRONT,30).accepted(1));
        assertFalse(new Phase3Observation(Phase3Observation.State.FRONT,30).accepted(4));
        assertTrue(new Phase3Observation(Phase3Observation.State.PLACED,30).accepted(4));
        assertFalse(new Phase3Observation(Phase3Observation.State.PLACED,30).accepted(5));
        assertFalse(new Phase3Observation(Phase3Observation.State.BACKWARD,30).accepted(5));
        assertFalse(new Phase3Observation(Phase3Observation.State.PLACED,30).accepted(6));
    }
}
