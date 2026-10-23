package dev.chyken.assembl_ar.diagnostics;
import org.junit.Test;import static org.junit.Assert.*;
public final class AcademicBenchmarkRunnerTest {
    @Test public void longNoDetectionRunsHaveBoundedHistoryWithoutLosingDeliveredFrameCounts(){
        AcademicBenchmarkRunner r=new AcademicBenchmarkRunner();r.start(1000,"Pan");
        for(long i=1;i<=100000;i++){r.frame(i);if(i%2==0){r.computedFrame(i);r.computedFrame(i);}}
        assertTrue(r.retainedFrameCount()<=2048);r.frame(1L);r.computedFrame(1L);
        assertTrue(r.report().contains("100000 delivered frames"));assertTrue(r.report().contains("50.00 %"));
    }
    private DiagnosticSample sample(long t,int good,int total,String light,String method){return new DiagnosticSample(1,t,2,"top","board","reference",100,good,total,120,100,80,light,true,"accepted",method,true,null,Double.NaN,Double.NaN,Double.NaN,"p2:top");}
    @Test public void actualRatiosAndMissingDenominatorsAreNotConfidenceScores(){assertEquals(85,sample(1,85,100,"Normal","ORB_HOMOGRAPHY").accuracy(),0);assertTrue(Double.isNaN(sample(1,1,-1,"Normal","NONE").accuracy()));}
    @Test public void incompleteLightingAndGroundTruthStayUnmeasured(){AcademicBenchmarkRunner r=new AcademicBenchmarkRunner();r.start(2,"Static");r.observe(sample(1,90,100,"Normal","ORB_HOMOGRAPHY"));assertTrue(r.report().contains("UNMEASURED / INCOMPLETE"));assertTrue(r.report().contains("Normal recognition (1/2 trials): UNMEASURED"));}
    @Test public void allFourConditionsMustBeCollectedAndDuplicateFramesDoNotCount(){AcademicBenchmarkRunner r=new AcademicBenchmarkRunner();long t=1;for(String light:AcademicBenchmarkRunner.LIGHTS){r.start(2,"Pan");DiagnosticSample first=sample(t++,90,100,light,"ORB_HOMOGRAPHY");r.observe(first);r.observe(first);r.observe(sample(t++,90,100,light,"ORB_HOMOGRAPHY"));}assertTrue(r.report().contains("Trials: 8"));for(String light:AcademicBenchmarkRunner.LIGHTS)assertTrue(r.report().contains(light+" recognition (2/2 trials): 100.00 % · PASS"));}
    @Test public void shapeFallbackCannotPassOrbRecognitionRate(){AcademicBenchmarkRunner r=new AcademicBenchmarkRunner();r.start(1,"Static");r.observe(sample(1,100,100,"Bright","COLOR_SHAPE_FALLBACK"));assertTrue(r.report().contains("Bright recognition (1/1 trials): 0.00 % · FAIL"));}
    @Test public void heldFramesDoNotInflatePoseCoverage(){AcademicBenchmarkRunner r=new AcademicBenchmarkRunner();r.start(30,"Pan");for(int i=0;i<10;i++)r.frame(false);r.computedFrame();assertTrue(r.report().contains("10.00 % · FAIL"));r.overlay(12,Double.NaN);r.overlay(32,Double.NaN);assertTrue(r.report().contains("22.00 px · FAIL"));assertTrue(r.report().contains("32.00 px · FAIL"));}
    @Test public void asynchronousAndDuplicatePoseCallbacksCannotExceedDeliveredCoverage(){AcademicBenchmarkRunner r=new AcademicBenchmarkRunner();r.start(30,"Pan");r.computedFrame(99);r.frame(1L);r.computedFrame(1L);r.computedFrame(1L);r.frame(2L);assertTrue(r.report().contains("50.00 % · FAIL"));}
    @Test public void lowPairYieldIsNeverRelabeledAsAcademicPass(){
        DiagnosticSample s=sample(1,183,7590,"Normal","ORB_HOMOGRAPHY");
        assertEquals(2.411067,s.accuracy(),.00001);assertTrue(s.hudText().contains("85% gate FAIL"));
        assertTrue(s.hudText().contains("Workflow: VERIFIED"));assertTrue(s.csv().endsWith(",FAIL"));
    }
    @Test public void exportsRetainCsvBoundariesAndUnknownMeasurements(){DiagnosticSample s=sample(1,4,10,"Normal","ORB_HOMOGRAPHY");assertTrue(s.csv().contains("40.0000"));assertFalse(s.csv().contains("NaN"));assertEquals(DiagnosticSample.HEADER.split(",").length,27);}
}
