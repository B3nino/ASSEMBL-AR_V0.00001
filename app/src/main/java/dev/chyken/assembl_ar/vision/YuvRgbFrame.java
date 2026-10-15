package dev.chyken.assembl_ar.vision;

import android.graphics.ImageFormat;
import android.graphics.Rect;

import androidx.camera.core.ImageProxy;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.nio.ByteBuffer;

/** Stride-aware, cropped RGB conversion for color cues in assembled motor inspection. */
final class YuvRgbFrame {
    private YuvRgbFrame() { }

    /** Caller owns the result. Pixel coordinates match AssemblyVisionAnalyzer's grayscale Mat. */
    static Mat from(ImageProxy image, Rect crop, int rotationDegrees, boolean rotate) {
        if (image.getFormat() != ImageFormat.YUV_420_888 || image.getPlanes().length < 3
                || crop.left < 0 || crop.top < 0 || crop.right > image.getWidth()
                || crop.bottom > image.getHeight() || crop.isEmpty()) {
            throw new IllegalArgumentException("Valid cropped YUV frame required");
        }
        Plane y = new Plane(image.getPlanes()[0]);
        Plane u = new Plane(image.getPlanes()[1]);
        Plane v = new Plane(image.getPlanes()[2]);
        byte[] pixels = new byte[Math.multiplyExact(Math.multiplyExact(crop.width(), crop.height()), 3)];
        int index = 0;
        for (int row = crop.top; row < crop.bottom; row++) {
            for (int column = crop.left; column < crop.right; column++) {
                int luminance = Math.max(0, y.sample(column, row) - 16);
                int cb = u.sample(column / 2, row / 2) - 128;
                int cr = v.sample(column / 2, row / 2) - 128;
                pixels[index++] = clamp((298 * luminance + 409 * cr + 128) >> 8);
                pixels[index++] = clamp((298 * luminance - 100 * cb - 208 * cr + 128) >> 8);
                pixels[index++] = clamp((298 * luminance + 516 * cb + 128) >> 8);
            }
        }
        Mat rgb = new Mat(crop.height(), crop.width(), CvType.CV_8UC3);
        rgb.put(0, 0, pixels);
        if (!rotate || rotationDegrees == 0) return rgb;
        Mat rotated = new Mat();
        try {
            switch (rotationDegrees) {
                case 90: Core.rotate(rgb, rotated, Core.ROTATE_90_CLOCKWISE); break;
                case 180: Core.rotate(rgb, rotated, Core.ROTATE_180); break;
                case 270: Core.rotate(rgb, rotated, Core.ROTATE_90_COUNTERCLOCKWISE); break;
                default: throw new IllegalArgumentException("Invalid camera rotation");
            }
            return rotated;
        } catch (RuntimeException error) {
            rotated.release();
            throw error;
        } finally {
            rgb.release();
        }
    }

    private static byte clamp(int value) {
        return (byte) Math.max(0, Math.min(255, value));
    }

    private static final class Plane {
        final ByteBuffer buffer;
        final int base;
        final int rowStride;
        final int pixelStride;

        Plane(ImageProxy.PlaneProxy plane) {
            buffer = plane.getBuffer().duplicate();
            base = buffer.position();
            rowStride = plane.getRowStride();
            pixelStride = plane.getPixelStride();
            if (rowStride < 1 || pixelStride < 1) throw new IllegalArgumentException("Invalid YUV stride");
        }

        int sample(int x, int y) {
            long offset = (long) base + (long) y * rowStride + (long) x * pixelStride;
            if (offset < base || offset >= buffer.limit()) {
                throw new IllegalArgumentException("Truncated YUV plane");
            }
            return buffer.get((int) offset) & 255;
        }
    }
}
