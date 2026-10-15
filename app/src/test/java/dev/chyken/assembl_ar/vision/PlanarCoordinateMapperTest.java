package dev.chyken.assembl_ar.vision;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class PlanarCoordinateMapperTest {
    @Test
    public void mapsCanonicalPointThroughPerspectiveQuadrilateral() {
        List<VisionPoint> corners = Arrays.asList(
                pointFromKnownHomography(0.0, 0.0),
                pointFromKnownHomography(1.0, 0.0),
                pointFromKnownHomography(1.0, 1.0),
                pointFromKnownHomography(0.0, 1.0));

        VisionPoint projected = PlanarCoordinateMapper.projectUnitSquare(
                corners, new VisionPoint(0.35, 0.60));
        VisionPoint expected = pointFromKnownHomography(0.35, 0.60);

        assertEquals(expected.getX(), projected.getX(), 1e-8);
        assertEquals(expected.getY(), projected.getY(), 1e-8);
    }

    @Test
    public void malformedCornerListFailsClosed() {
        assertNull(PlanarCoordinateMapper.projectUnitSquare(
                Arrays.asList(new VisionPoint(0.0, 0.0)),
                new VisionPoint(0.5, 0.5)));
        assertNull(PlanarCoordinateMapper.unprojectUnitSquare(
                Arrays.asList(new VisionPoint(0.0, 0.0)),
                new VisionPoint(0.5, 0.5)));
    }

    @Test
    public void unprojectionInvertsTheMapInsideAndOutsideTheQuad() {
        List<VisionPoint> corners = Arrays.asList(
                pointFromKnownHomography(0.0, 0.0),
                pointFromKnownHomography(1.0, 0.0),
                pointFromKnownHomography(1.0, 1.0),
                pointFromKnownHomography(0.0, 1.0));
        double[][] canonical = {{0.35, 0.60}, {0.0, 1.0}, {1.08, 0.42}, {-0.06, 0.95}};
        for (double[] point : canonical) {
            VisionPoint unprojected = PlanarCoordinateMapper.unprojectUnitSquare(
                    corners, pointFromKnownHomography(point[0], point[1]));
            assertEquals(point[0], unprojected.getX(), 1e-8);
            assertEquals(point[1], unprojected.getY(), 1e-8);
        }
    }

    @Test
    public void pointsBeyondThePlaneHorizonHaveNoCanonicalPosition() {
        // The top edge is shorter, so the plane recedes upward to a horizon at y = -200.
        List<VisionPoint> corners = Arrays.asList(new VisionPoint(100, 300),
                new VisionPoint(300, 300), new VisionPoint(320, 400), new VisionPoint(80, 400));
        assertNull(PlanarCoordinateMapper.unprojectUnitSquare(corners, new VisionPoint(200, -850)));
    }

    private static VisionPoint pointFromKnownHomography(double x, double y) {
        double denominator = 0.2 * x + 0.1 * y + 1.0;
        return new VisionPoint(
                (100.0 * x + 10.0 * y + 20.0) / denominator,
                (5.0 * x + 80.0 * y + 30.0) / denominator);
    }
}
