package dev.chyken.assembl_ar;

import android.app.Activity;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collection;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class PrimaryNavigationInstrumentedTest {
    @Test
    public void peerTabSwitchesDoNotBecomeBackHistory() {
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.navLearningButton)).perform(click());
            assertResumedActivity(LearningActivity.class);

            onView(withId(R.id.navComponentsButton)).perform(click());
            assertResumedActivity(ComponentLibraryActivity.class);

            onView(withId(R.id.navLearningButton)).perform(click());
            assertResumedActivity(LearningActivity.class);

            pressBack();
            assertResumedActivity(MainActivity.class);
        }
    }

    @Test
    public void assembleTabReturnsProjectSelectionToLandingPage() {
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.startAssemblyButton)).perform(click());
            assertResumedActivity(ProjectSelectionActivity.class);

            onView(withId(R.id.navAssemblyButton)).perform(click());
            assertResumedActivity(MainActivity.class);
        }
    }

    private static void assertResumedActivity(Class<? extends Activity> expectedClass) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Collection<Activity> resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED);
            assertEquals("Exactly one Activity should be resumed", 1, resumed.size());
            assertTrue("Expected " + expectedClass.getSimpleName() + " to be resumed",
                    expectedClass.isInstance(resumed.iterator().next()));
        });
    }
}
