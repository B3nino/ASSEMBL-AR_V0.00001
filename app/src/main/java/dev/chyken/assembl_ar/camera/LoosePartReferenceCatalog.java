package dev.chyken.assembl_ar.camera;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.VisionPoint;

/**
 * Reviewed photographs of loose parts that are safe to share between production assembly and the
 * individual-part diagnostic. Every entry is an exact component/path registration; assembly-state
 * scenes, instructional derivatives, held-out test captures, and administrator images are not
 * admitted by this catalog.
 */
public final class LoosePartReferenceCatalog {
    private static final String PATH_PREFIX = "references/individual-test/";

    private LoosePartReferenceCatalog() { }

    public static List<ComponentReferenceSpec> forComponent(
            Component component, double widthMm, double heightMm) {
        List<ComponentReferenceSpec> result = new ArrayList<>();
        switch (component.getId()) {
            case NeobotSeedData.MOTOR_BRACKET:
                addMotorBracketReferences(result, component, widthMm, heightMm);
                break;
            case NeobotSeedData.GEAR_MOTOR:
                addGearMotorReferences(result, component, widthMm, heightMm);
                break;
            case NeobotSeedData.MOTOR_DRIVER:
                addMotorDriverReferences(result, component, widthMm, heightMm);
                break;
            default:
                break;
        }
        return Collections.unmodifiableList(result);
    }

    /** True only for an entry authored by this catalog, including its exact component/path pair. */
    static boolean isReviewedReference(ComponentReferenceSpec spec) {
        if (spec == null || spec.getJpegAssetPath() == null) return false;
        switch (spec.getComponentId()) {
            case NeobotSeedData.MOTOR_BRACKET:
                return matches(spec, "motor-bracket-20260906_131707")
                        || matches(spec, "motor-bracket-20260906_131438")
                        || matches(spec, "motor-bracket-20260906_131502");
            case NeobotSeedData.GEAR_MOTOR:
                return matches(spec, "gear-motor-20260906_131326")
                        || matches(spec, "gear-motor-20260906_131755")
                        || matches(spec, "gear-motor-20260906_131759");
            case NeobotSeedData.MOTOR_DRIVER:
                return matches(spec, "motor-driver-20260906_131718")
                        || matches(spec, "motor-driver-20260906_131356");
            default:
                return false;
        }
    }

    private static boolean matches(ComponentReferenceSpec spec, String name) {
        return spec.getReferenceId().equals(spec.getComponentId() + "_loose_" + name)
                && spec.getJpegAssetPath().equals(PATH_PREFIX + name + ".jpg");
    }

    private static void addMotorBracketReferences(List<ComponentReferenceSpec> result,
            Component component, double widthMm, double heightMm) {
        // The T-shaped component occupies a concave region; desk at its shoulders is excluded.
        result.add(rectangle(component, "motor-bracket-20260906_131707",
                "motor-bracket-20260906_131707.jpg", widthMm, heightMm,
                .207, .298, .699, .693)
                .featureRegionsNormalized(Arrays.asList(
                        rectanglePoints(.306, .305, .600, .601),
                        rectanglePoints(.219, .596, .688, .681)))
                .build());

        // Opposite physical face. Negative winding retains the first face's handedness.
        result.add(mirroredRectangle(component, "motor-bracket-20260906_131438",
                "motor-bracket-20260906_131438.jpg", widthMm, heightMm,
                .185, .317, .507, .631)
                .featureRegionsNormalized(Collections.singletonList(polygon(
                        .193, .329, .497, .321, .504, .376, .455, .383,
                        .477, .617, .254, .630, .245, .387, .193, .390)))
                .build());

        result.add(quadrilateral(component, "motor-bracket-20260906_131502",
                "motor-bracket-20260906_131502.jpg", widthMm, heightMm,
                .313, .308, .591, .359, .551, .633, .341, .622)
                .featureRegionsNormalized(Collections.singletonList(polygon(
                        .313, .308, .591, .359, .594, .406, .548, .402,
                        .551, .633, .341, .622, .365, .369, .305, .362)))
                .build());
    }

