package dev.chyken.assembl_ar.camera;
import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import static org.junit.Assert.*;
public final class Phase3AssetCatalogTest {
    private List<String> paths()throws Exception{
        Path root=Paths.get("src/main/assets/references/Phase3");
        try(java.util.stream.Stream<Path> stream=Files.walk(root)){return stream.filter(Files::isRegularFile).map(p->Phase3AssetCatalog.ROOT+"/"+root.relativize(p).toString().replace('\\','/')).sorted().collect(Collectors.toList());}
    }
    @Test public void exactRawFoldersAndSpacedAliasesResolve(){
        assertEquals("front",Phase3AssetCatalog.role("STEP3_1MOTORDRIVER"));assertEquals("front",Phase3AssetCatalog.role("MOTORDRIVER FRONT"));
        assertEquals("front",Phase3AssetCatalog.role("MOTORDRIVER_FRONT"));assertEquals("back",Phase3AssetCatalog.role("MOTORDRIVER_BACK_INVALID"));
        assertEquals("assembly",Phase3AssetCatalog.role("STEP3_5ASSEMBLYSCAN"));assertNull(Phase3AssetCatalog.role("P3_VIDEOGUIDE"));
    }
    @Test public void allPhotosAreRegisteredWithUniqueIdsAndPhaseScope()throws Exception{
        List<String> paths=paths();List<ComponentReferenceSpec> specs=Phase3AssetCatalog.referencesForPaths(paths);
        long expected=paths.stream().filter(Phase2AssetCatalog::image).filter(p->Phase3AssetCatalog.role(p.substring(Phase3AssetCatalog.ROOT.length()+1).split("/")[0])!=null).count();
        assertEquals(expected,specs.size());assertTrue(specs.size()>=59);Set<String> ids=new HashSet<>(),roles=new HashSet<>();
        for(ComponentReferenceSpec s:specs){assertTrue(ids.add(s.getReferenceId()));assertEquals(3,s.getAssemblyStepNumber());assertEquals("dual_motor_driver",s.getComponentId());assertTrue(Files.isRegularFile(Paths.get("src/main/assets",s.getJpegAssetPath())));roles.add(Phase3AssetCatalog.referenceRole(s.getReferenceId()));}
        assertEquals(new HashSet<>(Arrays.asList("front","back","assembly")),roles);
    }
    @Test public void actualGuideTypoAndRequestedSpellingBothSelectCorrectArtwork()throws Exception{
        List<String> paths=paths();assertTrue(Phase3AssetCatalog.guideForPaths(paths,1).endsWith("GUIDEFORMOTORDRIVERSCANNING.jpg"));
        assertTrue(Phase3AssetCatalog.guideForPaths(paths,4).endsWith("GUIDEFORMOTORDRIVERINSTALLATIONSCANNING.jpg"));
        assertEquals(Phase3AssetCatalog.guideForPaths(paths,4),Phase3AssetCatalog.guideForPaths(paths,5));
        assertNull(Phase3AssetCatalog.guideForPaths(paths,2));
        assertNotNull(Phase3AssetCatalog.guideForPaths(Collections.singletonList("references/Phase3/P3_SCANNINGGUIDE/GUIDEFORMOTORDRIVERINSTALLATIONSCANNING.jpg"),4));
    }
    @Test public void clipsUseAuthoredOrder(){assertEquals(0,Phase3AssetCatalog.clipOrder("P3_HOW2SEATMOTORDRIVER.mp4"));assertEquals(1,Phase3AssetCatalog.clipOrder("P3_SCREWPOSITIONSREMINDER.mp4"));}
}
