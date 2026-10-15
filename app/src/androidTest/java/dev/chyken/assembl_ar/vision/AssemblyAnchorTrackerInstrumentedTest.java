package dev.chyken.assembl_ar.vision;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Device coverage for the native Shi-Tomasi/LK/RANSAC anchor bridge. */
@RunWith(AndroidJUnit4.class)
public final class AssemblyAnchorTrackerInstrumentedTest {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final long START_NANOS = 2_000_000_000L;
    private static final List<VisionPoint> INITIAL_CORNERS = Arrays.asList(
            new VisionPoint(180.0, 70.0),
            new VisionPoint(460.0, 70.0),
            new VisionPoint(460.0, 400.0),
            new VisionPoint(180.0, 400.0));

    private AssemblyAnchorTracker tracker;
    private CameraCalibration calibration;
    private Mat board;

    @Before
    public void setUp() {
        assertTrue("The packaged OpenCV runtime must initialize", OpenCVLoader.initLocal());
        tracker = new AssemblyAnchorTracker();
        calibration = CameraCalibration.pinhole(720.0, 720.0, WIDTH / 2.0, HEIGHT / 2.0);
        board = texturedBoard();
    }

    @After
    public void tearDown() {
        if (board != null) board.release();
        if (tracker != null) tracker.close();
    }

    @Test
    public void tracksKnownPlanarMotionAndRecomputesPose() {
        RecognitionResult.SpatialAnchor detection = anchor(INITIAL_CORNERS);
        assertSame(detection,
                tracker.update(board, START_NANOS, detection, calibration));
        assertFalse("A fresh ORB anchor is not an optical-flow result", tracker.isTracked());

        double translateX = 9.0;
        double translateY = 6.0;
        Mat moved = translate(board, translateX, translateY);
        try {
            RecognitionResult.SpatialAnchor result = tracker.update(
                    moved, START_NANOS + 120_000_000L, null, calibration);

            assertNotNull(result);
            assertTrue(tracker.isTracked());
            assertEquals("main_board_reference", result.getReferenceId());
            assertEquals("main_board", result.getComponentId());
            assertNotNull(result.getPose());
            assertTrue(result.getPose().getTranslationVector()[2] > 0.0);
            assertEquals(4, result.getProjectedCorners().size());
            for (int index = 0; index < INITIAL_CORNERS.size(); index++) {
                assertEquals(INITIAL_CORNERS.get(index).getX() + translateX,
                        result.getProjectedCorners().get(index).getX(), 2.5);
                assertEquals(INITIAL_CORNERS.get(index).getY() + translateY,
                        result.getProjectedCorners().get(index).getY(), 2.5);
            }
        } finally {
            moved.release();
        }
    }

    @Test
    public void flowExpiresShortlyAfterLastFreshDetection() {
        RecognitionResult.SpatialAnchor detection = anchor(INITIAL_CORNERS);
        assertNotNull(tracker.update(board, START_NANOS, detection, calibration));

        Mat moved = translate(board, 4.0, 3.0);
        try {
            long expired = START_NANOS
                    + (AssemblyAnchorTracker.MAX_TRACKING_AGE_MILLIS + 1L) * 1_000_000L;
            assertNull(tracker.update(moved, expired, null, calibration));
            assertFalse(tracker.isTracked());

            // Expiry clears native state; an older-looking frame cannot revive the anchor.
            assertNull(tracker.update(
                    moved, expired + 120_000_000L, null, calibration));
            assertFalse(tracker.isTracked());
        } finally {
            moved.release();
        }
    }

