package dev.chyken.assembl_ar.vision;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.camera.ReviewedIndividualReferenceCatalog;
import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.workflow.BracketSetRecognitionGate;

import static org.junit.Assert.*;

/**
 * Native packaging and registration regressions for reviewed original-photo crops.
 * RGB PNG fixtures are the exact prepared crops before grayscale packaging, not unseen captures.
 * Production stores only the 8-bit grayscale consumed by ReferenceDatabase; RGB archive masters
 * remain separate. These checks establish zero native grayscale packaging variance; they do not
 * establish unchanged confidence across different photographs or held-out recognition accuracy.
 */
@RunWith(AndroidJUnit4.class)
public final class ReviewedIndividualReferenceInstrumentedTest {
    private static final String RUNTIME_PREFIX = "references/individual-20260927/";
    private static final String PARITY_PREFIX = "references/individual-20260927-parity/";

    @BeforeClass public static void loadNative() {
        assertTrue("The packaged OpenCV runtime must initialize", OpenCVLoader.initLocal());
    }

    @Test public void reviewedAssetsAreRegisteredInProductionWithoutEvaluationFixtures() {
        Context app = appContext();
        List<ComponentReferenceSpec> production = productionSpecs(app);
        assertFalse("The reviewed catalog must contain admitted references", reviewed().isEmpty());
        for (ComponentReferenceSpec spec : reviewed()) {
            assertTrue(spec.getReferenceId(), production.stream().anyMatch(candidate ->
                    spec.getReferenceId().equals(candidate.getReferenceId())
                            && spec.getJpegAssetPath().equals(candidate.getJpegAssetPath())));
            assertTrue(spec.getJpegAssetPath().startsWith(RUNTIME_PREFIX));
            assertTrue(spec.getJpegAssetPath().endsWith(".png"));
            assertTrue("Original-photo features must be explicitly masked",
                    !spec.getFeatureRegionsNormalized().isEmpty());
            assertFalse("A new photograph must not certify terminal polarity",
                    spec.hasVerifiedPolarityAnchors());
        }
        for (ComponentReferenceSpec spec : production) {
            String path = spec.getJpegAssetPath();
            assertFalse("Parity fixtures must remain test-only", path != null
                    && path.contains("individual-20260927-parity"));
        }
    }

    @Test public void everyReviewedCropProvidesSufficientNativeMaskedFeatures() throws Exception {
        Context app = appContext();
        VisionConfig config = VisionConfig.defaults();
        try (ReferenceDatabase database = ReferenceDatabase.load(
                app.getAssets(), reviewed(), config)) {
            assertEquals("Preparation must not silently skip an admitted crop",
                    reviewed().size(), database.size());
            for (ReferenceDatabase.PreparedReference prepared : database.preparedReferences()) {
                int maximumFeatures = 0;
                for (ReferenceDatabase.PreparedOrbVariant variant : prepared.orbVariants()) {
                    assertEquals(prepared.spec.getReferenceId(), variant.keypoints.length,
                            variant.descriptors.rows());
                    maximumFeatures = Math.max(maximumFeatures, variant.keypoints.length);
                    for (KeyPoint point : variant.keypoints) {
                        assertTrue(prepared.spec.getReferenceId() + " keypoint outside authored mask",
                                insideAnyFeatureRegion(prepared.spec, point.pt.x, point.pt.y,
                                        prepared.imageWidth, prepared.imageHeight));
                    }
                }
                Log.i("ReviewedPhotoAudit", prepared.spec.getReferenceId()
                        + " maskedFeatures=" + maximumFeatures);
                assertTrue(prepared.spec.getReferenceId() + " has only " + maximumFeatures
                                + " native masked features",
                        maximumFeatures >= config.getMinimumGoodMatches());
            }
        }
    }

