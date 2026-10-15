package dev.chyken.assembl_ar;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.hamcrest.Matcher;
import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/** Manuscript-required automated UI checks for mismatch and orientation error gates. */
@RunWith(AndroidJUnit4.class)
public final class AssemblyValidationInstrumentedTest {
    @Test
    public void mismatchedComponentShowsCorrectionAndCannotAdvance() {
        try (ActivityScenario<AssemblyActivity> scenario = launch(AssemblyActivity.SIMULATE_MISMATCH)) {
            assertEventually(allOf(withId(R.id.recognitionText),
                    withText(containsString("use"))));
            onView(withId(R.id.confirmPlacementButton)).check(matches(not(isDisplayed())));
        }
    }

    @Test
    public void backwardComponentShowsWrongOrientationAndCannotAdvance() {
        try (ActivityScenario<AssemblyActivity> scenario =
                     launch(AssemblyActivity.SIMULATE_WRONG_ORIENTATION)) {
            assertEventually(allOf(withId(R.id.recognitionText),
                    withText(containsString("Wrong orientation"))));
            onView(withId(R.id.confirmPlacementButton)).check(matches(not(isDisplayed())));
        }
    }

    @Test
    public void reversedWiringShowsPolarityErrorAndCannotAdvance() {
        try (ActivityScenario<AssemblyActivity> scenario =
                     launch(AssemblyActivity.SIMULATE_POLARITY_ERROR)) {
            assertEventually(allOf(withId(R.id.recognitionText),
                    withText(containsString("Polarity error"))));
            onView(withId(R.id.confirmPlacementButton)).check(matches(not(isDisplayed())));
        }
    }

    @Test
    public void unresolvedPolarityCannotAdvance() {
        try (ActivityScenario<AssemblyActivity> scenario =
                     launch(AssemblyActivity.SIMULATE_POLARITY_UNCONFIRMED)) {
            assertEventually(allOf(withId(R.id.recognitionText),
                    withText(containsString("Polarity not yet verified"))));
            onView(withId(R.id.confirmPlacementButton)).check(matches(not(isDisplayed())));
        }
    }

    private static ActivityScenario<AssemblyActivity> launch(String state) {
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(context, AssemblyActivity.class)
                .putExtra(AssemblyActivity.EXTRA_SIMULATED_STATE, state)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return ActivityScenario.launch(intent);
    }

    private static void assertEventually(Matcher<android.view.View> matcher) {
        AssertionError lastFailure = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                onView(matcher).check(matches(isDisplayed()));
                return;
            } catch (AssertionError failure) {
                lastFailure = failure;
                SystemClock.sleep(100L);
            }
        }
        throw lastFailure == null ? new AssertionError("View did not appear") : lastFailure;
    }
}
