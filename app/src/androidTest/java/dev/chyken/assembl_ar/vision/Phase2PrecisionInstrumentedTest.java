package dev.chyken.assembl_ar.vision;
import android.content.Context;import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;import org.junit.*;import org.junit.runner.RunWith;import org.opencv.android.OpenCVLoader;import org.opencv.core.*;import org.opencv.imgcodecs.Imgcodecs;import org.opencv.imgproc.Imgproc;import dev.chyken.assembl_ar.camera.*;import java.io.*;import java.util.*;import static org.junit.Assert.*;

/** Camera-scale regression: PCB-wide consensus alone must never satisfy motor installation. */
@RunWith(AndroidJUnit4.class) public final class Phase2PrecisionInstrumentedTest {
    @BeforeClass public static void nativeRuntime(){assertTrue(OpenCVLoader.initLocal());}
    @Test public void topReferenceRequiresBothHardwareRoisAndBareBoardNeverPasses()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();List<ComponentReferenceSpec> bank=new ArrayList<>();ComponentReferenceSpec positive=null,negative=null;
        for(ComponentReferenceSpec s:Phase2AssetCatalog.references(c.getAssets())){String role=Phase2AssetCatalog.referenceRole(s.getReferenceId());if("top".equals(role)&&bank.size()<3){bank.add(s);positive=s;}if("invalid".equals(role)&&s.getJpegAssetPath().contains("BARE_BOARD")){bank.add(s);negative=s;}}
        assertNotNull(positive);assertNotNull(negative);VisionConfig config=VisionConfig.defaults();
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),bank,config,new File(c.getCacheDir(),"phase2_precision_v6"));Phase2ReferenceMatcher matcher=new Phase2ReferenceMatcher(db,config)){
            for(ReferenceDatabase.PreparedReference r:db.preparedReferences())if("top".equals(Phase2AssetCatalog.referenceRole(r.spec.getReferenceId())))assertEquals("Two motor bodies + four independent bracket/shaft bands",6,r.spec.getFeatureRegionsNormalized().size());
            Mat bareRgb=photo(c,negative),completeRgb=photo(c,positive),bare=new Mat(),complete=new Mat(),turned=new Mat(),turnedRgb=new Mat(),dimRgb=new Mat(),dim=new Mat();
            try{
                Imgproc.cvtColor(bareRgb,bare,Imgproc.COLOR_RGB2GRAY);Imgproc.cvtColor(completeRgb,complete,Imgproc.COLOR_RGB2GRAY);
                assertFalse("Bare board must not unlock",matcher.inspect(bare,bareRgb,1,5,null).getPhase2Observation().referenceSupported);
                assertTrue("Completed hardware must retain the local material and geometric evidence",matcher.inspect(complete,completeRgb,2,5,null).getPhase2Observation().referenceSupported);
                Core.rotate(complete,turned,Core.ROTATE_180);Core.rotate(completeRgb,turnedRgb,Core.ROTATE_180);
                assertTrue("Motor evidence must survive rotation",matcher.inspect(turned,turnedRgb,3,5,null).getPhase2Observation().referenceSupported);
                completeRgb.convertTo(dimRgb,-1,.55,8);Imgproc.cvtColor(dimRgb,dim,Imgproc.COLOR_RGB2GRAY);
                DiagnosticHammingMatcher.begin();boolean recognized=false;
                for(boolean recovery:new boolean[]{false,true}){DiagnosticHammingMatcher.recovery(recovery);recognized|=matcher.inspect(dim,dimRgb,4,5,null).getPhase2Observation().referenceSupported;}
                assertTrue("A dim valid assembly must retain an available matching path",recognized);
                // Removing either motor's live material cannot be rescued by PCB features.
                for(int index:new int[]{0,3}){
                    Mat missingRgb=completeRgb.clone(),missing=new Mat();
                    try{ReferenceDatabase.PreparedReference ref=null;for(ReferenceDatabase.PreparedReference r:db.preparedReferences())if(r.spec.getReferenceId().equals(positive.getReferenceId()))ref=r;
                        assertNotNull(ref);List<VisionPoint> region=ref.spec.getFeatureRegionsNormalized().get(index);
                        Point l=new Point(region.get(0).getX()*640,region.get(0).getY()*400),r=new Point(region.get(2).getX()*640,region.get(2).getY()*400);
                        Imgproc.rectangle(missingRgb,l,r,new Scalar(140,140,140),-1);Imgproc.cvtColor(missingRgb,missing,Imgproc.COLOR_RGB2GRAY);
                        for(boolean recovery:new boolean[]{false,true}){DiagnosticHammingMatcher.recovery(recovery);assertFalse("Missing motor region "+index,matcher.inspect(missing,missingRgb,5,5,null).getPhase2Observation().referenceSupported);}
                    }finally{missingRgb.release();missing.release();}
                }
            }finally{DiagnosticHammingMatcher.end();bareRgb.release();completeRgb.release();bare.release();complete.release();turned.release();turnedRgb.release();dimRgb.release();dim.release();}

        }
    }
    @Test public void boundedLiveSearchHandlesFullCatalogWithoutOneLongSweep()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();List<ComponentReferenceSpec> bank=new ArrayList<>();Map<String,ComponentReferenceSpec> probes=new LinkedHashMap<>();
        for(ComponentReferenceSpec spec:Phase2AssetCatalog.references(c.getAssets())){
            String role=Phase2AssetCatalog.referenceRole(spec.getReferenceId());if(Arrays.asList("top","left","right","invalid").contains(role))bank.add(spec);
            if(Arrays.asList("top","left","right").contains(role))probes.putIfAbsent(role,spec);
            if(spec.getJpegAssetPath().contains("BARE_BOARD_INVALID"))probes.putIfAbsent("bare",spec);
        }
        VisionConfig config=VisionConfig.defaults();
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),bank,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase2ReferenceMatcher matcher=new Phase2ReferenceMatcher(db,config)){
            for(Map.Entry<String,ComponentReferenceSpec> probe:probes.entrySet()){
                Mat rgb=cameraPhoto(c,probe.getValue()),gray=new Mat();Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
                try{boolean bare=probe.getKey().equals("bare"),matched=false;long longest=0;
                    for(int frame=0;frame<(bare?3:12);frame++){
                        DiagnosticHammingMatcher.begin();long started=android.os.SystemClock.elapsedRealtime();
                        RecognitionResult r=matcher.inspect(gray,rgb,frame+1,5,null,bare?"top":probe.getKey());long elapsed=android.os.SystemClock.elapsedRealtime()-started;
                        longest=Math.max(longest,elapsed);Phase2Observation o=r.getPhase2Observation();
                        android.util.Log.i("Phase2Bounded",probe.getKey()+" frame="+frame+" view="+o.view+" accepted="+o.referenceSupported+" ms="+elapsed);
                        if(bare)assertFalse(o.referenceSupported);else if(o.referenceSupported&&probe.getKey().equals(o.view)){matched=true;break;}
                    }
                    assertTrue("No 30-second full-bank sweep; "+probe.getKey()+" longest="+longest,longest<2000);
                    if(!bare&&!matched){RecognitionResult exhaustive=matcher.inspect(gray,rgb,99,5,null);android.util.Log.i("Phase2Bounded","exhaustive "+probe.getKey()+" view="+exhaustive.getPhase2Observation().view+" accepted="+exhaustive.getPhase2Observation().referenceSupported);}
                    if(!bare)assertTrue("The frame budget must retain "+probe.getKey()+" recognition",matched);
                }finally{DiagnosticHammingMatcher.end();rgb.release();gray.release();}
            }
        }
    }
    /** Preserve physical geometry when fitting portrait reference photos to the tablet frame. */
    private static Mat cameraPhoto(Context c,ComponentReferenceSpec s)throws Exception{
        byte[] data;try(InputStream in=c.getAssets().open(s.getJpegAssetPath())){data=in.readAllBytes();}
        MatOfByte bytes=new MatOfByte(data);Mat decoded=Imgcodecs.imdecode(bytes,Imgcodecs.IMREAD_COLOR),scaled=new Mat();
        Mat frame=new Mat(400,640,CvType.CV_8UC3,new Scalar(180,180,180));
        try{double scale=Math.min(640.0/decoded.cols(),400.0/decoded.rows());Imgproc.resize(decoded,scaled,new Size(),scale,scale,Imgproc.INTER_AREA);
            Mat crop=frame.submat(new Rect((640-scaled.cols())/2,(400-scaled.rows())/2,scaled.cols(),scaled.rows()));try{scaled.copyTo(crop);}finally{crop.release();}
            Imgproc.cvtColor(frame,frame,Imgproc.COLOR_BGR2RGB);return frame;
        }finally{bytes.release();decoded.release();scaled.release();}
    }
    private static Mat photo(Context c,ComponentReferenceSpec s)throws Exception{byte[] data;try(InputStream in=c.getAssets().open(s.getJpegAssetPath());ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);data=out.toByteArray();}MatOfByte bytes=new MatOfByte(data);try{Mat rgb=Imgcodecs.imdecode(bytes,Imgcodecs.IMREAD_COLOR);Imgproc.resize(rgb,rgb,new Size(640,400),0,0,Imgproc.INTER_AREA);Imgproc.cvtColor(rgb,rgb,Imgproc.COLOR_BGR2RGB);return rgb;}finally{bytes.release();}}
}
