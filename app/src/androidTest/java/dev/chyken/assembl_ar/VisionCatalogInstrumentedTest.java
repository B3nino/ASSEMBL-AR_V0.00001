package dev.chyken.assembl_ar;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.ReferenceDatabase;
import dev.chyken.assembl_ar.vision.VisionConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Loads every seeded bundled reference through the real mask and ORB preparation path. */
@RunWith(AndroidJUnit4.class)
public final class VisionCatalogInstrumentedTest {
    @Test
    public void everyBundledReferenceBuildsAUsableDatabaseEntry() throws Exception {
        assertTrue("The packaged OpenCV runtime must initialize", OpenCVLoader.initLocal());
        Context context = ApplicationProvider.getApplicationContext();
        AssemblArRepository repository = AssemblArRepository.getInstance(context);

        List<ComponentReferenceSpec> built = VisionReferenceFactory.build(repository);
        List<ComponentReferenceSpec> bundled = new ArrayList<>();
        Set<String> referenceIds = new HashSet<>();
        Set<String> expectedComponentIds = new HashSet<>();
        Set<String> expectedOriginalPaths = new HashSet<>(Arrays.asList(
                "references/assembly-captures/main-board-bare-20260906_115902.jpg",
                "references/assembly-captures/main-board-motors-20260906_124302.jpg",
                "references/assembly-captures/main-board-motors-oblique-124312.jpg",
                "references/assembly-captures/main-board-motors-left-124307.jpg",
                "references/assembly-captures/main-board-motors-right-124316.jpg",
                "references/assembly-captures/main-board-brackets-20260927_132917.jpg",
                "references/assembly-captures/main-board-brackets-20260927_133333.jpg",
                "references/assembly-captures/main-board-brackets-20260927_133502.jpg",
                "references/assembly-captures/main-board-brackets-20260927_133659.jpg"));
        Set<String> reviewedProductionIds = new HashSet<>(
                dev.chyken.assembl_ar.camera.IndividualPartTestCatalog.getStandaloneComponentIds());
        // The guided catalog additionally isolates these original contextual kit photographs.
        // Their masks and source hashes are covered by RecoveredPhysicalRecognitionInstrumentedTest.
        reviewedProductionIds.add(NeobotSeedData.BATTERY_PACK);
        reviewedProductionIds.add(NeobotSeedData.WHEEL);
        for (ComponentReferenceSpec spec : built) {
            if (spec.getJpegAssetPath() == null) {
                continue;
            }
            bundled.add(spec);
            assertTrue("Duplicate authored reference id: " + spec.getReferenceId(),
                    referenceIds.add(spec.getReferenceId()));
            assertFalse("Bundled reference path is blank",
                    spec.getJpegAssetPath().trim().isEmpty());
            assertNotNull(spec.getPlanarCornersNormalized());
            assertEquals(4, spec.getPlanarCornersNormalized().size());
            expectedComponentIds.add(spec.getComponentId());
            assertFalse("Generatively edited artwork must not train recognition",
                    spec.getJpegAssetPath().startsWith("references/instructional/"));
            if (spec.getJpegAssetPath().startsWith("references/assembly-captures/")) {
                assertTrue("Unexpected original production reference: "
                                + spec.getJpegAssetPath(),
                        expectedOriginalPaths.remove(spec.getJpegAssetPath()));
            }
        }
        assertTrue("Production catalog omitted reviewed originals: "
                        + expectedOriginalPaths, expectedOriginalPaths.isEmpty());

        assertEquals("Only visually reviewed identities belong in the bundled catalog",
                reviewedProductionIds, expectedComponentIds);
        for (ComponentReferenceSpec spec : bundled) {
            assertFalse("AR screenshots cannot teach board pose",
                    spec.getJpegAssetPath().startsWith("references/assembly-state/"));
            assertFalse("Wrong-object screw image cannot return from persisted library data",
                    spec.getJpegAssetPath().equals("references/assembly/image-015.png"));
        }

        // Deliberately omit a descriptor-cache directory: every asset must be decoded, masked,
        // and prepared during this test instead of succeeding through a stale cached blob.
        try (ReferenceDatabase database = ReferenceDatabase.load(
                context.getAssets(), bundled, VisionConfig.defaults())) {
            assertEquals("Every authored reference must produce one prepared entry",
                    bundled.size(), database.size());
            assertEquals(expectedComponentIds, database.getComponentIds());
        }
    }
}
