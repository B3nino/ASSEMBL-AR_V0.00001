package dev.chyken.assembl_ar.vision;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class CircularFallbackSelectionTest {
    @Test
    public void assemblyExpectationRejectsDifferentCircularCatalogEntry() {
        ComponentReferenceSpec wrongFirst = circular("nut-reference", "nut");
        ComponentReferenceSpec expectedSecond = circular("screw-reference", "screw-30mm");

        assertFalse(ComponentRecognizer.isEligibleCircularFallbackReference(
                wrongFirst, "screw-30mm"));
        assertTrue(ComponentRecognizer.isEligibleCircularFallbackReference(
                expectedSecond, "screw-30mm"));
    }

    @Test
    public void unconstrainedRecognitionStillAllowsCircularCatalogEntries() {
        assertTrue(ComponentRecognizer.isEligibleCircularFallbackReference(
                circular("nut-reference", "nut"), null));
        assertTrue(ComponentRecognizer.isEligibleCircularFallbackReference(
                circular("screw-reference", "screw-30mm"), null));
    }

    @Test
    public void planarReferenceNeverEntersCircularFallback() {
        ComponentReferenceSpec planar = ComponentReferenceSpec.builder(
                        "board-reference", "main-board", "Main board", 180.0, 140.0)
                .jpegAssetPath("unused.jpg")
                .build();

        assertFalse(ComponentRecognizer.isEligibleCircularFallbackReference(planar, null));
    }

    @Test
    public void calibratedCircleProducesConservativeCornersAndMetricPose() {
        ComponentReferenceSpec spec = ComponentReferenceSpec.builder(
                        "hardware-reference", "hardware", "Hardware", 20.0, 10.0)
                .jpegAssetPath("unused.jpg")
                .circularHardware(0.02, 0.10)
                .build();
        RecognitionResult.DetectedCircle circle = new RecognitionResult.DetectedCircle(
                new VisionPoint(320.0, 240.0), 25.0);
        CameraCalibration calibration = CameraCalibration.pinhole(
                500.0, 500.0, 320.0, 240.0);

        ComponentRecognizer.CircularGeometry geometry =
                ComponentRecognizer.estimateCircularGeometry(
                        spec, circle, calibration, 640, 480);

        assertNotNull(geometry);
        assertEquals(4, geometry.corners.size());
        assertEquals(270.0, geometry.corners.get(0).getX(), 0.0001);
        assertEquals(215.0, geometry.corners.get(0).getY(), 0.0001);
        assertEquals(370.0, geometry.corners.get(2).getX(), 0.0001);
        assertEquals(265.0, geometry.corners.get(2).getY(), 0.0001);
        assertEquals(0.0, geometry.pose.getTranslationVector()[0], 0.0001);
        assertEquals(0.0, geometry.pose.getTranslationVector()[1], 0.0001);
        assertEquals(100.0, geometry.pose.getTranslationVector()[2], 0.0001);
        assertEquals(-100.0, geometry.pose.getOpenGlModelViewMatrix()[14], 0.0001);
    }

    @Test
    public void incompatibleCalibrationFailsClosed() {
        ComponentReferenceSpec spec = circular("hardware-reference", "hardware");
        RecognitionResult.DetectedCircle circle = new RecognitionResult.DetectedCircle(
                new VisionPoint(320.0, 240.0), 25.0);
        CameraCalibration outsidePrincipalPoint = CameraCalibration.pinhole(
                500.0, 500.0, 900.0, 240.0);
        CameraCalibration distorted = CameraCalibration.pinhole(
                500.0, 500.0, 320.0, 240.0, 0.1, 0.0, 0.0, 0.0);

        assertNull(ComponentRecognizer.estimateCircularGeometry(
                spec, circle, null, 640, 480));
        assertNull(ComponentRecognizer.estimateCircularGeometry(
                spec, circle, outsidePrincipalPoint, 640, 480));
        assertNull(ComponentRecognizer.estimateCircularGeometry(
                spec, circle, distorted, 640, 480));
    }

    @Test
    public void orientationAndPolarityRequestsRejectCircleOnlyPlacementEvidence() {
        RecognitionRequest.AssemblyExpectation identityOnly =
                RecognitionRequest.AssemblyExpectation.component("hardware");
        RecognitionRequest.AssemblyExpectation oriented =
                new RecognitionRequest.AssemblyExpectation(
                        "hardware", 0.0, 20.0, null);
        RecognitionRequest.PolarityRule polarityRule = new RecognitionRequest.PolarityRule(
                new VisionPoint(0.25, 0.5), new VisionPoint(0.75, 0.5), 0.2, 0.05);
        RecognitionRequest.AssemblyExpectation polarized =
                new RecognitionRequest.AssemblyExpectation(
                        "hardware", null, null, polarityRule);

        assertTrue(ComponentRecognizer.isCircularPlacementEvidenceSufficient(identityOnly));
        assertFalse(ComponentRecognizer.isCircularPlacementEvidenceSufficient(oriented));
        assertFalse(ComponentRecognizer.isCircularPlacementEvidenceSufficient(polarized));
    }

    private static ComponentReferenceSpec circular(String referenceId, String componentId) {
        return ComponentReferenceSpec.builder(
                        referenceId, componentId, componentId, 10.0, 10.0)
                .jpegAssetPath("unused.jpg")
                .circularHardware(0.02, 0.04)
                .build();
    }
}
