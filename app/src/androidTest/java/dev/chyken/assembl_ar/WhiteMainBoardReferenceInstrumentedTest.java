package dev.chyken.assembl_ar;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;

import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.CameraCalibration;
import dev.chyken.assembl_ar.vision.ComponentRecognizer;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.RecognitionRequest;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.ReferenceDatabase;
import dev.chyken.assembl_ar.vision.VisionConfig;
import dev.chyken.assembl_ar.vision.VisionPoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Exercises the supplied white PCB captures through the production OpenCV preparation path. */
@RunWith(AndroidJUnit4.class)
public final class WhiteMainBoardReferenceInstrumentedTest {
    private static final String WHITE_TOP_REFERENCE =
            "references/variants/main-board-white-top.jpg";
    private static final String WHITE_TOP_CAMERA_CAPTURE =
            "references/camera/main-board-white-top-camera.jpg";
    private static final String WHITE_TOP_FIELD_CAPTURE =
            "references/camera/main-board-white-field.jpg";

    @Test
    public void whiteTopAndUndersideProduceUsablePhotometricVariants() throws Exception {
        assertTrue("The packaged OpenCV runtime must initialize", OpenCVLoader.initLocal());
        Context context = ApplicationProvider.getApplicationContext();

        ComponentReferenceSpec top = ComponentReferenceSpec.builder(
                "main_board_white_top", NeobotSeedData.MAIN_BOARD,
                "Personal Robot Kit main board", 100.0, 120.0)
                .jpegAssetPath(WHITE_TOP_REFERENCE)
                .planarReferenceCornersNormalized(
                        new VisionPoint(0.03, 0.03),
                        new VisionPoint(0.97, 0.03),
                        new VisionPoint(0.97, 0.97),
                        new VisionPoint(0.03, 0.97))
                .build();
        ComponentReferenceSpec underside = ComponentReferenceSpec.builder(
                        "main_board_white_underside", NeobotSeedData.MAIN_BOARD,
                        "Personal Robot Kit main board", 100.0, 120.0)
                .jpegAssetPath("references/variants/main-board-white-underside.jpg")
                .mirroredPlanarReferenceCornersNormalized(
                        new VisionPoint(0.97, 0.03),
                        new VisionPoint(0.03, 0.03),
                        new VisionPoint(0.03, 0.97),
                        new VisionPoint(0.97, 0.97))
                .build();

        try (ReferenceDatabase database = ReferenceDatabase.load(
                context.getAssets(), Arrays.asList(top, underside), VisionConfig.defaults())) {
            assertEquals(2, database.size());
            assertEquals(1, database.getComponentIds().size());
            assertTrue(database.getComponentIds().contains(NeobotSeedData.MAIN_BOARD));
        }
    }

