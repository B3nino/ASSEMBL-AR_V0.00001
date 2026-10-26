package dev.chyken.assembl_ar.diagnostics;
import org.junit.Test;
import static org.junit.Assert.*;

public class CoarsePoseTelemetryTest {
    @Test public void coarsePoseSurvivesVerificationAndNeverInventsOrbOrAccuracy(){
        DiagnosticScanTelemetry t=new DiagnosticScanTelemetry("Nano",null,-1,120,-1,-1,20,30,false,true,true,
                null,Double.NaN,null,null,Double.NaN,3).withCoarsePose().withVerification("Nano","Geometry/shape",.86);
        assertTrue(t.poseMeasured);assertTrue(t.coarsePose);assertFalse(t.isCorrectPose);
        assertTrue(t.hudText().contains("Correct Pose: Coarse PnP"));assertTrue(Float.isNaN(t.matchPercentage));
        assertTrue(Float.isNaN(t.reprojectionRmsPx));assertTrue(Float.isNaN(t.treMm));assertEquals("Match (VERIFIED)",t.outputStatus);
    }
    @Test public void absentPoseRemainsUnmeasured(){
        DiagnosticScanTelemetry t=new DiagnosticScanTelemetry("Nano",null,-1,120,-1,-1,20,30,false,true,false,
                null,Double.NaN,null,null,Double.NaN,3).withCoarsePose();
        assertFalse(t.coarsePose);assertTrue(t.hudText().contains("Correct Pose: Unmeasured"));
    }
}
