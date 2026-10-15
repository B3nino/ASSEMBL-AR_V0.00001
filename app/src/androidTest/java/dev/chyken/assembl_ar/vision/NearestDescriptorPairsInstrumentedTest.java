package dev.chyken.assembl_ar.vision;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.ORB;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.CLAHE;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;

import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.camera.IndividualPartTestCatalog;
import dev.chyken.assembl_ar.data.AssemblArRepository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The one-call nearest-pair matcher must reproduce BFMatcher knnMatch exactly, only faster. */
@RunWith(AndroidJUnit4.class)
public final class NearestDescriptorPairsInstrumentedTest {
    @Test public void frameCacheAcceptsOnlyIdenticalPixelsAcrossCaptureTimes() {
        assertTrue(OpenCVLoader.initLocal());
        ComponentRecognizer.FrameFeatureCache cache = new ComponentRecognizer.FrameFeatureCache();
        Mat source = Mat.zeros(12, 12, CvType.CV_8UC1);
        Mat copy = new Mat();
        Mat descriptors = Mat.zeros(1, 32, CvType.CV_8UC1);
        try {
            assertFalse(cache.holds(source));
            cache.store(source, source, descriptors, new KeyPoint[0]);
            source.copyTo(copy);
            assertTrue("A later timestamp does not change identical image evidence",
                    cache.holds(copy));
            copy.put(4, 5, new byte[]{1});
            assertFalse("A changed pixel invalidates all cached descriptors and pairs",
                    cache.holds(copy));
        } finally {
            descriptors.release();
            copy.release();
            source.release();
            cache.release();
        }
    }

    @Test public void batchedQuerySlicesPreserveEveryNeighbourAndLocalQueryIndex() {
        assertTrue(OpenCVLoader.initLocal());
        Random random = new Random(20929L);
        Mat first = new Mat(7, 32, CvType.CV_8UC1);
        Mat second = new Mat(11, 32, CvType.CV_8UC1);
        Mat train = new Mat(29, 32, CvType.CV_8UC1);
        Mat combined = new Mat();
        try {
            byte[] bytes = new byte[(7 + 11 + 29) * 32];
            random.nextBytes(bytes);
            first.put(0, 0, Arrays.copyOfRange(bytes, 0, 7 * 32));
            second.put(0, 0, Arrays.copyOfRange(bytes, 7 * 32, 18 * 32));
            train.put(0, 0, Arrays.copyOfRange(bytes, 18 * 32, bytes.length));
            Core.vconcat(Arrays.asList(first, second), combined);

            NearestDescriptorPairs batched = NearestDescriptorPairs.compute(combined, train);
            assertPairsEqual(NearestDescriptorPairs.compute(first, train), batched.slice(0, 7));
            assertPairsEqual(NearestDescriptorPairs.compute(second, train), batched.slice(7, 11));
        } finally {
            combined.release();
            train.release();
            second.release();
            first.release();
        }
    }

    private static void assertPairsEqual(NearestDescriptorPairs expected,
                                         NearestDescriptorPairs actual) {
        assertEquals(expected.size(), actual.size());
        for (int row = 0; row < expected.size(); row++) {
            assertEquals(expected.hasTwo(row), actual.hasTwo(row));
            if (!expected.hasTwo(row)) continue;
            assertEquals(row, actual.best(row).queryIdx);
            assertEquals(expected.best(row).trainIdx, actual.best(row).trainIdx);
            assertEquals(expected.bestDistance(row), actual.bestDistance(row), 0f);
            assertEquals(expected.secondDistance(row), actual.secondDistance(row), 0f);
        }
    }

