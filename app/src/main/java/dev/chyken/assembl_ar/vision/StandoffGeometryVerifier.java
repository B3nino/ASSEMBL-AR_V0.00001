package dev.chyken.assembl_ar.vision;

import java.util.*;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

/** Expectation-scoped fallback for a reflective loose brass standoff. Color alone
 * cannot pass: require a complete straight shaft, widened end shoulder and a
 * narrower threaded tip. This is reported as shape evidence, never ORB matches.
 * Installed-standoff/wheel verification does not use this fallback. */
final class StandoffGeometryVerifier {
    static final class Detection {
        final List<VisionPoint> corners;final double confidence;
        Detection(List<VisionPoint> corners,double confidence){this.corners=corners;this.confidence=confidence;}
    }
    static Detection find(Mat gray,Mat bgr){
        if(gray==null||gray.empty()||bgr==null||bgr.empty()||gray.rows()!=bgr.rows()||gray.cols()!=bgr.cols())return null;
        Mat hsv=new Mat(),gold=new Mat(),mask=new Mat(),hierarchy=new Mat(),body=Mat.zeros(gray.size(),CvType.CV_8UC1),intersection=new Mat(),pixels=new Mat();
        Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(3,3));List<MatOfPoint> contours=new ArrayList<>();
        try{
            Imgproc.cvtColor(bgr,hsv,Imgproc.COLOR_BGR2HSV);Core.inRange(hsv,new Scalar(8,35,35),new Scalar(45,255,255),gold);
            for(int polarity:new int[]{Imgproc.THRESH_BINARY_INV,Imgproc.THRESH_BINARY}){
                for(Mat c:contours)c.release();contours.clear();
                Imgproc.threshold(gray,mask,0,255,polarity|Imgproc.THRESH_OTSU);Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel);
                Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
                for(MatOfPoint contour:contours){
                    Rect bounds=Imgproc.boundingRect(contour);double area=Imgproc.contourArea(contour);
                    if(area<Math.max(100,gray.total()*.001)||area>gray.total()*.15||bounds.x<2||bounds.y<2||bounds.x+bounds.width>=gray.cols()-2||bounds.y+bounds.height>=gray.rows()-2)continue;
                    MatOfPoint2f points=new MatOfPoint2f(contour.toArray());RotatedRect rectangle;try{rectangle=Imgproc.minAreaRect(points);}finally{points.release();}
                    double length=Math.max(rectangle.size.width,rectangle.size.height),width=Math.min(rectangle.size.width,rectangle.size.height);
                    if(width<6||length<55||length/width<3.8||length/width>13||area/(length*width)<.60)continue;
                    body.setTo(new Scalar(0));Imgproc.drawContours(body,Collections.singletonList(contour),0,new Scalar(255),Imgproc.FILLED);
                    Core.bitwise_and(gold,body,intersection);int filled=Core.countNonZero(body);if(Core.countNonZero(intersection)<filled*.12)continue;
                    Core.findNonZero(body,pixels);int[] xy=new int[(int)pixels.total()*2];pixels.get(0,0,xy);
                    Point[] box=new Point[4];rectangle.points(box);double vx=0,vy=0,best=0;
                    for(int i=0;i<4;i++){double dx=box[(i+1)%4].x-box[i].x,dy=box[(i+1)%4].y-box[i].y,d=Math.hypot(dx,dy);if(d>best){best=d;vx=dx/d;vy=dy/d;}}
                    double low=Double.POSITIVE_INFINITY,high=-low;for(int i=0;i<xy.length;i+=2){double y=xy[i]*vx+xy[i+1]*vy;low=Math.min(low,y);high=Math.max(high,y);}
                    double[] left=new double[64],right=new double[64],profile=new double[64];Arrays.fill(left,Double.POSITIVE_INFINITY);Arrays.fill(right,Double.NEGATIVE_INFINITY);
                    for(int i=0;i<xy.length;i+=2){double y=xy[i]*vx+xy[i+1]*vy,x=-xy[i]*vy+xy[i+1]*vx;int bin=Math.min(63,(int)((y-low)/(high-low+1e-6)*64));left[bin]=Math.min(left[bin],x);right[bin]=Math.max(right[bin],x);}
                    for(int i=0;i<64;i++)profile[i]=Double.isFinite(left[i]+right[i])?right[i]-left[i]+1:0;
                    double[] center=Arrays.copyOfRange(profile,14,50);Arrays.sort(center);double median=(center[17]+center[18])*.5;if(median<6)continue;
                    double variance=0;for(int i=14;i<50;i++)variance+=(profile[i]-median)*(profile[i]-median);double variation=Math.sqrt(variance/36)/median;
                    if(variation>.22||!hasThreadAndShoulder(profile,median))continue;
                    // Brass must extend along the shaft, not just a single gold
                    // pin, label or yellow hub attached to another dark object.
                    Core.findNonZero(intersection,pixels);int[] colored=new int[(int)pixels.total()*2];pixels.get(0,0,colored);int[] thirds=new int[3];
                    for(int i=0;i<colored.length;i+=2){double y=colored[i]*vx+colored[i+1]*vy;int part=Math.max(0,Math.min(2,(int)((y-low)/(high-low+1e-6)*3)));thirds[part]++;}
                    if(thirds[0]<filled*.02||thirds[1]<filled*.02||thirds[2]<filled*.02)continue;
                    List<VisionPoint> corners=new ArrayList<>();for(Point p:box)corners.add(new VisionPoint(p.x,p.y));return new Detection(corners,Math.min(.90,.80+(.22-variation)*.3));
                }
            }return null;
        }finally{for(Mat c:contours)c.release();hsv.release();gold.release();mask.release();hierarchy.release();body.release();intersection.release();pixels.release();kernel.release();}
    }
    private static boolean hasThreadAndShoulder(double[] profile,double shaft){
        for(boolean flip:new boolean[]{false,true}){
            double[] tip=new double[4];for(int i=0;i<4;i++)tip[i]=profile[flip?63-i:i];Arrays.sort(tip);double narrow=(tip[1]+tip[2])*.5;
            if(narrow<shaft*.30||narrow>shaft*.78)continue;
            int shoulders=0;for(int i=4;i<14;i++){double w=profile[flip?63-i:i];if(w>=shaft*1.04&&w-shaft>=1.5)shoulders++;}
            double cap=0;for(int i=56;i<64;i++)cap=Math.max(cap,profile[flip?63-i:i]);
            if(shoulders>=2&&cap>=shaft*.85)return true;
        }return false;
    }
}
