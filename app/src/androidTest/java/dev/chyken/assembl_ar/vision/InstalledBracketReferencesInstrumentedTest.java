package dev.chyken.assembl_ar.vision;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import java.io.InputStream;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import dev.chyken.assembl_ar.camera.InstalledBracketReferenceCatalog;
import static org.junit.Assert.*;

/** Deterministic preparation and self-image registration checks, not held-out camera accuracy. */
@RunWith(AndroidJUnit4.class)
public final class InstalledBracketReferencesInstrumentedTest {
    @Test public void repeatedNativePreparationIsExactAndEveryViewHasMaskedFeatures() throws Exception {
        assertTrue(OpenCVLoader.initLocal());
        Context context=ApplicationProvider.getApplicationContext();
        VisionConfig config=VisionConfig.defaults();
        List<ComponentReferenceSpec> catalog=InstalledBracketReferenceCatalog.all();
        try(ReferenceDatabase first=ReferenceDatabase.load(context.getAssets(),catalog,config);
            ReferenceDatabase second=ReferenceDatabase.load(context.getAssets(),catalog,config)) {
            assertEquals(catalog.size(),first.size()); assertEquals(first.size(),second.size());
            for(int i=0;i<first.size();i++) {
                ReferenceDatabase.PreparedReference a=first.preparedReferences().get(i);
                ReferenceDatabase.PreparedReference b=second.preparedReferences().get(i);
                int maximum=0;
                for(int v=0;v<a.orbVariants().size();v++) {
                    ReferenceDatabase.PreparedOrbVariant av=a.orbVariants().get(v),bv=b.orbVariants().get(v);
                    assertEquals(av.keypoints.length,bv.keypoints.length);
                    maximum=Math.max(maximum,av.keypoints.length);
                    assertEquals(0.0,Core.norm(av.descriptors,bv.descriptors,Core.NORM_INF),0.0);
                    for(int k=0;k<av.keypoints.length;k++) {
                        assertEquals(av.keypoints[k].pt.x,bv.keypoints[k].pt.x,0.0);
                        assertEquals(av.keypoints[k].pt.y,bv.keypoints[k].pt.y,0.0);
                        assertEquals(av.keypoints[k].angle,bv.keypoints[k].angle,0.0);
                    }
                }
                assertTrue(a.spec.getReferenceId()+" masked features="+maximum,maximum>=config.getMinimumGoodMatches());
            }
        }
    }

