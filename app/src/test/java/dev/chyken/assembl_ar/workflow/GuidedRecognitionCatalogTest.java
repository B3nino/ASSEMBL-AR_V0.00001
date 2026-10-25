package dev.chyken.assembl_ar.workflow;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// This suite deliberately verifies the retained legacy guided-step adapter.
@SuppressWarnings("deprecation")
public final class GuidedRecognitionCatalogTest {
    @Test
    public void bracketInsertionsRequireTheirOwnBoardRelativeSlots() {
        String[] targets = {"left_outer_bracket", "left_inner_bracket",
                "right_inner_bracket", "right_outer_bracket"};
        for (int order = 1; order <= 4; order++) {
            GuidedCheckpoint checkpoint = checkpointAt(2, order);
            assertEquals(GuidedCheckpoint.Kind.PLACEMENT, checkpoint.getKind());
            assertEquals(NeobotSeedData.MOTOR_BRACKET, checkpoint.getComponentId());
            assertEquals(NeobotSeedData.MAIN_BOARD, checkpoint.getAnchorComponentId());
            assertEquals(1, checkpoint.getExpectedInstanceCount());
            assertTrue(checkpoint.requiresCamera());
            assertEquals(targets[order - 1], checkpoint.getPlacementGate()
                    .projectTargets(java.util.Arrays.asList(
                            new dev.chyken.assembl_ar.vision.VisionPoint(0, 0),
                            new dev.chyken.assembl_ar.vision.VisionPoint(1000, 0),
                            new dev.chyken.assembl_ar.vision.VisionPoint(1000, 1000),
                            new dev.chyken.assembl_ar.vision.VisionPoint(0, 1000)))
                    .get(0).getId());
            assertTrue(checkpoint.getPhysicalCheck().contains("slot"));
        }
    }

    @Test
    public void motorInstallationProgressesFromFirstInstalledMotorToBoth() {
        GuidedCheckpoint first = checkpointAt(2, 5);
        GuidedCheckpoint second = checkpointAt(2, 17);
        assertEquals(GuidedCheckpoint.Kind.PLACEMENT, first.getKind());
        assertEquals(GuidedCheckpoint.Kind.PLACEMENT, second.getKind());
        assertEquals(GuidedCheckpoint.MotorRequirement.FIRST, first.getMotorRequirement());
        assertEquals(GuidedCheckpoint.MotorRequirement.BOTH, second.getMotorRequirement());
        assertEquals(1, first.getExpectedInstanceCount());
        assertEquals(2, second.getExpectedInstanceCount());
        assertEquals(NeobotSeedData.MAIN_BOARD, first.getAnchorComponentId());
        assertEquals(NeobotSeedData.MAIN_BOARD, second.getAnchorComponentId());
        assertEquals(GuidedCheckpoint.Kind.PHYSICAL, checkpointAt(2, 6).getKind());
        assertTrue(checkpointAt(2, 6).getPhysicalCheck().contains("wires face inward"));
    }

    @Test
    public void invisibleFasteningAndWireEndpointsNeedTheExactPhysicalAction() {
        int[][] physicalActions = {{2, 8}, {2, 9}, {2, 13}, {2, 20}, {2, 23},
                {3, 2}, {3, 3}, {5, 3}, {5, 4}, {7, 2}, {7, 3},
                {8, 3}, {8, 4}, {8, 5}, {8, 6}, {11, 5}, {11, 14},
                {15, 2}, {15, 3}, {15, 4}, {15, 5}, {15, 6}, {16, 8}, {16, 9}};
        for (int[] action : physicalActions) {
            GuidedCheckpoint checkpoint = checkpointAt(action[0], action[1]);
            assertEquals("Section " + action[0] + ", instruction " + action[1],
                    GuidedCheckpoint.Kind.PHYSICAL, checkpoint.getKind());
            assertFalse(checkpoint.requiresCamera());
            assertNull(checkpoint.getComponentId());
            assertNull(checkpoint.getPlacementGate());
            assertEquals(0, checkpoint.getExpectedInstanceCount());
            assertFalse(checkpoint.getPhysicalCheck().trim().isEmpty());
        }
        assertEquals("Connect A-1A to pin 5.", checkpointAt(8, 3).getPhysicalCheck());
        assertEquals("Connect the right line tracker's S pin to A3.",
                checkpointAt(11, 14).getPhysicalCheck());
    }

