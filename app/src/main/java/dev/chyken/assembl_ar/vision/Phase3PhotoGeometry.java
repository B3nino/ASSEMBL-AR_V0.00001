package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase3AssetCatalog;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.util.*;

/** Finds the blue driver PCB in each raw photo; mounting holes are excluded from descriptors. */
final class Phase3PhotoGeometry {
    static final int VERSION=2;
    static ComponentReferenceSpec resolve(ComponentReferenceSpec source,byte[] bytes){
        Phase3ReferenceAnnotations.Regions authored=Phase3ReferenceAnnotations.forPath(source.getJpegAssetPath());
        if(authored!=null){List<VisionPoint> c=authored.outline;return ComponentReferenceSpec.builder(source.getReferenceId(),source.getComponentId(),source.getDisplayName(),28,37)
            .jpegAssetPath(source.getJpegAssetPath()).assemblyStepNumber(3).maximumImageDimension(1280)
            .planarReferenceCornersNormalized(c.get(0),c.get(1),c.get(2),c.get(3)).featureRegionsNormalized(authored.features()).polarityAnchorsVerified(false).build();}
        MatOfByte encoded=new MatOfByte(bytes);Mat rgb=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_COLOR),hsv=new Mat(),mask=new Mat(),hierarchy=new Mat();
        List<MatOfPoint> contours=new ArrayList<>();
        try{
            if(rgb.empty())throw new IllegalArgumentException("Unreadable Phase 3 photo");
            double scale=Math.min(1,1280.0/Math.max(rgb.cols(),rgb.rows()));if(scale<1)Imgproc.resize(rgb,rgb,new Size(),scale,scale,Imgproc.INTER_AREA);
            Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_BGR2HSV);Core.inRange(hsv,new Scalar(82,65,25),new Scalar(135,255,255),mask);
            // Blue PCB solder mask is split by traces and chips; join those patches before bounding it.
            Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(13,13));
            try{Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel);}finally{kernel.release();}
            Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
            Rect best=null;double area=0;
            for(MatOfPoint c:contours){double a=Imgproc.contourArea(c);Rect r=Imgproc.boundingRect(c);if(a>area&&a>rgb.total()*.0005&&r.area()<rgb.total()*.9){best=r;area=a;}}
            if(best==null)throw new IllegalArgumentException("Driver PCB not visible in Phase 3 photo: "+source.getJpegAssetPath());
            List<VisionPoint> corners=rect(best.x/(double)rgb.cols(),best.y/(double)rgb.rows(),(best.x+best.width)/(double)rgb.cols(),(best.y+best.height)/(double)rgb.rows());
            boolean assembly=Phase3AssetCatalog.installed(Phase3AssetCatalog.referenceRole(source.getReferenceId()));
            return ComponentReferenceSpec.builder(source.getReferenceId(),source.getComponentId(),source.getDisplayName(),28,37)
                    .jpegAssetPath(source.getJpegAssetPath()).assemblyStepNumber(3).maximumImageDimension(1280)
                    .planarReferenceCornersNormalized(corners.get(0),corners.get(1),corners.get(2),corners.get(3)).featureRegionsNormalized(Collections.singletonList(assembly?rect(.02,.02,.98,.98):corners))
                    .polarityAnchorsVerified(false).build();
        }finally{for(Mat c:contours)c.release();encoded.release();rgb.release();hsv.release();mask.release();hierarchy.release();}
    }
    static void excludeMountingHoles(Mat mask,ComponentReferenceSpec spec){
        Phase3ReferenceAnnotations.Regions authored=Phase3ReferenceAnnotations.forPath(spec.getJpegAssetPath());
        if(authored!=null){for(VisionPoint p:authored.holes)Imgproc.circle(mask,new Point(p.getX()*mask.cols(),p.getY()*mask.rows()),(int)Math.max(2,authored.holeRadius*mask.cols()),new Scalar(0),-1);return;}
        List<VisionPoint> c=spec.getPlanarCornersNormalized();double left=c.get(0).getX()*mask.cols(),top=c.get(0).getY()*mask.rows();
        double width=(c.get(2).getX()-c.get(0).getX())*mask.cols(),height=(c.get(2).getY()-c.get(0).getY())*mask.rows();
        int radius=(int)Math.max(3,Math.min(width,height)*.105);
        for(double x:new double[]{.12,.88})for(double y:new double[]{.18,.80})Imgproc.circle(mask,new Point(left+width*x,top+height*y),radius,new Scalar(0),-1);
    }
    private static List<VisionPoint> rect(double l,double t,double r,double b){return Arrays.asList(new VisionPoint(l,t),new VisionPoint(r,t),new VisionPoint(r,b),new VisionPoint(l,b));}
}