    @Test public void installedFaceMatchesAreRepeatableAtEveryBracketCheckpoint() throws Exception {
        assertTrue(OpenCVLoader.initLocal());
        Context context=ApplicationProvider.getApplicationContext();
        VisionConfig config=VisionConfig.defaults();
        for(ComponentReferenceSpec spec:InstalledBracketReferenceCatalog.all()) {
            if(!spec.isInstalledBracketView()) continue;
            byte[] bytes;
            try(InputStream input=context.getAssets().open(spec.getJpegAssetPath())) { bytes=input.readAllBytes(); }
            MatOfByte encoded=new MatOfByte(bytes);
            Mat frame=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_GRAYSCALE);
            ComponentReferenceSpec anchor=InstalledBracketReferenceCatalog.all().get(0);
            try(ReferenceDatabase database=ReferenceDatabase.load(context.getAssets(),Arrays.asList(spec,anchor),config);
                ComponentRecognizer recognizer=new ComponentRecognizer(config,database)) {
                for(int slot=1;slot<=4;slot++) {
                    RecognitionRequest request=new RecognitionRequest(
                            RecognitionRequest.AssemblyExpectation.component("motor_bracket"),
                            cropCalibration(spec,frame),
                            null,"main_board",1,2)
                            .withGuidedBracketSlot(slot);
                    RecognitionResult first=recognizer.recognize(frame,1_000_000L,request);
                    RecognitionResult second=recognizer.recognize(frame,2_000_000L,request);
                    assertEquals(spec.getReferenceId(),"motor_bracket",first.getComponentId());
                    assertEquals(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,first.getDetectionMethod());
                    assertNotNull(spec.getReferenceId()+" needs usable pose geometry; inliers="+first.getInlierCount()
                            +" corners="+first.getProjectedCorners(),first.getPose());
                    assertEquals(first.getConfidence(),second.getConfidence(),0.0);
                    assertEquals(first.getInlierCount(),second.getInlierCount());
                    assertEquals(first.getProjectedCorners(),second.getProjectedCorners());
                }
            } finally { frame.release(); encoded.release(); }
        }
    }

    private static CameraCalibration cropCalibration(ComponentReferenceSpec spec,Mat frame) throws Exception {
        byte[] bytes;
        try(InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                .open("references/installed-bracket-crops.json")) { bytes=input.readAllBytes(); }
        JSONArray entries=new JSONArray(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.getJSONObject(i);
            if(!spec.getJpegAssetPath().equals(entry.getString("asset"))) continue;
            JSONArray crop=entry.getJSONArray("crop");
            int left=crop.getInt(0),top=crop.getInt(1),right=crop.getInt(2),bottom=crop.getInt(3);
            // A crop moves the optical center; centering intrinsics on the crop invents a new camera.
            // EXIF-equivalent focal length estimates the source camera; this is not measured calibration.
            double focal=entry.getDouble("sourceFocalPx");
            return CameraCalibration.pinhole(focal,focal,entry.getInt("sourceWidth")/2.0,
                    entry.getInt("sourceHeight")/2.0).cropped(left,top)
                    .scaled(frame.cols()/(double)(right-left),frame.rows()/(double)(bottom-top));
        }
        throw new AssertionError("Missing source crop calibration");
    }

    @Test public void fullCaptureScenesProvideIndependentBracketIdentity() throws Exception {
        assertTrue(OpenCVLoader.initLocal());
        Context context=ApplicationProvider.getApplicationContext();
        Context fixtureContext=InstrumentationRegistry.getInstrumentation().getContext();
        VisionConfig config=VisionConfig.defaults();
        String[] captures={"132938","133425","133533","133646"};
        java.util.ArrayList<String> failures=new java.util.ArrayList<>();
        try(ReferenceDatabase database=ReferenceDatabase.load(context.getAssets(),InstalledBracketReferenceCatalog.all(),config);
            ComponentRecognizer recognizer=new ComponentRecognizer(config,database)) {
            for(int i=0;i<captures.length;i++) {
                byte[] bytes;
                try(InputStream input=fixtureContext.getAssets().open("references/installed-bracket-scenes/"+captures[i]+".jpg")) {
                    bytes=input.readAllBytes();
                }
                MatOfByte encoded=new MatOfByte(bytes);
                Mat frame=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_GRAYSCALE);
                try {
                    ComponentReferenceSpec face=InstalledBracketReferenceCatalog.all().get(i+4);
                    CameraCalibration calibration=cropCalibration(face,frame);
                    RecognitionResult board=recognizer.recognizeBoard(frame,1_000_000L,calibration,2);
                    RecognitionRequest request=new RecognitionRequest(
                            RecognitionRequest.AssemblyExpectation.component("motor_bracket"),calibration,
                            null,"main_board",1,2).withGuidedBracketSlot(i+1);
                    if("main_board".equals(board.getComponentId()) && board.getProjectedCorners().size()==4) {
                        request=request.withGuidedBracketWorkplane(board.getProjectedCorners());
                    }
                    RecognitionResult result=recognizer.recognize(frame,2_000_000L,request);
                    RecognitionResult.RejectionEvidence evidence=result.getRejectionEvidence();
                    String detail=evidence==null ? "none" : evidence.getReason()+" ref="+evidence.getReferenceId()
                            +" good="+evidence.getGoodMatchCount()+" inliers="+evidence.getInlierCount();
                    if(!"motor_bracket".equals(result.getComponentId())) {
                        failures.add(captures[i]+" board="+board.getComponentId()+" full scene confidence="
                                +result.getConfidence()+" rejection="+detail);
                    }
                } finally { frame.release(); encoded.release(); }
            }
        }
        assertTrue(failures.toString(),failures.isEmpty());
    }
}
