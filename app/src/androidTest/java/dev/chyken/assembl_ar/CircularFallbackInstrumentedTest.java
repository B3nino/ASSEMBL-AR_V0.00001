package dev.chyken.assembl_ar;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
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
import org.opencv.imgproc.Imgproc;

import java.util.Collections;

import dev.chyken.assembl_ar.vision.CameraCalibration;
import dev.chyken.assembl_ar.vision.ComponentRecognizer;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.RecognitionRequest;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.ReferenceDatabase;
import dev.chyken.assembl_ar.vision.VisionConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Executes the real OpenCV Canny/Hough fallback against deterministic synthetic frames. */
@RunWith(AndroidJUnit4.class)
public final class CircularFallbackInstrumentedTest {
    private static final int FRAME_WIDTH = 360;
    private static final int FRAME_HEIGHT = 240;
    private static final int EXPECTED_RADIUS_PX = 30;
    private static final String COMPONENT_ID = "synthetic_circular_hardware";
    private static final String REFERENCE_ID = "synthetic_circular_reference";

    private VisionConfig config;
    private ReferenceDatabase database;
    private ComponentRecognizer recognizer;
    private CameraCalibration calibration;

    @Before
    public void setUp() throws Exception {
        assertTrue("The packaged OpenCV runtime must initialize", OpenCVLoader.initLocal());
        Context context = ApplicationProvider.getApplicationContext();
        config = VisionConfig.builder()
                // Keep the unrelated asset from becoming an ORB candidate. These tests are solely
                // about the actual fallback path, not about helper selection functions.
                .matching(0.70f, 64.0f, 500)
                .homography(500, 0.90, 2.0, 500, 0.99)
                .circularFallback(30.0, 90.0, 5, 1.0,
                        72.0, 12.0, 18, 70, 1)
                .latencyLoggingEnabled(false)
                .build();
        ComponentReferenceSpec spec = ComponentReferenceSpec.builder(
                        REFERENCE_ID, COMPONENT_ID, "Synthetic circular hardware", 20.0, 20.0)
                .jpegAssetPath("references/assembly/image-001.png")
                .circularHardware(0.105, 0.145)
                .build();
        database = ReferenceDatabase.load(
                context.getAssets(), Collections.singletonList(spec), config);
        recognizer = new ComponentRecognizer(config, database);
        calibration = CameraCalibration.pinhole(
                300.0, 300.0, FRAME_WIDTH * 0.5, FRAME_HEIGHT * 0.5);
    }

    @After
    public void tearDown() {
        if (recognizer != null) {
            recognizer.close();
        }
        if (database != null) {
            database.close();
        }
    }

    @Test
    public void oneCircleProducesOneExpectedFallbackInstance() {
        Mat frame = frameWithCircles(new Point(FRAME_WIDTH * 0.5, FRAME_HEIGHT * 0.5));
        try {
            RecognitionResult result = recognizer.recognize(
                    frame, 1L, request(1));

            assertEquals(RecognitionResult.DetectionMethod.CANNY_HOUGH_FALLBACK,
                    result.getDetectionMethod());
            assertEquals(RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                    result.getValidationState());
            assertEquals(COMPONENT_ID, result.getComponentId());
            assertEquals(1, result.getInstanceObservations().size());
            assertEquals(RecognitionResult.DetectionMethod.CANNY_HOUGH_FALLBACK,
                    result.getInstanceObservations().get(0).getDetectionMethod());
            assertNotNull(result.getPose());
        } finally {
            frame.release();
        }
    }

    @Test
    public void twoSeparatedCirclesProduceTwoExpectedFallbackInstances() {
        Mat frame = frameWithCircles(
                new Point(105.0, FRAME_HEIGHT * 0.5),
                new Point(255.0, FRAME_HEIGHT * 0.5));
        try {
            RecognitionResult result = recognizer.recognize(
                    frame, 2L, request(2));

            assertEquals(RecognitionResult.DetectionMethod.CANNY_HOUGH_FALLBACK,
                    result.getDetectionMethod());
            assertEquals(RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                    result.getValidationState());
            assertEquals(2, result.getInstanceObservations().size());
            assertTrue(result.getInstanceObservations().get(0).getProjectedCorners().get(0)
                    .distanceTo(result.getInstanceObservations().get(1).getProjectedCorners().get(0))
                    > EXPECTED_RADIUS_PX * 2.0);
        } finally {
            frame.release();
        }
    }

    @Test
    public void unrelatedRadiusAndBlankFrameFailClosed() {
        Mat unrelatedRadius = Mat.zeros(FRAME_HEIGHT, FRAME_WIDTH, CvType.CV_8UC1);
        Mat blank = Mat.zeros(FRAME_HEIGHT, FRAME_WIDTH, CvType.CV_8UC1);
        try {
            Imgproc.circle(unrelatedRadius,
                    new Point(FRAME_WIDTH * 0.5, FRAME_HEIGHT * 0.5),
                    60, new Scalar(255.0), Imgproc.FILLED, Imgproc.LINE_8, 0);

            RecognitionResult unrelated = recognizer.recognize(
                    unrelatedRadius, 3L, request(1));
            assertEquals(RecognitionResult.DetectionMethod.CANNY_HOUGH_FALLBACK,
                    unrelated.getDetectionMethod());
            assertEquals(RecognitionResult.ValidationState.NO_COMPONENT,
                    unrelated.getValidationState());
            assertEquals(0, unrelated.getInstanceObservations().size());
            assertNull(unrelated.getComponentId());
            assertNull(unrelated.getPose());

            RecognitionResult none = recognizer.recognize(blank, 4L, request(1));
            assertEquals(RecognitionResult.DetectionMethod.NONE, none.getDetectionMethod());
            assertEquals(RecognitionResult.ValidationState.NO_COMPONENT,
                    none.getValidationState());
            assertEquals(0, none.getInstanceObservations().size());
            assertTrue(none.getCircles().isEmpty());
            assertNull(none.getPose());
        } finally {
            blank.release();
            unrelatedRadius.release();
        }
    }

    private RecognitionRequest request(int expectedInstanceCount) {
        return new RecognitionRequest(
                RecognitionRequest.AssemblyExpectation.component(COMPONENT_ID),
                calibration,
                null,
                null,
                expectedInstanceCount);
    }

    private static Mat frameWithCircles(Point... centers) {
        Mat frame = Mat.zeros(FRAME_HEIGHT, FRAME_WIDTH, CvType.CV_8UC1);
        for (Point center : centers) {
            Imgproc.circle(frame, center, EXPECTED_RADIUS_PX,
                    new Scalar(255.0), Imgproc.FILLED, Imgproc.LINE_8, 0);
        }
        return frame;
    }
}
