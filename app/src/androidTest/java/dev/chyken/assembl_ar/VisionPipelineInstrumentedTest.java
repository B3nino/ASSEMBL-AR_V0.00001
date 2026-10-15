package dev.chyken.assembl_ar;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.AdminDescriptorBlobCodec;
import dev.chyken.assembl_ar.vision.CameraCalibration;
import dev.chyken.assembl_ar.vision.ComponentRecognizer;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.PlanarCoordinateMapper;
import dev.chyken.assembl_ar.vision.RecognitionRequest;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.ReferenceDatabase;
import dev.chyken.assembl_ar.vision.VisionConfig;
import dev.chyken.assembl_ar.vision.VisionPoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Exercises the actual bundled-image -> ORB -> homography -> validation path on Android. */
@RunWith(AndroidJUnit4.class)
public final class VisionPipelineInstrumentedTest {
    private static final String ASSET = "references/assembly/image-001.png";
    private static final String COMPONENT = "main_board_fixture";
    private static final String MOTOR_DRIVER_ASSET =
            "references/assembly/image-013.png";
    private static final String MAIN_BOARD_UNDERSIDE_ASSET =
            "references/assembly/image-034.png";

    private Context context;
    private VisionConfig config;
    private ReferenceDatabase database;
    private ComponentRecognizer recognizer;
    private Mat fixture;

    @Before
    public void setUp() throws Exception {
        assertTrue("The packaged OpenCV runtime must initialize", OpenCVLoader.initLocal());
        context = ApplicationProvider.getApplicationContext();
        config = VisionConfig.defaults();
        ComponentReferenceSpec spec = ComponentReferenceSpec.builder(
                        "fixture_front", COMPONENT, "Main board fixture", 150.0, 130.0)
                .jpegAssetPath(ASSET)
                // Match the production target ROI; a full-frame target exceeds the unchanged
                // 95% projected-area gate when this fixture is also used as the query image.
                .planarReferenceCornersNormalized(
                        new VisionPoint(0.05, 0.10), new VisionPoint(0.92, 0.10),
                        new VisionPoint(0.92, 0.94), new VisionPoint(0.05, 0.94))
                .build();
        ComponentReferenceSpec mismatchSpec = ComponentReferenceSpec.builder(
                        "different_front", "different_component", "Different component",
                        45.0, 35.0)
                .jpegAssetPath(MOTOR_DRIVER_ASSET)
                .planarReferenceCornersNormalized(
                        new VisionPoint(0.39, 0.26), new VisionPoint(0.61, 0.26),
                        new VisionPoint(0.61, 0.53), new VisionPoint(0.39, 0.53))
                .build();
        database = ReferenceDatabase.load(
                context.getAssets(), Arrays.asList(spec, mismatchSpec), config);
        recognizer = new ComponentRecognizer(config, database);
        fixture = decodeAsset(ASSET);
        assertFalse(fixture.empty());
    }

    @After
    public void tearDown() {
        if (fixture != null) fixture.release();
        if (recognizer != null) recognizer.close();
        if (database != null) database.close();
    }

    @Test
    public void bundledReferenceProducesOrbHomographyAndMismatchState() {
        RecognitionResult expected = recognizer.recognize(
                fixture, 1L, request(COMPONENT, null, null));
        assertEquals(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,
                expected.getDetectionMethod());
        assertEquals(RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                expected.getValidationState());
        assertTrue(expected.getInlierCount() >= config.getMinimumInliers());
        assertEquals(4, expected.getProjectedCorners().size());

        RecognitionResult mismatch = recognizer.recognize(
                fixture, 2L, request("different_component", null, null));
        assertEquals(RecognitionResult.ValidationState.COMPONENT_MISMATCH,
                mismatch.getValidationState());
    }

