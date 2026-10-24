package dev.chyken.assembl_ar.vision;

import android.content.Context;
import android.os.SystemClock;
import androidx.camera.core.ImageProxy;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import static org.junit.Assert.*;

/** Owns only synthetic test images; never launches a progress-resetting activity harness. */
public final class CameraPauseResumeInstrumentedTest {
    @BeforeClass public static void runtime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    private Object field(Object owner,String name)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private ImageProxy image(Mat rgb,long time)throws Exception {
        Method m=Class.forName("dev.chyken.assembl_ar.AssemblyReplayHarness",true,getClass().getClassLoader()).getDeclaredMethod("image",Mat.class,long.class);m.setAccessible(true);return (ImageProxy)m.invoke(null,rgb,time);
    }
    private void drained(AssemblyVisionAnalyzer analyzer)throws Exception {((ExecutorService)field(analyzer,"detectorExecutor")).submit(()->{}).get(20,TimeUnit.SECONDS);}
    @Test public void pauseDropsInflightEvidenceAndReleasesFramesWhileReferencesSurvive()throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Mat gray=new Mat(400,640,CvType.CV_8UC1,new Scalar(180)),rgb=new Mat();
        Mat patch=gray.submat(new Rect(100,80,230,180));Core.setRNGSeed(78);Core.randu(patch,10,245);Imgproc.GaussianBlur(patch,patch,new Size(3,3),.7);patch.release();Imgproc.cvtColor(gray,rgb,Imgproc.COLOR_GRAY2RGB);
        File file=File.createTempFile("lifecycle-board-",".png",context.getCacheDir());Imgcodecs.imwrite(file.getPath(),gray);
        ComponentReferenceSpec spec=ComponentReferenceSpec.builder("lifecycle-board","main_board","Main Board",115,90).localImageFilePath(file.getPath()).planarReferenceCornersNormalized(new VisionPoint(100./640,80./400),new VisionPoint(330./640,80./400),new VisionPoint(330./640,260./400),new VisionPoint(100./640,260./400)).build();
        VisionConfig config=VisionConfig.defaults();RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("main_board"),CameraCalibration.pinhole(700,700,320,200),null,null,1,1).withRequestTag("stable-session");
        AtomicInteger results=new AtomicInteger(),outlines=new AtomicInteger();AtomicBoolean hold=new AtomicBoolean();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(ReferenceDatabase database=ReferenceDatabase.load(context.getAssets(),Collections.singletonList(spec),config,null);ComponentRecognizer recognizer=new ComponentRecognizer(config,database);
            AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,new AssemblyVisionAnalyzer.RequestProvider(){public RecognitionRequest requestFor(AssemblyVisionAnalyzer.FrameMetadata f){if(hold.get()){entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}return request;}},new AssemblyVisionAnalyzer.Callback(){public void onResult(RecognitionResult r){assertTrue(r.hasLocatedComponent());results.incrementAndGet();}public void onTargetTracking(TargetBoundingTracker.Target t,AssemblyVisionAnalyzer.FrameMetadata f,Object tag){if(t!=null)outlines.incrementAndGet();}},config)) {
            analyzer.analyzeForReplay(image(rgb,SystemClock.elapsedRealtimeNanos()));assertEquals(1,results.get());assertTrue(outlines.get()>0);
            Object cache=field(recognizer,"frameCache");assertFalse(((Mat)field(cache,"source")).empty());
            hold.set(true);analyzer.analyze(image(rgb,SystemClock.elapsedRealtimeNanos()+1_000_000_000L));assertTrue(entered.await(10,TimeUnit.SECONDS));
            int before=results.get(),visual=outlines.get();analyzer.pause();analyzer.resume();release.countDown();drained(analyzer);hold.set(false);
            assertEquals("Pre-pause evidence cannot unlock the resumed step",before,results.get());assertEquals("Pre-pause outline cannot repaint the resumed screen",visual,outlines.get());
            assertEquals(1,database.size());assertTrue(((Mat)field(cache,"source")).empty());assertTrue(((Mat)field(cache,"descriptors")).empty());
            for(int i=0;i<12;i++) {
                analyzer.pause();drained(analyzer);assertTrue(((Mat)field(cache,"source")).empty());
                int n=results.get();analyzer.analyzeForReplay(image(rgb,SystemClock.elapsedRealtimeNanos()));assertEquals(n,results.get());
                analyzer.resume();drained(analyzer);analyzer.analyzeForReplay(image(rgb,SystemClock.elapsedRealtimeNanos()));assertEquals(n+1,results.get());assertEquals(1,database.size());
            }
        }finally{release.countDown();gray.release();rgb.release();file.delete();}
    }
    @Test public void opticalBridgePauseReleasesLatestImageAndCanResumeTracking()throws Exception {
        Mat gray=new Mat(120,160,CvType.CV_8UC1,new Scalar(90));
        try(OpticalFlowTrackerBridge bridge=new OpticalFlowTrackerBridge()) {
            for(int i=0;i<30;i++){bridge.trackLatest(gray,i+1,null);assertNotNull(field(bridge,"latestGray"));bridge.pause();assertNull(field(bridge,"latestGray"));assertFalse(bridge.hasAnchor());}
        }finally{gray.release();}
    }
}
