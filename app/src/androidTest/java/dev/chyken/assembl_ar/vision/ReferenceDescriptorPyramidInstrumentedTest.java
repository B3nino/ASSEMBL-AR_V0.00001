package dev.chyken.assembl_ar.vision;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.CLAHE;
import java.util.*;
import dev.chyken.assembl_ar.camera.*;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import static org.junit.Assert.*;

/** Native preparation regressions only; never opens a camera or changes assembly progress. */
@RunWith(AndroidJUnit4.class)
public final class ReferenceDescriptorPyramidInstrumentedTest {
    @BeforeClass public static void initialize(){assertTrue(OpenCVLoader.initLocal());}

    @Test public void sourceSamplingIsRetainedAndAdditionalLevelsSurviveBlobRoundTrip(){
        VisionConfig config=VisionConfig.defaults();ORB orb=OpenCvVisionUtils.createOrb(config);CLAHE clahe=OpenCvVisionUtils.createClahe(config);
        Mat image=new Mat(960,1280,CvType.CV_8UC1),mask=new Mat(); // Administrator whole-image path.
        List<AdminDescriptorBlobCodec.DescriptorVariant> original=Collections.emptyList(),pooled=Collections.emptyList();
        try{
            Core.setRNGSeed(8128);Core.randu(image,0,255);
            original=AdminDescriptorBlobCodec.computeVariants(image,mask,orb,clahe);
            pooled=ReferenceDescriptorPyramid.compute(image,mask,orb,clahe);
            assertEquals(2,pooled.size());
            for(AdminDescriptorBlobCodec.DescriptorVariant base:original){
                AdminDescriptorBlobCodec.DescriptorVariant expanded=null;for(AdminDescriptorBlobCodec.DescriptorVariant v:pooled)if(v.photometricVariant==base.photometricVariant)expanded=v;
                assertNotNull(expanded);assertTrue(expanded.keypoints.length>base.keypoints.length);
                assertTrue(expanded.keypoints.length<=3*orb.getMaxFeatures());
                Set<String> rows=signatures(expanded);
                assertTrue("Every original descriptor and its source coordinates survive",rows.containsAll(signatures(base)));
            }
            byte[] encoded=AdminDescriptorBlobCodec.encode(image.cols(),image.rows(),pooled,config);
            try(AdminDescriptorBlobCodec.DecodedDescriptorBlob decoded=AdminDescriptorBlobCodec.decode(encoded,config)){
                assertEquals(image.cols(),decoded.imageWidth);assertEquals(image.rows(),decoded.imageHeight);
                for(int i=0;i<pooled.size();i++)assertEquals(signatures(pooled.get(i)),signatures(decoded.variants.get(i)));
            }
        }finally{for(AdminDescriptorBlobCodec.DescriptorVariant v:original)v.release();for(AdminDescriptorBlobCodec.DescriptorVariant v:pooled)v.release();image.release();mask.release();orb.clear();clahe.clear();}
    }

    @Test public void allCameraPhasesUseSharedPreparationWithValidSourceGeometry()throws Exception{
        android.content.res.AssetManager assets=InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets();
        List<ComponentReferenceSpec> samples=Arrays.asList(
            ReviewedIndividualReferenceCatalog.forComponent(NeobotSeedData.MAIN_BOARD,assets).get(0),
            Phase2AssetCatalog.references(assets).get(0),Phase3AssetCatalog.references(assets).get(0),
            Phase5AssetCatalog.references(assets).get(0),Phase7AssetCatalog.references(assets).get(0),Phase8AssetCatalog.references(assets).get(0));
        try(ReferenceDatabase db=ReferenceDatabase.load(assets,samples,VisionConfig.defaults())){
            assertEquals(samples.size(),db.size());
            for(ReferenceDatabase.PreparedReference r:db.preparedReferences())for(ReferenceDatabase.PreparedOrbVariant v:r.orbVariants){
                assertEquals(v.keypoints.length,v.descriptors.rows());assertTrue(v.hasOrbData());
                for(KeyPoint k:v.keypoints){assertTrue(k.pt.x>=0&&k.pt.x<r.imageWidth);assertTrue(k.pt.y>=0&&k.pt.y<r.imageHeight);}
            }
        }
        // Phases 4 and 6 are wiring/checklist flows. They use this same database whenever
        // a component inspection is requested, without introducing a new scan gate.
    }

    @Test public void extraDescriptorSupportCannotMixTheComponentWithItsBoard(){
        VisionConfig config=VisionConfig.defaults();ORB orb=OpenCvVisionUtils.createOrb(config);CLAHE clahe=OpenCvVisionUtils.createClahe(config);
        Mat image=new Mat(960,1280,CvType.CV_8UC1),mask=new Mat();
        List<AdminDescriptorBlobCodec.DescriptorVariant> original=Collections.emptyList(),expanded=Collections.emptyList();
        try{
            Core.setRNGSeed(481);Core.randu(image,0,255);
            ComponentReferenceSpec spec=ComponentReferenceSpec.builder("future_component","sample","Sample",30,40)
                .planarReferenceCornersNormalized(new VisionPoint(.3,.3),new VisionPoint(.7,.3),new VisionPoint(.7,.7),new VisionPoint(.3,.7)).build();
            original=AdminDescriptorBlobCodec.computeVariants(image,mask,orb,clahe);
            expanded=ReferenceDescriptorPyramid.forReference(spec,image,mask,orb,clahe);
            for(AdminDescriptorBlobCodec.DescriptorVariant v:expanded){
                AdminDescriptorBlobCodec.DescriptorVariant base=null;for(AdminDescriptorBlobCodec.DescriptorVariant b:original)if(b.photometricVariant==v.photometricVariant)base=b;
                Set<String> originals=signatures(base);assertTrue(signatures(v).containsAll(originals));byte[] bytes=new byte[v.descriptors.rows()*32];v.descriptors.get(0,0,bytes);
                for(int i=0;i<v.keypoints.length;i++){
                    KeyPoint k=v.keypoints[i];if(originals.contains(signature(k,bytes,i)))continue;
                    double x=k.pt.x,y=k.pt.y,r=k.size*.71,l=384,t=288,right=896,bottom=672;
                    boolean inside=x>=l&&x<=right&&y>=t&&y<=bottom;
                    assertTrue("An added coarse patch cannot certify a neighbouring surface",inside?x-r>=l&&x+r<=right&&y-r>=t&&y+r<=bottom:x+r<l||x-r>right||y+r<t||y-r>bottom);
                }
            }
        }finally{for(AdminDescriptorBlobCodec.DescriptorVariant v:original)v.release();for(AdminDescriptorBlobCodec.DescriptorVariant v:expanded)v.release();image.release();mask.release();orb.clear();clahe.clear();}
    }

    private static Set<String> signatures(AdminDescriptorBlobCodec.DescriptorVariant v){
        Set<String> result=new HashSet<>();byte[] bytes=new byte[v.descriptors.rows()*32];v.descriptors.get(0,0,bytes);
        for(int i=0;i<v.keypoints.length;i++)result.add(signature(v.keypoints[i],bytes,i));
        return result;
    }
    private static String signature(KeyPoint k,byte[] bytes,int row){return Float.floatToIntBits((float)k.pt.x)+","+Float.floatToIntBits((float)k.pt.y)+":"+Arrays.toString(Arrays.copyOfRange(bytes,row*32,(row+1)*32));}
}
