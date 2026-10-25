package dev.chyken.assembl_ar.vision;

import java.util.*;
import org.opencv.core.*;

/** Cached, independently owned descriptor rows for body identity and board context. */
final class PhotoFeaturePartition implements AutoCloseable {
    final Rows body,context;
    final Mat probe;
    PhotoFeaturePartition(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant v){
        double[] b=LocalPhotometricRefiner.bounds(ref.spec,ref.imageWidth,ref.imageHeight);double w=b[2]-b[0],h=b[3]-b[1];
        dev.chyken.assembl_ar.camera.Phase35VideoReferences.Entry authored=dev.chyken.assembl_ar.camera.Phase35VideoReferences.annotation(ref.spec.getJpegAssetPath());
        List<Integer> own=new ArrayList<>(),other=new ArrayList<>();
        for(int i=0;i<v.keypoints.length;i++){Point p=v.keypoints[i].pt;
            if(authored!=null){
                double x=p.x/ref.imageWidth,y=p.y/ref.imageHeight;
                if(Phase3ReferenceAnnotations.contains(authored.regions.get(0),x,y))own.add(i);
                else if(!Phase3ReferenceAnnotations.contains(authored.body,x,y))for(int n=1;n<authored.regions.size();n++)if(Phase3ReferenceAnnotations.contains(authored.regions.get(n),x,y)){other.add(i);break;}
                continue;
            }
            if(p.x>=b[0]&&p.x<=b[2]&&p.y>=b[1]&&p.y<=b[3])own.add(i);
            else if(p.x<b[0]-.15*w||p.x>b[2]+.15*w||p.y<b[1]-.15*h||p.y>b[3]+.15*h)other.add(i);
        }
        Rows loadedBody=null,loadedContext=null;Mat loadedProbe=new Mat();
        try{
            loadedBody=new Rows(v,own);loadedContext=new Rows(v,other);int count=Math.min(32,own.size());
            if(count>0){loadedProbe.create(count,32,CvType.CV_8UC1);byte[] all=new byte[own.size()*32],sample=new byte[count*32];loadedBody.rows.get(0,0,all);for(int i=0;i<count;i++)System.arraycopy(all,(i*own.size()/count)*32,sample,i*32,32);loadedProbe.put(0,0,sample);}
            body=loadedBody;context=loadedContext;probe=loadedProbe;
        }catch(RuntimeException|Error failure){if(loadedBody!=null)loadedBody.rows.release();if(loadedContext!=null)loadedContext.rows.release();loadedProbe.release();throw failure;}

    }
    static final class Rows {
        final KeyPoint[] keys;final Mat rows;
        Rows(ReferenceDatabase.PreparedOrbVariant v,List<Integer> chosen){
            keys=new KeyPoint[chosen.size()];rows=chosen.isEmpty()?new Mat():new Mat(chosen.size(),32,CvType.CV_8UC1);if(chosen.isEmpty())return;
            try{
            byte[] all=new byte[v.descriptors.rows()*32],selected=new byte[chosen.size()*32];v.descriptors.get(0,0,all);
            for(int i=0;i<chosen.size();i++){keys[i]=v.keypoints[chosen.get(i)];System.arraycopy(all,chosen.get(i)*32,selected,i*32,32);}rows.put(0,0,selected);
            }catch(RuntimeException|Error failure){rows.release();throw failure;}
        }
    }
    @Override public void close(){body.rows.release();context.rows.release();probe.release();}
}
