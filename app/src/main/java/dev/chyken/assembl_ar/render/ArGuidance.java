package dev.chyken.assembl_ar.render;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import dev.chyken.assembl_ar.model.OverlayType;
import dev.chyken.assembl_ar.model.PinConnection;

/**
 * Immutable, authored spatial guidance rendered in a recognized component's local coordinate
 * frame. Each authored wire route identifies its complete pin-map entry, source point, optional
 * waypoints, and target point. The legacy single-route accessors return the first route so older
 * renderer clients remain source compatible.
 * Coordinates follow the reference model used by solvePnP: +X right, +Y up, and +Z out of the
 * component face.
 */
public final class ArGuidance {
    public enum CoordinateSpace {
        /** Convenient authoring space for generic meshes, normally spanning roughly -1 to +1. */
        NORMALIZED_COMPONENT,
        /** Same physical unit as solvePnP object points, normally millimetres. */
        POSE_UNITS
    }

    public enum GhostStyle {
        AUTO,
        BOARD,
        MOTOR,
        CASTER,
        WHEEL,
        FASTENER,
        NONE
    }

    public enum TargetMarker {
        NONE,
        PIN,
        CONNECTION,
        COMPONENT_PLACEMENT,
        FASTENER_HOLE
    }

    /** Three-dimensional component-local coordinate. */
    public static final class Point3 {
        public final float x;
        public final float y;
        public final float z;

        public Point3(float x, float y, float z) {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                throw new IllegalArgumentException("Spatial guidance coordinates must be finite");
            }
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public static Point3 at(float x, float y, float z) {
            return new Point3(x, y, z);
        }
    }

    /**
     * One rendered route backed by one complete {@link PinConnection} from the seeded step.
     * Keeping the mapping metadata beside its geometry prevents a multi-wire bundle from losing
     * the source/target labels, color, signal, or safety note shown in the detailed pin list.
     */
    public static final class WireRoute {
        private final int order;
        private final String sourceComponentId;
        private final String sourcePin;
        private final String targetComponentId;
        private final String targetPin;
        private final String wireColor;
        private final String signal;
        private final String notes;
        private final Point3 sourcePoint;
        private final Point3 targetPoint;
        private final List<Point3> waypoints;
        private final List<Point3> points;

        private WireRoute(int order, String sourceComponentId, String sourcePin,
                          String targetComponentId, String targetPin, String wireColor,
                          String signal, String notes, Point3 sourcePoint, Point3 targetPoint,
                          Point3... waypoints) {
            if (order < 1) {
                throw new IllegalArgumentException("Wire-route order must be positive");
            }
            this.order = order;
            this.sourceComponentId = Objects.requireNonNull(
                    sourceComponentId, "sourceComponentId");
            this.sourcePin = Objects.requireNonNull(sourcePin, "sourcePin");
            this.targetComponentId = Objects.requireNonNull(
                    targetComponentId, "targetComponentId");
            this.targetPin = Objects.requireNonNull(targetPin, "targetPin");
            this.wireColor = Objects.requireNonNull(wireColor, "wireColor");
            this.signal = Objects.requireNonNull(signal, "signal");
            this.notes = Objects.requireNonNull(notes, "notes");
            this.sourcePoint = Objects.requireNonNull(sourcePoint, "sourcePoint");
            this.targetPoint = Objects.requireNonNull(targetPoint, "targetPoint");

            List<Point3> copiedWaypoints = new ArrayList<>();
            if (waypoints != null) {
                for (Point3 waypoint : waypoints) {
                    copiedWaypoints.add(Objects.requireNonNull(waypoint, "waypoint"));
                }
            }
            this.waypoints = Collections.unmodifiableList(copiedWaypoints);
            List<Point3> completePath = new ArrayList<>(copiedWaypoints.size() + 2);
            completePath.add(sourcePoint);
            completePath.addAll(copiedWaypoints);
            completePath.add(targetPoint);
            points = Collections.unmodifiableList(completePath);
        }

        /** Creates route geometry without dropping any field from the authored pin mapping. */
        public static WireRoute forConnection(PinConnection connection, Point3 sourcePoint,
                                              Point3 targetPoint, Point3... waypoints) {
            Objects.requireNonNull(connection, "connection");
            return new WireRoute(connection.getOrder(), connection.getSourceComponentId(),
                    connection.getSourcePin(), connection.getTargetComponentId(),
                    connection.getTargetPin(), connection.getWireColor(), connection.getSignal(),
                    connection.getNotes(), sourcePoint, targetPoint, waypoints);
        }

        private static WireRoute legacy(Point3 sourcePoint, Point3 targetPoint,
                                        List<Point3> waypoints) {
            return new WireRoute(1, "", "", "", "", "", "", "",
                    sourcePoint, targetPoint, waypoints.toArray(new Point3[0]));
        }

