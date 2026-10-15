package dev.chyken.assembl_ar.vision;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import dev.chyken.assembl_ar.data.NeobotSeedData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Synthetic topology regressions, not a physical-camera accuracy benchmark. Proportions follow
 * the supplied bracket photograph and nominal Blender model. These drawings are never references.
 */
@RunWith(AndroidJUnit4.class)
public final class MotorBracketTopologyInstrumentedTest {
    private static final Scalar BACKGROUND = Scalar.all(232);
    private static final Scalar BLACK = Scalar.all(24);
    private static final int FRAME_WIDTH = 900;
    private static final int FRAME_HEIGHT = 700;
    private static final double LEFT = 330;
    private static final double TOP = 180;
    private static final double WIDTH = 210;
    private static final double HEIGHT = 300;
    private static final long TIMESTAMP = 300L;

    @BeforeClass public static void loadOpenCv() {
        assertTrue(OpenCVLoader.initLocal());
    }

    @Test public void blackNeutralAndReflectiveTWithCorrectSlotsProduceCoarseSpatialResult() {
        for (int appearance = 0; appearance < 3; appearance++) {
            Mat rgb = fixture(false, Layout.CORRECT,
                    appearance == 1 ? Scalar.all(158) : BLACK, appearance == 2);
            try {
                assertSpatialFallback("appearance=" + appearance, rgb, false);
            } finally {
                rgb.release();
            }
        }
    }

    @Test public void authoredSlotSelectsOneBracketWhileTheOtherRemainsVisible() {
        Mat source = fixture(false, Layout.CORRECT, BLACK, false);
        Mat pair = new Mat(FRAME_HEIGHT, FRAME_WIDTH * 2, CvType.CV_8UC3, BACKGROUND);
        Mat left = pair.colRange(0, FRAME_WIDTH);
        Mat right = pair.colRange(FRAME_WIDTH, FRAME_WIDTH * 2);
        Mat gray = new Mat();
        try {
            source.copyTo(left);
            source.copyTo(right);
            Imgproc.cvtColor(pair, gray, Imgproc.COLOR_RGB2GRAY);
            RecognitionRequest request = request(gray);
            RecognitionResult empty = RecognitionResult.builder(TIMESTAMP).build();
            LoosePartShapeRecognizer detector = new LoosePartShapeRecognizer();
            assertSame("Unscoped parts testing must retain its ambiguity rejection", empty,
                    detector.recognize(gray, pair, TIMESTAMP, request, empty));
            Predicate<List<VisionPoint>> leftSlot = corners ->
                    corners.stream().mapToDouble(VisionPoint::getX).average().orElse(0) < FRAME_WIDTH;
            Predicate<List<VisionPoint>> rightSlot = corners ->
                    corners.stream().mapToDouble(VisionPoint::getX).average().orElse(0) > FRAME_WIDTH;
            RecognitionResult first = detector.recognize(gray, pair, TIMESTAMP + 1,
                    request, empty, leftSlot);
            assertEquals(NeobotSeedData.MOTOR_BRACKET, first.getComponentId());
            assertTrue(leftSlot.test(first.getProjectedCorners()));
            detector.reset();
            RecognitionResult second = detector.recognize(gray, pair, TIMESTAMP + 2,
                    request, first, rightSlot);
            assertEquals(NeobotSeedData.MOTOR_BRACKET, second.getComponentId());
            assertTrue("An earlier bracket cannot prevent inspecting the next slot",
                    rightSlot.test(second.getProjectedCorners()));
        } finally {
            gray.release(); right.release(); left.release(); pair.release(); source.release();
        }
    }

    @Test public void rotatedAndMirroredCorrectBracketKeepIdentityWithoutClaimingOrientation() {
        Mat source = fixture(false, Layout.CORRECT, BLACK, false);
        Mat transformed = new Mat();
        try {
            Core.flip(source, transformed, 1);
            assertSpatialFallback("mirrored face", transformed, false);
            Core.rotate(source, transformed, Core.ROTATE_90_CLOCKWISE);
            assertSpatialFallback("quarter turn", transformed, false);
            Core.rotate(source, transformed, Core.ROTATE_180);
            assertSpatialFallback("half turn", transformed, false);
        } finally {
            transformed.release();
            source.release();
        }
    }

