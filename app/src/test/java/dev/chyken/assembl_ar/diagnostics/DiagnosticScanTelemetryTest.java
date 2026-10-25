package dev.chyken.assembl_ar.diagnostics;
import org.junit.Test;import static org.junit.Assert.*;
public final class DiagnosticScanTelemetryTest {
 private DiagnosticScanTelemetry make(int good,int total,boolean correct,boolean verified,boolean pose){return new DiagnosticScanTelemetry("Nano","nano-ref",500,234,good,total,2.2,7.7,correct,verified,pose,"Forward",Double.NaN,new DiagnosticScanTelemetry.Point(10,20),new DiagnosticScanTelemetry.Point(13,24),.5,42);}
 @Test public void exactHudLineOrderAndNumbersAreShared(){String text=make(85,100,true,true,true).hudText();String[] lines=text.split("\n");assertEquals(15,lines.length);assertEquals("Scanned component: Nano",lines[0]);assertEquals("Good/Matches: 85 / 100",lines[3]);assertEquals("%: 85.00% (PASS)",lines[4]);assertEquals("Extraction time (ms) : 2",lines[5]);assertEquals("Matching time (ms) : 8",lines[6]);assertEquals("% TRE: Unmeasured",lines[9]);assertEquals("Pixel distance: 5.00 px",lines[12]);assertEquals("Output: Match",lines[14]);}
 @Test public void percentageAndPoseAreIndependentRequiredGates(){assertEquals("Mismatch",make(84,100,true,true,true).outputStatus);assertEquals("Mismatch",make(100,100,false,true,true).outputStatus);assertEquals("Mismatch",make(100,100,true,true,false).outputStatus);assertEquals("Mismatch",make(100,100,true,false,true).outputStatus);assertEquals("Match",make(85,100,true,true,true).outputStatus);}
 @Test public void undefinedAndInvalidDenominatorsCannotPass(){assertTrue(Float.isNaN(make(0,0,true,true,true).matchPercentage));assertTrue(Float.isNaN(make(101,100,true,true,true).matchPercentage));assertEquals("Mismatch",make(-1,-1,true,true,true).outputStatus);}
 @Test public void lowYieldNeverGetsRenamedAsAccuracyPass(){DiagnosticScanTelemetry t=make(183,7590,true,true,true);assertEquals(2.411067,t.matchPercentage,.00001);assertEquals("Mismatch",t.outputStatus);}
 @Test public void missingGeometryIsNotAZeroError(){DiagnosticScanTelemetry t=new DiagnosticScanTelemetry("Probe","probe",-1,25,-1,-1,0,0,false,false,false,null,Double.NaN,null,null,Double.NaN,4);assertEquals("Unverified",t.componentName);assertNull(t.referenceId);assertTrue(Float.isNaN(t.pixelDistance));assertTrue(Float.isNaN(t.reprojectionRmsPx));assertTrue(t.hudText().contains("Projected point per frame: Unmeasured"));}
 @Test public void pixelOffsetAndRmsAreSeparateQuantities(){DiagnosticScanTelemetry t=make(90,100,true,true,true);assertEquals(5,t.pixelDistance,0);assertEquals(.5,t.reprojectionRmsPx,0);assertTrue(Float.isNaN(t.treMm));}
 @Test public void verifiedGeometryDoesNotInventOrbOrThreeDimensionalMeasurements(){
  DiagnosticScanTelemetry raw=new DiagnosticScanTelemetry("Probe",null,-1,319,-1,-1,8,15,false,false,false,null,Double.NaN,null,null,Double.NaN,12);
  DiagnosticScanTelemetry t=raw.withVerification("Extension Board","Photo + geometry",.85);
  assertEquals("Match (VERIFIED)",t.outputStatus);assertTrue(t.outputMatches());assertEquals("Extension Board",t.componentName);
  assertEquals("Unverified",raw.componentName);assertFalse(raw.workflowVerified);
  assertEquals(-1,t.goodMatches);assertEquals(-1,t.totalMatches);assertTrue(Float.isNaN(t.matchPercentage));
  assertFalse(t.poseMeasured);assertFalse(t.isCorrectPose);assertTrue(Float.isNaN(t.treMm));assertTrue(Float.isNaN(t.reprojectionRmsPx));
  assertTrue(t.hudText().contains("Correct Pose: Unmeasured"));assertTrue(t.hudText().contains("verifier confidence 85.00%"));assertEquals(15,t.hudText().split("\n").length);
 }
 @Test public void workflowStatusDoesNotRewriteFailedAcademicGate(){
  DiagnosticScanTelemetry t=make(183,7590,true,true,true).withVerification("Nano","ORB + RANSAC",.90);
  assertEquals(2.411067,t.matchPercentage,.00001);assertEquals(183,t.goodMatches);assertEquals(7590,t.totalMatches);
  assertTrue(t.hudText().contains("2.41% (FAIL · ORB gate)"));assertTrue(t.workflowVerified);assertEquals("Match (VERIFIED)",t.outputStatus);
 }
 @Test public void invalidVerifierConfidenceRemainsUnmeasured(){assertTrue(Double.isNaN(make(85,100,true,true,true).withVerification("Nano","Geometry",2).verificationConfidence));}
}
