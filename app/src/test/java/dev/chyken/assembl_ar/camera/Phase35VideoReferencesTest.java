package dev.chyken.assembl_ar.camera;

import dev.chyken.assembl_ar.vision.*;
import java.io.File;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class Phase35VideoReferencesTest {
    @Test public void reviewedPixelsAndMasksHaveDistinctIdentityAndInstallationRoles() {
        Set<String> ids=new HashSet<>(),paths=new HashSet<>(); int fronts=0,backs=0,placed=0,invalid=0;
        for(ComponentReferenceSpec s:Phase35VideoReferences.all()) {
            assertTrue(ids.add(s.getReferenceId())); assertTrue(paths.add(s.getJpegAssetPath()));
            assertTrue(new File("src/main/assets",s.getJpegAssetPath()).isFile());
            assertFalse(s.getJpegAssetPath().contains("heldout")); assertFalse(s.hasVerifiedPolarityAnchors());
            Phase35VideoReferences.Entry e=Phase35VideoReferences.annotation(s.getJpegAssetPath());
            assertNotNull(e); assertEquals(4,e.body.size());
            if(e.part.equals("nano"))assertEquals(e.role.equals("invalid"),Phase5AssetCatalog.reversedInstallationReference(s.getReferenceId()));
            for(List<VisionPoint> region:e.regions)for(VisionPoint p:region) {
                assertTrue(p.getX()>=0&&p.getX()<=1); assertTrue(p.getY()>=0&&p.getY()<=1);
            }
            if(e.role.equals("front")){fronts++;assertEquals(1,e.regions.size());}
            else if(e.role.equals("back")){backs++;assertEquals(1,e.regions.size());}
            else {assertEquals(5,e.regions.size());if(e.role.equals("assembly"))placed++;else invalid++;}
            assertEquals(e.role.equals("assembly")||e.role.equals("invalid"),UnifiedReferenceCatalog.assembly(s));
        }
        assertEquals(11,fronts);assertEquals(1,backs);assertEquals(10,placed);assertEquals(6,invalid);
        assertEquals(28,paths.size());
    }
    @Test public void individualToolOnlyGetsIsolatedFrontFacesNotInstalledReversals() {
        List<ComponentReferenceSpec> all=new ArrayList<>(Phase35VideoReferences.individual());
        assertEquals(11,all.size());assertEquals(11,UnifiedReferenceCatalog.individual(all).size());
        for(ComponentReferenceSpec s:all) {
            assertEquals("front",Phase35VideoReferences.annotation(s.getJpegAssetPath()).role);
            assertEquals(0,s.getAssemblyStepNumber());assertFalse(UnifiedReferenceCatalog.assembly(s));
            RecognitionRequest request=UnifiedReferenceCatalog.request(s,null);
            assertEquals(0,request.getPhase3Stage());assertEquals(0,request.getPhase5Stage());
        }
    }
    @Test public void nestedAliasesPreserveAuthoredGeometryAndNegativeType() {
        List<String> driver=new ArrayList<>(),nano=new ArrayList<>();
        for(ComponentReferenceSpec s:Phase35VideoReferences.all()) {
            Phase35VideoReferences.Entry e=Phase35VideoReferences.annotation(s.getJpegAssetPath());
            (e.part.equals("driver")?driver:nano).add(s.getJpegAssetPath());
            if(e.part.equals("driver"))assertEquals(e.role,Phase3AssetCatalog.roleForPath(s.getJpegAssetPath()));
        }
        for(ComponentReferenceSpec s:Phase3AssetCatalog.referencesForPaths(driver))assertSame(Phase35VideoReferences.find(s.getJpegAssetPath()),s);
        for(ComponentReferenceSpec s:Phase5AssetCatalog.referencesForPaths(nano))assertSame(Phase35VideoReferences.find(s.getJpegAssetPath()),s);
        assertEquals("back",Phase5AssetCatalog.role("STEP5_1ARDUINONANO_INVALID"));
        assertEquals("back",Phase5AssetCatalog.role("STEP5_5ARDUINONANO_INVALID"));
        assertEquals("invalid",Phase3AssetCatalog.roleForPath("references/Phase3/STEP3_1MOTORDRIVER_INVALID/original.jpg"));
    }
}
