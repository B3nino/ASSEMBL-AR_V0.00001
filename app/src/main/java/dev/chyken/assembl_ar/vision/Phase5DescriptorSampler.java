package dev.chyken.assembl_ar.vision;

import java.util.*;
import org.opencv.core.*;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.CLAHE;

/** Independent descriptor budgets stop dense Nano/driver traces starving PCB landmarks. */
final class Phase5DescriptorSampler {
    static final int VERSION=1;
    static boolean installed(ComponentReferenceSpec spec){return dev.chyken.assembl_ar.camera.Phase5AssetCatalog.installed(dev.chyken.assembl_ar.camera.Phase5AssetCatalog.referenceRole(spec.getReferenceId()));}
    static List<AdminDescriptorBlobCodec.DescriptorVariant> compute(ComponentReferenceSpec spec,Mat gray,Mat mask,ORB orb,CLAHE clahe){
        List<AdminDescriptorBlobCodec.DescriptorVariant> original=ReferenceDescriptorPyramid.forReference(spec,gray,mask,orb,clahe),context=Collections.emptyList(),result=new ArrayList<>();
        double[] b=LocalPhotometricRefiner.bounds(spec,gray.cols(),gray.rows());
        try{
            context=LocalPhotometricRefiner.prepare(spec,gray,clahe);
            for(ReferenceDatabase.PhotometricVariant photo:ReferenceDatabase.PhotometricVariant.values()){
                List<KeyPoint> keys=new ArrayList<>();List<Mat> parts=new ArrayList<>();
                try{
                    for(AdminDescriptorBlobCodec.DescriptorVariant v:original)if(v.photometricVariant==photo){
                        List<Integer> chosen=new ArrayList<>();for(int i=0;i<v.keypoints.length;i++){Point p=v.keypoints[i].pt;if(p.x>=b[0]&&p.x<=b[2]&&p.y>=b[1]&&p.y<=b[3]){chosen.add(i);keys.add(v.keypoints[i]);}}
                        if(!chosen.isEmpty()){Mat rows=new Mat(chosen.size(),32,CvType.CV_8UC1);boolean published=false;try{byte[] all=new byte[v.descriptors.rows()*32],selected=new byte[chosen.size()*32];v.descriptors.get(0,0,all);for(int i=0;i<chosen.size();i++)System.arraycopy(all,chosen.get(i)*32,selected,i*32,32);rows.put(0,0,selected);parts.add(rows);published=true;}finally{if(!published)rows.release();}}
                    }
                    for(AdminDescriptorBlobCodec.DescriptorVariant v:context)if(v.photometricVariant==photo&&!v.descriptors.empty()){Collections.addAll(keys,v.keypoints);parts.add(v.descriptors.clone());}
                    if(!parts.isEmpty())AdminDescriptorBlobCodec.concatenateInto(result,photo,keys,parts);
                }finally{for(Mat rows:parts)rows.release();}
            }
            return result;
        }catch(RuntimeException|Error e){for(AdminDescriptorBlobCodec.DescriptorVariant v:result)v.release();throw e;}
        finally{for(AdminDescriptorBlobCodec.DescriptorVariant v:original)v.release();for(AdminDescriptorBlobCodec.DescriptorVariant v:context)v.release();}
    }
}
