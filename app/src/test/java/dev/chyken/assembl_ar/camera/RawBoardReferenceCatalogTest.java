package dev.chyken.assembl_ar.camera;

import org.junit.Test;
import java.util.*;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import static org.junit.Assert.*;

public final class RawBoardReferenceCatalogTest {
    @Test public void rawFoldersAliasesNestedPhotosAndFaceIdentityAreDiscoveredWithoutAFilenameManifest() {
        Map<String,String[]> tree=new HashMap<>();
        tree.put("references/Phase1",new String[]{"MainboardFront","MainboardBack","Mainboardbottom","other"});
        tree.put("references/Phase1/MainboardFront",new String[]{"b.JPG","a.png","nested","notes.txt"});
        tree.put("references/Phase1/MainboardFront/nested",new String[]{"c.jpeg"});
        tree.put("references/Phase1/MainboardBack",new String[]{"bottom.jpg"});
        tree.put("references/Phase1/Mainboardbottom",new String[]{"another.PNG"});
        List<ComponentReferenceSpec> photos=RawBoardReferenceCatalog.scan(
                path->tree.getOrDefault(path,new String[0]));
        assertEquals(5,photos.size());
        Set<String> ids=new HashSet<>();
        int bottoms=0;
        for(ComponentReferenceSpec photo:photos) {
            assertTrue(ids.add(photo.getReferenceId()));
            assertEquals("main_board",photo.getComponentId());
            assertEquals(1,photo.getAssemblyStepNumber());
            assertTrue(photo.isAutomaticBoardGeometry());
            assertFalse(photo.hasVerifiedPolarityAnchors());
            assertEquals(100,photo.getPhysicalWidthMm(),0);
            assertEquals(120,photo.getPhysicalHeightMm(),0);
            if(photo.isBottomFace()) bottoms++;
        }
        assertEquals(2,bottoms);
    }
}
