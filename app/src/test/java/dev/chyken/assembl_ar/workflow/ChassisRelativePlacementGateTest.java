package dev.chyken.assembl_ar.workflow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.vision.VisionPoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Pure JVM coverage; no OpenCV or Android camera runtime is involved. */
public final class ChassisRelativePlacementGateTest {
    private static final List<VisionPoint> PERSPECTIVE_ANCHOR = Arrays.asList(
            new VisionPoint(100.0, 80.0),
            new VisionPoint(560.0, 110.0),
            new VisionPoint(510.0, 430.0),
            new VisionPoint(130.0, 400.0));

    @Test
    public void eachBracketSlotRejectsALoosePartOrADifferentSlot() {
        for (int half = 1; half <= 4; half++) {
            ChassisRelativePlacementGate gate = ChassisRelativePlacementGate.forMotorBracketSlot(half);
            List<VisionPoint> correct = gate.projectTargets(PERSPECTIVE_ANCHOR).get(0).getCorners();
            assertTrue(gate.evaluateSingle(PERSPECTIVE_ANCHOR, correct).isAccepted());
            assertEquals(ChassisRelativePlacementGate.Reason.MISSING_ANCHOR,
                    gate.evaluateSingle(null, correct).getReason());
            assertFalse(gate.evaluateSingle(PERSPECTIVE_ANCHOR,
                    translate(correct, 0, -180)).isAccepted());
            for (int otherHalf = 1; otherHalf <= 4; otherHalf++) {
                if (otherHalf == half) continue;
                List<VisionPoint> wrongSlot = ChassisRelativePlacementGate.forMotorBracketSlot(otherHalf)
                        .projectTargets(PERSPECTIVE_ANCHOR).get(0).getCorners();
                assertFalse("Half " + half + " must not accept slot " + otherHalf,
                        gate.evaluateSingle(PERSPECTIVE_ANCHOR, wrongSlot).isAccepted());
            }
        }
    }

    @Test
    public void uprightBracketCanProjectAboveItsBoardFootpointWithoutCrossingSlots() {
        for (int half = 1; half <= 4; half++) {
            ChassisRelativePlacementGate gate = ChassisRelativePlacementGate.forMotorBracketSlot(half);
            List<VisionPoint> footprint = gate.projectTargets(PERSPECTIVE_ANCHOR).get(0).getCorners();
            // The plate stands on its tab: its outline keeps the footpoint and rises above it.
            List<VisionPoint> upright = Arrays.asList(
                    translate(footprint, 0, -65).get(0), translate(footprint, 0, -65).get(1),
                    footprint.get(2), footprint.get(3));
            assertTrue("Upright plate remains in its lateral lane for slot " + half,
                    gate.evaluateSingle(PERSPECTIVE_ANCHOR, upright).isAccepted());
            // A silhouette floating wholly above the slot never touches the seating line.
            assertFalse("Floating silhouette is not a seated bracket for slot " + half,
                    gate.evaluateSingle(PERSPECTIVE_ANCHOR, translate(footprint, 0, -110))
                            .isAccepted());
        }
    }

