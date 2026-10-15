package dev.chyken.assembl_ar.vision;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import androidx.camera.core.ImageProxy;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

/** Exercises real YUV conversion with padding, interleaved chroma, crop and display rotation. */
@RunWith(AndroidJUnit4.class)
public final class YuvRgbFrameInstrumentedTest {
    @BeforeClass public static void load() { assertTrue(OpenCVLoader.initLocal()); }

    @Test public void paddedInterleavedPlanesKeepYellowInRgbOrder() {
        ImageProxy image = frame(plane(4, 4, 7, 1, 210),
                plane(2, 2, 6, 2, 16), plane(2, 2, 6, 2, 146));
        Mat rgb = YuvRgbFrame.from(image, new Rect(1, 1, 4, 3), 0, false);
        try {
            assertEquals(3, rgb.cols()); assertEquals(2, rgb.rows());
            double[] pixel = rgb.get(1, 2);
            assertTrue(pixel[0] > 245); assertTrue(pixel[1] > 245); assertTrue(pixel[2] < 10);
        } finally { rgb.release(); }
    }

    @Test public void rotatedRgbMatchesCroppedLuminanceCoordinates() {
        ImageProxy.PlaneProxy y = plane(4, 4, 7, 1, 16);
        ByteBuffer bytes = y.getBuffer();
        bytes.put(bytes.position() + 1 * 7 + 1, (byte)235);
        ImageProxy image = frame(y, plane(2, 2, 4, 1, 128), plane(2, 2, 4, 1, 128));
        Mat rgb = YuvRgbFrame.from(image, new Rect(1, 1, 4, 3), 90, true);
        try {
            assertEquals(2, rgb.cols()); assertEquals(3, rgb.rows());
            assertEquals(255, rgb.get(0, 1)[0], 1);
            assertEquals(0, rgb.get(2, 0)[0], 1);
        } finally { rgb.release(); }
    }

    @Test(expected = IllegalArgumentException.class)
    public void truncatedPlaneFailsClosed() {
        ImageProxy.PlaneProxy y = plane(4, 4, 7, 1, 210);
        y.getBuffer().limit(4);
        Mat unexpected = YuvRgbFrame.from(frame(y, plane(2,2,4,1,128), plane(2,2,4,1,128)),
                new Rect(0,0,4,4),0,false);
        unexpected.release();
    }

    @Test public void diagnosticRgbPngRoundTripPreservesChannelOrder() {
        Mat rgb = new Mat(3, 4, CvType.CV_8UC3, new Scalar(241, 117, 9));
        MatOfByte encoded = new MatOfByte(
                AssemblyVisionAnalyzer.encodeSnapshotPng(rgb, true));
        Mat bgr = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
        Mat replayRgb = new Mat();
        try {
            Imgproc.cvtColor(bgr, replayRgb, Imgproc.COLOR_BGR2RGB);
            double[] pixel = replayRgb.get(1, 2);
            assertEquals(241, pixel[0], 0.0);
            assertEquals(117, pixel[1], 0.0);
            assertEquals(9, pixel[2], 0.0);
        } finally {
            replayRgb.release();
            bgr.release();
            encoded.release();
            rgb.release();
        }
    }

    private static ImageProxy.PlaneProxy plane(int width, int height, int rowStride,
                                               int pixelStride, int value) {
        ByteBuffer buffer = ByteBuffer.allocate(3 + rowStride * height);
        buffer.position(3);
        for (int y=0;y<height;y++) for(int x=0;x<width;x++)
            buffer.put(3 + y * rowStride + x * pixelStride, (byte)value);
        return new ImageProxy.PlaneProxy() {
            public int getRowStride() { return rowStride; }
            public int getPixelStride() { return pixelStride; }
            public ByteBuffer getBuffer() { return buffer; }
        };
    }
    private static ImageProxy frame(ImageProxy.PlaneProxy... planes) {
        return (ImageProxy) Proxy.newProxyInstance(ImageProxy.class.getClassLoader(),
                new Class<?>[]{ImageProxy.class}, (proxy, method, args) -> {
                    switch(method.getName()) {
                        case "getFormat": return ImageFormat.YUV_420_888;
                        case "getWidth": case "getHeight": return 4;
                        case "getPlanes": return planes;
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
