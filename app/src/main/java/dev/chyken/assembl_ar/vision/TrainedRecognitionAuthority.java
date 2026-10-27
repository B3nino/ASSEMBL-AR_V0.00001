package dev.chyken.assembl_ar.vision;

import java.util.*;
import org.opencv.core.*;

/** Explicit boundary between 2D component identity and face/installation/metric pose. */
final class TrainedRecognitionAuthority {
    static boolean loose(RecognitionRequest r){
        if(r==null||r.getExpectation()==null||r.getSpatialAnchorComponentId()!=null||r.getGuidedBracketSlot()!=0
                ||r.requestsInstalledMotorInspection()||r.getExpectation().getOrientationDegrees()!=null
                ||r.getExpectation().getPolarityRule()!=null)return false;
        // These experimental PCB models confuse other kit boards at high scores.
        // Their class/face identity still needs independent photo verification.
        String id=r.getExpectation().getComponentId();
        if("extension_circuit_board".equals(id)||"oled_protector".equals(id)||"oled_096_i2c".equals(id)
                ||"battery_pack_7v_12v".equals(id))return false;
        return r.getPhase2Stage()!=5&&r.getPhase3Stage()<=1&&r.getPhase5Stage()<=1
                &&r.getPhase7Stage()<=2&&r.getPhase8Stage()<=1&&r.getPhase9Stage()<=2
                &&r.getPhase10Stage()<=1&&r.getPhase11Stage()==0;
    }
    static RecognitionResult recognize(RecognitionRequest r,TrainedModelAssist.Hints hints,Mat gray,Mat rgb,long timestamp){
        if(!loose(r)||hints==null)return null;
        String component=r.getExpectation().getComponentId();List<TrainedDetection> qualified=new ArrayList<>();
        for(TrainedDetection d:hints.detections){
            if(!component.equals(d.component)||!d.qualified(hints.width,hints.height))continue;
            boolean competing=false;
            for(TrainedDetection other:hints.detections)if(!d.label.equals(other.label)&&other.confidence>=d.confidence*.85
                    &&DnnRoiDecoder.overlap(d.bounds,other.bounds)>.40){competing=true;break;}
            if(competing)continue;
            boolean distinct=true;
            for(TrainedDetection p:qualified)if(!ComponentRecognizer.areDistinctInstanceQuads(p.corners(),d.corners())){distinct=false;break;}
            if(distinct)qualified.add(d);
        }
        // One frame must contain the entire set: detections never accumulate parts across time.
        if(qualified.size()!=r.getExpectedInstanceCount())return null;
        // Phase 7's brightness audit found a high-score false proposal. Retain a
        // local, independent ball/sensor-body check in both camera screens.
        if("caster_wheel".equals(component)||"line_tracker_module".equals(component)){
            if(rgb==null)return null;
            for(TrainedDetection d:qualified){
                Rect b=d.bounds;int pad=(int)Math.max(12,Math.max(b.width,b.height)*.30);
                int l=Math.max(0,b.x-pad),t=Math.max(0,b.y-pad),right=Math.min(rgb.cols(),b.x+b.width+pad),bottom=Math.min(rgb.rows(),b.y+b.height+pad);
                Mat crop=rgb.submat(new Rect(l,t,right-l,bottom-t));List<VisionPoint> local=null;
                try{
                    if("caster_wheel".equals(component)){BallCasterShapeRecognizer.Candidate shape=BallCasterShapeRecognizer.find(crop,.002);if(shape!=null)local=shape.corners;}
                    else{LooseSensorShapeRecognizer.Candidate shape=LooseSensorShapeRecognizer.findLineTracker(crop,.002);if(shape!=null)local=shape.corners;}
                }finally{crop.release();}
                if(local==null)return null;
                List<VisionPoint> scene=new ArrayList<>();for(VisionPoint p:local)scene.add(new VisionPoint(p.getX()+l,p.getY()+t));
                if(overlap(d.bounds,scene)<.40)return null;
            }
        }
        List<VisionPoint> face=null;
        if("center_standoff".equals(component)){
            if(rgb==null||gray==null)return null;
            Mat bgr=new Mat();
            try{org.opencv.imgproc.Imgproc.cvtColor(rgb,bgr,org.opencv.imgproc.Imgproc.COLOR_RGB2BGR);
                StandoffGeometryVerifier.Detection body=StandoffGeometryVerifier.find(gray,bgr);if(body!=null)face=body.corners;
            }finally{bgr.release();}
            if(face==null||overlap(qualified.get(0).bounds,face)<.40)return null;
        }
        if(r.getPhase3Stage()==1||r.getPhase5Stage()==1||"ultrasonic_distance_sensor".equals(component)){
            if(rgb==null)return null;
            if(r.getPhase3Stage()==1){LoosePartShapeRecognizer.ShapeCandidate body=LoosePartShapeRecognizer.findMotorDriver(rgb,.002);if(body!=null)face=body.corners();}
            if(r.getPhase5Stage()==1){LooseNanoShapeRecognizer.Candidate body=LooseNanoShapeRecognizer.find(rgb,.002);if(body!=null)face=body.corners;}
            if("ultrasonic_distance_sensor".equals(component)){LooseSensorShapeRecognizer.Candidate body=LooseSensorShapeRecognizer.findUltrasonic(rgb,.002);if(body!=null)face=body.corners;}
            // These models do not label front/back. Confirm the visible front surface separately.
            if(face==null||overlap(qualified.get(0).bounds,face)<.40)return null;
        }
        if(r.getAssemblyStepNumber()==1&&"main_board".equals(component)
                &&!"main_board_front".equals(qualified.get(0).label))return null;
        List<RecognitionResult.InstanceObservation> instances=new ArrayList<>();double confidence=1;int confirmations=3;
        for(TrainedDetection d:qualified){
            confidence=Math.min(confidence,d.confidence);confirmations=Math.min(confirmations,d.confirmations);
            instances.add(new RecognitionResult.InstanceObservation("onnx:phase"+TrainedModelPolicy.modelPhase(r),component,
                    RecognitionResult.DetectionMethod.TRAINED_MODEL,d.corners(),d.confidence));
        }
        RecognitionResult result=RecognitionResult.builder(timestamp).identity("onnx:phase"+TrainedModelPolicy.modelPhase(r),component,ComponentContourTemplates.name(component,component))
                .method(RecognitionResult.DetectionMethod.TRAINED_MODEL).validation(RecognitionResult.ValidationState.EXPECTED_COMPONENT)
                .confidence(confidence).corners(qualified.get(0).corners()).instances(instances).build()
                .withTrainedEvidence(new TrainedRecognitionEvidence(TrainedModelPolicy.modelPhase(r),confirmations,qualified.size(),hints.sourceNanos,confidence,hints.inferenceMs,true));
        if(r.getPhase3Stage()==1)result=result.withPhase3Observation(new Phase3Observation(Phase3Observation.State.FRONT,confidence));
        if(r.getPhase5Stage()==1)result=result.withPhase5Observation(new Phase5Observation(Phase5Observation.State.FRONT,confidence));
        if(r.getPhase8Stage()==1)result=result.withPhase8Observation(new Phase8Observation(Phase8Observation.State.FRONT,confidence));
        if(r.getPhase7Stage()>0)result=result.withPhase7Observation(new Phase7Observation(qualified.size(),false,confidence));
        if(r.getPhase9Stage()>0)result=result.withPhase9Observation(new Phase9Observation(r.getPhase9Stage()==1,r.getPhase9Stage()==2?qualified.size():0,false,false,false,false,confidence));
        if(r.getPhase10Stage()==1)result=result.withPhase10Observation(new Phase10Observation(Phase10Observation.State.FRONT_CONFIRMED,confidence));
        return result;
    }
    /** Independent high-confidence identity at a board/view-projected hardware site. */
    static boolean supports(TrainedModelAssist.Hints hints,String component,List<VisionPoint> site){
        return support(hints,component,site)!=null;
    }
    static TrainedDetection support(TrainedModelAssist.Hints hints,String component,List<VisionPoint> site){
        if(hints==null||site.size()!=4)return null;
        TrainedDetection match=null;
        for(TrainedDetection d:hints.detections)if(component.equals(d.component)&&d.qualified(hints.width,hints.height)&&overlap(d.bounds,site)>=.45){if(match!=null)return null;match=d;}
        return match;
    }
    private static double overlap(Rect box,List<VisionPoint> points){
        double l=Double.POSITIVE_INFINITY,t=l,r=Double.NEGATIVE_INFINITY,b=r;
        for(VisionPoint p:points){l=Math.min(l,p.getX());r=Math.max(r,p.getX());t=Math.min(t,p.getY());b=Math.max(b,p.getY());}
        double area=Math.max(0,r-l)*Math.max(0,b-t),intersection=Math.max(0,Math.min(box.x+box.width,r)-Math.max(box.x,l))
                *Math.max(0,Math.min(box.y+box.height,b)-Math.max(box.y,t));
        return intersection/Math.max(1,area+box.area()-intersection);
    }
    private TrainedRecognitionAuthority(){}
}
