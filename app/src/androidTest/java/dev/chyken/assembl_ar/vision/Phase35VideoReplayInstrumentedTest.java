package dev.chyken.assembl_ar.vision;

import android.content.*;
import android.content.res.loader.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.chyken.assembl_ar.camera.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import static org.junit.Assert.*;

/** Disjoint raw timestamps; same-recording regression, not an independent accuracy study. */
public final class Phase35VideoReplayInstrumentedTest {
    @BeforeClass public static void runtime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    private Context context()throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String archive=InstrumentationRegistry.getArguments().getString("candidateDex");if(archive==null)return app;
        Context c=app.createConfigurationContext(new android.content.res.Configuration(app.getResources().getConfiguration()));
        ResourcesLoader loader=new ResourcesLoader();
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(new File(archive),ParcelFileDescriptor.MODE_READ_ONLY)){loader.addProvider(ResourcesProvider.loadFromApk(fd));}
        c.getResources().addLoaders(loader);return c;
    }
    private Mat raw(String part,String role,String time) {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file=new File(new File(c.getExternalFilesDir(null),"phase35-raw"),"heldout-"+part+"-"+role+"-"+time+".jpg");
        Mat source=Imgcodecs.imread(file.getPath()),frame=new Mat(400,640,CvType.CV_8UC3,new Scalar(180,180,180));
        assertFalse("Missing raw "+file,source.empty());
        try{assertEquals(640,source.cols());assertEquals(368,source.rows());Mat roi=frame.submat(new Rect(0,16,640,368));try{source.copyTo(roi);}finally{roi.release();}Imgproc.cvtColor(frame,frame,Imgproc.COLOR_BGR2RGB);return frame;}finally{source.release();}
    }
    @Test public void driverHeldOutFrontBackAndMountedOrientationsKeepOutlines()throws Exception {
        Context c=context();VisionConfig config=VisionConfig.defaults();
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),Phase3AssetCatalog.references(c.getAssets()),config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase3ReferenceMatcher matcher=new Phase3ReferenceMatcher(db,config)) {
            for(String role:new String[]{"front","back","assembly","invalid"}) {
                String[] times=role.equals("front")?new String[]{"7","12","34"}:role.equals("back")?new String[]{"23.5"}:role.equals("assembly")?new String[]{"58","68","80"}:new String[]{"6","12","20"};
                for(String time:times) {
                    Mat rgb=raw("driver",role,time),gray=new Mat();RecognitionResult accepted=null;
                    try{Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
                        for(int a=0;a<6;a++) {
                            DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.recovery((a&1)==1);long start=SystemClock.elapsedRealtime();
                            RecognitionResult r=matcher.inspect(gray,1,role.equals("front")||role.equals("back")?1:4);
                            Phase3Observation.State state=r.getPhase3Observation().state;
                            android.util.Log.i("Phase35Video","driver "+role+" "+time+" attempt="+a+" state="+state+" corners="+r.getProjectedCorners().size()+" ms="+(SystemClock.elapsedRealtime()-start));
                            if(role.equals("back")){assertNotEquals("Underside must not pass",Phase3Observation.State.FRONT,state);continue;}
                            if(role.equals("invalid")){assertNotEquals("Reversed driver must never pass",Phase3Observation.State.PLACED,state);if(state==Phase3Observation.State.BACKWARD||state==Phase3Observation.State.MISALIGNED){accepted=r;break;}}
                            else if(state==(role.equals("front")?Phase3Observation.State.FRONT:Phase3Observation.State.PLACED)){accepted=r;break;}
                        }
                        if(!role.equals("back")){assertNotNull("driver "+role+" "+time,accepted);movingOutline(gray,accepted);}
                    }finally{rgb.release();gray.release();DiagnosticHammingMatcher.end();}
                }
            }
        }
    }
    @Test public void nanoHeldOutFrontInstalledReversedAndMissingRequireBodyEvidence()throws Exception {
        Context c=context();VisionConfig config=VisionConfig.defaults();
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),Phase5AssetCatalog.references(c.getAssets()),config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase5ReferenceMatcher matcher=new Phase5ReferenceMatcher(db,config)) {
            for(String role:new String[]{"front","assembly","invalid","missing"}) {
                String[] times=role.equals("front")?new String[]{"8","20","37"}:role.equals("assembly")?new String[]{"108","122","139"}:role.equals("invalid")?new String[]{"90","97"}:new String[]{"54","72"};
                for(String time:times) {
                    Mat rgb=raw("nano",role,time),gray=new Mat();RecognitionResult accepted=null;
                    try{Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
                        for(int a=0;a<6;a++) {
                            DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.recovery((a&1)==1);long start=SystemClock.elapsedRealtime();
                            RecognitionResult r=matcher.inspect(gray,1,role.equals("front")?1:3);Phase5Observation.State state=r.getPhase5Observation().state;
                            android.util.Log.i("Phase35Video","nano "+role+" "+time+" attempt="+a+" state="+state+" corners="+r.getProjectedCorners().size()+" ms="+(SystemClock.elapsedRealtime()-start));
                            if(role.equals("missing")){assertNotEquals("Missing Nano must not pass",Phase5Observation.State.PLACED,state);continue;}
                            if(role.equals("front")&&time.equals("37")){assertNotEquals("Motion blur and deep shadow do not prove a flip",Phase5Observation.State.FLIPPED,state);continue;}
                            if(role.equals("invalid")){assertNotEquals("Reversed Nano must never pass",Phase5Observation.State.PLACED,state);if(state==Phase5Observation.State.BACKWARD||state==Phase5Observation.State.MISALIGNED){accepted=r;break;}}
                            else if(state==(role.equals("front")?Phase5Observation.State.FRONT:Phase5Observation.State.PLACED)){accepted=r;break;}
                        }
                        if(!role.equals("missing")&&!(role.equals("front")&&time.equals("37"))){assertNotNull("nano "+role+" "+time,accepted);movingOutline(gray,accepted);}
                    }finally{rgb.release();gray.release();DiagnosticHammingMatcher.end();}
                }
            }
        }
    }
    @Test public void isolatedCropsUseIndividualToolRequestsAndPublishOutlineBeforeResult()throws Exception {
        Context c=context();VisionConfig config=VisionConfig.defaults();CameraCalibration calibration=CameraCalibration.pinhole(700,700,320,200);
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),Phase35VideoReferences.individual(),config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(config,db)) {
            for(String part:new String[]{"driver","nano"}) {
                String id=part.equals("driver")?"dual_motor_driver":"arduino_nano_microcontroller";
                RecognitionRequest request=IndividualPartTestCatalog.requestFor(id,calibration,.003f).withRequestTag(part);
                List<String> order=new ArrayList<>();AtomicReference<RecognitionResult> latest=new AtomicReference<>();AtomicReference<TargetBoundingTracker.Target> outline=new AtomicReference<>();
                try(AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,f->request,new AssemblyVisionAnalyzer.Callback(){public void onResult(RecognitionResult r){order.add("result");latest.set(r);}public void onTargetTracking(TargetBoundingTracker.Target t,AssemblyVisionAnalyzer.FrameMetadata f,Object tag){if(t!=null){order.add("outline");outline.set(t);}}},config)) {
                    Mat rgb=raw(part,"front",part.equals("driver")?"7":"8"),gray=new Mat();
                    try{long now=SystemClock.elapsedRealtimeNanos();
                        for(int a=0;a<4;a++){java.lang.reflect.Method method=Class.forName("dev.chyken.assembl_ar.AssemblyReplayHarness",true,getClass().getClassLoader()).getDeclaredMethod("image",Mat.class,long.class);method.setAccessible(true);analyzer.analyzeForReplay((androidx.camera.core.ImageProxy)method.invoke(null,rgb,now+a*1_000_000_000L));if(latest.get()!=null&&latest.get().getValidationState()==RecognitionResult.ValidationState.EXPECTED_COMPONENT)break;}
                        assertNotNull(latest.get());assertEquals(id,latest.get().getComponentId());assertEquals(part,RecognitionResult.ValidationState.EXPECTED_COMPONENT,latest.get().getValidationState());assertNotNull("Individual mode must have a measured outline",outline.get());assertTrue("Outline callback precedes the successful result",order.indexOf("outline")<order.lastIndexOf("result"));
                        Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);movingOutline(gray,latest.get());
                    }finally{rgb.release();gray.release();}
                }
            }
        }
    }
    private void movingOutline(Mat gray,RecognitionResult result) {
        assertEquals("Recognition must include body geometry",4,result.getProjectedCorners().size());
        try(TargetBoundingTracker tracker=new TargetBoundingTracker()) {
            TargetBoundingTracker.State state=result.getValidationState()==RecognitionResult.ValidationState.WRONG_ORIENTATION?TargetBoundingTracker.State.WRONG:TargetBoundingTracker.State.LOCKED;
            assertNotNull(tracker.detect(gray,1,result.getComponentId(),result.getDisplayName(),result.getDisplayName(),state,Collections.singletonList(result.getProjectedCorners()),true));
            Mat transform=Mat.eye(2,3,CvType.CV_64F),moved=new Mat();
            try{transform.put(0,2,8);transform.put(1,2,3);Imgproc.warpAffine(gray,moved,transform,gray.size());TargetBoundingTracker.Target tracked=tracker.frame(moved,50_000_001L,null);assertNotNull(tracked);assertFalse("Outline follows measured LK, not a held screen rectangle",tracked.predicted);}finally{transform.release();moved.release();}
        }
    }
}
