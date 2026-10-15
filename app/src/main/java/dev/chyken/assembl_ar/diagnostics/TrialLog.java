package dev.chyken.assembl_ar.diagnostics;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.chyken.assembl_ar.vision.MotorAssemblyObservation;
import dev.chyken.assembl_ar.vision.RecognitionResult;

/**
 * An on-device CSV record of one assembly session for reproducing field reports: build and device
 * identity, every analysed frame's capture/processing timing and recognized identities, and every
 * phase transition, verification outcome, gate reset reason and banner the learner saw. Files stay
 * in the app's private storage until the learner shares one; nothing is sent automatically.
 */
public final class TrialLog implements AutoCloseable {
    private static final String TAG = "AssemblyTrial";
    public static final String DIRECTORY = "trials";
    static final String HEADER = "wall_time,kind,frame_ns,completed_ns,evidence_ns,processing_ms,"
            + "request_tag,step,component,validation,method,confidence,inliers,board_detected,"
            + "board_tracked,anchor_tracked,motor_quality,motor_left,motor_right,detail";
    static final int COLUMNS = HEADER.split(",").length;
    private static final long MAXIMUM_BYTES = 8L * 1024 * 1024;
    private static final int RETAINED_FILES = 12;

    private final ExecutorService writerThread = Executors.newSingleThreadExecutor();
    @Nullable private volatile File file;
    @Nullable private Writer writer;
    private long bytes;
    private boolean limitReported;

    private TrialLog() { }

    /**
     * Starts a new log. All storage work (creating the folder, pruning old logs, opening the file)
     * happens on the log's own thread so a slow volume can never stall the assembly screen.
     * Logging is diagnostic only: if storage is unavailable, rows are silently dropped.
     */
    public static TrialLog start(Context context, String session) {
        Context application = context.getApplicationContext();
        String created = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        return start(() -> directory(application), session, BuildStamp.details(), created);
    }

    /** Filesystem entry point for deterministic storage tests without an Android device. */
    static TrialLog start(File directory, String session, String buildDetails, String created) {
        return start(() -> directory, session, buildDetails, created);
    }