    @Test public void concurrentNativeReadersPreserveTiesAndCatalogOrder() {
        assertTrue(OpenCVLoader.initLocal());
        Mat train = random(1200, 811L);
        List<Mat> blocks = Arrays.asList(random(7, 1L), random(1200, 2L),
                random(99, 3L), random(11, 4L));
        Mat combined = new Mat();
        try (OrderedMatchingExecutor matcher = new OrderedMatchingExecutor(8, 1)) {
            // Repeated rows exercise equal-distance top-two ordering, including a tie at zero.
            byte[] repeated = new byte[32];
            train.get(0, 0, repeated);
            train.put(1, 0, repeated);
            for (Mat block : blocks) block.put(0, 0, repeated);
            Core.vconcat(blocks, combined);
            NearestDescriptorPairs serial = NearestDescriptorPairs.compute(combined, train);
            List<Callable<NearestDescriptorPairs>> batches = new ArrayList<>();
            for (Mat block : blocks) {
                batches.add(() -> NearestDescriptorPairs.compute(block, train));
            }
            List<NearestDescriptorPairs> parallel = matcher.compute(batches);
            int offset = 0;
            for (int index = 0; index < blocks.size(); index++) {
                int rows = blocks.get(index).rows();
                assertPairsEqual(serial.slice(offset, rows), parallel.get(index));
                offset += rows;
            }
        } finally {
            combined.release();
            for (Mat block : blocks) block.release();
            train.release();
        }
    }

    @Test public void profileSerialAndFourWorkersOnTheRealCatalogWithoutChangingNativePool()
            throws Exception {
        assertTrue(OpenCVLoader.initLocal());
        Context context = ApplicationProvider.getApplicationContext();
        VisionConfig config = VisionConfig.defaults();
        Mat frame = frameDescriptors(config);
        long serialNanos = 0L, parallelNanos = 0L;
        int variantCount;
        try (ReferenceDatabase database = ReferenceDatabase.load(context.getAssets(),
                VisionReferenceFactory.build(AssemblArRepository.getInstance(context)), config,
                new File(context.getNoBackupFilesDir(), "orb_reference_cache_v3"));
             OrderedMatchingExecutor matcher = new OrderedMatchingExecutor(8, 1)) {
            List<ReferenceDatabase.PreparedOrbVariant> variants = new ArrayList<>();
            for (ReferenceDatabase.PreparedReference reference : database.preparedReferences()) {
                if (reference.spec.getAssemblyStepNumber() != 0
                        || reference.spec.isInstalledBracketView()) continue;
                for (ReferenceDatabase.PreparedOrbVariant variant : reference.orbVariants()) {
                    if (variant.hasOrbData() && variant.photometricVariant
                            == ReferenceDatabase.PhotometricVariant.NORMALIZED) variants.add(variant);
                }
            }
            variantCount = variants.size();
            assertTrue("The real catalog must exercise at least four batches", variantCount >= 4);
            List<Callable<List<NearestDescriptorPairs>>> batches = new ArrayList<>();
            int blockSize = (variantCount + 3) / 4;
            for (int first = 0; first < variantCount; first += blockSize) {
                List<ReferenceDatabase.PreparedOrbVariant> block = new ArrayList<>(
                        variants.subList(first, Math.min(variantCount, first + blockSize)));
                batches.add(() -> ComponentRecognizer.computeDescriptorBatch(block, frame));
            }
            for (int repeat = 0; repeat < 3; repeat++) {
                long started = SystemClock.elapsedRealtimeNanos();
                List<NearestDescriptorPairs> serial =
                        ComponentRecognizer.computeDescriptorBatch(variants, frame);
                serialNanos += SystemClock.elapsedRealtimeNanos() - started;
                started = SystemClock.elapsedRealtimeNanos();
                List<List<NearestDescriptorPairs>> parallel = matcher.compute(batches);
                parallelNanos += SystemClock.elapsedRealtimeNanos() - started;
                int index = 0;
                for (List<NearestDescriptorPairs> block : parallel) {
                    for (NearestDescriptorPairs actual : block) {
                        assertPairsEqual(serial.get(index++), actual);
                    }
                }
                assertEquals(variantCount, index);
            }
        } finally {
            frame.release();
        }
        Log.i("NearestPairs", String.format(Locale.US,
                "nativeThreads=%d variants=%d serialNativePoolMs=%.2f forcedFourWorkersMs=%.2f speedup=%.2fx",
                Core.getNumThreads(), variantCount, serialNanos / 3e6,
                parallelNanos / 3e6, serialNanos / (double) parallelNanos));
    }

