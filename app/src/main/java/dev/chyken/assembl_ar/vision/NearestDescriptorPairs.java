package dev.chyken.assembl_ar.vision;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.DMatch;
import org.opencv.core.Mat;

/**
 * The two nearest Hamming neighbours of every query descriptor, identical to
 * {@code BFMatcher(NORM_HAMMING).knnMatch(query, train, pairs, 2)}.
 *
 * <p>The Java knnMatch binding materializes one native MatOfDMatch per query row. With about
 * 1,200 rows per reference variant and the catalog matched on every frame, that conversion, not
 * the distance computation, made each analyzed frame take seconds and expired the assembly
 * gates' frame-gap limits. {@link Core#batchDistance} is the routine BFMatcher itself calls;
 * reading its two result matrices once yields the same neighbours and distances.</p>
 */
final class NearestDescriptorPairs {
    private final int rows;
    private final int neighbours;
    private final int[] indices;
    private final int[] distances;
    private final int rowOffset;

    private NearestDescriptorPairs(int rows, int neighbours, int[] indices, int[] distances) {
        this(rows, neighbours, indices, distances, 0);
    }

    private NearestDescriptorPairs(int rows, int neighbours, int[] indices, int[] distances,
                                   int rowOffset) {
        this.rows = rows;
        this.neighbours = neighbours;
        this.indices = indices;
        this.distances = distances;
        this.rowOffset = rowOffset;
    }

    static NearestDescriptorPairs compute(Mat query, Mat train) {
        return compute(query,train,null);
    }
    /** Spatial gating changes the search domain only; callers still apply Lowe and RANSAC. */
    static NearestDescriptorPairs compute(Mat query, Mat train, Mat mask) {
        if (query.empty() || train.empty()) return new NearestDescriptorPairs(0, 0, new int[0], new int[0]);
        Mat distance = new Mat();
        Mat index = new Mat();
        try {
            // OpenCV clamps K to the train row count; a missing neighbour is reported as -1.
            if(mask==null)Core.batchDistance(query, train, distance, CvType.CV_32S, index, Core.NORM_HAMMING, 2);
            else Core.batchDistance(query,train,distance,CvType.CV_32S,index,Core.NORM_HAMMING,2,mask);
            int columns = index.cols();
            int[] indices = new int[query.rows() * columns];
            int[] distances = new int[indices.length];
            if (indices.length > 0) {
                index.get(0, 0, indices);
                distance.get(0, 0, distances);
            }
            return new NearestDescriptorPairs(query.rows(), columns, indices, distances);
        } finally {
            distance.release();
            index.release();
        }
    }

    /** One entry per query row, as knnMatch returns with compactResult=false. */
    int size() { return rows; }

    /** A view of contiguous query rows; neighbour indices still address the same train frame. */
    NearestDescriptorPairs slice(int firstRow, int rowCount) {
        if (firstRow < 0 || rowCount < 0 || firstRow > rows - rowCount) {
            throw new IndexOutOfBoundsException("Invalid nearest-pair row slice");
        }
        return new NearestDescriptorPairs(rowCount, neighbours, indices, distances,
                rowOffset + firstRow);
    }

    boolean hasTwo(int row) {
        int offset = (rowOffset + row) * neighbours;
        return neighbours >= 2 && indices[offset] >= 0
                && indices[offset + 1] >= 0;
    }

    /** Hamming distances are integers; knnMatch reports them as the same float values. */
    float bestDistance(int row) { return distances[(rowOffset + row) * neighbours]; }

    float secondDistance(int row) { return distances[(rowOffset + row) * neighbours + 1]; }

    DMatch best(int row) {
        return new DMatch(row, indices[(rowOffset + row) * neighbours], 0,
                bestDistance(row));
    }
}
