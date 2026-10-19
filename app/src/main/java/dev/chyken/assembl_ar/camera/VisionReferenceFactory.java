package dev.chyken.assembl_ar.camera;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.model.OrbDescriptorData;
import dev.chyken.assembl_ar.model.ReferenceImageMetadata;
import dev.chyken.assembl_ar.model.SpatialMetadata;
import dev.chyken.assembl_ar.vision.AdminReferenceGeometry;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.MainBoardDimensions;
import dev.chyken.assembl_ar.vision.VisionPoint;

/** Builds the bundled, fully offline multi-angle recognition catalog. */
public final class VisionReferenceFactory {
    private static final Pattern JSON_NUMBER = Pattern.compile(
            "-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");

    private VisionReferenceFactory() {
    }

    public static List<ComponentReferenceSpec> build(AssemblArRepository repository) {
        return build(repository, null);
    }

    public static List<ComponentReferenceSpec> build(AssemblArRepository repository,
            android.content.res.AssetManager assets) {
        List<ComponentReferenceSpec> result = new ArrayList<>();
        Set<String> referenceIds = new HashSet<>();
        for (Component component : repository.getComponents()) {
            double[] dimensions = dimensions(repository, component);
            List<String> paths = selectBundledReferences(component);
            int angle = 0;
            for (String path : paths) {
                String referenceId = component.getId() + "_bundled_" + (++angle);
                double[] referenceDimensions = bundledDimensions(
                        component, path, dimensions);
                ComponentReferenceSpec.Builder builder = ComponentReferenceSpec.builder(
                                referenceId, component.getId(), component.getName(),
                                referenceDimensions[0], referenceDimensions[1])
                        .jpegAssetPath(path);
                applyPlanarBounds(builder, component, path);
                applyShapeHint(builder, component);
                if (NeobotSeedData.MOTOR_BRACKET.equals(component.getId())
                        || NeobotSeedData.GEAR_MOTOR.equals(component.getId())
                        || NeobotSeedData.MOTOR_DRIVER.equals(component.getId())) {
                    builder.maximumImageDimension(1280)
                            .descriptorRegionNormalization(true);
                }
                result.add(builder.build());
                referenceIds.add(referenceId);
            }

            // App-versioned physical captures are independent of persisted manual paths and of
            // the legacy manual-view limit, so existing installs receive them without a reset.
            for (ComponentReferenceSpec physical : PhysicalReferenceCatalog.forComponent(
                    component, dimensions[0], dimensions[1])) {
                if (referenceIds.add(physical.getReferenceId())) {
                    result.add(physical);
                }
            }
            // The same reviewed loose-part photographs power both production recognition and the
            // individual diagnostic. Keeping their registration in one catalog prevents the two
            // workflows from silently training on different views of the same physical part.
            for (ComponentReferenceSpec loose : LoosePartReferenceCatalog.forComponent(
                    component, dimensions[0], dimensions[1])) {
                if (referenceIds.add(loose.getReferenceId())) {
                    result.add(loose);
                }
            }
            // Reviewed camera photographs retain the physical pixels and their own canonical
            // geometry. The same exact registrations are admitted by the independent Parts Test.
            for (ComponentReferenceSpec reviewed : ReviewedIndividualReferenceCatalog.forComponent(
                    component.getId(), assets)) {
                if (referenceIds.add(reviewed.getReferenceId())) result.add(reviewed);
            }
            // Every versioned front and underside photograph participates in the same offline
            // descriptor preparation and candidate batches as the original board views.
            for (ComponentReferenceSpec perspective :
                    MainboardPerspectiveReferenceCatalog.forComponent(component)) {
                if (referenceIds.add(perspective.getReferenceId())) result.add(perspective);
            }
            // Versioned exact-product references belong to production, independently of saved
            // library paths. Existing installations receive them without resetting app data.
            for (ComponentReferenceSpec product : ProductReferenceCatalog.forComponent(component.getId())) {
                if (referenceIds.add(product.getReferenceId())) result.add(product);
            }
            for (ComponentReferenceSpec recovered : SalvagedPhysicalReferenceCatalog.forComponent(
                    component.getId())) {
                if (referenceIds.add(recovered.getReferenceId())) result.add(recovered);
            }
            // Generative background edits can change the component's fine texture. Only the
            // inspected camera originals feed recognition; gray artwork remains guidance-only.
            for (ComponentReferenceSpec original : OriginalBoardReferenceCatalog.forComponent(component)) {
                if (referenceIds.add(original.getReferenceId())) result.add(original);
            }
            for (ComponentReferenceSpec installed : InstalledBracketReferenceCatalog.forComponent(component)) {
                if (referenceIds.add(installed.getReferenceId())) result.add(installed);
            }
            for (ComponentReferenceSpec progress : AssemblyProgressReferenceCatalog.forComponent(component)) {
                if (referenceIds.add(progress.getReferenceId())) result.add(progress);
            }
            // The old assembly-state source is a screenshot containing AR guide lines over
            // the board itself. It is view-only; matching those lines would reinforce a false pose.

            for (ReferenceImageMetadata image : repository.getActiveReferenceImages(component.getId())) {
                OrbDescriptorData descriptor = repository.getOrbDescriptor(image.getId());
                String localImagePath = image.getImagePath();
                boolean hasLocalImage = localImagePath != null
                        && !localImagePath.trim().isEmpty()
                        && new File(localImagePath).isFile();
                boolean hasDescriptor = descriptor != null
                        && descriptor.getDescriptorsBlob().length > 0;
                if (!hasDescriptor && !hasLocalImage) {
                    continue;
                }
                String referenceId = component.getId() + "_admin_" + image.getId();
                if (!referenceIds.add(referenceId)) {
                    continue;
                }
                ComponentReferenceSpec.Builder builder = ComponentReferenceSpec.builder(
                                referenceId, component.getId(), component.getName(),
                                dimensions[0], dimensions[1]);
                if (hasDescriptor) {
                    builder.adminDescriptorBlob(descriptor.getDescriptorsBlob());
                }
                if (hasLocalImage) {
                    builder.localImageFilePath(localImagePath);
                }
                if (descriptor != null
                        && descriptor.getAlgorithmVersion().contains("ROI-2")) {
                    List<VisionPoint> storedCorners = parseStoredAdminCorners(
                            descriptor.getParametersJson());
                    if (storedCorners == null) {
                        continue;
                    }
                    AdminReferenceGeometry.applyPlanarBounds(builder, storedCorners);
                }
                applyShapeHint(builder, component);
                result.add(builder.build());
            }
        }
        return result;
    }

