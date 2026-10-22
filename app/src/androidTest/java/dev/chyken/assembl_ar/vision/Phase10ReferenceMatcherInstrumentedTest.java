package dev.chyken.assembl_ar.vision;

import android.content.Context;
import android.content.res.AssetManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.chyken.assembl_ar.camera.Phase10AssetCatalog;
import org.junit.*;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Native regression fixtures; no learner progress, camera binding or installs are modified. */
@RunWith(AndroidJUnit4.class)
public final class Phase10ReferenceMatcherInstrumentedTest {
    @BeforeClass public static void initialize(){assertTrue(OpenCVLoader.initLocal());}
    @Test public void everyPhase10BankPreparesAndHeldOutViewsRemainRecognizable()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();AssetManager assets=app.getAssets();List<ComponentReferenceSpec> catalog=Phase10AssetCatalog.references(assets);VisionConfig config=VisionConfig.defaults();
        for(int stage=1;stage<=5;stage++){
            List<ComponentReferenceSpec> bank=new ArrayList<>();for(ComponentReferenceSpec s:catalog)if(Phase10AssetCatalog.eligible(Phase10AssetCatalog.referenceRole(s.getReferenceId()),stage))bank.add(s);
            assertTrue("Phase 10 scan bank "+stage,bank.size()>1);ComponentReferenceSpec probe=bank.remove(0);
            try(ReferenceDatabase db=ReferenceDatabase.load(assets,bank,config,new File(app.getCacheDir(),"p10_native_reference_test"));Phase10ReferenceMatcher matcher=new Phase10ReferenceMatcher(db,config)){
                assertEquals("Every source photo has valid localized geometry",bank.size(),db.size());Mat photo=read(assets,probe.getJpegAssetPath()),small=new Mat(),rotated=new Mat();
                try{double scale=Math.min(1,920.0/Math.max(photo.cols(),photo.rows()));Imgproc.resize(photo,small,new Size(),scale,scale,Imgproc.INTER_AREA);Core.rotate(small,rotated,Core.ROTATE_90_CLOCKWISE);boolean recognized=false;
                    for(int frame=0;frame<bank.size()*2;frame++){RecognitionResult result=matcher.inspect(rotated,frame+1,stage);assertNull(result.getPose());if(result.getPhase10Observation().accepted()){recognized=true;break;}}
                    assertTrue("Held-out rotated view stage "+stage,recognized);
                }finally{photo.release();small.release();rotated.release();}
                Mat blank=new Mat(640,960,CvType.CV_8UC1,new Scalar(200));try{assertFalse(matcher.inspect(blank,1000,stage).getPhase10Observation().accepted());}finally{blank.release();}
            }
        }
    }
    @Test public void boardPrintingThroughProtectorOpeningCannotVerifyMissingProtector()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();AssetManager assets=app.getAssets();VisionConfig config=VisionConfig.defaults();
        for(int stage:new int[]{4,5}){
            List<ComponentReferenceSpec> bank=new ArrayList<>();for(ComponentReferenceSpec s:Phase10AssetCatalog.references(assets))if(Phase10AssetCatalog.eligible(Phase10AssetCatalog.referenceRole(s.getReferenceId()),stage))bank.add(s);
            try(ReferenceDatabase db=ReferenceDatabase.load(assets,bank,config,new File(app.getCacheDir(),"p10_native_reference_test"));Phase10ReferenceMatcher matcher=new Phase10ReferenceMatcher(db,config)){
                for(ReferenceDatabase.PreparedReference ref:db.preparedReferences()){
                    Mat photo=read(assets,ref.spec.getJpegAssetPath());try{List<VisionPoint> roi=ref.spec.getFeatureRegionsNormalized().get(1);Point a=new Point(roi.get(0).getX()*photo.cols(),roi.get(0).getY()*photo.rows()),b=new Point(roi.get(2).getX()*photo.cols(),roi.get(2).getY()*photo.rows());Imgproc.rectangle(photo,a,b,new Scalar(200),-1);
                        for(int frame=0;frame<bank.size();frame++)assertFalse("Missing protector "+ref.spec.getReferenceId(),matcher.inspect(photo,2000+frame,stage).getPhase10Observation().accepted());
                    }finally{photo.release();}
                }
            }
        }
    }
    private static Mat read(AssetManager assets,String path)throws IOException{try(InputStream in=assets.open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);MatOfByte encoded=new MatOfByte(out.toByteArray());try{return Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_GRAYSCALE);}finally{encoded.release();}}}
}
