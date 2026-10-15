package dev.chyken.assembl_ar.vision;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class RetainedBatchCacheTest {
    @Test public void orderIsPartOfTheKeyAndCallerMutationDoesNotChangeRetainedRows() {
        AtomicInteger prepared = new AtomicInteger();
        Object first = new Object(), second = new Object();
        List<Object> key = new ArrayList<>(Arrays.asList(first, second));
        try (RetainedBatchCache<Integer> cache = new RetainedBatchCache<>(8, value -> { })) {
            Integer original = cache.get(key, prepared::incrementAndGet);
            Collections.reverse(key);
            Integer reversed = cache.get(key, prepared::incrementAndGet);
            assertEquals(Integer.valueOf(2), reversed);
            assertSame(original, cache.get(Arrays.asList(first, second), prepared::incrementAndGet));
            assertEquals(2, prepared.get());
        }
    }

    @Test public void fourPendingReadersAreRetainedAcrossAnEightEntryEvictionWindow() {
        List<Integer> released = new ArrayList<>();
        RetainedBatchCache<Integer> cache = new RetainedBatchCache<>(8, released::add);
        for (int index = 0; index < 8; index++) {
            int value = index;
            cache.get(Collections.singletonList(index), () -> value);
        }
        // A recognition pass prepares at most four native readers before draining them.
        for (int index = 8; index < 12; index++) {
            int value = index;
            cache.get(Collections.singletonList(index), () -> value);
        }
        assertEquals(Arrays.asList(0, 1, 2, 3), released);
        for (int index = 8; index < 12; index++) assertFalse(released.contains(index));
        cache.close();
        assertEquals(12, released.size());
        for (int index = 0; index < 12; index++) assertTrue(released.contains(index));
        cache.close();
        assertEquals("Each native batch is released once", 12, released.size());
    }

    @Test public void preparationFailureRetainsExistingBatchesAndOwnsNoFailedValue() {
        List<Integer> released = new ArrayList<>();
        try (RetainedBatchCache<Integer> cache = new RetainedBatchCache<>(1, released::add)) {
            Integer original = cache.get(Collections.singletonList("original"), () -> 1);
            try {
                cache.get(Collections.singletonList("broken"), () -> {
                    throw new IllegalStateException("native preparation failed");
                });
            } catch (IllegalStateException expected) {
                assertEquals("native preparation failed", expected.getMessage());
            }
            assertTrue(released.isEmpty());
            assertSame(original, cache.get(Collections.singletonList("original"), () -> 2));
        }
        assertEquals(Collections.singletonList(1), released);
    }
}
