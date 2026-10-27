package dev.chyken.assembl_ar.vision;
import org.junit.Test;
import static org.junit.Assert.*;
public class AssemblyCapturePolicyTest {
    @Test public void nativeDetailIsLimitedToPilotPhases(){for(int p=1;p<=11;p++)assertEquals(p==5||p==8?1280:640,AssemblyCapturePolicy.longEdge(p,false,false));}
    @Test public void lowRamAndUnsupportedCamerasKeepBaseline(){for(int p=1;p<=11;p++){assertEquals(640,AssemblyCapturePolicy.longEdge(p,true,false));assertEquals(640,AssemblyCapturePolicy.longEdge(p,false,true));}}
}
