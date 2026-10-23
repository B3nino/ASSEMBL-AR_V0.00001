package dev.chyken.assembl_ar.vision;

import java.util.*;

/** Bounded standalone-camera work. All cached photos remain eligible over successive
 * scans; a last successful view is retained while the other views keep rotating. */
public final class IndividualReferenceSearchWindow {
    public static final int EXPECTED_VIEWS=6,OTHER_VIEWS=2;
    private long cursor;private String expected,lastGood;
    public Set<String> next(List<ComponentReferenceSpec> catalog,String component){
        if(!Objects.equals(expected,component)){expected=component;lastGood=null;cursor=0;}
        List<String> own=new ArrayList<>();Map<String,List<String>> other=new LinkedHashMap<>();
        for(ComponentReferenceSpec s:catalog){
            if(Objects.equals(component,s.getComponentId()))own.add(s.getReferenceId());
            else other.computeIfAbsent(s.getComponentId(),k->new ArrayList<>()).add(s.getReferenceId());
        }
        Set<String> chosen=new LinkedHashSet<>();if(lastGood!=null&&own.contains(lastGood))chosen.add(lastGood);
        int slots=EXPECTED_VIEWS-chosen.size();
        for(int i=0;i<own.size()&&chosen.size()<EXPECTED_VIEWS;i++)chosen.add(own.get((int)Math.floorMod(cursor*slots+i,own.size())));
        List<List<String>> groups=new ArrayList<>(other.values());
        for(int i=0;i<Math.min(OTHER_VIEWS,groups.size());i++){
            List<String> group=groups.get((int)Math.floorMod(cursor*OTHER_VIEWS+i,groups.size()));
            chosen.add(group.get((int)Math.floorMod(cursor/Math.max(1,groups.size()),group.size())));
        }
        cursor++;return chosen;
    }
    public void matched(String reference){lastGood=reference;}
}
