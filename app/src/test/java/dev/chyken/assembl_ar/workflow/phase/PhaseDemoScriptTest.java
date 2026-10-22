package dev.chyken.assembl_ar.workflow.phase;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyInstruction;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence;
import dev.chyken.assembl_ar.workflow.phase.PhaseDemoScript.Chapter;
import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession.Observation;
import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession.Outcome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PhaseDemoScriptTest {
    private static PhaseDemoScript script(String id) {
        return PhaseDemoScript.forPhase(AssemblyPhaseCatalog.byId(id));
    }

    @Test
    public void everyGuidedCardIsOneChapterOfExactlyOnePhaseInManualOrder() {
        Set<String> seen = new HashSet<>();
        int chapters = 0;
        for (AssemblyPhase phase : AssemblyPhaseCatalog.phases()) {
            PhaseDemoScript script = PhaseDemoScript.forPhase(phase);
            int previousSection = 0, previousOrder = 0;
            for (Chapter chapter : script.getChapters()) {
                assertTrue(seen.add(chapter.getSectionNumber() + ":" + chapter.getOrder()));
                assertTrue(chapter.getSectionNumber() > previousSection
                        || chapter.getOrder() > previousOrder);
                previousSection = chapter.getSectionNumber();
                previousOrder = chapter.getOrder();
                assertFalse(chapter.getNarration().isEmpty());
                assertEquals(chapters++ - countBefore(phase), chapter.getIndex());
            }
        }
        assertEquals(GuidedAssemblySequence.countGuidedSteps(NeobotSeedData.getAssemblySteps()), chapters);
        assertEquals(128, chapters);
    }

    private static int countBefore(AssemblyPhase phase) {
        int count = 0;
        for (AssemblyPhase earlier : AssemblyPhaseCatalog.phases()) {
            if (earlier == phase) break;
            count += PhaseDemoScript.forPhase(earlier).getChapters().size();
        }
        return count;
    }

    @Test
    public void motorDemoShowsTheWholeGroupAndEndsOnTheFinishedPair() {
        PhaseDemoScript motors = script(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
        assertEquals(25, motors.getChapters().size());
        assertEquals(1, motors.getChapters().get(0).getOrder());
        assertEquals(27, motors.getFinalChapter().getOrder());
        assertTrue(motors.getIntroduction().contains("Nail polish is an acceptable"));
        assertFalse("Editorial app notes are not narrated",
                motors.getIntroduction().contains("Page 8 instructs"));
        assertTrue(motors.getIntroduction().contains("25 steps"));
    }

    @Test
    public void repeatedManualNotesAreNarratedOnce() {
        String intro = script(AssemblyPhaseCatalog.LINE_TRACKERS).getIntroduction();
        String note = "You may use stacked nuts instead of a plastic spacer.";
        assertEquals(intro.indexOf(note), intro.lastIndexOf(note));
        assertTrue(script(AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT).getIntroduction().endsWith("4 steps."));
    }

    @Test
    public void splitSectionChaptersAndNotesFollowTheirPhase() {
        PhaseDemoScript mount = script(AssemblyPhaseCatalog.DISTANCE_SENSOR_MOUNT);
        PhaseDemoScript wiring = script(AssemblyPhaseCatalog.SENSOR_WIRING);
        assertEquals(Arrays.asList(2,3,4,5,6,7,8,9,10,11,12,13,14,15,16),orders(mount.getChapters()));
        assertEquals(orders(mount.getChapters()),orders(wiring.getChapters()));
        assertTrue(mount.getIntroduction().contains("Do not add redundant power wires"));
        assertTrue(wiring.detailsText().contains("Trigger → Arduino Nano-compatible microcontroller board D2"));
    }

    @Test
    public void detailsKeepEverySourceInstructionOfThePhase() {
        for (AssemblyPhase phase : AssemblyPhaseCatalog.phases()) {
            String details = PhaseDemoScript.forPhase(phase).detailsText();
            for (AssemblyInstruction instruction : AssemblyPhaseCatalog.instructionsOf(phase)) {
                assertTrue(phase.getId() + " details lost " + instruction.getText(),
                        details.contains(instruction.getText()));
            }
        }
    }

    @Test
    public void correctionsReplayOnlyTheChaptersThatFixTheTarget() {
        PhaseDemoScript motors = script(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
        AssemblyPhase phase = motors.getPhase();
        assertEquals(Arrays.asList(17, 18, 19), orders(motors.correctionChapters(phase.getTarget("right_motor"))));
        assertEquals(Arrays.asList(3), orders(motors.correctionChapters(phase.getTarget("bracket_slot_3"))));
        PhaseDemoScript trackers = script(AssemblyPhaseCatalog.LINE_TRACKERS);
        assertEquals(Arrays.asList(8, 9, 10, 12), orders(trackers.correctionChapters(
                trackers.getPhase().getTarget("right_tracker"))));
    }

    @Test
    public void correctionNarrationNamesTheTargetAndTheExpectedPart() {
        PhaseVerificationSession session = new PhaseVerificationSession(
                AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.LINE_TRACKERS), 0);
        Observation wrongPart = new Observation("left_tracker", Outcome.WRONG_IDENTITY, false,
                "", NeobotSeedData.DISTANCE_SENSOR);
        for (long frame = 1; frame <= PhaseVerificationSession.ERROR_FRAMES; frame++) {
            session.observe(frame, java.util.Collections.singletonList(wrongPart));
        }
        String narration = PhaseDemoScript.correctionNarration(session.getCorrection());
        assertTrue(narration, narration.contains("Ultrasonic distance sensor"));
        assertTrue(narration.contains("Infrared line tracker module"));
        assertTrue(narration.contains("left line tracker"));
        assertTrue(narration.contains("Scan again"));
        assertEquals("✗ Left line tracker — wrong part",
                PhaseDemoScript.resultLine(session.getCorrection()));
    }

    @Test
    public void warningsAreCollectedOncePerPhase() {
        assertEquals(Arrays.asList("The right motor is flipped; reverse its wiring connection as illustrated."),
                script(AssemblyPhaseCatalog.MOTOR_LEADS).getWarnings());
        assertEquals(1, script(AssemblyPhaseCatalog.OLED_FIT).getWarnings().size());
    }

    @Test
    public void silentChaptersLastAtLeastTheAnimationAndAreBounded() {
        assertEquals(PhaseDemoScript.ANIMATION_MILLIS, PhaseDemoScript.silentDurationMillis("Short."));
        assertEquals(16_000L, PhaseDemoScript.silentDurationMillis(repeat("word ", 200)));
        assertEquals(20 * 420L, PhaseDemoScript.silentDurationMillis(repeat("word ", 20)));
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < count; index++) builder.append(value);
        return builder.toString();
    }

    private static List<Integer> orders(List<Chapter> chapters) {
        List<Integer> orders = new ArrayList<>();
        for (Chapter chapter : chapters) orders.add(chapter.getOrder());
        return orders;
    }
}
