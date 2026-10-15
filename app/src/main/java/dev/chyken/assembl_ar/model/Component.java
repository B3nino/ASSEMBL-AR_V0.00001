package dev.chyken.assembl_ar.model;

import java.util.List;
import java.util.Objects;

public final class Component {
    private final String id;
    private final String name;
    private final ComponentCategory category;
    private final int kitQuantity;
    private final String description;
    private final String purpose;
    private final String assemblyUsage;
    private final String recognitionNotes;
    private final ManualReference manualReference;
    private final List<String> referenceImagePaths;

    public Component(
            String id,
            String name,
            ComponentCategory category,
            int kitQuantity,
            String description,
            String purpose,
            String assemblyUsage,
            String recognitionNotes,
            ManualReference manualReference,
            List<String> referenceImagePaths) {
        if (kitQuantity < 0) {
            throw new IllegalArgumentException("kitQuantity cannot be negative");
        }
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.category = Objects.requireNonNull(category, "category");
        this.kitQuantity = kitQuantity;
        this.description = Objects.requireNonNull(description, "description");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.assemblyUsage = Objects.requireNonNull(assemblyUsage, "assemblyUsage");
        this.recognitionNotes = Objects.requireNonNull(recognitionNotes, "recognitionNotes");
        this.manualReference = Objects.requireNonNull(manualReference, "manualReference");
        this.referenceImagePaths = ModelCollections.immutableCopy(referenceImagePaths);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ComponentCategory getCategory() {
        return category;
    }

    public int getKitQuantity() {
        return kitQuantity;
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

    public String getRecognitionNotes() {
        return recognitionNotes;
    }

    public ManualReference getManualReference() {
        return manualReference;
    }

    public List<String> getReferenceImagePaths() {
        return referenceImagePaths;
    }
}
