package dev.chyken.assembl_ar.render;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import dev.chyken.assembl_ar.model.OverlayType;
import dev.chyken.assembl_ar.data.NeobotSeedData;

final class ArRenderer implements GLSurfaceView.Renderer {
    private static final float NORMALIZED_CAMERA_DISTANCE = 3.1f;
    private static final ArGuidance.Point3 ORIGIN = ArGuidance.Point3.at(0f, 0f, 0f);
    private static final float[] BRAND_GREEN = {
            104f / 255f, 184f / 255f, 40f / 255f, 0.96f};
    private static final float[] GUIDANCE_WHITE = {1f, 1f, 1f, 0.96f};
    private static final float[] RED = {1f, 0.22f, 0.18f, 0.92f};
    private static final float[] BLACK_WIRE = {0.72f, 0.77f, 0.82f, 0.96f};
    private static final float[] BLUE_WIRE = {0.22f, 0.55f, 1f, 0.96f};
    private static final float[] PURPLE_WIRE = {0.73f, 0.36f, 1f, 0.96f};
    private static final float[] GREEN_WIRE = {0.30f, 0.92f, 0.48f, 0.96f};
    private static final float[] YELLOW_WIRE = {1f, 0.82f, 0.20f, 0.96f};
    private static final float[] GRAY_WIRE = {0.78f, 0.82f, 0.86f, 0.96f};
    private static final float[] RIBBON_WIRE = {1f, 0.55f, 0.18f, 0.96f};
    private static final float[] ROUTE_SHADOW = {0.02f, 0.02f, 0.02f, 0.72f};
    private static final String VERTEX_SHADER =
            "uniform mat4 uMvp;" +
            "attribute vec3 aPosition;" +
            "void main(){ gl_Position = uMvp * vec4(aPosition, 1.0); }";
    private static final String FRAGMENT_SHADER =
            "precision mediump float;" +
            "uniform vec4 uColor;" +
            "void main(){ gl_FragColor = uColor; }";

    private final Context context;
    private final float[] projection = new float[16];
    private final float[] identityPose = new float[16];
    private final float[] model = new float[16];
    private final float[] viewModel = new float[16];
    private final float[] mvp = new float[16];
    private final FloatBuffer ringBuffer;
    private final FloatBuffer gridBuffer;
    private final FloatBuffer crossBuffer;
    private final FloatBuffer diamondBuffer;
    private final FloatBuffer squareBuffer;

    private volatile RenderGuidance guidance;
    @Nullable private volatile ArCameraProjection requestedProjection;
    @Nullable private ArCameraProjection appliedProjection;
    private boolean projectionDirty = true;
    private int surfaceWidth;
    private int surfaceHeight;
    private int program;
    private int positionHandle;
    private int mvpHandle;
    private int colorHandle;
    private ObjModel motorModel;
    private ObjModel boardModel;
    private ObjModel mainBoardModel;
    private ObjModel extensionBoardModel;
    private ObjMaterialRenderer materialRenderer;
    private ObjModel screwModel;
    private ObjModel casterModel;
    private ObjModel wheelModel;
    private ObjModel arrowModel;

    ArRenderer(Context context) {
        this.context = context;
        Matrix.setIdentityM(identityPose, 0);
        Matrix.translateM(identityPose, 0, 0f, 0f, -NORMALIZED_CAMERA_DISTANCE);
        Matrix.rotateM(identityPose, 0, -58f, 1f, 0f, 0f);
        guidance = RenderGuidance.hidden(identityPose);
        ringBuffer = buffer(circleVertices(64, 1f));
        gridBuffer = buffer(gridVertices(6, 1.4f));
        crossBuffer = buffer(new float[]{
                -1f, 0f, 0f, 1f, 0f, 0f,
                0f, -1f, 0f, 0f, 1f, 0f});
        diamondBuffer = buffer(new float[]{
                0f, 1f, 0f, 1f, 0f, 0f, 0f, -1f, 0f, -1f, 0f, 0f});
        squareBuffer = buffer(new float[]{
                -1f, -1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f});
    }