    @Test public void fourSeparatedBracketsAreCountedTogetherAndThreeRemainIncomplete() {
        Mat source = fixture(false, Layout.CORRECT, BLACK, false);
        Mat group = new Mat(FRAME_HEIGHT * 2, FRAME_WIDTH * 2, CvType.CV_8UC3, BACKGROUND);
        Mat gray = new Mat();
        try {
            for (int index = 0; index < 4; index++) {
                Mat tile = group.submat((index / 2) * FRAME_HEIGHT, (index / 2 + 1) * FRAME_HEIGHT,
                        (index % 2) * FRAME_WIDTH, (index % 2 + 1) * FRAME_WIDTH);
                try { source.copyTo(tile); }
                finally { tile.release(); }
            }
            double focal = Math.max(group.cols(), group.rows()) * 1.15;
            RecognitionRequest setRequest = new RecognitionRequest(
                    RecognitionRequest.AssemblyExpectation.component(NeobotSeedData.MOTOR_BRACKET),
                    CameraCalibration.pinhole(focal, focal, group.cols() * .5, group.rows() * .5),
                    null, null, 4, 2);
            Imgproc.cvtColor(group, gray, Imgproc.COLOR_RGB2GRAY);
            LoosePartShapeRecognizer detector = new LoosePartShapeRecognizer();
            RecognitionResult four = detector.recognize(gray, group, TIMESTAMP, setRequest,
                    RecognitionResult.builder(TIMESTAMP).build());
            assertEquals(4, four.getDetectedInstanceCount(NeobotSeedData.MOTOR_BRACKET));
            Mat fourthTile = group.submat(FRAME_HEIGHT, FRAME_HEIGHT * 2, FRAME_WIDTH, FRAME_WIDTH * 2);
            try { fourthTile.setTo(BACKGROUND); }
            finally { fourthTile.release(); }
            Imgproc.cvtColor(group, gray, Imgproc.COLOR_RGB2GRAY);
            RecognitionResult three = detector.recognize(gray, group, TIMESTAMP + 1, setRequest,
                    RecognitionResult.builder(TIMESTAMP + 1).build());
            assertEquals(3, three.getDetectedInstanceCount(NeobotSeedData.MOTOR_BRACKET));
        } finally {
            gray.release(); group.release(); source.release();
        }
    }

    @Test public void rectangularPlateWithCorrectSlotsIsRejected() {
        assertRejected("rectangular plate", fixture(true, Layout.CORRECT, BLACK, false));
    }

    @Test public void tWithOnlyTwoSlotsIsRejected() {
        assertRejected("only two slots", fixture(false, Layout.TWO_SLOTS, BLACK, false));
    }

    @Test public void oneSidedLPlateWithStaggeredSlotsIsRejected() {
        Mat rgb = new Mat(FRAME_HEIGHT, FRAME_WIDTH, CvType.CV_8UC3, BACKGROUND);
        MatOfPoint outline = new MatOfPoint(at(0,0), at(1,0), at(1,.18), at(.70,.18),
                at(.70,1), at(0,1));
        try {
            Imgproc.fillPoly(rgb, Collections.singletonList(outline), BLACK);
            slot(rgb,.35,.33,.24,false); slot(rgb,.35,.69,.24,false);
            slot(rgb,.58,.51,.30,false);
            assertRejected("L plate has only one shoulder", rgb);
        } finally { outline.release(); }
    }

    @Test public void tWithThreeCollinearSlotsIsRejected() {
        assertRejected("three collinear slots", fixture(false, Layout.COLLINEAR, BLACK, false));
    }

    @Test public void tWithOneHorizontalSlotIsRejected() {
        assertRejected("one horizontal slot", fixture(false, Layout.HORIZONTAL, BLACK, false));
    }

    @Test public void tWithIncorrectStaggeredSlotPositionsIsRejected() {
        assertRejected("incorrect stagger", fixture(false, Layout.WRONG_STAGGER, BLACK, false));
    }

    @Test public void blueAndGreenObjectsWithCorrectShapeAreRejectedWhenRgbIsAvailable() {
        for (Scalar color : new Scalar[]{new Scalar(28, 54, 150), new Scalar(30, 105, 35)}) {
            Mat rgb = fixture(false, Layout.CORRECT, color, false);
            // Establish that these are otherwise-valid topology controls before checking color.
            try {
                assertSpatialFallback("colored shape in grayscale", rgb, true);
                assertRejected("colored electronics veto", rgb.clone());
            } finally {
                rgb.release();
            }
        }
    }

