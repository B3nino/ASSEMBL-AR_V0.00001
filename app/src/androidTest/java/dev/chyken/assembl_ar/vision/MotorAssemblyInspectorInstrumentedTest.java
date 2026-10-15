package dev.chyken.assembl_ar.vision;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;

/** Field-photo regressions for visible installation evidence, not a claim of general recognition accuracy. */
@RunWith(AndroidJUnit4.class)
public final class MotorAssemblyInspectorInstrumentedTest {
    private static final String FIELD_CAPTURE = "references/assembly-state/white-board-two-motors-field.jpg";
    private static final List<VisionPoint> FIELD_BOARD = Arrays.asList(
            new VisionPoint(197, 479), new VisionPoint(887, 441),
            new VisionPoint(938, 1297), new VisionPoint(248, 1336));
    private static final List<VisionPoint> CANONICAL_BOARD = Arrays.asList(
            new VisionPoint(0, 0), new VisionPoint(383, 0),
            new VisionPoint(383, 455), new VisionPoint(0, 455));

    @BeforeClass public static void loadOpenCv() { assertTrue(OpenCVLoader.initLocal()); }

    @Test public void registeredRawInstalledPhotosFindTheBoardAndBothMotorsAcrossAssemblySteps()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        VisionConfig config = VisionConfig.defaults();
        // These supplied raw views are now explicitly registered training angles. This checks
        // registration and the real motor inspector; it does not measure held-out accuracy.
        try (ReferenceDatabase database = ReferenceDatabase.load(context.getAssets(),
                VisionReferenceFactory.build(AssemblArRepository.getInstance(context)), config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            MotorAssemblyInspector inspector = new MotorAssemblyInspector();
            for (String name : new String[]{
                    "20260906_124307.jpg", "20260906_124312.jpg", "20260906_124316.jpg"}) {
                Mat source = decodeRgb(testContext, "references/assembly-state/" + name);
                Mat camera = new Mat();
                Mat gray = new Mat();
                try {
                    // Analyze the entire camera photograph, retaining its background and aspect
                    // ratio. A tight crop or hand-authored board corners would hide detector failures.
                    double scale = 480.0 / source.cols();
                    Imgproc.resize(source, camera,
                            new Size(480, Math.round(source.rows() * scale)),
                            0, 0, Imgproc.INTER_AREA);
                    Imgproc.cvtColor(camera, gray, Imgproc.COLOR_RGB2GRAY);
                    double focalLength = 1.2 * Math.max(gray.cols(), gray.rows());
                    CameraCalibration calibration = CameraCalibration.pinhole(
                            focalLength, focalLength, gray.cols() / 2.0, gray.rows() / 2.0);
                    for (int step : new int[]{1, 2, 3, 14, 18}) {
                        String label = name + " step " + step;
                        RecognitionResult found = recognizer.recognizeBoard(gray, step,
                                calibration, step);
                        assertEquals(label, NeobotSeedData.MAIN_BOARD, found.getComponentId());
                        assertNotNull(label + ": board pose required; matches="
                                        + found.getGoodMatchCount() + " inliers=" + found.getInlierCount(),
                                found.getSpatialAnchor());
                        assertEquals(label, NeobotSeedData.MAIN_BOARD,
                                found.getSpatialAnchor().getComponentId());
                        assertFalse(label + ": fresh photo must establish board identity",
                                found.isAnchorTracked());
                        MotorAssemblyObservation installed = inspector.inspect(camera,
                                found.getSpatialAnchor().getProjectedCorners());
                        assertEquals(label, MotorAssemblyObservation.Quality.GOOD,
                                installed.getQuality());
                        assertTrue(label + ": " + describe(installed.getLeft()),
                                installed.getLeft().isAccepted());
                        assertTrue(label + ": " + describe(installed.getRight()),
                                installed.getRight().isAccepted());
                    }
                } finally {
                    gray.release();
                    camera.release();
                    source.release();
                }
            }
        }
    }

