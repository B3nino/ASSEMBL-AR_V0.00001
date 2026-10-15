package dev.chyken.assembl_ar;

import android.content.Context;
import android.graphics.Color;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertTrue;

/** Guards the minimum contrast of text and key accents over live camera surfaces. */
@RunWith(AndroidJUnit4.class)
public final class AssemblyPaletteInstrumentedTest {
    private static final double NORMAL_TEXT_MINIMUM = 4.5d;

    @Test
    public void cameraChromeRemainsReadableOverBrightScenes() {
        Context context = ApplicationProvider.getApplicationContext();
        int white = color(context, R.color.white);
        int panelOverWhite = ColorUtils.compositeColors(
                color(context, R.color.camera_panel), Color.WHITE);
        int toolbarOverWhite = ColorUtils.compositeColors(
                color(context, R.color.camera_toolbar), Color.WHITE);

        assertContrastAtLeast(white, panelOverWhite, NORMAL_TEXT_MINIMUM);
        assertContrastAtLeast(white, toolbarOverWhite, NORMAL_TEXT_MINIMUM);
        assertContrastAtLeast(
                color(context, R.color.hud_tracking), panelOverWhite, NORMAL_TEXT_MINIMUM);
    }

    @Test
    public void lightScreenWarningAndBodyTextRemainReadable() {
        Context context = ApplicationProvider.getApplicationContext();
        assertContrastAtLeast(
                color(context, R.color.ink),
                color(context, R.color.warning_soft),
                NORMAL_TEXT_MINIMUM);
        assertContrastAtLeast(
                color(context, R.color.primary),
                color(context, R.color.surface),
                NORMAL_TEXT_MINIMUM);
    }

    private static int color(Context context, int resourceId) {
        return ContextCompat.getColor(context, resourceId);
    }

    private static void assertContrastAtLeast(int foreground, int background, double minimum) {
        double contrast = ColorUtils.calculateContrast(foreground, background);
        assertTrue("Expected contrast >= " + minimum + " but was " + contrast,
                contrast >= minimum);
    }
}
