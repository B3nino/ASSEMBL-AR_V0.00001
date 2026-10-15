package dev.chyken.assembl_ar.workflow;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import dev.chyken.assembl_ar.vision.CameraCalibration;

/** Portable exact-frame sample used to reproduce individual-part recognition failures offline. */
public final class PartTestSampleArchive {
    private static final String METADATA_ENTRY = "sample.properties";
    private static final String FRAME_ENTRY = "frame.png";
    private static final String RGB_FRAME_ENTRY = "frame-rgb.png";
    private static final String LEGACY_FORMAT_VERSION = "1";
    private static final String FORMAT_VERSION = "2";
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    private static final int MAX_FRAME_DIMENSION = 2560;
    private static final long MAX_FRAME_PIXELS = 2_500_000L;
    // Version 1 was already shipped with a 32 MiB entry limit and no camera-resolution cap.
    // Keep practical high-resolution legacy captures readable while still putting a firm ceiling
    // on the native allocation implied by an untrusted PNG IHDR.
    private static final int MAX_LEGACY_FRAME_BYTES = 32 * 1024 * 1024;
    private static final int MAX_LEGACY_FRAME_DIMENSION = 8192;
    private static final long MAX_LEGACY_FRAME_PIXELS = 40_000_000L;
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    private final String componentId;
    private final long frameTimestampNanos;
    private final int frameWidth;
    private final int frameHeight;
    private final int rotationDegrees;
    private final String recordedOutcome;
    private final byte[] framePng;
    private final byte[] rgbFramePng;
    private final double[] cameraMatrix;
    private final double[] distortionCoefficients;

    public PartTestSampleArchive(String componentId, long frameTimestampNanos,
                                 int frameWidth, int frameHeight, int rotationDegrees,
                                 String recordedOutcome, byte[] framePng) {
        this(componentId, frameTimestampNanos, frameWidth, frameHeight, rotationDegrees,
                recordedOutcome, framePng, null, null);
    }

    /** Creates a version-2 sample that can replay both ORB and color/shape localization. */
    public PartTestSampleArchive(String componentId, long frameTimestampNanos,
                                 int frameWidth, int frameHeight, int rotationDegrees,
                                 String recordedOutcome, byte[] framePng, byte[] rgbFramePng,
                                 CameraCalibration cameraCalibration) {
        if (componentId == null || componentId.trim().isEmpty()) {
            throw new IllegalArgumentException("Component id is required");
        }
        boolean fullColorReplay = rgbFramePng != null;
        int maximumDimension = fullColorReplay
                ? MAX_FRAME_DIMENSION : MAX_LEGACY_FRAME_DIMENSION;
        long maximumPixels = fullColorReplay
                ? MAX_FRAME_PIXELS : MAX_LEGACY_FRAME_PIXELS;
        int maximumFrameBytes = fullColorReplay
                ? MAX_FRAME_BYTES : MAX_LEGACY_FRAME_BYTES;
        if (frameWidth < 1 || frameHeight < 1
                || frameWidth > maximumDimension || frameHeight > maximumDimension
                || (long) frameWidth * frameHeight > maximumPixels
                || rotationDegrees != 0 && rotationDegrees != 90
                && rotationDegrees != 180 && rotationDegrees != 270) {
            throw new IllegalArgumentException("Frame geometry is invalid");
        }
        if (framePng == null || framePng.length == 0
                || framePng.length > maximumFrameBytes) {
            throw new IllegalArgumentException("Frame PNG is empty or too large");
        }
        if ((rgbFramePng == null) != (cameraCalibration == null)) {
            throw new IllegalArgumentException(
                    "RGB frame and camera calibration must be supplied together");
        }
        if (rgbFramePng != null
                && (rgbFramePng.length == 0 || rgbFramePng.length > MAX_FRAME_BYTES)) {
            throw new IllegalArgumentException("RGB frame PNG is empty or too large");
        }
        validatePngHeader(framePng, frameWidth, frameHeight, "Grayscale frame");
        if (rgbFramePng != null) {
            validatePngHeader(rgbFramePng, frameWidth, frameHeight, "RGB frame");
        }
        this.componentId = componentId.trim();
        this.frameTimestampNanos = frameTimestampNanos;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.rotationDegrees = rotationDegrees;
        this.recordedOutcome = recordedOutcome == null ? "" : recordedOutcome;
        this.framePng = framePng.clone();
        this.rgbFramePng = rgbFramePng == null ? null : rgbFramePng.clone();
        this.cameraMatrix = cameraCalibration == null ? null
                : cameraCalibration.getCameraMatrixRowMajor();
        this.distortionCoefficients = cameraCalibration == null ? null
                : cameraCalibration.getDistortionCoefficients();
    }

