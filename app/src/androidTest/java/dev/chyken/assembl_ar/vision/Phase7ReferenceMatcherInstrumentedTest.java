package dev.chyken.assembl_ar.vision;

import android.content.res.AssetManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.io.*;
import java.util.*;
import dev.chyken.assembl_ar.camera.Phase7AssetCatalog;
import static org.junit.Assert.*;

/** Native-photo regression without modifying learner progress or using the camera. */
@RunWith(AndroidJUnit4.class)
public final class Phase7ReferenceMatcherInstrumentedTest {
    @BeforeClass public static void initialize(){assertTrue(OpenCVLoader.initLocal());}
    @Test public void identicalModulesInOneVgaFrameStillUnlockTheInstalledPairScan()throws Exception{
        AssetManager assets=InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets();
        List<ComponentReferenceSpec> all=Phase7AssetCatalog.references(assets);
        ComponentReferenceSpec probe=null;
        for(ComponentReferenceSpec spec:all)
            if("trackerwork".equals(Phase7AssetCatalog.referenceRole(spec.getReferenceId())))probe=spec;
        assertNotNull(probe);
        List<ComponentReferenceSpec> training=trackerReferences(all);training.remove(probe);
        byte[] photo=read(assets,probe.getJpegAssetPath());
        ComponentReferenceSpec geometry=Phase7PhotoGeometry.resolve(probe,photo);
        MatOfByte encoded=new MatOfByte(photo);Mat bgr=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_COLOR);
        VisionConfig config=VisionConfig.defaults();
        try(ReferenceDatabase database=ReferenceDatabase.load(assets,training,config,descriptorCache());
            Phase7ReferenceMatcher matcher=new Phase7ReferenceMatcher(database,config)){
            List<List<VisionPoint>> regions=geometry.getFeatureRegionsNormalized();
            Rect first=rectangle(regions.get(0),bgr),second=rectangle(regions.get(1),bgr);
            Mat source=bgr.submat(first),destination=bgr.submat(second),copy=source.clone();
            try{Imgproc.resize(copy,destination,new Size(second.width,second.height),0,0,Imgproc.INTER_LINEAR);}
            finally{source.release();destination.release();copy.release();}
            Mat camera=new Mat(),blurred=new Mat(),rotated=new Mat();
            try{
                Imgproc.resize(bgr,camera,new Size(640,Math.round(bgr.rows()*640.0/bgr.cols())),0,0,Imgproc.INTER_AREA);
                assertAcceptedOverFreshFrames(matcher,camera,true);
                Imgproc.GaussianBlur(camera,blurred,new Size(3,3),.7);
                assertAcceptedOverFreshFrames(matcher,blurred,true);
                Core.rotate(camera,rotated,Core.ROTATE_90_CLOCKWISE);
                assertAcceptedOverFreshFrames(matcher,rotated,true);
                assertAcceptedOverFreshFrames(matcher,camera,false); // Color is optional.
                // Erase a real module, leaving the board and the other module intact.
                Rect missing=rectangle(regions.get(1),camera);
                Imgproc.rectangle(camera,missing.tl(),missing.br(),new Scalar(220,220,220),-1);
                Mat rgb=new Mat(),gray=new Mat();
                try{
                    Imgproc.cvtColor(camera,rgb,Imgproc.COLOR_BGR2RGB);Imgproc.cvtColor(camera,gray,Imgproc.COLOR_BGR2GRAY);
                    for(int i=0;i<8;i++)assertFalse("One module must never count twice",matcher.inspect(gray,rgb,200+i,4).getPhase7Observation().accepted(4));
                }finally{rgb.release();gray.release();}
            }finally{camera.release();blurred.release();rotated.release();}
        }finally{encoded.release();bgr.release();}
    }
    /** Optional real-camera replay: the private diagnostic image is never bundled in the APK. */
    @Test public void capturedTabletPairMustMatchAndSingleModuleMustNot()throws Exception {
        String path=InstrumentationRegistry.getArguments().getString("phase7Frame");
        Assume.assumeNotNull(path);
        File file=new File(path).getCanonicalFile();
        File measure=InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir("measure").getCanonicalFile();
        assertTrue("Only the app's diagnostic frames may be replayed",file.getPath().startsWith(measure.getPath()+File.separator));
        AssetManager assets=InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets();
        VisionConfig config=VisionConfig.defaults();Mat bgr=Imgcodecs.imread(file.getPath());
        assertFalse("Captured frame must exist",bgr.empty());
        try(ReferenceDatabase database=ReferenceDatabase.load(assets,trackerReferences(Phase7AssetCatalog.references(assets)),config,descriptorCache());
            Phase7ReferenceMatcher matcher=new Phase7ReferenceMatcher(database,config)) {
            assertAcceptedOverFreshFrames(matcher,bgr,true);
            assertAcceptedOverFreshFrames(matcher,bgr,false);
            // Bounds belong to this 640x400 diagnostic fixture, not the production recognizer.
            assertEquals(640,bgr.cols());assertEquals(400,bgr.rows());
            for(Rect erased:new Rect[]{new Rect(85,70,135,240),new Rect(430,65,155,280)}) {
                Mat single=bgr.clone(),rgb=new Mat(),gray=new Mat();
                try {
                    Imgproc.rectangle(single,erased.tl(),erased.br(),new Scalar(220,220,220),-1);
                    Imgproc.cvtColor(single,rgb,Imgproc.COLOR_BGR2RGB);Imgproc.cvtColor(single,gray,Imgproc.COLOR_BGR2GRAY);
                    for(int i=0;i<8;i++)assertFalse("A missing tracker must not pass",matcher.inspect(gray,rgb,300+i,4).getPhase7Observation().accepted(4));
                }finally{single.release();rgb.release();gray.release();}
            }
        }finally{bgr.release();}
    }
    private static List<ComponentReferenceSpec> trackerReferences(List<ComponentReferenceSpec> all) {
        List<ComponentReferenceSpec> result=new ArrayList<>();
        for(ComponentReferenceSpec spec:all) {
            String role=Phase7AssetCatalog.referenceRole(spec.getReferenceId());
            if("trackerwork".equals(role)||"trackers".equals(role))result.add(spec);
        }
        return result;
    }
    private static File descriptorCache(){return new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getNoBackupFilesDir(),"orb_reference_cache_v3");}
    private static Rect rectangle(List<VisionPoint> region,Mat image){
        int l=Math.max(0,(int)(region.get(0).getX()*image.cols())),t=Math.max(0,(int)(region.get(0).getY()*image.rows()));
        int r=Math.min(image.cols(),(int)(region.get(2).getX()*image.cols())),b=Math.min(image.rows(),(int)(region.get(2).getY()*image.rows()));
        return new Rect(l,t,r-l,b-t);
    }
    private static void assertAcceptedOverFreshFrames(Phase7ReferenceMatcher matcher,Mat bgr,boolean color){
        Mat rgb=new Mat(),gray=new Mat();
        try{
            Imgproc.cvtColor(bgr,rgb,Imgproc.COLOR_BGR2RGB);Imgproc.cvtColor(bgr,gray,Imgproc.COLOR_BGR2GRAY);
            Phase7Observation found=null;
            // Bounded native searches continue through all reference angles over fresh frames.
            for(int i=0;i<12;i++){
                found=matcher.inspect(gray,color?rgb:null,100+i,4).getPhase7Observation();
                android.util.Log.i("Phase7Replay","frame="+(i+1)+" color="+color+" bodies="+found.visibleTrackers+" mounts="+found.verifiedMounts+" caster="+found.casterPresent+" accepted="+found.accepted(4));
                if(found.accepted(4))break;
            }
            assertTrue("Both identical modules need independent local descriptor pools",found!=null&&found.accepted(4));
            assertEquals(2,found.visibleTrackers);assertEquals(2,found.verifiedMounts);
        }finally{rgb.release();gray.release();}
    }
    @Test public void heldOutComponentsAndBottomAssembliesMatchButMissingPartsNeverPass()throws Exception{
        AssetManager assets=InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets();
        List<ComponentReferenceSpec> specs=Phase7AssetCatalog.references(assets);
        Map<String,ComponentReferenceSpec> probes=new LinkedHashMap<>();
        for(ComponentReferenceSpec s:specs){String relative=s.getJpegAssetPath().substring(Phase7AssetCatalog.ROOT.length()+1);probes.putIfAbsent(relative.split("/")[0],s);}
        assertTrue(specs.size()>=153);assertEquals(9,probes.size());
        List<ComponentReferenceSpec> training=new ArrayList<>(specs);training.removeAll(probes.values());
        VisionConfig config=VisionConfig.defaults();
        try(ReferenceDatabase database=ReferenceDatabase.load(assets,training,config);Phase7ReferenceMatcher matcher=new Phase7ReferenceMatcher(database,config)){
            assertEquals(training.size(),database.size());
            for(ComponentReferenceSpec probe:probes.values()){
                String role=Phase7AssetCatalog.referenceRole(probe.getReferenceId());boolean installed=Phase7AssetCatalog.installed(role),bad=Phase7AssetCatalog.negative(role);
                int step=role.startsWith("casterwork")?3:role.startsWith("trackerwork")?4:role.startsWith("caster")?1:2;
                byte[] photo=read(assets,probe.getJpegAssetPath());MatOfByte encoded=new MatOfByte(photo);Mat gray=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_GRAYSCALE);
                try{
                    for(int rotation:new int[]{-1,Core.ROTATE_90_CLOCKWISE,Core.ROTATE_180}){
                        Mat live=new Mat();try{
                            if(rotation<0)gray.copyTo(live);else Core.rotate(gray,live,rotation);
                            RecognitionResult result=inspectPhoto(matcher,live,100,step);Phase7Observation found=result.getPhase7Observation();
                            assertNotNull(found);assertNull(result.getPose());
                            boolean pair=probe.getJpegAssetPath().contains("STEP7_2");boolean expected=!bad&&(step!=2||pair);
                            assertEquals(probe.getJpegAssetPath()+" rotation="+rotation,expected,found.accepted(step));
                            if(!bad&&step==2&&!pair)assertEquals("One IR module is never counted twice",1,found.count);
                        }finally{live.release();}
                    }
                    if(step==4&&!bad){
                        Mat front=gray.submat((int)(gray.rows()*.06),(int)(gray.rows()*.49),(int)(gray.cols()*.24),(int)(gray.cols()*.87)),turned=new Mat();
                        try{assertTrue("The whole board is not required",inspectPhoto(matcher,front,102,4).getPhase7Observation().accepted(4));Core.rotate(front,turned,Core.ROTATE_90_CLOCKWISE);assertTrue(inspectPhoto(matcher,turned,103,4).getPhase7Observation().accepted(4));}finally{front.release();turned.release();}
                    }
                    if(installed&&!bad){
                        ComponentReferenceSpec geometry=Phase7PhotoGeometry.resolve(probe,photo);
                        int regionIndex=0;for(List<VisionPoint> region:geometry.getFeatureRegionsNormalized()){
                            if(step==4&&regionIndex>=4)break;int part=regionIndex++;
                            Mat missing=gray.clone();try{
                                VisionPoint l=region.get(0),r=region.get(2);
                                Imgproc.rectangle(missing,new Point(l.getX()*gray.cols(),l.getY()*gray.rows()),new Point(r.getX()*gray.cols(),r.getY()*gray.rows()),new Scalar(220),-1);
                                Phase7Observation check=inspectPhoto(matcher,missing,101,step).getPhase7Observation();
                                if(step==4&&part>=2){assertTrue("Located trackers still lead to explicit hardware confirmation",check.accepted(4));assertTrue("An erased nut cannot be image-confirmed",check.imageConfirmedNuts<2);}
                                else assertFalse("Each required component needs local evidence: "+probe.getJpegAssetPath(),check.accepted(step));
                            }finally{missing.release();}
                        }
                    }
                }finally{encoded.release();gray.release();}
            }
        }
    }
    private static RecognitionResult inspectPhoto(Phase7ReferenceMatcher matcher,Mat gray,long timestamp,int step){
        RecognitionResult result=null;
        for(int i=0;i<(step==4?12:1);i++){
            result=matcher.inspect(gray,timestamp+i,step);
            if(result.getPhase7Observation().accepted(step))break;
        }
        return result;
    }
    private static byte[] read(AssetManager assets,String path)throws IOException{
        try(InputStream input=assets.open(path);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);return bytes.toByteArray();
        }
    }
}
