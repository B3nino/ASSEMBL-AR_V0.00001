package dev.chyken.assembl_ar.vision;
import org.junit.Test;
import static org.junit.Assert.*;
import dev.chyken.assembl_ar.workflow.phase.*;
public final class Phase6RecognitionRequestTest {
 @Test public void phaseSixNeverCollectsCameraEvidence(){
     PhaseFlow parent=new PhaseFlow(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.DRIVER_WIRING),0,false);
     Phase6DriverWiringFlow wiring=new Phase6DriverWiringFlow(0);parent.attachDriverWiringFlow(wiring);
     for(int step=1;step<=4;step++){wiring.devJump(step,1);assertFalse(parent.collectsEvidence());assertFalse(parent.canScan());assertFalse(parent.accept(wiring.tag(),2));}
 }
}

