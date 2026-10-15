package dev.chyken.assembl_ar.model;

import java.util.List;
import java.util.Objects;

public final class ManualReference {
    private final String documentId;
    private final String documentTitle;
    private final int pageStart;
    private final int pageEnd;
    private final List<String> assetPaths;

    public ManualReference(
            String documentId,
            String documentTitle,
            int pageStart,
            int pageEnd,
            List<String> assetPaths) {
        if (pageStart < 1 || pageEnd < pageStart) {
            throw new IllegalArgumentException("Invalid manual page range");
        }
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.documentTitle = Objects.requireNonNull(documentTitle, "documentTitle");
        this.pageStart = pageStart;
        this.pageEnd = pageEnd;
        this.assetPaths = ModelCollections.immutableCopy(assetPaths);
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getDocumentTitle() {
        return documentTitle;
    }

    public int getPageStart() {
        return pageStart;
    }

    public int getPageEnd() {
        return pageEnd;
    }

    public List<String> getAssetPaths() {
        return assetPaths;
    }
}
