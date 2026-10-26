package dev.chyken.assembl_ar.vision;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.CLAHE;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Versioned offline blob format for administrator-captured photometric ORB variants.
 * OpenCV must have been initialized before calling {@link #computeAndEncode(Mat, VisionConfig)}.
 */
public final class AdminDescriptorBlobCodec {
    private static final int MAGIC = 0x41534F52; // "ASOR"
    private static final int VERSION = 3;
    private static final int PHOTOMETRIC_PIPELINE_VERSION = 3;
    private static final int ORB_DESCRIPTOR_LENGTH = 32;
    private static final int MAX_DIMENSION = 100_000;
    private static final int MAX_KEYPOINTS = 100_000;
    private static final int MAX_DESCRIPTOR_BYTES = 64 * 1024 * 1024;
    private static final int MAX_VARIANTS = ReferenceDatabase.PhotometricVariant.values().length;

    private AdminDescriptorBlobCodec() {}

    /** Computes both CLAHE-normalized and contrast-inverted reference representations. */
    public static byte[] computeAndEncode(Mat grayscale, VisionConfig config) {
        Mat emptyMask = new Mat();
        try {
            return computeAndEncode(grayscale, emptyMask, config);
        } finally {
            emptyMask.release();
        }
    }

    /**
     * Computes both representations inside a canonical component mask. Administrator captures
     * use this overload so background features cannot become recognition or pose evidence.
     */
    public static byte[] computeAndEncode(Mat grayscale, Mat mask, VisionConfig config) {
        validateGrayscale(grayscale);
        if (config == null) {
            throw new IllegalArgumentException("VisionConfig is required");
        }
        validateMask(mask, grayscale);

        ORB orb = OpenCvVisionUtils.createOrb(config);
        CLAHE clahe = OpenCvVisionUtils.createClahe(config);
        List<DescriptorVariant> variants = Collections.emptyList();
        try {
            variants = ReferenceDescriptorPyramid.compute(grayscale, mask, orb, clahe);
            validateRegistrationCapacity(variants, config);
            return encode(grayscale.cols(), grayscale.rows(), variants, config);
        } finally {
            releaseVariants(variants);
            clahe.clear();
            orb.clear();
        }
    }

    /**
     * Retained for callers that already own one descriptor matrix. New reference data should use
     * {@link #computeAndEncode(Mat, VisionConfig)} so both photometric alternatives are present.
     */
    public static byte[] encode(int imageWidth, int imageHeight,
                                KeyPoint[] keypoints, Mat descriptors,
                                VisionConfig config) {
        DescriptorVariant variant = new DescriptorVariant(
                ReferenceDatabase.PhotometricVariant.NORMALIZED,
                keypoints, descriptors, false);
        try {
            return encode(imageWidth, imageHeight,
                    Collections.singletonList(variant), config);
        } finally {
            variant.release();
        }
    }

    static List<DescriptorVariant> computeVariants(Mat grayscale, Mat mask,
                                                   ORB orb, CLAHE clahe) {
        validateGrayscale(grayscale);
        if (mask == null || orb == null || clahe == null) {
            throw new IllegalArgumentException("Mask, ORB, and CLAHE are required");
        }
        validateMask(mask, grayscale);
        Mat normalized = new Mat();
        Mat inverted = new Mat();
        List<DescriptorVariant> variants = new ArrayList<>(MAX_VARIANTS);
        boolean success = false;
        try {
            OpenCvVisionUtils.normalizeForOrb(grayscale, normalized, clahe);
            DescriptorVariant normal = detect(
                    ReferenceDatabase.PhotometricVariant.NORMALIZED, normalized, mask, orb);
            if (normal != null) {
                variants.add(normal);
            }

            Core.bitwise_not(normalized, inverted);
            DescriptorVariant inverse = detect(
                    ReferenceDatabase.PhotometricVariant.INVERTED, inverted, mask, orb);
            if (inverse != null) {
                variants.add(inverse);
            }
            success = true;
            return variants;
        } finally {
            inverted.release();
            normalized.release();
            if (!success) {
                releaseVariants(variants);
            }
        }
    }

    static byte[] encode(int imageWidth, int imageHeight,
                         List<DescriptorVariant> variants,
                         VisionConfig config) {
        validateImageDimensions(imageWidth, imageHeight);
        if (config == null) {
            throw new IllegalArgumentException("VisionConfig is required");
        }
        validateVariants(variants, imageWidth, imageHeight);

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeLong(configFingerprint(config));
            output.writeInt(imageWidth);
            output.writeInt(imageHeight);
            output.writeInt(variants.size());
            for (DescriptorVariant variant : variants) {
                output.writeInt(variant.photometricVariant.encodedValue);
                output.writeInt(variant.keypoints.length);
                for (KeyPoint point : variant.keypoints) {
                    output.writeFloat((float) point.pt.x);
                    output.writeFloat((float) point.pt.y);
                    output.writeFloat(point.size);
                    output.writeFloat(point.angle);
                    output.writeFloat(point.response);
                    output.writeInt(point.octave);
                    output.writeInt(point.class_id);
                }
                byte[] descriptorBytes = descriptorBytes(variant.descriptors);
                output.writeInt(variant.descriptors.rows());
                output.writeInt(variant.descriptors.cols());
                output.writeInt(variant.descriptors.type());
                output.writeInt(descriptorBytes.length);
                output.write(descriptorBytes);
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException impossibleForMemoryStreams) {
            throw new IllegalStateException("Unable to encode descriptor blob",
                    impossibleForMemoryStreams);
        }
    }

    static DecodedDescriptorBlob decode(byte[] blob, VisionConfig config) {
        if (blob == null || blob.length == 0) {
            throw new IllegalArgumentException("Descriptor blob cannot be empty");
        }
        if (config == null) {
            throw new IllegalArgumentException("VisionConfig is required");
        }
        List<DescriptorVariant> variants = new ArrayList<>();
        boolean success = false;
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(blob));
            if (input.readInt() != MAGIC) {
                throw new IllegalArgumentException("Descriptor blob has an invalid magic value");
            }
            int version = input.readInt();
            if (version != VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported descriptor blob version: " + version);
            }
            long storedFingerprint = input.readLong();
            if (storedFingerprint != configFingerprint(config)) {
                throw new IllegalArgumentException(
                        "Descriptor blob was generated with different vision parameters");
            }
            int imageWidth = input.readInt();
            int imageHeight = input.readInt();
            validateImageDimensions(imageWidth, imageHeight);

            int variantCount = input.readInt();
            if (variantCount <= 0 || variantCount > MAX_VARIANTS) {
                throw new IllegalArgumentException("Descriptor blob variant count is invalid");
            }
            Set<ReferenceDatabase.PhotometricVariant> seen =
                    EnumSet.noneOf(ReferenceDatabase.PhotometricVariant.class);
            for (int variantIndex = 0; variantIndex < variantCount; variantIndex++) {
                ReferenceDatabase.PhotometricVariant photometricVariant =
                        ReferenceDatabase.PhotometricVariant.fromEncodedValue(input.readInt());
                if (!seen.add(photometricVariant)) {
                    throw new IllegalArgumentException(
                            "Descriptor blob contains a duplicate photometric variant");
                }
                KeyPoint[] keypoints = readKeypoints(input, imageWidth, imageHeight);
                Mat descriptors = readDescriptors(input, keypoints.length);
                variants.add(new DescriptorVariant(
                        photometricVariant, keypoints, descriptors, true));
            }
            if (input.available() != 0) {
                throw new IllegalArgumentException("Descriptor blob contains trailing data");
            }
            DecodedDescriptorBlob decoded = new DecodedDescriptorBlob(
                    imageWidth, imageHeight, variants);
            success = true;
            return decoded;
        } catch (EOFException truncated) {
            throw new IllegalArgumentException("Descriptor blob is truncated", truncated);
        } catch (IOException malformed) {
            throw new IllegalArgumentException("Unable to decode descriptor blob", malformed);
        } finally {
            if (!success) {
                releaseVariants(variants);
            }
        }
    }

    private static DescriptorVariant detect(ReferenceDatabase.PhotometricVariant variant,
                                            Mat image, Mat mask, ORB orb) {
        MatOfKeyPoint keypointMat = new MatOfKeyPoint();
        Mat descriptors = new Mat();
        boolean transferred = false;
        try {
            orb.detectAndCompute(image, mask, keypointMat, descriptors);
            if (descriptors.empty()) {
                return null;
            }
            DescriptorVariant result = new DescriptorVariant(
                    variant, keypointMat.toArray(), descriptors, true);
            transferred = true;
            return result;
        } finally {
            if (!transferred) {
                descriptors.release();
            }
            keypointMat.release();
        }
    }

    private static KeyPoint[] readKeypoints(DataInputStream input,
                                            int imageWidth, int imageHeight) throws IOException {
        int keypointCount = input.readInt();
        if (keypointCount <= 0 || keypointCount > MAX_KEYPOINTS) {
            throw new IllegalArgumentException("Descriptor blob keypoint count is invalid");
        }
        KeyPoint[] keypoints = new KeyPoint[keypointCount];
        for (int index = 0; index < keypointCount; index++) {
            KeyPoint point = new KeyPoint(
                    input.readFloat(), input.readFloat(), input.readFloat(),
                    input.readFloat(), input.readFloat(), input.readInt(), input.readInt());
            validateKeypoint(point, imageWidth, imageHeight);
            keypoints[index] = point;
        }
        return keypoints;
    }

    private static Mat readDescriptors(DataInputStream input, int keypointCount)
            throws IOException {
        int rows = input.readInt();
        int columns = input.readInt();
        int type = input.readInt();
        int descriptorByteCount = input.readInt();
        long expectedByteCount = (long) rows * columns;
        if (rows != keypointCount || columns != ORB_DESCRIPTOR_LENGTH
                || type != CvType.CV_8UC1
                || descriptorByteCount != expectedByteCount
                || descriptorByteCount <= 0
                || descriptorByteCount > MAX_DESCRIPTOR_BYTES) {
            throw new IllegalArgumentException("Descriptor blob matrix metadata is invalid");
        }
        byte[] bytes = new byte[descriptorByteCount];
        input.readFully(bytes);
        Mat descriptors = new Mat(rows, columns, CvType.CV_8UC1);
        boolean success = false;
        try {
            descriptors.put(0, 0, bytes);
            success = true;
            return descriptors;
        } finally {
            if (!success) {
                descriptors.release();
            }
        }
    }

    private static void validateVariants(List<DescriptorVariant> variants,
                                         int imageWidth, int imageHeight) {
        if (variants == null || variants.isEmpty() || variants.size() > MAX_VARIANTS) {
            throw new IllegalArgumentException("A bounded non-empty variant list is required");
        }
        Set<ReferenceDatabase.PhotometricVariant> seen =
                EnumSet.noneOf(ReferenceDatabase.PhotometricVariant.class);
        for (DescriptorVariant variant : variants) {
            if (variant == null || !seen.add(variant.photometricVariant)) {
                throw new IllegalArgumentException("Photometric variants must be unique");
            }
            if (variant.keypoints.length == 0 || variant.keypoints.length > MAX_KEYPOINTS) {
                throw new IllegalArgumentException("A bounded non-empty keypoint array is required");
            }
            if (variant.descriptors == null || variant.descriptors.empty()
                    || variant.descriptors.type() != CvType.CV_8UC1
                    || variant.descriptors.rows() != variant.keypoints.length
                    || variant.descriptors.cols() != ORB_DESCRIPTOR_LENGTH) {
                throw new IllegalArgumentException(
                        "ORB descriptors must be CV_8UC1, 32 columns, and one row per keypoint");
            }
            for (KeyPoint point : variant.keypoints) {
                validateKeypoint(point, imageWidth, imageHeight);
            }
            descriptorBytes(variant.descriptors);
        }
    }

    private static byte[] descriptorBytes(Mat descriptors) {
        long byteCountLong = descriptors.total() * descriptors.elemSize();
        if (byteCountLong <= 0L || byteCountLong > MAX_DESCRIPTOR_BYTES) {
            throw new IllegalArgumentException("Descriptor data size is invalid");
        }
        byte[] bytes = new byte[(int) byteCountLong];
        descriptors.get(0, 0, bytes);
        return bytes;
    }

    private static void validateGrayscale(Mat grayscale) {
        if (grayscale == null || grayscale.empty() || grayscale.type() != CvType.CV_8UC1) {
            throw new IllegalArgumentException("A non-empty CV_8UC1 image is required");
        }
    }

    private static void validateMask(Mat mask, Mat grayscale) {
        if (mask == null) {
            throw new IllegalArgumentException("Descriptor mask is required");
        }
        if (!mask.empty() && (mask.type() != CvType.CV_8UC1
                || mask.rows() != grayscale.rows()
                || mask.cols() != grayscale.cols())) {
            throw new IllegalArgumentException(
                    "Descriptor mask must be empty or match the CV_8UC1 image dimensions");
        }
    }

    private static void validateRegistrationCapacity(
            List<DescriptorVariant> variants, VisionConfig config) {
        int minimumKeypoints = Math.max(4, config.getMinimumGoodMatches() * 2);
        if (variants.size() != 2 || variants.get(0).photometricVariant != ReferenceDatabase.PhotometricVariant.NORMALIZED
                || variants.get(1).photometricVariant != ReferenceDatabase.PhotometricVariant.INVERTED) {
            throw new IllegalArgumentException(
                    "both normal and color-inverted feature sets are required");
        }
        for (DescriptorVariant variant : variants) {
            if (variant.keypoints.length < minimumKeypoints) {
                throw new IllegalArgumentException(
                        variant.photometricVariant.name().toLowerCase(java.util.Locale.ROOT)
                                + " appearance has only " + variant.keypoints.length
                                + " stable features; at least " + minimumKeypoints
                                + " are required");
            }
        }
    }

    private static void validateImageDimensions(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("Reference image dimensions are invalid");
        }
    }

    private static void validateKeypoint(KeyPoint point, int imageWidth, int imageHeight) {
        if (point == null || !Double.isFinite(point.pt.x) || !Double.isFinite(point.pt.y)
                || !Float.isFinite(point.size) || !Float.isFinite(point.angle)
                || !Float.isFinite(point.response) || !(point.size > 0.0f)
                || point.pt.x < 0.0 || point.pt.x >= imageWidth
                || point.pt.y < 0.0 || point.pt.y >= imageHeight) {
            throw new IllegalArgumentException("Descriptor blob contains an invalid keypoint");
        }
    }

    static long configFingerprint(VisionConfig config) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, PHOTOMETRIC_PIPELINE_VERSION);
        hash = mix(hash, config.getOrbFeatureCount());
        hash = mix(hash, Float.floatToIntBits(config.getOrbScaleFactor()));
        hash = mix(hash, config.getOrbPyramidLevels());
        hash = mix(hash, config.getOrbEdgeThreshold());
        hash = mix(hash, config.getOrbFirstLevel());
        hash = mix(hash, config.getOrbWtaK());
        hash = mix(hash, ORB.HARRIS_SCORE);
        hash = mix(hash, config.getOrbPatchSize());
        hash = mix(hash, config.getOrbFastThreshold());
        hash = mixLong(hash, Double.doubleToLongBits(config.getClaheClipLimit()));
        hash = mix(hash, config.getClaheTileGridWidth());
        return mix(hash, config.getClaheTileGridHeight());
    }

    private static long mixLong(long hash, long value) {
        return mix(mix(hash, (int) value), (int) (value >>> 32));
    }

    private static long mix(long hash, int value) {
        hash ^= value & 0xffffffffL;
        return hash * 0x100000001b3L;
    }

    private static void releaseVariants(List<DescriptorVariant> variants) {
        if (variants == null) {
            return;
        }
        for (DescriptorVariant variant : variants) {
            if (variant != null) {
                variant.release();
            }
        }
    }

    /** Publish one owned concatenation; input rows remain borrowed on every exit. */
    static void concatenateInto(List<DescriptorVariant> output,ReferenceDatabase.PhotometricVariant photo,
                                List<KeyPoint> keypoints,List<Mat> inputs){
        Mat merged=new Mat();
        try{Core.vconcat(inputs,merged);output.add(new DescriptorVariant(photo,keypoints.toArray(new KeyPoint[0]),merged,true));}
        catch(RuntimeException|Error failure){merged.release();throw failure;}
    }

    static final class DescriptorVariant {
        final ReferenceDatabase.PhotometricVariant photometricVariant;
        final KeyPoint[] keypoints;
        final Mat descriptors;
        private boolean released;

        DescriptorVariant(ReferenceDatabase.PhotometricVariant photometricVariant,
                          KeyPoint[] keypoints, Mat descriptors, boolean takeOwnership) {
            if (photometricVariant == null || keypoints == null || descriptors == null) {
                throw new IllegalArgumentException("Complete descriptor variant data is required");
            }
            this.photometricVariant = photometricVariant;
            this.keypoints = keypoints.clone();
            this.descriptors = takeOwnership ? descriptors : descriptors.clone();
        }

        void release() {
            if (!released) {
                released = true;
                descriptors.release();
            }
        }
    }

    static final class DecodedDescriptorBlob implements AutoCloseable {
        final int imageWidth;
        final int imageHeight;
        final List<DescriptorVariant> variants;
        private boolean closed;

        DecodedDescriptorBlob(int imageWidth, int imageHeight,
                              List<DescriptorVariant> variants) {
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.variants = Collections.unmodifiableList(new ArrayList<>(variants));
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                releaseVariants(variants);
            }
        }
    }
}
