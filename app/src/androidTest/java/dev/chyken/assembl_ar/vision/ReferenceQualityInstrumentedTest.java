package dev.chyken.assembl_ar.vision;

import android.content.Context;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import static org.junit.Assert.*;

/** Native preparation audit; self-image diagnostics are explicitly not held-out accuracy. */
@RunWith(AndroidJUnit4.class)
public final class ReferenceQualityInstrumentedTest {
    @Test public void everyActiveImageHasIndependentMaskedFeatures() throws Exception {
        assertTrue(OpenCVLoader.initLocal());
        Context context = ApplicationProvider.getApplicationContext();
        VisionConfig config = VisionConfig.defaults();
        for (ComponentReferenceSpec spec : VisionReferenceFactory.build(
                AssemblArRepository.getInstance(context))) {
            if (spec.isAdministratorReference()) continue;
            try (ReferenceDatabase database = ReferenceDatabase.load(context.getAssets(),
                    Collections.singletonList(spec), config);
                 ComponentRecognizer recognizer = new ComponentRecognizer(config, database)) {
                ReferenceDatabase.PreparedReference prepared = database.preparedReferences().get(0);
                int features = 0;
                for (ReferenceDatabase.PreparedOrbVariant variant : prepared.orbVariants) {
                    features = Math.max(features, variant.keypoints.length);
                }
                assertTrue(spec.getReferenceId() + " has too few native masked features: " + features,
                        features >= config.getMinimumGoodMatches());
                Mat encoded = new MatOfByte(read(context, spec.getJpegAssetPath()));
                Mat source = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE);
                Mat scaled = new Mat();
                Mat frame = new Mat();
                try {
                    double scale = Math.min(1.0, 1000.0 / Math.max(source.cols(), source.rows()));
                    Imgproc.resize(source, scaled, new Size(), scale, scale, Imgproc.INTER_AREA);
                    Core.copyMakeBorder(scaled, frame, 100, 100, 100, 100,
                            Core.BORDER_CONSTANT, Scalar.all(127));
                    double focal = Math.max(frame.cols(), frame.rows()) * 1.2;
                    RecognitionRequest request = new RecognitionRequest(
                            RecognitionRequest.AssemblyExpectation.component(spec.getComponentId()),
                            CameraCalibration.pinhole(focal, focal, frame.cols()/2.0, frame.rows()/2.0),
                            null, null, 1, spec.getAssemblyStepNumber());
                    RecognitionResult result = recognizer.recognize(frame, 1L, request);
                    Log.i("AssemblArAudit", spec.getReferenceId() + "|features=" + features
                            + "|method=" + result.getDetectionMethod()
                            + "|inliers=" + result.getInlierCount()
                            + "|pose=" + (result.getPose() != null)
                            + "|selfImageOnly=true");
                } finally {
                    frame.release(); scaled.release(); source.release(); encoded.release();
                }
            }
        }
    }

    private static byte[] read(Context context, String path) throws Exception {
        try (InputStream input = context.getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            return output.toByteArray();
        }
    }
}
