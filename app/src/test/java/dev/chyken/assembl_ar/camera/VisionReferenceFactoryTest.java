package dev.chyken.assembl_ar.camera;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.PlanarCoordinateMapper;
import dev.chyken.assembl_ar.vision.VisionPoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Integrity tests for catalog policies that do not require an Android camera or OpenCV runtime. */
public final class VisionReferenceFactoryTest {
    @Test
    public void everyBoardPhotoUsesTheMeasuredPhysicalPlaneForPose() {
        Component board = component(NeobotSeedData.MAIN_BOARD);

        double[] white = VisionReferenceFactory.bundledDimensions(
                board, "references/variants/main-board-white-top.jpg",
                new double[]{130.0, 150.0});
        double[] original = VisionReferenceFactory.bundledDimensions(
                board, "references/assembly/image-001.png",
                new double[]{130.0, 150.0});
        double[] whiteUnderside = VisionReferenceFactory.bundledDimensions(
                board, "references/variants/main-board-white-underside.jpg",
                new double[]{130.0, 150.0});

        // Old saved nominal planes are deliberately passed above: a photograph's aspect
        // must not replace the ruler measurement with the larger printed mat outline.
        assertEquals(100.0, white[0], 0.0);
        assertEquals(120.0, white[1], 0.0);
        assertEquals(100.0, whiteUnderside[0], 0.0);
        assertEquals(120.0, whiteUnderside[1], 0.0);
        assertEquals(100.0, original[0], 0.0);
        assertEquals(120.0, original[1], 0.0);
    }

    @Test
    public void everyAuthoredBoardCatalogUsesTheSameRulerMeasurement() {
        Component board = component(NeobotSeedData.MAIN_BOARD);
        List<ComponentReferenceSpec> references = new ArrayList<>();
        references.addAll(OriginalBoardReferenceCatalog.forComponent(board));
        references.addAll(InstalledBracketReferenceCatalog.forComponent(board));
        references.addAll(AssemblyStateReferenceCatalog.forComponent(board));
        references.addAll(AssemblyProgressReferenceCatalog.all());
        for (ComponentReferenceSpec reference : references) {
            if (!NeobotSeedData.MAIN_BOARD.equals(reference.getComponentId())) continue;
            // Source image aspect and staged/active scope never change the physical kit size.
            assertEquals(reference.getReferenceId(), 100.0, reference.getPhysicalWidthMm(), 0.0);
            assertEquals(reference.getReferenceId(), 120.0, reference.getPhysicalHeightMm(), 0.0);
        }
    }

    @Test
    public void roi2AdminCornersAreParsedExactlyAndMalformedDataFailsClosed() {
        String json = "{\"canonicalGuide\":\"v2\",\"canonicalCorners\":"
                + "[[0.1,0.2],[0.9,0.2],[0.9,0.8],[0.1,0.8]]}";

        List<VisionPoint> parsed = VisionReferenceFactory.parseStoredAdminCorners(json);

        assertCorners(parsed,
                0.1, 0.2,
                0.9, 0.2,
                0.9, 0.8,
                0.1, 0.8);
        assertTrue(VisionReferenceFactory.parseStoredAdminCorners(
                "{\"canonicalCorners\":[[0,0],[1,0],[1,1]]}") == null);
        assertTrue(VisionReferenceFactory.parseStoredAdminCorners(
                "{\"canonicalCorners\":[[0,0],[1,1],[1,0],[0,1]]}") == null);
    }

    @Test
    public void mainBoardColourVariantsAndUndersidesUseExactCanonicalWinding() {
        Component board = component(NeobotSeedData.MAIN_BOARD);
        assertTrue(board.getReferenceImagePaths().contains(
                "references/assembly/image-001.png"));
        assertTrue(board.getReferenceImagePaths().contains(
                "references/assembly/image-034.png"));
        assertTrue(board.getReferenceImagePaths().contains(
                "references/variants/main-board-white-top.jpg"));
        assertTrue(board.getReferenceImagePaths().contains(
                "references/variants/main-board-white-underside.jpg"));
        List<VisionPoint> top = corners(board, "references/assembly/image-001.png");
        List<VisionPoint> underside = corners(board, "references/assembly/image-034.png");
        List<VisionPoint> whiteTop = corners(
                board, "references/variants/main-board-white-top.jpg");
        List<VisionPoint> whiteUnderside = corners(
                board, "references/variants/main-board-white-underside.jpg");

        assertCorners(top,
                0.069, 0.137,
                0.925, 0.137,
                0.925, 0.907,
                0.069, 0.907);
        assertCorners(underside,
                0.925, 0.157,
                0.069, 0.157,
                0.069, 0.907,
                0.925, 0.907);
        assertCorners(whiteTop,
                0.03, 0.03,
                0.97, 0.03,
                0.97, 0.97,
                0.03, 0.97);
        assertCorners(whiteUnderside,
                0.97, 0.03,
                0.03, 0.03,
                0.03, 0.97,
                0.97, 0.97);
        assertTrue("The top face must remain eligible as an upright step-1 observation",
                signedArea(top) > 0.0);
        assertTrue("The white top face must remain eligible as an upright observation",
                signedArea(whiteTop) > 0.0);
        assertTrue("The underside must be mirrored so step 1 rejects it",
                signedArea(underside) < 0.0);
        assertTrue("The white underside must be mirrored so step 1 rejects it",
                signedArea(whiteUnderside) < 0.0);

        VisionPoint canonicalLeftOnTop = PlanarCoordinateMapper.projectUnitSquare(
                top, new VisionPoint(0.20, 0.50));
        VisionPoint canonicalRightOnTop = PlanarCoordinateMapper.projectUnitSquare(
                top, new VisionPoint(0.80, 0.50));
        VisionPoint canonicalLeftOnUnderside = PlanarCoordinateMapper.projectUnitSquare(
                underside, new VisionPoint(0.20, 0.50));
        VisionPoint canonicalRightOnUnderside = PlanarCoordinateMapper.projectUnitSquare(
                underside, new VisionPoint(0.80, 0.50));
        assertTrue(canonicalLeftOnTop.getX() < canonicalRightOnTop.getX());
        assertTrue("Back-face anchors must retain front-face target handedness",
                canonicalLeftOnUnderside.getX() > canonicalRightOnUnderside.getX());
    }

