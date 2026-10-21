package dev.chyken.assembl_ar.ui;
import org.junit.Test;
import static org.junit.Assert.*;
public final class StartupRevealGateTest {
 @Test public void introAloneNeverRevealsUnrenderedRobot(){StartupRevealGate gate=new StartupRevealGate();gate.introFinished();assertFalse(gate.takeReveal());gate.frameReady();assertTrue(gate.takeReveal());assertFalse(gate.takeReveal());}
 @Test public void earlyFrameStillAllowsBriefBrandOpening(){StartupRevealGate gate=new StartupRevealGate();gate.frameReady();assertFalse(gate.takeReveal());gate.introFinished();assertTrue(gate.takeReveal());}
}

