package dev.chyken.assembl_ar.camera;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.MainBoardDimensions;
import dev.chyken.assembl_ar.vision.VisionPoint;

/** Camera photographs of the loose board. Corners describe the board, not the image canvas. */
public final class MainboardPerspectiveReferenceCatalog {
    private static final String ROOT = "references/Mainboard_perspectives/";
    // Broadly spaced front and underside viewpoints keep the common path small. Every other
    // registration remains available when this first pass cannot establish a sound homography.
    private static final Set<String> QUICK_VIEWS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    "Mainboard_FrontPOV_MID.jpg", "Mainboard_FrontPOV_LEFT2.jpg",
                    "Mainboard_FrontPOV_RIGHT1.jpg", "Mainboard_FrontPOV_LEFTANGLE1.jpg",
                    "Mainboard_FrontPOV_RIGHTANGLE2.jpg", "Mainboard_FrontPOV_LOWER2.jpg",
                    "Mainboard_FrontPOV_UPPER2.jpg", "Mainboard_BacksidePOV_Mid.jpg",
                    "Mainboard_BacksidePOV_LEFT1.jpg", "Mainboard_BacksidePOV_RIGHT1.jpg",
                    "Mainboard_BacksidePOV_lower1.jpg", "Mainboard_BacksidePOV_Upper1.jpg")));

    private MainboardPerspectiveReferenceCatalog() { }

    public static List<ComponentReferenceSpec> forComponent(Component component) {
        if (!NeobotSeedData.MAIN_BOARD.equals(component.getId())) return Collections.emptyList();
        List<ComponentReferenceSpec> result = new ArrayList<>();

        // Canonical order is front-face TL, TR, BR, BL. Back-face entries are reflected below,
        // so a recognized underside cannot be accepted as the correctly oriented base board.
        addBack(result, component, "Mainboard_BacksidePOV_LEFT1.jpg", new double[]{.13,.04,.81,.02,.97,.89,.19,.97});
        addBack(result, component, "Mainboard_BacksidePOV_LEFT14.jpg", new double[]{.18,.02,.58,.01,.87,.98,.28,.98});
        addBack(result, component, "Mainboard_BacksidePOV_LEFT2.jpg", new double[]{.20,.03,.70,.02,.96,.94,.32,.98});
        addBack(result, component, "Mainboard_BacksidePOV_LEFT3.jpg", new double[]{.24,.02,.67,.02,.96,.96,.31,.98});
        addBack(result, component, "Mainboard_BacksidePOV_lower1.jpg", new double[]{.06,.05,.93,.05,.99,.86,.02,.91});
        addBack(result, component, "Mainboard_BacksidePOV_lower2.jpg", new double[]{.05,.07,.94,.06,.98,.84,.03,.88});
        addBack(result, component, "Mainboard_BacksidePOV_Mid.jpg", new double[]{.05,.04,.93,.04,.97,.96,.04,.96});
        addBack(result, component, "Mainboard_BacksidePOV_RIGHT1.jpg", new double[]{.08,.06,.91,.02,.98,.98,.20,.98});
        addBack(result, component, "Mainboard_BacksidePOV_RIGHT2.jpg", new double[]{.12,.04,.84,.01,.94,.98,.29,.98});
        addBack(result, component, "Mainboard_BacksidePOV_RIGHT3.jpg", new double[]{.22,.02,.81,.01,.91,.97,.33,.98});
        addBack(result, component, "Mainboard_BacksidePOV_RIGHT4.jpg", new double[]{.30,.03,.73,.01,.83,.99,.40,.99});
        addBack(result, component, "Mainboard_BacksidePOV_Upper1.jpg", new double[]{.08,.02,.93,.02,.98,.97,.02,.97});
        addBack(result, component, "Mainboard_BacksidePOV_Upper2.jpg", new double[]{.04,.03,.96,.03,.91,.98,.09,.98});
        addBack(result, component, "Mainboard_BacksidePOV_Upper3.jpg", new double[]{.03,.01,.96,.02,.90,.99,.10,.99});

        addFront(result, component, "Mainboard_FrontPOV_LEFT1.jpg", new double[]{.13,.07,.80,.04,.92,.92,.16,.98});
        addFront(result, component, "Mainboard_FrontPOV_LEFT2.jpg", new double[]{.19,.06,.76,.03,.92,.93,.22,.98});
        addFront(result, component, "Mainboard_FrontPOV_LEFT3.jpg", new double[]{.18,.04,.77,.03,.91,.94,.23,.99});
        addFront(result, component, "Mainboard_FrontPOV_LEFT4.jpg", new double[]{.22,.03,.73,.02,.91,.94,.27,.98});
        addFront(result, component, "Mainboard_FrontPOV_LEFT5.jpg", new double[]{.27,.02,.69,.02,.88,.95,.31,.98});
        addFront(result, component, "Mainboard_FrontPOV_LEFTANGLE1.jpg", new double[]{.03,.10,.60,.10,.92,.90,.32,.96});
        addFront(result, component, "Mainboard_FrontPOV_LEFTANGLE2.jpg", new double[]{.03,.09,.58,.08,.91,.91,.34,.96});
        addFront(result, component, "Mainboard_FrontPOV_LEFTANGLE3.jpg", new double[]{.03,.12,.56,.08,.94,.92,.36,.97});
        addFront(result, component, "Mainboard_FrontPOV_LOWER1.jpg", new double[]{.07,.05,.92,.04,.97,.92,.03,.94});
        addFront(result, component, "Mainboard_FrontPOV_LOWER2.jpg", new double[]{.06,.05,.94,.05,.98,.91,.02,.94});
        addFront(result, component, "Mainboard_FrontPOV_LOWER3.jpg", new double[]{.07,.05,.92,.04,.99,.85,.02,.88});
        addFront(result, component, "Mainboard_FrontPOV_LOWER4.jpg", new double[]{.06,.04,.93,.04,.99,.83,.02,.87});
        addFront(result, component, "Mainboard_FrontPOV_LOWER5.jpg", new double[]{.07,.04,.92,.04,.99,.78,.02,.82});
        addFront(result, component, "Mainboard_FrontPOV_MID.jpg", new double[]{.15,.08,.91,.07,.94,.89,.12,.91});
        addFront(result, component, "Mainboard_FrontPOV_RIGHT1.jpg", new double[]{.10,.04,.88,.04,.95,.94,.09,.95});
        addFront(result, component, "Mainboard_FrontPOV_RIGHT3.jpg", new double[]{.09,.04,.84,.05,.92,.97,.19,.96});
        addFront(result, component, "Mainboard_FrontPOV_RIGHT4.jpg", new double[]{.11,.03,.80,.04,.90,.97,.23,.97});
        addFront(result, component, "Mainboard_FrontPOV_RIGHT5.jpg", new double[]{.12,.03,.79,.04,.89,.97,.25,.97});
        addFront(result, component, "Mainboard_FrontPOV_RIGHTANGLE2.jpg", new double[]{.44,.07,.95,.27,.67,.95,.05,.67});
        addFront(result, component, "Mainboard_FrontPOV_RIGHTANGLE3.jpg", new double[]{.42,.08,.95,.25,.70,.94,.05,.68});
        addFront(result, component, "Mainboard_FrontPOV_RIGHTANGLE4.jpg", new double[]{.42,.09,.94,.26,.69,.95,.04,.68});
        addFront(result, component, "Mainboard_FrontPOV_UPPER1.jpg", new double[]{.09,.04,.93,.04,.97,.96,.07,.97});
        addFront(result, component, "Mainboard_FrontPOV_UPPER2.jpg", new double[]{.08,.04,.94,.04,.97,.96,.05,.96});
        addFront(result, component, "Mainboard_FrontPOV_UPPER3.jpg", new double[]{.07,.03,.95,.03,.97,.97,.05,.97});
        addFront(result, component, "Mainboard_FrontPOV_UPPER5.jpg", new double[]{.04,.02,.97,.02,.96,.98,.04,.98});
        addFront(result, component, "Mainboard_FrontPOV_UPPER6.jpg", new double[]{.03,.02,.97,.02,.96,.98,.04,.98});
        addFront(result, component, "Mainboard_FrontPOV_UPPER7.jpg", new double[]{.04,.02,.96,.02,.97,.98,.03,.98});
        addFront(result, component, "Mainboard_FrontPOV_UPPER8.jpg", new double[]{.03,.02,.97,.02,.97,.97,.03,.97});
        return Collections.unmodifiableList(result);
    }

    private static void addFront(List<ComponentReferenceSpec> result, Component component,
                                 String fileName, double[] corners) {
        add(result, component, fileName, corners, false);
    }

    private static void addBack(List<ComponentReferenceSpec> result, Component component,
                                String fileName, double[] corners) {
        add(result, component, fileName, corners, true);
    }

    private static void add(List<ComponentReferenceSpec> result, Component component,
                            String fileName, double[] corners, boolean backside) {
        VisionPoint topLeft = point(corners, 0);
        VisionPoint topRight = point(corners, 2);
        VisionPoint bottomRight = point(corners, 4);
        VisionPoint bottomLeft = point(corners, 6);
        ComponentReferenceSpec.Builder builder = ComponentReferenceSpec.builder(
                        "main_board_perspective_" + fileName.substring(0, fileName.length() - 4)
                                .toLowerCase(java.util.Locale.US),
                        component.getId(), component.getName(),
                        MainBoardDimensions.WIDTH_MM, MainBoardDimensions.HEIGHT_MM)
                .jpegAssetPath(ROOT + fileName)
                .maximumImageDimension(1280)
                .fastAcquisitionView(QUICK_VIEWS.contains(fileName))
                .planarInlierPose(true)
                .polarityAnchorsVerified(false);
        if (backside) {
            builder.mirroredPlanarReferenceCornersNormalized(
                    topRight, topLeft, bottomLeft, bottomRight);
        } else {
            builder.planarReferenceCornersNormalized(
                    topLeft, topRight, bottomRight, bottomLeft);
        }
        result.add(builder.build());
    }

    private static VisionPoint point(double[] coordinates, int index) {
        return new VisionPoint(coordinates[index], coordinates[index + 1]);
    }
}
