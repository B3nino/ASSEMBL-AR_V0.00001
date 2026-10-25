package dev.chyken.assembl_ar.camera;
import dev.chyken.assembl_ar.vision.*;import java.util.*;import org.junit.Test;import static org.junit.Assert.*;
public final class UnifiedReferenceCatalogTest {
    @Test public void groupedTargetsCarryTheirRequiredInstanceCountsAndOnlyTopSideScansHaveAView(){
        List<ComponentReferenceSpec> refs=new ArrayList<>();
        refs.addAll(Phase2AssetCatalog.referencesForPaths(Arrays.asList("references/Phase2/STEP2_1_BRACKETS/a.jpg","references/Phase2/STEP2_2_MOTORSCANS/a.jpg")));
        refs.addAll(Phase7AssetCatalog.referencesForPaths(Collections.singletonList("references/Phase7/STEP7_2LINETRACKERSSCANS/a.jpg")));
        refs.addAll(Phase9AssetCatalog.referencesForPaths(Collections.singletonList("references/Phase9/STEP9_2DUALWHEELS/a.jpg")));
        assertEquals(4,refs.size());
        for(ComponentReferenceSpec s:refs){RecognitionRequest r=UnifiedReferenceCatalog.request(s,null);assertEquals(s.getComponentId().equals("motor_bracket")?4:2,r.getExpectedInstanceCount());assertNull(r.getPhase2VerificationView());}
    }
    private ComponentReferenceSpec ref(String path,int step){return ComponentReferenceSpec.builder("test_"+path,"main_board","Board",100,120).jpegAssetPath(path).assemblyStepNumber(step).build();}
    @Test public void phaseProvenanceAndValidUndersideAssemblyAreDistinctFromNegativePhotos(){ComponentReferenceSpec a=ref("references/Phase7/STEP7_3CASTER_BOTTOMSCANS/front.jpg",7);assertEquals(7,UnifiedReferenceCatalog.phase(a));assertTrue(UnifiedReferenceCatalog.assembly(a));assertFalse(UnifiedReferenceCatalog.negative(a));assertTrue(UnifiedReferenceCatalog.negative(ref("references/Phase2/STEP2_BARE_BOARD_INVALID/bare.jpg",2)));}
    @Test public void individualSelectionUsesTheSameObjectsWithoutAssemblyScenes(){ComponentReferenceSpec a=ref("references/Phase1/MainboardFront/board.jpg",0),b=ref("references/Phase2/STEP2_5_HARDWARE_CHECK_TOPVIEW/work.jpg",2);List<ComponentReferenceSpec> chosen=UnifiedReferenceCatalog.individual(Arrays.asList(a,b));assertEquals(1,chosen.size());assertSame(a,chosen.get(0));}
}
