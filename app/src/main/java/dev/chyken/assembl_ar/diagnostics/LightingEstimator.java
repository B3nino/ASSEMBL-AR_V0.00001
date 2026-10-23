package dev.chyken.assembl_ar.diagnostics;

/** Image-based exposure estimate, not a lux meter or a user-supplied room label. */
public final class LightingEstimator {
    private LightingEstimator() {}
    public static String estimate(byte[] gray, int width, int height) {
        if (gray == null || width < 4 || height < 4 || gray.length < width * height) return "Unknown";
        long sum = 0; int dark = 0, bright = 0;
        double low = 255, high = 0;
        for (int ty = 0; ty < 4; ty++) for (int tx = 0; tx < 4; tx++) {
            long tile = 0; int count = 0;
            for (int y = ty * height / 4; y < (ty + 1) * height / 4; y++)
                for (int x = tx * width / 4; x < (tx + 1) * width / 4; x++) {
                    int v = gray[y * width + x] & 255;
                    sum += v; tile += v; count++;
                    if (v < 35) dark++; if (v > 225) bright++;
                }
            double mean = tile / (double) Math.max(1, count);
            low = Math.min(low, mean); high = Math.max(high, mean);
        }
        double mean = sum / (double) (width * height);
        if (mean < 50) return "Weak";
        if (mean > 210) return "Bright";
        if (high - low > 100 && dark > gray.length * .12 && bright > gray.length * .12) return "Uneven";
        return "Normal";
    }
}
