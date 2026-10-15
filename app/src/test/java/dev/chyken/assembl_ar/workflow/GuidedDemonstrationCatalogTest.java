package dev.chyken.assembl_ar.workflow;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyInstruction;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.model.InstructionType;
import dev.chyken.assembl_ar.model.PinConnection;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;
import dev.chyken.assembl_ar.workflow.GuidedDemonstrationCatalog.Action;
import dev.chyken.assembl_ar.workflow.GuidedDemonstrationCatalog.Demo;
import dev.chyken.assembl_ar.workflow.GuidedDemonstrationCatalog.Relationship;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class GuidedDemonstrationCatalogTest {
    @Test public void all128CardsHaveAuthoredShortCopyAndAnUncutSourceInstruction() {
        Set<String> componentIds = new HashSet<>();
        for (Component component : NeobotSeedData.getComponents()) componentIds.add(component.getId());
        int count = 0;
        for (AssemblyStep section : NeobotSeedData.getAssemblySteps()) {
            for (GuidedStep card : GuidedAssemblySequence.forManualStep(section)) {
                Demo demo = GuidedDemonstrationCatalog.create(section, card,
                        GuidedRecognitionCatalog.checkpointFor(section, card));
                String location = section.getStepNumber() + ":" + card.getPrimaryInstruction().getOrder();
                assertNotEquals("Missing authored title at " + location, "Follow this action", demo.getShortTitle());
                assertTrue(location, demo.getShortTitle().length() <= 38);
                assertTrue(location, demo.getCaption().length() <= 64);
                assertFalse(location, demo.getCaption().isEmpty());
                assertTrue("Speech must retain the complete action " + location,
                        demo.getSpokenInstruction().startsWith(card.getPrimaryInstruction().getText()));
                assertEquals(card.getStableId(), demo.getStableId());
                assertEquals(card.getPrimaryInstruction().getReferenceImagePath(), demo.getReferenceImagePath());
                assertEquals(card.getReferenceImagePaths(), demo.getReferenceImagePaths());
                assertTrue("Part must be an actual project component " + location,
                        componentIds.contains(demo.getPartId()));
                assertFalse(location, demo.getPartLabel().isEmpty());
                count++;
            }
        }
        assertEquals(128, count);
    }

    @Test public void attachedWarningsAreSpokenExactlyOnceOnTheirRelevantCards() {
        int warnings = 0;
        for (AssemblyStep section : NeobotSeedData.getAssemblySteps()) {
            for (GuidedStep card : GuidedAssemblySequence.forManualStep(section)) {
                Demo demo = GuidedDemonstrationCatalog.create(section, card,
                        GuidedRecognitionCatalog.checkpointFor(section, card));
                for (AssemblyInstruction context : card.getContext()) {
                    if (context.getType() != InstructionType.WARNING) continue;
                    assertTrue(demo.getWarningText().contains(context.getText()));
                    int first = demo.getSpokenInstruction().indexOf(context.getText());
                    assertTrue(first >= 0);
                    assertEquals(-1, demo.getSpokenInstruction().indexOf(context.getText(), first + 1));
                    warnings++;
                }
            }
        }
        assertEquals("Do not silently omit the source warnings", 3, warnings);
        assertTrue(demo(5, 6).getSpokenInstruction().contains("right motor is flipped"));
        assertTrue(demo(17, 2).getSpokenInstruction().contains("keep the OLED display in the box"));
    }

    @Test public void bracketDemonstrationsUseFourDistinctRealSlotTargets() {
        Set<String> targetIds = new HashSet<>();
        Set<Double> xPositions = new HashSet<>();
        for (int order = 1; order <= 4; order++) {
            Demo demo = demo(2, order);
            assertEquals(Action.INSERT, demo.getAction());
            assertEquals(NeobotSeedData.MOTOR_BRACKET, demo.getPartId());
            assertEquals(NeobotSeedData.MAIN_BOARD, demo.getAnchorComponentId());
            assertEquals(1, demo.getTargets().size());
            targetIds.add(demo.getTargets().get(0).getId());
            xPositions.add(demo.getTargets().get(0).getCenter().getX());
        }
        assertEquals(4, targetIds.size());
        assertEquals(4, xPositions.size());
    }

    @Test public void bracketAndMotorDemosExplainTheBoardAndSupportRelationship() {
        String[] slotNames = {"left outer", "left inner", "right inner", "right outer"};
        for (int order = 1; order <= 4; order++) {
            Demo bracket = demo(2, order);
            assertEquals(Relationship.BRACKET_IN_BOARD_SLOT, bracket.getRelationship());
            assertTrue(bracket.getShortTitle().contains(slotNames[order - 1]));
            assertTrue(bracket.getAttachmentCaption().contains("board slot"));
            assertEquals(4, bracket.getContextTargets().size());
            assertEquals(1, bracket.getAnimatedTargets().size());
            assertEquals(bracket.getTargets().get(0).getId(),
                    bracket.getContextTargets().get(order - 1).getId());
        }
        for (int order : new int[]{5, 17}) {
            Demo motor = demo(2, order);
            assertEquals(Relationship.MOTOR_BETWEEN_BRACKETS, motor.getRelationship());
            assertEquals(NeobotSeedData.MAIN_BOARD, motor.getAnchorComponentId());
            assertEquals(4, motor.getContextTargets().size());
            assertTrue(motor.getCaption().contains(order == 5 ? "1 + 2" : "3 + 4"));
            assertTrue(motor.getCaption().contains("board center"));
            assertTrue(motor.getAttachmentCaption().contains("bracket pair"));
        }
    }

    @Test public void secondInstallationsAnimateOnlyTheNewPartAndChecksKeepAllPartsStill() {
        for (int[] action : new int[][]{{2, 17}, {10, 12}, {13, 3}, {13, 4}}) {
            Demo nextPart = demo(action[0], action[1]);
            assertEquals(2, nextPart.getTargets().size());
            assertEquals(1, nextPart.getAnimatedTargets().size());
            assertEquals(nextPart.getTargets().get(1).getId(), nextPart.getAnimatedTargets().get(0).getId());
        }
        for (int[] check : new int[][]{{2, 27}, {10, 14}, {13, 5}}) {
            Demo overview = demo(check[0], check[1]);
            assertEquals(Action.CHECK, overview.getAction());
            assertEquals(2, overview.getTargets().size());
            assertTrue(overview.getAnimatedTargets().isEmpty());
        }
        assertEquals(1, demo(2, 5).getAnimatedTargets().size());
        assertTrue("Other instructions must not invent bracket supports",
                demo(3, 1).getContextTargets().isEmpty());
    }

    @Test public void demonstrationTargetsExactlyMatchValidationTargetsWithoutClippingOutboardWheels() {
        List<VisionPoint> unit = Arrays.asList(new VisionPoint(0, 0), new VisionPoint(1, 0),
                new VisionPoint(1, 1), new VisionPoint(0, 1));
        for (AssemblyStep section : NeobotSeedData.getAssemblySteps()) {
            for (GuidedStep card : GuidedAssemblySequence.forManualStep(section)) {
                GuidedCheckpoint checkpoint = GuidedRecognitionCatalog.checkpointFor(section, card);
                if (checkpoint == null || checkpoint.getPlacementGate() == null) continue;
                Demo demo = GuidedDemonstrationCatalog.create(section, card, checkpoint);
                List<ChassisRelativePlacementGate.TargetProjection> expected =
                        checkpoint.getPlacementGate().projectTargets(unit);
                assertEquals(expected.size(), demo.getTargets().size());
                for (int index = 0; index < expected.size(); index++) {
                    assertEquals(expected.get(index).getId(), demo.getTargets().get(index).getId());
                    for (int corner = 0; corner < 4; corner++) {
                        VisionPoint actual = demo.getTargets().get(index).getCorners().get(corner);
                        VisionPoint wanted = expected.get(index).getCorners().get(corner);
                        assertEquals(wanted.getX(), actual.getX(), 1e-9);
                        assertEquals(wanted.getY(), actual.getY(), 1e-9);
                    }
                }
            }
        }
        assertTrue(demo(13, 1).getTargets().get(0).getCorners().stream().anyMatch(p -> p.getX() < 0));
    }

    @Test public void firstAndSecondInstallationsPreserveTheirDifferentTargetSets() {
        assertEquals(1, demo(2, 5).getTargets().size());
        assertEquals(2, demo(2, 17).getTargets().size());
        assertEquals("left_motor", demo(2, 5).getTargets().get(0).getId());
        assertEquals(1, demo(10, 6).getTargets().size());
        assertEquals(2, demo(10, 12).getTargets().size());
        assertEquals(1, demo(13, 2).getTargets().size());
        assertEquals(2, demo(13, 4).getTargets().size());
        assertEquals("Extension board", demo(14, 2).getAnchorLabel());
        assertEquals("Extension board", demo(17, 4).getAnchorLabel());
        assertEquals("Extension board", demo(18, 3).getAnchorLabel());
    }

    @Test public void physicalActionsNeverBorrowUnrelatedSectionPlacementGeometry() {
        for (int[] key : new int[][]{{2, 8}, {2, 9}, {2, 14}, {3, 4}, {4, 1},
                {5, 3}, {6, 5}, {9, 3}, {11, 16}, {12, 6}, {14, 9}, {15, 6}, {16, 9}}) {
            Demo demo = demo(key[0], key[1]);
            assertTrue(Arrays.toString(key), demo.getTargets().isEmpty());
            assertEquals("Manual illustration", demo.getViewLabel());
            assertFalse(demo.getReferenceImagePath().isEmpty());
        }
    }

    @Test public void wiringDemoUsesTheCurrentConnectionAndNeverInventsEmbeddedSupplyJumpers() {
        assertConnection(demo(7, 2), "VCC", "VIN");
        assertConnection(demo(7, 3), "GND", "GND");
        assertConnection(demo(8, 3), "A-1A", "D5 / PWM");
        assertConnection(demo(8, 4), "A-1B", "D9 / PWM");
        assertConnection(demo(8, 5), "B-1A", "D6 / PWM");
        assertConnection(demo(8, 6), "B-1B", "D10 / PWM");
        assertConnection(demo(11, 7), "Trigger", "D2");
        assertConnection(demo(11, 8), "Echo", "D4");
        assertConnection(demo(11, 11), "LEFT S", "A2 / D16");
        assertConnection(demo(11, 14), "RIGHT S", "A3 / D17");
        assertTrue(demo(11, 2).getPinConnections().isEmpty());
        assertTrue(demo(11, 3).getPinConnections().isEmpty());
        List<PinConnection> checkedSignals = demo(11, 15).getPinConnections();
        assertEquals(10, checkedSignals.size());
        assertTrue(checkedSignals.stream().noneMatch(p -> p.getOrder() == 5 || p.getOrder() == 6));
        assertTrue("Ribbon cable is a connector action, not nine separate jumpers",
                demo(15, 6).getPinConnections().isEmpty());
    }

    private static void assertConnection(Demo demo, String source, String target) {
        assertEquals(Action.WIRE, demo.getAction());
        assertEquals(1, demo.getPinConnections().size());
        assertEquals(source, demo.getPinConnections().get(0).getSourcePin());
        assertEquals(target, demo.getPinConnections().get(0).getTargetPin());
    }

    private static Demo demo(int number, int order) {
        AssemblyStep section = NeobotSeedData.getAssemblySteps().get(number - 1);
        for (GuidedStep card : GuidedAssemblySequence.forManualStep(section)) {
            if (card.getPrimaryInstruction().getOrder() == order) {
                return GuidedDemonstrationCatalog.create(section, card,
                        GuidedRecognitionCatalog.checkpointFor(section, card));
            }
        }
        throw new AssertionError("Missing instruction " + number + ":" + order);
    }
}
