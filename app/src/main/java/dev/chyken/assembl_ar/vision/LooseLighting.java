package dev.chyken.assembl_ar.vision;

import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

/** Background exposure evidence for loose appearance only; never changes camera/pose gates. */
final class LooseLighting {
    private LooseLighting() { }

    /** Median, rather than mean, keeps a small flashlight hotspot from setting the exposure. */
    static double neutralMedian(byte[] gray, byte[] hsv) {
        if (gray.length == 0 || hsv.length != gray.length * 3) return Double.NaN;
        int[] histogram = new int[256];
        int count = 0;
        for (int p = 0; p < gray.length; p++) {
            if ((hsv[p * 3 + 1] & 255) <= 45 && (hsv[p * 3 + 2] & 255) >= 50) {
                histogram[gray[p] & 255]++; count++;
            }
        }
        if (count < gray.length * .10) return Double.NaN;
        return median(histogram, count);
    }

    static double median(int[] histogram, int count) {
        if (count <= 0) return Double.NaN;
        int first = (count - 1) / 2, second = count / 2, cumulative = 0, low = -1;
        for (int value = 0; value < histogram.length; value++) {
            cumulative += histogram[value];
            if (low < 0 && cumulative > first) low = value;
            if (cumulative > second) return (low + value) * .5;
        }
        return Double.NaN;
    }

    static double exposureGain(double median) {
        return !Double.isFinite(median) || median <= 0 ? 1 : Math.min(3, Math.max(1, 200 / median));
    }

    /** Owns a new Mat; the caller always releases it, including when the gain is one. */
    static Mat normalizeExposure(Mat rgb) {
        Mat gray = new Mat(), hsv = new Mat(), result = new Mat();
        try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV);
            int count = (int) gray.total();
            byte[] values = new byte[count], colors = new byte[count * 3];
            gray.get(0, 0, values); hsv.get(0, 0, colors);
            double gain = exposureGain(neutralMedian(values, colors));
            byte[] pixels = new byte[count * 3]; rgb.get(0, 0, pixels);
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = (byte) Math.min(255, (int) Math.floor((pixels[i] & 255) * gain + .5));
            }
            result.create(rgb.rows(), rgb.cols(), rgb.type()); result.put(0, 0, pixels);
            return result;
        } catch (RuntimeException failure) {
            result.release(); throw failure;
        } finally {
            hsv.release(); gray.release();
        }
    }
}
