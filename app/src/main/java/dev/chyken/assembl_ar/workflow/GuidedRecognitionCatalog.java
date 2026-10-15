package dev.chyken.assembl_ar.workflow;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Objects;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyInstruction;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.InstructionType;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;

/**
 * Authored evidence for each NEOBOT assembly action, keyed by source instruction order.
 * Intermediate placements require the correct installed region relative to a visible board;
 * wiring, tightening, hidden pin seating and physical checks require explicit confirmation of
 * that action. Installation final cards retain the section's placement/physical validation;
 * preparing the main board completes on identity recognition alone.
 */
public final class GuidedRecognitionCatalog {
    private GuidedRecognitionCatalog() { }

    @Nullable
    public static GuidedCheckpoint checkpointFor(AssemblyStep section, GuidedStep card) {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(card, "card");
        if (!NeobotSeedData.PROJECT_ID.equals(section.getProjectId())) {
            return null;
        }
        AssemblyInstruction instruction = card.getPrimaryInstruction();
        String action = instruction.getText();
        int order = instruction.getOrder();
        int number = section.getStepNumber();
        // Preparing the board has one recognition-only card. Its workspace advice remains
        // attached context, so being the section's final card must not add a physical check.
        if (number == 1 && order == 1) {
            return GuidedCheckpoint.identity(NeobotSeedData.MAIN_BOARD,
                    "Show the main board component-side up on the work surface.", action);
        }
        if (isFinalGuidedInstruction(section, card)) {
            return null;
        }
        switch (number) {
            case 2:
                if (between(order, 1, 4)) {
                    ChassisRelativePlacementGate gate =
                            ChassisRelativePlacementGate.forMotorBracketSlot(order);
                    String[] positions = {"left outer", "left inner", "right inner", "right outer"};
                    return placement(NeobotSeedData.MOTOR_BRACKET, NeobotSeedData.MAIN_BOARD,
                            gate, "Insert bracket half " + order + " into the "
                                    + positions[order - 1] + " guide slot. Show this bracket and the "
                                    + "complete main board together, then check the tab is seated.",
                            action + " Check that this half passes through its own slot and stays upright.")
                            .forBracketSlot(order);
                }
                if (order == 5 || order == 17) {
                    boolean first = order == 5;
                    ChassisRelativePlacementGate gate = ChassisRelativePlacementGate.forNeobotStep(2);
                    if (first) gate = gate.selectTargets("left_motor");
                    return GuidedCheckpoint.placement(NeobotSeedData.GEAR_MOTOR,
                            NeobotSeedData.MAIN_BOARD, gate,
                            first ? GuidedCheckpoint.MotorRequirement.FIRST
                                    : GuidedCheckpoint.MotorRequirement.BOTH,
                            first ? "Place the first motor between the left bracket pair. Show the "
                                    + "installed motor and complete main board together."
                                    : "Place the second motor between the right bracket pair. Show both "
                                    + "installed motors and the complete main board together.",
                            action + " Check the body is between its brackets and lies flat against the board.");
                }
                break;
            case 3:
                // The board is found in nearly every top-down kit photo but few angled ones.
                if (order == 1) return boardPlacement(3, NeobotSeedData.MOTOR_DRIVER, action,
                        "Hold the phone straight above the board. Show the motor driver over its "
                                + "guide holes with the complete main board in view.");
                break;
            case 6:
                if (order == 2 || order == 3) {
                    return boardPlacement(6, NeobotSeedData.MICROCONTROLLER, action,
                            "Hold the phone straight above the board. Show the microcontroller at "
                                    + "the main-board socket with the complete board visible. Check both "
                                    + "pin rows directly before confirming.");
                }
                break;
            case 9:
                if (order == 7) return boardPlacement(9, NeobotSeedData.CASTER_WHEEL, action,
                        "Show the caster at the front mounting posts and the complete main board together. "
                                + "Check both posts pass through the bracket.");
                break;
            case 10:
                if (order == 6 || order == 12) {
                    ChassisRelativePlacementGate gate = ChassisRelativePlacementGate.forNeobotStep(10);
                    if (order == 6) gate = gate.selectTargets("left_tracker");
                    return placement(NeobotSeedData.LINE_TRACKER, NeobotSeedData.MAIN_BOARD, gate,
                            order == 6 ? "Fit the first tracker's screw at the left mounting hole. Show "
                                    + "the tracker and complete main board, then check the screw and spacer."
                                    : "Fit the second tracker's screw at the right mounting hole. Show both "
                                    + "trackers and complete main board, then check the screw and spacer.", action);
                }
                break;
            case 11:
                if (order == 3 || order == 4) return boardPlacement(11,
                        NeobotSeedData.DISTANCE_SENSOR, action,
                        "Show the distance sensor at the front header and the complete main board together. "
                                + "Check all four pins and forward-facing transducers directly.");
                break;
            case 12:
                if (order == 3) return boardPlacement(12, NeobotSeedData.STANDOFF, action,
                        "Show the standoff at the center hole and the complete main board together. "
                                + "Check it passes through the hole from the top.");
                break;
            case 13:
                if (between(order, 1, 4)) {
                    ChassisRelativePlacementGate gate = ChassisRelativePlacementGate.forNeobotStep(13);
                    if (order <= 2) gate = gate.selectTargets("left_wheel");
                    return placement(NeobotSeedData.WHEEL, NeobotSeedData.MAIN_BOARD, gate,
                            order <= 2 ? "Show the first wheel on the left motor shaft and the complete "
                                    + "main board together. Check the hub engages the shaft."
                                    : "Show both wheels on their opposite motor shafts and the complete "
                                    + "main board together. Check the second hub engages its shaft.", action);
                }
                break;
            case 14:
                if (order == 2) return placement(NeobotSeedData.OLED_PROTECTOR,
                        NeobotSeedData.EXTENSION_BOARD, ChassisRelativePlacementGate.forNeobotStep(14),
                        "Show the OLED protector over its footprint and the complete extension board together.",
                        action);
                break;
            case 16:
                if (order == 4 || order == 6) return boardPlacement(16,
                        NeobotSeedData.EXTENSION_BOARD, action,
                        "Show the extension board aligned over the main board, keeping the main-board "
                                + "outline visible. Check center-hole alignment, supports and wires directly.");
                break;
            case 17:
                if (order == 3 || order == 4) return placement(NeobotSeedData.OLED_DISPLAY,
                        NeobotSeedData.EXTENSION_BOARD, ChassisRelativePlacementGate.forNeobotStep(17),
                        "Show the OLED at its protected socket and the complete extension board together. "
                                + "Check the four pin labels, alignment and seating directly.", action);
                break;
            case 18:
                if (order == 3) return placement(NeobotSeedData.BATTERY_PACK,
                        NeobotSeedData.EXTENSION_BOARD, ChassisRelativePlacementGate.forNeobotStep(18),
                        "Show the battery in its mounting area and the complete extension board together.", action);
                break;
            default:
                break;
        }
        // No reference distinguishes a tightened nut, connected pin or seated cable end from
        // the same loose component. Requiring another identity scan would not verify the action.
        return GuidedCheckpoint.physical(action);
    }

