package dev.chyken.assembl_ar.workflow.phase;
import org.junit.Test;import static org.junit.Assert.*;
public final class CameraTrackingPolicyTest {
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
