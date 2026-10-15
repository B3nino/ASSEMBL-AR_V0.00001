package dev.chyken.assembl_ar.camera;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.VisionPoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReviewedIndividualReferenceCatalogTest {
    @Test
    public void newSensorCoverageAndOppositeFacesAreExplicitlyRegistered() {
        Set<String> expected = new HashSet<>(Arrays.asList(NeobotSeedData.MICROCONTROLLER,
                NeobotSeedData.LINE_TRACKER, NeobotSeedData.DISTANCE_SENSOR,
                NeobotSeedData.CASTER_WHEEL, NeobotSeedData.MOTOR_BRACKET));
        assertEquals(expected, ReviewedIndividualReferenceCatalog.getComponentIds());
        for (ComponentReferenceSpec bracket : ReviewedIndividualReferenceCatalog.forComponent(
                NeobotSeedData.MOTOR_BRACKET)) {
            assertEquals("Bracket preparation retains validated reference feature capacity",
                    768, bracket.getMaximumImageDimension());
        }
        for (String component : Arrays.asList(NeobotSeedData.LINE_TRACKER,
                NeobotSeedData.DISTANCE_SENSOR)) {
            assertTrue(IndividualPartTestCatalog.getStandaloneComponentIds().contains(component));
            assertFalse(IndividualPartTestCatalog.getUnavailableComponentIds().contains(component));
        }
        for (String component : Arrays.asList(NeobotSeedData.MICROCONTROLLER,
                NeobotSeedData.LINE_TRACKER, NeobotSeedData.MOTOR_BRACKET)) {
            boolean front = false;
            boolean opposite = false;
            for (ComponentReferenceSpec reference : ReviewedIndividualReferenceCatalog.forComponent(component)) {
                front |= signedArea(reference.getPlanarCornersNormalized()) > 0;
                opposite |= signedArea(reference.getPlanarCornersNormalized()) < 0;
            }
            assertTrue(component + " needs a canonical front view", front);
            assertTrue(component + " needs an opposite face with retained handedness", opposite);
        }
    }

    @Test
    public void lowTextureCasterTopCannotReturnToSharedProductionOrDiagnosticCatalog() {
        String rejectedId = "caster_wheel_reviewed_caster-20260927_095720";
        String rejectedPath = "references/individual-20260927/caster-20260927_095720.png";
        for (ComponentReferenceSpec reference : ReviewedIndividualReferenceCatalog.references()) {
            assertFalse(rejectedId.equals(reference.getReferenceId()));
            assertFalse(rejectedPath.equals(reference.getJpegAssetPath()));
        }
        List<ComponentReferenceSpec> caster = ReviewedIndividualReferenceCatalog.forComponent(
                NeobotSeedData.CASTER_WHEEL);
        assertEquals("Only the validated underside from this batch remains admitted", 1, caster.size());
        assertTrue(signedArea(caster.get(0).getPlanarCornersNormalized()) < 0);
        ComponentReferenceSpec rejected = copy(caster.get(0), rejectedId,
                NeobotSeedData.CASTER_WHEEL, rejectedPath);
        assertFalse(ReviewedIndividualReferenceCatalog.isReviewedReference(rejected));
        assertTrue(IndividualPartTestCatalog.filter(Collections.singletonList(rejected)).isEmpty());
    }

    @Test
    public void authoredPhotographsHaveUniqueMaskedRegistrationsAndKnownIdentities() {
        List<ComponentReferenceSpec> references = ReviewedIndividualReferenceCatalog.references();
        assertFalse("The new capture batch must register reviewed physical photographs", references.isEmpty());
        Set<String> knownIds = new HashSet<>();
        for (Component component : NeobotSeedData.getComponents()) knownIds.add(component.getId());
        Set<String> referenceIds = new HashSet<>();
        Set<String> paths = new HashSet<>();
        Set<String> observedIds = new HashSet<>();
        for (ComponentReferenceSpec reference : references) {
            assertTrue(reference.getReferenceId(), referenceIds.add(reference.getReferenceId()));
            assertTrue(reference.getJpegAssetPath(), paths.add(reference.getJpegAssetPath()));
            assertTrue(reference.getComponentId(), knownIds.contains(reference.getComponentId()));
            assertTrue(reference.getJpegAssetPath().endsWith(".png"));
            assertFalse(reference.getJpegAssetPath().contains("instructional"));
            assertFalse(reference.getJpegAssetPath().contains("held-out"));
            assertEquals(0, reference.getAssemblyStepNumber());
            assertFalse(reference.isAdministratorReference());
            assertFalse(reference.hasVerifiedPolarityAnchors());
            assertTrue(reference.isDescriptorRegionNormalizationEnabled());
            assertFalse(reference.getFeatureRegionsNormalized().isEmpty());
            assertEquals(4, reference.getPlanarCornersNormalized().size());
            assertTrue(Math.abs(signedArea(reference.getPlanarCornersNormalized())) > 1e-6);
            assertTrue(ReviewedIndividualReferenceCatalog.isReviewedReference(reference));
            observedIds.add(reference.getComponentId());
        }
        assertEquals(observedIds, ReviewedIndividualReferenceCatalog.getComponentIds());
        assertTrue(IndividualPartTestCatalog.getStandaloneComponentIds().containsAll(observedIds));
    }

    @Test
    public void diagnosticUsesExactlyTheSharedReferencesWithoutDuplicates() {
        List<ComponentReferenceSpec> source = new ArrayList<>(ReviewedIndividualReferenceCatalog.references());
        source.addAll(ReviewedIndividualReferenceCatalog.references());
        List<ComponentReferenceSpec> filtered = IndividualPartTestCatalog.filter(source);
        assertEquals(ReviewedIndividualReferenceCatalog.references().size(), filtered.size());
        Set<String> ids = new HashSet<>();
        for (ComponentReferenceSpec reference : filtered) {
            assertTrue(ids.add(reference.getReferenceId()));
            assertTrue(ReviewedIndividualReferenceCatalog.isReviewedReference(reference));
        }
    }

    @Test
    public void filenameLookalikesAndWrongIdentitiesCannotEnterReviewedCatalog() {
        ComponentReferenceSpec authored = ReviewedIndividualReferenceCatalog.references().get(0);
        ComponentReferenceSpec wrongId = copy(authored, "unreviewed_" + authored.getReferenceId(),
                authored.getComponentId(), authored.getJpegAssetPath());
        ComponentReferenceSpec wrongPath = copy(authored, authored.getReferenceId(),
                authored.getComponentId(), "references/unreviewed/" + authored.getReferenceId() + ".png");
        ComponentReferenceSpec wrongComponent = copy(authored, authored.getReferenceId(),
                "incorrect_component", authored.getJpegAssetPath());
        ComponentReferenceSpec wrongGeometry = copyBuilder(authored, authored.getReferenceId(),
                authored.getComponentId(), authored.getJpegAssetPath())
                .featureRegionsNormalized(Collections.singletonList(Arrays.asList(
                        new VisionPoint(0.0, 0.0), new VisionPoint(.1, 0.0),
                        new VisionPoint(.1, .1), new VisionPoint(0.0, .1)))).build();
        assertFalse(ReviewedIndividualReferenceCatalog.isReviewedReference(wrongId));
        assertFalse(ReviewedIndividualReferenceCatalog.isReviewedReference(wrongPath));
        assertFalse(ReviewedIndividualReferenceCatalog.isReviewedReference(wrongComponent));
        assertFalse(ReviewedIndividualReferenceCatalog.isReviewedReference(wrongGeometry));
        assertFalse(ReviewedIndividualReferenceCatalog.isReviewedReference(null));
    }

    private static ComponentReferenceSpec copy(ComponentReferenceSpec source, String id,
            String component, String path) {
        return copyBuilder(source, id, component, path).build();
    }

    private static ComponentReferenceSpec.Builder copyBuilder(ComponentReferenceSpec source,
            String id, String component, String path) {
        ComponentReferenceSpec.Builder builder = ComponentReferenceSpec.builder(
                        id, component, source.getDisplayName(), source.getPhysicalWidthMm(),
                        source.getPhysicalHeightMm()).jpegAssetPath(path)
                .maximumImageDimension(source.getMaximumImageDimension())
                .descriptorRegionNormalization(source.isDescriptorRegionNormalizationEnabled())
                .polarityAnchorsVerified(source.hasVerifiedPolarityAnchors())
                .featureRegionsNormalized(source.getFeatureRegionsNormalized());
        List<VisionPoint> corners = source.getPlanarCornersNormalized();
        if (signedArea(corners) < 0) {
            builder.mirroredPlanarReferenceCornersNormalized(corners.get(0), corners.get(1),
                    corners.get(2), corners.get(3));
        } else {
            builder.planarReferenceCornersNormalized(corners.get(0), corners.get(1),
                    corners.get(2), corners.get(3));
        }
        return builder;
    }

    private static double signedArea(List<VisionPoint> points) {
        double result = 0.0;
        for (int index = 0; index < points.size(); index++) {
            VisionPoint a = points.get(index);
            VisionPoint b = points.get((index + 1) % points.size());
            result += a.getX() * b.getY() - b.getX() * a.getY();
        }
        return result / 2.0;
    }
}
