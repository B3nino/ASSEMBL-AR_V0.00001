package dev.chyken.assembl_ar.vision;

/**
 * Immutable tuning parameters for the manuscript-defined OpenCV pipeline.
 *
 * <p>The manuscript deliberately leaves the numeric recognition thresholds to prototype
 * tuning. These defaults are conservative starting values for 720p/1080p CameraX frames and
 * every value that normally needs device-specific tuning is configurable through the builder.</p>
 */
public final class VisionConfig {
    private final int orbFeatureCount;
    private final float orbScaleFactor;
    private final int orbPyramidLevels;
    private final int orbEdgeThreshold;
    private final int orbFirstLevel;
    private final int orbWtaK;
    private final int orbPatchSize;
    private final int orbFastThreshold;
    private final double claheClipLimit;
    private final int claheTileGridWidth;
    private final int claheTileGridHeight;
    private final float knnRatioThreshold;
    private final float maximumHammingDistance;
    private final int minimumGoodMatches;
    private final int minimumInliers;
    private final double minimumInlierRatio;
    private final double homographyReprojectionThresholdPx;
    private final int homographyMaximumIterations;
    private final double homographyConfidence;
    private final double minimumCornerAreaRatio;
    private final double maximumCornerAreaRatio;
    private final double defaultOrientationToleranceDegrees;
    private final double maximumPoseReprojectionErrorPx;
    private final double cannyLowThreshold;
    private final double cannyHighThreshold;
    private final int houghGaussianKernelSize;
    private final double houghDp;
    private final double houghMinimumDistancePx;
    private final double houghAccumulatorThreshold;
    private final int houghMinimumRadiusPx;
    private final int houghMaximumRadiusPx;
    private final int minimumFallbackCircles;
    private final long minimumFrameIntervalMs;
    private final boolean latencyLoggingEnabled;

