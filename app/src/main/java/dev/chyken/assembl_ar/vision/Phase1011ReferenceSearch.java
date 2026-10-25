package dev.chyken.assembl_ar.vision;
import dev.chyken.assembl_ar.camera.Phase1011VideoReferences;
import java.util.*;
import org.opencv.core.*;

/** Small rotating/ranked scheduling windows. Sparse scores never verify a part. */
final class Phase1011ReferenceSearch implements AutoCloseable {
    private final Map<ReferenceDatabase.PreparedOrbVariant,Mat> probes=new IdentityHashMap<>();
    List<ReferenceDatabase.PreparedReference> order(List<ReferenceDatabase.PreparedReference> refs,Mat live,int cursor,ReferenceDatabase.PreparedReference preferred){
        LinkedHashSet<ReferenceDatabase.PreparedReference> window=new LinkedHashSet<>();if(refs.contains(preferred))window.add(preferred);
        List<ReferenceDatabase.PreparedReference> legacy=new ArrayList<>();for(ReferenceDatabase.PreparedReference ref:refs){if(Phase1011VideoReferences.annotation(ref.spec.getJpegAssetPath())!=null)window.add(ref);else legacy.add(ref);}
        for(int i=0;i<Math.min(4,legacy.size());i++)window.add(legacy.get(Math.floorMod(cursor*4+i,legacy.size())));
        List<ReferenceDatabase.PreparedReference> result=new ArrayList<>(window);Map<ReferenceDatabase.PreparedReference,Integer> scores=new IdentityHashMap<>();long start=android.os.SystemClock.elapsedRealtimeNanos();
        try{for(ReferenceDatabase.PreparedReference ref:result){int score=0;for(ReferenceDatabase.PreparedOrbVariant v:ref.orbVariants){if(!v.hasOrbData())continue;Mat sample=probes.computeIfAbsent(v,key->sample(v.descriptors));if(live.rows()>1){NearestDescriptorPairs p=NearestDescriptorPairs.compute(sample,live);for(int i=0;i<p.size();i++)if(p.hasTwo(i)&&p.bestDistance(i)<=64&&p.bestDistance(i)<.80*p.secondDistance(i))score++;}break;}scores.put(ref,score);}}
        finally{DiagnosticHammingMatcher.snapshot().matchingNs+=android.os.SystemClock.elapsedRealtimeNanos()-start;}
        result.sort((a,b)->Integer.compare(scores.get(b),scores.get(a)));boolean retained=result.remove(preferred);if(retained)result.add(0,preferred);
        // A bounded frame often visits only the first candidate in each family.
        // Exploration in slot three can therefore starve forever. Alternate the
        // first slot while searching; a verified view remains first when locked.
        if(!retained&&!window.isEmpty()&&(cursor&1)==1){ReferenceDatabase.PreparedReference rotating=new ArrayList<>(window).get(Math.floorMod(cursor/2,window.size()));result.remove(rotating);result.add(0,rotating);}
        return result.subList(0,Math.min(4,result.size()));
    }
    private static Mat sample(Mat rows){int n=Math.min(32,rows.rows()),stride=rows.cols();Mat result=new Mat(n,stride,rows.type());byte[] all=new byte[rows.rows()*stride],selected=new byte[n*stride];rows.get(0,0,all);for(int i=0;i<n;i++)System.arraycopy(all,(i*rows.rows()/n)*stride,selected,i*stride,stride);result.put(0,0,selected);return result;}
    public void close(){for(Mat p:probes.values())p.release();probes.clear();}
}
