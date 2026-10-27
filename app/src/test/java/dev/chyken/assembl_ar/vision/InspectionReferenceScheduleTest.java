package dev.chyken.assembl_ar.vision;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class InspectionReferenceScheduleTest {
    @Test public void largeFamiliesAreBoundedAndEveryPhotoIsEventuallyVisited(){
        List<Integer> all=new ArrayList<>();for(int i=0;i<101;i++)all.add(i);
        Set<Integer> visited=new HashSet<>();
        for(int frame=0;frame<13;frame++){
            List<Integer> selected=InspectionReferenceSchedule.window(all,99,frame);
            assertTrue(selected.size()<=9);assertEquals(new HashSet<>(selected).size(),selected.size());assertTrue(selected.contains(99));visited.addAll(selected);
        }
        assertEquals(new HashSet<>(all),visited);
    }
    @Test public void independentNegativeFamilyCannotBeStarvedByPositivePreference(){
        List<String> positive=Arrays.asList("front1","front2"),negative=Arrays.asList("back","missing");
        assertEquals(positive,InspectionReferenceSchedule.window(positive,"obsolete",Integer.MAX_VALUE));
        assertEquals(new HashSet<>(negative),new HashSet<>(InspectionReferenceSchedule.window(negative,null,Integer.MAX_VALUE)));
        assertTrue(InspectionReferenceSchedule.window(Collections.emptyList(),"old",0).isEmpty());
    }
}
