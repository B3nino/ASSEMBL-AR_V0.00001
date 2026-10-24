package dev.chyken.assembl_ar.camera;
import org.junit.Test;import java.util.*;import dev.chyken.assembl_ar.vision.*;import static org.junit.Assert.*;
public final class Phase12VideoReferencesTest {
 @Test public void reviewedReferencesStayUniqueAndSeparateFromHeldOutFrames(){
  Set<String> paths=new HashSet<>(),ids=new HashSet<>();int board=0,parts=0,top=0,left=0,right=0;
  for(ComponentReferenceSpec s:Phase12VideoReferences.all()){
   assertTrue(paths.add(s.getJpegAssetPath()));assertTrue(ids.add(s.getReferenceId()));assertFalse(s.getJpegAssetPath().contains("heldout"));
   assertFalse(s.hasVerifiedPolarityAnchors());String role=Phase2AssetCatalog.referenceRole(s.getReferenceId());
   if(role==null){if(s.getComponentId().equals("main_board"))board++;else parts++;assertEquals(0,s.getAssemblyStepNumber());assertFalse(UnifiedReferenceCatalog.assembly(s));assertEquals(0,UnifiedReferenceCatalog.request(s,null).getPhase2Stage());assertEquals(1,UnifiedReferenceCatalog.request(s,null).getExpectedInstanceCount());}
   else {assertEquals(2,s.getAssemblyStepNumber());assertTrue(UnifiedReferenceCatalog.assembly(s));if(role.equals("brackets")){assertEquals(4,s.getFeatureRegionsNormalized().size());}else{if(role.equals("top")){top++;assertEquals(6,s.getFeatureRegionsNormalized().size());}else{assertEquals(1,s.getFeatureRegionsNormalized().size());if(role.equals("left"))left++;else if(role.equals("right"))right++;}}}
  }
  assertEquals(3,board);assertEquals(14,parts);assertEquals(3,top);assertEquals(2,left);assertEquals(2,right);
  assertEquals("Individual camera gets isolated crops, never grouped inventory scenes",17,UnifiedReferenceCatalog.individual(new ArrayList<>(Phase12VideoReferences.all())).size());
 }
 @Test public void nestedLightingFoldersResolveWithoutDuplicateSceneRegistrations(){
  List<String> paths=new ArrayList<>();for(ComponentReferenceSpec s:Phase12VideoReferences.all())if(s.getJpegAssetPath().contains("/Phase2/"))paths.add(s.getJpegAssetPath());
  List<ComponentReferenceSpec> specs=Phase2AssetCatalog.referencesForPaths(paths);assertEquals(24,specs.size());
  for(ComponentReferenceSpec s:specs)assertSame(Phase12VideoReferences.find(s.getJpegAssetPath()),s);
  assertEquals("left",Phase2AssetCatalog.role("STEP2_TOPVIEW_LEFT"));assertEquals("right",Phase2AssetCatalog.role("STEP2_TOPVIEW_RIGHT"));assertEquals("top",Phase2AssetCatalog.role("STEP2_TOPVIEW_FULL"));
 }
}
