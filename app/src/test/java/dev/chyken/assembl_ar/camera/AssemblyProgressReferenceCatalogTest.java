package dev.chyken.assembl_ar.camera;

import org.junit.Test;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.PlanarCoordinateMapper;
import dev.chyken.assembl_ar.vision.VisionPoint;
import static org.junit.Assert.*;

public final class AssemblyProgressReferenceCatalogTest {
    private static File root() {
        File directory = new File(System.getProperty("user.dir"));
        while (directory != null && !new File(directory, "app/src/main/assets").isDirectory()) {
            directory = directory.getParentFile();
        }
        assertNotNull("Repository root", directory);
        return directory;
    }

    @Test public void bundledAssetsAreSmallUniqueAndKeepReviewedGeometry() throws Exception {
        File root = root();
        Set<String> ids = new HashSet<>();
        Set<String> assets = new HashSet<>();
        long bytes = 0;
        for (ComponentReferenceSpec spec : AssemblyProgressReferenceCatalog.all()) {
            assertTrue(ids.add(spec.getReferenceId()));
            File asset = new File(root, "app/src/main/assets/" + spec.getJpegAssetPath());
            assertTrue(asset.toString(), asset.isFile());
            if (assets.add(spec.getJpegAssetPath())) bytes += asset.length();
            assertEquals(1280, spec.getMaximumImageDimension());
            assertFalse(spec.isInstalledBracketView());
            assertFalse(spec.hasVerifiedPolarityAnchors());
            Matcher source = Pattern.compile("20261005_\\d{6}\\.jpg").matcher(asset.getName());
            assertTrue(source.find());
            // Historical upstream split labels are not shipped in this checkout.
            // This test checks the actual bundled asset/geometry contract instead.
            assertEquals(4, spec.getPlanarCornersNormalized().size());
            for (VisionPoint point : spec.getPlanarCornersNormalized()) unit(point);
            assertFalse(spec.getFeatureRegionsNormalized().isEmpty());
            for (java.util.List<VisionPoint> polygon : spec.getFeatureRegionsNormalized()) {
                assertTrue(polygon.size() >= 3);
                for (VisionPoint point : polygon) {
                    unit(point);
                    VisionPoint canonical = PlanarCoordinateMapper.unprojectUnitSquare(
                            spec.getPlanarCornersNormalized(), point);
                    assertNotNull(canonical);
                    unit(canonical);
                }
            }
        }
        assertEquals(10, assets.size());
        assertEquals(34, ids.size());
        assertTrue(bytes <= 2_000_000);
    }

    @Test public void boardScopesBeginAtTheirVisibleStateAndFacesOnlyAtPlacement() {
        for (ComponentReferenceSpec spec : AssemblyProgressReferenceCatalog.all()) {
            int step = spec.getAssemblyStepNumber();
            String path = spec.getJpegAssetPath();
            if ("main_board".equals(spec.getComponentId())) {
                int first = path.contains("both-motors") ? 2
                        : path.contains("driver-leads") ? 5 : path.contains("driver") ? 3 : 6;
                assertTrue(step >= first && step <= 8);
                // Staged references describe the same 100 x 120 mm board measured on 7 October.
                assertEquals(100.0, spec.getPhysicalWidthMm(), 0.0);
                assertEquals(120.0, spec.getPhysicalHeightMm(), 0.0);
            } else if ("dual_motor_driver".equals(spec.getComponentId())) {
                assertEquals(3, step);
                assertEquals(35.0, spec.getPhysicalWidthMm(), 0.0);
                assertEquals(45.0, spec.getPhysicalHeightMm(), 0.0);
            } else {
                assertEquals("arduino_nano_microcontroller", spec.getComponentId());
                assertEquals(6, step);
                assertEquals(18.0, spec.getPhysicalWidthMm(), 0.0);
                assertEquals(45.0, spec.getPhysicalHeightMm(), 0.0);
            }
        }
    }

    @Test public void productionCatalogLeavesBoardAnchorsStagedUntilFallbackCanHonorScopes() {
        int active = 0;
        for (dev.chyken.assembl_ar.model.Component component :
                dev.chyken.assembl_ar.data.NeobotSeedData.getComponents()) {
            java.util.List<ComponentReferenceSpec> references =
                    AssemblyProgressReferenceCatalog.forComponent(component);
            if ("main_board".equals(component.getId())) assertTrue(references.isEmpty());
            active += references.size();
        }
        assertEquals(4, active);
    }

    private static void unit(VisionPoint point) {
        assertTrue(Double.isFinite(point.getX()) && Double.isFinite(point.getY()));
        assertTrue(point.getX() >= -1e-7 && point.getX() <= 1.0000001);
        assertTrue(point.getY() >= -1e-7 && point.getY() <= 1.0000001);
    }
}
