package dev.chyken.assembl_ar.vision;
import org.opencv.core.*;import org.opencv.features2d.BFMatcher;import java.util.*;
/** Transparent native matcher with a standardized diagnostic Lowe probe (.75, Hamming <=64).
 * Production callers retain their own filters. No additional matching pass is performed. */
final class DiagnosticHammingMatcher {
    private final BFMatcher nativeMatcher=BFMatcher.create(Core.NORM_HAMMING,false);
    private static final ThreadLocal<State> STATE=ThreadLocal.withInitial(State::new);
    static final class State {ComponentReferenceSpec current,best;int good=-1,total=-1;long started=android.os.SystemClock.elapsedRealtimeNanos(),featureNs,matchingNs,geometryNs;Mat live;KeyPoint[] liveKeys;boolean recovery;}
    static void recovery(boolean enabled){STATE.get().recovery=enabled;}
    static void begin(){end();STATE.set(new State());}
    static void end(){State s=STATE.get();if(s.live!=null)s.live.release();s.live=null;s.liveKeys=null;}
    static void captureLive(KeyPoint[] keys,Mat descriptors,double scale){State s=STATE.get();if(s.live!=null||descriptors.empty())return;s.live=descriptors.clone();s.liveKeys=keys;if(scale!=1){s.liveKeys=new KeyPoint[keys.length];for(int i=0;i<keys.length;i++){KeyPoint k=keys[i];s.liveKeys[i]=new KeyPoint((float)(k.pt.x/scale),(float)(k.pt.y/scale),k.size,k.angle,k.response,k.octave,k.class_id);}}}
    static void normalize(org.opencv.imgproc.CLAHE clahe,Mat src,Mat dst){long t=android.os.SystemClock.elapsedRealtimeNanos();Mat balanced=null;try{if(STATE.get().recovery){balanced=new Mat();IlluminationRecovery.balance(src,balanced);clahe.apply(balanced,dst);}else clahe.apply(src,dst);}finally{if(balanced!=null)balanced.release();STATE.get().featureNs+=android.os.SystemClock.elapsedRealtimeNanos()-t;}}
    static void extract(org.opencv.features2d.ORB orb,Mat src,Mat mask,MatOfKeyPoint keys,Mat descriptors){long t=android.os.SystemClock.elapsedRealtimeNanos();try{orb.detectAndCompute(src,mask,keys,descriptors);}finally{STATE.get().featureNs+=android.os.SystemClock.elapsedRealtimeNanos()-t;}}
    static Mat homography(MatOfPoint2f src,MatOfPoint2f dst,int method,double threshold,Mat mask,int iterations,double confidence){long t=android.os.SystemClock.elapsedRealtimeNanos();try{return org.opencv.calib3d.Calib3d.findHomography(src,dst,method,threshold,mask,iterations,confidence);}finally{STATE.get().geometryNs+=android.os.SystemClock.elapsedRealtimeNanos()-t;}}
    static Mat homography(MatOfPoint2f src,MatOfPoint2f dst,int method,double threshold,Mat mask){return homography(src,dst,method,threshold,mask,2000,.995);}
    static void context(ComponentReferenceSpec s){STATE.get().current=s;}
    static State snapshot(){return STATE.get();}
    static void record(ComponentReferenceSpec source,int good,int total){State state=STATE.get();if(good>state.good||good==state.good&&total>state.total){state.best=source;state.good=good;state.total=total;}}
    /** BFMatcher's native Hamming top-two operation, read as two flat matrices instead
     * of thousands of MatOfDMatch/DMatch wrappers. Production thresholds are unchanged. */
    List<DMatch> ratioMatches(Mat query,Mat train,double ratio,int maximumDistance){
        return ratioMatches(query,train,null,ratio,maximumDistance);
    }
    List<DMatch> ratioMatches(Mat query,Mat train,Mat mask,double ratio,int maximumDistance){
        State state=STATE.get();long started=android.os.SystemClock.elapsedRealtimeNanos();NearestDescriptorPairs pairs;
        try{pairs=NearestDescriptorPairs.compute(query,train,mask);}finally{state.matchingNs+=android.os.SystemClock.elapsedRealtimeNanos()-started;}
        List<DMatch> accepted=new ArrayList<>();int good=0,total=0;
        for(int i=0;i<pairs.size();i++)if(pairs.hasTwo(i)){
            total++;float best=pairs.bestDistance(i),second=pairs.secondDistance(i);
            if(best<=64&&best<.75*second)good++;
            if(best<=maximumDistance&&best<ratio*second)accepted.add(pairs.best(i));
        }
        if(state.current!=null)record(state.current,good,total);
        return accepted;
    }
    void knnMatch(Mat query,Mat train,List<MatOfDMatch> pairs,int k){
        State state=STATE.get();long started=android.os.SystemClock.elapsedRealtimeNanos();try{nativeMatcher.knnMatch(query,train,pairs,k);}finally{state.matchingNs+=android.os.SystemClock.elapsedRealtimeNanos()-started;}if(state.current==null)return;
        // MatOfDMatch stores CV_32FC4 rows: query, train, image, distance. Reuse
        // one buffer; constructing another DMatch[] for every queried row caused GC churn.
        int good=0,total=0;float[] p=new float[8];for(MatOfDMatch pair:pairs){if(pair.total()>=2){pair.get(0,0,p);total++;if(p[3]<=64&&p[3]<.75*p[7])good++;}}
        if(good>state.good||good==state.good&&total>state.total){state.best=state.current;state.good=good;state.total=total;}
    }
    void clear(){nativeMatcher.clear();}
}
