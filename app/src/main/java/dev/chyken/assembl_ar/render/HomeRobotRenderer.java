package dev.chyken.assembl_ar.render;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import androidx.annotation.Nullable;

import java.nio.FloatBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** OpenGL ES 2 renderer for the assembled NEOBOT shown on the home screen. */
final class HomeRobotRenderer implements GLSurfaceView.Renderer {
    private static final float BASE_VERTICAL_FIELD_OF_VIEW_DEGREES = 38f;
    // A pale brand-green platform ties the model to the app identity without
    // recoloring physical robot parts that users must recognize accurately.
    private static final float[] SHADOW = {0.84f, 0.93f, 0.80f, 0.52f};
    private static final float[] MATERIAL_COLOR = {1f, 1f, 1f, 1f};

    private static final String VERTEX_SHADER =
            "uniform mat4 uMvp;" +
             "uniform mat4 uModel;" +
             "attribute vec3 aPosition;" +
             "attribute vec3 aNormal;" +
             "varying mediump vec3 vNormal;" +
             "void main(){" +
             " vNormal = normalize((uModel * vec4(aNormal, 0.0)).xyz);" +
             " gl_Position = uMvp * vec4(aPosition, 1.0);" +
             "}";
    private static final String FRAGMENT_SHADER =
             "precision mediump float;" +
             "uniform vec4 uColor;" +
             "varying mediump vec3 vNormal;" +
             "void main(){" +
             " vec3 normal = gl_FrontFacing ? normalize(vNormal) : -normalize(vNormal);" +
             " vec3 lightDirection = normalize(vec3(-0.35, 0.82, 0.48));" +
             " float vLight = 0.34 + 0.66 * max(dot(normal, lightDirection), 0.0);" +
             " gl_FragColor = vec4(uColor.rgb * vLight, uColor.a);" +
             "}";

    private final Context context;
    private final OrbitController orbitController;
    @Nullable private final String previewComponentId;
    private volatile boolean showBack;
    private Runnable firstFrame;
    private boolean frameDelivered;
    void setOnFirstFrame(Runnable listener){firstFrame=listener;}
    private final float[] projection = new float[16];
    private final float[] view = new float[16];
    private final float[] model = new float[16];
    private final float[] viewModel = new float[16];
    private final float[] mvp = new float[16];

    private int program;
    private int positionHandle;
    private int normalHandle;
    private int mvpHandle;
    private int modelHandle;
    private int colorHandle;
    @Nullable private ObjModel boardModel;
    @Nullable private ObjMaterialRenderer materialRenderer;
    private final Map<String, ObjModel> assemblyModels = new LinkedHashMap<>();
    @Nullable private ObjModel wiringModel;
    @Nullable private ObjModel componentModel;
    @Nullable private ObjModel companionModel;
    @Nullable private ObjModel spacerModel;
    private float componentScale = 1f;

    HomeRobotRenderer(Context context, OrbitController orbitController) {
        this(context, orbitController, null);
    }

    HomeRobotRenderer(Context context, OrbitController orbitController, @Nullable String previewComponentId) {
        this.context = context;
        this.orbitController = orbitController;
        this.previewComponentId = previewComponentId;
    }