    public void writeTo(OutputStream destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        Properties metadata = new Properties();
        boolean fullColorReplay = hasFullColorReplay();
        metadata.setProperty("format_version",
                fullColorReplay ? FORMAT_VERSION : LEGACY_FORMAT_VERSION);
        metadata.setProperty("component_id", componentId);
        metadata.setProperty("frame_timestamp_nanos", Long.toString(frameTimestampNanos));
        metadata.setProperty("frame_width", Integer.toString(frameWidth));
        metadata.setProperty("frame_height", Integer.toString(frameHeight));
        metadata.setProperty("rotation_degrees", Integer.toString(rotationDegrees));
        metadata.setProperty("recorded_outcome", recordedOutcome);
        if (fullColorReplay) {
            metadata.setProperty("camera_matrix", encodeDoubles(cameraMatrix));
            metadata.setProperty("distortion_coefficients",
                    encodeDoubles(distortionCoefficients));
        }
        ByteArrayOutputStream metadataBytes = new ByteArrayOutputStream();
        metadata.store(metadataBytes, "ASSEMBL-AR individual-part sample");

        try (ZipOutputStream zip = new ZipOutputStream(
                new NonClosingOutputStream(destination))) {
            zip.putNextEntry(new ZipEntry(METADATA_ENTRY));
            zip.write(metadataBytes.toByteArray());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(FRAME_ENTRY));
            zip.write(framePng);
            zip.closeEntry();
            if (fullColorReplay) {
                zip.putNextEntry(new ZipEntry(RGB_FRAME_ENTRY));
                zip.write(rgbFramePng);
                zip.closeEntry();
            }
        }
    }

    public static PartTestSampleArchive readFrom(InputStream source) throws IOException {
        Objects.requireNonNull(source, "source");
        byte[] metadataBytes = null;
        byte[] pngBytes = null;
        byte[] rgbPngBytes = null;
        try (ZipInputStream zip = new ZipInputStream(new NonClosingInputStream(source))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (METADATA_ENTRY.equals(entry.getName())) {
                    if (metadataBytes != null) throw new IOException("Duplicate sample metadata");
                    metadataBytes = readLimited(zip, MAX_METADATA_BYTES);
                } else if (FRAME_ENTRY.equals(entry.getName())) {
                    if (pngBytes != null) throw new IOException("Duplicate sample frame");
                    // Metadata may legally follow the frame in a ZIP, so read to the bounded v1
                    // ceiling here. The version-specific constructor applies the tighter v2 cap.
                    pngBytes = readLimited(zip, MAX_LEGACY_FRAME_BYTES);
                } else if (RGB_FRAME_ENTRY.equals(entry.getName())) {
                    if (rgbPngBytes != null) throw new IOException("Duplicate RGB sample frame");
                    rgbPngBytes = readLimited(zip, MAX_LEGACY_FRAME_BYTES);
                } else {
                    throw new IOException("Unexpected part-sample entry: " + entry.getName());
                }
                zip.closeEntry();
            }
        }
        if (metadataBytes == null || pngBytes == null || pngBytes.length == 0) {
            throw new IOException("Sample must contain metadata and frame.png");
        }
        Properties metadata = new Properties();
        metadata.load(new java.io.ByteArrayInputStream(metadataBytes));
        String formatVersion = metadata.getProperty("format_version");
        if (!FORMAT_VERSION.equals(formatVersion)
                && !LEGACY_FORMAT_VERSION.equals(formatVersion)) {
            throw new IOException("Unsupported part-sample format");
        }
        try {
            String componentId = require(metadata, "component_id");
            long timestampNanos = Long.parseLong(require(metadata, "frame_timestamp_nanos"));
            int width = Integer.parseInt(require(metadata, "frame_width"));
            int height = Integer.parseInt(require(metadata, "frame_height"));
            int rotation = Integer.parseInt(require(metadata, "rotation_degrees"));
            String outcome = metadata.getProperty("recorded_outcome", "");
            if (LEGACY_FORMAT_VERSION.equals(formatVersion)) {
                return new PartTestSampleArchive(componentId, timestampNanos,
                        width, height, rotation, outcome, pngBytes);
            }
            if (rgbPngBytes == null || rgbPngBytes.length == 0) {
                throw new IllegalArgumentException("Version-2 sample is missing frame-rgb.png");
            }
            double[] matrix = parseDoubles(require(metadata, "camera_matrix"));
            if (matrix.length != 9) {
                throw new IllegalArgumentException("Camera matrix must contain nine values");
            }
            double[] distortion = parseDoubles(
                    metadata.getProperty("distortion_coefficients", ""));
            CameraCalibration calibration = new CameraCalibration(matrix, distortion);
            return new PartTestSampleArchive(
                    componentId, timestampNanos, width, height, rotation,
                    outcome, pngBytes, rgbPngBytes, calibration);
        } catch (IllegalArgumentException invalidMetadata) {
            throw new IOException("Invalid part-sample metadata", invalidMetadata);
        }
    }

    private static String encodeDoubles(double[] values) {
        StringBuilder encoded = new StringBuilder();
        for (double value : values) {
            if (encoded.length() > 0) encoded.append(',');
            encoded.append(Double.toString(value));
        }
        return encoded.toString();
    }

    private static double[] parseDoubles(String encoded) {
        if (encoded == null || encoded.trim().isEmpty()) return new double[0];
        String[] fields = encoded.split(",", -1);
        double[] values = new double[fields.length];
        for (int index = 0; index < fields.length; index++) {
            values[index] = Double.parseDouble(fields[index].trim());
        }
        return values;
    }

    /** Rejects decompression bombs before native image decoding allocates from untrusted IHDR. */
    private static void validatePngHeader(byte[] png, int width, int height, String label) {
        if (png == null || png.length < 24) {
            throw new IllegalArgumentException(label + " PNG header is truncated");
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (png[index] != PNG_SIGNATURE[index]) {
                throw new IllegalArgumentException(label + " is not a PNG");
            }
        }
        if (readInt(png, 8) != 13
                || png[12] != 'I' || png[13] != 'H'
                || png[14] != 'D' || png[15] != 'R') {
            throw new IllegalArgumentException(label + " is missing its PNG IHDR");
        }
        int encodedWidth = readInt(png, 16);
        int encodedHeight = readInt(png, 20);
        if (encodedWidth != width || encodedHeight != height) {
            throw new IllegalArgumentException(label + " dimensions do not match metadata");
        }
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24
                | (bytes[offset + 1] & 0xff) << 16
                | (bytes[offset + 2] & 0xff) << 8
                | bytes[offset + 3] & 0xff;
    }

    /** True when a live analyzed frame can be saved in the bounded version-2 format. */
    public static boolean supportsFullColorFrameGeometry(int width, int height) {
        return width > 0 && height > 0
                && width <= MAX_FRAME_DIMENSION && height <= MAX_FRAME_DIMENSION
                && (long) width * height <= MAX_FRAME_PIXELS;
    }

    private static byte[] readLimited(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() > maximumBytes - count) {
                throw new IOException("Part-sample entry exceeds its size limit");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String require(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value;
    }

    /** Lets Zip streams release native inflater/deflater state without owning the caller stream. */
    private static final class NonClosingInputStream extends FilterInputStream {
        NonClosingInputStream(InputStream input) { super(input); }
        @Override public void close() { }
    }

    private static final class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream output) { super(output); }
        @Override public void close() throws IOException { flush(); }
    }

    public String getComponentId() { return componentId; }
    public long getFrameTimestampNanos() { return frameTimestampNanos; }
    public int getFrameWidth() { return frameWidth; }
    public int getFrameHeight() { return frameHeight; }
    public int getRotationDegrees() { return rotationDegrees; }
    public String getRecordedOutcome() { return recordedOutcome; }
    public byte[] getFramePng() { return framePng.clone(); }
    public boolean hasFullColorReplay() { return rgbFramePng != null; }
    public byte[] getRgbFramePng() {
        return rgbFramePng == null ? new byte[0] : rgbFramePng.clone();
    }
    public CameraCalibration getCameraCalibration() {
        return cameraMatrix == null ? null
                : new CameraCalibration(cameraMatrix, distortionCoefficients);
    }
}