    @Test
    public void circularFallbackBandsAreComponentSpecificAndDisjoint() {
        Set<String> expectedCircularComponents = new HashSet<>(Arrays.asList(
                NeobotSeedData.SCREW_SHORT,
                NeobotSeedData.MOUNTING_SCREW,
                NeobotSeedData.SCREW_20MM,
                NeobotSeedData.SCREW_30MM,
                NeobotSeedData.NUT,
                NeobotSeedData.STANDOFF,
                NeobotSeedData.CR2025_BATTERY,
                NeobotSeedData.SPEAKER,
                NeobotSeedData.DISTANCE_SENSOR,
                NeobotSeedData.CASTER_WHEEL,
                NeobotSeedData.WHEEL));
        List<ComponentReferenceSpec> circularSpecs = new ArrayList<>();

        for (Component component : NeobotSeedData.getComponents()) {
            ComponentReferenceSpec.Builder builder = ComponentReferenceSpec.builder(
                            "test_" + component.getId(), component.getId(), component.getName(),
                            50.0, 40.0)
                    .jpegAssetPath("unused.png");
            VisionReferenceFactory.applyShapeHint(builder, component);
            ComponentReferenceSpec spec = builder.build();
            if (spec.getShapeHint() == ComponentReferenceSpec.ShapeHint.CIRCULAR_HARDWARE) {
                circularSpecs.add(spec);
            }
        }

        Set<String> actualCircularComponents = new HashSet<>();
        for (ComponentReferenceSpec spec : circularSpecs) {
            assertTrue(actualCircularComponents.add(spec.getComponentId()));
        }
        assertEquals(expectedCircularComponents, actualCircularComponents);

        for (int firstIndex = 0; firstIndex < circularSpecs.size(); firstIndex++) {
            ComponentReferenceSpec first = circularSpecs.get(firstIndex);
            for (int secondIndex = firstIndex + 1;
                 secondIndex < circularSpecs.size(); secondIndex++) {
                ComponentReferenceSpec second = circularSpecs.get(secondIndex);
                boolean disjoint = first.getMaximumCircleRadiusRatio()
                        < second.getMinimumCircleRadiusRatio()
                        || second.getMaximumCircleRadiusRatio()
                        < first.getMinimumCircleRadiusRatio();
                assertTrue(first.getComponentId() + " overlaps " + second.getComponentId(), disjoint);
            }
        }
    }

    @Test
    public void everyBundledSourceHasExplicitTargetBounds() {
        for (Component component : NeobotSeedData.getComponents()) {
            for (String path : component.getReferenceImagePaths()) {
                ComponentReferenceSpec.Builder builder = ComponentReferenceSpec.builder(
                                "bounds_" + component.getId(), component.getId(),
                                component.getName(), 50.0, 40.0)
                        .jpegAssetPath(path);
                VisionReferenceFactory.applyPlanarBounds(builder, component, path);
                List<VisionPoint> corners = builder.build().getPlanarCornersNormalized();

                assertEquals(4, corners.size());
                boolean defaultFullFrame = corners.get(0).getX() == 0.0
                        && corners.get(0).getY() == 0.0
                        && corners.get(2).getX() == 1.0
                        && corners.get(2).getY() == 1.0;
                assertFalse(component.getId() + " uses the entire scene: " + path,
                        defaultFullFrame);
                for (VisionPoint corner : corners) {
                    assertTrue(path, corner.getX() >= 0.0 && corner.getX() <= 1.0);
                    assertTrue(path, corner.getY() >= 0.0 && corner.getY() <= 1.0);
                }
            }
        }
    }

    private static Component component(String componentId) {
        for (Component component : NeobotSeedData.getComponents()) {
            if (componentId.equals(component.getId())) {
                return component;
            }
        }
        throw new AssertionError("Missing seeded component " + componentId);
    }

    private static List<VisionPoint> corners(Component component, String path) {
        ComponentReferenceSpec.Builder builder = ComponentReferenceSpec.builder(
                        "bounds_" + path, component.getId(), component.getName(), 150.0, 130.0)
                .jpegAssetPath(path);
        VisionReferenceFactory.applyPlanarBounds(builder, component, path);
        return builder.build().getPlanarCornersNormalized();
    }

    private static void assertCorners(List<VisionPoint> actual, double... expected) {
        assertEquals(8, expected.length);
        assertEquals(4, actual.size());
        for (int index = 0; index < actual.size(); index++) {
            assertEquals(expected[index * 2], actual.get(index).getX(), 0.0);
            assertEquals(expected[index * 2 + 1], actual.get(index).getY(), 0.0);
        }
    }

    private static double signedArea(List<VisionPoint> corners) {
        double sum = 0.0;
        for (int index = 0; index < corners.size(); index++) {
            VisionPoint current = corners.get(index);
            VisionPoint next = corners.get((index + 1) % corners.size());
            sum += current.getX() * next.getY() - next.getX() * current.getY();
        }
        return sum * 0.5;
    }
}