    /** Backward-compatible generic guidance path. */
    void update(String componentId, List<OverlayType> overlayTypes,
                @Nullable float[] openGlViewMatrix) {
        List<OverlayType> safeOverlays = overlayTypes == null
                ? Collections.emptyList() : overlayTypes;
        update(ArGuidance.builder(componentId, safeOverlays)
                .pose(openGlViewMatrix)
                .build());
    }

    void update(ArGuidance authoredGuidance) {
        guidance = RenderGuidance.from(authoredGuidance, identityPose);
    }

    void setCameraProjection(@Nullable ArCameraProjection cameraProjection) {
        requestedProjection = cameraProjection;
        projectionDirty = true;
    }

    void hide() {
        guidance = RenderGuidance.hidden(identityPose);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvp");
        colorHandle = GLES20.glGetUniformLocation(program, "uColor");
        motorModel = ObjModel.load(context, "models/motor_ghost.obj");
        boardModel = ObjModel.load(context, "models/board_ghost.obj");
        mainBoardModel = ObjModel.load(context, "models/main_board.obj");
        extensionBoardModel = ObjModel.load(context, "models/extension_board.obj");
        screwModel = ObjModel.load(context, "models/screw_ghost.obj");
        casterModel = ObjModel.load(context, "models/caster_ghost.obj");
        wheelModel = ObjModel.load(context, "models/wheel_ghost.obj");
        arrowModel = ObjModel.load(context, "models/arrow.obj");
        materialRenderer = new ObjMaterialRenderer(context);
        materialRenderer.prepare(mainBoardModel);
        materialRenderer.prepare(extensionBoardModel);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        GLES20.glViewport(0, 0, width, height);
        projectionDirty = true;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        updateProjectionIfNeeded();
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        RenderGuidance current = guidance;
        if (!current.visible || program == 0) {
            return;
        }
        GLES20.glUseProgram(program);
        EnumSet<OverlayType> overlays = current.overlayTypes;

        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        if (overlays.contains(OverlayType.SPATIAL_HIGHLIGHT)) {
            drawGrid(current);
        }
        if (shouldDrawGhost(current)) {
            drawGhost(current);
        }

        // Camera depth is unavailable, so actionable markers must remain readable over the ghost.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        for (int routeIndex = 0; routeIndex < current.routes.size(); routeIndex++) {
            drawSpatialRoute(current, current.routes.get(routeIndex),
                    routeIndex, current.routes.size());
        }
        drawAuthoredMarkers(current);
        if (current.routes.isEmpty()
                && (overlays.contains(OverlayType.DYNAMIC_ARROW)
                || overlays.contains(OverlayType.POLARITY_ARROW)
                || overlays.contains(OverlayType.ORIENTATION_MARKER))) {
            drawGenericArrow(current);
        }
        if (current.targetPin == null
                && current.targetMarker == ArGuidance.TargetMarker.NONE
                && overlays.contains(OverlayType.TARGET_GLOW_RING)) {
            for (ArGuidance.Point3 target : current.componentTargets) {
                drawRingAt(current, target, current.markerRadius * 1.3f,
                        BRAND_GREEN, true);
            }
        }
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    private void updateProjectionIfNeeded() {
        ArCameraProjection current = requestedProjection;
        if (!projectionDirty && current == appliedProjection) {
            return;
        }
        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            return;
        }
        if (current == null) {
            float aspect = surfaceWidth / (float) surfaceHeight;
            Matrix.perspectiveM(projection, 0, 48f, aspect, 0.05f, 100f);
        } else {
            float[] calibrated = current.createOpenGlMatrix(surfaceWidth, surfaceHeight);
            System.arraycopy(calibrated, 0, projection, 0, projection.length);
        }
        appliedProjection = current;
        projectionDirty = false;
    }

    private boolean shouldDrawGhost(RenderGuidance current) {
        if (current.ghostStyle == ArGuidance.GhostStyle.NONE) {
            return false;
        }
        return current.ghostStyle != ArGuidance.GhostStyle.AUTO
                || current.targetMarker == ArGuidance.TargetMarker.COMPONENT_PLACEMENT
                || current.overlayTypes.contains(OverlayType.GHOST_COMPONENT)
                || current.overlayTypes.contains(OverlayType.PIN_ALIGNMENT);
    }

    private void drawGrid(RenderGuidance current) {
        beginLocalTransform(current, current.componentTarget);
        applyTargetRotation(current, current.targetRotationZ);
        Matrix.scaleM(model, 0,
                0.8f * current.ghostScaleX,
                0.8f * current.ghostScaleY,
                0.8f * current.ghostScaleZ);
        compose(current.pose, model);
        draw(gridBuffer, (6 + 1) * 4, GLES20.GL_LINES,
                new float[]{BRAND_GREEN[0], BRAND_GREEN[1], BRAND_GREEN[2], 0.48f},
                mvp, 1.5f);
    }

    private void drawGhost(RenderGuidance current) {
        ObjModel selected = selectGhost(current);
        if (selected == null) {
            return;
        }
        for (int index = 0; index < current.componentTargets.size(); index++) {
            ArGuidance.Point3 target = current.componentTargets.get(index);
            beginLocalTransform(current, target);
            applyTargetRotation(current, current.targetRotationsZ.get(index));
            boolean detailedBoard = selected == mainBoardModel || selected == extensionBoardModel;
            if (selected == extensionBoardModel) {
                // Manual image-063's canonical top is opposite the model's LED-pad end.
                Matrix.rotateM(model, 0, 180f, 0f, 0f, 1f);
            }
            float pulse = 1f + 0.04f
                    * (float) Math.sin(SystemClock.uptimeMillis() / 240.0);
            if (detailedBoard) {
                // Keep the legacy guide's authored XY footprint, without inflating a PCB
                // into the old box's thickness or altering recognition/pose calibration.
                float x = current.ghostScaleX * 0.70f / selected.halfExtent(0);
                float y = current.ghostScaleY * 0.48f / selected.halfExtent(1);
                Matrix.scaleM(model, 0, x * pulse, y * pulse, Math.min(x, y) * pulse);
            } else {
                Matrix.scaleM(model, 0, current.ghostScaleX * pulse,
                        current.ghostScaleY * pulse, current.ghostScaleZ * pulse);
            }
            compose(current.pose, model);
            if (detailedBoard && materialRenderer != null && materialRenderer.isReady()) {
                materialRenderer.draw(selected, mvp, model, new float[]{1f, 1f, 1f, 0.40f}, false);
                GLES20.glUseProgram(program);
            } else {
                draw(selected.vertices(), selected.vertexCount(), GLES20.GL_TRIANGLES,
                        new float[]{BRAND_GREEN[0], BRAND_GREEN[1], BRAND_GREEN[2], 0.28f},
                        mvp, 1f);
            }
        }
    }

    @Nullable
    private ObjModel selectGhost(RenderGuidance current) {
        if (current.ghostStyle == ArGuidance.GhostStyle.BOARD
                || current.ghostStyle == ArGuidance.GhostStyle.AUTO) {
            if (NeobotSeedData.MAIN_BOARD.equals(current.componentId) && mainBoardModel != null)
                return mainBoardModel;
            if (NeobotSeedData.EXTENSION_BOARD.equals(current.componentId) && extensionBoardModel != null)
                return extensionBoardModel;
        }
        switch (current.ghostStyle) {
            case BOARD:
                return boardModel;
            case MOTOR:
                return motorModel;
            case CASTER:
                return casterModel;
            case WHEEL:
                return wheelModel;
            case FASTENER:
                return screwModel;
            case NONE:
                return null;
            case AUTO:
            default:
                String id = current.componentId.toLowerCase(Locale.US);
                if (id.contains("motor")) {
                    return motorModel;
                }
                if (id.contains("caster")) {
                    return casterModel;
                }
                if (id.contains("wheel")) {
                    return wheelModel;
                }
                if (id.contains("screw") || id.contains("nut")
                        || id.contains("standoff") || id.contains("fastener")) {
                    return screwModel;
                }
                return boardModel;
        }
    }

    private void drawSpatialRoute(RenderGuidance current, RenderRoute route,
                                  int routeIndex, int routeCount) {
        Matrix.setIdentityM(model, 0);
        Matrix.scaleM(model, 0, current.coordinateScaleX,
                current.coordinateScaleY, current.coordinateScaleZ);
        compose(current.pose, model);
        draw(route.buffer, route.points.size(), GLES20.GL_LINE_STRIP,
                ROUTE_SHADOW, mvp, current.routeWidthPixels + 3f);
        draw(route.buffer, route.points.size(), GLES20.GL_LINE_STRIP,
                route.color, mvp, current.routeWidthPixels);

        if (arrowModel == null) {
            return;
        }
        float progress = (SystemClock.uptimeMillis() % 1500L) / 1500f;
        progress = (progress + routeIndex / (float) Math.max(1, routeCount)) % 1f;
        RouteSample sample = sampleRoute(route.points, progress);
        beginLocalTransform(current, sample.point);
        Matrix.translateM(model, 0, 0f, 0f, current.markerRadius * 0.08f);
        Matrix.rotateM(model, 0, sample.yawDegrees, 0f, 0f, 1f);
        Matrix.rotateM(model, 0, sample.pitchDegrees, 0f, 1f, 0f);
        float arrowScale = current.markerRadius * 1.35f;
        Matrix.scaleM(model, 0, arrowScale, arrowScale, arrowScale);
        compose(current.pose, model);
        draw(arrowModel.vertices(), arrowModel.vertexCount(), GLES20.GL_TRIANGLES,
                route.color, mvp, 1f);
    }

    private void drawAuthoredMarkers(RenderGuidance current) {
        if (!current.routes.isEmpty()) {
            for (RenderRoute route : current.routes) {
                ArGuidance.Point3 source = route.points.get(0);
                ArGuidance.Point3 target = route.points.get(route.points.size() - 1);
                drawRingAt(current, source, current.markerRadius, route.color, false);
                drawShapeAt(current, source, current.markerRadius * 0.52f,
                        crossBuffer, 4, GLES20.GL_LINES, route.color, 3f);
                drawTargetMarker(current, target);
            }
            return;
        }

        if (current.sourcePin != null) {
            drawRingAt(current, current.sourcePin, current.markerRadius, GUIDANCE_WHITE, false);
            drawShapeAt(current, current.sourcePin, current.markerRadius * 0.52f,
                    crossBuffer, 4, GLES20.GL_LINES, GUIDANCE_WHITE, 3f);
        }

        if (current.targetPin == null
                && current.targetMarker == ArGuidance.TargetMarker.COMPONENT_PLACEMENT) {
            for (ArGuidance.Point3 target : current.componentTargets) {
                drawRingAt(current, target, current.markerRadius * 1.15f,
                        BRAND_GREEN, true);
                drawShapeAt(current, target, current.markerRadius * 1.5f,
                        squareBuffer, 4, GLES20.GL_LINE_LOOP, BRAND_GREEN, 3f);
            }
            return;
        }

        ArGuidance.Point3 target = current.targetPin;
        if (target == null && current.targetMarker != ArGuidance.TargetMarker.NONE) {
            target = current.componentTarget;
        }
        if (target == null) {
            return;
        }
        drawTargetMarker(current, target);
    }

    private void drawTargetMarker(RenderGuidance current, ArGuidance.Point3 target) {
        drawRingAt(current, target, current.markerRadius * 1.15f, BRAND_GREEN, true);
        switch (current.targetMarker) {
            case PIN:
                drawShapeAt(current, target, current.markerRadius * 0.52f,
                        crossBuffer, 4, GLES20.GL_LINES, BRAND_GREEN, 3f);
                break;
            case CONNECTION:
                drawShapeAt(current, target, current.markerRadius * 0.78f,
                        diamondBuffer, 4, GLES20.GL_LINE_LOOP, BRAND_GREEN, 3f);
                break;
            case COMPONENT_PLACEMENT:
                drawShapeAt(current, target, current.markerRadius * 1.5f,
                        squareBuffer, 4, GLES20.GL_LINE_LOOP, BRAND_GREEN, 3f);
                break;
            case FASTENER_HOLE:
                drawRingAt(current, target, current.markerRadius * 0.58f,
                        GUIDANCE_WHITE, false);
                break;
            case NONE:
            default:
                break;
        }
    }

    private void drawGenericArrow(RenderGuidance current) {
        if (arrowModel == null) {
            return;
        }
        float travel = ((SystemClock.uptimeMillis() % 1200L) / 1200f - 0.5f)
                * current.markerRadius * 2.3f;
        beginLocalTransform(current, current.componentTarget);
        Matrix.translateM(model, 0, travel, 0f, current.markerRadius);
        float scale = current.markerRadius * 5.2f;
        Matrix.scaleM(model, 0, scale, scale, scale);
        compose(current.pose, model);
        boolean polarity = current.overlayTypes.contains(OverlayType.POLARITY_ARROW);
        draw(arrowModel.vertices(), arrowModel.vertexCount(), GLES20.GL_TRIANGLES,
                polarity ? RED : BRAND_GREEN, mvp, 1f);
    }

    private void drawRingAt(RenderGuidance current, ArGuidance.Point3 point,
                            float radius, float[] color, boolean pulse) {
        beginLocalTransform(current, point);
        Matrix.translateM(model, 0, 0f, 0f, radius * 0.08f);
        float animatedRadius = pulse
                ? radius * (1f + 0.09f * (float) Math.sin(SystemClock.uptimeMillis() / 180.0))
                : radius;
        Matrix.scaleM(model, 0, animatedRadius, animatedRadius, animatedRadius);
        compose(current.pose, model);
        draw(ringBuffer, 64, GLES20.GL_LINE_LOOP, color, mvp, pulse ? 5f : 3.5f);
    }

    private void drawShapeAt(RenderGuidance current, ArGuidance.Point3 point,
                             float radius, FloatBuffer vertices, int count,
                             int mode, float[] color, float lineWidth) {
        beginLocalTransform(current, point);
        Matrix.translateM(model, 0, 0f, 0f, radius * 0.15f);
        Matrix.scaleM(model, 0, radius, radius, radius);
        compose(current.pose, model);
        draw(vertices, count, mode, color, mvp, lineWidth);
    }

    /** Starts a model matrix as physical-unit scaling followed by local authored translation. */
    private void beginLocalTransform(RenderGuidance current, ArGuidance.Point3 point) {
        Matrix.setIdentityM(model, 0);
        Matrix.scaleM(model, 0, current.coordinateScaleX,
                current.coordinateScaleY, current.coordinateScaleZ);
        Matrix.translateM(model, 0, point.x, point.y, point.z);
    }

    private void applyTargetRotation(RenderGuidance current, float rotationZ) {
        Matrix.rotateM(model, 0, rotationZ, 0f, 0f, 1f);
        Matrix.rotateM(model, 0, current.targetRotationY, 0f, 1f, 0f);
        Matrix.rotateM(model, 0, current.targetRotationX, 1f, 0f, 0f);
    }

    private void compose(float[] pose, float[] modelMatrix) {
        Matrix.multiplyMM(viewModel, 0, pose, 0, modelMatrix, 0);
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0);
    }

    private void draw(FloatBuffer vertices, int count, int mode, float[] color,
                      float[] matrix, float lineWidth) {
        vertices.position(0);
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT,
                false, 3 * Float.BYTES, vertices);
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, matrix, 0);
        GLES20.glUniform4fv(colorHandle, 1, color, 0);
        GLES20.glLineWidth(lineWidth);
        GLES20.glDrawArrays(mode, 0, count);
        GLES20.glDisableVertexAttribArray(positionHandle);
    }

    private static boolean validMatrix(@Nullable float[] matrix) {
        if (matrix == null || matrix.length != 16) {
            return false;
        }
        for (float value : matrix) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Normalizes a physical solvePnP scene without changing its projected image coordinates.
     * POSE_UNITS geometry receives the same factor, preserving R*p+t up to a uniform scale.
     */
    private static RenderPose normalizePhysicalPose(float[] source) {
        float[] normalized = source.clone();
        float depth = Math.abs(normalized[14]);
        float factor = 1f;
        if (depth > 10f) {
            factor = NORMALIZED_CAMERA_DISTANCE / depth;
            normalized[12] *= factor;
            normalized[13] *= factor;
            normalized[14] *= factor;
        }
        return new RenderPose(normalized, factor);
    }

    private static RouteSample sampleRoute(List<ArGuidance.Point3> points, float progress) {
        float total = 0f;
        for (int index = 1; index < points.size(); index++) {
            total += distance(points.get(index - 1), points.get(index));
        }
        if (!(total > 0f)) {
            return new RouteSample(points.get(0), 0f, 0f);
        }
        float remaining = total * (0.08f + 0.84f * progress);
        for (int index = 1; index < points.size(); index++) {
            ArGuidance.Point3 start = points.get(index - 1);
            ArGuidance.Point3 end = points.get(index);
            float segment = distance(start, end);
            if (remaining <= segment || index == points.size() - 1) {
                float amount = segment > 0f ? Math.min(1f, remaining / segment) : 0f;
                ArGuidance.Point3 sampled = ArGuidance.Point3.at(
                        start.x + (end.x - start.x) * amount,
                        start.y + (end.y - start.y) * amount,
                        start.z + (end.z - start.z) * amount);
                float deltaX = end.x - start.x;
                float deltaY = end.y - start.y;
                float deltaZ = end.z - start.z;
                float yaw = (float) Math.toDegrees(Math.atan2(deltaY, deltaX));
                float horizontal = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                float pitch = (float) -Math.toDegrees(Math.atan2(deltaZ, horizontal));
                return new RouteSample(sampled, yaw, pitch);
            }
            remaining -= segment;
        }
        return new RouteSample(points.get(points.size() - 1), 0f, 0f);
    }

    private static float distance(ArGuidance.Point3 first, ArGuidance.Point3 second) {
        float x = second.x - first.x;
        float y = second.y - first.y;
        float z = second.z - first.z;
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static FloatBuffer buffer(float[] values) {
        FloatBuffer result = ByteBuffer.allocateDirect(values.length * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        result.put(values).position(0);
        return result;
    }

    private static float[] circleVertices(int segments, float radius) {
        float[] vertices = new float[segments * 3];
        for (int index = 0; index < segments; index++) {
            double angle = Math.PI * 2.0 * index / segments;
            vertices[index * 3] = (float) Math.cos(angle) * radius;
            vertices[index * 3 + 1] = (float) Math.sin(angle) * radius;
            vertices[index * 3 + 2] = 0f;
        }
        return vertices;
    }

    private static float[] gridVertices(int divisions, float size) {
        List<Float> values = new ArrayList<>();
        for (int index = 0; index <= divisions; index++) {
            float offset = -size / 2f + size * index / divisions;
            Collections.addAll(values, -size / 2f, offset, 0f, size / 2f, offset, 0f);
            Collections.addAll(values, offset, -size / 2f, 0f, offset, size / 2f, 0f);
        }
        float[] result = new float[values.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = values.get(index);
        }
        return result;
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int result = GLES20.glCreateProgram();
        GLES20.glAttachShader(result, vertex);
        GLES20.glAttachShader(result, fragment);
        GLES20.glLinkProgram(result);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        return result;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }

    private static final class RenderPose {
        final float[] matrix;
        final float physicalUnitScale;

        RenderPose(float[] matrix, float physicalUnitScale) {
            this.matrix = matrix;
            this.physicalUnitScale = physicalUnitScale;
        }
    }

    private static final class RouteSample {
        final ArGuidance.Point3 point;
        final float yawDegrees;
        final float pitchDegrees;

        RouteSample(ArGuidance.Point3 point, float yawDegrees, float pitchDegrees) {
            this.point = point;
            this.yawDegrees = yawDegrees;
            this.pitchDegrees = pitchDegrees;
        }
    }

    private static final class RenderRoute {
        final List<ArGuidance.Point3> points;
        final FloatBuffer buffer;
        final float[] color;

        private RenderRoute(List<ArGuidance.Point3> points, FloatBuffer buffer, float[] color) {
            this.points = points;
            this.buffer = buffer;
            this.color = color;
        }

        static RenderRoute from(ArGuidance.WireRoute route, boolean polarity) {
            List<ArGuidance.Point3> points = Collections.unmodifiableList(
                    new ArrayList<>(route.getPoints()));
            float[] vertices = new float[points.size() * 3];
            for (int index = 0; index < points.size(); index++) {
                ArGuidance.Point3 point = points.get(index);
                vertices[index * 3] = point.x;
                vertices[index * 3 + 1] = point.y;
                vertices[index * 3 + 2] = point.z;
            }
            return new RenderRoute(points, buffer(vertices),
                    routeColor(route.getWireColor(), polarity));
        }
    }

    private static float[] routeColor(String authoredColor, boolean polarity) {
        String color = authoredColor == null ? "" : authoredColor.toLowerCase(Locale.US);
        if (color.contains("red")) {
            return RED;
        }
        if (color.contains("black")) {
            return BLACK_WIRE;
        }
        if (color.contains("blue")) {
            return BLUE_WIRE;
        }
        if (color.contains("purple")) {
            return PURPLE_WIRE;
        }
        if (color.contains("green")) {
            return GREEN_WIRE;
        }
        if (color.contains("yellow")) {
            return YELLOW_WIRE;
        }
        if (color.contains("gray") || color.contains("grey")) {
            return GRAY_WIRE;
        }
        if (color.contains("ribbon")) {
            return RIBBON_WIRE;
        }
        return polarity ? RED : BRAND_GREEN;
    }

    private static final class RenderGuidance {
        final boolean visible;
        final String componentId;
        final EnumSet<OverlayType> overlayTypes;
        final float[] pose;
        final float coordinateScaleX;
        final float coordinateScaleY;
        final float coordinateScaleZ;
        final ArGuidance.GhostStyle ghostStyle;
        final ArGuidance.TargetMarker targetMarker;
        @Nullable final ArGuidance.Point3 sourcePin;
        @Nullable final ArGuidance.Point3 targetPin;
        final ArGuidance.Point3 componentTarget;
        final List<ArGuidance.Point3> componentTargets;
        final List<RenderRoute> routes;
        final float markerRadius;
        final float routeWidthPixels;
        final float ghostScaleX;
        final float ghostScaleY;
        final float ghostScaleZ;
        final float targetRotationX;
        final float targetRotationY;
        final float targetRotationZ;
        final List<Float> targetRotationsZ;

        private RenderGuidance(boolean visible, String componentId,
                               EnumSet<OverlayType> overlayTypes, float[] pose,
                               float coordinateScaleX, float coordinateScaleY,
                               float coordinateScaleZ, ArGuidance.GhostStyle ghostStyle,
                               ArGuidance.TargetMarker targetMarker,
                               @Nullable ArGuidance.Point3 sourcePin,
                               @Nullable ArGuidance.Point3 targetPin,
                               ArGuidance.Point3 componentTarget,
                               List<ArGuidance.Point3> componentTargets,
                               List<RenderRoute> routes, float markerRadius,
                               float routeWidthPixels, float ghostScaleX,
                               float ghostScaleY, float ghostScaleZ,
                               float targetRotationX, float targetRotationY,
                               float targetRotationZ, List<Float> targetRotationsZ) {
            this.visible = visible;
            this.componentId = componentId;
            this.overlayTypes = overlayTypes;
            this.pose = pose;
            this.coordinateScaleX = coordinateScaleX;
            this.coordinateScaleY = coordinateScaleY;
            this.coordinateScaleZ = coordinateScaleZ;
            this.ghostStyle = ghostStyle;
            this.targetMarker = targetMarker;
            this.sourcePin = sourcePin;
            this.targetPin = targetPin;
            this.componentTarget = componentTarget;
            this.componentTargets = componentTargets;
            this.routes = routes;
            this.markerRadius = markerRadius;
            this.routeWidthPixels = routeWidthPixels;
            this.ghostScaleX = ghostScaleX;
            this.ghostScaleY = ghostScaleY;
            this.ghostScaleZ = ghostScaleZ;
            this.targetRotationX = targetRotationX;
            this.targetRotationY = targetRotationY;
            this.targetRotationZ = targetRotationZ;
            this.targetRotationsZ = targetRotationsZ;
        }

        static RenderGuidance from(ArGuidance guidance, float[] fallbackPose) {
            float[] suppliedPose = guidance.getOpenGlModelViewMatrix();
            boolean hasPhysicalPose = validMatrix(suppliedPose);
            RenderPose renderPose = normalizePhysicalPose(
                    hasPhysicalPose ? suppliedPose : fallbackPose);
            float coordinateScaleX;
            float coordinateScaleY;
            float coordinateScaleZ;
            if (guidance.getCoordinateSpace() == ArGuidance.CoordinateSpace.POSE_UNITS) {
                coordinateScaleX = renderPose.physicalUnitScale;
                coordinateScaleY = renderPose.physicalUnitScale;
                coordinateScaleZ = renderPose.physicalUnitScale;
            } else if (hasPhysicalPose) {
                float halfWidth = guidance.getReferencePlaneWidthUnits() * 0.5f;
                float halfHeight = guidance.getReferencePlaneHeightUnits() * 0.5f;
                coordinateScaleX = halfWidth * renderPose.physicalUnitScale;
                coordinateScaleY = halfHeight * renderPose.physicalUnitScale;
                coordinateScaleZ = Math.min(halfWidth, halfHeight)
                        * renderPose.physicalUnitScale;
            } else {
                // Preview-only guidance uses the renderer's normalized fallback camera.
                coordinateScaleX = 1f;
                coordinateScaleY = 1f;
                coordinateScaleZ = 1f;
            }

            boolean polarity = guidance.getOverlayTypes().contains(OverlayType.POLARITY_ARROW);
            List<RenderRoute> routes = new ArrayList<>(guidance.getWireRoutes().size());
            for (ArGuidance.WireRoute route : guidance.getWireRoutes()) {
                routes.add(RenderRoute.from(route, polarity));
            }
            List<RenderRoute> immutableRoutes = Collections.unmodifiableList(routes);
            List<ArGuidance.Point3> targets = guidance.getComponentTargets().isEmpty()
                    ? Collections.singletonList(ORIGIN)
                    : guidance.getComponentTargets();
            List<ArGuidance.Point3> immutableTargets = Collections.unmodifiableList(
                    new ArrayList<>(targets));
            List<Float> targetRotations = new ArrayList<>(immutableTargets.size());
            for (int index = 0; index < immutableTargets.size(); index++) {
                targetRotations.add(guidance.getComponentTargetRotationZ(index));
            }
            List<Float> immutableTargetRotations = Collections.unmodifiableList(targetRotations);
            return new RenderGuidance(true, guidance.getComponentId(),
                    guidance.getOverlayTypes(), renderPose.matrix,
                    coordinateScaleX, coordinateScaleY, coordinateScaleZ,
                    guidance.getGhostStyle(), guidance.getTargetMarker(),
                    guidance.getSourcePin(), guidance.getTargetPin(),
                    immutableTargets.get(0), immutableTargets,
                    immutableRoutes, guidance.getMarkerRadius(),
                    guidance.getRouteWidthPixels(), guidance.getGhostScaleX(),
                    guidance.getGhostScaleY(), guidance.getGhostScaleZ(),
                    guidance.getTargetRotationX(), guidance.getTargetRotationY(),
                    guidance.getTargetRotationZ(), immutableTargetRotations);
        }

        static RenderGuidance hidden(float[] fallbackPose) {
            return new RenderGuidance(false, "", EnumSet.noneOf(OverlayType.class),
                    fallbackPose.clone(), 1f, 1f, 1f, ArGuidance.GhostStyle.NONE,
                    ArGuidance.TargetMarker.NONE, null, null, ORIGIN,
                    Collections.singletonList(ORIGIN),
                    Collections.emptyList(), 0.14f,
                    4f, 1f, 1f, 1f, 0f, 0f, 0f,
                    Collections.singletonList(0f));
        }
    }
}