    static List<VisionPoint> parseStoredAdminCorners(String parametersJson) {
        if (parametersJson == null) {
            return null;
        }
        int key = parametersJson.indexOf("\"canonicalCorners\"");
        int start = key < 0 ? -1 : parametersJson.indexOf('[', key);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        int end = -1;
        for (int index = start; index < parametersJson.length(); index++) {
            char character = parametersJson.charAt(index);
            if (character == '[') {
                depth++;
            } else if (character == ']' && --depth == 0) {
                end = index + 1;
                break;
            }
        }
        if (end < 0) {
            return null;
        }
        Matcher matcher = JSON_NUMBER.matcher(parametersJson.substring(start, end));
        double[] coordinates = new double[8];
        int count = 0;
        try {
            while (matcher.find()) {
                if (count >= coordinates.length) {
                    return null;
                }
                coordinates[count++] = Double.parseDouble(matcher.group());
            }
        } catch (NumberFormatException invalidNumber) {
            return null;
        }
        if (count != coordinates.length) {
            return null;
        }
        List<VisionPoint> result = new ArrayList<>(4);
        for (int index = 0; index < coordinates.length; index += 2) {
            double x = coordinates[index];
            double y = coordinates[index + 1];
            if (!Double.isFinite(x) || !Double.isFinite(y)
                    || x < 0.0 || x > 1.0 || y < 0.0 || y > 1.0) {
                return null;
            }
            result.add(new VisionPoint(x, y));
        }
        try {
            AdminReferenceGeometry.validateCorners(result);
        } catch (IllegalArgumentException invalidCorners) {
            return null;
        }
        return result;
    }

    private static List<String> selectBundledReferences(Component component) {
        // Existing installations retain old library paths in SQLite. Always apply the current
        // reviewed policy, so an app update cannot reintroduce a rejected image from that data.
        return RecognitionReferencePolicy.bundledAssetsFor(component.getId());
    }

