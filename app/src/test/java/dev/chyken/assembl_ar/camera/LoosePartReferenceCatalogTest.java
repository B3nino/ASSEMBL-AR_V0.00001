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

public final class LoosePartReferenceCatalogTest {
    @Test
    public void sharedCatalogContainsOnlyTheEightReviewedLoosePartCaptures() {
        Map<String, Integer> expectedCounts = new HashMap<>();
        expectedCounts.put(NeobotSeedData.MOTOR_BRACKET, 3);
        expectedCounts.put(NeobotSeedData.GEAR_MOTOR, 3);
        expectedCounts.put(NeobotSeedData.MOTOR_DRIVER, 2);
        Set<String> expectedPaths = new HashSet<>(Arrays.asList(
                "references/individual-test/motor-bracket-20260906_131438.jpg",
                "references/individual-test/motor-bracket-20260906_131502.jpg",
                "references/individual-test/motor-bracket-20260906_131707.jpg",
                "references/individual-test/gear-motor-20260906_131326.jpg",
                "references/individual-test/gear-motor-20260906_131755.jpg",
                "references/individual-test/gear-motor-20260906_131759.jpg",
                "references/individual-test/motor-driver-20260906_131356.jpg",
                "references/individual-test/motor-driver-20260906_131718.jpg"));

        List<ComponentReferenceSpec> shared = allReferences();
        assertEquals(8, shared.size());
        Set<String> ids = new HashSet<>();
        Map<String, Integer> actualCounts = new HashMap<>();
        for (ComponentReferenceSpec spec : shared) {
            assertTrue(spec.getReferenceId(), ids.add(spec.getReferenceId()));
            assertTrue(spec.getJpegAssetPath(), expectedPaths.remove(spec.getJpegAssetPath()));
            actualCounts.put(spec.getComponentId(),
                    actualCounts.containsKey(spec.getComponentId())
                            ? actualCounts.get(spec.getComponentId()) + 1 : 1);
            assertTrue(LoosePartReferenceCatalog.isReviewedReference(spec));
            assertTrue(spec.getJpegAssetPath().startsWith("references/individual-test/"));
            assertFalse(spec.getJpegAssetPath().contains("held-out"));
            assertFalse(spec.getJpegAssetPath().contains("instructional"));
            assertFalse(spec.getJpegAssetPath().contains("assembly-state"));
            assertEquals(0, spec.getAssemblyStepNumber());
            assertFalse(spec.isAdministratorReference());
            assertFalse(spec.hasVerifiedPolarityAnchors());
            assertEquals(1280, spec.getMaximumImageDimension());
            assertFalse(spec.getFeatureRegionsNormalized().isEmpty());
            assertFalse(isFullFrame(spec.getPlanarCornersNormalized()));
        }
        assertEquals(expectedCounts, actualCounts);
        assertTrue(expectedPaths.isEmpty());
    }

    @Test
    public void unrelatedComponentsAndLookalikeRegistrationsAreRejected() {
        assertTrue(catalog(NeobotSeedData.MAIN_BOARD).isEmpty());
        ComponentReferenceSpec contextual = ComponentReferenceSpec.builder(
                        NeobotSeedData.GEAR_MOTOR + "_loose_contextual",
                        NeobotSeedData.GEAR_MOTOR, "Gear motor", 70, 25)
                .jpegAssetPath("references/assembly/image-005.png").build();
        ComponentReferenceSpec instructional = ComponentReferenceSpec.builder(
                        NeobotSeedData.MOTOR_BRACKET + "_loose_instructional",
                        NeobotSeedData.MOTOR_BRACKET, "Motor bracket", 50, 40)
                .jpegAssetPath("references/instructional/gray/motor-bracket-half-gray.webp").build();
        ComponentReferenceSpec wrongIdentity = ComponentReferenceSpec.builder(
                        NeobotSeedData.GEAR_MOTOR + "_loose_wrong",
                        NeobotSeedData.GEAR_MOTOR, "Gear motor", 70, 25)
                .jpegAssetPath("references/individual-test/motor-driver-20260906_131718.jpg")
                .build();

        assertFalse(LoosePartReferenceCatalog.isReviewedReference(contextual));
        assertFalse(LoosePartReferenceCatalog.isReviewedReference(instructional));
        assertFalse(LoosePartReferenceCatalog.isReviewedReference(wrongIdentity));
    }

    @Test
    public void diagnosticFilterUsesTheSharedInstancesWithoutDuplicatingThem() {
        List<ComponentReferenceSpec> source = allReferences();
        source.addAll(allReferences());

        List<ComponentReferenceSpec> filtered = IndividualPartTestCatalog.filter(source);

        assertEquals(8, filtered.size());
        Set<String> ids = new HashSet<>();
        for (ComponentReferenceSpec spec : filtered) {
            assertTrue(ids.add(spec.getReferenceId()));
            assertTrue(LoosePartReferenceCatalog.isReviewedReference(spec));
        }
    }

    private static List<ComponentReferenceSpec> allReferences() {
        List<ComponentReferenceSpec> result = new ArrayList<>();
        for (Component component : NeobotSeedData.getComponents()) {
            result.addAll(LoosePartReferenceCatalog.forComponent(component, 50.0, 40.0));
        }
        return result;
    }

    private static List<ComponentReferenceSpec> catalog(String componentId) {
        for (Component component : NeobotSeedData.getComponents()) {
            if (componentId.equals(component.getId())) {
                return LoosePartReferenceCatalog.forComponent(component, 50.0, 40.0);
            }
        }
        throw new AssertionError(componentId);
    }

    private static boolean isFullFrame(List<VisionPoint> corners) {
        return corners.get(0).getX() == 0.0 && corners.get(0).getY() == 0.0
                && corners.get(2).getX() == 1.0 && corners.get(2).getY() == 1.0;
    }
}
