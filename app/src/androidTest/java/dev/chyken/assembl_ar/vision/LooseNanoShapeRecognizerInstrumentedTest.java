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

/** Native checks of POOL identity evidence, cue ablation and the unchanged September holdouts. */
@RunWith(AndroidJUnit4.class)
public final class LooseNanoShapeRecognizerInstrumentedTest {
    @BeforeClass public static void loadOpenCv() {
        assertTrue(OpenCVLoader.initLocal());
    }

    @Test public void twoPoolViewsProduceFiniteCoarsePose() throws Exception {
        for (String name : new String[]{"nano-pool-1.jpg", "nano-pool-9.jpg"}) {
            Mat rgb = decodeRgb("references/recognition-fix-20261008/" + name, false);
            try {
                LooseNanoShapeRecognizer.Candidate candidate = LooseNanoShapeRecognizer.find(rgb, .002);
                assertNotNull(name, candidate);
                assertEquals(4, candidate.corners.size());
                assertNotNull(name, LoosePartShapeRecognizer.estimatePose(candidate.corners, 45, 18,
                        CameraCalibration.pinhole(655.08, 655.08, 359, 250)));
            } finally {
                rgb.release();
            }
        }
    }

    @Test public void elongatedBlueBoardNeedsItsUsbShellAndShellAloneIsInsufficient() {
        Mat rgb = new Mat(500, 720, CvType.CV_8UC3, Scalar.all(210));
        try {
            Imgproc.rectangle(rgb, new Point(240, 190), new Point(400, 240), new Scalar(20, 70, 160), -1);
            Imgproc.rectangle(rgb, new Point(305, 200), new Point(335, 230), Scalar.all(20), -1);
            assertNull("An elongated blue board alone cannot identify a Nano", LooseNanoShapeRecognizer.find(rgb, .002));
            Imgproc.rectangle(rgb, new Point(240, 196), new Point(258, 234), new Scalar(190, 145, 40), -1);
            assertNotNull("The compact end shell completes the independent synthetic evidence",
                    LooseNanoShapeRecognizer.find(rgb, .002));
            rgb.setTo(Scalar.all(210));
            Imgproc.rectangle(rgb, new Point(240, 196), new Point(258, 234), new Scalar(190, 145, 40), -1);
            assertNull("Gold-colored shell without the board is insufficient", LooseNanoShapeRecognizer.find(rgb, .002));
        } finally {
            rgb.release();
        }
    }

    @Test public void allSeptemberPhotosRemainZeroNanoDetectionsIncludingEveryOtherPart() throws Exception {
        Context tests = InstrumentationRegistry.getInstrumentation().getContext();
        int checked = 0;
        for (String name : tests.getAssets().list("references/assembly-replay")) {
            if (!name.startsWith("loose-") || !name.endsWith(".jpg")) continue;
            Mat rgb = decodeRgb("references/assembly-replay/" + name, true);
            try {
                assertNull(name, LooseNanoShapeRecognizer.find(rgb, .002));
                checked++;
            } finally {
                rgb.release();
            }
        }
        assertEquals("Four Nano holdouts and sixteen other-part holdouts", 20, checked);
    }

    @Test public void emptyWarmCoolAndMarkedBackgroundsAreRejected() {
        for (Scalar color : new Scalar[]{Scalar.all(255), Scalar.all(210),
                new Scalar(232, 216, 196), new Scalar(191, 212, 231)}) {
            Mat rgb = new Mat(500, 720, CvType.CV_8UC3, color);
            try {
                assertNull(LooseNanoShapeRecognizer.find(rgb, .002));
                Imgproc.line(rgb, new Point(0, 0), new Point(719, 499), Scalar.all(170), 1);
                Imgproc.circle(rgb, new Point(360, 250), 150, Scalar.all(185), 1);
                assertNull(LooseNanoShapeRecognizer.find(rgb, .002));
            } finally {
                rgb.release();
            }
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
