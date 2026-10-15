package dev.chyken.assembl_ar.vision;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ComponentReferenceSpecTest {
    @Test
    public void optionalMetadataDefaultsToUnrestrictedFullPlanarRegion() {
        ComponentReferenceSpec spec = builder().build();

        assertTrue(spec.getFeatureRegionsNormalized().isEmpty());
        assertEquals(0, spec.getAssemblyStepNumber());
    }

    @Test
    public void featureRegionsAreDefensivelyCopiedAndDeeplyImmutable() {
        List<VisionPoint> polygon = new ArrayList<>(Arrays.asList(
                new VisionPoint(0.20, 0.25),
                new VisionPoint(0.75, 0.25),
                new VisionPoint(0.70, 0.80),
                new VisionPoint(0.25, 0.80)));
        List<List<VisionPoint>> input = new ArrayList<>();
        input.add(polygon);

        ComponentReferenceSpec spec = builder()
                .featureRegionsNormalized(input)
                .assemblyStepNumber(2)
                .build();
        polygon.clear();
        input.clear();

        assertEquals(1, spec.getFeatureRegionsNormalized().size());
        assertEquals(4, spec.getFeatureRegionsNormalized().get(0).size());
        assertEquals(new VisionPoint(0.20, 0.25),
                spec.getFeatureRegionsNormalized().get(0).get(0));
        assertEquals(2, spec.getAssemblyStepNumber());
        assertThrows(UnsupportedOperationException.class,
                () -> spec.getFeatureRegionsNormalized().add(Collections.emptyList()));
        assertThrows(UnsupportedOperationException.class,
                () -> spec.getFeatureRegionsNormalized().get(0)
                        .add(new VisionPoint(0.5, 0.5)));
    }

    @Test
    public void featureRegionRejectsTooFewOrTooManyPoints() {
        List<VisionPoint> twoPoints = Arrays.asList(
                new VisionPoint(0.1, 0.1), new VisionPoint(0.9, 0.9));
        List<VisionPoint> ninePoints = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            ninePoints.add(new VisionPoint(index / 10.0, (index % 3) / 3.0));
        }

        assertThrows(IllegalArgumentException.class,
                () -> builder().featureRegionsNormalized(
                        Collections.singletonList(twoPoints)));
        assertThrows(IllegalArgumentException.class,
                () -> builder().featureRegionsNormalized(
                        Collections.singletonList(ninePoints)));
    }

    @Test
    public void featureRegionRejectsNullAndOutOfRangePoints() {
        List<List<VisionPoint>> nullPolygon = new ArrayList<>();
        nullPolygon.add(null);
        List<VisionPoint> nullPoint = Arrays.asList(
                new VisionPoint(0.1, 0.1), null, new VisionPoint(0.9, 0.9));
        List<VisionPoint> negativeCoordinate = Arrays.asList(
                new VisionPoint(-0.01, 0.1),
                new VisionPoint(0.5, 0.1),
                new VisionPoint(0.5, 0.5));
        List<VisionPoint> oversizedCoordinate = Arrays.asList(
                new VisionPoint(0.1, 0.1),
                new VisionPoint(1.01, 0.1),
                new VisionPoint(0.5, 0.5));

        assertThrows(IllegalArgumentException.class,
                () -> builder().featureRegionsNormalized(nullPolygon));
        assertThrows(IllegalArgumentException.class,
                () -> builder().featureRegionsNormalized(
                        Collections.singletonList(nullPoint)));
        assertThrows(IllegalArgumentException.class,
                () -> builder().featureRegionsNormalized(
                        Collections.singletonList(negativeCoordinate)));
        assertThrows(IllegalArgumentException.class,
                () -> builder().featureRegionsNormalized(
                        Collections.singletonList(oversizedCoordinate)));
    }

    @Test
    public void assemblyStepRejectsValuesOutsideSupportedWorkflow() {
        assertThrows(IllegalArgumentException.class,
                () -> builder().assemblyStepNumber(-1));
        assertThrows(IllegalArgumentException.class,
                () -> builder().assemblyStepNumber(19));
    }

    @Test
    public void extrapolatedCornersRequireAnExplicitBoundedOptIn() {
        VisionPoint topLeft = new VisionPoint(0.1, 0.1);
        VisionPoint topRight = new VisionPoint(0.9, 0.1);
        VisionPoint bottomRight = new VisionPoint(1.2, 1.1);
        VisionPoint bottomLeft = new VisionPoint(0.0, 1.1);

        assertThrows(IllegalArgumentException.class, () -> builder()
                .planarReferenceCornersNormalized(
                        topLeft, topRight, bottomRight, bottomLeft)
                .build());

        ComponentReferenceSpec extrapolated = builder()
                .extrapolatedPlanarReferenceCornersNormalized(
                        topLeft, topRight, bottomRight, bottomLeft)
                .build();
        assertEquals(bottomRight, extrapolated.getPlanarCornersNormalized().get(2));

        assertThrows(IllegalArgumentException.class, () -> builder()
                .extrapolatedPlanarReferenceCornersNormalized(
                        topLeft, topRight, new VisionPoint(2.01, 1.1), bottomLeft)
                .build());
    }

    @Test
    public void stepScopedReferenceIsEligibleOnlyForItsAuthoredStep() {
        ComponentReferenceSpec scoped = builder().assemblyStepNumber(2).build();
        ComponentReferenceSpec unrestricted = builder().build();
        RecognitionRequest stepOne = requestForStep(1);
        RecognitionRequest stepTwo = requestForStep(2);

        assertFalse(ComponentRecognizer.isReferenceEligibleForRequest(scoped, null));
        assertFalse(ComponentRecognizer.isReferenceEligibleForRequest(scoped, stepOne));
        assertTrue(ComponentRecognizer.isReferenceEligibleForRequest(scoped, stepTwo));
        assertTrue(ComponentRecognizer.isReferenceEligibleForRequest(unrestricted, stepOne));
        assertTrue(ComponentRecognizer.isReferenceEligibleForRequest(unrestricted, stepTwo));
    }

    @Test
    public void everyScopedMainBoardViewIsEligibleForTheBoardFallbackTier() {
        ComponentReferenceSpec stepTwo = builder().assemblyStepNumber(2).build();
        ComponentReferenceSpec stepThree = ComponentReferenceSpec.builder(
                        "step-three-board", "main_board", "Main board", 100.0, 120.0)
                .jpegAssetPath("references/step-three-board.png")
                .assemblyStepNumber(3)
                .build();
        ComponentReferenceSpec baseline = builder().build();
        ComponentReferenceSpec otherComponent = ComponentReferenceSpec.builder(
                        "step-two-motor", "gear_motor_6v", "Motor", 70.0, 25.0)
                .jpegAssetPath("references/step-two-motor.png")
                .assemblyStepNumber(2)
                .build();

        assertTrue(ComponentRecognizer.isAssemblyReferenceEligibleForBoardFallback(stepTwo));
        assertTrue(ComponentRecognizer.isAssemblyReferenceEligibleForBoardFallback(stepThree));
        assertFalse(ComponentRecognizer.isAssemblyReferenceEligibleForBoardFallback(baseline));
        assertFalse(ComponentRecognizer.isAssemblyReferenceEligibleForBoardFallback(otherComponent));
    }

    private static ComponentReferenceSpec.Builder builder() {
        // Board fixtures share the measured kit plane; these tests only exercise reference scope.
        return ComponentReferenceSpec.builder(
                        "board-reference", "main_board", "Main board", 100.0, 120.0)
                .jpegAssetPath("references/board.jpg");
    }

    private static RecognitionRequest requestForStep(int stepNumber) {
        return new RecognitionRequest(
                RecognitionRequest.AssemblyExpectation.component("main_board"),
                null, null, "main_board", 1, stepNumber);
    }
}
