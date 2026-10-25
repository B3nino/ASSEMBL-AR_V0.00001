package dev.chyken.assembl_ar.camera;
import dev.chyken.assembl_ar.vision.*;
import dev.chyken.assembl_ar.workflow.phase.*;
import static dev.chyken.assembl_ar.data.NeobotSeedData.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public final class StartupReferencePlanTest {
 private ComponentReferenceSpec ref(String id,String component,int phase){return ComponentReferenceSpec.builder(id,component,id,30,40).jpegAssetPath("references/Phase"+phase+"/"+id+".jpg").assemblyStepNumber(phase).build();}
 @Test public void savedWheelVerificationWarmsCurrentAndUpcomingBanksWithinBound(){
  List<ComponentReferenceSpec> all=new ArrayList<>();for(int i=0;i<20;i++){all.add(ref("p9scene_assembly_"+i,MAIN_BOARD,9));all.add(ref("p9scene_invalid_"+i,MAIN_BOARD,9));all.add(ref("p9scene_wheel_"+i,WHEEL,9));all.add(ref("p10scene_cable_"+i,DATA_CABLE,10));}
  Phase9StandoffWheelsFlow flow=new Phase9StandoffWheelsFlow(1);flow.devJump(8,2);
  PhaseSessionCheckpoint saved=new PhaseSessionCheckpoint(AssemblyPhaseCatalog.CENTER_STANDOFF,false,flow.mode(),0,0,false,false,flow.snapshot());String before=saved.encode();
  List<ComponentReferenceSpec> warm=StartupReferencePlan.select(all,saved);assertTrue(warm.size()<=24);assertTrue(warm.size()>=12);
  assertTrue(warm.stream().anyMatch(s->s.getReferenceId().startsWith("p9scene_invalid_")));assertFalse(warm.stream().anyMatch(s->s.getReferenceId().startsWith("p9scene_wheel_")));assertTrue(warm.stream().anyMatch(s->s.getReferenceId().startsWith("p10scene_cable_")));assertEquals(before,saved.encode());
 }
 @Test public void emptyCatalogIsSafeForEverySavedPhase(){
  for(AssemblyPhase phase:AssemblyPhaseCatalog.phases()){
   PhaseSessionCheckpoint saved=new PhaseSessionCheckpoint(phase.getId(),false,PhaseFlow.Mode.DEMO,0,0,false,false,"");
   assertTrue(StartupReferencePlan.select(Collections.emptyList(),saved).isEmpty());
  }
 }
}
