package dev.chyken.assembl_ar.vision;
import org.junit.Test;
import static org.junit.Assert.*;
public final class Phase3ReferenceEligibilityTest {
    @Test public void phasePhotosNeverBecomeOrdinaryBoardOrGhostAnchors(){
        RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("dual_motor_driver"),null,null,null,1,3);
        for(String role:new String[]{"front","back","assembly"}){
            ComponentReferenceSpec scene=ComponentReferenceSpec.builder("p3scene_"+role+"_fixture","dual_motor_driver","driver",28,37).jpegAssetPath("references/Phase3/test.jpg").assemblyStepNumber(3).build();
            assertFalse(ComponentRecognizer.isReferenceEligibleForRequest(scene,request));assertFalse(ComponentRecognizer.isAssemblyReferenceEligibleForBoardFallback(scene));
        }
    }
    @Test public void stageAndPhotoEvidenceSurviveAnalyzerTagAndScopeCopies(){
        RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("dual_motor_driver"),null,null,null,1,3).withPhase3Stage(4).withRequestTag("generation");
        assertEquals(4,request.getPhase3Stage());assertNull(request.getSpatialAnchorComponentId());assertEquals("generation",request.getRequestTag());
        Phase3Observation evidence=new Phase3Observation(Phase3Observation.State.PLACED,30);
        RecognitionResult result=RecognitionResult.builder(123).build().withPhase3Observation(evidence).withAssemblyInspection(3,null,false,null).withRequestTag("generation");
        assertSame(evidence,result.getPhase3Observation());assertEquals("generation",result.getRequestTag());assertNull(result.getPose());assertNull(result.getSpatialAnchor());
    }
    @Test(expected=IllegalArgumentException.class)public void wrongTargetCannotEnterPhotoVerification(){new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("main_board"),null,null,null,1,3).withPhase3Stage(4);}
}
