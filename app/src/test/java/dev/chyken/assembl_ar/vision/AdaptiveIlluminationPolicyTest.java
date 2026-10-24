package dev.chyken.assembl_ar.vision;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public final class AdaptiveIlluminationPolicyTest {
    private byte[] frame(int value){byte[] p=new byte[640];Arrays.fill(p,(byte)value);return p;}
    @Test public void weakAndUnevenFramesChooseRecoveryWithoutARoomSetting(){
        AdaptiveIlluminationPolicy p=new AdaptiveIlluminationPolicy();assertTrue(p.choose(frame(30),32,20));p.reset();
        byte[] uneven=frame(20);for(int y=0;y<20;y++)for(int x=16;x<32;x++)uneven[y*32+x]=(byte)235;
        assertTrue(p.choose(uneven,32,20));
    }
    @Test public void failuresKeepBothInputsAvailableAndRecognitionRemembersTheWorkingOne(){
        AdaptiveIlluminationPolicy p=new AdaptiveIlluminationPolicy();byte[] normal=frame(120);
        assertFalse(p.choose(normal,32,20));p.observe(false);assertTrue(p.choose(normal,32,20));p.observe(true);assertTrue(p.choose(normal,32,20));
        for(int i=0;i<20;i++){p.observe(false);assertEquals(i%2!=0,p.choose(normal,32,20));}
    }
    @Test public void aSustainedRoomChangeAndANewTargetDiscardTheOldPreference(){
        AdaptiveIlluminationPolicy p=new AdaptiveIlluminationPolicy();p.choose(frame(120),32,20);p.observe(true);
        p.choose(frame(30),32,20);assertTrue(p.choose(frame(30),32,20));p.observe(true);p.reset();assertFalse(p.choose(frame(120),32,20));
    }
}