    @Test
    public void flowPreservesDetectedBoardAspectAndPhysicalUnits() {
        double[] rotation = {Math.PI - .2, .15, .03};
        double[] translation = {0.0, 0.0, 350.0};
        org.opencv.core.MatOfPoint3f object = new org.opencv.core.MatOfPoint3f(
                new org.opencv.core.Point3(-63.25, 75, 0),
                new org.opencv.core.Point3(63.25, 75, 0),
                new org.opencv.core.Point3(63.25, -75, 0),
                new org.opencv.core.Point3(-63.25, -75, 0));
        Mat rvec = new Mat(3, 1, CvType.CV_64F);
        Mat tvec = new Mat(3, 1, CvType.CV_64F);
        Mat camera = calibration.toOpenCvCameraMatrix();
        org.opencv.core.MatOfDouble distortion = calibration.toDistortionMatrix();
        org.opencv.core.MatOfPoint2f projected = new org.opencv.core.MatOfPoint2f();
        try {
            rvec.put(0, 0, rotation);
            tvec.put(0, 0, translation);
            org.opencv.calib3d.Calib3d.projectPoints(object, rvec, tvec, camera, distortion, projected);
            List<VisionPoint> corners = new java.util.ArrayList<>();
            for (Point p : projected.toArray()) corners.add(new VisionPoint(p.x, p.y));
            RecognitionResult.SpatialAnchor detection = new RecognitionResult.SpatialAnchor(
                    "white_board", "main_board", corners, new RecognitionResult.Pose(
                    rotation, translation, anchor(corners).getPose().getOpenGlModelViewMatrix()));
            tracker.update(board, START_NANOS, detection, calibration);
            RecognitionResult.SpatialAnchor tracked = tracker.update(
                    board, START_NANOS + 100_000_000L, null, calibration);
            assertNotNull("A stationary calibrated white board must remain trackable", tracked);
            for (int index = 0; index < 3; index++) {
                assertEquals(translation[index], tracked.getPose().getTranslationVector()[index], .8);
                assertEquals(rotation[index], tracked.getPose().getRotationVector()[index], .03);
            }
        } finally {
            projected.release(); distortion.release(); camera.release();
            tvec.release(); rvec.release(); object.release();
        }
    }

    @Test
    public void blankFrameFailsClosedWithoutFreezingPreviousPose() {
        RecognitionResult.SpatialAnchor detection = anchor(INITIAL_CORNERS);
        assertNotNull(tracker.update(board, START_NANOS, detection, calibration));

        Mat blank = new Mat(HEIGHT, WIDTH, CvType.CV_8UC1, new Scalar(28.0));
        try {
            assertNull(tracker.update(
                    blank, START_NANOS + 120_000_000L, null, calibration));
            assertFalse(tracker.isTracked());
            assertNull("Lost flow must not return a frozen anchor on the next frame",
                    tracker.update(blank, START_NANOS + 240_000_000L, null, calibration));
        } finally {
            blank.release();
        }
    }

    @Test
    public void freshDetectorAnchorSurvivesCornersUnsuitableForOpticalFlow() {
        // The detector permits projected corners up to 25% outside the image. Flow requires
        // every corner inside the image; adding tracking must not veto that fresh detection.
        RecognitionResult.SpatialAnchor detection = anchor(Arrays.asList(
                new VisionPoint(-10.0, 70.0), new VisionPoint(270.0, 70.0),
                new VisionPoint(270.0, 400.0), new VisionPoint(-10.0, 400.0)));

        assertSame(detection, tracker.update(board, START_NANOS, detection, calibration));
        assertFalse(tracker.isTracked());
        assertNull("Unseedable fresh corners must not authorize later optical-flow poses",
                tracker.update(board, START_NANOS + 120_000_000L, null, calibration));
    }

