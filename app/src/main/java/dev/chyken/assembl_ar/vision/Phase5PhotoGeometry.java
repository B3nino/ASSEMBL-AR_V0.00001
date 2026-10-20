package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase5AssetCatalog;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.util.*;

/** Localizes the narrow Nano PCB separately from the wider blue motor driver in raw photos. */
final class Phase5PhotoGeometry {
    static final int VERSION=1;
    static ComponentReferenceSpec resolve(ComponentReferenceSpec source,byte[] bytes){
        MatOfByte encoded=new MatOfByte(bytes);Mat rgb=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_COLOR),hsv=new Mat(),mask=new Mat(),hierarchy=new Mat();
        List<MatOfPoint> contours=new ArrayList<>();
        try{
            if(rgb.empty())throw new IllegalArgumentException("Unreadable Phase 5 photo");
            double scale=Math.min(1,1280.0/Math.max(rgb.cols(),rgb.rows()));
            if(scale<1)Imgproc.resize(rgb,rgb,new Size(),scale,scale,Imgproc.INTER_AREA);
            Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_BGR2HSV);
            Rect best=null;double bestArea=0;
            // Preserve the strict blue mask normally. A second, larger closing joins PCB patches
            // split by reflections only when the strict pass cannot localize a complete Nano.
            for(int pass=0;pass<2&&best==null;pass++){
                Core.inRange(hsv,new Scalar(82,pass==0?65:25,25),new Scalar(135,255,255),mask);
                Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(pass==0?11:31,pass==0?11:31));
                try{Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel);}finally{kernel.release();}
                for(Mat contour:contours)contour.release();contours.clear();
                Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
                for(MatOfPoint contour:contours){
                    MatOfPoint2f points=new MatOfPoint2f(contour.toArray());RotatedRect shape;
                    try{shape=Imgproc.minAreaRect(points);}finally{points.release();}
                    double shortSide=Math.min(shape.size.width,shape.size.height),longSide=Math.max(shape.size.width,shape.size.height);
                    double aspect=longSide/Math.max(1,shortSide),area=Imgproc.contourArea(contour);
                    // Exclude the wider driver and tiny fragments. These are source-photo gates;
                    // live identity still depends on distributed ORB inliers, not PCB color.
                    if(aspect>=1.7&&aspect<=4.2&&longSide>=Math.max(rgb.cols(),rgb.rows())*.15
                            &&area>rgb.total()*.006&&area<rgb.total()*.65&&area>bestArea){best=Imgproc.boundingRect(contour);bestArea=area;}
                }
            }
            if(best==null)throw new IllegalArgumentException("Nano PCB not localized: "+source.getJpegAssetPath());
            List<VisionPoint> corners=rect(best.x/(double)rgb.cols(),best.y/(double)rgb.rows(),(best.x+best.width)/(double)rgb.cols(),(best.y+best.height)/(double)rgb.rows());
            boolean installed=Phase5AssetCatalog.installed(Phase5AssetCatalog.referenceRole(source.getReferenceId()));
            return ComponentReferenceSpec.builder(source.getReferenceId(),source.getComponentId(),source.getDisplayName(),18,45)
                    .jpegAssetPath(source.getJpegAssetPath()).assemblyStepNumber(5).maximumImageDimension(1280)
                    .planarReferenceCornersNormalized(corners.get(0),corners.get(1),corners.get(2),corners.get(3))
                    .featureRegionsNormalized(Collections.singletonList(installed?rect(.02,.02,.98,.98):corners)).polarityAnchorsVerified(false).build();
        }finally{for(Mat contour:contours)contour.release();encoded.release();rgb.release();hsv.release();mask.release();hierarchy.release();}
    }
    private static List<VisionPoint> rect(double l,double t,double r,double b){return Arrays.asList(new VisionPoint(l,t),new VisionPoint(r,t),new VisionPoint(r,b),new VisionPoint(l,b));}
}
