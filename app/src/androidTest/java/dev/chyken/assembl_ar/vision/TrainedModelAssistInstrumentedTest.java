package dev.chyken.assembl_ar.vision;

import android.content.*;
import android.os.SystemClock;
import android.util.Log;
import dev.chyken.assembl_ar.camera.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.junit.*;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import static org.junit.Assert.*;

/** Candidate APK assets and native tablet runtime; never advances learner progress. */
public class TrainedModelAssistInstrumentedTest {
    @SuppressWarnings("unchecked") private TrainedModelAssist.Hints qualifiedPredictions(TrainedModelAssist assist,Mat first,Mat second,int phase)throws Exception{
        Method prepare=TrainedModelAssist.class.getDeclaredMethod("prepare",int.class),infer=TrainedModelAssist.class.getDeclaredMethod("inferDetections",Mat.class);
        prepare.setAccessible(true);infer.setAccessible(true);prepare.invoke(assist,phase);
        infer.invoke(assist,first); // Native graph warmup is not temporal evidence.
        long before=SystemClock.elapsedRealtimeNanos();List<TrainedDetection> previous=(List<TrainedDetection>)infer.invoke(assist,first);
        Thread.sleep(160);long source=SystemClock.elapsedRealtimeNanos(),started=source;
        List<TrainedDetection> current=(List<TrainedDetection>)infer.invoke(assist,second);
        current=TrainedDetection.confirm(current,previous,source,before);List<Rect> boxes=new ArrayList<>();for(TrainedDetection d:current)boxes.add(d.bounds);
        return new TrainedModelAssist.Hints(second.cols(),second.rows(),source,(SystemClock.elapsedRealtimeNanos()-started)/1e6,boxes,current);
    }
    @Test public void priorityModelIdentityOwnsRealNanoAndWheelsDespiteBrightnessChange()throws Exception{
        Context c=context();JSONArray fixtures=cases(c);int accepted=0;
        try(TrainedModelAssist assist=new TrainedModelAssist(c)){
            for(int index:new int[]{1,3}){
                JSONObject fixture=fixtures.getJSONObject(index);int phase=fixture.getInt("phase");String id=phase==5?"arduino_nano_microcontroller":"drive_wheel";
                String path=phase==5?"references/Phase5/STEP5_1ARDUINONANO/var_light/VIDEO35_JlWZAbKu_nano_front_4.jpg":fixture.getString("path");
                Mat rgb=photo(c,path),brighter=new Mat(),gray=new Mat();
                try{
                    rgb.convertTo(brighter,-1,1.1,8);Imgproc.cvtColor(brighter,gray,Imgproc.COLOR_RGB2GRAY);
                    TrainedModelAssist.Hints hints=qualifiedPredictions(assist,rgb,brighter,phase);
                    RecognitionRequest r=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id),null,null,null,phase==9?2:1,0);
                    RecognitionResult result=TrainedRecognitionAuthority.recognize(r,hints,gray,brighter,SystemClock.elapsedRealtimeNanos());
                    Log.i("TrainedPriorityTest","real_identity phase="+phase+" detections="+hints.detections.size()+" accepted="+(result!=null));
                    assertNotNull("A real strong model must own loose identity without ORB: phase "+phase,result);
                    assertEquals(0,result.getInlierCount());assertNull(result.getPose());assertTrue(VerifiedScanEvidence.workflowAccepted(result,r));accepted++;
                    DiagnosticHammingMatcher.begin();
                    dev.chyken.assembl_ar.diagnostics.DiagnosticSample sample=dev.chyken.assembl_ar.diagnostics.DiagnosticSession.INSTANCE.record(result,r,gray,false);
                    assertTrue(sample.telemetry.workflowVerified);assertFalse(sample.telemetry.poseMeasured);assertTrue(sample.telemetry.verificationSource.startsWith("ONNX"));
                    assertNotNull(sample.telemetry.trainedEvidence);assertEquals(-1,sample.telemetry.goodMatches);
                }finally{rgb.release();brighter.release();gray.release();DiagnosticHammingMatcher.end();}
            }
        }
        assertEquals(2,accepted);
    }
    @Test public void priorityAuthorityRejectsRealBackFacesWrongBoardsAndIncompleteSets()throws Exception{
        Context c=context();JSONArray fixtures;
        try(InputStream in=c.getAssets().open("models/trained/replay_negatives.json")){fixtures=new JSONArray(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        try(TrainedModelAssist assist=new TrainedModelAssist(c)){
            for(int index=0;index<fixtures.length();index++){
                JSONObject f=fixtures.getJSONObject(index);int phase=f.getInt("phase");String id=phase==3?"dual_motor_driver":phase==5?"arduino_nano_microcontroller":phase==7?"caster_wheel":phase==9?"drive_wheel":phase==10?"extension_circuit_board":"oled_096_i2c";
                RecognitionRequest r=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id),null,null,null,phase==9?2:1,phase);
                r=phase==3?r.withPhase3Stage(1):phase==5?r.withPhase5Stage(1):phase==7?r.withPhase7Stage(1):phase==9?r.withPhase9Stage(2):phase==10?r.withPhase10Stage(2):r.withPhase11Stage(1);
                Mat rgb=photo(c,f.getString("path")),dim=new Mat(),gray=new Mat();
                try{rgb.convertTo(dim,-1,.8,0);Imgproc.cvtColor(dim,gray,Imgproc.COLOR_RGB2GRAY);
                    TrainedModelAssist.Hints hints=qualifiedPredictions(assist,rgb,dim,phase);
                    assertNull("Model identity must not skip negative face/count evidence: phase "+phase,TrainedRecognitionAuthority.recognize(r,hints,gray,dim,SystemClock.elapsedRealtimeNanos()));
                }finally{rgb.release();dim.release();gray.release();}
            }
            int[] models={7,8,9,10,11},inputs={1,0,5,0,0};
            String[] components={"line_tracker_module","ultrasonic_distance_sensor","center_standoff","extension_circuit_board","oled_096_i2c"};
            JSONArray positives=cases(c);
            for(int i=0;i<models.length;i++){
                Mat rgb=photo(c,positives.getJSONObject(inputs[i]).getString("path")),gray=new Mat();
                try{Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);TrainedModelAssist.Hints hints=qualifiedPredictions(assist,rgb,rgb,models[i]);
                    RecognitionRequest generic=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(components[i]),null,null);
                    assertNull("A high-score prediction on another kit component must not pass: "+components[i],TrainedRecognitionAuthority.recognize(generic,hints,gray,rgb,SystemClock.elapsedRealtimeNanos()));
                }finally{rgb.release();gray.release();}
            }
        }
    }
    @Test public void sharedAnalyzerCanConfirmNanoWithoutMatchingOrbReferences()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();
        RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("arduino_nano_microcontroller"),null,null);
        AtomicReference<RecognitionResult> latest=new AtomicReference<>();List<Throwable> errors=new ArrayList<>();Mat rgb=photo(c,"references/Phase5/STEP5_1ARDUINONANO/var_light/VIDEO35_JlWZAbKu_nano_front_4.jpg");
        // Only a bottom-face Nano photo is loaded. It cannot establish this front view.
        List<ComponentReferenceSpec> refs=new ArrayList<>();
        for(ComponentReferenceSpec spec:Phase5AssetCatalog.references(c.getAssets()))if(spec.getJpegAssetPath().contains("MICROCONTROLLER_BOTTOMINVALID")){refs.add(spec);break;}
        assertEquals(1,refs.size());
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));
                ComponentRecognizer recognizer=new ComponentRecognizer(config,db);AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,f->request,new AssemblyVisionAnalyzer.Callback(){
                    public void onResult(RecognitionResult r){latest.set(r);}public void onError(Throwable t){errors.add(t);}},config)){
            analyzer.attachMotion(c);analyzer.preloadTrainedPhase(5);
            Field field=AssemblyVisionAnalyzer.class.getDeclaredField("trainedAssist");field.setAccessible(true);idle((TrainedModelAssist)field.get(analyzer));
            long start=SystemClock.elapsedRealtime();
            for(int attempt=0;attempt<180;attempt++){
                // Feed CameraX continuously while its serial detector is busy. The
                // production tracking path supplies fresh inference pixels meanwhile.
                analyzer.analyze(image(rgb,SystemClock.elapsedRealtimeNanos()));RecognitionResult r=latest.get();
                if(r!=null&&r.getDetectionMethod()==RecognitionResult.DetectionMethod.TRAINED_MODEL)break;
                Thread.sleep(40);
            }
            assertTrue(errors.toString(),errors.isEmpty());assertNotNull(latest.get());assertEquals(RecognitionResult.DetectionMethod.TRAINED_MODEL,latest.get().getDetectionMethod());
            assertTrue(VerifiedScanEvidence.workflowAccepted(latest.get(),request));assertNull(latest.get().getPose());
            Log.i("TrainedPriorityTest","shared_analyzer_identity_without_matching_references elapsedMs="+(SystemClock.elapsedRealtime()-start));
        }finally{rgb.release();}
    }
    @BeforeClass public static void runtime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    private Context context()throws Exception{
        Method m=Phase12VideoReplayInstrumentedTest.class.getDeclaredMethod("context");m.setAccessible(true);
        Context source=(Context)m.invoke(new Phase12VideoReplayInstrumentedTest());
        return new ContextWrapper(source){
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("trained_assist_fixture_"+name,mode);}
        };
    }
    private RecognitionRequest driver(){return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("dual_motor_driver"),null,null,null,1,3).withPhase3Stage(1);}
    private JSONArray cases(Context c)throws Exception{
        try(InputStream in=c.getAssets().open("models/trained/replay_cases.json")){return new JSONArray(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
    }
    private Mat photo(Context c,String path)throws Exception{
        MatOfByte bytes;try(InputStream in=c.getAssets().open(path)){bytes=new MatOfByte(in.readAllBytes());}
        Mat bgr=null,rgb=new Mat();
        try{bgr=Imgcodecs.imdecode(bytes,Imgcodecs.IMREAD_COLOR);assertFalse(path,bgr.empty());
            double scale=Math.min(1,640.0/Math.max(bgr.cols(),bgr.rows()));
            // The shared camera fixture encodes I420, whose width/height must be even.
            Imgproc.resize(bgr,rgb,new Size(Math.max(2,Math.round(bgr.cols()*scale)/2*2),Math.max(2,Math.round(bgr.rows()*scale)/2*2)),0,0,Imgproc.INTER_AREA);
            Imgproc.cvtColor(rgb,rgb,Imgproc.COLOR_BGR2RGB);return rgb;
        }catch(Throwable failure){rgb.release();throw failure;}finally{bytes.release();if(bgr!=null)bgr.release();}
    }
    @Test public void guidedAndIndividualRoutesExecuteTheirActualModelWithoutRestart()throws Exception{
        Context c=context();Mat rgb=new Mat(400,640,CvType.CV_8UC3,Scalar.all(150)),gray=new Mat();Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
        String[] ids={"main_board","motor_bracket","gear_motor_6v","dual_motor_driver","arduino_nano_microcontroller","caster_wheel",
                "line_tracker_module","ultrasonic_distance_sensor","center_standoff","drive_wheel","extension_data_cable","extension_circuit_board","oled_protector","oled_096_i2c","battery_pack_7v_12v"};
        int[] phases={1,2,2,3,5,7,7,8,9,9,10,10,10,11,11};
        Field loaded=TrainedModelAssist.class.getDeclaredField("loadedPhase"),output=TrainedModelAssist.class.getDeclaredField("completed");loaded.setAccessible(true);output.setAccessible(true);
        try(TrainedModelAssist assist=new TrainedModelAssist(c)){
            for(int route=0;route<2;route++)for(int i=0;i<ids.length;i++){
                RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(ids[i]),null,null,null,1,route==0?phases[i]:0).withRequestTag(route+":"+i);
                assist.scope(request);assertTrue(ids[i],assist.handles(request));assertTrue("New checkpoint must immediately admit a request",assist.due(request));
                assist.submit(request,rgb,gray,SystemClock.elapsedRealtimeNanos());idle(assist);
                assertEquals(ids[i],phases[i],loaded.getInt(assist));assertNotNull("Even empty predictions must publish completion",((AtomicReference<?>)output.get(assist)).get());
            }
            RecognitionRequest top=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("main_board"),null,null,null,1,2).withPhase2Stage(5).withPhase2VerificationView("top").withRequestTag("top");
            assist.scope(top);assist.submit(top,rgb,gray,SystemClock.elapsedRealtimeNanos());idle(assist);assertEquals(2,loaded.getInt(assist));
        }finally{rgb.release();gray.release();}
    }
    @Test public void proposalExtractionKeepsBothPhysicalPartsAndWholeSceneContext(){
        Mat gray=new Mat(480,640,CvType.CV_8UC1);Core.randu(gray,0,255);
        org.opencv.features2d.ORB orb=EdgeGradientDescriptorExtractor.createOrb();MatOfKeyPoint keys=new MatOfKeyPoint();Mat descriptors=new Mat();
        try(EdgeGradientDescriptorExtractor.Workspace workspace=new EdgeGradientDescriptorExtractor.Workspace()){
            workspace.extractProposals(gray,Arrays.asList(new Rect(0,0,200,200),new Rect(420,260,200,200)),orb,keys,descriptors);
            KeyPoint[] points=keys.toArray();int left=0,right=0;Set<Long> distinct=new HashSet<>();
            for(KeyPoint p:points){if(p.pt.x<200&&p.pt.y<200)left++;if(p.pt.x>420&&p.pt.y>260)right++;
                assertTrue("Overlapping windows cannot duplicate the same physical point",distinct.add(((long)Math.round(p.pt.x*4)<<32)|(Math.round(p.pt.y*4)&0xffffffffL)));}
            assertTrue(left>=20);assertTrue(right>=20);assertEquals(points.length,descriptors.rows());assertTrue(points.length<=3*LiveVisionPolicy.MAX_ROI_KEYPOINTS);
        }finally{gray.release();keys.release();descriptors.release();orb.clear();}
    }
    @Test public void proposalDescriptorsCannotPoisonTheFollowingFullFrameSearch()throws Exception{
        Context c=context();RecognitionRequest request=driver();VisionConfig config=VisionConfig.defaults();
        List<ComponentReferenceSpec> refs=ActiveReferenceScope.select(Phase3AssetCatalog.references(c.getAssets()),request);
        Mat rgb=photo(c,cases(c).getJSONObject(0).getString("path")),gray=new Mat(),original=new Mat();Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(config,db);
                EdgeGradientDescriptorExtractor.Workspace workspace=new EdgeGradientDescriptorExtractor.Workspace()){
            DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.State state=DiagnosticHammingMatcher.snapshot();state.fastLive=true;state.workspace=workspace;
            recognizer.recognize(gray,1,request);Field f=ComponentRecognizer.class.getDeclaredField("frameCache");f.setAccessible(true);
            ComponentRecognizer.FrameFeatureCache cache=(ComponentRecognizer.FrameFeatureCache)f.get(recognizer);cache.descriptors.copyTo(original);KeyPoint[] fullKeys=cache.keypoints;
            state.trainedHints=new TrainedModelAssist.Hints(gray.cols(),gray.rows(),1,1,Collections.singletonList(new Rect(0,0,100,100)));
            DiagnosticHammingMatcher.discardCandidateEvidence();state.trainedPass=true;
            try{recognizer.recognize(gray,2,request);}finally{state.trainedPass=false;}
            assertSame(fullKeys,cache.keypoints);assertEquals(0,Core.norm(original,cache.descriptors,Core.NORM_INF),0);assertTrue(cache.pairs.isEmpty());
            DiagnosticHammingMatcher.discardCandidateEvidence();recognizer.recognize(gray,3,request);assertSame("The following full search must reuse its own rows",fullKeys,cache.keypoints);
        }finally{rgb.release();gray.release();original.release();DiagnosticHammingMatcher.end();}
    }
    @Test public void boardInventoryHardwareAndNanoRecoverInOneContinuousAnalyzer()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();CameraCalibration calibration=CameraCalibration.pinhole(700,700,320,200);
        List<ComponentReferenceSpec> refs=new ArrayList<>(Phase12VideoReferences.all());refs.addAll(Phase5AssetCatalog.references(c.getAssets()));
        AtomicReference<RecognitionRequest> current=new AtomicReference<>();AtomicReference<RecognitionResult> latest=new AtomicReference<>();List<Throwable> errors=new ArrayList<>();
        Method raw=Phase12VideoReplayInstrumentedTest.class.getDeclaredMethod("raw",String.class,int.class);raw.setAccessible(true);
        String[] roles={"board","brackets","motors","top","left","right","nano"};int[] seconds={18,8,19,13,28,40,0};
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(config,db);
                AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,f->current.get(),new AssemblyVisionAnalyzer.Callback(){
                    public void onResult(RecognitionResult result){latest.set(result);}public void onError(Throwable failure){errors.add(failure);}},config)){
            analyzer.attachMotion(c);long warmHeap=0;
            for(int cycle=0;cycle<2;cycle++)for(int step=0;step<roles.length;step++){
                String id=step==1?"motor_bracket":step==2?"gear_motor_6v":step==6?"arduino_nano_microcontroller":"main_board";
                RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id),calibration,null,null,step==1?4:step==2?2:1,step==0?1:step==6?5:2).withRequestTag(cycle+":"+roles[step]);
                if(step==1||step==2)request=request.withPhase2Stage(step);else if(step>=3&&step<=5)request=request.withPhase2Stage(5).withPhase2VerificationView(roles[step]);else if(step==6)request=request.withPhase5Stage(1);
                current.set(request);latest.set(null);Mat rgb=step==6?photo(c,cases(c).getJSONObject(1).getString("path")):(Mat)raw.invoke(new Phase12VideoReplayInstrumentedTest(),roles[step],seconds[step]);
                try{
                    long started=SystemClock.elapsedRealtime();boolean accepted=false;
                    for(int attempt=0;attempt<8;attempt++){
                        analyzer.analyzeForReplay(image(rgb,SystemClock.elapsedRealtimeNanos()));RecognitionResult result=latest.get();
                        if(VerifiedScanEvidence.workflowAccepted(result,request)
                                &&(step<3||step>5||roles[step].equals(result.getPhase2Observation().view))){accepted=true;break;}
                        Thread.sleep(120);
                    }
                    assertTrue("Continuous scan failed: cycle="+cycle+" checkpoint="+roles[step]+" errors="+errors,accepted);
                    assertEquals(request.getRequestTag(),latest.get().getRequestTag());
                    Log.i("TrainedAssistTest","continuous cycle="+cycle+" checkpoint="+roles[step]+" elapsedMs="+(SystemClock.elapsedRealtime()-started)+" nativeHeap="+android.os.Debug.getNativeHeapAllocatedSize());
                    if(cycle==0&&step==6)warmHeap=android.os.Debug.getNativeHeapAllocatedSize();
                    if(cycle==1&&step==6)assertTrue("Repeated scans must stay within a bounded native memory budget",android.os.Debug.getNativeHeapAllocatedSize()-warmHeap<128L*1024*1024);
                }finally{rgb.release();}
            }
            assertTrue("No background error may require a rescan",errors.isEmpty());
        }
    }
    @Test public void allNinePackagedModelsLoadAndExecuteOnAndroid()throws Exception{
        Context c=context();Mat rgb=new Mat(400,640,CvType.CV_8UC3,new Scalar(150,150,150));
        Method prepare=TrainedModelAssist.class.getDeclaredMethod("prepare",int.class),infer=TrainedModelAssist.class.getDeclaredMethod("infer",Mat.class,String.class);
        prepare.setAccessible(true);infer.setAccessible(true);
        try(TrainedModelAssist assist=new TrainedModelAssist(c)){
            for(int phase:new int[]{1,2,3,5,7,8,9,10,11}){
                long start=SystemClock.elapsedRealtimeNanos();prepare.invoke(assist,phase);
                assertNotNull(infer.invoke(assist,rgb,"unrelated-test-component"));
                Log.i("TrainedAssistTest","native_contract phase="+phase+" ms="+(SystemClock.elapsedRealtimeNanos()-start)/1e6);
            }
        }finally{rgb.release();}
    }
    @Test public void proposalCannotPublishIdentityAndFailureRetriesOriginalSearch(){
        DiagnosticHammingMatcher.begin();
        try{
            DiagnosticHammingMatcher.State s=DiagnosticHammingMatcher.snapshot();
            s.trainedHints=new TrainedModelAssist.Hints(640,400,1,12,Collections.singletonList(new Rect(0,0,20,20)));
            AtomicInteger calls=new AtomicInteger();
            RecognitionResult missing=RecognitionResult.builder(1).identity("fixture","dual_motor_driver","Driver")
                    .validation(RecognitionResult.ValidationState.NO_COMPONENT).build();
            RecognitionResult actual=TrainedRoiSearch.inspect(driver(),()->{calls.incrementAndGet();return missing;});
            assertSame(missing,actual);assertEquals(2,calls.get());assertFalse(s.trainedPass);
            assertNull(s.live);assertTrue(s.fits.isEmpty());assertFalse(VerifiedScanEvidence.workflowAccepted(actual,driver()));
            s.trainedHints=new TrainedModelAssist.Hints(640,400,1,12,Collections.emptyList());calls.set(0);
            TrainedRoiSearch.inspect(driver(),()->{calls.incrementAndGet();return missing;});
            assertEquals("An empty detection must not duplicate the full search",1,calls.get());
        }finally{DiagnosticHammingMatcher.end();}
    }
    @Test public void expiryAndMotionChecksNeverReuseAnOldPhysicalLocation(){
        TrainedModelAssist.Hints hints=new TrainedModelAssist.Hints(1920,1080,1,20,Collections.singletonList(new Rect(1400,500,240,180)));
        Rect scaled=hints.windows(640,360).get(0);
        assertTrue(scaled.x>=0&&scaled.y>=0&&scaled.x+scaled.width<=640&&scaled.y+scaled.height<=360);
        assertTrue(scaled.x>400);assertTrue(scaled.width<150);
        assertFalse(TrainedModelPolicy.fresh(1,TrainedModelPolicy.MAX_AGE_NS+2,true,640,400,640,400,0));
        assertFalse(TrainedModelPolicy.fresh(1,2,true,640,400,640,400,40));
    }
    @Test public void queuedFrameIsReleasedAndCannotPublishAfterPauseOrClose()throws Exception{
        Context c=context();Mat rgb=photo(c,cases(c).getJSONObject(0).getString("path")),gray=new Mat();Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
        TrainedModelAssist assist=new TrainedModelAssist(c);
        Field worker=TrainedModelAssist.class.getDeclaredField("worker"),busy=TrainedModelAssist.class.getDeclaredField("busy"),completed=TrainedModelAssist.class.getDeclaredField("completed");
        worker.setAccessible(true);busy.setAccessible(true);completed.setAccessible(true);
        try{
            assist.scope(driver());assist.submit(driver(),rgb,gray,SystemClock.elapsedRealtimeNanos());assist.reset();assist.close();
            assertTrue(((ExecutorService)worker.get(assist)).awaitTermination(30,TimeUnit.SECONDS));
            assertFalse(((AtomicBoolean)busy.get(assist)).get());assertNull(((AtomicReference<?>)completed.get(assist)).get());
        }finally{assist.close();rgb.release();gray.release();}
    }
    @Test public void backgroundResultBelongsToOneRunAndIsConsumedOnlyOnce()throws Exception{
        Context c=context();Mat rgb=photo(c,cases(c).getJSONObject(1).getString("path")),gray=new Mat();Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
        RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("arduino_nano_microcontroller"),null,null,null,1,5).withPhase5Stage(1).withRequestTag("run-a");
        Field busy=TrainedModelAssist.class.getDeclaredField("busy"),completed=TrainedModelAssist.class.getDeclaredField("completed");busy.setAccessible(true);completed.setAccessible(true);
        try(TrainedModelAssist assist=new TrainedModelAssist(c)){
            assist.scope(request);long source=SystemClock.elapsedRealtimeNanos();assist.submit(request,rgb,gray,source);
            long deadline=SystemClock.elapsedRealtime()+30_000;
            while(((AtomicBoolean)busy.get(assist)).get()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(20);
            assertFalse("One background request must terminate",((AtomicBoolean)busy.get(assist)).get());
            AtomicReference<?> output=(AtomicReference<?>)completed.get(assist);
            assertNotNull("The worker must publish its result, including an empty detection",output.get());
            Object published=output.get();Field hintsField=published.getClass().getDeclaredField("hints");hintsField.setAccessible(true);
            TrainedModelAssist.Hints publishedHints=(TrainedModelAssist.Hints)hintsField.get(published);
            // A deterministic replay frame uses an explicit capture timestamp. Wall-clock
            // expiry is tested separately; this checks the asynchronous ownership path.
            TrainedModelAssist.Hints hints=assist.take(request,gray,source+1);
            if(publishedHints.windows(gray.cols(),gray.rows()).isEmpty()){
                assertNull("An empty model result cannot start a proposal search",hints);assertEquals(0,assist.consumedHints);
            }else{assertNotNull(hints);assertEquals(source,hints.sourceNanos);assertEquals(1,assist.consumedHints);}
            assertNull("Taking any result clears its publication",output.get());
            assertNull("A consumed result cannot be replayed",assist.take(request,gray,source+2));
            assist.scope(request.withRequestTag("run-b"));
            assertNull(assist.take(request.withRequestTag("run-b"),gray,source+3));
        }finally{rgb.release();gray.release();}
    }
    @Test public void missingModelDisablesOnlyAssistanceAndReleasesItsFrame()throws Exception{
        Context source=context();android.content.res.AssetManager empty=android.content.res.Resources.getSystem().getAssets();
        Context c=new ContextWrapper(source){
            @Override public Context getApplicationContext(){return this;}
            @Override public android.content.res.AssetManager getAssets(){return empty;}
        };
        Mat rgb=new Mat(400,640,CvType.CV_8UC3,Scalar.all(150)),gray=new Mat(400,640,CvType.CV_8UC1,Scalar.all(150));
        try(TrainedModelAssist assist=new TrainedModelAssist(c)){
            assist.scope(driver());assertTrue(assist.handles(driver()));assist.submit(driver(),rgb,gray,SystemClock.elapsedRealtimeNanos());idle(assist);
            assertFalse("An unavailable model must stop being retried",assist.handles(driver()));
            assertNull(assist.take(driver(),gray,SystemClock.elapsedRealtimeNanos()));
        }finally{rgb.release();gray.release();}
    }
    @Test public void realDriverBadHintFallsBackToVerifiedPhotoGeometry()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();RecognitionRequest request=driver();
        List<ComponentReferenceSpec> references=ActiveReferenceScope.select(Phase3AssetCatalog.references(c.getAssets()),request);
        Mat rgb=photo(c,cases(c).getJSONObject(0).getString("path")),gray=new Mat();Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),references,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));
                ComponentRecognizer recognizer=new ComponentRecognizer(config,db);EdgeGradientDescriptorExtractor.Workspace workspace=new EdgeGradientDescriptorExtractor.Workspace()){
            DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.State s=DiagnosticHammingMatcher.snapshot();s.fastLive=true;s.workspace=workspace;
            s.adaptiveDetail=true;s.scope="trained-bad-hint-profile";
            s.trainedHints=new TrainedModelAssist.Hints(gray.cols(),gray.rows(),1,20,Collections.singletonList(new Rect(0,0,20,20)));
            Field profiles=ComponentRecognizer.class.getDeclaredField("detailedScopes");profiles.setAccessible(true);
            Set<?> detailProfiles=(Set<?>)profiles.get(recognizer);AtomicInteger fallbackCalls=new AtomicInteger(),proposalCalls=new AtomicInteger();
            RecognitionResult result=TrainedRoiSearch.inspect(request,()->{
                RecognitionResult observed=recognizer.inspectPhase3(gray,1,1);
                if(s.trainedPass){proposalCalls.incrementAndGet();assertFalse("A proposal alone cannot change the persistent detail profile",detailProfiles.contains(s.scope));}
                else fallbackCalls.incrementAndGet();
                return observed;
            });
            assertTrue("Bad proposal must preserve full photo verification",VerifiedScanEvidence.workflowAccepted(result,request));
            assertTrue(result.hasLocatedComponent());assertEquals(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,result.getDetectionMethod());
            assertEquals(1,proposalCalls.get());assertTrue("At most one unchanged full search follows the proposal",fallbackCalls.get()<=1);
            // The full-frame hybrid search may legitimately retain a successful detail
            // profile. Only the preceding neural trial is forbidden from changing it.
        }finally{rgb.release();gray.release();DiagnosticHammingMatcher.end();}
    }
    private androidx.camera.core.ImageProxy image(Mat rgb,long time)throws Exception{
        Method m=Class.forName("dev.chyken.assembl_ar.AssemblyReplayHarness",true,getClass().getClassLoader()).getDeclaredMethod("image",Mat.class,long.class);
        m.setAccessible(true);return (androidx.camera.core.ImageProxy)m.invoke(null,rgb,time);
    }
    private void idle(TrainedModelAssist assist)throws Exception{
        Field f=TrainedModelAssist.class.getDeclaredField("busy");f.setAccessible(true);long deadline=SystemClock.elapsedRealtime()+30_000;
        while(((AtomicBoolean)f.get(assist)).get()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(20);
        assertFalse(((AtomicBoolean)f.get(assist)).get());
    }
    @Test public void fastVerifiedNanoReceivesPriorityQueryAndAutomaticFallback()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();
        RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("arduino_nano_microcontroller"),null,null,null,1,5).withPhase5Stage(1);
        List<ComponentReferenceSpec> refs=ActiveReferenceScope.select(Phase5AssetCatalog.references(c.getAssets()),request);
        Mat rgb=photo(c,cases(c).getJSONObject(1).getString("path"));AtomicReference<RecognitionResult> latest=new AtomicReference<>();
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(config,db);
                AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,f->request,r->latest.set(r),config)){
            analyzer.attachMotion(c);analyzer.analyzeForReplay(image(rgb,SystemClock.elapsedRealtimeNanos()));
            Field client=AssemblyVisionAnalyzer.class.getDeclaredField("trainedAssist");client.setAccessible(true);TrainedModelAssist assist=(TrainedModelAssist)client.get(analyzer);
            Field query=TrainedModelAssist.class.getDeclaredField("lastQuery");query.setAccessible(true);
            assertNotEquals("ONNX starts before any photo failure",Long.MIN_VALUE,query.getLong(assist));
            for(int attempt=0;attempt<12&&!VerifiedScanEvidence.workflowAccepted(latest.get(),request);attempt++){
                Thread.sleep(160);analyzer.analyzeForReplay(image(rgb,SystemClock.elapsedRealtimeNanos()));
            }
            assertTrue("Model identity or the bounded original verifier must resolve without Rescan",VerifiedScanEvidence.workflowAccepted(latest.get(),request));
        }finally{rgb.release();}
    }
    @Test public void cameraRecoveryUsesFreshPixelsAndPreservesTheVerifier()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();
        RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("arduino_nano_microcontroller"),null,null,null,1,5).withPhase5Stage(1);
        List<ComponentReferenceSpec> refs=ActiveReferenceScope.select(Phase5AssetCatalog.references(c.getAssets()),request);
        Mat rgb=photo(c,cases(c).getJSONObject(1).getString("path")),blank=new Mat(rgb.size(),CvType.CV_8UC3,Scalar.all(180));
        AtomicReference<RecognitionResult> result=new AtomicReference<>();
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(config,db);
                AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,f->request,new AssemblyVisionAnalyzer.Callback(){public void onResult(RecognitionResult r){result.set(r);}},config)){
            analyzer.attachMotion(c);analyzer.analyzeForReplay(image(blank,SystemClock.elapsedRealtimeNanos()));
            Field client=AssemblyVisionAnalyzer.class.getDeclaredField("trainedAssist"),admission=AssemblyVisionAnalyzer.class.getDeclaredField("detectorBusy");client.setAccessible(true);admission.setAccessible(true);
            TrainedModelAssist assist=(TrainedModelAssist)client.get(analyzer);idle(assist);assist.reset();assist.scope(request);
            Thread.sleep(275); // Fast successful frames are allowed to bypass neural work.
            AtomicBoolean busy=(AtomicBoolean)admission.get(analyzer);busy.set(true);
            try{analyzer.analyze(image(rgb,SystemClock.elapsedRealtimeNanos()));}finally{busy.set(false);}
            for(int attempt=0;attempt<3&&assist.consumedHints==0;attempt++){
                idle(assist);result.set(null);analyzer.analyzeForReplay(image(rgb,SystemClock.elapsedRealtimeNanos()));
            }
            assertTrue("Fresh CameraX recovery must reach the detector",assist.consumedHints>0);
            assertNotNull(result.get());assertTrue(VerifiedScanEvidence.workflowAccepted(result.get(),request));
            assertEquals(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,result.get().getDetectionMethod());
        }finally{rgb.release();blank.release();}
    }
    @Test public void expiredColdResultRetriesCurrentPixelsWithoutManualReset()throws Exception{
        Context c=context();Mat rgb=photo(c,cases(c).getJSONObject(1).getString("path")),gray=new Mat();Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
        RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("arduino_nano_microcontroller"),null,null,null,1,5).withPhase5Stage(1);
        try(TrainedModelAssist assist=new TrainedModelAssist(c)){
            assist.scope(request);assist.submit(request,rgb,gray,1);idle(assist);
            assertNull("An expired model result must never reach verification",assist.take(request,gray,SystemClock.elapsedRealtimeNanos()));
            assertTrue("Expired cold results must reopen current-frame admission without Rescan",assist.due(request));
            assist.submit(request,rgb,gray,SystemClock.elapsedRealtimeNanos());idle(assist);
            assertTrue("A second automatic request must finish normally",assist.handles(request));
        }finally{rgb.release();gray.release();}
    }
    @Test public void initialPhotoPassDoesNotWaitForTheTrainedWorker()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();
        RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("arduino_nano_microcontroller"),null,null,null,1,5).withPhase5Stage(1);
        List<ComponentReferenceSpec> refs=ActiveReferenceScope.select(Phase5AssetCatalog.references(c.getAssets()),request);
        Mat blank=new Mat(400,640,CvType.CV_8UC3,Scalar.all(180));
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();AtomicInteger delivered=new AtomicInteger();
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(config,db);
                AssemblyVisionAnalyzer analyzer=new AssemblyVisionAnalyzer(recognizer,f->request,new AssemblyVisionAnalyzer.Callback(){public void onResult(RecognitionResult r){delivered.incrementAndGet();}},config)){
            analyzer.attachMotion(c);
            Field client=AssemblyVisionAnalyzer.class.getDeclaredField("trainedAssist");client.setAccessible(true);
            TrainedModelAssist assist=(TrainedModelAssist)client.get(analyzer);Field worker=TrainedModelAssist.class.getDeclaredField("worker");worker.setAccessible(true);
            ((ExecutorService)worker.get(assist)).execute(()->{held.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(held.await(3,TimeUnit.SECONDS));
            executor.execute(()->{try{analyzer.analyzeForReplay(image(blank,SystemClock.elapsedRealtimeNanos()));}catch(Throwable t){failure.set(t);}finally{finished.countDown();}});
            boolean nonBlocking=finished.await(3,TimeUnit.SECONDS);release.countDown();assertTrue(finished.await(15,TimeUnit.SECONDS));
            assertNull(failure.get());assertTrue("The initial photo pass waited for the recovery locator monitor",nonBlocking);
            assertEquals("A pending neural query must not impose a wait before the original result",1,delivered.get());
        }finally{release.countDown();executor.shutdown();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));blank.release();}
    }
    @Test public void realLoosePhotosCompareProposalAndFullSearchTimings()throws Exception{
        Context c=context();JSONArray cases=cases(c);VisionConfig config=VisionConfig.defaults();
        Method prepare=TrainedModelAssist.class.getDeclaredMethod("prepare",int.class),infer=TrainedModelAssist.class.getDeclaredMethod("infer",Mat.class,String.class);prepare.setAccessible(true);infer.setAccessible(true);
        try(TrainedModelAssist assist=new TrainedModelAssist(c)){
            for(int index=0;index<cases.length();index++){
                JSONObject fixture=cases.getJSONObject(index);int phase=fixture.getInt("phase");
                String id=phase==3?"dual_motor_driver":phase==5?"arduino_nano_microcontroller":phase==7?"caster_wheel":phase==9?"drive_wheel":phase==10?"oled_protector":"oled_096_i2c";
                RecognitionRequest base=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id),null,null,null,phase==9?2:1,phase);
                RecognitionRequest request=phase==3?base.withPhase3Stage(1):phase==5?base.withPhase5Stage(1):phase==7?base.withPhase7Stage(1):phase==9?base.withPhase9Stage(2):phase==10?base.withPhase10Stage(3):base.withPhase11Stage(1);
                List<ComponentReferenceSpec> refs=phase==3?Phase3AssetCatalog.references(c.getAssets()):phase==5?Phase5AssetCatalog.references(c.getAssets()):phase==7?Phase7AssetCatalog.references(c.getAssets()):phase==9?Phase9AssetCatalog.references(c.getAssets()):phase==10?Phase10AssetCatalog.references(c.getAssets()):Phase11AssetCatalog.references(c.getAssets());
                refs=ActiveReferenceScope.select(refs,request);Mat rgb=photo(c,fixture.getString("path")),gray=new Mat();Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
                try{
                    prepare.invoke(assist,phase);long inferenceStart=SystemClock.elapsedRealtimeNanos();
                    @SuppressWarnings("unchecked") List<Rect> boxes=(List<Rect>)infer.invoke(assist,rgb,id);
                    double inferenceMs=(SystemClock.elapsedRealtimeNanos()-inferenceStart)/1e6;
                    for(int mode=0;mode<2;mode++)try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(config,db);EdgeGradientDescriptorExtractor.Workspace workspace=new EdgeGradientDescriptorExtractor.Workspace()){
                        DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.State s=DiagnosticHammingMatcher.snapshot();s.fastLive=true;s.workspace=workspace;
                        if(mode==1&&!boxes.isEmpty())s.trainedHints=new TrainedModelAssist.Hints(gray.cols(),gray.rows(),1,inferenceMs,boxes);
                        long start=SystemClock.elapsedRealtimeNanos();RecognitionResult result=TrainedRoiSearch.inspect(request,()->{
                            switch(phase){case 3:return recognizer.inspectPhase3(gray,1,1);case 5:return recognizer.inspectPhase5(gray,1,1);case 7:return recognizer.inspectPhase7(gray,rgb,1,1);case 9:return recognizer.inspectPhase9(gray,rgb,1,2);case 10:return recognizer.inspectPhase10(gray,rgb,1,3);default:return recognizer.inspectPhase11(gray,1,1);}
                        });
                        Log.i("TrainedAssistTest","compare phase="+phase+" assisted="+(mode==1)+" proposals="+boxes.size()+" inferenceMs="+inferenceMs+" verifyMs="+(SystemClock.elapsedRealtimeNanos()-start)/1e6+" accepted="+VerifiedScanEvidence.workflowAccepted(result,request));
                    }finally{DiagnosticHammingMatcher.end();}
                }finally{rgb.release();gray.release();}
            }
        }
    }
    @Test public void reversedFacesWrongBoardsAndOneWheelCannotPassFromHints()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();JSONArray fixtures;
        try(InputStream in=c.getAssets().open("models/trained/replay_negatives.json")){fixtures=new JSONArray(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        Method prepare=TrainedModelAssist.class.getDeclaredMethod("prepare",int.class),infer=TrainedModelAssist.class.getDeclaredMethod("infer",Mat.class,String.class);prepare.setAccessible(true);infer.setAccessible(true);
        try(TrainedModelAssist assist=new TrainedModelAssist(c)){
            for(int index=0;index<fixtures.length();index++){
                JSONObject fixture=fixtures.getJSONObject(index);int phase=fixture.getInt("phase");
                String id=phase==3?"dual_motor_driver":phase==5?"arduino_nano_microcontroller":phase==7?"caster_wheel":phase==9?"drive_wheel":phase==10?"extension_circuit_board":"oled_096_i2c";
                RecognitionRequest base=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id),null,null,null,phase==9?2:1,phase);
                RecognitionRequest request=phase==3?base.withPhase3Stage(1):phase==5?base.withPhase5Stage(1):phase==7?base.withPhase7Stage(1):phase==9?base.withPhase9Stage(2):phase==10?base.withPhase10Stage(2):base.withPhase11Stage(1);
                List<ComponentReferenceSpec> refs=phase==3?Phase3AssetCatalog.references(c.getAssets()):phase==5?Phase5AssetCatalog.references(c.getAssets()):phase==7?Phase7AssetCatalog.references(c.getAssets()):phase==9?Phase9AssetCatalog.references(c.getAssets()):phase==10?Phase10AssetCatalog.references(c.getAssets()):Phase11AssetCatalog.references(c.getAssets());
                Mat rgb=photo(c,fixture.getString("path")),gray=new Mat();Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
                try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),ActiveReferenceScope.select(refs,request),config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"));ComponentRecognizer recognizer=new ComponentRecognizer(config,db);EdgeGradientDescriptorExtractor.Workspace workspace=new EdgeGradientDescriptorExtractor.Workspace()){
                    prepare.invoke(assist,phase);
                    @SuppressWarnings("unchecked") List<Rect> boxes=(List<Rect>)infer.invoke(assist,rgb,id);
                    if(boxes.isEmpty())boxes.add(new Rect(0,0,gray.cols(),gray.rows()));
                    if(phase==9)boxes.add(boxes.get(0));
                    DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.State s=DiagnosticHammingMatcher.snapshot();s.fastLive=true;s.workspace=workspace;
                    s.trainedHints=new TrainedModelAssist.Hints(gray.cols(),gray.rows(),1,1,boxes);
                    RecognitionResult result=TrainedRoiSearch.inspect(request,()->{
                        switch(phase){case 3:return recognizer.inspectPhase3(gray,1,1);case 5:return recognizer.inspectPhase5(gray,1,1);case 7:return recognizer.inspectPhase7(gray,rgb,1,1);case 9:return recognizer.inspectPhase9(gray,rgb,1,2);case 10:return recognizer.inspectPhase10(gray,rgb,1,2);default:return recognizer.inspectPhase11(gray,1,1);}
                    });
                    assertFalse("Invalid hardware must not pass phase "+phase,VerifiedScanEvidence.workflowAccepted(result,request));
                    Log.i("TrainedAssistTest","negative phase="+phase+" state="+result.getValidationState()+" pass=false");
                }finally{DiagnosticHammingMatcher.end();rgb.release();gray.release();}
            }
        }
    }
    @Test public void variedFramesMeasureWarmVerificationWithoutRepeatingOneCachedImage()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();JSONObject fixtures;
        try(InputStream in=c.getAssets().open("models/trained/replay_benchmark.json")){fixtures=new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        Method prepare=TrainedModelAssist.class.getDeclaredMethod("prepare",int.class),infer=TrainedModelAssist.class.getDeclaredMethod("infer",Mat.class,String.class);prepare.setAccessible(true);infer.setAccessible(true);
        try(TrainedModelAssist assist=new TrainedModelAssist(c)){
            for(int phase:new int[]{5,9}){
                String id=phase==5?"arduino_nano_microcontroller":"drive_wheel";
                RecognitionRequest base=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id),null,null,null,phase==5?1:2,phase);
                RecognitionRequest request=phase==5?base.withPhase5Stage(1):base.withPhase9Stage(2);
                List<ComponentReferenceSpec> refs=ActiveReferenceScope.select(phase==5?Phase5AssetCatalog.references(c.getAssets()):Phase9AssetCatalog.references(c.getAssets()),request);
                List<Mat> colors=new ArrayList<>(),grays=new ArrayList<>();List<List<Rect>> boxes=new ArrayList<>();
                List<Double> inferenceTimes=new ArrayList<>(),plain=new ArrayList<>(),assisted=new ArrayList<>();boolean[][] accepted=new boolean[2][6];
                try{
                    prepare.invoke(assist,phase);JSONArray paths=fixtures.getJSONArray(Integer.toString(phase));assertEquals(3,paths.length());
                    for(int i=0;i<3;i++){
                        Mat rgb=photo(c,paths.getString(i)),gray=new Mat();Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);colors.add(rgb);grays.add(gray);
                        long start=SystemClock.elapsedRealtimeNanos();
                        @SuppressWarnings("unchecked") List<Rect> regions=(List<Rect>)infer.invoke(assist,rgb,id);boxes.add(regions);inferenceTimes.add((SystemClock.elapsedRealtimeNanos()-start)/1e6);
                    }
                    try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),refs,config,new File(c.getNoBackupFilesDir(),"orb_reference_cache_v3"))){
                        // Reverse mode order in round two; use three different images and
                        // an excluded warmup for each fresh recognizer/workspace.
                        for(int round=0;round<2;round++)for(int slot=0;slot<2;slot++){
                            int mode=round==0?slot:1-slot;
                            try(ComponentRecognizer recognizer=new ComponentRecognizer(config,db);EdgeGradientDescriptorExtractor.Workspace workspace=new EdgeGradientDescriptorExtractor.Workspace()){
                                for(int sample=-1;sample<3;sample++){
                                    int index=sample<0?2:sample;Mat gray=grays.get(index),rgb=colors.get(index);
                                    DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.State s=DiagnosticHammingMatcher.snapshot();s.fastLive=true;s.workspace=workspace;
                                    if(mode==1&&!boxes.get(index).isEmpty())s.trainedHints=new TrainedModelAssist.Hints(gray.cols(),gray.rows(),1,inferenceTimes.get(index),boxes.get(index));
                                    long start=SystemClock.elapsedRealtimeNanos();RecognitionResult result=TrainedRoiSearch.inspect(request,()->phase==5?recognizer.inspectPhase5(gray,1,1):recognizer.inspectPhase9(gray,rgb,1,2));
                                    double ms=(SystemClock.elapsedRealtimeNanos()-start)/1e6;
                                    if(sample>=0){(mode==0?plain:assisted).add(ms);accepted[mode][round*3+sample]=VerifiedScanEvidence.workflowAccepted(result,request);}
                                }
                            }finally{DiagnosticHammingMatcher.end();}
                        }
                    }
                    for(int i=0;i<6;i++)assertFalse("Assistance regressed a baseline acceptance at phase "+phase,accepted[0][i]&&!accepted[1][i]);
                    Log.i("TrainedAssistTest","varied phase="+phase+" baselineMs="+plain+" assistedMs="+assisted+" inferenceMs="+inferenceTimes+" frames=3 rounds=2");
                }finally{for(Mat m:colors)m.release();for(Mat m:grays)m.release();}
            }
        }
    }
}
