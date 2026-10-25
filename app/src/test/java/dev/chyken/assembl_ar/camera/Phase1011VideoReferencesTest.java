package dev.chyken.assembl_ar.camera;
import dev.chyken.assembl_ar.vision.*;
import java.io.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class Phase1011VideoReferencesTest {
    @Test public void targetedHardwareMasksRetainIndependentMaterialAndSceneEvidence(){int screws=0,headers=0;
        for(ComponentReferenceSpec s:Phase1011VideoReferences.all()){Phase1011VideoReferences.Entry e=Phase1011VideoReferences.annotation(s.getJpegAssetPath());if(e.anchors.isEmpty()||s.getJpegAssetPath().contains("2ubF9XWd")||s.getJpegAssetPath().contains("y5NK9Z5i"))continue;
            assertFalse(s.getJpegAssetPath().contains("_159_"));assertFalse(s.getJpegAssetPath().contains("_190_"));assertFalse(s.getJpegAssetPath().contains("_83_"));assertFalse(e.context.isEmpty());
            if(e.role.equals("protected")){screws++;assertEquals(2,e.anchors.size());assertEquals(3,e.identity.size());assertTrue(e.anchors.get(0).get(2).getX()<e.anchors.get(1).get(0).getX());}
            else{headers++;assertEquals("assembly",e.role);assertEquals(4,e.anchors.size());assertTrue(e.anchors.get(1).get(2).getX()<e.anchors.get(2).get(0).getX());}
        }assertEquals(3,screws);assertEquals(2,headers);
    }
    @Test public void reviewedPixelsHaveValidMasksAndCannotPointAtDownloads(){Set<String> paths=new HashSet<>();int protectedBoards=0,oleds=0;
        for(ComponentReferenceSpec s:Phase1011VideoReferences.all()){assertTrue(paths.add(s.getJpegAssetPath()));assertTrue(new File("src/main/assets",s.getJpegAssetPath()).isFile());assertFalse(s.getJpegAssetPath().contains("Downloads"));Phase1011VideoReferences.Entry e=Phase1011VideoReferences.annotation(s.getJpegAssetPath());List<List<VisionPoint>> shapes=new ArrayList<>(e.identity);shapes.addAll(e.context);shapes.addAll(e.anchors);shapes.addAll(e.regions);shapes.add(e.body);for(List<VisionPoint> p:shapes){assertEquals(4,p.size());for(VisionPoint v:p)assertTrue(v.getX()>=0&&v.getX()<=1&&v.getY()>=0&&v.getY()<=1);}if(e.role.equals("protected")){protectedBoards++;assertEquals(2,e.regions.size());assertEquals(3,e.identity.size());assertFalse(e.context.isEmpty());}if(e.role.equals("assembly")){oleds++;assertFalse(e.context.isEmpty());}}
        assertEquals(52,paths.size());assertEquals(12,protectedBoards);assertEquals(6,oleds);
    }
    @Test public void aliasesAndNestedLightingRegistrationPreserveAuthoredGeometry(){List<String> p10=new ArrayList<>(),p11=new ArrayList<>();for(ComponentReferenceSpec s:Phase1011VideoReferences.all())(s.getAssemblyStepNumber()==10?p10:p11).add(s.getJpegAssetPath());List<ComponentReferenceSpec> all=new ArrayList<>(Phase10AssetCatalog.referencesForPaths(p10));all.addAll(Phase11AssetCatalog.referencesForPaths(p11));assertEquals(52,all.size());for(ComponentReferenceSpec s:all)assertSame(Phase1011VideoReferences.find(s.getJpegAssetPath()),s);assertEquals("protector",Phase10AssetCatalog.role("ISTEP10_1OLEDPROTECTORSCAN"));assertEquals("extensionbad",Phase10AssetCatalog.role("STEP10_1EXTENSIONBOARDSCAN _INVALID"));}
    @Test public void individualCopiesReusePixelsWithoutAssemblyGatesOrNegativeLabels(){assertEquals(23,Phase1011VideoReferences.individual().size());for(ComponentReferenceSpec s:Phase1011VideoReferences.individual()){assertEquals(0,s.getAssemblyStepNumber());assertFalse(UnifiedReferenceCatalog.assembly(s));assertFalse(UnifiedReferenceCatalog.negative(s));RecognitionRequest r=UnifiedReferenceCatalog.request(s,null);assertEquals(0,r.getPhase10Stage());assertEquals(0,r.getPhase11Stage());}}
}
