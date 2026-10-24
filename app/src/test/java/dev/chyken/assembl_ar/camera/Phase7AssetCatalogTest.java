package dev.chyken.assembl_ar.camera;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import dev.chyken.assembl_ar.vision.*;
import dev.chyken.assembl_ar.data.NeobotSeedData;

public final class Phase7AssetCatalogTest {
    private List<String> paths() throws Exception {
        Path root=Paths.get("src/main/assets");
        try(java.util.stream.Stream<Path> files=Files.walk(root.resolve("references/Phase7"))){
            return files.filter(Files::isRegularFile).map(p->root.relativize(p).toString().replace('\\','/')).collect(Collectors.toList());
        }
    }
    @Test public void allRawPhotosRegisterOnceAndEveryRoleStaysWithinItsStep() throws Exception {
        List<String> paths=paths();List<ComponentReferenceSpec> specs=Phase7AssetCatalog.referencesForPaths(paths);
        assertEquals(paths.stream().filter(Phase2AssetCatalog::image).filter(path->Phase7AssetCatalog.role(path.substring(Phase7AssetCatalog.ROOT.length()+1).split("/")[0])!=null).count(),specs.size());assertTrue(specs.size()>=153);Set<String> ids=new HashSet<>();Map<String,Integer> roles=new HashMap<>();
        for(ComponentReferenceSpec spec:specs){
            assertTrue(ids.add(spec.getReferenceId()));String role=Phase7AssetCatalog.referenceRole(spec.getReferenceId());assertNotNull(role);
            roles.put(role,roles.getOrDefault(role,0)+1);assertEquals(7,spec.getAssemblyStepNumber());assertEquals(1280,spec.getMaximumImageDimension());assertFalse(spec.hasVerifiedPolarityAnchors());
            int eligible=0;for(int step=1;step<=4;step++)if(Phase7AssetCatalog.eligible(role,step))eligible++;assertEquals(1,eligible);
        }
        long reviewed=Phase789VideoReferences.all().stream().filter(s->s.getAssemblyStepNumber()==7&&"trackers".equals(Phase7AssetCatalog.referenceRole(s.getReferenceId()))).count();
        assertEquals(42+reviewed,roles.get("trackers").longValue());assertTrue(roles.get("trackerwork")>=40);
        paths.addAll(new ArrayList<>(paths));assertEquals(specs.size(),Phase7AssetCatalog.referencesForPaths(paths).size());
    }
    @Test public void promptSpellingAndRealFolderAliasesAreAccepted(){
        assertEquals("casterworkbad",Phase7AssetCatalog.role("STEP7_3CASTER BOTTOMSCANS INVALId"));
        assertEquals("casterworkbad",Phase7AssetCatalog.role("STEP7_3CASTER_BOTTOMSCANS_INVALID"));
        assertFalse(Phase7AssetCatalog.eligible("casterwork",1));assertFalse(Phase7AssetCatalog.eligible("trackers",4));assertNull(Phase7AssetCatalog.role("P7 SCANNINGGUIDE"));
    }
    @Test public void mediaOrderAndPerStepGuidesUseActualAssets() throws Exception {
        List<String> paths=paths();Collections.reverse(paths);
        for(int step=1;step<=4;step++)assertNotNull(Phase7AssetCatalog.guideForPaths(paths,step));
        assertTrue(Phase7AssetCatalog.guideForPaths(paths,3).endsWith("GUIDEFORCASTERWHEELASSEMBLYSCANNING.jpg"));
        assertTrue(Phase7AssetCatalog.guideForPaths(paths,4).endsWith("GUIDEFORDUALTRACKERASSEMBLYSCANNER.jpg"));
        assertNull(Phase7AssetCatalog.guideForPaths(paths,5));
        for(int step=3;step<=4;step++){
            List<String> clips=Phase7AssetCatalog.videosForPaths(paths,step);assertEquals(2,clips.size());
            assertEquals((step-3)*2,Phase7AssetCatalog.clipOrder(clips.get(0)));assertEquals((step-3)*2+1,Phase7AssetCatalog.clipOrder(clips.get(1)));
        }
        assertEquals(0,Phase7AssetCatalog.clipOrder("P7_WHERETOPLACESCREWSFORCASTER.mp4"));
        assertEquals(0,Phase7AssetCatalog.clipOrder("P7_WHERETOPLACESCREWSFORCASTERWHEEL.mp4"));
        assertTrue(Phase7AssetCatalog.videosForPaths(paths,2).isEmpty());
    }
    @Test public void requestCannotEnableAnchorsOrLeakCountsAcrossSteps(){
        RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(NeobotSeedData.CASTER_WHEEL),null,null,null,1,7).withPhase7Stage(1).withRequestTag("fresh");
        assertEquals(1,request.getPhase7Stage());assertEquals("fresh",request.getRequestTag());
        assertFalse(new Phase7Observation(2,true,100).accepted(2));assertFalse(new Phase7Observation(1,false,100).accepted(2));assertTrue(new Phase7Observation(2,false,100).accepted(2));
        assertFalse(new Phase7Observation(1,false,100).accepted(0));assertFalse(new Phase7Observation(1,false,100).accepted(5));
    }
}
