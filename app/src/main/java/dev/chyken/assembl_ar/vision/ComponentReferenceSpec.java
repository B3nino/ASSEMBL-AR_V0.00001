package dev.chyken.assembl_ar.vision;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable registration record for one reference angle of a NEOBOT component.
 *
 * <p>References may be prepared from a bundled image asset, an app-private administrator image,
 * or an administrator-generated descriptor blob. A compatible descriptor blob takes priority;
 * image data remains the source-of-truth fallback for rebuilding stale or corrupt blobs. Multiple
 * specs may share a component id while using unique reference ids.</p>
 */
public final class ComponentReferenceSpec {
    public enum ShapeHint {
        TEXTURED_PLANAR,
        CIRCULAR_HARDWARE
    }

    private final String referenceId;
    private final String componentId;
    private final String displayName;
    private final String jpegAssetPath;
    private final String localImageFilePath;
    private final byte[] adminDescriptorBlob;
    private final double physicalWidthMm;
    private final double physicalHeightMm;
    private final ShapeHint shapeHint;
    private final double minimumCircleRadiusRatio;
    private final double maximumCircleRadiusRatio;
    private final List<VisionPoint> planarCornersNormalized;
    private final List<List<VisionPoint>> featureRegionsNormalized;
    private final int maximumImageDimension;
    private final boolean descriptorRegionNormalizationEnabled;
    private final boolean polarityAnchorsVerified;
    private final int assemblyStepNumber;
    private final boolean installedBracketView;
    private final boolean fastAcquisitionView;
    private final boolean planarInlierPoseEnabled;
    private final boolean automaticBoardGeometry;
    private final boolean bottomFace;

    private ComponentReferenceSpec(Builder builder) {
        referenceId = builder.referenceId;
        componentId = builder.componentId;
        displayName = builder.displayName;
        jpegAssetPath = builder.jpegAssetPath;
        localImageFilePath = builder.localImageFilePath;
        adminDescriptorBlob = builder.adminDescriptorBlob == null
                ? null
                : builder.adminDescriptorBlob.clone();
        physicalWidthMm = builder.physicalWidthMm;
        physicalHeightMm = builder.physicalHeightMm;
        shapeHint = builder.shapeHint;
        minimumCircleRadiusRatio = builder.minimumCircleRadiusRatio;
        maximumCircleRadiusRatio = builder.maximumCircleRadiusRatio;
        planarCornersNormalized = Collections.unmodifiableList(
                new ArrayList<>(builder.planarCornersNormalized));
        featureRegionsNormalized = immutableFeatureRegions(
                builder.featureRegionsNormalized);
        maximumImageDimension = builder.maximumImageDimension;
        descriptorRegionNormalizationEnabled = builder.descriptorRegionNormalizationEnabled;
        polarityAnchorsVerified = builder.polarityAnchorsVerified;
        assemblyStepNumber = builder.assemblyStepNumber;
        installedBracketView = builder.installedBracketView;
        fastAcquisitionView = builder.fastAcquisitionView;
        planarInlierPoseEnabled = builder.planarInlierPoseEnabled;
        automaticBoardGeometry = builder.automaticBoardGeometry;
        bottomFace = builder.bottomFace;
    }

    public static Builder builder(String referenceId, String componentId, String displayName,
                                  double physicalWidthMm, double physicalHeightMm) {
        return new Builder(referenceId, componentId, displayName,
                physicalWidthMm, physicalHeightMm);
    }

    public String getReferenceId() { return referenceId; }
    public String getComponentId() { return componentId; }
    public String getDisplayName() { return displayName; }
    public String getJpegAssetPath() { return jpegAssetPath; }
    public String getLocalImageFilePath() { return localImageFilePath; }
    public byte[] getAdminDescriptorBlob() {
        return adminDescriptorBlob == null ? null : adminDescriptorBlob.clone();
    }
    /** Non-copying provenance check for hot recognition paths. */
    public boolean isAdministratorReference() {
        return localImageFilePath != null || adminDescriptorBlob != null;
    }
    public double getPhysicalWidthMm() { return physicalWidthMm; }
    public double getPhysicalHeightMm() { return physicalHeightMm; }
    public ShapeHint getShapeHint() { return shapeHint; }
    public double getMinimumCircleRadiusRatio() { return minimumCircleRadiusRatio; }
    public double getMaximumCircleRadiusRatio() { return maximumCircleRadiusRatio; }
    public List<VisionPoint> getPlanarCornersNormalized() { return planarCornersNormalized; }
    /**
     * Source-image-normalized polygons used only for descriptor extraction. An empty list means
     * the complete registered planar quadrilateral remains eligible.
     */
    public List<List<VisionPoint>> getFeatureRegionsNormalized() {
        return featureRegionsNormalized;
    }
    public int getMaximumImageDimension() { return maximumImageDimension; }
    /** Whether ORB preparation should crop and normalize the authored descriptor region. */
    public boolean isDescriptorRegionNormalizationEnabled() {
        return descriptorRegionNormalizationEnabled;
    }
    public boolean hasVerifiedPolarityAnchors() { return polarityAnchorsVerified; }
    /** Zero is unrestricted; positive values restrict this reference to one assembly step. */
    public int getAssemblyStepNumber() { return assemblyStepNumber; }
    /** Installed face crops are eligible only during a board-relative bracket checkpoint. */
    public boolean isInstalledBracketView() { return installedBracketView; }
    /** A representative view tried before the complete catalog for quick board acquisition. */
    public boolean isFastAcquisitionView() { return fastAcquisitionView; }
    /** Fits a planar pose from RANSAC inliers mapped through this view's authored corners. */
    public boolean isPlanarInlierPoseEnabled() { return planarInlierPoseEnabled; }
    public boolean isAutomaticBoardGeometry() { return automaticBoardGeometry; }
    public boolean isBottomFace() { return bottomFace; }

