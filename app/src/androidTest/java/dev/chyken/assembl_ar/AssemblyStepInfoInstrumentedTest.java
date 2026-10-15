package dev.chyken.assembl_ar;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises the camera-side information panel without a camera or changing saved progress. */
@RunWith(AndroidJUnit4.class)
public final class AssemblyStepInfoInstrumentedTest {
    @Test
    public void longInstructionsScrollWithoutGrowingPanelOrCoveringCamera() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            int[] originalPanelHeight = new int[1];
            Rect[] originalHeader = new Rect[1];
            scenario.onActivity(activity -> {
                originalPanelHeight[0] = activity.findViewById(R.id.instructionPanel).getHeight();
                originalHeader[0] = bounds(activity.findViewById(R.id.instructionHeader));
                injectLongContent(activity);
            });

            eventually(scenario, activity -> {
                View panel = activity.findViewById(R.id.instructionPanel);
                View scroll = activity.findViewById(R.id.stepInfoScroll);
                assertEquals("Content length must not change the expanded panel height",
                        originalPanelHeight[0], panel.getHeight());
                assertTrue("Long instructions must scroll instead of being truncated",
                        scroll.canScrollVertically(1));
                TextView instructions = activity.findViewById(R.id.stepInstruction);
                assertTrue("Instructions must not retain the old four-line limit",
                        instructions.getMaxLines() > 4);
                assertSafeBoundsAndCameraSpace(activity);
                assertFullyVisible(activity.findViewById(R.id.replayDemoButton));
                assertFullyVisible(activity.findViewById(R.id.voiceGuideButton));
            });

