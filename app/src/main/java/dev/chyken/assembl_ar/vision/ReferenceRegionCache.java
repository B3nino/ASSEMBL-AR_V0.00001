package dev.chyken.assembl_ar.vision;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/** Optional geometry sidecar beside a content- and algorithm-versioned descriptor cache. */
final class ReferenceRegionCache {
    private static final int FORMAT = 1;

    private ReferenceRegionCache() { }

    static List<List<VisionPoint>> read(File file) {
        if (file == null) return null;
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            if (input.readInt() != FORMAT) return null;
            int count = input.readInt();
            if (count < 1 || count > 16) return null;
            List<List<VisionPoint>> regions = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int vertices = input.readInt();
                if (vertices < 3 || vertices > 8) return null;
                List<VisionPoint> polygon = new ArrayList<>();
                for (int j = 0; j < vertices; j++) {
                    double x = input.readDouble(), y = input.readDouble();
                    if (!Double.isFinite(x) || !Double.isFinite(y)
                            || x < 0 || x > 1 || y < 0 || y > 1) return null;
                    polygon.add(new VisionPoint(x, y));
                }
                regions.add(polygon);
            }
            return input.read() == -1 ? regions : null;
        } catch (IOException | IllegalArgumentException failure) {
            return null; // Missing/corrupt cache always falls back to the original photo.
        }
    }

    static void write(File file, List<List<VisionPoint>> regions) {
        if (file == null) return;
        File temporary = null;
        try {
            temporary = File.createTempFile("regions", ".tmp", file.getAbsoluteFile().getParentFile());
            try (DataOutputStream output = new DataOutputStream(new FileOutputStream(temporary))) {
                output.writeInt(FORMAT);
                output.writeInt(regions.size());
                for (List<VisionPoint> polygon : regions) {
                    output.writeInt(polygon.size());
                    for (VisionPoint point : polygon) {
                        output.writeDouble(point.getX());
                        output.writeDouble(point.getY());
                    }
                }
            }
            java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ignored) {
            // Cache writes are optional and cannot prevent recognition from starting.
        } finally {
            if (temporary != null) temporary.delete();
        }
    }
}