    @Test
    public void firstMotorDoesNotRequireSecondButRejectsTheOppositeSide() {
        ChassisRelativePlacementGate full = ChassisRelativePlacementGate.forNeobotStep(2);
        ChassisRelativePlacementGate first = full.selectTargets("left_motor");
        List<ChassisRelativePlacementGate.TargetProjection> targets = full.projectTargets(PERSPECTIVE_ANCHOR);
        assertTrue(first.evaluateSingle(PERSPECTIVE_ANCHOR, targets.get(0).getCorners()).isAccepted());
        assertFalse(first.evaluateSingle(PERSPECTIVE_ANCHOR, targets.get(1).getCorners()).isAccepted());
        assertFalse(full.evaluateSingle(PERSPECTIVE_ANCHOR, targets.get(0).getCorners()).isAccepted());
        assertEquals(1, first.getRequiredDetectionCount());
        assertEquals(2, full.getRequiredDetectionCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void selectedProfileRejectsUnknownTarget() {
        ChassisRelativePlacementGate.forNeobotStep(13).selectTargets("left_motor");
    }

    @Test(expected = IllegalArgumentException.class)
    public void selectedProfileRejectsDuplicateTarget() {
        ChassisRelativePlacementGate.forNeobotStep(13).selectTargets("left_wheel", "left_wheel");
    }

    @Test
    public void exactProjectedSingleTargetPassesUnderPerspective() {
        ChassisRelativePlacementGate gate =
                ChassisRelativePlacementGate.forNeobotStep(3);
        ChassisRelativePlacementGate.TargetProjection target =
                gate.projectTargets(PERSPECTIVE_ANCHOR).get(0);

        ChassisRelativePlacementGate.Decision decision = gate.evaluateSingle(
                PERSPECTIVE_ANCHOR, target.getCorners());

        assertTrue(decision.isAccepted());
        assertEquals(ChassisRelativePlacementGate.Reason.ACCEPTED, decision.getReason());
        assertEquals(1, decision.getMatches().size());
        assertEquals(0.0,
                decision.getMatches().get(0).getNormalizedPositionError(), 1e-9);
        assertEquals(1.0, decision.getMatches().get(0).getScaleRatio(), 1e-9);
        assertFalse(decision.getMessage().isEmpty());
    }

    @Test
    public void dualMotorsRequireBothAndAssignmentIsOrderIndependent() {
        ChassisRelativePlacementGate gate =
                ChassisRelativePlacementGate.forNeobotStep(2);
        List<ChassisRelativePlacementGate.TargetProjection> targets =
                gate.projectTargets(PERSPECTIVE_ANCHOR);

        ChassisRelativePlacementGate.Decision missing = gate.evaluateSingle(
                PERSPECTIVE_ANCHOR, targets.get(0).getCorners());
        ChassisRelativePlacementGate.Decision reversed = gate.evaluate(
                PERSPECTIVE_ANCHOR,
                Arrays.asList(targets.get(1).getCorners(), targets.get(0).getCorners()));

        assertFalse(missing.isAccepted());
        assertEquals(ChassisRelativePlacementGate.Reason.MISSING_COMPONENTS,
                missing.getReason());
        assertTrue(reversed.isAccepted());
        assertEquals("left_motor", reversed.getMatches().get(0).getTargetId());
        assertEquals("right_motor", reversed.getMatches().get(1).getTargetId());
    }

    @Test
    public void repeatedPartsCannotBeAccumulatedAcrossSeparateFrames() {
        ChassisRelativePlacementGate gate =
                ChassisRelativePlacementGate.forNeobotStep(2);
        List<ChassisRelativePlacementGate.TargetProjection> targets =
                gate.projectTargets(PERSPECTIVE_ANCHOR);

        ChassisRelativePlacementGate.Decision leftOnly = gate.evaluateSingle(
                PERSPECTIVE_ANCHOR, targets.get(0).getCorners());
        ChassisRelativePlacementGate.Decision rightOnly = gate.evaluateSingle(
                PERSPECTIVE_ANCHOR, targets.get(1).getCorners());
        ChassisRelativePlacementGate.Decision simultaneous = gate.evaluate(
                PERSPECTIVE_ANCHOR,
                Arrays.asList(targets.get(0).getCorners(), targets.get(1).getCorners()));

        assertFalse(leftOnly.isAccepted());
        assertFalse(rightOnly.isAccepted());
        assertEquals(ChassisRelativePlacementGate.Reason.MISSING_COMPONENTS,
                leftOnly.getReason());
        assertEquals(ChassisRelativePlacementGate.Reason.MISSING_COMPONENTS,
                rightOnly.getReason());
        assertTrue(simultaneous.isAccepted());
        assertEquals(2, simultaneous.getDetectedCount());
    }

    @Test
    public void mirroredUndersideAnchorPreservesCanonicalLeftAndRightTargets() {
        List<VisionPoint> undersideAnchor = Arrays.asList(
                PERSPECTIVE_ANCHOR.get(1),
                PERSPECTIVE_ANCHOR.get(0),
                PERSPECTIVE_ANCHOR.get(3),
                PERSPECTIVE_ANCHOR.get(2));
        ChassisRelativePlacementGate gate =
                ChassisRelativePlacementGate.forNeobotStep(2);
        List<ChassisRelativePlacementGate.TargetProjection> targets =
                gate.projectTargets(undersideAnchor);

        assertEquals("left_motor", targets.get(0).getId());
        assertEquals("right_motor", targets.get(1).getId());
        assertTrue("Canonical left appears on screen-right when viewing the chassis underside",
                targets.get(0).getCenter().getX() > targets.get(1).getCenter().getX());
        assertTrue(gate.evaluate(undersideAnchor, Arrays.asList(
                targets.get(0).getCorners(), targets.get(1).getCorners())).isAccepted());
    }

    @Test
    public void dualTrackersRequireDistinctTargets() {
        ChassisRelativePlacementGate gate =
                ChassisRelativePlacementGate.forNeobotStep(10);
        List<ChassisRelativePlacementGate.TargetProjection> targets =
                gate.projectTargets(PERSPECTIVE_ANCHOR);

        ChassisRelativePlacementGate.Decision accepted = gate.evaluate(
                PERSPECTIVE_ANCHOR,
                Arrays.asList(targets.get(0).getCorners(), targets.get(1).getCorners()));
        ChassisRelativePlacementGate.Decision duplicateLeft = gate.evaluate(
                PERSPECTIVE_ANCHOR,
                Arrays.asList(targets.get(0).getCorners(), targets.get(0).getCorners()));

        assertTrue(accepted.isAccepted());
        assertFalse(duplicateLeft.isAccepted());
        assertEquals(ChassisRelativePlacementGate.Reason.POSITION_MISMATCH,
                duplicateLeft.getReason());
    }

    @Test
    public void missingAndDegenerateGeometryFailClosed() {
        ChassisRelativePlacementGate gate =
                ChassisRelativePlacementGate.forNeobotStep(6);
        List<VisionPoint> degenerate = Arrays.asList(
                new VisionPoint(0.0, 0.0),
                new VisionPoint(1.0, 0.0),
                new VisionPoint(2.0, 0.0),
                new VisionPoint(3.0, 0.0));
        List<VisionPoint> collapsedComponent = Arrays.asList(
                new VisionPoint(200.0, 200.0),
                new VisionPoint(200.0, 200.0),
                new VisionPoint(200.0, 200.0),
                new VisionPoint(200.0, 200.0));

        ChassisRelativePlacementGate.Decision missingAnchor = gate.evaluateSingle(
                null, Collections.emptyList());
        ChassisRelativePlacementGate.Decision badAnchor = gate.evaluateSingle(
                degenerate, collapsedComponent);
        ChassisRelativePlacementGate.Decision badComponent = gate.evaluateSingle(
                PERSPECTIVE_ANCHOR, collapsedComponent);

        assertEquals(ChassisRelativePlacementGate.Reason.MISSING_ANCHOR,
                missingAnchor.getReason());
        assertEquals(ChassisRelativePlacementGate.Reason.DEGENERATE_ANCHOR,
                badAnchor.getReason());
        assertEquals(ChassisRelativePlacementGate.Reason.INVALID_COMPONENT_GEOMETRY,
                badComponent.getReason());
        assertFalse(missingAnchor.isAccepted());
        assertFalse(badAnchor.isAccepted());
        assertFalse(badComponent.isAccepted());
    }

    @Test
    public void positionOrientationAndScaleReturnSpecificUiReasons() {
        ChassisRelativePlacementGate gate =
                ChassisRelativePlacementGate.forNeobotStep(3);
        ChassisRelativePlacementGate.TargetProjection target =
                gate.projectTargets(PERSPECTIVE_ANCHOR).get(0);

        ChassisRelativePlacementGate.Decision moved = gate.evaluateSingle(
                PERSPECTIVE_ANCHOR, translate(target.getCorners(), 150.0, 0.0));
        ChassisRelativePlacementGate.Decision rotated = gate.evaluateSingle(
                PERSPECTIVE_ANCHOR, transform(target.getCorners(), target.getCenter(), 1.0, 65.0));
        ChassisRelativePlacementGate.Decision oversized = gate.evaluateSingle(
                PERSPECTIVE_ANCHOR, transform(target.getCorners(), target.getCenter(), 1.9, 0.0));

        assertEquals(ChassisRelativePlacementGate.Reason.POSITION_MISMATCH,
                moved.getReason());
        assertEquals(ChassisRelativePlacementGate.Reason.ORIENTATION_MISMATCH,
                rotated.getReason());
        assertEquals(ChassisRelativePlacementGate.Reason.SCALE_MISMATCH,
                oversized.getReason());
        assertTrue(moved.getMessage().contains("Move"));
        assertTrue(rotated.getMessage().contains("Rotate"));
        assertTrue(oversized.getMessage().contains("too large"));
    }

    @Test
    public void allEighteenProfilesProjectDeterministically() {
        for (int step = 1; step <= 18; step++) {
            ChassisRelativePlacementGate gate =
                    ChassisRelativePlacementGate.forNeobotStep(step);
            List<ChassisRelativePlacementGate.TargetProjection> first =
                    gate.projectTargets(PERSPECTIVE_ANCHOR);
            List<ChassisRelativePlacementGate.TargetProjection> second =
                    gate.projectTargets(PERSPECTIVE_ANCHOR);

            int expectedCount = step == 2 || step == 10 || step == 13 ? 2 : 1;
            assertEquals(expectedCount, gate.getRequiredDetectionCount());
            assertEquals(expectedCount, first.size());
            assertEquals(expectedCount, second.size());
            for (int index = 0; index < first.size(); index++) {
                assertEquals(first.get(index).getId(), second.get(index).getId());
                assertEquals(first.get(index).getCenter(), second.get(index).getCenter());
                assertEquals(first.get(index).getCorners(), second.get(index).getCorners());
                assertTrue(first.get(index).getArea() > 0.0);
                assertNotNull(first.get(index).getLabel());
            }
        }
    }

    private static List<VisionPoint> translate(List<VisionPoint> source,
                                               double deltaX, double deltaY) {
        List<VisionPoint> translated = new ArrayList<>(source.size());
        for (VisionPoint point : source) {
            translated.add(new VisionPoint(point.getX() + deltaX, point.getY() + deltaY));
        }
        return translated;
    }

    private static List<VisionPoint> transform(List<VisionPoint> source, VisionPoint center,
                                               double scale, double rotationDegrees) {
        double radians = Math.toRadians(rotationDegrees);
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        List<VisionPoint> transformed = new ArrayList<>(source.size());
        for (VisionPoint point : source) {
            double x = (point.getX() - center.getX()) * scale;
            double y = (point.getY() - center.getY()) * scale;
            transformed.add(new VisionPoint(
                    center.getX() + cosine * x - sine * y,
                    center.getY() + sine * x + cosine * y));
        }
        return transformed;
    }
}