    @Test
    public void invertedBoardAppearanceRetainsIdentityAndStrictGeometry() {
        Mat inverted = new Mat();
        try {
            Core.bitwise_not(fixture, inverted);

            RecognitionResult result = recognizer.recognize(
                    inverted, 21L, request(COMPONENT, null, null));

            assertEquals(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,
                    result.getDetectionMethod());
            assertEquals(RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                    result.getValidationState());
            assertEquals("fixture_front", result.getReferenceId());
            assertTrue(result.getGoodMatchCount() >= config.getMinimumGoodMatches());
            assertTrue(result.getInlierCount() >= config.getMinimumInliers());
            assertTrue(result.getInlierRatio() >= config.getMinimumInlierRatio());
            assertEquals(4, result.getProjectedCorners().size());
        } finally {
            inverted.release();
        }
    }

    @Test
    public void claheRecoversAValidLowContrastBoardObservation() {
        Mat lowContrast = new Mat();
        try {
            fixture.convertTo(lowContrast, CvType.CV_8UC1, 0.38, 78.0);

            RecognitionResult result = recognizer.recognize(
                    lowContrast, 22L, request(COMPONENT, null, null));

            assertEquals(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,
                    result.getDetectionMethod());
            assertEquals(RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                    result.getValidationState());
            assertTrue(result.getInlierCount() >= config.getMinimumInliers());
            assertTrue(result.getInlierRatio() >= config.getMinimumInlierRatio());
        } finally {
            lowContrast.release();
        }
    }

    @Test
    public void invertedUnrelatedPartDoesNotBypassGeometryValidation() throws Exception {
        Mat unrelated = decodeAsset(MOTOR_DRIVER_ASSET);
        Mat inverted = new Mat();
        try {
            Core.bitwise_not(unrelated, inverted);

            RecognitionResult result = recognizer.recognize(
                    inverted, 23L, request(COMPONENT, null, null));

            assertFalse("Photometric fallback must not replace component geometry",
                    result.getValidationState()
                            == RecognitionResult.ValidationState.EXPECTED_COMPONENT);
        } finally {
            inverted.release();
            unrelated.release();
        }
    }

    @Test
    public void rotatedFixtureIsRejectedByOrientationValidation() {
        Mat rotated = new Mat();
        try {
            Core.rotate(fixture, rotated, Core.ROTATE_180);
            RecognitionResult result = recognizer.recognize(
                    rotated, 3L, request(COMPONENT, 0.0, null));
            assertEquals(RecognitionResult.ValidationState.WRONG_ORIENTATION,
                    result.getValidationState());
        } finally {
            rotated.release();
        }
    }

