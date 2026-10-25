package dev.chyken.assembl_ar.vision;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.chyken.assembl_ar.diagnostics.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import static org.junit.Assert.*;

/** Mathematical fixtures only; no generated pixels are registered in the app's photo library. */
public final class DiagnosticTelemetryInstrumentedTest {
 @Test public void checkerboardTreIsThreeDimensionalAndUnavailableWithoutMetricIntrinsics(){
  assertTrue(OpenCVLoader.initLocal());Mat gray=new Mat(400,640,CvType.CV_8UC1,new Scalar(180));
  try{for(int y=0;y<6;y++)for(int x=0;x<8;x++)Imgproc.rectangle(gray,new Point(80+x*20,60+y*20),new Point(99+x*20,79+y*20),new Scalar((x+y)%2==0?0:255),-1);
   CameraCalibration c=CameraCalibration.pinhole(700,700,320,200).withSource(CameraCalibration.Source.CAMERA2_INTRINSICS);float[] gl={1,0,0,0,0,-1,0,0,0,0,-1,0,0,0,0,1};
   List<VisionPoint> outline=Arrays.asList(new VisionPoint(100,80),new VisionPoint(200,80),new VisionPoint(200,160),new VisionPoint(100,160));
   CheckerboardGroundTruth gt=new CheckerboardGroundTruth(7,5,10,20,10,RecognitionResult.OrientationClassification.UPRIGHT);
   RecognitionResult.Pose exact=new RecognitionResult.Pose(new double[]{Math.PI,0,0},new double[]{-90,-50,350},gl,.1);
   CheckerboardGroundTruth.Measurement zero=gt.measure(gray,outline,exact,c);assertNotNull(zero);assertEquals(0,zero.errorMm,.75);
   RecognitionResult.Pose shifted=new RecognitionResult.Pose(new double[]{Math.PI,0,0},new double[]{-78,-50,350},gl,.1);
   CheckerboardGroundTruth.Measurement moved=gt.measure(gray,outline,shifted,c);assertNotNull(moved);assertEquals(12,moved.errorMm,.75);assertTrue(moved.errorPx>20);assertTrue(Double.isNaN(gt.measure(gray,outline,shifted,c.withSource(CameraCalibration.Source.APPROXIMATE_MISSING_METADATA)).errorMm));
  }finally{gray.release();}
 }
 @Test public void bothHudStatesKeepAllFifteenLinesAndColorOnlyTheFinalOutput()throws Exception{
  Context source=InstrumentationRegistry.getInstrumentation().getTargetContext();Context c=source.createConfigurationContext(new android.content.res.Configuration(source.getResources().getConfiguration()));
  String path=InstrumentationRegistry.getArguments().getString("candidateDex");if(path!=null){android.content.res.loader.ResourcesLoader loader=new android.content.res.loader.ResourcesLoader();try(android.os.ParcelFileDescriptor fd=android.os.ParcelFileDescriptor.open(new File(path),android.os.ParcelFileDescriptor.MODE_READ_ONLY)){loader.addProvider(android.content.res.loader.ResourcesProvider.loadFromApk(fd));}c.getResources().addLoaders(loader);}
  android.view.ContextThemeWrapper themed=new android.view.ContextThemeWrapper(c,dev.chyken.assembl_ar.R.style.Theme_ASSEMBLAR);
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
   dev.chyken.assembl_ar.ui.DevDiagnosticsOverlayView hud=new dev.chyken.assembl_ar.ui.DevDiagnosticsOverlayView(themed,null);
   for(boolean match:new boolean[]{false,true}){
    DiagnosticScanTelemetry t=new DiagnosticScanTelemetry("Nano","nano",100,100,match?90:10,100,3,8,true,true,true,"Forward",Double.NaN,new DiagnosticScanTelemetry.Point(1,2),new DiagnosticScanTelemetry.Point(2,4),.2,match?2:1);hud.showTelemetry(t);
    assertEquals(15,hud.getText().toString().split("\n").length);assertTrue(hud.getMaxLines()>=15);assertNull(hud.getEllipsize());
    android.text.Spanned text=(android.text.Spanned)hud.getText();android.text.style.ForegroundColorSpan[] spans=text.getSpans(0,text.length(),android.text.style.ForegroundColorSpan.class);assertEquals(1,spans.length);assertEquals(match?0xFF00FF7F:0xFFFF3B30,spans[0].getForegroundColor());assertEquals(text.toString().lastIndexOf("Output: "),text.getSpanStart(spans[0]));
   }
  });
 }
 @Test public void sharedAnalyzerPublishesOneVerifiedCandidateAndTruePoseResiduals()throws Exception{
  assertTrue(OpenCVLoader.initLocal());Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  Mat scene=new Mat(400,640,CvType.CV_8UC1,new Scalar(180)),body=scene.submat(new Rect(100,80,230,180));Core.setRNGSeed(78);Core.randu(body,10,245);Imgproc.GaussianBlur(body,body,new Size(3,3),.7);body.release();
  File image=File.createTempFile("telemetry-fixture-",".png",c.getCacheDir());Imgcodecs.imwrite(image.getPath(),scene);
  ComponentReferenceSpec spec=ComponentReferenceSpec.builder("telemetry-board","main_board","Verified Board",115,90).localImageFilePath(image.getPath()).planarReferenceCornersNormalized(new VisionPoint(100./640,80./400),new VisionPoint(330./640,80./400),new VisionPoint(330./640,260./400),new VisionPoint(100./640,260./400)).build();
  CameraCalibration calibration=CameraCalibration.pinhole(700,700,320,200);RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("main_board"),calibration,null,null,1,1);
  AtomicReference<DiagnosticScanTelemetry> published=new AtomicReference<>();
  try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),Collections.singletonList(spec),VisionConfig.defaults(),null);ComponentRecognizer recognizer=new ComponentRecognizer(VisionConfig.defaults(),db);
      AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,f->request,new AssemblyVisionAnalyzer.Callback(){public void onResult(RecognitionResult r){assertEquals(RecognitionResult.ValidationState.EXPECTED_COMPONENT,r.getValidationState());}public void onDiagnosticSample(DiagnosticSample s){published.set(s.telemetry);}},VisionConfig.defaults())){
   Mat rgb=new Mat();Imgproc.cvtColor(scene,rgb,Imgproc.COLOR_GRAY2RGB);try{
    long time=android.os.SystemClock.elapsedRealtimeNanos();analyzer.analyzeForReplay(image(rgb,time));DiagnosticScanTelemetry t=published.get();assertNotNull(t);assertTrue("A selected RANSAC fit must own the telemetry",t.candidateVerified);assertEquals(spec.getReferenceId(),t.referenceId);assertEquals("Verified Board",t.componentName);assertTrue(t.goodMatches>=8);assertTrue(t.goodMatches<=t.totalMatches);assertTrue(t.liveKeypoints>=t.goodMatches);assertEquals(KeypointInventoryManager.INSTANCE.count(spec.getReferenceId()),t.activeKeypoints);
    assertTrue(t.poseMeasured);assertTrue(t.isCorrectPose);assertEquals("Forward",t.orientationLabel);assertTrue("True solve residual",t.reprojectionRmsPx<1);assertEquals(215,t.projectedPoint.x,2);assertEquals(170,t.projectedPoint.y,2);assertTrue(Float.isNaN(t.treMm));assertEquals(Math.hypot(t.projectedPoint.x-t.observedPoint.x,t.projectedPoint.y-t.observedPoint.y),t.pixelDistance,.001);
    long frames=t.totalAnalyzedFrames;analyzer.analyzeForReplay(image(rgb,time+1_000_000_000L));assertEquals(frames+1,published.get().totalAnalyzedFrames);
   }finally{rgb.release();}
  }finally{scene.release();image.delete();}
 }
 @Test public void higherMatchCountOnAnotherReferenceCannotOwnCandidateTelemetry()throws Exception{
  assertTrue(OpenCVLoader.initLocal());Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();Mat pixels=new Mat(100,100,CvType.CV_8UC1);Core.randu(pixels,0,255);File image=File.createTempFile("telemetry-binding-",".png",c.getCacheDir());Imgcodecs.imwrite(image.getPath(),pixels);pixels.release();
  ComponentReferenceSpec a=ComponentReferenceSpec.builder("verified","main_board","Verified",100,100).localImageFilePath(image.getPath()).build(),b=ComponentReferenceSpec.builder("probe","other","Probe",100,100).localImageFilePath(image.getPath()).build();
  try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),Arrays.asList(a,b),VisionConfig.defaults(),null)){
   DiagnosticHammingMatcher.begin();for(ReferenceDatabase.PreparedReference ref:db.preparedReferences()){
    DiagnosticHammingMatcher.context(ref);DiagnosticHammingMatcher.attempted(200);List<Point> p=new ArrayList<>();int n=ref.spec.getReferenceId().equals("verified")?4:8;for(int y=1;y<=n;y++)for(int x=1;x<=n;x++)p.add(new Point(x*10,y*10));
    MatOfPoint2f from=new MatOfPoint2f(),to=new MatOfPoint2f();Mat mask=new Mat(),h=null;try{from.fromList(p);to.fromList(p);h=DiagnosticHammingMatcher.homography(from,to,org.opencv.calib3d.Calib3d.RANSAC,3,mask);}finally{from.release();to.release();mask.release();if(h!=null)h.release();}
   }
   RecognitionResult r=RecognitionResult.builder(1).identity("verified","main_board","Verified").method(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY).corners(Arrays.asList(new VisionPoint(0,0),new VisionPoint(100,0),new VisionPoint(100,100),new VisionPoint(0,100))).build();
   VerifiedScanEvidence e=DiagnosticHammingMatcher.bind(r,null);assertNotNull(e);assertEquals("verified",e.source.getReferenceId());assertEquals(16,e.good);assertEquals(200,e.total);assertNull(e.pose);assertTrue(Float.isNaN(new DiagnosticScanTelemetry("Verified","verified",100,100,e.good,e.total,0,0,false,true,false,null,Double.NaN,null,null,Double.NaN,1).treMm));
  }finally{DiagnosticHammingMatcher.end();image.delete();}
 }

 @Test public void realCasterAndSensorCandidatesOwnTheirRoiTelemetry()throws Exception{
  assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);
  Phase789VideoReplayInstrumentedTest replay=new Phase789VideoReplayInstrumentedTest();
  java.lang.reflect.Method ctx=Phase789VideoReplayInstrumentedTest.class.getDeclaredMethod("context"),raw=Phase789VideoReplayInstrumentedTest.class.getDeclaredMethod("raw",int.class,String.class,int.class);ctx.setAccessible(true);raw.setAccessible(true);Context c=(Context)ctx.invoke(replay);
  VisionConfig config=VisionConfig.defaults();
  for(int phase:new int[]{7,8}){
   List<ComponentReferenceSpec> bank=new ArrayList<>();for(ComponentReferenceSpec s:dev.chyken.assembl_ar.camera.Phase789VideoReferences.all()){
    dev.chyken.assembl_ar.camera.Phase789VideoReferences.Entry e=dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(s.getJpegAssetPath());if(e.phase==phase)bank.add(s);
   }
   String id=phase==7?"caster_wheel":"ultrasonic_distance_sensor";RecognitionRequest r=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id),CameraCalibration.pinhole(700,700,320,200),null,null,1,phase);
   final RecognitionRequest request=(phase==7?r.withPhase7Stage(1):r.withPhase8Stage(2)).withRequestTag("telemetry-real-P"+phase);
   try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),bank,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase7ReferenceMatcher p7=new Phase7ReferenceMatcher(db,config);Phase8ReferenceMatcher p8=new Phase8ReferenceMatcher(db,config)){
    Mat rgb=(Mat)raw.invoke(replay,phase,phase==7?"caster":"assembly",phase==7?20:82),gray=new Mat();
    try{Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);RecognitionResult found=null;for(int attempt=0;attempt<8;attempt++){DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.recovery((attempt&1)==1);found=phase==7?p7.inspect(gray,rgb,attempt+1,1):p8.inspect(gray,attempt+1,2);if(found.getValidationState()==RecognitionResult.ValidationState.EXPECTED_COMPONENT)break;}
     assertNotNull(found);assertEquals("P"+phase,RecognitionResult.ValidationState.EXPECTED_COMPONENT,found.getValidationState());VerifiedScanEvidence evidence=DiagnosticHammingMatcher.bind(found,request.getCameraCalibration());assertNotNull("Verified P"+phase+" ROI must own telemetry",evidence);
     found=found.withLatency(new RecognitionResult.Latency(evidence.extractionMs,evidence.matchingMs,DiagnosticHammingMatcher.snapshot().geometryNs/1e6,0,found.getLatency().getTotalMs())).withVerifiedScanEvidence(evidence,null,"CLAHE");DiagnosticSession.INSTANCE.analyzedFrame();DiagnosticSession.INSTANCE.record(found,request,gray,false);DiagnosticScanTelemetry t=DiagnosticSession.INSTANCE.latestTelemetry;assertNotNull(t);assertTrue(t.candidateVerified);assertNotNull(t.referenceId);assertTrue(t.goodMatches>=10);assertTrue(t.totalMatches>=t.goodMatches);assertTrue(t.activeKeypoints>0);assertTrue(t.liveKeypoints>0);assertTrue(t.matchingTimeMs>0);assertTrue(Float.isNaN(t.treMm));
     android.util.Log.i("DiagnosticTelemetry","P"+phase+" "+t.hudText().replace('\n',';'));
    }finally{rgb.release();gray.release();DiagnosticHammingMatcher.end();}
   }
  }
 }
 private static androidx.camera.core.ImageProxy image(Mat rgb,long time)throws Exception{java.lang.reflect.Method m=Class.forName("dev.chyken.assembl_ar.AssemblyReplayHarness",true,DiagnosticTelemetryInstrumentedTest.class.getClassLoader()).getDeclaredMethod("image",Mat.class,long.class);m.setAccessible(true);return (androidx.camera.core.ImageProxy)m.invoke(null,rgb,time);}

}
