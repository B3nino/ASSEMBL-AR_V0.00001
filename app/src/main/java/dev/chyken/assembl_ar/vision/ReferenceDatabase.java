package dev.chyken.assembl_ar.vision;

import android.content.res.AssetManager;
import android.util.Log;

import org.opencv.core.KeyPoint;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.features2d.ORB;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Prepared, fully offline component references loaded from configured asset paths or stored
 * administrator descriptor blobs. The owner must close this database when recognition ends.
 */
public final class ReferenceDatabase implements AutoCloseable {
    /**
     * Bump whenever image-backed descriptor preparation changes. Descriptor-cache filenames carry
     * this value so a cache produced by the old full-photo pipeline cannot silently bypass ROI
     * normalization.
     */
    private static final int REFERENCE_PREPARATION_VERSION = 2;
    private static final double ROI_PADDING_RATIO = 0.05;

    private final List<PreparedReference> references;
    private final Set<String> componentIds;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

    private ReferenceDatabase(List<PreparedReference> references) {
        this.references = Collections.unmodifiableList(new ArrayList<>(references));
        Set<String> ids = new LinkedHashSet<>();
        for (PreparedReference reference : references) {
            ids.add(reference.spec.getComponentId());
        }
        componentIds = Collections.unmodifiableSet(ids);
    }

    /** OpenCV must be initialized before this method is called. */
    public static ReferenceDatabase load(AssetManager assetManager,
                                         List<ComponentReferenceSpec> specs,
                                         VisionConfig config) throws IOException {
        return load(assetManager, specs, config, null);
    }

    /**
     * Loads the offline catalog and optionally persists versioned ORB blobs in app-private
     * storage. A fresh install needs no user input; later sessions avoid recomputing descriptors.
     */
    public static ReferenceDatabase load(AssetManager assetManager,
                                         List<ComponentReferenceSpec> specs,
                                         VisionConfig config,
                                         File descriptorCacheDirectory) throws IOException {
        if (assetManager == null) {
            throw new IllegalArgumentException("AssetManager is required");
        }
        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("At least one component reference is required");
        }
        if (config == null) {
            throw new IllegalArgumentException("VisionConfig is required");
        }