    public boolean acceptsCircleRadiusRatio(double radiusRatio) {
        return shapeHint == ShapeHint.CIRCULAR_HARDWARE
                && radiusRatio >= minimumCircleRadiusRatio
                && radiusRatio <= maximumCircleRadiusRatio;
    }

    public static final class Builder {
        private final String referenceId;
        private final String componentId;
        private final String displayName;
        private final double physicalWidthMm;
        private final double physicalHeightMm;
        private String jpegAssetPath;
        private String localImageFilePath;
        private byte[] adminDescriptorBlob;
        private ShapeHint shapeHint = ShapeHint.TEXTURED_PLANAR;
        private double minimumCircleRadiusRatio = 0.005;
        private double maximumCircleRadiusRatio = 0.20;
        private boolean mirroredPlanarCorners;
        private boolean extrapolatedPlanarCorners;
        private int maximumImageDimension;
        private boolean descriptorRegionNormalizationEnabled;
        private boolean polarityAnchorsVerified = true;
        private int assemblyStepNumber;
        private boolean installedBracketView;
        private boolean fastAcquisitionView;
        private boolean planarInlierPoseEnabled;
        private boolean automaticBoardGeometry;
        private boolean bottomFace;
        private List<VisionPoint> planarCornersNormalized = Arrays.asList(
                new VisionPoint(0.0, 0.0),
                new VisionPoint(1.0, 0.0),
                new VisionPoint(1.0, 1.0),
                new VisionPoint(0.0, 1.0));
        private List<List<VisionPoint>> featureRegionsNormalized = Collections.emptyList();

        private Builder(String referenceId, String componentId, String displayName,
                        double physicalWidthMm, double physicalHeightMm) {
            this.referenceId = requireText(referenceId, "referenceId");
            this.componentId = requireText(componentId, "componentId");
            this.displayName = requireText(displayName, "displayName");
            if (!(physicalWidthMm > 0.0) || !(physicalHeightMm > 0.0)
                    || !Double.isFinite(physicalWidthMm) || !Double.isFinite(physicalHeightMm)) {
                throw new IllegalArgumentException("Physical dimensions must be finite and positive");
            }
            this.physicalWidthMm = physicalWidthMm;
            this.physicalHeightMm = physicalHeightMm;
        }

        public Builder jpegAssetPath(String path) {
            jpegAssetPath = requireText(path, "jpegAssetPath");
            return this;
        }

        /** Limits high-resolution photo preparation without changing normalized corner geometry. */
        public Builder maximumImageDimension(int pixels) {
            if (pixels < 256 || pixels > 4096) {
                throw new IllegalArgumentException("Image dimension must be between 256 and 4096");
            }
            maximumImageDimension = pixels;
            return this;
        }

        /**
         * Opts a small target into crop-normalized descriptor preparation. Large board and legacy
         * references retain their proven full-image preprocessing unless explicitly migrated.
         */
        public Builder descriptorRegionNormalization(boolean enabled) {
            descriptorRegionNormalizationEnabled = enabled;
            return this;
        }

        /** New views must not inherit another crop's terminal coordinates without verification. */
        public Builder polarityAnchorsVerified(boolean verified) {
            polarityAnchorsVerified = verified;
            return this;
        }

        /**
         * Limits descriptor extraction to a union of source-image-normalized polygons. These
         * regions never replace the full planar corners used for homography projection and pose.
         */
        public Builder featureRegionsNormalized(List<List<VisionPoint>> regions) {
            featureRegionsNormalized = immutableFeatureRegions(regions);
            return this;
        }