    private static GuidedCheckpoint boardPlacement(int number, String componentId,
                                                   String action, String prompt) {
        return placement(componentId, NeobotSeedData.MAIN_BOARD,
                ChassisRelativePlacementGate.forNeobotStep(number), prompt, action);
    }

    private static GuidedCheckpoint placement(String componentId, String anchorId,
                                               ChassisRelativePlacementGate gate,
                                               String prompt, String physicalCheck) {
        return GuidedCheckpoint.placement(componentId, anchorId, gate,
                GuidedCheckpoint.MotorRequirement.NONE, prompt,
                physicalCheck + " The camera checks visible position; confirm this physical action yourself.");
    }

    /** Legacy reference subject for compatibility. Use checkpointFor for completion evidence. */
    @Deprecated
    @Nullable
    public static String componentIdFor(AssemblyStep section, GuidedStep card) {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(card, "card");
        if (!NeobotSeedData.PROJECT_ID.equals(section.getProjectId())) {
            return null;
        }
        int order = card.getPrimaryInstruction().getOrder();
        if (section.getStepNumber() == 1 && order == 1) {
            return NeobotSeedData.MAIN_BOARD;
        }
        if (isFinalGuidedInstruction(section, card)) {
            return null;
        }
        switch (section.getStepNumber()) {
            case 2:
                switch (order) {
                    case 1: case 2: case 3: case 4:
                        return NeobotSeedData.MOTOR_BRACKET;
                    case 5: case 6: case 17: case 18:
                        return NeobotSeedData.GEAR_MOTOR;
                    case 8: case 10: case 13: case 20: case 22: case 25:
                        return NeobotSeedData.SCREW_30MM;
                    case 9: case 11: case 21: case 23:
                        return NeobotSeedData.NUT;
                    default:
                        return null;
                }
            case 3:
                switch (order) {
                    case 1: return NeobotSeedData.MOTOR_DRIVER;
                    case 2: return NeobotSeedData.SCREW_SHORT;
                    case 3: return NeobotSeedData.NUT;
                    default: return null;
                }
            case 5:
                return between(order, 3, 4) || between(order, 6, 7)
                        ? NeobotSeedData.MOTOR_DRIVER : null;
            case 6:
                // The first CHECK is an orientation instruction, not a physical integrity check.
                return between(order, 1, 3) ? NeobotSeedData.MICROCONTROLLER : null;
            case 7:
                return between(order, 2, 3) ? NeobotSeedData.MOTOR_DRIVER : null;
            case 8:
                return between(order, 3, 6) ? NeobotSeedData.MOTOR_DRIVER : null;
            case 9:
                switch (order) {
                    case 1: case 2: return NeobotSeedData.SCREW_20MM;
                    case 3: case 4: case 5: case 8: case 9: case 10:
                        return NeobotSeedData.NUT;
                    case 7: return NeobotSeedData.CASTER_WHEEL;
                    default: return null;
                }
            case 10:
                switch (order) {
                    case 3: case 9: return NeobotSeedData.LINE_TRACKER;
                    case 6: case 12: return NeobotSeedData.MOUNTING_SCREW;
                    case 7: case 13: return NeobotSeedData.NUT;
                    // Spacer installation also allows stacked nuts. Keep both manual options
                    // available instead of requiring an image of only one permitted material.
                    default: return null;
                }
            case 11:
                switch (order) {
                    case 2: case 3: case 4: case 7: case 8:
                        return NeobotSeedData.DISTANCE_SENSOR;
                    case 5: case 6:
                        return NeobotSeedData.MICROCONTROLLER;
                    case 9: case 10: case 11: case 12: case 13: case 14:
                        return NeobotSeedData.LINE_TRACKER;
                    default: return null;
                }
            case 12:
                switch (order) {
                    case 3: return NeobotSeedData.STANDOFF;
                    case 4: return NeobotSeedData.NUT;
                    default: return null;
                }
            case 13:
                return between(order, 1, 4) ? NeobotSeedData.WHEEL : null;
            case 14:
                switch (order) {
                    case 2: case 3: return NeobotSeedData.OLED_PROTECTOR;
                    case 4: case 5: case 8: return NeobotSeedData.MOUNTING_SCREW;
                    case 6: case 7: return NeobotSeedData.NUT;
                    default: return null;
                }
            case 15:
                return between(order, 2, 6) ? NeobotSeedData.DATA_CABLE : null;
            case 16:
                switch (order) {
                    case 2: case 4: case 6: return NeobotSeedData.EXTENSION_BOARD;
                    case 3: return NeobotSeedData.DATA_CABLE;
                    // The retained center screw has no authored length; use generic hardware.
                    case 8: case 9: return NeobotSeedData.MOUNTING_SCREW;
                    default: return null;
                }
            case 17:
                return between(order, 2, 4) ? NeobotSeedData.OLED_DISPLAY : null;
            case 18:
                return between(order, 3, 4) ? NeobotSeedData.BATTERY_PACK : null;
            default:
                return null;
        }
    }

    private static boolean between(int value, int first, int last) {
        return value >= first && value <= last;
    }

    private static boolean isFinalGuidedInstruction(AssemblyStep section, GuidedStep card) {
        List<AssemblyInstruction> instructions = section.getInstructions();
        for (int index = instructions.size() - 1; index >= 0; index--) {
            AssemblyInstruction instruction = instructions.get(index);
            if (instruction.getType() == InstructionType.ACTION
                    || instruction.getType() == InstructionType.CHECK) {
                return instruction.getOrder() == card.getPrimaryInstruction().getOrder();
            }
        }
        return true;
    }
}
