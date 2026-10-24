package dev.chyken.assembl_ar.workflow.phase;
import org.junit.Test;import static org.junit.Assert.*;
public final class CameraTrackingPolicyTest {
    @Test public void fastSuccessfulScansKeepVisualsUntilNavigationButNeverInDemos(){
        for(int phase=1;phase<=11;phase++)for(PhaseFlow.Mode source:new PhaseFlow.Mode[]{PhaseFlow.Mode.SCAN_PARTS,PhaseFlow.Mode.VERIFY}){
            PhaseFlow.ScanTag scan=new PhaseFlow.ScanTag("phase"+phase,source,4);
            for(PhaseFlow.Mode mode:new PhaseFlow.Mode[]{source,PhaseFlow.Mode.PHYSICAL_CHECK,PhaseFlow.Mode.COMPLETE}){
                assertTrue(CameraTrackingPolicy.retains(scan,new PhaseFlow.ScanTag("phase"+phase,mode,4),false));
                assertFalse(CameraTrackingPolicy.retains(scan,new PhaseFlow.ScanTag("phase"+phase,mode,5),false));
                assertFalse(CameraTrackingPolicy.retains(scan,new PhaseFlow.ScanTag("other",mode,4),false));
                assertFalse(CameraTrackingPolicy.retains(scan,new PhaseFlow.ScanTag("phase"+phase,mode,4),true));
            }
            assertFalse(CameraTrackingPolicy.retains(scan,new PhaseFlow.ScanTag("phase"+phase,PhaseFlow.Mode.DEMO,4),false));
        }
    }
    @Test public void completedPhaseOneStillOwnsTheCameraWhileOldEvidenceIsRejected(){
        PhaseFlow flow=new PhaseFlow(AssemblyPhaseCatalog.phases().get(0),1);
        PhaseFlow.ScanTag scan=flow.tag();flow.inventoryComplete(2);
        assertEquals(PhaseFlow.Mode.COMPLETE,flow.getMode());assertFalse(flow.accept(scan,3));
        assertTrue(CameraTrackingPolicy.focused(1,flow.getMode(),false));
    }
    @Test public void allScanModesTrackAndDemosNeverAcquireEvidence(){
        for(int phase=1;phase<=11;phase++){
            assertTrue(CameraTrackingPolicy.focused(phase,PhaseFlow.Mode.SCAN_PARTS,false));assertTrue(CameraTrackingPolicy.focused(phase,PhaseFlow.Mode.VERIFY,false));
            assertFalse(CameraTrackingPolicy.focused(phase,PhaseFlow.Mode.DEMO,false));assertFalse(CameraTrackingPolicy.focused(phase,PhaseFlow.Mode.PHYSICAL_CHECK,false));
            assertFalse(CameraTrackingPolicy.focused(phase,PhaseFlow.Mode.SCAN_PARTS,true));
        }
    }
}
