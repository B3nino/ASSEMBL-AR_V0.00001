package dev.chyken.assembl_ar.vision;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrainedModelPolicyTest {
    private RecognitionRequest request(String id,int count,int phase){
        return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id),null,null,null,count,phase);
    }
    @Test public void trainedComponentsUseHintsInLooseAndInstalledScans(){
        assertTrue(TrainedModelPolicy.assisted(request("dual_motor_driver",1,3).withPhase3Stage(1)));
        assertTrue(TrainedModelPolicy.assisted(request("dual_motor_driver",1,3).withPhase3Stage(4)));
        assertTrue(TrainedModelPolicy.assisted(request("arduino_nano_microcontroller",1,5).withPhase5Stage(1)));
        assertTrue(TrainedModelPolicy.assisted(request("arduino_nano_microcontroller",1,5).withPhase5Stage(3)));
        assertTrue(TrainedModelPolicy.assisted(request("drive_wheel",2,9).withPhase9Stage(2)));
        assertTrue(TrainedModelPolicy.assisted(request("center_standoff",1,9).withPhase9Stage(1)));
        assertTrue(TrainedModelPolicy.assisted(request("line_tracker_module",2,7).withPhase7Stage(2)));
        assertTrue(TrainedModelPolicy.assisted(request("ultrasonic_distance_sensor",1,8).withPhase8Stage(1)));
        assertTrue(TrainedModelPolicy.assisted(request("battery_pack_7v_12v",1,11).withPhase11Stage(2)));
        assertFalse(TrainedModelPolicy.assisted(null));
    }
    @Test public void phaseOneTwoAndIndividualTesterSelectTheComponentModel(){
        String[] ids={"main_board","motor_bracket","gear_motor_6v","dual_motor_driver","arduino_nano_microcontroller",
                "caster_wheel","line_tracker_module","ultrasonic_distance_sensor","center_standoff","drive_wheel",
                "extension_data_cable","extension_circuit_board","oled_protector","oled_096_i2c","battery_pack_7v_12v"};
        int[] phases={1,2,2,3,5,7,7,8,9,9,10,10,10,11,11};
        for(int i=0;i<ids.length;i++)assertEquals(ids[i],phases[i],TrainedModelPolicy.modelPhase(request(ids[i],1,0)));
        assertEquals(1,TrainedModelPolicy.modelPhase(request("main_board",1,1)));
        RecognitionRequest top=request("main_board",1,2).withPhase2Stage(5).withPhase2VerificationView("top");
        assertEquals(2,TrainedModelPolicy.modelPhase(top));
        assertTrue(TrainedModelPolicy.proposes(top,"gear_motor_6v"));assertTrue(TrainedModelPolicy.proposes(top,"motor_bracket"));
        assertFalse(TrainedModelPolicy.proposes(top,"main_board"));
        assertFalse(TrainedModelPolicy.assisted(request("main_board",1,4)));
        assertFalse(TrainedModelPolicy.assisted(request("main_board",1,6)));
        assertFalse(TrainedModelPolicy.assisted(request("untrained_part",1,0)));
    }
    @Test public void mountedCheckpointsLocateTheirActualBodies(){
        RecognitionRequest trackers=request("main_board",1,7).withPhase7Stage(4);
        assertEquals(7,TrainedModelPolicy.modelPhase(trackers));assertTrue(TrainedModelPolicy.proposes(trackers,"line_tracker_module"));
        RecognitionRequest wheels=request("main_board",1,9).withPhase9Stage(3);
        assertEquals(9,TrainedModelPolicy.modelPhase(wheels));assertTrue(TrainedModelPolicy.proposes(wheels,"drive_wheel"));
        RecognitionRequest protectedBoard=request("extension_circuit_board",1,10).withPhase10Stage(4);
        assertTrue(TrainedModelPolicy.proposes(protectedBoard,"extension_circuit_board"));assertTrue(TrainedModelPolicy.proposes(protectedBoard,"oled_protector"));
        RecognitionRequest stacked=request("extension_circuit_board",1,10).withPhase10Stage(5);
        assertTrue(TrainedModelPolicy.proposes(stacked,"extension_circuit_board"));
    }
    @Test public void aPartialInventoryCannotStopAutomaticRecovery(){
        RecognitionRequest request=request("motor_bracket",4,2).withPhase2Stage(1);
        java.util.List<VisionPoint> quad=java.util.Arrays.asList(new VisionPoint(0,0),new VisionPoint(40,0),new VisionPoint(40,40),new VisionPoint(0,40));
        RecognitionResult partial=RecognitionResult.builder(1).identity("test","motor_bracket","Bracket")
                .method(RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK).corners(quad)
                .validation(RecognitionResult.ValidationState.EXPECTED_COMPONENT).build();
        assertTrue(partial.hasLocatedComponent());assertFalse(VerifiedScanEvidence.workflowAccepted(partial,request));
        java.util.List<RecognitionResult.InstanceObservation> all=new java.util.ArrayList<>();
        for(int i=0;i<4;i++){
            java.util.List<VisionPoint> separate=java.util.Arrays.asList(new VisionPoint(i*60,0),new VisionPoint(i*60+40,0),new VisionPoint(i*60+40,40),new VisionPoint(i*60,40));
            all.add(new RecognitionResult.InstanceObservation("test-"+i,"motor_bracket",RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK,separate,.9));
        }
        RecognitionResult complete=RecognitionResult.builder(2).identity("test","motor_bracket","Brackets")
                .method(RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK).corners(quad).instances(all)
                .validation(RecognitionResult.ValidationState.EXPECTED_COMPONENT).build();
        assertEquals(4,complete.getDetectedInstanceCount("motor_bracket"));assertTrue(VerifiedScanEvidence.workflowAccepted(complete,request));
    }
    @Test public void classAliasesPreserveComponentIdentity(){
        assertEquals("gear_motor_6v",TrainedModelPolicy.component("gear_motor"));
        assertEquals("main_board",TrainedModelPolicy.component("main_board_back"));
        assertEquals("main_board",TrainedModelPolicy.component("main_board_front"));
        assertEquals("extension_circuit_board",TrainedModelPolicy.component("extension_board"));
        assertEquals("drive_wheel",TrainedModelPolicy.component("drive_wheel"));
        assertFalse(TrainedModelPolicy.packaged(4));assertFalse(TrainedModelPolicy.packaged(6));
        assertTrue(TrainedModelPolicy.packaged(11));assertFalse(TrainedModelPolicy.packaged(12));
    }
    @Test public void staleMovedResizedOrDifferentScopeHintsAreRejected(){
        long t=1_000_000_000L;
        assertTrue(TrainedModelPolicy.fresh(t,t+50_000_000,true,640,400,640,400,2));
        assertFalse(TrainedModelPolicy.fresh(t,t-1,true,640,400,640,400,0));
        assertFalse(TrainedModelPolicy.fresh(t,t+TrainedModelPolicy.MAX_AGE_NS+1,true,640,400,640,400,0));
        assertFalse(TrainedModelPolicy.fresh(t,t,false,640,400,640,400,0));
        assertFalse(TrainedModelPolicy.fresh(t,t,true,400,640,640,400,0));
        assertFalse(TrainedModelPolicy.fresh(t,t,true,640,400,640,400,13));
        assertFalse(TrainedModelPolicy.fresh(t,t,true,640,400,640,400,Double.NaN));
    }
}
