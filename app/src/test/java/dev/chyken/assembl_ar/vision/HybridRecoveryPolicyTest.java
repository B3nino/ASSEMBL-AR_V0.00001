package dev.chyken.assembl_ar.vision;

import org.junit.Test;
import static org.junit.Assert.*;

public class HybridRecoveryPolicyTest {
    @Test public void structuralEvidenceIsEligibleOnTheFirstFrameWithoutMissHistory(){
        AdaptiveIlluminationPolicy p=new AdaptiveIlluminationPolicy();assertTrue(p.edgeRecovery());
        for(int i=0;i<10000;i++){p.observe(false);assertTrue(p.edgeRecovery());}
        p.observe(true);assertTrue(p.edgeRecovery());p.reset();assertTrue(p.edgeRecovery());
    }
    @Test public void detectorBoundsAreClampedPaddedAndCannotAcceptInvalidNumbers(){
        org.opencv.core.Rect r=OpenCvDnnRoiLocator.decodeBounds(.2,.3,.5,.6,640,400);
        assertNotNull(r);assertTrue(r.x<128);assertTrue(r.y<120);assertTrue(r.x+r.width>=320);
        assertNull(OpenCvDnnRoiLocator.decodeBounds(Double.NaN,0,1,1,640,400));
        assertNull(OpenCvDnnRoiLocator.decodeBounds(-.1,0,1,1,640,400));
        assertNull(OpenCvDnnRoiLocator.decodeBounds(.5,0,.4,1,640,400));
        assertNull(OpenCvDnnRoiLocator.decodeBounds(0,0,.001,.001,640,400));
    }
    @Test public void optionalDetectorHasNoImplicitModelOrAcceptance(){assertFalse(OpenCvDnnRoiLocator.INSTANCE.available());}
    @Test public void structuralWinnerPersistsAcrossTheConsecutiveScanGate(){
        AdaptiveIlluminationPolicy p=new AdaptiveIlluminationPolicy();p.observe(false);p.observe(false);assertTrue(p.edgeRecovery());
        for(int i=0;i<5;i++){p.observe(true,true);assertTrue(p.edgeRecovery());}
        p.observe(false,true);assertTrue(p.edgeRecovery());p.observe(true,false);assertTrue(p.edgeRecovery());assertFalse(p.structuralFirst());
    }
}