    @Test public void overlaidScreenshotsCannotPassInstallationWithoutVerifiedBoardGeometry() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        VisionConfig config = VisionConfig.defaults();
        try (ReferenceDatabase database = ReferenceDatabase.load(context.getAssets(),
                VisionReferenceFactory.build(AssemblArRepository.getInstance(context)), config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            for (String capture : new String[]{"field", "motion", "oblique"}) {
                Mat rgb = decodeRgb(InstrumentationRegistry.getInstrumentation().getContext(),
                        "references/assembly-state/white-board-two-motors-" + capture + ".jpg");
                // Preserve the screenshot's camera viewport/aspect instead of magnifying a
                // tight board crop. Intrinsics are approximate: source camera metadata is absent.
                Mat region = rgb.submat(new Rect(0, 270, 1080, 1950));
                Mat camera = new Mat();
                Mat gray = new Mat();
                try {
                    Imgproc.resize(region, camera, new Size(480, 867), 0, 0, Imgproc.INTER_AREA);
                    Imgproc.cvtColor(camera, gray, Imgproc.COLOR_RGB2GRAY);
                    CameraCalibration calibration = CameraCalibration.pinhole(1023, 1023, 240, 433.5);
                    RecognitionResult baseline = null;
                    for (int step : new int[]{1, 2, 3, 14, 18}) {
                        String label = capture + " step " + step;
                        // Exercise the actual shared analyzer entry point. The legacy Step-2
                        // request alone could pass using a template excluded from that path.
                        RecognitionResult found = recognizer.recognizeBoard(gray, step,
                                calibration, step);
                        // These screenshots contain drawn AR lines and previously also trained
                        // the detector. After excluding them from training, missing evidence is
                        // a valid outcome; raw, unoverlaid held-out photos test positive coverage.
                        if (found.getComponentId() == null) {
                            assertEquals(label, RecognitionResult.ValidationState.NO_COMPONENT,
                                    found.getValidationState());
                            assertTrue(label, found.getSpatialAnchor() == null);
                            MotorAssemblyObservation unavailable = new MotorAssemblyInspector()
                                    .inspect(camera, Collections.emptyList());
                            assertFalse(label, unavailable.getLeft().isAccepted());
                            assertFalse(label, unavailable.getRight().isAccepted());
                            continue;
                        }
                        assertEquals(label, NeobotSeedData.MAIN_BOARD, found.getComponentId());
                        assertNotNull(label + ": real board pose required; matches="
                                + found.getGoodMatchCount() + " inliers=" + found.getInlierCount(),
                                found.getSpatialAnchor());
                        if (baseline == null) {
                            baseline = found;
                        } else {
                            assertEquals(label + ": reference must not change with the step",
                                    baseline.getReferenceId(), found.getReferenceId());
                            assertEquals(label + ": frame features must not change with the step",
                                    baseline.getFrameKeypointCount(), found.getFrameKeypointCount());
                            assertEquals(label + ": good matches must not change with the step",
                                    baseline.getGoodMatchCount(), found.getGoodMatchCount());
                            assertEquals(label + ": inliers must not change with the step",
                                    baseline.getInlierCount(), found.getInlierCount());
                            assertEquals(label + ": confidence must not change with the step",
                                    baseline.getConfidence(), found.getConfidence(), 1e-9);
                        }
                        MotorAssemblyObservation result = new MotorAssemblyInspector().inspect(camera,
                                found.getSpatialAnchor().getProjectedCorners());
                        if (!capture.equals("oblique")) {
                            assertEquals(label, MotorAssemblyObservation.Quality.GOOD, result.getQuality());
                            assertTrue(label + ": " + describe(result.getLeft()), result.getLeft().isAccepted());
                            assertTrue(label + ": " + describe(result.getRight()), result.getRight().isAccepted());
                        } else {
                            // This supplied view clips part of the board: no false installation pass.
                            assertFalse(result.getLeft().isAccepted() && result.getRight().isAccepted());
                        }
                    }
                } finally { gray.release(); camera.release(); region.release(); rgb.release(); }
            }
            Mat otherBoard = decodeRgb(context, "references/physical/extension-board-white-top.jpg");
            Mat camera = new Mat();
            Mat gray = new Mat();
            try {
                double scale = 480.0 / Math.max(otherBoard.rows(), otherBoard.cols());
                Imgproc.resize(otherBoard, camera, new Size(Math.round(otherBoard.cols() * scale),
                        Math.round(otherBoard.rows() * scale)), 0, 0, Imgproc.INTER_AREA);
                Imgproc.cvtColor(camera, gray, Imgproc.COLOR_RGB2GRAY);
                RecognitionResult found = recognizer.recognize(gray, 2L, new RecognitionRequest(
                        RecognitionRequest.AssemblyExpectation.component(NeobotSeedData.MAIN_BOARD),
                        CameraCalibration.pinhole(600, 600, gray.cols() / 2.0, gray.rows() / 2.0),
                        null, NeobotSeedData.MAIN_BOARD, 1, 2));
                assertFalse("The extension board must not become the main-board anchor",
                        NeobotSeedData.MAIN_BOARD.equals(found.getComponentId()));
                assertTrue(found.getSpatialAnchor() == null
                        || !NeobotSeedData.MAIN_BOARD.equals(found.getSpatialAnchor().getComponentId()));
            } finally { gray.release(); camera.release(); otherBoard.release(); }
        }
    }

