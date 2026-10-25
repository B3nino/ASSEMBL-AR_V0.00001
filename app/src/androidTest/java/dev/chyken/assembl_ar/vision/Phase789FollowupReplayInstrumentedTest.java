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
public final class Phase789FollowupReplayInstrumentedTest {
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
        Mat source;try(ZipFile zip=new ZipFile(archive);InputStream in=zip.getInputStream(zip.getEntry("assets/replay/phase789/"+name))){
            MatOfByte encoded=new MatOfByte(in.readAllBytes());try{source=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_COLOR);}finally{encoded.release();}
        }
        assertFalse(name,source.empty());Mat image=new Mat(400,640,CvType.CV_8UC3,new Scalar(180,180,180)),scaled=new Mat();
        try{double scale=Math.min(640.0/source.cols(),400.0/source.rows());Imgproc.resize(source,scaled,new Size(),scale,scale,Imgproc.INTER_AREA);Mat roi=image.submat(new Rect((640-scaled.cols())/2,(400-scaled.rows())/2,scaled.cols(),scaled.rows()));try{scaled.copyTo(roi);}finally{roi.release();}Imgproc.cvtColor(image,image,Imgproc.COLOR_BGR2RGB);return image;}finally{source.release();scaled.release();}
    }
    private List<ComponentReferenceSpec> bank(int phase){List<ComponentReferenceSpec> refs=new ArrayList<>();for(ComponentReferenceSpec s:Phase789VideoReferences.all())if(s.getAssemblyStepNumber()==phase)refs.add(s);return refs;}
    @Test public void shadowedBottomAssemblyRequiresBothTrackersAndCasterWithOutlines()throws Exception{replay(7,"shadowtrackers",new int[]{14,36,58,73},4,true);}
    @Test public void looseSensorOnPalmHasMeasuredOutlines()throws Exception{replay(8,"palm",new int[]{8,18,29,35},1,true);}
    @Test public void looseSensorOnPaperAndRotatedFrontHasMeasuredOutlines()throws Exception{replay(8,"paper",new int[]{13,28,40,61},1,true);}
    @Test public void clippedSensorCannotBecomeACompleteFrontFace()throws Exception{replay(8,"outofframe",new int[]{49},1,false);}
    @Test public void restoredLooseStandoffScanUsesFreshGeometricEvidence()throws Exception{replay(9,"standoff",new int[]{42},1,true);}
    @Test public void blurredStandoffDoesNotInventACompletePose()throws Exception{replay(9,"standoff",new int[]{15},1,false);}
    @Test public void standoffScanRejectsYellowWheelHubs()throws Exception{replay(9,"wheel",new int[]{70,90},1,false);}
    @Test public void phase8CameraAnalyzerPublishesTheOutlineBeforeConsecutiveSuccess()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();Mat rgb=raw(8,"palm",18);
        java.util.concurrent.atomic.AtomicReference<RecognitionResult> latest=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<TargetBoundingTracker.Target> outline=new java.util.concurrent.atomic.AtomicReference<>();List<String> order=new ArrayList<>();
        RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("ultrasonic_distance_sensor"),CameraCalibration.pinhole(700,700,320,200),null,null,1,8).withPhase8Stage(1).withRequestTag("p8-followup");
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),bank(8),config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(config,db);AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,f->request,new AssemblyVisionAnalyzer.Callback(){
            public void onResult(RecognitionResult r){order.add("result");latest.set(r);}
            public void onTargetTracking(TargetBoundingTracker.Target target,AssemblyVisionAnalyzer.FrameMetadata frame,Object tag){if(target!=null){order.add("outline");outline.set(target);}}
        },config)){
            // Invoke only the static image adapter. Constructing the Activity
            // replay harness would reset learner progress and is forbidden here.
            java.lang.reflect.Method image=Class.forName("dev.chyken.assembl_ar.AssemblyReplayHarness",true,getClass().getClassLoader()).getDeclaredMethod("image",Mat.class,long.class);image.setAccessible(true);
            long now=android.os.SystemClock.elapsedRealtimeNanos();int streak=0;
            for(int a=0;a<12&&streak<2;a++){latest.set(null);analyzer.analyzeForReplay((androidx.camera.core.ImageProxy)image.invoke(null,rgb,now+a*1_000_000_000L));RecognitionResult r=latest.get();streak=r!=null&&r.getPhase8Observation()!=null&&r.getPhase8Observation().accepted(1)?streak+1:0;}
            assertEquals("The real YUV/RGB analyzer path must reach two fresh successful frames",2,streak);assertNotNull("Even the quick loose scan must draw a measured outline",outline.get());assertTrue(order.indexOf("outline")<order.lastIndexOf("result"));
        }finally{rgb.release();}
    }
    private void replay(int phase,String role,int[] times,int stage,boolean expected)throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();List<String> failed=new ArrayList<>();
        List<ComponentReferenceSpec> refs=bank(phase);
        if(phase==9&&stage==1){refs=new ArrayList<>();for(ComponentReferenceSpec s:Phase9AssetCatalog.references(c.getAssets()))if("standoff".equals(Phase9AssetCatalog.referenceRole(s.getReferenceId())))refs.add(s);}
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase7ReferenceMatcher p7=new Phase7ReferenceMatcher(db,config);Phase8ReferenceMatcher p8=new Phase8ReferenceMatcher(db,config);Phase9ReferenceMatcher p9=new Phase9ReferenceMatcher(db,config)){
            for(int time:times){Mat rgb=raw(phase,role,time),gray=new Mat();boolean found=false;int consecutive=0;try{
                Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
                for(int a=0;a<12;a++){DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.recovery((a&1)==1);
                    RecognitionResult r=phase==7?p7.inspect(gray,rgb,a+1,stage):phase==8?p8.inspect(gray,rgb,a+1,stage):p9.inspect(gray,rgb,a+1,stage);
                    boolean accepted=phase==7?r.getPhase7Observation().accepted(stage):phase==8?r.getPhase8Observation().accepted(stage):r.getPhase9Observation().accepted(stage);
                    android.util.Log.i("Phase789Followup","P"+phase+" "+role+" "+time+" attempt="+a+" accepted="+accepted+" outlines="+r.getInstanceObservations().size()+" corners="+r.getProjectedCorners().size()+" ms="+r.getLatency().getTotalMs()+" state="+(phase==8?r.getPhase8Observation().state:phase==7?r.getPhase7Observation().visibleTrackers+" trackers":"standoff="+r.getPhase9Observation().standoff));
                    DiagnosticHammingMatcher.end();
                    if(accepted){assertTrue("A fast success must still publish an outline",r.getProjectedCorners().size()==4||!r.getInstanceObservations().isEmpty());if(++consecutive>=2||!expected){found=true;break;}}else consecutive=0;
                }
                if(found!=expected)failed.add(role+" "+time+" accepted="+found);
            }finally{rgb.release();gray.release();DiagnosticHammingMatcher.end();}}
        }assertTrue(failed.toString(),failed.isEmpty());
    }
}