        /** Zero leaves the reference eligible for every step; 1..18 scopes it to one step. */
        public Builder assemblyStepNumber(int stepNumber) {
            if (stepNumber < 0 || stepNumber > 18) {
                throw new IllegalArgumentException(
                        "Assembly step number must be between 0 and 18");
            }
            assemblyStepNumber = stepNumber;
            return this;
        }

        public Builder installedBracketView(boolean enabled) {
            installedBracketView = enabled;
            return this;
        }

        public Builder fastAcquisitionView(boolean enabled) {
            fastAcquisitionView = enabled;
            return this;
        }

        public Builder planarInlierPose(boolean enabled) {
            planarInlierPoseEnabled = enabled;
            return this;
        }

        /** Raw Phase-1 photo: detect its board perimeter before descriptor preparation. */
        public Builder automaticBoardGeometry(boolean bottom) {
            automaticBoardGeometry = true;
            bottomFace = bottom;
            return this;
        }

        /** Supplies the app-private source image behind an administrator descriptor blob. */
        public Builder localImageFilePath(String path) {
            localImageFilePath = requireText(path, "localImageFilePath");
            return this;
        }

        public Builder adminDescriptorBlob(byte[] blob) {
            if (blob == null || blob.length == 0) {
                throw new IllegalArgumentException("Descriptor blob cannot be empty");
            }
            adminDescriptorBlob = blob.clone();
            return this;
        }

        public Builder shapeHint(ShapeHint hint) {
            shapeHint = Objects.requireNonNull(hint, "hint");
            return this;
        }

        public Builder circularHardware(double minimumRadiusRatio, double maximumRadiusRatio) {
            shapeHint = ShapeHint.CIRCULAR_HARDWARE;
            minimumCircleRadiusRatio = minimumRadiusRatio;
            maximumCircleRadiusRatio = maximumRadiusRatio;
            return this;
        }

        /**
         * Sets the registered physical rectangle's corners within the JPEG, in TL, TR, BR, BL
         * order. This supports reference photos whose component does not fill the whole image.
         */
        public Builder planarReferenceCornersNormalized(VisionPoint topLeft,
                                                        VisionPoint topRight,
                                                        VisionPoint bottomRight,
                                                        VisionPoint bottomLeft) {
            planarCornersNormalized = Arrays.asList(
                    Objects.requireNonNull(topLeft, "topLeft"),
                    Objects.requireNonNull(topRight, "topRight"),
                    Objects.requireNonNull(bottomRight, "bottomRight"),
                    Objects.requireNonNull(bottomLeft, "bottomLeft"));
            mirroredPlanarCorners = false;
            extrapolatedPlanarCorners = false;
            return this;
        }

        /**
         * Sets the complete target plane when a tightly cropped source leaves one or more known
         * physical corners just outside the image. Coordinates remain in normalized source-image
         * units, but may extend to [-1, 2]. The descriptor mask is clipped to the source image;
         * homography projection retains the complete physical outline.
         */
        public Builder extrapolatedPlanarReferenceCornersNormalized(
                VisionPoint topLeft,
                VisionPoint topRight,
                VisionPoint bottomRight,
                VisionPoint bottomLeft) {
            planarCornersNormalized = Arrays.asList(
                    Objects.requireNonNull(topLeft, "topLeft"),
                    Objects.requireNonNull(topRight, "topRight"),
                    Objects.requireNonNull(bottomRight, "bottomRight"),
                    Objects.requireNonNull(bottomLeft, "bottomLeft"));
            mirroredPlanarCorners = false;
            extrapolatedPlanarCorners = true;
            return this;
        }

        /**
         * Sets the source-image positions of canonical TL, TR, BR, BL for the opposite face of a
         * planar component. The resulting negative winding is deliberate: it preserves the
         * component's front-face handedness when the camera observes its horizontally reflected
         * back face. Use the ordinary setter for every non-mirrored reference.
         */
        public Builder mirroredPlanarReferenceCornersNormalized(
                VisionPoint canonicalTopLeft,
                VisionPoint canonicalTopRight,
                VisionPoint canonicalBottomRight,
                VisionPoint canonicalBottomLeft) {
            planarCornersNormalized = Arrays.asList(
                    Objects.requireNonNull(canonicalTopLeft, "canonicalTopLeft"),
                    Objects.requireNonNull(canonicalTopRight, "canonicalTopRight"),
                    Objects.requireNonNull(canonicalBottomRight, "canonicalBottomRight"),
                    Objects.requireNonNull(canonicalBottomLeft, "canonicalBottomLeft"));
            mirroredPlanarCorners = true;
            extrapolatedPlanarCorners = false;
            return this;
        }

