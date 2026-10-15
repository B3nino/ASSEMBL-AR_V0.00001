package dev.chyken.assembl_ar.model;

import java.util.Objects;

public final class RequiredComponent {
    private final String componentId;
    private final int quantity;
    private final String notes;

    public RequiredComponent(String componentId, int quantity, String notes) {
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        this.componentId = Objects.requireNonNull(componentId, "componentId");
        this.quantity = quantity;
        this.notes = Objects.requireNonNull(notes, "notes");
    }

    public String getComponentId() {
        return componentId;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getNotes() {
        return notes;
    }
}
