package dev.chyken.assembl_ar.vision;

import org.opencv.core.Mat;

import java.util.List;

/** Additional veto: a gold USB shell beside a blue PCB is not a yellow gearbox. */
final class LooseGearMotorEvidence {
    static boolean accepts(Mat hsv, List<VisionPoint> corners) {
        VisionPoint first = corners.get(0), second = corners.get(1), third = corners.get(2);
        double length = first.distanceTo(second), width = second.distanceTo(third);
        if (length <= 0 || width <= 0) return false;
        double ax = (second.getX() - first.getX()) / length;
        double ay = (second.getY() - first.getY()) / length;
        double cx = 0, cy = 0;
        for (VisionPoint p : corners) { cx += p.getX() / 4; cy += p.getY() / 4; }
        double reach = .75 * (length + width);
        int left = Math.max(0, (int) Math.floor(cx - reach));
        int right = Math.min(hsv.cols() - 1, (int) Math.ceil(cx + reach));
        int top = Math.max(0, (int) Math.floor(cy - reach));
        int bottom = Math.min(hsv.rows() - 1, (int) Math.ceil(cy + reach));
        byte[] pixels = new byte[(int) hsv.total() * 3];
        hsv.get(0, 0, pixels);
        int expanded = 0, blue = 0, roomBlue = 0, body = 0, yellow = 0;
        for (int y = top; y <= bottom; y++) for (int x = left; x <= right; x++) {
            double u = ((x - cx) * ax + (y - cy) * ay) / length;
            double v = (-(x - cx) * ay + (y - cy) * ax) / width;
            if (Math.abs(u) > .75 || Math.abs(v) > .75) continue;
            expanded++;
            int i = (y * hsv.cols() + x) * 3;
            int h = pixels[i] & 255, s = pixels[i + 1] & 255, value = pixels[i + 2] & 255;
            if (h >= 91 && h <= 137 && s >= 65 && value >= 32 && s * value >= 30 * 255) blue++;
            // Redmi room light leaves the Nano's PCB dark and less saturated.
            // The original chroma-product floor missed it next to the gold USB shell.
            // This is an additional blue-neighbourhood veto, never positive motor evidence.
            if (h >= 85 && h <= 137 && s >= 55 && value >= 24) roomBlue++;
            if (Math.abs(u) <= .5 && Math.abs(v) <= .5) {
                body++;
                if (h >= 17 && h <= 43 && s >= 160 && value >= 62) yellow++;
            }
        }
        return expanded > 0 && body > 0 && blue / (double) expanded <= .05
                && roomBlue / (double) expanded <= .05
                && yellow / (double) body >= .20;
    }
}
