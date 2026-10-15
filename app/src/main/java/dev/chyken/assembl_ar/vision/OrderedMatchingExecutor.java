package dev.chyken.assembl_ar.vision;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs independent native distance batches, then merges in catalog order. */
final class OrderedMatchingExecutor implements AutoCloseable {
    private final ExecutorService workers;
    private final int workerCount;

    OrderedMatchingExecutor(int availableProcessors, int nativeThreads) {
        // The native pool already divides a concatenated query into parallel row ranges.
        // Extra callers then compete with that pool rather than adding useful concurrency.
        workerCount = nativeThreads > 1 ? 1 : Math.max(1, Math.min(4, availableProcessors));
        AtomicInteger sequence = new AtomicInteger();
        workers = Executors.newFixedThreadPool(workerCount, task -> {
            Thread thread = new Thread(task, "AssemblArMatch-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    int workerCount() { return workerCount; }

    <T> List<T> compute(List<? extends Callable<T>> batches) {
        List<Future<T>> pending = new ArrayList<>(batches.size());
        for (Callable<T> batch : batches) pending.add(workers.submit(batch));
        List<T> results = new ArrayList<>(batches.size());
        Throwable failure = null;
        boolean interrupted = false;
        // A native call may ignore cancellation. Drain every reader before the caller releases
        // its frame/reference Mats, including when another batch failed or the caller stopped.
        for (Future<T> future : pending) {
            while (true) {
                try {
                    results.add(future.get());
                    break;
                } catch (InterruptedException stopped) {
                    interrupted = true;
                } catch (ExecutionException failed) {
                    if (failure == null) failure = failed.getCause();
                    break;
                }
            }
        }
        // Future.get() may return an already completed result without checking the
        // caller's interrupt flag. Honor that interruption after draining all readers too.
        interrupted |= Thread.currentThread().isInterrupted();
        if (interrupted) Thread.currentThread().interrupt();
        if (failure instanceof Error) throw (Error) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure != null) throw new IllegalStateException("Descriptor matching failed", failure);
        if (interrupted) throw new IllegalStateException("Descriptor matching interrupted");
        return results;
    }

    @Override
    public void close() {
        workers.shutdown();
    }
}
