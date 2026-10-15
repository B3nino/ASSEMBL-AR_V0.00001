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
 * Historical rejected registrations retained for exclusion regression tests only.
 *
 * <p>Generated gray artwork remains available to instruction cards. None of these registrations
 * belongs in production or Parts Test: masks cannot certify that generated pixels preserve
 * physical feature texture. See RECOGNITION_REFERENCE_AUDIT.md.</p>
 */
public final class ProcessedGrayReferenceCatalog {
    static final String ASSET_PREFIX = "references/instructional/gray/";

    private ProcessedGrayReferenceCatalog() { }

    public static List<ComponentReferenceSpec> forComponent(
            Component component, double widthMm, double heightMm) {
        List<ComponentReferenceSpec> result = new ArrayList<>();
        switch (component.getId()) {
            case NeobotSeedData.MOTOR_BRACKET:
                addMotorBracket(result, component, widthMm, heightMm);
                break;
            case NeobotSeedData.GEAR_MOTOR:
                addGearMotor(result, component, widthMm, heightMm);
                break;
            case NeobotSeedData.MOTOR_DRIVER:
                addMotorDriver(result, component, widthMm, heightMm);
                break;
            case NeobotSeedData.MAIN_BOARD:
                addMainBoardStates(result, component, widthMm, heightMm);
                break;
            default:
                break;
        }
        return Collections.unmodifiableList(result);
    }

    private static void addMotorBracket(List<ComponentReferenceSpec> result, Component component,
                                        double widthMm, double heightMm) {
        result.add(rectangle(component, "motor-bracket-half-gray", widthMm, heightMm,
                .183, .223, .813, .737)
                .featureRegionsNormalized(Arrays.asList(
                        rectanglePoints(.300, .230, .690, .635),
                        rectanglePoints(.190, .600, .805, .730)))
                .build());
    }

    private static void addGearMotor(List<ComponentReferenceSpec> result, Component component,
                                     double widthMm, double heightMm) {
        // The motor is portrait in this source, so its registered plane swaps nominal axes.
        result.add(rectangle(component, "gear-motor-gray", heightMm, widthMm,
                .285, .369, .615, .821)
                .featureRegionsNormalized(Collections.singletonList(polygon(
                        .350, .375, .585, .375, .585, .790,
                        .535, .815, .350, .790)))
                .build());
    }

    private static void addMotorDriver(List<ComponentReferenceSpec> result, Component component,
                                       double widthMm, double heightMm) {
        result.add(quadrilateral(component, "motor-driver-gray", widthMm, heightMm,
                .267, .260, .725, .260, .758, .695, .258, .695)
                .featureRegionsNormalized(Collections.singletonList(polygon(
                        .275, .270, .715, .270, .744, .680, .270, .680)))
                .build());
    }

    private static void addMainBoardStates(List<ComponentReferenceSpec> result,
                                           Component component,
                                           double widthMm,
                                           double heightMm) {
        result.add(board(component, "s02-00-main-board-gray", widthMm, heightMm, 2,
                polygon(.088, .137, .918, .137, .920, .913, .087, .913),
                Collections.emptyList(), false));

        result.add(board(component, "s02-01-brackets-mounted-gray", widthMm, heightMm, 2,
                polygon(.172, .158, .866, .158, .884, .803, .168, .803),
                Arrays.asList(
                        rectanglePoints(.300, .180, .745, .340),
                        rectanglePoints(.365, .345, .690, .605),
                        rectanglePoints(.290, .610, .750, .780)), false));

        result.add(board(component, "s02-02-first-motor-positioned-gray", widthMm, heightMm, 2,
                polygon(.217, .220, .842, .220, .881, .832, .204, .832),
                Arrays.asList(
                        rectanglePoints(.300, .245, .775, .395),
                        rectanglePoints(.455, .400, .750, .610),
                        rectanglePoints(.435, .615, .780, .800)), false));

        result.add(board(component, "s02-03-first-motor-one-screw-gray", widthMm, heightMm, 2,
                polygon(.143, .292, .773, .276, .984, .885, .055, .930),
                Arrays.asList(
                        polygon(.490, .300, .690, .292, .745, .445, .440, .455),
                        polygon(.535, .460, .790, .445, .870, .635, .480, .655),
                        polygon(.550, .660, .890, .640, .950, .820, .600, .840)), false));

        // The lower-right board corner is outside this tightly cropped oblique source. Retaining
        // the complete authored plane lets homography/pose code extrapolate the board instead of
        // pretending the image boundary is a physical chassis edge.
        result.add(board(component, "s02-04-first-motor-two-screws-gray", widthMm, heightMm, 2,
                polygon(.155, .274, .746, .254, 1.165, 1.080, .110, 1.045),
                Arrays.asList(
                        polygon(.515, .285, .710, .270, .800, .430, .460, .450),
                        polygon(.510, .455, .820, .435, .920, .630, .450, .660),
                        polygon(.470, .675, .940, .645, 1.000, .790, .500, .835)), true));

        result.add(board(component, "s02-05-both-motors-gray", widthMm, heightMm, 2,
                polygon(.198, .196, .844, .196, .867, .825, .169, .825),
                Arrays.asList(
                        rectanglePoints(.300, .220, .745, .355),
                        rectanglePoints(.360, .365, .710, .605),
                        rectanglePoints(.340, .610, .730, .795)), false));

        result.add(board(component, "s03-00-motor-driver-installed-gray", widthMm, heightMm, 3,
                polygon(.135, .194, .864, .194, .869, .828, .145, .828),
                Arrays.asList(
                        rectanglePoints(.240, .215, .760, .305),
                        rectanglePoints(.300, .350, .405, .615),
                        rectanglePoints(.650, .350, .755, .615),
                        rectanglePoints(.325, .575, .720, .800)), false));
    }

    private static ComponentReferenceSpec board(Component component,
                                                String name,
                                                double widthMm,
                                                double heightMm,
                                                int assemblyStep,
                                                List<VisionPoint> corners,
                                                List<List<VisionPoint>> featureRegions,
                                                boolean extrapolatedCorners) {
        ComponentReferenceSpec.Builder builder = builder(
                component, name, widthMm, heightMm)
                .assemblyStepNumber(assemblyStep)
                .featureRegionsNormalized(featureRegions);
        if (extrapolatedCorners) {
            builder.extrapolatedPlanarReferenceCornersNormalized(
                    corners.get(0), corners.get(1), corners.get(2), corners.get(3));
        } else {
            builder.planarReferenceCornersNormalized(
                    corners.get(0), corners.get(1), corners.get(2), corners.get(3));
        }
        return builder.build();
    }

    private static ComponentReferenceSpec.Builder rectangle(Component component,
            String name, double width, double height,
            double left, double top, double right, double bottom) {
        List<VisionPoint> corners = rectanglePoints(left, top, right, bottom);
        return builder(component, name, width, height)
                .planarReferenceCornersNormalized(corners.get(0), corners.get(1),
                        corners.get(2), corners.get(3));
    }

    private static ComponentReferenceSpec.Builder quadrilateral(Component component,
            String name, double width, double height, double... coordinates) {
        List<VisionPoint> corners = polygon(coordinates);
        return builder(component, name, width, height)
                .planarReferenceCornersNormalized(corners.get(0), corners.get(1),
                        corners.get(2), corners.get(3));
    }

    private static ComponentReferenceSpec.Builder builder(Component component,
            String name, double width, double height) {
        return ComponentReferenceSpec.builder(
                        component.getId() + "_processed_gray_" + name,
                        component.getId(), component.getName(), width, height)
                .jpegAssetPath(ASSET_PREFIX + name + ".webp")
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
