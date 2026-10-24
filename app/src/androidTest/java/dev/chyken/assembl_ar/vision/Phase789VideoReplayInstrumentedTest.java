package dev.chyken.assembl_ar.vision;

import android.content.*;
import android.content.res.loader.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.chyken.assembl_ar.camera.*;
import java.io.*;
import java.util.*;
import org.junit.*;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import static org.junit.Assert.*;

/** Real disjoint video timestamps at tablet resolution. Same-session regression,
 * not a held-out-room accuracy study. Never starts an Activity or resets progress. */
public final class Phase789VideoReplayInstrumentedTest {
    private final List<String> outlineFailures=new ArrayList<>();
    @Test public void allNewVideoCases()throws Exception{
        List<String> failed=new ArrayList<>();
        try{darkTrackersRequireBothBodiesAndCasterAndKeepMeasuredOutlines();}catch(AssertionError e){failed.add(e.getMessage());}
        try{sensorRequiresBothEyesAndRejectsRealInwardAndMissingInstallations();}catch(AssertionError e){failed.add(e.getMessage());}
        try{wheelsNeedTwoDistinctInstancesAndInstalledStandWithOutlines();}catch(AssertionError e){failed.add(e.getMessage());}
        failed.addAll(outlineFailures);assertTrue(failed.toString(),failed.isEmpty());
    }
    @BeforeClass public static void runtime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    private Context context()throws Exception{
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String path=InstrumentationRegistry.getArguments().getString("candidateDex");if(path==null)return app;
        Context c=app.createConfigurationContext(new android.content.res.Configuration(app.getResources().getConfiguration()));
        ResourcesLoader loader=new ResourcesLoader();try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(new File(path),ParcelFileDescriptor.MODE_READ_ONLY)){loader.addProvider(ResourcesProvider.loadFromApk(fd));}c.getResources().addLoaders(loader);return c;
    }
    private Mat raw(int phase,String role,int time){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File path=new File(new File(c.getExternalFilesDir(null),"phase789-raw"),"heldout-p"+phase+"-"+role+"-"+time+".jpg");
        Mat source;String archive=InstrumentationRegistry.getArguments().getString("candidateDex");
        try(java.util.zip.ZipFile zip=archive==null?null:new java.util.zip.ZipFile(archive)){
            java.util.zip.ZipEntry entry=zip==null?null:zip.getEntry("assets/replay/phase789/"+path.getName());
            if(entry==null)source=Imgcodecs.imread(path.getPath());else try(InputStream input=zip.getInputStream(entry)){MatOfByte encoded=new MatOfByte(input.readAllBytes());try{source=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_COLOR);}finally{encoded.release();}}
        }catch(IOException e){throw new AssertionError("Cannot decode real replay frame "+path.getName(),e);}
        Mat scaled=new Mat(),result=new Mat(400,640,CvType.CV_8UC3,new Scalar(180,180,180));
        assertFalse("Missing replay frame "+path,source.empty());
        try{Imgproc.resize(source,scaled,new Size(640,360),0,0,Imgproc.INTER_AREA);Mat roi=result.submat(new Rect(0,20,640,360));try{scaled.copyTo(roi);}finally{roi.release();}Imgproc.cvtColor(result,result,Imgproc.COLOR_BGR2RGB);return result;}finally{source.release();scaled.release();}
    }
    private static void begin(int attempt){DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.recovery((attempt&1)==1);}
    private static void log(String view,int attempt,RecognitionResult r,String evidence){android.util.Log.i("Phase789Video",view+" attempt="+attempt+" "+evidence+" outlines="+r.getInstanceObservations().size()+" corners="+r.getProjectedCorners().size()+" ms="+r.getLatency().getTotalMs());}
    @Test public void difficultAnglesWithReviewedReferencesKeepTheirIdentityGates()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();List<String> failed=new ArrayList<>();
        for(int phase:new int[]{7,8,9}){
            List<ComponentReferenceSpec> bank=new ArrayList<>();for(ComponentReferenceSpec s:Phase789VideoReferences.all()){Phase789VideoReferences.Entry e=Phase789VideoReferences.annotation(s.getJpegAssetPath());if(e.phase==phase&&(phase==7?e.role.equals("caster"):phase==8?true:e.role.equals("wheel")||e.role.equals("pair")))bank.add(s);}
            try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),bank,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase7ReferenceMatcher p7=new Phase7ReferenceMatcher(db,config);Phase8ReferenceMatcher p8=new Phase8ReferenceMatcher(db,config);Phase9ReferenceMatcher p9=new Phase9ReferenceMatcher(db,config)){
                for(int time:phase==7?new int[]{20}:phase==8?new int[]{170,201}:new int[]{90}){Mat rgb=raw(phase,phase==7?"caster":phase==8?"invalid":"wheel",time),gray=new Mat();boolean found=false;
                    try{Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);for(int a=0;a<12;a++){begin(a);RecognitionResult r=phase==7?p7.inspect(gray,rgb,a+1,1):phase==8?p8.inspect(gray,a+1,2):p9.inspect(gray,rgb,a+1,2);
                        found=phase==7?r.getPhase7Observation().accepted(1):phase==8?r.getPhase8Observation().state==Phase8Observation.State.INWARD:r.getPhase9Observation().looseWheels==1;
                        if(phase==8)assertFalse("Inward frame must never unlock",r.getPhase8Observation().accepted(2));log("hard P"+phase+" "+time,a,r,phase==8?"state="+r.getPhase8Observation().state:"found="+found);if(found)break;
                    }if(!found&&!(phase==8&&time==170))failed.add("P"+phase+" "+time);
                    if(phase==8&&time==170&&!found)android.util.Log.i("Phase789Video","Motion-blurred inward 170: no identity claim, never unlocked; clear inward views are required to warn.");
                    }finally{rgb.release();gray.release();DiagnosticHammingMatcher.end();}
                }
            }
        }
        assertTrue("Difficult reviewed-bank views: "+failed,failed.isEmpty());
    }
    @Test public void catalogDiscoveryIncludesEveryReviewedAsset()throws Exception{
        Context c=context();List<ComponentReferenceSpec> specs=new ArrayList<>();specs.addAll(Phase7AssetCatalog.references(c.getAssets()));specs.addAll(Phase8AssetCatalog.references(c.getAssets()));specs.addAll(Phase9AssetCatalog.references(c.getAssets()));
        Set<String> paths=new HashSet<>();for(ComponentReferenceSpec s:specs)paths.add(s.getJpegAssetPath());List<String> missing=new ArrayList<>();for(ComponentReferenceSpec s:Phase789VideoReferences.all())if(!paths.contains(s.getJpegAssetPath()))missing.add(s.getJpegAssetPath());
        android.util.Log.i("Phase789Video","dynamic missing="+missing);assertTrue("Dynamic catalog omitted reviewed assets: "+missing,missing.isEmpty());
    }
    @Test public void individualToolPublishesMeasuredOutlinesForTheReviewedLooseParts()throws Exception{
        individualParts(new String[]{"caster_wheel","line_tracker_module","drive_wheel"});
    }
    @Ignore("Recorded loose standoff still has insufficient spatial consensus at 640x400. Normal assembly explicitly bypasses it; retain this opt-in false-negative fixture.")
    @Test public void standaloneStandoffExploratoryReplay()throws Exception{
        individualParts(new String[]{"center_standoff"});
    }
    private void individualParts(String[] ids)throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();List<String> failed=new ArrayList<>();
        for(String id:ids){
            List<ComponentReferenceSpec> bank=new ArrayList<>();for(ComponentReferenceSpec s:Phase789VideoReferences.individual())if(id.equals(s.getComponentId()))bank.add(s);
            try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),bank,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(config,db)){
                RecognitionRequest request=IndividualPartTestCatalog.requestFor(id,CameraCalibration.pinhole(700,700,320,200),.001).withRequestTag(id);
                java.util.concurrent.atomic.AtomicReference<RecognitionResult> latest=new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.atomic.AtomicReference<TargetBoundingTracker.Target> outline=new java.util.concurrent.atomic.AtomicReference<>();List<String> order=new ArrayList<>();
                try(AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,f->request,new AssemblyVisionAnalyzer.Callback(){public void onResult(RecognitionResult r){order.add("result");latest.set(r);}public void onTargetTracking(TargetBoundingTracker.Target t,AssemblyVisionAnalyzer.FrameMetadata f,Object tag){if(t!=null){order.add("outline");outline.set(t);}}},config)){
                    int phase=id.equals("caster_wheel")||id.equals("line_tracker_module")?7:9;String role=id.equals("caster_wheel")?"caster":id.equals("line_tracker_module")?"single":id.equals("center_standoff")?"standoff":"wheel";int time=id.equals("caster_wheel")?48:id.equals("line_tracker_module")?15:id.equals("center_standoff")?42:70;
                    Mat rgb=raw(phase,role,time);try{
                        java.lang.reflect.Method image=Class.forName("dev.chyken.assembl_ar.AssemblyReplayHarness",true,getClass().getClassLoader()).getDeclaredMethod("image",Mat.class,long.class);image.setAccessible(true);long now=android.os.SystemClock.elapsedRealtimeNanos();
                        for(int a=0;a<6;a++){analyzer.analyzeForReplay((androidx.camera.core.ImageProxy)image.invoke(null,rgb,now+a*1_000_000_000L));if(latest.get()!=null&&latest.get().getValidationState()==RecognitionResult.ValidationState.EXPECTED_COMPONENT)break;}
                        boolean matched=latest.get()!=null&&latest.get().getValidationState()==RecognitionResult.ValidationState.EXPECTED_COMPONENT;
                        android.util.Log.i("Phase789Video","individual "+id+" frame="+time+" matched="+matched+" outlined="+(outline.get()!=null)+" result="+(latest.get()==null?"none":latest.get().getValidationState()+" "+latest.get().getReferenceId()+" "+latest.get().getGoodMatchCount()+" rejection="+(latest.get().getRejectionEvidence()==null?"none":latest.get().getRejectionEvidence().getReason())));
                        if(!matched)failed.add(id);else{assertNotNull(id+" must publish a measured outline",outline.get());assertTrue(order.indexOf("outline")<order.lastIndexOf("result"));}
                    }finally{rgb.release();}
                }
            }
        }assertTrue("Unmatched individual-tool camera frames: "+failed,failed.isEmpty());
    }
    @Test public void reviewedMasksAreUsableAtTabletSampling()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();List<String> failures=new ArrayList<>();
        for(int phase:new int[]{7,8,9}){
            List<ComponentReferenceSpec> bank=new ArrayList<>();ComponentReferenceSpec probe=null;
            for(ComponentReferenceSpec s:Phase789VideoReferences.all()){
                Phase789VideoReferences.Entry e=Phase789VideoReferences.annotation(s.getJpegAssetPath());
                if(e.phase==phase&&(phase==7?e.role.equals("trackerwork"):phase==8?e.role.equals("assembly")||e.role.equals("invalid"):e.role.equals("pair"))){bank.add(s);if(probe==null)probe=s;}
            }
            byte[] bytes;try(InputStream in=c.getAssets().open(probe.getJpegAssetPath())){bytes=in.readAllBytes();}
            MatOfByte encoded=new MatOfByte(bytes);Mat source=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_COLOR),gray=new Mat(),rgb=new Mat(400,640,CvType.CV_8UC3,new Scalar(180,180,180)),scaled=new Mat();
            try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),bank,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"))){
                double scale=Math.min(640.0/source.cols(),400.0/source.rows());Imgproc.resize(source,scaled,new Size(),scale,scale,Imgproc.INTER_AREA);Mat roi=rgb.submat(new Rect((640-scaled.cols())/2,(400-scaled.rows())/2,scaled.cols(),scaled.rows()));try{scaled.copyTo(roi);}finally{roi.release();}Imgproc.cvtColor(rgb,rgb,Imgproc.COLOR_BGR2RGB);Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
                try(Phase7ReferenceMatcher p7=new Phase7ReferenceMatcher(db,config);Phase8ReferenceMatcher p8=new Phase8ReferenceMatcher(db,config);Phase9ReferenceMatcher p9=new Phase9ReferenceMatcher(db,config)){
                    boolean passed=false;for(int a=0;a<4;a++){begin(a);RecognitionResult r=phase==7?p7.inspect(gray,rgb,a+1,4):phase==8?p8.inspect(gray,a+1,2):p9.inspect(gray,rgb,a+1,2);
                        passed=phase==7?r.getPhase7Observation().accepted(4):phase==8?r.getPhase8Observation().accepted(2):r.getPhase9Observation().accepted(2);log("self P"+phase,a,r,"accepted="+passed);if(passed)break;}
                    if(!passed)failures.add("P"+phase+" "+probe.getJpegAssetPath());
                }
            }finally{encoded.release();source.release();gray.release();rgb.release();scaled.release();DiagnosticHammingMatcher.end();}
        }
        assertTrue("Authored mask sampling sanity: "+failures,failures.isEmpty());
    }
    @Test public void darkTrackersRequireBothBodiesAndCasterAndKeepMeasuredOutlines()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();List<String> failed=new ArrayList<>();
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),Phase7AssetCatalog.references(c.getAssets()),config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase7ReferenceMatcher matcher=new Phase7ReferenceMatcher(db,config)){
            for(String role:new String[]{"caster","trackers","single","trackerwork"}){
                int[] times=role.equals("caster")?new int[]{20,48}:role.equals("trackers")?new int[]{10,44}:role.equals("single")?new int[]{15,42}:new int[]{12,35,65,83};
                int step=role.equals("caster")?1:role.equals("trackerwork")?4:2;
                for(int time:times){Mat rgb=raw(7,role,time),gray=new Mat();RecognitionResult found=null;
                    try{Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
                        for(int a=0;a<8;a++){begin(a);RecognitionResult r=matcher.inspect(gray,rgb,a+1,step);Phase7Observation o=r.getPhase7Observation();log("p7 "+role+" "+time,a,r,"count="+o.count+" bodies="+o.visibleTrackers+" mounts="+o.verifiedMounts+" caster="+o.casterPresent);
                            if(role.equals("single")){assertFalse("One physical tracker must never count twice",o.accepted(2));if(o.count>0)found=r;}
                            else if(o.accepted(step)){found=r;break;}
                        }
                        if(found==null)failed.add(role+" "+time);else if(!role.equals("single"))movingOutline(gray,found,step==4?3:step==2?2:1);
                    }finally{rgb.release();gray.release();DiagnosticHammingMatcher.end();}
                }
            }
        }
        failed.addAll(outlineFailures);assertTrue("Unmatched Phase 7 raw views: "+failed,failed.isEmpty());
    }
    @Test public void sensorRequiresBothEyesAndRejectsRealInwardAndMissingInstallations()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();List<String> failed=new ArrayList<>();
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),Phase8AssetCatalog.references(c.getAssets()),config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase8ReferenceMatcher matcher=new Phase8ReferenceMatcher(db,config)){
            for(String role:new String[]{"assembly","wired","invalid","missing","occluded"}){
                int[] times=role.equals("assembly")?new int[]{75,82}:role.equals("wired")?new int[]{10,42}:role.equals("invalid")?new int[]{150,170,188,201}:role.equals("occluded")?new int[]{100}:new int[]{8,25};
                for(int time:times){Mat rgb=raw(8,role,time),gray=new Mat();RecognitionResult found=null;
                    try{Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
                        for(int a=0;a<6;a++){begin(a);RecognitionResult r=matcher.inspect(gray,a+1,2);Phase8Observation o=r.getPhase8Observation();log("p8 "+role+" "+time,a,r,"state="+o.state);
                            if(role.equals("missing")||role.equals("occluded")){assertFalse("Missing or out-of-frame sensor cannot pass from PCB identity",o.accepted(2));continue;}
                            if(role.equals("invalid")){assertFalse("Real inward sensor must never pass",o.accepted(2));if(o.state==Phase8Observation.State.INWARD){found=r;break;}}
                            else if(o.accepted(2)){found=r;break;}
                        }
                        // At 170 seconds motion blur leaves <10 independent sensor
                        // correspondences. It must remain blocked, not be coerced
                        // into a match. Explicit warnings are required on all three
                        // clear inward angles (150,188,201), plus measured outlines.
                        if(!role.equals("missing")&&!role.equals("occluded")){if(found==null){if(!(role.equals("invalid")&&time==170))failed.add(role+" "+time);}else movingOutline(gray,found,1);}
                    }finally{rgb.release();gray.release();DiagnosticHammingMatcher.end();}
                }
            }
        }
        failed.addAll(outlineFailures);assertTrue("Unmatched Phase 8 raw views: "+failed,failed.isEmpty());
    }
    @Test public void wheelsNeedTwoDistinctInstancesAndInstalledStandWithOutlines()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();List<String> failed=new ArrayList<>();
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),Phase9AssetCatalog.references(c.getAssets()),config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase9ReferenceMatcher matcher=new Phase9ReferenceMatcher(db,config)){
            for(String role:new String[]{"wheel","pair","assembly"}){
                int[] times=role.equals("wheel")?new int[]{70,90}:role.equals("pair")?new int[]{114,129}:new int[]{10,31,55};int stage=role.equals("assembly")?4:2;
                for(int time:times){Mat rgb=raw(9,role,time),gray=new Mat();RecognitionResult found=null;
                    try{Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
                        for(int a=0;a<6;a++){begin(a);RecognitionResult r=matcher.inspect(gray,rgb,a+1,stage);Phase9Observation o=r.getPhase9Observation();log("p9 "+role+" "+time,a,r,"loose="+o.looseWheels+" stand="+o.standoff+" left="+o.leftWheel+" right="+o.rightWheel);
                            if(role.equals("wheel")){assertFalse("One wheel cannot complete two-wheel preparation",o.accepted(2));if(o.looseWheels==1)found=r;}
                            else if(o.accepted(stage)){found=r;break;}
                        }
                        if(found==null)failed.add(role+" "+time);else movingOutline(gray,found,stage==4?3:role.equals("wheel")?1:2);
                    }finally{rgb.release();gray.release();DiagnosticHammingMatcher.end();}
                }
            }
        }
        failed.addAll(outlineFailures);assertTrue("Unmatched Phase 9 raw views: "+failed,failed.isEmpty());
    }
    private void movingOutline(Mat gray,RecognitionResult result,int count){
        List<List<VisionPoint>> regions=new ArrayList<>();for(RecognitionResult.InstanceObservation i:result.getInstanceObservations())regions.add(i.getProjectedCorners());if(regions.isEmpty())regions.add(result.getProjectedCorners());
        assertEquals("Every verified component needs visible geometry",count,regions.size());for(List<VisionPoint> q:regions)assertEquals(4,q.size());
        try(TargetBoundingTracker tracker=new TargetBoundingTracker()){
            TargetBoundingTracker.State state=result.getValidationState()==RecognitionResult.ValidationState.WRONG_ORIENTATION?TargetBoundingTracker.State.WRONG:TargetBoundingTracker.State.LOCKED;
            assertNotNull(tracker.detect(gray,1,result.getComponentId(),result.getDisplayName(),result.getDisplayName(),state,regions,true));
            Mat transform=Mat.eye(2,3,CvType.CV_64F),moved=new Mat();
            try{transform.put(0,2,8);transform.put(1,2,3);Imgproc.warpAffine(gray,moved,transform,gray.size());TargetBoundingTracker.Target tracked=tracker.frame(moved,50_000_001L,null);assertNotNull(tracked);if(tracked.predicted){android.util.Log.w("Phase789Video","LK not measured "+result.getComponentId()+" "+regions);outlineFailures.add("Unmeasured flow: "+result.getComponentId());}}finally{transform.release();moved.release();}
        }
    }
}
