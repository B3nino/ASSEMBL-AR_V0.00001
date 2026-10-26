package dev.chyken.assembl_ar.vision;

import android.content.Context;
import dev.chyken.assembl_ar.camera.Phase35VideoReferences;
import dev.chyken.assembl_ar.diagnostics.*;
import java.util.*;
import org.junit.*;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.features2d.ORB;
import static org.junit.Assert.*;

/** Native arithmetic/ownership tests using retained real photo pixels. No training assets or
 * user checkpoints are written. Photometric transforms below are test stress inputs only. */
public class HybridVisionInstrumentedTest {
    @BeforeClass public static void runtime(){assertTrue(OpenCVLoader.initLocal());Core.setNumThreads(2);}
    private Context context()throws Exception{
        java.lang.reflect.Method m=Phase1011VideoReplayInstrumentedTest.class.getDeclaredMethod("context");m.setAccessible(true);
        return (Context)m.invoke(new Phase1011VideoReplayInstrumentedTest());
    }
    private ComponentReferenceSpec reference(){for(ComponentReferenceSpec s:Phase35VideoReferences.all())if(s.getJpegAssetPath().contains("driver_front_2"))return s;throw new AssertionError("Missing retained real pixels");}
    private Mat photo()throws Exception{
        MatOfByte bytes;try(java.io.InputStream in=context().getAssets().open(reference().getJpegAssetPath())){bytes=new MatOfByte(in.readAllBytes());}
        try{return Imgcodecs.imdecode(bytes,Imgcodecs.IMREAD_GRAYSCALE);}finally{bytes.release();}
    }
    @Test public void structuralCacheIsSeparateBoundedAndCopiedWithItsOwner()throws Exception{
        ComponentReferenceSpec spec=reference();
        try(ReferenceDatabase db=ReferenceDatabase.load(context().getAssets(),Collections.singletonList(spec),VisionConfig.defaults())){
            ReferenceDatabase.PreparedReference ref=db.preparedReferences().get(0);
            assertEquals(1,ref.edgeVariants.size());assertFalse(ref.orbVariants.isEmpty());
            for(ReferenceDatabase.PreparedOrbVariant v:ref.orbVariants)assertNotEquals(ReferenceDatabase.PhotometricVariant.EDGE,v.photometricVariant);
            assertTrue(ref.edgeVariants.get(0).keypoints.length<=600);assertTrue(ref.edgeVariants.get(0).keypoints.length>=8);
            assertTrue(KeypointInventoryManager.INSTANCE.snapshot().stream().anyMatch(e->e.referenceId.equals(spec.getReferenceId())&&e.variants.containsKey("EDGE")));
            List<AdminDescriptorBlobCodec.DescriptorVariant> encoded=new ArrayList<>();
            try{
                for(ReferenceDatabase.PreparedOrbVariant v:ref.allVariants)encoded.add(new AdminDescriptorBlobCodec.DescriptorVariant(v.photometricVariant,v.keypoints,v.descriptors.clone(),true));
                byte[] blob=AdminDescriptorBlobCodec.encode(ref.imageWidth,ref.imageHeight,encoded,VisionConfig.defaults());
                AdminDescriptorBlobCodec.DecodedDescriptorBlob decoded=AdminDescriptorBlobCodec.decode(blob,VisionConfig.defaults());
                try{assertEquals(ref.allVariants.size(),decoded.variants.size());assertTrue(decoded.variants.stream().anyMatch(v->v.photometricVariant==ReferenceDatabase.PhotometricVariant.EDGE));}
                finally{for(AdminDescriptorBlobCodec.DescriptorVariant v:decoded.variants)v.release();}
            }finally{for(AdminDescriptorBlobCodec.DescriptorVariant v:encoded)v.release();}
            ReferenceDatabase.PreparedReference copy=ref.copy();
            try{assertEquals(ref.estimatedBytes(),copy.estimatedBytes());assertNotEquals(ref.edgeVariants.get(0).descriptors.nativeObj,copy.edgeVariants.get(0).descriptors.nativeObj);}
            finally{copy.release();}
            DiagnosticHammingMatcher.begin();assertSame(ref.orbVariants,ref.recoveryVariants());
            DiagnosticHammingMatcher.snapshot().structuralInput=true;assertSame(ref.edgeVariants,ref.recoveryVariants());
            DiagnosticHammingMatcher.end();
        }
    }
    @Test public void realPhotoStructuralDescriptorsRetainConsensusAfterExposureShift()throws Exception{
        Mat gray=photo(),dim=new Mat(),mask=new Mat(),a=new Mat(),b=new Mat();MatOfKeyPoint ka=new MatOfKeyPoint(),kb=new MatOfKeyPoint();
        ORB orb=EdgeGradientDescriptorExtractor.createOrb();
        try{
            gray.convertTo(dim,CvType.CV_8U,.55,12);
            EdgeGradientDescriptorExtractor.extract(gray,mask,orb,ka,a);EdgeGradientDescriptorExtractor.extract(dim,mask,orb,kb,b);
            DiagnosticHammingMatcher.begin();List<DMatch> good=new DiagnosticHammingMatcher().ratioMatches(a,b);
            assertTrue("Same physical photo under exposure stress: "+good.size(),good.size()>=15);
            Set<Integer> ids=new HashSet<>();for(DMatch m:good){assertTrue(OrbMatchPolicy.accepts(m.distance,256));assertTrue(ids.add(m.trainIdx));}
            MatOfPoint2f from=new MatOfPoint2f(),to=new MatOfPoint2f();Mat keep=new Mat(),h=null;
            try{KeyPoint[] source=ka.toArray(),target=kb.toArray();List<Point> x=new ArrayList<>(),y=new ArrayList<>();for(DMatch m:good){x.add(source[m.queryIdx].pt);y.add(target[m.trainIdx].pt);}from.fromList(x);to.fromList(y);
                h=org.opencv.calib3d.Calib3d.findHomography(from,to,org.opencv.calib3d.Calib3d.RANSAC,3,keep);
                assertFalse(h.empty());assertTrue(Core.countNonZero(keep)>=15);
            }finally{from.release();to.release();keep.release();if(h!=null)h.release();}
        }finally{DiagnosticHammingMatcher.end();orb.clear();gray.release();dim.release();mask.release();a.release();b.release();ka.release();kb.release();}
    }
    @Test public void flatFramesCannotInventStructuralKeypointsAndRepeatedBuffersRelease(){
        Mat flat=new Mat(400,640,CvType.CV_8UC1,new Scalar(130)),work=new Mat(),mask=new Mat(),rows=new Mat();MatOfKeyPoint keys=new MatOfKeyPoint();ORB orb=EdgeGradientDescriptorExtractor.createOrb();
        try{for(int i=0;i<80;i++){EdgeGradientDescriptorExtractor.normalize(flat,work);assertEquals(CvType.CV_8UC1,work.type());assertEquals(flat.size(),work.size());orb.detectAndCompute(work,mask,keys,rows);assertTrue(rows.empty());}}
        finally{flat.release();work.release();mask.release();rows.release();keys.release();orb.clear();}
        assertTrue(work.empty());assertTrue(rows.empty());assertTrue(keys.empty());
    }
    @Test public void fallbackPoseIsReportedAsCoarseWithoutFabricatingOrbOrBenchmarkOrientation(){
        Mat gray=new Mat(240,320,CvType.CV_8UC1,new Scalar(150));
        try{
            List<VisionPoint> corners=Arrays.asList(new VisionPoint(50,60),new VisionPoint(150,60),new VisionPoint(150,160),new VisionPoint(50,160));
            CameraCalibration c=CameraCalibration.pinhole(400,400,160,120);
            RecognitionResult.Pose p=LoosePartShapeRecognizer.estimatePose(corners,45,45,c);assertNotNull(p);
            RecognitionResult r=RecognitionResult.builder(123).identity("shape:test","test","Test board").method(RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK)
                    .validation(RecognitionResult.ValidationState.EXPECTED_COMPONENT).corners(corners).pose(p).confidence(.85).build();
            RecognitionRequest request=new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("test"),c,null,null,1,0);
            DiagnosticSession.INSTANCE.analyzedFrame();DiagnosticSample sample=DiagnosticSession.INSTANCE.record(r,request,gray,false);
            assertTrue(sample.telemetry.poseMeasured);assertTrue(sample.telemetry.coarsePose);assertNull(sample.correctPose);
            assertTrue(sample.telemetry.hudText().contains("Correct Pose: Coarse PnP"));assertTrue(Float.isNaN(sample.telemetry.matchPercentage));assertTrue(Float.isNaN(sample.telemetry.reprojectionRmsPx));
            assertEquals("Match (VERIFIED)",sample.telemetry.outputStatus);
        }finally{gray.release();}
    }
    @Test public void phaseMatcherCanUseOnlyTheStructuralBankAndBlankCannotPass()throws Exception{
        Mat gray=photo(),blank=new Mat(gray.size(),gray.type(),new Scalar(170));
        DiagnosticHammingMatcher.begin();
        try(ReferenceDatabase db=ReferenceDatabase.load(context().getAssets(),Collections.singletonList(reference()),VisionConfig.defaults());
                Phase3ReferenceMatcher matcher=new Phase3ReferenceMatcher(db,VisionConfig.defaults())){
            DiagnosticHammingMatcher.snapshot().structuralInput=true;
            RecognitionResult result=matcher.inspect(gray,1,1);
            assertEquals("Structural bank alone must verify the retained real front",Phase3Observation.State.FRONT,result.getPhase3Observation().state);
            assertEquals(4,result.getProjectedCorners().size());
            DiagnosticHammingMatcher.begin();DiagnosticHammingMatcher.snapshot().structuralInput=true;
            assertNotEquals(Phase3Observation.State.FRONT,matcher.inspect(blank,2,1).getPhase3Observation().state);
        }finally{DiagnosticHammingMatcher.end();gray.release();blank.release();}
    }
    @Test public void migrationKeepsLuminanceBlobAndGeometryWhileOnlyAddingAnEdgeSidecar()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();ComponentReferenceSpec spec=reference();
        java.io.File dir=new java.io.File(c.getCacheDir(),"hybrid-migration-"+System.nanoTime());assertTrue(dir.mkdirs());
        try(ReferenceDatabase ignored=ReferenceDatabase.load(c.getAssets(),Collections.singletonList(spec),config,dir)){}
        java.io.File[] blobs=dir.listFiles(f->f.getName().endsWith(".asor"));assertNotNull(blobs);assertEquals(1,blobs.length);
        java.io.File normal=blobs[0];byte[] before=java.nio.file.Files.readAllBytes(normal.toPath());long written=normal.lastModified();
        ReferenceCacheIndex index=new ReferenceCacheIndex(dir,c.getApplicationInfo().sourceDir,ReferenceDatabase.cacheRevision());ReferenceCacheIndex.install(c.getAssets(),index);
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),Collections.singletonList(spec),config,dir)){assertEquals(1,db.preparedReferences().get(0).edgeVariants.size());}
        assertArrayEquals(before,java.nio.file.Files.readAllBytes(normal.toPath()));assertEquals(written,normal.lastModified());
        java.io.File[] edges=dir.listFiles(f->f.getName().startsWith("edge1_"));assertNotNull(edges);assertEquals(1,edges.length);long edgeWritten=edges[0].lastModified();
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),Collections.singletonList(spec),config,dir)){assertEquals(1,db.preparedReferences().get(0).edgeVariants.size());}
        assertEquals(edgeWritten,edges[0].lastModified());assertEquals(written,normal.lastModified());
        Mat gray=photo();try(AdminDescriptorBlobCodec.DecodedDescriptorBlob admin=AdminDescriptorBlobCodec.decode(AdminDescriptorBlobCodec.computeAndEncode(gray,config),config)){
            assertEquals("Administrator capture still requires exactly the original two luminance variants",2,admin.variants.size());
        }finally{gray.release();}
    }
    @Test public void lostOptionalSidecarCannotBlockAWarmLuminanceScanner()throws Exception{
        Context c=context();VisionConfig config=VisionConfig.defaults();ComponentReferenceSpec spec=reference();
        java.io.File dir=new java.io.File(c.getCacheDir(),"hybrid-warm-"+System.nanoTime());assertTrue(dir.mkdirs());
        ReferenceCacheIndex index=new ReferenceCacheIndex(dir,c.getApplicationInfo().sourceDir,ReferenceDatabase.cacheRevision());ReferenceCacheIndex.install(c.getAssets(),index);
        try(ReferenceDatabase ignored=ReferenceDatabase.load(c.getAssets(),Collections.singletonList(spec),config,dir)){}
        java.io.File[] edges=dir.listFiles(f->f.getName().startsWith("edge1_"));assertEquals(1,edges.length);assertTrue(edges[0].delete());
        VisionPreloadManager manager=VisionPreloadManager.get(c);
        java.lang.reflect.Field completion=VisionPreloadManager.class.getDeclaredField("completion");completion.setAccessible(true);completion.set(manager,java.util.concurrent.CompletableFuture.completedFuture(Collections.singletonList(spec)));
        java.lang.reflect.Field active=VisionPreloadManager.class.getDeclaredField("ACTIVE");active.setAccessible(true);
        @SuppressWarnings("unchecked") Map<android.content.res.AssetManager,VisionPreloadManager> owners=(Map<android.content.res.AssetManager,VisionPreloadManager>)active.get(null);
        VisionPreloadManager previous=owners.put(c.getAssets(),manager);
        try(ReferenceDatabase db=ReferenceDatabase.load(c.getAssets(),Collections.singletonList(spec),config,dir)){
            assertTrue(db.preparedReferences().get(0).hasOrbData());assertTrue(db.preparedReferences().get(0).edgeVariants.isEmpty());assertFalse("No photo extraction/sidecar rebuild during live loading",edges[0].exists());
        }finally{if(previous==null)owners.remove(c.getAssets());else owners.put(c.getAssets(),previous);}
    }
}
