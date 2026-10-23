package dev.chyken.assembl_ar.diagnostics;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public final class LightingEstimatorTest {
    private byte[] flat(int value){byte[] p=new byte[640];Arrays.fill(p,(byte)value);return p;}
    @Test public void exposureIsEstimatedWithoutManualRoomSelection(){
        assertEquals("Weak",LightingEstimator.estimate(flat(30),32,20));
        assertEquals("Normal",LightingEstimator.estimate(flat(120),32,20));
        assertEquals("Bright",LightingEstimator.estimate(flat(235),32,20));
    }
    @Test public void alternatingMaterialTextureDoesNotAloneMeanUnevenExposure(){
        byte[] p=flat(0);for(int i=0;i<p.length;i++)p[i]=(byte)(i%2==0?20:235);
        assertEquals("Normal",LightingEstimator.estimate(p,32,20));
        for(int y=0;y<20;y++)for(int x=0;x<32;x++)p[y*32+x]=(byte)(x<16?20:235);
        assertEquals("Uneven",LightingEstimator.estimate(p,32,20));
    }
    @Test public void missingPixelsRemainUnknown(){assertEquals("Unknown",LightingEstimator.estimate(null,32,20));}
}
