package dev.chyken.assembl_ar.vision;

import java.util.*;
import org.opencv.core.*;

/** Cached, independently owned descriptor rows for body identity and board context. */
final class PhotoFeaturePartition implements AutoCloseable {
    final Rows body,context;
    final Mat probe;
    PhotoFeaturePartition(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant v){
        double[] b=LocalPhotometricRefiner.bounds(ref.spec,ref.imageWidth,ref.imageHeight);double w=b[2]-b[0],h=b[3]-b[1];
        List<Integer> own=new ArrayList<>(),other=new ArrayList<>();
        for(int i=0;i<v.keypoints.length;i++){Point p=v.keypoints[i].pt;
            if(p.x>=b[0]&&p.x<=b[2]&&p.y>=b[1]&&p.y<=b[3])own.add(i);
            else if(p.x<b[0]-.15*w||p.x>b[2]+.15*w||p.y<b[1]-.15*h||p.y>b[3]+.15*h)other.add(i);
        }
        body=new Rows(v,own);context=new Rows(v,other);int count=Math.min(32,own.size());
        probe=count==0?new Mat():new Mat(count,32,CvType.CV_8UC1);
        if(count>0){byte[] all=new byte[own.size()*32],sample=new byte[count*32];body.rows.get(0,0,all);for(int i=0;i<count;i++)System.arraycopy(all,(i*own.size()/count)*32,sample,i*32,32);probe.put(0,0,sample);}
    }
    static final class Rows {
        final KeyPoint[] keys;final Mat rows;
        Rows(ReferenceDatabase.PreparedOrbVariant v,List<Integer> chosen){
            keys=new KeyPoint[chosen.size()];rows=chosen.isEmpty()?new Mat():new Mat(chosen.size(),32,CvType.CV_8UC1);if(chosen.isEmpty())return;
            byte[] all=new byte[v.descriptors.rows()*32],selected=new byte[chosen.size()*32];v.descriptors.get(0,0,all);
            for(int i=0;i<chosen.size();i++){keys[i]=v.keypoints[chosen.get(i)];System.arraycopy(all,chosen.get(i)*32,selected,i*32,32);}rows.put(0,0,selected);
        }
    }
    @Override public void close(){body.rows.release();context.rows.release();probe.release();}
}
