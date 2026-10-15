package dev.chyken.assembl_ar.vision;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

import java.util.List;

import dev.chyken.assembl_ar.camera.IndividualPartTestCatalog;
import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class RecognitionDiagnosticsInstrumentedTest {
    @Test
    public void emptyFrameReportsExpectedReferenceAndFeatureCounts() throws Exception {
        assertTrue(OpenCVLoader.initLocal());
        Context context = ApplicationProvider.getApplicationContext();
        VisionConfig config = IndividualPartTestCatalog.visionConfig();
        List<ComponentReferenceSpec> references = IndividualPartTestCatalog.filter(
                VisionReferenceFactory.build(AssemblArRepository.getInstance(context)));
        Mat blank = new Mat(720, 1280, CvType.CV_8UC1, new Scalar(0));
        try (ReferenceDatabase database = ReferenceDatabase.load(
                context.getAssets(), references, config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
            RecognitionResult result = recognizer.recognize(blank, 1001L,
                    IndividualPartTestCatalog.requestFor(NeobotSeedData.GEAR_MOTOR, null));

            assertEquals(RecognitionResult.DetectionMethod.NONE, result.getDetectionMethod());
            RecognitionResult.RejectionEvidence evidence = result.getRejectionEvidence();
            assertNotNull(evidence);
            assertEquals(RecognitionResult.RejectionReason.NO_FRAME_DESCRIPTORS,
                    evidence.getReason());
            assertEquals(NeobotSeedData.GEAR_MOTOR, evidence.getComponentId());
            assertNotNull(evidence.getReferenceId());
            assertEquals(0, evidence.getFrameKeypointCount());
            assertTrue(evidence.getReferenceKeypointCount() >= 4);
            assertEquals(0, evidence.getGoodMatchCount());
            assertEquals(-1, evidence.getValidMatchCount());
            assertEquals(-1, evidence.getInlierCount());
            assertEquals(config.getMinimumGoodMatches(),
                    evidence.getRequiredGoodMatchCount());
            assertEquals(config.getMinimumInliers(), evidence.getRequiredInlierCount());
            assertEquals(config.getMinimumInlierRatio(),
                    evidence.getRequiredInlierRatio(), 0.0);
        } finally {
            blank.release();
        }
    }
}
