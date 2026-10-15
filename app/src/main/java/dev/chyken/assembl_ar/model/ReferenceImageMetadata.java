package dev.chyken.assembl_ar.model;

import java.util.Objects;

public final class ReferenceImageMetadata {
    private final String id;
    private final String registrationId;
    private final String componentId;
    private final String imagePath;
    private final String angleLabel;
    private final double azimuthDegrees;
    private final double elevationDegrees;
    private final double distanceMillimeters;
    private final String lightingCondition;
    private final boolean synthetic;
    private final int pixelWidth;
    private final int pixelHeight;
    private final String sha256;
    private final long capturedAtEpochMillis;

    public ReferenceImageMetadata(
            String id,
            String registrationId,
            String componentId,
            String imagePath,
            String angleLabel,
            double azimuthDegrees,
            double elevationDegrees,
            double distanceMillimeters,
            String lightingCondition,
            boolean synthetic,
            int pixelWidth,
            int pixelHeight,
            String sha256,
            long capturedAtEpochMillis) {
        this.id = Objects.requireNonNull(id, "id");
        this.registrationId = Objects.requireNonNull(registrationId, "registrationId");
        this.componentId = Objects.requireNonNull(componentId, "componentId");
        this.imagePath = Objects.requireNonNull(imagePath, "imagePath");
        this.angleLabel = Objects.requireNonNull(angleLabel, "angleLabel");
        this.azimuthDegrees = azimuthDegrees;
        this.elevationDegrees = elevationDegrees;
        this.distanceMillimeters = distanceMillimeters;
        this.lightingCondition = Objects.requireNonNull(lightingCondition, "lightingCondition");
        this.synthetic = synthetic;
        this.pixelWidth = pixelWidth;
        this.pixelHeight = pixelHeight;
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        this.capturedAtEpochMillis = capturedAtEpochMillis;
    }

    public String getId() { return id; }
    public String getRegistrationId() { return registrationId; }
    public String getComponentId() { return componentId; }
    public String getImagePath() { return imagePath; }
    public String getAngleLabel() { return angleLabel; }
    public double getAzimuthDegrees() { return azimuthDegrees; }
    public double getElevationDegrees() { return elevationDegrees; }
    public double getDistanceMillimeters() { return distanceMillimeters; }
    public String getLightingCondition() { return lightingCondition; }
    public boolean isSynthetic() { return synthetic; }
    public int getPixelWidth() { return pixelWidth; }
    public int getPixelHeight() { return pixelHeight; }
    public String getSha256() { return sha256; }
    public long getCapturedAtEpochMillis() { return capturedAtEpochMillis; }
}
