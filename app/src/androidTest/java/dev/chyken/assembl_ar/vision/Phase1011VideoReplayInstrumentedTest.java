package dev.chyken.assembl_ar.vision;

import android.content.Context;
import android.content.res.loader.*;
import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.chyken.assembl_ar.camera.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.*;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import static org.junit.Assert.*;

/** Disjoint real timestamps at 640x400. Does not launch Activities or alter progress. */
public final class Phase1011VideoReplayInstrumentedTest {
    @BeforeClass public static void runtime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    private Context context()throws Exception {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String path=InstrumentationRegistry.getArguments().getString("candidateDex");if(path==null)return app;
        Context c=app.createConfigurationContext(new android.content.res.Configuration(app.getResources().getConfiguration()));
        ResourcesLoader loader=new ResourcesLoader();try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(new File(path),ParcelFileDescriptor.MODE_READ_ONLY)){loader.addProvider(ResourcesProvider.loadFromApk(fd));}c.getResources().addLoaders(loader);return c;
    }
    private Mat raw(int phase,String role,int time)throws Exception {
        String name="heldout-p"+phase+"-"+role+"-"+time+".jpg";
        String archive=InstrumentationRegistry.getArguments().getString("candidateDex");Mat source;
        try(ZipFile zip=new ZipFile(archive);InputStream input=zip.getInputStream(Objects.requireNonNull(zip.getEntry("assets/replay/phase1011/"+name),name))){
            MatOfByte encoded=new MatOfByte(input.readAllBytes());try{source=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_COLOR);}finally{encoded.release();}
        }
        assertFalse(source.empty());Mat scaled=new Mat(),result=new Mat(400,640,CvType.CV_8UC3,new Scalar(180,180,180));
        try{Imgproc.resize(source,scaled,new Size(640,360),0,0,Imgproc.INTER_AREA);Mat roi=result.submat(new Rect(0,20,640,360));try{scaled.copyTo(roi);}finally{roi.release();}Imgproc.cvtColor(result,result,Imgproc.COLOR_BGR2RGB);return result;}finally{source.release();scaled.release();}
    }
    private static void begin(int attempt){DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.recovery((attempt&1)==1);}

    @Test public void phase10LoosePartsAndRealBoardBottomRequireCorrectOrientation()throws Exception {
        phase10(new String[]{"cable","protector","extension","extensionbad"},new int[][]{{51,79},{9,27},{94,104},{120,129}},new int[]{1,3,2,2},new int[]{1,1,1,-1});
    }
    @Test public void auditRotatedExtensionBodyCorrespondences()throws Exception {
        Context c=context();VisionConfig config=VisionConfig.defaults();List<ComponentReferenceSpec> bank=new ArrayList<>();
        for(ComponentReferenceSpec s:Phase1011VideoReferences.all())if(Phase1011VideoReferences.annotation(s.getJpegAssetPath()).role.equals("extension"))bank.add(s);
        Mat rgb=raw(10,"extension",104),gray=new Mat(),work=new Mat(),descriptors=new Mat(),empty=new Mat();MatOfKeyPoint keys=new MatOfKeyPoint();
        org.opencv.features2d.ORB orb=org.opencv.features2d.ORB.create(3200,1.2f,8,31,0,2,org.opencv.features2d.ORB.HARRIS_SCORE,31,10);org.opencv.imgproc.CLAHE clahe=OpenCvVisionUtils.createClahe(config);
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),bank,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase10ReferenceMatcher inspector=new Phase10ReferenceMatcher(db,config)) {
            Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);clahe.apply(gray,work);orb.detectAndCompute(work,empty,keys,descriptors);KeyPoint[] live=keys.toArray();
            java.lang.reflect.Method match=Phase10ReferenceMatcher.class.getDeclaredMethod("match",ReferenceDatabase.PreparedReference.class,ReferenceDatabase.PreparedOrbVariant.class,KeyPoint[].class,Mat.class,double.class,int.class,Mat.class);match.setAccessible(true);
            for(ReferenceDatabase.PreparedReference ref:db.preparedReferences())for(ReferenceDatabase.PreparedOrbVariant v:ref.orbVariants()){
                begin(0);DiagnosticHammingMatcher diagnostic=new DiagnosticHammingMatcher();try{
                    List<DMatch> good=diagnostic.ratioMatches(v.descriptors,descriptors,.76,64);good.sort(Comparator.comparingDouble(m->m.distance));Set<Integer> used=new HashSet<>();List<Point> a=new ArrayList<>(),b=new ArrayList<>();for(DMatch m:good)if(used.add(m.trainIdx)){a.add(v.keypoints[m.queryIdx].pt);b.add(live[m.trainIdx].pt);}
                    MatOfPoint2f source=new MatOfPoint2f(),target=new MatOfPoint2f();Mat mask=new Mat(),h=null;
                    try{int n=0;double l=99999,t=l,r=0,bottom=0;if(a.size()>=4){source.fromList(a);target.fromList(b);h=org.opencv.calib3d.Calib3d.findHomography(source,target,org.opencv.calib3d.Calib3d.RANSAC,3.5,mask,1800,.995);if(!h.empty()){n=Core.countNonZero(mask);for(int i=0;i<a.size();i++)if(mask.get(i,0)[0]!=0){l=Math.min(l,a.get(i).x);r=Math.max(r,a.get(i).x);t=Math.min(t,a.get(i).y);bottom=Math.max(bottom,a.get(i).y);}}}
                        Object found=match.invoke(inspector,ref,v,live,descriptors,1.0,2,gray);
                        android.util.Log.i("Phase1011Audit",ref.spec.getJpegAssetPath()+" rows="+v.descriptors.rows()+" good="+a.size()+" inliers="+n+" span="+(r-l)+","+(bottom-t)+" accepted="+(found!=null));
                    }finally{source.release();target.release();mask.release();if(h!=null)h.release();}
                }finally{diagnostic.clear();DiagnosticHammingMatcher.end();}
            }
        }finally{rgb.release();gray.release();work.release();descriptors.release();empty.release();keys.release();orb.clear();clahe.clear();}
    }
    @Test public void phase10ProtectorAssemblyRejectsMissingProtectorAndMissingTopBoard()throws Exception {
        phase10(new String[]{"protected","missingprotector","missingtop"},new int[][]{{159,190},{94},{14,47}},new int[]{4,4,5},new int[]{1,0,0});
    }
    private void phase10(String[] roles,int[][] times,int[] stages,int[] expectations)throws Exception {
        Context c=context();VisionConfig config=VisionConfig.defaults();List<String> failures=new ArrayList<>();
        for(int row=0;row<roles.length;row++) {
            final int stage=stages[row];List<ComponentReferenceSpec> bank=new ArrayList<>();
            for(ComponentReferenceSpec s:Phase10AssetCatalog.references(c.getAssets()))if(Phase10AssetCatalog.eligible(Phase10AssetCatalog.referenceRole(s.getReferenceId()),stage))bank.add(s);
            try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),bank,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase10ReferenceMatcher matcher=new Phase10ReferenceMatcher(db,config)) {
                for(int time:times[row]) {
                    Mat rgb=raw(10,roles[row],time),gray=new Mat();boolean found=false;
                    try{Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
                        for(int a=0;a<8;a++) {begin(a);RecognitionResult r=matcher.inspect(gray,a+1,stage);Phase10Observation o=r.getPhase10Observation();
                            android.util.Log.i("Phase1011Video","P10 "+roles[row]+" "+time+" attempt="+a+" state="+o.state+" score="+o.score+" ms="+r.getLatency().getTotalMs());
                            if(expectations[row]<=0)assertFalse("Incorrect P10 "+roles[row]+" "+time+" cannot pass",o.accepted());
                            if(expectations[row]>0&&o.accepted()||expectations[row]<0&&o.flipped()){found=true;movingOutline(gray,r);break;}
                        }
                        if(expectations[row]!=0&&!found)failures.add(roles[row]+" "+time);
                    }finally{rgb.release();gray.release();DiagnosticHammingMatcher.end();}
                }
            }
        }
        assertTrue("Unmatched Phase 10 views "+failures,failures.isEmpty());
    }
    @Test public void phase11LooseOledBatteryAndBackFaceKeepTheirIdentity()throws Exception {
        phase11(new String[]{"battery","front","back"},new int[][]{{9,29},{44,52},{65,72}},new int[]{2,1,1},new int[]{1,1,-1});
    }
    @Test public void phase11MountedOledRequiresIndependentModuleAndBoardEvidence()throws Exception {
        phase11(new String[]{"assembly","missingoled"},new int[][]{{83,92,118},{159,190}},new int[]{3,3},new int[]{1,0});
    }
    @Test public void motionBlurredBatteryKeepsHonestConfidence()throws Exception {
        Context c=context();VisionConfig config=VisionConfig.defaults();List<ComponentReferenceSpec> bank=new ArrayList<>();
        for(ComponentReferenceSpec s:Phase11AssetCatalog.references(c.getAssets()))if(Phase11AssetCatalog.eligible(Phase11AssetCatalog.referenceRole(s.getReferenceId()),2))bank.add(s);
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),bank,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase11ReferenceMatcher matcher=new Phase11ReferenceMatcher(db,config)) {
            Mat rgb=raw(11,"batteryblur",30),gray=new Mat();boolean recognized=false;
            try{Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);for(int a=0;a<4;a++){begin(a);RecognitionResult result=matcher.inspect(gray,a+1,2);if(result.getPhase11Observation().accepted()){assertTrue(result.getPhase11Observation().score>=12);movingOutline(gray,result);recognized=true;break;}}
                android.util.Log.i("Phase1011Video","Motion-blurred battery 30 recognized="+recognized+"; excluded from clear-view recognition successes. No forced identity.");
            }finally{rgb.release();gray.release();DiagnosticHammingMatcher.end();}
        }
    }
    private void phase11(String[] roles,int[][] times,int[] stages,int[] expectations)throws Exception {
        Context c=context();VisionConfig config=VisionConfig.defaults();List<String> failures=new ArrayList<>();
        for(int row=0;row<roles.length;row++) {
            final int stage=stages[row];List<ComponentReferenceSpec> bank=new ArrayList<>();
            for(ComponentReferenceSpec s:Phase11AssetCatalog.references(c.getAssets()))if(Phase11AssetCatalog.eligible(Phase11AssetCatalog.referenceRole(s.getReferenceId()),stage))bank.add(s);
            try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),bank,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));Phase11ReferenceMatcher matcher=new Phase11ReferenceMatcher(db,config)) {
                for(int time:times[row]) {Mat rgb=raw(11,roles[row],time),gray=new Mat();boolean found=false;
                    try{Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
                        for(int a=0;a<8;a++){begin(a);RecognitionResult r=matcher.inspect(gray,a+1,stage);Phase11Observation o=r.getPhase11Observation();
                            android.util.Log.i("Phase1011Video","P11 "+roles[row]+" "+time+" attempt="+a+" state="+o.state+" score="+o.score+" board="+o.boardInliers+" ms="+r.getLatency().getTotalMs());
                            if(expectations[row]<=0)assertFalse("Incorrect P11 "+roles[row]+" "+time+" cannot pass",o.accepted());
                            if(expectations[row]>0&&o.accepted()||expectations[row]<0&&o.state==Phase11Observation.State.FLIPPED){found=true;movingOutline(gray,r);break;}
                        }
                        if(expectations[row]!=0&&!found)failures.add(roles[row]+" "+time);
                    }finally{rgb.release();gray.release();DiagnosticHammingMatcher.end();}
                }
            }
        }
        assertTrue("Unmatched Phase 11 views "+failures,failures.isEmpty());
    }
    @Test public void individualToolShowsOutlinesBeforeSuccessForEveryNewLoosePart()throws Exception {
        String[] ids={"extension_data_cable","oled_protector","extension_circuit_board","oled_096_i2c","battery_pack_7v_12v"};
        String[] roles={"cable","protector","extension","front","battery"};int[] times={51,9,94,44,9};
        Context c=context();VisionConfig config=VisionConfig.defaults();List<String> failed=new ArrayList<>();
        for(int i=0;i<ids.length;i++) {String id=ids[i];List<ComponentReferenceSpec> bank=new ArrayList<>();
            for(ComponentReferenceSpec s:Phase1011VideoReferences.individual())if(id.equals(s.getComponentId()))bank.add(s);
            try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),bank,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(config,db)) {
                RecognitionRequest request=IndividualPartTestCatalog.requestFor(id,CameraCalibration.pinhole(700,700,320,200),.001).withRequestTag(id);
                java.util.concurrent.atomic.AtomicReference<RecognitionResult> latest=new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.atomic.AtomicReference<TargetBoundingTracker.Target> outline=new java.util.concurrent.atomic.AtomicReference<>();List<String> order=new ArrayList<>();
                try(AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,f->request,new AssemblyVisionAnalyzer.Callback(){public void onResult(RecognitionResult r){order.add("result");latest.set(r);}public void onTargetTracking(TargetBoundingTracker.Target t,AssemblyVisionAnalyzer.FrameMetadata f,Object tag){if(t!=null){order.add("outline");outline.set(t);}}},config)) {
                    Mat rgb=raw(i<3?10:11,roles[i],times[i]);
                    try{java.lang.reflect.Method image=Class.forName("dev.chyken.assembl_ar.AssemblyReplayHarness",true,getClass().getClassLoader()).getDeclaredMethod("image",Mat.class,long.class);image.setAccessible(true);long now=android.os.SystemClock.elapsedRealtimeNanos();
                        for(int a=0;a<8;a++){analyzer.analyzeForReplay((androidx.camera.core.ImageProxy)image.invoke(null,rgb,now+a*1_000_000_000L));if(latest.get()!=null&&latest.get().getValidationState()==RecognitionResult.ValidationState.EXPECTED_COMPONENT)break;}
                        boolean match=latest.get()!=null&&latest.get().getValidationState()==RecognitionResult.ValidationState.EXPECTED_COMPONENT;
                        android.util.Log.i("Phase1011Video","individual "+id+" matched="+match+" outline="+(outline.get()!=null));
                        if(!match)failed.add(id);else {assertNotNull(outline.get());assertTrue(order.indexOf("outline")<order.lastIndexOf("result"));}
                    }finally{rgb.release();}
                }
            }
        }
        assertTrue("Individual unmatched "+failed,failed.isEmpty());
    }
    private static void movingOutline(Mat gray,RecognitionResult result) {
        assertEquals("Matched hardware requires an outline",4,result.getProjectedCorners().size());
        try(TargetBoundingTracker tracker=new TargetBoundingTracker()) {
            TargetBoundingTracker.State state=result.getValidationState()==RecognitionResult.ValidationState.WRONG_ORIENTATION?TargetBoundingTracker.State.WRONG:TargetBoundingTracker.State.LOCKED;
            assertNotNull(tracker.detect(gray,1,result.getComponentId(),result.getDisplayName(),result.getDisplayName(),state,Collections.singletonList(result.getProjectedCorners()),true));
            Mat transform=Mat.eye(2,3,CvType.CV_64F),moved=new Mat();
            try{transform.put(0,2,8);transform.put(1,2,3);Imgproc.warpAffine(gray,moved,transform,gray.size());TargetBoundingTracker.Target tracked=tracker.frame(moved,50_000_001L,null);assertNotNull(tracked);assertFalse("Outline must move using measured optical flow",tracked.predicted);}finally{transform.release();moved.release();}
        }
    }
}
