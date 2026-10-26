package dev.chyken.assembl_ar.vision;

import org.opencv.core.Mat;
import dev.chyken.assembl_ar.data.NeobotSeedData;

/** Shared rigid-body fallback dispatch, not a second competing detector. Keeps the proven
 * bracket slots, gearbox/can pair, driver terminals, Nano USB shell and dual-eye gates.
 * Board/OLED rectangles cannot prove identity or installation: their photo/ROI verifiers
 * remain required, including the Phase 11 bezel/header and independent board checks. */
public final class ModularShapeGeometryVerifier {
    private ModularShapeGeometryVerifier() { }
    static LoosePartShapeRecognizer.ShapeCandidate find(String component,Mat gray,Mat rgb,double minimumArea){
        if(NeobotSeedData.GEAR_MOTOR.equals(component))return LoosePartShapeRecognizer.findGearMotor(rgb,minimumArea);
        if(NeobotSeedData.MOTOR_DRIVER.equals(component))return LoosePartShapeRecognizer.findMotorDriver(rgb,minimumArea);
        if(NeobotSeedData.MICROCONTROLLER.equals(component)){
            LooseNanoShapeRecognizer.Candidate nano=LooseNanoShapeRecognizer.find(rgb,minimumArea);
            return nano==null?null:new LoosePartShapeRecognizer.ShapeCandidate("shape:loose-nano-v1","Arduino Nano",nano.confidence,nano.corners,45,18);
        }
        if(NeobotSeedData.DISTANCE_SENSOR.equals(component)||NeobotSeedData.LINE_TRACKER.equals(component)){
            LooseSensorShapeRecognizer.Candidate sensor=NeobotSeedData.DISTANCE_SENSOR.equals(component)
                    ?LooseSensorShapeRecognizer.findUltrasonic(rgb,minimumArea):LooseSensorShapeRecognizer.findLineTracker(rgb,minimumArea);
            return sensor==null?null:new LoosePartShapeRecognizer.ShapeCandidate(
                    NeobotSeedData.DISTANCE_SENSOR.equals(component)?"shape:loose-ultrasonic-v1":"shape:loose-line-tracker-v1",
                    NeobotSeedData.DISTANCE_SENSOR.equals(component)?"Ultrasonic distance sensor":"Line tracker",
                    sensor.confidence,sensor.corners,sensor.widthMm,sensor.heightMm);
        }
        if(NeobotSeedData.MOTOR_BRACKET.equals(component)){
            MotorBracketShapeRecognizer.Candidate bracket=MotorBracketShapeRecognizer.find(gray,rgb,minimumArea,null);
            return bracket==null?null:new LoosePartShapeRecognizer.ShapeCandidate("shape:loose-motor-bracket-v2","Gear-motor bracket",
                    bracket.confidence,bracket.corners,MotorBracketShapeRecognizer.WIDTH_MM,MotorBracketShapeRecognizer.HEIGHT_MM);
        }
        return null;
    }
}
