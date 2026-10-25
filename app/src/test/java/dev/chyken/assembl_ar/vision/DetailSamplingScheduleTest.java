package dev.chyken.assembl_ar.vision;
import org.junit.Test;
import static org.junit.Assert.*;
public final class DetailSamplingScheduleTest {
    @Test public void photometricRecoveryCannotAlternateAwayFromAProvenFrame(){DetailSamplingSchedule s=new DetailSamplingSchedule();assertTrue(s.recoveryFor(true));s.next();s.observe(true,false);assertFalse(s.recoveryFor(true));s.next();s.observe(false,true);assertFalse(s.recoveryFor(true));s.next();s.observe(false,true);assertTrue(s.recoveryFor(true));s.reset();assertFalse(s.recoveryFor(false));}
    @Test public void completeEvidenceKeepsItsScaleForTheNextFreshFrame(){DetailSamplingSchedule s=new DetailSamplingSchedule();assertEquals(0,s.next());s.observe(false);assertEquals(1,s.next());s.observe(true);for(int i=0;i<6;i++){assertEquals(1,s.next());s.observe(true);}}
    @Test public void twoMissesReleaseSamplingWithoutCreatingRecognitionEvidence(){DetailSamplingSchedule s=new DetailSamplingSchedule();s.next();s.observe(false);assertEquals(1,s.next());s.observe(true);assertEquals(1,s.next());s.observe(false);assertEquals(1,s.next());s.observe(false);assertEquals(0,s.next());s.observe(false);assertEquals(3,s.next());}
    @Test public void resetForAnotherStepRestoresAllRecoveryScales(){DetailSamplingSchedule s=new DetailSamplingSchedule();s.next();s.observe(true);s.reset();assertEquals(0,s.next());for(int i=1;i<=4;i++){s.observe(false);assertEquals(i,s.next());s.observe(false);assertEquals(0,s.next());}}
}
