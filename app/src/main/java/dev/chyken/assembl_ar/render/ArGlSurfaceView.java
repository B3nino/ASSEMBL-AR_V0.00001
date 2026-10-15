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

    public ArGlSurfaceView(@NonNull Context context) {
        this(context, null);
    }

    public ArGlSurfaceView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setZOrderOnTop(true);
        setPreserveEGLContextOnPause(true);
        arRenderer = new ArRenderer(context.getApplicationContext());
        setRenderer(arRenderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setClickable(false);
        setFocusable(false);
    }

    public void showGuidance(String componentId, List<OverlayType> overlayTypes,
                             @Nullable float[] openGlViewMatrix) {
        arRenderer.update(componentId,
                overlayTypes == null ? Collections.emptyList() : overlayTypes,
                openGlViewMatrix);
    }

    /**
     * Shows authored component-local pins, connection routes, target markers, and ghost geometry.
     * A projection previously supplied through {@link #setCameraProjection(ArCameraProjection)}
     * remains active.
     */
    public void showGuidance(@NonNull ArGuidance guidance) {
        arRenderer.update(guidance);
    }

    /** Updates authored guidance and the camera projection used to register it. */
    public void showGuidance(@NonNull ArGuidance guidance,
                             @Nullable ArCameraProjection cameraProjection) {
        arRenderer.setCameraProjection(cameraProjection);
        arRenderer.update(guidance);
    }

    /**
     * Sets intrinsics for the exact analyzed image coordinate system. Pass {@code null} to use
     * the backward-compatible 48-degree perspective projection.
     */
    public void setCameraProjection(@Nullable ArCameraProjection cameraProjection) {
        arRenderer.setCameraProjection(cameraProjection);
    }

    public void hideGuidance() {
        arRenderer.hide();
    }
}
