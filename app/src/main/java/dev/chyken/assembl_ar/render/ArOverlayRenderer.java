package dev.chyken.assembl_ar.render;
import dev.chyken.assembl_ar.vision.RecognitionResult;
/** Applies visual-only tracking without replacing authored guidance or advancing a phase gate. */
public final class ArOverlayRenderer {
    private ArOverlayRenderer(){}
    public static void track(ArGlSurfaceView view,RecognitionResult.SpatialAnchor anchor){if(anchor==null)view.hideGuidance();else view.updateTrackedPose(anchor.getPose().getOpenGlModelViewMatrix());}
}
