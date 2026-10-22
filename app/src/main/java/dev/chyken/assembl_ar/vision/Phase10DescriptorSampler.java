package dev.chyken.assembl_ar.vision;

import org.opencv.core.*;
import java.util.*;

/** Keep PCB printing visible through the protector opening out of protector identity evidence. */
final class Phase10DescriptorSampler {
    private Phase10DescriptorSampler(){}
    static void isolateProtectorMaterial(ComponentReferenceSpec spec,Mat source,List<AdminDescriptorBlobCodec.DescriptorVariant> variants){
        List<List<VisionPoint>> regions=spec.getFeatureRegionsNormalized();if(regions.size()<2)return;List<VisionPoint> box=regions.get(1);
        double l=box.get(0).getX()*source.cols(),t=box.get(0).getY()*source.rows(),w=(box.get(2).getX()-box.get(0).getX())*source.cols(),h=(box.get(2).getY()-box.get(0).getY())*source.rows();
        for(int vi=0;vi<variants.size();vi++){
            AdminDescriptorBlobCodec.DescriptorVariant v=variants.get(vi);List<Integer> indexes=new ArrayList<>();List<KeyPoint> keys=new ArrayList<>();
            for(int i=0;i<v.keypoints.length;i++){KeyPoint key=v.keypoints[i];double u=(key.pt.x-l)/w,vertical=(key.pt.y-t)/h;boolean within=u>=0&&u<=1&&vertical>=0&&vertical<=1,hole=u>.30&&u<.70&&vertical>.20&&vertical<.78;
                int x=Math.max(0,Math.min(source.cols()-1,(int)key.pt.x)),y=Math.max(0,Math.min(source.rows()-1,(int)key.pt.y));
                if(within&&(hole||source.get(y,x)[0]>=65))continue;indexes.add(i);keys.add(key);
            }
            Mat rows=indexes.isEmpty()?new Mat():new Mat(indexes.size(),v.descriptors.cols(),v.descriptors.type());
            if(!indexes.isEmpty()){int stride=v.descriptors.cols()*(int)v.descriptors.elemSize();byte[] original=new byte[v.descriptors.rows()*stride],selected=new byte[indexes.size()*stride];v.descriptors.get(0,0,original);for(int i=0;i<indexes.size();i++)System.arraycopy(original,indexes.get(i)*stride,selected,i*stride,stride);rows.put(0,0,selected);}
            variants.set(vi,new AdminDescriptorBlobCodec.DescriptorVariant(v.photometricVariant,keys.toArray(new KeyPoint[0]),rows,true));v.release();
        }
    }
}
