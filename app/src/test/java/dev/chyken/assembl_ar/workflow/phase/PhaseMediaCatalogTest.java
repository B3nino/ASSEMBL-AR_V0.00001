package dev.chyken.assembl_ar.workflow.phase;

import org.junit.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class PhaseMediaCatalogTest {
    @Test
    public void everyRegisteredClipCoversExactlyItsPhaseChapters() {
        for (Map.Entry<String, PhaseMediaCatalog.Clip> entry : PhaseMediaCatalog.all().entrySet()) {
            AssemblyPhase phase = AssemblyPhaseCatalog.byId(entry.getKey());
            assertNotNull("Clip for an unknown phase: " + entry.getKey(), phase);
            PhaseMediaCatalog.Clip clip = entry.getValue();
            assertEquals(entry.getKey(), clip.getPhaseId());
            assertEquals("Chapter starts must match the demo chapters of " + phase.getId(),
                    PhaseDemoScript.forPhase(phase).getChapters().size(),
                    clip.getChapterStartMillis().size());
            assertTrue("Provenance is required", !clip.getProvenance().trim().isEmpty());
        }
    }

    @Test
    public void clipsAreNamedPerPhaseAndAbsentUntilFilmed() {
        assertEquals("phase_motor_assemblies",
                PhaseMediaCatalog.rawResourceName(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES));
        assertNull("No clip has been filmed yet; the schematic chapters are the demo",
                PhaseMediaCatalog.clipFor(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES));
    }

    @Test
    public void chapterBoundariesAreValidated() {
        PhaseMediaCatalog.Clip clip = new PhaseMediaCatalog.Clip(AssemblyPhaseCatalog.CASTER_FIT,
                Arrays.asList(0L, 4_000L, 9_000L), 15_000L, "test fixture");
        assertEquals(4_000L, clip.chapterEndMillis(0));
        assertEquals(15_000L, clip.chapterEndMillis(2));
        assertThrows(() -> new PhaseMediaCatalog.Clip("x", Arrays.asList(100L, 200L), 300L, "p"));
        assertThrows(() -> new PhaseMediaCatalog.Clip("x", Arrays.asList(0L, 200L, 200L), 300L, "p"));
        assertThrows(() -> new PhaseMediaCatalog.Clip("x", Arrays.asList(0L, 200L), 200L, "p"));
    }

    @Test
    public void sideLoadedClipsMustTimeEveryDemoChapter() {
        java.util.List<Long> twelve = new java.util.ArrayList<>();
        for (long chapter = 0; chapter < 12; chapter++) twelve.add(chapter * 2_000L);
        PhaseMediaCatalog.Clip caster = PhaseMediaCatalog.describe(AssemblyPhaseCatalog.CASTER_FIT,
                twelve, 24_000L, " ");
        assertEquals("side-loaded", caster.getProvenance());
        assertEquals(22_000L, (long) caster.getChapterStartMillis().get(11));
        assertThrows(() -> PhaseMediaCatalog.describe(AssemblyPhaseCatalog.CASTER_FIT,
                twelve.subList(0, 11), 24_000L, "short"));
        assertThrows(() -> PhaseMediaCatalog.describe("no_such_phase", twelve, 24_000L, "x"));
    }

    private static void assertThrows(Runnable runnable) {
        try {
            runnable.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected an invalid clip to be rejected");
    }
}
