package dev.chyken.assembl_ar;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.ui.AssemblyDemoView;
import dev.chyken.assembl_ar.ui.AssemblyVoiceGuide;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence;
import dev.chyken.assembl_ar.workflow.GuidedDemonstrationCatalog;
import dev.chyken.assembl_ar.workflow.GuidedRecognitionCatalog;

import static org.junit.Assert.*;

/** Exercises actual Canvas drawing, asynchronous source images, and Activity voice lifecycle. */
@RunWith(AndroidJUnit4.class)
public final class AssemblyDemoViewInstrumentedTest {
    @Test
    public void representativeActionsRenderAndKeepTheirFullAccessibleInstruction() throws Exception {
        int[][] examples = {{2, 1}, {2, 2}, {2, 3}, {2, 4}, {2, 5}, {2, 17},
                {2, 27}, {2, 9}, {5, 3}, {11, 15}, {12, 2}, {17, 3}};
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            for (int[] example : examples) {
                GuidedDemonstrationCatalog.Demo demo = demo(example[0], example[1]);
                scenario.onActivity(activity -> {
                    AssemblyDemoView view = activity.findViewById(R.id.assemblyDemo);
                    view.setDemo(demo);
                    view.pause();
                    assertTrue(view.getContentDescription().toString().contains(demo.getSpokenInstruction()));
                    if (!demo.getContextTargets().isEmpty()) {
                        assertTrue(view.getContentDescription().toString().contains(demo.getAttachmentCaption()));
                    }
                });
                if (!demo.getTargets().isEmpty()
                        || demo.getPinConnections().isEmpty() || demo.getPinConnections().size() > 4) {
                    awaitReference(scenario);
                }
                scenario.onActivity(activity -> {
                    AssemblyDemoView view = activity.findViewById(R.id.assemblyDemo);
                    ((ValueAnimator) field(view, "animator")).setCurrentPlayTime(2700);
                    Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
                    view.draw(new Canvas(bitmap));
                    int background = bitmap.getPixel(bitmap.getWidth() / 2, 5);
                    int different = 0;
                    for (int y = bitmap.getHeight() / 5; y < bitmap.getHeight() * 3 / 4; y += 3) {
                        for (int x = 10; x < bitmap.getWidth() - 10; x += 3) {
                            if (bitmap.getPixel(x, y) != background) different++;
                        }
                    }
                    assertTrue("Demo must contain a rendered instruction, not only a blank panel", different > 100);
                    save(activity, bitmap, "demo-" + example[0] + "-" + example[1] + ".png");
                    bitmap.recycle();
                });
            }
        }
    }

    @Test
    public void muteSurvivesRecreationAndVoiceEngineClosesWithActivity() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("AssemblyActivity", Context.MODE_PRIVATE)
                .edit().remove("voice_guide_muted").commit();
        AssemblyVoiceGuide[] previous = new AssemblyVoiceGuide[1];
        ValueAnimator[] previousAnimation = new ValueAnimator[1];
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                previous[0] = (AssemblyVoiceGuide) field(activity, "voiceGuide");
                previousAnimation[0] = (ValueAnimator) field(activity.findViewById(R.id.assemblyDemo), "animator");
                activity.findViewById(R.id.replayDemoButton).performClick();
                assertFalse(previous[0].isMuted());
                activity.findViewById(R.id.voiceGuideButton).performClick();
                assertTrue(previous[0].isMuted());
            });
            scenario.recreate();
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                AssemblyVoiceGuide current = (AssemblyVoiceGuide) field(activity, "voiceGuide");
                assertNotSame(previous[0], current);
                assertEquals(AssemblyVoiceGuide.Status.UNAVAILABLE, previous[0].getStatus());
                assertFalse("Destroyed Activity must release its animation", previousAnimation[0].isStarted());
                assertTrue(current.isMuted());
                assertEquals(AssemblyVoiceGuide.Status.MUTED, current.getStatus());
                activity.findViewById(R.id.voiceGuideButton).performClick();
                assertFalse(current.isMuted());
            });
        } finally {
            context.getSharedPreferences("AssemblyActivity", Context.MODE_PRIVATE)
                    .edit().remove("voice_guide_muted").commit();
        }
    }

    @Test
    public void backgroundingPausesDemoAndReplayRemainsAvailableOnReturn() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> activity.findViewById(R.id.replayDemoButton).performClick());
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.onActivity(activity -> {
                ValueAnimator animator = (ValueAnimator) field(activity.findViewById(R.id.assemblyDemo), "animator");
                assertFalse("A background Activity must not keep animating", animator.isRunning() && !animator.isPaused());
            });
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.replayDemoButton).isEnabled()));
        }
    }

    private static GuidedDemonstrationCatalog.Demo demo(int sectionNumber, int order) {
        AssemblyStep section = NeobotSeedData.getAssemblySteps().get(sectionNumber - 1);
        for (GuidedAssemblySequence.GuidedStep card : GuidedAssemblySequence.forManualStep(section)) {
            if (card.getPrimaryInstruction().getOrder() == order) {
                return GuidedDemonstrationCatalog.create(section, card, GuidedRecognitionCatalog.checkpointFor(section, card));
            }
        }
        throw new AssertionError("Missing demo " + sectionNumber + ":" + order);
    }

    private static ActivityScenario<AssemblyActivity> launch() {
        Context context = ApplicationProvider.getApplicationContext();
        return ActivityScenario.launch(new Intent(context, AssemblyActivity.class)
                .putExtra(AssemblyActivity.EXTRA_SIMULATED_STATE, AssemblyActivity.SIMULATE_MISMATCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static void awaitLoaded(ActivityScenario<AssemblyActivity> scenario) {
        await(scenario, activity -> {
            AssemblyDemoView view = activity.findViewById(R.id.assemblyDemo);
            assertTrue(view.getWidth() > 0 && view.getHeight() > 0);
            assertNotNull(view.getContentDescription());
        });
    }

    private static void awaitReference(ActivityScenario<AssemblyActivity> scenario) {
        await(scenario, activity -> assertNotNull("Authored illustration must load",
                field(activity.findViewById(R.id.assemblyDemo), "reference")));
    }

    private static void await(ActivityScenario<AssemblyActivity> scenario,
                              ActivityScenario.ActivityAction<AssemblyActivity> assertion) {
        AssertionError last = null;
        for (int index = 0; index < 60; index++) {
            try { scenario.onActivity(assertion); return; }
            catch (AssertionError failure) { last = failure; SystemClock.sleep(100); }
        }
        throw last == null ? new AssertionError("View did not load") : last;
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void save(Context context, Bitmap bitmap, String name) {
        File directory = context.getExternalFilesDir("demo-previews");
        assertNotNull(directory);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
}
