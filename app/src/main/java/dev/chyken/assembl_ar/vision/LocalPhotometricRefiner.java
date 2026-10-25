package dev.chyken.assembl_ar.vision;

import java.util.*;
import org.opencv.core.*;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.*;

/** Identical local CLAHE grids for reference preparation and camera resampling.
 * A module pose only proposes this window; independent PCB correspondences must
 * still prove placement. No reference image is read by this runtime sampler. */
final class LocalPhotometricRefiner implements AutoCloseable {
    static final int VERSION=1;
    private final ORB orb=ORB.create(2400,1.2f,8,12,0,2,ORB.HARRIS_SCORE,31,7);
    private final CLAHE clahe;
    LocalPhotometricRefiner(VisionConfig config){clahe=OpenCvVisionUtils.createClahe(config);}
    static double[] bounds(ComponentReferenceSpec spec,int width,int height){
        double l=width,t=height,r=0,b=0;for(VisionPoint p:spec.getPlanarCornersNormalized()){l=Math.min(l,p.getX()*width);t=Math.min(t,p.getY()*height);r=Math.max(r,p.getX()*width);b=Math.max(b,p.getY()*height);}return new double[]{l,t,r,b};
    }
    static Rect window(ComponentReferenceSpec spec,int width,int height){
        double[] b=bounds(spec,width,height);double w=b[2]-b[0],h=b[3]-b[1];
        int l=Math.max(0,(int)(b[0]-.9*w)),t=Math.max(0,(int)(b[1]-.3*h));
        int r=Math.min(width,(int)(b[2]+.9*w)),bottom=Math.min(height,(int)(b[3]+.25*h));
        return new Rect(l,t,Math.max(1,r-l),Math.max(1,bottom-t));
    }
    static Mat contextMask(ComponentReferenceSpec spec,int width,int height,Rect region){
        Mat mask=new Mat(region.height,region.width,CvType.CV_8UC1,new Scalar(255));
        try{double[] b=bounds(spec,width,height);double w=b[2]-b[0],h=b[3]-b[1];
        Imgproc.rectangle(mask,new Point(b[0]-.15*w-region.x,b[1]-.15*h-region.y),new Point(b[2]+.15*w-region.x,b[3]+.15*h-region.y),new Scalar(0),-1);return mask;}catch(RuntimeException|Error failure){mask.release();throw failure;}
    }
    ComponentDetailExtractor.Sample context(Mat gray,ComponentReferenceSpec spec,int width,int height,Mat hypothesis){
        long geometryStart=android.os.SystemClock.elapsedRealtimeNanos();
        Rect region=window(spec,width,height);Mat inverse=hypothesis.inv(),shift=Mat.eye(3,3,CvType.CV_64F),map=new Mat(),empty=new Mat(),aligned=new Mat(),normalized=new Mat(),mask=contextMask(spec,width,height,region),rows=new Mat();MatOfKeyPoint keys=new MatOfKeyPoint();
        try{
            shift.put(0,2,-region.x);shift.put(1,2,-region.y);Core.gemm(shift,inverse,1,empty,0,map);
            Imgproc.warpPerspective(gray,aligned,map,region.size(),Imgproc.INTER_LINEAR,Core.BORDER_CONSTANT,new Scalar(128));
            DiagnosticHammingMatcher.snapshot().geometryNs+=android.os.SystemClock.elapsedRealtimeNanos()-geometryStart;
            DiagnosticHammingMatcher.normalize(clahe,aligned,normalized);DiagnosticHammingMatcher.extract(orb,normalized,mask,keys,rows);
            KeyPoint[] points=keys.toArray();for(KeyPoint p:points){p.pt.x+=region.x;p.pt.y+=region.y;}
            return new ComponentDetailExtractor.Sample(points,rows);
        }catch(RuntimeException|Error e){rows.release();throw e;}
        finally{inverse.release();shift.release();map.release();empty.release();aligned.release();normalized.release();mask.release();keys.release();}
    }
    /** Cached context rows use the same crop/CLAHE grid as context() above. */
    static List<AdminDescriptorBlobCodec.DescriptorVariant> prepare(ComponentReferenceSpec spec,Mat gray,CLAHE clahe){
        Rect region=window(spec,gray.cols(),gray.rows());Mat crop=gray.submat(region),mask=contextMask(spec,gray.cols(),gray.rows(),region);
        ORB orb=ORB.create(2400,1.2f,8,12,0,2,ORB.HARRIS_SCORE,31,7);
        List<AdminDescriptorBlobCodec.DescriptorVariant> variants=Collections.emptyList();
        try{
            variants=AdminDescriptorBlobCodec.computeVariants(crop,mask,orb,clahe);
            double[] body=bounds(spec,gray.cols(),gray.rows());
            for(int i=0;i<variants.size();i++){
                AdminDescriptorBlobCodec.DescriptorVariant v=variants.get(i);
                for(KeyPoint p:v.keypoints){p.pt.x+=region.x;p.pt.y+=region.y;}
                // The whole descriptor support patch, not just its centre, must
                // stay outside the module before it can prove stationary PCB pose.
                AdminDescriptorBlobCodec.DescriptorVariant safe=ReferenceDescriptorPyramid.keepIndependentPatches(v,body);variants.set(i,safe);v.release();
            }
            return variants;
        }catch(RuntimeException|Error failure){for(AdminDescriptorBlobCodec.DescriptorVariant v:variants)v.release();throw failure;}
        finally{crop.release();mask.release();orb.clear();}
    }
    @Override public void close(){orb.clear();clahe.clear();}
}
