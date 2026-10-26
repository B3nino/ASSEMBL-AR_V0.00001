package dev.chyken.assembl_ar.vision;

import android.content.Context;
import dev.chyken.assembl_ar.camera.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.Imgproc;
import static org.junit.Assert.*;

/** Hardware measurements use retained real camera pixels; never change learner progress. */
public class LiveLatencyInstrumentedTest {
    @BeforeClass public static void runtime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    private Context context()throws Exception{java.lang.reflect.Method m=Phase12VideoReplayInstrumentedTest.class.getDeclaredMethod("context");m.setAccessible(true);return (Context)m.invoke(new Phase12VideoReplayInstrumentedTest());}
    private Mat raw(int second)throws Exception{java.lang.reflect.Method m=Phase12VideoReplayInstrumentedTest.class.getDeclaredMethod("raw",String.class,int.class);m.setAccessible(true);return (Mat)m.invoke(new Phase12VideoReplayInstrumentedTest(),"board",second);}
    private androidx.camera.core.ImageProxy image(Mat rgb,long time)throws Exception{java.lang.reflect.Method m=Phase12VideoReplayInstrumentedTest.class.getDeclaredMethod("image",Mat.class,long.class);m.setAccessible(true);return (androidx.camera.core.ImageProxy)m.invoke(new Phase12VideoReplayInstrumentedTest(),rgb,time);}
    @Test public void pooled640AnalysisCaps300AndRestoresFullResolutionCoordinates()throws Exception{
        Mat rgb=raw(18),gray=new Mat(),large=new Mat(),low=new Mat(),high=new Mat(),a=new Mat(),b=new Mat(),empty=new Mat();MatOfKeyPoint ka=new MatOfKeyPoint(),kb=new MatOfKeyPoint();
        ORB orb=EdgeGradientDescriptorExtractor.createOrb();org.opencv.imgproc.CLAHE clahe=OpenCvVisionUtils.createClahe(VisionConfig.defaults());
        EdgeGradientDescriptorExtractor.Workspace w=new EdgeGradientDescriptorExtractor.Workspace();
        try{
            Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);Imgproc.resize(gray,large,new Size(gray.cols()*2,gray.rows()*2),0,0,Imgproc.INTER_NEAREST);
            w.beginFrame();w.normalize(gray,low,clahe,true,false);w.extract(low,empty,orb,ka,a);
            w.beginFrame();w.normalize(large,high,clahe,true,false);w.extract(high,empty,orb,kb,b);
            assertTrue(a.rows()<=300);assertTrue(b.rows()<=300);assertEquals(600,orb.getMaxFeatures());
            assertTrue(a.rows()>15);List<DMatch> good=new DiagnosticHammingMatcher().ratioMatches(a,b);assertTrue(good.size()>15);
            KeyPoint[] first=ka.toArray(),second=kb.toArray();for(DMatch match:good){assertEquals(first[match.queryIdx].pt.x*2,second[match.trainIdx].pt.x,.01);assertEquals(first[match.queryIdx].pt.y*2,second[match.trainIdx].pt.y,.01);}
            w.beginFrame();w.normalize(gray,low,clahe,true,false);w.extractScene(low,orb,ka,a);
            w.beginFrame();w.normalize(large,high,clahe,true,false);w.extractScene(high,orb,kb,b);
            assertTrue("Mounted-scene crops must preserve small-body coverage",a.rows()>300);assertTrue(a.rows()<=1200);assertTrue(b.rows()<=1200);assertEquals(600,orb.getMaxFeatures());
            good=new DiagnosticHammingMatcher().ratioMatches(a,b);assertTrue(good.size()>30);first=ka.toArray();second=kb.toArray();
            for(DMatch match:good){assertEquals(first[match.queryIdx].pt.x*2,second[match.trainIdx].pt.x,.01);assertEquals(first[match.queryIdx].pt.y*2,second[match.trainIdx].pt.y,.01);}
            java.lang.reflect.Field magnitude=w.getClass().getDeclaredField("magnitude");magnitude.setAccessible(true);Mat buffer=(Mat)magnitude.get(w);assertTrue(Math.max(buffer.cols(),buffer.rows())<=640);long address=buffer.dataAddr();
            for(int i=0;i<30;i++){w.beginFrame();w.normalize(large,high,clahe,true,false);w.extract(high,empty,orb,kb,b);assertEquals(address,buffer.dataAddr());assertTrue(b.rows()<=300);}
            w.close();for(java.lang.reflect.Field f:w.getClass().getDeclaredFields())if(Mat.class.isAssignableFrom(f.getType())){f.setAccessible(true);Mat m=(Mat)f.get(w);if(m!=null)assertTrue(f.getName(),m.empty());}
        }finally{w.close();orb.clear();clahe.clear();rgb.release();gray.release();large.release();low.release();high.release();a.release();b.release();empty.release();ka.release();kb.release();}
    }
    @Test public void mainBoardFirstFrameAndWarmTimingsAreMeasuredWithoutChangingGates()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();List<ComponentReferenceSpec> refs=new ArrayList<>();for(ComponentReferenceSpec s:Phase12VideoReferences.all())if(s.getComponentId().equals("main_board"))refs.add(s);
        CameraCalibration calibration=CameraCalibration.pinhole(700,700,320,200);RecognitionRequest r=IndividualPartTestCatalog.requestFor("main_board",calibration).withRequestTag("latency");
        AtomicReference<RecognitionResult> latest=new AtomicReference<>();List<Double> times=new ArrayList<>();int first=-1;
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,config,new java.io.File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(config,db);
                AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,f->r,new AssemblyVisionAnalyzer.Callback(){public void onResult(RecognitionResult result){latest.set(result);}},config)){
            Mat[] frames={raw(7),raw(18),raw(29)};try{long now=android.os.SystemClock.elapsedRealtimeNanos();
                for(int i=0;i<18;i++){
                    // CameraX already supplies YUV. Exclude this fixture's RGB-to-YUV conversion.
                    androidx.camera.core.ImageProxy input=image(frames[i%frames.length],now+i*200_000_000L);
                    long started=android.os.SystemClock.elapsedRealtimeNanos();analyzer.analyzeForReplay(input);double elapsed=(android.os.SystemClock.elapsedRealtimeNanos()-started)/1e6;
                    RecognitionResult result=latest.get();assertNotNull(result);if(first<0&&result.hasLocatedComponent())first=i+1;
                    if(i>=3)times.add(elapsed);
                    android.util.Log.i("LiveLatency","frame="+(i+1)+" located="+result.hasLocatedComponent()+" totalMs="+elapsed+" extractionMs="+result.getLatency().getFeatureExtractionMs()+" matchingMs="+result.getLatency().getMatchingMs()+" features="+result.getFrameKeypointCount()+" method="+result.getDetectionMethod());
                }
                assertTrue("First localization should require no failed-frame recovery gate: "+first,first>0&&first<=2);
                Collections.sort(times);double p50=times.get(times.size()/2),p95=times.get((int)Math.ceil(times.size()*.95)-1);
                android.util.Log.i("LiveLatency","SUMMARY firstFrame="+first+" medianMs="+p50+" p95Ms="+p95+" under100="+(p95<100));
            }finally{for(Mat rgb:frames)rgb.release();}
        }
    }
    @Test public void adaptivePassReplacesFastDescriptorOwnershipAndReleasesTheOldRows(){
        Mat fast=new Mat(5,32,CvType.CV_8UC1,new Scalar(1)),detail=new Mat(12,32,CvType.CV_8UC1,new Scalar(2));
        try{
            DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.State s=DiagnosticHammingMatcher.snapshot();s.fastLive=true;
            KeyPoint[] small=new KeyPoint[5],large=new KeyPoint[12];for(int i=0;i<large.length;i++)large[i]=new KeyPoint(i*3,i*4,7);System.arraycopy(large,0,small,0,5);
            DiagnosticHammingMatcher.captureLive(small,fast,1);assertEquals(5,s.uniqueLive);Mat old=s.live;
            s.fastLive=false;DiagnosticHammingMatcher.captureLive(large,detail,1);assertTrue(old.empty());assertEquals(12,s.uniqueLive);assertEquals(12,s.live.rows());
            DiagnosticHammingMatcher.end();assertNull(s.live);assertNull(s.liveKeys);assertNull(s.workspace);
        }finally{DiagnosticHammingMatcher.end();fast.release();detail.release();}
    }
    @Test public void scopedMountedTargetsKeepBodyContextRejectionAndOutlines()throws Exception{
        List<String> failed=new ArrayList<>();
        replayTarget(3,4,Phase35VideoReplayInstrumentedTest.class,"assembly",58,true,failed);
        replayTarget(3,4,Phase35VideoReplayInstrumentedTest.class,"invalid",12,false,failed);
        replayTarget(5,3,Phase35VideoReplayInstrumentedTest.class,"assembly",122,true,failed);
        replayTarget(5,3,Phase35VideoReplayInstrumentedTest.class,"invalid",90,false,failed);
        replayTarget(7,4,Phase789FollowupReplayInstrumentedTest.class,"shadowtrackers",14,true,failed);
        replayTarget(8,2,Phase789VideoReplayInstrumentedTest.class,"assembly",75,true,failed);
        replayTarget(8,2,Phase789VideoReplayInstrumentedTest.class,"invalid",150,false,failed);
        replayTarget(9,4,Phase789VideoReplayInstrumentedTest.class,"assembly",31,true,failed);
        replayTarget(10,4,Phase10FollowupReplayInstrumentedTest.class,"roomprotected",20,true,failed);
        replayTarget(10,5,Phase10FollowupReplayInstrumentedTest.class,"roomstacked",18,true,failed);
        replayTarget(11,3,Phase1011VideoReplayInstrumentedTest.class,"assembly",83,true,failed);
        replayTarget(11,3,Phase1011VideoReplayInstrumentedTest.class,"missingoled",159,false,failed);
        assertTrue("Live-path regressions: "+failed,failed.isEmpty());
    }
    private void replayTarget(int phase,int stage,Class<?> fixture,String role,int second,boolean expected,List<String> failed)throws Exception{
        Context c=context();if(phase==5)OpenCvDnnRoiLocator.INSTANCE.prepare(c.getAssets());
        List<ComponentReferenceSpec> catalog=phase==3?Phase3AssetCatalog.references(c.getAssets()):phase==5?Phase5AssetCatalog.references(c.getAssets()):phase==7?Phase7AssetCatalog.references(c.getAssets()):phase==8?Phase8AssetCatalog.references(c.getAssets()):phase==9?Phase9AssetCatalog.references(c.getAssets()):phase==10?Phase10AssetCatalog.references(c.getAssets()):Phase11AssetCatalog.references(c.getAssets());
        String id=phase==3?"dual_motor_driver":phase==5?"arduino_nano_microcontroller":phase==7?"main_board":phase==8?"ultrasonic_distance_sensor":phase==9?"main_board":phase==10?Phase10AssetCatalog.target(stage):Phase11AssetCatalog.target(stage);
        RecognitionRequest base=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id),CameraCalibration.pinhole(700,700,320,200),null,null,1,phase);
        RecognitionRequest r=(phase==3?base.withPhase3Stage(stage):phase==5?base.withPhase5Stage(stage):phase==7?base.withPhase7Stage(stage):phase==8?base.withPhase8Stage(stage):phase==9?base.withPhase9Stage(stage):phase==10?base.withPhase10Stage(stage):base.withPhase11Stage(stage)).withRequestTag("latency:"+phase+":"+role);
        java.lang.reflect.Method m=phase<7?fixture.getDeclaredMethod("raw",String.class,String.class,String.class):fixture.getDeclaredMethod("raw",int.class,String.class,int.class);m.setAccessible(true);
        Mat rgb=(Mat)(phase<7?m.invoke(fixture.getDeclaredConstructor().newInstance(),phase==3?"driver":"nano",role,Integer.toString(second)):m.invoke(fixture.getDeclaredConstructor().newInstance(),phase,role,second));
        AtomicReference<RecognitionResult> latest=new AtomicReference<>();java.util.concurrent.atomic.AtomicBoolean outlined=new java.util.concurrent.atomic.AtomicBoolean();boolean accepted=false;int streak=0;
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),ActiveReferenceScope.select(catalog,r),VisionConfig.defaults(),new java.io.File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(VisionConfig.defaults(),db);AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,f->r,new AssemblyVisionAnalyzer.Callback(){
            public void onResult(RecognitionResult result){latest.set(result);}
            public void onTargetTracking(TargetBoundingTracker.Target t,AssemblyVisionAnalyzer.FrameMetadata f,Object tag){if(t!=null)outlined.set(true);}
        },VisionConfig.defaults())){
            long now=android.os.SystemClock.elapsedRealtimeNanos();
            for(int i=0;i<6;i++){
                androidx.camera.core.ImageProxy input=image(rgb,now+i*1_000_000_000L);long start=android.os.SystemClock.elapsedRealtimeNanos();analyzer.analyzeForReplay(input);
                RecognitionResult result=latest.get();boolean pass=result!=null&&VerifiedScanEvidence.workflowAccepted(result,r);
                android.util.Log.i("LiveLatency","phase="+phase+" role="+role+" frame="+(i+1)+" verified="+pass+" ms="+(android.os.SystemClock.elapsedRealtimeNanos()-start)/1e6+" features="+(result==null?-1:result.getFrameKeypointCount())+" state="+(result==null?null:result.getValidationState())+" p5="+(result==null||result.getPhase5Observation()==null?null:result.getPhase5Observation().state));
                if(pass){accepted=true;if(++streak>=2)break;}else streak=0;
            }
            if(accepted!=expected||expected&&(streak<2||!outlined.get()))failed.add(phase+":"+role+" accepted="+accepted+" streak="+streak+" outline="+outlined.get());
        }finally{rgb.release();}
    }
}
