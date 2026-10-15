package dev.chyken.assembl_ar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.Set;
import java.util.TreeSet;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyInstruction;
import dev.chyken.assembl_ar.model.AssemblyStep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Verifies every early-assembly instructional photograph is packaged and tablet-safe. */
@RunWith(AndroidJUnit4.class)
public final class InstructionalAssetInstrumentedTest {
    private static final String PREFIX = "references/instructional/gray/";

    @Test
    public void everyDeclaredGrayInstructionalAssetDecodesWithinTheSizeBudget() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Set<String> paths = new TreeSet<>();
        for (AssemblyStep step : NeobotSeedData.getAssemblySteps()) {
            for (AssemblyInstruction instruction : step.getInstructions()) {
                if (instruction.getReferenceImagePath().startsWith(PREFIX)) {
                    paths.add(instruction.getReferenceImagePath());
                }
            }
        }

        assertEquals("The reviewed instructional catalog must remain complete", 10, paths.size());
        for (String path : paths) {
            Bitmap bitmap;
            try (InputStream input = context.getAssets().open(path)) {
                bitmap = BitmapFactory.decodeStream(input);
            }
            assertNotNull("Unable to decode packaged instructional image: " + path, bitmap);
            try {
                assertTrue("Instructional image has invalid dimensions: " + path,
                        bitmap.getWidth() > 0 && bitmap.getHeight() > 0);
                assertTrue("Instructional image exceeds the 1600 px budget: " + path
                                + " (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")",
                        Math.max(bitmap.getWidth(), bitmap.getHeight()) <= 1600);
            } finally {
                bitmap.recycle();
            }
        }
    }
}
