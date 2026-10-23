package dev.chyken.assembl_ar.render;

import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.model.OverlayType;

/** Transparent OpenGL ES layer rendered directly over the CameraX preview. */
public final class ArGlSurfaceView extends GLSurfaceView {
    private final ArRenderer arRenderer;
    private boolean dynamicOutlinesOnly;
    public void useDynamicOutlines(){hideGuidance();dynamicOutlinesOnly=true;}

    public ArGlSurfaceView(@NonNull Context context) {
        this(context, null);
    }

    public ArGlSurfaceView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setZOrderOnTop(true);
        setPreserveEGLContextOnPause(false);
        arRenderer = new ArRenderer(context.getApplicationContext());
        setRenderer(arRenderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        setClickable(false);
        setFocusable(false);
    }

    public void showGuidance(String componentId, List<OverlayType> overlayTypes,
                             @Nullable float[] openGlViewMatrix) {
        if(dynamicOutlinesOnly)return;
        arRenderer.update(componentId,
                overlayTypes == null ? Collections.emptyList() : overlayTypes,
                openGlViewMatrix);
        requestRender();
    }

    /**
     * Shows authored component-local pins, connection routes, target markers, and ghost geometry.
     * A projection previously supplied through {@link #setCameraProjection(ArCameraProjection)}
     * remains active.
     */
    private volatile ArGuidance retainedGuidance;
    private volatile boolean guidanceVisible;
    public void showGuidance(@NonNull ArGuidance guidance) {
        if(dynamicOutlinesOnly)return;
        retainedGuidance=guidance;guidanceVisible=true;
        arRenderer.update(guidance);
        requestRender();
    }

    /** Updates authored guidance and the camera projection used to register it. */
    public void showGuidance(@NonNull ArGuidance guidance,
                             @Nullable ArCameraProjection cameraProjection) {
        if(dynamicOutlinesOnly)return;
        retainedGuidance=guidance;guidanceVisible=true;
        arRenderer.setCameraProjection(cameraProjection);
        arRenderer.update(guidance);
        requestRender();
    }

    /**
     * Sets intrinsics for the exact analyzed image coordinate system. Pass {@code null} to use
     * the backward-compatible 48-degree perspective projection.
     */
    public void setCameraProjection(@Nullable ArCameraProjection cameraProjection) {
        arRenderer.setCameraProjection(cameraProjection);
        requestRender();
    }

    public void updateTrackedPose(float[] matrix){if(dynamicOutlinesOnly)return;ArGuidance authored=retainedGuidance;if(!guidanceVisible&&authored!=null){arRenderer.update(authored);guidanceVisible=true;}arRenderer.updateTrackedPose(matrix);requestRender();}
    public void hideGuidance() {
        if(dynamicOutlinesOnly&&!guidanceVisible)return;
        guidanceVisible=false;
        arRenderer.hide();
        requestRender();
    }
}
