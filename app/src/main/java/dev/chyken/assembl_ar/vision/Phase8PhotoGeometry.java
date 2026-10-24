package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase8AssetCatalog;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.util.*;

/** Source-only sensor ROI. Installed matching also requires independent surrounding-board inliers. */
final class Phase8PhotoGeometry {
    static final int VERSION=5;
    static ComponentReferenceSpec resolve(ComponentReferenceSpec source,byte[] bytes){
        if(dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(source.getJpegAssetPath())!=null)return source;
        MatOfByte encoded=new MatOfByte(bytes);Mat rgb=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_COLOR),hsv=new Mat(),mask=new Mat(),hierarchy=new Mat();List<MatOfPoint> contours=new ArrayList<>();
        try{
            if(rgb.empty())throw new IllegalArgumentException("Unreadable Phase 8 photo");double scale=Math.min(1,1280.0/Math.max(rgb.cols(),rgb.rows()));if(scale<1)Imgproc.resize(rgb,rgb,new Size(),scale,scale,Imgproc.INTER_AREA);
            String role=Phase8AssetCatalog.referenceRole(source.getReferenceId());boolean installed=Phase8AssetCatalog.installed(role);Rect best=null;double score=0;
            Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_BGR2HSV);Core.inRange(hsv,new Scalar(80,55,25),new Scalar(135,255,255),mask);
            Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(13,9));try{Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel);}finally{kernel.release();}
            Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
            for(MatOfPoint c:contours){Rect r=Imgproc.boundingRect(c);double ratio=r.width/(double)Math.max(1,r.height),area=Imgproc.contourArea(c);
                if(installed){
                    // The installed sensor PCB is edge-on and often has no blue pixels. Locate
                    // the square driver below it, then take the front sensor/socket region.
                    // This crop supplies metadata only; live acceptance still needs sensor inliers.
                    if(r.width<rgb.cols()*.08||ratio<.55||ratio>1.45||area<rgb.total()*.003||r.y>rgb.rows()*.7)continue;
                }else if(r.width<rgb.cols()*.12||ratio<1.65||area<rgb.total()*.0006)continue;
                double rank=installed?area*Math.pow(1-r.y/(double)rgb.rows(),4):area;if(rank>score){score=rank;best=r;}
            }
            if(installed&&best!=null){
                // Inward-facing transducers sit over the driver rather than beyond the
                // board edge. They need their own local ROI, not the empty outward socket.
                boolean inward="invalid".equals(role)&&Phase8AssetCatalog.inwardReference(source.getJpegAssetPath());
                double cx=best.x+best.width*.5;int l=(int)Math.max(0,cx-best.width*1.2),t=(int)Math.max(0,best.y-best.width*(inward?.65:1.45)),r=(int)Math.min(rgb.cols(),cx+best.width*1.2),b=(int)Math.min(rgb.rows(),best.y+best.width*(inward?.60:-.10));
                best=new Rect(l,t,Math.max(1,r-l),Math.max(1,b-t));
            }
            // The supplied invalid installations include a missing sensor. Keep only its expected
            // front socket region as negative evidence, never the unchanged whole board.
            if(best==null&&"invalid".equals(role))best=new Rect((int)(rgb.cols()*.32),(int)(rgb.rows()*.07),(int)(rgb.cols()*.38),(int)(rgb.rows()*.23));
            if(best==null)throw new IllegalArgumentException("Sensor PCB not localized: "+source.getJpegAssetPath());
            double padX=installed?0:best.width*.04,padY=installed?0:best.height*.08;
            double l=Math.max(0,(best.x-padX)/rgb.cols()),t=Math.max(0,(best.y-padY)/rgb.rows()),r=Math.min(1,(best.x+best.width+padX)/rgb.cols()),b=Math.min(1,(best.y+best.height+best.height*.08)/rgb.rows());
            List<VisionPoint> roi=rect(l,t,r,b);
            return ComponentReferenceSpec.builder(source.getReferenceId(),source.getComponentId(),source.getDisplayName(),45,20).jpegAssetPath(source.getJpegAssetPath()).assemblyStepNumber(8).maximumImageDimension(1280)
                .planarReferenceCornersNormalized(roi.get(0),roi.get(1),roi.get(2),roi.get(3)).featureRegionsNormalized(Collections.singletonList(installed?rect(.02,.02,.98,.98):roi)).polarityAnchorsVerified(false).build();
        }finally{for(Mat c:contours)c.release();encoded.release();rgb.release();hsv.release();mask.release();hierarchy.release();}
    }
    private static List<VisionPoint> rect(double l,double t,double r,double b){return Arrays.asList(new VisionPoint(l,t),new VisionPoint(r,t),new VisionPoint(r,b),new VisionPoint(l,b));}
}
