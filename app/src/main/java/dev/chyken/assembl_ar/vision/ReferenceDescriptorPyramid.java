package dev.chyken.assembl_ar.vision;

import org.opencv.core.*;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import java.util.*;

/** Shared photo preparation for every phase and new administrator registrations.
 * At most three sampling levels; source resolution and both photometric variants survive.
 * Keypoints return to source coordinates before ROI, homography or pose calculations.
 * Live matching remains reference-to-camera, so reference scale alternatives cannot
 * compete with each other in Lowe's ratio. Live-keypoint deduplication remains mandatory.
 */
final class ReferenceDescriptorPyramid {
    static final int VERSION=5;
    private ReferenceDescriptorPyramid() {}

    static List<AdminDescriptorBlobCodec.DescriptorVariant> forReference(ComponentReferenceSpec spec,Mat gray,Mat mask,ORB orb,CLAHE clahe){
        String role=dev.chyken.assembl_ar.camera.Phase7AssetCatalog.referenceRole(spec.getReferenceId());
        // The proven dual-tracker matcher already visits native, 400, 600 and 800 pixel
        // module windows under a strict deadline. Repeating scales on its reference side
        // would multiply descriptor work without adding a new sampling opportunity.
        if("trackerwork".equals(role)||"trackers".equals(role))
            return AdminDescriptorBlobCodec.computeVariants(gray,mask,orb,clahe);
        // The driver PCB is nearly rotationally symmetric. Added coarse support can make
        // board printing look like module orientation. Its fine-sampled installed check
        // retains the proven descriptor set; loose driver recognition uses the pyramid.
        if(dev.chyken.assembl_ar.camera.Phase3AssetCatalog.installed(dev.chyken.assembl_ar.camera.Phase3AssetCatalog.referenceRole(spec.getReferenceId())))
            return AdminDescriptorBlobCodec.computeVariants(gray,mask,orb,clahe);
        // Cylinders protrude beyond a flat PCB boundary. This matcher already separates
        // cylinder-only inliers from independent board context and competing inward photos.
        if(dev.chyken.assembl_ar.camera.Phase8AssetCatalog.installed(
                dev.chyken.assembl_ar.camera.Phase8AssetCatalog.referenceRole(spec.getReferenceId())))
            return compute(gray,mask,orb,clahe);
        double[] boundary=null;
        if(!spec.isDescriptorRegionNormalizationEnabled()){
            double l=1,t=1,r=0,b=0;
            for(VisionPoint p:spec.getPlanarCornersNormalized()){l=Math.min(l,p.getX());t=Math.min(t,p.getY());r=Math.max(r,p.getX());b=Math.max(b,p.getY());}
            boundary=new double[]{l*gray.cols(),t*gray.rows(),r*gray.cols(),b*gray.rows()};
        }
        return compute(gray,mask,orb,clahe,boundary);
    }

    static List<AdminDescriptorBlobCodec.DescriptorVariant> compute(Mat gray,Mat mask,ORB orb,CLAHE clahe){
        return compute(gray,mask,orb,clahe,null);
    }

