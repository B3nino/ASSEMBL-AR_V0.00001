package dev.chyken.assembl_ar.vision;

import android.content.Context;
import java.lang.reflect.Method;
import org.junit.*;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import static org.junit.Assert.*;

/** Actual supplied YOLO model on held-out camera pixels; does not write progress. */
public class NanoDnnInstrumentedTest {
    @BeforeClass public static void runtime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    private Context context()throws Exception{Method m=Phase35VideoReplayInstrumentedTest.class.getDeclaredMethod("context");m.setAccessible(true);return (Context)m.invoke(new Phase35VideoReplayInstrumentedTest());}
    private Mat raw(String role,String time)throws Exception{Method m=Phase35VideoReplayInstrumentedTest.class.getDeclaredMethod("raw",String.class,String.class,String.class);m.setAccessible(true);return (Mat)m.invoke(new Phase35VideoReplayInstrumentedTest(),"nano",role,time);}
    @Test public void detectorTogglePersistsWithoutChangingAssemblyProgress()throws Exception{
        Context c=context();android.content.SharedPreferences settings=c.getSharedPreferences("diagnostic_settings",0);
        String key=dev.chyken.assembl_ar.diagnostics.PreferencesManager.KEY_NANO_DETECTOR;boolean existed=settings.contains(key),old=settings.getBoolean(key,true);
        java.util.Map<String,?> before=new java.util.HashMap<>(c.getSharedPreferences("assembly_phase_progress",0).getAll());
        try{
            dev.chyken.assembl_ar.diagnostics.PreferencesManager.nanoDetector(c,false);assertFalse(dev.chyken.assembl_ar.diagnostics.PreferencesManager.nanoDetector(c));
            dev.chyken.assembl_ar.diagnostics.PreferencesManager.nanoDetector(c,true);assertTrue(dev.chyken.assembl_ar.diagnostics.PreferencesManager.nanoDetector(c));
            assertEquals(before,c.getSharedPreferences("assembly_phase_progress",0).getAll());
        }finally{android.content.SharedPreferences.Editor editor=settings.edit();if(existed)editor.putBoolean(key,old);else editor.remove(key);editor.commit();}
    }
    @Test public void suppliedChipModelLoadsOnCpuAndProposesOnlyNanoRegions()throws Exception{
        OpenCvDnnRoiLocator locator=OpenCvDnnRoiLocator.INSTANCE;locator.prepare(context().getAssets());assertTrue(locator.status(),locator.available());
        assertTrue(locator.supports("arduino_nano_microcontroller"));assertFalse(locator.supports("main_board"));assertFalse(locator.supports("dual_motor_driver"));
        String[] roles={"front","assembly","invalid","missing"},times={"8","122","90","54"};int detections=0;
        for(int i=0;i<roles.length;i++){Mat rgb=raw(roles[i],times[i]);try{
            OpenCvDnnRoiLocator.Proposal p=locator.locateDetailed(rgb,"arduino_nano_microcontroller");assertTrue(locator.status(),locator.available());
            android.util.Log.i("NanoDnnTest",roles[i]+" detected="+(p!=null)+" confidence="+(p==null?0:p.confidence)+" ms="+(p==null?0:p.inferenceMs)+" anchor="+(p==null?null:p.anchor)+" search="+(p==null?null:p.search));
            if(p!=null){detections++;assertTrue(p.search.width>=p.anchor.width);assertTrue(p.search.height>=p.anchor.height);assertTrue(p.search.x>=0&&p.search.y>=0);assertTrue(p.search.x+p.search.width<=rgb.cols());assertEquals("ATmega chip",p.kind);}
        }finally{rgb.release();}}
        assertTrue("Supplied model must localize at least one held-out Nano to enable the experiment",detections>0);
        Mat blank=new Mat(400,640,CvType.CV_8UC3,new Scalar(180,180,180));try{
            assertNull("Blank scene must not localize a chip",locator.locateDetailed(blank,"arduino_nano_microcontroller"));
            long allocated=android.os.Debug.getNativeHeapAllocatedSize();
            for(int i=0;i<8;i++)assertNull(locator.locateDetailed(blank,"arduino_nano_microcontroller"));
            long growth=android.os.Debug.getNativeHeapAllocatedSize()-allocated;
            android.util.Log.i("NanoDnnTest","repeatInferenceNativeGrowth="+growth);
            assertTrue("Repeated inference must reuse its network without unbounded native growth: "+growth,growth<32L*1024*1024);
        }finally{blank.release();}
    }
    @Test public void chipProposalCannotPassReversedOrMissingNano()throws Exception{
        Context c=context();java.util.List<ComponentReferenceSpec> refs=dev.chyken.assembl_ar.camera.Phase5AssetCatalog.references(c.getAssets());
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,VisionConfig.defaults(),new java.io.File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase5ReferenceMatcher matcher=new Phase5ReferenceMatcher(db,VisionConfig.defaults())){
            for(String role:new String[]{"invalid","missing"}){
                Mat rgb=raw(role,role.equals("invalid")?"90":"54"),gray=new Mat();
                try{
                    org.opencv.imgproc.Imgproc.cvtColor(rgb,gray,org.opencv.imgproc.Imgproc.COLOR_RGB2GRAY);
                    for(int i=0;i<3;i++){
                        DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.snapshot().proposedRoi=new Rect(0,0,gray.cols(),gray.rows());
                        RecognitionResult result=matcher.inspect(gray,i+1,3);
                        assertFalse("Even a false neural proposal cannot verify "+role,result.getPhase5Observation().accepted(3));
                    }
                }finally{DiagnosticHammingMatcher.end();rgb.release();gray.release();}
            }
        }
    }
}
