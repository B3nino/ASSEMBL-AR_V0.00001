package dev.chyken.assembl_ar.vision;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import org.opencv.core.Rect;
import dev.chyken.assembl_ar.workflow.PartRecognitionTestSession;

public class TrainedRecognitionAuthorityTest {
    private RecognitionRequest request(String id,int count,int phase){return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id),null,null,null,count,phase);}
    private TrainedDetection detection(String id,int x,int confirmations){return new TrainedDetection(id,id,new Rect(x,80,80,100),.93,.80,confirmations);}
    private TrainedModelAssist.Hints hints(TrainedDetection... predictions){List<Rect> boxes=new ArrayList<>();for(TrainedDetection p:predictions)boxes.add(p.bounds);return new TrainedModelAssist.Hints(640,480,1,280,boxes,Arrays.asList(predictions));}
    private RecognitionResult result(RecognitionRequest r,TrainedModelAssist.Hints h){return TrainedRecognitionAuthority.recognize(r,h,null,null,2);}
    @Test public void independentPredictionsOwnIdentityWithoutInventingOrbOrPose(){
        RecognitionRequest r=request("gear_motor_6v",1,0);TrainedModelAssist.Hints h=hints(detection("gear_motor_6v",100,2));
        RecognitionResult found=result(r,h);assertNotNull(found);assertTrue(VerifiedScanEvidence.workflowAccepted(found,r));
        assertEquals(RecognitionResult.DetectionMethod.TRAINED_MODEL,found.getDetectionMethod());assertNull(found.getPose());assertNull(VerifiedScanEvidence.coarsePose(found));
        assertEquals(0,found.getInlierCount());assertEquals(0,found.getGoodMatchCount());assertEquals(.93,found.getTrainedEvidence().confidence,0);
        assertEquals(2,found.withRequestTag("new").withLatency(new RecognitionResult.Latency(0,0,0,0,1)).getTrainedEvidence().confirmations);
    }
    @Test public void oneInferenceOrDuplicateFrameNeverConfirms(){
        TrainedDetection d=detection("gear_motor_6v",100,1);
        assertNull(result(request(d.component,1,0),hints(d)));
        for(long source:new long[]{1,100_000_000L,3_000_000_001L})assertEquals(1,TrainedDetection.confirm(Arrays.asList(d),Arrays.asList(d),source,1).get(0).confirmations);
        assertEquals(2,TrainedDetection.confirm(Arrays.asList(d),Arrays.asList(d),400_000_001L,1).get(0).confirmations);
        assertEquals(1,TrainedDetection.confirm(Arrays.asList(detection("gear_motor_6v",300,1)),Arrays.asList(d),400_000_001L,1).get(0).confirmations);
    }
    @Test public void countRequiresSeparateObjectsTogether(){
        RecognitionRequest r=request("gear_motor_6v",2,2).withPhase2Stage(2);TrainedDetection d=detection("gear_motor_6v",100,2);
        assertNull(result(r,hints(d)));assertNull(result(r,hints(d,d)));
        RecognitionResult pair=result(r,hints(d,detection(d.component,350,2)));assertNotNull(pair);assertEquals(2,pair.getDetectedInstanceCount(d.component));assertTrue(VerifiedScanEvidence.workflowAccepted(pair,r));
    }
    @Test public void weakAmbiguousClippedOrWrongClassCannotApprove(){
        RecognitionRequest r=request("gear_motor_6v",1,0);
        assertNull(result(r,hints(detection("motor_bracket",100,2))));
        for(TrainedDetection d:new TrainedDetection[]{new TrainedDetection("gear_motor_6v","gear_motor",new Rect(100,80,80,100),.84,.8,2),
                new TrainedDetection("gear_motor_6v","gear_motor",new Rect(100,80,80,100),.95,.10,2),detection("gear_motor_6v",0,2)})assertNull(result(r,hints(d)));
    }
    @Test public void identityDoesNotSkipFaceMountingDirectionOrWiring(){
        for(String id:new String[]{"extension_circuit_board","oled_protector","oled_096_i2c","battery_pack_7v_12v"})assertFalse(TrainedRecognitionAuthority.loose(request(id,1,0)));
        assertNull(result(request("ultrasonic_distance_sensor",1,0),hints(detection("ultrasonic_distance_sensor",100,2))));
        assertNull(result(request("center_standoff",1,0),hints(detection("center_standoff",100,2))));
        assertFalse(TrainedRecognitionAuthority.loose(request("main_board",1,2).withPhase2Stage(5).withPhase2VerificationView("top")));
        assertFalse(TrainedRecognitionAuthority.loose(request("arduino_nano_microcontroller",1,5).withPhase5Stage(3)));
        assertFalse(TrainedRecognitionAuthority.loose(request("main_board",1,7).withPhase7Stage(4)));
        assertFalse(TrainedRecognitionAuthority.loose(request("extension_circuit_board",1,10).withPhase10Stage(4)));
        assertFalse(TrainedRecognitionAuthority.loose(request("oled_096_i2c",1,11).withPhase11Stage(1)));
        RecognitionRequest front=request("arduino_nano_microcontroller",1,5).withPhase5Stage(1);
        assertNull(result(front,hints(detection("arduino_nano_microcontroller",100,2))));
    }
    @Test public void oneMotorCannotVerifyBothBoardSites(){
        TrainedModelAssist.Hints h=hints(detection("gear_motor_6v",100,2));
        assertTrue(TrainedRecognitionAuthority.supports(h,"gear_motor_6v",detection("gear_motor_6v",100,2).corners()));
        assertFalse(TrainedRecognitionAuthority.supports(h,"gear_motor_6v",detection("gear_motor_6v",350,2).corners()));
    }
    @Test public void testerCanLockTwoDimensionalIdentityWithoutFakeTranslation(){
        PartRecognitionTestSession session=new PartRecognitionTestSession();long token=session.selectPart("gear_motor_6v","Motor",.001f);
        for(int i=0;i<4;i++){
            RecognitionResult found=TrainedRecognitionAuthority.recognize(request("gear_motor_6v",1,0),hints(detection("gear_motor_6v",100,2)),null,null,1+i*400_000_000L);
            assertTrue(session.observe(token,found,new float[]{.2f,.2f,.4f,.2f,.4f,.5f,.2f,.5f}));
        }
        assertTrue(session.getSnapshot("gear_motor_6v").isLocked());
    }
    @Test public void localStructureAllowsBrightnessChangesButRejectsMovedBody(){
        byte[] source=new byte[128*96],brighter=new byte[source.length],moved=new byte[source.length];
        Random random=new Random(8);
        for(int i=0;i<source.length;i++){source[i]=(byte)(40+random.nextInt(150));brighter[i]=(byte)((source[i]&255)+20);moved[i]=(byte)(40+random.nextInt(150));}
        Rect b=new Rect(100,80,150,150);
        assertTrue(TrainedDetection.sameAppearance(source,brighter,128,96,b,640,480));
        assertFalse(TrainedDetection.sameAppearance(source,moved,128,96,b,640,480));
        assertFalse(TrainedDetection.sameAppearance(new byte[source.length],new byte[source.length],128,96,b,640,480));
    }
    @Test public void overlappingCompetingClassCannotBeAccepted(){
        TrainedDetection motor=detection("gear_motor_6v",100,2),bracket=detection("motor_bracket",100,2);
        assertNull(result(request("gear_motor_6v",1,0),hints(motor,bracket)));
    }
    @Test public void phaseOneNeedsTheFrontClassAndCannotInventOrientation(){
        RecognitionRequest r=request("main_board",1,1);Rect box=new Rect(100,80,200,200);
        TrainedDetection front=new TrainedDetection("main_board","main_board_front",box,.94,.70,2);
        TrainedDetection back=new TrainedDetection("main_board","main_board_back",box,.94,.70,2);
        assertNull(result(r,hints(back)));
        RecognitionResult accepted=result(r,hints(front));assertNotNull(accepted);assertNull(accepted.getPose());
        assertEquals(RecognitionResult.OrientationClassification.UNKNOWN,accepted.getOrientationClassification());
    }
}
