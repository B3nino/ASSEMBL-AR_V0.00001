package dev.chyken.assembl_ar.vision;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Regression for a common mainboard incorrectly vetoing an installed Nano scan. */
public final class GlobalComponentIdentityTest {
    private ComponentReferenceSpec reference(int phase){
        return ComponentReferenceSpec.builder("p"+phase+"scene_assembly_test",phase==3?"dual_motor_driver":"arduino_nano_microcontroller","Module",28,37)
            .jpegAssetPath("references/Phase"+phase+"/assembly.jpg").assemblyStepNumber(phase)
            .planarReferenceCornersNormalized(new VisionPoint(.4,.4),new VisionPoint(.6,.4),new VisionPoint(.6,.6),new VisionPoint(.4,.6))
            .featureRegionsNormalized(Collections.singletonList(Arrays.asList(new VisionPoint(.02,.02),new VisionPoint(.98,.02),new VisionPoint(.98,.98),new VisionPoint(.02,.98)))).build();
    }
    @Test public void commonPcbPrintingCannotBecomeDriverOrNanoIdentity(){
        List<VisionPoint> pcb=new ArrayList<>();for(int i=0;i<40;i++)pcb.add(new VisionPoint(.1+(i%5)*.02,.1+(i/5)*.02));
        for(int phase:new int[]{3,5})assertEquals(-1,GlobalComponentRegistry.identitySupport(reference(phase),pcb));
    }
    @Test public void distributedModuleInliersAreRequiredAndOnlyTheyContributeToVetoScore(){
        List<VisionPoint> points=new ArrayList<>();for(int i=0;i<8;i++)points.add(new VisionPoint(.41+(i%4)*.055,.42+(i/4)*.14));
        for(int i=0;i<30;i++)points.add(new VisionPoint(.1,.1));
        for(int phase:new int[]{3,5})assertEquals(8,GlobalComponentRegistry.identitySupport(reference(phase),points));
    }
    @Test public void aSingleHeaderRowCannotEstablishModuleOrientation(){
        List<VisionPoint> points=new ArrayList<>();for(int i=0;i<12;i++)points.add(new VisionPoint(.41+i*.014,.43));
        assertEquals(-1,GlobalComponentRegistry.identitySupport(reference(5),points));
    }
    private List<VisionPoint> rect(double l,double t,double r,double b){return Arrays.asList(new VisionPoint(l,t),new VisionPoint(r,t),new VisionPoint(r,b),new VisionPoint(l,b));}
    @Test public void motorDriverElsewhereOnBoardCannotVetoCorrectInstalledNano(){
        assertFalse(GlobalComponentRegistry.samePhysicalTarget(rect(20,20,120,60),rect(180,100,260,190)));
        assertFalse(GlobalComponentRegistry.samePhysicalTarget(rect(20,20,120,60),rect(0,0,640,400)));
        assertTrue(GlobalComponentRegistry.samePhysicalTarget(rect(20,20,120,60),rect(22,19,119,62)));
        assertFalse(GlobalComponentRegistry.samePhysicalTarget(Collections.emptyList(),rect(0,0,40,40)));
    }
    @Test public void aLocatedNanoStillCheckingPlacementIsNotVetoedByADifferentPartElsewhere(){
        RecognitionResult nano=RecognitionResult.builder(1).identity("nano:body","arduino_nano_microcontroller","Nano")
            .method(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY).validation(RecognitionResult.ValidationState.NO_COMPONENT).corners(rect(180,100,260,190)).build();
        assertTrue(nano.hasLocatedComponent());
        assertFalse(GlobalComponentRegistry.competesWithLocalization(nano,rect(20,20,120,60)));
        assertTrue("A wrong identity at the actual Nano location can still challenge it",GlobalComponentRegistry.competesWithLocalization(nano,rect(181,100,260,191)));
        assertTrue(GlobalComponentRegistry.competesWithLocalization(RecognitionResult.builder(1).build(),rect(20,20,120,60)));
    }
}
