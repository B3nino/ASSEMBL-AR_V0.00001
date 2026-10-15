package dev.chyken.assembl_ar.camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.VisionPoint;

/**
 * Reviewed photographs of the physical kit. Source files are preserved byte-for-byte; only the
 * feature extraction region and canonical corner correspondence are authored here. These are
 * recognition references, not measurements or evidence of connected wiring.
 *
 * <p>The extension PCB is identified by its populated buttons and absent main-board sockets,
 * not by the shared chassis outline. See references/new/PHYSICAL_REFERENCE_INVENTORY.md.</p>
 */
public final class PhysicalReferenceCatalog {
    private PhysicalReferenceCatalog() { }

    public static List<ComponentReferenceSpec> forComponent(
            Component component, double widthMm, double heightMm) {
        List<ComponentReferenceSpec> result = new ArrayList<>();
        switch (component.getId()) {
            case NeobotSeedData.EXTENSION_BOARD:
                // The manual's installed extension view has the power/reset edge at the top.
                add(result, component, widthMm, heightMm, "extension-board-white-top", false,
                        .821, .844, .101, .844, .122, .199, .815, .211);
                // Back face retains the front's handedness and must fail an upright-face check.
                add(result, component, widthMm, heightMm, "extension-board-white-underside", true,
                        .069, .878, .849, .876, .813, .196, .094, .202);
                break;
            case NeobotSeedData.MICROCONTROLLER:
                // Canonical Nano orientation follows image-024: USB connector at the bottom.
                add(result, component, widthMm, heightMm, "nano-top-usb-right", false,
                        .357, .496, .359, .348, .631, .352, .629, .499);
                add(result, component, widthMm, heightMm, "nano-top-usb-left", false,
                        .640, .482, .641, .572, .344, .570, .348, .481);
                add(result, component, widthMm, heightMm, "nano-underside", true,
                        .565, .305, .455, .304, .453, .662, .562, .660);
                break;
            case NeobotSeedData.MOTOR_DRIVER:
                // Canonical terminal block is at the bottom, as in installed image-013.
                add(result, component, widthMm, heightMm, "motor-driver-top", false,
                        .550, .637, .387, .637, .390, .480, .551, .480);
                break;
            default:
                break;
        }
        return Collections.unmodifiableList(result);
    }

    private static void add(List<ComponentReferenceSpec> result, Component component,
                            double widthMm, double heightMm, String name, boolean mirrored,
                            double... coordinates) {
        ComponentReferenceSpec.Builder builder = ComponentReferenceSpec.builder(
                        component.getId() + "_physical_" + name,
                        component.getId(), component.getName(), widthMm, heightMm)
                .jpegAssetPath("references/physical/" + name + ".jpg")
                .maximumImageDimension(1280)
                .polarityAnchorsVerified(false);
        if (NeobotSeedData.MOTOR_DRIVER.equals(component.getId())) {
            builder.descriptorRegionNormalization(true);
        }
        VisionPoint first = new VisionPoint(coordinates[0], coordinates[1]);
        VisionPoint second = new VisionPoint(coordinates[2], coordinates[3]);
        VisionPoint third = new VisionPoint(coordinates[4], coordinates[5]);
        VisionPoint fourth = new VisionPoint(coordinates[6], coordinates[7]);
        if (mirrored) {
            builder.mirroredPlanarReferenceCornersNormalized(first, second, third, fourth);
        } else {
            builder.planarReferenceCornersNormalized(first, second, third, fourth);
        }
        result.add(builder.build());
    }
}
