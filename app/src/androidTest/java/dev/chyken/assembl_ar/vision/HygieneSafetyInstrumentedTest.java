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

/** No Activity harness, preference mutations or assembly-progress resets. */
public final class HygieneSafetyInstrumentedTest {
    @BeforeClass public static void runtime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    private static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static void set(Object owner,String name,Object value)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    private static void drained(AssemblyVisionAnalyzer analyzer)throws Exception{((ExecutorService)field(analyzer,"detectorExecutor")).submit(()->{}).get(20,TimeUnit.SECONDS);}
    private static ImageProxy image(Mat rgb,long time)throws Exception{Method m=Class.forName("dev.chyken.assembl_ar.AssemblyReplayHarness",true,HygieneSafetyInstrumentedTest.class.getClassLoader()).getDeclaredMethod("image",Mat.class,long.class);m.setAccessible(true);return (ImageProxy)m.invoke(null,rgb,time);}
    private static ImageProxy watched(ImageProxy source,AtomicInteger closes,boolean failPlanes){
        return (ImageProxy)Proxy.newProxyInstance(ImageProxy.class.getClassLoader(),new Class<?>[]{ImageProxy.class},(p,m,a)->{
            if(m.getName().equals("close"))closes.incrementAndGet();
            if(failPlanes&&m.getName().equals("getPlanes"))throw new IllegalStateException("injected camera plane failure");
            try{return m.invoke(source,a);}catch(InvocationTargetException e){throw e.getCause();}
        });
    }
    private static final class Fixture implements AutoCloseable {
        final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Mat gray=new Mat(400,640,CvType.CV_8UC1,new Scalar(90)),rgb=new Mat();
        final File file;final ReferenceDatabase database;final ComponentRecognizer recognizer;
        final RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("main_board"),CameraCalibration.pinhole(700,700,320,200),null,null,1,1).withRequestTag("hygiene");
        Fixture()throws Exception{
            Mat patch=gray.submat(new Rect(100,80,230,180));try{Core.setRNGSeed(78);Core.randu(patch,10,245);Imgproc.GaussianBlur(patch,patch,new Size(3,3),.7);}finally{patch.release();}
            Imgproc.cvtColor(gray,rgb,Imgproc.COLOR_GRAY2RGB);file=File.createTempFile("hygiene-board-",".png",context.getCacheDir());Imgcodecs.imwrite(file.getPath(),gray);
            ComponentReferenceSpec spec=ComponentReferenceSpec.builder("hygiene-board","main_board","Board",115,90).localImageFilePath(file.getPath()).planarReferenceCornersNormalized(new VisionPoint(100./640,80./400),new VisionPoint(330./640,80./400),new VisionPoint(330./640,260./400),new VisionPoint(100./640,260./400)).build();
            database=ReferenceDatabase.load(context.getAssets(),Collections.singletonList(spec),VisionConfig.defaults(),null);recognizer=new ComponentRecognizer(VisionConfig.defaults(),database);
        }
        public void close(){recognizer.close();database.close();gray.release();rgb.release();file.delete();}
    }
    @Test public void providerAndPlaneFailuresCloseFramesAndReleaseAdmission()throws Exception{
        try(Fixture f=new Fixture()){
            AtomicBoolean failProvider=new AtomicBoolean(true);AtomicInteger errors=new AtomicInteger(),results=new AtomicInteger();
            try(AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(f.recognizer,new AssemblyVisionAnalyzer.RequestProvider(){
                public RecognitionRequest requestFor(AssemblyVisionAnalyzer.FrameMetadata frame){return f.request;}
                public boolean isAnalysisSuspended(){if(failProvider.getAndSet(false))throw new IllegalStateException("injected provider failure");return false;}
            },new AssemblyVisionAnalyzer.Callback(){public void onResult(RecognitionResult r){results.incrementAndGet();}public void onError(Throwable t){errors.incrementAndGet();}},VisionConfig.defaults())){
                long now=SystemClock.elapsedRealtimeNanos();AtomicInteger one=new AtomicInteger(),two=new AtomicInteger(),three=new AtomicInteger();
                analyzer.analyze(watched(image(f.rgb,now),one,false));assertEquals(1,one.get());assertEquals(1,errors.get());
                analyzer.analyze(watched(image(f.rgb,now+1_000_000_000L),two,true));assertEquals(1,two.get());assertFalse(((AtomicBoolean)field(analyzer,"detectorBusy")).get());assertEquals(2,errors.get());
                analyzer.analyze(watched(image(f.rgb,now+2_000_000_000L),three,false));drained(analyzer);assertEquals(1,three.get());assertEquals(1,results.get());
            }
        }
    }
    @Test public void pauseFromOutlineCallbackDropsTheRemainingTrackingCallback()throws Exception{
        try(Fixture f=new Fixture()){
            AtomicReference<AssemblyVisionAnalyzer> owner=new AtomicReference<>();AtomicBoolean pause=new AtomicBoolean();AtomicInteger tracking=new AtomicInteger(),pausedOutline=new AtomicInteger();
            try(AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(f.recognizer,frame->f.request,new AssemblyVisionAnalyzer.Callback(){
                public void onResult(RecognitionResult r){}
                public void onTargetTracking(TargetBoundingTracker.Target t,AssemblyVisionAnalyzer.FrameMetadata frame,Object tag){if(pause.get()){pausedOutline.incrementAndGet();owner.get().pause();}}
                public void onTracking(RecognitionResult.SpatialAnchor a,AssemblyVisionAnalyzer.FrameMetadata frame,CameraCalibration c,boolean predicted,Object tag){tracking.incrementAndGet();}
            },VisionConfig.defaults())){
                owner.set(analyzer);long now=SystemClock.elapsedRealtimeNanos();analyzer.analyzeForReplay(image(f.rgb,now));int before=tracking.get();set(analyzer,"lastQueuedDetection",now);pause.set(true);
                analyzer.analyze(image(f.rgb,now+40_000_000L));drained(analyzer);assertEquals(1,pausedOutline.get());assertEquals(before,tracking.get());
            }
        }
    }
    @Test public void closeQueuesNativeCleanupAfterTheInflightReader()throws Exception{
        closeDuringFrame(false);
    }
    @Test public void directReplayAndCloseShareTheSameNativeOwnershipGuard()throws Exception{
        closeDuringFrame(true);
    }
    private void closeDuringFrame(boolean directReplay)throws Exception{
        try(Fixture f=new Fixture()){
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger results=new AtomicInteger();
            AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(f.recognizer,frame->{entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}return f.request;},new AssemblyVisionAnalyzer.Callback(){public void onResult(RecognitionResult r){results.incrementAndGet();}},VisionConfig.defaults());
            ExecutorService closer=Executors.newSingleThreadExecutor(),replay=Executors.newSingleThreadExecutor();Future<?> replayed=null;
            try{if(directReplay)replayed=replay.submit(()->{try{analyzer.analyzeForReplay(image(f.rgb,SystemClock.elapsedRealtimeNanos()));}catch(Exception e){throw new AssertionError(e);}});else analyzer.analyze(image(f.rgb,SystemClock.elapsedRealtimeNanos()));
                assertTrue(entered.await(10,TimeUnit.SECONDS));closer.submit(analyzer::close).get(1,TimeUnit.SECONDS);assertTrue(analyzer.isClosed());assertFalse("Native owners survive until the active reader exits",(Boolean)field(field(analyzer,"targetBounds"),"closed"));release.countDown();if(replayed!=null)replayed.get(20,TimeUnit.SECONDS);
                ExecutorService detector=(ExecutorService)field(analyzer,"detectorExecutor");assertTrue(detector.awaitTermination(20,TimeUnit.SECONDS));assertEquals(0,results.get());assertTrue((Boolean)field(field(analyzer,"targetBounds"),"closed"));assertNull(field(analyzer,"globalOrb"));assertNull(field(analyzer,"diagnosticGray"));assertFalse(((AtomicBoolean)field(analyzer,"processing")).get());
            }finally{release.countDown();analyzer.close();closer.shutdown();replay.shutdown();}
        }
    }
    @Test public void rapidPauseResumeKeepsOnlyCurrentEpochEvidence()throws Exception{
        try(Fixture f=new Fixture()){
            AtomicInteger results=new AtomicInteger();try(AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(f.recognizer,frame->f.request,new AssemblyVisionAnalyzer.Callback(){public void onResult(RecognitionResult r){results.incrementAndGet();}},VisionConfig.defaults())){
                long now=SystemClock.elapsedRealtimeNanos();analyzer.analyzeForReplay(image(f.rgb,now));assertEquals(1,results.get());
                CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService worker=(ExecutorService)field(analyzer,"detectorExecutor");
                worker.submit(()->{entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(entered.await(10,TimeUnit.SECONDS));
                try{for(int i=0;i<40;i++){analyzer.pause();analyzer.resume();}}finally{release.countDown();}drained(analyzer);
                Object cache=field(f.recognizer,"frameCache");assertTrue(((Mat)field(cache,"source")).empty());assertNull(field(analyzer,"trackingScope"));analyzer.analyzeForReplay(image(f.rgb,now+2_000_000_000L));assertEquals(2,results.get());assertEquals(1,f.database.size());
            }
        }
    }
    @Test public void threeThousandFlowFramesReuseBuffersAndResetReleasesEveryOwner()throws Exception{
        try(Fixture f=new Fixture();ImageOutlineTracker tracker=new ImageOutlineTracker();OpticalFlowTrackerBridge bridge=new OpticalFlowTrackerBridge()){
            List<VisionPoint> quad=Arrays.asList(new VisionPoint(100,80),new VisionPoint(330,80),new VisionPoint(330,260),new VisionPoint(100,260));assertNotNull(tracker.detect(f.gray,1,"main_board","Board",quad));assertNotNull(tracker.frame(f.gray,40_000_001L));
            Object scratch=field(tracker,"workspace"),packed=field(scratch,"old");Mat previous=(Mat)field(tracker,"previous"),latest=(Mat)field(tracker,"latest");long previousData=previous.dataAddr(),latestData=latest.dataAddr();
            bridge.trackLatest(f.gray,1,null);Mat bridgeImage=(Mat)field(bridge,"latestGray");long bridgeData=bridgeImage.dataAddr(),started=SystemClock.elapsedRealtimeNanos();
            for(int i=2;i<=3000;i++){assertNotNull("LK frame "+i,tracker.frame(f.gray,i*40_000_000L+1));bridge.trackLatest(f.gray,i*40_000_000L+1,null);assertSame(scratch,field(tracker,"workspace"));assertSame(packed,field(scratch,"old"));assertEquals(previousData,previous.dataAddr());assertEquals(latestData,latest.dataAddr());assertEquals(bridgeData,bridgeImage.dataAddr());}
            tracker.reset();bridge.pause();assertTrue(previous.empty());assertTrue(latest.empty());assertTrue(bridgeImage.empty());assertNull(field(tracker,"workspace"));
            for(Field member:scratch.getClass().getDeclaredFields())if(Mat.class.isAssignableFrom(member.getType())){member.setAccessible(true);assertTrue(member.getName(),((Mat)member.get(scratch)).empty());}
            android.util.Log.i("HygieneAudit","3000 LK/bridge frames: stable native image owners and packed arrays; every owned Mat released on reset; elapsed_ms="+(SystemClock.elapsedRealtimeNanos()-started)/1e6);
        }
    }
    @Test public void rejectedCaptureWritesDoNotLeakAdmissionCounters()throws Exception{
        MeasurementRecorder.install(InstrumentationRegistry.getInstrumentation().getTargetContext());MeasurementRecorder recorder=new MeasurementRecorder();Mat rgb=new Mat(32,32,CvType.CV_8UC3,new Scalar(90,80,70));
        try{((ExecutorService)field(recorder,"writer")).shutdown();for(int i=0;i<100;i++){assertNull(recorder.capture(rgb,i+1));assertEquals(0,((AtomicInteger)field(recorder,"pendingWrites")).get());}recorder.close();assertNull(recorder.capture(rgb,101));assertFalse(recorder.captureDue(102));}finally{recorder.close();rgb.release();}
    }
    @Test public void cyclicContourHierarchyTerminatesWithoutInventingRepeatedHoles()throws Exception{
        List<MatOfPoint> contours=Arrays.asList(new MatOfPoint(),new MatOfPoint(),new MatOfPoint());Mat hierarchy=new Mat(1,3,CvType.CV_32SC4);
        try{hierarchy.put(0,0,new int[]{-1,-1,1,-1,2,-1,-1,0,1,1,-1,0});Method children=MotorBracketShapeRecognizer.class.getDeclaredMethod("children",List.class,Mat.class,int.class);children.setAccessible(true);
            assertTrue(((List<?>)children.invoke(null,contours,hierarchy,0)).isEmpty());hierarchy.put(0,2,new int[]{-1,1,-1,0});assertEquals(2,((List<?>)children.invoke(null,contours,hierarchy,0)).size());
        }finally{hierarchy.release();for(Mat contour:contours)contour.release();}
    }
    @Test public void failedDescriptorPublicationPreservesBorrowedInputsAndSuccessfulOutputOwnsItsRows(){
        Mat first=new Mat(1,32,CvType.CV_8UC1,new Scalar(11)),second=new Mat(1,32,CvType.CV_8UC1,new Scalar(22));
        List<AdminDescriptorBlobCodec.DescriptorVariant> output=new ArrayList<>();List<KeyPoint> keys=Arrays.asList(new KeyPoint(1,2,3),new KeyPoint(4,5,3));
        try{
            for(int i=0;i<100;i++){
                try{AdminDescriptorBlobCodec.concatenateInto(Collections.emptyList(),ReferenceDatabase.PhotometricVariant.NORMALIZED,keys,Arrays.asList(first,second));fail("Immutable output must reject publication");}
                catch(UnsupportedOperationException expected){}
                assertFalse(first.empty());assertFalse(second.empty());
            }
            AdminDescriptorBlobCodec.concatenateInto(output,ReferenceDatabase.PhotometricVariant.NORMALIZED,keys,Arrays.asList(first,second));
            assertEquals(1,output.size());Mat owned=output.get(0).descriptors;assertEquals(2,owned.rows());assertEquals(11,owned.get(0,0)[0],0);assertEquals(22,owned.get(1,0)[0],0);
            first.setTo(new Scalar(33));assertEquals("Published rows do not alias borrowed input",11,owned.get(0,0)[0],0);
            output.get(0).release();assertTrue(owned.empty());assertFalse(first.empty());assertFalse(second.empty());
        }finally{for(AdminDescriptorBlobCodec.DescriptorVariant v:output)v.release();first.release();second.release();}
    }
}
