package dev.chyken.assembl_ar.vision;

import org.opencv.core.Core;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.List;

/** Immutable concatenation of reference rows; live descriptors are never retained. */
final class PreparedDescriptorBatch implements AutoCloseable {
    private final Mat query = new Mat();
    private final int[] rowCounts;

    PreparedDescriptorBatch(List<ReferenceDatabase.PreparedOrbVariant> variants) {
        rowCounts = new int[variants.size()];
        List<Mat> descriptors = new ArrayList<>(variants.size());
        for (int index = 0; index < variants.size(); index++) {
            Mat rows = variants.get(index).descriptors;
            rowCounts[index] = rows.rows();
            descriptors.add(rows);
        }
        try {
            Core.vconcat(descriptors, query);
        } catch (RuntimeException | Error failure) {
            query.release();
            throw failure;
        }
    }

    List<NearestDescriptorPairs> compute(Mat liveDescriptors) {
        NearestDescriptorPairs all = NearestDescriptorPairs.compute(query, liveDescriptors);
        List<NearestDescriptorPairs> results = new ArrayList<>(rowCounts.length);
        int firstRow = 0;
        for (int rows : rowCounts) {
            results.add(all.slice(firstRow, rows));
            firstRow += rows;
        }
        return results;
    }

    @Override public void close() { query.release(); }
}