        public int getOrder() {
            return order;
        }

        public String getSourceComponentId() {
            return sourceComponentId;
        }

        public String getSourcePin() {
            return sourcePin;
        }

        public String getTargetComponentId() {
            return targetComponentId;
        }

        public String getTargetPin() {
            return targetPin;
        }

        public String getWireColor() {
            return wireColor;
        }

        public String getSignal() {
            return signal;
        }

        public String getNotes() {
            return notes;
        }

        public Point3 getSourcePoint() {
            return sourcePoint;
        }

        public Point3 getTargetPoint() {
            return targetPoint;
        }

        public List<Point3> getWaypoints() {
            return waypoints;
        }

        /** Complete source-to-target polyline consumed by the renderer. */
        public List<Point3> getPoints() {
            return points;
        }
    }

    private final String componentId;
    private final EnumSet<OverlayType> overlayTypes;
    @Nullable private final float[] openGlModelViewMatrix;
    private final CoordinateSpace coordinateSpace;
    private final float referencePlaneWidthUnits;
    private final float referencePlaneHeightUnits;
    private final GhostStyle ghostStyle;
    private final TargetMarker targetMarker;
    @Nullable private final Point3 sourcePin;
    @Nullable private final Point3 targetPin;
    @Nullable private final Point3 componentTarget;
    private final List<Point3> componentTargets;
    private final List<Point3> connectionPath;
    private final List<WireRoute> wireRoutes;
    private final float markerRadius;
    private final float routeWidthPixels;
    private final float ghostScaleX;
    private final float ghostScaleY;
    private final float ghostScaleZ;
    private final float targetRotationX;
    private final float targetRotationY;
    private final float targetRotationZ;
    private final List<Float> additionalTargetRotationsZ;

    private ArGuidance(Builder builder) {
        componentId = builder.componentId;
        overlayTypes = builder.overlayTypes.clone();
        openGlModelViewMatrix = builder.openGlModelViewMatrix == null
                ? null : builder.openGlModelViewMatrix.clone();
        coordinateSpace = builder.coordinateSpace;
        referencePlaneWidthUnits = builder.referencePlaneWidthUnits;
        referencePlaneHeightUnits = builder.referencePlaneHeightUnits;
        ghostStyle = builder.ghostStyle;
        targetMarker = builder.targetMarker;
        List<WireRoute> routes = new ArrayList<>(builder.wireRoutes);
        if (routes.isEmpty() && builder.sourcePin != null && builder.targetPin != null) {
            routes.add(WireRoute.legacy(
                    builder.sourcePin, builder.targetPin, builder.connectionPath));
        }
        wireRoutes = Collections.unmodifiableList(routes);
        if (wireRoutes.isEmpty()) {
            sourcePin = builder.sourcePin;
            targetPin = builder.targetPin;
            connectionPath = Collections.unmodifiableList(
                    new ArrayList<>(builder.connectionPath));
        } else {
            WireRoute firstRoute = wireRoutes.get(0);
            sourcePin = firstRoute.getSourcePoint();
            targetPin = firstRoute.getTargetPoint();
            connectionPath = firstRoute.getWaypoints();
        }
        componentTarget = builder.componentTarget;
        List<Point3> targets = new ArrayList<>();
        if (componentTarget != null) {
            targets.add(componentTarget);
        }
        targets.addAll(builder.additionalComponentTargets);
        componentTargets = Collections.unmodifiableList(targets);
        markerRadius = Float.isNaN(builder.markerRadius)
                ? (coordinateSpace == CoordinateSpace.POSE_UNITS ? 4f : 0.14f)
                : builder.markerRadius;
        routeWidthPixels = builder.routeWidthPixels;
        ghostScaleX = builder.ghostScaleX;
        ghostScaleY = builder.ghostScaleY;
        ghostScaleZ = builder.ghostScaleZ;
        targetRotationX = builder.targetRotationX;
        targetRotationY = builder.targetRotationY;
        targetRotationZ = builder.targetRotationZ;
        additionalTargetRotationsZ = Collections.unmodifiableList(
                new ArrayList<>(builder.additionalTargetRotationsZ));
    }

    public static Builder builder(String componentId, List<OverlayType> overlayTypes) {
        return new Builder(componentId, overlayTypes);
    }

    public static Builder builder(String componentId, OverlayType... overlayTypes) {
        return new Builder(componentId,
                overlayTypes == null ? Collections.emptyList() : Arrays.asList(overlayTypes));
    }

    public String getComponentId() {
        return componentId;
    }

    public EnumSet<OverlayType> getOverlayTypes() {
        return overlayTypes.clone();
    }

    @Nullable
    public float[] getOpenGlModelViewMatrix() {
        return openGlModelViewMatrix == null ? null : openGlModelViewMatrix.clone();
    }

