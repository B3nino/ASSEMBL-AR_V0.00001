package dev.chyken.assembl_ar.vision;
import org.junit.Test;import static org.junit.Assert.*;
public final class PoseOrientationTest {
 private double[] front(double yaw){double a=Math.toRadians(yaw),c=Math.cos(a),s=Math.sin(a);return new double[]{c,s,0,s,-c,0,0,0,-1};}
 @Test public void nominalFrontRx180IsForwardNotUpsideDown(){PoseOrientation o=PoseOrientation.fromRotation(front(0),null);assertEquals("Forward",o.label);assertEquals(0,o.roll,.0001);assertTrue(o.frontFacing);}
 @Test public void signedYawSeparatesLeftRightAndBackward(){assertEquals("Right",PoseOrientation.fromRotation(front(90),null).label);assertEquals("Left",PoseOrientation.fromRotation(front(-90),null).label);assertEquals("Backward",PoseOrientation.fromRotation(front(180),null).label);assertEquals("Backward",PoseOrientation.fromRotation(front(-180),null).label);}
 @Test public void independentExpectedFrontCancelsBoardCameraRotation(){PoseOrientation o=PoseOrientation.fromRotation(front(90),front(90));assertEquals("Forward",o.label);assertEquals(0,o.yaw,.0001);}
 @Test public void oppositeFaceIsNotCorrectFrontPose(){PoseOrientation o=PoseOrientation.fromRotation(new double[]{1,0,0,0,1,0,0,0,1},null);assertEquals("Upside Down",o.label);assertFalse(o.frontFacing);}
 @Test public void gapAnglesDoNotGetInventedCardinalDirection(){assertEquals("Upright",PoseOrientation.fromRotation(front(45),null).label);}
}