    private static double[] dimensions(AssemblArRepository repository, Component component) {
        // This kit's board was measured on 7 October. Existing saved nominal metadata must
        // not restore the larger printed mat outline as the physical pose plane.
        if (NeobotSeedData.MAIN_BOARD.equals(component.getId())) {
            return new double[]{MainBoardDimensions.WIDTH_MM, MainBoardDimensions.HEIGHT_MM};
        }
        SpatialMetadata spatial = repository.getSpatialMetadata(component.getId());
        if (spatial != null && spatial.getWidthMillimeters() > 0
                && spatial.getHeightMillimeters() > 0) {
            return new double[]{spatial.getWidthMillimeters(), spatial.getHeightMillimeters()};
        }
        // The manuals omit most measurements. These conservative nominal planes keep solvePnP
        // operational until an administrator replaces them with measured registration metadata.
        String id = component.getId().toLowerCase(Locale.US);
        // The extension PCB is authored upright in its reference images: the model X axis
        // spans the shorter left/right edge and Y spans the longer top/bottom edge. Keeping the
        // old landscape ordering (150 x 130) made solvePnP try to fit the homography to the wrong
        // aspect ratio, so a strong identity match could still yield no usable pose.
        if (id.contains("extension_circuit")) {
            return new double[]{130, 150};
        }
        // Upright bracket references share the nominal Blender profile. Measured metadata above
        // still takes precedence; these dimensions are not a physical calibration.
        if (NeobotSeedData.MOTOR_BRACKET.equals(id)) return new double[]{32, 48};
        if (id.contains("gear_motor")) return new double[]{70, 25};
        // Canonical Nano corners put USB at the bottom: narrow edge is X, long edge is Y.
        if (id.contains("microcontroller")) return new double[]{18, 45};
        // Driver references place the terminal block at the bottom of the longer PCB axis.
        if (id.contains("motor_driver")) return new double[]{35, 45};
        if (id.contains("distance_sensor")) return new double[]{45, 20};
        if (id.contains("oled")) return new double[]{28, 28};
        if (id.contains("wheel") || id.contains("caster")) return new double[]{65, 65};
        if (id.contains("screw") || id.contains("nut") || id.contains("standoff")) return new double[]{20, 20};
        return new double[]{50, 40};
    }

    /**
     * Uses the measured board plane for every photograph, independently of source-image aspect.
     * Other components retain their own registration dimensions.
     */
    static double[] bundledDimensions(
            Component component, String path, double[] componentDimensions) {
        if (component == null || path == null || componentDimensions == null
                || componentDimensions.length != 2) {
            throw new IllegalArgumentException("Bundled reference dimensions are required");
        }
        if (NeobotSeedData.MAIN_BOARD.equals(component.getId())) {
            return new double[]{MainBoardDimensions.WIDTH_MM, MainBoardDimensions.HEIGHT_MM};
        }
        return componentDimensions.clone();
    }