    void showBack(boolean back) { showBack = back; }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        frameDelivered=false;
        if (previewComponentId == null) GLES20.glClearColor(1f, 1f, 1f, 1f);
        else GLES20.glClearColor(.94f, .94f, .94f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        normalHandle = GLES20.glGetAttribLocation(program, "aNormal");
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvp");
        modelHandle = GLES20.glGetUniformLocation(program, "uModel");
        colorHandle = GLES20.glGetUniformLocation(program, "uColor");

        materialRenderer = new ObjMaterialRenderer(context);
        if (previewComponentId == null) {
            if(boardModel==null)boardModel = ObjModel.load(context, "models/board_ghost.obj");
            for (HomeRobotAssembly.Part part : HomeRobotAssembly.PARTS) {
                if (!assemblyModels.containsKey(part.assetPath)) {
                    ObjModel mesh = ObjModel.load(context, part.assetPath);
                    assemblyModels.put(part.assetPath, mesh);

                }
            }
            for(ObjModel mesh:assemblyModels.values())materialRenderer.prepare(mesh);
            if(wiringModel==null)wiringModel = ObjModel.load(context, HomeRobotAssembly.WIRING_ASSET);
            materialRenderer.prepare(wiringModel);
        } else {
            // Only decode the selected mesh and its materials when opening a library preview.
            String path = ComponentModelCatalog.assetPath(previewComponentId);
            if(componentModel==null)componentModel = path == null ? null : ObjModel.load(context, path);
            if (componentModel != null) componentScale = previewScale(componentModel);
            materialRenderer.prepare(componentModel);
            if(ComponentModelCatalog.isFastenerGroup(previewComponentId)){
                if(companionModel==null)companionModel=ObjModel.load(context,"models/hex_nut.obj");materialRenderer.prepare(companionModel);
                if(ComponentModelCatalog.TRACKER_FASTENERS.equals(previewComponentId)){
                    if(spacerModel==null)spacerModel=ObjModel.load(context,ComponentModelCatalog.assetPath(dev.chyken.assembl_ar.data.NeobotSeedData.PLASTIC_SPACER));materialRenderer.prepare(spacerModel);
                }
            }
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        float aspect = width / (float) Math.max(1, height);
        Matrix.perspectiveM(projection, 0,
                calculateVerticalFieldOfViewDegrees(aspect), aspect, 0.1f, 40f);
    }

    /**
     * Keeps at least the same horizontal field of view in narrow split/tablet panes. Without
     * this adjustment, a tall 3D viewport can crop the wheels even though there is ample height.
     */
    static float calculateVerticalFieldOfViewDegrees(float aspect) {
        if (!Float.isFinite(aspect) || aspect <= 0f || aspect >= 1f) {
            return BASE_VERTICAL_FIELD_OF_VIEW_DEGREES;
        }
        double baseHalfRadians = Math.toRadians(BASE_VERTICAL_FIELD_OF_VIEW_DEGREES * 0.5f);
        return (float) Math.toDegrees(2.0 * Math.atan(Math.tan(baseHalfRadians) / aspect));
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (program == 0) {
            return;
        }
        updateCamera();
        GLES20.glUseProgram(program);

        if (previewComponentId != null) {
            if (componentModel == null) return;
            if(companionModel!=null){
                if(spacerModel!=null){
                    drawPreviewPart(componentModel,-1.05f,.44f);
                    drawPreviewPart(companionModel,0,.32f);
                    drawPreviewPart(spacerModel,1.05f,.38f);
                    return;
                }
                drawPreviewPart(componentModel,-.65f,.60f);
                drawPreviewPart(companionModel,.65f,.45f);
                return;
            }
            Matrix.setIdentityM(model, 0);
            Matrix.rotateM(model, 0, -90f, 1f, 0f, 0f);
            // Turn around the model's vertical axis so the named top edge stays at the top.
            if (showBack) Matrix.rotateM(model, 0, 180f, 0f, 1f, 0f);
            Matrix.scaleM(model, 0, componentScale, componentScale, componentScale);
            Matrix.translateM(model, 0, -componentModel.center(0),
                    -componentModel.center(1), -componentModel.center(2));
            draw(componentModel, MATERIAL_COLOR);
            return;
        }

        for (HomeRobotAssembly.Part part : HomeRobotAssembly.PARTS) {
            setBoardFrame();
            Matrix.translateM(model, 0, part.x, part.y, part.z);
            Matrix.rotateM(model, 0, part.rotateZ, 0f, 0f, 1f);
            Matrix.rotateM(model, 0, part.rotateY, 0f, 1f, 0f);
            Matrix.rotateM(model, 0, part.rotateX, 1f, 0f, 0f);
            Matrix.scaleM(model, 0, part.scale, part.scale, part.scale);
            draw(assemblyModels.get(part.assetPath), MATERIAL_COLOR);
        }
        setBoardFrame();
        draw(wiringModel, MATERIAL_COLOR);

        // Draw the translucent ground last without writing depth. It can receive opaque robot
        // occlusion, but can never hide later geometry when the camera orbits below the plane.
        if (orbitController.snapshot().pitchDegrees >= 0f) {
            GLES20.glDepthMask(false);
            drawHorizontal(boardModel, SHADOW, 0f, -0.435f, 0.04f, 2.05f, 1.78f, 0.04f);
            GLES20.glDepthMask(true);
        }
        if(!frameDelivered){frameDelivered=true;GLES20.glFinish();if(firstFrame!=null)firstFrame.run();}
    }
    private void drawPreviewPart(ObjModel part,float x,float size){
        OrbitController.Snapshot orbit=orbitController.snapshot();
        // Rotate each labeled part in place so the screw/nut never exchange their label positions.
        Matrix.setIdentityM(model,0);Matrix.translateM(model,0,x,0,0);
        Matrix.rotateM(model,0,orbit.pitchDegrees,1,0,0);Matrix.rotateM(model,0,orbit.yawDegrees,0,1,0);Matrix.rotateM(model,0,-90f,1,0,0);
        float scale=previewScale(part)*size;Matrix.scaleM(model,0,scale,scale,scale);
        Matrix.translateM(model,0,-part.center(0),-part.center(1),-part.center(2));draw(part,MATERIAL_COLOR);
    }

    private void updateCamera() {
        OrbitController.Snapshot orbit = orbitController.snapshot();
        if(companionModel!=null){Matrix.setLookAtM(view,0,0,0,orbit.distance,0,0,0,0,1,0);return;}
        double yaw = Math.toRadians(orbit.yawDegrees);
        double pitch = Math.toRadians(orbit.pitchDegrees);
        float horizontalDistance = orbit.distance * (float) Math.cos(pitch);
        float targetY = previewComponentId == null ? 0.35f : 0f;
        float eyeX = horizontalDistance * (float) Math.sin(yaw);
        float eyeY = targetY + orbit.distance * (float) Math.sin(pitch);
        float eyeZ = horizontalDistance * (float) Math.cos(yaw);
        Matrix.setLookAtM(view, 0,
                eyeX, eyeY, eyeZ,
                0f, targetY, 0f,
                0f, 1f, 0f);
    }

    /** Fits any component in the initial 38-degree camera frustum, with room to orbit it. */
    static float previewScale(ObjModel mesh) {
        float radius = mesh.boundingRadius();
        return Float.isFinite(radius) && radius > 0f ? 1.70f / radius : 1f;
    }

    private void drawHorizontal(@Nullable ObjModel mesh, float[] color,
                                float x, float y, float z,
                                float scaleX, float scaleZ, float scaleY) {
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, x, y, z);
        Matrix.rotateM(model, 0, -90f, 1f, 0f, 0f);
        Matrix.scaleM(model, 0, scaleX, scaleZ, scaleY);
        draw(mesh, color);
    }

