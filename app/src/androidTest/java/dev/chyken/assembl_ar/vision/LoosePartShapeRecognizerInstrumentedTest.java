package dev.chyken.assembl_ar.vision;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Physical-appearance regressions using only supplied captures of this exact kit. */
@RunWith(AndroidJUnit4.class)
public final class LoosePartShapeRecognizerInstrumentedTest {
    private static final String BASE = "references/individual-test/";
    private static final String HELD_OUT = "references/individual-test-held-out/";

    @BeforeClass public static void loadOpenCv() {
        assertTrue(OpenCVLoader.initLocal());
    }

    @Test public void allSuppliedBracketViewsProduceCornersAndCoarsePoseFromGray() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        for (Fixture fixture : Arrays.asList(
                new Fixture(app, BASE + "motor-bracket-20260906_131438.jpg"),
                new Fixture(app, BASE + "motor-bracket-20260906_131502.jpg"),
                new Fixture(app, BASE + "motor-bracket-20260906_131707.jpg"),
                new Fixture(tests, HELD_OUT + "motor-bracket-20260906_131648.jpg"))) {
            Mat rgb = decodeRgb(fixture.context, fixture.path);
            Mat gray = new Mat();
            try {
                Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
                RecognitionResult result = recognize(gray, null, NeobotSeedData.MOTOR_BRACKET);
                assertSpatialFallback(fixture.path, result, NeobotSeedData.MOTOR_BRACKET);
            } finally {
                gray.release();
                rgb.release();
            }
        }
    }

    @Test public void allSuppliedGearMotorViewsRequireYellowHousingAndMetalCan() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        for (Fixture fixture : Arrays.asList(
                new Fixture(app, BASE + "gear-motor-20260906_131326.jpg"),
                new Fixture(app, BASE + "gear-motor-20260906_131755.jpg"),
                new Fixture(app, BASE + "gear-motor-20260906_131759.jpg"),
                new Fixture(tests, HELD_OUT + "gear-motor-20260906_131803.jpg"))) {
            Mat rgb = decodeRgb(fixture.context, fixture.path);
            Mat gray = new Mat();
            try {
                Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
                RecognitionResult result = recognize(gray, rgb, NeobotSeedData.GEAR_MOTOR);
                assertSpatialFallback(fixture.path, result, NeobotSeedData.GEAR_MOTOR);
            } finally {
                gray.release();
                rgb.release();
            }
        }
    }

    @Test public void yellowRectangleWithoutMetalCanIsRejected() {
        Mat rgb = new Mat(720, 960, CvType.CV_8UC3, new Scalar(238, 238, 238));
        Mat gray = new Mat();
        try {
            Imgproc.rectangle(rgb, new Point(300, 210), new Point(650, 470),
                    new Scalar(245, 220, 15), -1);
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
            RecognitionResult primary = RecognitionResult.builder(41L).build();
            RecognitionResult result = new LoosePartShapeRecognizer().recognize(
                    gray, rgb, 41L, request(NeobotSeedData.GEAR_MOTOR, gray), primary);
            assertSame(primary, result);
        } finally {
            gray.release();
            rgb.release();
        }
    }

    @Test public void rectangularBlackPlateIsNotABracket() {
        Mat gray = new Mat(720, 960, CvType.CV_8UC1, Scalar.all(232));
        try {
            Imgproc.rectangle(gray, new Point(300, 160), new Point(650, 560),
                    Scalar.all(24), -1);
            Imgproc.rectangle(gray, new Point(375, 220), new Point(410, 420),
                    Scalar.all(232), -1);
            Imgproc.rectangle(gray, new Point(480, 270), new Point(515, 470),
                    Scalar.all(232), -1);
            Imgproc.rectangle(gray, new Point(570, 210), new Point(605, 410),
                    Scalar.all(232), -1);
            RecognitionResult primary = RecognitionResult.builder(42L).build();
            RecognitionResult result = new LoosePartShapeRecognizer().recognize(
                    gray, null, 42L, request(NeobotSeedData.MOTOR_BRACKET, gray), primary);
            assertSame(primary, result);
        } finally {
            gray.release();
        }
    }

    @Test public void validConflictingGeometryCannotBeOverwritten() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        Mat rgb = decodeRgb(app, BASE + "gear-motor-20260906_131759.jpg");
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
            List<VisionPoint> corners = Arrays.asList(
                    new VisionPoint(100, 100), new VisionPoint(300, 100),
                    new VisionPoint(300, 250), new VisionPoint(100, 250));
            RecognitionResult.Pose pose = new RecognitionResult.Pose(
                    new double[]{0, 0, 0}, new double[]{0, 0, 300},
                    new float[]{1, 0, 0, 0, 0, -1, 0, 0,
                            0, 0, -1, 0, 0, 0, -300, 1});
            RecognitionResult primary = RecognitionResult.builder(43L)
                    .identity("other-ref", NeobotSeedData.MAIN_BOARD, "Other component")
                    .method(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY)
                    .validation(RecognitionResult.ValidationState.COMPONENT_MISMATCH)
                    .corners(corners).pose(pose).build();
            RecognitionResult result = new LoosePartShapeRecognizer().recognize(
                    gray, rgb, 43L, request(NeobotSeedData.GEAR_MOTOR, gray), primary);
            assertSame(primary, result);
        } finally {
            gray.release();
            rgb.release();
        }
    }

    @Test public void allSuppliedMotorDriverViewsProduceCornersAndCoarsePose() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        for (Fixture fixture : Arrays.asList(
                new Fixture(app, BASE + "motor-driver-20260906_131356.jpg"),
                new Fixture(app, BASE + "motor-driver-20260906_131718.jpg"),
                new Fixture(app, "references/physical/motor-driver-top.jpg"),
                new Fixture(tests, HELD_OUT + "motor-driver-20260906_131741.jpg"))) {
            Mat rgb = decodeRgb(fixture.context, fixture.path);
            Mat gray = new Mat();
            try {
                Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
                RecognitionResult result = recognize(gray, rgb, NeobotSeedData.MOTOR_DRIVER);
                assertSpatialFallback(fixture.path, result, NeobotSeedData.MOTOR_DRIVER);
            } finally {
                gray.release();
                rgb.release();
            }
        }
    }

    @Test public void blueBoardWithoutGreenTerminalsIsRejectedAsMotorDriver() {
        Mat rgb = new Mat(720, 960, CvType.CV_8UC3, new Scalar(232, 232, 232));
        Mat gray = new Mat();
        try {
            Imgproc.rectangle(rgb, new Point(310, 180), new Point(640, 550),
                    new Scalar(18, 55, 145), -1);
            Imgproc.line(rgb, new Point(350, 230), new Point(590, 480),
                    Scalar.all(15), 8);
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
            RecognitionResult primary = RecognitionResult.builder(44L).build();
            RecognitionResult result = new LoosePartShapeRecognizer().recognize(
                    gray, rgb, 44L, request(NeobotSeedData.MOTOR_DRIVER, gray), primary);
            assertSame(primary, result);
        } finally {
            gray.release();
            rgb.release();
        }
    }

    @Test public void coloredMotorDriverCannotSatisfyBracketExpectation() throws Exception {
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        Mat rgb = decodeRgb(tests, HELD_OUT + "motor-driver-20260906_131741.jpg");
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
            RecognitionResult primary = RecognitionResult.builder(45L).build();
            RecognitionResult result = new LoosePartShapeRecognizer().recognize(
                    gray, rgb, 45L, request(NeobotSeedData.MOTOR_BRACKET, gray), primary);
            assertSame(primary, result);
        } finally {
            gray.release();
            rgb.release();
        }
    }

    @Test public void verifiedBoardPrimaryAllowsBracketAndDriverFromCurrentPixels() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        String[] ids = {NeobotSeedData.MOTOR_BRACKET, NeobotSeedData.MOTOR_DRIVER};
        String[] paths = {BASE + "motor-bracket-20260906_131502.jpg",
                BASE + "motor-driver-20260906_131718.jpg"};
        for (int index = 0; index < ids.length; index++) {
            Mat rgb = decodeRgb(app, paths[index]);
            Mat gray = new Mat();
            try {
                Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
                RecognitionResult primary = boardPrimary(frameQuad(gray));
                RecognitionResult result = new LoosePartShapeRecognizer().recognize(gray,
                        index == 0 ? null : rgb, 51L, assemblyRequest(ids[index], gray), primary);
                assertSpatialFallback(paths[index], result, ids[index]);
                assertSame("Keep independently recognized workplane", primary.getSpatialAnchor(),
                        result.getSpatialAnchor());
            } finally { gray.release(); rgb.release(); }
        }
    }

    @Test public void boardPrimaryDoesNotBypassTheCurrentBracketSlot() throws Exception {
        Mat rgb = decodeRgb(ApplicationProvider.getApplicationContext(),
                BASE + "motor-bracket-20260906_131502.jpg");
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
            RecognitionResult bracket = recognize(gray, null, NeobotSeedData.MOTOR_BRACKET);
            assertSpatialFallback("isolated bracket", bracket, NeobotSeedData.MOTOR_BRACKET);
            // Isolate fallback/slot policy from board ORB: place a supplied bracket's measured
            // footprint in slot 1 of a controlled board observation. This is not a mounted-kit photo.
            List<VisionPoint> board = new ArrayList<>();
            for (VisionPoint corner : Arrays.asList(new VisionPoint(0, 0), new VisionPoint(1, 0),
                    new VisionPoint(1, 1), new VisionPoint(0, 1))) {
                board.add(PlanarCoordinateMapper.projectUnitSquare(bracket.getProjectedCorners(),
                        new VisionPoint((corner.getX() - .03) / .14, (corner.getY() - .51) / .24)));
            }
            RecognitionResult primary = boardPrimary(board);
            RecognitionRequest request = assemblyRequest(NeobotSeedData.MOTOR_BRACKET, gray);
            ChassisRelativePlacementGate first = ChassisRelativePlacementGate.forMotorBracketSlot(1);
            ChassisRelativePlacementGate opposite = ChassisRelativePlacementGate.forMotorBracketSlot(4);
            RecognitionResult accepted = new LoosePartShapeRecognizer().recognize(gray, null, 51L,
                    request.withGuidedBracketSlot(1), primary,
                    corners -> first.evaluateSingle(board, corners).isAccepted());
            assertSpatialFallback("current slot", accepted, NeobotSeedData.MOTOR_BRACKET);
            assertSame("Another installed bracket cannot complete this slot", primary,
                    new LoosePartShapeRecognizer().recognize(gray, null, 51L,
                            request.withGuidedBracketSlot(4), primary,
                            corners -> opposite.evaluateSingle(board, corners).isAccepted()));
        } finally { gray.release(); rgb.release(); }
    }

    @Test public void bareBoardAndEmptyFrameDoNotCreateBracketEvidence() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        Mat rgb = decodeRgb(app,
                "references/assembly-captures/main-board-bare-20260906_115902.jpg");
        Mat gray = new Mat();
        VisionConfig config = VisionConfig.defaults();
        try (ReferenceDatabase database = ReferenceDatabase.load(app.getAssets(),
                VisionReferenceFactory.build(AssemblArRepository.getInstance(app)), config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
            RecognitionRequest bracketRequest = assemblyRequest(NeobotSeedData.MOTOR_BRACKET, gray);
            RecognitionResult primary = recognizer.recognize(gray, 51L, bracketRequest);
            assertEquals(NeobotSeedData.MAIN_BOARD, primary.getComponentId());
            assertNotNull("Component ORB already supplies workplane before shape fallback",
                    primary.getSpatialAnchor());
            assertTrue(LoosePartShapeRecognizer.shouldAttempt(bracketRequest, primary));
            List<VisionPoint> board = primary.getSpatialAnchor().getProjectedCorners();
            for (int slot = 1; slot <= 4; slot++) {
                ChassisRelativePlacementGate gate = ChassisRelativePlacementGate.forMotorBracketSlot(slot);
                RecognitionRequest request = assemblyRequest(NeobotSeedData.MOTOR_BRACKET, gray)
                        .withGuidedBracketSlot(slot);
                assertSame("Bare board slot " + slot, primary,
                        new LoosePartShapeRecognizer().recognize(gray, rgb, 51L, request, primary,
                                corners -> gate.evaluateSingle(board, corners).isAccepted()));
            }
            gray.setTo(Scalar.all(238));
            assertSame("Board pose alone is not component evidence", primary,
                    new LoosePartShapeRecognizer().recognize(gray, null, 51L,
                            assemblyRequest(NeobotSeedData.MOTOR_BRACKET, gray), primary));
        } finally { gray.release(); rgb.release(); }
    }

    @Test public void anchoredRequestPreservesAConflictingPartEvenWhenBracketPixelsExist() throws Exception {
        Mat rgb = decodeRgb(ApplicationProvider.getApplicationContext(),
                BASE + "motor-bracket-20260906_131502.jpg");
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
            RecognitionResult board = boardPrimary(frameQuad(gray));
            RecognitionResult primary = RecognitionResult.builder(51L)
                    .identity("different-part", NeobotSeedData.GEAR_MOTOR, "Gear motor")
                    .method(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY)
                    .validation(RecognitionResult.ValidationState.COMPONENT_MISMATCH)
                    .corners(board.getProjectedCorners()).pose(board.getPose())
                    .spatialAnchor(board.getSpatialAnchor()).build();
            RecognitionRequest request = assemblyRequest(NeobotSeedData.MOTOR_BRACKET, gray)
                    .withGuidedBracketSlot(1);
            assertSame(primary, new LoosePartShapeRecognizer().recognize(
                    gray, null, 51L, request, primary, corners -> true));
        } finally { gray.release(); rgb.release(); }
    }

    private static RecognitionRequest assemblyRequest(String id, Mat frame) {
        RecognitionRequest loose = request(id, frame);
        return new RecognitionRequest(loose.getExpectation(), loose.getCameraCalibration(), null,
                NeobotSeedData.MAIN_BOARD, 1, NeobotSeedData.MOTOR_DRIVER.equals(id) ? 3 : 2,
                loose.getMinimumCornerAreaRatioOverride());
    }

    private static List<VisionPoint> frameQuad(Mat frame) {
        return Arrays.asList(new VisionPoint(0, 0), new VisionPoint(frame.cols() - 1, 0),
                new VisionPoint(frame.cols() - 1, frame.rows() - 1),
                new VisionPoint(0, frame.rows() - 1));
    }

    private static RecognitionResult boardPrimary(List<VisionPoint> corners) {
        RecognitionResult.Pose pose = new RecognitionResult.Pose(
                new double[]{0, 0, 0}, new double[]{0, 0, 300},
                new float[]{1, 0, 0, 0, 0, -1, 0, 0, 0, 0, -1, 0, 0, 0, -300, 1});
        return RecognitionResult.builder(51L).identity("board-ref", NeobotSeedData.MAIN_BOARD, "Main board")
                .method(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY)
                .validation(RecognitionResult.ValidationState.COMPONENT_MISMATCH)
                .corners(corners).pose(pose)
                .spatialAnchor(new RecognitionResult.SpatialAnchor("board-ref",
                        NeobotSeedData.MAIN_BOARD, corners, pose)).build();
    }

    private static RecognitionResult recognize(Mat gray, Mat rgb, String componentId) {
        RecognitionResult primary = RecognitionResult.builder(40L)
                .method(RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY)
                .identity("diagnostic-orb", componentId, componentId)
                .validation(RecognitionResult.ValidationState.EXPECTED_COMPONENT)
                .build();
        return new LoosePartShapeRecognizer().recognize(
                gray, rgb, 40L, request(componentId, gray), primary);
    }

    private static RecognitionRequest request(String componentId, Mat frame) {
        double focal = Math.max(frame.cols(), frame.rows()) * 1.15;
        return new RecognitionRequest(
                RecognitionRequest.AssemblyExpectation.component(componentId),
                CameraCalibration.pinhole(focal, focal,
                        frame.cols() * 0.5, frame.rows() * 0.5),
                null, null, 1, 0, 0.001, true);
    }

    private static void assertSpatialFallback(String label, RecognitionResult result,
                                              String componentId) {
        assertEquals(label, componentId, result.getComponentId());
        assertEquals(label, RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK,
                result.getDetectionMethod());
        assertEquals(label, 4, result.getProjectedCorners().size());
        assertNotNull(label, result.getPose());
        assertTrue(label, result.getConfidence() >= 0.65);
        assertEquals(label, 1, result.getInstanceObservations().size());
    }

    private static Mat decodeRgb(Context context, String path) throws Exception {
        byte[] bytes;
        try (InputStream input = context.getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            bytes = output.toByteArray();
        }
        Mat encoded = new MatOfByte(bytes);
        try {
            Mat bgr = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
            Mat rgb = new Mat();
            try {
                Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
                return rgb;
            } finally {
                bgr.release();
            }
        } finally {
            encoded.release();
        }
    }

    private static final class Fixture {
        private final Context context;
        private final String path;

        private Fixture(Context context, String path) {
            this.context = context;
            this.path = path;
        }
    }
}
