package dev.chyken.assembl_ar;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.camera.view.PreviewView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Real camera regression: a stalled descriptor load cannot keep the preview black. */
@RunWith(AndroidJUnit4.class)
public final class CameraStartupInstrumentedTest {
    @Test public void previewStreamsAndResumesWhileReferenceLoadingIsBlocked() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(context.getPackageName(), Manifest.permission.CAMERA);
        Intent intent = new Intent(context, AssemblyActivity.class)
                .putExtra(AssemblyActivity.EXTRA_PROJECT_ID, dev.chyken.assembl_ar.data.NeobotSeedData.PROJECT_ID)
                .putExtra(AssemblyActivity.EXTRA_WORKFLOW_MODE, AssemblyActivity.WORKFLOW_CARDS)
                // Loads the actual screen without automatically starting its real catalog.
                .putExtra(AssemblyActivity.EXTRA_SIMULATED_STATE, "none");
        CountDownLatch loaderEntered = new CountDownLatch(1), releaseLoader = new CountDownLatch(1);
        try (ActivityScenario<AssemblyActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                ExecutorService loader = (ExecutorService) field(activity, "visionLoaderExecutor");
                loader.execute(() -> {
                    loaderEntered.countDown();
                    try { releaseLoader.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
            });
            assertTrue(loaderEntered.await(2, TimeUnit.SECONDS));
            scenario.onActivity(activity -> invoke(activity, "initializeVision"));
            awaitStreaming(scenario);
            Object[] camera = new Object[1];
            scenario.onActivity(activity -> {
                assertNull(field(activity, "recognizer"));
                assertNull(field(activity, "analyzer"));
                assertNotNull(field(activity, "imageAnalysis"));
                camera[0] = field(activity, "boundCamera");
                assertNotNull(camera[0]);
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitStreaming(scenario);
            scenario.onActivity(activity -> assertSame(camera[0], field(activity, "boundCamera")));
            // Closing interrupts the blocked worker; the queued catalog never needs to load.
        } finally {
            releaseLoader.countDown();
        }
    }

    private static void awaitStreaming(ActivityScenario<AssemblyActivity> scenario) {
        boolean[] streaming = new boolean[1];
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        do {
            scenario.onActivity(activity -> streaming[0] =
                    ((PreviewView) activity.findViewById(R.id.previewView)).getPreviewStreamState().getValue()
                            == PreviewView.StreamState.STREAMING);
            if (streaming[0]) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Camera preview waited for the blocked reference loader");
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void invoke(Object target, String name) {
        try {
            Method method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
