package dev.chyken.assembl_ar.camera;
import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import static org.junit.Assert.*;

public final class Phase5AssetCatalogTest {
    private List<String> paths()throws Exception{
        Path root=Paths.get("src/main/assets/references/Phase5");
        try(java.util.stream.Stream<Path> files=Files.walk(root)){return files.filter(Files::isRegularFile).map(p->Phase5AssetCatalog.ROOT+"/"+root.relativize(p).toString().replace('\\','/')).sorted().collect(Collectors.toList());}
    }
    @Test public void everyPhotoRegistersOnceWithItsExactRoleAndScope()throws Exception{
        List<String> paths=paths();List<ComponentReferenceSpec> specs=Phase5AssetCatalog.referencesForPaths(paths);assertEquals(paths.stream().filter(Phase2AssetCatalog::image).filter(path->Phase5AssetCatalog.role(path.substring(Phase5AssetCatalog.ROOT.length()+1).split("/")[0])!=null).count(),specs.size());
        Set<String> ids=new HashSet<>();Map<String,Integer> counts=new HashMap<>();
        for(ComponentReferenceSpec spec:specs){assertTrue(ids.add(spec.getReferenceId()));assertEquals(5,spec.getAssemblyStepNumber());assertEquals(NeobotSeedData.MICROCONTROLLER,spec.getComponentId());assertEquals(18,spec.getPhysicalWidthMm(),0);assertEquals(45,spec.getPhysicalHeightMm(),0);counts.merge(Phase5AssetCatalog.referenceRole(spec.getReferenceId()),1,Integer::sum);}
        assertTrue(counts.get("front")>=17);assertTrue(counts.get("back")>=11);assertTrue(counts.get("assembly")>=15);assertTrue(counts.get("invalid")>=13);
        paths.addAll(new ArrayList<>(paths));assertEquals(specs.size(),Phase5AssetCatalog.referencesForPaths(paths).size());
    }
    @Test public void guideAndVideoAssetsCannotBecomeRecognitionReferences()throws Exception{
        List<String> paths=paths();assertEquals(1,Phase5AssetCatalog.videosForPaths(paths).size());assertTrue(Phase5AssetCatalog.videosForPaths(paths).get(0).endsWith("PHASE5_VIDS.mp4"));
        assertTrue(Phase5AssetCatalog.guideForPaths(paths,1).endsWith("GUIDEMICROCONTROLLERCOMPSCANNING.jpg"));assertTrue(Phase5AssetCatalog.guideForPaths(paths,3).endsWith("GUIDEFORMICROCONTROLLERHARDWARESCANNING.jpg"));
        assertNull(Phase5AssetCatalog.guideForPaths(paths,2));assertNull(Phase5AssetCatalog.guideForPaths(paths,4));
        assertNotNull(Phase5AssetCatalog.guideForPaths(Collections.singletonList("references/Phase5/P5 SCANNINGGUIDE/GUIDEHARDWARE.png"),3));
        assertNull(Phase5AssetCatalog.role("P5_VIDEOGUIDE"));assertNull(Phase5AssetCatalog.role("P5_SCANNINGGUIDE"));
    }
}
