package dev.chyken.assembl_ar.vision;

/** Native-detail capture is limited to the two installed-part pilots. Other phases
 * keep their existing stream, and low-RAM/unsupported devices keep the safe baseline. */
public final class AssemblyCapturePolicy {
    private AssemblyCapturePolicy() { }
    public static int longEdge(int phase,boolean lowRam,boolean compatibilityFallback){
        return !lowRam&&!compatibilityFallback&&(phase==5||phase==8)?1280:640;
    }
}
