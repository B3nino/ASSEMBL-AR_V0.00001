package dev.chyken.assembl_ar.camera;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.CameraCalibration;
import dev.chyken.assembl_ar.vision.ComponentRecognizer;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.LoosePartShapeRecognizer;
import dev.chyken.assembl_ar.vision.RecognitionRequest;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.ReferenceDatabase;
import dev.chyken.assembl_ar.vision.VisionConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Catalog preparation and end-to-end diagnostic-pipeline regressions on reviewed photographs. */
@RunWith(AndroidJUnit4.class)
public final class IndividualPartTestCatalogInstrumentedTest {
    @Test
    public void legacyEntryPointCannotReintroduceRejectedHardwareReferences() {
        Context context = context();
        List<ComponentReferenceSpec> baselines = IndividualPartTestCatalog.withLegacyBaselines(
                VisionReferenceFactory.build(AssemblArRepository.getInstance(context)));
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (ComponentReferenceSpec spec : baselines) {
            ids.add(spec.getComponentId());
            assertEquals(0, spec.getAssemblyStepNumber());
            assertFalse(spec.isAdministratorReference());
            assertFalse(spec.getJpegAssetPath().startsWith("references/assembly-state/"));
        }
        assertEquals(IndividualPartTestCatalog.getStandaloneComponentIds(), ids);
        assertFalse(ids.contains(NeobotSeedData.SCREW_30MM));
        assertFalse(ids.contains(NeobotSeedData.SCREW_SHORT));
        assertFalse(ids.contains(NeobotSeedData.NUT));
        assertFalse(IndividualPartTestCatalog.getStandaloneComponentIds().contains(NeobotSeedData.NUT));
    }

    @Test
    public void allReviewedIndependentReferencesPrepareWithoutAssemblyFallbacks() throws Exception {
        Context context = context();
        List<ComponentReferenceSpec> isolated = catalog(context);
        assertEquals(23 + ReviewedIndividualReferenceCatalog.references().size(), isolated.size());
        try (ReferenceDatabase database = ReferenceDatabase.load(
                context.getAssets(), isolated, VisionConfig.defaults())) {
            assertEquals("A reviewed source must not silently fail preparation", isolated.size(), database.size());
            assertEquals(IndividualPartTestCatalog.getStandaloneComponentIds(), database.getComponentIds());
            assertTrue(database.getComponentIds().contains(NeobotSeedData.GEAR_MOTOR));
            assertTrue(database.getComponentIds().contains(NeobotSeedData.MOTOR_BRACKET));
            for (String unavailable : IndividualPartTestCatalog.getUnavailableComponentIds()) {
                assertFalse(unavailable, database.getComponentIds().contains(unavailable));
            }
        }
    }

    @Test
    public void originalJpegDecodeOrientationMatchesRegisteredPartPlanes() throws Exception {
        Context context = context();
        for (String file : new String[]{"motor-bracket-20260906_131438.jpg",
                "motor-bracket-20260906_131502.jpg", "motor-bracket-20260906_131707.jpg",
                "gear-motor-20260906_131326.jpg", "gear-motor-20260906_131755.jpg",
                "gear-motor-20260906_131759.jpg", "motor-driver-20260906_131356.jpg",
                "motor-driver-20260906_131718.jpg"}) {
            Mat photograph = decode(context, "references/individual-test/" + file);
            try {
                if (file.equals("gear-motor-20260906_131326.jpg")
                        || file.equals("gear-motor-20260906_131759.jpg")) {
                    assertTrue(file, photograph.cols() > photograph.rows());
                } else {
                    assertTrue(file, photograph.rows() > photograph.cols());
                }
            } finally {
                photograph.release();
            }
        }
    }

    @Test
    public void heldOutPhotosAreTestOnlyAndNeverBecomeReferenceInputs() throws Exception {
        List<ComponentReferenceSpec> isolated = catalog(context());
        for (ComponentReferenceSpec spec : isolated) {
            assertFalse(spec.getJpegAssetPath().contains("individual-test-held-out"));
            assertFalse(spec.getJpegAssetPath().endsWith("20260906_131648.jpg"));
            assertFalse(spec.getJpegAssetPath().endsWith("20260906_131803.jpg"));
            assertFalse(spec.getJpegAssetPath().endsWith("20260906_131741.jpg"));
        }
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        for (String file : new String[]{"motor-bracket-20260906_131648.jpg",
                "gear-motor-20260906_131803.jpg", "motor-driver-20260906_131741.jpg"}) {
            Mat photograph = decode(testContext,
                    "references/individual-test-held-out/" + file);
            try {
                assertFalse(file, photograph.empty());
            } finally {
                photograph.release();
            }
        }
    }