    public CoordinateSpace getCoordinateSpace() {
        return coordinateSpace;
    }

    /** Physical width represented by normalized X=-1..+1, normally millimetres. */
    public float getReferencePlaneWidthUnits() {
        return referencePlaneWidthUnits;
    }

    /** Physical height represented by normalized Y=-1..+1, normally millimetres. */
    public float getReferencePlaneHeightUnits() {
        return referencePlaneHeightUnits;
    }

    public GhostStyle getGhostStyle() {
        return ghostStyle;
    }

    public TargetMarker getTargetMarker() {
        return targetMarker;
    }

    @Nullable
    public Point3 getSourcePin() {
        return sourcePin;
    }

    @Nullable
    public Point3 getTargetPin() {
        return targetPin;
    }

    @Nullable
    public Point3 getComponentTarget() {
        return componentTarget;
    }

    /** All authored placement targets; the first entry is {@link #getComponentTarget()}. */
    public List<Point3> getComponentTargets() {
        return componentTargets;
    }

    public List<Point3> getConnectionPath() {
        return connectionPath;
    }

    /** Every authored pin mapping for this step, in its seeded order. */
    public List<WireRoute> getWireRoutes() {
        return wireRoutes;
    }

    public float getMarkerRadius() {
        return markerRadius;
    }

    public float getRouteWidthPixels() {
        return routeWidthPixels;
    }

    public float getGhostScaleX() {
        return ghostScaleX;
    }

    public float getGhostScaleY() {
        return ghostScaleY;
    }

    public float getGhostScaleZ() {
        return ghostScaleZ;
    }

    public float getTargetRotationX() {
        return targetRotationX;
    }

    public float getTargetRotationY() {
        return targetRotationY;
    }

    public float getTargetRotationZ() {
        return targetRotationZ;
    }

