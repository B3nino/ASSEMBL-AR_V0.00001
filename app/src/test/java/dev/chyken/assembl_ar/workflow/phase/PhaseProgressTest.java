package dev.chyken.assembl_ar.workflow.phase;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public final class PhaseProgressTest {
    private static final class FakeRecords implements PhaseProgress.Records {
        final Set<String> phases = new HashSet<>();
        final Set<String> cards = new HashSet<>();

        @Override public boolean isPhaseComplete(String phaseId) { return phases.contains(phaseId); }

        @Override public boolean isLegacyCheckpointComplete(AssemblyStep section, GuidedStep card) {
            return cards.contains(section.getStepNumber() + ":" + card.getPrimaryInstruction().getOrder());
        }
    }

    private static AssemblyPhase phase(String id) { return AssemblyPhaseCatalog.byId(id); }

    @Test
    public void completedSectionsResumeAtTheFirstPhaseOfTheCurrentSection() {
        FakeRecords records = new FakeRecords();
        assertSame(phase(AssemblyPhaseCatalog.BOARD_PREPARATION), PhaseProgress.resume(1, records));
        assertSame(phase(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES), PhaseProgress.resume(2, records));
        assertSame(phase(AssemblyPhaseCatalog.MOTOR_LEADS), PhaseProgress.resume(4, records));
        assertSame("A saved section 4 continues the combined wiring phase",
                phase(AssemblyPhaseCatalog.MOTOR_LEADS), PhaseProgress.resume(5, records));
        assertSame(phase(AssemblyPhaseCatalog.BATTERY_FIT), PhaseProgress.resume(18, records));
        assertNull("A complete project has no phase", PhaseProgress.resume(0, records));
    }

    @Test
    public void partialOldCardProgressNeverImpliesThatAGroupIsInstalled() {
        FakeRecords records = new FakeRecords();
        records.cards.addAll(Arrays.asList("2:1", "2:2", "2:3", "2:4", "2:5"));
        assertSame(phase(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES), PhaseProgress.resume(2, records));
        records.phases.add(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
        assertSame("Section progress, not a phase record, finishes a whole section",
                phase(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES), PhaseProgress.resume(2, records));
    }

    @Test
    public void aFinishedSensorMountResumesAtItsWiring() {
        FakeRecords records = new FakeRecords();
        assertSame(phase(AssemblyPhaseCatalog.DISTANCE_SENSOR_MOUNT), PhaseProgress.resume(11, records));
        records.phases.add(AssemblyPhaseCatalog.DISTANCE_SENSOR_MOUNT);
        assertSame(phase(AssemblyPhaseCatalog.SENSOR_WIRING), PhaseProgress.resume(11, records));
    }

    @Test
    public void fullyValidatedOldCardsCarryTheSensorMountForward() {
        FakeRecords records = new FakeRecords();
        records.cards.addAll(Arrays.asList("11:2", "11:3"));
        assertSame(phase(AssemblyPhaseCatalog.DISTANCE_SENSOR_MOUNT), PhaseProgress.resume(11, records));
        records.cards.add("11:4");
        assertSame(phase(AssemblyPhaseCatalog.SENSOR_WIRING), PhaseProgress.resume(11, records));
    }

    @Test
    public void finishingAPhaseSavesOnlyTheSectionsItCompletes() {
        assertEquals(Arrays.asList(4, 5), PhaseProgress.sectionsToSave(phase(AssemblyPhaseCatalog.MOTOR_LEADS), 4));
        assertEquals(Arrays.asList(5), PhaseProgress.sectionsToSave(phase(AssemblyPhaseCatalog.MOTOR_LEADS), 5));
        assertEquals(Collections.emptyList(),
                PhaseProgress.sectionsToSave(phase(AssemblyPhaseCatalog.DISTANCE_SENSOR_MOUNT), 11));
        assertEquals(Arrays.asList(11),
                PhaseProgress.sectionsToSave(phase(AssemblyPhaseCatalog.SENSOR_WIRING), 11));
        assertEquals(Arrays.asList(1),
                PhaseProgress.sectionsToSave(phase(AssemblyPhaseCatalog.BOARD_PREPARATION), 1));
    }

    @Test
    public void redoReopensThePhaseAndInvalidatesEveryLaterPhase() {
        PhaseProgress.Redo redo = PhaseProgress.redoFrom(phase(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES));
        assertEquals(2, redo.getReopenSection());
        assertEquals(17, redo.getClearedPhaseIds().size());
        assertEquals(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES, redo.getClearedPhaseIds().get(0));
        assertEquals(AssemblyPhaseCatalog.BATTERY_FIT, redo.getClearedPhaseIds().get(16));
        PhaseProgress.Redo wiring = PhaseProgress.redoFrom(phase(AssemblyPhaseCatalog.SENSOR_WIRING));
        assertEquals(11, wiring.getReopenSection());
        assertEquals(8, wiring.getClearedPhaseIds().size());
    }
}