    @Test
    public void freshDetectorAnchorSurvivesAreaOutsideOpticalFlowSeedRange() {
        // Both areas satisfy the detector's 0.002..0.95 range while falling outside the
        // tracker's stricter 0.004..0.90 range. A valid fresh anchor remains authoritative.
        List<List<VisionPoint>> detectorCorners = Arrays.asList(
                Arrays.asList(new VisionPoint(300.0, 200.0), new VisionPoint(340.0, 200.0),
                        new VisionPoint(340.0, 225.0), new VisionPoint(300.0, 225.0)),
                Arrays.asList(new VisionPoint(20.0, 5.0), new VisionPoint(620.0, 5.0),
                        new VisionPoint(620.0, 475.0), new VisionPoint(20.0, 475.0)));
        long timestamp = START_NANOS;
        for (List<VisionPoint> corners : detectorCorners) {
            RecognitionResult.SpatialAnchor detection = anchor(corners);
            assertSame(detection, tracker.update(board, timestamp, detection, calibration));
            assertFalse(tracker.isTracked());
            assertNull("No valid flow seed exists for the next detector dropout",
                    tracker.update(board, timestamp + 120_000_000L, null, calibration));
            timestamp += 240_000_000L;
        }
    }

    private static Mat texturedBoard() {
        Mat image = new Mat(HEIGHT, WIDTH, CvType.CV_8UC1, new Scalar(24.0));
        Imgproc.rectangle(image, new Point(180.0, 70.0), new Point(460.0, 400.0),
                new Scalar(182.0), Imgproc.FILLED);
        Imgproc.rectangle(image, new Point(180.0, 70.0), new Point(460.0, 400.0),
                new Scalar(238.0), 4);

        // Deterministic, asymmetric features cover the central board and its top/bottom strips.
        // The tracker deliberately masks the side motor zones, so this fixture proves that the
        // stable planar surface alone is sufficient.
        for (int row = 0; row < 11; row++) {
            for (int column = 0; column < 8; column++) {
                double x = 246.0 + column * 24.0 + (row % 2) * 3.0;
                double y = 91.0 + row * 28.0;
                int shade = 35 + ((row * 37 + column * 19) % 105);
                if ((row + column) % 3 == 0) {
                    Imgproc.rectangle(image,
                            new Point(x - 6.0, y - 5.0),
                            new Point(x + 7.0, y + 6.0),
                            new Scalar(shade), Imgproc.FILLED);
                } else {
                    Imgproc.circle(image, new Point(x, y),
                            5 + (column % 3), new Scalar(shade), Imgproc.FILLED);
                }
            }
        }
        Imgproc.line(image, new Point(205.0, 92.0), new Point(435.0, 118.0),
                new Scalar(60.0), 3);
        Imgproc.line(image, new Point(220.0, 370.0), new Point(442.0, 342.0),
                new Scalar(245.0), 3);
        Imgproc.putText(image, "NEOBOT", new Point(263.0, 245.0),
                Imgproc.FONT_HERSHEY_SIMPLEX, 0.75, new Scalar(250.0), 2);
        return image;
    }

    private static Mat translate(Mat source, double x, double y) {
        Mat transform = new Mat(2, 3, CvType.CV_64FC1);
        Mat destination = new Mat();
        try {
            transform.put(0, 0, 1.0, 0.0, x, 0.0, 1.0, y);
            Imgproc.warpAffine(source, destination, transform,
                    new Size(source.cols(), source.rows()), Imgproc.INTER_LINEAR,
                    org.opencv.core.Core.BORDER_CONSTANT, new Scalar(24.0));
            return destination;
        } catch (RuntimeException failure) {
            destination.release();
            throw failure;
        } finally {
            transform.release();
        }
    }

    private static RecognitionResult.SpatialAnchor anchor(List<VisionPoint> corners) {
        return new RecognitionResult.SpatialAnchor(
                "main_board_reference", "main_board", corners,
                new RecognitionResult.Pose(
                        new double[]{0.0, 0.0, 0.0},
                        new double[]{0.0, 0.0, 540.0},
                        new float[]{
                                1f, 0f, 0f, 0f,
                                0f, 1f, 0f, 0f,
                                0f, 0f, 1f, 0f,
                                0f, 0f, -540f, 1f
                        }));
    }
}
