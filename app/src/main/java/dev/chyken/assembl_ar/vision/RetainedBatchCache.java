package dev.chyken.assembl_ar.vision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bounded ownership of immutable reference batches, accessed on the recognition thread. */
final class RetainedBatchCache<T> implements AutoCloseable {
    private final int capacity;
    private final Consumer<T> release;
    private final LinkedHashMap<List<?>, T> entries = new LinkedHashMap<>(8, .75f, true);

    RetainedBatchCache(int capacity, Consumer<T> release) {
        if (capacity < 1) throw new IllegalArgumentException("Positive cache capacity required");
        this.capacity = capacity;
        this.release = release;
    }

    T get(List<?> references, Supplier<T> prepare) {
        T batch = entries.get(references);
        if (batch != null) return batch;
        List<?> key = Collections.unmodifiableList(new ArrayList<>(references));
        batch = prepare.get();
        entries.put(key, batch);
        if (entries.size() > capacity) {
            List<?> oldest = entries.keySet().iterator().next();
            release.accept(entries.remove(oldest));
        }
        return batch;
    }

    @Override public void close() {
        for (T batch : entries.values()) release.accept(batch);
        entries.clear();
    }
}