    private static void assertSpatialFallback(String label, Mat rgb, boolean grayscaleOnly) {
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
            RecognitionResult result = new LoosePartShapeRecognizer().recognize(
                    gray, grayscaleOnly ? null : rgb, TIMESTAMP, request(gray),
                    RecognitionResult.builder(TIMESTAMP).build());
            assertEquals(label, NeobotSeedData.MOTOR_BRACKET, result.getComponentId());
            assertEquals(label, RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK,
                    result.getDetectionMethod());
            assertEquals(label, RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                    result.getValidationState());
            assertEquals(label, RecognitionResult.OrientationClassification.UNKNOWN,
                    result.getOrientationClassification());
            assertEquals(label, 4, result.getProjectedCorners().size());
            assertNotNull(label, result.getPose());
        } finally {
            gray.release();
        }
    }

    /** Consumes this independently allocated fixture. */
    private static void assertRejected(String label, Mat rgb) {
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
            RecognitionResult primary = RecognitionResult.builder(TIMESTAMP).build();
            RecognitionResult result = new LoosePartShapeRecognizer().recognize(
                    gray, rgb, TIMESTAMP, request(gray), primary);
            assertSame(label, primary, result);
        } finally {
            gray.release();
            rgb.release();
        }
    }

    private static RecognitionRequest request(Mat gray) {
        double focal = Math.max(gray.cols(), gray.rows()) * 1.15;
        return new RecognitionRequest(
                RecognitionRequest.AssemblyExpectation.component(NeobotSeedData.MOTOR_BRACKET),
                CameraCalibration.pinhole(focal, focal, gray.cols() * .5, gray.rows() * .5),
                null);
    }

    private static Mat fixture(boolean rectangle, Layout layout, Scalar color, boolean reflective) {
        Mat rgb = new Mat(FRAME_HEIGHT, FRAME_WIDTH, CvType.CV_8UC3, BACKGROUND);
        Mat mask = Mat.zeros(FRAME_HEIGHT, FRAME_WIDTH, CvType.CV_8UC1);
        MatOfPoint outline = new MatOfPoint(rectangle
                ? new Point[]{at(0, 0), at(1, 0), at(1, 1), at(0, 1)}
                : new Point[]{at(0, 0), at(1, 0), at(1, .18), at(.85, .18),
                        at(.85, 1), at(.15, 1), at(.15, .18), at(0, .18)});
        try {
            Imgproc.fillPoly(mask, Collections.singletonList(outline), Scalar.all(255));
            rgb.setTo(color, mask);
            if (reflective) {
                // Bright neutral regions meet the outer edge, so dark-only segmentation loses
                // the shoulder and stem. Re-drawn holes retain the actual aperture topology.
                Mat highlight = new Mat(FRAME_HEIGHT, FRAME_WIDTH, CvType.CV_8UC3, Scalar.all(174));
                Mat band = Mat.zeros(FRAME_HEIGHT, FRAME_WIDTH, CvType.CV_8UC1);
                try {
                    Imgproc.rectangle(band, at(0, 0), at(.57, .55), Scalar.all(255), -1);
                    Core.bitwise_and(band, mask, band);
                    highlight.copyTo(rgb, band);
                } finally {
                    band.release();
                    highlight.release();
                }
            }
            Imgproc.circle(rgb, at(.50, .09), 7, BACKGROUND, -1);
            if (layout == Layout.COLLINEAR) {
                slot(rgb, .50, .32, .18, false);
                slot(rgb, .50, .56, .18, false);
                slot(rgb, .50, .80, .18, false);
            } else if (layout == Layout.WRONG_STAGGER) {
                slot(rgb, .30, .34, .24, false);
                slot(rgb, .50, .52, .24, false);
                slot(rgb, .70, .71, .30, false);
            } else {
                slot(rgb, .43, .33, .24, layout == Layout.HORIZONTAL);
                slot(rgb, .43, .69, .24, false);
                if (layout != Layout.TWO_SLOTS) slot(rgb, .70, .51, .30, false);
            }
            return rgb;
        } finally {
            outline.release();
            mask.release();
        }
    }

    private static void slot(Mat image, double x, double y, double length, boolean horizontal) {
        Point center = at(x, y);
        int thickness = (int) Math.round(.08 * WIDTH);
        double halfStraight = length * HEIGHT * .5 - thickness * .5;
        Point first = new Point(center.x - (horizontal ? halfStraight : 0),
                center.y - (horizontal ? 0 : halfStraight));
        Point second = new Point(center.x + (horizontal ? halfStraight : 0),
                center.y + (horizontal ? 0 : halfStraight));
        Imgproc.line(image, first, second, BACKGROUND, thickness, Imgproc.LINE_8);
        Imgproc.circle(image, first, thickness / 2, BACKGROUND, -1);
        Imgproc.circle(image, second, thickness / 2, BACKGROUND, -1);
    }

    private static Point at(double x, double y) {
        return new Point(LEFT + WIDTH * x, TOP + HEIGHT * y);
    }

    private enum Layout { CORRECT, TWO_SLOTS, COLLINEAR, HORIZONTAL, WRONG_STAGGER }
}
