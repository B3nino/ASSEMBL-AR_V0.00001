package dev.chyken.assembl_ar;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The final acknowledgement remains reachable when physical instructions exceed the window. */
@RunWith(AndroidJUnit4.class)
public final class PhysicalValidationLayoutInstrumentedTest {
    @Test public void longInstructionsAndLargeFontsKeepAllControlsReachableByScrolling() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (float fontScale : new float[]{1f, 2f}) {
                Context context = themedContext(fontScale);
                ScrollView scroll = inflate(context);
                ((TextView) scroll.findViewById(R.id.validationTitle)).setText(
                        "Check the installed motor bracket and both motors");
                StringBuilder instructions = new StringBuilder();
                for (int paragraph = 0; paragraph < 20; paragraph++) {
                    instructions.append("Check that the motor bracket sits in its assigned board slot. "
                            + "Check the shafts, terminals, pin alignment and fastening directly.\n\n");
                }
                instructions.append("Finally, make sure neither motor moves in its brackets.");
                ((TextView) scroll.findViewById(R.id.validationBody)).setText(instructions);
                measure(scroll, context, 320, 260);

                assertTrue("Long instructions must have a scrollable viewport", scroll.canScrollVertically(1));
                AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
                try {
                    scroll.onInitializeAccessibilityNodeInfo(info);
                    assertTrue("Assistive technology must discover scrolling", info.isScrollable());
                    assertTrue(info.getActionList().contains(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD));
                } finally {
                    info.recycle();
                }

                CheckBox checkbox = scroll.findViewById(R.id.validationCheckbox);
                View confirm = scroll.findViewById(R.id.confirmNextButton);
                assertFalse("Scrolling must not acknowledge the physical check", checkbox.isChecked());
                assertFalse("Confirmation starts locked", confirm.isEnabled());

                revealAndAssertVisible(scroll, checkbox);
                revealAndAssertVisible(scroll, confirm);
                assertTrue("The final control requires scrolling past the long instructions", scroll.getScrollY() > 0);
                assertFalse(checkbox.isChecked());
                assertFalse(confirm.isEnabled());
            }
        });
    }

    @Test public void shortInstructionsStillWrapContentWithoutAnUnneededFullHeightDialog() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themedContext(1f);
            ScrollView scroll = inflate(context);
            ((TextView) scroll.findViewById(R.id.validationTitle)).setText("Check the part");
            ((TextView) scroll.findViewById(R.id.validationBody)).setText("Make sure it is secure.");
            measure(scroll, context, 360, 700);
            assertTrue("Short checks should remain compact", scroll.getHeight() < dp(context, 700));
            assertFalse(scroll.canScrollVertically(1));
            revealAndAssertVisible(scroll, scroll.findViewById(R.id.confirmNextButton));
        });
    }

    private static Context themedContext(float fontScale) {
        Context base = ApplicationProvider.getApplicationContext();
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.fontScale = fontScale;
        return new ContextThemeWrapper(base.createConfigurationContext(configuration), R.style.Theme_ASSEMBLAR);
    }

    private static ScrollView inflate(Context context) {
        View content = LayoutInflater.from(context).inflate(R.layout.dialog_validation, null, false);
        ScrollView scroll = content.findViewById(R.id.validationScroll);
        assertNotNull(scroll);
        return scroll;
    }

    private static void measure(View view, Context context, int widthDp, int maxHeightDp) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(context, widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(context, maxHeightDp), View.MeasureSpec.AT_MOST));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private static void revealAndAssertVisible(ScrollView scroll, View control) {
        // Use the same rectangle-reveal mechanism as keyboard/accessibility focus traversal.
        Rect rectangle = new Rect(0, 0, control.getWidth(), control.getHeight());
        scroll.requestChildRectangleOnScreen(control, rectangle, true);
        View content = scroll.getChildAt(0);
        int top = content.getTop() + control.getTop();
        assertTrue("Control top must fit in the viewport", top >= scroll.getScrollY());
        assertTrue("Control bottom must fit in the viewport",
                top + control.getHeight() <= scroll.getScrollY() + scroll.getHeight());
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
