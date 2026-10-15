package dev.chyken.assembl_ar.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** GL-thread-owned material renderer. Recreate after EGL context loss, including texture IDs. */
final class ObjMaterialRenderer {
    private static final String VERTEX =
            "uniform mat4 uMvp; uniform mat4 uNormalMatrix;" +
            "attribute vec3 aPosition; attribute vec3 aNormal; attribute vec2 aUv;" +
            "varying mediump vec3 vNormal; varying mediump vec2 vUv;" +
            "void main(){ vNormal=(uNormalMatrix*vec4(aNormal,0.0)).xyz;" +
            "vUv=aUv; gl_Position=uMvp*vec4(aPosition,1.0); }";
    private static final String FRAGMENT =
            "precision mediump float;" +
            "uniform vec4 uColor; uniform sampler2D uTexture;" +
            "uniform float uTextured; uniform float uLit;" +
            "varying mediump vec3 vNormal; varying mediump vec2 vUv;" +
            "void main(){ vec4 surface=uColor;" +
            "if(uTextured>0.5) surface*=texture2D(uTexture,vUv);" +
            "vec3 n=gl_FrontFacing?normalize(vNormal):-normalize(vNormal);" +
            "float diffuse=max(dot(n,normalize(vec3(-0.35,0.82,0.48))),0.0);" +
            "float ambient=mix(0.34,0.65,uTextured);" +
            "float light=mix(1.0,ambient+(1.0-ambient)*diffuse,uLit);" +
            "gl_FragColor=vec4(surface.rgb*light,surface.a); }";

    private final Context context;
    private final Map<String, Integer> textures = new HashMap<>();
    private final float[] inverse = new float[16];
    private final float[] normalMatrix = new float[16];
    private final float[] materialColor = new float[4];
    private final int program;
    private final int position, normal, uv, mvp, normalTransform, color, textured, lit, sampler;

    ObjMaterialRenderer(Context context) {
        this.context = context;
        program = createProgram();
        if (program == 0) {
            position = normal = uv = mvp = normalTransform = color = textured = lit = sampler = -1;
            return;
        }
        position = GLES20.glGetAttribLocation(program, "aPosition");
        normal = GLES20.glGetAttribLocation(program, "aNormal");
        uv = GLES20.glGetAttribLocation(program, "aUv");
        mvp = GLES20.glGetUniformLocation(program, "uMvp");
        normalTransform = GLES20.glGetUniformLocation(program, "uNormalMatrix");
        color = GLES20.glGetUniformLocation(program, "uColor");
        textured = GLES20.glGetUniformLocation(program, "uTextured");
        lit = GLES20.glGetUniformLocation(program, "uLit");
        sampler = GLES20.glGetUniformLocation(program, "uTexture");
    }

    boolean isReady() { return program != 0; }

    int uploadedTextureCount() {
        int count = 0;
        for (Integer id : textures.values()) if (id != 0) count++;
        return count;
    }

    void prepare(ObjModel mesh) {
        if (mesh == null || program == 0) return;
        for (ObjModel.Batch batch : mesh.batches()) {
            if (batch.hasTextureCoordinates && batch.material.texturePath != null)
                texture(batch.material.texturePath);
        }
    }

    void draw(ObjModel mesh, float[] matrix, float[] model, float[] tint, boolean lighting) {
        if (mesh == null || program == 0) return;
        GLES20.glUseProgram(program);
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 12, mesh.vertices());
        GLES20.glEnableVertexAttribArray(normal);
        GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 12, mesh.normals());
        GLES20.glEnableVertexAttribArray(uv);
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 8, mesh.textureCoordinates());
        GLES20.glUniformMatrix4fv(mvp, 1, false, matrix, 0);
        if (Matrix.invertM(inverse, 0, model, 0)) Matrix.transposeM(normalMatrix, 0, inverse, 0);
        else Matrix.setIdentityM(normalMatrix, 0);
        GLES20.glUniformMatrix4fv(normalTransform, 1, false, normalMatrix, 0);
        GLES20.glUniform1f(lit, lighting ? 1f : 0f);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform1i(sampler, 0);
        for (ObjModel.Batch batch : mesh.batches()) {
            int texture = batch.hasTextureCoordinates && batch.material.texturePath != null
                    ? texture(batch.material.texturePath) : 0;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glUniform1f(textured, texture == 0 ? 0f : 1f);
            for (int i = 0; i < 4; i++) materialColor[i] = batch.material.color[i] * tint[i];
            GLES20.glUniform4fv(color, 1, materialColor, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, batch.firstVertex, batch.vertexCount);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glDisableVertexAttribArray(uv);
        GLES20.glDisableVertexAttribArray(normal);
        GLES20.glDisableVertexAttribArray(position);
    }

    private int texture(String path) {
        Integer cached = textures.get(path);
        if (cached != null) return cached;
        int result = upload(path);
        textures.put(path, result);
        return result;
    }

    private int upload(String path) {
        Bitmap bitmap = null;
        int[] id = new int[1];
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream input = context.getAssets().open(path)) {
                BitmapFactory.decodeStream(input, null, options);
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) return 0;
            int[] maximum = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maximum, 0);
            int limit = Math.min(2048, maximum[0]);
            if (limit <= 0) return 0;
            options.inSampleSize = sampleSize(options.outWidth, options.outHeight, limit);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false;
            try (InputStream input = context.getAssets().open(path)) {
                bitmap = BitmapFactory.decodeStream(input, null, options);
            }
            if (bitmap == null) return 0;
            GLES20.glGenTextures(1, id, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
            // Clamp + linear (no mipmaps) keeps arbitrary photo dimensions legal on GLES2.
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                GLES20.glDeleteTextures(1, id, 0);
                return 0;
            }
            return id[0];
        } catch (IOException | IllegalArgumentException ignored) {
            if (id[0] != 0) GLES20.glDeleteTextures(1, id, 0);
            return 0;
        } finally {
            if (bitmap != null) bitmap.recycle();
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
    }

    static int sampleSize(int width, int height, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("Texture limit must be positive");
        int sample = 1;
        while (Math.ceil(width / (double) sample) > limit
                || Math.ceil(height / (double) sample) > limit) sample *= 2;
        return sample;
    }

    private static int createProgram() {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
        int fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
        if (vertex == 0 || fragment == 0) {
            if (vertex != 0) GLES20.glDeleteShader(vertex);
            if (fragment != 0) GLES20.glDeleteShader(fragment);
            return 0;
        }
        int result = GLES20.glCreateProgram();
        GLES20.glAttachShader(result, vertex);
        GLES20.glAttachShader(result, fragment);
        GLES20.glLinkProgram(result);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] != 0) return result;
        GLES20.glDeleteProgram(result);
        return 0;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] != 0) return shader;
        GLES20.glDeleteShader(shader);
        return 0;
    }
}
