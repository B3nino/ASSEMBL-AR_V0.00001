package dev.chyken.assembl_ar.vision;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.*;
/** Corroborates a top-view photo match with two separate visible yellow gearbox bodies. */
final class Phase2MotorColorCounter {
    static int count(Mat rgb){
        Mat hsv=new Mat(),mask=new Mat(),hierarchy=new Mat();List<MatOfPoint> contours=new ArrayList<>();
        try {
            Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_RGB2HSV);
            Core.inRange(hsv,new Scalar(15,70,50),new Scalar(43,255,255),mask);
            Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
            int found=0;
            for(MatOfPoint c:contours){
                double area=Imgproc.contourArea(c);if(area<rgb.total()*.002||area>rgb.total()*.25)continue;
                MatOfPoint2f p=new MatOfPoint2f(c.toArray());
                try{RotatedRect box=Imgproc.minAreaRect(p);double min=Math.min(box.size.width,box.size.height),max=Math.max(box.size.width,box.size.height);
                    if(min>8&&max/min>=1.1&&max/min<=5.5&&area/box.size.area()>.35)found++;
                }finally{p.release();}
            }
            return found;
        }finally{hsv.release();mask.release();hierarchy.release();for(Mat c:contours)c.release();}
    }
}
