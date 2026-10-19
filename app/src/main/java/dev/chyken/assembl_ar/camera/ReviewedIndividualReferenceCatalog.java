package dev.chyken.assembl_ar.camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.VisionPoint;

/**
 * Reviewed September 2026 camera captures, shared by production and Parts Test.
 *
 * <p>The lossless 8-bit single-channel PNG assets preserve the prepared photographs' native
 * grayscale working pixels. Full-color original pixels are retained in external lossless masters.
 * Each registration carries geometry authored for that exact cropped asset; a matching filename or directory is
 * insufficient to admit an unreviewed capture. Background, moving surfaces and unrelated parts
 * are excluded by the descriptor regions. Plane dimensions are nominal pose estimates, not
 * physical calibration or installation evidence. No new view certifies wiring polarity.</p>
 */
public final class ReviewedIndividualReferenceCatalog {
    private static final List<ComponentReferenceSpec> REFERENCES = createReferences();
    private static final Set<String> COMPONENT_IDS = componentIds();

    private ReviewedIndividualReferenceCatalog() { }

    public static List<ComponentReferenceSpec> references() { return REFERENCES; }

    public static Set<String> getComponentIds() { return COMPONENT_IDS; }

    public static List<ComponentReferenceSpec> forComponent(String componentId) {
        List<ComponentReferenceSpec> result = new ArrayList<>();
        for (ComponentReferenceSpec reference : REFERENCES) {
            if (reference.getComponentId().equals(componentId)) result.add(reference);
        }
        return Collections.unmodifiableList(result);
    }

    /** Raw Phase-1 directories are discovered on the background vision loader. */
    public static List<ComponentReferenceSpec> forComponent(String componentId,
            android.content.res.AssetManager assets) {
        List<ComponentReferenceSpec> result = new ArrayList<>(forComponent(componentId));
        if ("main_board".equals(componentId) && assets != null) {
            result.addAll(RawBoardReferenceCatalog.scan(assets));
        }
        return Collections.unmodifiableList(result);
    }

    static boolean isReviewedReference(ComponentReferenceSpec candidate) {
        if (candidate == null || candidate.getJpegAssetPath() == null) return false;
        for (ComponentReferenceSpec authored : REFERENCES) {
            if (authored.getComponentId().equals(candidate.getComponentId())
                    && authored.getReferenceId().equals(candidate.getReferenceId())
                    && authored.getJpegAssetPath().equals(candidate.getJpegAssetPath())
                    && authored.getPlanarCornersNormalized().equals(candidate.getPlanarCornersNormalized())
                    && authored.getFeatureRegionsNormalized().equals(candidate.getFeatureRegionsNormalized())
                    && authored.getPhysicalWidthMm() == candidate.getPhysicalWidthMm()
                    && authored.getPhysicalHeightMm() == candidate.getPhysicalHeightMm()
                    && authored.getMaximumImageDimension() == candidate.getMaximumImageDimension()
                    && authored.isDescriptorRegionNormalizationEnabled()
                    == candidate.isDescriptorRegionNormalizationEnabled()
                    && !candidate.hasVerifiedPolarityAnchors()
                    && candidate.getShapeHint() == ComponentReferenceSpec.ShapeHint.TEXTURED_PLANAR) return true;
        }
        return false;
    }

    private static Set<String> componentIds() {
        Set<String> result = new LinkedHashSet<>();
        for (ComponentReferenceSpec reference : REFERENCES) result.add(reference.getComponentId());
        return Collections.unmodifiableSet(result);
    }

