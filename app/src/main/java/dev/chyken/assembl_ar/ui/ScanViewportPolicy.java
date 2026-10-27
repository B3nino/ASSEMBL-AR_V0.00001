package dev.chyken.assembl_ar.ui;

/** Window-size policy only. Camera resolution, verification and checkpoints are independent. */
public final class ScanViewportPolicy {
    private ScanViewportPolicy() { }
    public static boolean compact(int width, int height, float density) {
        return width > 0 && height > 0 && Math.min(width, height) / Math.max(.1f, density) < 600;
    }
    public static int phoneRailWidth(int width, float density) {
        return Math.min(Math.round(280 * density), Math.round(width * .40f));
    }
    public static int phoneDrawerHeight(int height, float density) {
        return Math.min(Math.round(260 * density), Math.round(height * .34f));
    }
}