    @Test public void suppliedInstalledCaptureFindsBothMotorsInSeparateSlots() throws Exception {
        Mat rgb = fieldCapture();
        try {
            MotorAssemblyObservation result = new MotorAssemblyInspector().inspect(rgb, FIELD_BOARD);
            assertEquals(MotorAssemblyObservation.Quality.GOOD, result.getQuality());
            assertTrue(describe(result.getLeft()), result.getLeft().isAccepted());
            assertTrue(describe(result.getRight()), result.getRight().isAccepted());
            assertEquals(4, result.getLeft().getProjectedCorners().size());
            assertEquals(4, result.getRight().getProjectedCorners().size());
            assertTrue(result.getLeft().getProjectedCorners().get(0).getX()
                    < result.getRight().getProjectedCorners().get(0).getX());
        } finally { rgb.release(); }
    }

    @Test public void sameCaptureAtCameraAnalysisScaleStillFindsBothMotors() throws Exception {
        Mat source = fieldCapture();
        Rect crop = new Rect(100, 410, 900, 1040);
        Mat region = source.submat(crop);
        Mat camera = new Mat();
        double scale = 480.0 / crop.width;
        List<VisionPoint> corners = new java.util.ArrayList<>();
        for (VisionPoint point : FIELD_BOARD) corners.add(new VisionPoint(
                (point.getX() - crop.x) * scale, (point.getY() - crop.y) * scale));
        try {
            Imgproc.resize(region, camera, new Size(480, Math.round(crop.height * scale)),
                    0, 0, Imgproc.INTER_AREA);
            MotorAssemblyObservation result = new MotorAssemblyInspector().inspect(camera, corners);
            assertEquals(MotorAssemblyObservation.Quality.GOOD, result.getQuality());
            assertTrue(describe(result.getLeft()), result.getLeft().isAccepted());
            assertTrue(describe(result.getRight()), result.getRight().isAccepted());
        } finally { camera.release(); region.release(); source.release(); }
    }

    @Test public void barePhysicalWhiteBoardCannotCountAsInstalledMotors() throws Exception {
        Mat board = decodeRgb(ApplicationProvider.getApplicationContext(),
                "references/variants/main-board-white-top.jpg");
        try {
            MotorAssemblyObservation result = new MotorAssemblyInspector().inspect(board,
                    Arrays.asList(new VisionPoint(board.cols() * 0.03, board.rows() * 0.03),
                            new VisionPoint(board.cols() * 0.97, board.rows() * 0.03),
                            new VisionPoint(board.cols() * 0.97, board.rows() * 0.97),
                            new VisionPoint(board.cols() * 0.03, board.rows() * 0.97)));
            assertFalse(result.getLeft().isAccepted());
            assertFalse(result.getRight().isAccepted());
        } finally { board.release(); }
    }

    @Test public void aVisibleLeftMotorCannotAlsoSatisfyTheRightSlot() throws Exception {
        Mat board = rectifiedFieldCapture();
        Mat erased = board.submat(new Rect(250, 0, 134, 456));
        try {
            erased.setTo(new Scalar(220, 220, 220));
            MotorAssemblyObservation result = new MotorAssemblyInspector().inspect(board, CANONICAL_BOARD);
            assertEquals(MotorAssemblyObservation.Quality.GOOD, result.getQuality());
            assertTrue(describe(result.getLeft()), result.getLeft().isAccepted());
            assertEquals(MotorAssemblyObservation.SlotState.MISSING, result.getRight().getState());
        } finally { erased.release(); board.release(); }
    }

    @Test public void yellowBlocksWithCorrectFootprintsAreInsufficientWithoutMetalCans() throws Exception {
        Mat board = rectifiedFieldCapture();
        try {
            // Preserve real central PCB detail so a negative cannot pass merely by failing focus.
            Imgproc.rectangle(board, new Point(0, 0), new Point(132, 455), new Scalar(230, 230, 230), -1);
            Imgproc.rectangle(board, new Point(258, 0), new Point(383, 455), new Scalar(230, 230, 230), -1);
            Imgproc.rectangle(board, new Point(10, 235), new Point(112, 438), new Scalar(245, 220, 15), -1);
            Imgproc.rectangle(board, new Point(275, 182), new Point(379, 390), new Scalar(245, 220, 15), -1);
            MotorAssemblyObservation result = new MotorAssemblyInspector().inspect(board, CANONICAL_BOARD);
            assertEquals(MotorAssemblyObservation.Quality.GOOD, result.getQuality());
            assertEquals(MotorAssemblyObservation.SlotState.ORIENTATION_UNCONFIRMED, result.getLeft().getState());
            assertEquals(MotorAssemblyObservation.SlotState.ORIENTATION_UNCONFIRMED, result.getRight().getState());
        } finally { board.release(); }
    }

