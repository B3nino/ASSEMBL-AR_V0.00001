package dev.chyken.assembl_ar.camera;

import dev.chyken.assembl_ar.vision.*;
import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public class PlanFReferenceCatalogTest {
    private List<ComponentReferenceSpec> bundled() throws Exception {
        Path root = Paths.get("src/main/assets");
        List<String> paths;
        try (java.util.stream.Stream<Path> files = Files.walk(root.resolve("references"))) {
            paths = files.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\','/')).collect(Collectors.toList());
        }
        List<ComponentReferenceSpec> all = new ArrayList<>();
        all.addAll(AssemblyProgressReferenceCatalog.dynamicPhase1());
        all.addAll(Phase2AssetCatalog.referencesForPaths(paths));
        all.addAll(Phase3AssetCatalog.referencesForPaths(paths));
        all.addAll(Phase5AssetCatalog.referencesForPaths(paths));
        all.addAll(Phase7AssetCatalog.referencesForPaths(paths));
        all.addAll(Phase8AssetCatalog.referencesForPaths(paths));
        all.addAll(Phase9AssetCatalog.referencesForPaths(paths));
        all.addAll(Phase10AssetCatalog.referencesForPaths(paths));
        all.addAll(Phase11AssetCatalog.referencesForPaths(paths));
        return all;
    }
    @Test public void everyAuthoredScanFamilyHasOnlyPositiveBoundedHelpPhotos() throws Exception {
        List<ComponentReferenceSpec> all = bundled();
        Set<String> checked = new HashSet<>();
        for (ComponentReferenceSpec s : all) {
            if (UnifiedReferenceCatalog.negative(s)) continue;
            RecognitionRequest request = UnifiedReferenceCatalog.request(s, null);
            String scope = ActiveReferenceScope.key(request) + ":" + request.getPhase2VerificationView();
            if (!checked.add(scope)) continue;
            List<ComponentReferenceSpec> help = PlanFReferenceCatalog.select(all, request);
            assertFalse("No help photo for " + scope, help.isEmpty());
            assertTrue(help.size() <= 6);
            Set<String> paths = new HashSet<>();
            for (ComponentReferenceSpec photo : help) {
                assertFalse("Rejection photo offered as guidance: " + photo.getReferenceId(), UnifiedReferenceCatalog.negative(photo));
                assertTrue(paths.add(photo.getJpegAssetPath()));
                assertTrue(Files.isRegularFile(Paths.get("src/main/assets").resolve(photo.getJpegAssetPath())));
            }
        }
        assertTrue("Expected phase 1–11 camera target coverage", checked.size() >= 25);
    }
    @Test public void bottomTrackerHelpDoesNotUseLooseTrackerOrCasterOnlyPhotos() throws Exception {
        RecognitionRequest r = new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("main_board"),null,null,null,1,7).withPhase7Stage(4);
        for (ComponentReferenceSpec s : PlanFReferenceCatalog.select(bundled(),r))
            assertEquals("trackerwork",Phase7AssetCatalog.referenceRole(s.getReferenceId()));
        assertTrue(PlanFReferenceCatalog.targetName(r,"Board").contains("bottom"));
    }
    @Test public void phaseTwoSideGuidanceNeverSubstitutesTheOtherSide() throws Exception {
        List<ComponentReferenceSpec> all = bundled();
        for (String view : new String[]{"top","left","right"}) {
            RecognitionRequest r = new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("main_board"),null,null,null,1,2).withPhase2Stage(5).withPhase2VerificationView(view);
            List<ComponentReferenceSpec> help = PlanFReferenceCatalog.select(all,r);
            assertFalse(help.isEmpty());
            for(ComponentReferenceSpec s:help) assertEquals(view,Phase2AssetCatalog.referenceRole(s.getReferenceId()));
        }
    }
    @Test public void guidanceNeedsNoNewReferencesOrDescriptorCopies() {
        ComponentReferenceSpec s=ComponentReferenceSpec.builder("p10scene_cable_test",Phase10AssetCatalog.target(1),"Cable",150,20).jpegAssetPath("references/Phase10/STEP10_1DATACABLESCANNING/a.jpg").assemblyStepNumber(10).build();
        RecognitionRequest r=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(Phase10AssetCatalog.target(1)),null,null,null,1,10).withPhase10Stage(1);
        assertSame(s,PlanFReferenceCatalog.select(Collections.singletonList(s),r).get(0));
        assertTrue(PlanFReferenceCatalog.select(null,r).isEmpty());
        assertTrue(PlanFReferenceCatalog.select(Collections.singletonList(s),null).isEmpty());
    }
}
