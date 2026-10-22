package dev.chyken.assembl_ar.camera;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.nio.file.*;
import java.util.stream.*;

public final class Phase8AssetCatalogTest {
    private List<String> paths()throws Exception{try(Stream<Path> s=Files.walk(Paths.get("src/main/assets/references/Phase8"))){return s.filter(Files::isRegularFile).map(p->p.toString().replace('\\','/').substring("src/main/assets/".length())).collect(Collectors.toList());}}
    @Test public void everyPhotoRoleIsRegisteredAndMediaIsExcluded()throws Exception{List<String> p=paths();assertEquals(p.stream().filter(path->Phase2AssetCatalog.image(path)&&Phase8AssetCatalog.role(path.substring(Phase8AssetCatalog.ROOT.length()+1).split("/")[0])!=null).count(),Phase8AssetCatalog.referencesForPaths(p).size());Set<String> ids=new HashSet<>();for(dev.chyken.assembl_ar.vision.ComponentReferenceSpec r:Phase8AssetCatalog.referencesForPaths(p)){assertTrue(ids.add(r.getReferenceId()));assertNotNull(Phase8AssetCatalog.referenceRole(r.getReferenceId()));assertFalse(r.getJpegAssetPath().contains("GUIDE"));}}
    @Test public void mislabeledPhotoIsExcludedByContentAndReplacementIsAllowed(){String p=Phase8AssetCatalog.ROOT+"/STEP8_2DISTANCESENSORHARDWARESCAN_INVALID/DISTANCESENSORWRONGORIENTATIONTOPVIEW (15).jpg";
        assertTrue(Phase8AssetCatalog.conflictingOrientationPhoto(p,"365e397da8047f3fcd4c0c01308a8fa97b231e623a5d301d152038cfa206cdce"));
        assertFalse(Phase8AssetCatalog.conflictingOrientationPhoto(p,"replacement"));
        assertTrue(Phase8AssetCatalog.inwardReference(p));assertFalse(Phase8AssetCatalog.inwardReference("IMG_20261019_213645_705@1941746869.jpg"));
    }
    @Test public void actualVideoAndGuideFilenameVariantsResolve()throws Exception{List<String> p=paths();assertEquals(1,Phase8AssetCatalog.videosForPaths(p,false).size());assertTrue(Phase8AssetCatalog.videosForPaths(p,false).get(0).endsWith("P8_DISTANCESENSORPLACEMENT.mp4"));assertEquals(1,Phase8AssetCatalog.videosForPaths(p,true).size());assertTrue(Phase8AssetCatalog.guideForPaths(p,true).contains("HARDWARE"));assertFalse(Phase8AssetCatalog.guideForPaths(p,false).contains("HARDWARE"));}
    @Test public void futureImagesRegisterOnceAndUnknownFoldersDoNot(){String photo=Phase8AssetCatalog.ROOT+"/STEP8_1SCANDISTANCESENSOR/new.JPG";assertEquals(1,Phase8AssetCatalog.referencesForPaths(Arrays.asList(photo,photo,Phase8AssetCatalog.ROOT+"/other/anything.png")).size());assertEquals("assembly",Phase8AssetCatalog.role("STEP8_2DISTANCESENSORHARDWARESCAN"));assertNull(Phase8AssetCatalog.referenceRole("p7scene_front_anything"));}
}
