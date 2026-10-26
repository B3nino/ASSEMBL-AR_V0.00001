package dev.chyken.assembl_ar.vision;
import org.junit.Test;
import static org.junit.Assert.*;
public class LiveVisionPolicyTest {
    private ComponentReferenceSpec ref(String id){return ComponentReferenceSpec.builder(id,id,id,20,40).jpegAssetPath("references/test/"+id+".jpg").build();}
    @Test public void selectedCameraCannotSearchUnrelatedIdentities(){
        RecognitionRequest r=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("driver"),null,null,"board",1,3);
        assertTrue(LiveVisionPolicy.acceptsIdentity(ref("driver"),r));assertTrue(LiveVisionPolicy.acceptsIdentity(ref("board"),r));
        assertFalse(LiveVisionPolicy.acceptsIdentity(ref("oled"),r));
    }
    @Test public void boundsDoNotAlterMatchingOrWorkflowRules(){
        assertEquals(640,LiveVisionPolicy.MAX_ANALYSIS_EDGE);assertEquals(300,LiveVisionPolicy.MAX_ROI_KEYPOINTS);
        assertTrue(LiveVisionPolicy.DETECTOR_INTERVAL_NS<=33_000_000L);assertEquals(.75,OrbMatchPolicy.LOWE_RATIO,0);assertEquals(64,OrbMatchPolicy.MAX_HAMMING);
    }
}
