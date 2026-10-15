package dev.chyken.assembl_ar.workflow.phase;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession.Observation;
import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession.Outcome;
import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession.TargetResult;
import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession.Verdict;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class PhaseVerificationSessionTest {
    private static final long MS = 1_000_000L;

    private static PhaseVerificationSession motorScan() {
        return new PhaseVerificationSession(
                AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES), 0);
    }

    private static List<Observation> motorsReady() {
        return Arrays.asList(Observation.ready("left_motor"), Observation.ready("right_motor"));
    }

    @Test
    public void everyGroupMustBeReadyOnFreshFramesBeforeThePhasePasses() {
        PhaseVerificationSession session = motorScan();
        assertEquals("motor_pair", session.getActiveGroup());
        assertEquals(Verdict.IN_PROGRESS, session.observe(1 * MS, Arrays.asList(
                Observation.of("left_motor", Outcome.ACCEPTED, "checking"),
                Observation.of("right_motor", Outcome.ACCEPTED, "checking"))));
        assertFalse("Seen is not yet stable", session.getResult("left_motor").isAccepted());
        session.observe(2 * MS, motorsReady());
        assertEquals("bracket_slot_1", session.getActiveGroup());
        for (int slot = 1; slot <= 3; slot++) {
            assertEquals(Verdict.IN_PROGRESS, session.observe((2 + slot) * MS,
                    Collections.singletonList(Observation.ready("bracket_slot_" + slot))));
        }
        assertEquals("bracket_slot_4", session.getActiveGroup());
        assertEquals(Verdict.PASSED, session.observe(9 * MS,
                Collections.singletonList(Observation.ready("bracket_slot_4"))));
        assertNull(session.getActiveGroup());
        for (TargetResult result : session.getResults()) assertTrue(result.isAccepted());
    }

    @Test
    public void aWrongResultMustRepeatOnConsecutiveFramesBeforeItIsEstablished() {
        PhaseVerificationSession session = motorScan();
        Observation misaligned = Observation.of("right_motor", Outcome.WRONG_POSITION, "Not between brackets");
        session.observe(1 * MS, Collections.singletonList(misaligned));
        session.observe(2 * MS, Collections.singletonList(misaligned));
        session.observe(3 * MS, Collections.singletonList(
                Observation.of("right_motor", Outcome.UNOBSERVABLE, "Blurred")));
        session.observe(4 * MS, Collections.singletonList(misaligned));
        assertEquals("An unclear frame resets the streak", Verdict.IN_PROGRESS, session.getVerdict());
        session.observe(5 * MS, Collections.singletonList(misaligned));
        assertEquals(Verdict.CORRECTION_NEEDED, session.observe(6 * MS,
                Collections.singletonList(misaligned)));
        TargetResult correction = session.getCorrection();
        assertEquals("right_motor", correction.getTarget().getId());
        assertEquals(Outcome.WRONG_POSITION, correction.getOutcome());
        assertEquals("Not between brackets", correction.getMessage());
        assertEquals("A settled session ignores later frames", Verdict.CORRECTION_NEEDED,
                session.observe(7 * MS, motorsReady()));
    }

    @Test
    public void absenceNeedsMoreEvidenceThanAMeasuredMisplacement() {
        PhaseVerificationSession session = motorScan();
        Observation missing = Observation.of("left_motor", Outcome.MISSING, "No motor in the left slot");
        for (int frame = 1; frame < PhaseVerificationSession.MISSING_FRAMES; frame++) {
            assertEquals(Verdict.IN_PROGRESS,
                    session.observe(frame * MS, Collections.singletonList(missing)));
        }
        assertEquals(Verdict.CORRECTION_NEEDED, session.observe(
                PhaseVerificationSession.MISSING_FRAMES * MS, Collections.singletonList(missing)));
        assertEquals(Outcome.MISSING, session.getCorrection().getOutcome());
    }

    @Test
    public void aTargetRemovedAfterAcceptanceIsRevokedByLaterEvidence() {
        PhaseVerificationSession session = motorScan();
        session.observe(1 * MS, motorsReady());
        assertTrue(session.getResult("left_motor").isAccepted());
        // Motors are also inspected while a bracket slot is probed; the left motor is taken out.
        Observation gone = Observation.of("left_motor", Outcome.MISSING, "No motor in the left slot");
        long time = 2;
        for (int frame = 0; frame < PhaseVerificationSession.MISSING_FRAMES; frame++) {
            session.observe((time++) * MS, Arrays.asList(gone,
                    Observation.ready("bracket_slot_1")));
        }
        assertEquals(Verdict.CORRECTION_NEEDED, session.getVerdict());
        assertFalse(session.getResult("left_motor").isAccepted());
        assertEquals("left_motor", session.getCorrection().getTarget().getId());
    }

    @Test
    public void unclearGroupsBecomeInconclusiveAfterTheirBudgetAndAreNeverCalledWrong() {
        PhaseVerificationSession session = new PhaseVerificationSession(
                AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES), 0, 100 * MS);
        session.observe(10 * MS, motorsReady());
        long time = 11;
        for (int slot = 1; slot <= 2; slot++) {
            session.observe((time++) * MS, Collections.singletonList(Observation.ready("bracket_slot_" + slot)));
        }
        assertEquals("bracket_slot_3", session.getActiveGroup());
        for (; time < 200; time += 10) {
            session.observe(time * MS, Collections.singletonList(Observation.of("bracket_slot_3",
                    Outcome.UNOBSERVABLE, "Show bracket 3 from the side")));
        }
        assertEquals("bracket_slot_4", session.getActiveGroup());
        TargetResult slot3 = session.getResult("bracket_slot_3");
        assertTrue(slot3.isExhausted());
        assertEquals(Outcome.UNOBSERVABLE, slot3.getOutcome());
        assertFalse(slot3.isEstablishedError());
        session.observe(210 * MS, Collections.singletonList(Observation.ready("bracket_slot_4")));
        assertEquals(Verdict.INCONCLUSIVE, session.getVerdict());
        assertNull(session.getCorrection());
    }

    @Test
    public void budgetAlsoExpiresWhenNoFrameArrives() {
        PhaseVerificationSession session = new PhaseVerificationSession(
                AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.CASTER_FIT), 0, 50 * MS);
        assertEquals(Verdict.IN_PROGRESS, session.checkBudget(40 * MS));
        assertEquals(Verdict.INCONCLUSIVE, session.checkBudget(60 * MS));
        assertEquals(Outcome.UNOBSERVABLE, session.getResult("caster").getOutcome());
    }

    @Test
    public void staleOrRepeatedEvidenceTimesAreIgnored() {
        PhaseVerificationSession session = motorScan();
        Observation wrong = Observation.of("left_motor", Outcome.WRONG_POSITION, "");
        session.observe(5 * MS, Collections.singletonList(wrong));
        session.observe(5 * MS, Collections.singletonList(wrong));
        session.observe(4 * MS, Collections.singletonList(wrong));
        assertEquals(Verdict.IN_PROGRESS, session.getVerdict());
        session.observe(6 * MS, Collections.singletonList(wrong));
        assertEquals(Verdict.IN_PROGRESS, session.getVerdict());
        session.observe(7 * MS, Collections.singletonList(wrong));
        assertEquals(Verdict.CORRECTION_NEEDED, session.getVerdict());
    }

    @Test
    public void pairedTargetsPassOnlyTogetherFromTheirSharedGroup() {
        PhaseVerificationSession session = new PhaseVerificationSession(
                AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.LINE_TRACKERS), 0);
        assertEquals("section_10", session.getActiveGroup());
        assertEquals(2, session.getActiveTargets().size());
        session.observe(1 * MS, Collections.singletonList(Observation.ready("left_tracker")));
        assertEquals(Verdict.IN_PROGRESS, session.getVerdict());
        assertEquals(Verdict.PASSED, session.observe(2 * MS, Arrays.asList(
                Observation.ready("left_tracker"), Observation.ready("right_tracker"))));
    }

    @Test
    public void abandoningAScanWithoutFramesIsInconclusiveAndKeepsEarlierAcceptances() {
        PhaseVerificationSession session = motorScan();
        session.observe(1 * MS, motorsReady());
        assertEquals(Verdict.INCONCLUSIVE, session.abandon("Show the whole board"));
        assertTrue(session.getResult("left_motor").isAccepted());
        TargetResult slot = session.getResult("bracket_slot_2");
        assertEquals(Outcome.UNOBSERVABLE, slot.getOutcome());
        assertEquals("Show the whole board", slot.getMessage());
        assertNull(session.getActiveGroup());
        assertNull(session.getCorrection());
    }

    @Test(expected = IllegalArgumentException.class)
    public void phasesWithoutCameraTargetsHaveNoScanSession() {
        new PhaseVerificationSession(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_LEADS), 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void onlyAcceptedObservationsMayBeGateReady() {
        new Observation("left_motor", Outcome.WRONG_POSITION, true, "", null);
    }
}
