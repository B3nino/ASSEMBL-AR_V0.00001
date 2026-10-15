package dev.chyken.assembl_ar.model;

import java.util.Objects;

public final class PinConnection {
    private final int order;
    private final String sourceComponentId;
    private final String sourcePin;
    private final String targetComponentId;
    private final String targetPin;
    private final String wireColor;
    private final String signal;
    private final String notes;

    public PinConnection(
            int order,
            String sourceComponentId,
            String sourcePin,
            String targetComponentId,
            String targetPin,
            String wireColor,
            String signal,
            String notes) {
        if (order < 1) {
            throw new IllegalArgumentException("order must be positive");
        }
        this.order = order;
        this.sourceComponentId = Objects.requireNonNull(sourceComponentId, "sourceComponentId");
        this.sourcePin = Objects.requireNonNull(sourcePin, "sourcePin");
        this.targetComponentId = Objects.requireNonNull(targetComponentId, "targetComponentId");
        this.targetPin = Objects.requireNonNull(targetPin, "targetPin");
        this.wireColor = Objects.requireNonNull(wireColor, "wireColor");
        this.signal = Objects.requireNonNull(signal, "signal");
        this.notes = Objects.requireNonNull(notes, "notes");
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
}
