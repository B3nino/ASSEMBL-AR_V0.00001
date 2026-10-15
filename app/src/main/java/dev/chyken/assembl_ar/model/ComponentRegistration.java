package dev.chyken.assembl_ar.model;

import java.util.Objects;

public final class ComponentRegistration {
    private final String id;
    private final String componentId;
    private final String administratorId;
    private final String displayName;
    private final String description;
    private final String purpose;
    private final String assemblyUsage;
    private final String manualReference;
    private final boolean active;
    private final long createdAtEpochMillis;
    private final long updatedAtEpochMillis;

    public ComponentRegistration(
            String id,
            String componentId,
            String administratorId,
            String displayName,
            String description,
            String purpose,
            String assemblyUsage,
            String manualReference,
            boolean active,
            long createdAtEpochMillis,
            long updatedAtEpochMillis) {
        this.id = Objects.requireNonNull(id, "id");
        this.componentId = Objects.requireNonNull(componentId, "componentId");
        this.administratorId = Objects.requireNonNull(administratorId, "administratorId");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.description = Objects.requireNonNull(description, "description");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.assemblyUsage = Objects.requireNonNull(assemblyUsage, "assemblyUsage");
        this.manualReference = Objects.requireNonNull(manualReference, "manualReference");
        this.active = active;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.updatedAtEpochMillis = updatedAtEpochMillis;
    }

    public String getId() {
        return id;
    }

    public String getComponentId() {
        return componentId;
    }

    public String getAdministratorId() {
        return administratorId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getAssemblyUsage() {
        return assemblyUsage;
    }

    public String getManualReference() {
        return manualReference;
    }

    public boolean isActive() {
        return active;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    public long getUpdatedAtEpochMillis() {
        return updatedAtEpochMillis;
    }
}
