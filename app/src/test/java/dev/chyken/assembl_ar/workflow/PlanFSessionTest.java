package dev.chyken.assembl_ar.workflow;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlanFSessionTest {
    @Test public void fiveSecondsMeansActiveReadyScanningAndOnlyOnePrompt() {
        PlanFSession s = new PlanFSession();
        assertFalse(s.update("p5:3", 1, true, true, false, 100));
        assertFalse(s.update("p5:3", 1, true, true, false, 5099));
        assertTrue(s.update("p5:3", 1, true, true, false, 5100));
        assertFalse(s.update("p5:3", 1, true, true, false, 100_000));
    }
    @Test public void loadingPreviewAndDisabledTimeNeverCount() {
        PlanFSession s = new PlanFSession();
        assertFalse(s.update("p8:2", 1, true, false, false, 0));
        assertFalse(s.update("p8:2", 1, false, true, false, 60_000));
        assertFalse(s.update("p8:2", 1, true, true, false, 70_000));
        assertFalse(s.update("p8:2", 1, true, true, false, 74_999));
        assertTrue(s.update("p8:2", 1, true, true, false, 75_000));
    }
    @Test public void pauseAndResumeDoNotCountBackgroundTimeOrRepeatDismissedHelp() {
        PlanFSession s = new PlanFSession();
        s.update("p7:4", 1, true, true, false, 0); s.suspend();
        assertFalse(s.update("p7:4", 2, true, true, false, 60_000));
        assertTrue(s.update("p7:4", 2, true, true, false, 65_000));
        s.suspend();
        assertFalse(s.update("p7:4", 3, true, true, false, 120_000));
        assertFalse(s.update("p7:4", 3, true, true, false, 125_000));
    }
    @Test public void navigationAndExplicitRescanHaveTheirOwnAttempt() {
        PlanFSession s = new PlanFSession();
        s.update("p2:left", 1, true, true, false, 0);
        assertTrue(s.update("p2:left", 1, true, true, false, 5_000));
        assertFalse(s.update("p2:right", 2, true, true, false, 6_000));
        assertTrue(s.update("p2:right", 2, true, true, false, 11_000));
        assertFalse(s.update("p2:right", 3, true, true, false, 12_000));
        assertTrue(s.update("p2:right", 3, true, true, false, 17_000));
    }
    @Test public void verifiedAndFastSuccessNeverTriggerHelp() {
        PlanFSession s = new PlanFSession();
        s.update("p1", 1, true, true, false, 0);
        assertFalse(s.update("p1", 1, true, false, true, 1_000));
        assertFalse(s.update("p1", 2, true, false, true, 10_000));
        assertFalse(s.update("p1", 3, true, true, false, 11_000));
        assertTrue(s.update("p1", 3, true, true, false, 16_000));
    }
    @Test public void manualHelpSuppressesAutomaticInterruption() {
        PlanFSession s = new PlanFSession();
        s.update("p10", 1, true, true, false, 0); s.markHelpOpened();
        assertFalse(s.update("p10", 1, true, true, false, 6_000));
    }
    @Test public void clockRollbackAndMissingScopeCannotTriggerEarly() {
        PlanFSession s = new PlanFSession();
        assertFalse(s.update(null, null, true, true, false, 10_000));
        s.update("p11", 1, true, true, false, 10_000);
        assertFalse(s.update("p11", 1, true, true, false, 0));
        assertFalse(s.update("p11", 1, true, true, false, 4_999));
        assertTrue(s.update("p11", 1, true, true, false, 5_000));
    }
}
