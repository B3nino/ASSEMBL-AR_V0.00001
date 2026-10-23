package dev.chyken.assembl_ar.vision;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;import org.opencv.core.*;import org.opencv.imgproc.Imgproc;
import java.util.*;import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class TargetBoundingTrackerInstrumentedTest {
    @BeforeClass public static void nativeRuntime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    private List<VisionPoint> quad(){return Arrays.asList(new VisionPoint(100,80),new VisionPoint(330,80),new VisionPoint(330,260),new VisionPoint(100,260));}
    private Mat scene(){Mat m=new Mat(400,640,CvType.CV_8UC1,new Scalar(190)),roi=m.submat(new Rect(100,80,230,180));Core.setRNGSeed(1707);Core.randu(roi,10,240);Imgproc.GaussianBlur(roi,roi,new Size(3,3),.7);roi.release();return m;}
    private Mat moved(Mat input,double x,double y){Mat affine=Mat.eye(2,3,CvType.CV_64F),output=new Mat();try{affine.put(0,2,x);affine.put(1,2,y);Imgproc.warpAffine(input,output,affine,input.size(),Imgproc.INTER_LINEAR,Core.BORDER_CONSTANT,new Scalar(190));return output;}finally{affine.release();}}
    @Test public void measuredOutlineMovesAfterVerificationAndDelayedResultCannotSnapItBack(){
        Mat source=scene();try(TargetBoundingTracker tracker=new TargetBoundingTracker()){
            assertNotNull(tracker.detect(source,1,"main_board","Main Board","Main Board",TargetBoundingTracker.State.LOCKED,Collections.singletonList(quad()),false));
            for(int i=1;i<=35;i++){Mat frame=moved(source,2*i,i);try{
                TargetBoundingTracker.Target target=tracker.frame(frame,1+i*50_000_000L,null);assertNotNull("Camera pan "+i,target);assertFalse(target.predicted);
                assertEquals(100+2*i,target.regions.get(0).get(0).getX(),8);assertEquals(80+i,target.regions.get(0).get(0).getY(),8);
                if(i==20){TargetBoundingTracker.Target late=tracker.detect(source,1,"main_board","Main Board","Main Board",TargetBoundingTracker.State.LOCKED,Collections.singletonList(quad()),true);assertTrue(late.verified);assertTrue("Never restore old screen coordinates",late.regions.get(0).get(0).getX()>130);}
            }finally{frame.release();}}
        }finally{source.release();}
    }
    @Test public void wrongIdentityKeepsTrackingAndPredictionsExpireWithoutVerification(){
        Mat source=scene(),blank=new Mat(400,640,CvType.CV_8UC1,new Scalar(190));try(TargetBoundingTracker tracker=new TargetBoundingTracker()){
            assertNotNull(tracker.detect(source,1,"motor","Motor","Nano",TargetBoundingTracker.State.WRONG,Collections.singletonList(quad()),false));
            TargetBoundingTracker.Target predicted=tracker.frame(blank,1_000_000_001L,new OpticalFlowTrackerBridge.Motion(1_000_000_001L,40,0,0));
            assertNotNull(predicted);assertTrue(predicted.predicted);assertFalse(predicted.verified);assertEquals(TargetBoundingTracker.State.WRONG,predicted.state);
            assertTrue(predicted.regions.get(0).get(0).getX()>100);
            assertNull(tracker.frame(blank,1_600_000_001L,new OpticalFlowTrackerBridge.Motion(1_600_000_001L,40,0,0)));
        }finally{source.release();blank.release();}
    }
    @Test public void hullAndBodyBoundsDoNotGrowToSurroundingWires(){
        List<VisionPoint> hull=TargetBoundingTracker.clusterBounds(Arrays.asList(new VisionPoint(20,30),new VisionPoint(80,30),new VisionPoint(80,60),new VisionPoint(20,60),new VisionPoint(45,40)));
        assertEquals(4,hull.size());assertEquals(20,hull.get(0).getX(),.1);assertEquals(30,hull.get(0).getY(),.1);
        Mat gray=scene();try{for(int i=0;i<10;i++)Imgproc.line(gray,new Point(330,90+i*12),new Point(620,i*30),new Scalar(15),2);
            List<VisionPoint> body=TargetBoundingTracker.rigidBodyBounds(gray,quad());for(VisionPoint p:body){assertTrue(p.getX()>=95&&p.getX()<=335);assertTrue(p.getY()>=75&&p.getY()<=265);}
        }finally{gray.release();}
    }
    @Test public void uiEpochReadNeverWaitsForTheNativeTrackingMutex()throws Exception{
        try(TargetBoundingTracker tracker=new TargetBoundingTracker()){
            java.util.concurrent.CountDownLatch locked=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
            Thread worker=new Thread(()->{synchronized(tracker){locked.countDown();try{release.await(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}});worker.start();
            assertTrue(locked.await(1,java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.FutureTask<Long> read=new java.util.concurrent.FutureTask<>(tracker::epoch);Thread reader=new Thread(read);reader.start();
            try{assertEquals("The UI freshness check must stay independent of LK",0L,(long)read.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));}
            finally{release.countDown();worker.join(1000);reader.join(1000);}
        }
    }
}
