package dev.chyken.assembl_ar.vision;

import org.opencv.core.*;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.*;
import java.util.*;

/** Match small shiny parts at the same sampling size as a local camera search window.
 * Global descriptors still locate the board. Every local keypoint returns to source coordinates.
 */
final class Phase9DescriptorSampler {
    private Phase9DescriptorSampler(){}
    static List<AdminDescriptorBlobCodec.DescriptorVariant> compute(ComponentReferenceSpec spec,Mat gray,Mat mask,CLAHE clahe){
        List<AdminDescriptorBlobCodec.DescriptorVariant> levels=new ArrayList<>(),result=new ArrayList<>();
        ORB detail=ORB.create(1200,1.2f,8,12,0,2,ORB.HARRIS_SCORE,31,7),context=ORB.create(2000,1.2f,8,31,0,2,ORB.HARRIS_SCORE,31,10);
        try{
            List<List<VisionPoint>> regions=spec.getFeatureRegionsNormalized();
            boolean installed=dev.chyken.assembl_ar.camera.Phase9AssetCatalog.installed(dev.chyken.assembl_ar.camera.Phase9AssetCatalog.referenceRole(spec.getReferenceId()));
            if(installed){
                Mat image=new Mat(),region=new Mat();try{
                    double scale=Math.min(1,640.0/Math.max(gray.cols(),gray.rows()));Imgproc.resize(gray,image,new Size(),scale,scale,Imgproc.INTER_AREA);Imgproc.resize(mask,region,image.size(),0,0,Imgproc.INTER_NEAREST);
                    List<AdminDescriptorBlobCodec.DescriptorVariant> scene=AdminDescriptorBlobCodec.computeVariants(image,region,context,clahe);levels.addAll(scene);
                    for(AdminDescriptorBlobCodec.DescriptorVariant v:scene)for(KeyPoint k:v.keypoints){k.pt.x/=scale;k.pt.y/=scale;k.size/=scale;}
                }finally{image.release();region.release();}
            }
            // One normalized local sample per physical object, rather than multiplying
            // whole-photo pyramid descriptors for every new angle. ORB retains eight levels.
            for(int part=0;part<(installed?3:regions.size());part++){
                List<VisionPoint> box=regions.get(part);int x=Math.max(0,(int)(box.get(0).getX()*gray.cols())),y=Math.max(0,(int)(box.get(0).getY()*gray.rows()));
                int w=Math.min(gray.cols()-x,(int)Math.round((box.get(2).getX()-box.get(0).getX())*gray.cols())),h=Math.min(gray.rows()-y,(int)Math.round((box.get(2).getY()-box.get(0).getY())*gray.rows()));
                if(w<3||h<3)continue;
                boolean standoff=dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(spec.getJpegAssetPath())!=null&&"center_standoff".equals(spec.getComponentId());
                // ORB's edge threshold used to discard both end faces of this
                // narrow metal part. Keep REAL surrounding pixels for descriptor
                // neighborhoods, with keypoint centers still masked to the body.
                if(standoff){int right=Math.min(gray.cols(),x+w+16),bottom=Math.min(gray.rows(),y+h+16);x=Math.max(0,x-16);y=Math.max(0,y-16);w=right-x;h=bottom-y;}
                Rect area=new Rect(x,y,w,h);Mat crop=gray.submat(area),image=new Mat(),empty=new Mat(),sourceMask=standoff?mask.submat(area):null;
                try{
                  boolean reviewed=dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(spec.getJpegAssetPath())!=null;
                  for(int extent:reviewed?new int[]{128,256,512}:new int[]{512}){
                    double scale=extent/(double)Math.max(w,h);Imgproc.resize(crop,image,new Size(),scale,scale,scale<1?Imgproc.INTER_AREA:Imgproc.INTER_LINEAR);
                    if(sourceMask!=null)Imgproc.resize(sourceMask,empty,image.size(),0,0,Imgproc.INTER_NEAREST);
                    List<AdminDescriptorBlobCodec.DescriptorVariant> local=AdminDescriptorBlobCodec.computeVariants(image,empty,detail,clahe);levels.addAll(local);
                    for(AdminDescriptorBlobCodec.DescriptorVariant v:local)for(KeyPoint k:v.keypoints){k.pt.x=k.pt.x/scale+x;k.pt.y=k.pt.y/scale+y;k.size/=scale;}
                  }
                }finally{crop.release();image.release();empty.release();if(sourceMask!=null)sourceMask.release();}
            }
            for(ReferenceDatabase.PhotometricVariant photo:ReferenceDatabase.PhotometricVariant.values()){
                List<KeyPoint> keys=new ArrayList<>();List<Mat> rows=new ArrayList<>();
                for(AdminDescriptorBlobCodec.DescriptorVariant v:levels)if(v.photometricVariant==photo&&!v.descriptors.empty()){Collections.addAll(keys,v.keypoints);rows.add(v.descriptors);}
                if(rows.isEmpty())continue;Mat merged=new Mat();Core.vconcat(rows,merged);result.add(new AdminDescriptorBlobCodec.DescriptorVariant(photo,keys.toArray(new KeyPoint[0]),merged,true));
            }
            return result;
        }catch(RuntimeException failure){for(AdminDescriptorBlobCodec.DescriptorVariant v:result)v.release();throw failure;}
        finally{for(AdminDescriptorBlobCodec.DescriptorVariant v:levels)v.release();detail.clear();context.clear();}
    }
}
