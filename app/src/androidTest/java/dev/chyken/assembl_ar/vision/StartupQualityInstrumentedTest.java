package dev.chyken.assembl_ar.vision;

import android.content.Context;
import android.content.res.loader.*;
import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.chyken.assembl_ar.camera.*;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.Test;
import org.opencv.android.OpenCVLoader;
import static org.junit.Assert.*;

/** Offline startup/index/selector QA. Never changes assembly progress or opens a camera. */
public final class StartupQualityInstrumentedTest {
 private Context context()throws Exception{
  Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();String path=InstrumentationRegistry.getArguments().getString("candidateDex");if(path==null)return app;
  Context c=app.createConfigurationContext(new android.content.res.Configuration(app.getResources().getConfiguration()));ResourcesLoader loader=new ResourcesLoader();
  try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(new File(path),ParcelFileDescriptor.MODE_READ_ONLY)){loader.addProvider(ResourcesProvider.loadFromApk(fd));}c.getResources().addLoaders(loader);return c;
 }
 @Test public void compactIndexPreservesEveryCountGeometryAndDescriptorWithoutFullBankReload()throws Exception{
  assertTrue(OpenCVLoader.initLocal());org.opencv.core.Core.setNumThreads(2);Context c=context();
  List<ComponentReferenceSpec> specs=VisionReferenceFactory.build(AssemblArRepository.getInstance(c),c.getAssets());VisionConfig config=VisionConfig.defaults();
  File dir=new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3");ReferenceCacheIndex index=new ReferenceCacheIndex(dir,c.getApplicationInfo().sourceDir,ReferenceDatabase.cacheRevision());ReferenceCacheIndex.install(c.getAssets(),index);
  KeypointInventoryManager.INSTANCE.register(specs);long began=android.os.SystemClock.elapsedRealtime();
  ReferenceDatabase.preloadLibrary(c.getAssets(),specs,config,dir,index,(done,total)->{});GlobalComponentRegistry.INSTANCE.publish();
  File first=new File(dir,"qa-global-index.gz"),second=new File(dir,"qa-global-roundtrip.gz");
  GlobalComponentRegistry.INSTANCE.saveCompact(first,index,specs,config,KeypointInventoryManager.INSTANCE);
  long loadedAt=android.os.SystemClock.elapsedRealtime();GlobalComponentRegistry restored=new GlobalComponentRegistry();KeypointInventoryManager inventory=new KeypointInventoryManager();
  assertTrue(restored.restoreCompact(first,index,specs,config,inventory));long compactMs=android.os.SystemClock.elapsedRealtime()-loadedAt;
  assertEquals(GlobalComponentRegistry.INSTANCE.referenceCount(),restored.referenceCount());assertEquals(KeypointInventoryManager.INSTANCE.preparedCount(),inventory.preparedCount());
  for(ComponentReferenceSpec s:specs)assertEquals(KeypointInventoryManager.INSTANCE.count(s.getReferenceId()),inventory.count(s.getReferenceId()));
  restored.saveCompact(second,index,specs,config,inventory);
  assertArrayEquals("Round trip must retain all descriptor bytes, sampled points and resolved ROI geometry",Files.readAllBytes(first.toPath()),Files.readAllBytes(second.toPath()));
  assertTrue(first.length()<32L*1024*1024);
  List<DiagnosticTargetCatalog.Target> targets=DiagnosticTargetCatalog.build(specs,inventory::count);assertTrue(targets.size()>15);
  Set<Integer> phases=new TreeSet<>();for(DiagnosticTargetCatalog.Target t:targets){
   assertFalse(t.title,t.componentId.equals("red_led"));assertTrue(t.keypoints>0);assertFalse(t.references.isEmpty());
   for(ComponentReferenceSpec s:t.references){assertTrue(DiagnosticTargetCatalog.realPhoto(s));assertFalse(UnifiedReferenceCatalog.negative(s));assertTrue(inventory.count(s.getReferenceId())>0);}
   if(t.assembly)phases.add(UnifiedReferenceCatalog.phase(t.representative));
  }
  assertEquals(new TreeSet<>(Arrays.asList(1,2,3,5,7,8,9,10,11)),phases);
  android.util.Log.i("StartupQuality","PASS references="+specs.size()+" targets="+targets.size()+" compactBytes="+first.length()+" compactRestoreMs="+compactMs+" fullPassMs="+(loadedAt-began));
 }
 @Test public void corruptOrDifferentCatalogCannotPublishPartialGlobalEvidence()throws Exception{
  assertTrue(OpenCVLoader.initLocal());Context c=context();VisionConfig config=VisionConfig.defaults();
  List<ComponentReferenceSpec> all=VisionReferenceFactory.build(AssemblArRepository.getInstance(c),c.getAssets());
  List<ComponentReferenceSpec> selected=new ArrayList<>();for(ComponentReferenceSpec s:all)if(s.getReferenceId().startsWith("video1011_")&&!UnifiedReferenceCatalog.assembly(s)&&!UnifiedReferenceCatalog.negative(s)){selected.add(s);if(selected.size()==2)break;}assertEquals(2,selected.size());
  File dir=new File(c.getCacheDir(),"compact-corruption-"+System.nanoTime());assertTrue(dir.mkdirs());ReferenceCacheIndex index=new ReferenceCacheIndex(dir,c.getApplicationInfo().sourceDir,ReferenceDatabase.cacheRevision());ReferenceCacheIndex.install(c.getAssets(),index);
  GlobalComponentRegistry source=new GlobalComponentRegistry();KeypointInventoryManager inventory=new KeypointInventoryManager();
  try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),selected,config,dir)){for(ReferenceDatabase.PreparedReference r:db.preparedReferences()){source.prepared(r);inventory.prepared(r);}}
  source.publish();File good=new File(dir,"index.gz");source.saveCompact(good,index,selected,config,inventory);
  GlobalComponentRegistry mismatch=new GlobalComponentRegistry();assertFalse(mismatch.restoreCompact(good,index,selected.subList(0,1),config,new KeypointInventoryManager()));assertFalse(mismatch.isReady());assertEquals(0,mismatch.referenceCount());
  byte[] broken=Files.readAllBytes(good.toPath());broken[broken.length-5]^=0x55;File corrupt=new File(dir,"broken.gz");Files.write(corrupt.toPath(),broken);
  GlobalComponentRegistry target=new GlobalComponentRegistry();KeypointInventoryManager untouched=new KeypointInventoryManager();assertFalse(target.restoreCompact(corrupt,index,selected,config,untouched));assertFalse(target.isReady());assertEquals(0,target.referenceCount());assertTrue(untouched.snapshot().isEmpty());
 }
 @Test public void previewSkipIsVisibleAccessibleAndCannotLeakIntoNonVideoContent()throws Exception{
  Context source=context();android.view.ContextThemeWrapper themed=new android.view.ContextThemeWrapper(source,dev.chyken.assembl_ar.R.style.Theme_ASSEMBLAR);
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
   dev.chyken.assembl_ar.ui.ComponentPreviewWindow preview=new dev.chyken.assembl_ar.ui.ComponentPreviewWindow(themed,null);
   int[] clicked={0};preview.setSkipPreviewAction(()->clicked[0]++);
   com.google.android.material.button.MaterialButton skip=null;
   for(int i=0;i<preview.getChildCount();i++)if(preview.getChildAt(i) instanceof com.google.android.material.button.MaterialButton)skip=(com.google.android.material.button.MaterialButton)preview.getChildAt(i);
   assertNotNull(skip);assertEquals(android.view.View.VISIBLE,skip.getVisibility());assertTrue(skip.isEnabled());
   assertTrue(skip.getLayoutParams().height>=dev.chyken.assembl_ar.ui.UiUtils.dp(themed,48));assertEquals(0xFFFFFFFF,skip.getCurrentTextColor());
   skip.performClick();assertEquals(1,clicked[0]);preview.showContent(new android.widget.TextView(themed),"Checklist");assertEquals(android.view.View.GONE,skip.getVisibility());assertFalse(skip.isEnabled());skip.performClick();assertEquals(1,clicked[0]);preview.dispose();
  });
 }
}
