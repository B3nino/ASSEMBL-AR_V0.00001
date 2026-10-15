package dev.chyken.assembl_ar.workflow.phase;

import org.junit.Test;

import dev.chyken.assembl_ar.workflow.phase.PhaseFlow.Mode;
import dev.chyken.assembl_ar.workflow.phase.PhaseFlow.ScanTag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class PhaseFlowTest {
    private static final long MS = 1_000_000L;

    private static PhaseFlow motors(long now) {
        return new PhaseFlow(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES), now);
    }

    @Test
    public void groupedInventoryAutomaticallyStartsTheDemoAndDropsInFlightScanFrames() {
        PhaseFlow flow = motors(0);
        assertEquals(Mode.SCAN_PARTS, flow.getMode());
        assertTrue(flow.collectsEvidence());
        ScanTag inventory = flow.tag();
        assertTrue(flow.accept(inventory, 10 * MS));
        assertTrue(flow.inventoryComplete(20 * MS));
        assertEquals(Mode.DEMO, flow.getMode());
        assertFalse("The demo never collects camera evidence", flow.collectsEvidence());
        assertFalse("A late inventory frame cannot verify the assembly", flow.accept(inventory, 30 * MS));
        assertFalse(flow.accept(flow.tag(), 40 * MS));
    }

    @Test
    public void verificationStartsOnlyOnScanAndRejectsFramesFromBeforeTheScan() {
        PhaseFlow flow = motors(0);
        flow.inventoryComplete(10 * MS);
        flow.demoFinished(20 * MS);
        assertEquals(Mode.ASSEMBLE, flow.getMode());
        assertFalse(flow.collectsEvidence());
        ScanTag assembling = flow.tag();
        assertTrue(flow.scan(100 * MS));
        assertEquals(Mode.VERIFY, flow.getMode());
        assertFalse(flow.accept(assembling, 150 * MS));
        assertFalse("Captured while assembling, before Scan", flow.accept(flow.tag(), 99 * MS));
        assertTrue(flow.accept(flow.tag(), 101 * MS));
        assertFalse("Duplicate frame", flow.accept(flow.tag(), 101 * MS));
        assertFalse("Reordered frame", flow.accept(flow.tag(), 100 * MS));
        assertTrue(flow.accept(flow.tag(), 102 * MS));
    }

    @Test
    public void scanIsAvailableDuringTheDemoAndAfterACorrection() {
        PhaseFlow flow = motors(0);
        assertFalse("Parts must be scanned before assembly verification", flow.canScan());
        assertFalse(flow.scan(1 * MS));
        flow.inventoryComplete(2 * MS);
        assertTrue(flow.canScan());
        assertTrue(flow.scan(3 * MS));
        flow.correctionNeeded("right_motor", 4 * MS);
        assertEquals(Mode.CORRECTION, flow.getMode());
        assertEquals("right_motor", flow.getCorrectionTargetId());
        flow.demoFinished(5 * MS);
        assertEquals("A finished correction waits for a fresh Scan", Mode.CORRECTION, flow.getMode());
        int generation = flow.getGeneration();
        assertTrue(flow.scan(6 * MS));
        assertEquals(Mode.VERIFY, flow.getMode());
        assertNull(flow.getCorrectionTargetId());
        assertNotEquals(generation, flow.getGeneration());
    }

    @Test
    public void replayNeverVerifiesOrCompletesAPhase() {
        PhaseFlow flow = motors(0);
        flow.inventoryComplete(1 * MS);
        flow.demoFinished(2 * MS);
        for (int replay = 0; replay < 3; replay++) {
            assertTrue(flow.replay((10 + replay) * MS));
            assertEquals(Mode.DEMO, flow.getMode());
            assertFalse(flow.physicalCheckConfirmed(20 * MS));
            flow.demoFinished((30 + replay) * MS);
            assertEquals(Mode.ASSEMBLE, flow.getMode());
        }
        assertFalse(flow.physicalCheckConfirmed(40 * MS));
        assertTrue(flow.scan(50 * MS));
        assertTrue(flow.replay(60 * MS));
        assertEquals("Replay cancels an in-progress Scan", Mode.DEMO, flow.getMode());
    }

    @Test
    public void previewFromPartScanningReturnsToScanningWithoutSkippingInventory() {
        PhaseFlow flow = motors(0);
        assertTrue(flow.replay(1 * MS));
        assertTrue(flow.isPreviewing());
        assertFalse(flow.collectsEvidence());
        assertFalse("Preview offers no Scan", flow.canScan());
        flow.demoFinished(2 * MS);
        assertEquals(Mode.SCAN_PARTS, flow.getMode());
        assertFalse(flow.isInventoryComplete());
        assertFalse("Frames before the preview ended are stale", flow.accept(flow.tag(), 1 * MS));
        assertTrue(flow.accept(flow.tag(), 3 * MS));
    }

    @Test
    public void completionNeedsPassedVerificationThenTheDirectChecklist() {
        PhaseFlow flow = motors(0);
        flow.inventoryComplete(1 * MS);
        flow.scan(2 * MS);
        assertFalse(flow.physicalCheckConfirmed(3 * MS));
        flow.verificationPassed(4 * MS);
        assertEquals(Mode.PHYSICAL_CHECK, flow.getMode());
        flow.physicalCheckCancelled(5 * MS);
        assertEquals(Mode.ASSEMBLE, flow.getMode());
        flow.scan(6 * MS);
        flow.verificationPassed(7 * MS);
        assertTrue(flow.physicalCheckConfirmed(8 * MS));
        assertEquals(Mode.COMPLETE, flow.getMode());
        flow.completionSaveFailed(9 * MS);
        assertEquals("A failed save offers the checklist again", Mode.PHYSICAL_CHECK, flow.getMode());
    }

    @Test
    public void inconclusiveScanReturnsToAssemblyWithoutCompletion() {
        PhaseFlow flow = motors(0);
        flow.inventoryComplete(1 * MS);
        flow.scan(2 * MS);
        flow.verificationInconclusive(3 * MS);
        assertEquals(Mode.ASSEMBLE, flow.getMode());
        assertTrue(flow.canScan());
    }

    @Test
    public void pausingDuringVerificationDiscardsTheScanAndItsInFlightFrames() {
        PhaseFlow flow = motors(0);
        flow.inventoryComplete(1 * MS);
        flow.scan(2 * MS);
        ScanTag scan = flow.tag();
        flow.interrupt(3 * MS);
        assertEquals(Mode.ASSEMBLE, flow.getMode());
        assertFalse(flow.accept(scan, 4 * MS));
    }

    @Test
    public void resumedPartScanningRejectsFramesFromBeforeThePause() {
        PhaseFlow flow = motors(0);
        flow.interrupt(5 * MS);
        assertFalse("Paused scanning accepts nothing", flow.accept(flow.tag(), 6 * MS));
        flow.resume(10 * MS);
        assertFalse(flow.accept(flow.tag(), 9 * MS));
        assertTrue(flow.accept(flow.tag(), 11 * MS));
    }

    @Test
    public void phasesWithoutCameraTargetsGoFromScanStraightToTheirChecklist() {
        PhaseFlow flow = new PhaseFlow(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_LEADS), 0);
        assertEquals("Nothing new to scan", Mode.DEMO, flow.getMode());
        assertTrue(flow.isInventoryComplete());
        assertTrue(flow.scan(1 * MS));
        assertEquals(Mode.PHYSICAL_CHECK, flow.getMode());
    }

    @Test
    public void boardPreparationCompletesOnItsIdentityScan() {
        PhaseFlow flow = new PhaseFlow(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.BOARD_PREPARATION), 0);
        assertEquals(Mode.SCAN_PARTS, flow.getMode());
        assertTrue(flow.inventoryComplete(1 * MS));
        assertEquals(Mode.COMPLETE, flow.getMode());
        flow.completionSaveFailed(2 * MS);
        assertEquals(Mode.COMPLETE, flow.getMode());
    }

    @Test
    public void restartDiscardsTheCurrentAttempt() {
        PhaseFlow flow = motors(0);
        flow.inventoryComplete(1 * MS);
        flow.scan(2 * MS);
        ScanTag scan = flow.tag();
        flow.restart(3 * MS);
        assertEquals(Mode.SCAN_PARTS, flow.getMode());
        assertFalse(flow.isInventoryComplete());
        assertFalse(flow.accept(scan, 4 * MS));
    }

    @Test
    public void switchingToTheNextSubjectDropsFramesRequestedForThePreviousOne() {
        PhaseFlow flow = motors(0);
        ScanTag brackets = flow.tag();
        assertTrue(flow.accept(brackets, 1 * MS));
        flow.restartEvidence(5 * MS);
        assertEquals(Mode.SCAN_PARTS, flow.getMode());
        assertFalse("A bracket-set frame cannot identify the motor", flow.accept(brackets, 6 * MS));
        assertFalse(flow.accept(flow.tag(), 4 * MS));
        assertTrue(flow.accept(flow.tag(), 6 * MS));
        flow.inventoryComplete(7 * MS);
        int demo = flow.getGeneration();
        flow.restartEvidence(8 * MS);
        assertEquals("Nothing to restart outside evidence modes", demo, flow.getGeneration());
        assertTrue(flow.isCurrent(flow.tag()));
        assertFalse(flow.isCurrent(brackets));
    }

    @Test
    public void aRecreatedScreenResumesAssemblingWithoutSkippingTheScan() {
        PhaseFlow flow = new PhaseFlow(
                AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES), 0, true);
        assertEquals(Mode.ASSEMBLE, flow.getMode());
        assertTrue(flow.isInventoryComplete());
        assertFalse(flow.collectsEvidence());
        assertFalse(flow.physicalCheckConfirmed(1 * MS));
        assertTrue(flow.scan(2 * MS));
        assertEquals(Mode.VERIFY, flow.getMode());
        flow.restart(3 * MS);
        assertEquals("Restart asks for the parts again", Mode.SCAN_PARTS, flow.getMode());
        PhaseFlow board = new PhaseFlow(
                AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.BOARD_PREPARATION), 0, true);
        assertEquals("Board preparation is only ever finished by its scan",
                Mode.SCAN_PARTS, board.getMode());
    }

    @Test
    public void tagsFromAnotherPhaseAreRejected() {
        PhaseFlow flow = motors(0);
        ScanTag other = new ScanTag(AssemblyPhaseCatalog.CASTER_FIT, flow.getMode(), flow.getGeneration());
        assertFalse(flow.accept(other, 1 * MS));
        assertFalse(flow.accept(null, 1 * MS));
    }
}
