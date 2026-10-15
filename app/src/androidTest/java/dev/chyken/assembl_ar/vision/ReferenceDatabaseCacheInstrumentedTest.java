package dev.chyken.assembl_ar.vision;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Exercises the app-private, versioned ORB descriptor cache through its public load API. */
@RunWith(AndroidJUnit4.class)
public final class ReferenceDatabaseCacheInstrumentedTest {
    private static final String TEST_CACHE_DIRECTORY =
            "reference_database_cache_instrumented_test";
    private static final int ASOR_MAGIC = 0x41534F52;
    private static final int ASOR_VERSION = 3;

    private Context context;
    private File cacheDirectory;

    @Before
    public void setUpOwnCacheDirectory() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        cacheDirectory = new File(context.getCacheDir(), TEST_CACHE_DIRECTORY);
        assertDirectChildOfAppCache(cacheDirectory);
        deleteOwnCacheDirectory(cacheDirectory);
    }

    @After
    public void cleanOnlyOwnCacheDirectory() throws Exception {
        if (cacheDirectory != null) {
            assertDirectChildOfAppCache(cacheDirectory);
            deleteOwnCacheDirectory(cacheDirectory);
        }
    }

    @Test
    public void cacheIsWrittenReadAndRebuiltAfterCorruption() throws Exception {
        assertTrue("The packaged OpenCV runtime must initialize", OpenCVLoader.initLocal());
        VisionConfig config = VisionConfig.defaults();
        ComponentReferenceSpec bundledReference = bundledMainBoardReference();
        List<ComponentReferenceSpec> catalog = Collections.singletonList(bundledReference);

        // The first load has no cache to consume, so it extracts ORB data from the asset and
        // atomically publishes one versioned .asor file in this test's private directory.
        try (ReferenceDatabase first = ReferenceDatabase.load(
                context.getAssets(), catalog, config, cacheDirectory)) {
            assertEquals(1, first.size());
        }
        File cacheFile = onlyAsorCacheFile();
        byte[] firstBytes = readFully(cacheFile);
        assertValidVersionedCache(firstBytes, config);

        // A successful cache read must not replace the file. Pin its modification time to an old
        // sentinel and also compare the bytes, making an unnoticed ORB rebuild observable.
        long requestedSentinel = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
        assertTrue("The app-private cache timestamp must be adjustable for this assertion",
                cacheFile.setLastModified(requestedSentinel));
        long storedSentinel = cacheFile.lastModified();
        assertTrue(storedSentinel > 0L);
        try (ReferenceDatabase second = ReferenceDatabase.load(
                context.getAssets(), catalog, config, cacheDirectory)) {
            assertEquals(1, second.size());
        }
        assertEquals("A cache hit must not rewrite the descriptor file",
                storedSentinel, cacheFile.lastModified());
        assertArrayEquals("A cache hit must preserve the versioned blob",
                firstBytes, readFully(cacheFile));

        // Break the ASOR header. ReferenceDatabase must reject this blob, recompute from the
        // packaged JPEG, and replace the corrupt bytes without making the catalog unavailable.
        byte[] corruptBytes = new byte[]{0x00, 0x13, 0x37, 0x55, 0x21};
        writeBytes(cacheFile, corruptBytes);
        assertArrayEquals(corruptBytes, readFully(cacheFile));
        try (ReferenceDatabase rebuilt = ReferenceDatabase.load(
                context.getAssets(), catalog, config, cacheDirectory)) {
            assertEquals(1, rebuilt.size());
        }

        File rebuiltCache = onlyAsorCacheFile();
        assertEquals(cacheFile.getCanonicalPath(), rebuiltCache.getCanonicalPath());
        byte[] rebuiltBytes = readFully(rebuiltCache);
        assertFalse("Corrupt cache bytes must be replaced",
                Arrays.equals(corruptBytes, rebuiltBytes));
        assertValidVersionedCache(rebuiltBytes, config);
    }

    @Test
    public void versionTwoCacheIsRejectedAndRebuiltFromTheBundledImage() throws Exception {
        assertTrue("The packaged OpenCV runtime must initialize", OpenCVLoader.initLocal());
        VisionConfig config = VisionConfig.defaults();
        List<ComponentReferenceSpec> catalog =
                Collections.singletonList(bundledMainBoardReference());

        try (ReferenceDatabase ignored = ReferenceDatabase.load(
                context.getAssets(), catalog, config, cacheDirectory)) {
            assertEquals(1, ignored.size());
        }
        File cacheFile = onlyAsorCacheFile();
        byte[] legacyHeader = readFully(cacheFile);
        legacyHeader[4] = 0;
        legacyHeader[5] = 0;
        legacyHeader[6] = 0;
        legacyHeader[7] = 2;
        writeBytes(cacheFile, legacyHeader);

        try (ReferenceDatabase rebuilt = ReferenceDatabase.load(
                context.getAssets(), catalog, config, cacheDirectory)) {
            assertEquals(1, rebuilt.size());
        }

        byte[] rebuiltBytes = readFully(onlyAsorCacheFile());
        assertFalse("ASOR-2 bytes must not remain active",
                Arrays.equals(legacyHeader, rebuiltBytes));
        assertValidVersionedCache(rebuiltBytes, config);
    }

    @Test
    public void canonicalCropCoordinatesAreMappedBackToTheCompleteSourceImage() {
        assertTrue("The packaged OpenCV runtime must initialize", OpenCVLoader.initLocal());
        Mat descriptors = Mat.ones(2, 32, CvType.CV_8UC1);
        AdminDescriptorBlobCodec.DescriptorVariant variant =
                new AdminDescriptorBlobCodec.DescriptorVariant(
                        ReferenceDatabase.PhotometricVariant.NORMALIZED,
                        new KeyPoint[]{
                                new KeyPoint(0.0f, 0.0f, 31.0f),
                                new KeyPoint(1279.0f, 639.0f, 31.0f)
                        }, descriptors, true);
        try {
            Rect sourceCrop = new Rect(1_000, 500, 800, 400);
            ReferenceDatabase.remapKeypointsToSource(
                    Collections.singletonList(variant), sourceCrop, 1_280, 640);

            assertEquals(1_000.0, variant.keypoints[0].pt.x, 1e-6);
            assertEquals(500.0, variant.keypoints[0].pt.y, 1e-6);
            assertEquals(1_799.0, variant.keypoints[1].pt.x, 1e-6);
            assertEquals(899.0, variant.keypoints[1].pt.y, 1e-6);
            double expectedSizeScale = Math.sqrt((799.0 / 1279.0) * (399.0 / 639.0));
            assertEquals(31.0 * expectedSizeScale, variant.keypoints[0].size, 1e-4);
        } finally {
            variant.release();
        }
    }

    @Test
    public void maskedLoosePartIsCachedInOriginalSourceCoordinates() throws Exception {
        assertTrue("The packaged OpenCV runtime must initialize", OpenCVLoader.initLocal());
        ComponentReferenceSpec bundled = referenceWithAssetSuffix(
                "references/individual-test/motor-driver-20260906_131718.jpg");
        // Packaging now limits this source to 1280px. Use an explicit smaller preparation
        // limit to keep exercising source-coordinate preservation across crop resizing.
        List<VisionPoint> corners = bundled.getPlanarCornersNormalized();
        ComponentReferenceSpec reference = ComponentReferenceSpec.builder(
                        bundled.getReferenceId(), bundled.getComponentId(), bundled.getDisplayName(),
                        bundled.getPhysicalWidthMm(), bundled.getPhysicalHeightMm())
                .jpegAssetPath(bundled.getJpegAssetPath())
                .maximumImageDimension(640).descriptorRegionNormalization(true)
                .featureRegionsNormalized(bundled.getFeatureRegionsNormalized())
                .planarReferenceCornersNormalized(corners.get(0), corners.get(1),
                        corners.get(2), corners.get(3))
                .build();
        VisionConfig config = VisionConfig.builder()
                .matching(0.75f, 64.0f, 6)
                .homography(4, 0.45, 3.0, 2_000, 0.995)
                .build();

        MatOfByte encoded = new MatOfByte();
        Mat source = null;
        try {
            encoded.fromArray(readAsset(reference.getJpegAssetPath()));
            source = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE);
            assertFalse(source.empty());
            assertTrue("Fixture must exercise the high-resolution crop path",
                    Math.max(source.cols(), source.rows()) > reference.getMaximumImageDimension());

            try (ReferenceDatabase database = ReferenceDatabase.load(
                    context.getAssets(), Collections.singletonList(reference),
                    config, cacheDirectory)) {
                ReferenceDatabase.PreparedReference prepared =
                        database.preparedReferences().get(0);
                assertEquals("Prepared geometry must retain the complete source width",
                        source.cols(), prepared.imageWidth);
                assertEquals("Prepared geometry must retain the complete source height",
                        source.rows(), prepared.imageHeight);
                assertKeypointsRemainInsideDriverFeatureRegion(prepared);
            }

            byte[] cacheBytes = readFully(onlyAsorCacheFile());
            try (AdminDescriptorBlobCodec.DecodedDescriptorBlob decoded =
                         AdminDescriptorBlobCodec.decode(cacheBytes, config)) {
                assertEquals(source.cols(), decoded.imageWidth);
                assertEquals(source.rows(), decoded.imageHeight);
                assertTrue("The cache name must identify the ROI-preparation generation",
                        onlyAsorCacheFile().getName().contains("_prep2.asor"));
            }
        } finally {
            if (source != null) source.release();
            encoded.release();
        }
    }

    private ComponentReferenceSpec bundledMainBoardReference() {
        AssemblArRepository repository = AssemblArRepository.getInstance(context);
        for (ComponentReferenceSpec spec : VisionReferenceFactory.build(repository)) {
            if (NeobotSeedData.MAIN_BOARD.equals(spec.getComponentId())
                    && spec.getJpegAssetPath() != null) {
                return spec;
            }
        }
        throw new AssertionError("The seeded catalog needs a bundled main-board reference");
    }

    private ComponentReferenceSpec referenceWithAssetSuffix(String assetPath) {
        AssemblArRepository repository = AssemblArRepository.getInstance(context);
        for (ComponentReferenceSpec spec : VisionReferenceFactory.build(repository)) {
            if (assetPath.equals(spec.getJpegAssetPath())) {
                return spec;
            }
        }
        throw new AssertionError("The catalog needs reference asset " + assetPath);
    }

    private static void assertKeypointsRemainInsideDriverFeatureRegion(
            ReferenceDatabase.PreparedReference reference) {
        int keypointCount = 0;
        for (ReferenceDatabase.PreparedOrbVariant variant : reference.orbVariants()) {
            for (KeyPoint keypoint : variant.keypoints) {
                keypointCount++;
                double normalizedX = keypoint.pt.x / (reference.imageWidth - 1.0);
                double normalizedY = keypoint.pt.y / (reference.imageHeight - 1.0);
                assertTrue("Remapped X must stay within the authored feature-region bounds",
                        normalizedX >= 0.349 && normalizedX <= 0.643);
                assertTrue("Remapped Y must stay within the authored feature-region bounds",
                        normalizedY >= 0.401 && normalizedY <= 0.553);
            }
        }
        assertTrue("The canonical crop must retain enough descriptors for matching",
                keypointCount >= 12);
    }

    private File onlyAsorCacheFile() {
        File[] files = cacheDirectory.listFiles(
                (directory, name) -> name.endsWith(".asor"));
        assertNotNull("The descriptor cache directory must remain readable", files);
        assertEquals("Exactly one reference was loaded, so exactly one cache is expected",
                1, files.length);
        File[] temporaryFiles = cacheDirectory.listFiles(
                (directory, name) -> name.endsWith(".tmp"));
        assertNotNull(temporaryFiles);
        assertEquals("Atomic publication must not leave a temporary file", 0,
                temporaryFiles.length);
        return files[0];
    }

    private static void assertValidVersionedCache(byte[] bytes, VisionConfig config)
            throws Exception {
        assertTrue("A versioned descriptor blob needs its complete header", bytes.length > 8);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            assertEquals("ASOR magic", ASOR_MAGIC, input.readInt());
            assertEquals("ASOR format version", ASOR_VERSION, input.readInt());
        }

        AdminDescriptorBlobCodec.DecodedDescriptorBlob decoded =
                AdminDescriptorBlobCodec.decode(bytes, config);
        try (AdminDescriptorBlobCodec.DecodedDescriptorBlob ignored = decoded) {
            assertTrue(decoded.imageWidth > 0);
            assertTrue(decoded.imageHeight > 0);
            assertEquals(2, decoded.variants.size());
            Set<ReferenceDatabase.PhotometricVariant> variants =
                    EnumSet.noneOf(ReferenceDatabase.PhotometricVariant.class);
            for (AdminDescriptorBlobCodec.DescriptorVariant variant : decoded.variants) {
                variants.add(variant.photometricVariant);
                assertTrue(variant.keypoints.length >= 4);
                assertFalse(variant.descriptors.empty());
            }
            assertEquals(EnumSet.allOf(ReferenceDatabase.PhotometricVariant.class), variants);
        }
    }

    private static byte[] readFully(File file) throws IOException {
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private byte[] readAsset(String assetPath) throws IOException {
        try (InputStream input = context.getAssets().open(assetPath);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void writeBytes(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(bytes);
            output.getFD().sync();
        }
    }

    private void assertDirectChildOfAppCache(File directory) throws IOException {
        assertEquals("The test may clean only its direct app-cache child",
                context.getCacheDir().getCanonicalFile(),
                directory.getCanonicalFile().getParentFile());
    }

    private static void deleteOwnCacheDirectory(File directory) throws IOException {
        if (!directory.exists()) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            throw new IOException("Unable to list test cache directory: " + directory);
        }
        for (File child : children) {
            if (child.isDirectory()) {
                deleteOwnCacheDirectory(child);
            } else if (!child.delete()) {
                throw new IOException("Unable to delete test cache file: " + child);
            }
        }
        if (!directory.delete()) {
            throw new IOException("Unable to delete test cache directory: " + directory);
        }
    }
}