        ORB orb = OpenCvVisionUtils.createOrb(config);
        CLAHE clahe = OpenCvVisionUtils.createClahe(config);
        List<PreparedReference> prepared = new ArrayList<>();
        Set<String> referenceIds = new HashSet<>();
        try {
            for (ComponentReferenceSpec spec : specs) {
                if (spec == null) {
                    throw new IllegalArgumentException("Reference specs cannot contain null");
                }
                if (!referenceIds.add(spec.getReferenceId())) {
                    throw new IllegalArgumentException(
                            "Duplicate reference id: " + spec.getReferenceId());
                }
                try {
                    PreparedReference ref = prepareReference(
                            assetManager, spec, orb, clahe, config, descriptorCacheDirectory);
                    prepared.add(ref);
                } catch (IOException | RuntimeException perSpecFailure) {
                    String assetPath = spec == null ? "unknown" : spec.getJpegAssetPath();
                    Log.e("ReferenceDatabase", "Skipping reference due to preparation failure: "
                            + spec.getReferenceId() + " (asset=" + assetPath + ")",
                            perSpecFailure);
                    // skip this reference and continue with others
                }
            }
            if (prepared.isEmpty()) {
                throw new IOException("No valid references available for offline recognition");
            }
            return new ReferenceDatabase(prepared);
        } catch (IOException | RuntimeException failure) {
            for (PreparedReference reference : prepared) {
                reference.release();
            }
            throw failure;
        } finally {
            clahe.clear();
            orb.clear();
        }
    }

    public int size() {
        lifecycleLock.readLock().lock();
        try {
            ensureOpen();
            return references.size();
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    public Set<String> getComponentIds() {
        lifecycleLock.readLock().lock();
        try {
            ensureOpen();
            return componentIds;
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    List<PreparedReference> preparedReferences() {
        lifecycleLock.readLock().lock();
        try {
            ensureOpen();
            return references;
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    boolean containsComponent(String componentId) {
        lifecycleLock.readLock().lock();
        try {
            ensureOpen();
            return componentIds.contains(componentId);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    void acquireForRecognition() {
        lifecycleLock.readLock().lock();
        try {
            ensureOpen();
        } catch (RuntimeException failure) {
            lifecycleLock.readLock().unlock();
            throw failure;
        }
    }

    void releaseAfterRecognition() {
        lifecycleLock.readLock().unlock();
    }

    @Override
    public void close() {
        lifecycleLock.writeLock().lock();
        try {
            if (closed.compareAndSet(false, true)) {
                for (PreparedReference reference : references) {
                    reference.release();
                }
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("ReferenceDatabase is closed");
        }
    }

    private static PreparedReference prepareReference(AssetManager assetManager,
                                                      ComponentReferenceSpec spec,
                                                      ORB orb,
                                                      CLAHE clahe,
                                                      VisionConfig config,
                                                      File descriptorCacheDirectory) throws IOException {
        byte[] storedBlob = spec.getAdminDescriptorBlob();
        // A current precompiled blob avoids repeating ORB extraction at startup. Legacy/corrupt
        // administrator blobs deliberately fall through to their retained app-private image.
        // ASOR administrator blobs already store keypoints in complete-source coordinates, so
        // they remain compatible; only image-backed cache filenames need the preparation token.
        if (storedBlob != null) {
            try {
                return preparedFromBlob(spec, storedBlob, config);
            } catch (IllegalArgumentException staleOrCorruptBlob) {
                if (spec.getJpegAssetPath() == null && spec.getLocalImageFilePath() == null) {
                    throw staleOrCorruptBlob;
                }
            }
        }

        byte[] imageBytes = readReferenceImageBytes(assetManager, spec);
        if (imageBytes == null) {
            throw new IllegalArgumentException(
                    "Reference has neither compatible descriptor data nor an image source: "
                            + spec.getReferenceId());
        }
        String sourceLabel = spec.getJpegAssetPath() != null
                ? spec.getJpegAssetPath() : spec.getLocalImageFilePath();
        File cacheFile = cacheFile(descriptorCacheDirectory, spec, imageBytes);
        if (spec.isAutomaticBoardGeometry()) {
            spec = BoardPhotoGeometry.resolve(spec, imageBytes, cacheFile);
        }
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase2AssetCatalog.PREFIX))
            spec=Phase2PhotoGeometry.resolve(spec,imageBytes,cacheFile);
        if (cacheFile != null && cacheFile.isFile()) {
            try (InputStream input = new FileInputStream(cacheFile)) {
                return preparedFromBlob(spec, readFully(input), config);
            } catch (IOException | IllegalArgumentException staleOrCorruptCache) {
                // Rebuild below. The retained image remains the source of truth.
            }
        }
        MatOfByte encoded = new MatOfByte();
        Mat grayscale = null;
        Mat mask = new Mat();
        DescriptorExtractionRegion extractionRegion = null;
        List<AdminDescriptorBlobCodec.DescriptorVariant> variants = Collections.emptyList();
        try {
            encoded.fromArray(imageBytes);
            grayscale = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE);
            if (grayscale.empty()) {
                throw new IOException("Unable to decode reference image: " + sourceLabel);
            }
            if (spec.isDescriptorRegionNormalizationEnabled()) {
                mask.release();
                mask = referenceMask(spec, grayscale.cols(), grayscale.rows());
                extractionRegion = canonicalDescriptorRegion(grayscale, mask, spec);
                variants = AdminDescriptorBlobCodec.computeVariants(
                        extractionRegion.grayscale, extractionRegion.mask, orb, clahe);
                remapKeypointsToSource(variants, extractionRegion.sourceBounds,
                        extractionRegion.grayscale.cols(), extractionRegion.grayscale.rows());
            } else {
                // Preserve the proven board and legacy-reference pipeline. Region normalization is
                // deliberately opt-in because changing the sampling scale changes ORB descriptors
                // even when their remapped geometry is mathematically equivalent.
                int limit = spec.getMaximumImageDimension();
                int largestSide = Math.max(grayscale.cols(), grayscale.rows());
                if (limit > 0 && largestSide > limit) {
                    double scale = limit / (double) largestSide;
                    Mat resized = new Mat();
                    try {
                        Imgproc.resize(grayscale, resized,
                                new Size(Math.round(grayscale.cols() * scale),
                                        Math.round(grayscale.rows() * scale)),
                                0.0, 0.0, Imgproc.INTER_AREA);
                        grayscale.release();
                        grayscale = resized;
                        resized = null;
                    } finally {
                        if (resized != null) resized.release();
                    }
                }
                mask.release();
                mask = referenceMask(spec, grayscale.cols(), grayscale.rows());
                variants = AdminDescriptorBlobCodec.computeVariants(
                        grayscale, mask, orb, clahe);
            }
            validateTexturedVariants(spec, variants, config, "Reference image");
            if (cacheFile != null && !variants.isEmpty()) {
                try {
                    byte[] cacheBlob = AdminDescriptorBlobCodec.encode(
                            grayscale.cols(), grayscale.rows(), variants, config);
                    writeCacheAtomically(cacheFile, cacheBlob);
                } catch (IOException | RuntimeException cacheFailure) {
                    // Cache failure must never make the packaged offline catalog unavailable.
                }
            }
            return new PreparedReference(spec, grayscale.cols(), grayscale.rows(),
                    variants);
        } finally {
            releaseDescriptorVariants(variants);
            if (extractionRegion != null) {
                extractionRegion.release();
            }
            mask.release();
            if (grayscale != null) {
                grayscale.release();
            }
            encoded.release();
        }
    }

    private static PreparedReference preparedFromBlob(ComponentReferenceSpec spec,
                                                       byte[] blob,
                                                       VisionConfig config) {
        try (AdminDescriptorBlobCodec.DecodedDescriptorBlob decoded =
                     AdminDescriptorBlobCodec.decode(blob, config)) {
            validateTexturedVariants(spec, decoded.variants, config, "Descriptor blob");
            return new PreparedReference(spec, decoded.imageWidth, decoded.imageHeight,
                    decoded.variants);
        }
    }

    private static void validateTexturedVariants(
            ComponentReferenceSpec spec,
            List<AdminDescriptorBlobCodec.DescriptorVariant> variants,
            VisionConfig config,
            String source) {
        if (spec.getShapeHint() != ComponentReferenceSpec.ShapeHint.TEXTURED_PLANAR) {
            return;
        }
        for (AdminDescriptorBlobCodec.DescriptorVariant variant : variants) {
            if (variant.keypoints.length >= config.getMinimumGoodMatches()
                    && !variant.descriptors.empty()) {
                return;
            }
        }
        throw new IllegalArgumentException(source + " cannot satisfy the minimum of "
                + config.getMinimumGoodMatches() + " ORB matches: " + spec.getReferenceId());
    }

    private static byte[] readReferenceImageBytes(AssetManager assetManager,
                                                  ComponentReferenceSpec spec)
            throws IOException {
        String assetPath = spec.getJpegAssetPath();
        if (assetPath != null) {
            try (InputStream input = assetManager.open(assetPath)) {
                return readFully(input);
            }
        }
        String localPath = spec.getLocalImageFilePath();
        if (localPath == null) {
            return null;
        }
        File source = new File(localPath);
        if (!source.isFile()) {
            throw new IOException("Administrator reference image is unavailable: " + localPath);
        }
        try (InputStream input = new FileInputStream(source)) {
            return readFully(input);
        }
    }

    private static void releaseDescriptorVariants(
            List<AdminDescriptorBlobCodec.DescriptorVariant> variants) {
        if (variants == null) {
            return;
        }
        for (AdminDescriptorBlobCodec.DescriptorVariant variant : variants) {
            if (variant != null) {
                variant.release();
            }
        }
    }

    private static byte[] readFully(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static File cacheFile(File directory, ComponentReferenceSpec spec, byte[] assetBytes) {
        if (directory == null) {
            return null;
        }
        if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.canWrite()) {
            return null;
        }
        String safeId = spec.getReferenceId().replaceAll("[^A-Za-z0-9._-]", "_");
        String assetFingerprint = Integer.toUnsignedString(Arrays.hashCode(assetBytes), 16)
                + "_" + assetBytes.length
                + "_" + Integer.toUnsignedString(
                spec.getPlanarCornersNormalized().hashCode(), 16)
                + "_regions" + Integer.toUnsignedString(
                spec.getFeatureRegionsNormalized().hashCode(), 16)
                + "_step" + spec.getAssemblyStepNumber()
                + "_installedBracket" + (spec.isInstalledBracketView() ? 1 : 0)
                + "_size" + spec.getMaximumImageDimension()
                + "_roi" + (spec.isDescriptorRegionNormalizationEnabled() ? 1 : 0)
                + "_raw" + (spec.isAutomaticBoardGeometry() ? 1 : 0)
                + "_bottom" + (spec.isBottomFace() ? 1 : 0)
                + "_prep" + REFERENCE_PREPARATION_VERSION;
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase2AssetCatalog.PREFIX))
            assetFingerprint+="_p2roi"+Phase2PhotoGeometry.CACHE_VERSION;
        return new File(directory, safeId + "_" + assetFingerprint + ".asor");
    }

    /**
     * Crops descriptor extraction to the authored mask and scales that crop to a stable working
     * size. The configured maximum dimension is applied to the component crop, rather than to the
     * surrounding photograph. This preserves substantially more feature detail for small loose
     * parts without allowing high-resolution sources to make ORB preparation unbounded.
     */
    private static DescriptorExtractionRegion canonicalDescriptorRegion(
            Mat sourceGrayscale, Mat sourceMask, ComponentReferenceSpec spec) {
        Mat grayscaleView = null;
        Mat maskView = null;
        Mat canonicalGrayscale = new Mat();
        Mat canonicalMask = new Mat();
        boolean success = false;
        try {
            // boundingRect accepts an 8-bit mask directly. Avoid findNonZero here: materializing
            // every active pixel in a high-resolution board mask can consume hundreds of MB.
            Rect activeBounds = Imgproc.boundingRect(sourceMask);
            if (activeBounds.width <= 0 || activeBounds.height <= 0) {
                throw new IllegalArgumentException(
                        "Reference descriptor mask is empty: " + spec.getReferenceId());
            }
            Rect paddedBounds = paddedBounds(
                    activeBounds, sourceGrayscale.cols(), sourceGrayscale.rows());
            grayscaleView = sourceGrayscale.submat(paddedBounds);
            maskView = sourceMask.submat(paddedBounds);

            int sourceLongestEdge = Math.max(sourceGrayscale.cols(), sourceGrayscale.rows());
            int configuredLimit = spec.getMaximumImageDimension();
            int targetLongestEdge = configuredLimit > 0
                    ? Math.min(configuredLimit, sourceLongestEdge)
                    : sourceLongestEdge;
            double scale = targetLongestEdge
                    / (double) Math.max(paddedBounds.width, paddedBounds.height);
            int targetWidth = Math.max(1, (int) Math.round(paddedBounds.width * scale));
            int targetHeight = Math.max(1, (int) Math.round(paddedBounds.height * scale));

            if (targetWidth == paddedBounds.width && targetHeight == paddedBounds.height) {
                grayscaleView.copyTo(canonicalGrayscale);
                maskView.copyTo(canonicalMask);
            } else {
                int grayscaleInterpolation = scale < 1.0
                        ? Imgproc.INTER_AREA : Imgproc.INTER_CUBIC;
                Imgproc.resize(grayscaleView, canonicalGrayscale,
                        new Size(targetWidth, targetHeight), 0.0, 0.0,
                        grayscaleInterpolation);
                Imgproc.resize(maskView, canonicalMask,
                        new Size(targetWidth, targetHeight), 0.0, 0.0,
                        Imgproc.INTER_NEAREST);
            }
            DescriptorExtractionRegion region = new DescriptorExtractionRegion(
                    canonicalGrayscale, canonicalMask, paddedBounds);
            success = true;
            return region;
        } finally {
            if (maskView != null) maskView.release();
            if (grayscaleView != null) grayscaleView.release();
            if (!success) {
                canonicalMask.release();
                canonicalGrayscale.release();
            }
        }
    }

    private static Rect paddedBounds(Rect active, int imageWidth, int imageHeight) {
        int padding = Math.max(1, (int) Math.ceil(
                Math.max(active.width, active.height) * ROI_PADDING_RATIO));
        int left = Math.max(0, active.x - padding);
        int top = Math.max(0, active.y - padding);
        int right = Math.min(imageWidth, active.x + active.width + padding);
        int bottom = Math.min(imageHeight, active.y + active.height + padding);
        return new Rect(left, top, right - left, bottom - top);
    }

    /**
     * ORB runs in canonical-crop pixels, while homography projection and pose use normalized
     * coordinates from the complete source photograph. Move keypoints back into that original
     * source coordinate system before caching or exposing the prepared reference.
     */
    static void remapKeypointsToSource(
            List<AdminDescriptorBlobCodec.DescriptorVariant> variants,
            Rect sourceBounds, int preparedWidth, int preparedHeight) {
        if (variants == null || sourceBounds == null
                || sourceBounds.width <= 0 || sourceBounds.height <= 0
                || preparedWidth <= 0 || preparedHeight <= 0) {
            throw new IllegalArgumentException("Complete descriptor crop geometry is required");
        }
        double xScale = preparedWidth > 1
                ? (sourceBounds.width - 1.0) / (preparedWidth - 1.0) : 1.0;
        double yScale = preparedHeight > 1
                ? (sourceBounds.height - 1.0) / (preparedHeight - 1.0) : 1.0;
        float sizeScale = (float) Math.sqrt(xScale * yScale);
        for (AdminDescriptorBlobCodec.DescriptorVariant variant : variants) {
            if (variant == null) {
                throw new IllegalArgumentException("Descriptor variants cannot contain null");
            }
            for (KeyPoint keypoint : variant.keypoints) {
                if (keypoint == null) {
                    throw new IllegalArgumentException("Descriptor keypoints cannot contain null");
                }
                keypoint.pt.x = sourceBounds.x + keypoint.pt.x * xScale;
                keypoint.pt.y = sourceBounds.y + keypoint.pt.y * yScale;
                keypoint.size *= sizeScale;
            }
        }
    }

    private static Mat referenceMask(ComponentReferenceSpec spec, int width, int height) {
        List<VisionPoint> normalized = spec.getPlanarCornersNormalized();
        Point[] pixels = new Point[normalized.size()];
        for (int index = 0; index < normalized.size(); index++) {
            VisionPoint point = normalized.get(index);
            pixels[index] = new Point(
                    point.getX() * Math.max(0, width - 1),
                    point.getY() * Math.max(0, height - 1));
        }
        Mat mask = Mat.zeros(height, width, org.opencv.core.CvType.CV_8UC1);
        MatOfPoint polygon = new MatOfPoint(pixels);
        try {
            Imgproc.fillConvexPoly(mask, polygon, new Scalar(255));
            List<List<VisionPoint>> featureRegions = spec.getFeatureRegionsNormalized();
            if (featureRegions.isEmpty()) {
                return mask;
            }

            Mat featureMask = Mat.zeros(
                    height, width, org.opencv.core.CvType.CV_8UC1);
            try {
                for (List<VisionPoint> region : featureRegions) {
                    Point[] regionPixels = new Point[region.size()];
                    for (int index = 0; index < region.size(); index++) {
                        VisionPoint point = region.get(index);
                        regionPixels[index] = new Point(
                                point.getX() * Math.max(0, width - 1),
                                point.getY() * Math.max(0, height - 1));
                    }
                    MatOfPoint featurePolygon = new MatOfPoint(regionPixels);
                    try {
                        // Regions may be concave; their union is authored by filling each source
                        // polygon into the same mask before intersecting the physical board quad.
                        Imgproc.fillPoly(featureMask,
                                Collections.singletonList(featurePolygon), new Scalar(255));
                    } finally {
                        featurePolygon.release();
                    }
                }
                Core.bitwise_and(mask, featureMask, mask);
                return mask;
            } finally {
                featureMask.release();
            }
        } catch (RuntimeException failure) {
            mask.release();
            throw failure;
        } finally {
            polygon.release();
        }
    }

    private static void writeCacheAtomically(File destination, byte[] data) throws IOException {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(data);
            output.getFD().sync();
        }
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Unable to replace stale descriptor cache");
        }
        if (!temporary.renameTo(destination)) {
            throw new IOException("Unable to publish descriptor cache");
        }
    }

    private static final class DescriptorExtractionRegion {
        final Mat grayscale;
        final Mat mask;
        final Rect sourceBounds;
        private boolean released;

        DescriptorExtractionRegion(Mat grayscale, Mat mask, Rect sourceBounds) {
            this.grayscale = grayscale;
            this.mask = mask;
            this.sourceBounds = sourceBounds;
        }

        void release() {
            if (!released) {
                released = true;
                mask.release();
                grayscale.release();
            }
        }
    }

    enum PhotometricVariant {
        NORMALIZED(1, false),
        INVERTED(2, true);

        final int encodedValue;
        private final boolean inverted;

        PhotometricVariant(int encodedValue, boolean inverted) {
            this.encodedValue = encodedValue;
            this.inverted = inverted;
        }

        boolean isInverted() {
            return inverted;
        }

        static PhotometricVariant fromEncodedValue(int encodedValue) {
            for (PhotometricVariant variant : values()) {
                if (variant.encodedValue == encodedValue) {
                    return variant;
                }
            }
            throw new IllegalArgumentException(
                    "Descriptor blob contains an unknown photometric variant");
        }
    }

    static final class PreparedOrbVariant {
        final PhotometricVariant photometricVariant;
        final KeyPoint[] keypoints;
        final Mat descriptors;
        private boolean released;

        PreparedOrbVariant(PhotometricVariant photometricVariant,
                           KeyPoint[] keypoints, Mat descriptors) {
            if (photometricVariant == null || keypoints == null || descriptors == null) {
                throw new IllegalArgumentException("Complete prepared ORB data is required");
            }
            this.photometricVariant = photometricVariant;
            this.keypoints = keypoints.clone();
            this.descriptors = descriptors.clone();
        }

        boolean hasOrbData() {
            return keypoints.length >= 4 && !descriptors.empty();
        }

        void release() {
            if (!released) {
                released = true;
                descriptors.release();
            }
        }
    }

    static final class PreparedReference {
        final ComponentReferenceSpec spec;
        final int imageWidth;
        final int imageHeight;
        final List<PreparedOrbVariant> orbVariants;
        // Compatibility aliases for callers being migrated to explicit photometric alternatives.
        final KeyPoint[] keypoints;
        final Mat descriptors;

        PreparedReference(ComponentReferenceSpec spec, int imageWidth, int imageHeight,
                          List<AdminDescriptorBlobCodec.DescriptorVariant> variants) {
            this.spec = spec;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            List<PreparedOrbVariant> prepared = new ArrayList<>(variants.size());
            boolean preparedSuccessfully = false;
            try {
                for (AdminDescriptorBlobCodec.DescriptorVariant variant : variants) {
                    prepared.add(new PreparedOrbVariant(
                            variant.photometricVariant, variant.keypoints, variant.descriptors));
                }
                preparedSuccessfully = true;
            } finally {
                if (!preparedSuccessfully) {
                    for (PreparedOrbVariant variant : prepared) {
                        variant.release();
                    }
                }
            }
            orbVariants = Collections.unmodifiableList(prepared);
            PreparedOrbVariant primary = null;
            for (PreparedOrbVariant variant : prepared) {
                if (variant.photometricVariant == PhotometricVariant.NORMALIZED) {
                    primary = variant;
                    break;
                }
            }
            if (primary == null && !prepared.isEmpty()) {
                primary = prepared.get(0);
            }
            keypoints = primary == null ? new KeyPoint[0] : primary.keypoints;
            descriptors = primary == null ? null : primary.descriptors;
        }

        List<PreparedOrbVariant> orbVariants() {
            return orbVariants;
        }

        boolean hasOrbData() {
            for (PreparedOrbVariant variant : orbVariants) {
                if (variant.hasOrbData()) {
                    return true;
                }
            }
            return false;
        }

        void release() {
            for (PreparedOrbVariant variant : orbVariants) {
                variant.release();
            }
        }
    }
}
