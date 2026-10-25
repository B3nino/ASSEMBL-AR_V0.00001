package dev.chyken.assembl_ar.vision;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.chyken.assembl_ar.camera.Phase10AssetCatalog;
import dev.chyken.assembl_ar.diagnostics.*;
import dev.chyken.assembl_ar.workflow.phase.Phase10ExtensionBoardFlow;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import static org.junit.Assert.*;

/** Actual room pixels through the shared CameraX analyzer, without opening/wiping user progress. */
public final class VerifiedTelemetryReplayInstrumentedTest {
    @BeforeClass public static void runtime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    private Context context()throws Exception{
        java.lang.reflect.Method method=Phase10FollowupReplayInstrumentedTest.class.getDeclaredMethod("context");method.setAccessible(true);
        return (Context)method.invoke(new Phase10FollowupReplayInstrumentedTest());
    }
    private Mat raw(int time)throws Exception{
        java.lang.reflect.Method method=Phase10FollowupReplayInstrumentedTest.class.getDeclaredMethod("raw",int.class,String.class,int.class);method.setAccessible(true);
        return (Mat)method.invoke(new Phase10FollowupReplayInstrumentedTest(),10,"roomstacked",time);
    }
    private androidx.camera.core.ImageProxy image(Mat rgb,long time)throws Exception{
        java.lang.reflect.Method method=Class.forName("dev.chyken.assembl_ar.AssemblyReplayHarness",true,getClass().getClassLoader()).getDeclaredMethod("image",Mat.class,long.class);method.setAccessible(true);
        return (androidx.camera.core.ImageProxy)method.invoke(null,rgb,time);
    }
    @Test public void roomStackedAssemblyPairsAndFreezesVerifiedMetricsWhileTrackingStaysLive()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();Mat rgb=raw(12);long now=android.os.SystemClock.elapsedRealtimeNanos();
        Phase10ExtensionBoardFlow flow=new Phase10ExtensionBoardFlow(now);flow.devJump(12,now);
        DiagnosticTelemetryLocker lock=new DiagnosticTelemetryLocker();String scope="P10/BOARD_WORK_SCAN";lock.enter(scope,flow.tag(),false);
        AtomicReference<DiagnosticSample> latest=new AtomicReference<>();List<String> order=new ArrayList<>(),states=new ArrayList<>();
        RecognitionRequest base=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(Phase10AssetCatalog.target(5)),CameraCalibration.pinhole(700,700,320,200),null,null,1,10).withPhase10Stage(5);
        List<ComponentReferenceSpec> refs=new ArrayList<>();for(ComponentReferenceSpec spec:Phase10AssetCatalog.references(c.getAssets()))if(Phase10AssetCatalog.eligible(Phase10AssetCatalog.referenceRole(spec.getReferenceId()),5))refs.add(spec);
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(config,db);
                AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,new AssemblyVisionAnalyzer.RequestProvider(){
                    public RecognitionRequest requestFor(AssemblyVisionAnalyzer.FrameMetadata f){return flow.scanning()?base.withRequestTag(flow.tag()):null;}
                    public RecognitionRequest requestForTracking(AssemblyVisionAnalyzer.FrameMetadata f){return base.withRequestTag(flow.tag());}
                    public boolean isAnalysisSuspended(){return flow.complete();}
                    public boolean isTrackingSuspended(){return false;}
                },new AssemblyVisionAnalyzer.Callback(){
                    public void onResult(RecognitionResult r){fail("The production screen must receive the paired callback");}
                    public void onTargetTracking(TargetBoundingTracker.Target t,AssemblyVisionAnalyzer.FrameMetadata f,Object tag){if(t!=null)order.add("outline");}
                    public void onResultWithTelemetry(RecognitionResult r,DiagnosticSample sample){
                        assertNotNull(sample);assertEquals(r.getFrameTimestampNanos(),sample.frameNanos);assertEquals(String.valueOf(r.getRequestTag()),sample.step);latest.set(sample);
                        states.add(r.getValidationState()+":"+(r.getPhase10Observation()==null?"no phase evidence":r.getPhase10Observation().state+"/"+r.getPhase10Observation().score)+":"+r.getFrameKeypointCount()+":"+sample.reason+":"+sample.telemetry.verificationSource);
                        lock.stream(r.getRequestTag(),sample.telemetry);order.add("result");
                        if(flow.accepts(r.getRequestTag(),r.getFrameTimestampNanos()))flow.observe(r.getPhase10Observation()!=null&&r.getPhase10Observation().accepted());
                        if(flow.complete())assertTrue(lock.freeze(r.getRequestTag(),sample.telemetry));
                    }
                    public void onDiagnosticSample(DiagnosticSample s){latest.set(s);lock.stream(flow.tag(),s.telemetry);}
                },config)){
            // The video changes viewpoint; telemetry ownership is independent of which valid view wins.
            int[] times={12,18,28,36};
            for(int a=0;a<24&&!flow.complete();a++){
                if(a>0&&a%6==0){rgb.release();rgb=raw(times[a/6]);}
                analyzer.analyzeForReplay(image(rgb,now+a*1_000_000_000L));
            }
            assertTrue("Real two-frame gate: "+states,flow.complete());assertTrue(lock.frozen());assertTrue(order.indexOf("outline")>=0&&order.indexOf("outline")<order.lastIndexOf("result"));
            DiagnosticScanTelemetry winner=lock.snapshot();assertTrue(winner.workflowVerified);assertEquals("Match (VERIFIED)",winner.outputStatus);assertNotEquals("Unverified",winner.componentName);assertNotNull(winner.verificationSource);
            assertFalse("Geometry acceptance must not manufacture a calibrated pose",!winner.poseMeasured&&winner.isCorrectPose);
            assertEquals(winner.goodMatches<0||winner.totalMatches<=0?Float.NaN:100f*winner.goodMatches/winner.totalMatches,winner.matchPercentage,.0001);
            analyzer.analyzeForReplay(image(rgb,now+30_000_000_000L));assertSame(winner,lock.snapshot());
            Mat blank=new Mat(rgb.size(),rgb.type(),new Scalar(180,180,180));try{analyzer.analyzeForReplay(image(blank,now+32_000_000_000L));}finally{blank.release();}
            assertSame("Later frames cannot erase the winner",winner,lock.snapshot());assertTrue(latest.get().telemetry.totalAnalyzedFrames>winner.totalAnalyzedFrames);
            flow.rescan(now+34_000_000_000L);lock.enter(scope,flow.tag(),flow.complete());assertFalse(lock.frozen());assertNull(lock.snapshot());
        }finally{rgb.release();}
    }
    @Test public void geometryHudIsGreenAndUnmeasuredValuesStayHonestInBothLayouts()throws Exception{
        Context c=context();Context wrapper=new android.content.ContextWrapper(c){
            @Override public ClassLoader getClassLoader(){return VerifiedTelemetryReplayInstrumentedTest.this.getClass().getClassLoader();}
            @Override public Object getSystemService(String name){if(LAYOUT_INFLATER_SERVICE.equals(name))return android.view.LayoutInflater.from(getBaseContext()).cloneInContext(this);return super.getSystemService(name);}
        };
        androidx.appcompat.view.ContextThemeWrapper themed=new androidx.appcompat.view.ContextThemeWrapper(wrapper,dev.chyken.assembl_ar.R.style.Theme_ASSEMBLAR);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            for(int layout:new int[]{dev.chyken.assembl_ar.R.layout.activity_assembly,dev.chyken.assembl_ar.R.layout.activity_part_recognition_test}){
                android.view.View root=android.view.LayoutInflater.from(themed).inflate(layout,null);
                dev.chyken.assembl_ar.ui.DevDiagnosticsOverlayView hud=root.findViewById(dev.chyken.assembl_ar.R.id.devDiagnosticsOverlay);
                DiagnosticScanTelemetry verified=new DiagnosticScanTelemetry(null,null,-1,3189,-1,-1,209,3222,false,false,false,null,Double.NaN,null,null,Double.NaN,21).withVerification("Extension Board","Photo + geometry",.85);
                hud.showTelemetry(verified);String text=hud.getText().toString();assertTrue(text.contains("Extension Board (VERIFIED)"));assertTrue(text.contains("Correct Pose: Unmeasured"));assertTrue(text.contains("ORB unmeasured"));assertFalse(text.contains("Output: Mismatch"));assertFalse(text.contains("100.00%"));assertEquals(15,text.split("\n").length);
                android.text.Spanned styled=(android.text.Spanned)hud.getText();assertEquals(0xFF00FF7F,styled.getSpans(0,styled.length(),android.text.style.ForegroundColorSpan.class)[0].getForegroundColor());
                assertTrue(hud.getElevation()>=999*themed.getResources().getDisplayMetrics().density);assertNull(hud.getOutlineProvider());
                if(layout==dev.chyken.assembl_ar.R.layout.activity_part_recognition_test)assertNotNull(root.findViewById(dev.chyken.assembl_ar.R.id.partTestHelpButton));
                root.measure(android.view.View.MeasureSpec.makeMeasureSpec(1600,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1000,android.view.View.MeasureSpec.EXACTLY));root.layout(0,0,1600,1000);
                android.graphics.Bitmap render=android.graphics.Bitmap.createBitmap(1600,1000,android.graphics.Bitmap.Config.ARGB_8888);
                try{root.draw(new android.graphics.Canvas(render));try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(c.getExternalFilesDir(null),layout==dev.chyken.assembl_ar.R.layout.activity_assembly?"telemetry-assembly.png":"telemetry-individual.png"))){render.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}}
                catch(java.io.IOException failure){throw new AssertionError(failure);}finally{render.recycle();}
            }
        });
    }
}
