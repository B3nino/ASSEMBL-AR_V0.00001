package dev.chyken.assembl_ar.vision;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReferenceEligibilityTest {
    @Test
    public void progressReferencesHaveExactSectionEligibilityAndNoLoosePartEligibility() {
        for (ComponentReferenceSpec spec : dev.chyken.assembl_ar.camera.AssemblyProgressReferenceCatalog.all()) {
            assertFalse(ComponentRecognizer.isReferenceEligibleForRequest(spec, null));
            assertFalse(ComponentRecognizer.isReferenceEligibleForRequest(spec,
                    RecognitionRequest.unconstrained(null)));
            for (int section = 1; section <= 18; section++) {
                RecognitionRequest request = new RecognitionRequest(
                        RecognitionRequest.AssemblyExpectation.component(spec.getComponentId()),
                        null, null, "main_board", 1, section);
                org.junit.Assert.assertEquals(spec.getAssemblyStepNumber() == section,
                        ComponentRecognizer.isReferenceEligibleForRequest(spec, request));
            }
        }
    }
    @Test
    public void installedBracketPhotosAreScopedToTheFourPlacementCheckpoints() {
        for (ComponentReferenceSpec spec : dev.chyken.assembl_ar.camera.InstalledBracketReferenceCatalog.all()) {
            org.junit.Assert.assertEquals(2, spec.getAssemblyStepNumber());
            assertFalse(spec.getFeatureRegionsNormalized().isEmpty());
            if (!spec.isInstalledBracketView()) {
                org.junit.Assert.assertEquals("main_board", spec.getComponentId());
                continue;
            }
            org.junit.Assert.assertEquals("motor_bracket", spec.getComponentId());
            RecognitionRequest loose = new RecognitionRequest(
                    RecognitionRequest.AssemblyExpectation.component("motor_bracket"),
                    null, null, null, 4, 2);
            assertFalse(ComponentRecognizer.isReferenceEligibleForRequest(spec, loose));
            assertFalse(ComponentRecognizer.isReferenceEligibleForRequest(spec,
                    RecognitionRequest.unconstrained(null)));
            for (int slot = 1; slot <= 4; slot++) {
                RecognitionRequest placed = new RecognitionRequest(
                        RecognitionRequest.AssemblyExpectation.component("motor_bracket"),
                        null, null, "main_board", 1, 2).withGuidedBracketSlot(slot);
                assertTrue(ComponentRecognizer.isReferenceEligibleForRequest(spec, placed));
            }
        }
    }
    @Test
    public void physicalViewWithoutTerminalCalibrationCannotValidatePolarity() {
        ComponentReferenceSpec physical = ComponentReferenceSpec.builder(
                        "physical_driver", "motor_driver", "Motor driver", 45.0, 35.0)
                .jpegAssetPath("references/physical/motor-driver-top.jpg")
                .polarityAnchorsVerified(false)
                .build();
        assertFalse(ComponentRecognizer.isReferenceEligibleForRequest(
                physical, polarityRequest("motor_driver")));
        assertTrue(ComponentRecognizer.isReferenceEligibleForRequest(
                physical, RecognitionRequest.unconstrained(null)));
    }

    @Test
    public void adminViewWithoutCanonicalAnchorsIsExcludedFromPolarityStep() {
        ComponentReferenceSpec admin = ComponentReferenceSpec.builder(
                        "admin", "motor_driver", "Motor driver", 45.0, 35.0)
                .adminDescriptorBlob(new byte[]{1})
                .build();
        RecognitionRequest.PolarityRule polarity = new RecognitionRequest.PolarityRule(
                new VisionPoint(0.25, 0.5), new VisionPoint(0.75, 0.5), 0.2, 0.05);
        RecognitionRequest request = new RecognitionRequest(
                new RecognitionRequest.AssemblyExpectation(
                        "motor_driver", null, null, polarity),
                null,
                null);

        assertFalse(ComponentRecognizer.isReferenceEligibleForRequest(admin, request));
    }

    @Test
    public void adminViewRemainsEligibleWhenPolarityIsNotRequired() {
        ComponentReferenceSpec admin = ComponentReferenceSpec.builder(
                        "admin", "motor_driver", "Motor driver", 45.0, 35.0)
                .adminDescriptorBlob(new byte[]{1})
                .build();

        assertTrue(ComponentRecognizer.isReferenceEligibleForRequest(
                admin, RecognitionRequest.unconstrained(null)));
    }

    @Test
    public void adminAnchorRemainsEligibleForDifferentPolaritySensitiveComponent() {
        // Eligibility is independent of the corrected 100 x 120 mm physical board plane.
        ComponentReferenceSpec adminMainBoard = ComponentReferenceSpec.builder(
                        "admin_board", "main_board", "Main board", 100.0, 120.0)
                .adminDescriptorBlob(new byte[]{1})
                .build();
        RecognitionRequest request = polarityRequest("motor_driver");

        assertTrue(ComponentRecognizer.isReferenceEligibleForRequest(adminMainBoard, request));
    }

    @Test
    public void localImageOnlyAdminViewIsStillExcludedForExpectedPolarityComponent() {
        ComponentReferenceSpec admin = ComponentReferenceSpec.builder(
                        "local_admin", "motor_driver", "Motor driver", 45.0, 35.0)
                .localImageFilePath("private/reference.jpg")
                .build();

        assertFalse(ComponentRecognizer.isReferenceEligibleForRequest(
                admin, polarityRequest("motor_driver")));
    }

    @Test
    public void authoredAdminGuidesPreserveCanonicalCornerOrder() {
        // Capture geometry uses the same portrait aspect as the ruler-measured main board.
        double componentAspect = 100.0 / 120.0;
        double portraitFrameAspect = 3.0 / 4.0;
        List<VisionPoint> front = AdminReferenceGeometry.cornersForCapture(
                0.0, componentAspect, portraitFrameAspect);
        List<VisionPoint> left = AdminReferenceGeometry.cornersForCapture(
                -20.0, componentAspect, portraitFrameAspect);
        List<VisionPoint> right = AdminReferenceGeometry.cornersForCapture(
                20.0, componentAspect, portraitFrameAspect);

        assertTrue(front.get(0).getX() < front.get(1).getX());
        assertTrue(front.get(0).getY() < front.get(3).getY());
        double guidePixelAspect =
                (front.get(1).getX() - front.get(0).getX()) * portraitFrameAspect
                        / (front.get(3).getY() - front.get(0).getY());
        org.junit.Assert.assertEquals(componentAspect, guidePixelAspect, 1e-9);
        assertTrue(left.get(1).getY() > left.get(0).getY());
        assertTrue(right.get(0).getY() > right.get(1).getY());
        assertTrue(signedArea(front) > 0.0);
        assertTrue(signedArea(left) > 0.0);
        assertTrue(signedArea(right) > 0.0);
    }

    private static RecognitionRequest polarityRequest(String componentId) {
        RecognitionRequest.PolarityRule polarity = new RecognitionRequest.PolarityRule(
                new VisionPoint(0.25, 0.5), new VisionPoint(0.75, 0.5), 0.2, 0.05);
        return new RecognitionRequest(
                new RecognitionRequest.AssemblyExpectation(
                        componentId, null, null, polarity),
                null,
                null);
    }

    private static double signedArea(List<VisionPoint> points) {
        double area = 0.0;
        for (int index = 0; index < points.size(); index++) {
            VisionPoint current = points.get(index);
            VisionPoint next = points.get((index + 1) % points.size());
            area += current.getX() * next.getY() - next.getX() * current.getY();
        }
        return area * 0.5;
    }
}
