package dev.chyken.assembl_ar.vision;

import java.util.*;
import org.opencv.core.*;

/** Bounded guided Hamming search, not acceptance evidence. A separately measured
 * board pose or strong RANSAC seed constrains ambiguous repeated metal/spokes;
 * fresh Lowe matches, unique live points, coverage and RANSAC remain mandatory. */
final class GeometricMatchDomain {
    static Mat mask(KeyPoint[] source,KeyPoint[] target,Mat h,double radius){
        if(source.length==0||target.length==0||(long)source.length*target.length>8_000_000L)return new Mat();
        long started=android.os.SystemClock.elapsedRealtimeNanos();MatOfPoint2f a=new MatOfPoint2f(),b=new MatOfPoint2f();
        try{
            Point[] p=new Point[source.length];for(int i=0;i<p.length;i++)p[i]=source[i].pt;a.fromArray(p);Core.perspectiveTransform(a,b,h);Point[] expected=b.toArray();
            Map<Long,List<Integer>> bins=new HashMap<>();for(int i=0;i<target.length;i++){Point q=target[i].pt;bins.computeIfAbsent(key((int)Math.floor(q.x/radius),(int)Math.floor(q.y/radius)),k->new ArrayList<>()).add(i);}
            byte[] data=new byte[source.length*target.length];double r2=radius*radius;
            for(int i=0;i<expected.length;i++){Point q=expected[i];if(!Double.isFinite(q.x+q.y))continue;int x=(int)Math.floor(q.x/radius),y=(int)Math.floor(q.y/radius);
                for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){List<Integer> indexes=bins.get(key(x+dx,y+dy));if(indexes==null)continue;for(int j:indexes){Point v=target[j].pt;if((v.x-q.x)*(v.x-q.x)+(v.y-q.y)*(v.y-q.y)<=r2)data[i*target.length+j]=(byte)255;}}
            }
            Mat result=new Mat(source.length,target.length,CvType.CV_8UC1);result.put(0,0,data);return result;
        }finally{a.release();b.release();DiagnosticHammingMatcher.snapshot().geometryNs+=android.os.SystemClock.elapsedRealtimeNanos()-started;}
    }
    private static long key(int x,int y){return ((long)x<<32)^(y&0xffffffffL);}
}
