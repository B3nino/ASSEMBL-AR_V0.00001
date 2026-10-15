package dev.chyken.assembl_ar.model;

import java.util.List;

public final class SeedIntegrityReport {
    private final List<String> errors;

    public SeedIntegrityReport(List<String> errors) {
        this.errors = ModelCollections.immutableCopy(errors);
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    public List<String> getErrors() {
        return errors;
    }
}
