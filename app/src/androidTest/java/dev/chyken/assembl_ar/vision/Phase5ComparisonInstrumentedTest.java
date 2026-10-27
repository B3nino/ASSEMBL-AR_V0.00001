package dev.chyken.assembl_ar.vision;

import android.content.Context;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

/** Optional committed-matcher A/B probe; prepare_phase5_comparison.py supplies
 * the renamed baseline DEX. Not part of the normal suite or learner APK. */
public class Phase5ComparisonInstrumentedTest {
    @Test public void committedAndCandidateDetailedNanoMatchersUseIdenticalPixels()throws Exception{
        Class<?> baselineClass;
        try{baselineClass=Class.forName("dev.chyken.assembl_ar.vision.BaselinePhase5ReferenceMatcher");}
        catch(ClassNotFoundException absent){Assume.assumeTrue("Optional comparison requires tools/prepare_phase5_comparison.py",false);return;}
        assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);
        Method contextMethod=Phase12VideoReplayInstrumentedTest.class.getDeclaredMethod("context");contextMethod.setAccessible(true);Context context=(Context)contextMethod.invoke(new Phase12VideoReplayInstrumentedTest());
        Method raw=Phase35VideoReplayInstrumentedTest.class.getDeclaredMethod("raw",String.class,String.class,String.class);raw.setAccessible(true);
        Mat rgb=(Mat)raw.invoke(new Phase35VideoReplayInstrumentedTest(),"nano","invalid","90"),gray=new Mat();Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
        Constructor<?> constructor=baselineClass.getDeclaredConstructor(ReferenceDatabase.class,VisionConfig.class);constructor.setAccessible(true);
        Method inspect=baselineClass.getDeclaredMethod("inspect",Mat.class,long.class,int.class);inspect.setAccessible(true);Field oldCursor=baselineClass.getDeclaredField("cursor"),newCursor=Phase5ReferenceMatcher.class.getDeclaredField("cursor");oldCursor.setAccessible(true);newCursor.setAccessible(true);
        VisionConfig config=VisionConfig.defaults();List<Double> a=new ArrayList<>(),b=new ArrayList<>();
        try(ReferenceDatabase db=ReferenceDatabase.load(context.getAssets(),dev.chyken.assembl_ar.camera.Phase5AssetCatalog.references(context.getAssets()),config,new java.io.File(context.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase5ReferenceMatcher candidate=new Phase5ReferenceMatcher(db,config);AutoCloseable baseline=(AutoCloseable)constructor.newInstance(db,config)){
            for(int round=0;round<8;round++)for(int slot=0;slot<2;slot++){
                boolean current=((round&1)==0?slot==1:slot==0);DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.recovery(true);
                // Align the rotating alternative as well as the source image/profile.
                oldCursor.setInt(baseline,round);newCursor.setInt(candidate,round-1);
                long started=android.os.SystemClock.elapsedRealtimeNanos();
                RecognitionResult result=current?candidate.inspect(gray,round+1,3):(RecognitionResult)inspect.invoke(baseline,gray,(long)round+1,3);
                double ms=(android.os.SystemClock.elapsedRealtimeNanos()-started)/1e6;
                assertEquals(Phase5Observation.State.BACKWARD,result.getPhase5Observation().state);
                if(round>=2)(current?b:a).add(ms);
                android.util.Log.i("Phase5Comparison","round="+round+" current="+current+" ms="+ms);
            }
            Collections.sort(a);Collections.sort(b);android.util.Log.i("Phase5Comparison","SUMMARY committedMedianMs="+a.get(a.size()/2)+" candidateMedianMs="+b.get(b.size()/2)+" committed="+a+" candidate="+b);
        }finally{DiagnosticHammingMatcher.end();rgb.release();gray.release();}
    }
}
