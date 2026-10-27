package dev.chyken.assembl_ar.vision;

import android.content.Context;
import java.lang.reflect.Method;
import java.util.*;
import org.junit.*;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import static org.junit.Assert.*;

/** The trained full Nano board model, including held-out and invalid camera views. */
public class NanoDnnInstrumentedTest {
    @BeforeClass public static void runtime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    private Context context()throws Exception{Method m=TrainedModelAssistInstrumentedTest.class.getDeclaredMethod("context");m.setAccessible(true);return (Context)m.invoke(new TrainedModelAssistInstrumentedTest());}
    private Mat raw(String role,String time)throws Exception{Method m=Phase35VideoReplayInstrumentedTest.class.getDeclaredMethod("raw",String.class,String.class,String.class);m.setAccessible(true);return (Mat)m.invoke(new Phase35VideoReplayInstrumentedTest(),"nano",role,time);}
    @Test public void trainedTogglePersistsWithoutChangingAssemblyProgress()throws Exception{
        Context c=context();android.content.SharedPreferences settings=c.getSharedPreferences("diagnostic_settings",0);
        String key=dev.chyken.assembl_ar.diagnostics.PreferencesManager.KEY_TRAINED_MODELS;boolean existed=settings.contains(key),old=settings.getBoolean(key,true);
        Map<String,?> before=new HashMap<>(c.getSharedPreferences("assembly_phase_progress",0).getAll());
        try{
            dev.chyken.assembl_ar.diagnostics.PreferencesManager.trainedModels(c,false);assertFalse(dev.chyken.assembl_ar.diagnostics.PreferencesManager.trainedModels(c));
            dev.chyken.assembl_ar.diagnostics.PreferencesManager.trainedModels(c,true);assertTrue(dev.chyken.assembl_ar.diagnostics.PreferencesManager.trainedModels(c));
            assertEquals(before,c.getSharedPreferences("assembly_phase_progress",0).getAll());
        }finally{android.content.SharedPreferences.Editor editor=settings.edit();if(existed)editor.putBoolean(key,old);else editor.remove(key);editor.commit();}
    }
    @Test public void trainedBoardModelLoadsOnCpuAndProposesNanoRegions()throws Exception{
        Context c=context();Method prepare=TrainedModelAssist.class.getDeclaredMethod("prepare",int.class),infer=TrainedModelAssist.class.getDeclaredMethod("infer",Mat.class,String.class);
        prepare.setAccessible(true);infer.setAccessible(true);
        try(TrainedModelAssist assist=new TrainedModelAssist(c)){
            prepare.invoke(assist,5);int detections=0;
            String[] roles={"front","front","assembly"},times={"8","20","108"};
            for(int i=0;i<roles.length;i++){Mat rgb=raw(roles[i],times[i]);try{
                List<?> boxes=(List<?>)infer.invoke(assist,rgb,"arduino_nano_microcontroller");
                if(!boxes.isEmpty())detections++;
                for(Object value:boxes){Rect box=(Rect)value;assertTrue(box.x>=0&&box.y>=0&&box.x+box.width<=rgb.cols()&&box.y+box.height<=rgb.rows());}
            }finally{rgb.release();}}
            assertTrue("The trained board model must localize a held-out Nano",detections>0);
            Mat blank=new Mat(400,640,CvType.CV_8UC3,Scalar.all(180));
            try{
                assertTrue(((List<?>)infer.invoke(assist,blank,"arduino_nano_microcontroller")).isEmpty());
                long allocated=android.os.Debug.getNativeHeapAllocatedSize();
                for(int i=0;i<8;i++)assertTrue(((List<?>)infer.invoke(assist,blank,"arduino_nano_microcontroller")).isEmpty());
                assertTrue(android.os.Debug.getNativeHeapAllocatedSize()-allocated<32L*1024*1024);
            }finally{blank.release();}
        }
    }
    @Test public void trainedProposalCannotPassReversedOrMissingNano()throws Exception{
        Context c=context();List<ComponentReferenceSpec> refs=dev.chyken.assembl_ar.camera.Phase5AssetCatalog.references(c.getAssets());
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,VisionConfig.defaults(),new java.io.File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase5ReferenceMatcher matcher=new Phase5ReferenceMatcher(db,VisionConfig.defaults())){
            for(String role:new String[]{"invalid","missing"}){
                Mat rgb=raw(role,role.equals("invalid")?"90":"54"),gray=new Mat();
                try{
                    org.opencv.imgproc.Imgproc.cvtColor(rgb,gray,org.opencv.imgproc.Imgproc.COLOR_RGB2GRAY);
                    for(int i=0;i<3;i++){
                        DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.State state=DiagnosticHammingMatcher.snapshot();
                        state.trainedPass=true;state.trainedHints=new TrainedModelAssist.Hints(gray.cols(),gray.rows(),1,1,Collections.singletonList(new Rect(0,0,gray.cols(),gray.rows())));
                        RecognitionResult result=matcher.inspect(gray,i+1,3);
                        assertFalse("Even a false neural proposal cannot verify "+role,result.getPhase5Observation().accepted(3));
                    }
                }finally{DiagnosticHammingMatcher.end();rgb.release();gray.release();}
            }
        }
    }
}
