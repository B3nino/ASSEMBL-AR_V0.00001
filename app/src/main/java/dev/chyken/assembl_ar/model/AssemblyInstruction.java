package dev.chyken.assembl_ar.model;

import java.util.Objects;

public final class AssemblyInstruction {
    private final int order;
    private final InstructionType type;
    private final String text;
    private final int manualPage;
    private final String referenceImagePath;

    public AssemblyInstruction(
            int order,
            InstructionType type,
            String text,
            int manualPage,
            String referenceImagePath) {
        if (order < 1 || manualPage < 1) {
            throw new IllegalArgumentException("order and manualPage must be positive");
        }
        this.order = order;
        this.type = Objects.requireNonNull(type, "type");
        this.text = Objects.requireNonNull(text, "text");
        this.manualPage = manualPage;
        this.referenceImagePath = Objects.requireNonNull(referenceImagePath, "referenceImagePath");
    }

    public int getOrder() {
        return order;
    }

    public InstructionType getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public int getManualPage() {
        return manualPage;
    }

    public String getReferenceImagePath() {
        return referenceImagePath;
    }
}
