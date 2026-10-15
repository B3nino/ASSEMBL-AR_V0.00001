package dev.chyken.assembl_ar.render;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.OverlayType;
import dev.chyken.assembl_ar.model.PinConnection;

/**
 * Deterministic spatial authoring for the 18 section-level Personal Robot Kit validation gates.
 *
 * <p>All coordinates are normalized within the currently tracked chassis/board solvePnP
 * reference: +X right, +Y up, and +Z out of its face. Every seeded pin mapping receives its own
 * independently rendered route and retains its complete endpoint/color/signal/note metadata.</p>
 */
public final class NeobotGuidanceFactory {
    private NeobotGuidanceFactory() {
    }

    /**
     * Creates authored guidance for a manuscript section's final assembly validation.
     *
     * @param step exact NEOBOT assembly step
     * @param componentId component recognized for this step
     * @param openGlModelViewMatrix solvePnP OpenCV-to-OpenGL model-view matrix, or null for preview
     */
    public static ArGuidance create(AssemblyStep step, String componentId,
                                    @Nullable float[] openGlModelViewMatrix) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(componentId, "componentId");
        switch (step.getStepNumber()) {
            case 1:
                return placement(step, componentId, openGlModelViewMatrix,
                        ArGuidance.GhostStyle.BOARD,
                        boardPoint(0.50f, 0.50f, 0.04f),
                        0.95f, 0.72f, 0.12f, 0f, 0f, 0f, 0.18f);
            case 2:
                return dualPlacement(step, componentId, openGlModelViewMatrix,
                        ArGuidance.GhostStyle.MOTOR,
                        boardPoint(0.14f, 0.58f, 0.08f),
                        boardPoint(0.86f, 0.58f, 0.08f),
                        0.72f, 0.38f, 0.38f, 0f, 0f, 90f, -90f, 0.16f);
            case 3:
                return placement(step, componentId, openGlModelViewMatrix,
                        ArGuidance.GhostStyle.BOARD,
                        boardPoint(0.50f, 0.35f, 0.10f),
                        0.50f, 0.36f, 0.18f, 0f, 0f, 0f, 0.14f);
            case 4:
                return terminalScrewTarget(step, componentId, openGlModelViewMatrix);
            case 5:
                return motorLeadRoute(step, componentId, openGlModelViewMatrix);
            case 6:
                return placement(step, componentId, openGlModelViewMatrix,
                        ArGuidance.GhostStyle.BOARD,
                        boardPoint(0.50f, 0.72f, 0.10f),
                        0.38f, 0.72f, 0.12f, 0f, 0f, 90f, 0.13f);
            case 7:
                return driverPowerRoute(step, componentId, openGlModelViewMatrix);
            case 8:
                return pwmSignalRoute(step, componentId, openGlModelViewMatrix);
            case 9:
                return placement(step, componentId, openGlModelViewMatrix,
                        ArGuidance.GhostStyle.CASTER,
                        boardPoint(0.50f, 0.14f, 0.08f),
                        0.50f, 0.50f, 0.72f, 0f, 0f, 0f, 0.16f);
            case 10:
                return dualPlacement(step, componentId, openGlModelViewMatrix,
                        ArGuidance.GhostStyle.BOARD,
                        boardPoint(0.07f, 0.23f, -0.08f),
                        boardPoint(0.93f, 0.23f, -0.08f),
                        0.38f, 0.50f, 0.10f, 180f, 0f, 0f, 0f, 0.14f);
            case 11:
                return sensorHarnessRoute(step, componentId, openGlModelViewMatrix);
            case 12:
                return placement(step, componentId, openGlModelViewMatrix,
                        ArGuidance.GhostStyle.FASTENER,
                        boardPoint(0.50f, 0.50f, 0.12f),
                        0.28f, 0.28f, 0.80f, 0f, 0f, 0f, 0.13f);
            case 13:
                return dualPlacement(step, componentId, openGlModelViewMatrix,
                        ArGuidance.GhostStyle.WHEEL,
                        boardPoint(0.02f, 0.58f, 0.05f),
                        boardPoint(0.98f, 0.58f, 0.05f),
                        0.70f, 0.70f, 0.45f, 0f, 90f, 0f, 0f, 0.17f);
            case 14:
                return placement(step, componentId, openGlModelViewMatrix,
                        ArGuidance.GhostStyle.BOARD,
                        boardPoint(0.43f, 0.42f, 0.08f),
                        0.48f, 0.36f, 0.14f, 0f, 0f, 0f, 0.14f);
            case 15:
                return ribbonCableRoute(step, componentId, openGlModelViewMatrix);
            case 16:
                return placement(step, componentId, openGlModelViewMatrix,
                        ArGuidance.GhostStyle.BOARD,
                        boardPoint(0.50f, 0.50f, 0.24f),
                        1.00f, 0.72f, 0.12f, 0f, 0f, 0f, 0.18f);
            case 17:
                return oledHeaderRoute(step, componentId, openGlModelViewMatrix);
            case 18:
                return placement(step, componentId, openGlModelViewMatrix,
                        ArGuidance.GhostStyle.BOARD,
                        boardPoint(0.22f, 0.62f, 0.10f),
                        0.52f, 0.34f, 0.22f, 0f, 0f, 0f, 0.14f);
            default:
                throw new IllegalArgumentException(
                        "NEOBOT guidance requires a step number from 1 through 18");
        }
    }

    private static ArGuidance terminalScrewTarget(AssemblyStep step, String componentId,
                                                   @Nullable float[] pose) {
        return base(step, componentId, pose,
                OverlayType.TARGET_GLOW_RING, OverlayType.DYNAMIC_ARROW)
                .ghostStyle(ArGuidance.GhostStyle.NONE)
                .componentTarget(boardPoint(0.50f, 0.35f, 0.04f))
                .targetPin(point(-0.52f, 0.34f, 0.12f))
                .targetMarker(ArGuidance.TargetMarker.FASTENER_HOLE)
                .markerRadius(0.12f)
                .build();
    }

    /** Step 5: motor lead bundle into the left/right green output-terminal bank. */
    private static ArGuidance motorLeadRoute(AssemblyStep step, String componentId,
                                              @Nullable float[] pose) {
        return wiring(step, componentId, pose, true,
                ArGuidance.GhostStyle.BOARD,
                boardPoint(0.50f, 0.35f, 0.04f),
                point(-0.94f, -0.50f, 0.16f),
                point(-0.38f, 0.30f, 0.16f),
                ArGuidance.TargetMarker.CONNECTION,
                0.13f, 0.62f, 0.46f, 0.18f,
                point(-0.76f, -0.42f, 0.28f),
                point(-0.68f, 0.12f, 0.30f));
    }

    /** Step 7: the paired VCC-to-VIN and GND-to-GND power run. */
    private static ArGuidance driverPowerRoute(AssemblyStep step, String componentId,
                                                @Nullable float[] pose) {
        return wiring(step, componentId, pose, true,
                ArGuidance.GhostStyle.BOARD,
                boardPoint(0.50f, 0.35f, 0.04f),
                point(0.56f, -0.30f, 0.15f),
                point(0.48f, 0.58f, 0.15f),
                ArGuidance.TargetMarker.CONNECTION,
                0.12f, 0.60f, 0.44f, 0.18f,
                point(0.80f, -0.18f, 0.28f),
                point(0.78f, 0.30f, 0.28f));
    }

    /** Step 8: A-1A/A-1B/B-1A/B-1B signal bank to D5/D9/D6/D10 PWM bank. */
    private static ArGuidance pwmSignalRoute(AssemblyStep step, String componentId,
                                              @Nullable float[] pose) {
        return wiring(step, componentId, pose, false,
                ArGuidance.GhostStyle.BOARD,
                boardPoint(0.50f, 0.35f, 0.04f),
                point(-0.46f, 0.34f, 0.15f),
                point(0.50f, 0.38f, 0.15f),
                ArGuidance.TargetMarker.PIN,
                0.10f, 0.60f, 0.44f, 0.18f,
                point(-0.24f, 0.66f, 0.26f),
                point(0.22f, 0.68f, 0.26f));
    }

    /** Step 11: front distance/LED/speaker/tracker harness toward the MCU signal header. */
    private static ArGuidance sensorHarnessRoute(AssemblyStep step, String componentId,
                                                  @Nullable float[] pose) {
        return wiring(step, componentId, pose, false,
                ArGuidance.GhostStyle.BOARD,
                boardPoint(0.50f, 0.12f, 0.04f),
                point(0f, 0.52f, 0.18f),
                point(0.28f, -0.72f, 0.14f),
                ArGuidance.TargetMarker.PIN,
                0.11f, 0.54f, 0.34f, 0.20f,
                point(0.28f, 0.26f, 0.30f),
                point(0.38f, -0.30f, 0.28f));
    }

    /** Step 15: polarized keyed ribbon from the main-board socket to the extension socket. */
    private static ArGuidance ribbonCableRoute(AssemblyStep step, String componentId,
                                                @Nullable float[] pose) {
        return wiring(step, componentId, pose, true,
                ArGuidance.GhostStyle.BOARD,
                boardPoint(0.28f, 0.42f, 0.04f),
                point(-0.78f, -0.06f, 0.10f),
                point(0.78f, -0.06f, 0.10f),
                ArGuidance.TargetMarker.CONNECTION,
                0.14f, 0.92f, 0.22f, 0.08f,
                point(-0.38f, 0.24f, 0.30f),
                point(0.36f, 0.24f, 0.30f));
    }

    /** Step 17: GND/VCC/SCL/SDA OLED header into its protected extension-board header. */
    private static ArGuidance oledHeaderRoute(AssemblyStep step, String componentId,
                                               @Nullable float[] pose) {
        // The OLED header is keyed by GND/VCC/SCL/SDA geometry; it has no red lead. Its ORB
        // orientation and pin alignment are the visual evidence, followed by continuity checking.
        return wiring(step, componentId, pose, false,
                ArGuidance.GhostStyle.BOARD,
                boardPoint(0.43f, 0.42f, 0.04f),
                point(-0.56f, -0.60f, 0.14f),
                point(0.54f, -0.54f, 0.14f),
                ArGuidance.TargetMarker.PIN,
                0.10f, 0.54f, 0.42f, 0.12f,
                point(-0.30f, -0.38f, 0.28f),
                point(0.18f, -0.34f, 0.26f));
    }

    private static ArGuidance placement(AssemblyStep step, String componentId,
                                         @Nullable float[] pose,
                                         ArGuidance.GhostStyle ghostStyle,
                                         ArGuidance.Point3 target,
                                         float scaleX, float scaleY, float scaleZ,
                                         float rotationX, float rotationY, float rotationZ,
                                         float markerRadius) {
        return base(step, componentId, pose,
                OverlayType.GHOST_COMPONENT, OverlayType.TARGET_GLOW_RING)
                .ghostStyle(ghostStyle)
                .componentTarget(target)
                .componentTargetRotation(rotationX, rotationY, rotationZ)
                .targetMarker(ArGuidance.TargetMarker.COMPONENT_PLACEMENT)
                .ghostScale(scaleX, scaleY, scaleZ)
                .markerRadius(markerRadius)
                .build();
    }

    private static ArGuidance wiring(AssemblyStep step, String componentId,
                                      @Nullable float[] pose, boolean polarity,
                                      ArGuidance.GhostStyle ghostStyle,
                                      ArGuidance.Point3 componentTarget,
                                      ArGuidance.Point3 source,
                                      ArGuidance.Point3 target,
                                      ArGuidance.TargetMarker targetMarker,
                                      float markerRadius,
                                      float ghostScaleX, float ghostScaleY, float ghostScaleZ,
                                      ArGuidance.Point3... waypoints) {
        ArGuidance.Builder builder = polarity
                ? base(step, componentId, pose,
                        OverlayType.GHOST_COMPONENT, OverlayType.PIN_ALIGNMENT,
                        OverlayType.TARGET_GLOW_RING, OverlayType.POLARITY_ARROW)
                : base(step, componentId, pose,
                        OverlayType.GHOST_COMPONENT, OverlayType.PIN_ALIGNMENT,
                        OverlayType.TARGET_GLOW_RING, OverlayType.DYNAMIC_ARROW);
        List<PinConnection> mappings = step.getPinConnections();
        if (mappings.isEmpty()) {
            throw new IllegalArgumentException(
                    "Wiring guidance requires at least one seeded pin mapping");
        }

        ArGuidance.WireRoute[] routes = new ArGuidance.WireRoute[mappings.size()];
        float deltaX = target.x - source.x;
        float deltaY = target.y - source.y;
        float length = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        float perpendicularX = length > 0f ? -deltaY / length : 0f;
        float perpendicularY = length > 0f ? deltaX / length : 1f;
        float spacing = mappings.size() <= 1
                ? 0f : Math.min(0.065f, 0.52f / (mappings.size() - 1));
        float centerIndex = (mappings.size() - 1) * 0.5f;

        for (int index = 0; index < mappings.size(); index++) {
            float lane = (index - centerIndex) * spacing;
            float zLane = (index - centerIndex) * 0.004f;
            ArGuidance.Point3 routedSource = offset(
                    source, perpendicularX * lane, perpendicularY * lane, zLane);
            ArGuidance.Point3 routedTarget = offset(
                    target, perpendicularX * lane, perpendicularY * lane, zLane);
            ArGuidance.Point3[] routedWaypoints = new ArGuidance.Point3[waypoints.length];
            for (int waypointIndex = 0; waypointIndex < waypoints.length; waypointIndex++) {
                // A slightly wider middle fan keeps dense headers individually legible while the
                // average centerline and all existing component placement centers remain fixed.
                routedWaypoints[waypointIndex] = offset(waypoints[waypointIndex],
                        perpendicularX * lane * 1.15f,
                        perpendicularY * lane * 1.15f, zLane);
            }
            routes[index] = ArGuidance.WireRoute.forConnection(
                    mappings.get(index), routedSource, routedTarget, routedWaypoints);
        }

        return builder
                .ghostStyle(ghostStyle)
                .componentTarget(componentTarget)
                .wireRoutes(routes)
                .targetMarker(targetMarker)
                .markerRadius(markerRadius)
                .routeWidthPixels(polarity ? 5f : 4f)
                .ghostScale(ghostScaleX, ghostScaleY, ghostScaleZ)
                .build();
    }

    private static ArGuidance dualPlacement(AssemblyStep step, String componentId,
                                             @Nullable float[] pose,
                                             ArGuidance.GhostStyle ghostStyle,
                                             ArGuidance.Point3 firstTarget,
                                             ArGuidance.Point3 secondTarget,
                                             float scaleX, float scaleY, float scaleZ,
                                             float rotationX, float rotationY, float rotationZ,
                                             float secondRotationZ,
                                             float markerRadius) {
        return base(step, componentId, pose,
                OverlayType.GHOST_COMPONENT, OverlayType.TARGET_GLOW_RING)
                .ghostStyle(ghostStyle)
                .componentTarget(firstTarget)
                .additionalComponentTargets(secondTarget)
                .componentTargetRotation(rotationX, rotationY, rotationZ)
                .additionalComponentTargetRotationsZ(secondRotationZ)
                .targetMarker(ArGuidance.TargetMarker.COMPONENT_PLACEMENT)
                .ghostScale(scaleX, scaleY, scaleZ)
                .markerRadius(markerRadius)
                .build();
    }

    private static ArGuidance.Builder base(AssemblyStep step, String componentId,
                                            @Nullable float[] pose,
                                            OverlayType... requiredOverlays) {
        EnumSet<OverlayType> overlays = EnumSet.noneOf(OverlayType.class);
        overlays.addAll(step.getOverlayTypes());
        if (requiredOverlays != null) {
            for (OverlayType overlay : requiredOverlays) {
                overlays.add(Objects.requireNonNull(overlay, "requiredOverlay"));
            }
        }
        List<OverlayType> orderedOverlays = new ArrayList<>(overlays);
        return ArGuidance.builder(componentId, orderedOverlays)
                .pose(pose)
                .coordinateSpace(ArGuidance.CoordinateSpace.NORMALIZED_COMPONENT)
                // These are the same nominal planes used by VisionReferenceFactory for both
                // NEOBOT chassis boards. The renderer maps -1..+1 to their physical half-extents.
                .referencePlaneSize(100f, 120f);
    }

    private static ArGuidance.Point3 point(float x, float y, float z) {
        return ArGuidance.Point3.at(x, y, z);
    }

    private static ArGuidance.Point3 offset(ArGuidance.Point3 point,
                                             float x, float y, float z) {
        return ArGuidance.Point3.at(point.x + x, point.y + y, point.z + z);
    }

    /** Converts top-left unit-square board coordinates to renderer-local +X right/+Y up. */
    private static ArGuidance.Point3 boardPoint(float x, float y, float z) {
        return point(x * 2f - 1f, 1f - y * 2f, z);
    }
}