    @Test
    public void heldOutLoosePartPhotosLocalizeInTheLiveDiagnosticPipeline() throws Exception {
        Context appContext = context();
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        VisionConfig config = IndividualPartTestCatalog.visionConfig();
        String[][] cases = {
                {NeobotSeedData.MOTOR_BRACKET, "motor-bracket-20260906_131648.jpg"},
                {NeobotSeedData.GEAR_MOTOR, "gear-motor-20260906_131803.jpg"},
                {NeobotSeedData.MOTOR_DRIVER, "motor-driver-20260906_131741.jpg"}
        };
        String[] targetIds = {NeobotSeedData.MOTOR_BRACKET,
                NeobotSeedData.GEAR_MOTOR, NeobotSeedData.MOTOR_DRIVER};
        StringBuilder failures = new StringBuilder();
        try (ReferenceDatabase database = ReferenceDatabase.load(
                appContext.getAssets(), IndividualPartTestCatalog.withLegacyBaselines(
                        VisionReferenceFactory.build(AssemblArRepository.getInstance(appContext))),
                config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            LoosePartShapeRecognizer shapeRecognizer = new LoosePartShapeRecognizer();
            for (String[] testCase : cases) {
                Mat originalRgb = decodeRgb(testContext,
                        "references/individual-test-held-out/" + testCase[1]);
                Mat cameraRgb = resizeToAnalysisResolution(originalRgb);
                originalRgb.release();
                Mat cameraGray = new Mat();
                Imgproc.cvtColor(cameraRgb, cameraGray, Imgproc.COLOR_RGB2GRAY);
                try {
                    double focalLengthPx = Math.max(cameraGray.cols(), cameraGray.rows()) * 1.15;
                    CameraCalibration calibration = CameraCalibration.pinhole(
                            focalLengthPx, focalLengthPx,
                            cameraGray.cols() / 2.0, cameraGray.rows() / 2.0);
                    RecognitionResult primary = recognizer.recognize(cameraGray,
                            System.nanoTime(), IndividualPartTestCatalog.requestFor(
                                    testCase[0], calibration, 0.001));
                    RecognitionResult result = shapeRecognizer.recognize(cameraGray, cameraRgb,
                            System.nanoTime(), IndividualPartTestCatalog.requestFor(
                                    testCase[0], calibration, 0.001), primary);
                    if (!testCase[0].equals(result.getComponentId())
                            || (result.getDetectionMethod()
                            != RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY
                            && result.getDetectionMethod()
                            != RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK)
                            || result.getProjectedCorners().size() != 4
                            || result.getPose() == null
                            || result.getValidationState()
                            != RecognitionResult.ValidationState.EXPECTED_COMPONENT) {
                        failures.append(testCase[1]).append(' ')
                                .append(diagnostics(result)).append('\n');
                    }
                    for (String wrongTargetId : targetIds) {
                        if (testCase[0].equals(wrongTargetId)) continue;
                        RecognitionResult wrongPrimary = recognizer.recognize(cameraGray,
                                System.nanoTime(), IndividualPartTestCatalog.requestFor(
                                        wrongTargetId, calibration, 0.001));
                        RecognitionResult wrongExpectation = shapeRecognizer.recognize(
                                cameraGray, cameraRgb, System.nanoTime(),
                                IndividualPartTestCatalog.requestFor(
                                        wrongTargetId, calibration, 0.001), wrongPrimary);
                        if (wrongExpectation.getValidationState()
                                == RecognitionResult.ValidationState.EXPECTED_COMPONENT) {
                            failures.append(testCase[1]).append(" wrong-expectation=")
                                    .append(wrongTargetId).append(' ')
                                    .append(diagnostics(wrongExpectation)).append('\n');
                        }
                    }
                } finally {
                    cameraGray.release();
                    cameraRgb.release();
                }
            }
        }
        assertEquals(failures.toString(), "", failures.toString());
    }

    @Test
    public void productionAssemblyCatalogLocalizesHeldOutPartsAndRejectsBoardForAllThree()
            throws Exception {
        Context appContext = context();
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        VisionConfig config = VisionConfig.defaults();
        String[][] cases = {
                {NeobotSeedData.MOTOR_BRACKET, "motor-bracket-20260906_131648.jpg"},
                {NeobotSeedData.GEAR_MOTOR, "gear-motor-20260906_131803.jpg"},
                {NeobotSeedData.MOTOR_DRIVER, "motor-driver-20260906_131741.jpg"}
        };
        try (ReferenceDatabase database = ReferenceDatabase.load(appContext.getAssets(),
                VisionReferenceFactory.build(AssemblArRepository.getInstance(appContext)), config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            LoosePartShapeRecognizer shapeRecognizer = new LoosePartShapeRecognizer();
            for (String[] testCase : cases) {
                Mat originalRgb = decodeRgb(testContext,
                        "references/individual-test-held-out/" + testCase[1]);
                Mat rgb = resizeToAnalysisResolution(originalRgb);
                originalRgb.release();
                Mat gray = new Mat();
                try {
                    Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
                    CameraCalibration calibration = calibration(gray);
                    RecognitionRequest request = productionRequest(testCase[0], calibration);
                    RecognitionResult primary = recognizer.recognize(
                            gray, System.nanoTime(), request);
                    RecognitionResult result = shapeRecognizer.recognize(
                            gray, rgb, System.nanoTime(), request, primary);
                    assertEquals(testCase[1], testCase[0], result.getComponentId());
                    assertTrue(testCase[1], result.getDetectionMethod()
                            == RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY
                            || result.getDetectionMethod()
                            == RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK);
                    assertEquals(testCase[1], 4, result.getProjectedCorners().size());
                    assertTrue(testCase[1], result.getPose() != null);
                    assertEquals(testCase[1],
                            RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                            result.getValidationState());
                } finally {
                    gray.release();
                    rgb.release();
                }
            }

            Mat originalBoardRgb = decodeRgb(
                    appContext, "references/variants/main-board-white-top.jpg");
            Mat boardRgb = resizeToAnalysisResolution(originalBoardRgb);
            originalBoardRgb.release();
            Mat boardGray = new Mat();
            try {
                Imgproc.cvtColor(boardRgb, boardGray, Imgproc.COLOR_RGB2GRAY);
                CameraCalibration calibration = calibration(boardGray);
                for (String targetId : new String[]{NeobotSeedData.MOTOR_BRACKET,
                        NeobotSeedData.GEAR_MOTOR, NeobotSeedData.MOTOR_DRIVER}) {
                    RecognitionRequest request = productionRequest(targetId, calibration);
                    RecognitionResult primary = recognizer.recognize(
                            boardGray, System.nanoTime(), request);
                    RecognitionResult result = shapeRecognizer.recognize(
                            boardGray, boardRgb, System.nanoTime(), request, primary);
                    assertEquals(targetId, NeobotSeedData.MAIN_BOARD, result.getComponentId());
                    assertEquals(targetId,
                            RecognitionResult.ValidationState.COMPONENT_MISMATCH,
                            result.getValidationState());
                }
            } finally {
                boardGray.release();
                boardRgb.release();
            }
        }
    }

    private static Context context() {
        assertTrue(OpenCVLoader.initLocal());
        return ApplicationProvider.getApplicationContext();
    }

    private static List<ComponentReferenceSpec> catalog(Context context) {
        return IndividualPartTestCatalog.filter(
                VisionReferenceFactory.build(AssemblArRepository.getInstance(context)));
    }

    private static Mat decode(Context context, String path) throws Exception {
        return decode(context, path, Imgcodecs.IMREAD_GRAYSCALE);
    }

    private static Mat decodeRgb(Context context, String path) throws Exception {
        Mat bgr = decode(context, path, Imgcodecs.IMREAD_COLOR);
        Mat rgb = new Mat();
        try {
            Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
            return rgb;
        } finally {
            bgr.release();
        }
    }

    private static Mat decode(Context context, String path, int flags) throws Exception {
        try (InputStream input = context.getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            MatOfByte encoded = new MatOfByte(output.toByteArray());
            try {
                Mat decoded = Imgcodecs.imdecode(encoded, flags);
                assertFalse(path, decoded.empty());
                return decoded;
            } finally {
                encoded.release();
            }
        }
    }

    private static Mat resizeToAnalysisResolution(Mat source) {
        double scale = Math.min(1.0, 1280.0 / Math.max(source.cols(), source.rows()));
        Mat resized = new Mat();
        Imgproc.resize(source, resized,
                new Size(Math.round(source.cols() * scale), Math.round(source.rows() * scale)),
                0.0, 0.0, Imgproc.INTER_AREA);
        return resized;
    }

    private static CameraCalibration calibration(Mat frame) {
        double focalLengthPx = Math.max(frame.cols(), frame.rows()) * 1.15;
        return CameraCalibration.pinhole(focalLengthPx, focalLengthPx,
                frame.cols() / 2.0, frame.rows() / 2.0);
    }

    private static RecognitionRequest productionRequest(
            String componentId, CameraCalibration calibration) {
        return new RecognitionRequest(
                RecognitionRequest.AssemblyExpectation.component(componentId),
                calibration, null);
    }

    private static String diagnostics(RecognitionResult result) {
        RecognitionResult.RejectionEvidence evidence = result.getRejectionEvidence();
        String prefix = "method=" + result.getDetectionMethod()
                + " component=" + result.getComponentId()
                + " reference=" + result.getReferenceId()
                + " frameKp=" + result.getFrameKeypointCount()
                + " good=" + result.getGoodMatchCount()
                + " inliers=" + result.getInlierCount() + " ";
        return evidence == null ? prefix + "no rejection evidence"
                : prefix + "reason=" + evidence.getReason()
                + " ref=" + evidence.getReferenceId()
                + " frameKp=" + evidence.getFrameKeypointCount()
                + " refKp=" + evidence.getReferenceKeypointCount()
                + " good=" + evidence.getGoodMatchCount()
                + " valid=" + evidence.getValidMatchCount()
                + " inliers=" + evidence.getInlierCount()
                + " ratio=" + evidence.getInlierRatio();
    }
}