    @Test
    public void installationUsesTheCorrectBoardAndOnlyAlreadyRequiredInstances() {
        assertEquals(NeobotSeedData.MOTOR_DRIVER, checkpointAt(3, 1).getComponentId());
        assertEquals(NeobotSeedData.MICROCONTROLLER, checkpointAt(6, 3).getComponentId());
        assertEquals(NeobotSeedData.CASTER_WHEEL, checkpointAt(9, 7).getComponentId());
        assertEquals(NeobotSeedData.LINE_TRACKER, checkpointAt(10, 6).getComponentId());
        assertEquals(1, checkpointAt(10, 6).getExpectedInstanceCount());
        assertEquals(2, checkpointAt(10, 12).getExpectedInstanceCount());
        assertEquals(1, checkpointAt(13, 2).getExpectedInstanceCount());
        assertEquals(2, checkpointAt(13, 4).getExpectedInstanceCount());
        assertEquals(NeobotSeedData.MAIN_BOARD, checkpointAt(16, 6).getAnchorComponentId());
        for (int[] action : new int[][]{{14, 2}, {17, 3}, {17, 4}, {18, 3}}) {
            assertEquals(NeobotSeedData.EXTENSION_BOARD,
                    checkpointAt(action[0], action[1]).getAnchorComponentId());
        }
    }

    @Test
    public void everyActionHasExplicitEvidenceAndInstallationFinalCardsRetainSectionGate() {
        Map<String, Component> components = new HashMap<>();
        for (Component component : NeobotSeedData.getComponents()) {
            components.put(component.getId(), component);
        }
        int identities = 0;
        for (AssemblyStep section : NeobotSeedData.getAssemblySteps()) {
            List<GuidedStep> cards = GuidedAssemblySequence.forManualStep(section);
            for (int index = 0; index < cards.size(); index++) {
                GuidedCheckpoint checkpoint = GuidedRecognitionCatalog.checkpointFor(section, cards.get(index));
                if (index == cards.size() - 1 && section.getStepNumber() != 1) {
                    assertNull(checkpoint);
                    continue;
                }
                assertNotNull(checkpoint);
                assertFalse(checkpoint.getPrompt().trim().isEmpty());
                assertFalse(checkpoint.getPhysicalCheck().trim().isEmpty());
                if (checkpoint.getKind() == GuidedCheckpoint.Kind.IDENTITY) identities++;
                if (checkpoint.requiresCamera()) {
                    Component component = components.get(checkpoint.getComponentId());
                    assertNotNull(component);
                    assertFalse(component.getReferenceImagePaths().isEmpty());
                    assertTrue(checkpoint.getExpectedInstanceCount() >= 1);
                    assertTrue(checkpoint.getExpectedInstanceCount() <= 2);
                }
                if (checkpoint.getKind() == GuidedCheckpoint.Kind.PLACEMENT) {
                    assertNotNull(checkpoint.getPlacementGate());
                    assertNotNull(components.get(checkpoint.getAnchorComponentId()));
                }
            }
        }
        assertEquals("Only preparing the main board uses identity alone", 1, identities);
    }

    @Test
    public void singleMainBoardCardRemainsRecognitionOnlyWhenItIsSectionFinal() {
        AssemblyStep preparation = NeobotSeedData.getAssemblySteps().get(0);
        List<GuidedStep> cards = GuidedAssemblySequence.forManualStep(preparation);
        assertEquals(1, cards.size());
        GuidedCheckpoint checkpoint = GuidedRecognitionCatalog.checkpointFor(preparation, cards.get(0));
        assertNotNull(checkpoint);
        assertEquals(GuidedCheckpoint.Kind.IDENTITY, checkpoint.getKind());
        assertEquals(NeobotSeedData.MAIN_BOARD, checkpoint.getComponentId());
        assertTrue(checkpoint.requiresCamera());
        assertNull(checkpoint.getPlacementGate());
    }

