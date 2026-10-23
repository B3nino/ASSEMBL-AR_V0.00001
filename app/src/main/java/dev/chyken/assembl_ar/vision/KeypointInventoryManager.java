package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.UnifiedReferenceCatalog;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Exact prepared counts per source and photometric variant. Unknown is -1, never zero. */
public final class KeypointInventoryManager {
    public static final KeypointInventoryManager INSTANCE=new KeypointInventoryManager();
    public static final class Entry {
        public final String referenceId,componentId,path,displayName; public final int phase;
        public final Map<String,Integer> variants;
        Entry(ComponentReferenceSpec s,Map<String,Integer> counts){displayName=s.getDisplayName();referenceId=s.getReferenceId();componentId=s.getComponentId();path=s.getJpegAssetPath();phase=UnifiedReferenceCatalog.phase(s);variants=Collections.unmodifiableMap(counts);}
        public int primary(){Integer n=variants.get("NORMALIZED");return n!=null?n:variants.values().stream().findFirst().orElse(-1);}
    }
    public void register(List<ComponentReferenceSpec> specs){for(ComponentReferenceSpec s:specs)entries.putIfAbsent(s.getReferenceId(),new Entry(s,new LinkedHashMap<>()));}
    void skipped(ComponentReferenceSpec s){Map<String,Integer> count=new LinkedHashMap<>();count.put("UNUSABLE",-1);entries.put(s.getReferenceId(),new Entry(s,count));}
    private final ConcurrentHashMap<String,Entry> entries=new ConcurrentHashMap<>();
    void prepared(ReferenceDatabase.PreparedReference r){
        Map<String,Integer> counts=new LinkedHashMap<>();
        for(ReferenceDatabase.PreparedOrbVariant v:r.orbVariants)counts.put(v.photometricVariant.name(),v.keypoints.length);
        entries.put(r.spec.getReferenceId(),new Entry(r.spec,counts));
    }
    public String name(String referenceId){Entry e=referenceId==null?null:entries.get(referenceId);return e==null?null:e.displayName;}
    public String status(String referenceId){Entry e=entries.get(referenceId);return e==null||e.variants.isEmpty()?"preparing":e.variants.containsKey("UNUSABLE")?"unusable source":e.primary()+" kp";}
    public int count(String referenceId){Entry e=referenceId==null?null:entries.get(referenceId);return e==null?-1:e.primary();}
    public int preparedCount(){int n=0;for(Entry e:entries.values())if(!e.variants.isEmpty()&&!e.variants.containsKey("UNUSABLE"))n++;return n;}
    public List<Entry> snapshot(){List<Entry> list=new ArrayList<>(entries.values());list.sort(Comparator.comparing(e->e.referenceId));return Collections.unmodifiableList(list);}
    public String componentSummary(String component){int refs=0,points=0;for(Entry e:entries.values())if(e.componentId.equals(component)){refs++;points+=Math.max(0,e.primary());}return refs+" refs · "+points+" assigned keypoints";}
    public String csv(){StringBuilder b=new StringBuilder("reference,component,phase,variant,keypoints\n");for(Entry e:snapshot())for(Map.Entry<String,Integer> v:e.variants.entrySet())b.append(quote(e.referenceId)).append(',').append(quote(e.componentId)).append(',').append(e.phase).append(',').append(v.getKey()).append(',').append(v.getValue()).append('\n');return b.toString();}
    private static String quote(String s){return "\""+s.replace("\"","\"\"")+"\"";}
}