    private static List<AdminDescriptorBlobCodec.DescriptorVariant> compute(Mat gray,Mat mask,ORB orb,CLAHE clahe,double[] boundary){
        List<AdminDescriptorBlobCodec.DescriptorVariant> levels=new ArrayList<>(),result=new ArrayList<>();
        try{
            int largest=Math.max(gray.cols(),gray.rows()),previous=0;
            for(int extent:new int[]{Math.min(512,largest),Math.min(768,largest),largest}){
                int target=Math.min(largest,extent);if(target==previous)continue;previous=target;
                double scale=target/(double)largest;Mat image=new Mat(),region=new Mat();
                try{
                    Imgproc.resize(gray,image,new Size(Math.round(gray.cols()*scale),Math.round(gray.rows()*scale)),0,0,Imgproc.INTER_AREA);
                    // Administrator registration deliberately uses an empty mask (whole image).
                    if(!mask.empty())Imgproc.resize(mask,region,image.size(),0,0,Imgproc.INTER_NEAREST);
                    List<AdminDescriptorBlobCodec.DescriptorVariant> extracted=AdminDescriptorBlobCodec.computeVariants(image,region,orb,clahe);
                    levels.addAll(extracted);
                    double sx=gray.cols()/(double)image.cols(),sy=gray.rows()/(double)image.rows();
                    for(int i=0;i<extracted.size();i++){
                        AdminDescriptorBlobCodec.DescriptorVariant v=extracted.get(i);
                        for(KeyPoint k:v.keypoints){k.pt.x*=sx;k.pt.y*=sy;k.size*=Math.sqrt(sx*sy);}
                        if(target<largest&&boundary!=null){
                            AdminDescriptorBlobCodec.DescriptorVariant safe=keepIndependentPatches(v,boundary);
                            levels.set(levels.size()-extracted.size()+i,safe);v.release();
                        }
                    }
                }finally{image.release();region.release();}
            }
            for(ReferenceDatabase.PhotometricVariant photo:ReferenceDatabase.PhotometricVariant.values()){
                List<KeyPoint> keys=new ArrayList<>();List<Mat> descriptors=new ArrayList<>();
                for(AdminDescriptorBlobCodec.DescriptorVariant v:levels)if(v.photometricVariant==photo&&!v.descriptors.empty()){Collections.addAll(keys,v.keypoints);descriptors.add(v.descriptors);}
                if(descriptors.isEmpty())continue;
                Mat merged=new Mat();Core.vconcat(descriptors,merged);
                result.add(new AdminDescriptorBlobCodec.DescriptorVariant(photo,keys.toArray(new KeyPoint[0]),merged,true));
            }
            return result;
        }catch(RuntimeException failure){for(AdminDescriptorBlobCodec.DescriptorVariant v:result)v.release();throw failure;}
        finally{for(AdminDescriptorBlobCodec.DescriptorVariant v:levels)v.release();}
    }

    /** A coarse patch centred on a small PCB can actually describe the stationary board
     * behind it. Extra scales must not cross that identity/context boundary. Native
     * descriptors remain exactly as before; only newly added sampling is filtered. */
    static AdminDescriptorBlobCodec.DescriptorVariant keepIndependentPatches(AdminDescriptorBlobCodec.DescriptorVariant v,double[] box){
        List<KeyPoint> keys=new ArrayList<>();List<Integer> ids=new ArrayList<>();
        for(int i=0;i<v.keypoints.length;i++){
            KeyPoint k=v.keypoints[i];double x=k.pt.x,y=k.pt.y,radius=k.size*.71;
            boolean inside=x>=box[0]&&x<=box[2]&&y>=box[1]&&y<=box[3];
            boolean safe=inside?x-radius>=box[0]&&x+radius<=box[2]&&y-radius>=box[1]&&y+radius<=box[3]
                    :x+radius<box[0]||x-radius>box[2]||y+radius<box[1]||y-radius>box[3];
            if(safe){keys.add(k);ids.add(i);}
        }
        Mat rows=ids.isEmpty()?new Mat():new Mat(ids.size(),v.descriptors.cols(),v.descriptors.type());
        if(!ids.isEmpty()){
            int stride=v.descriptors.cols()*(int)v.descriptors.elemSize();byte[] source=new byte[v.descriptors.rows()*stride],selected=new byte[ids.size()*stride];v.descriptors.get(0,0,source);
            for(int i=0;i<ids.size();i++)System.arraycopy(source,ids.get(i)*stride,selected,i*stride,stride);rows.put(0,0,selected);
        }
        return new AdminDescriptorBlobCodec.DescriptorVariant(v.photometricVariant,keys.toArray(new KeyPoint[0]),rows,true);
    }
}