    /**
     * Marks the physical target inside each source-PDF photograph. These quadrilaterals keep pose
     * corners on the component rather than on captions, hands, or the complete robot scene. They
     * are also the intended region-of-interest for reference ORB extraction.
     */
    static void applyPlanarBounds(ComponentReferenceSpec.Builder builder,
                                  Component component,
                                  String path) {
        String id = component.getId();
        if (NeobotSeedData.MAIN_BOARD.equals(id)) {
            boolean whiteVariant = path.contains("/variants/main-board-white-");
            boolean underside = path.endsWith("image-034.png")
                    || path.endsWith("main-board-white-underside.jpg");
            // The PDF extracts include black margins. Pose corners belong to the PCB edge,
            // not that image padding (the underside board starts lower in its photograph).
            double left = whiteVariant ? 0.03 : 0.069;
            double top = whiteVariant ? 0.03 : underside ? 0.157 : 0.137;
            double right = whiteVariant ? 0.97 : 0.925;
            double bottom = whiteVariant ? 0.97 : 0.907;
            if (underside) {
                // The underside is the opposite face of the chassis. Preserve the top face's
                // canonical left/right axes by reflecting its source-image corner correspondence.
                // The negative winding also makes an underside observation MIRRORED during the
                // step-1 upright check, while later spatial-anchor use retains correct handedness.
                horizontallyMirroredBounds(builder, left, top, right, bottom);
            } else {
                bounds(builder, left, top, right, bottom);
            }
        } else if (NeobotSeedData.MOTOR_BRACKET.equals(id)) {
            bounds(builder, 0.08, 0.58, 0.48, 0.96);
        } else if (NeobotSeedData.GEAR_MOTOR.equals(id)) {
            if (path.endsWith("image-005.png")) {
                bounds(builder, 0.08, 0.48, 0.93, 0.90);
            } else {
                bounds(builder, 0.18, 0.06, 0.67, 0.58);
            }
        } else if (NeobotSeedData.MOTOR_DRIVER.equals(id)) {
            if (path.endsWith("image-013.png")) {
                bounds(builder, 0.39, 0.26, 0.61, 0.53);
            } else {
                bounds(builder, 0.03, 0.04, 0.45, 0.61);
            }
        } else if (NeobotSeedData.MICROCONTROLLER.equals(id)) {
            if (path.endsWith("image-023.png")) {
                bounds(builder, 0.10, 0.38, 0.48, 0.68);
            } else {
                bounds(builder, 0.43, 0.53, 0.62, 0.98);
            }
        } else if (NeobotSeedData.CASTER_WHEEL.equals(id)) {
            bounds(builder, 0.21, 0.09, 0.78, 0.35);
        } else if (NeobotSeedData.LINE_TRACKER.equals(id)) {
            bounds(builder, 0.00, 0.09, 0.16, 0.52);
        } else if (NeobotSeedData.DISTANCE_SENSOR.equals(id)) {
            if (path.endsWith("image-043.png")) {
                bounds(builder, 0.26, 0.76, 0.73, 0.99);
            } else {
                bounds(builder, 0.28, 0.70, 0.67, 0.91);
            }
        } else if (NeobotSeedData.LED.equals(id)) {
            bounds(builder, 0.74, 0.48, 0.95, 0.78);
        } else if (NeobotSeedData.SPEAKER.equals(id)) {
            // On the angled step-2 source image, isolate the round on-board buzzer and nearby
            // silkscreen; the rest of the partly assembled chassis must not contribute ORB points.
            bounds(builder, 0.58, 0.45, 0.70, 0.61);
        } else if (NeobotSeedData.STANDOFF.equals(id)) {
            bounds(builder, 0.40, 0.33, 0.53, 0.53);
        } else if (NeobotSeedData.WHEEL.equals(id)) {
            bounds(builder, 0.68, 0.15, 0.99, 0.88);
        } else if (NeobotSeedData.OLED_PROTECTOR.equals(id)) {
            bounds(builder, 0.30, 0.02, 0.69, 0.43);
        } else if (NeobotSeedData.DATA_CABLE.equals(id)) {
            if (path.endsWith("image-057.png")) {
                bounds(builder, 0.31, 0.27, 0.99, 0.65);
            } else {
                bounds(builder, 0.22, 0.18, 0.64, 0.55);
            }
        } else if (NeobotSeedData.EXTENSION_BOARD.equals(id)) {
            bounds(builder, 0.04, 0.04, 0.96, 0.91);
        } else if (NeobotSeedData.OLED_DISPLAY.equals(id)) {
            if (path.endsWith("image-019.png")) {
                bounds(builder, 0.025, 0.125, 0.98, 0.99);
                builder.polarityAnchorsVerified(false);
            } else {
                bounds(builder, 0.22, 0.38, 0.77, 0.78);
            }
        } else if (NeobotSeedData.BATTERY_PACK.equals(id)) {
            bounds(builder, 0.00, 0.56, 0.48, 0.98);
        } else if (NeobotSeedData.JUMPER_WIRE.equals(id)) {
            if (path.endsWith("image-030.png")) {
                bounds(builder, 0.25, 0.20, 0.74, 0.80);
            } else if (path.endsWith("image-032.png")) {
                bounds(builder, 0.25, 0.12, 0.75, 0.82);
            } else {
                bounds(builder, 0.20, 0.00, 0.63, 0.66);
            }
        } else if (NeobotSeedData.MOMENTARY_BUTTON.equals(id)) {
            if (path.endsWith("image-002.png")) {
                bounds(builder, 0.49, 0.12, 0.72, 0.73);
            } else {
                bounds(builder, 0.12, 0.12, 0.58, 0.94);
            }
        } else if (NeobotSeedData.IR_RECEIVER.equals(id)) {
            if (path.endsWith("image-042.png")) {
                bounds(builder, 0.72, 0.19, 0.99, 0.62);
            } else {
                // Follow the rotated PCB plane; the former axis-aligned box included black
                // padding and the raised sensor, neither of which supplies board pose corners.
                builder.planarReferenceCornersNormalized(
                        new VisionPoint(.3125, .348), new VisionPoint(.632, .3227),
                        new VisionPoint(.679, .567), new VisionPoint(.344, .597))
                        .polarityAnchorsVerified(false);
            }
        } else if (NeobotSeedData.IR_TRANSMITTER.equals(id)) {
            builder.planarReferenceCornersNormalized(
                    new VisionPoint(.042, .240), new VisionPoint(.256, .020),
                    new VisionPoint(.672, .716), new VisionPoint(.466, .902))
                    .polarityAnchorsVerified(false);
        } else if (NeobotSeedData.CR2025_BATTERY.equals(id)) {
            bounds(builder, 0.43, 0.75, 0.68, 0.99);
        } else if (NeobotSeedData.SCREW_30MM.equals(id)) {
            bounds(builder, 0.43, 0.47, 0.67, 0.75);
        } else if (NeobotSeedData.SCREW_SHORT.equals(id)) {
            bounds(builder, 0.40, 0.24, 0.63, 0.48);
        } else if (NeobotSeedData.SCREW_20MM.equals(id)) {
            bounds(builder, 0.16, 0.26, 0.32, 0.70);
        } else if (NeobotSeedData.MOUNTING_SCREW.equals(id)) {
            bounds(builder, 0.18, 0.53, 0.37, 0.78);
        } else if (NeobotSeedData.NUT.equals(id)) {
            bounds(builder, 0.34, 0.37, 0.78, 0.93);
        } else if (NeobotSeedData.PLASTIC_SPACER.equals(id)) {
            bounds(builder, 0.70, 0.05, 0.98, 0.65);
        } else if (NeobotSeedData.PHILLIPS_SCREWDRIVER.equals(id)) {
            bounds(builder, 0.61, 0.45, 1.00, 0.99);
        }
    }

