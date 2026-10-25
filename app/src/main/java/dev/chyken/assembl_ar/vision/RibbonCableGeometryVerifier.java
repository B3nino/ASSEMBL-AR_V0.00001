package dev.chyken.assembl_ar.vision;

import java.util.*;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

/** Photo-first, expectation-scoped fallback for the kit's red-edged gray IDC
 * ribbon cable. A long rectangle or red wire cannot pass: both enlarged end
 * housings, the continuous flat body and the distributed edge stripe are needed.
 * Reports measured shape evidence, never invented ORB matches or a 3D pose. */
final class RibbonCableGeometryVerifier {
    static final class Detection {
        final List<VisionPoint> corners;
        Detection(List<VisionPoint> corners){this.corners=corners;}
    }
    static Detection find(Mat gray,Mat rgb){
        if(gray==null||rgb==null||gray.empty()||rgb.empty()||gray.rows()!=rgb.rows()||gray.cols()!=rgb.cols())return null;
        Mat hsv=new Mat(),red=new Mat(),high=new Mat(),mask=new Mat(),hierarchy=new Mat(),body=Mat.zeros(gray.size(),CvType.CV_8UC1),intersection=new Mat(),pixels=new Mat();
        Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(3,3));List<MatOfPoint> contours=new ArrayList<>();
        try{
            Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_RGB2HSV);
            Core.inRange(hsv,new Scalar(0,45,25),new Scalar(12,255,255),red);Core.inRange(hsv,new Scalar(168,45,25),new Scalar(180,255,255),high);Core.bitwise_or(red,high,red);
            if(Core.countNonZero(red)<40)return null;
            for(int threshold:new int[]{0,80,110,140}){
                for(Mat c:contours)c.release();contours.clear();
                Imgproc.threshold(gray,mask,threshold,255,Imgproc.THRESH_BINARY_INV|(threshold==0?Imgproc.THRESH_OTSU:0));Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel);
                Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
                for(MatOfPoint contour:contours){
                    Rect bounds=Imgproc.boundingRect(contour);double area=Imgproc.contourArea(contour);
                    if(area<gray.total()*.004||area>gray.total()*.30||bounds.x<3||bounds.y<3||bounds.x+bounds.width>=gray.cols()-3||bounds.y+bounds.height>=gray.rows()-3)continue;
                    MatOfPoint2f points=new MatOfPoint2f(contour.toArray());RotatedRect rect;try{rect=Imgproc.minAreaRect(points);}finally{points.release();}
                    double length=Math.max(rect.size.width,rect.size.height),width=Math.min(rect.size.width,rect.size.height);
                    if(length<90||width<10||length/width<3.8||length/width>14||area/(length*width)<.45)continue;
                    body.setTo(new Scalar(0));Imgproc.drawContours(body,Collections.singletonList(contour),0,new Scalar(255),Imgproc.FILLED);Core.bitwise_and(red,body,intersection);
                    if(Core.countNonZero(intersection)<length*.4)continue;
                    Point[] box=new Point[4];rect.points(box);double vx=0,vy=0,best=0;
                    for(int i=0;i<4;i++){double dx=box[(i+1)%4].x-box[i].x,dy=box[(i+1)%4].y-box[i].y,d=Math.hypot(dx,dy);if(d>best){best=d;vx=dx/d;vy=dy/d;}}
                    Core.findNonZero(body,pixels);int[] xy=new int[(int)pixels.total()*2];pixels.get(0,0,xy);
                    double low=Double.POSITIVE_INFINITY,highAlong=-low;for(int i=0;i<xy.length;i+=2){double a=xy[i]*vx+xy[i+1]*vy;low=Math.min(low,a);highAlong=Math.max(highAlong,a);}
                    double[] left=new double[48],right=new double[48],profile=new double[48];Arrays.fill(left,Double.POSITIVE_INFINITY);Arrays.fill(right,Double.NEGATIVE_INFINITY);
                    for(int i=0;i<xy.length;i+=2){double a=xy[i]*vx+xy[i+1]*vy,c=-xy[i]*vy+xy[i+1]*vx;int bin=Math.min(47,(int)((a-low)/(highAlong-low+1e-6)*48));left[bin]=Math.min(left[bin],c);right[bin]=Math.max(right[bin],c);}
                    for(int i=0;i<48;i++)profile[i]=Double.isFinite(left[i]+right[i])?right[i]-left[i]+1:0;
                    double middle=median(profile,10,38);if(middle<9||middle>length*.18)continue;
                    double variation=0;for(int i=10;i<38;i++)variation+=(profile[i]-middle)*(profile[i]-middle);
                    if(Math.sqrt(variation/28)/middle>.20||median(profile,1,5)<middle*1.25||median(profile,43,47)<middle*1.25||profile[0]<middle*1.05||profile[47]<middle*1.05)continue;
                    // The red edge must run along the ribbon, not be a single
                    // colored sticker or a connector beside an unrelated object.
                    Core.findNonZero(intersection,pixels);int[] edge=new int[(int)pixels.total()*2];pixels.get(0,0,edge);int[] thirds=new int[3];
                    for(int i=0;i<edge.length;i+=2){double a=edge[i]*vx+edge[i+1]*vy;int part=Math.max(0,Math.min(2,(int)((a-low)/(highAlong-low+1e-6)*3)));thirds[part]++;}
                    if(thirds[0]<length*.06||thirds[1]<length*.06||thirds[2]<length*.06)continue;
                    List<VisionPoint> corners=new ArrayList<>();for(Point p:box)corners.add(new VisionPoint(p.x,p.y));return new Detection(corners);
                }
            }return null;
        }finally{for(Mat c:contours)c.release();hsv.release();red.release();high.release();mask.release();hierarchy.release();body.release();intersection.release();pixels.release();kernel.release();}
    }
    private static double median(double[] source,int start,int end){double[] copy=Arrays.copyOfRange(source,start,end);Arrays.sort(copy);return (copy[(copy.length-1)/2]+copy[copy.length/2])*.5;}
}
