package dev.chyken.assembl_ar.vision;

import android.content.res.AssetManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import java.io.*;
import java.util.*;
import dev.chyken.assembl_ar.camera.Phase5AssetCatalog;
import static org.junit.Assert.*;

/** Native Android regression; uses held-out photos and never changes assembly progress. */
@RunWith(AndroidJUnit4.class)
public final class Phase5ReferenceMatcherInstrumentedTest {
    @BeforeClass public static void initialize(){assertTrue(OpenCVLoader.initLocal());}
    @Test public void registeredPhotosPrepareAndHeldOutViewsKeepTheirIdentityAcrossRotations()throws Exception{
        AssetManager assets=InstrumentationRegistry.getInstrumentation().getTargetContext().getAssets();
        List<ComponentReferenceSpec> specs=Phase5AssetCatalog.references(assets);
        Map<String,ComponentReferenceSpec> probes=new LinkedHashMap<>();
        for(ComponentReferenceSpec spec:specs)probes.putIfAbsent(Phase5AssetCatalog.referenceRole(spec.getReferenceId()),spec);
        assertEquals(new HashSet<>(Arrays.asList("front","back","assembly","invalid")),probes.keySet());
        List<ComponentReferenceSpec> training=new ArrayList<>(specs);training.removeAll(probes.values());
        VisionConfig config=VisionConfig.defaults();
        try(ReferenceDatabase database=ReferenceDatabase.load(assets,training,config);Phase5ReferenceMatcher matcher=new Phase5ReferenceMatcher(database,config)){
            assertEquals("Every training image must prepare successfully, including glare photos",training.size(),database.size());
            for(Map.Entry<String,ComponentReferenceSpec> probe:probes.entrySet()){
                MatOfByte bytes=new MatOfByte(read(assets,probe.getValue().getJpegAssetPath()));Mat gray=Imgcodecs.imdecode(bytes,Imgcodecs.IMREAD_GRAYSCALE);
                try{
                    for(int rotation:new int[]{-1,Core.ROTATE_90_CLOCKWISE,Core.ROTATE_180}){
                        Mat rotated=new Mat();try{
                            if(rotation<0)gray.copyTo(rotated);else Core.rotate(gray,rotated,rotation);
                            int step=Phase5AssetCatalog.installed(probe.getKey())?3:1;
                            RecognitionResult result=matcher.inspect(rotated,100,step);Phase5Observation observation=result.getPhase5Observation();assertNotNull(observation);assertNull(result.getPose());
                            String label=probe.getValue().getJpegAssetPath()+" rotation="+rotation;
                            if(probe.getKey().equals("front"))assertEquals(label,Phase5Observation.State.FRONT,observation.state);
                            else if(probe.getKey().equals("back"))assertEquals(label,Phase5Observation.State.FLIPPED,observation.state);
                            else if(probe.getKey().equals("assembly"))assertTrue(label,observation.accepted(3));
                            else assertTrue(label,observation.state==Phase5Observation.State.BACKWARD||observation.state==Phase5Observation.State.MISALIGNED);
                        }finally{rotated.release();}
                    }
                }finally{bytes.release();gray.release();}
            }
        }
    }
    private static byte[] read(AssetManager assets,String path)throws IOException{
        try(InputStream input=assets.open(path);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);return bytes.toByteArray();
        }
    }
}
