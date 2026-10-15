package dev.chyken.assembl_ar.vision;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Pure JVM policy coverage for atomic repeated-part observations. */
public final class RepeatedInstanceRecognitionPolicyTest {
    private static final List<VisionPoint> FIRST_QUAD = Arrays.asList(
            new VisionPoint(10.0, 10.0),
            new VisionPoint(50.0, 10.0),
            new VisionPoint(50.0, 40.0),
            new VisionPoint(10.0, 40.0));

    @Test
    public void requestDefaultsToOneAndExplicitlySupportsTheManuscriptDualCount() {
        RecognitionRequest.AssemblyExpectation expectation =
                RecognitionRequest.AssemblyExpectation.component("motor");

        RecognitionRequest single = new RecognitionRequest(
                expectation, null, null, "chassis");
        RecognitionRequest dual = new RecognitionRequest(
                expectation, null, null, "chassis", 2);

        assertEquals(1, single.getExpectedInstanceCount());
        assertEquals(2, dual.getExpectedInstanceCount());
        expectIllegalArgument(() -> new RecognitionRequest(
                expectation, null, null, "chassis", 0));
        expectIllegalArgument(() -> new RecognitionRequest(
                expectation, null, null, "chassis", 3));
        expectIllegalArgument(() -> new RecognitionRequest(
                null, null, null, null, 2));
    }

    @Test public void fourLooseBracketInstancesAreAllowedButCannotRequestInstalledPlacement() {
        RecognitionRequest.AssemblyExpectation brackets =
                RecognitionRequest.AssemblyExpectation.component("motor_bracket");
        RecognitionRequest set = new RecognitionRequest(brackets, null, null, null, 4, 2);
        assertEquals(4, set.getExpectedInstanceCount());
        assertTrue(set.inspectsLooseBracketSet());
        assertFalse(new RecognitionRequest(brackets, null, null, "main_board", 1, 2)
                .inspectsLooseBracketSet());
        expectIllegalArgument(() -> new RecognitionRequest(brackets, null, null, "main_board", 4, 2));
        expectIllegalArgument(() -> new RecognitionRequest(brackets, null, null, null, 5, 2));
    }

    @Test
    public void firstMotorAndCompletedPairBothInspectInstalledMotors() {
        RecognitionRequest.AssemblyExpectation motor =
                RecognitionRequest.AssemblyExpectation.component("gear_motor_6v");
        RecognitionRequest individual = new RecognitionRequest(
                motor, null, null, "main_board", 1, 2);
        RecognitionRequest completedPair = new RecognitionRequest(
                motor, null, null, "main_board", 2, 2);

        assertTrue(individual.inspectsInstalledMotors());
        assertEquals(1, individual.getExpectedInstanceCount());
        assertEquals(2, completedPair.getExpectedInstanceCount());
        assertTrue(completedPair.inspectsInstalledMotors());
        assertFalse(new RecognitionRequest(
                motor, null, null, null, 2, 2).inspectsInstalledMotors());
        assertFalse(new RecognitionRequest(
                motor, null, null, "main_board", 2, 5).inspectsInstalledMotors());
        assertFalse(new RecognitionRequest(
                RecognitionRequest.AssemblyExpectation.component("motor_bracket"),
                null, null, "main_board", 2, 2).inspectsInstalledMotors());
    }

    @Test
    public void guidedBracketSelectionIsScopedToTheRequestedSlotAndLeavesTheOriginalUnchanged() {
        RecognitionRequest original = new RecognitionRequest(
                RecognitionRequest.AssemblyExpectation.component("motor_bracket"),
                null, null, "main_board", 1, 2);
        assertEquals(0, original.getGuidedBracketSlot());
        assertEquals(3, original.withGuidedBracketSlot(3).getGuidedBracketSlot());
        assertEquals(0, original.getGuidedBracketSlot());
        expectIllegalArgument(() -> original.withGuidedBracketSlot(5));
        expectIllegalArgument(() -> original.withGuidedBracketSlot(0));
        expectIllegalArgument(() -> new RecognitionRequest(
                RecognitionRequest.AssemblyExpectation.component("gear_motor_6v"),
                null, null, "main_board", 1, 2).withGuidedBracketSlot(1));
    }

    @Test
    public void resultOwnsAnImmutablePerFrameObservationSnapshot() {
        List<VisionPoint> mutableCorners = new ArrayList<>(FIRST_QUAD);
        RecognitionResult.InstanceObservation observation =
                new RecognitionResult.InstanceObservation(
                        "motor-front",
                        "motor",
                        RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,
                        mutableCorners,
                        0.85);
        List<RecognitionResult.InstanceObservation> mutableObservations =
                new ArrayList<>(Collections.singletonList(observation));

        RecognitionResult result = RecognitionResult.builder(9L)
                .identity("motor-front", "motor", "Motor")
                .method(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY)
                .validation(RecognitionResult.ValidationState.EXPECTED_COMPONENT)
                .corners(FIRST_QUAD)
                .instances(mutableObservations)
                .build();
        mutableCorners.clear();
        mutableObservations.clear();

        assertEquals(1, result.getInstanceObservations().size());
        assertEquals(4, result.getInstanceObservations().get(0)
                .getProjectedCorners().size());
        try {
            result.getInstanceObservations().clear();
            fail("Result observation list must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void duplicateQuadsAreRejectedButSeparatedInstancesAreDistinct() {
        List<VisionPoint> smallJitter = translate(FIRST_QUAD, 2.0, 1.0);
        List<VisionPoint> separate = translate(FIRST_QUAD, 100.0, 0.0);

        assertFalse(ComponentRecognizer.areDistinctInstanceQuads(
                FIRST_QUAD, smallJitter));
        assertTrue(ComponentRecognizer.areDistinctInstanceQuads(
                FIRST_QUAD, separate));
    }

    @Test
    public void houghSelectionDropsConcentricRingsAndKeepsTwoSeparatedCircles() {
        RecognitionResult.DetectedCircle primary = circle(50.0, 50.0, 20.0);
        RecognitionResult.DetectedCircle concentric = circle(52.0, 49.0, 24.0);
        RecognitionResult.DetectedCircle secondPart = circle(150.0, 50.0, 21.0);

        List<RecognitionResult.DetectedCircle> selected =
                ComponentRecognizer.selectDistinctCircles(
                        Arrays.asList(primary, concentric, secondPart), 2);

        assertEquals(2, selected.size());
        assertEquals(primary, selected.get(0));
        assertEquals(secondPart, selected.get(1));
    }

    private static RecognitionResult.DetectedCircle circle(
            double x, double y, double radius) {
        return new RecognitionResult.DetectedCircle(new VisionPoint(x, y), radius);
    }

    private static List<VisionPoint> translate(
            List<VisionPoint> points, double x, double y) {
        List<VisionPoint> translated = new ArrayList<>(points.size());
        for (VisionPoint point : points) {
            translated.add(new VisionPoint(point.getX() + x, point.getY() + y));
        }
        return translated;
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
