package dev.chyken.assembl_ar.workflow;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyInstruction;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.InstructionType;
import dev.chyken.assembl_ar.model.OverlayType;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GuidedAssemblySequenceTest {
    @Test
    public void everyActionAndCheckBecomesOneOfOneHundredTwentyEightGuidedSteps() {
        List<AssemblyStep> manualSteps = NeobotSeedData.getAssemblySteps();
        assertEquals(128, GuidedAssemblySequence.countGuidedSteps(manualSteps));
        int[] expectedCardsPerSection = {
                1, 25, 4, 1, 5, 5, 3, 5, 12, 11, 15, 5, 5, 8, 6, 8, 4, 5
        };

        int sourceInstructionCount = 0;
        int presentedInstructionCount = 0;
        int actionCount = 0;
        int checkCount = 0;
        int noteCount = 0;
        int warningCount = 0;
        Set<String> instructionalPaths = new HashSet<>();
        for (int sectionIndex = 0; sectionIndex < manualSteps.size(); sectionIndex++) {
            AssemblyStep manualStep = manualSteps.get(sectionIndex);
            List<GuidedStep> guidedSteps = GuidedAssemblySequence.forManualStep(manualStep);
            assertEquals("Unexpected guided-card count in manual section "
                            + manualStep.getStepNumber(),
                    expectedCardsPerSection[sectionIndex], guidedSteps.size());
            Set<Integer> presentedOrders = new HashSet<>();
            Set<String> stableIds = new HashSet<>();
            sourceInstructionCount += manualStep.getInstructions().size();
            for (AssemblyInstruction instruction : manualStep.getInstructions()) {
                if (instruction.getType() == InstructionType.ACTION) actionCount++;
                else if (instruction.getType() == InstructionType.CHECK) checkCount++;
                else if (instruction.getType() == InstructionType.NOTE) noteCount++;
                else if (instruction.getType() == InstructionType.WARNING) warningCount++;
            }
            for (GuidedStep guidedStep : guidedSteps) {
                AssemblyInstruction primary = guidedStep.getPrimaryInstruction();
                assertTrue(primary.getType() == InstructionType.ACTION
                        || primary.getType() == InstructionType.CHECK);
                assertTrue("Stable card identity repeated in manual step "
                                + manualStep.getStepNumber(),
                        stableIds.add(guidedStep.getStableId()));
                assertTrue("Instruction presented twice in manual step "
                                + manualStep.getStepNumber(),
                        presentedOrders.add(primary.getOrder()));
                for (AssemblyInstruction context : guidedStep.getContext()) {
                    assertTrue("Instruction presented twice in manual step "
                                    + manualStep.getStepNumber(),
                            presentedOrders.add(context.getOrder()));
                }
                for (String path : guidedStep.getReferenceImagePaths()) {
                    boolean manualPage = path.matches(
                            "manuals/assembly/page-[0-9]{2}\\.jpg");
                    boolean instructionalImage = path.matches(
                            "references/instructional/gray/[a-z0-9-]+-gray\\.webp");
                    assertTrue("Unexpected guided-card image path: " + path,
                            manualPage || instructionalImage);
                    if (instructionalImage) instructionalPaths.add(path);
                }
            }
            presentedInstructionCount += presentedOrders.size();
            assertEquals(manualStep.getInstructions().size(), presentedOrders.size());
        }
        assertEquals(sourceInstructionCount, presentedInstructionCount);
        assertEquals(152, presentedInstructionCount);
        assertEquals(109, actionCount);
        assertEquals(19, checkCount);
        assertEquals(21, noteCount);
        assertEquals(3, warningCount);
        assertEquals(expectedInstructionalPaths(), instructionalPaths);
    }

    @Test
    public void workSurfaceAdviceIsContextWithoutASeparateConfirmationCard() {
        List<GuidedStep> preparation = guidedSteps(1);
        assertEquals(1, preparation.size());
        GuidedStep board = preparation.get(0);
        assertEquals("2|ACTION|Place the main board component-side up on the work surface.",
                board.getStableId());
        assertEquals(1, board.getTrailingContext().size());
        assertEquals(InstructionType.NOTE, board.getTrailingContext().get(0).getType());
        assertTrue(board.getTrailingContext().get(0).getText()
                .contains("flat, clean, non-metal"));
    }

    @Test
    public void motorSectionExpandsBothMotorInstallationsWithoutGenericRepeatCard() {
        List<GuidedStep> motorSteps = guidedSteps(2);

        assertEquals(25, motorSteps.size());
        assertContainsPrimary(motorSteps, "first motor position");
        assertContainsPrimary(motorSteps, "opposite motor position");
        assertContainsPrimary(motorSteps, "first gear motor");
        assertContainsPrimary(motorSteps, "first motor flat");
        assertContainsPrimary(motorSteps, "lower 30 mm screw");
        assertContainsPrimary(motorSteps, "upper 30 mm screw");
        assertContainsPrimary(motorSteps, "second gear motor");
        assertContainsPrimary(motorSteps, "second motor's lower");
        assertContainsPrimary(motorSteps, "second motor's upper");
        assertContainsPrimary(motorSteps, "both completed motor assemblies");
        for (GuidedStep card : motorSteps) {
            assertFalse(card.getPrimaryInstruction().getText().toLowerCase(Locale.ROOT)
                    .startsWith("repeat the installation"));
            assertTrue("Every motor action/check needs an assembly-specific gray visual",
                    card.getPrimaryInstruction().getReferenceImagePath()
                            .startsWith("references/instructional/gray/"));
        }

        assertPrimaryAsset(motorSteps, "first bracket half",
                "motor-bracket-half-gray.webp");
        assertPrimaryAsset(motorSteps, "fourth bracket half",
                "s02-01-brackets-mounted-gray.webp");
        assertPrimaryAsset(motorSteps, "lower 30 mm screw",
                "s02-03-first-motor-one-screw-gray.webp");
        assertPrimaryAsset(motorSteps, "upper 30 mm screw",
                "s02-04-first-motor-two-screws-gray.webp");
        assertPrimaryAsset(motorSteps, "second motor's upper",
                "s02-05-both-motors-gray.webp");

        GuidedStep secondMotor = findPrimary(motorSteps, "Place the second gear motor");
        assertEquals(4, secondMotor.getPageStart());
        assertEquals(8, secondMotor.getPageEnd());
        assertTrue(secondMotor.getLeadingContext().get(0).getText()
                .contains("following cards expand that repeat"));
        assertEquals(8, motorSteps.get(motorSteps.size() - 1).getPageStart());
    }

    @Test
    public void powerTrackersFastenersAndOledAreIndividuallyGuided() {
        assertContainsPrimary(guidedSteps(7), "VCC to microcontroller VIN");
        assertContainsPrimary(guidedSteps(7), "GND to microcontroller GND");

        List<GuidedStep> sensorSteps = guidedSteps(11);
        assertContainsPrimary(sensorSteps, "left line tracker's V+");
        assertContainsPrimary(sensorSteps, "left line tracker's GND");
        assertContainsPrimary(sensorSteps, "left line tracker's S pin to A2");
        assertContainsPrimary(sensorSteps, "right line tracker's V+");
        assertContainsPrimary(sensorSteps, "right line tracker's GND");
        assertContainsPrimary(sensorSteps, "right line tracker's S pin to A3");
        assertEquals(20, sensorSteps.get(sensorSteps.size() - 1).getPageStart());

        assertContainsPrimary(guidedSteps(10), "first tracker's mounting screw");
        assertEquals(InstructionType.ACTION,
                findPrimary(guidedSteps(10), "second tracker's mounting screw")
                        .getPrimaryInstruction().getType());
        assertContainsPrimary(guidedSteps(14), "first mounting fastener");
        assertContainsPrimary(guidedSteps(14), "second mounting fastener");

        List<GuidedStep> casterSteps = guidedSteps(9);
        assertEquals(InstructionType.CHECK,
                casterSteps.get(casterSteps.size() - 1).getPrimaryInstruction().getType());

        List<GuidedStep> oledSteps = guidedSteps(17);
        assertContainsPrimary(oledSteps, "Align all four OLED pins");
        assertContainsPrimary(oledSteps, "Press the OLED straight");
        assertEquals(28, oledSteps.get(oledSteps.size() - 1).getPageStart());
    }

    @Test
    public void leadingSafetyContextStaysBeforeItsAction() {
        GuidedStep oledOrientation = guidedSteps(17).get(0);
        assertEquals(1, oledOrientation.getLeadingContext().size());
        assertTrue(oledOrientation.getLeadingContext().get(0).getText()
                .contains("keep the OLED display in the box"));
        assertTrue(oledOrientation.getTrailingContext().isEmpty());

        GuidedStep firstMotorLead = findPrimary(guidedSteps(5), "first motor's red");
        assertEquals(2, firstMotorLead.getLeadingContext().size());
        assertTrue(firstMotorLead.getLeadingContext().get(0).getText()
                .contains("will not enter"));
    }

    @Test
    public void batterySectionDoesNotInventAnElectricalConnection() {
        AssemblyStep battery = manualStep(18);
        assertTrue(battery.getPinConnections().isEmpty());
        assertFalse(battery.getOverlayTypes().contains(OverlayType.POLARITY_ARROW));
        assertContainsPrimary(GuidedAssemblySequence.forManualStep(battery),
                "battery-retention method");
        assertContainsPrimary(GuidedAssemblySequence.forManualStep(battery),
                "rated 7-12 V");
    }

    private static List<GuidedStep> guidedSteps(int manualStepNumber) {
        return GuidedAssemblySequence.forManualStep(manualStep(manualStepNumber));
    }

    private static AssemblyStep manualStep(int manualStepNumber) {
        return NeobotSeedData.getAssemblySteps().get(manualStepNumber - 1);
    }

    private static GuidedStep findPrimary(List<GuidedStep> guidedSteps, String fragment) {
        for (GuidedStep guidedStep : guidedSteps) {
            if (guidedStep.getPrimaryInstruction().getText()
                    .toLowerCase(Locale.ROOT).contains(fragment.toLowerCase(Locale.ROOT))) {
                return guidedStep;
            }
        }
        throw new AssertionError("Missing guided card containing: " + fragment);
    }

    private static void assertContainsPrimary(List<GuidedStep> guidedSteps, String fragment) {
        findPrimary(guidedSteps, fragment);
    }

    private static void assertPrimaryAsset(
            List<GuidedStep> guidedSteps, String textFragment, String filename) {
        assertEquals("references/instructional/gray/" + filename,
                findPrimary(guidedSteps, textFragment)
                        .getPrimaryInstruction().getReferenceImagePath());
    }

    private static Set<String> expectedInstructionalPaths() {
        return new HashSet<>(Arrays.asList(
                "references/instructional/gray/motor-bracket-half-gray.webp",
                "references/instructional/gray/gear-motor-gray.webp",
                "references/instructional/gray/motor-driver-gray.webp",
                "references/instructional/gray/s02-00-main-board-gray.webp",
                "references/instructional/gray/s02-01-brackets-mounted-gray.webp",
                "references/instructional/gray/s02-02-first-motor-positioned-gray.webp",
                "references/instructional/gray/s02-03-first-motor-one-screw-gray.webp",
                "references/instructional/gray/s02-04-first-motor-two-screws-gray.webp",
                "references/instructional/gray/s02-05-both-motors-gray.webp",
                "references/instructional/gray/s03-00-motor-driver-installed-gray.webp"));
    }
}
