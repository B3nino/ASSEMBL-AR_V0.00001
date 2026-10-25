package dev.chyken.assembl_ar.camera;

import dev.chyken.assembl_ar.vision.*;
import java.io.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class Phase789VideoReferencesTest {
    @Test public void shadowSensorVariantsHaveTwoSeparateBarrelsAndIndependentBoardMasks(){int count=0;
        for(ComponentReferenceSpec s:Phase789VideoReferences.all())if(s.getJpegAssetPath().contains("VIDEO789_fDOmql_e_73_assembly")||s.getJpegAssetPath().contains("VIDEO789_fDOmql_e_79_assembly")){
            count++;Phase789VideoReferences.Entry e=Phase789VideoReferences.annotation(s.getJpegAssetPath());assertEquals(2,e.identity.size());assertTrue(e.identity.get(0).get(2).getX()<e.identity.get(1).get(0).getX());assertTrue(e.context.size()>=2);
        }assertEquals(2,count);
    }
    @Test public void realPixelsHaveIndependentHardwareRegionsAndDisjointReplayPaths(){
        Set<String> paths=new HashSet<>(),ids=new HashSet<>();int trackers=0,sensors=0,wheels=0;
        for(ComponentReferenceSpec s:Phase789VideoReferences.all()){
            assertTrue(paths.add(s.getJpegAssetPath()));assertTrue(ids.add(s.getReferenceId()));
            assertTrue(new File("src/main/assets",s.getJpegAssetPath()).isFile());assertFalse(s.getJpegAssetPath().contains("heldout"));
            Phase789VideoReferences.Entry e=Phase789VideoReferences.annotation(s.getJpegAssetPath());assertNotNull(e);
            List<List<VisionPoint>> groups=new ArrayList<>(e.regions);groups.addAll(e.identity);groups.addAll(e.context);groups.add(e.body);
            for(List<VisionPoint> r:groups){assertEquals(4,r.size());for(VisionPoint v:r){assertTrue(v.getX()>=0&&v.getX()<=1);assertTrue(v.getY()>=0&&v.getY()<=1);}}
            boolean installed=e.role.equals("trackerwork")||e.role.equals("assembly")||e.role.equals("invalid");
            assertEquals(installed||e.role.equals("pair"),UnifiedReferenceCatalog.assembly(s));
            if(e.role.equals("trackerwork")){trackers++;assertEquals(7,e.regions.size());assertEquals("trackerwork",Phase7AssetCatalog.referenceRole(s.getReferenceId()));}
            if(e.phase==8){sensors++;if(e.role.equals("front")){assertEquals(1,e.identity.size());assertTrue(e.context.isEmpty());assertEquals("front",Phase8AssetCatalog.referenceRole(s.getReferenceId()));}else{assertEquals(2,e.identity.size());assertFalse(e.context.isEmpty());}assertEquals(e.role.equals("invalid"),Phase8AssetCatalog.inwardReference(s.getJpegAssetPath()));}
            if(e.phase==9&&installed){wheels++;assertEquals(4,e.regions.size());}
        }
        assertEquals(58,paths.size());assertEquals(15,trackers);assertEquals(20,sensors);assertEquals(4,wheels);
    }
    @Test public void nestedLightingFoldersRetainManualMasksAndExistingCatalogAliases(){
        Map<Integer,List<String>> paths=new HashMap<>();
        for(ComponentReferenceSpec s:Phase789VideoReferences.all())paths.computeIfAbsent(s.getAssemblyStepNumber(),p->new ArrayList<>()).add(s.getJpegAssetPath());
        List<ComponentReferenceSpec> specs=new ArrayList<>();specs.addAll(Phase7AssetCatalog.referencesForPaths(paths.get(7)));specs.addAll(Phase8AssetCatalog.referencesForPaths(paths.get(8)));specs.addAll(Phase9AssetCatalog.referencesForPaths(paths.get(9)));
        assertEquals(58,specs.size());for(ComponentReferenceSpec s:specs)assertSame(Phase789VideoReferences.find(s.getJpegAssetPath()),s);
        assertEquals("caster",Phase7AssetCatalog.role("STEP7_1CASTERWHEELSCANS"));assertEquals("trackerwork",Phase7AssetCatalog.role("STEP7_TOPVIEW"));
    }
    @Test public void individualViewsReuseRealPixelsWithoutActivatingAssemblyStageMatchers(){
        for(ComponentReferenceSpec s:Phase789VideoReferences.individual()){
            assertEquals(0,s.getAssemblyStepNumber());assertFalse(UnifiedReferenceCatalog.assembly(s));
            assertTrue(Arrays.asList("line_tracker_module","caster_wheel","center_standoff","drive_wheel","ultrasonic_distance_sensor").contains(s.getComponentId()));
            RecognitionRequest r=UnifiedReferenceCatalog.request(s,null);
            assertEquals(0,r.getPhase7Stage());assertEquals(0,r.getPhase8Stage());assertEquals(0,r.getPhase9Stage());
        }
    }
}