    @Test
    public void undersideIsRejectedForStepOneButRemainsAHandedSpatialAnchor()
            throws Exception {
        RecognitionResult top = recognizer.recognize(
                fixture, 31L, request(COMPONENT, 0.0, null));
        assertEquals(RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                top.getValidationState());
        assertEquals(RecognitionResult.OrientationClassification.UPRIGHT,
                top.getOrientationClassification());

        Mat underside = decodeAsset(MAIN_BOARD_UNDERSIDE_ASSET);
        // This is a synthetic pinhole fixture, not a measured board. Use its registered pixel
        // extents as world units so the test's assumed camera can exactly reproject the plane.
        ComponentReferenceSpec topSpec = ComponentReferenceSpec.builder(
                        "main_board_top", NeobotSeedData.MAIN_BOARD,
                        "Main board top", (fixture.cols() - 1.0) * (0.92 - 0.05),
                        (fixture.rows() - 1.0) * (0.94 - 0.10))
                .jpegAssetPath(ASSET)
                .planarReferenceCornersNormalized(
                        new VisionPoint(0.05, 0.10),
                        new VisionPoint(0.92, 0.10),
                        new VisionPoint(0.92, 0.94),
                        new VisionPoint(0.05, 0.94))
                .build();
        ComponentReferenceSpec undersideSpec = ComponentReferenceSpec.builder(
                        "main_board_underside", NeobotSeedData.MAIN_BOARD,
                        "Main board underside", (underside.cols() - 1.0) * (0.92 - 0.05),
                        (underside.rows() - 1.0) * (0.94 - 0.10))
                .jpegAssetPath(MAIN_BOARD_UNDERSIDE_ASSET)
                .mirroredPlanarReferenceCornersNormalized(
                        new VisionPoint(0.92, 0.10),
                        new VisionPoint(0.05, 0.10),
                        new VisionPoint(0.05, 0.94),
                        new VisionPoint(0.92, 0.94))
                .build();
        try (ReferenceDatabase undersideDatabase = ReferenceDatabase.load(
                context.getAssets(), Arrays.asList(topSpec, undersideSpec), config);
             ComponentRecognizer undersideRecognizer =
                     new ComponentRecognizer(config, undersideDatabase)) {
            double focal = Math.max(underside.cols(), underside.rows()) * 1.18;
            CameraCalibration calibration = CameraCalibration.pinhole(
                    focal, focal, underside.cols() / 2.0, underside.rows() / 2.0);
            RecognitionRequest request = new RecognitionRequest(
                    new RecognitionRequest.AssemblyExpectation(
                            NeobotSeedData.MAIN_BOARD, 0.0, 20.0, null),
                    calibration,
                    null,
                    NeobotSeedData.MAIN_BOARD);

            RecognitionResult result = undersideRecognizer.recognize(
                    underside, 32L, request);

            assertEquals(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,
                    result.getDetectionMethod());
            assertEquals("main_board_underside", result.getReferenceId());
            assertEquals(RecognitionResult.ValidationState.WRONG_ORIENTATION,
                    result.getValidationState());
            assertEquals(RecognitionResult.OrientationClassification.MIRRORED,
                    result.getOrientationClassification());
            assertNotNull("The same underside observation must remain usable as an anchor",
                    result.getSpatialAnchor());
            assertEquals("main_board_underside",
                    result.getSpatialAnchor().getReferenceId());
            assertNotNull(result.getSpatialAnchor().getPose());

            List<VisionPoint> anchorCorners = result.getSpatialAnchor().getProjectedCorners();
            VisionPoint canonicalLeft = PlanarCoordinateMapper.projectUnitSquare(
                    anchorCorners, new VisionPoint(0.20, 0.50));
            VisionPoint canonicalRight = PlanarCoordinateMapper.projectUnitSquare(
                    anchorCorners, new VisionPoint(0.80, 0.50));
            assertNotNull(canonicalLeft);
            assertNotNull(canonicalRight);
            assertTrue("Back-face anchor targets must keep the front-face left/right frame",
                    canonicalLeft.getX() > canonicalRight.getX());
        } finally {
            underside.release();
        }
    }

    @Test
    public void authoredPolarityAnchorsDistinguishCorrectAndReversedEndpoints() {
        RecognitionRequest.PolarityRule rule = new RecognitionRequest.PolarityRule(
                new VisionPoint(0.30, 0.55), new VisionPoint(0.70, 0.55), 0.20, 0.04);
        RecognitionResult first = recognizer.recognize(
                fixture, 4L, request(COMPONENT, null, rule));
        assertEquals(RecognitionResult.ValidationState.POLARITY_UNCONFIRMED,
                first.getValidationState());
        List<VisionPoint> corners = first.getProjectedCorners();
        VisionPoint positive = bilinear(corners, 0.30, 0.55);
        VisionPoint negative = bilinear(corners, 0.70, 0.55);

        RecognitionResult correct = recognizer.recognize(fixture, 5L,
                request(COMPONENT, null, rule,
                        new RecognitionRequest.PolarityObservation(positive, negative)));
        assertEquals(RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                correct.getValidationState());
        assertEquals(RecognitionResult.PolarityState.CORRECT, correct.getPolarityState());

        RecognitionResult reversed = recognizer.recognize(fixture, 6L,
                request(COMPONENT, null, rule,
                        new RecognitionRequest.PolarityObservation(negative, positive)));
        assertEquals(RecognitionResult.ValidationState.POLARITY_ERROR,
                reversed.getValidationState());
        assertEquals(RecognitionResult.PolarityState.REVERSED, reversed.getPolarityState());

        RecognitionResult ambiguous = recognizer.recognize(fixture, 7L,
                request(COMPONENT, null, rule,
                        new RecognitionRequest.PolarityObservation(
                                bilinear(corners, 0.495, 0.55),
                                bilinear(corners, 0.505, 0.55))));
        assertEquals("Nearby colors that fit both assignments cannot validate wiring",
                RecognitionResult.PolarityState.UNCONFIRMED, ambiguous.getPolarityState());
        assertEquals(RecognitionResult.ValidationState.POLARITY_UNCONFIRMED,
                ambiguous.getValidationState());
    }