    private static List<ComponentReferenceSpec> createReferences() {
        List<ComponentReferenceSpec> result = new ArrayList<>();
        // BEGIN AUTHORED MANIFEST ENTRIES
        // individual new/20260927_095332.jpg
        result.add(entry("arduino_nano_microcontroller_reviewed_nano-20260927_095332",
                "arduino_nano_microcontroller", "Arduino Nano microcontroller",
                "references/individual-20260927/nano-20260927_095332.png", 18, 45, 1280,
                false, new double[]{0.31, 0.152, 0.755, 0.165, 0.697, 0.885, 0.182, 0.873},
                new double[][]{
                        new double[]{0.34, 0.22, 0.703, 0.23, 0.653, 0.775, 0.24, 0.766}
                }));
        // individual new/20260927_095406.jpg — top face, closer and more head-on
        result.add(entry("arduino_nano_microcontroller_reviewed_nano-20260927_095406",
                "arduino_nano_microcontroller", "Arduino Nano microcontroller",
                "references/individual-20260927/nano-20260927_095406.png", 18, 45, 1280,
                false, new double[]{0.26, 0.10, 0.76, 0.10, 0.81, 0.83, 0.15, 0.83},
                new double[][]{
                        new double[]{0.29, 0.17, 0.70, 0.17, 0.72, 0.73, 0.23, 0.73}
                }));
        // individual new/20260927_095418.jpg
        result.add(entry("arduino_nano_microcontroller_reviewed_nano-20260927_095418",
                "arduino_nano_microcontroller", "Arduino Nano microcontroller",
                "references/individual-20260927/nano-20260927_095418.png", 18, 45, 1280,
                false, new double[]{0.415, 0.18, 0.81, 0.132, 0.522, 0.82, 0.09, 0.91},
                new double[][]{
                        new double[]{0.452, 0.245, 0.748, 0.209, 0.494, 0.759, 0.237, 0.781}
                }));
        // individual new/20260927_095441.jpg — independently captured underside
        result.add(entry("arduino_nano_microcontroller_reviewed_nano-20260927_095441",
                "arduino_nano_microcontroller", "Arduino Nano microcontroller",
                "references/individual-20260927/nano-20260927_095441.png", 18, 45, 1280,
                true, new double[]{0.75, 0.22, 0.41, 0.19, 0.25, 0.76, 0.78, 0.79},
                new double[][]{
                        new double[]{0.45, 0.27, 0.71, 0.28, 0.70, 0.70, 0.35, 0.68}
                }));
        // individual new/20260927_095445.jpg
        result.add(entry("arduino_nano_microcontroller_reviewed_nano-20260927_095445",
                "arduino_nano_microcontroller", "Arduino Nano microcontroller",
                "references/individual-20260927/nano-20260927_095445.png", 18, 45, 1280,
                true, new double[]{0.787, 0.144, 0.32, 0.119, 0.185, 0.865, 0.731, 0.903},
                new double[][]{
                        new double[]{0.367, 0.177, 0.718, 0.194, 0.671, 0.84, 0.256, 0.821}
                }));
        // individual new/20260927_095503.jpg
        result.add(entry("line_tracker_module_reviewed_line-tracker-20260927_095503",
                "line_tracker_module", "Line tracker module",
                "references/individual-20260927/line-tracker-20260927_095503.png", 14, 50, 1280,
                false, new double[]{0.398, 0.217, 0.716, 0.224, 0.641, 0.866, 0.227, 0.851},
                new double[][]{
                        new double[]{0.42, 0.255, 0.637, 0.255, 0.637, 0.365, 0.42, 0.365},
                        new double[]{0.375, 0.48, 0.445, 0.487, 0.39, 0.79, 0.311, 0.785},
                        new double[]{0.605, 0.482, 0.645, 0.483, 0.597, 0.78, 0.552, 0.775}
                }));
        // individual new/20260927_095530.jpg
        result.add(entry("line_tracker_module_reviewed_line-tracker-20260927_095530",
                "line_tracker_module", "Line tracker module",
                "references/individual-20260927/line-tracker-20260927_095530.png", 14, 50, 1280,
                true, new double[]{0.777, 0.147, 0.528, 0.117, 0.181, 0.76, 0.49, 0.8},
                new double[][]{
                        new double[]{0.376, 0.395, 0.62, 0.414, 0.507, 0.754, 0.233, 0.725},
                        new double[]{0.536, 0.216, 0.676, 0.239, 0.655, 0.287, 0.517, 0.261}
                }));
        // individual new/20260927_095624.jpg
        result.add(entry("ultrasonic_distance_sensor_reviewed_ultrasonic-sensor-20260927_095624",
                "ultrasonic_distance_sensor", "Ultrasonic distance sensor",
                "references/individual-20260927/ultrasonic-sensor-20260927_095624.png", 45, 20, 1280,
                true, new double[]{0.74, 0.16, 0.098, 0.451, 0.154, 0.849, 0.874, 0.51},
                new double[][]{
                        new double[]{0.141, 0.461, 0.707, 0.218, 0.811, 0.49, 0.185, 0.784}
                }));
        // individual new/20260927_095728.jpg
        result.add(entry("caster_wheel_reviewed_caster-20260927_095728",
                "caster_wheel", "Ball caster",
                "references/individual-20260927/caster-20260927_095728.png", 28, 50, 1280,
                true, new double[]{0.496, 0.073, 0.25, 0.115, 0.385, 0.803, 0.677, 0.785},
                new double[][]{
                        new double[]{0.292, 0.199, 0.489, 0.181, 0.543, 0.431, 0.304, 0.421},
                        new double[]{0.357, 0.483, 0.538, 0.473, 0.58, 0.677, 0.391, 0.714}
                }));
        // individual new/20260927_100023.jpg
        result.add(entry("motor_bracket_reviewed_motor-bracket-half-20260927_100023",
                "motor_bracket", "Motor bracket half",
                "references/individual-20260927/motor-bracket-half-20260927_100023.png", 32, 48, 768,
                false, new double[]{0.128, 0.818, 0.134, 0.128, 0.867, 0.291, 0.88, 0.699},
                new double[][]{
                        new double[]{0.15, 0.17, 0.27, 0.17, 0.27, 0.395, 0.15, 0.395},
                        new double[]{0.15, 0.57, 0.28, 0.57, 0.28, 0.783, 0.15, 0.783},
                        new double[]{0.316, 0.288, 0.843, 0.321, 0.847, 0.348, 0.316, 0.321},
                        new double[]{0.322, 0.439, 0.841, 0.439, 0.841, 0.494, 0.322, 0.494},
                        new double[]{0.316, 0.595, 0.85, 0.595, 0.85, 0.674, 0.316, 0.674}
                }));
        // individual new/20260927_100035.jpg
        result.add(entry("motor_bracket_reviewed_motor-bracket-half-20260927_100035",
                "motor_bracket", "Motor bracket half",
                "references/individual-20260927/motor-bracket-half-20260927_100035.png", 32, 48, 768,
                false, new double[]{0.821, 0.87, 0.133, 0.872, 0.244, 0.14, 0.683, 0.13},
                new double[][]{
                        new double[]{0.281, 0.158, 0.642, 0.158, 0.642, 0.21, 0.281, 0.21},
                        new double[]{0.269, 0.248, 0.302, 0.248, 0.302, 0.697, 0.269, 0.697},
                        new double[]{0.447, 0.24, 0.482, 0.24, 0.482, 0.688, 0.447, 0.688},
                        new double[]{0.61, 0.239, 0.651, 0.239, 0.651, 0.688, 0.61, 0.688},
                        new double[]{0.17, 0.742, 0.444, 0.742, 0.444, 0.829, 0.17, 0.829},
                        new double[]{0.581, 0.742, 0.803, 0.742, 0.803, 0.828, 0.581, 0.828}
                }));
        // individual new/20260927_100041.jpg
        result.add(entry("motor_bracket_reviewed_motor-bracket-half-20260927_100041",
                "motor_bracket", "Motor bracket half",
                "references/individual-20260927/motor-bracket-half-20260927_100041.png", 32, 48, 768,
                true, new double[]{0.136, 0.158, 0.132, 0.802, 0.883, 0.67, 0.823, 0.302},
                new double[][]{
                        new double[]{0.148, 0.186, 0.258, 0.186, 0.258, 0.387, 0.148, 0.387},
                        new double[]{0.146, 0.574, 0.262, 0.574, 0.262, 0.744, 0.146, 0.744},
                        new double[]{0.29, 0.296, 0.808, 0.32, 0.815, 0.35, 0.29, 0.331},
                        new double[]{0.31, 0.407, 0.826, 0.407, 0.826, 0.45, 0.31, 0.45},
                        new double[]{0.311, 0.624, 0.845, 0.624, 0.845, 0.66, 0.311, 0.66}
                }));
        // individual new/20260927_100053.jpg
        result.add(entry("motor_bracket_reviewed_motor-bracket-half-20260927_100053",
                "motor_bracket", "Motor bracket half",
                "references/individual-20260927/motor-bracket-half-20260927_100053.png", 32, 48, 768,
                true, new double[]{0.86, 0.117, 0.165, 0.132, 0.304, 0.877, 0.836, 0.862},
                new double[][]{
                        new double[]{0.19, 0.149, 0.827, 0.149, 0.827, 0.235, 0.19, 0.235},
                        new double[]{0.325, 0.302, 0.376, 0.302, 0.376, 0.789, 0.325, 0.789},
                        new double[]{0.713, 0.305, 0.75, 0.306, 0.808, 0.786, 0.769, 0.786},
                        new double[]{0.395, 0.831, 0.78, 0.831, 0.78, 0.858, 0.395, 0.858},
                        new double[]{0.465, 0.451, 0.6, 0.451, 0.6, 0.485, 0.465, 0.485}
                }));
        // END AUTHORED MANIFEST ENTRIES
        return Collections.unmodifiableList(result);
    }

