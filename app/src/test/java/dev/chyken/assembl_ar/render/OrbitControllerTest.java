package dev.chyken.assembl_ar.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class OrbitControllerTest {
    private static final float TOLERANCE = 0.0001f;

    @Test
    public void resetRestoresTheAuthoredHomePose() {
        OrbitController controller = new OrbitController();
        controller.orbitBy(83f, -20f);
        controller.zoomBy(2f);

        controller.reset();

        OrbitController.Snapshot state = controller.snapshot();
        assertEquals(OrbitController.DEFAULT_YAW_DEGREES, state.yawDegrees, TOLERANCE);
        assertEquals(OrbitController.DEFAULT_PITCH_DEGREES, state.pitchDegrees, TOLERANCE);
        assertEquals(OrbitController.DEFAULT_DISTANCE, state.distance, TOLERANCE);
    }

    @Test
    public void orbitWrapsYawAndClampsPitch() {
        OrbitController controller = new OrbitController();

        controller.orbitBy(400f, 1000f);
        OrbitController.Snapshot upper = controller.snapshot();
        assertEquals(8f, upper.yawDegrees, TOLERANCE);
        assertEquals(OrbitController.MAX_PITCH_DEGREES, upper.pitchDegrees, TOLERANCE);

        controller.orbitBy(-800f, -1000f);
        OrbitController.Snapshot lower = controller.snapshot();
        assertEquals(-72f, lower.yawDegrees, TOLERANCE);
        assertEquals(OrbitController.MIN_PITCH_DEGREES, lower.pitchDegrees, TOLERANCE);
    }

    @Test
    public void pinchZoomClampsAndIgnoresInvalidFactors() {
        OrbitController controller = new OrbitController();

        controller.zoomBy(100f);
        assertEquals(OrbitController.MIN_DISTANCE,
                controller.snapshot().distance, TOLERANCE);
        controller.zoomBy(0.001f);
        assertEquals(OrbitController.MAX_DISTANCE,
                controller.snapshot().distance, TOLERANCE);

        controller.zoomBy(Float.NaN);
        controller.zoomBy(0f);
        assertEquals(OrbitController.MAX_DISTANCE,
                controller.snapshot().distance, TOLERANCE);
    }

    @Test
    public void restoredStateIsSanitizedAndClamped() {
        OrbitController controller = new OrbitController();
        controller.restore(725f, 100f, 100f);

        OrbitController.Snapshot state = controller.snapshot();
        assertEquals(5f, state.yawDegrees, TOLERANCE);
        assertEquals(OrbitController.MAX_PITCH_DEGREES, state.pitchDegrees, TOLERANCE);
        assertEquals(OrbitController.MAX_DISTANCE, state.distance, TOLERANCE);

        controller.restore(Float.NaN, Float.NaN, Float.NaN);
        state = controller.snapshot();
        assertEquals(OrbitController.DEFAULT_YAW_DEGREES, state.yawDegrees, TOLERANCE);
        assertEquals(OrbitController.DEFAULT_PITCH_DEGREES, state.pitchDegrees, TOLERANCE);
        assertEquals(OrbitController.DEFAULT_DISTANCE, state.distance, TOLERANCE);
    }
}