    public float getComponentTargetRotationZ(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= componentTargets.size()) {
            throw new IndexOutOfBoundsException("Unknown component target " + targetIndex);
        }
        return targetIndex == 0 || additionalTargetRotationsZ.isEmpty()
                ? targetRotationZ : additionalTargetRotationsZ.get(targetIndex - 1);
    }

    public static final class Builder {
        private final String componentId;
        private final EnumSet<OverlayType> overlayTypes = EnumSet.noneOf(OverlayType.class);
        @Nullable private float[] openGlModelViewMatrix;
        private CoordinateSpace coordinateSpace = CoordinateSpace.NORMALIZED_COMPONENT;
        private float referencePlaneWidthUnits = 2f;
        private float referencePlaneHeightUnits = 2f;
        private GhostStyle ghostStyle = GhostStyle.AUTO;
        private TargetMarker targetMarker = TargetMarker.NONE;
        @Nullable private Point3 sourcePin;
        @Nullable private Point3 targetPin;
        @Nullable private Point3 componentTarget;
        private final List<Point3> additionalComponentTargets = new ArrayList<>();
        private final List<Point3> connectionPath = new ArrayList<>();
        private final List<WireRoute> wireRoutes = new ArrayList<>();
        private float markerRadius = Float.NaN;
        private float routeWidthPixels = 4f;
        private float ghostScaleX = 1f;
        private float ghostScaleY = 1f;
        private float ghostScaleZ = 1f;
        private float targetRotationX;
        private float targetRotationY;
        private float targetRotationZ;
        private final List<Float> additionalTargetRotationsZ = new ArrayList<>();

        private Builder(String componentId, List<OverlayType> overlayTypes) {
            this.componentId = componentId == null ? "" : componentId;
            if (overlayTypes != null) {
                this.overlayTypes.addAll(overlayTypes);
            }
            if (this.overlayTypes.isEmpty()) {
                this.overlayTypes.add(OverlayType.LABEL);
            }
        }

        public Builder pose(@Nullable float[] openGlModelViewMatrix) {
            if (openGlModelViewMatrix != null && openGlModelViewMatrix.length != 16) {
                throw new IllegalArgumentException("OpenGL model-view matrix must contain 16 values");
            }
            if (openGlModelViewMatrix != null) {
                for (float value : openGlModelViewMatrix) {
                    if (!Float.isFinite(value)) {
                        throw new IllegalArgumentException("OpenGL model-view matrix must be finite");
                    }
                }
            }
            this.openGlModelViewMatrix = openGlModelViewMatrix == null
                    ? null : openGlModelViewMatrix.clone();
            return this;
        }

        public Builder coordinateSpace(CoordinateSpace coordinateSpace) {
            this.coordinateSpace = Objects.requireNonNull(coordinateSpace, "coordinateSpace");
            return this;
        }

        /**
         * Defines the physical reference plane behind normalized guidance. A normalized point
         * (-1,-1) is the lower-left plane corner and (+1,+1) is the upper-right corner. This
         * keeps authored targets registered to the same dimensions used by solvePnP.
         */
        public Builder referencePlaneSize(float widthUnits, float heightUnits) {
            if (!(widthUnits > 0f) || !(heightUnits > 0f)
                    || !Float.isFinite(widthUnits) || !Float.isFinite(heightUnits)) {
                throw new IllegalArgumentException(
                        "Reference-plane dimensions must be finite and positive");
            }
            referencePlaneWidthUnits = widthUnits;
            referencePlaneHeightUnits = heightUnits;
            return this;
        }

        public Builder ghostStyle(GhostStyle ghostStyle) {
            this.ghostStyle = Objects.requireNonNull(ghostStyle, "ghostStyle");
            return this;
        }

        public Builder targetMarker(TargetMarker targetMarker) {
            this.targetMarker = Objects.requireNonNull(targetMarker, "targetMarker");
            return this;
        }

        public Builder sourcePin(@Nullable Point3 sourcePin) {
            this.sourcePin = sourcePin;
            return this;
        }

        public Builder targetPin(@Nullable Point3 targetPin) {
            this.targetPin = targetPin;
            return this;
        }

        /** Target origin for a component ghost; independent from a pin-to-pin route target. */
        public Builder componentTarget(@Nullable Point3 componentTarget) {
            this.componentTarget = componentTarget;
            return this;
        }

        /** Additional identical-part targets, such as the opposite motor or wheel. */
        public Builder additionalComponentTargets(Point3... targets) {
            additionalComponentTargets.clear();
            if (targets != null) {
                for (Point3 target : targets) {
                    additionalComponentTargets.add(
                            Objects.requireNonNull(target, "componentTarget"));
                }
            }
            return this;
        }

        /**
         * Sets intermediate route points. Source and target pins are automatically prepended and
         * appended when present, so this method normally receives only authored bend points.
         */
        public Builder connectionWaypoints(Point3... waypoints) {
            connectionPath.clear();
            if (waypoints != null) {
                for (Point3 point : waypoints) {
                    connectionPath.add(Objects.requireNonNull(point, "waypoint"));
                }
            }
            return this;
        }

        /** Replaces the complete set of independently rendered, metadata-bearing wire routes. */
        public Builder wireRoutes(WireRoute... routes) {
            wireRoutes.clear();
            if (routes != null) {
                for (WireRoute route : routes) {
                    wireRoutes.add(Objects.requireNonNull(route, "wireRoute"));
                }
            }
            return this;
        }

        public Builder markerRadius(float radiusInCoordinateUnits) {
            if (!(radiusInCoordinateUnits > 0f) || !Float.isFinite(radiusInCoordinateUnits)) {
                throw new IllegalArgumentException("Marker radius must be finite and positive");
            }
            markerRadius = radiusInCoordinateUnits;
            return this;
        }

        public Builder routeWidthPixels(float widthPixels) {
            if (!(widthPixels > 0f) || !Float.isFinite(widthPixels)) {
                throw new IllegalArgumentException("Route width must be finite and positive");
            }
            routeWidthPixels = widthPixels;
            return this;
        }

        /** Scale applied to the selected normalized OBJ mesh in the chosen coordinate space. */
        public Builder ghostScale(float x, float y, float z) {
            if (!(x > 0f) || !(y > 0f) || !(z > 0f)
                    || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                throw new IllegalArgumentException("Ghost scales must be finite and positive");
            }
            ghostScaleX = x;
            ghostScaleY = y;
            ghostScaleZ = z;
            return this;
        }

        /** Euler rotation of the component ghost at its authored target, applied Z then Y then X. */
        public Builder componentTargetRotation(float xDegrees, float yDegrees, float zDegrees) {
            if (!Float.isFinite(xDegrees) || !Float.isFinite(yDegrees)
                    || !Float.isFinite(zDegrees)) {
                throw new IllegalArgumentException("Component target rotation must be finite");
            }
            targetRotationX = xDegrees;
            targetRotationY = yDegrees;
            targetRotationZ = zDegrees;
            return this;
        }

        /** Per-additional-target Z rotations; X/Y continue to use the primary target values. */
        public Builder additionalComponentTargetRotationsZ(float... zDegrees) {
            additionalTargetRotationsZ.clear();
            if (zDegrees != null) {
                for (float value : zDegrees) {
                    if (!Float.isFinite(value)) {
                        throw new IllegalArgumentException(
                                "Component target rotation must be finite");
                    }
                    additionalTargetRotationsZ.add(value);
                }
            }
            return this;
        }

        public ArGuidance build() {
            if (!additionalTargetRotationsZ.isEmpty()
                    && additionalTargetRotationsZ.size()
                    != additionalComponentTargets.size()) {
                throw new IllegalStateException(
                        "Each additional component target needs one Z rotation");
            }
            return new ArGuidance(this);
        }
    }
}
