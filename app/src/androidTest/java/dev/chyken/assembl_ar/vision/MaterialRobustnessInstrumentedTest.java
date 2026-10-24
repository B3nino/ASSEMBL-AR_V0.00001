package dev.chyken.assembl_ar.vision;

import android.content.Context;
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

/** Held-out photo transformations are synthetic robustness checks, not academic
 * accuracy measurements or proof of performance in an unobserved physical room. */
public final class MaterialRobustnessInstrumentedTest {
    private final Context app=testContext();
    private static Context testContext(){
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String candidate=InstrumentationRegistry.getArguments().getString("candidateDex");
        if(candidate==null)return target;
        Context isolated=target.createConfigurationContext(new android.content.res.Configuration(target.getResources().getConfiguration()));
        try{
            android.content.res.loader.ResourcesLoader loader=new android.content.res.loader.ResourcesLoader();
            try(android.os.ParcelFileDescriptor fd=android.os.ParcelFileDescriptor.open(new File(candidate),android.os.ParcelFileDescriptor.MODE_READ_ONLY)){
                loader.addProvider(android.content.res.loader.ResourcesProvider.loadFromApk(fd));
            }
            isolated.getResources().addLoaders(loader);return isolated;
        }catch(IOException e){throw new AssertionError(e);}
    }
    private final VisionConfig config=VisionConfig.defaults();
    @Before public void openCv(){assertTrue(OpenCVLoader.initLocal());}
    @After public void releaseProbe(){DiagnosticHammingMatcher.end();}
    @Test public void aFastMeasuredOutlineIsVisibleWithoutInventingPersistentFlow(){
        Mat gray=new Mat(240,320,CvType.CV_8UC1,new Scalar(160));
        List<VisionPoint> box=box(80,70,140,120);
        try(TargetBoundingTracker tracker=new TargetBoundingTracker()){
            TargetBoundingTracker.Target measured=tracker.detect(gray,100_000_000L,"oled_display","OLED","OLED",TargetBoundingTracker.State.LOCKED,Collections.singletonList(box),true);
            assertNotNull("Render fresh measured ORB geometry even if LK has too few corners",measured);assertTrue(measured.verified);assertFalse(measured.predicted);
            assertNull("No screen-fixed polygon after measured evidence expires",tracker.frame(gray,500_000_000L,null));
        }finally{gray.release();}
    }
    @Test public void aNewlyConfirmedThirdPartCanJoinAnAlreadyMovingPair(){
        Mat gray=new Mat(240,320,CvType.CV_8UC1,new Scalar(180));Core.setRNGSeed(812);
        List<List<VisionPoint>> boxes=Arrays.asList(box(35,50,90,170),box(220,50,275,170),box(125,55,190,110));
        for(List<VisionPoint> b:boxes){Mat patch=gray.submat((int)b.get(0).getY(),(int)b.get(2).getY(),(int)b.get(0).getX(),(int)b.get(2).getX());try{Core.randu(patch,20,220);}finally{patch.release();}}
        try(TargetBoundingTracker tracker=new TargetBoundingTracker()){
            assertEquals(2,tracker.detect(gray,100_000_000L,"line_tracker_module","Assembly","Assembly",TargetBoundingTracker.State.LOCKED,boxes.subList(0,2),false).regions.size());
            assertNotNull(tracker.frame(gray,180_000_000L,null));
            TargetBoundingTracker.Target complete=tracker.detect(gray,150_000_000L,"line_tracker_module","Assembly","Assembly",TargetBoundingTracker.State.LOCKED,boxes,true);
            assertNotNull(complete);assertEquals("A delayed confirmation must not permanently retain only two outlines",3,complete.regions.size());assertTrue(complete.verified);
        }finally{gray.release();}
    }
    @Test public void installedDriverKeepsOrientationUnderShadowAndRejectsMissingDriver()throws Exception{
        List<ComponentReferenceSpec> bank=new ArrayList<>();for(ComponentReferenceSpec s:Phase3AssetCatalog.references(app.getAssets()))if(Phase3AssetCatalog.installed(Phase3AssetCatalog.referenceRole(s.getReferenceId())))bank.add(s);
        ComponentReferenceSpec probe=bank.remove(0);byte[] bytes=bytes(probe);ComponentReferenceSpec geometry=Phase3PhotoGeometry.resolve(probe,bytes);
        try(ReferenceDatabase db=ReferenceDatabase.load(app.getAssets(),bank,config,cache());Phase3ReferenceMatcher matcher=new Phase3ReferenceMatcher(db,config)){
            Mat photo=read(bytes),gray=new Mat(),shadow=new Mat();
            try{
                Imgproc.cvtColor(photo,gray,Imgproc.COLOR_BGR2GRAY);gray.convertTo(shadow,-1,.50,8);boolean accepted=false;
                for(int i=0;i<10;i++){begin(i);RecognitionResult result=matcher.inspect(shadow,100+i,4);if(result.getPhase3Observation().accepted(4)){accepted=true;assertEquals(4,result.getProjectedCorners().size());break;}}
                assertTrue("Held-out installed driver in shadow",accepted);
                List<VisionPoint> roi=geometry.getPlanarCornersNormalized();
                int x=(int)(roi.get(0).getX()*shadow.cols()),y=(int)(roi.get(0).getY()*shadow.rows()),r=(int)(roi.get(2).getX()*shadow.cols()),b=(int)(roi.get(2).getY()*shadow.rows());
                Mat reversed=shadow.clone(),patch=reversed.submat(y,b,x,r),copy=patch.clone();
                try{Core.rotate(copy,patch,Core.ROTATE_180);boolean corrected=false;for(int i=0;i<8;i++){begin(i);Phase3Observation o=matcher.inspect(reversed,150+i,4).getPhase3Observation();assertFalse("Reversed driver cannot pass placement",o.accepted(4));corrected|=o.state==Phase3Observation.State.BACKWARD;}assertTrue("Reversed module needs an explicit rotation correction",corrected);}
                finally{reversed.release();patch.release();copy.release();}
                paint(shadow,geometry.getPlanarCornersNormalized());
                for(int i=0;i<4;i++){begin(i);assertFalse("Unchanged board cannot replace driver",matcher.inspect(shadow,200+i,4).getPhase3Observation().accepted(4));}
            }finally{photo.release();gray.release();shadow.release();}
        }
    }
    @Test public void installedSensorHandlesPartialHighlightAndRejectsMissingSensor()throws Exception{
        List<ComponentReferenceSpec> bank=new ArrayList<>();for(ComponentReferenceSpec s:Phase8AssetCatalog.references(app.getAssets()))if(Phase8AssetCatalog.installed(Phase8AssetCatalog.referenceRole(s.getReferenceId())))bank.add(s);
        ComponentReferenceSpec probe=null;for(ComponentReferenceSpec s:bank)if("assembly".equals(Phase8AssetCatalog.referenceRole(s.getReferenceId()))){probe=s;break;}assertNotNull(probe);bank.remove(probe);
        byte[] bytes=bytes(probe);ComponentReferenceSpec geometry=Phase8PhotoGeometry.resolve(probe,bytes);
        try(ReferenceDatabase db=ReferenceDatabase.load(app.getAssets(),bank,config,cache());Phase8ReferenceMatcher matcher=new Phase8ReferenceMatcher(db,config)){
            Mat photo=read(bytes),gray=new Mat();
            try{
                Imgproc.cvtColor(photo,gray,Imgproc.COLOR_BGR2GRAY);gray.convertTo(gray,-1,.65,12);
                List<VisionPoint> roi=geometry.getPlanarCornersNormalized();double l=roi.get(0).getX()*gray.cols(),t=roi.get(0).getY()*gray.rows(),w=(roi.get(2).getX()-roi.get(0).getX())*gray.cols(),h=(roi.get(2).getY()-roi.get(0).getY())*gray.rows();
                Imgproc.line(gray,new Point(l+w*.20,t+h*.15),new Point(l+w*.20,t+h*.52),new Scalar(245),2);
                boolean accepted=false;for(int i=0;i<10;i++){begin(i);if(matcher.inspect(gray,100+i,2).getPhase8Observation().accepted(2)){accepted=true;break;}}
                assertTrue("Sensor with partial metallic highlight",accepted);paint(gray,roi);
                for(int i=0;i<4;i++){begin(i);assertFalse("Board without transducers",matcher.inspect(gray,200+i,2).getPhase8Observation().accepted(2));}
            }finally{photo.release();gray.release();}
        }
    }
    @Test public void recordedDriverPlacementMustMatchAndRetainAnObjectOutline()throws Exception{
        String paths=InstrumentationRegistry.getArguments().getString("phase3Frame");assertNotNull("Supply opt-in raw Phase 3 captures, separated by semicolons",paths);
        boolean backward="BACKWARD".equals(InstrumentationRegistry.getArguments().getString("phase3Expected"));
        File root=app.getExternalFilesDir("measure").getCanonicalFile();List<ComponentReferenceSpec> bank=new ArrayList<>();
        for(ComponentReferenceSpec s:Phase3AssetCatalog.references(app.getAssets()))if(Phase3AssetCatalog.installed(Phase3AssetCatalog.referenceRole(s.getReferenceId())))bank.add(s);
        String folder="references/Phase3/STEP3_5ASSEMBLYSCAN/";List<String> tablets=new ArrayList<>();
        for(String name:app.getAssets().list(folder))if(name.startsWith("TABLET_DRIVER_PLACEMENT"))tablets.add(folder+name);
        assertEquals("Four bundled camera viewpoints",4,tablets.size());
        // A running test process may have cached an asset listing before its
        // candidate ResourcesLoader was attached. Open the packaged file directly.
        for(String tablet:tablets){try(InputStream check=app.getAssets().open(tablet)){assertTrue(check.read()>=0);}
        if(bank.stream().noneMatch(s->tablet.equals(s.getJpegAssetPath())))bank.addAll(Phase3AssetCatalog.referencesForPaths(Collections.singletonList(tablet)));}
        try(ReferenceDatabase db=ReferenceDatabase.load(app.getAssets(),bank,config,cache())){for(String path:paths.split(";")){
            assertEquals("All tablet assets must be prepared",4,db.preparedReferences().stream().filter(r->tablets.contains(r.spec.getJpegAssetPath())).count());
            File input=new File(path).getCanonicalFile();assertTrue(input.getPath().startsWith(root.getPath()+File.separator));
            Mat gray=Imgcodecs.imread(path,Imgcodecs.IMREAD_GRAYSCALE);assertFalse(gray.empty());
            try(Phase3ReferenceMatcher matcher=new Phase3ReferenceMatcher(db,config);TargetBoundingTracker tracker=new TargetBoundingTracker()){
            RecognitionResult matched=null;for(int i=0;i<6;i++){begin(i);RecognitionResult r=matcher.inspect(gray,100+i,4);android.util.Log.i("Phase3Replay","frame="+input.getName()+" attempt="+i+" state="+r.getPhase3Observation().state+" ms="+r.getLatency().getTotalMs());
                if(backward)assertFalse("Actual backward driver cannot unlock",r.getPhase3Observation().accepted(4));
                if(backward?r.getPhase3Observation().state==Phase3Observation.State.BACKWARD:r.getPhase3Observation().accepted(4)){matched=r;break;}}
            assertNotNull(backward?"Actual backward driver needs an explicit correction":"Actual installed driver must unlock",matched);assertEquals(4,matched.getProjectedCorners().size());
            if(backward)assertEquals(RecognitionResult.ValidationState.WRONG_ORIENTATION,matched.getValidationState());
            TargetBoundingTracker.Target original=tracker.detect(gray,200,"dual_motor_driver","Motor Driver","Motor Driver",backward?TargetBoundingTracker.State.WRONG:TargetBoundingTracker.State.LOCKED,Collections.singletonList(matched.getProjectedCorners()),!backward);
            assertNotNull("Even a fast confirmation must deliver measured bounds",original);
            Mat shifted=new Mat(),translation=new Mat(2,3,CvType.CV_64F);
            try{
                translation.put(0,0,1,0,24,0,1,12);Imgproc.warpAffine(gray,shifted,translation,gray.size(),Imgproc.INTER_LINEAR,Core.BORDER_CONSTANT,new Scalar(160));
                TargetBoundingTracker.Target moved=tracker.frame(shifted,80_000_200L,null);assertNotNull("Confirmed driver keeps an outline while panning",moved);
                assertFalse("Tracking must be measured, not a screen-fixed prediction",moved.predicted);
                double dx=0,dy=0;for(int i=0;i<4;i++){dx+=moved.regions.get(0).get(i).getX()-original.regions.get(0).get(i).getX();dy+=moved.regions.get(0).get(i).getY()-original.regions.get(0).get(i).getY();}
                assertEquals("Driver outline follows a 24 pixel pan",24,dx/4,3);assertEquals("Driver outline follows a 12 pixel pan",12,dy/4,3);
            }finally{shifted.release();translation.release();}
            }finally{gray.release();}
        }}
    }
    @Test public void occludedRecordedDriverCannotUnlockFromBoardContextAlone()throws Exception{
        String path=InstrumentationRegistry.getArguments().getString("phase3ObscuredFrame");assertNotNull(path);
        File input=new File(path).getCanonicalFile(),root=app.getExternalFilesDir("measure").getCanonicalFile();
        assertTrue(input.getPath().startsWith(root.getPath()+File.separator));
        List<ComponentReferenceSpec> bank=new ArrayList<>();
        for(ComponentReferenceSpec s:Phase3AssetCatalog.references(app.getAssets()))if(Phase3AssetCatalog.installed(Phase3AssetCatalog.referenceRole(s.getReferenceId())))bank.add(s);
        Mat gray=Imgcodecs.imread(path,Imgcodecs.IMREAD_GRAYSCALE);assertFalse(gray.empty());
        try(ReferenceDatabase db=ReferenceDatabase.load(app.getAssets(),bank,config,cache());Phase3ReferenceMatcher matcher=new Phase3ReferenceMatcher(db,config)){
            for(int i=0;i<6;i++){begin(i);RecognitionResult r=matcher.inspect(gray,100+i,4);
                android.util.Log.i("Phase3Replay","obscured="+input.getName()+" attempt="+i+" state="+r.getPhase3Observation().state+" ms="+r.getLatency().getTotalMs());
                assertFalse("An obstructed backward module must never pass from nearby PCB matches",r.getPhase3Observation().accepted(4));}
        }finally{gray.release();}
    }
    @Test public void actualBackwardDriverPhotosRejectAtTabletScaleAndCameraRotations()throws Exception{
        List<ComponentReferenceSpec> bank=new ArrayList<>(),wrong=new ArrayList<>();
        for(ComponentReferenceSpec s:Phase3AssetCatalog.references(app.getAssets()))if(Phase3AssetCatalog.installed(Phase3AssetCatalog.referenceRole(s.getReferenceId()))){bank.add(s);if("invalid".equals(Phase3AssetCatalog.referenceRole(s.getReferenceId())))wrong.add(s);}
        assertEquals("Physical backward-installation photos and two tablet calibrations",9,wrong.size());
        List<ComponentReferenceSpec> probes=Arrays.asList(wrong.get(0),wrong.get(3),wrong.get(6));bank.removeAll(probes);
        try(ReferenceDatabase db=ReferenceDatabase.load(app.getAssets(),bank,config,cache());Phase3ReferenceMatcher matcher=new Phase3ReferenceMatcher(db,config)){
            for(ComponentReferenceSpec probe:probes){Mat image=read(bytes(probe)),gray=new Mat(),camera=new Mat(),turned=new Mat();
                try{
                    Imgproc.cvtColor(image,gray,Imgproc.COLOR_BGR2GRAY);Imgproc.resize(gray,camera,new Size(640,480),0,0,Imgproc.INTER_AREA);
                    Mat crop=camera.submat(40,440,0,640);
                    try{for(int rotation:new int[]{-1,Core.ROTATE_180}){
                        if(rotation<0)crop.copyTo(turned);else Core.rotate(crop,turned,rotation);
                        boolean corrected=false;
                        for(int i=0;i<6;i++){begin(i);RecognitionResult r=matcher.inspect(turned,100+i,4);Phase3Observation o=r.getPhase3Observation();
                            android.util.Log.i("Phase3Replay","actualNegative="+probe.getJpegAssetPath()+" rotation="+rotation+" attempt="+i+" state="+o.state+" ms="+r.getLatency().getTotalMs());
                            assertFalse("Actual backward installation must never unlock",o.accepted(4));
                            if(o.state==Phase3Observation.State.BACKWARD){corrected=true;assertEquals(4,r.getProjectedCorners().size());assertEquals(RecognitionResult.ValidationState.WRONG_ORIENTATION,r.getValidationState());break;}
                        }
                        assertTrue("Need explicit rotate-driver correction for "+probe.getJpegAssetPath(),corrected);
                    }}finally{crop.release();}
                }finally{image.release();gray.release();camera.release();turned.release();}
            }
        }
    }
    @Test public void darkAchromaticTrackerPairRequiresCasterInTheSameFrame()throws Exception{
        List<ComponentReferenceSpec> bank=new ArrayList<>();
        for(ComponentReferenceSpec s:Phase7AssetCatalog.references(app.getAssets())){
            String role=Phase7AssetCatalog.referenceRole(s.getReferenceId());if("trackerwork".equals(role)||"trackers".equals(role))bank.add(s);
        }
        ComponentReferenceSpec probe=take(bank,"trackerwork",7);byte[] bytes=bytes(probe);
        ComponentReferenceSpec geometry=Phase7PhotoGeometry.resolve(probe,bytes);
        try(ReferenceDatabase db=ReferenceDatabase.load(app.getAssets(),bank,config,cache());Phase7ReferenceMatcher matcher=new Phase7ReferenceMatcher(db,config)){
            Mat photo=read(bytes),gray=new Mat(),rgb=new Mat();
            try{
                Imgproc.cvtColor(photo,gray,Imgproc.COLOR_BGR2GRAY);gray.convertTo(gray,-1,.45,8);
                Imgproc.cvtColor(gray,rgb,Imgproc.COLOR_GRAY2RGB);
                boolean accepted=false;Phase7Observation last=null;
                for(int i=0;i<16;i++){begin(i);last=matcher.inspect(gray,rgb,100+i,4).getPhase7Observation();if(last.accepted(4)){accepted=true;break;}}
                assertTrue("Dark pair + caster: bodies="+last.visibleTrackers+" mounts="+last.verifiedMounts+" caster="+last.casterPresent,accepted);
                // Erase only the caster, preserving both tracker bodies and their board mounts.
                paint(gray,geometry.getFeatureRegionsNormalized().get(6));Imgproc.cvtColor(gray,rgb,Imgproc.COLOR_GRAY2RGB);
                for(int i=0;i<8;i++){begin(i);assertFalse("No caster cannot pass final assembly",matcher.inspect(gray,rgb,200+i,4).getPhase7Observation().accepted(4));}
            }finally{photo.release();gray.release();rgb.release();}
        }
    }
    @Test public void desaturatedMetalStandoffStillNeedsLocalGeometry()throws Exception{
        List<ComponentReferenceSpec> bank=new ArrayList<>();for(ComponentReferenceSpec s:Phase9AssetCatalog.references(app.getAssets()))if(Phase9AssetCatalog.installed(Phase9AssetCatalog.referenceRole(s.getReferenceId())))bank.add(s);
        ComponentReferenceSpec probe=take(bank,"assembly",9);byte[] bytes=bytes(probe);ComponentReferenceSpec geometry=Phase9PhotoGeometry.resolve(probe,bytes);
        try(ReferenceDatabase db=ReferenceDatabase.load(app.getAssets(),bank,config,cache());Phase9ReferenceMatcher matcher=new Phase9ReferenceMatcher(db,config)){
            Mat photo=read(bytes),gray=new Mat(),rgb=new Mat();
            try{
                Imgproc.cvtColor(photo,gray,Imgproc.COLOR_BGR2GRAY);Imgproc.cvtColor(gray,rgb,Imgproc.COLOR_GRAY2RGB);
                Phase9Observation last=null;boolean accepted=false;
                for(int i=0;i<12;i++){begin(i);last=matcher.inspect(gray,rgb,100+i,3).getPhase9Observation();if(last.accepted(3)){accepted=true;break;}}
                assertTrue("Achromatic standoff must have local identity",accepted);
                paint(gray,geometry.getFeatureRegionsNormalized().get(2));Imgproc.cvtColor(gray,rgb,Imgproc.COLOR_GRAY2RGB);
                for(int i=0;i<6;i++){begin(i);assertFalse("PCB printing cannot replace the standoff",matcher.inspect(gray,rgb,200+i,3).getPhase9Observation().accepted(3));}
            }finally{photo.release();gray.release();rgb.release();}
        }
    }
    @Test public void assembledWheelCheckOutlinesAllThreePartsAndRejectsEitherMissingWheel()throws Exception{
        List<ComponentReferenceSpec> bank=new ArrayList<>();for(ComponentReferenceSpec s:Phase9AssetCatalog.references(app.getAssets()))if(Phase9AssetCatalog.installed(Phase9AssetCatalog.referenceRole(s.getReferenceId())))bank.add(s);
        ComponentReferenceSpec probe=take(bank,"assembly",9);byte[] bytes=bytes(probe);ComponentReferenceSpec geometry=Phase9PhotoGeometry.resolve(probe,bytes);
        try(ReferenceDatabase db=ReferenceDatabase.load(app.getAssets(),bank,config,cache());Phase9ReferenceMatcher matcher=new Phase9ReferenceMatcher(db,config)){
            Mat photo=read(bytes),gray=new Mat(),rgb=new Mat();
            try{
                Imgproc.cvtColor(photo,gray,Imgproc.COLOR_BGR2GRAY);Imgproc.cvtColor(photo,rgb,Imgproc.COLOR_BGR2RGB);RecognitionResult accepted=null;
                for(int i=0;i<12;i++){begin(i);RecognitionResult result=matcher.inspect(gray,rgb,100+i,4);if(result.getPhase9Observation().accepted(4)){accepted=result;break;}}
                assertNotNull("Both installed wheels and standoff",accepted);assertEquals("Three separately located bodies",3,accepted.getInstanceObservations().size());
                for(int side=0;side<2;side++){
                    Mat missing=gray.clone(),missingRgb=new Mat();
                    try{paint(missing,geometry.getFeatureRegionsNormalized().get(side));Imgproc.cvtColor(missing,missingRgb,Imgproc.COLOR_GRAY2RGB);
                        for(int i=0;i<5;i++){begin(i);assertFalse("Missing wheel side "+side,matcher.inspect(missing,missingRgb,200+i,4).getPhase9Observation().accepted(4));}
                    }finally{missing.release();missingRgb.release();}
                }
            }finally{photo.release();gray.release();rgb.release();}
        }
    }
    @Test public void cableAndProtectorRemainRecognizableAcrossExposureChanges()throws Exception{
        for(int stage:new int[]{1,3}){
            List<ComponentReferenceSpec> bank=new ArrayList<>();for(ComponentReferenceSpec s:Phase10AssetCatalog.references(app.getAssets()))if(Phase10AssetCatalog.eligible(Phase10AssetCatalog.referenceRole(s.getReferenceId()),stage))bank.add(s);
            ComponentReferenceSpec probe=bank.remove(0);
            try(ReferenceDatabase db=ReferenceDatabase.load(app.getAssets(),bank,config,cache());Phase10ReferenceMatcher matcher=new Phase10ReferenceMatcher(db,config)){
                Mat photo=read(bytes(probe)),gray=new Mat(),changed=new Mat();
                try{
                    Imgproc.cvtColor(photo,gray,Imgproc.COLOR_BGR2GRAY);
                    for(double gain:new double[]{.45,1.25}){
                        gray.convertTo(changed,-1,gain,12);boolean accepted=false;
                        for(int i=0;i<12;i++){begin(i);if(matcher.inspect(changed,100+i,stage).getPhase10Observation().accepted()){accepted=true;break;}}
                        assertTrue("Held-out Phase 10 stage "+stage+" gain "+gain,accepted);
                    }
                }finally{photo.release();gray.release();changed.release();}
            }
        }
    }
    @Test public void mountedProtectorUsesRelativeRailContrastAndRejectsBarePcb()throws Exception{
        List<ComponentReferenceSpec> all=Phase10AssetCatalog.references(app.getAssets()),bank=new ArrayList<>();
        ComponentReferenceSpec bare=null;
        for(ComponentReferenceSpec s:all){String role=Phase10AssetCatalog.referenceRole(s.getReferenceId());if(Phase10AssetCatalog.eligible(role,4))bank.add(s);if("extension".equals(role)&&bare==null)bare=s;}
        ComponentReferenceSpec probe=bank.remove(0);assertNotNull(bare);
        try(ReferenceDatabase db=ReferenceDatabase.load(app.getAssets(),bank,config,cache());Phase10ReferenceMatcher matcher=new Phase10ReferenceMatcher(db,config)){
            Mat photo=read(bytes(probe)),gray=new Mat(),bright=new Mat(),missing=read(bytes(bare)),bareGray=new Mat();
            try{
                Imgproc.cvtColor(photo,gray,Imgproc.COLOR_BGR2GRAY);gray.convertTo(bright,-1,1,80);boolean accepted=false;
                for(int i=0;i<12;i++){begin(i);if(matcher.inspect(bright,100+i,4).getPhase10Observation().accepted()){accepted=true;break;}}
                assertTrue("Bright protector rails",accepted);Imgproc.cvtColor(missing,bareGray,Imgproc.COLOR_BGR2GRAY);
                for(int i=0;i<6;i++){begin(i);assertFalse("Uncovered extension PCB",matcher.inspect(bareGray,200+i,4).getPhase10Observation().accepted());}
            }finally{photo.release();gray.release();bright.release();missing.release();bareGray.release();}
        }
    }
    @Test public void recordedNanoCameraAreasRequireFreshModuleAndPcbGeometry()throws Exception{
        String paths=InstrumentationRegistry.getArguments().getString("phase5Frame");assertNotNull("Supply opt-in camera frames or UI-masked recording test areas",paths);
        File root=app.getExternalFilesDir("measure").getCanonicalFile();List<ComponentReferenceSpec> bank=new ArrayList<>();
        for(ComponentReferenceSpec s:Phase5AssetCatalog.references(app.getAssets()))if(Phase5AssetCatalog.installed(Phase5AssetCatalog.referenceRole(s.getReferenceId())))bank.add(s);
        try(ReferenceDatabase db=ReferenceDatabase.load(app.getAssets(),bank,config,cache())){for(String path:paths.split(";")){
            File input=new File(path).getCanonicalFile();assertTrue(input.getPath().startsWith(root.getPath()+File.separator));
            Mat gray=Imgcodecs.imread(path,Imgcodecs.IMREAD_GRAYSCALE);assertFalse(gray.empty());
            try(Phase5ReferenceMatcher matcher=new Phase5ReferenceMatcher(db,config);TargetBoundingTracker tracker=new TargetBoundingTracker()){
                RecognitionResult found=null;for(int i=0;i<6;i++){begin(i);RecognitionResult r=matcher.inspect(gray,100+i,3);
                    android.util.Log.i("NanoLightingReplay","frame="+input.getName()+" attempt="+i+" state="+r.getPhase5Observation().state+" ms="+r.getLatency().getTotalMs());
                    if(r.getPhase5Observation().accepted(3)){found=r;break;}}
                assertNotNull("Nano and independent PCB context must verify this unobscured view",found);assertEquals(4,found.getProjectedCorners().size());
                TargetBoundingTracker.Target original=tracker.detect(gray,200,"arduino_nano_microcontroller","Arduino Nano","Arduino Nano",TargetBoundingTracker.State.LOCKED,Collections.singletonList(found.getProjectedCorners()),true);
                assertNotNull("A fast successful scan must still locate its outline",original);
                Mat shifted=new Mat(),translation=new Mat(2,3,CvType.CV_64F);
                try{
                    translation.put(0,0,1,0,18,0,1,8);Imgproc.warpAffine(gray,shifted,translation,gray.size(),Imgproc.INTER_LINEAR,Core.BORDER_CONSTANT,new Scalar(128));
                    TargetBoundingTracker.Target moved=tracker.frame(shifted,80_000_200L,null);
                    assertNotNull("Nano outline must follow a measured camera pan",moved);assertFalse("Do not substitute a frozen screen polygon",moved.predicted);
                    // Small motion intentionally uses corner EMA. Check that the
                    // displayed outline converges as measured LK frames arrive.
                    for(int tick=1;tick<8;tick++){
                        moved=tracker.frame(shifted,80_000_200L+tick*33_000_000L,null);
                        assertNotNull("Measured outline remains available during smoothing",moved);assertFalse(moved.predicted);
                    }
                    double dx=0,dy=0;for(int i=0;i<4;i++){dx+=moved.regions.get(0).get(i).getX()-original.regions.get(0).get(i).getX();dy+=moved.regions.get(0).get(i).getY()-original.regions.get(0).get(i).getY();}
                    assertEquals(18,dx/4,3);assertEquals(8,dy/4,3);
                }finally{shifted.release();translation.release();}
                Mat noContext=new Mat(gray.size(),gray.type(),new Scalar(128)),mask=Mat.zeros(gray.size(),CvType.CV_8UC1);MatOfPoint body=new MatOfPoint();
                try{
                    List<Point> polygon=new ArrayList<>();for(VisionPoint p:found.getProjectedCorners())polygon.add(new Point(p.getX(),p.getY()));body.fromList(polygon);Imgproc.fillConvexPoly(mask,body,new Scalar(255));gray.copyTo(noContext,mask);
                    boolean outline=false;for(int i=0;i<4;i++){begin(i);RecognitionResult r=matcher.inspect(noContext,250+i,3);assertFalse("Nano pixels cannot also prove an unseen mainboard",r.getPhase5Observation().accepted(3));outline|=r.hasLocatedComponent();}
                    assertTrue("A localized Nano remains available for an acquiring outline while PCB context is missing",outline);
                }finally{noContext.release();mask.release();body.release();}
                // Remove only the located Nano, retaining the surrounding board and
                // recording mask. A known good previous view cannot substitute for it.
                Mat missing=gray.clone();try{paint(missing,normalize(found.getProjectedCorners(),gray.cols(),gray.rows()));
                    for(int i=0;i<4;i++){begin(i);assertFalse("PCB context alone cannot pass a missing Nano",matcher.inspect(missing,300+i,3).getPhase5Observation().accepted(3));}
                }finally{missing.release();}
            }finally{gray.release();}
        }}
    }
    @Test public void nanoPlacementHeldOutPhotosKeepOrientationUnderLightingChanges()throws Exception{
        List<ComponentReferenceSpec> bank=new ArrayList<>();ComponentReferenceSpec positive=null,negative=null;
        for(ComponentReferenceSpec s:Phase5AssetCatalog.references(app.getAssets())){
            String role=Phase5AssetCatalog.referenceRole(s.getReferenceId());if(!Phase5AssetCatalog.installed(role))continue;
            if(positive==null&&role.equals("assembly")){positive=s;continue;}if(negative==null&&role.equals("invalid")){negative=s;continue;}bank.add(s);
        }
        assertNotNull(positive);assertNotNull(negative);
        try(ReferenceDatabase db=ReferenceDatabase.load(app.getAssets(),bank,config,cache())){
            for(ComponentReferenceSpec probe:Arrays.asList(positive,negative))for(double gain:new double[]{1,.5,1.25}){
                Mat photo=read(bytes(probe)),gray=new Mat(),lit=new Mat();try(Phase5ReferenceMatcher matcher=new Phase5ReferenceMatcher(db,config)){
                    Imgproc.cvtColor(photo,gray,Imgproc.COLOR_BGR2GRAY);gray.convertTo(lit,-1,gain,8);boolean located=false;
                    for(int i=0;i<6;i++){begin(i);Phase5Observation o=matcher.inspect(lit,100+i,3).getPhase5Observation();
                        if(probe==negative)assertFalse("A reversed or misaligned installation must not become valid under different lighting",o.accepted(3));
                        if(probe==positive?o.accepted(3):o.state==Phase5Observation.State.BACKWARD||o.state==Phase5Observation.State.MISALIGNED){located=true;break;}}
                    assertTrue("Held-out Nano role="+Phase5AssetCatalog.referenceRole(probe.getReferenceId())+" gain="+gain,located);
                }finally{photo.release();gray.release();lit.release();}
            }
        }
    }
    private static List<VisionPoint> normalize(List<VisionPoint> pixels,int width,int height){List<VisionPoint> result=new ArrayList<>();for(VisionPoint p:pixels)result.add(new VisionPoint(p.getX()/width,p.getY()/height));return result;}
    private void begin(int i){DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.recovery((i&1)==1);}
    private static List<VisionPoint> box(double l,double t,double r,double b){return Arrays.asList(new VisionPoint(l,t),new VisionPoint(r,t),new VisionPoint(r,b),new VisionPoint(l,b));}
    private File cache(){return new File(app.getNoBackupFilesDir(),"orb_reference_cache_v3");}
    private byte[] bytes(ComponentReferenceSpec s)throws IOException{try(InputStream in=app.getAssets().open(s.getJpegAssetPath());ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}}
    private static Mat read(byte[] bytes){MatOfByte data=new MatOfByte(bytes);Mat full=Imgcodecs.imdecode(data,Imgcodecs.IMREAD_COLOR);try{Mat resized=new Mat();double scale=Math.min(640.0/full.cols(),400.0/full.rows());Imgproc.resize(full,resized,new Size(),scale,scale,Imgproc.INTER_AREA);return resized;}finally{data.release();full.release();}}
    private static void paint(Mat image,List<VisionPoint> roi){Imgproc.rectangle(image,new Point(roi.get(0).getX()*image.cols(),roi.get(0).getY()*image.rows()),new Point(roi.get(2).getX()*image.cols(),roi.get(2).getY()*image.rows()),new Scalar(200),-1);}
    private static ComponentReferenceSpec take(List<ComponentReferenceSpec> bank,String role,int phase){for(ComponentReferenceSpec s:new ArrayList<>(bank)){String r=phase==7?Phase7AssetCatalog.referenceRole(s.getReferenceId()):Phase9AssetCatalog.referenceRole(s.getReferenceId());if(role.equals(r)){bank.remove(s);return s;}}throw new AssertionError(role);}
}
