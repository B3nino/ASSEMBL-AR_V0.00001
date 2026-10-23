package dev.chyken.assembl_ar.vision;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.*;
import static org.junit.Assert.*;

/** Real native LK regression for photo matchers with no metric solvePnP pose. */
@RunWith(AndroidJUnit4.class)
public final class ImageOutlineTrackerInstrumentedTest {
    @BeforeClass public static void runtime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    private List<VisionPoint> corners(){return Arrays.asList(new VisionPoint(100,90),new VisionPoint(300,90),new VisionPoint(300,230),new VisionPoint(100,230));}
    private Mat scene(){Mat m=new Mat(400,640,CvType.CV_8UC1,new Scalar(90));Mat roi=m.submat(new Rect(100,90,200,140));Core.setRNGSeed(81);Core.randu(roi,20,230);Imgproc.GaussianBlur(roi,roi,new Size(3,3),.7);roi.release();return m;}
    private Mat moved(Mat m,double x,double y){Mat out=new Mat(),a=Mat.eye(2,3,CvType.CV_64F);try{a.put(0,2,x);a.put(1,2,y);Imgproc.warpAffine(m,out,a,m.size(),Imgproc.INTER_LINEAR,Core.BORDER_CONSTANT,new Scalar(90));return out;}finally{a.release();}}
    @Test public void photoOnlyOutlineFollowsMeasuredPanAndClearsWhenOccluded(){
        Mat start=scene(),blank=new Mat(400,640,CvType.CV_8UC1,new Scalar(90));
        try(ImageOutlineTracker tracker=new ImageOutlineTracker()){
            assertNotNull(tracker.detect(start,1,"nano","Nano",corners()));
            for(int i=1;i<=30;i++){Mat frame=moved(start,i*2,i*.5);try{
                ImageOutlineTracker.Outline o=tracker.frame(frame,1+i*40_000_000L);assertNotNull("Pan frame "+i,o);
                assertEquals(100+i*2,o.corners.get(0).getX(),2);assertEquals(90+i*.5,o.corners.get(0).getY(),2);
            }finally{frame.release();}}
            assertNull("Do not hold a screen-fixed success polygon",tracker.frame(blank,1_300_000_001L));
            assertFalse(tracker.hasOutline());
        }finally{start.release();blank.release();}
    }
    @Test public void delayedRecognitionCatchesUpToLatestCameraFrame(){
        Mat start=scene(),next=moved(start,16,5);
        try(ImageOutlineTracker tracker=new ImageOutlineTracker()){
            assertNull(tracker.frame(next,160_000_000L));
            ImageOutlineTracker.Outline o=tracker.detect(start,100_000_000L,"driver","Driver",corners());
            assertNotNull(o);assertEquals(116,o.corners.get(0).getX(),2);assertEquals(95,o.corners.get(0).getY(),2);
        }finally{start.release();next.release();}
    }
    @Test public void mountedPairKeepsTwoSeparateMovingOutlines(){
        Mat start=scene(),second=start.submat(new Rect(380,100,130,100));Core.randu(second,20,230);second.release();
        List<VisionPoint> other=Arrays.asList(new VisionPoint(380,100),new VisionPoint(510,100),new VisionPoint(510,200),new VisionPoint(380,200));
        try(ImageOutlineTracker tracker=new ImageOutlineTracker()){
            assertEquals(2,tracker.detectRegions(start,1,"trackers","Tracker",Arrays.asList(corners(),other)).regions.size());
            for(int i=1;i<=15;i++){Mat frame=moved(start,i*2,i);try{
                ImageOutlineTracker.Outline o=tracker.frame(frame,1+i*40_000_000L);assertNotNull(o);assertEquals(2,o.regions.size());
                assertEquals(100+i*2,o.regions.get(0).get(0).getX(),2);assertEquals(380+i*2,o.regions.get(1).get(0).getX(),2);
            }finally{frame.release();}}
        }finally{start.release();}
    }
    @Test public void multiSecondDetectorSeedIsRelocatedAgainstTheNewestMeasuredImage(){
        Mat start=scene(),next=moved(start,32,10),blank=new Mat(400,640,CvType.CV_8UC1,new Scalar(90));
        try(ImageOutlineTracker tracker=new ImageOutlineTracker()){
            assertNull(tracker.frame(next,5_000_000_000L));
            ImageOutlineTracker.Outline located=tracker.detect(start,100_000_000L,"main_board","Main Board",corners());
            assertNotNull("A slow detector must catch up to the current camera image",located);
            assertEquals(5_000_000_000L,located.timestamp);
            assertEquals(132,located.corners.get(0).getX(),2);
            assertEquals(100,located.corners.get(0).getY(),2);
            tracker.reset();assertNull(tracker.frame(blank,6_000_000_000L));
            assertNull("A delayed seed cannot be displayed without fresh geometric support",tracker.detect(start,100_000_000L,"main_board","Main Board",corners()));
        }finally{start.release();next.release();blank.release();}
    }
    @Test public void resetAndCloseReleaseFrameBuffersAcrossRepeatedScans()throws Exception{
        Mat start=scene(),next=moved(start,3,1);long before=0,after=0;
        java.lang.reflect.Field previous=ImageOutlineTracker.class.getDeclaredField("previous"),latest=ImageOutlineTracker.class.getDeclaredField("latest"),points=ImageOutlineTracker.class.getDeclaredField("points");
        previous.setAccessible(true);latest.setAccessible(true);points.setAccessible(true);
        try(ImageOutlineTracker tracker=new ImageOutlineTracker()){
            for(int i=0;i<220;i++){
                assertNotNull(tracker.detect(start,i*100_000_000L+1,"part","Part",corners()));
                assertNotNull(tracker.frame(next,i*100_000_000L+40_000_001L));
                Mat prior=(Mat)previous.get(tracker),current=(Mat)latest.get(tracker),features=(Mat)points.get(tracker);
                assertFalse(prior.empty());assertFalse(current.empty());assertFalse(features.empty());tracker.reset();
                assertTrue("Reset releases the previous image allocation",prior.empty());assertTrue("Reset releases the latest image allocation",current.empty());assertTrue("Reset releases tracked keypoint allocation",features.empty());
                assertNull(previous.get(tracker));assertNull(latest.get(tracker));assertNull(points.get(tracker));
                if(i==20)before=android.os.Debug.getNativeHeapAllocatedSize();
            }
            after=android.os.Debug.getNativeHeapAllocatedSize();
            assertNotNull(tracker.detect(start,23_000_000_000L,"part","Part",corners()));
            Mat finalImage=(Mat)previous.get(tracker),finalPoints=(Mat)points.get(tracker);tracker.close();assertTrue(finalImage.empty());assertTrue(finalPoints.empty());
            assertNull(previous.get(tracker));assertNull(latest.get(tracker));assertNull(points.get(tracker));
            // Application startup preloads native descriptors concurrently with this fixture.
            // A process-wide heap delta cannot attribute those allocations to this tracker.
            // Assert the owned native allocations above; retain the whole-process sample as
            // diagnostic information rather than misreporting it as tracker leakage.
            android.util.Log.i("OutlineRegression","220 scan/reset cycles: all owned frame/keypoint allocations released; concurrent-process native heap delta="+(after-before));
        }finally{start.release();next.release();}
    }
    @Test public void allocationLightRatioProbeMatchesNativeDMatchValues(){
        Mat query=new Mat(200,32,CvType.CV_8UC1),train=new Mat(240,32,CvType.CV_8UC1);Core.randu(query,0,256);Core.randu(train,0,256);
        Mat head=train.rowRange(0,150),source=query.rowRange(0,150);source.copyTo(head);head.release();source.release();
        DiagnosticHammingMatcher probe=new DiagnosticHammingMatcher();List<MatOfDMatch> pairs=new ArrayList<>();
        DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.context(ComponentReferenceSpec.builder("probe","part","Part",20,20).jpegAssetPath("references/probe.jpg").build());
        try{probe.knnMatch(query,train,pairs,2);int good=0,total=0;for(MatOfDMatch p:pairs){DMatch[] m=p.toArray();if(m.length==2){total++;if(m[0].distance<=64&&m[0].distance<.75*m[1].distance)good++;}}
            assertEquals(total,DiagnosticHammingMatcher.snapshot().total);assertEquals(good,DiagnosticHammingMatcher.snapshot().good);
            assertTrue(DiagnosticHammingMatcher.snapshot().matchingNs>0);
            List<DMatch> flat=probe.ratioMatches(query,train,.75,64);assertEquals(good,flat.size());
            assertEquals(total,DiagnosticHammingMatcher.snapshot().total);assertEquals(good,DiagnosticHammingMatcher.snapshot().good);
        }finally{for(Mat m:pairs)m.release();probe.clear();DiagnosticHammingMatcher.end();query.release();train.release();}
    }
}
