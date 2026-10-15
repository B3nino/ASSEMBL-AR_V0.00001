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
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;

import static org.junit.Assert.*;

/** Real bracket appearance regressions run against the main app and its normal thresholds. */
@RunWith(AndroidJUnit4.class)
public final class BracketLightingInstrumentedTest {
    private static final String BASE = "references/bracket-shape/";
    private static final String COLLAGE = "user-bracket-lighting.webp";
    private static final String[] RESERVED = {
            "20260906_131457.jpg", "20260906_131444.jpg",
            "20260906_131614.jpg", "20260906_131506.jpg"};
    // Original-pixel crops, with every shoulder retained. Small clipped neighbor fragments at
    // some crop edges are intentional: they must not become part of the selected bracket.
    private static final Rect[] PHOTO_PARTS = {
            new Rect(23, 36, 150, 213), new Rect(145, 109, 145, 206),
            new Rect(254, 36, 152, 210), new Rect(366, 108, 151, 209)};

    @BeforeClass public static void loadNative() {
        assertTrue(OpenCVLoader.initLocal());
    }

    @Test public void mainAppUsesProductionCatalogAndNeverLoadsEvaluationPhotos() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        assertEquals("dev.chyken.assembl_ar", app.getPackageName());
        List<ComponentReferenceSpec> specs = VisionReferenceFactory.build(AssemblArRepository.getInstance(app));
        for (ComponentReferenceSpec spec : specs) {
            String path = spec.getJpegAssetPath();
            if (path == null) continue;
            assertFalse(path, path.contains("bracket-shape") || path.endsWith(COLLAGE));
            for (String reserved : RESERVED) assertFalse(path, path.endsWith(reserved));
        }
        try (ReferenceDatabase database = ReferenceDatabase.load(app.getAssets(), specs, VisionConfig.defaults())) {
            assertTrue(database.getComponentIds().contains(NeobotSeedData.MOTOR_BRACKET));
        }
    }

    @Test public void mainPipelineLocalizesFourReservedRealBracketPhotos() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        VisionConfig config = VisionConfig.defaults();
        List<String> failures = new ArrayList<>();
        try (ReferenceDatabase database = ReferenceDatabase.load(app.getAssets(),
                VisionReferenceFactory.build(AssemblArRepository.getInstance(app)), config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            for (String name : RESERVED) {
                Mat rgb = ProductRecognitionInstrumentedTest.decode(tests, BASE + name);
                Mat gray = new Mat();
                try {
                    Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
                    RecognitionRequest request = request(gray);
                    long timestamp = System.nanoTime();
                    RecognitionResult primary = recognizer.recognize(gray, timestamp, request);
                    RecognitionResult result = new LoosePartShapeRecognizer().recognize(
                            gray, rgb, timestamp, request, primary);
                    recordGeometry("main:" + name, result, failures);
                } finally { gray.release(); rgb.release(); }
            }
        }
        assertTrue(failures.toString(), failures.isEmpty());
    }

    @Test public void reflectiveAndBlackPhotoPartsLocalizeFromRgbAndGray() throws Exception {
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        Mat collage = ProductRecognitionInstrumentedTest.decode(tests, BASE + COLLAGE);
        List<String> failures = new ArrayList<>();
        try {
            assertEquals(532, collage.cols());
            assertEquals(360, collage.rows());
            for (int index = 0; index < PHOTO_PARTS.length; index++) {
                Mat crop = collage.submat(PHOTO_PARTS[index]);
                try { checkBothChannels("original-photo-part-" + index, crop, failures); }
                finally { crop.release(); }
            }
            for (String name : RESERVED) {
                Mat rgb = ProductRecognitionInstrumentedTest.decode(tests, BASE + name);
                try { checkBothChannels(name, rgb, failures); }
                finally { rgb.release(); }
            }
        } finally { collage.release(); }
        assertTrue(failures.toString(), failures.isEmpty());
    }

    @Test public void moderateExposureTransformsAreSyntheticRobustnessChecksOnly() throws Exception {
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        Mat collage = ProductRecognitionInstrumentedTest.decode(tests, BASE + COLLAGE);
        Mat reflective = collage.submat(PHOTO_PARTS[1]);
        Mat black = ProductRecognitionInstrumentedTest.decode(tests, BASE + "20260906_131444.jpg");
        List<String> failures = new ArrayList<>();
        try {
            int sourceIndex = 0;
            for (Mat source : new Mat[]{reflective, black}) {
                for (double[] exposure : new double[][]{{.70, 0}, {1.10, 18}}) {
                    Mat changed = new Mat();
                    try {
                        source.convertTo(changed, -1, exposure[0], exposure[1]);
                        checkBothChannels("synthetic-exposure:source=" + sourceIndex
                                + ":gain=" + exposure[0] + ":offset=" + exposure[1], changed, failures);
                    } finally { changed.release(); }
                }
                sourceIndex++;
            }
        } finally { black.release(); reflective.release(); collage.release(); }
        assertTrue(failures.toString(), failures.isEmpty());
    }

    @Test public void fourPartPhotoCannotChooseAnArbitrarySingleBracket() throws Exception {
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        Mat rgb = ProductRecognitionInstrumentedTest.decode(tests, BASE + COLLAGE);
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
            for (Mat color : new Mat[]{rgb, null}) {
                RecognitionResult primary = RecognitionResult.builder(301L).build();
                RecognitionResult result = new LoosePartShapeRecognizer().recognize(
                        gray, color, 301L, request(gray), primary);
                assertSame("Four visible brackets require disambiguation", primary, result);
            }
        } finally { gray.release(); rgb.release(); }
    }

    @Test public void realMotorDriverAndMainBoardCannotBecomeBracket() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        VisionConfig config = VisionConfig.defaults();
        try (ReferenceDatabase database = ReferenceDatabase.load(app.getAssets(),
                VisionReferenceFactory.build(AssemblArRepository.getInstance(app)), config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            String[] paths = {"references/individual-test-held-out/gear-motor-20260906_131803.jpg",
                    "references/individual-test-held-out/motor-driver-20260906_131741.jpg",
                    "references/variants/main-board-white-top.jpg"};
            for (int index = 0; index < paths.length; index++) {
                Mat rgb = ProductRecognitionInstrumentedTest.decode(index == 2 ? app : tests, paths[index]);
                Mat gray = new Mat();
                try {
                    Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
                    RecognitionRequest request = request(gray);
                    long timestamp = System.nanoTime();
                    RecognitionResult primary = recognizer.recognize(gray, timestamp, request);
                    RecognitionResult result = new LoosePartShapeRecognizer().recognize(
                            gray, rgb, timestamp, request, primary);
                    assertFalse(paths[index], NeobotSeedData.MOTOR_BRACKET.equals(result.getComponentId())
                            && result.getValidationState() == RecognitionResult.ValidationState.EXPECTED_COMPONENT);
                    // Also exercise the grayscale-only branch; it cannot rely on the color veto.
                    RecognitionResult empty = RecognitionResult.builder(timestamp).build();
                    assertSame(paths[index], empty, new LoosePartShapeRecognizer().recognize(
                            gray, null, timestamp, request, empty));
                } finally { gray.release(); rgb.release(); }
            }
        }
    }

    @Test public void orientationRepeatedInstancesAndConflictingGeometryRemainProtected() throws Exception {
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        Mat rgb = ProductRecognitionInstrumentedTest.decode(tests, BASE + "20260906_131444.jpg");
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
            RecognitionRequest single = request(gray);
            RecognitionResult empty = RecognitionResult.builder(401L).build();
            LoosePartShapeRecognizer fallback = new LoosePartShapeRecognizer();
            RecognitionResult control = fallback.recognize(gray, rgb, 401L, single, empty);
            assertEquals(NeobotSeedData.MOTOR_BRACKET, control.getComponentId());
            assertNotNull(control.getPose());
            assertEquals(RecognitionResult.OrientationClassification.UNKNOWN, control.getOrientationClassification());
            RecognitionRequest oriented = new RecognitionRequest(new RecognitionRequest.AssemblyExpectation(
                    NeobotSeedData.MOTOR_BRACKET, 0.0, 15.0, null), single.getCameraCalibration(), null);
            RecognitionRequest repeated = new RecognitionRequest(single.getExpectation(),
                    single.getCameraCalibration(), null, null, 2);
            assertSame(empty, fallback.recognize(gray, rgb, 402L, oriented, empty));
            // Repeated loose inventories now report visible partial sets for the quantity HUD.
            // One visible bracket must never be inflated into the requested pair.
            RecognitionResult partial = fallback.recognize(gray, rgb, 403L, repeated, empty);
            assertEquals(1, partial.getDetectedInstanceCount(NeobotSeedData.MOTOR_BRACKET));
            RecognitionResult conflict = RecognitionResult.builder(404L)
                    .identity("other-reference", NeobotSeedData.MAIN_BOARD, "Main board")
                    .method(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY)
                    .validation(RecognitionResult.ValidationState.COMPONENT_MISMATCH)
                    .corners(control.getProjectedCorners()).pose(control.getPose()).build();
            assertSame(conflict, fallback.recognize(gray, rgb, 404L, single, conflict));
        } finally { gray.release(); rgb.release(); }
    }

    private static void checkBothChannels(String label, Mat rgb, List<String> failures) {
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
            for (Mat color : new Mat[]{rgb, null}) {
                long timestamp = System.nanoTime();
                RecognitionResult result = new LoosePartShapeRecognizer().recognize(gray, color,
                        timestamp, request(gray), RecognitionResult.builder(timestamp).build());
                String caseName = label + (color == null ? ":gray" : ":rgb");
                recordGeometry(caseName, result, failures);
                if (hasBracketGeometry(result)) {
                    assertEquals(caseName, RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK,
                            result.getDetectionMethod());
                    assertEquals(caseName, RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                            result.getValidationState());
                }
            }
        } finally { gray.release(); }
    }

    private static void recordGeometry(String label, RecognitionResult result, List<String> failures) {
        boolean passes = hasBracketGeometry(result);
        Log.i("BracketLighting", label + " pass=" + passes + " id=" + result.getComponentId()
                + " method=" + result.getDetectionMethod() + " validation=" + result.getValidationState()
                + " corners=" + result.getProjectedCorners().size() + " pose=" + (result.getPose() != null));
        if (!passes) failures.add(label);
    }

    private static boolean hasBracketGeometry(RecognitionResult result) {
        return NeobotSeedData.MOTOR_BRACKET.equals(result.getComponentId())
                && result.getProjectedCorners().size() == 4 && result.getPose() != null;
    }

    private static RecognitionRequest request(Mat frame) {
        double focal = Math.max(frame.cols(), frame.rows()) * 1.2;
        return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(NeobotSeedData.MOTOR_BRACKET),
                CameraCalibration.pinhole(focal, focal, frame.cols() / 2.0, frame.rows() / 2.0),
                null, null, 1, 0);
    }
}
