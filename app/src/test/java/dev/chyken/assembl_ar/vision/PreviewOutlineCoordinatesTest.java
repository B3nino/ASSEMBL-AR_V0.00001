package dev.chyken.assembl_ar.vision;
import java.util.*;import org.junit.Test;import static org.junit.Assert.*;
public final class PreviewOutlineCoordinatesTest {
    @Test public void fillsPreviewAndAccountsForCenterCropWithoutClampingOffscreenTargets(){
        List<VisionPoint> corners=Arrays.asList(new VisionPoint(0,0),new VisionPoint(640,0),new VisionPoint(640,400),new VisionPoint(0,400));
        float[] p=PreviewOutlineCoordinates.normalize(corners,640,400,1000,600);assertEquals(0,p[0],.001);assertEquals(-.0208333,p[1],.001);assertEquals(1,p[2],.001);assertTrue(p[5]>1);
    }
    @Test public void centerStaysCenteredForPortraitOrLandscapeAnalysis(){
        List<VisionPoint> p=Arrays.asList(new VisionPoint(320,200),new VisionPoint(330,200),new VisionPoint(330,210),new VisionPoint(320,210));
        assertEquals(.5,PreviewOutlineCoordinates.normalize(p,640,400,400,700)[0],.001);assertEquals(.5,PreviewOutlineCoordinates.normalize(p,640,400,400,700)[1],.001);
    }
}
