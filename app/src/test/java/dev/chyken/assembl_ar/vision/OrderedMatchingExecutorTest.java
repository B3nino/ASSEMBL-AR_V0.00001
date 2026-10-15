package dev.chyken.assembl_ar.vision;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class OrderedMatchingExecutorTest {
    @Test
    public void completionOrderDoesNotChangeCatalogOrder() throws Exception {
        CountDownLatch laterFinished = new CountDownLatch(1);
        try (OrderedMatchingExecutor matcher = new OrderedMatchingExecutor(8, 1)) {
            List<String> results = matcher.compute(Arrays.asList(
                    () -> {
                        assertTrue(laterFinished.await(2, TimeUnit.SECONDS));
                        return "first reference";
                    },
                    () -> {
                        laterFinished.countDown();
                        return "second reference";
                    }));
            assertEquals(Arrays.asList("first reference", "second reference"), results);
            assertEquals(4, matcher.workerCount());
        }
    }

    @Test
    public void failureWaitsUntilAllNativeReadersHaveFinished() throws Exception {
        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        AtomicBoolean readerFinished = new AtomicBoolean();
        AtomicBoolean callerFinished = new AtomicBoolean();
        AtomicReference<Throwable> observed = new AtomicReference<>();
        RuntimeException expected = new RuntimeException("native failure");
        try (OrderedMatchingExecutor matcher = new OrderedMatchingExecutor(2, 1)) {
            Thread caller = new Thread(() -> {
                try {
                    matcher.compute(Arrays.asList(
                            () -> { throw expected; },
                            () -> {
                                readerStarted.countDown();
                                assertTrue(releaseReader.await(2, TimeUnit.SECONDS));
                                readerFinished.set(true);
                                return "finished";
                            }));
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    callerFinished.set(true);
                }
            });
            caller.start();
            assertTrue(readerStarted.await(2, TimeUnit.SECONDS));
            assertFalse(callerFinished.get());
            releaseReader.countDown();
            caller.join(2_000);
            assertTrue(callerFinished.get());
            assertTrue(readerFinished.get());
            assertSame(expected, observed.get());
        } finally {
            releaseReader.countDown();
        }
    }

    @Test
    public void interruptDrainsReadersAndRestoresCallerInterrupt() throws Exception {
        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        AtomicBoolean readerFinished = new AtomicBoolean();
        AtomicBoolean callerInterrupted = new AtomicBoolean();
        AtomicReference<Throwable> observed = new AtomicReference<>();
        try (OrderedMatchingExecutor matcher = new OrderedMatchingExecutor(1, 1)) {
            Thread caller = new Thread(() -> {
                try {
                    matcher.compute(Arrays.asList(() -> {
                        readerStarted.countDown();
                        assertTrue(releaseReader.await(2, TimeUnit.SECONDS));
                        readerFinished.set(true);
                        return "finished";
                    }));
                } catch (Throwable failure) {
                    observed.set(failure);
                    callerInterrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            caller.start();
            assertTrue(readerStarted.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            assertFalse(readerFinished.get());
            releaseReader.countDown();
            caller.join(2_000);
            assertFalse(caller.isAlive());
            assertTrue(readerFinished.get());
            assertTrue(callerInterrupted.get());
            assertTrue(observed.get() instanceof IllegalStateException);
            assertEquals(1, matcher.workerCount());
        } finally {
            releaseReader.countDown();
        }
    }

    @Test
    public void nativeParallelPoolKeepsTheSingleConcatenatedDistanceCall() {
        try (OrderedMatchingExecutor matcher = new OrderedMatchingExecutor(8, 8)) {
            assertEquals(1, matcher.workerCount());
        }
        try (OrderedMatchingExecutor matcher = new OrderedMatchingExecutor(0, 1)) {
            assertEquals(1, matcher.workerCount());
        }
    }
}
