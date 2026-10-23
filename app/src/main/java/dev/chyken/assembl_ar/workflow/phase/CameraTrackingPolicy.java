package dev.chyken.assembl_ar.workflow.phase;
/** Visual ownership is distinct from accepting new verification evidence. */
public final class CameraTrackingPolicy {
    private CameraTrackingPolicy(){}
    public static boolean focused(int phase,PhaseFlow.Mode mode,boolean previewing){
        return !previewing&&(mode==PhaseFlow.Mode.SCAN_PARTS||mode==PhaseFlow.Mode.VERIFY||phase==1&&mode==PhaseFlow.Mode.COMPLETE);
    }
}