    /**
     * Regression for the supplied source photo. The analyzer receives the CameraX luminance plane
     * after display rotation, not the tightly cropped reference JPEG. Rotate and downsample the
     * untouched 4:3 phone capture to approximate a 360x480 analyzed frame.
     */
    @Test
    public void suppliedWhiteTopCameraFrameMatchesProcessedReference() throws Exception {
        assertTrue("The packaged OpenCV runtime must initialize", OpenCVLoader.initLocal());
        Context context = ApplicationProvider.getApplicationContext();
        VisionConfig config = VisionConfig.defaults();
        ComponentReferenceSpec top = productionWhiteTopSpec(context);

        Mat source = decodeAsset(
                InstrumentationRegistry.getInstrumentation().getContext(),
                WHITE_TOP_CAMERA_CAPTURE);
        Mat displayOriented = new Mat();
        Mat cameraFrame = new Mat();
        try (ReferenceDatabase database = ReferenceDatabase.load(
                context.getAssets(), Collections.singletonList(top), config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            Core.rotate(source, displayOriented, Core.ROTATE_90_COUNTERCLOCKWISE);
            Imgproc.resize(displayOriented, cameraFrame, new Size(360.0, 480.0),
                    0.0, 0.0, Imgproc.INTER_AREA);
            CameraCalibration calibration = CameraCalibration.pinhole(
                    1132.8, 1132.8, cameraFrame.cols() / 2.0, cameraFrame.rows() / 2.0);
            RecognitionRequest request = new RecognitionRequest(
                    new RecognitionRequest.AssemblyExpectation(
                            NeobotSeedData.MAIN_BOARD, 0.0, 30.0, null),
                    calibration,
                    null);

            RecognitionResult result = recognizer.recognize(cameraFrame, 101L, request);
            String diagnostic = "state=" + result.getValidationState()
                    + " method=" + result.getDetectionMethod()
                    + " reference=" + result.getReferenceId()
                    + " frameKeypoints=" + result.getFrameKeypointCount()
                    + " good=" + result.getGoodMatchCount()
                    + " inliers=" + result.getInlierCount()
                    + " ratio=" + result.getInlierRatio()
                    + " corners=" + result.getProjectedCorners();
            assertTrue("Supplied white-board capture did not pass recognition: " + diagnostic,
                    result.getValidationState()
                            == RecognitionResult.ValidationState.EXPECTED_COMPONENT);
            assertEquals(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,
                    result.getDetectionMethod());
            assertEquals("main_board_bundled_3", result.getReferenceId());
            assertTrue(result.getGoodMatchCount() >= config.getMinimumGoodMatches());
            assertTrue(result.getInlierCount() >= config.getMinimumInliers());
            assertTrue(result.getInlierRatio() >= config.getMinimumInlierRatio());
            assertEquals(4, result.getProjectedCorners().size());
            assertNotNull("Step one requires a solvePnP pose after identity recognition: "
                    + diagnostic, result.getPose());
        } finally {
            cameraFrame.release();
            displayOriented.release();
            source.release();
        }
    }

    /**
     * Held-out regression from the user's failing device capture. The crop excludes the app's
     * header and instruction sheet but deliberately retains the live brackets, perspective,
     * uneven room light, and the ribbon-cable occlusion visible in the report.
     */
    @Test
    public void heldOutWhiteBoardFieldCaptureIsRecognized() throws Exception {
        assertTrue("The packaged OpenCV runtime must initialize", OpenCVLoader.initLocal());
        Context targetContext = ApplicationProvider.getApplicationContext();
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        VisionConfig config = VisionConfig.defaults();
        ComponentReferenceSpec top = productionWhiteTopSpec(targetContext);

        Mat screenshot = decodeAsset(testContext, WHITE_TOP_FIELD_CAPTURE);
        Mat cameraCrop = new Mat();
        try (ReferenceDatabase database = ReferenceDatabase.load(
                targetContext.getAssets(), Collections.singletonList(top), config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            Mat cropView = screenshot.submat(new Rect(150, 450, 750, 850));
            try {
                cropView.copyTo(cameraCrop);
            } finally {
                cropView.release();
            }
            RecognitionRequest request = new RecognitionRequest(
                    new RecognitionRequest.AssemblyExpectation(
                            NeobotSeedData.MAIN_BOARD, 0.0, 30.0, null),
                    CameraCalibration.pinhole(
                            1_200.0, 1_200.0,
                            cameraCrop.cols() / 2.0, cameraCrop.rows() / 2.0),
                    null);

            RecognitionResult result = recognizer.recognize(cameraCrop, 102L, request);
            String diagnostic = "state=" + result.getValidationState()
                    + " method=" + result.getDetectionMethod()
                    + " reference=" + result.getReferenceId()
                    + " frameKeypoints=" + result.getFrameKeypointCount()
                    + " good=" + result.getGoodMatchCount()
                    + " inliers=" + result.getInlierCount()
                    + " ratio=" + result.getInlierRatio()
                    + " corners=" + result.getProjectedCorners();
            assertTrue("Held-out white-board capture did not pass recognition: " + diagnostic,
                    result.getValidationState()
                            == RecognitionResult.ValidationState.EXPECTED_COMPONENT);
            assertEquals(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,
                    result.getDetectionMethod());
            assertEquals("main_board_bundled_3", result.getReferenceId());
            assertNotNull("A strong held-out white-board match must also produce the step-one pose: "
                    + diagnostic, result.getPose());
        } finally {
            cameraCrop.release();
            screenshot.release();
        }
    }

    private static Mat decodeAsset(Context context, String path) throws IOException {
        byte[] bytes;
        try (InputStream input = context.getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            bytes = output.toByteArray();
        }
        MatOfByte encoded = new MatOfByte();
        try {
            encoded.fromArray(bytes);
            Mat decoded = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE);
            if (decoded.empty()) {
                decoded.release();
                throw new IOException("Unable to decode test image: " + path);
            }
            return decoded;
        } finally {
            encoded.release();
        }
    }

    private static ComponentReferenceSpec productionWhiteTopSpec(Context context) {
        for (ComponentReferenceSpec spec : VisionReferenceFactory.build(
                AssemblArRepository.getInstance(context))) {
            if (WHITE_TOP_REFERENCE.equals(spec.getJpegAssetPath())) {
                // Ruler-measured physical plane replaces the old source-image-aspect estimate.
                assertEquals(100.0, spec.getPhysicalWidthMm(), 0.0001);
                assertEquals(120.0, spec.getPhysicalHeightMm(), 0.0001);
                return spec;
            }
        }
        throw new AssertionError("Production catalog is missing the white main-board top view");
    }
}
