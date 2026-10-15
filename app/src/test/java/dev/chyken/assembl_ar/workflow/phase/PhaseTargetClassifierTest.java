package dev.chyken.assembl_ar.workflow.phase;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.MotorAssemblyObservation;
import dev.chyken.assembl_ar.vision.RecognitionResult.ValidationState;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase.VisualTarget;
import dev.chyken.assembl_ar.workflow.phase.PhaseTargetClassifier.PlacementFrame;
import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession.Observation;
import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession.Outcome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PhaseTargetClassifierTest {
    private static final List<VisionPoint> BOARD = Arrays.asList(new VisionPoint(100, 100),
            new VisionPoint(500, 100), new VisionPoint(500, 600), new VisionPoint(100, 600));
    private static final ChassisRelativePlacementGate TRACKERS =
            ChassisRelativePlacementGate.forNeobotStep(10);

    private static AssemblyPhase phase(String id) { return AssemblyPhaseCatalog.byId(id); }

    private static List<VisualTarget> trackerTargets() {
        return phase(AssemblyPhaseCatalog.LINE_TRACKERS).getTargets();
    }

    private static List<VisionPoint> target(String id) {
        for (ChassisRelativePlacementGate.TargetProjection projection : TRACKERS.projectTargets(BOARD)) {
            if (projection.getId().equals(id)) return projection.getCorners();
        }
        throw new AssertionError(id);
    }

    private static List<VisionPoint> shifted(List<VisionPoint> quad, double dx, double dy) {
        List<VisionPoint> moved = new ArrayList<>();
        for (VisionPoint point : quad) moved.add(new VisionPoint(point.getX() + dx, point.getY() + dy));
        return moved;
    }

    private static Outcome outcome(List<Observation> observations, String targetId) {
        for (Observation observation : observations) {
            if (observation.getTargetId().equals(targetId)) return observation.getOutcome();
        }
        throw new AssertionError(targetId);
    }

    @Test
    public void motorSlotsMapInspectorStatesToTruthfulOutcomes() {
        VisualTarget left = phase(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES).getTarget("left_motor");
        MotorAssemblyObservation.Slot present = new MotorAssemblyObservation.Slot(
                MotorAssemblyObservation.SlotState.PRESENT, 0.9, "ok", Collections.emptyList());
        MotorAssemblyObservation.Slot misaligned = new MotorAssemblyObservation.Slot(
                MotorAssemblyObservation.SlotState.MISALIGNED, 0.6, "Rotate the motor", Collections.emptyList());
        MotorAssemblyObservation good = new MotorAssemblyObservation(
                MotorAssemblyObservation.Quality.GOOD, present, misaligned);

        Observation seen = PhaseTargetClassifier.motorSlot(left, good, true, true, false);
        assertEquals(Outcome.ACCEPTED, seen.getOutcome());
        assertFalse(seen.isGateReady());
        assertTrue(PhaseTargetClassifier.motorSlot(left, good, true, true, true).isGateReady());
        Observation wrong = PhaseTargetClassifier.motorSlot(left, good, false, true, false);
        assertEquals(Outcome.WRONG_POSITION, wrong.getOutcome());
        assertEquals("Rotate the motor", wrong.getMessage());
        assertEquals(Outcome.MISSING, PhaseTargetClassifier.motorSlot(left,
                new MotorAssemblyObservation(MotorAssemblyObservation.Quality.GOOD,
                        MotorAssemblyObservation.Slot.missing("none"), present), true, true, false).getOutcome());
        assertEquals("A blurred view is never called wrong", Outcome.UNOBSERVABLE,
                PhaseTargetClassifier.motorSlot(left, MotorAssemblyObservation.unavailable(
                        MotorAssemblyObservation.Quality.BLURRED), true, true, false).getOutcome());
        assertEquals(Outcome.UNOBSERVABLE,
                PhaseTargetClassifier.motorSlot(left, good, true, false, false).getOutcome());
    }

    @Test
    public void bracketsAreEitherConfirmedInTheirSlotOrNotYetConfirmed() {
        VisualTarget slot3 = phase(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES).getTarget("bracket_slot_3");
        assertEquals(Outcome.UNOBSERVABLE,
                PhaseTargetClassifier.bracketSlot(slot3, true, false, false).getOutcome());
        assertEquals(Outcome.UNOBSERVABLE,
                PhaseTargetClassifier.bracketSlot(slot3, false, true, false).getOutcome());
        assertTrue(PhaseTargetClassifier.bracketSlot(slot3, true, true, true).isGateReady());
    }

    @Test
    public void correctPairIsSeenThenReadyOnlyWhenTheGroupGateIsReady() {
        PlacementFrame frame = new PlacementFrame(BOARD, NeobotSeedData.LINE_TRACKER,
                ValidationState.EXPECTED_COMPONENT,
                Arrays.asList(target("left_tracker"), target("right_tracker")), target("left_tracker"));
        List<Observation> seen = PhaseTargetClassifier.sectionPlacement(TRACKERS, trackerTargets(), frame, false);
        assertEquals(Outcome.ACCEPTED, outcome(seen, "left_tracker"));
        assertEquals(Outcome.ACCEPTED, outcome(seen, "right_tracker"));
        for (Observation observation : seen) assertFalse(observation.isGateReady());
        for (Observation observation : PhaseTargetClassifier.sectionPlacement(
                TRACKERS, trackerTargets(), frame, true)) {
            assertTrue(observation.isGateReady());
        }
    }

    @Test
    public void oneVisibleTrackerLeavesTheOtherUnconfirmedNotMisplaced() {
        PlacementFrame frame = new PlacementFrame(BOARD, NeobotSeedData.LINE_TRACKER,
                ValidationState.EXPECTED_COMPONENT,
                Collections.singletonList(target("left_tracker")), target("left_tracker"));
        List<Observation> observations =
                PhaseTargetClassifier.sectionPlacement(TRACKERS, trackerTargets(), frame, false);
        assertEquals(Outcome.ACCEPTED, outcome(observations, "left_tracker"));
        assertEquals(Outcome.UNOBSERVABLE, outcome(observations, "right_tracker"));
    }

    @Test
    public void aTrackerAwayFromItsTargetIsAMeasuredWrongPosition() {
        List<VisionPoint> moved = shifted(target("right_tracker"), 0, 220);
        PlacementFrame frame = new PlacementFrame(BOARD, NeobotSeedData.LINE_TRACKER,
                ValidationState.EXPECTED_COMPONENT,
                Arrays.asList(target("left_tracker"), moved), moved);
        List<Observation> observations =
                PhaseTargetClassifier.sectionPlacement(TRACKERS, trackerTargets(), frame, false);
        assertEquals(Outcome.ACCEPTED, outcome(observations, "left_tracker"));
        assertEquals(Outcome.WRONG_POSITION, outcome(observations, "right_tracker"));
    }

    @Test
    public void aDifferentRecognizedPartOnATargetIsAWrongIdentity() {
        PlacementFrame frame = new PlacementFrame(BOARD, NeobotSeedData.DISTANCE_SENSOR,
                ValidationState.COMPONENT_MISMATCH, Collections.emptyList(), target("left_tracker"));
        List<Observation> observations =
                PhaseTargetClassifier.sectionPlacement(TRACKERS, trackerTargets(), frame, false);
        Observation left = observations.get(0);
        assertEquals(Outcome.WRONG_IDENTITY, left.getOutcome());
        assertEquals(NeobotSeedData.DISTANCE_SENSOR, left.getDetectedComponentId());
        assertEquals(Outcome.UNOBSERVABLE, outcome(observations, "right_tracker"));
    }

    @Test
    public void missingBoardOrUnconfirmedDirectionIsUnclearRatherThanWrong() {
        PlacementFrame noBoard = new PlacementFrame(null, NeobotSeedData.LINE_TRACKER,
                ValidationState.EXPECTED_COMPONENT,
                Collections.singletonList(target("left_tracker")), target("left_tracker"));
        for (Observation observation : PhaseTargetClassifier.sectionPlacement(
                TRACKERS, trackerTargets(), noBoard, false)) {
            assertEquals(Outcome.UNOBSERVABLE, observation.getOutcome());
        }
        AssemblyPhase nano = phase(AssemblyPhaseCatalog.MICROCONTROLLER_FIT);
        ChassisRelativePlacementGate gate = ChassisRelativePlacementGate.forNeobotStep(6);
        List<VisionPoint> socket = gate.projectTargets(BOARD).get(0).getCorners();
        PlacementFrame unconfirmed = new PlacementFrame(BOARD, NeobotSeedData.MICROCONTROLLER,
                ValidationState.ORIENTATION_UNCONFIRMED, Collections.singletonList(socket), socket);
        assertEquals(Outcome.AMBIGUOUS, PhaseTargetClassifier.sectionPlacement(
                gate, nano.getTargets(), unconfirmed, false).get(0).getOutcome());
        PlacementFrame reversed = new PlacementFrame(BOARD, NeobotSeedData.MICROCONTROLLER,
                ValidationState.WRONG_ORIENTATION, Collections.singletonList(socket), socket);
        assertEquals(Outcome.WRONG_ORIENTATION, PhaseTargetClassifier.sectionPlacement(
                gate, nano.getTargets(), reversed, false).get(0).getOutcome());
    }

    @Test public void flatModulesWithoutTheBoardAskForATopDownView() {
        AssemblyPhase nano = phase(AssemblyPhaseCatalog.MICROCONTROLLER_FIT);
        PlacementFrame noBoard = new PlacementFrame(null, null, ValidationState.NO_COMPONENT,
                Collections.emptyList(), null);
        Observation missing = PhaseTargetClassifier.sectionPlacement(
                ChassisRelativePlacementGate.forNeobotStep(6), nano.getTargets(), noBoard, false).get(0);
        assertEquals(Outcome.UNOBSERVABLE, missing.getOutcome());
        assertTrue(missing.getMessage().startsWith("Hold the phone straight above the complete"));
        assertTrue(PhaseTargetClassifier.anchorMissingPrompt("Main board", "motor driver",
                NeobotSeedData.MOTOR_DRIVER).contains("straight above"));
        assertFalse(PhaseTargetClassifier.anchorMissingPrompt("Main board", "front caster",
                NeobotSeedData.CASTER_WHEEL).contains("straight above"));
    }
}
