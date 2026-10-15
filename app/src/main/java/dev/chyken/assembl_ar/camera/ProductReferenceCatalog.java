package dev.chyken.assembl_ar.camera;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.VisionPoint;

/** Exact-product seller photographs, shared by the main assembly app and diagnostics.
 * Original image bytes and source attribution are in references/products/provenance.json.
 * Descriptors are restricted to rigid surfaces, excluding wires, reflective balls and background.
 * Registered plane dimensions below are visual estimates except the caster's 50 x 25 mm base.
 */
public final class ProductReferenceCatalog {
    private static final String PREFIX = "references/products/";
    private ProductReferenceCatalog() { }

    public static List<ComponentReferenceSpec> references() {
        List<ComponentReferenceSpec> result = new ArrayList<>();
        // Broad gearbox face only: the published 69 x 37 x 23 mm describes the complete motor,
        // not this plane. Its estimated 36 x 23 mm face excludes can, shafts and movable leads.
        // Corner order runs along its short edge first. A 384 px crop avoids inventing fine-scale
        // descriptors by enlarging a 476 px source face to 1280 px.
        result.add(builder("makerlab-yellow-6v-motor", NeobotSeedData.GEAR_MOTOR,
                "6 V DC gear motor", 23, 36)
                .maximumImageDimension(384)
                .planarReferenceCornersNormalized(p(.505, .403), p(.615, .442),
                        p(.519, .613), p(.394, .559))
                .featureRegionsNormalized(Collections.singletonList(polygon(
                        .510,.416, .584,.435, .606,.454, .522,.598,
                        .408,.554, .435,.512)))
                .build());
        // Ball-facing view: only the two fixed mounting ears and exposed base contribute
        // descriptors. Reflections on the moving ball and raised clips cannot define its plane.
        // Per-view crop sizes retain detail at the live camera's analyzed scale.
        result.add(builder("bc-robotics-caster-ball", NeobotSeedData.CASTER_WHEEL,
                "Ball caster wheel", 50, 25)
                .maximumImageDimension(768)
                .planarReferenceCornersNormalized(p(.296,.294), p(.841,.639),
                        p(.724,.823), p(.179,.479))
                .featureRegionsNormalized(Arrays.asList(polygon(
                        .234,.365, .336,.329, .378,.439, .306,.516, .252,.470),
                        polygon(.654,.631, .719,.602, .790,.657,
                                .800,.728, .749,.764, .693,.747)))
                .build());
        // Opposite face of the same base. Negative winding preserves physical handedness.
        result.add(builder("bc-robotics-caster-mount", NeobotSeedData.CASTER_WHEEL,
                "Ball caster wheel", 50, 25)
                .maximumImageDimension(640)
                .mirroredPlanarReferenceCornersNormalized(p(.779,.663), p(.318,.111),
                        p(.215,.296), p(.676,.848))
                .featureRegionsNormalized(Collections.singletonList(polygon(
                        .272,.212, .318,.173, .554,.323, .733,.670,
                        .721,.752, .680,.783, .421,.590, .306,.400)))
                .build());
        return Collections.unmodifiableList(result);
    }

    public static List<ComponentReferenceSpec> forComponent(String componentId) {
        List<ComponentReferenceSpec> result = new ArrayList<>();
        for (ComponentReferenceSpec spec : references()) {
            if (componentId.equals(spec.getComponentId())) result.add(spec);
        }
        return result;
    }

    static boolean isReviewedReference(ComponentReferenceSpec spec) {
        if (spec == null) return false;
        for (ComponentReferenceSpec authored : forComponent(spec.getComponentId())) {
            if (authored.getReferenceId().equals(spec.getReferenceId())
                    && authored.getJpegAssetPath().equals(spec.getJpegAssetPath())) return true;
        }
        return false;
    }

    private static ComponentReferenceSpec.Builder builder(String name, String id,
            String label, double width, double height) {
        return ComponentReferenceSpec.builder(id + "_product_" + name, id, label, width, height)
                .jpegAssetPath(PREFIX + name + ".jpg").maximumImageDimension(1280)
                .descriptorRegionNormalization(true).polarityAnchorsVerified(false);
    }
    private static VisionPoint p(double x, double y) { return new VisionPoint(x, y); }
    private static List<VisionPoint> polygon(double... xy) {
        List<VisionPoint> result = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) result.add(p(xy[i], xy[i + 1]));
        return result;
    }
}
