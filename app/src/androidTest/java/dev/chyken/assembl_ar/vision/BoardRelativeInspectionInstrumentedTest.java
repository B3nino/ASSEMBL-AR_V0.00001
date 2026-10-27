package dev.chyken.assembl_ar.vision;

import android.content.Context;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

public class BoardRelativeInspectionInstrumentedTest {
    @BeforeClass public static void runtime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    private Mat board()throws Exception{Method m=Phase12VideoReplayInstrumentedTest.class.getDeclaredMethod("raw",String.class,int.class);m.setAccessible(true);return (Mat)m.invoke(new Phase12VideoReplayInstrumentedTest(),"board",18);}
    @Test public void nativeCaptureOutlineCoordinatesFollowPanWithBoundedLkBuffers()throws Exception{
        Mat rgb=board(),gray=new Mat(),large=new Mat(),moved=new Mat(),shift=Mat.eye(2,3,CvType.CV_64F);
        BoundedOutlineTracker tracker=new BoundedOutlineTracker();
        try{
            Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
            // Upsampling is for coordinate invariance only, never recognition evidence.
            Imgproc.resize(gray,large,new Size(1280,800),0,0,Imgproc.INTER_LINEAR);
            List<VisionPoint> box=Arrays.asList(new VisionPoint(180,100),new VisionPoint(1100,100),new VisionPoint(1100,720),new VisionPoint(180,720));
            TargetBoundingTracker.Target first=tracker.detect(large,1_000_000_000L,"main_board","Board","Board",TargetBoundingTracker.State.LOCKED,Collections.singletonList(box),true);
            assertNotNull(first);assertTrue(first.verified);assertFalse(first.predicted);
            Field field=BoundedOutlineTracker.class.getDeclaredField("sampled");field.setAccessible(true);Mat buffer=(Mat)field.get(tracker);assertEquals(640,buffer.cols());assertEquals(400,buffer.rows());long address=buffer.dataAddr();
            shift.put(0,2,20);shift.put(1,2,12);Imgproc.warpAffine(large,moved,shift,large.size());
            TargetBoundingTracker.Target next=tracker.frame(moved,1_100_000_000L,null);assertNotNull(next);assertFalse(next.predicted);assertEquals(address,buffer.dataAddr());
            double dx=0,dy=0;for(int i=0;i<4;i++){dx+=(next.regions.get(0).get(i).getX()-first.regions.get(0).get(i).getX())/4;dy+=(next.regions.get(0).get(i).getY()-first.regions.get(0).get(i).getY())/4;}
            // Existing EMA applies alpha .25 to ordinary measured motion.
            assertTrue("Full-camera x displacement: "+dx,dx>3&&dx<23);assertTrue("Full-camera y displacement: "+dy,dy>1&&dy<15);
            long epoch=tracker.epoch();tracker.reset();assertTrue(buffer.empty());assertFalse(tracker.hasTarget());assertTrue(tracker.epoch()>epoch);
            tracker.close();assertNull(tracker.frame(large,1_200_000_000L,null));assertTrue(buffer.empty());
        }finally{tracker.close();rgb.release();gray.release();large.release();moved.release();shift.release();}
    }
    @Test public void pooledDetailRowsSurviveReuseAndPauseReleasesScratchPixels()throws Exception{
        Mat rgb=board(),gray=new Mat();ComponentDetailExtractor extractor=new ComponentDetailExtractor(VisionConfig.defaults());
        try{
            Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
            List<VisionPoint> box=Arrays.asList(new VisionPoint(60,40),new VisionPoint(560,40),new VisionPoint(560,350),new VisionPoint(60,350));
            try(ComponentDetailExtractor.Sample first=extractor.extract(gray,box,0)){
                assertNotNull(first);assertTrue(first.descriptors.rows()>15);Mat saved=first.descriptors.clone();
                try{Field field=ComponentDetailExtractor.class.getDeclaredField("work");field.setAccessible(true);Mat pixels=(Mat)field.get(extractor);long address=pixels.dataAddr();
                    for(int i=0;i<30;i++)try(ComponentDetailExtractor.Sample next=extractor.extract(gray,box,0)){assertNotNull(next);assertEquals(address,pixels.dataAddr());assertEquals(0,Core.norm(saved,first.descriptors,Core.NORM_INF),0);}
                    extractor.releaseWorkspace();assertTrue(pixels.empty());assertFalse(first.descriptors.empty());
                    try(ComponentDetailExtractor.Sample resumed=extractor.extract(gray,box,0)){assertNotNull(resumed);assertTrue(resumed.descriptors.rows()>15);}
                }finally{saved.release();}
            }
        }finally{extractor.close();rgb.release();gray.release();}
    }
    @Test public void installedPilotsKeepFreshBodyBoardAndInvalidOrientationGates()throws Exception{
        LiveLatencyInstrumentedTest fixture=new LiveLatencyInstrumentedTest();Method replay=LiveLatencyInstrumentedTest.class.getDeclaredMethod("replayTarget",int.class,int.class,Class.class,String.class,int.class,boolean.class,List.class);replay.setAccessible(true);List<String> failures=new ArrayList<>();
        replay.invoke(fixture,5,3,Phase35VideoReplayInstrumentedTest.class,"assembly",122,true,failures);
        replay.invoke(fixture,5,3,Phase35VideoReplayInstrumentedTest.class,"invalid",90,false,failures);
        replay.invoke(fixture,8,2,Phase789VideoReplayInstrumentedTest.class,"assembly",75,true,failures);
        replay.invoke(fixture,8,2,Phase789VideoReplayInstrumentedTest.class,"invalid",150,false,failures);
        assertTrue("Installed pilot regressions: "+failures,failures.isEmpty());
    }
    @Test public void completedStepKeepsMovingOutlineWithoutHeavyVerificationAndCanReacquire()throws Exception{
        Method c=Phase12VideoReplayInstrumentedTest.class.getDeclaredMethod("context");c.setAccessible(true);Context context=(Context)c.invoke(new Phase12VideoReplayInstrumentedTest());
        Method image=Phase12VideoReplayInstrumentedTest.class.getDeclaredMethod("image",Mat.class,long.class);image.setAccessible(true);Object fixture=new Phase12VideoReplayInstrumentedTest();
        java.util.concurrent.atomic.AtomicBoolean completed=new java.util.concurrent.atomic.AtomicBoolean();java.util.concurrent.atomic.AtomicInteger results=new java.util.concurrent.atomic.AtomicInteger(),outlines=new java.util.concurrent.atomic.AtomicInteger();
        List<ComponentReferenceSpec> refs=new ArrayList<>();for(ComponentReferenceSpec ref:dev.chyken.assembl_ar.camera.Phase12VideoReferences.all())if(ref.getComponentId().equals("main_board"))refs.add(ref);
        RecognitionRequest request=dev.chyken.assembl_ar.camera.IndividualPartTestCatalog.requestFor("main_board",CameraCalibration.pinhole(700,700,320,200)).withRequestTag("completed-test");
        Mat rgb=board(),blank=Mat.zeros(rgb.size(),rgb.type());
        try(ReferenceDatabase db=ReferenceDatabase.load(context.getAssets(),refs,VisionConfig.defaults(),new java.io.File(context.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(VisionConfig.defaults(),db);AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,new AssemblyVisionAnalyzer.RequestProvider(){
            public RecognitionRequest requestFor(AssemblyVisionAnalyzer.FrameMetadata f){return request;}
            public RecognitionRequest requestForTracking(AssemblyVisionAnalyzer.FrameMetadata f){return request;}
            public boolean isAnalysisSuspended(){return completed.get();}
            public boolean isTrackingSuspended(){return false;}
            public boolean requiresMetricTracking(){return false;}
        },new AssemblyVisionAnalyzer.Callback(){
            public void onResult(RecognitionResult r){results.incrementAndGet();if(r.hasLocatedComponent())completed.set(true);}
            public void onTargetTracking(TargetBoundingTracker.Target target,AssemblyVisionAnalyzer.FrameMetadata f,Object tag){if(target!=null&&!target.predicted)outlines.incrementAndGet();}
        },VisionConfig.defaults())){
            Field busy=AssemblyVisionAnalyzer.class.getDeclaredField("detectorBusy"),queued=AssemblyVisionAnalyzer.class.getDeclaredField("lastQueuedDetection");busy.setAccessible(true);queued.setAccessible(true);
            long now=android.os.SystemClock.elapsedRealtimeNanos();
            for(int i=0;i<3&&!completed.get();i++){analyzer.analyze((androidx.camera.core.ImageProxy)image.invoke(fixture,rgb,now+i*200_000_000L));awaitIdle(analyzer,busy);}
            assertTrue("A real board must seed the outline first",completed.get());long queuedAt=queued.getLong(analyzer);int initialOutlines=outlines.get(),initialResults=results.get();
            for(int i=1;i<=6;i++)analyzer.analyze((androidx.camera.core.ImageProxy)image.invoke(fixture,rgb,queuedAt+i*100_000_000L));
            assertEquals("Locked completion cannot queue repeated photo verification",queuedAt,queued.getLong(analyzer));assertTrue(outlines.get()>initialOutlines);assertEquals(initialResults,results.get());
            analyzer.analyze((androidx.camera.core.ImageProxy)image.invoke(fixture,blank,queuedAt+1_000_000_000L));
            analyzer.analyze((androidx.camera.core.ImageProxy)image.invoke(fixture,blank,queuedAt+1_200_000_000L));awaitIdle(analyzer,busy);
            assertTrue("Losing measured tracking must reopen visual localization",queued.getLong(analyzer)>queuedAt);assertEquals("Visual reacquisition cannot advance workflow again",initialResults,results.get());
        }finally{rgb.release();blank.release();}
    }
    private static void awaitIdle(AssemblyVisionAnalyzer analyzer,Field busy)throws Exception{
        long deadline=android.os.SystemClock.elapsedRealtime()+10000;
        while(((java.util.concurrent.atomic.AtomicBoolean)busy.get(analyzer)).get()&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(10);
        assertFalse("Detector failed to release its admission flag",((java.util.concurrent.atomic.AtomicBoolean)busy.get(analyzer)).get());
    }
    @Test public void pcbOnlyGlobalFeaturesProposeFreshNanoCropButCannotVerifyErasedBody()throws Exception{
        Method contextMethod=Phase12VideoReplayInstrumentedTest.class.getDeclaredMethod("context");contextMethod.setAccessible(true);Context context=(Context)contextMethod.invoke(new Phase12VideoReplayInstrumentedTest());
        ComponentReferenceSpec spec=null;for(ComponentReferenceSpec s:dev.chyken.assembl_ar.camera.Phase5AssetCatalog.references(context.getAssets()))if(s.getJpegAssetPath().contains("VIDEO35_")&&"assembly".equals(dev.chyken.assembl_ar.camera.Phase5AssetCatalog.referenceRole(s.getReferenceId()))){spec=s;break;}assertNotNull(spec);
        byte[] bytes;try(java.io.InputStream input=context.getAssets().open(spec.getJpegAssetPath())){bytes=input.readAllBytes();}
        MatOfByte encoded=new MatOfByte(bytes);Mat gray=org.opencv.imgcodecs.Imgcodecs.imdecode(encoded,org.opencv.imgcodecs.Imgcodecs.IMREAD_GRAYSCALE),erased=gray.clone();
        try(ReferenceDatabase db=ReferenceDatabase.load(context.getAssets(),Collections.singletonList(spec),VisionConfig.defaults(),new java.io.File(context.getNoBackupFilesDir(),"orb_reference_cache_v3"))){
            ReferenceDatabase.PreparedReference ref=db.preparedReferences().get(0);ReferenceDatabase.PreparedOrbVariant variant=ref.orbVariants().get(0);
            try(PhotoFeaturePartition source=new PhotoFeaturePartition(ref,variant)){
                assertTrue(source.context.keys.length>=16);
                // Keep only PCB descriptors that cannot also nominate a Nano-body
                // match. This deliberately creates the initial-detail starvation
                // scenario, without changing any verifier threshold or pixels.
                NearestDescriptorPairs cross=NearestDescriptorPairs.compute(source.context.rows,source.body.rows);
                List<Integer> indexes=new ArrayList<>();for(int i=0;i<cross.size();i++)if(cross.bestDistance(i)>OrbMatchPolicy.MAX_HAMMING)indexes.add(i);
                assertTrue("Need independent PCB-only seeds",indexes.size()>=16);
                KeyPoint[] globalKeys=new KeyPoint[indexes.size()];Mat globalRows=new Mat(indexes.size(),32,CvType.CV_8UC1);byte[] row=new byte[32];
                try{
                for(int i=0;i<indexes.size();i++){int index=indexes.get(i);globalKeys[i]=source.context.keys[index];source.context.rows.get(index,0,row);globalRows.put(i,0,row);}
                Method match=Phase5ReferenceMatcher.class.getDeclaredMethod("match",ReferenceDatabase.PreparedReference.class,ReferenceDatabase.PreparedOrbVariant.class,KeyPoint[].class,Mat.class,Mat.class,double.class,boolean.class);match.setAccessible(true);
                // Deliberately withhold all component features from the global pass.
                // This isolates routing, not held-out recognition accuracy. The crop
                // must independently recover identity from real source pixels.
                DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.snapshot().fastLive=true;
                try(Phase5ReferenceMatcher matcher=new Phase5ReferenceMatcher(db,VisionConfig.defaults())){
                    Object found=match.invoke(matcher,ref,variant,globalKeys,globalRows,gray,1.0,true);assertNotNull("PCB context should recover the small body crop",found);
                    assertEquals(Phase5Observation.State.PLACED,placement(found));
                    Field proposals=Phase5ReferenceMatcher.class.getDeclaredField("boardProposals");proposals.setAccessible(true);assertEquals(1,proposals.getInt(matcher));
                }
                MatOfPoint polygon=new MatOfPoint();try{List<VisionPoint> corners=spec.getPlanarCornersNormalized();Point[] vertices=new Point[corners.size()];for(int i=0;i<vertices.length;i++)vertices[i]=new Point(corners.get(i).getX()*gray.cols(),corners.get(i).getY()*gray.rows());polygon.fromArray(vertices);Imgproc.fillConvexPoly(erased,polygon,new Scalar(128));}finally{polygon.release();}
                DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.snapshot().fastLive=true;
                try(Phase5ReferenceMatcher matcher=new Phase5ReferenceMatcher(db,VisionConfig.defaults())){Object found=match.invoke(matcher,ref,variant,globalKeys,globalRows,erased,1.0,true);assertTrue("Unchanged PCB evidence cannot verify an erased component",found==null||placement(found)!=Phase5Observation.State.PLACED);}
                }finally{globalRows.release();}
            }
        }finally{DiagnosticHammingMatcher.end();encoded.release();gray.release();erased.release();}
    }
    @SuppressWarnings("unchecked") private static Phase5Observation.State placement(Object found)throws Exception{
        Field expected=found.getClass().getDeclaredField("expected"),actual=found.getClass().getDeclaredField("actual");expected.setAccessible(true);actual.setAccessible(true);
        return Phase5Observation.placement((List<VisionPoint>)expected.get(found),(List<VisionPoint>)actual.get(found));
    }
    @Test public void activeLockedFlowAtNativeSizeKeepsTimeAndMemoryBounded()throws Exception{
        Mat rgb=board(),gray=new Mat(),large=new Mat();Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);Imgproc.resize(gray,large,new Size(1280,800));
        try{
            double baseline=activeFlowBudget(gray),nativeDetail=activeFlowBudget(large);
            android.util.Log.i("InstalledCapture","RECORDED_ACTIVE_LK baselineP95Ms="+baseline+" nativeP95Ms="+nativeDetail);
            assertTrue("Native-detail capture must keep active LK within a frame budget: "+nativeDetail,nativeDetail<33.4);
        }finally{rgb.release();gray.release();large.release();}
    }
    private double activeFlowBudget(Mat gray){
        BoundedOutlineTracker tracker=new BoundedOutlineTracker();Mat shifted=new Mat(),warp=Mat.eye(2,3,CvType.CV_64F);List<Double> costs=new ArrayList<>();
        try{
            double scale=gray.cols()/640.0;warp.put(0,2,6*scale);warp.put(1,2,3*scale);Imgproc.warpAffine(gray,shifted,warp,gray.size());
            List<VisionPoint> box=Arrays.asList(new VisionPoint(70*scale,40*scale),new VisionPoint(570*scale,40*scale),new VisionPoint(570*scale,360*scale),new VisionPoint(70*scale,360*scale));
            assertNotNull(tracker.detect(gray,1_000_000_000L,"main_board","Timing fixture","Timing fixture",TargetBoundingTracker.State.LOCKED,Collections.singletonList(box),false));
            long allocated=0;
            for(int i=0;i<220;i++){
                long began=android.os.SystemClock.elapsedRealtimeNanos();
                TargetBoundingTracker.Target target=tracker.frame((i&1)==0?shifted:gray,1_050_000_000L+i*50_000_000L,null);
                double ms=(android.os.SystemClock.elapsedRealtimeNanos()-began)/1e6;
                assertNotNull("Real texture must remain tracked",target);assertFalse("Prediction is not active LK",target.predicted);
                if(i==19)allocated=android.os.Debug.getNativeHeapAllocatedSize();if(i>=20)costs.add(ms);
            }
            long growth=android.os.Debug.getNativeHeapAllocatedSize()-allocated;
            android.util.Log.i("InstalledCapture","ACTIVE_LK width="+gray.cols()+" nativeHeapGrowthBytes="+growth);
            assertTrue("Repeated active flow must not grow native owners: "+growth,growth<8L*1024*1024);
            Collections.sort(costs);return costs.get((int)Math.ceil(costs.size()*.95)-1);
        }finally{tracker.close();shifted.release();warp.release();}
    }
}
