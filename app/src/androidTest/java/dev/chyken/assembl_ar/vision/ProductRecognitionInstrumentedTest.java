package dev.chyken.assembl_ar.vision;

import android.content.Context;
import android.util.Log;
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
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import dev.chyken.assembl_ar.camera.ProductReferenceCatalog;
import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import static org.junit.Assert.*;

/** Production catalog + default assembly thresholds; evaluation photos never enter the database. */
@RunWith(AndroidJUnit4.class)
public final class ProductRecognitionInstrumentedTest {
    @BeforeClass public static void loadNative() { assertTrue(OpenCVLoader.initLocal()); }

    @Test public void mainAppLoadsExactProductReferencesWithoutResetOrDiagnosticFilter() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        assertEquals("dev.chyken.assembl_ar", app.getPackageName());
        List<ComponentReferenceSpec> specs = VisionReferenceFactory.build(AssemblArRepository.getInstance(app));
        for (ComponentReferenceSpec product : ProductReferenceCatalog.references()) {
            assertTrue(product.getReferenceId(), specs.stream().anyMatch(s ->
                    product.getReferenceId().equals(s.getReferenceId())
                            && product.getJpegAssetPath().equals(s.getJpegAssetPath())));
        }
        assertFalse(specs.stream().anyMatch(s -> s.getJpegAssetPath() != null
                && (s.getJpegAssetPath().contains("held-out")
                || s.getJpegAssetPath().contains("caster-kit-original")
                || s.getJpegAssetPath().contains("caster-oblique"))));
        try (ReferenceDatabase database = ReferenceDatabase.load(app.getAssets(), specs, VisionConfig.defaults())) {
            assertTrue(database.getComponentIds().contains(NeobotSeedData.CASTER_WHEEL));
            assertTrue(database.getComponentIds().contains(NeobotSeedData.GEAR_MOTOR));
        }
    }

    @Test public void mainPipelineRecognizesIndependentKitMotorAndCaster() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        VisionConfig config = VisionConfig.defaults();
        try (ReferenceDatabase database = ReferenceDatabase.load(app.getAssets(),
                VisionReferenceFactory.build(AssemblArRepository.getInstance(app)), config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            for (String[] fixture : new String[][] {
                    {NeobotSeedData.GEAR_MOTOR, "references/individual-test-held-out/gear-motor-20260906_131803.jpg"},
                    {NeobotSeedData.CASTER_WHEEL, "references/product-held-out/caster-kit-original.jpg"}}) {
                Mat rgb = decode(tests, fixture[1]);
                Mat gray = new Mat();
                try {
                    Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
                    RecognitionResult result = recognize(recognizer, gray, rgb, fixture[0]);
                    Log.i("ProductRecognition", fixture[1] + " method=" + result.getDetectionMethod()
                            + " id=" + result.getComponentId() + " inliers=" + result.getInlierCount());
                    assertEquals(fixture[1], fixture[0], result.getComponentId());
                    assertEquals(fixture[1], RecognitionResult.ValidationState.EXPECTED_COMPONENT, result.getValidationState());
                    assertEquals(fixture[1], 4, result.getProjectedCorners().size());
                    assertNotNull(fixture[1], result.getPose());
                    String wrong = NeobotSeedData.GEAR_MOTOR.equals(fixture[0])
                            ? NeobotSeedData.CASTER_WHEEL : NeobotSeedData.GEAR_MOTOR;
                    RecognitionResult mismatch = recognize(recognizer, gray, rgb, wrong);
                    assertFalse("Wrong part accepted: " + fixture[1], wrong.equals(mismatch.getComponentId())
                            && mismatch.getValidationState() == RecognitionResult.ValidationState.EXPECTED_COMPONENT);
                } finally { gray.release(); rgb.release(); }
            }
        }
    }

    @Test public void exactProductSourcesIdentifyPartAndPreserveFaceHandednessAtCameraScale() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        VisionConfig config = VisionConfig.defaults();
        try (ReferenceDatabase database = ReferenceDatabase.load(app.getAssets(),
                VisionReferenceFactory.build(AssemblArRepository.getInstance(app)), config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            for (ComponentReferenceSpec spec : ProductReferenceCatalog.references()) {
                Mat rgb = decode(app, spec.getJpegAssetPath());
                Mat gray = new Mat();
                try {
                    Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
                    RecognitionResult result = recognize(recognizer, gray, rgb, spec.getComponentId());
                    Log.i("ProductRecognition", "selfImageOnly=true " + spec.getReferenceId()
                            + " method=" + result.getDetectionMethod() + " id=" + result.getComponentId()
                            + " pose=" + (result.getPose()!=null) + " inliers=" + result.getInlierCount()
                            + " rejection=" + (result.getRejectionEvidence()==null ? "none" : result.getRejectionEvidence().getReason()));
                    assertEquals(spec.getReferenceId(), spec.getComponentId(), result.getComponentId());
                    boolean underside = spec.getJpegAssetPath().endsWith("bc-robotics-caster-mount.jpg");
                    assertEquals(spec.getReferenceId(), underside
                            ? RecognitionResult.ValidationState.WRONG_ORIENTATION
                            : RecognitionResult.ValidationState.EXPECTED_COMPONENT, result.getValidationState());
                    if (underside) assertEquals(RecognitionResult.OrientationClassification.MIRRORED,
                            result.getOrientationClassification());
                    assertEquals(spec.getReferenceId(), 4, result.getProjectedCorners().size());
                    // Seller lens intrinsics are unknown. This is an identity/registration sanity
                    // check, not a metric-pose benchmark. Kit-photo regressions require a pose.
                } finally { gray.release(); rgb.release(); }
            }
        }
    }

    @Test public void boardAndBlankCannotBecomeEitherProduct() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        VisionConfig config = VisionConfig.defaults();
        try (ReferenceDatabase database = ReferenceDatabase.load(app.getAssets(),
                VisionReferenceFactory.build(AssemblArRepository.getInstance(app)), config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            Mat board = decode(app, "references/variants/main-board-white-top.jpg");
            Mat blank = new Mat(720, 960, CvType.CV_8UC3, Scalar.all(185));
            try {
                for (Mat rgb : new Mat[]{board, blank}) {
                    Mat gray = new Mat();
                    try {
                        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
                        for (String id : new String[]{NeobotSeedData.GEAR_MOTOR, NeobotSeedData.CASTER_WHEEL}) {
                            RecognitionResult result = recognize(recognizer, gray, rgb, id);
                            assertFalse(id, id.equals(result.getComponentId())
                                    && result.getValidationState() == RecognitionResult.ValidationState.EXPECTED_COMPONENT);
                        }
                    } finally { gray.release(); }
                }
            } finally { board.release(); blank.release(); }
        }
    }

    @Test public void coinAndOtherLoosePartsCannotBecomeCaster() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        Mat source = decode(tests, "references/product-held-out/bc-robotics-caster-oblique.jpg");
        Mat coin = source.submat(new Rect((int)(source.cols()*.58), (int)(source.rows()*.54),
                (int)(source.cols()*.23), (int)(source.rows()*.28)));
        Mat bracket = decode(tests, "references/individual-test-held-out/motor-bracket-20260906_131648.jpg");
        Mat driver = decode(tests, "references/individual-test-held-out/motor-driver-20260906_131741.jpg");
        VisionConfig config = VisionConfig.defaults();
        try (ReferenceDatabase database = ReferenceDatabase.load(app.getAssets(),
                VisionReferenceFactory.build(AssemblArRepository.getInstance(app)), config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            for (Mat rgb : new Mat[]{coin, bracket, driver}) {
                Mat gray = new Mat();
                try {
                    Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
                    RecognitionResult result = recognize(recognizer, gray, rgb, NeobotSeedData.CASTER_WHEEL);
                    assertFalse("Look-alike accepted as caster", NeobotSeedData.CASTER_WHEEL.equals(result.getComponentId())
                            && result.getValidationState() == RecognitionResult.ValidationState.EXPECTED_COMPONENT);
                } finally { gray.release(); }
            }
        } finally { driver.release(); bracket.release(); coin.release(); source.release(); }
    }

    @Test public void casterFallbackRequiresBothMountingHoles() throws Exception {
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        Mat caster = decode(tests, "references/product-held-out/caster-kit-original.jpg");
        try {
            assertNotNull("Control photo must be recognizable", BallCasterShapeRecognizer.find(caster, .002));
            // Controlled occlusion of each mounting bore; no generated image is used as a reference.
            for (double[] hole : new double[][]{{.268,.604,.355,.690}, {.552,.435,.641,.518}}) {
                Mat occluded = caster.clone();
                try {
                    Imgproc.rectangle(occluded, new Point(caster.cols()*hole[0],caster.rows()*hole[1]),
                            new Point(caster.cols()*hole[2],caster.rows()*hole[3]), Scalar.all(185), -1);
                    assertNull("A missing bore must not satisfy caster shape", BallCasterShapeRecognizer.find(occluded, .002));
                } finally { occluded.release(); }
            }
        } finally { caster.release(); }
    }

    @Test public void casterFallbackCannotValidateOrientationRepeatedInstancesOrConflictingGeometry() throws Exception {
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        Mat rgb = decode(tests, "references/product-held-out/caster-kit-original.jpg");
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
            CameraCalibration calibration = CameraCalibration.pinhole(1500,1500,gray.cols()/2.0,gray.rows()/2.0);
            RecognitionRequest single = new RecognitionRequest(
                    RecognitionRequest.AssemblyExpectation.component(NeobotSeedData.CASTER_WHEEL), calibration, null);
            RecognitionResult primary = RecognitionResult.builder(75L).build();
            LoosePartShapeRecognizer fallback = new LoosePartShapeRecognizer();
            RecognitionResult control = fallback.recognize(gray,rgb,75L,single,primary);
            assertEquals(NeobotSeedData.CASTER_WHEEL, control.getComponentId());
            assertEquals(RecognitionResult.OrientationClassification.UNKNOWN, control.getOrientationClassification());
            RecognitionRequest oriented = new RecognitionRequest(new RecognitionRequest.AssemblyExpectation(
                    NeobotSeedData.CASTER_WHEEL, 0.0, 15.0, null), calibration, null);
            RecognitionRequest repeated = new RecognitionRequest(single.getExpectation(), calibration, null, null, 2);
            assertSame(primary, fallback.recognize(gray,rgb,76L,oriented,primary));
            assertSame(primary, fallback.recognize(gray,rgb,77L,repeated,primary));
            RecognitionResult conflict = RecognitionResult.builder(78L)
                    .identity("other-reference", NeobotSeedData.MAIN_BOARD, "Main board")
                    .method(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY)
                    .validation(RecognitionResult.ValidationState.COMPONENT_MISMATCH)
                    .corners(control.getProjectedCorners()).pose(control.getPose()).build();
            assertSame(conflict, fallback.recognize(gray,rgb,78L,single,conflict));
        } finally { gray.release(); rgb.release(); }
    }

    private static RecognitionResult recognize(ComponentRecognizer recognizer, Mat gray, Mat rgb, String id) {
        double focal = Math.max(gray.cols(), gray.rows()) * 1.2;
        RecognitionRequest request = new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id),
                CameraCalibration.pinhole(focal, focal, gray.cols()/2.0, gray.rows()/2.0), null, null, 1, 0);
        long timestamp = System.nanoTime();
        RecognitionResult primary = recognizer.recognize(gray, timestamp, request);
        return new LoosePartShapeRecognizer().recognize(gray, rgb, timestamp, request, primary);
    }

    static Mat decode(Context context, String path) throws Exception {
        byte[] bytes;
        try (InputStream in = context.getAssets().open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            bytes = out.toByteArray();
        }
        MatOfByte encoded = new MatOfByte(bytes);
        Mat bgr = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
        Mat resized = new Mat(); Mat rgb = new Mat();
        try {
            double scale = Math.min(1.0, 1280.0 / Math.max(bgr.cols(), bgr.rows()));
            Imgproc.resize(bgr, resized, new Size(), scale, scale, Imgproc.INTER_AREA);
            Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB);
            return rgb;
        } finally { resized.release(); bgr.release(); encoded.release(); }
    }
}
