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
import dev.chyken.assembl_ar.vision.CameraCalibration;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.RecognitionRequest;
import dev.chyken.assembl_ar.vision.VisionPoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class IndividualPartTestCatalogTest {
    @Test
    public void everyPartHasIndependentReferencesOrAnExplicitInventoryGap() {
        List<ComponentReferenceSpec> isolated = IndividualPartTestCatalog.filter(seededReferences());
        Set<String> available = new HashSet<>();
        for (ComponentReferenceSpec spec : isolated) available.add(spec.getComponentId());
        Set<String> expectedAvailable = new HashSet<>(Arrays.asList(NeobotSeedData.MAIN_BOARD,
                NeobotSeedData.MOTOR_BRACKET, NeobotSeedData.GEAR_MOTOR, NeobotSeedData.CASTER_WHEEL,
                NeobotSeedData.MOTOR_DRIVER, NeobotSeedData.MICROCONTROLLER,
                NeobotSeedData.EXTENSION_BOARD, NeobotSeedData.OLED_DISPLAY,
                NeobotSeedData.IR_RECEIVER, NeobotSeedData.IR_TRANSMITTER));
        expectedAvailable.addAll(ReviewedIndividualReferenceCatalog.getComponentIds());
        assertEquals(expectedAvailable, available);
        assertEquals(IndividualPartTestCatalog.getStandaloneComponentIds(), available);
        assertEquals(23 + ReviewedIndividualReferenceCatalog.references().size(), isolated.size());
        Set<String> missing = IndividualPartTestCatalog.getUnavailableComponentIds();
        assertEquals(NeobotSeedData.getComponents().size() - available.size(), missing.size());
        assertEquals(33, NeobotSeedData.getComponents().size());
        Set<String> accountedFor = new HashSet<>(available);
        accountedFor.addAll(missing);
        for (Component component : NeobotSeedData.getComponents()) {
            assertTrue(component.getId(), accountedFor.remove(component.getId()));
            assertFalse("An untestable part must not receive an assembly fallback: "
                    + component.getId(), available.contains(component.getId())
                    && missing.contains(component.getId()));
        }
        assertTrue(accountedFor.isEmpty());
    }

    @Test
    public void legacyEntryPointCannotReintroduceRejectedContextualTemplates() {
        List<ComponentReferenceSpec> source = seededReferences();
        source.add(spec("future_installed_screw", NeobotSeedData.SCREW_30MM,
                "references/assembly-state/installed-screw.jpg").build());
        source.add(spec("scoped_screw", NeobotSeedData.SCREW_SHORT,
                "references/assembly/image-015.png").assemblyStepNumber(3).build());
        source.add(spec("admin_screw", NeobotSeedData.SCREW_SHORT,
                "references/assembly/image-015.png").adminDescriptorBlob(new byte[]{1}).build());
        source.add(spec("illustrated_screw", NeobotSeedData.SCREW_30MM,
                "references/instructional/gray/screw-gray.webp").build());
        List<ComponentReferenceSpec> baselines = IndividualPartTestCatalog.withLegacyBaselines(source);
        Set<String> available = new HashSet<>();
        Set<String> standaloneReferenceIds = new HashSet<>();
        for (ComponentReferenceSpec spec : IndividualPartTestCatalog.filter(source)) {
            standaloneReferenceIds.add(spec.getReferenceId());
        }
        for (ComponentReferenceSpec spec : baselines) {
            available.add(spec.getComponentId());
            assertEquals(0, spec.getAssemblyStepNumber());
            assertFalse(spec.isAdministratorReference());
            assertFalse(spec.getJpegAssetPath().startsWith("references/assembly-state/"));
            assertFalse(spec.getJpegAssetPath().startsWith("references/instructional/"));
            if (IndividualPartTestCatalog.getStandaloneComponentIds().contains(spec.getComponentId())) {
                assertTrue("Reviewed parts cannot regain their contextual templates",
                        standaloneReferenceIds.contains(spec.getReferenceId()));
            }
        }
        for (ComponentReferenceSpec reference : baselines) {
            assertFalse(Arrays.asList("future_installed_screw", "scoped_screw", "admin_screw",
                    "illustrated_screw").contains(reference.getReferenceId()));
        }
        assertEquals(IndividualPartTestCatalog.getStandaloneComponentIds(), available);
        Set<String> noSource = IndividualPartTestCatalog.getUnavailableComponentIds();
        for (Component component : NeobotSeedData.getComponents()) {
            assertTrue("All 33 parts must have a baseline or an explicit missing-source status: "
                    + component.getId(), available.contains(component.getId())
                    != noSource.contains(component.getId()));
        }
    }

    @Test
    public void noInstalledPartOrIllustrationCanEnterTheTestCatalog() {
        List<ComponentReferenceSpec> source = seededReferences();
        source.add(spec("new_scene", NeobotSeedData.MAIN_BOARD,
                "references/assembly-state/future-assembled-board.jpg").build());
        source.add(spec("illustration", NeobotSeedData.MOTOR_BRACKET,
                "references/instructional/gray/motor-bracket-half-gray.webp").build());
        source.add(spec("mislabelled", NeobotSeedData.MAIN_BOARD,
                "references/physical/extension-board-white-top.jpg").build());
        source.add(spec("scoped", NeobotSeedData.MAIN_BOARD,
                "references/variants/main-board-white-top.jpg").assemblyStepNumber(2).build());
        source.add(spec("admin_override", NeobotSeedData.MAIN_BOARD,
                "references/variants/main-board-white-top.jpg")
                .adminDescriptorBlob(new byte[]{1, 2, 3}).build());
        Set<String> allowedPaths = new HashSet<>(Arrays.asList(
                "references/products/makerlab-yellow-6v-motor.jpg",
                "references/products/bc-robotics-caster-ball.jpg",
                "references/products/bc-robotics-caster-mount.jpg",
                "references/assembly/image-001.png",
                "references/variants/main-board-white-top.jpg",
                "references/variants/main-board-white-underside.jpg",
                "references/physical/extension-board-white-top.jpg",
                "references/physical/extension-board-white-underside.jpg",
                "references/physical/nano-top-usb-right.jpg",
                "references/physical/nano-top-usb-left.jpg",
                "references/physical/nano-underside.jpg",
                "references/physical/motor-driver-top.jpg",
                "references/individual-test/motor-driver-20260906_131718.jpg",
                "references/individual-test/motor-driver-20260906_131356.jpg",
                "references/individual-test/motor-bracket-20260906_131438.jpg",
                "references/individual-test/motor-bracket-20260906_131502.jpg",
                "references/individual-test/motor-bracket-20260906_131707.jpg",
                "references/individual-test/gear-motor-20260906_131326.jpg",
                "references/individual-test/gear-motor-20260906_131755.jpg",
                "references/individual-test/gear-motor-20260906_131759.jpg",
                "references/learning/image-019.png",
                "references/learning/image-042.png",
                "references/learning/image-044.png"));
        for (ComponentReferenceSpec reference : ReviewedIndividualReferenceCatalog.references()) {
            allowedPaths.add(reference.getJpegAssetPath());
        }
        List<ComponentReferenceSpec> isolated = IndividualPartTestCatalog.filter(source);
        assertEquals(allowedPaths.size(), isolated.size());
        for (ComponentReferenceSpec spec : isolated) {
            assertTrue(spec.getReferenceId(), allowedPaths.remove(spec.getJpegAssetPath()));
            assertEquals(0, spec.getAssemblyStepNumber());
            assertFalse(spec.isAdministratorReference());
            assertFalse(Arrays.asList("new_scene", "illustration", "mislabelled",
                    "scoped", "admin_override").contains(spec.getReferenceId()));
            if (!ReviewedIndividualReferenceCatalog.isReviewedReference(spec)) {
                assertFalse(spec.getJpegAssetPath(), isFullFrame(spec.getPlanarCornersNormalized()));
            }
        }
        assertTrue(allowedPaths.isEmpty());
    }

    @Test
    public void originalMotorBracketAndDriverPhotosUseReviewedComponentMasks() {
        for (ComponentReferenceSpec spec : IndividualPartTestCatalog.filter(seededReferences())) {
            if ((NeobotSeedData.GEAR_MOTOR.equals(spec.getComponentId())
                    && spec.getJpegAssetPath().startsWith("references/individual-test/"))
                    || (NeobotSeedData.MOTOR_BRACKET.equals(spec.getComponentId())
                    && spec.getJpegAssetPath().startsWith("references/individual-test/"))
                    || (NeobotSeedData.MOTOR_DRIVER.equals(spec.getComponentId())
                    && spec.getJpegAssetPath().startsWith("references/individual-test/"))) {
                assertTrue(spec.getJpegAssetPath().startsWith("references/individual-test/"));
                assertFalse(spec.getFeatureRegionsNormalized().isEmpty());
                assertFalse(spec.hasVerifiedPolarityAnchors());
                assertEquals(1280, spec.getMaximumImageDimension());
            }
            if (NeobotSeedData.OLED_DISPLAY.equals(spec.getComponentId())
                    && spec.getJpegAssetPath().equals("references/learning/image-019.png")) {
                assertTrue("Green caption must lie outside the OLED reference",
                        spec.getPlanarCornersNormalized().get(0).getY() >= .125);
            }
            if (NeobotSeedData.IR_TRANSMITTER.equals(spec.getComponentId())
                    && spec.getJpegAssetPath().equals("references/learning/image-042.png")) {
                for (VisionPoint corner : spec.getPlanarCornersNormalized()) {
                    assertTrue("The adjacent receiver starts at x=.72", corner.getX() < .72);
                }
            }
        }
    }

    @Test
    public void everyPartRequestIsIdentityOnlyIncludingBoardAndMotor() {
        CameraCalibration calibration = CameraCalibration.pinhole(900, 900, 640, 360);
        for (Component component : NeobotSeedData.getComponents()) {
            RecognitionRequest request = IndividualPartTestCatalog.requestFor(
                    component.getId(), calibration);
            assertEquals(component.getId(), request.getExpectation().getComponentId());
            assertSame(calibration, request.getCameraCalibration());
            assertNull(request.getExpectation().getOrientationDegrees());
            assertNull(request.getExpectation().getOrientationToleranceDegrees());
            assertNull(request.getExpectation().getPolarityRule());
            assertNull(request.getPolarityObservation());
            assertNull(request.getSpatialAnchorComponentId());
            assertEquals(1, request.getExpectedInstanceCount());
            assertEquals(0, request.getAssemblyStepNumber());
            assertEquals(0.001, request.getMinimumCornerAreaRatioOverride(), 0.0);
            assertTrue(request.isDescriptorOnlyIdentityAllowed());
            assertFalse(request.inspectsInstalledMotors());
        }
        assertNull(IndividualPartTestCatalog.requestFor(NeobotSeedData.GEAR_MOTOR, null)
                .getCameraCalibration());
    }

    @Test
    public void fastenerRequestCanUseItsDiagnosticAreaFloor() {
        RecognitionRequest request = IndividualPartTestCatalog.requestFor(
                NeobotSeedData.SCREW_SHORT, null, 0.00003);
        assertEquals(0.00003, request.getMinimumCornerAreaRatioOverride(), 0.0);
        assertEquals(NeobotSeedData.SCREW_SHORT,
                request.getExpectation().getComponentId());
        assertEquals(0, request.getAssemblyStepNumber());
    }

    @Test
    public void noInputAndUnreviewedAdminImagesDoNotInventTestCoverage() {
        assertTrue(IndividualPartTestCatalog.filter(Collections.emptyList()).isEmpty());
        ComponentReferenceSpec admin = ComponentReferenceSpec.builder("admin_motor",
                        NeobotSeedData.GEAR_MOTOR, "Motor", 70, 25)
                .localImageFilePath("unreviewed-assembled-motor.jpg").build();
        assertTrue(IndividualPartTestCatalog.filter(Collections.singletonList(admin)).isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyExpectedIdentityIsRejected() {
        IndividualPartTestCatalog.requestFor("  ", null);
    }

    private static List<ComponentReferenceSpec> seededReferences() {
        List<ComponentReferenceSpec> result = new ArrayList<>();
        for (Component component : NeobotSeedData.getComponents()) {
            for (String path : component.getReferenceImagePaths()) {
                ComponentReferenceSpec.Builder builder = spec(component.getId() + path,
                        component.getId(), path);
                VisionReferenceFactory.applyPlanarBounds(builder, component, path);
                VisionReferenceFactory.applyShapeHint(builder, component);
                result.add(builder.build());
            }
            result.addAll(PhysicalReferenceCatalog.forComponent(component, 50, 40));
            result.addAll(LoosePartReferenceCatalog.forComponent(component, 50, 40));
            result.addAll(ReviewedIndividualReferenceCatalog.forComponent(component.getId()));
            result.addAll(ProductReferenceCatalog.forComponent(component.getId()));
            result.addAll(ProcessedGrayReferenceCatalog.forComponent(component, 50, 40));
            result.addAll(AssemblyStateReferenceCatalog.forComponent(component));
        }
        return result;
    }

    private static ComponentReferenceSpec.Builder spec(String id, String component, String path) {
        return ComponentReferenceSpec.builder(id, component, component, 50, 40).jpegAssetPath(path);
    }

    private static boolean isFullFrame(List<VisionPoint> corners) {
        return corners.get(0).getX() == 0 && corners.get(0).getY() == 0
                && corners.get(2).getX() == 1 && corners.get(2).getY() == 1;
    }
}
