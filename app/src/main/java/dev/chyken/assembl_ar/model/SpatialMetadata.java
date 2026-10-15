package dev.chyken.assembl_ar.model;

import java.util.Arrays;
import java.util.Objects;

public final class SpatialMetadata {
    private final String componentId;
    private final double widthMillimeters;
    private final double heightMillimeters;
    private final double depthMillimeters;
    private final String coordinateFrame;
    private final String anchorPointsJson;
    private final String orientationRulesJson;
    private final byte[] objectPointsBlob;
    private final String modelAssetPath;
    private final long updatedAtEpochMillis;

    public SpatialMetadata(
            String componentId,
            double widthMillimeters,
            double heightMillimeters,
            double depthMillimeters,
            String coordinateFrame,
            String anchorPointsJson,
            String orientationRulesJson,
            byte[] objectPointsBlob,
            String modelAssetPath,
            long updatedAtEpochMillis) {
        this.componentId = Objects.requireNonNull(componentId, "componentId");
        this.widthMillimeters = widthMillimeters;
        this.heightMillimeters = heightMillimeters;
        this.depthMillimeters = depthMillimeters;
        this.coordinateFrame = Objects.requireNonNull(coordinateFrame, "coordinateFrame");
        this.anchorPointsJson = Objects.requireNonNull(anchorPointsJson, "anchorPointsJson");
        this.orientationRulesJson = Objects.requireNonNull(orientationRulesJson, "orientationRulesJson");
        this.objectPointsBlob = objectPointsBlob == null
                ? new byte[0]
                : Arrays.copyOf(objectPointsBlob, objectPointsBlob.length);
        this.modelAssetPath = Objects.requireNonNull(modelAssetPath, "modelAssetPath");
        this.updatedAtEpochMillis = updatedAtEpochMillis;
    }

    public String getComponentId() { return componentId; }
    public double getWidthMillimeters() { return widthMillimeters; }
    public double getHeightMillimeters() { return heightMillimeters; }
    public double getDepthMillimeters() { return depthMillimeters; }
    public String getCoordinateFrame() { return coordinateFrame; }
    public String getAnchorPointsJson() { return anchorPointsJson; }
    public String getOrientationRulesJson() { return orientationRulesJson; }
    public byte[] getObjectPointsBlob() { return Arrays.copyOf(objectPointsBlob, objectPointsBlob.length); }
    public String getModelAssetPath() { return modelAssetPath; }
    public long getUpdatedAtEpochMillis() { return updatedAtEpochMillis; }
}
