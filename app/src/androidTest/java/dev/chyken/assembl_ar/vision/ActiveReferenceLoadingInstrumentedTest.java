package dev.chyken.assembl_ar.vision;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.chyken.assembl_ar.camera.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static dev.chyken.assembl_ar.data.NeobotSeedData.STANDOFF;

/** No Activity, camera or learner progress writes. Exercises the production scoped loader. */
@RunWith(AndroidJUnit4.class)
public final class ActiveReferenceLoadingInstrumentedTest {
    @BeforeClass public static void initialize(){assertTrue(OpenCVLoader.initLocal());}
    private RecognitionRequest standoff(){return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(STANDOFF),null,null,null,1,9).withPhase9Stage(1);}
    @Test public void standoffBankPreparesOnlyItsCurrentPhotosAndReportsCompletion()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();List<ComponentReferenceSpec> all=Phase9AssetCatalog.references(app.getAssets());List<ComponentReferenceSpec> scoped=ActiveReferenceScope.select(all,standoff());
        assertTrue(scoped.size()>=18);assertTrue(scoped.size()<all.size());List<Integer> progress=new ArrayList<>();
        long start=SystemClock.elapsedRealtime();
        try(ReferenceDatabase db=ReferenceDatabase.load(app.getAssets(),scoped,VisionConfig.defaults(),new File(app.getNoBackupFilesDir(),"orb_reference_cache_v3"),()->true,(done,total)->{assertEquals(scoped.size(),total.intValue());progress.add(done);})){
            assertEquals(scoped.size(),db.size());assertEquals(scoped.size(),progress.size());assertEquals(Integer.valueOf(scoped.size()),progress.get(progress.size()-1));
            for(ReferenceDatabase.PreparedReference ref:db.preparedReferences())assertEquals("standoff",Phase9AssetCatalog.referenceRole(ref.spec.getReferenceId()));
            android.util.Log.i("ReferenceBankTiming","scope=p9:1 prepared="+db.size()+" unrelatedSkipped="+(all.size()-db.size())+" ms="+(SystemClock.elapsedRealtime()-start));
        }
    }
    @Test public void leavingAScanCancelsBeforePreparingAnotherPhoto()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();List<ComponentReferenceSpec> scoped=ActiveReferenceScope.select(Phase9AssetCatalog.references(app.getAssets()),standoff());AtomicBoolean active=new AtomicBoolean(true);List<Integer> progress=new ArrayList<>();
        try{
            ReferenceDatabase db=ReferenceDatabase.load(app.getAssets(),scoped,VisionConfig.defaults(),new File(app.getNoBackupFilesDir(),"orb_reference_cache_v3"),active::get,(done,total)->{progress.add(done);active.set(false);});db.close();fail("Cancelled bank must not be installed");
        }catch(InterruptedIOException expected){assertEquals(Collections.singletonList(1),progress);}
    }
}
