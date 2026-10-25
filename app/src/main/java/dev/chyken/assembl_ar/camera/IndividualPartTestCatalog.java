package dev.chyken.assembl_ar.camera;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.vision.CameraCalibration;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.RecognitionRequest;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.vision.VisionConfig;

/** Compatibility registrations for reviewed standalone captures. UnifiedReferenceCatalog merges
 * these entries into production registration; the test camera now selects that shared catalog.
 * filter() remains the reviewed-source registration helper used during that merge. */
public final class IndividualPartTestCatalog {
    private static final Set<String> STANDALONE_COMPONENT_IDS = standaloneComponentIds();
    private static final Set<String> UNAVAILABLE_COMPONENT_IDS = unavailableComponentIds();

    private static Set<String> standaloneComponentIds() {
        Set<String> result = new HashSet<>(Arrays.asList(NeobotSeedData.MAIN_BOARD,
                    NeobotSeedData.MOTOR_BRACKET, NeobotSeedData.GEAR_MOTOR, NeobotSeedData.CASTER_WHEEL,
                    NeobotSeedData.MOTOR_DRIVER, NeobotSeedData.MICROCONTROLLER,
                    NeobotSeedData.EXTENSION_BOARD, NeobotSeedData.OLED_DISPLAY,
                    NeobotSeedData.IR_RECEIVER, NeobotSeedData.IR_TRANSMITTER));
        result.addAll(ReviewedIndividualReferenceCatalog.getComponentIds());
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> unavailableComponentIds() {
        Set<String> result = new HashSet<>();
        for (Component component : NeobotSeedData.getComponents()) {
            if (!STANDALONE_COMPONENT_IDS.contains(component.getId())) result.add(component.getId());
        }
        return Collections.unmodifiableSet(result);
    }

    private IndividualPartTestCatalog() { }

    public static java.util.List<DiagnosticTargetCatalog.Target> selectableTargets(java.util.List<ComponentReferenceSpec> references,java.util.function.ToIntFunction<String> counts){return DiagnosticTargetCatalog.build(references,counts);}

    /**
     * Test-only tuning for small and low-texture loose parts. Assembly keeps VisionConfig.defaults().
     * Four geometrically consistent inliers are still required; identity competition stays enabled.
     */
    public static VisionConfig visionConfig() {
        return VisionConfig.builder()
                .matching(0.75f, 64.0f, 6)
                .homography(4, 0.45, 3.0, 2_000, 0.995)
                .build();
    }

    /**
     * Retains reviewed loose-component references and adds the explicitly registered test captures
     * for components present in the source catalog. Missing references stay missing; there is no
     * fallback to an assembly image or to an administrator image without isolated-part provenance.
     */
    public static List<ComponentReferenceSpec> filter(List<ComponentReferenceSpec> specs) {
        Objects.requireNonNull(specs, "specs");
        List<ComponentReferenceSpec> result = new ArrayList<>();
        Map<String, ComponentReferenceSpec> components = new LinkedHashMap<>();
        Set<String> referenceIds = new HashSet<>();
        for (ComponentReferenceSpec spec : specs) {
            Objects.requireNonNull(spec, "reference");
            if (spec.getAssemblyStepNumber() != 0 || spec.isAdministratorReference()) continue;
            components.putIfAbsent(spec.getComponentId(), spec);
            if ((isReviewedExistingReference(spec)
                    || LoosePartReferenceCatalog.isReviewedReference(spec)
                    || ReviewedIndividualReferenceCatalog.isReviewedReference(spec)
                    || ProductReferenceCatalog.isReviewedReference(spec))
                    && referenceIds.add(spec.getReferenceId())) {
                result.add(spec);
            }
        }
        for (ComponentReferenceSpec component : components.values()) {
            for (ComponentReferenceSpec added : additionalReferences(component)) {
                if (referenceIds.add(added.getReferenceId())) result.add(added);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Compatibility API for older callers. Only reviewed standalone sources are admitted;
     * unusable contextual baselines remain excluded even when supplied by an old installation.
     */
    public static List<ComponentReferenceSpec> withLegacyBaselines(List<ComponentReferenceSpec> specs) {
        // Compatibility entry point: rejected contextual images must not return through the
        // tester. Missing coverage is shown explicitly instead of counting unrelated templates.
        return UnifiedReferenceCatalog.individual(UnifiedReferenceCatalog.mergeRegistered(filter(specs)));
    }

    /** No pose direction, polarity, board anchor, repeated instance, or assembly pass is requested. */
    public static RecognitionRequest requestFor(String componentId, CameraCalibration calibration) {
        return requestFor(componentId, calibration, 0.001);
    }

    /** Applies a diagnostic-only projected-area floor without changing assembly recognition. */
    public static RecognitionRequest requestFor(String componentId, CameraCalibration calibration,
                                                double minimumCornerAreaRatio) {
        return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(componentId),
                calibration, null, null, 1, 0, minimumCornerAreaRatio, true);
    }

    /** Component identities with reviewed standalone sources (separate from legacy baselines). */
    public static Set<String> getStandaloneComponentIds() {
        return STANDALONE_COMPONENT_IDS;
    }

    /** Gaps in reviewed standalone coverage; some still have contextual legacy baseline images. */
    public static Set<String> getUnavailableComponentIds() {
        return UNAVAILABLE_COMPONENT_IDS;
    }

    private static boolean isReviewedExistingReference(ComponentReferenceSpec spec) {
        String path = spec.getJpegAssetPath();
        if (path == null) return false;
        switch (spec.getComponentId()) {
            case NeobotSeedData.MAIN_BOARD:
                // image-034 is an installed underside with brackets and screws, not a loose PCB.
                return path.equals("references/assembly/image-001.png")
                        || path.equals("references/variants/main-board-white-top.jpg")
                        || path.equals("references/variants/main-board-white-underside.jpg");
            case NeobotSeedData.MOTOR_DRIVER:
                return path.equals("references/physical/motor-driver-top.jpg");
            case NeobotSeedData.MICROCONTROLLER:
                return path.equals("references/physical/nano-top-usb-right.jpg")
                        || path.equals("references/physical/nano-top-usb-left.jpg")
                        || path.equals("references/physical/nano-underside.jpg");
            case NeobotSeedData.EXTENSION_BOARD:
                return path.equals("references/physical/extension-board-white-top.jpg")
                        || path.equals("references/physical/extension-board-white-underside.jpg");
            case NeobotSeedData.IR_RECEIVER:
                return path.equals("references/learning/image-044.png");
            default:
                return false;
        }
    }

    private static List<ComponentReferenceSpec> additionalReferences(ComponentReferenceSpec source) {
        List<ComponentReferenceSpec> result = new ArrayList<>();
        switch (source.getComponentId()) {
            case NeobotSeedData.OLED_DISPLAY:
                // The legacy crop includes the green instructional caption above the module.
                result.add(rectangle(source, "oled-loose-face", "references/learning/image-019.png",
                        source.getPhysicalWidthMm(), source.getPhysicalHeightMm(),
                        .025, .125, .98, .99).build());
                break;
            case NeobotSeedData.IR_TRANSMITTER:
                // The left photograph in image-042 shows the actual remote without image-062's
                // red annotation. This quadrilateral excludes the neighboring receiver and labels.
                List<VisionPoint> remote = polygon(.042, .240, .256, .020,
                        .672, .716, .466, .902);
                result.add(builder(source, "ir-remote-unannotated",
                        "references/learning/image-042.png",
                        source.getPhysicalWidthMm(), source.getPhysicalHeightMm())
                        .planarReferenceCornersNormalized(remote.get(0), remote.get(1),
                                remote.get(2), remote.get(3)).build());
                break;
            default:
                break;
        }
        return result;
    }

    private static ComponentReferenceSpec.Builder rectangle(ComponentReferenceSpec source,
            String name, String path, double width, double height,
            double left, double top, double right, double bottom) {
        List<VisionPoint> corners = rectanglePoints(left, top, right, bottom);
        return builder(source, name, path, width, height)
                .planarReferenceCornersNormalized(corners.get(0), corners.get(1),
                        corners.get(2), corners.get(3));
    }

    private static ComponentReferenceSpec.Builder builder(ComponentReferenceSpec source,
            String name, String path, double width, double height) {
        return ComponentReferenceSpec.builder(source.getComponentId() + "_individual_test_" + name,
                        source.getComponentId(), source.getDisplayName(), width, height)
                .jpegAssetPath(path).maximumImageDimension(1280).polarityAnchorsVerified(false);
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
