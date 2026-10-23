package dev.chyken.assembl_ar.vision;
import org.junit.Test;import static org.junit.Assert.*;
public final class PoseSmoothingTest {
    @Test public void wrapAtPiDoesNotFlipThroughZero(){double[] r=PoseSmoothing.rotation(new double[]{0,0,Math.toRadians(179)},new double[]{0,0,Math.toRadians(-179)},.5);assertEquals(Math.PI,Math.abs(r[2]),.001);}
    @Test public void endpointAndStaticRotationsRemainStable(){double[] r={.2,.1,-.4};assertArrayEquals(r,PoseSmoothing.rotation(r,r,.5),1e-9);assertArrayEquals(new double[3],PoseSmoothing.rotation(new double[3],r,0),1e-9);assertArrayEquals(r,PoseSmoothing.rotation(new double[3],r,1),1e-9);}
}