    @Test
    public void administratorDescriptorBlobReloadsWithTheSameVisionConfig() throws Exception {
        byte[] blob = AdminDescriptorBlobCodec.computeAndEncode(fixture, config);
        ComponentReferenceSpec descriptorSpec = ComponentReferenceSpec.builder(
                        "descriptor_fixture", COMPONENT, "Descriptor fixture", 150.0, 130.0)
                .adminDescriptorBlob(blob)
                .planarReferenceCornersNormalized(
                        new VisionPoint(0.05, 0.10), new VisionPoint(0.92, 0.10),
                        new VisionPoint(0.92, 0.94), new VisionPoint(0.05, 0.94))
                .build();
        try (ReferenceDatabase descriptorDatabase = ReferenceDatabase.load(
                context.getAssets(), Collections.singletonList(descriptorSpec), config);
             ComponentRecognizer descriptorRecognizer =
                     new ComponentRecognizer(config, descriptorDatabase)) {
            RecognitionResult result = descriptorRecognizer.recognize(
                    fixture, 7L, request(COMPONENT, null, null));
            assertEquals(RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                    result.getValidationState());
        }
    }

    @Test
    public void expectedComponentRemainsPrimaryWhileChassisAnchorIsReturned() throws Exception {
        checkSimultaneousPartAndAnchor(false);
    }

    @Test
    public void validPartCannotResolveAmbiguousChassisIdentity() throws Exception {
        checkSimultaneousPartAndAnchor(true);
    }