    @Test public void retainedReferenceRowsStayExactWhenTheLiveFrameChanges() throws Exception {
        assertTrue(OpenCVLoader.initLocal());
        Context context = ApplicationProvider.getApplicationContext();
        VisionConfig config = IndividualPartTestCatalog.visionConfig();
        // Today's independently selected POOL frame, shared with the mismatch regression.
        Mat frame = frameDescriptors(config,
                "references/redmi-room-20261008/nano-gold-usb-horizontal-pool.jpg");
        Mat reorderedFrame = new Mat();
        try (ReferenceDatabase database = ReferenceDatabase.load(context.getAssets(),
                IndividualPartTestCatalog.filter(VisionReferenceFactory.build(
                        AssemblArRepository.getInstance(context))), config)) {
            List<ReferenceDatabase.PreparedOrbVariant> variants = new ArrayList<>();
            for (ReferenceDatabase.PreparedReference reference : database.preparedReferences()) {
                for (ReferenceDatabase.PreparedOrbVariant variant : reference.orbVariants()) {
                    if (variant.hasOrbData()) variants.add(variant);
                }
            }
            assertTrue(variants.size() >= 4);
            Core.flip(frame, reorderedFrame, 0);
            try (PreparedDescriptorBatch retained = new PreparedDescriptorBatch(variants)) {
                for (Mat live : Arrays.asList(frame, reorderedFrame, frame)) {
                    List<NearestDescriptorPairs> fresh =
                            ComponentRecognizer.computeDescriptorBatch(variants, live);
                    List<NearestDescriptorPairs> reused = retained.compute(live);
                    assertEquals(fresh.size(), reused.size());
                    for (int index = 0; index < fresh.size(); index++) {
                        assertPairsEqual(fresh.get(index), reused.get(index));
                    }
                }
            }
        } finally {
            reorderedFrame.release();
            frame.release();
        }
    }

