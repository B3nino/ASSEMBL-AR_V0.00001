package dev.chyken.assembl_ar.vision;

import android.content.Context;

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
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Native parity checks for the frozen odd-scan sensor prototype; never used for calibration. */
@RunWith(AndroidJUnit4.class)
public final class LooseSensorShapeRecognizerInstrumentedTest {
    private static final String BASE = "references/recognition-fix-20261008/";

    @BeforeClass public static void loadOpenCv() {
        assertTrue(OpenCVLoader.initLocal());
    }

    @Test public void poolSensorViewsHaveIdentityAndFiniteCoarsePose() throws Exception {
        for (String name : new String[]{"ultrasonic-pool-1.jpg", "ultrasonic-pool-3.jpg",
                "line-pool-1.jpg", "line-pool-5.jpg"}) {
            Mat rgb = decodeRgb(BASE + name, false);
            try {
                LooseSensorShapeRecognizer.Candidate candidate = name.startsWith("ultrasonic")
                        ? LooseSensorShapeRecognizer.findUltrasonic(rgb, .002)
                        : LooseSensorShapeRecognizer.findLineTracker(rgb, .002);
                assertNotNull(name, candidate);
                assertEquals(4, candidate.corners.size());
                CameraCalibration calibration = new CameraCalibration(
                        new double[]{655.08, 0, 359, 0, 655.08, 250, 0, 0, 1}, new double[0]);
                assertNotNull(name, LoosePartShapeRecognizer.estimatePose(candidate.corners,
                        candidate.widthMm, candidate.heightMm, calibration));
                if (name.startsWith("ultrasonic")) {
                    assertNull(name, LooseSensorShapeRecognizer.findLineTracker(rgb, .002));
                } else {
                    assertNull(name, LooseSensorShapeRecognizer.findUltrasonic(rgb, .002));
                }
            } finally {
                rgb.release();
            }
        }
    }

    @Test public void septemberReplayMatchesFrozenSensitivityAndRejectsEveryOtherPart() throws Exception {
        String[] names = {
                "loose-caster-095653.jpg", "loose-caster-095658.jpg", "loose-caster-095707.jpg", "loose-caster-095713.jpg",
                "loose-line-tracker-095507.jpg", "loose-line-tracker-095513.jpg", "loose-line-tracker-095519.jpg", "loose-line-tracker-095522.jpg",
                "loose-motor-bracket-half-100026.jpg", "loose-motor-bracket-half-100038.jpg", "loose-motor-bracket-half-100105.jpg", "loose-motor-bracket-half-100111.jpg",
                "loose-nano-095135.jpg", "loose-nano-095402.jpg", "loose-nano-095424.jpg", "loose-nano-095449.jpg",
                "loose-ultrasonic-sensor-095548.jpg", "loose-ultrasonic-sensor-095553.jpg", "loose-ultrasonic-sensor-095602.jpg", "loose-ultrasonic-sensor-095636.jpg"
        };
        for (String name : names) {
            Mat rgb = decodeRgb("references/assembly-replay/" + name, true);
            try {
                // The four oblique ultrasonic photos are still misses; preserve the honest result.
                assertNull(name, LooseSensorShapeRecognizer.findUltrasonic(rgb, .002));
                // Neutral-background exposure now also recovers the first tracker view.
                boolean expectedLine = name.equals("loose-line-tracker-095507.jpg")
                        || name.equals("loose-line-tracker-095513.jpg")
                        || name.equals("loose-line-tracker-095519.jpg");
                assertEquals(name, expectedLine,
                        LooseSensorShapeRecognizer.findLineTracker(rgb, .002) != null);
            } finally {
                rgb.release();
            }
        }
    }

    @Test public void emptyBackgroundsAndUnstructuredColoredShapesAreRejected() {
        for (Scalar color : new Scalar[]{new Scalar(255, 255, 255), new Scalar(210, 210, 210),
                new Scalar(232, 216, 196), new Scalar(191, 212, 231)}) {
            Mat rgb = new Mat(500, 720, CvType.CV_8UC3, color);
            try {
                Imgproc.line(rgb, new Point(0, 0), new Point(719, 499), new Scalar(170, 170, 170), 1);
                Imgproc.circle(rgb, new Point(360, 250), 150, new Scalar(185, 185, 185), 1);
                assertNull(LooseSensorShapeRecognizer.findUltrasonic(rgb, .002));
                assertNull(LooseSensorShapeRecognizer.findLineTracker(rgb, .002));
            } finally {
                rgb.release();
            }
        }
        Mat rgb = new Mat(500, 720, CvType.CV_8UC3, Scalar.all(220));
        try {
            Imgproc.rectangle(rgb, new Point(230, 190), new Point(490, 250), new Scalar(15, 25, 35), -1);
            assertNull(LooseSensorShapeRecognizer.findLineTracker(rgb, .002));
            Imgproc.rectangle(rgb, new Point(250, 200), new Point(290, 235), new Scalar(20, 80, 180), -1);
            assertNull(LooseSensorShapeRecognizer.findLineTracker(rgb, .002));
            assertNull(LooseSensorShapeRecognizer.findUltrasonic(rgb, .002));
        } finally {
            rgb.release();
        }
    }

    private static Mat decodeRgb(String path, boolean replaySize) throws Exception {
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream stream = tests.getAssets().open(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
        }
        MatOfByte encoded = new MatOfByte(bytes.toByteArray());
        Mat bgr = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR), rgb = new Mat();
        try {
            assertTrue(path, !bgr.empty());
            Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
            if (!replaySize) return rgb;
            double scale = 640.0 / Math.max(rgb.cols(), rgb.rows());
            int width = Math.max(2, (int) Math.round(rgb.cols() * scale) & ~1);
            int height = Math.max(2, (int) Math.round(rgb.rows() * scale) & ~1);
            Mat resized = new Mat();
            Imgproc.resize(rgb, resized, new Size(width, height), 0, 0, Imgproc.INTER_AREA);
            rgb.release();
            return resized;
        } finally {
            bgr.release(); encoded.release();
        }
    }
}
