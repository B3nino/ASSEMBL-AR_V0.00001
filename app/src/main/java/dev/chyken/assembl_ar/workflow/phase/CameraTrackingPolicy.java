package dev.chyken.assembl_ar.workflow.phase;
/** Visual ownership is distinct from accepting new verification evidence. */
public final class CameraTrackingPolicy {
    private CameraTrackingPolicy(){}
    public static boolean focused(int phase,PhaseFlow.Mode mode,boolean previewing){
        return !previewing&&(mode==PhaseFlow.Mode.SCAN_PARTS||mode==PhaseFlow.Mode.VERIFY||phase==1&&mode==PhaseFlow.Mode.COMPLETE);
    }
    /** A successful scan may stop accepting evidence while its SAME screen keeps visual LK.
     * Navigation, replay, hardware preparation and pause/resume change the generation. */
    public static boolean retains(PhaseFlow.ScanTag scan,PhaseFlow.ScanTag current,boolean previewing){
        if(scan==null||current==null||previewing)return false;
        if(scan.getMode()!=PhaseFlow.Mode.SCAN_PARTS&&scan.getMode()!=PhaseFlow.Mode.VERIFY)return false;
        PhaseFlow.Mode mode=current.getMode();
        return scan.getPhaseId().equals(current.getPhaseId())&&scan.getGeneration()==current.getGeneration()
            &&(mode==PhaseFlow.Mode.SCAN_PARTS||mode==PhaseFlow.Mode.VERIFY||mode==PhaseFlow.Mode.PHYSICAL_CHECK||mode==PhaseFlow.Mode.COMPLETE);
    }
}