    private void checkSimultaneousPartAndAnchor(boolean ambiguousAnchor) throws Exception {
        Mat motorDriverSource = decodeAsset(MOTOR_DRIVER_ASSET);
        Mat motorDriverCrop = null;
        Mat enlargedMotorDriver = new Mat();
        Mat simultaneousFrame = null;
        try {
            int left = (int) Math.floor(motorDriverSource.cols() * 0.39);
            int right = (int) Math.ceil(motorDriverSource.cols() * 0.61);
            int top = (int) Math.floor(motorDriverSource.rows() * 0.26);
            int bottom = (int) Math.ceil(motorDriverSource.rows() * 0.53);
            motorDriverCrop = motorDriverSource.submat(top, bottom, left, right);
            Imgproc.resize(motorDriverCrop, enlargedMotorDriver,
                    new org.opencv.core.Size(
                            motorDriverCrop.cols() * 2.0,
                            motorDriverCrop.rows() * 2.0));
            simultaneousFrame = sideBySide(fixture, enlargedMotorDriver, 40);

            // Encode the isolated part as an administrator-style reference so the frame contains
            // an exact loose-component observation alongside the independently bundled chassis.
            ComponentReferenceSpec expectedSpec = ComponentReferenceSpec.builder(
                            "motor_driver_front", NeobotSeedData.MOTOR_DRIVER,
                            "Motor driver fixture", 45.0, 35.0)
                    .adminDescriptorBlob(
                            AdminDescriptorBlobCodec.computeAndEncode(enlargedMotorDriver, config))
                    .build();
            ComponentReferenceSpec anchorSpec = ComponentReferenceSpec.builder(
                            "main_board_anchor", NeobotSeedData.MAIN_BOARD,
                            "Main board spatial anchor",
                            (fixture.cols() - 1.0) * (0.92 - 0.05),
                            (fixture.rows() - 1.0) * (0.94 - 0.10))
                    .jpegAssetPath(ASSET)
                    .planarReferenceCornersNormalized(
                            new VisionPoint(0.05, 0.10),
                            new VisionPoint(0.92, 0.10),
                            new VisionPoint(0.92, 0.94),
                            new VisionPoint(0.05, 0.94))
                    .build();

            ComponentReferenceSpec competingAnchor = ComponentReferenceSpec.builder(
                            "other_board_anchor", "other_board", "Indistinguishable board",
                            anchorSpec.getPhysicalWidthMm(), anchorSpec.getPhysicalHeightMm())
                    .jpegAssetPath(ASSET)
                    .planarReferenceCornersNormalized(
                            new VisionPoint(0.05, 0.10), new VisionPoint(0.92, 0.10),
                            new VisionPoint(0.92, 0.94), new VisionPoint(0.05, 0.94))
                    .build();
            List<ComponentReferenceSpec> references = ambiguousAnchor
                    ? Arrays.asList(expectedSpec, anchorSpec, competingAnchor)
                    : Arrays.asList(expectedSpec, anchorSpec);
            try (ReferenceDatabase simultaneousDatabase = ReferenceDatabase.load(
                    context.getAssets(), references, config);
                 ComponentRecognizer simultaneousRecognizer =
                         new ComponentRecognizer(config, simultaneousDatabase)) {
                double focal = Math.max(
                        simultaneousFrame.cols(), simultaneousFrame.rows()) * 1.18;
                CameraCalibration calibration = CameraCalibration.pinhole(
                        focal, focal,
                        simultaneousFrame.cols() / 2.0,
                        simultaneousFrame.rows() / 2.0);
                RecognitionRequest request = new RecognitionRequest(
                        RecognitionRequest.AssemblyExpectation.component(
                                NeobotSeedData.MOTOR_DRIVER),
                        calibration,
                        null,
                        NeobotSeedData.MAIN_BOARD);

                RecognitionResult result = simultaneousRecognizer.recognize(
                        simultaneousFrame, 8L, request);

                assertEquals(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,
                        result.getDetectionMethod());
                assertEquals(RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                        result.getValidationState());
                assertEquals("The expected part must outrank the larger chassis observation",
                        NeobotSeedData.MOTOR_DRIVER, result.getComponentId());
                if (ambiguousAnchor) {
                    assertNull("Matching parts do not disambiguate an unrelated chassis",
                            result.getSpatialAnchor());
                    return;
                }
                assertNotNull("The simultaneously visible chassis anchor must be returned",
                        result.getSpatialAnchor());
                assertEquals(NeobotSeedData.MAIN_BOARD,
                        result.getSpatialAnchor().getComponentId());
                assertEquals(4, result.getSpatialAnchor().getProjectedCorners().size());
                assertNotNull(result.getSpatialAnchor().getPose());
            }
        } finally {
            if (simultaneousFrame != null) simultaneousFrame.release();
            enlargedMotorDriver.release();
            if (motorDriverCrop != null) motorDriverCrop.release();
            motorDriverSource.release();
        }
    }

