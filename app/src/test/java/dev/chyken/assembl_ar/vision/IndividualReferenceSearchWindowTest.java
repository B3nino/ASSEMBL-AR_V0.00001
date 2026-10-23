package dev.chyken.assembl_ar.vision;
import java.util.*;import org.junit.Test;import static org.junit.Assert.*;
public final class IndividualReferenceSearchWindowTest {
    private List<ComponentReferenceSpec> catalog(){List<ComponentReferenceSpec> out=new ArrayList<>();for(int i=0;i<17;i++)out.add(photo("target-"+i,"target"));for(int c=0;c<7;c++)for(int i=0;i<5;i++)out.add(photo("other-"+c+"-"+i,"other-"+c));return out;}
    private ComponentReferenceSpec photo(String id,String part){return ComponentReferenceSpec.builder(id,part,part,20,20).jpegAssetPath("references/"+id+".png").build();}
    @Test public void boundedBatchesEventuallyCoverEveryCachedViewAndCompetingIdentity(){
        List<ComponentReferenceSpec> all=catalog();Set<String> seen=new HashSet<>();IndividualReferenceSearchWindow window=new IndividualReferenceSearchWindow();
        for(int n=0;n<100;n++){Set<String> scan=window.next(all,"target");assertTrue(scan.size()<=8);assertEquals(6,scan.stream().filter(id->id.startsWith("target-")).count());seen.addAll(scan);}
        for(ComponentReferenceSpec photo:all)assertTrue("Never starve a cached photo: "+photo.getReferenceId(),seen.contains(photo.getReferenceId()));
    }
    @Test public void retainedGoodViewDoesNotStarveOtherViewsAndSelectionChangeClearsIt(){
        List<ComponentReferenceSpec> all=catalog();IndividualReferenceSearchWindow window=new IndividualReferenceSearchWindow();window.next(all,"target");window.matched("target-16");Set<String> seen=new HashSet<>();
        for(int n=0;n<50;n++){Set<String> scan=window.next(all,"target");assertTrue(scan.contains("target-16"));seen.addAll(scan);}
        for(int n=0;n<17;n++)assertTrue(seen.contains("target-"+n));
        assertEquals(5,window.next(all,"other-3").stream().filter(id->id.startsWith("other-3-")).count());
    }
}
