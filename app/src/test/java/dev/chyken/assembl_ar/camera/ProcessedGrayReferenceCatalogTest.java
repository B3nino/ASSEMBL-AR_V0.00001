package dev.chyken.assembl_ar.camera;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.VisionPoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ProcessedGrayReferenceCatalogTest {
    @Test
    public void allTenProcessedImagesAreRegisteredForProduction() {
        Set<String> expectedPaths = new HashSet<>(Arrays.asList(
                path("motor-bracket-half-gray"),
                path("gear-motor-gray"),
                path("motor-driver-gray"),
                path("s02-00-main-board-gray"),
                path("s02-01-brackets-mounted-gray"),
                path("s02-02-first-motor-positioned-gray"),
                path("s02-03-first-motor-one-screw-gray"),
                path("s02-04-first-motor-two-screws-gray"),
                path("s02-05-both-motors-gray"),
                path("s03-00-motor-driver-installed-gray")));
        Map<String, Integer> expectedCounts = new HashMap<>();
        expectedCounts.put(NeobotSeedData.MAIN_BOARD, 7);
        expectedCounts.put(NeobotSeedData.MOTOR_BRACKET, 1);
        expectedCounts.put(NeobotSeedData.GEAR_MOTOR, 1);
        expectedCounts.put(NeobotSeedData.MOTOR_DRIVER, 1);

        List<ComponentReferenceSpec> references = allReferences();

        assertEquals(10, references.size());
        Set<String> ids = new HashSet<>();
        Map<String, Integer> actualCounts = new HashMap<>();
        for (ComponentReferenceSpec spec : references) {
            assertTrue(spec.getReferenceId(), ids.add(spec.getReferenceId()));
            assertTrue(spec.getJpegAssetPath(), expectedPaths.remove(spec.getJpegAssetPath()));
            assertTrue(spec.getReferenceId().startsWith(
                    spec.getComponentId() + "_processed_gray_"));
            assertEquals(1280, spec.getMaximumImageDimension());
            assertTrue(spec.isDescriptorRegionNormalizationEnabled());
            assertFalse(spec.isAdministratorReference());
            assertFalse(spec.hasVerifiedPolarityAnchors());
            actualCounts.put(spec.getComponentId(),
                    actualCounts.containsKey(spec.getComponentId())
                            ? actualCounts.get(spec.getComponentId()) + 1 : 1);
        }
        assertTrue(expectedPaths.isEmpty());
        assertEquals(expectedCounts, actualCounts);
    }

    @Test
    public void isolatedPartsAreGlobalAndCompositeBoardsAreStepScoped() {
        int stepTwoBoards = 0;
        int stepThreeBoards = 0;
        for (ComponentReferenceSpec spec : allReferences()) {
            if (!NeobotSeedData.MAIN_BOARD.equals(spec.getComponentId())) {
                assertEquals(0, spec.getAssemblyStepNumber());
                assertFalse(spec.getFeatureRegionsNormalized().isEmpty());
                continue;
            }
            assertFalse(spec.getFeatureRegionsNormalized().isEmpty()
                    && !spec.getJpegAssetPath().endsWith("s02-00-main-board-gray.webp"));
            if (spec.getAssemblyStepNumber() == 2) {
                stepTwoBoards++;
            } else if (spec.getAssemblyStepNumber() == 3) {
                stepThreeBoards++;
            }
        }
        assertEquals(6, stepTwoBoards);
        assertEquals(1, stepThreeBoards);
    }

    @Test
    public void clippedObliqueBoardRetainsItsOffFramePhysicalCorner() {
        ComponentReferenceSpec clipped = null;
        for (ComponentReferenceSpec spec : allReferences()) {
            if (spec.getJpegAssetPath().endsWith("s02-04-first-motor-two-screws-gray.webp")) {
                clipped = spec;
                break;
            }
        }

        assertTrue(clipped != null);
        boolean hasOffFrameCorner = false;
        for (VisionPoint corner : clipped.getPlanarCornersNormalized()) {
            hasOffFrameCorner |= corner.getX() > 1.0 || corner.getY() > 1.0;
        }
        assertTrue(hasOffFrameCorner);
    }

    @Test
    public void unrelatedComponentsReceiveNoProcessedReference() {
        assertTrue(ProcessedGrayReferenceCatalog.forComponent(
                component(NeobotSeedData.MICROCONTROLLER), 50.0, 40.0).isEmpty());
    }

    private static List<ComponentReferenceSpec> allReferences() {
        List<ComponentReferenceSpec> result = new ArrayList<>();
        for (Component component : NeobotSeedData.getComponents()) {
            result.addAll(ProcessedGrayReferenceCatalog.forComponent(component, 50.0, 40.0));
        }
        return result;
    }

    private static Component component(String componentId) {
        for (Component component : NeobotSeedData.getComponents()) {
            if (componentId.equals(component.getId())) return component;
        }
        throw new AssertionError(componentId);
    }

    private static String path(String name) {
        return ProcessedGrayReferenceCatalog.ASSET_PREFIX + name + ".webp";
    }
}