    /** Face the front/LED edge toward the initial camera, with populated faces upward. */
    private void setBoardFrame() {
        Matrix.setIdentityM(model, 0);
        Matrix.rotateM(model, 0, 180f, 0f, 1f, 0f);
        Matrix.rotateM(model, 0, -90f, 1f, 0f, 0f);
    }

    private void draw(@Nullable ObjModel mesh, float[] color) {
        if (mesh == null) {
            return;
        }
        Matrix.multiplyMM(viewModel, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0);
        if (materialRenderer != null && materialRenderer.isReady()) {
            // Material colors are physical appearances, not UI palette tinting. Legacy meshes
            // (including the ground shadow) still receive their original fallback color.
            boolean authored = false;
            for (ObjModel.Batch batch : mesh.batches()) {
                if (!batch.material.name.isEmpty()) { authored = true; break; }
            }
            materialRenderer.draw(mesh, mvp, model, authored ? MATERIAL_COLOR : color, true);
            return;
        }
        GLES20.glUseProgram(program);
        FloatBuffer vertices = mesh.vertices();
        FloatBuffer normals = mesh.normals();
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT,
                false, 3 * Float.BYTES, vertices);
        GLES20.glEnableVertexAttribArray(normalHandle);
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT,
                false, 3 * Float.BYTES, normals);
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(modelHandle, 1, false, model, 0);
        GLES20.glUniform4fv(colorHandle, 1, color, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount());
        GLES20.glDisableVertexAttribArray(normalHandle);
        GLES20.glDisableVertexAttribArray(positionHandle);
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (vertexShader == 0 || fragmentShader == 0) {
            return 0;
        }
        int result = GLES20.glCreateProgram();
        GLES20.glAttachShader(result, vertexShader);
        GLES20.glAttachShader(result, fragmentShader);
        GLES20.glLinkProgram(result);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linked, 0);
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        if (linked[0] == 0) {
            GLES20.glDeleteProgram(result);
            return 0;
        }
        return result;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
