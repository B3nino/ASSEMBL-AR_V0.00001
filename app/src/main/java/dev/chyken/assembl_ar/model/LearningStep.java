package dev.chyken.assembl_ar.model;

import java.util.Objects;

public final class LearningStep {
    private final String id;
    private final String moduleId;
    private final int order;
    private final String title;
    private final LearningContentType contentType;
    private final String content;
    private final String codeSnippet;
    private final String warning;
    private final String dependency;
    private final ManualReference manualReference;

    public LearningStep(
            String id,
            String moduleId,
            int order,
            String title,
            LearningContentType contentType,
            String content,
            String codeSnippet,
            String warning,
            String dependency,
            ManualReference manualReference) {
        if (order < 1) {
            throw new IllegalArgumentException("order must be positive");
        }
        this.id = Objects.requireNonNull(id, "id");
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId");
        this.order = order;
        this.title = Objects.requireNonNull(title, "title");
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        this.content = Objects.requireNonNull(content, "content");
        this.codeSnippet = Objects.requireNonNull(codeSnippet, "codeSnippet");
        this.warning = Objects.requireNonNull(warning, "warning");
        this.dependency = Objects.requireNonNull(dependency, "dependency");
        this.manualReference = Objects.requireNonNull(manualReference, "manualReference");
    }

    public String getId() {
        return id;
    }

    public String getModuleId() {
        return moduleId;
    }

    public int getOrder() {
        return order;
    }

    public String getTitle() {
        return title;
    }

    public LearningContentType getContentType() {
        return contentType;
    }

    public String getContent() {
        return content;
    }

    public String getCodeSnippet() {
        return codeSnippet;
    }

    public String getWarning() {
        return warning;
    }

    public String getDependency() {
        return dependency;
    }

    public ManualReference getManualReference() {
        return manualReference;
    }
}
