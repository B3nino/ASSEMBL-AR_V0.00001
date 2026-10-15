package dev.chyken.assembl_ar.vision;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.io.InputStream;
import java.util.*;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence;
import dev.chyken.assembl_ar.workflow.GuidedRecognitionCatalog;
import dev.chyken.assembl_ar.workflow.GuidedCheckpoint;
import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate;
import static org.junit.Assert.*;

/** Mesh fixtures exercise matching and rejection, not real-camera accuracy. */
@RunWith(AndroidJUnit4.class)
public final class ModelBasedRecognitionInstrumentedTest {
    private static final int WIDTH=736,HEIGHT=832;
    private static final List<VisionPoint> BOARD=Arrays.asList(new VisionPoint(112,112),
            new VisionPoint(624,112),new VisionPoint(624,720),new VisionPoint(112,720));
    private static final RecognitionResult.Pose POSE=new RecognitionResult.Pose(new double[]{0,0,0},
            new double[]{0,0,500},new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,-500,1});
    @BeforeClass public static void init(){assertTrue(OpenCVLoader.initLocal());}

    @Test public void everyCameraActionHasPreparedPartEvidenceAndAPhotographicBoardAnchor() throws Exception {
        ModelBasedPartRecognizer models=new ModelBasedPartRecognizer(context().getAssets());
        try(ReferenceDatabase database=ReferenceDatabase.load(context().getAssets(),
                VisionReferenceFactory.build(AssemblArRepository.getInstance(context())),VisionConfig.defaults())) {
            Set<String> available=new HashSet<>(database.getComponentIds());
            available.addAll(models.supportedComponentIds());
            int cameraActions=0;
            for(AssemblyStep section:NeobotSeedData.getAssemblySteps())
                for(GuidedAssemblySequence.GuidedStep card:GuidedAssemblySequence.forManualStep(section)) {
                    GuidedCheckpoint checkpoint=GuidedRecognitionCatalog.checkpointFor(section,card);
                    if(checkpoint==null||!checkpoint.requiresCamera()) continue;
                    cameraActions++;
                    assertTrue("Missing automatic evidence: "+checkpoint.getComponentId(),
                            available.contains(checkpoint.getComponentId()));
                    if(checkpoint.getAnchorComponentId()!=null) assertTrue("Anchor needs an actual photo",
                            database.getComponentIds().contains(checkpoint.getAnchorComponentId()));
                }
            assertEquals(26,cameraActions);
        }
    }

    @Test public void allFiveMeshFamiliesRequireVisibleMatchingEdges() throws Exception {
        for(int step:new int[]{10,11,12,13,14}) {
            String id=id(step);
            ModelBasedPartRecognizer detector=new ModelBasedPartRecognizer(context().getAssets());
            Mat scene=scene(step,true);
            try {
                RecognitionResult result=detector.recognize(scene,1,request(step),empty(),anchor(step));
                assertEquals("Mesh fixture for section "+step,id,result.getComponentId());
                assertEquals(RecognitionResult.DetectionMethod.MODEL_EDGE_MATCH,result.getDetectionMethod());
                assertNotNull(result.getPose());
                assertEquals(request(step).getExpectedInstanceCount(),result.getInstanceObservations().size());
            } finally {scene.release();}
        }
    }

    @Test public void emptyBoardAndWrongShapeCannotBeCompletedByTheTargetLocation() throws Exception {
        ModelBasedPartRecognizer detector=new ModelBasedPartRecognizer(context().getAssets());
        Mat blank=new Mat(HEIGHT,WIDTH,CvType.CV_8UC1,Scalar.all(245));
        Mat rectangle=blank.clone();
        Imgproc.rectangle(rectangle,new Point(300,155),new Point(430,230),Scalar.all(15),3);
        try {
            for(int step:new int[]{10,11,12,13,14}) {
                RecognitionResult empty=empty();
                assertSame("Blank step "+step,empty,detector.recognize(blank,1,request(step),empty,anchor(step)));
                assertSame("Rectangle step "+step,empty,detector.recognize(rectangle,1,request(step),empty,anchor(step)));
            }
        } finally {blank.release();rectangle.release();}
    }

    @Test public void placementBetweenSearchSamplesStillRequiresMeasuredEdges() throws Exception {
        ModelBasedPartRecognizer detector=new ModelBasedPartRecognizer(context().getAssets());
        for(int step:new int[]{10,11,12,13,14}) {
            Mat original=scene(step,true),shifted=new Mat();
            Mat affine=Imgproc.getRotationMatrix2D(new Point(WIDTH*.5,HEIGHT*.5),3,1.035);
            affine.put(0,2,affine.get(0,2)[0]+4.3);
            affine.put(1,2,affine.get(1,2)[0]-3.7);
            try {
                Imgproc.warpAffine(original,shifted,affine,new Size(WIDTH,HEIGHT),Imgproc.INTER_LINEAR,
                        Core.BORDER_CONSTANT,Scalar.all(245));
                RecognitionResult result=detector.recognize(shifted,1,request(step),empty(),anchor(step));
                assertEquals("Off-grid model section "+step,id(step),result.getComponentId());
                assertTrue(ChassisRelativePlacementGate.forNeobotStep(step).evaluate(BOARD,
                        quads(result)).isAccepted());
            } finally {affine.release();shifted.release();original.release();}
        }
    }

    private static List<List<VisionPoint>> quads(RecognitionResult result) {
        List<List<VisionPoint>> out=new ArrayList<>();
        for(RecognitionResult.InstanceObservation observation:result.getInstanceObservations())
            out.add(observation.getProjectedCorners());
        return out;
    }

    @Test public void repeatedPartsNeedTwoSameFrameInstancesAndARealAnchor() throws Exception {
        ModelBasedPartRecognizer detector=new ModelBasedPartRecognizer(context().getAssets());
        Mat one=scene(10,false);
        try {
            RecognitionResult empty=empty();
            assertSame(empty,detector.recognize(one,1,request(10),empty,anchor(10)));
            assertSame(empty,detector.recognize(one,1,request(10),empty,null));
        } finally {one.release();}
    }

    @Test public void texturedBareBoardCannotSupplyMissingComponents() throws Exception {
        ModelBasedPartRecognizer detector=new ModelBasedPartRecognizer(context().getAssets());
        Mat source=decode(context(),"references/variants/main-board-white-top.jpg",Imgcodecs.IMREAD_GRAYSCALE);
        Mat frame=new Mat();
        try {
            Imgproc.resize(source,frame,new Size(WIDTH,HEIGHT));
            for(int step:new int[]{10,11,12,13,14}) {
                RecognitionResult empty=empty();
                assertSame("Bare real PCB section "+step,empty,detector.recognize(frame,1,request(step),empty,anchor(step)));
            }
        } finally {frame.release();source.release();}
    }

    private static Mat scene(int step,boolean both) throws Exception {
        Context tests=InstrumentationRegistry.getInstrumentation().getContext();
        Mat rgba=decode(tests,"references/model-fixtures/"+id(step)+"-0.png",Imgcodecs.IMREAD_UNCHANGED);
        Mat mask=new Mat(), rgb=new Mat(),gray=new Mat(), scene=new Mat(HEIGHT,WIDTH,CvType.CV_8UC1,Scalar.all(245));
        Core.extractChannel(rgba,mask,3);
        Imgproc.threshold(mask,mask,127,255,Imgproc.THRESH_BINARY);
        Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_BGRA2BGR);Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_BGR2GRAY);
        MatOfPoint nonzero=new MatOfPoint();Core.findNonZero(mask,nonzero);Rect bounds=Imgproc.boundingRect(nonzero);
        Mat crop=gray.submat(bounds),alpha=mask.submat(bounds);
        List<ChassisRelativePlacementGate.TargetProjection> targets=ChassisRelativePlacementGate.forNeobotStep(step).projectTargets(BOARD);
        try {
            for(int i=0;i<(both?targets.size():1);i++) {
                ChassisRelativePlacementGate.TargetProjection target=targets.get(i);
                double aspect=bounds.width/(double)bounds.height;
                int w=(int)Math.round(Math.sqrt(target.getArea()*aspect)),h=(int)Math.round(Math.sqrt(target.getArea()/aspect));
                Mat scaled=new Mat(),scaledAlpha=new Mat();
                Imgproc.resize(crop,scaled,new Size(w,h));Imgproc.resize(alpha,scaledAlpha,new Size(w,h),0,0,Imgproc.INTER_NEAREST);
                Rect region=new Rect((int)Math.round(target.getCenter().getX()-w*.5),
                        (int)Math.round(target.getCenter().getY()-h*.5),w,h);
                Mat destination=scene.submat(region);scaled.copyTo(destination,scaledAlpha);
                destination.release();scaledAlpha.release();scaled.release();
            }
            return scene;
        } finally {alpha.release();crop.release();nonzero.release();gray.release();rgb.release();mask.release();rgba.release();}
    }
    private static Context context(){return ApplicationProvider.getApplicationContext();}
    private static RecognitionResult empty(){return RecognitionResult.builder(1).build();}
    private static String id(int step){switch(step){case 10:return NeobotSeedData.LINE_TRACKER;case 11:return NeobotSeedData.DISTANCE_SENSOR;
        case 12:return NeobotSeedData.STANDOFF;case 13:return NeobotSeedData.WHEEL;default:return NeobotSeedData.OLED_PROTECTOR;}}
    private static RecognitionResult.SpatialAnchor anchor(int step){return new RecognitionResult.SpatialAnchor("fixture-board",
            step==14?NeobotSeedData.EXTENSION_BOARD:NeobotSeedData.MAIN_BOARD,BOARD,POSE);}
    private static RecognitionRequest request(int step){return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id(step)),
            CameraCalibration.pinhole(900,900,WIDTH*.5,HEIGHT*.5),null,anchor(step).getComponentId(),step==10||step==13?2:1,step);}
    private static Mat decode(Context context,String path,int mode) throws Exception {
        try(InputStream stream=context.getAssets().open(path)){byte[] bytes=new byte[stream.available()];int offset=0,n;
            while(offset<bytes.length&&(n=stream.read(bytes,offset,bytes.length-offset))>0)offset+=n;
            MatOfByte encoded=new MatOfByte(bytes);try{return Imgcodecs.imdecode(encoded,mode);}finally{encoded.release();}}
    }
}
