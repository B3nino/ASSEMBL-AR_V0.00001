package dev.chyken.assembl_ar.vision;

import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;

import dev.chyken.assembl_ar.camera.SalvagedPhysicalReferenceCatalog;
import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;

import static org.junit.Assert.*;

/** Native default-threshold checks; same-source registration and held-out coverage stay distinct. */
@RunWith(AndroidJUnit4.class)
public final class RecoveredPhysicalRecognitionInstrumentedTest {
    @BeforeClass public static void loadNative() { assertTrue(OpenCVLoader.initLocal()); }

    @Test public void productionIncludesOnlyTheReviewedSourcesAndPreservesOriginalBytes() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        List<ComponentReferenceSpec> specs = VisionReferenceFactory.build(AssemblArRepository.getInstance(app));
        for (ComponentReferenceSpec recovered : SalvagedPhysicalReferenceCatalog.references()) {
            assertTrue(recovered.getReferenceId(), specs.stream().anyMatch(s ->
                    recovered.getReferenceId().equals(s.getReferenceId())
                            && recovered.getJpegAssetPath().equals(s.getJpegAssetPath())));
            assertFalse("No source establishes electrical polarity", recovered.hasVerifiedPolarityAnchors());
        }
        assertTrue("Opposite printed face needs its own registration", specs.stream().anyMatch(s ->
                s.getJpegAssetPath() != null && s.getJpegAssetPath().contains("battery-label-opposite")));
        assertEquals("ef116af731dcffcd035c834f9964bd886469fca4331cb8a1c12aa68fc41e9723",
                sha256(app, "references/recovered/battery-label.jpg"));
        assertEquals("f1fa3651af3c254848f7979121ea0636e531501c8cf8c39f0927cb1fa9e8e0d9",
                sha256(app, "references/recovered/wheel-five-spoke.jpg"));
        assertEquals("91c6ebc0823ac524a1445dd5b0f7a257819ec17938677c3dfdf0241ba765106e",
                sha256(app, "references/recovered/battery-label-opposite.jpg"));
    }

    @Test public void sameSourceBatteryAndWheelRegistrationsStayOnTheirActualFaces() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        VisionConfig config = VisionConfig.defaults();
        try (ReferenceDatabase db = productionDatabase(app, config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, db)) {
            for (ComponentReferenceSpec spec : SalvagedPhysicalReferenceCatalog.references()) {
                Mat rgb = ProductRecognitionInstrumentedTest.decode(app, spec.getJpegAssetPath());
                try {
                    RecognitionResult result = recognize(recognizer, rgb, spec.getComponentId());
                    log("same-source-registration", spec.getJpegAssetPath(), result);
                    assertEquals(spec.getReferenceId(), spec.getComponentId(), result.getComponentId());
                    assertEquals(RecognitionResult.ValidationState.EXPECTED_COMPONENT, result.getValidationState());
                    assertNotNull("Default pipeline must estimate a usable pose", result.getPose());
                    assertGeometryCloseToReference(spec, result, rgb);
                    assertNull("A loose-part image must not create an assembly board", result.getSpatialAnchor());
                } finally { rgb.release(); }
            }
        }
    }

    @Test public void unseenOppositeLabelDoesNotBorrowGeometryFromTheFirstFace() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        VisionConfig config = VisionConfig.defaults();
        List<ComponentReferenceSpec> firstFaceOnly = new java.util.ArrayList<>(
                VisionReferenceFactory.build(AssemblArRepository.getInstance(app)));
        firstFaceOnly.removeIf(s -> s.getJpegAssetPath() != null
                && s.getJpegAssetPath().contains("battery-label-opposite"));
        Mat rgb = ProductRecognitionInstrumentedTest.decode(app,"references/recovered/battery-label-opposite.jpg");
        try (ReferenceDatabase db = ReferenceDatabase.load(app.getAssets(), firstFaceOnly, config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, db)) {
            RecognitionResult result = recognize(recognizer,rgb,NeobotSeedData.BATTERY_PACK);
            assertFalse("Different label text must not provide a false plane correspondence",
                    NeobotSeedData.BATTERY_PACK.equals(result.getComponentId()) && result.getPose()!=null);
        } finally {rgb.release();}
    }

    @Test public void originalClutterCannotSupplyAnIdentityAfterThePartIsOccluded() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        VisionConfig config = VisionConfig.defaults();
        try (ReferenceDatabase db = productionDatabase(app, config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, db)) {
            for (ComponentReferenceSpec spec : SalvagedPhysicalReferenceCatalog.references()) {
                Mat rgb = ProductRecognitionInstrumentedTest.decode(app, spec.getJpegAssetPath());
                try {
                    // Controlled negative only: erase the entire registered target rectangle plus
                    // margin, retaining the original desk/jumpers/leads outside it. This edited
                    // frame is never saved or used as a training reference.
                    double minX = 1, minY = 1, maxX = 0, maxY = 0;
                    for (VisionPoint p : spec.getPlanarCornersNormalized()) {
                        minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY());
                        maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY());
                    }
                    Imgproc.rectangle(rgb, new Point((minX-.035)*rgb.cols(), (minY-.035)*rgb.rows()),
                            new Point((maxX+.035)*rgb.cols(), (maxY+.035)*rgb.rows()), Scalar.all(160), -1);
                    RecognitionResult result = recognize(recognizer, rgb, spec.getComponentId());
                    log("target-erased-negative", spec.getJpegAssetPath(), result);
                    assertNotEquals("Desk or adjacent wires must not become the removed target",
                            spec.getComponentId(), result.getComponentId());
                } finally { rgb.release(); }
            }
        }
    }

    @Test public void batteryAndWheelCannotBeConfusedWithEachOther() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        VisionConfig config = VisionConfig.defaults();
        try (ReferenceDatabase db = productionDatabase(app, config);
             ComponentRecognizer recognizer = new ComponentRecognizer(config, db)) {
            for (ComponentReferenceSpec spec : SalvagedPhysicalReferenceCatalog.references()) {
                Mat rgb = ProductRecognitionInstrumentedTest.decode(app, spec.getJpegAssetPath());
                try {
                    String wrong = NeobotSeedData.WHEEL.equals(spec.getComponentId())
                            ? NeobotSeedData.BATTERY_PACK : NeobotSeedData.WHEEL;
                    RecognitionResult result = recognize(recognizer, rgb, wrong);
                    assertFalse(wrong.equals(result.getComponentId())
                            && result.getValidationState() == RecognitionResult.ValidationState.EXPECTED_COMPONENT);
                } finally { rgb.release(); }
            }
        }
    }

    private static ReferenceDatabase productionDatabase(Context app, VisionConfig config) throws Exception {
        return ReferenceDatabase.load(app.getAssets(),
                VisionReferenceFactory.build(AssemblArRepository.getInstance(app)), config);
    }

    private static RecognitionResult recognize(ComponentRecognizer recognizer, Mat rgb, String id) {
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);
            double focal = Math.max(rgb.cols(), rgb.rows()) * 1.2;
            RecognitionRequest request = new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(id),
                    CameraCalibration.pinhole(focal, focal, rgb.cols()/2.0, rgb.rows()/2.0), null);
            return recognizer.recognize(gray, System.nanoTime(), request);
        } finally { gray.release(); }
    }

    private static void assertGeometryCloseToReference(ComponentReferenceSpec spec,
                                                      RecognitionResult result, Mat rgb) {
        assertEquals(spec.getReferenceId(), 4, result.getProjectedCorners().size());
        // Wheel symmetry can rotate corner correspondence; compare center and area, not angle.
        double expectedX = 0, expectedY = 0, observedX = 0, observedY = 0;
        for (int index = 0; index < 4; index++) {
            VisionPoint expected = spec.getPlanarCornersNormalized().get(index);
            VisionPoint observed = result.getProjectedCorners().get(index);
            expectedX += expected.getX()/4; expectedY += expected.getY()/4;
            observedX += observed.getX()/rgb.cols()/4; observedY += observed.getY()/rgb.rows()/4;
        }
        assertEquals(spec.getReferenceId() + " center x", expectedX, observedX, .035);
        assertEquals(spec.getReferenceId() + " center y", expectedY, observedY, .035);
        double expectedArea = area(spec.getPlanarCornersNormalized());
        double actualArea = area(result.getProjectedCorners()) / rgb.cols() / rgb.rows();
        assertTrue(spec.getReferenceId() + " registered face area", actualArea / expectedArea > .70
                && actualArea / expectedArea < 1.30);
    }

    private static double area(List<VisionPoint> points) {
        double sum = 0;
        for (int index = 0; index < points.size(); index++) {
            VisionPoint a = points.get(index), b = points.get((index + 1) % points.size());
            sum += a.getX()*b.getY() - b.getX()*a.getY();
        }
        return Math.abs(sum)/2;
    }

    private static void log(String role, String image, RecognitionResult result) {
        Log.i("RecoveredRecognition", role + " image=" + image + " id=" + result.getComponentId()
                + " method=" + result.getDetectionMethod() + " pose=" + (result.getPose()!=null)
                + " matches=" + result.getGoodMatchCount() + " inliers=" + result.getInlierCount()
                + " rejection=" + (result.getRejectionEvidence()==null ? "none"
                : result.getRejectionEvidence().getReason()));
    }

    private static String sha256(Context context, String path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = context.getAssets().open(path)) {
            byte[] buffer = new byte[8192]; int length;
            while ((length = in.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        StringBuilder out = new StringBuilder();
        for (byte value : digest.digest()) out.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        return out.toString();
    }
}
