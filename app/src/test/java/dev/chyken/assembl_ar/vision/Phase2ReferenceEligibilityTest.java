package dev.chyken.assembl_ar.vision;
import org.junit.Test;
import static org.junit.Assert.*;
public final class Phase2ReferenceEligibilityTest {
    @Test public void positiveAndNegativeScenesNeverBecomeOrdinaryBoardAnchors(){
        RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("main_board"),null,null,null,1,2);
        for(String role:new String[]{"top","left","right","invalid"}){
            ComponentReferenceSpec scene=ComponentReferenceSpec.builder("p2scene_"+role+"_fixture","main_board","scene",100,120)
                    .jpegAssetPath("references/Phase2/test.jpg").assemblyStepNumber(2).build();
            assertFalse(ComponentRecognizer.isReferenceEligibleForRequest(scene,request));
            assertFalse(ComponentRecognizer.isAssemblyReferenceEligibleForBoardFallback(scene));
        }
    }
    @Test public void phase2StageAndRequestTagSurviveCopies(){
        RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("gear_motor_6v"),null,null,null,2,2)
                .withPhase2Stage(2).withRequestTag("stage-generation");
        assertEquals(2,request.getPhase2Stage());assertEquals("stage-generation",request.getRequestTag());
    }
}