    @Test public void freshPreparationAndCachePreserveDescriptorAndKeypointValues() throws Exception {
        Context app = appContext();
        VisionConfig config = VisionConfig.defaults();
        File directory = newTestDirectory(app);
        try (ReferenceDatabase first = ReferenceDatabase.load(app.getAssets(), reviewed(), config);
             ReferenceDatabase repeated = ReferenceDatabase.load(app.getAssets(), reviewed(), config);
             ReferenceDatabase cacheWriter = ReferenceDatabase.load(
                     app.getAssets(), reviewed(), config, directory)) {
            assertPreparedListsEqual(first, repeated, false);
            assertPreparedListsEqual(first, cacheWriter, false);
            File[] cacheFiles = directory.listFiles((parent, name) -> name.endsWith(".asor"));
            assertNotNull(cacheFiles);
            assertEquals("Every reviewed source needs its own versioned cache",
                    reviewed().size(), cacheFiles.length);
            byte[][] before = new byte[cacheFiles.length][];
            long[] timestamps = new long[cacheFiles.length];
            for (int index = 0; index < cacheFiles.length; index++) {
                before[index] = java.nio.file.Files.readAllBytes(cacheFiles[index].toPath());
                assertTrue(cacheFiles[index].setLastModified(1_600_000_000_000L));
                timestamps[index] = cacheFiles[index].lastModified();
            }
            try (ReferenceDatabase cached = ReferenceDatabase.load(
                    app.getAssets(), reviewed(), config, directory)) {
                // ASOR stores remapped coordinates as IEEE float. Compare exact serialized
                // float values, not pre-serialization doubles; descriptor bytes remain exact.
                assertPreparedListsEqual(first, cached, true);
            }
            for (int index = 0; index < cacheFiles.length; index++) {
                assertEquals("A cache hit must not rebuild ORB data", timestamps[index],
                        cacheFiles[index].lastModified());
                assertArrayEquals(before[index],
                        java.nio.file.Files.readAllBytes(cacheFiles[index].toPath()));
            }
        } finally {
            deleteTestDirectory(app, directory);
        }
    }

    @Test public void optimizedGrayPngPreservesNativeGrayAndPreparedOrbExactly() throws Exception {
        Context app = appContext();
        AssetManager fixtures = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        VisionConfig config = VisionConfig.defaults();
        File directory = newTestDirectory(app);
        try {
            for (ComponentReferenceSpec spec : reviewed()) {
                String basename = spec.getJpegAssetPath().substring(RUNTIME_PREFIX.length());
                String pngPath = PARITY_PREFIX + basename;
                byte[] pngBytes = read(fixtures, pngPath);
                byte[] runtimeBytes = read(app.getAssets(), spec.getJpegAssetPath());
                Mat fixturePixels = decode(pngBytes, Imgcodecs.IMREAD_UNCHANGED);
                Mat runtimePixels = decode(runtimeBytes, Imgcodecs.IMREAD_UNCHANGED);
                Mat fixtureGray = decode(pngBytes, Imgcodecs.IMREAD_GRAYSCALE);
                Mat runtimeGray = decode(runtimeBytes, Imgcodecs.IMREAD_GRAYSCALE);
                try {
                    assertEquals("Uncompressed baseline retains photographed RGB channels",
                            CvType.CV_8UC3, fixturePixels.type());
                    assertEquals("Runtime stores only the grayscale consumed by recognition",
                            CvType.CV_8UC1, runtimePixels.type());
                    assertEquals(fixturePixels.rows(), runtimePixels.rows());
                    assertEquals(fixturePixels.cols(), runtimePixels.cols());
                    assertMatEqual(spec.getReferenceId() + " native grayscale pixels",
                            fixtureGray, runtimeGray);
                } finally {
                    fixturePixels.release(); runtimePixels.release();
                    fixtureGray.release(); runtimeGray.release();
                }
                File pngSource = new File(directory, basename + ".png");
                try (FileOutputStream output = new FileOutputStream(pngSource)) {
                    output.write(pngBytes);
                }
                ComponentReferenceSpec pngSpec = withLocalSource(spec, pngSource);
                try (ReferenceDatabase pngDatabase = ReferenceDatabase.load(app.getAssets(),
                        Collections.singletonList(pngSpec), config);
                     ReferenceDatabase runtimeDatabase = ReferenceDatabase.load(app.getAssets(),
                             Collections.singletonList(spec), config)) {
                    assertPreparedListsEqual(pngDatabase, runtimeDatabase, false);
                    Mat pngGray = decode(pngBytes, Imgcodecs.IMREAD_GRAYSCALE);
                    Mat packagedGray = decode(runtimeBytes, Imgcodecs.IMREAD_GRAYSCALE);
                    try (ComponentRecognizer pngFrameRecognizer = new ComponentRecognizer(config, runtimeDatabase);
                         ComponentRecognizer packagedFrameRecognizer = new ComponentRecognizer(config, runtimeDatabase)) {
                        // Both frames use the identical production registration. Fix the native
                        // RANSAC seed to isolate file encoding from randomized estimation.
                        Core.setRNGSeed(27_092_026);
                        RecognitionResult pngResult = recognize(pngFrameRecognizer, pngGray,
                                spec.getComponentId());
                        Core.setRNGSeed(27_092_026);
                        RecognitionResult packagedResult = recognize(packagedFrameRecognizer, packagedGray,
                                spec.getComponentId());
                        assertEquals(spec.getReferenceId(), pngResult.getComponentId(), packagedResult.getComponentId());
                        assertEquals(spec.getReferenceId(), pngResult.getValidationState(), packagedResult.getValidationState());
                        assertEquals(spec.getReferenceId(), pngResult.getDetectionMethod(), packagedResult.getDetectionMethod());
                        assertEquals(spec.getReferenceId(), pngResult.getGoodMatchCount(), packagedResult.getGoodMatchCount());
                        assertEquals(spec.getReferenceId(), pngResult.getInlierCount(), packagedResult.getInlierCount());
                        assertEquals(spec.getReferenceId(), pngResult.getConfidence(), packagedResult.getConfidence(), 0.0);
                        assertEquals(spec.getReferenceId(), pngResult.getProjectedCorners(), packagedResult.getProjectedCorners());
                    } finally { pngGray.release(); packagedGray.release(); }
                }
                assertTrue(pngSource.delete());
            }
        } finally { deleteTestDirectory(app, directory); }
    }

