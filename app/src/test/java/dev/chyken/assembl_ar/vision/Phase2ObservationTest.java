package dev.chyken.assembl_ar.vision;
import org.junit.Test;
import static org.junit.Assert.*;
public final class Phase2ObservationTest {
    @Test public void sharedPcbMatchesDoNotProveHardwareIsMissing(){
        Phase2Observation uncertain=new Phase2Observation("top",0,90,1,0,false);
        assertFalse(uncertain.rejected);assertFalse(uncertain.referenceSupported);assertFalse(uncertain.withVisibleMotors(2).rejected);
        assertTrue(new Phase2Observation("top",0,90,0,0,true).rejected);
    }
    @Test public void bareBoardEvidenceCannotSubstituteForVerifiedLocalMotorFeatures(){
        assertFalse(new Phase2Observation("top",30,29).rejected);
        assertTrue(new Phase2Observation("top",30,270).referenceSupported);
        assertTrue(new Phase2Observation("top",0,40).rejected);
        assertFalse(new Phase2Observation("top",12,0).referenceSupported);
        assertTrue(new Phase2Observation("top",40,20).referenceSupported);
    }
    @Test public void topViewDoesNotNeedAnIndependentYellowMotorCount(){
        Phase2Observation top=new Phase2Observation("top",40,0,0);
        assertTrue(top.referenceSupported);assertTrue(top.withVisibleMotors(-1).referenceSupported);
    }
    @Test public void similarLeftAndRightScoresCannotConfirmEitherSide(){
        Phase2Observation tie=new Phase2Observation("left",40,0,1,38);
        assertTrue(tie.viewAmbiguous);assertFalse(tie.referenceSupported);
        Phase2Observation clear=new Phase2Observation("right",48,10,1,30);
        assertFalse(clear.viewAmbiguous);assertTrue(clear.referenceSupported);
        assertFalse(tie.withVisibleMotors(2).referenceSupported);
        assertEquals(38,tie.withVisibleMotors(2).competingViewScore,0);
    }
}
