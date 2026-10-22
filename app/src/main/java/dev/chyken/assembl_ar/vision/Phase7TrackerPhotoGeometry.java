package dev.chyken.assembl_ar.vision;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.*;

/** Source metadata: two bodies, two nut patches, then two physical sensor identity patches. */
final class Phase7TrackerPhotoGeometry {
    static List<List<VisionPoint>> regions(Mat bgr){
        return regions(bgr, true);
    }
    /** Live search proposals only; color never constitutes recognition evidence. */
    static List<List<VisionPoint>> searchRegions(Mat bgr){
        return regions(bgr, false);
    }
    private static List<List<VisionPoint>> regions(Mat bgr, boolean locateNuts){
        Mat gray=new Mat(),hsv=new Mat(),blue=new Mat(),hierarchy=new Mat();List<MatOfPoint> contours=new ArrayList<>();
        try{
            Imgproc.cvtColor(bgr,gray,Imgproc.COLOR_BGR2GRAY);Imgproc.cvtColor(bgr,hsv,Imgproc.COLOR_BGR2HSV);
            // Exclude the dark blue PCB and its silkscreen. Bright blue trim-pot rims remain
            // separate even in the new close-up photographs at the edges of the frame.
            Core.inRange(hsv,new Scalar(85,100,70),new Scalar(135,255,255),blue);
            Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(3,3));
            try{Imgproc.morphologyEx(blue,blue,Imgproc.MORPH_CLOSE,kernel);}finally{kernel.release();}
            Imgproc.findContours(blue,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
            List<Blob> blobs=new ArrayList<>(),pots=new ArrayList<>();
            for(MatOfPoint c:contours){
                Rect r=Imgproc.boundingRect(c);double area=Imgproc.contourArea(c);if(area<8||Math.min(r.width,r.height)<3)continue;
                Mat patch=gray.submat(r),bright=new Mat();double fraction;
                try{Imgproc.threshold(patch,bright,130,255,Imgproc.THRESH_BINARY);fraction=Core.countNonZero(bright)/r.area();}finally{patch.release();bright.release();}
                Blob blob=new Blob(area,r);blobs.add(blob);
                if(fraction>.20&&Math.max(r.width,r.height)/(double)Math.min(r.width,r.height)<2.5
                        &&blob.diameter<Math.min(bgr.cols(),bgr.rows())*.18)pots.add(blob);
            }
            pots.sort((a,b)->Double.compare(b.area,a.area));List<List<VisionPoint>> bodies=new ArrayList<>(),nuts=new ArrayList<>(),identity=new ArrayList<>();
            for(int i=0;i<Math.min(2,pots.size());i++){
                Blob pot=pots.get(i),led=null;double best=Double.MAX_VALUE;
                for(Blob b:blobs){double distance=Math.hypot(b.x-pot.x,b.y-pot.y);
                    if(b.area<pot.area*.9&&distance>pot.diameter*1.1&&distance<pot.diameter*4.5&&b.diameter<pot.diameter&&distance<best){best=distance;led=b;}}
                if(led==null)continue;
                double ax=(led.x-pot.x)/best,ay=(led.y-pot.y)/best,d=pot.diameter;
                double tx=led.x+ax*d*1.15,ty=led.y+ay*d*1.15,bx=pot.x-ax*d*2.8,by=pot.y-ay*d*2.8;
                bodies.add(bounds(bgr,new Point(tx+ay*d*.85,ty-ax*d*.85),new Point(tx-ay*d*.85,ty+ax*d*.85),
                        new Point(bx+ay*d*.85,by-ax*d*.85),new Point(bx-ay*d*.85,by+ax*d*.85)));
                double mx=led.x+ax*d*.85,my=led.y+ay*d*.85;
                List<VisionPoint> seed=bounds(bgr,new Point(mx-d*.75,my-d*.75),new Point(mx+d*.75,my+d*.75));
                nuts.add(locateNuts?nutPatch(gray,seed,d):seed);
                Rect pr=pot.rect;identity.add(bounds(bgr,new Point(pr.x-pr.width*.5,pr.y-pr.height*.5),new Point(pr.x+pr.width*1.5,pr.y+pr.height*1.5)));
            }
            while(bodies.size()<2){bodies.add(tiny());nuts.add(tiny());identity.add(tiny());}
            bodies.addAll(nuts);bodies.addAll(identity);return bodies;
        }finally{for(Mat c:contours)c.release();gray.release();hsv.release();blue.release();hierarchy.release();}
    }
    private static List<VisionPoint> nutPatch(Mat gray,List<VisionPoint> seed,double diameter){
        int l=(int)(seed.get(0).getX()*gray.cols()),t=(int)(seed.get(0).getY()*gray.rows());
        int r=(int)(seed.get(2).getX()*gray.cols()),b=(int)(seed.get(2).getY()*gray.rows());
        if(r<=l||b<=t)return seed;
        Mat patch=gray.submat(t,b,l,r),blur=new Mat(),edges=new Mat(),hierarchy=new Mat();List<MatOfPoint> contours=new ArrayList<>();
        List<VisionPoint> best=seed;double bestScore=0;
        try{
            Imgproc.GaussianBlur(patch,blur,new Size(3,3),0);Imgproc.Canny(blur,edges,50,120);
            Imgproc.findContours(edges,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE);
            for(MatOfPoint c:contours){
                Rect box=Imgproc.boundingRect(c);double area=Imgproc.contourArea(c);MatOfPoint2f points=new MatOfPoint2f(c.toArray());double perimeter;
                try{perimeter=Imgproc.arcLength(points,true);}finally{points.release();}
                if(perimeter<=0||Math.min(box.width,box.height)<=diameter*.22||Math.max(box.width,box.height)>=diameter*1.3
                        ||Math.max(box.width,box.height)/(double)Math.min(box.width,box.height)>1.7||4*Math.PI*area/(perimeter*perimeter)<.50)continue;
                double distance=Math.hypot(box.x+box.width*.5-patch.cols()*.5,box.y+box.height*.5-patch.rows()*.5),score=area/(1+distance/diameter*2);
                if(score>bestScore){bestScore=score;best=bounds(gray,new Point(l+box.x-box.width*.15,t+box.y-box.height*.15),new Point(l+box.x+box.width*1.15,t+box.y+box.height*1.15));}
            }
            if(bestScore==0){
                Mat circles=new Mat();try{
                    Imgproc.HoughCircles(blur,circles,Imgproc.HOUGH_GRADIENT,1,Math.max(3,diameter*.3),100,12,Math.max(3,(int)(diameter*.16)),Math.max(4,(int)(diameter*.60)));
                    for(int i=0;i<circles.cols();i++){
                        double[] circle=circles.get(0,i);double x=circle[0],y=circle[1],radius=circle[2];
                        if(x-radius<0||y-radius<0||x+radius>=patch.cols()||y+radius>=patch.rows())continue;
                        return bounds(gray,new Point(l+x-radius*1.4,t+y-radius*1.4),new Point(l+x+radius*1.4,t+y+radius*1.4));
                    }
                }finally{circles.release();}
            }
            return best;
        }finally{for(Mat c:contours)c.release();patch.release();blur.release();edges.release();hierarchy.release();}
    }
    private static List<VisionPoint> bounds(Mat image,Point... points){
        double l=Double.MAX_VALUE,t=l,r=-l,b=-l;for(Point p:points){l=Math.min(l,p.x);t=Math.min(t,p.y);r=Math.max(r,p.x);b=Math.max(b,p.y);}
        l=Math.max(0,l/image.cols());r=Math.min(1,r/image.cols());t=Math.max(0,t/image.rows());b=Math.min(1,b/image.rows());
        return Arrays.asList(new VisionPoint(l,t),new VisionPoint(r,t),new VisionPoint(r,b),new VisionPoint(l,b));
    }
    private static List<VisionPoint> tiny(){return Arrays.asList(new VisionPoint(0,0),new VisionPoint(.002,0),new VisionPoint(.002,.002),new VisionPoint(0,.002));}
    private static final class Blob{final double area,x,y,diameter;final Rect rect;Blob(double area,Rect r){this.area=area;rect=r;x=r.x+r.width*.5;y=r.y+r.height*.5;diameter=Math.max(r.width,r.height);}}
}
