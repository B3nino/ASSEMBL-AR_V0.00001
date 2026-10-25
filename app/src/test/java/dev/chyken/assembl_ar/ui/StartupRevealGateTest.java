package dev.chyken.assembl_ar.ui;
import org.junit.Test;
import static org.junit.Assert.*;
public final class StartupRevealGateTest {
 @Test public void menuWaitsForAllThreePrerequisitesInAnyOrder(){
  for(int[] order:new int[][]{{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}}){
   StartupRevealGate gate=new StartupRevealGate(true);
   for(int i=0;i<3;i++){if(order[i]==0)gate.introFinished();if(order[i]==1)gate.frameReady();if(order[i]==2)gate.referencesReady();assertEquals(i==2,gate.takeReveal());}
   assertFalse(gate.takeReveal());
  }
 }
 @Test public void introAloneNeverRevealsUnrenderedRobot(){StartupRevealGate gate=new StartupRevealGate();gate.introFinished();assertFalse(gate.takeReveal());gate.frameReady();assertTrue(gate.takeReveal());assertFalse(gate.takeReveal());}
 @Test public void earlyFrameStillAllowsBriefBrandOpening(){StartupRevealGate gate=new StartupRevealGate();gate.frameReady();assertFalse(gate.takeReveal());gate.introFinished();assertTrue(gate.takeReveal());}
}