    @Test
    public void repeatedExpectedPartsRequireTwoDistinctObservationsFromTheSameFrame() {
        Mat scaledFixture = new Mat();
        Mat simultaneousFrame = null;
        try {
            Imgproc.resize(fixture, scaledFixture,
                    new org.opencv.core.Size(
                            Math.round(fixture.cols() * 0.82),
                            Math.round(fixture.rows() * 0.82)),
                    0.0, 0.0, Imgproc.INTER_AREA);
            simultaneousFrame = sideBySide(fixture, scaledFixture, 60);
            RecognitionRequest dualRequest = new RecognitionRequest(
                    RecognitionRequest.AssemblyExpectation.component(COMPONENT),
                    null,
                    null,
                    null,
                    2);

            RecognitionResult bothVisible = recognizer.recognize(
                    simultaneousFrame, 9L, dualRequest);
            RecognitionResult oneVisible = recognizer.recognize(
                    fixture, 10L, dualRequest);

            assertEquals(RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                    bothVisible.getValidationState());
            assertEquals(2, bothVisible.getInstanceObservations().size());
            assertTrue(center(bothVisible.getInstanceObservations().get(0))
                    .distanceTo(center(bothVisible.getInstanceObservations().get(1)))
                    > fixture.cols() * 0.50);
            assertEquals("A later frame cannot retain the earlier instance",
                    1, oneVisible.getInstanceObservations().size());
        } finally {
            if (simultaneousFrame != null) simultaneousFrame.release();
            scaledFixture.release();
        }
    }

    private RecognitionRequest request(String componentId, Double orientation,
                                       RecognitionRequest.PolarityRule polarityRule) {
        return request(componentId, orientation, polarityRule, null);
    }

    private RecognitionRequest request(
            String componentId,
            Double orientation,
            RecognitionRequest.PolarityRule polarityRule,
            RecognitionRequest.PolarityObservation observation) {
        RecognitionRequest.AssemblyExpectation expectation =
                new RecognitionRequest.AssemblyExpectation(
                        componentId, orientation, orientation == null ? null : 20.0, polarityRule);
        double focal = Math.max(fixture.cols(), fixture.rows()) * 1.18;
        CameraCalibration calibration = CameraCalibration.pinhole(
                focal, focal, fixture.cols() / 2.0, fixture.rows() / 2.0);
        return new RecognitionRequest(expectation, calibration, observation);
    }

    private Mat decodeAsset(String path) throws IOException {
        byte[] bytes;
        try (InputStream input = context.getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        MatOfByte encoded = new MatOfByte();
        try {
            encoded.fromArray(bytes);
            return Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE);
        } finally {
            encoded.release();
        }
    }

    private static Mat sideBySide(Mat left, Mat right, int padding) {
        int height = Math.max(left.rows(), right.rows()) + padding * 2;
        int width = left.cols() + right.cols() + padding * 3;
        Mat result = Mat.zeros(height, width, CvType.CV_8UC1);
        int leftTop = padding + (height - padding * 2 - left.rows()) / 2;
        int rightTop = padding + (height - padding * 2 - right.rows()) / 2;
        int rightLeft = padding * 2 + left.cols();
        Mat leftRegion = result.submat(
                leftTop, leftTop + left.rows(), padding, padding + left.cols());
        Mat rightRegion = result.submat(
                rightTop, rightTop + right.rows(), rightLeft, rightLeft + right.cols());
        try {
            left.copyTo(leftRegion);
            right.copyTo(rightRegion);
        } finally {
            rightRegion.release();
            leftRegion.release();
        }
        return result;
    }

    private static VisionPoint bilinear(List<VisionPoint> corners, double x, double y) {
        double topX = corners.get(0).getX() * (1.0 - x) + corners.get(1).getX() * x;
        double topY = corners.get(0).getY() * (1.0 - x) + corners.get(1).getY() * x;
        double bottomX = corners.get(3).getX() * (1.0 - x) + corners.get(2).getX() * x;
        double bottomY = corners.get(3).getY() * (1.0 - x) + corners.get(2).getY() * x;
        return new VisionPoint(
                topX * (1.0 - y) + bottomX * y,
                topY * (1.0 - y) + bottomY * y);
    }

    private static VisionPoint center(RecognitionResult.InstanceObservation observation) {
        double x = 0.0;
        double y = 0.0;
        for (VisionPoint corner : observation.getProjectedCorners()) {
            x += corner.getX();
            y += corner.getY();
        }
        return new VisionPoint(x / 4.0, y / 4.0);
    }
}
