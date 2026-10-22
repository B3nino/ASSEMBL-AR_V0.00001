package dev.chyken.assembl_ar.vision;

import java.io.File;
import java.util.*;
import java.util.function.Supplier;

/** Versioned photo ROI metadata avoids decoding a large JPEG before reading a warm ORB cache. */
final class PhotoGeometryCache {
    private PhotoGeometryCache(){}
    static ComponentReferenceSpec resolve(ComponentReferenceSpec source,File descriptorCache,int phase,double width,double height,Supplier<ComponentReferenceSpec> rebuild){
        File sidecar=descriptorCache==null?null:new File(descriptorCache.getPath()+".photo-geometry1");
        List<List<VisionPoint>> saved=ReferenceRegionCache.read(sidecar);
        if(saved!=null&&saved.size()>=2&&saved.get(0).size()==4)try{
            List<VisionPoint> p=saved.get(0);
            return ComponentReferenceSpec.builder(source.getReferenceId(),source.getComponentId(),source.getDisplayName(),width,height)
                .jpegAssetPath(source.getJpegAssetPath()).assemblyStepNumber(phase).maximumImageDimension(1280)
                .planarReferenceCornersNormalized(p.get(0),p.get(1),p.get(2),p.get(3)).featureRegionsNormalized(saved.subList(1,saved.size())).polarityAnchorsVerified(false).build();
        }catch(IllegalArgumentException corrupt){/* Recompute the exact source geometry. */}
        ComponentReferenceSpec result=rebuild.get();List<List<VisionPoint>> geometry=new ArrayList<>();geometry.add(result.getPlanarCornersNormalized());geometry.addAll(result.getFeatureRegionsNormalized());ReferenceRegionCache.write(sidecar,geometry);return result;
    }
}
