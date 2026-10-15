package dev.chyken.assembl_ar.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class HomeRobotRendererTest {
    private static final float TOLERANCE = 0.01f;

    @Test
    public void narrowViewportExpandsVerticalFieldOfViewToPreserveHorizontalFit() {
        float aspect = 0.59f;
        float verticalFov = HomeRobotRenderer.calculateVerticalFieldOfViewDegrees(aspect);

        assertTrue(verticalFov > 38f);
        double horizontalHalfTangent = aspect
                * Math.tan(Math.toRadians(verticalFov * 0.5f));
        assertEquals(Math.tan(Math.toRadians(19f)), horizontalHalfTangent, TOLERANCE);
    }

    @Test
    public void wideViewportKeepsAuthoredVerticalFieldOfView() {
        assertEquals(38f,
                HomeRobotRenderer.calculateVerticalFieldOfViewDegrees(1.6f), TOLERANCE);
        assertEquals(38f,
                HomeRobotRenderer.calculateVerticalFieldOfViewDegrees(Float.NaN), TOLERANCE);
    }
}
