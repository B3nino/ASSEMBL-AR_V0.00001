package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase1011VideoReferences;
import java.util.*;
import org.opencv.core.*;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.*;

/** Cache-loader-only real body/context sampling. No frame-thread file reads. */
final class Phase1011DescriptorSampler {
    static List<AdminDescriptorBlobCodec.DescriptorVariant> compute(ComponentReferenceSpec spec,Mat gray,CLAHE clahe){
        Phase1011VideoReferences.Entry e=Phase1011VideoReferences.annotation(spec.getJpegAssetPath());
        Mat allowed=Mat.zeros(gray.size(),CvType.CV_8UC1);List<List<VisionPoint>> shapes=new ArrayList<>(e.identity);shapes.addAll(e.context);
        for(List<VisionPoint> shape:shapes)fill(allowed,shape,gray.cols(),gray.rows());
        ORB orb=ORB.create(1600,1.2f,8,12,0,2,ORB.HARRIS_SCORE,31,7);
        List<AdminDescriptorBlobCodec.DescriptorVariant> levels=new ArrayList<>(),result=new ArrayList<>();
        try{
            levels.addAll(ReferenceDescriptorPyramid.forReference(spec,gray,allowed,orb,clahe));
            List<List<VisionPoint>> bodies=new ArrayList<>();bodies.add(e.role.equals("protected")?e.regions.get(1):e.body);
            if(e.role.equals("cable"))bodies.addAll(e.identity.subList(0,2));
            for(int part=0;part<bodies.size();part++){
                List<VisionPoint> body=bodies.get(part);double l=1,t=1,r=0,b=0;for(VisionPoint p:body){l=Math.min(l,p.getX());r=Math.max(r,p.getX());t=Math.min(t,p.getY());b=Math.max(b,p.getY());}
                Rect area=Phase9PhotoGeometry.bounded(l*gray.cols()-18,t*gray.rows()-18,r*gray.cols()+18,b*gray.rows()+18,gray);
                Mat crop=gray.submat(area),mask=allowed.submat(area),image=new Mat(),localMask=new Mat();
                try{for(int extent:part==0?new int[]{96,160,320}:new int[]{256}){
                    double scale=extent/(double)Math.max(area.width,area.height);Imgproc.resize(crop,image,new Size(),scale,scale,scale<1?Imgproc.INTER_AREA:Imgproc.INTER_LINEAR);Imgproc.resize(mask,localMask,image.size(),0,0,Imgproc.INTER_NEAREST);
                    List<AdminDescriptorBlobCodec.DescriptorVariant> added=AdminDescriptorBlobCodec.computeVariants(image,localMask,orb,clahe);levels.addAll(added);
                    for(AdminDescriptorBlobCodec.DescriptorVariant v:added)for(KeyPoint k:v.keypoints){k.pt.x=k.pt.x/scale+area.x;k.pt.y=k.pt.y/scale+area.y;k.size/=scale;}
                }}finally{crop.release();mask.release();image.release();localMask.release();}
            }
            for(ReferenceDatabase.PhotometricVariant photo:ReferenceDatabase.PhotometricVariant.values()){
                List<KeyPoint> keys=new ArrayList<>();List<Mat> rows=new ArrayList<>();for(AdminDescriptorBlobCodec.DescriptorVariant v:levels)if(v.photometricVariant==photo&&!v.descriptors.empty()){Collections.addAll(keys,v.keypoints);rows.add(v.descriptors);}
                if(rows.isEmpty())continue;Mat merged=new Mat();Core.vconcat(rows,merged);
                // Bound new-view descriptor work and resident memory. Every
                // source scale participates; no trial outcome is altered.
                int count=Math.min(2400,keys.size());if(count<keys.size()){
                    Mat limited=new Mat(count,merged.cols(),merged.type());List<KeyPoint> kept=new ArrayList<>();int stride=merged.cols();byte[] all=new byte[merged.rows()*stride],sample=new byte[count*stride];merged.get(0,0,all);
                    for(int i=0;i<count;i++){int n=i*keys.size()/count;kept.add(keys.get(n));System.arraycopy(all,n*stride,sample,i*stride,stride);}limited.put(0,0,sample);merged.release();merged=limited;keys=kept;
                }
                result.add(new AdminDescriptorBlobCodec.DescriptorVariant(photo,keys.toArray(new KeyPoint[0]),merged,true));
            }
            return result;
        }catch(RuntimeException failure){for(AdminDescriptorBlobCodec.DescriptorVariant v:result)v.release();throw failure;}
        finally{for(AdminDescriptorBlobCodec.DescriptorVariant v:levels)v.release();allowed.release();orb.clear();}
    }
    private static void fill(Mat mask,List<VisionPoint> p,int w,int h){MatOfPoint polygon=new MatOfPoint();try{Point[] v=new Point[p.size()];for(int i=0;i<v.length;i++)v[i]=new Point(p.get(i).getX()*w,p.get(i).getY()*h);polygon.fromArray(v);Imgproc.fillConvexPoly(mask,polygon,new Scalar(255));}finally{polygon.release();}}
}
