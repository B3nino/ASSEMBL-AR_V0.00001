package dev.chyken.assembl_ar.workflow.phase;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyInstruction;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.InstructionType;
import dev.chyken.assembl_ar.model.RequiredComponent;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;
import dev.chyken.assembl_ar.workflow.GuidedCheckpoint;
import dev.chyken.assembl_ar.workflow.GuidedRecognitionCatalog;
import dev.chyken.assembl_ar.workflow.SectionEvidencePolicy;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase.InventoryEvidence;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase.Part;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase.PartRole;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase.TargetKind;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase.VisualTarget;

import static dev.chyken.assembl_ar.data.NeobotSeedData.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class AssemblyPhaseCatalogTest {
    private static final List<VisionPoint> UNIT = Arrays.asList(new VisionPoint(0, 0),
            new VisionPoint(1, 0), new VisionPoint(1, 1), new VisionPoint(0, 1));

    @Test
    public void seventeenPhasesKeepTheirStableOrderAndIdentifiers() {
        List<AssemblyPhase> phases = AssemblyPhaseCatalog.phases();
        assertEquals(17, phases.size());
        List<String> expected = Arrays.asList(
                AssemblyPhaseCatalog.BOARD_PREPARATION, AssemblyPhaseCatalog.MOTOR_ASSEMBLIES,
                AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT, AssemblyPhaseCatalog.MOTOR_LEADS,
                AssemblyPhaseCatalog.MICROCONTROLLER_FIT, AssemblyPhaseCatalog.DRIVER_WIRING,
                AssemblyPhaseCatalog.CASTER_FIT, AssemblyPhaseCatalog.LINE_TRACKERS,
                AssemblyPhaseCatalog.DISTANCE_SENSOR_MOUNT, AssemblyPhaseCatalog.SENSOR_WIRING,
                AssemblyPhaseCatalog.CENTER_STANDOFF, AssemblyPhaseCatalog.WHEEL_PAIR,
                AssemblyPhaseCatalog.OLED_PROTECTOR_FIT, AssemblyPhaseCatalog.RIBBON_CABLE,
                AssemblyPhaseCatalog.UPPER_BOARD, AssemblyPhaseCatalog.OLED_FIT,
                AssemblyPhaseCatalog.BATTERY_FIT);
        for (int index = 0; index < phases.size(); index++) {
            assertEquals(expected.get(index), phases.get(index).getId());
            assertEquals(index + 1, phases.get(index).getNumber());
            assertSame(phases.get(index), AssemblyPhaseCatalog.byId(expected.get(index)));
        }
        assertNull(AssemblyPhaseCatalog.byId("bracket_1"));
    }

    @Test
    public void everySourceInstructionBelongsToExactlyOnePhaseInManualOrder() {
        int instructions = 0;
        int previousPhase = 0;
        for (AssemblyStep section : NeobotSeedData.getAssemblySteps()) {
            for (AssemblyInstruction instruction : section.getInstructions()) {
                instructions++;
                int owners = 0;
                AssemblyPhase owner = null;
                for (AssemblyPhase phase : AssemblyPhaseCatalog.phases()) {
                    if (phase.contains(section.getStepNumber(), instruction.getOrder())) {
                        owners++;
                        owner = phase;
                    }
                }
                assertEquals("Instruction " + section.getStepNumber() + ":" + instruction.getOrder(),
                        1, owners);
                assertSame(owner, AssemblyPhaseCatalog.phaseFor(section.getStepNumber(),
                        instruction.getOrder()));
                // Section 11's leading wiring note belongs to wiring, the only non-monotonic owner.
                if (!(section.getStepNumber() == 11 && instruction.getOrder() == 1)) {
                    assertTrue("Phase order must follow the manual", owner.getNumber() >= previousPhase);
                    previousPhase = owner.getNumber();
                }
            }
        }
        assertEquals(152, instructions);
    }

    @Test
    public void sectionElevenIsSplitIntoSensorMountingAndItsWiring() {
        AssemblyPhase mount = AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.DISTANCE_SENSOR_MOUNT);
        AssemblyPhase wiring = AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.SENSOR_WIRING);
        for (int order = 1; order <= 16; order++) {
            boolean mounting = order >= 2 && order <= 4;
            assertEquals(mounting, mount.contains(11, order));
            assertEquals(!mounting, wiring.contains(11, order));
        }
        assertTrue(AssemblyPhaseCatalog.sectionsCompletedBy(mount).isEmpty());
        assertEquals(Arrays.asList(11), AssemblyPhaseCatalog.sectionsCompletedBy(wiring));
        assertEquals(Arrays.asList(4, 5), AssemblyPhaseCatalog.sectionsCompletedBy(
                AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_LEADS)));
        assertEquals(Arrays.asList(7, 8), AssemblyPhaseCatalog.sectionsCompletedBy(
                AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.DRIVER_WIRING)));
    }

    @Test
    public void everyManualSectionIsSavedByExactlyOnePhase() {
        Map<Integer, String> savedBy = new HashMap<>();
        for (AssemblyPhase phase : AssemblyPhaseCatalog.phases()) {
            for (int section : AssemblyPhaseCatalog.sectionsCompletedBy(phase)) {
                assertNull("Section saved twice: " + section, savedBy.put(section, phase.getId()));
            }
        }
        for (int section = 1; section <= 18; section++) {
            assertNotNull("Section never saved: " + section, savedBy.get(section));
        }
    }

    @Test
    public void phaseBillsOfMaterialsPreserveEverySectionQuantity() {
        for (AssemblyStep section : NeobotSeedData.getAssemblySteps()) {
            List<AssemblyPhase> owners = AssemblyPhaseCatalog.phasesForSection(section.getStepNumber());
            for (RequiredComponent required : section.getRequiredComponents()) {
                int listed = 0;
                for (AssemblyPhase phase : owners) {
                    for (Part part : phase.getParts()) {
                        if (part.getComponentId().equals(required.getComponentId())) {
                            listed = Math.max(listed, part.getQuantity());
                        }
                    }
                }
                if (JUMPER_WIRE.equals(required.getComponentId()) && (section.getStepNumber() == 7
                        || section.getStepNumber() == 8)) {
                    assertEquals("Combined power and control leads", 6, listed);
                } else {
                    assertEquals("Section " + section.getStepNumber() + " "
                            + required.getComponentId(), required.getQuantity(), listed);
                }
            }
        }
    }

    @Test
    public void motorGroupScansBracketsTogetherAndListsHardwareHonestly() {
        AssemblyPhase motors = AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
        Part brackets = part(motors, MOTOR_BRACKET);
        assertEquals(InventoryEvidence.CAMERA_COUNT, brackets.getEvidence());
        assertEquals(4, brackets.getQuantity());
        assertEquals(InventoryEvidence.CAMERA_IDENTITY, part(motors, GEAR_MOTOR).getEvidence());
        assertEquals(2, part(motors, GEAR_MOTOR).getQuantity());
        for (String hardware : Arrays.asList(SCREW_30MM, NUT)) {
            assertEquals(InventoryEvidence.CHECKLIST, part(motors, hardware).getEvidence());
            assertEquals(4, part(motors, hardware).getQuantity());
            assertEquals(PartRole.NEW_PART, part(motors, hardware).getRole());
        }
        assertEquals(PartRole.INSTALLED_CONTEXT, part(motors, MAIN_BOARD).getRole());
        assertEquals(PartRole.TOOL_OR_CONSUMABLE, part(motors, THREADLOCKER).getRole());
        assertEquals(Arrays.asList(NAIL_POLISH), part(motors, THREADLOCKER).getAlternatives());
        assertEquals(Arrays.asList(MOTOR_BRACKET, GEAR_MOTOR), ids(motors.getCameraInventory()));
        assertEquals(Arrays.asList(NUT), part(AssemblyPhaseCatalog.byId(
                AssemblyPhaseCatalog.LINE_TRACKERS), PLASTIC_SPACER).getAlternatives());
    }

    @Test
    public void contextToolsAndUnsupportedPartsAreNeverCameraScanned() {
        for (AssemblyPhase phase : AssemblyPhaseCatalog.phases()) {
            for (Part part : phase.getParts()) {
                if (part.getRole() != PartRole.NEW_PART) {
                    assertEquals(InventoryEvidence.CHECKLIST, part.getEvidence());
                }
                if (Arrays.asList(SCREW_30MM, SCREW_20MM, SCREW_SHORT, MOUNTING_SCREW, NUT,
                        JUMPER_WIRE, THREADLOCKER, DOUBLE_SIDED_TAPE).contains(part.getComponentId())) {
                    assertFalse(part.isCameraScanned());
                }
            }
        }
        assertTrue(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_LEADS).getCameraInventory().isEmpty());
        assertTrue(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.UPPER_BOARD).getCameraInventory().isEmpty());
    }

    @Test
    public void directChecksRepeatEveryManualCheckAndSectionValidationVerbatim() {
        for (AssemblyPhase phase : AssemblyPhaseCatalog.phases()) {
            if (phase.completesOnInventory()) {
                assertTrue(phase.getDirectChecks().isEmpty());
                continue;
            }
            assertFalse(phase.getId(), phase.getDirectChecks().isEmpty());
            for (AssemblyInstruction instruction : AssemblyPhaseCatalog.instructionsOf(phase)) {
                if (instruction.getType() == InstructionType.CHECK) {
                    assertTrue(phase.getId() + " lost " + instruction.getText(),
                            phase.getDirectChecks().contains(instruction.getText()));
                }
            }
            for (int section : AssemblyPhaseCatalog.sectionsCompletedBy(phase)) {
                assertTrue(phase.getDirectChecks().contains(
                        AssemblyPhaseCatalog.section(section).getValidationPrompt()));
            }
        }
    }

    @Test
    public void exactPinMappingsSurviveAsDirectChecks() {
        List<String> driver = AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.DRIVER_WIRING).getDirectChecks();
        assertTrue(containsAll(driver, "VCC → Arduino Nano-compatible microcontroller board VIN",
                "GND → Arduino Nano-compatible microcontroller board GND", "A-1A →", "D5 / PWM",
                "A-1B →", "D9 / PWM", "B-1A →", "D6 / PWM", "B-1B →", "D10 / PWM"));
        List<String> sensors = AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.SENSOR_WIRING).getDirectChecks();
        assertTrue(containsAll(sensors, "A1 / D15", "D3", "Trigger →", "Echo →", "LEFT S",
                "A2 / D16", "RIGHT S", "A3 / D17", "LEFT V+", "RIGHT GND"));
        for (String check : sensors) {
            assertFalse("Embedded supplies are not extra jumpers", check.contains("embedded"));
        }
        List<String> oled = AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.OLED_FIT).getDirectChecks();
        assertTrue(containsAll(oled, "SCL →", "A5 / SCL", "SDA →", "A4 / SDA"));
    }

    @Test
    public void physicalOnlySectionsHaveNoCameraTargetsAndCameraSectionsKeepThem() {
        for (AssemblyPhase phase : AssemblyPhaseCatalog.phases()) {
            boolean physicalOnly = true;
            for (int section : phase.getSections()) {
                physicalOnly &= SectionEvidencePolicy.isPhysicalFinal(section);
            }
            if (physicalOnly || phase.completesOnInventory()
                    || phase.getId().equals(AssemblyPhaseCatalog.SENSOR_WIRING)
                    || phase.getId().equals(AssemblyPhaseCatalog.RIBBON_CABLE)) {
                assertFalse(phase.getId(), phase.hasVisualTargets());
            } else {
                assertTrue(phase.getId(), phase.hasVisualTargets());
            }
        }
    }

    @Test
    public void motorPhaseVerifiesBothMotorsAndEveryBracketSlot() {
        AssemblyPhase motors = AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
        assertEquals(Arrays.asList("motor_pair", "bracket_slot_1", "bracket_slot_2",
                "bracket_slot_3", "bracket_slot_4"), motors.getProbeGroups());
        String[] slotIds = {"left_outer_bracket", "left_inner_bracket", "right_inner_bracket",
                "right_outer_bracket"};
        for (int slot = 1; slot <= 4; slot++) {
            VisualTarget target = motors.getTarget("bracket_slot_" + slot);
            assertEquals(TargetKind.BRACKET_SLOT, target.getKind());
            assertEquals(slot, target.getBracketSlot());
            assertEquals(slotIds[slot - 1], target.getPlacementTargetId());
            assertEquals(slotIds[slot - 1], ChassisRelativePlacementGate.forMotorBracketSlot(slot)
                    .projectTargets(UNIT).get(0).getId());
        }
        assertEquals(TargetKind.MOTOR_SLOT, motors.getTarget("left_motor").getKind());
        assertEquals(Arrays.asList(17, 18, 19), motors.getTarget("right_motor").getCorrectionOrders());
    }

    @Test
    public void placementTargetsMatchTheAuthoredBoardRelativeProfiles() {
        for (AssemblyPhase phase : AssemblyPhaseCatalog.phases()) {
            for (VisualTarget target : phase.getTargets()) {
                assertTrue(phase.getSections().contains(target.getSection()));
                String expectedAnchor = target.getSection() == 14 || target.getSection() >= 17
                        ? EXTENSION_BOARD : MAIN_BOARD;
                assertEquals(expectedAnchor, target.getAnchorComponentId());
                if (target.getKind() == TargetKind.SECTION_PLACEMENT) {
                    Set<String> authored = new HashSet<>();
                    for (ChassisRelativePlacementGate.TargetProjection projection
                            : ChassisRelativePlacementGate.forNeobotStep(target.getSection()).projectTargets(UNIT)) {
                        authored.add(projection.getId());
                    }
                    assertTrue(target.getId(), authored.contains(target.getPlacementTargetId()));
                    assertEquals("section_" + target.getSection(), target.getProbeGroup());
                }
                for (int order : target.getCorrectionOrders()) {
                    assertTrue(target.getId() + " correction " + order,
                            phase.contains(target.getSection(), order));
                    assertTrue(isGuidedCard(target.getSection(), order));
                }
            }
        }
    }

    @Test
    public void everyPerCardCameraPlacementIsStillCheckedByItsPhase() {
        for (AssemblyStep section : NeobotSeedData.getAssemblySteps()) {
            for (GuidedStep card : GuidedAssemblySequence.forManualStep(section)) {
                GuidedCheckpoint checkpoint = GuidedRecognitionCatalog.checkpointFor(section, card);
                if (checkpoint == null || checkpoint.getKind() != GuidedCheckpoint.Kind.PLACEMENT) continue;
                AssemblyPhase phase = AssemblyPhaseCatalog.phaseFor(section.getStepNumber(),
                        card.getPrimaryInstruction().getOrder());
                boolean covered = false;
                for (VisualTarget target : phase.getTargets()) {
                    covered |= target.getComponentId().equals(checkpoint.getComponentId())
                            && target.getAnchorComponentId().equals(checkpoint.getAnchorComponentId())
                            && (checkpoint.getBracketSlot() == 0
                            || checkpoint.getBracketSlot() == target.getBracketSlot());
                }
                assertTrue("Camera check dropped: " + section.getStepNumber() + ":"
                        + card.getPrimaryInstruction().getOrder(), covered);
            }
        }
    }

    @Test
    public void dependenciesPointBackwardAndPropagateToLaterWork() {
        List<String> seen = new ArrayList<>();
        for (AssemblyPhase phase : AssemblyPhaseCatalog.phases()) {
            for (String dependency : phase.getDependsOn()) assertTrue(seen.contains(dependency));
            seen.add(phase.getId());
        }
        List<String> dependents = new ArrayList<>();
        for (AssemblyPhase phase : AssemblyPhaseCatalog.dependentsOf(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES)) {
            dependents.add(phase.getId());
        }
        assertTrue(dependents.containsAll(Arrays.asList(AssemblyPhaseCatalog.MOTOR_LEADS,
                AssemblyPhaseCatalog.WHEEL_PAIR, AssemblyPhaseCatalog.UPPER_BOARD,
                AssemblyPhaseCatalog.OLED_FIT, AssemblyPhaseCatalog.BATTERY_FIT)));
        assertFalse(dependents.contains(AssemblyPhaseCatalog.CASTER_FIT));
        assertFalse(dependents.contains(AssemblyPhaseCatalog.DRIVER_WIRING));
    }

    @Test
    public void boardPreparationFinishesOnItsIdentityScanAlone() {
        AssemblyPhase board = AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.BOARD_PREPARATION);
        assertTrue(board.completesOnInventory());
        assertFalse(board.hasVisualTargets());
        assertEquals(Arrays.asList(MAIN_BOARD), ids(board.getCameraInventory()));
    }

    private static boolean isGuidedCard(int sectionNumber, int order) {
        for (GuidedStep card : GuidedAssemblySequence.forManualStep(AssemblyPhaseCatalog.section(sectionNumber))) {
            if (card.getPrimaryInstruction().getOrder() == order) return true;
        }
        return false;
    }

    private static Part part(AssemblyPhase phase, String id) {
        for (Part part : phase.getParts()) {
            if (part.getComponentId().equals(id)) return part;
        }
        throw new AssertionError(phase.getId() + " is missing " + id);
    }

    private static List<String> ids(List<Part> parts) {
        List<String> ids = new ArrayList<>();
        for (Part part : parts) ids.add(part.getComponentId());
        return ids;
    }

    private static boolean containsAll(List<String> checks, String... fragments) {
        for (String fragment : fragments) {
            boolean found = false;
            for (String check : checks) found |= check.contains(fragment);
            if (!found) throw new AssertionError("Missing " + fragment + " in " + checks);
        }
        return true;
    }
}