    @Test public void motorMovedToTheCenterDoesNotPassTheLeftMountingLocation() throws Exception {
        Mat board = rectifiedFieldCapture();
        Mat originalMotor = board.submat(new Rect(0, 120, 128, 330));
        Mat motorCopy = originalMotor.clone();
        originalMotor.release();
        Mat destination = board.submat(new Rect(119, 120, 128, 330));
        try {
            Imgproc.rectangle(board, new Point(0, 0), new Point(132, 455), new Scalar(220, 220, 220), -1);
            motorCopy.copyTo(destination);
            MotorAssemblyObservation result = new MotorAssemblyInspector().inspect(board, CANONICAL_BOARD);
            assertEquals(MotorAssemblyObservation.Quality.GOOD, result.getQuality());
            assertEquals(MotorAssemblyObservation.SlotState.MISALIGNED, result.getLeft().getState());
        } finally { destination.release(); motorCopy.release(); board.release(); }
    }

    @Test public void severeBlurIsIgnoredInsteadOfReportingAWrongPart() throws Exception {
        Mat board = rectifiedFieldCapture();
        try {
            Imgproc.GaussianBlur(board, board, new Size(0, 0), 4.0);
            MotorAssemblyObservation result = new MotorAssemblyInspector().inspect(board, CANONICAL_BOARD);
            assertEquals(MotorAssemblyObservation.Quality.BLURRED, result.getQuality());
            assertFalse(result.getLeft().isAccepted());
            assertFalse(result.getRight().isAccepted());
        } finally { board.release(); }
    }

    @Test public void exposureAndAnchorFailuresDoNotCreatePositiveEvidence() {
        Mat black = new Mat(456, 384, CvType.CV_8UC3, Scalar.all(0));
        Mat white = new Mat(456, 384, CvType.CV_8UC3, Scalar.all(255));
        MotorAssemblyInspector inspector = new MotorAssemblyInspector();
        try {
            assertEquals(MotorAssemblyObservation.Quality.TOO_DARK,
                    inspector.inspect(black, CANONICAL_BOARD).getQuality());
            assertEquals(MotorAssemblyObservation.Quality.GLARE,
                    inspector.inspect(white, CANONICAL_BOARD).getQuality());
            assertEquals(MotorAssemblyObservation.Quality.ANCHOR_UNAVAILABLE,
                    inspector.inspect(white, Collections.emptyList()).getQuality());
            assertEquals(MotorAssemblyObservation.Quality.ANCHOR_UNAVAILABLE,
                    inspector.inspect(white, Arrays.asList(CANONICAL_BOARD.get(1), CANONICAL_BOARD.get(0),
                            CANONICAL_BOARD.get(3), CANONICAL_BOARD.get(2))).getQuality());
            assertEquals(MotorAssemblyObservation.Quality.VIEW_TOO_OBLIQUE,
                    inspector.inspect(white, Arrays.asList(new VisionPoint(120, 0),
                            new VisionPoint(245, 0), new VisionPoint(245, 455),
                            new VisionPoint(120, 455))).getQuality());
        } finally { white.release(); black.release(); }
    }

    private static String describe(MotorAssemblyObservation.Slot slot) {
        return slot.getState() + ": " + slot.getMessage() + " (" + slot.getConfidence() + ")";
    }

    private static Mat fieldCapture() throws Exception {
        return decodeRgb(InstrumentationRegistry.getInstrumentation().getContext(), FIELD_CAPTURE);
    }

    private static Mat rectifiedFieldCapture() throws Exception {
        Mat field = fieldCapture();
        MatOfPoint2f source = new MatOfPoint2f(new Point(197, 479), new Point(887, 441),
                new Point(938, 1297), new Point(248, 1336));
        MatOfPoint2f target = new MatOfPoint2f(new Point(0, 0), new Point(383, 0),
                new Point(383, 455), new Point(0, 455));
        Mat transform = Imgproc.getPerspectiveTransform(source, target);
        Mat board = new Mat();
        try {
            Imgproc.warpPerspective(field, board, transform, new Size(384, 456));
            return board;
        } finally { transform.release(); target.release(); source.release(); field.release(); }
    }

    private static Mat decodeRgb(Context context, String assetPath) throws Exception {
        byte[] bytes;
        try (InputStream stream = context.getAssets().open(assetPath);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) != -1) buffer.write(chunk, 0, count);
            bytes = buffer.toByteArray();
        }
        MatOfByte encoded = new MatOfByte(bytes);
        Mat bgr = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
        Mat rgb = new Mat();
        try {
            assertFalse("Could not decode " + assetPath, bgr.empty());
            Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
            return rgb;
        } finally { bgr.release(); encoded.release(); }
    }
}
