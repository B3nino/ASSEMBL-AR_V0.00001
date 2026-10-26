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
        try{for(ReferenceDatabase.PreparedReference ref:result){int score=0;for(ReferenceDatabase.PreparedOrbVariant v:ref.recoveryVariants()){if(!v.hasOrbData())continue;Mat sample=probes.computeIfAbsent(v,key->sample(ref,v));if(live.rows()>1){NearestDescriptorPairs p=NearestDescriptorPairs.compute(sample,live);for(int i=0;i<p.size();i++)if(p.hasTwo(i)&&OrbMatchPolicy.accepts(p.bestDistance(i),p.secondDistance(i)))score++;}break;}scores.put(ref,score);}}
        finally{DiagnosticHammingMatcher.snapshot().matchingNs+=android.os.SystemClock.elapsedRealtimeNanos()-start;}
        result.sort((a,b)->Integer.compare(scores.get(b),scores.get(a)));boolean retained=result.remove(preferred);if(retained)result.add(0,preferred);
        // A bounded frame often visits only the first candidate in each family.
        // Exploration in slot three can therefore starve forever. Alternate the
        // first slot while searching; a verified view remains first when locked.
        if(!retained&&!window.isEmpty()&&(cursor&1)==1){ReferenceDatabase.PreparedReference rotating=new ArrayList<>(window).get(Math.floorMod(cursor/2,window.size()));result.remove(rotating);result.add(0,rotating);}
        return result.subList(0,Math.min(DiagnosticHammingMatcher.snapshot().fastLive?2:4,result.size()));
    }
    private static Mat sample(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant v){
        Mat rows=v.descriptors;int n=Math.min(32,rows.rows()),stride=rows.cols();LinkedHashSet<Integer> chosen=new LinkedHashSet<>();
        Phase1011VideoReferences.Entry e=Phase1011VideoReferences.annotation(ref.spec.getJpegAssetPath());
        // Half of this scheduling-only budget covers authored physical anchors;
        // half retains scene coverage. No duplicate rows or synthetic score boost.
        if(e!=null&&!e.anchors.isEmpty())for(List<VisionPoint> anchor:e.anchors){List<Integer> local=new ArrayList<>();for(int i=0;i<v.keypoints.length;i++){Point p=v.keypoints[i].pt;double x=p.x/ref.imageWidth,y=p.y/ref.imageHeight;if(x>=anchor.get(0).getX()&&x<=anchor.get(2).getX()&&y>=anchor.get(0).getY()&&y<=anchor.get(2).getY())local.add(i);}int count=Math.min(local.size(),Math.min(n/2,16)/e.anchors.size());for(int i=0;i<count;i++)chosen.add(local.get(i*local.size()/count));}
        for(int i=0;i<n&&chosen.size()<n;i++)chosen.add(i*rows.rows()/n);
        for(int i=0;i<rows.rows()&&chosen.size()<n;i++)chosen.add(i);
        Mat result=new Mat(n,stride,rows.type());try{byte[] all=new byte[rows.rows()*stride],selected=new byte[n*stride];rows.get(0,0,all);int i=0;for(int index:chosen)System.arraycopy(all,index*stride,selected,i++*stride,stride);result.put(0,0,selected);return result;}catch(RuntimeException|Error failure){result.release();throw failure;}
    }
    public void close(){for(Mat p:probes.values())p.release();probes.clear();}
}
