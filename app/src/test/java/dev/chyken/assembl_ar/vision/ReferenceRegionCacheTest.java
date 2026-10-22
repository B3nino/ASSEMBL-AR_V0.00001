package dev.chyken.assembl_ar.vision;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public final class ReferenceRegionCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static List<List<VisionPoint>> regions() {
        return Arrays.asList(
                Arrays.asList(new VisionPoint(.1, .2), new VisionPoint(.4, .2),
                        new VisionPoint(.4, .8), new VisionPoint(.1, .8)),
                Arrays.asList(new VisionPoint(.6, .2), new VisionPoint(.9, .2),
                        new VisionPoint(.9, .8), new VisionPoint(.6, .8)));
    }

    @Test public void retainsIndependentSensorAndMountPolygonsExactly() throws Exception {
        File file = new File(temporary.getRoot(), "photo.asor.regions1");
        ReferenceRegionCache.write(file, regions());
        assertEquals(regions(), ReferenceRegionCache.read(file));
    }

    @Test public void replacesExistingGeometryWithoutLeavingTemporaryFiles() throws Exception {
        File file = temporary.newFile();
        ReferenceRegionCache.write(file, regions());
        List<List<VisionPoint>> replacement = regions().subList(0, 1);
        ReferenceRegionCache.write(file, replacement);
        assertEquals(replacement, ReferenceRegionCache.read(file));
        assertEquals(1, temporary.getRoot().list().length);
    }

    @Test public void corruptTruncatedAndOversizedDataFallBackToPhoto() throws Exception {
        File file = temporary.newFile();
        for (int count : new int[]{-1, 0, 17, Integer.MAX_VALUE}) {
            try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
                out.writeInt(1); out.writeInt(count);
            }
            assertNull(ReferenceRegionCache.read(file));
        }
        ReferenceRegionCache.write(file, regions());
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.setLength(file.length() - 1);
        }
        assertNull(ReferenceRegionCache.read(file));
    }

    @Test public void nonFiniteOutOfBoundsAndUnknownFormatAreRejected() throws Exception {
        File file = temporary.newFile();
        for (double coordinate : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -.01, 1.01}) {
            try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
                out.writeInt(1); out.writeInt(1); out.writeInt(4);
                out.writeDouble(coordinate); out.writeDouble(.2);
            }
            assertNull(ReferenceRegionCache.read(file));
        }
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeInt(99);
        }
        assertNull(ReferenceRegionCache.read(file));
    }

    @Test public void unavailableStorageIsOptional() throws Exception {
        assertNull(ReferenceRegionCache.read(null));
        ReferenceRegionCache.write(null, regions());
        File file = new File(temporary.getRoot(), "missing/folder/regions");
        ReferenceRegionCache.write(file, regions());
        assertNull(ReferenceRegionCache.read(file));
    }
}
