package dev.chyken.assembl_ar.vision;

import java.util.*;
import org.opencv.core.Rect;

/** One actual model prediction. Confidence is never an ORB match count or pose. */
final class TrainedDetection {
    final String component,label;
    final Rect bounds;
    final double confidence,margin;
    final int confirmations;
    TrainedDetection(String component,String label,Rect bounds,double confidence,double margin,int confirmations){
        this.component=component;this.label=label;this.bounds=new Rect(bounds.x,bounds.y,bounds.width,bounds.height);
        this.confidence=confidence;this.margin=margin;this.confirmations=confirmations;
    }
    boolean qualified(int width,int height){
        double area=bounds.area()/(width*(double)height);
        return Double.isFinite(confidence)&&confidence>=.85&&confidence<=1&&margin>=.15&&confirmations>=2
                &&bounds.width>=10&&bounds.height>=10&&bounds.x>1&&bounds.y>1
                &&bounds.x+bounds.width<width-1&&bounds.y+bounds.height<height-1&&area>=.001&&area<=.80;
    }
    List<VisionPoint> corners(){return Arrays.asList(new VisionPoint(bounds.x,bounds.y),new VisionPoint(bounds.x+bounds.width,bounds.y),
            new VisionPoint(bounds.x+bounds.width,bounds.y+bounds.height),new VisionPoint(bounds.x,bounds.y+bounds.height));}
    /** Compare local structure after removing a uniform brightness offset. */
    static boolean sameAppearance(byte[] source,byte[] current,int width,int height,Rect box,int imageWidth,int imageHeight){
        int l=Math.max(0,box.x*width/imageWidth),t=Math.max(0,box.y*height/imageHeight);
        int r=Math.min(width,(box.x+box.width)*width/imageWidth),b=Math.min(height,(box.y+box.height)*height/imageHeight);
        int n=(r-l)*(b-t);if(n<16||source.length!=width*height||current.length!=source.length)return false;
        double a=0,c=0,aa=0,cc=0,ac=0;
        for(int y=t;y<b;y++)for(int x=l;x<r;x++){int i=y*width+x;double av=source[i]&255,cv=current[i]&255;a+=av;c+=cv;aa+=av*av;cc+=cv*cv;ac+=av*cv;}
        double va=aa-a*a/n,vc=cc-c*c/n,cov=ac-a*c/n;
        return va/n>=36&&vc/n>=36&&Math.abs(a-c)/n<=45&&vc/va>=.36&&vc/va<=2.72&&cov/Math.sqrt(va*vc)>=.90;
    }
    static List<TrainedDetection> confirm(List<TrainedDetection> current,List<TrainedDetection> previous,long source,long prior){
        boolean independent=source>prior&&source-prior>=150_000_000L&&source-prior<=2_500_000_000L;
        Set<TrainedDetection> used=Collections.newSetFromMap(new IdentityHashMap<>());List<TrainedDetection> result=new ArrayList<>();
        for(TrainedDetection d:current){
            TrainedDetection best=null;double overlap=.55;
            if(independent&&d.confidence>=.85&&d.margin>=.15)for(TrainedDetection p:previous){
                double iou=DnnRoiDecoder.overlap(d.bounds,p.bounds);
                if(!used.contains(p)&&d.label.equals(p.label)&&p.confidence>=.85&&p.margin>=.15&&iou>overlap){best=p;overlap=iou;}
            }
            if(best!=null)used.add(best);
            result.add(new TrainedDetection(d.component,d.label,d.bounds,d.confidence,d.margin,best==null?1:Math.min(3,best.confirmations+1)));
        }
        return result;
    }
}
