package dev.chyken.assembl_ar.vision;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class Phase7MountGeometryTest {
    private List<VisionPoint> rotated(double degrees){
        List<VisionPoint> result=new ArrayList<>();double a=Math.toRadians(degrees);
        for(double[] p:new double[][]{{-20,-10},{20,-10},{20,160},{-20,160}})
            result.add(new VisionPoint(300+p[0]*Math.cos(a)-p[1]*Math.sin(a),200+p[0]*Math.sin(a)+p[1]*Math.cos(a)));
        return result;
    }
    @Test public void screwStaysAtTheSameAnchorWhenTheSensorSwivels(){
        for(int degrees:new int[]{-60,-30,-15,0,15,30,60,90,180})assertTrue("angle="+degrees,Phase7MountGeometry.fits(rotated(degrees),new VisionPoint(300,200),true));
    }
    @Test public void looseOrWronglyPositionedModulesDoNotPassAnUnrelatedMount(){
        assertFalse(Phase7MountGeometry.fits(rotated(0),new VisionPoint(450,200),true));
        assertFalse(Phase7MountGeometry.fits(rotated(0),new VisionPoint(300,280),true));
        assertFalse(Phase7MountGeometry.fits(Collections.emptyList(),new VisionPoint(300,200),true));
    }
    @Test public void twoSensorsBesideOneBoardMountCannotSubstituteForTheOtherMount(){
        List<VisionPoint> left=rotated(0),nearby=new ArrayList<>(),right=new ArrayList<>();
        for(VisionPoint p:left){nearby.add(new VisionPoint(p.getX()+45,p.getY()));right.add(new VisionPoint(p.getX()+600,p.getY()));}
        VisionPoint[] screws={new VisionPoint(300,200),new VisionPoint(900,200)};boolean[][] wrong=new boolean[2][2],correct=new boolean[2][2];
        for(int i=0;i<2;i++){
            wrong[i][0]=correct[i][0]=Phase7MountGeometry.fits(left,screws[i],true);
            wrong[i][1]=Phase7MountGeometry.fits(nearby,screws[i],true);correct[i][1]=Phase7MountGeometry.fits(right,screws[i],true);
        }
        assertEquals(1,Phase7MountGeometry.distinctMounts(wrong));assertEquals(2,Phase7MountGeometry.distinctMounts(correct));
    }
    @Test public void trackerInstallationNeedsBothBodiesBothMountPositionsAndBoardContext(){
        for(int bodies=0;bodies<=2;bodies++)for(int mounts=0;mounts<=2;mounts++)for(boolean board:new boolean[]{false,true}){
            Phase7Observation proof=Phase7Observation.installedTrackers(bodies,mounts,board,80);
            assertEquals(bodies==2&&mounts==2&&board,proof.withCaster(true).accepted(4));assertFalse("An unclear view is not a missing-part alert",proof.invalid);
        }
    }
}
