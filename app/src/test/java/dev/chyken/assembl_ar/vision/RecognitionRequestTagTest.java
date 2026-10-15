package dev.chyken.assembl_ar.vision;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class RecognitionRequestTagTest {
    private static final CameraCalibration CALIBRATION = CameraCalibration.pinhole(500, 500, 320, 240);

    private static RecognitionRequest bracketOnBoard() {
        return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("motor_bracket"),
                CALIBRATION, null, "main_board", 1, 2);
    }

    @Test
    public void tagsAndMotorInspectionSurviveEveryCopy() {
        Object tag = "motor_assemblies/VERIFY#7";
        RecognitionRequest request = bracketOnBoard().withGuidedBracketSlot(3)
                .withInstalledMotorInspection().withRequestTag(tag);
        assertEquals(tag, request.getRequestTag());
        assertEquals(3, request.getGuidedBracketSlot());
        assertTrue(request.requestsInstalledMotorInspection());
        assertFalse("A bracket request still identifies a bracket", request.inspectsInstalledMotors());

        RecognitionRequest withWorkplane = request.withGuidedBracketWorkplane(Arrays.asList(
                new VisionPoint(0, 0), new VisionPoint(10, 0), new VisionPoint(10, 10), new VisionPoint(0, 10)));
        assertEquals(tag, withWorkplane.getRequestTag());
        assertEquals(3, withWorkplane.getGuidedBracketSlot());
        assertEquals(4, withWorkplane.getGuidedBracketWorkplane().size());
        assertTrue(withWorkplane.requestsInstalledMotorInspection());
        assertTrue("Changing the slot discards the old workplane",
                withWorkplane.withGuidedBracketSlot(4).getGuidedBracketWorkplane().isEmpty());
    }

    @Test
    public void untaggedRequestsKeepTheirPreviousBehaviour() {
        RecognitionRequest plain = bracketOnBoard();
        assertNull(plain.getRequestTag());
        assertFalse(plain.requestsInstalledMotorInspection());
        RecognitionRequest motors = new RecognitionRequest(
                RecognitionRequest.AssemblyExpectation.component("gear_motor_6v"), CALIBRATION, null,
                "main_board", 2, 2);
        assertTrue("The installed-motor request still runs the inspector",
                motors.requestsInstalledMotorInspection());
    }

    @Test(expected = IllegalArgumentException.class)
    public void installedMotorsAreOnlyInspectedOnTheSectionTwoBoard() {
        new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component("caster_wheel"),
                CALIBRATION, null, "main_board", 1, 9).withInstalledMotorInspection();
    }
}
