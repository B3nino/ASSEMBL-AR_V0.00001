package dev.chyken.assembl_ar.camera;

import org.junit.Test;

import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.VisionPoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PhysicalReferenceCatalogTest {
    @Test
    public void extensionPhotosCannotBecomeMainBoardReferences() {
        assertTrue(catalog(NeobotSeedData.MAIN_BOARD).isEmpty());
        List<ComponentReferenceSpec> extension = catalog(NeobotSeedData.EXTENSION_BOARD);
        assertEquals(2, extension.size());
        assertTrue(signedArea(extension.get(0)) > 0.0);
        assertTrue(signedArea(extension.get(1)) < 0.0);
        for (ComponentReferenceSpec spec : extension) {
            assertEquals(NeobotSeedData.EXTENSION_BOARD, spec.getComponentId());
            // Deliberately non-default dimensions prove capture registration cannot replace
            // measured administrator metadata or invent a scale from an unmeasured image.
            assertEquals(123.0, spec.getPhysicalWidthMm(), 0.0);
            assertEquals(147.0, spec.getPhysicalHeightMm(), 0.0);
            assertFalse(spec.hasVerifiedPolarityAnchors());
        }
    }

    @Test
    public void nanoViewsRetainCanonicalUsbEndAndBackFaceHandedness() {
        List<ComponentReferenceSpec> nano = catalog(NeobotSeedData.MICROCONTROLLER);
        assertEquals(3, nano.size());
        List<VisionPoint> rightUsb = nano.get(0).getPlanarCornersNormalized();
        List<VisionPoint> leftUsb = nano.get(1).getPlanarCornersNormalized();
        assertTrue(rightUsb.get(2).getX() > rightUsb.get(1).getX());
        assertTrue(leftUsb.get(2).getX() < leftUsb.get(1).getX());
        assertTrue(signedArea(nano.get(0)) > 0.0);
        assertTrue(signedArea(nano.get(1)) > 0.0);
        assertTrue(signedArea(nano.get(2)) < 0.0);
    }

    private static List<ComponentReferenceSpec> catalog(String componentId) {
        for (Component component : NeobotSeedData.getComponents()) {
            if (componentId.equals(component.getId())) {
                return PhysicalReferenceCatalog.forComponent(component, 123.0, 147.0);
            }
        }
        throw new AssertionError(componentId);
    }

    private static double signedArea(ComponentReferenceSpec spec) {
        List<VisionPoint> corners = spec.getPlanarCornersNormalized();
        double area = 0.0;
        for (int index = 0; index < corners.size(); index++) {
            VisionPoint a = corners.get(index);
            VisionPoint b = corners.get((index + 1) % corners.size());
            area += a.getX() * b.getY() - b.getX() * a.getY();
        }
        return area * 0.5;
    }
}
