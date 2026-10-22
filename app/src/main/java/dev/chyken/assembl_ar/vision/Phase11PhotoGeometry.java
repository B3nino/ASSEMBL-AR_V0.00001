package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase11AssetCatalog;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.util.*;

/** Source-only OLED PCB / battery face regions; installed OLED also retains independent PCB context. */
final class Phase11PhotoGeometry {
    static final int VERSION=2;
    static ComponentReferenceSpec resolve(ComponentReferenceSpec source,byte[] bytes){
        MatOfByte encoded=new MatOfByte(bytes);Mat bgr=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_COLOR);
        try{if(bgr.empty())throw new IllegalArgumentException("Unreadable Phase 11 photo");double scale=Math.min(1,1280.0/Math.max(bgr.cols(),bgr.rows()));if(scale<1)Imgproc.resize(bgr,bgr,new Size(),scale,scale,Imgproc.INTER_AREA);
            boolean battery="battery".equals(Phase11AssetCatalog.referenceRole(source.getReferenceId())),installed="assembly".equals(Phase11AssetCatalog.referenceRole(source.getReferenceId()));Rect best=null;
            List<Rect> candidates=new ArrayList<>();
            for(Rect r:Phase9PhotoGeometry.colored(bgr,new Scalar(80,45,60),new Scalar(135,255,255),11)){
                if(installed){double cx=r.x+r.width*.5;if(cx<bgr.cols()*.30||cx>bgr.cols()*.70||r.width<bgr.cols()*.04||r.height<bgr.rows()*.006||r.y<bgr.rows()*.20)continue;}
                else if(r.area()<=bgr.total()*.0015||r.width<=bgr.cols()*.06||r.height<=bgr.rows()*.025)continue;
                candidates.add(r);
            }
            if(!candidates.isEmpty()){
                Rect seed=candidates.get(0);best=seed;
                for(Rect r:candidates){double overlap=Math.min(seed.x+seed.width,r.x+r.width)-Math.max(seed.x,r.x);
                    if(overlap<Math.min(seed.width,r.width)*.5||Math.abs((r.y+r.height*.5)-(seed.y+seed.height*.5))>seed.width*1.5)continue;
                    int l=Math.min(best.x,r.x),t=Math.min(best.y,r.y);best=new Rect(l,t,Math.max(best.x+best.width,r.x+r.width)-l,Math.max(best.y+best.height,r.y+r.height)-t);
                }
            }
            if(best==null)throw new IllegalArgumentException("OLED PCB or battery label not localized: "+source.getJpegAssetPath());
            int l=Math.max(0,(int)(best.x-best.width*(battery?.20:.06))),t=Math.max(0,(int)(best.y-best.height*(battery?.7:.06))),r=Math.min(bgr.cols(),(int)(best.x+best.width*(battery?1.20:1.06))),b=Math.min(bgr.rows(),(int)(best.y+best.height*(battery?1.7:1.06)));
            List<VisionPoint> roi=rect(l/(double)bgr.cols(),t/(double)bgr.rows(),r/(double)bgr.cols(),b/(double)bgr.rows());
            return ComponentReferenceSpec.builder(source.getReferenceId(),source.getComponentId(),source.getDisplayName(),source.getPhysicalWidthMm(),source.getPhysicalHeightMm()).jpegAssetPath(source.getJpegAssetPath()).assemblyStepNumber(11).maximumImageDimension(1280).planarReferenceCornersNormalized(roi.get(0),roi.get(1),roi.get(2),roi.get(3)).featureRegionsNormalized(Collections.singletonList(installed?rect(.02,.02,.98,.98):roi)).polarityAnchorsVerified(false).build();
        }finally{bgr.release();encoded.release();}
    }
    private static List<VisionPoint> rect(double l,double t,double r,double b){return Arrays.asList(new VisionPoint(l,t),new VisionPoint(r,t),new VisionPoint(r,b),new VisionPoint(l,b));}
}
