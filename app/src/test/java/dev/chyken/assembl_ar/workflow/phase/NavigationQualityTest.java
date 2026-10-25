package dev.chyken.assembl_ar.workflow.phase;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NavigationQualityTest {
 @Test public void rescanNeedsCameraCheckpointAndExistingEvidence(){
  assertFalse(AssemblyStepNavigationManager.canRescan(false,false));
  assertFalse(AssemblyStepNavigationManager.canRescan(false,true));
  assertFalse(AssemblyStepNavigationManager.canRescan(true,false));
  assertTrue(AssemblyStepNavigationManager.canRescan(true,true));
 }
 @Test public void skipCannotRunWhilePausedSavingOrOutsidePreview(){
  for(boolean video:new boolean[]{false,true})for(boolean paused:new boolean[]{false,true})for(boolean saving:new boolean[]{false,true})
   assertEquals(video&&!paused&&!saving,AssemblyStepNavigationManager.canSkipPreview(video,paused,saving));
 }
 @Test public void everyNewerCameraStateRefusesPrematureRescanWithoutMutatingCheckpoint(){
  Phase8SensorWiringFlow p8=new Phase8SensorWiringFlow(1);
  for(Phase8SensorWiringFlow.Screen s:Phase8SensorWiringFlow.Screen.values()){p8.devJump(s.ordinal()+1,2);assertFalse(p8.canRescan());String before=p8.snapshot();p8.rescan(3);assertEquals(before,p8.snapshot());}
  Phase9StandoffWheelsFlow p9=new Phase9StandoffWheelsFlow(1);
  for(Phase9StandoffWheelsFlow.Screen s:Phase9StandoffWheelsFlow.Screen.values()){p9.devJump(s.ordinal()+1,2);assertFalse(p9.canRescan());String before=p9.snapshot();p9.rescan(3);assertEquals(before,p9.snapshot());}
  Phase10ExtensionBoardFlow p10=new Phase10ExtensionBoardFlow(1);
  for(Phase10ExtensionBoardFlow.Screen s:Phase10ExtensionBoardFlow.Screen.values()){p10.devJump(s.ordinal()+1,2);assertFalse(p10.canRescan());String before=p10.snapshot();p10.rescan(3);assertEquals(before,p10.snapshot());}
  Phase11OledBatteryFlow p11=new Phase11OledBatteryFlow(1);
  for(Phase11OledBatteryFlow.Screen s:Phase11OledBatteryFlow.Screen.values()){p11.devJump(s.ordinal()+1,2);assertFalse(p11.canRescan());String before=p11.snapshot();p11.rescan(3);assertEquals(before,p11.snapshot());}
 }
 @Test public void skippedDemosStillRequireTheirNextPhysicalGate(){
  Phase9StandoffWheelsFlow p9=new Phase9StandoffWheelsFlow(1);
  for(Phase9StandoffWheelsFlow.Screen s:Phase9StandoffWheelsFlow.Screen.values())if(s.video()){
   p9.devJump(s.ordinal()+1,2);p9.confirm();assertTrue(p9.next(3));assertTrue(p9.screen().scan());assertFalse(p9.complete());assertFalse(p9.canRescan());
  }
  Phase10ExtensionBoardFlow p10=new Phase10ExtensionBoardFlow(1);
  for(Phase10ExtensionBoardFlow.Screen s:Phase10ExtensionBoardFlow.Screen.values())if(s.video()){
   p10.devJump(s.ordinal()+1,2);p10.confirm();assertTrue(p10.next(3));assertFalse(p10.screen().video());assertFalse(p10.complete());
  }
  Phase11OledBatteryFlow p11=new Phase11OledBatteryFlow(1);
  for(Phase11OledBatteryFlow.Screen s:Phase11OledBatteryFlow.Screen.values())if(s.video()){
   p11.devJump(s.ordinal()+1,2);p11.confirm();assertTrue(p11.next(3));assertFalse(p11.screen().video());assertFalse(p11.complete());
  }
 }
}
