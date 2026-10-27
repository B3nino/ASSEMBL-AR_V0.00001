package dev.chyken.assembl_ar.vision;

import org.junit.Test;
import static org.junit.Assert.*;

public class HybridRecoveryPolicyTest {
    @Test public void structuralEvidenceIsEligibleOnTheFirstFrameWithoutMissHistory(){
        AdaptiveIlluminationPolicy p=new AdaptiveIlluminationPolicy();assertTrue(p.edgeRecovery());
        for(int i=0;i<10000;i++){p.observe(false);assertTrue(p.edgeRecovery());}
        p.observe(true);assertTrue(p.edgeRecovery());p.reset();assertTrue(p.edgeRecovery());
    }
    @Test public void trainedBoundsAreClampedAndCannotAcceptInvalidNumbers(){
        DnnRoiDecoder.Letterbox transform=new DnnRoiDecoder.Letterbox(640,400,512);
        org.opencv.core.Rect r=transform.bounds(256,256,200,100);
        assertNotNull(r);assertTrue(r.x>=0&&r.y>=0&&r.x+r.width<=640&&r.y+r.height<=400);
        assertNull(transform.bounds(Double.NaN,0,1,1));
        assertNull(transform.bounds(200,200,-1,20));
        assertNull(transform.bounds(200,200,.001,.001));
        assertNull(transform.bounds(900,900,20,20));
    }
    @Test public void optionalDetectorCannotManufactureAcceptance(){assertFalse(VerifiedScanEvidence.workflowAccepted(null,null));assertFalse(TrainedModelPolicy.assisted(null));}
    @Test public void structuralWinnerPersistsAcrossTheConsecutiveScanGate(){
        AdaptiveIlluminationPolicy p=new AdaptiveIlluminationPolicy();p.observe(false);p.observe(false);assertTrue(p.edgeRecovery());
        for(int i=0;i<5;i++){p.observe(true,true);assertTrue(p.edgeRecovery());}
        p.observe(false,true);assertTrue(p.edgeRecovery());p.observe(true,false);assertTrue(p.edgeRecovery());assertFalse(p.structuralFirst());
    }
}
