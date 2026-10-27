package dev.chyken.assembl_ar.vision;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrainedYoloDecoderTest {
    @Test public void nmsKeepsSeparateInstancesAndSuppressesDuplicateBoxesPerClass(){
        float[] output={100,102,400,100, 250,250,250,250, 60,60,60,60, 80,80,80,80,
                .95f,.90f,.85f,.02f, .01f,.02f,.03f,.96f};
        List<DnnRoiDecoder.Detection> boxes=DnnRoiDecoder.detections(output,2,4,.8,.45,new DnnRoiDecoder.Letterbox(512,512,512));
        assertEquals(3,boxes.size());
        assertEquals(1,boxes.get(0).label);assertEquals(0,boxes.get(1).label);assertEquals(0,boxes.get(2).label);
        assertTrue(boxes.get(2).bounds.x>300);
    }
    @Test public void paddingInvalidConfidenceAndNonFiniteGeometryCannotBecomeProposals(){
        float[] output={256,256,Float.NaN,256, 5,256,256,256, 40,40,40,40, 10,40,40,40,
                .99f,Float.POSITIVE_INFINITY,.95f,.79f};
        assertTrue(DnnRoiDecoder.detections(output,1,4,.8,.45,new DnnRoiDecoder.Letterbox(640,400,512)).isEmpty());
    }
    @Test(expected=IllegalArgumentException.class) public void wrongChannelLayoutIsRejected(){
        DnnRoiDecoder.detections(new float[6],1,2,.8,.45,new DnnRoiDecoder.Letterbox(512,512,512));
    }
    @Test public void outputWorkIsBoundedEvenWhenEveryAnchorIsOverconfident(){
        int n=5376;float[] output=new float[5*n];
        for(int i=0;i<n;i++){output[i]=(i%70)*7;output[n+i]=(i/70)*6;output[2*n+i]=4;output[3*n+i]=4;output[4*n+i]=.99f;}
        assertTrue(DnnRoiDecoder.detections(output,1,n,.8,.45,new DnnRoiDecoder.Letterbox(512,512,512)).size()<=16);
    }
}