        public ComponentReferenceSpec build() {
            if (installedBracketView && (assemblyStepNumber != 2
                    || !"motor_bracket".equals(componentId))) {
                throw new IllegalStateException("Installed bracket views require motor_bracket in step 2");
            }
            if (jpegAssetPath == null && localImageFilePath == null
                    && adminDescriptorBlob == null) {
                throw new IllegalStateException(
                        "An image source or administrator descriptor blob is required");
            }
            if (shapeHint == ShapeHint.CIRCULAR_HARDWARE
                    && (!(minimumCircleRadiusRatio >= 0.0)
                    || !(maximumCircleRadiusRatio > minimumCircleRadiusRatio)
                    || maximumCircleRadiusRatio > 0.5)) {
                throw new IllegalArgumentException(
                        "Circle radius ratios must satisfy 0 <= min < max <= 0.5");
            }
            validatePlanarCorners(planarCornersNormalized, mirroredPlanarCorners,
                    extrapolatedPlanarCorners);
            return new ComponentReferenceSpec(this);
        }

        private static void validatePlanarCorners(
                List<VisionPoint> corners,
                boolean mirroredPlanarCorners,
                boolean extrapolatedPlanarCorners) {
            if (corners == null || corners.size() != 4) {
                throw new IllegalArgumentException("Exactly four planar corners are required");
            }
            for (VisionPoint point : corners) {
                double minimum = extrapolatedPlanarCorners ? -1.0 : 0.0;
                double maximum = extrapolatedPlanarCorners ? 2.0 : 1.0;
                if (point.getX() < minimum || point.getX() > maximum
                        || point.getY() < minimum || point.getY() > maximum) {
                    throw new IllegalArgumentException(
                            extrapolatedPlanarCorners
                                    ? "Extrapolated planar corners must remain within [-1, 2]"
                                    : "Planar reference corners must be normalized to [0, 1]");
                }
            }
            for (int first = 0; first < corners.size(); first++) {
                for (int second = first + 1; second < corners.size(); second++) {
                    if (corners.get(first).distanceTo(corners.get(second)) <= 1e-8) {
                        throw new IllegalArgumentException(
                                "Planar reference corners must be distinct");
                    }
                }
            }
            double crossSign = 0.0;
            for (int index = 0; index < corners.size(); index++) {
                VisionPoint a = corners.get(index);
                VisionPoint b = corners.get((index + 1) % corners.size());
                VisionPoint c = corners.get((index + 2) % corners.size());
                double cross = (b.getX() - a.getX()) * (c.getY() - b.getY())
                        - (b.getY() - a.getY()) * (c.getX() - b.getX());
                if (Math.abs(cross) <= 1e-8) {
                    throw new IllegalArgumentException(
                            "Planar reference corners cannot be collinear");
                }
                if (crossSign == 0.0) {
                    crossSign = Math.signum(cross);
                } else if (Math.signum(cross) != crossSign) {
                    throw new IllegalArgumentException(
                            "Planar reference corners must form a convex quadrilateral");
                }
            }
            double signedArea = 0.0;
            for (int index = 0; index < corners.size(); index++) {
                VisionPoint current = corners.get(index);
                VisionPoint next = corners.get((index + 1) % corners.size());
                signedArea += current.getX() * next.getY() - next.getX() * current.getY();
            }
            if (mirroredPlanarCorners ? !(signedArea < -1e-8) : !(signedArea > 1e-8)) {
                throw new IllegalArgumentException(
                        mirroredPlanarCorners
                                ? "Mirrored planar corners must have negative canonical winding"
                                : "Planar corners must be distinct and ordered TL, TR, BR, BL");
            }
        }

        private static String requireText(String value, String label) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(label + " cannot be blank");
            }
            return value.trim();
        }
    }

    private static List<List<VisionPoint>> immutableFeatureRegions(
            List<List<VisionPoint>> regions) {
        Objects.requireNonNull(regions, "featureRegionsNormalized");
        if (regions.isEmpty()) {
            return Collections.emptyList();
        }
        List<List<VisionPoint>> copy = new ArrayList<>(regions.size());
        for (List<VisionPoint> region : regions) {
            if (region == null || region.size() < 3 || region.size() > 8) {
                throw new IllegalArgumentException(
                        "Each feature region must contain between 3 and 8 points");
            }
            List<VisionPoint> polygon = new ArrayList<>(region.size());
            for (VisionPoint point : region) {
                if (point == null
                        || !Double.isFinite(point.getX())
                        || !Double.isFinite(point.getY())
                        || point.getX() < 0.0 || point.getX() > 1.0
                        || point.getY() < 0.0 || point.getY() > 1.0) {
                    throw new IllegalArgumentException(
                            "Feature-region points must be normalized to [0, 1]");
                }
                polygon.add(point);
            }
            copy.add(Collections.unmodifiableList(polygon));
        }
        return Collections.unmodifiableList(copy);
    }
}