    @Test public void sameSourceRegistrationKeepsActualComponentAndLocalizedFace() throws Exception {
        Context app = appContext();
        VisionConfig config = VisionConfig.defaults();
        try (ReferenceDatabase database = ReferenceDatabase.load(app.getAssets(), productionSpecs(app), config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            for (ComponentReferenceSpec spec : reviewed()) {
                Mat gray = decode(read(app.getAssets(), spec.getJpegAssetPath()), Imgcodecs.IMREAD_GRAYSCALE);
                try {
                    RecognitionResult result = recognize(recognizer, gray, spec.getComponentId());
                    log("same-source-registration-not-held-out", spec, result);
                    assertEquals(spec.getReferenceId(), spec.getComponentId(), result.getComponentId());
                    assertEquals(spec.getReferenceId(), signedArea(spec.getPlanarCornersNormalized()) < 0
                                    ? RecognitionResult.ValidationState.WRONG_ORIENTATION
                                    : RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                            result.getValidationState());
                    assertEquals("Identity must be localized, not descriptor-only",
                            RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY, result.getDetectionMethod());
                    assertEquals(spec.getReferenceId(), 4, result.getProjectedCorners().size());
                    assertLocalizedFace(spec, result, gray);
                    assertNull("A loose part must not fabricate a board anchor", result.getSpatialAnchor());
                } finally { gray.release(); }
            }
        }
    }

    @Test public void blankAndOriginalBackgroundCannotSupplyRemovedPartIdentity() throws Exception {
        Context app = appContext();
        VisionConfig config = VisionConfig.defaults();
        try (ReferenceDatabase database = ReferenceDatabase.load(app.getAssets(), productionSpecs(app), config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            Mat blank = new Mat(720, 960, CvType.CV_8UC1, Scalar.all(160));
            try {
                for (ComponentReferenceSpec spec : reviewed()) {
                    RecognitionResult blankResult = recognize(recognizer, blank, spec.getComponentId());
                    assertNotAccepted(spec.getReferenceId() + " blank", spec.getComponentId(), blankResult);
                    Mat gray = decode(read(app.getAssets(), spec.getJpegAssetPath()), Imgcodecs.IMREAD_GRAYSCALE);
                    try {
                        double minX = 1, minY = 1, maxX = 0, maxY = 0;
                        for (VisionPoint point : spec.getPlanarCornersNormalized()) {
                            minX = Math.min(minX, point.getX()); minY = Math.min(minY, point.getY());
                            maxX = Math.max(maxX, point.getX()); maxY = Math.max(maxY, point.getY());
                        }
                        // Controlled negative only. Never saved or registered as a reference.
                        Imgproc.rectangle(gray, new Point((minX - .035) * gray.cols(),
                                        (minY - .035) * gray.rows()),
                                new Point((maxX + .035) * gray.cols(), (maxY + .035) * gray.rows()),
                                Scalar.all(160), -1);
                        RecognitionResult erased = recognize(recognizer, gray, spec.getComponentId());
                        log("target-erased-negative", spec, erased);
                        assertNotAccepted(spec.getReferenceId() + " erased target",
                                spec.getComponentId(), erased);
                    } finally { gray.release(); }
                }
            } finally { blank.release(); }
        }
    }

    @Test public void reviewedLoosePartsCannotValidateWrongComponentOrAssemblyBoard() throws Exception {
        Context app = appContext();
        VisionConfig config = VisionConfig.defaults();
        try (ReferenceDatabase database = ReferenceDatabase.load(app.getAssets(), productionSpecs(app), config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            for (ComponentReferenceSpec spec : reviewed()) {
                Mat gray = decode(read(app.getAssets(), spec.getJpegAssetPath()), Imgcodecs.IMREAD_GRAYSCALE);
                try {
                    String wrong = NeobotSeedData.MICROCONTROLLER.equals(spec.getComponentId())
                            ? NeobotSeedData.MOTOR_BRACKET : NeobotSeedData.MICROCONTROLLER;
                    for (String expected : new String[]{wrong, NeobotSeedData.MAIN_BOARD}) {
                        RecognitionResult result = recognize(recognizer, gray, expected);
                        assertNotAccepted(spec.getReferenceId() + " expected " + expected, expected, result);
                    }
                } finally { gray.release(); }
            }
        }
    }

    @Test public void unregisteredSameSessionSetCapturesRecordRealCountAndIncompleteGates() throws Exception {
        Context app = appContext();
        Context fixtures = InstrumentationRegistry.getInstrumentation().getContext();
        List<ComponentReferenceSpec> production = productionSpecs(app);
        String[] paths = {
                "references/individual-20260927-sets/four-brackets-20260927_095901.png",
                "references/individual-20260927-sets/four-brackets-20260927_095916.png",
                "references/individual-20260927-sets/four-brackets-20260927_095937.png",
                "references/individual-20260927-sets/four-brackets-20260927_095945.png",
                "references/individual-20260927-sets/four-brackets-20260927_095949.png",
                "references/individual-20260927-sets/four-brackets-20260927_100002.png"
        };
        // Two clear views localize all four. Harder views have these measured floors;
        // improvements may exceed them without making this regression fail.
        int[] minimumObservedCounts = {4, 4, 2, 2, 0, 3};
        for (ComponentReferenceSpec spec : production) {
            assertFalse("Group evaluation captures must never be registered",
                    spec.getJpegAssetPath() != null
                            && spec.getJpegAssetPath().contains("individual-20260927-sets"));
        }
        VisionConfig config = VisionConfig.defaults();
        List<String> failures = new java.util.ArrayList<>();
        try (ReferenceDatabase database = ReferenceDatabase.load(app.getAssets(), production, config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            LoosePartShapeRecognizer fallback = new LoosePartShapeRecognizer();
            for (int captureIndex = 0; captureIndex < paths.length; captureIndex++) {
                String path = paths[captureIndex];
                // This helper preserves RGB channel order and bounds the capture to 1280 pixels,
                // consistently with existing camera-photo recognition regressions.
                Mat rgb = ProductRecognitionInstrumentedTest.decode(fixtures, path);
                Mat gray = new Mat();
                try {
                    Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
                    double focal = Math.max(gray.cols(), gray.rows()) * 1.2;
                    RecognitionRequest request = new RecognitionRequest(
                            RecognitionRequest.AssemblyExpectation.component(NeobotSeedData.MOTOR_BRACKET),
                            CameraCalibration.pinhole(focal, focal, gray.cols() / 2.0, gray.rows() / 2.0),
                            null, null, 4, 2);
                    long timestamp = System.nanoTime();
                    RecognitionResult primary = recognizer.recognize(gray, timestamp, request);
                    List<MotorBracketShapeRecognizer.Candidate> shapes = MotorBracketShapeRecognizer.findAll(
                            gray, rgb, .002, null);
                    Log.i("ReviewedPhotoAudit", path + " rawSilhouetteCount=" + shapes.size());
                    for (MotorBracketShapeRecognizer.Candidate shape : shapes) {
                        Log.i("ReviewedPhotoAudit", path + " rawSilhouette center=" + shape.center
                                + " confidence=" + shape.confidence + " corners=" + shape.corners);
                    }
                    RecognitionResult result = fallback.recognize(gray, rgb, timestamp, request, primary);
                    int count = result.getDetectedInstanceCount(NeobotSeedData.MOTOR_BRACKET);
                    Log.i("ReviewedPhotoAudit", "unregistered-same-kit-same-session-set image=" + path
                            + " frame=" + gray.cols() + "x" + gray.rows()
                            + " primaryCount=" + primary.getDetectedInstanceCount(NeobotSeedData.MOTOR_BRACKET)
                            + " finalCount=" + count + " method=" + result.getDetectionMethod()
                            + " validation=" + result.getValidationState()
                            + " confidence=" + result.getConfidence()
                            + " inliers=" + result.getInlierCount()
                            + " rejection=" + (result.getRejectionEvidence() == null ? "none"
                            : result.getRejectionEvidence().getReason()));
                    for (RecognitionResult.InstanceObservation observation : result.getInstanceObservations()) {
                        Log.i("ReviewedPhotoAudit", path + " instance id=" + observation.getComponentId()
                                + " method=" + observation.getDetectionMethod()
                                + " confidence=" + observation.getConfidence()
                                + " corners=" + observation.getProjectedCorners());
                    }
                    if (count < minimumObservedCounts[captureIndex]) {
                        failures.add(path + " expected at least " + minimumObservedCounts[captureIndex]
                                + " observed brackets but found " + count);
                    }
                    if (count > 4) failures.add(path + " fabricated more than four brackets");
                    if (count > 0 && (!NeobotSeedData.MOTOR_BRACKET.equals(result.getComponentId())
                            || result.getValidationState() != RecognitionResult.ValidationState.EXPECTED_COMPONENT)) {
                        failures.add(path + " localized brackets lacked expected identity/validation");
                    }
                    BracketSetRecognitionGate gate = new BracketSetRecognitionGate();
                    gate.observe(result);
                    if (gate.getVisibleCount() != count) failures.add(path + " gate counted a different quantity");
                    if (count < 4 && gate.isReady()) failures.add(path + " incomplete inventory unlocked gate");
                    if (result.getSpatialAnchor() != null) {
                        failures.add(path + " inventory fabricated a placement anchor");
                    }
                } finally { gray.release(); rgb.release(); }
            }
        }
        assertTrue("Same-session group recognition failures: " + failures, failures.isEmpty());
    }

    @Test public void mirroredLooseBracketCountsOnlyForInventoryAndCannotCompleteFourPieceSet() throws Exception {
        Context app = appContext();
        VisionConfig config = VisionConfig.defaults();
        try (ReferenceDatabase database = ReferenceDatabase.load(app.getAssets(), productionSpecs(app), config);
             ComponentRecognizer inventoryRecognizer = new ComponentRecognizer(config, database);
             ComponentRecognizer singleRecognizer = new ComponentRecognizer(config, database)) {
            for (String suffix : new String[]{"100041.png", "100053.png"}) {
                ComponentReferenceSpec spec = reviewed().stream()
                        .filter(candidate -> candidate.getJpegAssetPath().endsWith(suffix))
                        .findFirst().orElseThrow(() -> new AssertionError("Missing mirrored bracket " + suffix));
                assertTrue("Control reference must retain mirrored physical geometry",
                        signedArea(spec.getPlanarCornersNormalized()) < 0);
                Mat gray = decode(read(app.getAssets(), spec.getJpegAssetPath()), Imgcodecs.IMREAD_GRAYSCALE);
                try {
                    double focal = Math.max(gray.cols(), gray.rows()) * 1.2;
                    RecognitionRequest inventoryRequest = new RecognitionRequest(
                            RecognitionRequest.AssemblyExpectation.component(NeobotSeedData.MOTOR_BRACKET),
                            CameraCalibration.pinhole(focal, focal, gray.cols() / 2.0, gray.rows() / 2.0),
                            null, null, 4, 2);
                    RecognitionResult inventory = inventoryRecognizer.recognize(gray,
                            System.nanoTime(), inventoryRequest);
                    log("mirrored-one-bracket-inventory", spec, inventory);
                    assertEquals(spec.getReferenceId(), NeobotSeedData.MOTOR_BRACKET, inventory.getComponentId());
                    assertEquals(spec.getReferenceId(), RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                            inventory.getValidationState());
                    assertEquals("Inventory must retain the observed mirrored face classification",
                            RecognitionResult.OrientationClassification.MIRRORED,
                            inventory.getOrientationClassification());
                    assertEquals("One visible bracket must remain one distinct instance", 1,
                            inventory.getDetectedInstanceCount(NeobotSeedData.MOTOR_BRACKET));
                    BracketSetRecognitionGate gate = new BracketSetRecognitionGate();
                    assertFalse("A single mirrored bracket cannot complete four-piece inventory",
                            gate.observe(inventory));
                    assertEquals(1, gate.getVisibleCount());
                    assertFalse(gate.isReady());
                    assertNull(inventory.getSpatialAnchor());

                    RecognitionResult single = recognize(singleRecognizer, gray, NeobotSeedData.MOTOR_BRACKET);
                    log("mirrored-one-bracket-single-orientation", spec, single);
                    assertEquals(spec.getReferenceId(), NeobotSeedData.MOTOR_BRACKET, single.getComponentId());
                    assertEquals("Ordinary single-part checks must retain mirrored-face rejection",
                            RecognitionResult.ValidationState.WRONG_ORIENTATION, single.getValidationState());
                    assertEquals(RecognitionResult.OrientationClassification.MIRRORED,
                            single.getOrientationClassification());
                    assertEquals(0, single.getDetectedInstanceCount(NeobotSeedData.MOTOR_BRACKET));
                } finally { gray.release(); }
            }
        }
    }

    private static List<ComponentReferenceSpec> reviewed() {
        List<ComponentReferenceSpec> result = ReviewedIndividualReferenceCatalog.references();
        assertFalse("No reviewed references were generated", result.isEmpty());
        return result;
    }

    private static Context appContext() { return ApplicationProvider.getApplicationContext(); }

    private static List<ComponentReferenceSpec> productionSpecs(Context context) {
        return VisionReferenceFactory.build(AssemblArRepository.getInstance(context));
    }

    private static RecognitionResult recognize(ComponentRecognizer recognizer, Mat gray, String expected) {
        double focal = Math.max(gray.cols(), gray.rows()) * 1.2;
        RecognitionRequest request = new RecognitionRequest(
                RecognitionRequest.AssemblyExpectation.component(expected),
                CameraCalibration.pinhole(focal, focal, gray.cols() / 2.0, gray.rows() / 2.0),
                null, null, 1, 0);
        return recognizer.recognize(gray, System.nanoTime(), request);
    }

    private static ComponentReferenceSpec withLocalSource(ComponentReferenceSpec spec, File file) {
        ComponentReferenceSpec.Builder builder = ComponentReferenceSpec.builder(spec.getReferenceId(),
                        spec.getComponentId(), spec.getDisplayName(),
                        spec.getPhysicalWidthMm(), spec.getPhysicalHeightMm())
                .localImageFilePath(file.getAbsolutePath())
                .maximumImageDimension(spec.getMaximumImageDimension())
                .descriptorRegionNormalization(spec.isDescriptorRegionNormalizationEnabled())
                .polarityAnchorsVerified(spec.hasVerifiedPolarityAnchors())
                .featureRegionsNormalized(spec.getFeatureRegionsNormalized())
                .assemblyStepNumber(spec.getAssemblyStepNumber());
        List<VisionPoint> corners = spec.getPlanarCornersNormalized();
        if (signedArea(corners) < 0) {
            builder.mirroredPlanarReferenceCornersNormalized(
                    corners.get(0), corners.get(1), corners.get(2), corners.get(3));
        } else {
            builder.planarReferenceCornersNormalized(
                    corners.get(0), corners.get(1), corners.get(2), corners.get(3));
        }
        return builder.build();
    }

    private static void assertPreparedListsEqual(ReferenceDatabase expected,
                                                 ReferenceDatabase actual, boolean serialized) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            ReferenceDatabase.PreparedReference a = expected.preparedReferences().get(index);
            ReferenceDatabase.PreparedReference b = actual.preparedReferences().get(index);
            String label = a.spec.getReferenceId();
            assertEquals(label, a.imageWidth, b.imageWidth);
            assertEquals(label, a.imageHeight, b.imageHeight);
            assertEquals(label, a.orbVariants.size(), b.orbVariants.size());
            for (int variantIndex = 0; variantIndex < a.orbVariants.size(); variantIndex++) {
                ReferenceDatabase.PreparedOrbVariant av = a.orbVariants.get(variantIndex);
                ReferenceDatabase.PreparedOrbVariant bv = b.orbVariants.get(variantIndex);
                assertEquals(label, av.photometricVariant, bv.photometricVariant);
                assertEquals(label, av.keypoints.length, bv.keypoints.length);
                assertMatEqual(label + " descriptor bytes", av.descriptors, bv.descriptors);
                for (int pointIndex = 0; pointIndex < av.keypoints.length; pointIndex++) {
                    KeyPoint ap = av.keypoints[pointIndex], bp = bv.keypoints[pointIndex];
                    assertEquals(label + " keypoint x", serialized ? (double) (float) ap.pt.x : ap.pt.x,
                            bp.pt.x, 0.0);
                    assertEquals(label + " keypoint y", serialized ? (double) (float) ap.pt.y : ap.pt.y,
                            bp.pt.y, 0.0);
                    assertEquals(label, ap.size, bp.size, 0.0f);
                    assertEquals(label, ap.angle, bp.angle, 0.0f);
                    assertEquals(label, ap.response, bp.response, 0.0f);
                    assertEquals(label, ap.octave, bp.octave);
                    assertEquals(label, ap.class_id, bp.class_id);
                }
            }
        }
    }

    private static void assertMatEqual(String label, Mat expected, Mat actual) {
        assertFalse(label + " empty baseline", expected.empty());
        assertFalse(label + " empty output", actual.empty());
        assertEquals(label, expected.rows(), actual.rows());
        assertEquals(label, expected.cols(), actual.cols());
        assertEquals(label, expected.type(), actual.type());
        assertEquals(label + " maximum absolute difference", 0.0,
                Core.norm(expected, actual, Core.NORM_INF), 0.0);
    }

    private static boolean insideAnyFeatureRegion(ComponentReferenceSpec spec, double x, double y,
                                                 int width, int height) {
        // Rasterized masks round authored vertices, so permit a 1.5-source-pixel edge band.
        for (List<VisionPoint> polygon : spec.getFeatureRegionsNormalized()) {
            boolean inside = false;
            for (int index = 0, previous = polygon.size() - 1;
                 index < polygon.size(); previous = index++) {
                VisionPoint normalizedA = polygon.get(index), normalizedB = polygon.get(previous);
                VisionPoint a = new VisionPoint(normalizedA.getX() * (width - 1.0),
                        normalizedA.getY() * (height - 1.0));
                VisionPoint b = new VisionPoint(normalizedB.getX() * (width - 1.0),
                        normalizedB.getY() * (height - 1.0));
                if ((a.getY() > y) != (b.getY() > y)
                        && x < (b.getX() - a.getX()) * (y - a.getY())
                        / (b.getY() - a.getY()) + a.getX()) inside = !inside;
                double dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                double t = Math.max(0, Math.min(1,
                        ((x - a.getX()) * dx + (y - a.getY()) * dy) / (dx * dx + dy * dy)));
                if (Math.hypot(x - a.getX() - t * dx, y - a.getY() - t * dy) <= 1.5) return true;
            }
            if (inside) return true;
        }
        return false;
    }

    private static void assertLocalizedFace(ComponentReferenceSpec spec, RecognitionResult result, Mat frame) {
        double expectedX = 0, expectedY = 0, actualX = 0, actualY = 0;
        for (int index = 0; index < 4; index++) {
            VisionPoint expected = spec.getPlanarCornersNormalized().get(index);
            VisionPoint actual = result.getProjectedCorners().get(index);
            expectedX += expected.getX() / 4; expectedY += expected.getY() / 4;
            actualX += actual.getX() / frame.cols() / 4; actualY += actual.getY() / frame.rows() / 4;
        }
        assertEquals(spec.getReferenceId() + " face center x", expectedX, actualX, .035);
        assertEquals(spec.getReferenceId() + " face center y", expectedY, actualY, .035);
        double expectedArea = Math.abs(signedArea(spec.getPlanarCornersNormalized()));
        double actualArea = Math.abs(signedArea(result.getProjectedCorners())) / frame.cols() / frame.rows();
        assertTrue(spec.getReferenceId() + " face area ratio=" + actualArea / expectedArea,
                actualArea / expectedArea > .70 && actualArea / expectedArea < 1.30);
    }

    private static double signedArea(List<VisionPoint> points) {
        double sum = 0;
        for (int index = 0; index < points.size(); index++) {
            VisionPoint a = points.get(index), b = points.get((index + 1) % points.size());
            sum += a.getX() * b.getY() - b.getX() * a.getY();
        }
        return sum / 2;
    }

    private static void assertNotAccepted(String label, String expected, RecognitionResult result) {
        assertFalse(label, expected.equals(result.getComponentId())
                && result.getValidationState() == RecognitionResult.ValidationState.EXPECTED_COMPONENT);
    }

    private static void log(String role, ComponentReferenceSpec spec, RecognitionResult result) {
        Log.i("ReviewedPhotoAudit", role + " reference=" + spec.getReferenceId()
                + " id=" + result.getComponentId() + " method=" + result.getDetectionMethod()
                + " validation=" + result.getValidationState() + " pose=" + (result.getPose() != null)
                + " matches=" + result.getGoodMatchCount() + " inliers=" + result.getInlierCount()
                + " confidence=" + result.getConfidence()
                + " rejection=" + (result.getRejectionEvidence() == null ? "none"
                : result.getRejectionEvidence().getReason()));
    }

    private static Mat decode(byte[] bytes, int mode) {
        MatOfByte encoded = new MatOfByte(bytes);
        try { return Imgcodecs.imdecode(encoded, mode); }
        finally { encoded.release(); }
    }

    private static byte[] read(AssetManager assets, String path) throws Exception {
        try (InputStream input = assets.open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            return output.toByteArray();
        }
    }

    private static File newTestDirectory(Context context) throws Exception {
        File directory = File.createTempFile("reviewed-photo-native-", ".test", context.getCacheDir());
        assertTrue(directory.delete());
        assertTrue(directory.mkdir());
        return directory;
    }

    private static void deleteTestDirectory(Context context, File directory) throws Exception {
        assertEquals("Only an immediate child of this app's cache may be removed",
                context.getCacheDir().getCanonicalPath(), directory.getParentFile().getCanonicalPath());
        File[] files = directory.listFiles();
        assertNotNull(files);
        for (File file : files) {
            assertTrue("Test cache must contain only regular files", file.isFile());
            assertTrue(file.delete());
        }
        assertTrue(directory.delete());
    }
}
