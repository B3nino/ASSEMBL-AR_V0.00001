package dev.chyken.assembl_ar.vision;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ScanSourceVerificationTest {
    private RecognitionResult result(){return result("target");}
    private RecognitionResult result(String id){return RecognitionResult.builder(1).identity("shape:test",id,"Verified target")
            .method(RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK).validation(RecognitionResult.ValidationState.EXPECTED_COMPONENT)
            .corners(Arrays.asList(new VisionPoint(10,10),new VisionPoint(110,10),new VisionPoint(110,80),new VisionPoint(10,80))).confidence(.85).build();}
    private RecognitionRequest request(){return request("target",10);}
    private RecognitionRequest request(String id,int phase){return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id),null,null,null,1,phase);}
    @Test public void specializedGeometryUsesActualPhaseDecision(){
        String board=dev.chyken.assembl_ar.camera.Phase10AssetCatalog.target(5);
        assertTrue(VerifiedScanEvidence.workflowAccepted(result(board).withPhase10Observation(new Phase10Observation(Phase10Observation.State.ASSEMBLY_CONFIRMED,30)),request(board,10).withPhase10Stage(5)));
        assertFalse(VerifiedScanEvidence.workflowAccepted(result(board).withPhase10Observation(new Phase10Observation(Phase10Observation.State.SEARCHING,30)),request(board,10).withPhase10Stage(5)));
        assertTrue(VerifiedScanEvidence.workflowAccepted(result("center_standoff").withPhase9Observation(new Phase9Observation(true,0,false,false,false,false,0)),request("center_standoff",9).withPhase9Stage(1)));
        assertFalse(VerifiedScanEvidence.workflowAccepted(result("main_board").withPhase9Observation(new Phase9Observation(false,2,true,true,true,false,80)),request("main_board",9).withPhase9Stage(4)));
    }
    @Test public void phaseSevenStillRequiresBothTrackersAndCaster(){
        RecognitionRequest r=request("main_board",7).withPhase7Stage(4);Phase7Observation trackers=Phase7Observation.installedTrackers(2,2,true,30);
        assertFalse(VerifiedScanEvidence.workflowAccepted(result("line_tracker_module").withPhase7Observation(trackers),r));
        assertTrue(VerifiedScanEvidence.workflowAccepted(result("line_tracker_module").withPhase7Observation(trackers.withCaster(true)),r));
    }
    @Test public void wrongOrientationAndGlobalMismatchCanNeverBecomeVerified(){
        assertFalse(VerifiedScanEvidence.workflowAccepted(result().withOrientationValidation(RecognitionResult.OrientationClassification.UPSIDE_DOWN,180,RecognitionResult.ValidationState.WRONG_ORIENTATION),request()));
        assertFalse(VerifiedScanEvidence.workflowAccepted(result().withDiagnosticMetrics(new RecognitionResult.DiagnosticMetrics("Wrong phase")),request()));
        RecognitionRequest wrong=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("other"),null,null,null,1,10);
        assertFalse(VerifiedScanEvidence.workflowAccepted(result(),wrong));
    }
    @Test public void inwardSensorAndReversedNanoRemainUnverified(){
        assertFalse(VerifiedScanEvidence.workflowAccepted(result("ultrasonic_distance_sensor").withPhase8Observation(new Phase8Observation(Phase8Observation.State.INWARD,50)),request("ultrasonic_distance_sensor",8).withPhase8Stage(2)));
        assertFalse(VerifiedScanEvidence.workflowAccepted(result("arduino_nano_microcontroller").withPhase5Observation(new Phase5Observation(Phase5Observation.State.BACKWARD,50)),request("arduino_nano_microcontroller",5).withPhase5Stage(3)));
        String oled=dev.chyken.assembl_ar.camera.Phase11AssetCatalog.target(3);
        assertTrue(VerifiedScanEvidence.workflowAccepted(result(oled).withPhase11Observation(new Phase11Observation(Phase11Observation.State.ASSEMBLY,20)),request(oled,11).withPhase11Stage(3)));
    }
}
