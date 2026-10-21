package dev.chyken.assembl_ar;

import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Ensures every assembly-stage number, including two digits, fits its badge. */
@RunWith(AndroidJUnit4.class)
public final class ProjectDetailStepNumberInstrumentedTest {
    @Test
    public void twoDigitStageNumberHasDedicatedUncrowdedBadge() {
        try (ActivityScenario<ProjectDetailActivity> ignored =
                     ActivityScenario.launch(ProjectDetailActivity.class)) {
            assertEventually(ignored);
        }
    }

    @Test public void firstSixPhaseDescriptionsUseTheOverhauledStepCounts(){
        try(ActivityScenario<ProjectDetailActivity> scenario=ActivityScenario.launch(ProjectDetailActivity.class)){
            for(int attempt=0;attempt<40;attempt++){
                boolean[] ready={false};scenario.onActivity(activity->{
                    LinearLayout rows=activity.findViewById(R.id.stepsContainer);if(rows.getChildCount()<6)return;
                    int[] counts={1,6,6,3,4,4};
                    for(int index=0;index<counts.length;index++){
                        View row=rows.getChildAt(index);String title=((TextView)row.findViewById(R.id.itemTitle)).getText().toString();String summary=((TextView)row.findViewById(R.id.itemSubtitle)).getText().toString();
                        assertTrue(title.startsWith("Phase "+(index+1)));assertTrue(summary.startsWith(counts[index]+(counts[index]==1?" step":" steps")));
                    }
                    ready[0]=true;
                });if(ready[0])return;SystemClock.sleep(100);
            }
            throw new AssertionError("Overhauled phase descriptions were not displayed");
        }
    }

    private static void assertEventually(ActivityScenario<ProjectDetailActivity> scenario) {
        for (int attempt = 0; attempt < 30; attempt++) {
            boolean[] verified = {false};
            scenario.onActivity(activity -> {
                LinearLayout steps = activity.findViewById(R.id.stepsContainer);
                TextView number = findStageNumber(steps, "10");
                if (number == null || number.getWidth() == 0) {
                    return;
                }
                int usableWidth = number.getWidth()
                        - number.getCompoundPaddingLeft()
                        - number.getCompoundPaddingRight();
                assertTrue("Two-digit stage number must fit within its badge",
                        usableWidth >= Math.ceil(number.getPaint().measureText(number.getText()
                                .toString())));
                assertEquals("Step 10", number.getContentDescription());
                verified[0] = true;
            });
            if (verified[0]) {
                return;
            }
            SystemClock.sleep(100L);
        }
        throw new AssertionError("Two-digit stage number did not appear");
    }

    private static TextView findStageNumber(LinearLayout steps, String value) {
        for (int index = 0; index < steps.getChildCount(); index++) {
            View child = steps.getChildAt(index);
            TextView number = child.findViewById(R.id.itemNumber);
            if (number != null && value.contentEquals(number.getText())) {
                return number;
            }
        }
        return null;
    }
}