    @Test
    public void eachBracketAndEachMotorFastenerHasItsOwnRecognitionSubject() {
        assertSubject(2, "Insert the first bracket half", NeobotSeedData.MOTOR_BRACKET);
        assertSubject(2, "Insert the matching bracket half", NeobotSeedData.MOTOR_BRACKET);
        assertSubject(2, "Insert the third bracket half", NeobotSeedData.MOTOR_BRACKET);
        assertSubject(2, "Insert the fourth bracket half", NeobotSeedData.MOTOR_BRACKET);
        assertSubject(2, "Place the first gear motor", NeobotSeedData.GEAR_MOTOR);
        assertSubject(2, "Place the second gear motor", NeobotSeedData.GEAR_MOTOR);
        assertSubject(2, "insert the lower 30 mm screw", NeobotSeedData.SCREW_30MM);
        assertSubject(2, "Insert the upper 30 mm screw", NeobotSeedData.SCREW_30MM);
        assertSubject(2, "insert its lower 30 mm screw", NeobotSeedData.SCREW_30MM);
        assertSubject(2, "Insert the second motor's upper 30 mm screw", NeobotSeedData.SCREW_30MM);
        assertSubject(2, "Fit the nut on the opposite side of the lower screw", NeobotSeedData.NUT);
        assertSubject(2, "Fit the nut on the opposite side of the upper screw", NeobotSeedData.NUT);
        assertSubject(2, "Fit the nut on the opposite side of the second motor's lower", NeobotSeedData.NUT);
        assertSubject(2, "Fit the nut on the opposite side of the second motor's upper", NeobotSeedData.NUT);
    }

    @Test
    public void mountingHardwareIsRecognizedAcrossTheRemainingAssemblySections() {
        assertSubject(3, "Insert the two short screws", NeobotSeedData.SCREW_SHORT);
        assertSubject(3, "Place both nuts", NeobotSeedData.NUT);
        assertSubject(9, "Insert the first 20 mm screw", NeobotSeedData.SCREW_20MM);
        assertSubject(9, "Insert the second 20 mm screw", NeobotSeedData.SCREW_20MM);
        assertSubject(9, "Place the caster bracket", NeobotSeedData.CASTER_WHEEL);
        assertSubject(9, "Fit the first caster-retaining nut", NeobotSeedData.NUT);
        assertSubject(9, "Fit the second caster-retaining nut", NeobotSeedData.NUT);
        assertSubject(10, "Insert the first tracker's mounting screw", NeobotSeedData.MOUNTING_SCREW);
        assertSubject(10, "Insert the second tracker's mounting screw", NeobotSeedData.MOUNTING_SCREW);
        assertSubject(10, "Fit the first tracker's nut", NeobotSeedData.NUT);
        assertSubject(10, "Fit the second tracker's nut", NeobotSeedData.NUT);
        assertSubject(12, "Insert the standoff", NeobotSeedData.STANDOFF);
        assertSubject(12, "Fit the retaining nut", NeobotSeedData.NUT);
        assertSubject(14, "Insert the first 20mm screw", NeobotSeedData.SCREW_20MM);
        assertSubject(14, "Insert the second 20mm screw", NeobotSeedData.SCREW_20MM);
        assertSubject(14, "Fit the first fastener's nut", NeobotSeedData.NUT);
        assertSubject(14, "Fit the second fastener's nut", NeobotSeedData.NUT);
        assertSubject(16, "Insert the top screw saved from the center standoff", NeobotSeedData.MOUNTING_SCREW);
        assertSubject(16, "Tighten the center screw", NeobotSeedData.MOUNTING_SCREW);
    }

    @Test
    public void connectionsAndIndividualPlacementsHaveVisibleComponentSubjects() {
        assertSubject(1, "Place the main board", NeobotSeedData.MAIN_BOARD);
        assertSubject(5, "Insert the first motor's red and black leads", NeobotSeedData.MOTOR_DRIVER);
        assertSubject(5, "Insert the second motor's red and black leads", NeobotSeedData.MOTOR_DRIVER);
        assertSubject(6, "Orient the microcontroller", NeobotSeedData.MICROCONTROLLER);
        assertSubject(6, "Press the microcontroller", NeobotSeedData.MICROCONTROLLER);
        assertSubject(7, "Connect motor-driver VCC", NeobotSeedData.MOTOR_DRIVER);
        assertSubject(7, "Connect motor-driver GND", NeobotSeedData.MOTOR_DRIVER);
        assertSubject(8, "Connect A-1A to pin 5", NeobotSeedData.MOTOR_DRIVER);
        assertSubject(8, "Connect A-1B to pin 9", NeobotSeedData.MOTOR_DRIVER);
        assertSubject(8, "Connect B-1A to pin 6", NeobotSeedData.MOTOR_DRIVER);
        assertSubject(8, "Connect B-1B to pin 10", NeobotSeedData.MOTOR_DRIVER);
        assertSubject(11, "Press the distance sensor", NeobotSeedData.DISTANCE_SENSOR);
        assertSubject(11, "Connect the onboard LED signal", NeobotSeedData.MICROCONTROLLER);
        assertSubject(11, "Connect the Distance Sensor Trigger pin", NeobotSeedData.DISTANCE_SENSOR);
        assertSubject(11, "Connect the left line tracker's S", NeobotSeedData.LINE_TRACKER);
        assertSubject(11, "Connect the right line tracker's S", NeobotSeedData.LINE_TRACKER);
        assertSubject(13, "Press the first wheel", NeobotSeedData.WHEEL);
        assertSubject(13, "Press the second wheel", NeobotSeedData.WHEEL);
        assertSubject(14, "Position the OLED protector", NeobotSeedData.OLED_PROTECTOR);
        assertSubject(15, "Press the first connector", NeobotSeedData.DATA_CABLE);
        assertSubject(15, "Press the second connector", NeobotSeedData.DATA_CABLE);
        assertSubject(16, "Lower the extension board", NeobotSeedData.EXTENSION_BOARD);
        assertSubject(17, "Press the OLED straight", NeobotSeedData.OLED_DISPLAY);
        assertSubject(18, "Position the battery", NeobotSeedData.BATTERY_PACK);
    }