    private static TrialLog start(Supplier<File> location, String session, String buildDetails,
                                  String created) {
        TrialLog log = new TrialLog();
        log.enqueue(() -> {
            File directory = location.get();
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Cannot create " + directory);
            }
            prune(directory);
            // Atomic creation also protects sessions started within the same millisecond.
            File target = File.createTempFile("trial-" + created + "-", ".csv", directory);
            log.writer = new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8);
            log.file = target;
            for (String line : buildDetails.split("\\R")) log.writeRaw("# " + line);
            log.writeRaw("# Session: " + session);
            log.writeRaw(HEADER);
        });
        return log;
    }

    public static File directory(Context context) {
        File external = context.getExternalFilesDir(DIRECTORY);
        return external != null ? external : new File(context.getFilesDir(), DIRECTORY);
    }

    /** The log file once its writer thread has opened it; null before that or without storage. */
    @Nullable public File getFile() { return file; }

    /**
     * Exports a stable copy after every row already submitted to this log has been written. The
     * callback runs on the writer thread and receives null if storage or the log is unavailable.
     * Later camera frames continue in the session file and cannot alter the exported evidence.
     * The twelve most recent exports are retained; older shared URIs expire when pruned.
     */
    public void shareSnapshot(Consumer<File> ready) {
        if (!enqueue(() -> {
            File snapshot = null;
            try {
                if (writer != null) writer.flush();
                File source = file;
                if (source != null && source.isFile()) {
                    File shared = new File(source.getParentFile(), "shared");
                    if (!shared.isDirectory() && !shared.mkdirs()) {
                        throw new IOException("Cannot create " + shared);
                    }
                    prune(shared);
                    snapshot = File.createTempFile(source.getName().replace(".csv", "") + "-",
                            ".csv", shared);
                    Files.copy(source.toPath(), snapshot.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException failure) {
                if (snapshot != null) {
                    if (!snapshot.delete()) Log.w(TAG, "Could not remove incomplete export " + snapshot);
                    snapshot = null;
                }
                Log.w(TAG, "Trial log export failed", failure);
            }
            ready.accept(snapshot);
        })) ready.accept(null);
    }

    /** A mode change, learner action, banner, gate reset or verification outcome. */
    public void event(String kind, String detail) {
        String line = eventRow(kind, detail);
        enqueue(() -> writeRow(line));
    }

    static String eventRow(String kind, String detail) {
        String[] fields = new String[COLUMNS];
        fields[0] = now();
        fields[1] = kind;
        fields[COLUMNS - 1] = detail;
        return row(fields);
    }

    /** One analysed frame with the camera and processing times needed for latency analysis. */
    public void frame(RecognitionResult result, long completedNanos, long evidenceNanos,
                      @Nullable Object requestTag, String detail) {
        RecognitionResult.BoardObservation board = result.getBoardObservation();
        MotorAssemblyObservation motors = result.getMotorAssembly();
        long processingNanos = completedNanos > result.getFrameTimestampNanos()
                ? completedNanos - result.getFrameTimestampNanos() : 0L;
        String line = row(now(), "frame",
                Long.toString(result.getFrameTimestampNanos()),
                completedNanos == Long.MIN_VALUE ? "" : Long.toString(completedNanos),
                Long.toString(evidenceNanos),
                String.format(Locale.US, "%.1f", processingNanos / 1_000_000.0),
                requestTag == null ? "" : requestTag.toString(),
                Integer.toString(result.getAssemblyStepNumber()),
                value(result.getComponentId()), result.getValidationState().name(),
                result.getDetectionMethod().name(),
                String.format(Locale.US, "%.3f", result.getConfidence()),
                Integer.toString(result.getInlierCount()),
                Boolean.toString(board != null && board.isDetected()),
                Boolean.toString(board != null && board.isTracked()),
                Boolean.toString(result.isAnchorTracked()),
                motors == null ? "" : motors.getQuality().name(),
                motors == null ? "" : motors.getLeft().getState().name(),
                motors == null ? "" : motors.getRight().getState().name(),
                detail);
        enqueue(() -> writeRow(line));
    }

    @Override
    public void close() {
        enqueue(() -> {
            if (writer != null) writer.close();
            writer = null;
        });
        writerThread.shutdown();
    }

    /** Storage tests can await close without making the activity's close path block. */
    boolean awaitClosed(long timeoutMillis) throws InterruptedException {
        return writerThread.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** RFC 4180 row: fields containing commas, quotes or line breaks are quoted. */
    static String row(String... fields) {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < fields.length; index++) {
            if (index > 0) line.append(',');
            String field = fields[index] == null ? "" : fields[index];
            if (field.contains(",") || field.contains("\"") || field.contains("\n")
                    || field.contains("\r")) {
                line.append('"').append(field.replace("\"", "\"\"")).append('"');
            } else {
                line.append(field);
            }
        }
        return line.toString();
    }

    /** Data rows stop at the size limit; one note records where the log was cut. */
    private void writeRow(String line) throws IOException {
        if (bytes > MAXIMUM_BYTES) {
            if (!limitReported) {
                limitReported = true;
                writeRaw(eventRow("note", "Logging stopped at the size limit"));
            }
            return;
        }
        writeRaw(line);
    }

    private void writeRaw(String line) throws IOException {
        if (writer == null) return;
        writer.write(line);
        writer.write('\n');
        writer.flush();
        bytes += line.length() + 1;
    }

    private interface IoTask { void run() throws IOException; }

    private boolean enqueue(IoTask task) {
        if (writerThread.isShutdown()) return false;
        try {
            writerThread.execute(() -> {
                try {
                    task.run();
                } catch (IOException | RuntimeException failure) {
                    Log.w(TAG, "Trial log write failed", failure);
                }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Closed concurrently; the record is best-effort diagnostics.
            return false;
        }
    }

    private static void prune(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.startsWith("trial-") && name.endsWith(".csv"));
        if (files == null || files.length < RETAINED_FILES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        for (int index = 0; index <= files.length - RETAINED_FILES; index++) {
            if (!files[index].delete()) Log.w(TAG, "Could not remove old trial log " + files[index]);
        }
    }

    private static String now() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }

    private static String value(@Nullable String value) { return value == null ? "" : value; }
}
