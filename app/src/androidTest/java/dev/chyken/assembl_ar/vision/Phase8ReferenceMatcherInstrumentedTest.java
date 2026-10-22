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
import dev.chyken.assembl_ar.camera.Phase8AssetCatalog;
import static org.junit.Assert.*;

/** Optional Android Studio native regression. Does not modify learner checkpoints or run a camera. */
@RunWith(AndroidJUnit4.class)
public final class Phase8ReferenceMatcherInstrumentedTest {
    @BeforeClass public static void initialize(){assertTrue(OpenCVLoader.initLocal());}
    @Test public void inwardInstallationsAreRejectedAtCameraScaleIncludingRotatedBoards()throws Exception{
        android.content.Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        List<ComponentReferenceSpec> all=Phase8AssetCatalog.references(app.getAssets()),inward=new ArrayList<>();
        for(ComponentReferenceSpec s:all){
            assertFalse("Mislabeled original is quarantined",s.getJpegAssetPath().endsWith("/DISTANCESENSORWRONGORIENTATIONTOPVIEW (15).jpg"));
            if(Phase8AssetCatalog.inwardReference(s.getJpegAssetPath()))inward.add(s);
        }
        assertTrue(inward.size()>=21);
        List<ComponentReferenceSpec> probes=Arrays.asList(inward.get(0),inward.get(inward.size()/2),inward.get(inward.size()-1));
        List<ComponentReferenceSpec> training=new ArrayList<>(all);training.removeAll(probes);VisionConfig config=VisionConfig.defaults();
        try(ReferenceDatabase db=ReferenceDatabase.load(app.getAssets(),training,config,new File(app.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase8ReferenceMatcher matcher=new Phase8ReferenceMatcher(db,config)){
            for(ComponentReferenceSpec probe:probes){
                MatOfByte bytes=new MatOfByte(read(app.getAssets(),probe.getJpegAssetPath()));Mat gray=Imgcodecs.imdecode(bytes,Imgcodecs.IMREAD_GRAYSCALE),camera=new Mat(),turned=new Mat();
                try{
                    double scale=640.0/Math.max(gray.cols(),gray.rows());Imgproc.resize(gray,camera,new Size(),scale,scale,Imgproc.INTER_AREA);
                    for(int turn:new int[]{-1,Core.ROTATE_180}){
                        if(turn<0)camera.copyTo(turned);else Core.rotate(camera,turned,turn);
                        Phase8Observation o=matcher.inspect(turned,100+turn,2).getPhase8Observation();
                        assertFalse(probe.getJpegAssetPath(),o.accepted(2));assertEquals("Need a specific turn-the-eyes-outward correction",Phase8Observation.State.INWARD,o.state);
                    }
                }finally{bytes.release();gray.release();camera.release();turned.release();}
            }
        }
    }
    /** Replays a private camera capture; does not change the learner's checkpoint. */
    @Test public void capturedInstalledSensorMatchesAtCameraScaleAndMissingSensorDoesNot()throws Exception{
        String path=InstrumentationRegistry.getArguments().getString("phase8Frame");Assume.assumeNotNull(path);
        android.content.Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file=new File(path).getCanonicalFile(),root=app.getExternalFilesDir("measure").getCanonicalFile();
        assertTrue(file.getPath().startsWith(root.getPath()+File.separator));
        Mat gray=Imgcodecs.imread(file.getPath(),Imgcodecs.IMREAD_GRAYSCALE);assertFalse(gray.empty());
        List<ComponentReferenceSpec> refs=new ArrayList<>();for(ComponentReferenceSpec s:Phase8AssetCatalog.references(app.getAssets()))
            if(Phase8AssetCatalog.installed(Phase8AssetCatalog.referenceRole(s.getReferenceId())))refs.add(s);
        VisionConfig config=VisionConfig.defaults();
        try(ReferenceDatabase db=ReferenceDatabase.load(app.getAssets(),refs,config,new File(app.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase8ReferenceMatcher matcher=new Phase8ReferenceMatcher(db,config)){
            for(int turn:new int[]{-1,Core.ROTATE_90_CLOCKWISE,Core.ROTATE_180}){
                Mat live=new Mat();try{
                    if(turn<0)gray.copyTo(live);else Core.rotate(gray,live,turn);
                    for(int i=0;i<2;i++){
                        RecognitionResult r=matcher.inspect(live,100+i,2);
                        android.util.Log.i("Phase8Replay","rotation="+turn+" state="+r.getPhase8Observation().state+" score="+r.getPhase8Observation().score+" ms="+r.getLatency().getTotalMs());
                        assertTrue("Actual installed sensor must unlock: rotation="+turn,r.getPhase8Observation().accepted(2));
                    }
                }finally{live.release();}
            }
            // The captured fixture is a 640x400 front-half board, with both cylinders here.
            assertEquals(640,gray.cols());assertEquals(400,gray.rows());
            Mat missing=gray.clone();try{
                Imgproc.rectangle(missing,new Point(200,20),new Point(510,180),new Scalar(220),-1);
                assertFalse("Unchanged board alone must not pass",matcher.inspect(missing,200,2).getPhase8Observation().accepted(2));
            }finally{missing.release();}
        }finally{gray.release();}
    }
    @Test public void heldOutSensorViewsRequireSensorEvidenceAndKeepRotationInvariance()throws Exception{
        AssetManager assets=InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets();List<ComponentReferenceSpec> all=Phase8AssetCatalog.references(assets);
        Map<String,ComponentReferenceSpec> probes=new LinkedHashMap<>();for(ComponentReferenceSpec spec:all)probes.putIfAbsent(Phase8AssetCatalog.referenceRole(spec.getReferenceId()),spec);assertEquals(4,probes.size());
        List<ComponentReferenceSpec> training=new ArrayList<>(all);training.removeAll(probes.values());VisionConfig config=VisionConfig.defaults();
        try(ReferenceDatabase database=ReferenceDatabase.load(assets,training,config);Phase8ReferenceMatcher matcher=new Phase8ReferenceMatcher(database,config)){
            assertEquals(training.size(),database.size());for(Map.Entry<String,ComponentReferenceSpec> probe:probes.entrySet()){
                MatOfByte encoded=new MatOfByte(read(assets,probe.getValue().getJpegAssetPath()));Mat gray=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_GRAYSCALE);
                try{for(int turn:new int[]{-1,Core.ROTATE_90_CLOCKWISE,Core.ROTATE_180}){Mat view=new Mat();try{
                    if(turn<0)gray.copyTo(view);else Core.rotate(gray,view,turn);int stage=Phase8AssetCatalog.installed(probe.getKey())?2:1;
                    RecognitionResult r=matcher.inspect(view,100,stage);Phase8Observation o=r.getPhase8Observation();assertNotNull(o);assertNull(r.getPose());String label=probe.getKey()+" rotation="+turn;
                    if(probe.getKey().equals("front")||probe.getKey().equals("assembly"))assertTrue(label,o.accepted(stage));else assertFalse("Negative photo must never unlock the step: "+label,o.accepted(stage));
                }finally{view.release();}}}finally{encoded.release();gray.release();}
            }
        }
    }
    private byte[] read(AssetManager assets,String path)throws IOException{try(InputStream in=assets.open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
}

