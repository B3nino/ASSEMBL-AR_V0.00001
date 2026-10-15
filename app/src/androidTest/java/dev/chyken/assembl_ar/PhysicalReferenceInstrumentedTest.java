package dev.chyken.assembl_ar;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.ComponentRecognizer;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.RecognitionRequest;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.ReferenceDatabase;
import dev.chyken.assembl_ar.vision.VisionConfig;
import dev.chyken.assembl_ar.vision.VisionPoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Real supplied photographs test identity and cross-part negatives, not measured pose accuracy. */
@RunWith(AndroidJUnit4.class)
public final class PhysicalReferenceInstrumentedTest {
    @Test
    public void expectedLabelCannotBreakAnIndistinguishableReferenceTie() throws Exception {
        Context context = context();
        VisionConfig config = VisionConfig.defaults();
        ComponentReferenceSpec actual = null;
        for (ComponentReferenceSpec spec : production(context)) {
            if (spec.getJpegAssetPath() != null && spec.getJpegAssetPath().endsWith(
                    "physical/extension-board-white-top.jpg")) actual = spec;
        }
        assertTrue(actual != null);
        List<VisionPoint> corners = actual.getPlanarCornersNormalized();
        // An intentionally conflicting registration reproduces the case where both labels have
        // indistinguishable texture/geometry evidence. It is never part of the production catalog.
        ComponentReferenceSpec conflicting = ComponentReferenceSpec.builder(
                        "test_conflicting_board", NeobotSeedData.MAIN_BOARD, "Conflicting board",
                        actual.getPhysicalWidthMm(), actual.getPhysicalHeightMm())
                .jpegAssetPath(actual.getJpegAssetPath()).maximumImageDimension(1280)
                .planarReferenceCornersNormalized(corners.get(0), corners.get(1),
                        corners.get(2), corners.get(3)).build();
        Mat photograph = decode(context, actual.getJpegAssetPath());
        Mat frame = resize(photograph, 800);
        try (ReferenceDatabase database = ReferenceDatabase.load(
                context.getAssets(), Arrays.asList(actual, conflicting), config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            for (String id : new String[]{NeobotSeedData.MAIN_BOARD, NeobotSeedData.EXTENSION_BOARD}) {
                RecognitionResult result = recognizer.recognize(frame, 601L, expected(id));
                assertEquals(describe(result), RecognitionResult.ValidationState.NO_COMPONENT,
                        result.getValidationState());
                org.junit.Assert.assertNull(result.getPose());
            }
        } finally {
            frame.release();
            photograph.release();
        }
    }

    @Test
    public void physicalCatalogPreparesAllReviewedPhotos() throws Exception {
        Context context = context();
        List<ComponentReferenceSpec> physical = new ArrayList<>();
        for (ComponentReferenceSpec spec : production(context)) {
            if (spec.getReferenceId().contains("_physical_")) physical.add(spec);
        }
        assertEquals(6, physical.size());
        try (ReferenceDatabase database = ReferenceDatabase.load(
                context.getAssets(), physical, VisionConfig.defaults())) {
            assertEquals("A curated source must not silently fail preparation", 6, database.size());
            assertEquals(3, database.getComponentIds().size());
        }
    }

    @Test
    public void whiteMainAndExtensionBoardsCannotStandInForEachOther() throws Exception {
        Context context = context();
        VisionConfig config = VisionConfig.defaults();
        List<ComponentReferenceSpec> boards = new ArrayList<>();
        for (ComponentReferenceSpec spec : production(context)) {
            if (NeobotSeedData.MAIN_BOARD.equals(spec.getComponentId())
                    || NeobotSeedData.EXTENSION_BOARD.equals(spec.getComponentId())) {
                boards.add(spec);
            }
        }
        try (ReferenceDatabase database = ReferenceDatabase.load(context.getAssets(), boards, config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            checkBoard(recognizer, context,
                    "references/physical/extension-board-white-top.jpg",
                    NeobotSeedData.EXTENSION_BOARD, NeobotSeedData.MAIN_BOARD);
            checkBoard(recognizer, InstrumentationRegistry.getInstrumentation().getContext(),
                    "references/camera/main-board-white-top-camera.jpg",
                    NeobotSeedData.MAIN_BOARD, NeobotSeedData.EXTENSION_BOARD);
        }
    }

    @Test
    public void heldOutNanoPhotoMatchesButTableBackgroundDoesNot() throws Exception {
        Context context = context();
        VisionConfig config = VisionConfig.defaults();
        List<ComponentReferenceSpec> electronics = new ArrayList<>();
        for (ComponentReferenceSpec spec : production(context)) {
            if (NeobotSeedData.MICROCONTROLLER.equals(spec.getComponentId())
                    || NeobotSeedData.MOTOR_DRIVER.equals(spec.getComponentId())) {
                electronics.add(spec);
            }
        }
        Mat photograph = decode(InstrumentationRegistry.getInstrumentation().getContext(),
                "references/camera/nano-top-held-out.jpg");
        Mat frame = resize(photograph, 960);
        Mat background = new Mat(photograph, new Rect(400, 600, 1600, 950));
        Mat backgroundFrame = resize(background, 640);
        try (ReferenceDatabase database = ReferenceDatabase.load(context.getAssets(), electronics, config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            RecognitionResult nano = recognizer.recognize(frame, 401L,
                    expected(NeobotSeedData.MICROCONTROLLER));
            assertEquals(describe(nano), RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                    nano.getValidationState());
            assertEquals(NeobotSeedData.MICROCONTROLLER, nano.getComponentId());
            RecognitionResult table = recognizer.recognize(backgroundFrame, 402L,
                    expected(NeobotSeedData.MICROCONTROLLER));
            assertFalse("Background features must not validate a part: " + describe(table),
                    table.getValidationState() == RecognitionResult.ValidationState.EXPECTED_COMPONENT);
        } finally {
            backgroundFrame.release();
            background.release();
            frame.release();
            photograph.release();
        }
    }

    private static void checkBoard(ComponentRecognizer recognizer, Context context,
                                   String path, String actualId, String wrongId) throws Exception {
        Mat photograph = decode(context, path);
        Mat frame = resize(photograph, 800);
        Mat rotated = new Mat();
        try {
            Core.rotate(frame, rotated, Core.ROTATE_180);
            for (Mat query : new Mat[]{frame, rotated}) {
                RecognitionResult correct = recognizer.recognize(query, 501L, expected(actualId));
                assertEquals(path + " " + describe(correct),
                        RecognitionResult.ValidationState.EXPECTED_COMPONENT, correct.getValidationState());
                assertEquals(actualId, correct.getComponentId());
                RecognitionResult incorrect = recognizer.recognize(query, 502L, expected(wrongId));
                assertFalse("Shared holes/outline cannot validate the other PCB: " + path
                                + " " + describe(incorrect),
                        incorrect.getValidationState()
                                == RecognitionResult.ValidationState.EXPECTED_COMPONENT);
            }
        } finally {
            rotated.release();
            frame.release();
            photograph.release();
        }
    }

    private static RecognitionRequest expected(String id) {
        return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id), null, null);
    }

    private static Context context() {
        assertTrue(OpenCVLoader.initLocal());
        return ApplicationProvider.getApplicationContext();
    }

    private static List<ComponentReferenceSpec> production(Context context) {
        return VisionReferenceFactory.build(AssemblArRepository.getInstance(context));
    }

    private static Mat resize(Mat source, int maximumSide) {
        Mat resized = new Mat();
        double scale = maximumSide / (double) Math.max(source.cols(), source.rows());
        Imgproc.resize(source, resized, new Size(Math.round(source.cols() * scale),
                Math.round(source.rows() * scale)), 0.0, 0.0, Imgproc.INTER_AREA);
        return resized;
    }

    private static Mat decode(Context context, String path) throws Exception {
        try (InputStream input = context.getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            MatOfByte encoded = new MatOfByte(output.toByteArray());
            try {
                Mat decoded = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE);
                assertFalse(path, decoded.empty());
                return decoded;
            } finally {
                encoded.release();
            }
        }
    }

    private static String describe(RecognitionResult result) {
        return "state=" + result.getValidationState() + " component=" + result.getComponentId()
                + " ref=" + result.getReferenceId() + " matches=" + result.getGoodMatchCount()
                + " inliers=" + result.getInlierCount();
    }
}