    private static ComponentReferenceSpec entry(String referenceId, String componentId,
            String label, String assetPath, double widthMm, double heightMm, int maximumDimension,
            boolean mirrored, double[] cornerCoordinates, double[][] featureCoordinates) {
        List<VisionPoint> corners = polygon(cornerCoordinates);
        if (corners.size() != 4) throw new IllegalArgumentException("Four canonical corners required");
        List<List<VisionPoint>> regions = new ArrayList<>();
        for (double[] region : featureCoordinates) regions.add(polygon(region));
        ComponentReferenceSpec.Builder builder = ComponentReferenceSpec.builder(
                        referenceId, componentId, label, widthMm, heightMm)
                .jpegAssetPath(assetPath).maximumImageDimension(maximumDimension)
                .descriptorRegionNormalization(true).polarityAnchorsVerified(false)
                .featureRegionsNormalized(regions);
        if (mirrored) {
            builder.mirroredPlanarReferenceCornersNormalized(corners.get(0), corners.get(1),
                    corners.get(2), corners.get(3));
        } else {
            builder.planarReferenceCornersNormalized(corners.get(0), corners.get(1),
                    corners.get(2), corners.get(3));
        }
        return builder.build();
    }

    private static List<VisionPoint> polygon(double... coordinates) {
        if ((coordinates.length & 1) != 0) throw new IllegalArgumentException("Point pairs required");
        List<VisionPoint> result = new ArrayList<>(coordinates.length / 2);
        for (int index = 0; index < coordinates.length; index += 2) {
            result.add(new VisionPoint(coordinates[index], coordinates[index + 1]));
        }
        return result;
    }
}
