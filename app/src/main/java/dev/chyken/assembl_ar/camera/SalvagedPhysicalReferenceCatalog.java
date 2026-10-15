package dev.chyken.assembl_ar.camera;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.VisionPoint;

/**
 * Original kit photographs recovered with foreground-only registration. A contextual photograph
 * need not be discarded when the complete rigid face can be isolated without modifying pixels.
 * Source bytes, masks, dimensional assumptions and held-out split are documented in
 * references/recovered/provenance.json. These sources do not verify electrical compatibility,
 * connector polarity, mounting depth or physical attachment.
 */
public final class SalvagedPhysicalReferenceCatalog {
    private SalvagedPhysicalReferenceCatalog() { }

    public static List<ComponentReferenceSpec> references() {
        List<ComponentReferenceSpec> result = new ArrayList<>();
        // The full battery label face is visible; the adjacent jumper bundle, clip and leads
        // lie outside this plane and mask. Dimensions are nominal 9 V package face dimensions.
        result.add(builder("battery-label", NeobotSeedData.BATTERY_PACK,
                "9 V battery pack", 26.5, 48.5)
                .planarReferenceCornersNormalized(p(.263,.470), p(.450,.405),
                        p(.629,.631), p(.463,.709))
                .featureRegionsNormalized(Collections.singletonList(polygon(
                        .278,.478, .446,.425, .608,.622, .467,.688)))
                .build());

        // A different printed face must have its own registration. This image is promoted
        // into training; it is not an independent held-out recognition measurement.
        result.add(builder("battery-label-opposite", NeobotSeedData.BATTERY_PACK,
                "9 V battery pack", 26.5, 48.5)
                .planarReferenceCornersNormalized(p(.306,.366), p(.495,.322),
                        p(.612,.553), p(.438,.604))
                .featureRegionsNormalized(Collections.singletonList(polygon(
                        .323,.378, .487,.337, .594,.550, .445,.589)))
                .build());

        // Symmetric five-spoke wheel: its circular plane gives position/scale, never shaft
        // orientation. Only the rubber face, hub and solid spoke material contribute features.
        // In particular the textured desk visible through all five apertures is excluded.
        List<List<VisionPoint>> wheelRegions = new ArrayList<>();
        addAnnulus(wheelRegions, .4705, .580, .2855, .212, .91, .97);
        wheelRegions.add(ellipse(.476, .581, .069, .052, 8));
        wheelRegions.add(polygon(.438,.520, .471,.434, .499,.438, .497,.519));
        wheelRegions.add(polygon(.530,.551, .649,.523, .663,.550, .545,.587));
        wheelRegions.add(polygon(.504,.632, .573,.687, .552,.711, .474,.646));
        wheelRegions.add(polygon(.435,.622, .359,.691, .333,.675, .399,.601));
        wheelRegions.add(polygon(.408,.564, .291,.533, .303,.509, .425,.536));
        result.add(builder("wheel-five-spoke", NeobotSeedData.WHEEL,
                "Five-spoke drive wheel", 65.0, 65.0)
                .maximumImageDimension(1280).descriptorRegionNormalization(false)
                .planarReferenceCornersNormalized(p(.185,.368), p(.756,.368),
                        p(.756,.792), p(.185,.792))
                .featureRegionsNormalized(wheelRegions)
                .build());
        return Collections.unmodifiableList(result);
    }

    public static List<ComponentReferenceSpec> forComponent(String componentId) {
        List<ComponentReferenceSpec> result = new ArrayList<>();
        for (ComponentReferenceSpec spec : references()) {
            if (componentId.equals(spec.getComponentId())) result.add(spec);
        }
        return Collections.unmodifiableList(result);
    }

    private static ComponentReferenceSpec.Builder builder(String name, String componentId,
            String displayName, double widthMm, double heightMm) {
        return ComponentReferenceSpec.builder(componentId + "_recovered_" + name,
                        componentId, displayName, widthMm, heightMm)
                .jpegAssetPath("references/recovered/" + name + ".jpg")
                .maximumImageDimension(768)
                .descriptorRegionNormalization(true)
                .polarityAnchorsVerified(false);
    }

    private static void addAnnulus(List<List<VisionPoint>> regions,
                                   double x, double y, double rx, double ry,
                                   double inner, double outer) {
        for (int segment = 0; segment < 32; segment++) {
            double a = Math.PI * 2.0 * segment / 32.0;
            double b = Math.PI * 2.0 * (segment + 1) / 32.0;
            regions.add(Arrays.asList(
                    p(x + rx * outer * Math.cos(a), y + ry * outer * Math.sin(a)),
                    p(x + rx * outer * Math.cos(b), y + ry * outer * Math.sin(b)),
                    p(x + rx * inner * Math.cos(b), y + ry * inner * Math.sin(b)),
                    p(x + rx * inner * Math.cos(a), y + ry * inner * Math.sin(a))));
        }
    }

    private static List<VisionPoint> ellipse(double x, double y, double rx, double ry, int count) {
        List<VisionPoint> points = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            double angle = Math.PI * 2.0 * index / count;
            points.add(p(x + rx * Math.cos(angle), y + ry * Math.sin(angle)));
        }
        return points;
    }

    private static List<VisionPoint> polygon(double... xy) {
        List<VisionPoint> points = new ArrayList<>();
        for (int index = 0; index < xy.length; index += 2) points.add(p(xy[index], xy[index + 1]));
        return points;
    }

    private static VisionPoint p(double x, double y) { return new VisionPoint(x, y); }
}
