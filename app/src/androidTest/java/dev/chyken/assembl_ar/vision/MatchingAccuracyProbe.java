package dev.chyken.assembl_ar.vision;

import org.opencv.core.Mat;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.CLAHE;

/**
 * Section 3.3.1 Test A matching accuracy for one registered view: descriptors passing Lowe's
 * ratio test divided by all attempted two-nearest-neighbour matches, using the production
 * frame preparation (CLAHE, ORB) and the prepared reference descriptors.
 */
public final class MatchingAccuracyProbe {
    public static final class Counts {
        public final int attempted;
        public final int good;

        Counts(int attempted, int good) {
            this.attempted = attempted;
            this.good = good;
        }

        public double accuracy() { return attempted == 0 ? 0.0 : good / (double) attempted; }
    }

    private MatchingAccuracyProbe() { }

    public static Counts measure(ReferenceDatabase database, VisionConfig config, Mat gray,
                                 String referenceId) {
        Mat normalized = new Mat();
        Mat descriptors = new Mat();
        MatOfKeyPoint keypoints = new MatOfKeyPoint();
        ORB orb = OpenCvVisionUtils.createOrb(config);
        CLAHE clahe = OpenCvVisionUtils.createClahe(config);
        try {
            clahe.apply(gray, normalized);
            orb.detectAndCompute(normalized, new Mat(), keypoints, descriptors);
            for (ReferenceDatabase.PreparedReference reference : database.preparedReferences()) {
                if (!reference.spec.getReferenceId().equals(referenceId)) continue;
                for (ReferenceDatabase.PreparedOrbVariant variant : reference.orbVariants()) {
                    if (variant.photometricVariant != ReferenceDatabase.PhotometricVariant.NORMALIZED
                            || !variant.hasOrbData()) continue;
                    NearestDescriptorPairs pairs = NearestDescriptorPairs.compute(variant.descriptors, descriptors);
                    int attempted = 0, good = 0;
                    for (int row = 0; row < pairs.size(); row++) {
                        if (!pairs.hasTwo(row)) continue;
                        attempted++;
                        float best = pairs.bestDistance(row);
                        if (best <= config.getMaximumHammingDistance()
                                && best < config.getKnnRatioThreshold() * pairs.secondDistance(row)) good++;
                    }
                    return new Counts(attempted, good);
                }
            }
            return new Counts(0, 0);
        } finally {
            orb.clear();
            clahe.clear();
            normalized.release();
            descriptors.release();
            keypoints.release();
        }
    }
}
