package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase789VideoReferences;
import java.util.*;
import org.opencv.core.*;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.*;

/** Offline/cache-loader preparation only. Preserve scene context and add bounded
 * local samples of reviewed REAL bodies at tablet sampling sizes. All points
 * return to source coordinates; local samples cannot become board identity. */
final class ReviewedHardwareDescriptorSampler {
    static List<AdminDescriptorBlobCodec.DescriptorVariant> compute(ComponentReferenceSpec spec,Mat gray,Mat mask,ORB orb,CLAHE clahe){
        Phase789VideoReferences.Entry e=Phase789VideoReferences.annotation(spec.getJpegAssetPath());
        List<AdminDescriptorBlobCodec.DescriptorVariant> levels=new ArrayList<>(),result=new ArrayList<>();
        ORB detail=ORB.create(1200,1.2f,8,12,0,2,ORB.HARRIS_SCORE,31,7);
        try{
            levels.addAll(ReferenceDescriptorPyramid.forReference(spec,gray,mask,orb,clahe));
            List<List<VisionPoint>> bodies=e.phase==8?Collections.singletonList(e.body):e.role.equals("trackerwork")?Arrays.asList(e.regions.get(0),e.regions.get(1),e.regions.get(6)):e.regions;
            for(List<VisionPoint> body:bodies){
                double l=1,t=1,r=0,b=0;for(VisionPoint p:body){l=Math.min(l,p.getX());t=Math.min(t,p.getY());r=Math.max(r,p.getX());b=Math.max(b,p.getY());}
                Rect roi=Phase9PhotoGeometry.bounded(l*gray.cols()-12,t*gray.rows()-12,r*gray.cols()+12,b*gray.rows()+12,gray);
                if(roi.width<8||roi.height<8)continue;
                Mat source=gray.submat(roi),allowed=Mat.zeros(gray.size(),CvType.CV_8UC1),bodyMask=null,image=new Mat(),localMask=new Mat();MatOfPoint polygon=new MatOfPoint();
                try{
                    Point[] p=new Point[4];for(int n=0;n<4;n++)p[n]=new Point(body.get(n).getX()*gray.cols(),body.get(n).getY()*gray.rows());polygon.fromArray(p);Imgproc.fillConvexPoly(allowed,polygon,new Scalar(255));Core.bitwise_and(allowed,mask,allowed);bodyMask=allowed.submat(roi);
                    for(int extent:new int[]{128,256,400}){
                        double scale=extent/(double)Math.max(roi.width,roi.height);Imgproc.resize(source,image,new Size(),scale,scale,scale<1?Imgproc.INTER_AREA:Imgproc.INTER_LINEAR);Imgproc.resize(bodyMask,localMask,image.size(),0,0,Imgproc.INTER_NEAREST);
                        List<AdminDescriptorBlobCodec.DescriptorVariant> samples=AdminDescriptorBlobCodec.computeVariants(image,localMask,detail,clahe);levels.addAll(samples);
                        for(AdminDescriptorBlobCodec.DescriptorVariant v:samples)for(KeyPoint k:v.keypoints){k.pt.x=k.pt.x/scale+roi.x;k.pt.y=k.pt.y/scale+roi.y;k.size/=scale;}
                    }
                }finally{source.release();if(bodyMask!=null)bodyMask.release();allowed.release();image.release();localMask.release();polygon.release();}
            }
            for(ReferenceDatabase.PhotometricVariant photo:ReferenceDatabase.PhotometricVariant.values()){
                List<KeyPoint> keys=new ArrayList<>();List<Mat> rows=new ArrayList<>();for(AdminDescriptorBlobCodec.DescriptorVariant v:levels)if(v.photometricVariant==photo&&!v.descriptors.empty()){Collections.addAll(keys,v.keypoints);rows.add(v.descriptors);}
                if(rows.isEmpty())continue;Mat merged=new Mat();Core.vconcat(rows,merged);result.add(new AdminDescriptorBlobCodec.DescriptorVariant(photo,keys.toArray(new KeyPoint[0]),merged,true));
            }return result;
        }catch(RuntimeException failure){for(AdminDescriptorBlobCodec.DescriptorVariant v:result)v.release();throw failure;}
        finally{for(AdminDescriptorBlobCodec.DescriptorVariant v:levels)v.release();detail.clear();}
    }
}
