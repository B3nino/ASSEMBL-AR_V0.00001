package dev.chyken.assembl_ar.vision;
import org.junit.Test;
import org.opencv.core.Rect;
import static org.junit.Assert.*;

public class DnnRoiDecoderTest {
    @Test public void letterboxRemovesPaddingBeforeReturningCameraCoordinates(){
        DnnRoiDecoder.Letterbox t=new DnnRoiDecoder.Letterbox(640,400,640);assertEquals(120,t.top);assertEquals(0,t.left);
        Rect b=t.bounds(320,320,80,40);assertEquals(new Rect(280,180,80,40),b);
        assertNull(t.bounds(320,20,40,20));assertNull(t.bounds(Double.NaN,1,20,20));
    }
    @Test public void portraitAndNonIntegerResizeUseSeparateInverseScales(){
        DnnRoiDecoder.Letterbox t=new DnnRoiDecoder.Letterbox(401,641,640);
        Rect b=t.bounds(t.left+t.resizedWidth/2.0,t.top+t.resizedHeight/2.0,t.resizedWidth,t.resizedHeight);
        assertEquals(new Rect(0,0,401,641),b);
    }
    @Test public void yoloUsesClassConfidenceWithoutAnInventedObjectnessColumn(){
        float[] values={100,320,320,320,20,40,20,40,.4f,.95f};
        DnnRoiDecoder.Box p=DnnRoiDecoder.yolo(values,5,2,0,.8,new DnnRoiDecoder.Letterbox(640,400,640));
        assertNotNull(p);assertEquals(.95,p.confidence,.001);assertEquals(new Rect(300,180,40,40),p.bounds);
    }
    @Test public void chipProposalIsLargerThanAnchorButNeverBecomesBoardEvidence(){
        Rect anchor=new Rect(300,180,20,20),roi=DnnRoiDecoder.searchArea(anchor,640,400,true);
        assertTrue(roi.width>anchor.width*4);assertTrue(roi.x>=0);assertTrue(roi.y>=0);assertTrue(roi.x+roi.width<=640);assertTrue(roi.y+roi.height<=400);
        assertEquals(new Rect(0,0,24,24),DnnRoiDecoder.searchArea(new Rect(1,1,5,5),640,400,true));
    }
    @Test public void invalidOrLowConfidencePredictionsCannotProposeARegion(){
        float[] values={320,320,40,40,.79f};assertNull(DnnRoiDecoder.yolo(values,5,1,0,.8,new DnnRoiDecoder.Letterbox(640,400,640)));
        values[4]=Float.NaN;assertNull(DnnRoiDecoder.yolo(values,5,1,0,.8,new DnnRoiDecoder.Letterbox(640,400,640)));
    }
}
