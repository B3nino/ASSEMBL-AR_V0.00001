package dev.chyken.assembl_ar.vision;
import org.opencv.core.Rect;

/** Coordinate decoding only. Neural boxes never constitute assembly evidence. */
final class DnnRoiDecoder {
    static final class Letterbox {
        final int width,height,resizedWidth,resizedHeight,left,top,edge;
        final double scaleX,scaleY;
        Letterbox(int width,int height,int edge){
            if(width<=0||height<=0||edge<=0)throw new IllegalArgumentException("Positive image dimensions required");
            this.width=width;this.height=height;this.edge=edge;
            double scale=Math.min(edge/(double)width,edge/(double)height);
            resizedWidth=Math.max(1,(int)Math.round(width*scale));resizedHeight=Math.max(1,(int)Math.round(height*scale));
            left=(edge-resizedWidth)/2;top=(edge-resizedHeight)/2;
            scaleX=resizedWidth/(double)width;scaleY=resizedHeight/(double)height;
        }
        Rect bounds(double cx,double cy,double w,double h){
            if(!Double.isFinite(cx+cy+w+h)||w<=0||h<=0)return null;
            double l=(cx-w/2-left)/scaleX,t=(cy-h/2-top)/scaleY,r=(cx+w/2-left)/scaleX,b=(cy+h/2-top)/scaleY;
            if(r<=0||b<=0||l>=width||t>=height)return null;
            int x=Math.max(0,(int)Math.floor(l)),y=Math.max(0,(int)Math.floor(t));
            int right=Math.min(width,(int)Math.ceil(r)),bottom=Math.min(height,(int)Math.ceil(b));
            return right-x>=3&&bottom-y>=3?new Rect(x,y,right-x,bottom-y):null;
        }
    }
    static final class Box {final Rect bounds;final float confidence;Box(Rect bounds,float score){this.bounds=bounds;confidence=score;}}
    static Box yolo(float[] output,int channels,int count,int label,double threshold,Letterbox transform){
        if(channels<5||label<0||label>=channels-4||count<=0||output.length<channels*count)throw new IllegalArgumentException("Invalid YOLO output contract");
        Box best=null;double score=threshold;
        for(int i=0;i<count;i++){
            float confidence=output[(4+label)*count+i];
            if(!Float.isFinite(confidence)||confidence<score||confidence>1)continue;
            Rect box=transform.bounds(output[i],output[count+i],output[2*count+i],output[3*count+i]);
            if(box!=null){best=new Box(box,confidence);score=confidence;}
        }
        return best;
    }
    static Rect searchArea(Rect anchor,int width,int height,boolean chip){
        if(anchor==null)return null;
        // ATmega package detection proposes a broad board search, never a PCB outline.
        double padding=chip?Math.max(anchor.width,anchor.height)*3.5:Math.max(8,Math.min(anchor.width,anchor.height)*.1);
        int l=Math.max(0,(int)Math.floor(anchor.x-padding)),t=Math.max(0,(int)Math.floor(anchor.y-padding));
        int r=Math.min(width,(int)Math.ceil(anchor.x+anchor.width+padding)),b=Math.min(height,(int)Math.ceil(anchor.y+anchor.height+padding));
        return r>l&&b>t?new Rect(l,t,r-l,b-t):null;
    }
    private DnnRoiDecoder() { }
}