    private VisionConfig(Builder builder) {
        orbFeatureCount = builder.orbFeatureCount;
        orbScaleFactor = builder.orbScaleFactor;
        orbPyramidLevels = builder.orbPyramidLevels;
        orbEdgeThreshold = builder.orbEdgeThreshold;
        orbFirstLevel = builder.orbFirstLevel;
        orbWtaK = builder.orbWtaK;
        orbPatchSize = builder.orbPatchSize;
        orbFastThreshold = builder.orbFastThreshold;
        claheClipLimit = builder.claheClipLimit;
        claheTileGridWidth = builder.claheTileGridWidth;
        claheTileGridHeight = builder.claheTileGridHeight;
        knnRatioThreshold = builder.knnRatioThreshold;
        maximumHammingDistance = builder.maximumHammingDistance;
        minimumGoodMatches = builder.minimumGoodMatches;
        minimumInliers = builder.minimumInliers;
        minimumInlierRatio = builder.minimumInlierRatio;
        homographyReprojectionThresholdPx = builder.homographyReprojectionThresholdPx;
        homographyMaximumIterations = builder.homographyMaximumIterations;
        homographyConfidence = builder.homographyConfidence;
        minimumCornerAreaRatio = builder.minimumCornerAreaRatio;
        maximumCornerAreaRatio = builder.maximumCornerAreaRatio;
        defaultOrientationToleranceDegrees = builder.defaultOrientationToleranceDegrees;
        maximumPoseReprojectionErrorPx = builder.maximumPoseReprojectionErrorPx;
        cannyLowThreshold = builder.cannyLowThreshold;
        cannyHighThreshold = builder.cannyHighThreshold;
        houghGaussianKernelSize = builder.houghGaussianKernelSize;
        houghDp = builder.houghDp;
        houghMinimumDistancePx = builder.houghMinimumDistancePx;
        houghAccumulatorThreshold = builder.houghAccumulatorThreshold;
        houghMinimumRadiusPx = builder.houghMinimumRadiusPx;
        houghMaximumRadiusPx = builder.houghMaximumRadiusPx;
        minimumFallbackCircles = builder.minimumFallbackCircles;
        minimumFrameIntervalMs = builder.minimumFrameIntervalMs;
        latencyLoggingEnabled = builder.latencyLoggingEnabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static VisionConfig defaults() {
        return builder().build();
    }

    public int getOrbFeatureCount() { return orbFeatureCount; }
    public float getOrbScaleFactor() { return orbScaleFactor; }
    public int getOrbPyramidLevels() { return orbPyramidLevels; }
    public int getOrbEdgeThreshold() { return orbEdgeThreshold; }
    public int getOrbFirstLevel() { return orbFirstLevel; }
    public int getOrbWtaK() { return orbWtaK; }
    public int getOrbPatchSize() { return orbPatchSize; }
    public int getOrbFastThreshold() { return orbFastThreshold; }
    public double getClaheClipLimit() { return claheClipLimit; }
    public int getClaheTileGridWidth() { return claheTileGridWidth; }
    public int getClaheTileGridHeight() { return claheTileGridHeight; }
    public float getKnnRatioThreshold() { return knnRatioThreshold; }
    public float getMaximumHammingDistance() { return maximumHammingDistance; }
    public int getMinimumGoodMatches() { return minimumGoodMatches; }
    public int getMinimumInliers() { return minimumInliers; }
    public double getMinimumInlierRatio() { return minimumInlierRatio; }
    public double getHomographyReprojectionThresholdPx() { return homographyReprojectionThresholdPx; }
    public int getHomographyMaximumIterations() { return homographyMaximumIterations; }
    public double getHomographyConfidence() { return homographyConfidence; }
    public double getMinimumCornerAreaRatio() { return minimumCornerAreaRatio; }
    public double getMaximumCornerAreaRatio() { return maximumCornerAreaRatio; }
    public double getDefaultOrientationToleranceDegrees() { return defaultOrientationToleranceDegrees; }
    public double getMaximumPoseReprojectionErrorPx() { return maximumPoseReprojectionErrorPx; }
    public double getCannyLowThreshold() { return cannyLowThreshold; }
    public double getCannyHighThreshold() { return cannyHighThreshold; }
    public int getHoughGaussianKernelSize() { return houghGaussianKernelSize; }
    public double getHoughDp() { return houghDp; }
    public double getHoughMinimumDistancePx() { return houghMinimumDistancePx; }
    public double getHoughAccumulatorThreshold() { return houghAccumulatorThreshold; }
    public int getHoughMinimumRadiusPx() { return houghMinimumRadiusPx; }
    public int getHoughMaximumRadiusPx() { return houghMaximumRadiusPx; }
    public int getMinimumFallbackCircles() { return minimumFallbackCircles; }
    public long getMinimumFrameIntervalMs() { return minimumFrameIntervalMs; }
    public boolean isLatencyLoggingEnabled() { return latencyLoggingEnabled; }

    public static final class Builder {
        private int orbFeatureCount = 1_200;
        private float orbScaleFactor = 1.2f;
        private int orbPyramidLevels = 8;
        private int orbEdgeThreshold = 31;
        private int orbFirstLevel = 0;
        private int orbWtaK = 2;
        private int orbPatchSize = 31;
        private int orbFastThreshold = 20;
        private double claheClipLimit = 2.0;
        private int claheTileGridWidth = 8;
        private int claheTileGridHeight = 8;
        private float knnRatioThreshold = 0.75f;
        private float maximumHammingDistance = 64.0f;
        private int minimumGoodMatches = 12;
        private int minimumInliers = 8;
        private double minimumInlierRatio = 0.45;
        private double homographyReprojectionThresholdPx = 3.0;
        private int homographyMaximumIterations = 2_000;
        private double homographyConfidence = 0.995;
        private double minimumCornerAreaRatio = 0.002;
        private double maximumCornerAreaRatio = 0.95;
        private double defaultOrientationToleranceDegrees = 25.0;
        private double maximumPoseReprojectionErrorPx = 6.0;
        private double cannyLowThreshold = 80.0;
        private double cannyHighThreshold = 160.0;
        private int houghGaussianKernelSize = 5;
        private double houghDp = 1.2;
        private double houghMinimumDistancePx = 24.0;
        private double houghAccumulatorThreshold = 18.0;
        private int houghMinimumRadiusPx = 4;
        private int houghMaximumRadiusPx = 80;
        private int minimumFallbackCircles = 1;
        private long minimumFrameIntervalMs = 120L;
        private boolean latencyLoggingEnabled = true;

        public Builder orb(int featureCount, float scaleFactor, int pyramidLevels,
                           int edgeThreshold, int firstLevel, int wtaK,
                           int patchSize, int fastThreshold) {
            this.orbFeatureCount = featureCount;
            this.orbScaleFactor = scaleFactor;
            this.orbPyramidLevels = pyramidLevels;
            this.orbEdgeThreshold = edgeThreshold;
            this.orbFirstLevel = firstLevel;
            this.orbWtaK = wtaK;
            this.orbPatchSize = patchSize;
            this.orbFastThreshold = fastThreshold;
            return this;
        }

        /** Configures the contrast-limited normalization shared by references and live frames. */
        public Builder clahe(double clipLimit, int tileGridWidth, int tileGridHeight) {
            claheClipLimit = clipLimit;
            claheTileGridWidth = tileGridWidth;
            claheTileGridHeight = tileGridHeight;
            return this;
        }

        public Builder matching(float ratioThreshold, float maxHammingDistance,
                                int minGoodMatches) {
            this.knnRatioThreshold = ratioThreshold;
            this.maximumHammingDistance = maxHammingDistance;
            this.minimumGoodMatches = minGoodMatches;
            return this;
        }

        public Builder homography(int minInliers, double minInlierRatio,
                                  double reprojectionThresholdPx, int maximumIterations,
                                  double confidence) {
            this.minimumInliers = minInliers;
            this.minimumInlierRatio = minInlierRatio;
            this.homographyReprojectionThresholdPx = reprojectionThresholdPx;
            this.homographyMaximumIterations = maximumIterations;
            this.homographyConfidence = confidence;
            return this;
        }

        public Builder cornerAreaRange(double minimumRatio, double maximumRatio) {
            this.minimumCornerAreaRatio = minimumRatio;
            this.maximumCornerAreaRatio = maximumRatio;
            return this;
        }

        public Builder defaultOrientationToleranceDegrees(double toleranceDegrees) {
            this.defaultOrientationToleranceDegrees = toleranceDegrees;
            return this;
        }

        public Builder maximumPoseReprojectionErrorPx(double errorPx) {
            maximumPoseReprojectionErrorPx = errorPx;
            return this;
        }

        public Builder circularFallback(double cannyLow, double cannyHigh,
                                        int gaussianKernelSize, double dp,
                                        double minimumDistancePx,
                                        double accumulatorThreshold,
                                        int minimumRadiusPx, int maximumRadiusPx,
                                        int minimumCircles) {
            this.cannyLowThreshold = cannyLow;
            this.cannyHighThreshold = cannyHigh;
            this.houghGaussianKernelSize = gaussianKernelSize;
            this.houghDp = dp;
            this.houghMinimumDistancePx = minimumDistancePx;
            this.houghAccumulatorThreshold = accumulatorThreshold;
            this.houghMinimumRadiusPx = minimumRadiusPx;
            this.houghMaximumRadiusPx = maximumRadiusPx;
            this.minimumFallbackCircles = minimumCircles;
            return this;
        }

        public Builder minimumFrameIntervalMs(long intervalMs) {
            this.minimumFrameIntervalMs = intervalMs;
            return this;
        }

        public Builder latencyLoggingEnabled(boolean enabled) {
            this.latencyLoggingEnabled = enabled;
            return this;
        }

        public VisionConfig build() {
            require(orbFeatureCount > 0, "orbFeatureCount must be positive");
            require(orbScaleFactor > 1.0f && Float.isFinite(orbScaleFactor),
                    "orbScaleFactor must be finite and greater than 1");
            require(orbPyramidLevels > 0, "orbPyramidLevels must be positive");
            require(orbEdgeThreshold >= 0 && orbFirstLevel >= 0,
                    "ORB edge/first-level values are invalid");
            require(orbWtaK == 2,
                    "BF_HAMMING requires ORB WTA_K=2; Hamming2 is outside this pipeline");
            require(orbPatchSize >= 2 && orbFastThreshold >= 0,
                    "ORB patch/FAST thresholds are invalid");
            require(claheClipLimit > 0.0 && Double.isFinite(claheClipLimit),
                    "CLAHE clip limit must be finite and positive");
            require(claheTileGridWidth > 0 && claheTileGridHeight > 0,
                    "CLAHE tile grid dimensions must be positive");
            require(knnRatioThreshold > 0.0f && knnRatioThreshold < 1.0f,
                    "KNN ratio threshold must be between 0 and 1");
            require(maximumHammingDistance > 0.0f
                            && Float.isFinite(maximumHammingDistance),
                    "max Hamming distance must be finite and positive");
            require(minimumGoodMatches >= 4, "at least four good matches are required");
            require(minimumInliers >= 4, "at least four homography inliers are required");
            require(minimumInlierRatio > 0.0 && minimumInlierRatio <= 1.0,
                    "inlier ratio must be in (0, 1]");
            require(homographyReprojectionThresholdPx > 0.0
                            && Double.isFinite(homographyReprojectionThresholdPx),
                    "RANSAC reprojection threshold must be finite and positive");
            require(homographyMaximumIterations > 0, "RANSAC iterations must be positive");
            require(homographyConfidence > 0.0 && homographyConfidence < 1.0,
                    "RANSAC confidence must be in (0, 1)");
            require(minimumCornerAreaRatio >= 0.0
                            && maximumCornerAreaRatio > minimumCornerAreaRatio
                            && maximumCornerAreaRatio <= 1.0,
                    "corner area ratios are invalid");
            require(defaultOrientationToleranceDegrees >= 0.0
                            && defaultOrientationToleranceDegrees <= 90.0,
                    "orientation tolerance must be between 0 and 90 degrees");
            require(maximumPoseReprojectionErrorPx > 0.0
                            && Double.isFinite(maximumPoseReprojectionErrorPx),
                    "maximum pose reprojection error must be finite and positive");
            require(cannyLowThreshold >= 0.0 && cannyHighThreshold > cannyLowThreshold
                            && Double.isFinite(cannyLowThreshold)
                            && Double.isFinite(cannyHighThreshold),
                    "Canny thresholds are invalid");
            require(houghGaussianKernelSize > 0 && (houghGaussianKernelSize & 1) == 1,
                    "Gaussian kernel size must be a positive odd number");
            require(houghDp >= 1.0 && houghMinimumDistancePx > 0.0
                            && Double.isFinite(houghDp)
                            && Double.isFinite(houghMinimumDistancePx),
                    "Hough resolution/distance values are invalid");
            require(houghAccumulatorThreshold > 0.0
                            && Double.isFinite(houghAccumulatorThreshold),
                    "Hough accumulator threshold must be finite and positive");
            require(houghMinimumRadiusPx >= 0 && houghMaximumRadiusPx > houghMinimumRadiusPx,
                    "Hough radius range is invalid");
            require(minimumFallbackCircles > 0, "minimum fallback circles must be positive");
            require(minimumFrameIntervalMs >= 0L
                            && minimumFrameIntervalMs <= Long.MAX_VALUE / 1_000_000L,
                    "frame interval is outside the supported range");
            return new VisionConfig(this);
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new IllegalArgumentException(message);
            }
        }
    }
}
