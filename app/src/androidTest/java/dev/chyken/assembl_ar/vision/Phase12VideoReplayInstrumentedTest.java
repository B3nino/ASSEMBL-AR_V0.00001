package dev.chyken.assembl_ar.vision;
import android.content.*;import android.content.res.loader.*;import android.os.*;import androidx.test.platform.app.InstrumentationRegistry;
import dev.chyken.assembl_ar.camera.*;
import java.io.*;import java.util.*;import java.util.concurrent.atomic.*;import org.junit.*;import org.opencv.android.OpenCVLoader;import org.opencv.core.*;import org.opencv.imgcodecs.Imgcodecs;import org.opencv.imgproc.Imgproc;import static org.junit.Assert.*;
/** Raw-video replay. Timestamps are disjoint from registered frames; not an independent-room accuracy study. */
public final class Phase12VideoReplayInstrumentedTest {
 @BeforeClass public static void runtime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
 private androidx.camera.core.ImageProxy image(Mat rgb,long time)throws Exception{java.lang.reflect.Method m=Class.forName("dev.chyken.assembl_ar.AssemblyReplayHarness",true,getClass().getClassLoader()).getDeclaredMethod("image",Mat.class,long.class);m.setAccessible(true);return (androidx.camera.core.ImageProxy)m.invoke(null,rgb,time);}
 private Context context()throws Exception{
  Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();String archive=InstrumentationRegistry.getArguments().getString("candidateDex");if(archive==null)return app;
  Context c=app.createConfigurationContext(new android.content.res.Configuration(app.getResources().getConfiguration()));ResourcesLoader l=new ResourcesLoader();try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(new File(archive),ParcelFileDescriptor.MODE_READ_ONLY)){l.addProvider(ResourcesProvider.loadFromApk(fd));}c.getResources().addLoaders(l);return c;
 }
 private Mat raw(String role,int time){
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File dir=new File(c.getExternalFilesDir(null),"phase12-raw");Mat source=Imgcodecs.imread(new File(dir,"heldout-"+role+"-"+time+".jpg").getPath()),scaled=new Mat(),frame=new Mat(400,640,CvType.CV_8UC3,new Scalar(180,180,180));assertFalse("Missing raw "+role+" "+time,source.empty());
  try{double scale=Math.min(640.0/source.cols(),400.0/source.rows());Imgproc.resize(source,scaled,new Size(),scale,scale,Imgproc.INTER_AREA);Mat roi=frame.submat(new Rect((640-scaled.cols())/2,(400-scaled.rows())/2,scaled.cols(),scaled.rows()));try{scaled.copyTo(roi);}finally{roi.release();}Imgproc.cvtColor(frame,frame,Imgproc.COLOR_BGR2RGB);return frame;}finally{source.release();scaled.release();}
 }
 @Test public void rawInstalledTopLeftRightRequireHardwareAndKeepOutlines()throws Exception{
  Context c=context();VisionConfig config=VisionConfig.defaults();List<ComponentReferenceSpec> refs=new ArrayList<>();for(ComponentReferenceSpec s:Phase2AssetCatalog.references(c.getAssets()))if(Phase2AssetCatalog.referenceRole(s.getReferenceId())!=null)refs.add(s);
  try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase2ReferenceMatcher matcher=new Phase2ReferenceMatcher(db,config)){
   String[] roles={"top","left","right"};int[][] times={{6,13,18},{23,28,33},{37,40,42}};
   for(int i=0;i<3;i++)for(int second:times[i]){Mat rgb=raw(roles[i],second),gray=new Mat();try{
    Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);RecognitionResult accepted=null;
    for(int attempt=0;attempt<8;attempt++){DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.recovery((attempt&1)==1);long t=SystemClock.elapsedRealtime();RecognitionResult r=matcher.inspect(gray,rgb,1,5,null,roles[i]);Phase2Observation o=r.getPhase2Observation();android.util.Log.i("Phase12Video",roles[i]+" second="+second+" attempt="+attempt+" view="+o.view+" accepted="+o.referenceSupported+" located="+r.getInstanceObservations().size()+" ms="+(SystemClock.elapsedRealtime()-t));if(o.referenceSupported&&roles[i].equals(o.view)){accepted=r;break;}}
    if(i==1&&second==33){assertNull("Motion-blurred, hand-occluded transition is not valid side-view evidence",accepted);continue;}
    assertNotNull("Raw "+roles[i]+" second="+second,accepted);assertEquals(i==0?2:1,accepted.getInstanceObservations().size());assertMovingOutline(gray,accepted);
   }finally{rgb.release();gray.release();DiagnosticHammingMatcher.end();}}
   for(int second:new int[]{7,18,29}){Mat rgb=raw("board",second),gray=new Mat();try{Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);for(int a=0;a<4;a++){DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.recovery((a&1)==1);Phase2Observation o=matcher.inspect(gray,rgb,1,5,null,"top").getPhase2Observation();assertFalse("Actual bare-board raw video must not pass Phase 2",o.referenceSupported&&"top".equals(o.view));}}finally{rgb.release();gray.release();DiagnosticHammingMatcher.end();}}
  }
 }
 private void assertMovingOutline(Mat gray,RecognitionResult r){
  List<List<VisionPoint>> regions=new ArrayList<>();for(RecognitionResult.InstanceObservation p:r.getInstanceObservations())regions.add(p.getProjectedCorners());if(regions.isEmpty())regions.add(r.getProjectedCorners());
  try(TargetBoundingTracker tracker=new TargetBoundingTracker()){
   assertNotNull("Successful recognition must publish measured geometry",tracker.detect(gray,1,r.getComponentId(),r.getDisplayName(),r.getDisplayName(),TargetBoundingTracker.State.LOCKED,regions,true));
   Mat affine=Mat.eye(2,3,CvType.CV_64F),moved=new Mat();try{affine.put(0,2,8);affine.put(1,2,3);Imgproc.warpAffine(gray,moved,affine,gray.size());TargetBoundingTracker.Target target=tracker.frame(moved,50_000_001L,null);assertNotNull(target);assertFalse("Outline needs fresh optical flow, not a screen hold",target.predicted);assertEquals(regions.size(),target.regions.size());}finally{affine.release();moved.release();}
  }
 }
 @Test public void reviewedBracketPhotosCannotCreateAMissingFourthPart()throws Exception{
  Context c=context();VisionConfig config=VisionConfig.defaults();List<ComponentReferenceSpec> refs=new ArrayList<>(Phase12VideoReferences.all());CameraCalibration calibration=CameraCalibration.pinhole(700,700,320,200);
  int[][] boxes={{172,146,376,406},{376,109,585,361},{571,62,773,317},{753,15,976,270}};
  try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(config,db)){
   for(int missing=0;missing<4;missing++){
    Mat rgb=raw("brackets",28);int[] b=boxes[missing];Imgproc.rectangle(rgb,new Point(b[0]*.625,b[1]*.625+20),new Point(b[2]*.625,b[3]*.625+20),new Scalar(180,180,180),-1);
    RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("motor_bracket"),calibration,null,null,4,2).withPhase2Stage(1).withRequestTag("missing-"+missing);AtomicReference<RecognitionResult> latest=new AtomicReference<>();
    try(AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,f->request,r->latest.set(r),config)){
     long now=SystemClock.elapsedRealtimeNanos();for(int attempt=0;attempt<3;attempt++){analyzer.analyzeForReplay(image(rgb,now+attempt*1000000000L));assertNotNull(latest.get());assertTrue("Missing physical bracket "+missing+" cannot complete the inventory",latest.get().getDetectedInstanceCount("motor_bracket")<4);}
    }finally{rgb.release();}
   }
  }
 }
 @Test public void rawBoardBracketsAndMotorsUseTheSharedCameraPipeline()throws Exception{
  Context c=context();VisionConfig config=VisionConfig.defaults();List<ComponentReferenceSpec> refs=new ArrayList<>(Phase12VideoReferences.all());CameraCalibration calibration=CameraCalibration.pinhole(700,700,320,200);
  try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(config,db)){
   String[] roles={"board","brackets","motors"},ids={"main_board","motor_bracket","gear_motor_6v"};int[][] times={{7,18,29},{8,21,28},{14,19,35}};
   for(int i=0;i<3;i++){
    RecognitionRequest base=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(ids[i]),calibration,null,null,i==1?4:i==2?2:1,i==0?1:2).withRequestTag(roles[i]);final RecognitionRequest request=i==0?base:base.withPhase2Stage(i);AtomicReference<RecognitionResult> latest=new AtomicReference<>();AtomicReference<TargetBoundingTracker.Target> outline=new AtomicReference<>();
    try(AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,new AssemblyVisionAnalyzer.RequestProvider(){public RecognitionRequest requestFor(AssemblyVisionAnalyzer.FrameMetadata f){return request;}},new AssemblyVisionAnalyzer.Callback(){public void onResult(RecognitionResult r){latest.set(r);}public void onTargetTracking(TargetBoundingTracker.Target t,AssemblyVisionAnalyzer.FrameMetadata f,Object tag){if(t!=null)outline.set(t);}},config)){
     for(int second:times[i]){Mat rgb=raw(roles[i],second);try{latest.set(null);outline.set(null);long time=SystemClock.elapsedRealtimeNanos();for(int attempt=0;attempt<4;attempt++){analyzer.analyzeForReplay(image(rgb,time+attempt*1000000000L));RecognitionResult trial=latest.get();if(trial!=null&&(i==0||trial.getDetectedInstanceCount(ids[i])>=request.getExpectedInstanceCount()||i==1&&second==21))break;}RecognitionResult r=latest.get();assertNotNull(r);android.util.Log.i("Phase12Video",roles[i]+" second="+second+" state="+r.getValidationState()+" id="+r.getComponentId()+" count="+r.getDetectedInstanceCount(ids[i])+" ms="+r.getLatency().getTotalMs());assertEquals(ids[i],r.getComponentId());assertEquals(RecognitionResult.ValidationState.EXPECTED_COMPONENT,r.getValidationState());if(i>0){if(i==1&&second==21)assertTrue("Blurred bracket frame may retain partial evidence",r.getDetectedInstanceCount(ids[i])>=2);else assertTrue("Simultaneous inventory count: "+roles[i]+" second="+second,r.getDetectedInstanceCount(ids[i])>=request.getExpectedInstanceCount());}else assertTrue(r.hasLocatedComponent());assertNotNull("A fast scan must have an outline",outline.get());}finally{rgb.release();}}
    }
   }
  }
 }
}
