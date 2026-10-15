package dev.chyken.assembl_ar.vision;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Exercises the ASOR-3 multi-representation container and legacy-image fallback. */
@RunWith(AndroidJUnit4.class)
public final class AdminDescriptorBlobCodecInstrumentedTest {
    private static final String ASSET = "references/assembly/image-001.png";

    private Context context;
    private VisionConfig config;

    @Before
    public void initializeOpenCv() {
        assertTrue("The packaged OpenCV runtime must initialize", OpenCVLoader.initLocal());
        context = ApplicationProvider.getApplicationContext();
        config = VisionConfig.defaults();
    }

    @Test
    public void roundTripContainsBothPhotometricVariantsAndRejectsWrongConfig() throws Exception {
        Mat fixture = decodeAsset();
        try {
            byte[] blob = AdminDescriptorBlobCodec.computeAndEncode(fixture, config);
            try (AdminDescriptorBlobCodec.DecodedDescriptorBlob decoded =
                         AdminDescriptorBlobCodec.decode(blob, config)) {
                assertEquals(fixture.cols(), decoded.imageWidth);
                assertEquals(fixture.rows(), decoded.imageHeight);
                assertEquals(2, decoded.variants.size());
                Set<ReferenceDatabase.PhotometricVariant> types =
                        EnumSet.noneOf(ReferenceDatabase.PhotometricVariant.class);
                for (AdminDescriptorBlobCodec.DescriptorVariant variant : decoded.variants) {
                    assertTrue(types.add(variant.photometricVariant));
                    assertTrue(variant.keypoints.length
                            >= config.getMinimumGoodMatches() * 2);
                    assertFalse(variant.descriptors.empty());
                }
                assertEquals(EnumSet.allOf(ReferenceDatabase.PhotometricVariant.class), types);
            }

            expectInvalid(blob, VisionConfig.builder().clahe(3.0, 8, 8).build());
            expectInvalid(Arrays.copyOf(blob, blob.length / 2), config);
        } finally {
            fixture.release();
        }
    }

    @Test
    public void canonicalGuideMaskKeepsBackgroundOutOfBothVariants() throws Exception {
        Mat fixture = decodeAsset();
        List<VisionPoint> guide = AdminReferenceGeometry.cornersForCapture(
                AdminReferenceGeometry.FRONT_AZIMUTH_DEGREES,
                150.0 / 130.0,
                fixture.cols() / (double) fixture.rows());
        Mat mask = AdminReferenceGeometry.createDescriptorMask(
                fixture.cols(), fixture.rows(), guide);
        try {
            byte[] blob = AdminDescriptorBlobCodec.computeAndEncode(fixture, mask, config);
            try (AdminDescriptorBlobCodec.DecodedDescriptorBlob decoded =
                         AdminDescriptorBlobCodec.decode(blob, config)) {
                for (AdminDescriptorBlobCodec.DescriptorVariant variant : decoded.variants) {
                    for (org.opencv.core.KeyPoint point : variant.keypoints) {
                        double normalizedX = point.pt.x / (fixture.cols() - 1.0);
                        double normalizedY = point.pt.y / (fixture.rows() - 1.0);
                        assertTrue(normalizedX >= guide.get(0).getX() - 0.005
                                && normalizedX <= guide.get(1).getX() + 0.005);
                        assertTrue(normalizedY >= guide.get(0).getY() - 0.005
                                && normalizedY <= guide.get(3).getY() + 0.005);
                    }
                }
            }
        } finally {
            mask.release();
            fixture.release();
        }
    }

    @Test
    public void texturelessCaptureIsRejectedBeforeRegistrationCanClaimSuccess() {
        Mat textureless = new Mat(320, 240, CvType.CV_8UC1, new Scalar(128.0));
        try {
            try {
                AdminDescriptorBlobCodec.computeAndEncode(textureless, config);
                fail("A reference that cannot meet the matcher gate must be rejected");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("feature"));
            }
        } finally {
            textureless.release();
        }
    }

    @Test
    public void legacyAdminBlobRebuildsFromItsRetainedPrivateImage() throws Exception {
        Mat fixture = decodeAsset();
        File localImage = new File(context.getCacheDir(),
                "asor3_legacy_admin_reference_fixture.png");
        try {
            assertTrue(Imgcodecs.imwrite(localImage.getAbsolutePath(), fixture));
            byte[] legacyBlob = AdminDescriptorBlobCodec.computeAndEncode(fixture, config);
            legacyBlob[4] = 0;
            legacyBlob[5] = 0;
            legacyBlob[6] = 0;
            legacyBlob[7] = 2;
            ComponentReferenceSpec spec = ComponentReferenceSpec.builder(
                            "legacy_admin", "main_board", "Legacy admin board", 150.0, 130.0)
                    .adminDescriptorBlob(legacyBlob)
                    .localImageFilePath(localImage.getAbsolutePath())
                    .build();

            try (ReferenceDatabase database = ReferenceDatabase.load(
                    context.getAssets(), Collections.singletonList(spec), config)) {
                assertEquals(1, database.size());
                ReferenceDatabase.PreparedReference prepared =
                        database.preparedReferences().get(0);
                assertEquals(2, prepared.orbVariants().size());
                assertTrue(prepared.hasOrbData());
            }
        } finally {
            fixture.release();
            if (localImage.exists() && !localImage.delete()) {
                throw new IOException("Unable to remove test-owned reference image");
            }
        }
    }

    private Mat decodeAsset() throws IOException {
        MatOfByte encoded = new MatOfByte();
        try {
            encoded.fromArray(readFully(context.getAssets().open(ASSET)));
            Mat decoded = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE);
            if (decoded.empty()) {
                decoded.release();
                throw new IOException("Unable to decode test fixture");
            }
            return decoded;
        } finally {
            encoded.release();
        }
    }

    private static byte[] readFully(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int count;
            while ((count = source.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void expectInvalid(byte[] blob, VisionConfig config) {
        try {
            AdminDescriptorBlobCodec.decode(blob, config).close();
            fail("Expected an incompatible descriptor blob to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected: stale/truncated blobs cannot enter the live catalog.
        }
    }
}
