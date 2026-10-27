package dev.chyken.assembl_ar.vision;

/** Camera work limits, independent of matching thresholds and workflow checkpoints. */
public final class LiveVisionPolicy {
    public static final int MAX_ANALYSIS_EDGE=640,MAX_ROI_KEYPOINTS=300;
    public static final int ADAPTIVE_DETAIL_KEYPOINTS=1200;
    public static final long DETECTOR_INTERVAL_NS=33_000_000L;
    private LiveVisionPolicy() { }
    static boolean needsDetection(boolean verificationSuspended,boolean trackedTarget){return !verificationSuspended||!trackedTarget;}
    static boolean scoped(RecognitionRequest r){return r!=null&&r.getExpectation()!=null;}
    static boolean acceptsIdentity(ComponentReferenceSpec s,RecognitionRequest r){
        if(!scoped(r))return true;
        return s.getComponentId().equals(r.getExpectation().getComponentId())
                ||s.getComponentId().equals(r.getSpatialAnchorComponentId());
    }
}
