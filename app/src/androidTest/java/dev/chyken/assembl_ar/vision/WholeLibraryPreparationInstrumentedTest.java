package dev.chyken.assembl_ar.vision;

import android.content.Context;
import android.content.res.loader.*;
import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import java.io.*;
import java.util.*;
import org.junit.Test;
import org.opencv.android.OpenCVLoader;
import static org.junit.Assert.*;

/** Only compiled reference caches are touched. No Activity, progress, or checkpoint writes. */
public final class WholeLibraryPreparationInstrumentedTest {
    private Context context()throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String path=InstrumentationRegistry.getArguments().getString("candidateDex");
        if(path==null)return app;
        Context c=app.createConfigurationContext(new android.content.res.Configuration(app.getResources().getConfiguration()));
        ResourcesLoader loader=new ResourcesLoader();
        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(new File(path),ParcelFileDescriptor.MODE_READ_ONLY)) {
            loader.addProvider(ResourcesProvider.loadFromApk(fd));
        }
        c.getResources().addLoaders(loader);return c;
    }

    @Test public void identifyInstalledFailureAt387()throws Exception {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ClassLoader installed=c.getClassLoader();
        Class<?> repository=Class.forName("dev.chyken.assembl_ar.data.AssemblArRepository",true,installed);
        Object repo=repository.getMethod("getInstance",Context.class).invoke(null,c);
        Class<?> factory=Class.forName("dev.chyken.assembl_ar.camera.VisionReferenceFactory",true,installed);
        List<?> specs=(List<?>)factory.getMethod("build",repository,android.content.res.AssetManager.class).invoke(null,repo,c.getAssets());
        Class<?> specClass=Class.forName("dev.chyken.assembl_ar.vision.ComponentReferenceSpec",true,installed);
        Class<?> database=Class.forName("dev.chyken.assembl_ar.vision.ReferenceDatabase",true,installed);
        java.lang.reflect.Method filename=database.getDeclaredMethod("cacheFile",File.class,specClass,byte[].class);filename.setAccessible(true);
        File directory=new File(c.getCacheDir(),"filename-limit-regression");assertTrue(directory.isDirectory()||directory.mkdirs());
        for(int position=383;position<=389;position++) {
            Object spec=specs.get(position-1);
            String path=(String)specClass.getMethod("getJpegAssetPath").invoke(spec);
            byte[] image;try(InputStream input=c.getAssets().open(path)){image=input.readAllBytes();}
            File legacy=(File)filename.invoke(null,directory,spec,image);
            android.util.Log.i("WholeLibrary","installed reference="+position+" path="+path+" filenameBytes="+legacy.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            if(position==388&&legacy.getName().length()>255) {
                try(FileOutputStream out=new FileOutputStream(legacy)){fail("Old oversized filename unexpectedly writable");}
                catch(FileNotFoundException expected){android.util.Log.i("WholeLibrary","REPRODUCED: "+expected);}
            }
        }
    }

    @Test public void completeWholeCatalogAndReuseItsDiskIndex()throws Exception {
        assertTrue(OpenCVLoader.initLocal());org.opencv.core.Core.setNumThreads(2);
        Context c=context();
        List<ComponentReferenceSpec> specs=VisionReferenceFactory.build(AssemblArRepository.getInstance(c),c.getAssets());
        VisionConfig config=VisionConfig.defaults();
        File directory=new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3");
        ReferenceCacheIndex index=new ReferenceCacheIndex(directory,c.getApplicationInfo().sourceDir,ReferenceDatabase.cacheRevision());
        ReferenceCacheIndex.install(c.getAssets(),index);
        int[] last={0};long start=android.os.SystemClock.elapsedRealtime();
        ReferenceDatabase.preloadLibrary(c.getAssets(),specs,config,directory,index,(done,total)->{
            last[0]=done;
            if(done%25==0||done==total)android.util.Log.i("WholeLibrary","compiled="+done+"/"+total+" elapsedMs="+(android.os.SystemClock.elapsedRealtime()-start));
        });
        assertEquals(specs.size(),last[0]);
        ReferenceCacheIndex disk=new ReferenceCacheIndex(directory,c.getApplicationInfo().sourceDir,ReferenceDatabase.cacheRevision());
        for(ComponentReferenceSpec spec:specs)assertTrue("Missing persisted entry "+spec.getReferenceId(),disk.terminal(spec,config));
        android.util.Log.i("WholeLibrary","PASS all "+specs.size()+" terminal entries durable; elapsedMs="+(android.os.SystemClock.elapsedRealtime()-start));
        ComponentReferenceSpec formerlyBroken=brokenReference(specs);
        assertFalse("Reference 388 must be compiled, not silently skipped",disk.isSkipped(formerlyBroken,config));
        assertTrue(disk.compiled(formerlyBroken,config).isFile());
        Map<File,Long> before=new HashMap<>();for(File f:Objects.requireNonNull(directory.listFiles()))if(f.getName().endsWith(".asor"))before.put(f,f.lastModified());
        GlobalComponentRegistry.INSTANCE.publish();ReferenceCacheIndex.install(c.getAssets(),disk);
        final long reusedAt=android.os.SystemClock.elapsedRealtime();
        ReferenceDatabase.preloadLibrary(c.getAssets(),specs,config,directory,disk,(done,total)->last[0]=done);
        for(Map.Entry<File,Long> file:before.entrySet())assertEquals("Warm library must reuse compiled bytes",(long)file.getValue(),file.getKey().lastModified());
        assertEquals(specs.size(),last[0]);
        android.util.Log.i("WholeLibrary","PASS warm index reused "+specs.size()+" entries without rewriting descriptors; elapsedMs="+(android.os.SystemClock.elapsedRealtime()-reusedAt));
    }

    @Test public void longNameColdCompileAndWarmReloadPreserveDescriptors()throws Exception {
        assertTrue(OpenCVLoader.initLocal());Context c=context();
        List<ComponentReferenceSpec> specs=VisionReferenceFactory.build(AssemblArRepository.getInstance(c),c.getAssets());
        ComponentReferenceSpec selected=brokenReference(specs);
        File dir=new File(c.getCacheDir(),"long-reference-"+System.nanoTime());assertTrue(dir.mkdirs());
        VisionConfig config=VisionConfig.defaults();ReferenceCacheIndex index=new ReferenceCacheIndex(dir,c.getApplicationInfo().sourceDir,ReferenceDatabase.cacheRevision());
        ReferenceCacheIndex.install(c.getAssets(),index);
        List<ComponentReferenceSpec> one=Collections.singletonList(selected);
        ReferenceDatabase.preloadLibrary(c.getAssets(),one,config,dir,index,(done,total)->assertEquals(1,(int)done));
        assertFalse("Real component must compile rather than be skipped",index.isSkipped(selected,config));
        File compiled=index.compiled(selected,config);assertNotNull(compiled);assertTrue(compiled.isFile());
        long modified=compiled.lastModified();
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),one,config,dir)) {
            assertEquals(1,db.size());assertTrue(db.preparedReferences().get(0).estimatedBytes()>0);
        }
        assertEquals("Warm read must not rewrite the source",modified,compiled.lastModified());
    }

    @Test public void storageFailureIsReportedAndNeverMarkedAsBadPhoto()throws Exception {
        assertTrue(OpenCVLoader.initLocal());Context c=context();
        ComponentReferenceSpec s=brokenReference(VisionReferenceFactory.build(AssemblArRepository.getInstance(c),c.getAssets()));
        File dir=new File(c.getCacheDir(),"reference-write-failure-"+System.nanoTime());assertTrue(dir.mkdirs());
        VisionConfig config=VisionConfig.defaults();ReferenceCacheIndex index=new ReferenceCacheIndex(dir,c.getApplicationInfo().sourceDir,ReferenceDatabase.cacheRevision());
        ReferenceCacheIndex.install(c.getAssets(),index);
        // A directory at the index destination deterministically simulates a publish failure.
        assertTrue(new File(dir,"compiled-index-v1.properties").mkdir());
        try {
            ReferenceDatabase.preloadLibrary(c.getAssets(),Collections.singletonList(s),config,dir,index,(done,total)->fail("A failed publish cannot report completion"));
            fail("Storage failure must reach the caller");
        } catch(IOException expected) {
            assertTrue(expected.getMessage(),expected.getMessage().contains("Reference 1 / 1"));
            assertTrue(expected.getMessage().contains(s.getJpegAssetPath()));
            assertFalse("Storage trouble must not blacklist valid pixels",index.isSkipped(s,config));
        }
        for(File file:Objects.requireNonNull(dir.listFiles()))assertFalse("Atomic writes must clean temporary files",file.getName().endsWith(".tmp"));
    }

    private static ComponentReferenceSpec brokenReference(List<ComponentReferenceSpec> specs) {
        for(ComponentReferenceSpec s:specs)if(s.getReferenceId().startsWith("p8scene_")&&s.getJpegAssetPath()!=null&&s.getJpegAssetPath().endsWith("VIDEO789_fDOmql_e_166_invalid_INWARD.jpg"))return s;
        throw new AssertionError("Missing actual reference 388 regression fixture");
    }
}
