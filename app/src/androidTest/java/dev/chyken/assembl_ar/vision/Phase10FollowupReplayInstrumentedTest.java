package dev.chyken.assembl_ar.vision;

import android.content.Context;
import android.content.res.loader.*;
import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.chyken.assembl_ar.camera.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.*;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import static org.junit.Assert.*;

/** New room recordings, disjoint from published keyframes. No Activity, progress
 * store or camera session is opened. Uses the real production phase matchers. */
public final class Phase10FollowupReplayInstrumentedTest {
    @BeforeClass public static void runtime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    private Context context()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String path=InstrumentationRegistry.getArguments().getString("candidateDex");if(path==null)return app;
        Context c=app.createConfigurationContext(new android.content.res.Configuration(app.getResources().getConfiguration()));
        ResourcesLoader loader=new ResourcesLoader();try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(new File(path),ParcelFileDescriptor.MODE_READ_ONLY)){loader.addProvider(ResourcesProvider.loadFromApk(fd));}c.getResources().addLoaders(loader);return c;
    }
    private Mat raw(int phase,String role,int time)throws Exception{
        String name="heldout-p"+phase+"-"+role+"-"+time+".jpg";
        String archive=InstrumentationRegistry.getArguments().getString("candidateDex");
        Mat source;try(ZipFile zip=new ZipFile(archive);InputStream in=zip.getInputStream(zip.getEntry("assets/replay/phase1011/"+name))){
            MatOfByte encoded=new MatOfByte(in.readAllBytes());try{source=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_COLOR);}finally{encoded.release();}
        }
        assertFalse(name,source.empty());Mat image=new Mat(400,640,CvType.CV_8UC3,new Scalar(180,180,180)),scaled=new Mat();
        try{double scale=Math.min(640.0/source.cols(),400.0/source.rows());Imgproc.resize(source,scaled,new Size(),scale,scale,Imgproc.INTER_AREA);Mat roi=image.submat(new Rect((640-scaled.cols())/2,(400-scaled.rows())/2,scaled.cols(),scaled.rows()));try{scaled.copyTo(roi);}finally{roi.release();}Imgproc.cvtColor(image,image,Imgproc.COLOR_BGR2RGB);return image;}finally{source.release();scaled.release();}
    }
    @Test public void looseCableRequiresBothConnectorHeadsAndVisibleOutline()throws Exception{replay("roomcable",new int[]{18,27,40,52},1,true);}
    @Test public void protectorUnderGlareRequiresIndependentFrameAndBoardEvidence()throws Exception{replay("roomprotected",new int[]{12,20,26,35},4,true);}
    @Test public void stackedTopBoardRequiresLowerChassisEvidence()throws Exception{replay("roomstacked",new int[]{12,18,28,36},5,true);}
    @Test public void clippedCableCannotPassAsBothCompleteConnectors()throws Exception{replay("clippedcable",new int[]{34},1,false);}
    @Test public void boardOnlyCannotPassStackedAssembly()throws Exception{replay("roomprotected",new int[]{12,26},5,false);}
    @Test public void individualCableUsesTheCameraAnalyzerAndPublishesAnOutlineBeforeSuccess()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();Mat rgb=raw(10,"roomcable",40);List<ComponentReferenceSpec> refs=new ArrayList<>();
        for(ComponentReferenceSpec spec:Phase1011VideoReferences.individual())if("extension_data_cable".equals(spec.getComponentId()))refs.add(spec);
        java.util.concurrent.atomic.AtomicReference<RecognitionResult> latest=new java.util.concurrent.atomic.AtomicReference<>();java.util.concurrent.atomic.AtomicReference<TargetBoundingTracker.Target> outline=new java.util.concurrent.atomic.AtomicReference<>();List<String> order=new ArrayList<>();
        RecognitionRequest request=IndividualPartTestCatalog.requestFor("extension_data_cable",CameraCalibration.pinhole(700,700,320,200),.001).withRequestTag("p10-cable");
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(config,db);AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,frame->request,new AssemblyVisionAnalyzer.Callback(){
            public void onResult(RecognitionResult r){latest.set(r);order.add("result");}
            public void onTargetTracking(TargetBoundingTracker.Target t,AssemblyVisionAnalyzer.FrameMetadata frame,Object tag){if(t!=null){outline.set(t);order.add("outline");}}
        },config)){
            java.lang.reflect.Method image=Class.forName("dev.chyken.assembl_ar.AssemblyReplayHarness",true,getClass().getClassLoader()).getDeclaredMethod("image",Mat.class,long.class);image.setAccessible(true);long now=android.os.SystemClock.elapsedRealtimeNanos();
            for(int a=0;a<8&&outline.get()==null;a++)analyzer.analyzeForReplay((androidx.camera.core.ImageProxy)image.invoke(null,rgb,now+a*1_000_000_000L));
            assertNotNull("The standalone tool must draw the cable outline too",outline.get());assertTrue(order.indexOf("outline")<order.lastIndexOf("result"));assertEquals("extension_data_cable",latest.get().getComponentId());
        }finally{rgb.release();}
    }
    @Test public void assemblyCableCameraCarriesRgbAndCompletesTwoFreshFrames()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();Mat rgb=raw(10,"roomcable",52);List<ComponentReferenceSpec> refs=new ArrayList<>();
        for(ComponentReferenceSpec spec:Phase10AssetCatalog.references(c.getAssets()))if("cable".equals(Phase10AssetCatalog.referenceRole(spec.getReferenceId())))refs.add(spec);
        java.util.concurrent.atomic.AtomicReference<RecognitionResult> latest=new java.util.concurrent.atomic.AtomicReference<>();java.util.concurrent.atomic.AtomicReference<TargetBoundingTracker.Target> outline=new java.util.concurrent.atomic.AtomicReference<>();List<String> order=new ArrayList<>();
        RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("extension_data_cable"),CameraCalibration.pinhole(700,700,320,200),null,null,1,10).withPhase10Stage(1).withRequestTag("p10-cable-assembly");
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(config,db);AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,frame->request,new AssemblyVisionAnalyzer.Callback(){
            public void onResult(RecognitionResult r){latest.set(r);order.add("result");}
            public void onTargetTracking(TargetBoundingTracker.Target t,AssemblyVisionAnalyzer.FrameMetadata frame,Object tag){if(t!=null){outline.set(t);order.add("outline");}}
        },config)){
            java.lang.reflect.Method image=Class.forName("dev.chyken.assembl_ar.AssemblyReplayHarness",true,getClass().getClassLoader()).getDeclaredMethod("image",Mat.class,long.class);image.setAccessible(true);long now=android.os.SystemClock.elapsedRealtimeNanos();int streak=0;
            for(int a=0;a<10&&streak<2;a++){latest.set(null);analyzer.analyzeForReplay((androidx.camera.core.ImageProxy)image.invoke(null,rgb,now+a*1_000_000_000L));RecognitionResult result=latest.get();streak=result!=null&&result.getPhase10Observation()!=null&&result.getPhase10Observation().accepted()?streak+1:0;}
            assertEquals("The production YUV/RGB path must complete the real two-frame gate",2,streak);assertNotNull(outline.get());assertTrue(order.indexOf("outline")<order.lastIndexOf("result"));
        }finally{rgb.release();}
    }
    private static void movingOutline(Mat gray,RecognitionResult result){
        try(TargetBoundingTracker tracker=new TargetBoundingTracker()){
            assertNotNull(tracker.detect(gray,1,result.getComponentId(),result.getDisplayName(),result.getDisplayName(),TargetBoundingTracker.State.LOCKED,Collections.singletonList(result.getProjectedCorners()),true));
            Mat transform=Mat.eye(2,3,CvType.CV_64F),moved=new Mat();try{transform.put(0,2,8);transform.put(1,2,3);Imgproc.warpAffine(gray,moved,transform,gray.size());TargetBoundingTracker.Target target=tracker.frame(moved,50_000_001L,null);assertNotNull(target);assertFalse("Tracking must follow measured motion",target.predicted);}finally{transform.release();moved.release();}
        }
    }
    private void replay(String role,int[] times,int stage,boolean expected)throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();List<String> failed=new ArrayList<>();List<ComponentReferenceSpec> refs=new ArrayList<>();
        for(ComponentReferenceSpec s:Phase10AssetCatalog.references(c.getAssets()))if(Phase10AssetCatalog.eligible(Phase10AssetCatalog.referenceRole(s.getReferenceId()),stage))refs.add(s);
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase10ReferenceMatcher matcher=new Phase10ReferenceMatcher(db,config)){
            for(int time:times){Mat rgb=raw(10,role,time),gray=new Mat();boolean found=false;int streak=0;try{
                Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
                for(int a=0;a<10;a++){DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.recovery((a&1)==1);RecognitionResult result=matcher.inspect(gray,rgb,a+1,stage);boolean accepted=result.getPhase10Observation().accepted();
                    android.util.Log.i("Phase10Followup",role+" "+time+" attempt="+a+" accepted="+accepted+" score="+result.getPhase10Observation().score+" corners="+result.getProjectedCorners().size()+" ms="+result.getLatency().getTotalMs());
                    if(accepted){assertEquals("Every successful scan must publish its measured outline",4,result.getProjectedCorners().size());movingOutline(gray,result);if(++streak==2||!expected){found=true;break;}}else streak=0;
                    DiagnosticHammingMatcher.end();
                }if(found!=expected)failed.add(role+" "+time+" accepted="+found);
            }finally{rgb.release();gray.release();DiagnosticHammingMatcher.end();}}
        }assertTrue(failed.toString(),failed.isEmpty());
    }
}
