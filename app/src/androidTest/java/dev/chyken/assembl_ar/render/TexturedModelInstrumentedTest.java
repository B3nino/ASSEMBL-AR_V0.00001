package dev.chyken.assembl_ar.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.ByteBuffer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.*;

/** Exercises real packaged OBJ/MTL/photo assets and GLES2 without depending on activity focus. */
@RunWith(AndroidJUnit4.class)
public final class TexturedModelInstrumentedTest {
    @Test public void packagedPhotosUploadAndEveryComponentFrontBackAndHomeRender() throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean capture = "true".equals(InstrumentationRegistry.getArguments().getString("captureModels"));
        int size = capture ? 1024 : 256;
        ObjModel main = ObjModel.load(context, "models/main_board.obj");
        ObjModel extension = ObjModel.load(context, "models/extension_board.obj");
        assertNotNull(main);
        assertNotNull(extension);
        assertTrue(main.vertexCount() > 100);
        assertTrue(extension.vertexCount() > 100);

        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertNotEquals(EGL14.EGL_NO_DISPLAY, display);
        int[] version = new int[2];
        assertTrue(EGL14.eglInitialize(display, version, 0, version, 1));
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        assertTrue(EGL14.eglChooseConfig(display, new int[]{
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_NONE}, 0, configs, 0, 1, count, 0));
        assertTrue(count[0] > 0);
        EGLContext eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                new int[]{EGL14.EGL_WIDTH, size, EGL14.EGL_HEIGHT, size, EGL14.EGL_NONE}, 0);
        try {
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, eglContext));
            ObjMaterialRenderer material = new ObjMaterialRenderer(context);
            assertTrue("Texture shader must link on GLES2", material.isReady());
            material.prepare(main);
            material.prepare(extension);
            assertEquals("Both photos of both boards must be available", 4, material.uploadedTextureCount());

            OrbitController homeOrbit = new OrbitController();
            HomeRobotRenderer home = new HomeRobotRenderer(context, homeOrbit);
            home.onSurfaceCreated(null, null);
            renderAndAssert(context, home, "home", size, size, capture);
            homeOrbit.restore(0f, OrbitController.DEFAULT_PITCH_DEGREES,
                    OrbitController.DEFAULT_DISTANCE);
            renderAndAssert(context, home, "home-front", size, size, capture);
            homeOrbit.restore(180f, OrbitController.DEFAULT_PITCH_DEGREES,
                    OrbitController.DEFAULT_DISTANCE);
            renderAndAssert(context, home, "home-rear", size, size, capture);
            homeOrbit.restore(30f, OrbitController.MIN_PITCH_DEGREES,
                    OrbitController.DEFAULT_DISTANCE);
            renderAndAssert(context, home, "home-underside", size, size, capture);
            homeOrbit.restore(OrbitController.DEFAULT_YAW_DEGREES,
                    OrbitController.DEFAULT_PITCH_DEGREES, OrbitController.DEFAULT_DISTANCE);
            renderAndAssert(context, home, "home-narrow", size / 2, size, capture);
            for (String id : ComponentModelCatalog.assets().keySet()) {
                OrbitController orbit = new OrbitController();
                orbit.restore(-20f, 55f, 6.5f);
                HomeRobotRenderer preview = new HomeRobotRenderer(context, orbit, id);
                preview.onSurfaceCreated(null, null);
                renderAndAssert(context, preview, id + "-front", size, size, capture);
                preview.showBack(true);
                renderAndAssert(context, preview, id + "-back", size, size, capture);
            }
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, eglContext);
            EGL14.eglTerminate(display);
        }
    }

    private static void renderAndAssert(Context context, HomeRobotRenderer renderer, String name,
                                       int width, int height, boolean capture) throws IOException {
        renderer.onSurfaceChanged(null, width, height);
        renderer.onDrawFrame(null);
        assertVisibleGeometry(context, name, width, height, capture);
        assertEquals("GLES error for " + name, GLES20.GL_NO_ERROR, GLES20.glGetError());
    }

    private static void assertVisibleGeometry(Context context, String name, int width, int height,
                                               boolean capture) throws IOException {
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        int darkPixels = 0;
        for (int i = 0; i < pixels.capacity(); i += 4) {
            if ((pixels.get(i) & 255) < 230 || (pixels.get(i + 1) & 255) < 230
                    || (pixels.get(i + 2) & 255) < 230) darkPixels++;
        }
        // Thin fasteners and wires occupy less area than boards, but must remain visible.
        assertTrue(name + " should not be a blank white viewport", darkPixels > 200);
        if (capture) {
            int[] colors = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int i = ((height - 1 - y) * width + x) * 4;
                    colors[y * width + x] = 0xff000000 | (pixels.get(i) & 255) << 16
                            | (pixels.get(i + 1) & 255) << 8 | (pixels.get(i + 2) & 255);
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
            File directory = context.getExternalFilesDir("model-previews");
            assertNotNull(directory);
            try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            } finally {
                bitmap.recycle();
            }
        }
    }
}
