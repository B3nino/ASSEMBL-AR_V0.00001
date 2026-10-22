package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase11AssetCatalog;
import org.opencv.core.*;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.*;
import java.util.*;

/** Compile OLED detail at camera-compatible scales, independently of the surrounding PCB. */
final class Phase11DescriptorSampler {
    static final int VERSION=2;
    private Phase11DescriptorSampler(){}
    static boolean installed(ComponentReferenceSpec spec){return "assembly".equals(Phase11AssetCatalog.referenceRole(spec.getReferenceId()));}
    static List<AdminDescriptorBlobCodec.DescriptorVariant> compute(ComponentReferenceSpec spec,Mat gray,Mat mask,ORB contextOrb,CLAHE clahe){
        List<VisionPoint> c=spec.getPlanarCornersNormalized();
        int l=Math.max(0,(int)(c.get(0).getX()*gray.cols())),t=Math.max(0,(int)(c.get(0).getY()*gray.rows()));
        int r=Math.min(gray.cols(),(int)Math.ceil(c.get(2).getX()*gray.cols())),b=Math.min(gray.rows(),(int)Math.ceil(c.get(2).getY()*gray.rows()));
        Rect bounds=new Rect(l,t,r-l,b-t);Mat contextMask=mask.clone(),crop=gray.submat(bounds),empty=new Mat();
        ORB local=ORB.create(1600,1.2f,8,15,0,2,ORB.HARRIS_SCORE,31,5);
        List<AdminDescriptorBlobCodec.DescriptorVariant> levels=new ArrayList<>(),result=new ArrayList<>();
        try{
            int gap=(int)Math.ceil(Math.max(bounds.width,bounds.height)*.15);
            Imgproc.rectangle(contextMask,new Point(Math.max(0,l-gap),Math.max(0,t-gap)),new Point(Math.min(gray.cols()-1,r+gap),Math.min(gray.rows()-1,b+gap)),new Scalar(0),-1);
            levels.addAll(AdminDescriptorBlobCodec.computeVariants(gray,contextMask,contextOrb,clahe));
            for(int extent:new int[]{96,160,320}){
                double scale=extent/(double)Math.max(bounds.width,bounds.height);Mat resized=new Mat();
                try{Imgproc.resize(crop,resized,new Size(Math.max(1,Math.round(bounds.width*scale)),Math.max(1,Math.round(bounds.height*scale))),0,0,scale<1?Imgproc.INTER_AREA:Imgproc.INTER_CUBIC);
                    List<AdminDescriptorBlobCodec.DescriptorVariant> variants=AdminDescriptorBlobCodec.computeVariants(resized,empty,local,clahe);levels.addAll(variants);
                    double sx=bounds.width/(double)resized.cols(),sy=bounds.height/(double)resized.rows();
                    for(AdminDescriptorBlobCodec.DescriptorVariant v:variants)for(KeyPoint k:v.keypoints){k.pt.x=l+k.pt.x*sx;k.pt.y=t+k.pt.y*sy;k.size*=Math.sqrt(sx*sy);}
                }finally{resized.release();}
            }
            for(ReferenceDatabase.PhotometricVariant photo:ReferenceDatabase.PhotometricVariant.values()){
                List<KeyPoint> keys=new ArrayList<>();List<Mat> rows=new ArrayList<>();
                for(AdminDescriptorBlobCodec.DescriptorVariant v:levels)if(v.photometricVariant==photo&&!v.descriptors.empty()){Collections.addAll(keys,v.keypoints);rows.add(v.descriptors);}
                if(!rows.isEmpty()){Mat merged=new Mat();Core.vconcat(rows,merged);result.add(new AdminDescriptorBlobCodec.DescriptorVariant(photo,keys.toArray(new KeyPoint[0]),merged,true));}
            }
            return result;
        }catch(RuntimeException failure){for(AdminDescriptorBlobCodec.DescriptorVariant v:result)v.release();throw failure;}
        finally{for(AdminDescriptorBlobCodec.DescriptorVariant v:levels)v.release();contextMask.release();crop.release();empty.release();local.clear();}
    }
}