            scenario.onActivity(activity ->
                    ((ScrollView) activity.findViewById(R.id.stepInfoScroll))
                            .fullScroll(View.FOCUS_DOWN));
            eventually(scenario, activity -> {
                View scroll = activity.findViewById(R.id.stepInfoScroll);
                assertTrue(scroll.canScrollVertically(-1));
                assertFalse("End of instructions must be reachable", scroll.canScrollVertically(1));
                assertFullyVisible(activity.findViewById(R.id.manualModeButton));
                assertEquals("Progress/header must stay pinned while content scrolls",
                        originalHeader[0], bounds(activity.findViewById(R.id.instructionHeader)));
            });
        }
    }

    @Test
    public void hideAndReopenPreserveStepProgressAndScrollWithoutUnlockingValidation() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(AssemblyStepInfoInstrumentedTest::injectLongContent);
            eventually(scenario, activity -> assertTrue(
                    activity.findViewById(R.id.stepInfoScroll).canScrollVertically(1)));
            scenario.onActivity(activity ->
                    activity.findViewById(R.id.stepInfoScroll).scrollTo(0, 160));

            String[] step = new String[1];
            String[] progress = new String[1];
            String[] instructions = new String[1];
            int[] expandedHeight = new int[1];
            int[] scrollPosition = new int[1];
            scenario.onActivity(activity -> {
                step[0] = text(activity, R.id.stepCounter);
                progress[0] = text(activity, R.id.progressLabel);
                instructions[0] = text(activity, R.id.stepInstruction);
                expandedHeight[0] = activity.findViewById(R.id.instructionPanel).getHeight();
                scrollPosition[0] = activity.findViewById(R.id.stepInfoScroll).getScrollY();
                assertTrue("Scroll precondition", scrollPosition[0] > 0);
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                activity.findViewById(R.id.toggleStepInfoButton).performClick();
            });

            eventually(scenario, activity -> {
                assertFalse(activity.findViewById(R.id.stepInfoScroll).isShown());
                assertFalse(activity.findViewById(R.id.replayDemoButton).isShown());
                assertTrue("Collapsed panel must give the camera more room",
                        activity.findViewById(R.id.instructionPanel).getHeight() < expandedHeight[0]);
                assertFullyVisible(activity.findViewById(R.id.toggleStepInfoButton));
                assertTrue(text(activity, R.id.toggleStepInfoButton)
                        .toLowerCase(Locale.ROOT).contains("show"));
                assertEquals(step[0], text(activity, R.id.stepCounter));
                assertEquals(progress[0], text(activity, R.id.progressLabel));
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
                assertSafeBoundsAndCameraSpace(activity);
            });

            scenario.onActivity(activity ->
                    activity.findViewById(R.id.toggleStepInfoButton).performClick());
            eventually(scenario, activity -> {
                assertTrue(activity.findViewById(R.id.stepInfoScroll).isShown());
                assertEquals(expandedHeight[0],
                        activity.findViewById(R.id.instructionPanel).getHeight());
                assertEquals(scrollPosition[0],
                        activity.findViewById(R.id.stepInfoScroll).getScrollY());
                assertEquals(step[0], text(activity, R.id.stepCounter));
                assertEquals(progress[0], text(activity, R.id.progressLabel));
                assertEquals(instructions[0], text(activity, R.id.stepInstruction));
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
            });
        }
    }

    @Test
    public void hiddenInformationRemainsHiddenAfterActivityRecreation() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            String[] step = new String[1];
            String[] progress = new String[1];
            scenario.onActivity(activity -> {
                step[0] = text(activity, R.id.stepCounter);
                progress[0] = text(activity, R.id.progressLabel);
                activity.findViewById(R.id.toggleStepInfoButton).performClick();
            });
            eventually(scenario, activity ->
                    assertFalse(activity.findViewById(R.id.stepInfoScroll).isShown()));

            scenario.recreate();
            awaitLoaded(scenario);
            eventually(scenario, activity -> {
                assertFalse("Recreation must not reopen the information over the camera",
                        activity.findViewById(R.id.stepInfoScroll).isShown());
                assertEquals(step[0], text(activity, R.id.stepCounter));
                assertEquals(progress[0], text(activity, R.id.progressLabel));
                assertFullyVisible(activity.findViewById(R.id.toggleStepInfoButton));
                assertEquals(View.GONE,
                        activity.findViewById(R.id.confirmPlacementButton).getVisibility());
            });
            scenario.onActivity(activity ->
                    activity.findViewById(R.id.toggleStepInfoButton).performClick());
            eventually(scenario, activity ->
                    assertTrue(activity.findViewById(R.id.stepInfoScroll).isShown()));
        }
    }

    @Test
    public void alreadyAvailablePhysicalCheckStaysAccessibleWhenInfoIsHidden() {
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitLoaded(scenario);
            scenario.onActivity(activity -> {
                // Presentation-only fixture: do not click this button or modify workflow state.
                activity.findViewById(R.id.confirmPlacementButton).setVisibility(View.VISIBLE);
                activity.findViewById(R.id.toggleStepInfoButton).performClick();
            });
            eventually(scenario, activity -> {
                assertFalse(activity.findViewById(R.id.stepInfoScroll).isShown());
                assertFullyVisible(activity.findViewById(R.id.confirmPlacementButton));
                assertFullyVisible(activity.findViewById(R.id.toggleStepInfoButton));
                assertSafeBoundsAndCameraSpace(activity);
            });
        }
    }

    private static ActivityScenario<AssemblyActivity> launch() {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, AssemblyActivity.class)
                .putExtra(AssemblyActivity.EXTRA_SIMULATED_STATE, AssemblyActivity.SIMULATE_MISMATCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return ActivityScenario.launch(intent);
    }

    private static void awaitLoaded(ActivityScenario<AssemblyActivity> scenario) {
        eventually(scenario, activity -> {
            assertTrue("Project must finish loading", text(activity, R.id.stepCounter).length() > 0);
            assertTrue("Mismatch fixture must finish loading",
                    text(activity, R.id.recognitionText).contains("use"));
            assertTrue(activity.findViewById(R.id.instructionPanel).getHeight() > 0);
            assertTrue(activity.findViewById(R.id.toggleStepInfoButton).getHeight() > 0);
        });
    }

    private static void injectLongContent(AssemblyActivity activity) {
        activity.findViewById(R.id.assemblyDetailsButton).performClick();
        StringBuilder instructions = new StringBuilder();
        StringBuilder materials = new StringBuilder();
        for (int index = 1; index <= 32; index++) {
            instructions.append(index).append(". Keep both motor terminals facing inward and ")
                    .append("check the bracket placement before tightening the screws.\n");
            materials.append("• 2 × Required component ").append(index)
                    .append(" — Verify the correct part and orientation before continuing.\n");
        }
        ((TextView) activity.findViewById(R.id.stepInstruction)).setText(instructions);
        ((TextView) activity.findViewById(R.id.requiredComponentLabel)).setText(materials);
    }

    private static void assertSafeBoundsAndCameraSpace(AssemblyActivity activity) {
        View root = activity.findViewById(R.id.assemblyRoot);
        View panel = activity.findViewById(R.id.instructionPanel);
        Rect safe = bounds(root);
        WindowInsetsCompat windowInsets = ViewCompat.getRootWindowInsets(root);
        if (windowInsets != null) {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            safe.left += insets.left;
            safe.top += insets.top;
            safe.right -= insets.right;
            safe.bottom -= insets.bottom;
        }
        Rect panelBounds = bounds(panel);
        assertTrue("Step info must remain within safe screen bounds: " + safe + " / " + panelBounds,
                safe.contains(panelBounds));
        Rect toolbar = bounds(activity.findViewById(R.id.cameraToolbar));
        assertTrue("Step info must not overlap navigation", panelBounds.top >= toolbar.bottom);
        long panelArea = (long) panelBounds.width() * panelBounds.height();
        long usableArea = (long) safe.width() * safe.height();
        assertTrue("A useful camera region must remain visible on phones and tablets",
                panelArea <= usableArea * 0.60d);
    }

    private static String text(AssemblyActivity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private static Rect bounds(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(location[0], location[1],
                location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    private static void assertFullyVisible(View view) {
        Rect visible = new Rect();
        assertTrue("View must be shown: " + view.getResources().getResourceEntryName(view.getId()),
                view.isShown() && view.getGlobalVisibleRect(visible));
        assertEquals("Full control height must be accessible", view.getHeight(), visible.height());
        assertEquals("Full control width must be accessible", view.getWidth(), visible.width());
    }

    private static void eventually(ActivityScenario<AssemblyActivity> scenario, UiAssertion assertion) {
        AssertionError lastFailure = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                scenario.onActivity(assertion::check);
                return;
            } catch (AssertionError failure) {
                lastFailure = failure;
                SystemClock.sleep(100L);
            }
        }
        throw lastFailure == null ? new AssertionError("Assembly layout did not settle") : lastFailure;
    }

    private interface UiAssertion {
        void check(AssemblyActivity activity);
    }
}