    private static void addGearMotorReferences(List<ComponentReferenceSpec> result,
            Component component, double widthMm, double heightMm) {
        // Limit the opposite broad side to the rigid gearbox face. Movable leads, shaft, metal can,
        // and table remain outside the registered feature region.
        List<VisionPoint> oppositeMotorFace = polygon(
                .565, .431, .841, .389, .873, .654, .591, .683);
        result.add(quadrilateral(component, "gear-motor-20260906_131326",
                "gear-motor-20260906_131326.jpg", widthMm, heightMm,
                .565, .431, .841, .389, .873, .654, .591, .683)
                .featureRegionsNormalized(Collections.singletonList(oppositeMotorFace))
                .build());

        // This portrait view registers its plane with nominal width and height swapped.
        result.add(rectangle(component, "gear-motor-20260906_131755",
                "gear-motor-20260906_131755.jpg", heightMm, widthMm,
                .285, .369, .615, .821)
                .featureRegionsNormalized(Collections.singletonList(polygon(
                        .380, .383, .512, .390, .532, .787,
                        .357, .787, .360, .420)))
                .build());

        result.add(rectangle(component, "gear-motor-20260906_131759",
                "gear-motor-20260906_131759.jpg", widthMm, heightMm,
                .140, .175, .770, .565)
                .featureRegionsNormalized(Arrays.asList(
                        rectanglePoints(.211, .225, .524, .537),
                        rectanglePoints(.635, .317, .725, .410)))
                .build());
    }

    private static void addMotorDriverReferences(List<ComponentReferenceSpec> result,
            Component component, double widthMm, double heightMm) {
        // Keep descriptors on the coherent PCB face rather than its raised terminals and pins.
        result.add(quadrilateral(component, "motor-driver-20260906_131718",
                "motor-driver-20260906_131718.jpg", widthMm, heightMm,
                .332, .389, .641, .385, .675, .674, .332, .679)
                .featureRegionsNormalized(Collections.singletonList(polygon(
                        .350, .405, .625, .402, .642, .548, .356, .552)))
                .build());

        result.add(quadrilateral(component, "motor-driver-20260906_131356",
                "motor-driver-20260906_131356.jpg", widthMm, heightMm,
                .378, .490, .555, .527, .569, .705, .376, .702)
                .featureRegionsNormalized(Collections.singletonList(polygon(
                        .390, .566, .556, .582, .558, .665, .388, .660)))
                .build());
    }

    private static ComponentReferenceSpec.Builder rectangle(Component component,
            String name, String file, double width, double height,
            double left, double top, double right, double bottom) {
        List<VisionPoint> corners = rectanglePoints(left, top, right, bottom);
        return builder(component, name, file, width, height)
                .planarReferenceCornersNormalized(corners.get(0), corners.get(1),
                        corners.get(2), corners.get(3));
    }

    private static ComponentReferenceSpec.Builder mirroredRectangle(Component component,
            String name, String file, double width, double height,
            double left, double top, double right, double bottom) {
        return builder(component, name, file, width, height)
                .mirroredPlanarReferenceCornersNormalized(
                        new VisionPoint(right, top), new VisionPoint(left, top),
                        new VisionPoint(left, bottom), new VisionPoint(right, bottom));
    }

    private static ComponentReferenceSpec.Builder quadrilateral(Component component,
            String name, String file, double width, double height, double... coordinates) {
        List<VisionPoint> corners = polygon(coordinates);
        if (corners.size() != 4) {
            throw new IllegalArgumentException("A reference quadrilateral requires four points");
        }
        return builder(component, name, file, width, height)
                .planarReferenceCornersNormalized(corners.get(0), corners.get(1),
                        corners.get(2), corners.get(3));
    }

    private static ComponentReferenceSpec.Builder builder(Component component,
            String name, String file, double width, double height) {
        return ComponentReferenceSpec.builder(component.getId() + "_loose_" + name,
                        component.getId(), component.getName(), width, height)
                .jpegAssetPath(PATH_PREFIX + file)
                .maximumImageDimension(1280)
                .descriptorRegionNormalization(true)
                .polarityAnchorsVerified(false);
    }

    private static List<VisionPoint> rectanglePoints(
            double left, double top, double right, double bottom) {
        return polygon(left, top, right, top, right, bottom, left, bottom);
    }

    private static List<VisionPoint> polygon(double... points) {
        List<VisionPoint> result = new ArrayList<>(points.length / 2);
        for (int index = 0; index < points.length; index += 2) {
            result.add(new VisionPoint(points[index], points[index + 1]));
        }
        return result;
    }
}