    @Test
    public void physicalActionsAndAllowedMaterialAlternativesStayManual() {
        assertSubject(2, "Hold the first motor flat", null);
        assertSubject(2, "Check that the first motor lies flat", null);
        assertSubject(2, "Apply threadlocker to both nuts on the first motor", null);
        assertSubject(6, "Confirm every microcontroller pin", null);
        assertSubject(10, "Choose the illustrated mounting hole", null);
        assertSubject(10, "Place a plastic spacer between the first", null);
        assertSubject(10, "Place a plastic spacer between the second", null);
        assertSubject(11, "Verify every sensor, LED and speaker connection", null);
        assertSubject(16, "Before lowering the board", null);
        assertSubject(18, "Choose a battery-retention method", null);
        assertSubject(18, "Apply the tape to the battery", null);
    }

    @Test
    public void everySubjectHasBundledReferencesAndFinalCardsKeepTheSectionGate() {
        Map<String, Component> components = new HashMap<>();
        for (Component component : NeobotSeedData.getComponents()) {
            components.put(component.getId(), component);
        }
        int subjects = 0;
        for (AssemblyStep section : NeobotSeedData.getAssemblySteps()) {
            List<GuidedStep> cards = GuidedAssemblySequence.forManualStep(section);
            if (section.getStepNumber() != 1) {
                assertNull("Installation final card must retain section validation: "
                                + section.getStepNumber(),
                        GuidedRecognitionCatalog.componentIdFor(section, cards.get(cards.size() - 1)));
            }
            for (GuidedStep card : cards) {
                String id = GuidedRecognitionCatalog.componentIdFor(section, card);
                if (id == null) continue;
                subjects++;
                Component component = components.get(id);
                assertNotNull("Unknown recognition component " + id, component);
                assertFalse("Checkpoint lacks a bundled reference: " + id,
                        component.getReferenceImagePaths().isEmpty());
            }
        }
        assertTrue("Intermediate actions should be broadly covered", subjects > 80);
    }

    private static void assertSubject(int sectionNumber, String instructionFragment, String expected) {
        AssemblyStep section = NeobotSeedData.getAssemblySteps().get(sectionNumber - 1);
        for (GuidedStep card : GuidedAssemblySequence.forManualStep(section)) {
            if (card.getPrimaryInstruction().getText().contains(instructionFragment)) {
                assertEquals("Wrong checkpoint for: " + card.getPrimaryInstruction().getText(),
                        expected, GuidedRecognitionCatalog.componentIdFor(section, card));
                return;
            }
        }
        throw new AssertionError("Missing authored instruction: " + instructionFragment);
    }

    private static GuidedCheckpoint checkpointAt(int sectionNumber, int order) {
        AssemblyStep section = NeobotSeedData.getAssemblySteps().get(sectionNumber - 1);
        for (GuidedStep card : GuidedAssemblySequence.forManualStep(section)) {
            if (card.getPrimaryInstruction().getOrder() == order) {
                GuidedCheckpoint checkpoint = GuidedRecognitionCatalog.checkpointFor(section, card);
                assertNotNull("Missing checkpoint " + sectionNumber + ":" + order, checkpoint);
                return checkpoint;
            }
        }
        throw new AssertionError("Missing instruction " + sectionNumber + ":" + order);
    }
}
