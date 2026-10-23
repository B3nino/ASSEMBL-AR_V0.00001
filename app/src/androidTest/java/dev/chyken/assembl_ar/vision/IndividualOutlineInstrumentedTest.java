package dev.chyken.assembl_ar.vision;
import android.content.Context;import android.os.SystemClock;import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;
import dev.chyken.assembl_ar.camera.*;import dev.chyken.assembl_ar.data.AssemblArRepository;import org.junit.*;import org.junit.runner.RunWith;import org.opencv.android.OpenCVLoader;import org.opencv.core.*;import org.opencv.imgcodecs.Imgcodecs;
import java.io.*;import java.util.*;import static org.junit.Assert.*;

/** Replays the tablet's failing individual-screen preview against the full shared
 * standalone library. The preview crop is a regression fixture, not a raw-camera metric. */
@RunWith(AndroidJUnit4.class) public final class IndividualOutlineInstrumentedTest {
    @BeforeClass public static void runtime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    @Test public void fullStandaloneLibraryFindsCapturedBoardWithBoundedWork()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File file=new File(c.getExternalFilesDir(null),"outline-individual-preview.png");assertTrue("Stage the captured tablet preview",file.isFile());
        Mat scene=Imgcodecs.imread(file.getPath(),Imgcodecs.IMREAD_GRAYSCALE);assertFalse(scene.empty());
        List<ComponentReferenceSpec> specs=UnifiedReferenceCatalog.individual(VisionReferenceFactory.build(AssemblArRepository.getInstance(c),c.getAssets()));assertTrue(specs.size()>100);
        boolean located=false;double max=0;int attempts=0;RecognitionResult found=null;
        try(ReferenceDatabase db=ReferenceDatabase.loadIndividualBank(c.getAssets(),specs,"main_board",VisionConfig.defaults(),new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"),()->true);ComponentRecognizer recognizer=new ComponentRecognizer(VisionConfig.defaults(),db)){
            assertTrue("Only the live bank owns full reference records",db.size()<=8);
            for(int i=0;i<24&&!located;i++){
                DiagnosticHammingMatcher.begin();long now=SystemClock.elapsedRealtimeNanos();
                try{found=recognizer.recognize(scene,now,IndividualPartTestCatalog.requestFor("main_board",CameraCalibration.pinhole(700,700,scene.cols()/2.,scene.rows()/2.)));max=Math.max(max,(SystemClock.elapsedRealtimeNanos()-now)/1e6);attempts++;
                    located=found.hasLocatedComponent()&&"main_board".equals(found.getComponentId());
                }finally{DiagnosticHammingMatcher.end();}
            }
            assertTrue("The actual failing preview must produce measured board geometry",located);
            assertEquals(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,found.getDetectionMethod());
            try(TargetBoundingTracker tracker=new TargetBoundingTracker()){
                assertNotNull(tracker.detect(scene,1,"main_board","Main Board","Main Board",TargetBoundingTracker.State.LOCKED,Collections.singletonList(found.getProjectedCorners()),false));
            }
            android.util.Log.i("OutlineRegression","Indexed individual refs="+specs.size()+" resident refs="+db.size()+" board attempts="+attempts+" max job ms="+max+" good="+found.getGoodMatchCount()+" inliers="+found.getInlierCount());
            assertTrue("A live job cannot return to multi-second full-library sweeps: "+max,max<1500);
        }finally{scene.release();}
    }
    @Test public void bankRotationCoversEveryRegisteredStandaloneIdentityAndReleasesEarlierRecords()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        List<ComponentReferenceSpec> specs=UnifiedReferenceCatalog.individual(VisionReferenceFactory.build(AssemblArRepository.getInstance(c),c.getAssets()));
        Map<String,Integer> identities=new LinkedHashMap<>();for(ComponentReferenceSpec s:specs)identities.merge(s.getComponentId(),1,Integer::sum);
        assertTrue(identities.size()>=10);String initial=identities.keySet().iterator().next();long peak=0;StringBuilder inventory=new StringBuilder("component,registered_photos,active_photos,estimated_active_bytes\n");
        try(ReferenceDatabase db=ReferenceDatabase.loadIndividualBank(c.getAssets(),specs,initial,VisionConfig.defaults(),new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"),()->true)){
            assertEquals(identities.keySet(),db.getComponentIds());
            for(int pass=0;pass<2;pass++)for(String id:identities.keySet()){
                List<ReferenceDatabase.PreparedReference> previous=new ArrayList<>(db.preparedReferences());
                boolean changed=db.prepareIndividualBank(IndividualPartTestCatalog.requestFor(id,null));int target=0;long bytes=0;
                assertTrue("Bounded resident references for "+id,db.size()<=8);
                for(ReferenceDatabase.PreparedReference ref:db.preparedReferences()){bytes+=ref.estimatedBytes();if(ref.spec.getComponentId().equals(id)&&ref.hasOrbData())target++;}
                assertTrue("Registered identity has usable native descriptors: "+id,target>0);peak=Math.max(peak,bytes);
                if(changed)for(ReferenceDatabase.PreparedReference old:previous)for(ReferenceDatabase.PreparedOrbVariant variant:old.orbVariants)assertTrue("Old native bank released for "+id,variant.descriptors.empty());
                if(pass==0)inventory.append(id).append(',').append(identities.get(id)).append(',').append(target).append(',').append(bytes).append('\n');
            }
            List<ReferenceDatabase.PreparedReference> finalBank=new ArrayList<>(db.preparedReferences());db.close();
            for(ReferenceDatabase.PreparedReference ref:finalBank)for(ReferenceDatabase.PreparedOrbVariant variant:ref.orbVariants)assertTrue(variant.descriptors.empty());
            try{db.preparedReferences();fail("A closed database must reject reads");}catch(IllegalStateException closed){assertEquals("ReferenceDatabase is closed",closed.getMessage());}
        }
        File out=new File(c.getExternalFilesDir(null),"individual-bank-inventory.csv");try(java.io.Writer writer=new java.io.OutputStreamWriter(new java.io.FileOutputStream(out),java.nio.charset.StandardCharsets.UTF_8)){writer.write(inventory.toString());}
        android.util.Log.i("OutlineRegression","Bank rotation: identities="+identities.size()+" peak estimated resident bytes="+peak+" two complete selection cycles; old descriptors released");
        assertTrue("Native reference records stay bounded",peak<24L*1024*1024);
    }
}
