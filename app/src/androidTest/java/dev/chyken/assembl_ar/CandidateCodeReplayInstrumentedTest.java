package dev.chyken.assembl_ar;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import dalvik.system.InMemoryDexClassLoader;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Replay the actual newly built app DEX without updating the learner's installed app.
 * Test-only: candidate code and fixtures share a child loader; Android/OpenCV/JUnit stay
 * in the parent. No activity is launched and no assembly preferences are changed. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion=27)
public final class CandidateCodeReplayInstrumentedTest {
    @Test public void replayCandidateMatchers() throws Throwable {
        String path=InstrumentationRegistry.getArguments().getString("candidateDex");
        assumeTrue("Supply a local code-only archive extracted from the built APK",path!=null);
        File archive=new File(path);assertTrue(archive.isFile());
        List<ByteBuffer> dex=new ArrayList<>();readDex(archive,dex);
        readDex(new File(InstrumentationRegistry.getInstrumentation().getContext().getApplicationInfo().sourceDir),dex);
        ClassLoader parent=getClass().getClassLoader();
        ClassLoader platformParent=new ClassLoader(parent){
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                if(name.startsWith("dev.chyken.assembl_ar."))throw new ClassNotFoundException(name);
                return super.loadClass(name,resolve);
            }
        };
        ClassLoader candidate=new InMemoryDexClassLoader(dex.toArray(new ByteBuffer[0]),platformParent);
        String cases=InstrumentationRegistry.getArguments().getString("candidateCases",
                "vision.Phase2PrecisionInstrumentedTest#boundedLiveSearchHandlesFullCatalogWithoutOneLongSweep");
        assertTrue(org.opencv.android.OpenCVLoader.initLocal());
        org.opencv.core.Core.setNumThreads(2);
        for(String entry:cases.split(",")){
            String[] parts=entry.split("#");Class<?> fixture=candidate.loadClass("dev.chyken.assembl_ar."+parts[0]);
            assertSame("Must exercise candidate code, not the installed implementation",candidate,candidate.loadClass("dev.chyken.assembl_ar.vision.Phase2ReferenceMatcher").getClassLoader());
            for(java.lang.reflect.Method method:fixture.getMethods())if(method.isAnnotationPresent(org.junit.BeforeClass.class))invoke(method,null);
            long started=android.os.SystemClock.elapsedRealtime();
            invoke(fixture.getMethod(parts[1]),fixture.getConstructor().newInstance());
            android.util.Log.i("CandidateReplay",entry+" PASS ms="+(android.os.SystemClock.elapsedRealtime()-started));
        }
    }
    private static void invoke(java.lang.reflect.Method method,Object target)throws Throwable{
        try{method.invoke(target);}catch(InvocationTargetException failure){throw failure.getCause();}
    }
    private static void readDex(File archive,List<ByteBuffer> output)throws IOException{
        try(ZipFile zip=new ZipFile(archive)){
            List<? extends ZipEntry> entries=Collections.list(zip.entries());entries.sort(Comparator.comparing(ZipEntry::getName));
            for(ZipEntry entry:entries)if(entry.getName().matches("classes[0-9]*\\.dex"))try(InputStream in=zip.getInputStream(entry)){
                byte[] data=in.readAllBytes();ByteBuffer buffer=ByteBuffer.allocateDirect(data.length);buffer.put(data);buffer.flip();output.add(buffer);
            }
        }
    }
}
