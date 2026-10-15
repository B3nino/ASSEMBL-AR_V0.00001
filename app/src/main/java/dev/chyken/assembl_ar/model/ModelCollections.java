package dev.chyken.assembl_ar.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ModelCollections {
    private ModelCollections() {
    }

    static <T> List<T> immutableCopy(List<T> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
