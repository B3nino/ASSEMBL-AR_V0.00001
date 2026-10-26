package dev.chyken.assembl_ar.vision;

import java.util.*;
import org.opencv.core.*;
import dev.chyken.assembl_ar.camera.UnifiedReferenceCatalog;

/** Tiny, prebuilt local scheduling probes. Scores never become recognition evidence.
 * Retains positive and rejection families; full geometry still verifies selected photos. */
final class ScopedReferenceSelector implements AutoCloseable {
    private final Map<ReferenceDatabase.PreparedOrbVariant,Mat> probes=new IdentityHashMap<>();
    private int cursor;
    void prepare(List<ReferenceDatabase.PreparedReference> refs){
        for(ReferenceDatabase.PreparedReference ref:refs)for(ReferenceDatabase.PreparedOrbVariant v:ref.allVariants){
            if(!v.hasOrbData()||probes.containsKey(v))continue;
            int n=Math.min(24,v.descriptors.rows()),stride=v.descriptors.cols();
            byte[] row=new byte[stride],selected=new byte[n*stride];
            for(int i=0;i<n;i++){v.descriptors.get(i*v.descriptors.rows()/n,0,row);System.arraycopy(row,0,selected,i*stride,stride);}
            Mat sample=new Mat(n,stride,v.descriptors.type());try{sample.put(0,0,selected);probes.put(v,sample);}catch(RuntimeException|Error failure){sample.release();throw failure;}
        }
    }
    Set<String> select(List<ReferenceDatabase.PreparedReference> refs,Mat live,RecognitionRequest request,boolean structural){
        Map<String,List<Scored>> families=new LinkedHashMap<>();
        for(ReferenceDatabase.PreparedReference ref:refs){
            if(!LiveVisionPolicy.acceptsIdentity(ref.spec,request)||!ComponentRecognizer.isReferenceEligibleForRequest(ref.spec,request))continue;
            int score=0;
            for(ReferenceDatabase.PreparedOrbVariant v:ref.variantsFor(structural?ReferenceDatabase.PhotometricVariant.EDGE:ReferenceDatabase.PhotometricVariant.NORMALIZED)){
                if(!structural&&v.photometricVariant!=ReferenceDatabase.PhotometricVariant.NORMALIZED)continue;
                Mat probe=probes.get(v);if(probe==null||live.rows()<2)continue;
                NearestDescriptorPairs pairs=NearestDescriptorPairs.compute(probe,live);
                for(int i=0;i<pairs.size();i++)if(pairs.hasTwo(i)&&OrbMatchPolicy.accepts(pairs.bestDistance(i),pairs.secondDistance(i)))score++;
            }
            String group=ref.spec.getComponentId()+":"+UnifiedReferenceCatalog.negative(ref.spec);
            families.computeIfAbsent(group,k->new ArrayList<>()).add(new Scored(ref.spec.getReferenceId(),score));
        }
        Set<String> chosen=new LinkedHashSet<>();
        for(List<Scored> group:families.values()){
            group.sort((a,b)->Integer.compare(b.score,a.score));
            if(group.isEmpty())continue;chosen.add(group.get(0).id);
            // One alternate keeps weak probes from permanently hiding an unusual viewpoint.
            if(group.size()>1)chosen.add(group.get(1+Math.floorMod(cursor,group.size()-1)).id);
        }
        cursor++;return chosen;
    }
    private static final class Scored{final String id;final int score;Scored(String i,int s){id=i;score=s;}}
    @Override public void close(){for(Mat p:probes.values())p.release();probes.clear();cursor=0;}
}
