package dev.chyken.assembl_ar.vision;

import java.util.ArrayList;
import java.util.List;

/** Bounded scheduling only. Every orientation family is sampled independently;
 * rotation eventually visits every photo. No cached score is recognition evidence. */
final class InspectionReferenceSchedule {
    static final int PROBES_PER_FAMILY=8;
    private InspectionReferenceSchedule() { }
    static <T> List<T> window(List<T> family,T preferred,int frame) {
        List<T> result=new ArrayList<>(PROBES_PER_FAMILY+1);
        if(preferred!=null&&family.contains(preferred))result.add(preferred);
        if(family.isEmpty())return result;
        int count=Math.min(PROBES_PER_FAMILY,family.size());
        for(int i=0;i<count;i++) {
            int begin=(int)((long)i*family.size()/count),end=(int)((long)(i+1)*family.size()/count);
            // Spread the first window across the library's lighting/viewpoint groups.
            // Adjacent sequential video frames must not consume the whole first pass.
            T item=family.get(begin+Math.floorMod(frame,end-begin));
            if(!result.contains(item))result.add(item);
        }
        return result;
    }
}
