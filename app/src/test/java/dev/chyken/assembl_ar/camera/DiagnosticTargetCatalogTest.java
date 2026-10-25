package dev.chyken.assembl_ar.camera;
import dev.chyken.assembl_ar.vision.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public final class DiagnosticTargetCatalogTest {
 private ComponentReferenceSpec ref(String id,String component,String path,int phase){return ComponentReferenceSpec.builder(id,component,component,40,30).jpegAssetPath(path).assemblyStepNumber(phase).build();}
 @Test public void selectorIncludesPreparedRealPhotosAndExcludesIllustrationsNegativesAndUnknownCounts(){
  List<ComponentReferenceSpec> all=Arrays.asList(
   ref("motor","gear_motor","references/Phase1/motor/a.jpg",0),
   ref("copy","gear_motor","references/Phase1/motor/a.jpg",0),
   ref("led","red_led","references/learning/led.jpg",0),
   ref("bad","gear_motor","references/Phase1/motor_INVALID/a.jpg",0),
   ref("pending","oled_display","references/Phase11/oled/a.jpg",0),
   ref("mounted",dev.chyken.assembl_ar.data.NeobotSeedData.DISTANCE_SENSOR,"references/Phase8/STEP8_2DISTANCESENSORHARDWARESCAN/a.jpg",8));
  List<DiagnosticTargetCatalog.Target> targets=DiagnosticTargetCatalog.build(all,id->id.equals("pending")?-1:70);
  assertEquals(2,targets.size());
  DiagnosticTargetCatalog.Target motor=targets.stream().filter(t->!t.assembly).findFirst().get();
  assertEquals(1,motor.references.size());assertEquals(70,motor.keypoints);assertTrue(motor.matches("gear motor"));assertFalse(motor.matches("battery"));
  assertTrue(targets.stream().anyMatch(t->t.assembly&&t.title.startsWith("Phase 8")));
 }
 @Test public void groupingNeverCreatesAnAssemblyForAnUnversionedLegacyScene(){
  assertTrue(DiagnosticTargetCatalog.build(Collections.singletonList(ref("old","main_board","references/assembly/section18/a.jpg",18)),id->30).isEmpty());
 }
 @Test public void sourceObjectsRemainSharedWithTheUnifiedLibrary(){
  ComponentReferenceSpec s=ref("nano","microcontroller","references/Phase5/STEP5_1ARDUINONANO/a.jpg",0);
  DiagnosticTargetCatalog.Target target=DiagnosticTargetCatalog.build(Collections.singletonList(s),id->123).get(0);
  assertSame(s,target.representative);assertEquals(123,target.keypoints);assertFalse(target.assembly);
 }
}
