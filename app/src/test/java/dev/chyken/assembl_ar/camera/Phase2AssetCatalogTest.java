package dev.chyken.assembl_ar.camera;
import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import static org.junit.Assert.*;

public final class Phase2AssetCatalogTest {
    @Test public void aliasesAndUnicodeFolderDecorationResolveToTheSameRoles(){
        assertEquals("brackets",Phase2AssetCatalog.role("BRACKETS REFERENCE"));
        assertEquals("motors",Phase2AssetCatalog.role("MOTORS_REFERENCE"));
        assertEquals("invalid",Phase2AssetCatalog.role("STEP2_5_HARDWARE_CHECK_INVALIDSCANS"));
        assertEquals("P2SCANNINGGUIDE",Phase2AssetCatalog.normalize("₩ P2 SCANNINGGUIDE"));
    }
    @Test public void everyRawPhotoIsRegisteredOnceAndGuidesAndVideosStayOutOfOrb() throws Exception {
        Path root=Paths.get("src/main/assets/references/Phase2");
        List<String> paths;
        try(java.util.stream.Stream<Path> stream=Files.walk(root)){
            paths=stream.filter(Files::isRegularFile).map(p->"references/Phase2/"+root.relativize(p).toString().replace('\\','/')).collect(Collectors.toList());
        }
        List<ComponentReferenceSpec> specs=Phase2AssetCatalog.referencesForPaths(paths);
        long expected=paths.stream().filter(Phase2AssetCatalog::image)
                .filter(p->{String role=Phase2AssetCatalog.role(p.substring(Phase2AssetCatalog.ROOT.length()+1).split("/")[0]);return role!=null&&(!role.equals("invalid")||p.contains("STEP2_BARE_BOARD_INVALID"));}).count();
        assertEquals(expected,specs.size());assertTrue(specs.size()>50);
        Set<String> ids=new HashSet<>();Set<String> roles=new HashSet<>();
        for(ComponentReferenceSpec spec:specs){
            assertTrue(ids.add(spec.getReferenceId()));
            assertEquals(spec.getReferenceId().startsWith("video12_")?0:2,spec.getAssemblyStepNumber());
            assertTrue(Files.isRegularFile(Paths.get("src/main/assets",spec.getJpegAssetPath())));
            String role=Phase2AssetCatalog.referenceRole(spec.getReferenceId());
            if(role!=null)roles.add(role);else assertTrue(spec.getReferenceId().startsWith("video12_"));
            assertFalse(spec.hasVerifiedPolarityAnchors());
            if(spec.getJpegAssetPath().contains("INVALID")){assertTrue(spec.getJpegAssetPath().contains("STEP2_BARE_BOARD_INVALID"));assertEquals("invalid",Phase2AssetCatalog.referenceRole(spec.getReferenceId()));}
        }
        assertEquals(new HashSet<>(Arrays.asList("brackets","motors","top","left","right","invalid")),roles);
    }
    @Test public void authoredVideoOrderDoesNotDependOnAlphabeticalOrder(){
        assertEquals(0,Phase2AssetCatalog.clipOrder("P2_Where to insert motor brackets.mp4"));
        assertEquals(1,Phase2AssetCatalog.clipOrder("P2_MOTORSFACEINWARDS.mp4"));
        assertEquals(2,Phase2AssetCatalog.clipOrder("P2_SCREWPOSITIONS.mp4"));
    }
    @Test public void eachHardwareCheckpointUsesItsOwnActualGuideAndMotorScanDoesNotUseASideGuide() throws Exception {
        Path root=Paths.get("src/main/assets/references/Phase2/P2_SCANNINGGUIDE");
        List<String> paths;
        try(java.util.stream.Stream<Path> stream=Files.walk(root)){
            paths=stream.filter(Files::isRegularFile).map(p->"references/Phase2/P2_SCANNINGGUIDE/"+p.getFileName()).sorted().collect(Collectors.toList());
        }
        assertTrue(Phase2AssetCatalog.guideForPaths(paths,2,5,"top").endsWith("GUIDEFORASSEMBLYSCANNING.jpg"));
        assertTrue(Phase2AssetCatalog.guideForPaths(paths,2,5,"left").endsWith("GUIDEFORLEFTMOTORSCANNING.jpg"));
        assertTrue(Phase2AssetCatalog.guideForPaths(paths,2,5,"right").endsWith("GUIDEFORRIIGHTTMOTORSCANNING.jpg"));
        assertTrue(Phase2AssetCatalog.guideForPaths(paths,2,2,"top").endsWith("GUIDEFORMOTORSCANNING.jpg"));
    }
}
