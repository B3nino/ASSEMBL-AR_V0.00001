package dev.chyken.assembl_ar.vision;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import dev.chyken.assembl_ar.camera.IndividualPartTestCatalog;
import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import static org.junit.Assert.*;

/** Native Parts Test/assembly loose-camera policy on POOL captures, with identity competition.
 * This exercises frame decisions, not the four-frame UI card workflow or phone latency target.
 */
@RunWith(AndroidJUnit4.class)
public final class RedmiRoomIdentityInstrumentedTest {
    private static final String BASE = "references/redmi-room-20261008/";
    private static final List<String> PARTS = Arrays.asList(NeobotSeedData.MICROCONTROLLER,
            NeobotSeedData.DISTANCE_SENSOR, NeobotSeedData.LINE_TRACKER, NeobotSeedData.CASTER_WHEEL,
            NeobotSeedData.MOTOR_DRIVER, NeobotSeedData.MOTOR_BRACKET, NeobotSeedData.GEAR_MOTOR);
    @BeforeClass public static void loadNative() { assertTrue(OpenCVLoader.initLocal()); }

    @Test public void catalogCompetitionCannotAcceptAnotherPartOrClaimAGoldUsbMotor() throws Exception {
        Context app = ApplicationProvider.getApplicationContext();
        List<ComponentReferenceSpec> production = VisionReferenceFactory.build(AssemblArRepository.getInstance(app));
        JSONObject manifest = new JSONObject(new String(read(BASE+"audit-provenance.json"), StandardCharsets.UTF_8));
        JSONArray fixtures = manifest.getJSONArray("fixtures");
        assertEquals(3, fixtures.length());
        for (boolean diagnostic : new boolean[]{true, false}) {
            VisionConfig config = diagnostic ? IndividualPartTestCatalog.visionConfig() : VisionConfig.defaults();
            List<ComponentReferenceSpec> specs = diagnostic ? IndividualPartTestCatalog.filter(production) : production;
            try (ReferenceDatabase db = ReferenceDatabase.load(app.getAssets(), specs, config);
                 ComponentRecognizer recognizer = new ComponentRecognizer(config, db)) {
                for (int index=0; index<fixtures.length(); index++) {
                    JSONObject fixture = fixtures.getJSONObject(index);
                    assertEquals("POOL", fixture.getString("split"));
                    String actual=fixture.getString("actual"), file=fixture.getString("file");
                    MatOfByte bytes=new MatOfByte(read(BASE+file));
                    Mat bgr=Imgcodecs.imdecode(bytes, Imgcodecs.IMREAD_COLOR), rgb=new Mat(), gray=new Mat();
                    try {
                        Imgproc.cvtColor(bgr,rgb,Imgproc.COLOR_BGR2RGB);
                        Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
                        CameraCalibration camera=CameraCalibration.pinhole(655.07794,655.07788,359,250);
                        for (String expected : PARTS) {
                            String label=file+" target="+expected+" diagnostic="+diagnostic;
                            RecognitionRequest request = diagnostic ? IndividualPartTestCatalog.requestFor(expected,camera)
                                    : new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(expected),camera,null);
                            RecognitionResult result=recognizer.recognize(gray, index+1,request);
                            result=LooseOrbEvidenceGate.validate(request,result);
                            result=new LoosePartShapeRecognizer().recognize(gray,rgb,index+1,request,result);
                            result=LoosePartMismatchRecognizer.recognize(gray,rgb,index+1,request,result);
                            if (expected.equals(actual)) {
                                // The two Nano frames are the phone's gold-USB "gear motor" false claims;
                                // they test the veto below. Neither the native pipeline nor the Nano shape
                                // replica recognizes the Nano in them (host strict-ORB replay did), so the
                                // correct part is only required never to be misnamed here.
                                if (result.hasLocatedComponent()) {
                                    assertEquals(label, actual, result.getComponentId());
                                    assertEquals(label, RecognitionResult.ValidationState.EXPECTED_COMPONENT,
                                            result.getValidationState());
                                }
                                assertFalse(label, result.getValidationState()
                                        == RecognitionResult.ValidationState.COMPONENT_MISMATCH);
                            } else {
                                assertFalse(label,result.getValidationState()==RecognitionResult.ValidationState.EXPECTED_COMPONENT);
                                if (result.getValidationState()==RecognitionResult.ValidationState.COMPONENT_MISMATCH) {
                                    assertTrue(label,result.hasLocatedComponent());
                                    assertEquals(label,actual,result.getComponentId());
                                }
                            }
                        }
                    } finally { gray.release(); rgb.release(); bgr.release(); bytes.release(); }
                }
            }
        }
    }

    @Test public void descriptorDiagnosticsCannotBecomeNativeNamedFeedbackOrAcceptance() {
        for (String identity : PARTS) {
            RecognitionResult result=RecognitionResult.builder(1).identity("diagnostic",identity,identity)
                    .method(RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY)
                    .validation(RecognitionResult.ValidationState.COMPONENT_MISMATCH)
                    .matches(1200,25,0,0).confidence(.65).build();
            assertFalse(result.hasLocatedComponent());
            assertEquals(RecognitionResult.ValidationState.NO_COMPONENT,result.getValidationState());
            assertEquals(0,result.getDetectedInstanceCount(identity));
        }
    }

    private static byte[] read(String path) throws Exception {
        try (InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(path);
             ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] bytes=new byte[8192]; int count;
            while ((count=input.read(bytes))!=-1) output.write(bytes,0,count);
            return output.toByteArray();
        }
    }
}
