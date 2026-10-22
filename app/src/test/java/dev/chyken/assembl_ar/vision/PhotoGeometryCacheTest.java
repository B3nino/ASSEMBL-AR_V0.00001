package dev.chyken.assembl_ar.vision;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class PhotoGeometryCacheTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private ComponentReferenceSpec source(){return ComponentReferenceSpec.builder("p8scene_front_x","sensor","Sensor",45,20).assemblyStepNumber(8).jpegAssetPath("photo.jpg").build();}
    private ComponentReferenceSpec resolved(){return ComponentReferenceSpec.builder("p8scene_front_x","sensor","Sensor",45,20).assemblyStepNumber(8).maximumImageDimension(1280).jpegAssetPath("photo.jpg").planarReferenceCornersNormalized(new VisionPoint(.2,.2),new VisionPoint(.8,.2),new VisionPoint(.8,.8),new VisionPoint(.2,.8)).featureRegionsNormalized(Collections.singletonList(Arrays.asList(new VisionPoint(.1,.1),new VisionPoint(.9,.1),new VisionPoint(.9,.9),new VisionPoint(.1,.9)))).polarityAnchorsVerified(false).build();}
    @Test public void warmCacheRetainsGeometryWithoutInvokingPhotoDecoding()throws Exception{
        File descriptor=new File(temporary.getRoot(),"content-and-algorithm-key.orb");AtomicInteger calls=new AtomicInteger();
        ComponentReferenceSpec first=PhotoGeometryCache.resolve(source(),descriptor,8,45,20,()->{calls.incrementAndGet();return resolved();});
        ComponentReferenceSpec second=PhotoGeometryCache.resolve(source(),descriptor,8,45,20,()->{throw new AssertionError("Warm cache must not decode the JPEG");});
        assertEquals(1,calls.get());assertEquals(first.getPlanarCornersNormalized(),second.getPlanarCornersNormalized());assertEquals(first.getFeatureRegionsNormalized(),second.getFeatureRegionsNormalized());assertEquals(1280,second.getMaximumImageDimension());assertFalse(second.hasVerifiedPolarityAnchors());
    }
    @Test public void corruptGeometryFallsBackToTheSameSource()throws Exception{
        File descriptor=new File(temporary.getRoot(),"key.orb");Files.write(new File(descriptor.getPath()+".photo-geometry1").toPath(),new byte[]{1,2,3});AtomicInteger calls=new AtomicInteger();
        PhotoGeometryCache.resolve(source(),descriptor,8,45,20,()->{calls.incrementAndGet();return resolved();});assertEquals(1,calls.get());
    }
}
