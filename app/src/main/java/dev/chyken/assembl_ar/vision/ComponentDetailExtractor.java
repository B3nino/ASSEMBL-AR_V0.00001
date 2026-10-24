package dev.chyken.assembl_ar.vision;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import org.opencv.core.*;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.*;

/** Short-lived local sampling. A board pose proposes a crop; fresh component ORB
 * correspondences still have to establish identity and orientation independently. */
final class ComponentDetailExtractor implements AutoCloseable {
    private final ORB orb=ORB.create(1200,1.2f,8,12,0,2,ORB.HARRIS_SCORE,31,7);
    private final CLAHE clahe;
    ComponentDetailExtractor(VisionConfig config){clahe=OpenCvVisionUtils.createClahe(config);}
    /** Brightness-relative proposals for black connectors/acrylic. Never identity evidence. */
    static List<List<VisionPoint>> proposals(Mat gray){
        Mat mask=new Mat(),hierarchy=new Mat(),kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(3,3));
        List<MatOfPoint> contours=new ArrayList<>();List<Rect> boxes=new ArrayList<>();
        try{
            Imgproc.threshold(gray,mask,0,255,Imgproc.THRESH_BINARY_INV|Imgproc.THRESH_OTSU);
            Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_OPEN,kernel);
            Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
            Rect union=null;
            for(MatOfPoint c:contours){Rect b=Imgproc.boundingRect(c);
                if(b.area()<gray.total()*.001||b.area()>gray.total()*.65||b.width<5||b.height<5)continue;
                boxes.add(b);if(union==null)union=new Rect(b.x,b.y,b.width,b.height);else{int r=Math.max(union.x+union.width,b.x+b.width),bottom=Math.max(union.y+union.height,b.y+b.height);union.x=Math.min(union.x,b.x);union.y=Math.min(union.y,b.y);union.width=r-union.x;union.height=bottom-union.y;}
            }
            boxes.sort((a,b)->Double.compare(b.area(),a.area()));if(union!=null)boxes.add(0,union);
            List<List<VisionPoint>> result=new ArrayList<>();
            for(int i=0;i<Math.min(3,boxes.size());i++){Rect b=boxes.get(i);result.add(Arrays.asList(new VisionPoint(b.x,b.y),new VisionPoint(b.x+b.width,b.y),new VisionPoint(b.x+b.width,b.y+b.height),new VisionPoint(b.x,b.y+b.height)));}
            return result;
        }finally{for(Mat c:contours)c.release();mask.release();hierarchy.release();kernel.release();}
    }
    Sample extract(Mat gray,List<VisionPoint> quad,int extent){
        if(quad.size()!=4)return null;
        double l=Double.MAX_VALUE,t=l,r=-l,b=-l;
        for(VisionPoint p:quad){l=Math.min(l,p.getX());r=Math.max(r,p.getX());t=Math.min(t,p.getY());b=Math.max(b,p.getY());}
        if(!Double.isFinite(l+t+r+b)||r<=l||b<=t)return null;
        int x=Math.max(0,(int)Math.floor(l-(r-l)*.30)),y=Math.max(0,(int)Math.floor(t-(b-t)*.30));
        int right=Math.min(gray.cols(),(int)Math.ceil(r+(r-l)*.30)),bottom=Math.min(gray.rows(),(int)Math.ceil(b+(b-t)*.30));
        if(right-x<16||bottom-y<16)return null;
        Mat crop=gray.submat(y,bottom,x,right),work=new Mat(),rows=new Mat(),mask=new Mat();MatOfKeyPoint keys=new MatOfKeyPoint();
        try{
            double scale=extent==0?1:Math.min(4,Math.max(1,extent/(double)Math.max(crop.cols(),crop.rows())));
            Imgproc.resize(crop,work,new Size(),scale,scale,Imgproc.INTER_LINEAR);
            DiagnosticHammingMatcher.normalize(clahe,work,work);
            DiagnosticHammingMatcher.extract(orb,work,mask,keys,rows);
            KeyPoint[] points=keys.toArray();double sx=crop.cols()/(double)work.cols(),sy=crop.rows()/(double)work.rows();
            for(KeyPoint k:points){k.pt.x=x+k.pt.x*sx;k.pt.y=y+k.pt.y*sy;k.size*=sx;}
            return new Sample(points,rows);
        }catch(RuntimeException|Error failure){rows.release();throw failure;}
        finally{crop.release();work.release();mask.release();keys.release();}
    }
    static final class Sample implements AutoCloseable {
        final KeyPoint[] keys;final Mat descriptors;
        Sample(KeyPoint[] keys,Mat descriptors){this.keys=keys;this.descriptors=descriptors;}
        public void close(){descriptors.release();}
    }
    public void close(){orb.clear();clahe.clear();}
}
