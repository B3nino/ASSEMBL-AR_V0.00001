package dev.chyken.assembl_ar;
import android.content.Context;import android.os.SystemClock;import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;
import dev.chyken.assembl_ar.vision.*;import org.junit.*;import org.junit.runner.RunWith;import org.opencv.android.OpenCVLoader;import org.opencv.core.*;import org.opencv.imgproc.Imgproc;import org.opencv.imgcodecs.Imgcodecs;
import java.io.File;import java.util.*;import java.util.concurrent.atomic.*;import static org.junit.Assert.*;

/** Full shared analyzer regression for the exact scan -> complete token transition. */
@RunWith(AndroidJUnit4.class)
public final class DynamicOutlinePipelineInstrumentedTest {
    @BeforeClass public static void nativeRuntime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    @Test public void completionChangesTheTokenButKeepsVisualLocalizationWithoutNewWorkflowEvidence()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();Mat scene=new Mat(400,640,CvType.CV_8UC1,new Scalar(180)),body=scene.submat(new Rect(100,80,230,180));Core.setRNGSeed(78);Core.randu(body,10,245);Imgproc.GaussianBlur(body,body,new Size(3,3),.7);body.release();
        File file=File.createTempFile("outline-pipeline-",".png",c.getCacheDir());Imgcodecs.imwrite(file.getPath(),scene);
        ComponentReferenceSpec spec=ComponentReferenceSpec.builder("outline-pipeline","main_board","Main Board",115,90).localImageFilePath(file.getPath()).planarReferenceCornersNormalized(new VisionPoint(100./640,80./400),new VisionPoint(330./640,80./400),new VisionPoint(330./640,260./400),new VisionPoint(100./640,260./400)).build();
        VisionConfig config=VisionConfig.defaults();CameraCalibration calibration=CameraCalibration.pinhole(700,700,320,200);
        RecognitionRequest scan=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("main_board"),calibration,null,null,1,1).withRequestTag("scan-generation");
        AtomicBoolean complete=new AtomicBoolean();AtomicInteger evidence=new AtomicInteger(),diagnostics=new AtomicInteger();List<String> order=new ArrayList<>();AtomicReference<TargetBoundingTracker.Target> last=new AtomicReference<>();AtomicReference<Object> visualTag=new AtomicReference<>();
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),Collections.singletonList(spec),config,null);ComponentRecognizer recognizer=new ComponentRecognizer(config,db);
            AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,new AssemblyVisionAnalyzer.RequestProvider(){
                public RecognitionRequest requestFor(AssemblyVisionAnalyzer.FrameMetadata frame){return scan;}
                public RecognitionRequest requestForTracking(AssemblyVisionAnalyzer.FrameMetadata frame){return scan.withRequestTag("complete-generation");}
                public boolean isAnalysisSuspended(){return complete.get();}public boolean isTrackingSuspended(){return false;}
            },new AssemblyVisionAnalyzer.Callback(){
                public void onDiagnosticSample(dev.chyken.assembl_ar.diagnostics.DiagnosticSample sample){assertNotNull(sample);diagnostics.incrementAndGet();}
                public void onResult(RecognitionResult result){assertTrue("Synthetic registered board located",result.hasLocatedComponent());evidence.incrementAndGet();order.add("evidence");complete.set(true);}
                public void onTargetTracking(TargetBoundingTracker.Target target,AssemblyVisionAnalyzer.FrameMetadata frame,Object tag){if(target!=null){order.add("outline");last.set(target);visualTag.set(tag);}}
            },config)){
            long now=SystemClock.elapsedRealtimeNanos();Mat rgb=new Mat();Imgproc.cvtColor(scene,rgb,Imgproc.COLOR_GRAY2RGB);
            try{analyzer.analyzeForReplay(AssemblyReplayHarness.image(rgb,now));assertNotNull(last.get());assertTrue(order.indexOf("outline")<order.indexOf("evidence"));double before=last.get().regions.get(0).get(0).getX();
                dev.chyken.assembl_ar.diagnostics.DiagnosticSession.INSTANCE.latest=null;int recorded=diagnostics.get();
                Mat transform=Mat.eye(2,3,CvType.CV_64F),moved=new Mat();try{transform.put(0,2,24);Imgproc.warpAffine(rgb,moved,transform,rgb.size(),Imgproc.INTER_LINEAR,Core.BORDER_CONSTANT,new Scalar(180,180,180));
                    analyzer.analyzeForReplay(AssemblyReplayHarness.image(moved,now+400_000_000L));
                    assertEquals("Visual-only rechecks must not verify or advance again",1,evidence.get());assertEquals("complete-generation",visualTag.get());assertTrue("Visual-only detections keep diagnostic snapshots fresh without advancing workflow",diagnostics.get()>recorded);
                    assertTrue("Outline follows the new board location after completion",last.get().regions.get(0).get(0).getX()>before+8);
                    long old=last.get().epoch;analyzer.resetAssemblyTracking();assertTrue("Queued old visual frames can be rejected",analyzer.visualEpoch()>old);
                }finally{transform.release();moved.release();}
            }finally{rgb.release();}
        }finally{scene.release();file.delete();}
    }
}