    @Test public void matchesBruteForceKnnOnEveryCatalogReferenceAndIsFaster() throws Exception {
        assertTrue(OpenCVLoader.initLocal());
        Context context = ApplicationProvider.getApplicationContext();
        VisionConfig config = VisionConfig.defaults();
        Mat frame = frameDescriptors(config);
        DescriptorMatcher bruteForce = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING);
        long knnNanos = 0L, batchNanos = 0L;
        int references = 0, rows = 0;
        try (ReferenceDatabase database = ReferenceDatabase.load(context.getAssets(),
                VisionReferenceFactory.build(AssemblArRepository.getInstance(context)), config,
                new File(context.getNoBackupFilesDir(), "orb_reference_cache_v3"))) {
            for (ReferenceDatabase.PreparedReference reference : database.preparedReferences()) {
                for (ReferenceDatabase.PreparedOrbVariant variant : reference.orbVariants()) {
                    if (!variant.hasOrbData()) continue;
                    references++;
                    long started = SystemClock.elapsedRealtimeNanos();
                    List<MatOfDMatch> expected = new ArrayList<>();
                    bruteForce.knnMatch(variant.descriptors, frame, expected, 2);
                    DMatch[][] expectedPairs = new DMatch[expected.size()][];
                    for (int row = 0; row < expected.size(); row++) {
                        expectedPairs[row] = expected.get(row).toArray();
                        expected.get(row).release();
                    }
                    knnNanos += SystemClock.elapsedRealtimeNanos() - started;

                    started = SystemClock.elapsedRealtimeNanos();
                    NearestDescriptorPairs actual = NearestDescriptorPairs.compute(variant.descriptors, frame);
                    batchNanos += SystemClock.elapsedRealtimeNanos() - started;

                    String label = reference.spec.getReferenceId() + "/" + variant.photometricVariant;
                    assertEquals(label, expectedPairs.length, actual.size());
                    for (int row = 0; row < expectedPairs.length; row++) {
                        DMatch[] pair = expectedPairs[row];
                        assertEquals(label + " row " + row, pair.length >= 2, actual.hasTwo(row));
                        if (pair.length < 2) continue;
                        DMatch best = actual.best(row);
                        assertEquals(label, pair[0].queryIdx, best.queryIdx);
                        assertEquals(label, pair[0].trainIdx, best.trainIdx);
                        assertEquals(label, pair[0].distance, best.distance, 0f);
                        assertEquals(label, pair[1].distance, actual.secondDistance(row), 0f);
                    }
                    rows += expectedPairs.length;
                }
            }
        } finally {
            frame.release();
            bruteForce.clear();
        }
        Log.i("NearestPairs", String.format(Locale.US,
                "variants=%d queryRows=%d knnMatchMs=%.1f batchDistanceMs=%.1f speedup=%.1fx",
                references, rows, knnNanos / 1e6, batchNanos / 1e6, knnNanos / (double) batchNanos));
        assertTrue("Catalog must contain prepared references", references > 0);
        assertTrue("One-call matching must be faster than the per-row JNI conversion",
                batchNanos < knnNanos);
    }

    @Test public void singleTrainRowAndEmptyInputsBehaveLikeKnnMatch() {
        assertTrue(OpenCVLoader.initLocal());
        Mat query = random(40, 7L), single = random(1, 11L), empty = new Mat();
        DescriptorMatcher bruteForce = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING);
        try {
            List<MatOfDMatch> expected = new ArrayList<>();
            bruteForce.knnMatch(query, single, expected, 2);
            NearestDescriptorPairs actual = NearestDescriptorPairs.compute(query, single);
            assertEquals(expected.size(), actual.size());
            for (int row = 0; row < expected.size(); row++) {
                assertEquals(expected.get(row).toArray().length >= 2, actual.hasTwo(row));
                expected.get(row).release();
            }
            assertEquals(0, NearestDescriptorPairs.compute(query, empty).size());
            assertEquals(0, NearestDescriptorPairs.compute(empty, single).size());
        } finally {
            query.release();
            single.release();
            bruteForce.clear();
        }
    }

    /** Descriptors of a real kit photo, prepared exactly as the recognizer prepares frames. */
    private static Mat frameDescriptors(VisionConfig config) throws Exception {
        return frameDescriptors(config, "references/assembly-replay/brackets-2-133355.jpg");
    }

    private static Mat frameDescriptors(VisionConfig config, String fixture) throws Exception {
        byte[] bytes;
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                .open(fixture)) {
            bytes = input.readAllBytes();
        }
        MatOfByte encoded = new MatOfByte(bytes);
        Mat gray = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE);
        Mat normalized = new Mat();
        MatOfKeyPoint keypoints = new MatOfKeyPoint();
        Mat descriptors = new Mat();
        ORB orb = OpenCvVisionUtils.createOrb(config);
        CLAHE clahe = OpenCvVisionUtils.createClahe(config);
        try {
            clahe.apply(gray, normalized);
            orb.detectAndCompute(normalized, new Mat(), keypoints, descriptors);
            assertTrue("Frame must produce descriptors", descriptors.rows() > 100);
            return descriptors;
        } finally {
            orb.clear();
            clahe.clear();
            encoded.release();
            gray.release();
            normalized.release();
            keypoints.release();
        }
    }

    private static Mat random(int rows, long seed) {
        Mat descriptors = new Mat(rows, 32, CvType.CV_8UC1);
        byte[] bytes = new byte[rows * 32];
        new Random(seed).nextBytes(bytes);
        descriptors.put(0, 0, bytes);
        return descriptors;
    }
}
