package dev.chyken.assembl_ar.vision;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Looper;
import org.opencv.android.OpenCVLoader;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.data.AssemblArRepository;

/** Process-owned, offline reference preparation. Activities never own or release the hot cache. */
public final class VisionPreloadManager {
    private static volatile VisionPreloadManager instance;
    private static final java.util.concurrent.atomic.AtomicBoolean nativeBudgetConfigured=new java.util.concurrent.atomic.AtomicBoolean();
    private static final Map<AssetManager, VisionPreloadManager> ACTIVE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Context context;
    private final File directory;
    private final VisionConfig config = VisionConfig.defaults();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            task.run();
        }, "assemblar-reference-preload");
        thread.setDaemon(true);
        return thread;
    });
    // All registered references retain an availability entry; native descriptors are bounded.
    private final ConcurrentHashMap<String, Boolean> prepared = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HotReference> hot = new ConcurrentHashMap<>();
    private final long maximumBytes;
    private long residentBytes, accessSequence;
    private CompletableFuture<List<ComponentReferenceSpec>> completion;
    private CompletableFuture<List<ComponentReferenceSpec>> catalog = new CompletableFuture<>();
    private volatile int completed, total;
    private volatile Throwable failure;

    private VisionPreloadManager(Context source) {
        context = source.getApplicationContext();
        directory = new File(context.getNoBackupFilesDir(), "orb_reference_cache_v3");
        android.app.ActivityManager activity=(android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo memory=new android.app.ActivityManager.MemoryInfo();
        if(activity!=null)activity.getMemoryInfo(memory);
        boolean constrained=activity!=null&&(activity.isLowRamDevice()||memory.totalMem<=4L*1024*1024*1024);
        maximumBytes = Math.max(8L << 20, Math.min((constrained?16L:32L) << 20,
                Runtime.getRuntime().maxMemory() / 12));
        ACTIVE.put(context.getAssets(), this);
        context.registerComponentCallbacks(new android.content.ComponentCallbacks2() {
            @Override public void onConfigurationChanged(android.content.res.Configuration c) { }
            @Override public void onLowMemory() { trimMemory(); }
            @Override public void onTrimMemory(int level) {
                // UI_HIDDEN (20) is an ordinary app switch, not a low-memory
                // signal. Keep the warm cache when opening Recents/control center.
                if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL
                        || level >= TRIM_MEMORY_BACKGROUND) trimMemory();
            }
        });
    }

    public static VisionPreloadManager get(Context context) {
        VisionPreloadManager ready = instance;
        if (ready == null) synchronized (VisionPreloadManager.class) {
            if ((ready = instance) == null) instance = ready = new VisionPreloadManager(context);
        }
        return ready;
    }

    /** Idempotent startup hook; no assets, SQLite or OpenCV work occurs on the calling thread. */
    public synchronized CompletableFuture<List<ComponentReferenceSpec>> start() {
        if (completion != null && !completion.isCompletedExceptionally()) return completion;
        failure = null;
        if (catalog.isCompletedExceptionally()) catalog = new CompletableFuture<>();
        completion = new CompletableFuture<>();
        final CompletableFuture<List<ComponentReferenceSpec>> attempt = completion;
        final CompletableFuture<List<ComponentReferenceSpec>> indexed = catalog;
        executor.execute(() -> {
            try {
                if (!OpenCVLoader.initLocal()) throw new IOException("OpenCV runtime unavailable");
                if(nativeBudgetConfigured.compareAndSet(false,true))org.opencv.core.Core.setNumThreads(2);
                List<ComponentReferenceSpec> specs = Collections.unmodifiableList(
                        VisionReferenceFactory.build(AssemblArRepository.getInstance(context), context.getAssets()));
                total = specs.size(); completed = 0;
                KeypointInventoryManager.INSTANCE.register(specs);
                ReferenceCacheIndex index = new ReferenceCacheIndex(directory,
                        context.getApplicationInfo().sourceDir, ReferenceDatabase.cacheRevision());
                ReferenceCacheIndex.install(context.getAssets(), index);
                indexed.complete(specs);
                ReferenceDatabase.preloadLibrary(context.getAssets(), specs, config, directory, index,
                        (done, count) -> { completed = done; total = count; });
                GlobalComponentRegistry.INSTANCE.publish();
                attempt.complete(specs);
            } catch (Throwable problem) {
                failure = problem;
                indexed.completeExceptionally(problem);
                attempt.completeExceptionally(problem);
            }
        });
        return completion;
    }

    public CompletableFuture<List<ComponentReferenceSpec>> catalog() { start(); return catalog; }
    public int completedCount() { return completed; }
    public int totalCount() { return total; }
    public Throwable failure() { return failure; }
    public synchronized boolean isReady() {
        return completion != null && completion.isDone() && !completion.isCompletedExceptionally();
    }
    public int preparedReferenceCount() { return prepared.size(); }
    public synchronized long residentBytes() { return residentBytes; }

    /** Call on the existing vision loader. Returned native matrices belong to this bank only. */
    public ReferenceDatabase loadBank(List<ComponentReferenceSpec> specs, BooleanSupplier active)
            throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Reference banks must be opened on a background loader");
        ACTIVE.put(context.getAssets(), this);
        return ReferenceDatabase.load(context.getAssets(), specs, config, directory, active);
    }

    static VisionPreloadManager active(AssetManager assets) { return ACTIVE.get(assets); }

    synchronized ReferenceDatabase.PreparedReference acquire(String key) {
        HotReference entry = hot.get(key);
        if (entry == null) return null;
        entry.lastAccess = ++accessSequence;
        return entry.reference.copy();
    }

    synchronized void retain(String key, ReferenceDatabase.PreparedReference reference) {
        prepared.put(key, Boolean.TRUE);
        if (hot.containsKey(key)) return;
        long bytes = reference.estimatedBytes();
        if (bytes > maximumBytes) return;
        while (residentBytes + bytes > maximumBytes && !hot.isEmpty()) evictOldest();
        hot.put(key, new HotReference(reference.copy(), bytes, ++accessSequence));
        residentBytes += bytes;
    }

    /** Dropping cached owners never invalidates copies already used by recognition. */
    public synchronized void trimMemory() {
        for (HotReference entry : hot.values()) entry.reference.release();
        hot.clear(); residentBytes = 0;
    }
    private void evictOldest() {
        String oldest = null; long access = Long.MAX_VALUE;
        for (Map.Entry<String, HotReference> entry : hot.entrySet())
            if (entry.getValue().lastAccess < access) {
                access = entry.getValue().lastAccess; oldest = entry.getKey();
            }
        HotReference removed = oldest == null ? null : hot.remove(oldest);
        if (removed != null) { residentBytes -= removed.bytes; removed.reference.release(); }
    }
    private static final class HotReference {
        final ReferenceDatabase.PreparedReference reference;
        final long bytes;
        long lastAccess;
        HotReference(ReferenceDatabase.PreparedReference reference, long bytes, long lastAccess) {
            this.reference = reference; this.bytes = bytes; this.lastAccess = lastAccess;
        }
    }
}