    private static void bounds(ComponentReferenceSpec.Builder builder,
                               double left, double top, double right, double bottom) {
        builder.planarReferenceCornersNormalized(
                new VisionPoint(left, top),
                new VisionPoint(right, top),
                new VisionPoint(right, bottom),
                new VisionPoint(left, bottom));
    }

    private static void horizontallyMirroredBounds(
            ComponentReferenceSpec.Builder builder,
            double left, double top, double right, double bottom) {
        builder.mirroredPlanarReferenceCornersNormalized(
                new VisionPoint(right, top),
                new VisionPoint(left, top),
                new VisionPoint(left, bottom),
                new VisionPoint(right, bottom));
    }

    /**
     * Applies disjoint apparent-radius bands to the circular fallback targets. The radius is a
     * fraction of the camera frame's shorter edge, so these ranges are deliberately conservative.
     * Distinct bands are essential: otherwise one Hough circle satisfies every circular catalog
     * entry and iteration order, rather than evidence, determines the reported component.
     */
    static void applyShapeHint(ComponentReferenceSpec.Builder builder, Component component) {
        switch (component.getId()) {
            case NeobotSeedData.SCREW_SHORT:
                builder.circularHardware(0.006, 0.010);
                break;
            case NeobotSeedData.MOUNTING_SCREW:
                builder.circularHardware(0.011, 0.015);
                break;
            case NeobotSeedData.SCREW_20MM:
                builder.circularHardware(0.016, 0.021);
                break;
            case NeobotSeedData.SCREW_30MM:
                builder.circularHardware(0.022, 0.028);
                break;
            case NeobotSeedData.NUT:
                builder.circularHardware(0.030, 0.037);
                break;
            case NeobotSeedData.STANDOFF:
                builder.circularHardware(0.039, 0.048);
                break;
            case NeobotSeedData.CR2025_BATTERY:
                builder.circularHardware(0.050, 0.062);
                break;
            case NeobotSeedData.SPEAKER:
                builder.circularHardware(0.065, 0.080);
                break;
            case NeobotSeedData.DISTANCE_SENSOR:
                builder.circularHardware(0.083, 0.105);
                break;
            case NeobotSeedData.CASTER_WHEEL:
                builder.circularHardware(0.110, 0.145);
                break;
            case NeobotSeedData.WHEEL:
                builder.circularHardware(0.150, 0.220);
                break;
            default:
                // ORB remains the recognition method for non-circular catalog entries.
                break;
        }
    }
}
