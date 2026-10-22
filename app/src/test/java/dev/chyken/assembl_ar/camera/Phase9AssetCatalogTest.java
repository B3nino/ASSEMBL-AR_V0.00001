package dev.chyken.assembl_ar.camera;

import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import static org.junit.Assert.*;

public final class Phase9AssetCatalogTest {
    private List<String> paths()throws Exception{try(Stream<Path> s=Files.walk(Paths.get("src/main/assets/references/Phase9"))){return s.filter(Files::isRegularFile).map(p->p.toString().replace('\\','/').substring("src/main/assets/".length())).collect(Collectors.toList());}}
    @Test public void allRawPhotosRegisterOnceWithTheirCorrectRole()throws Exception{
        List<String> paths=paths();List<ComponentReferenceSpec> refs=Phase9AssetCatalog.referencesForPaths(paths);assertEquals(paths.stream().filter(path->Phase2AssetCatalog.image(path)&&Phase9AssetCatalog.role(path.substring(Phase9AssetCatalog.ROOT.length()+1).split("/")[0])!=null).count(),refs.size());
        Map<String,Integer> counts=new HashMap<>();Set<String> ids=new HashSet<>();
        for(ComponentReferenceSpec spec:refs){assertTrue(ids.add(spec.getReferenceId()));String role=Phase9AssetCatalog.referenceRole(spec.getReferenceId());counts.put(role,counts.getOrDefault(role,0)+1);assertEquals(9,spec.getAssemblyStepNumber());assertFalse(spec.getJpegAssetPath().contains("GUIDE"));}
        assertTrue(counts.get("standoff")>=18);assertTrue(counts.get("pair")>=30);assertTrue(counts.get("wheel")>=17);assertTrue(counts.get("assembly")>=20);assertTrue(counts.get("invalid")>=31);
    }
    @Test public void bothExactVideosAndAllGuidesResolve()throws Exception{
        List<String> p=paths();assertEquals(1,Phase9AssetCatalog.videosForPaths(p,false).size());assertTrue(Phase9AssetCatalog.videosForPaths(p,false).get(0).endsWith("P9_STANDOFFPLACEMENT.mp4"));assertEquals(1,Phase9AssetCatalog.videosForPaths(p,true).size());assertTrue(Phase9AssetCatalog.videosForPaths(p,true).get(0).endsWith("P9_WHEELS PLACEMENT.mp4"));
        for(int stage=1;stage<=4;stage++)assertNotNull(Phase9AssetCatalog.guideForPaths(p,stage));
        assertTrue(Phase9AssetCatalog.guideForPaths(p,3).contains("INVALIDNOWHEELS"));assertTrue(Phase9AssetCatalog.guideForPaths(p,4).contains("TOPVIEWWHEELS"));
    }
    @Test public void newPhotosDiscoverAutomaticallyWithoutRegisteringGuidesAsEvidence(){
        String p=Phase9AssetCatalog.ROOT+"/WHEELSREF/future.PNG";
        assertEquals(1,Phase9AssetCatalog.referencesForPaths(Arrays.asList(p,p,Phase9AssetCatalog.ROOT+"/P9 SCANNINGGUIDE/no.png")).size());
        assertNull(Phase9AssetCatalog.referenceRole("p8scene_assembly_x"));assertEquals("invalid",Phase9AssetCatalog.role("STEP9_4ASSEMBLYSCANINVALID"));
    }
}
