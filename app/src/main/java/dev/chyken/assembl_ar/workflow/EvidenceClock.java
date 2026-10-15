package dev.chyken.assembl_ar.workflow;

/**
 * Timestamps for the live-evidence gates that exclude the analyzer's own processing time.
 *
 * <p>CameraX delivers only the newest frame to a busy analyzer, so the next analyzed frame is
 * captured while the previous one is still being processed. Capture gaps therefore grow with
 * analysis time even though the camera never stopped. The gates' frame-gap limits are meant to
 * detect camera silence; on a device taking longer than those limits per frame, raw capture
 * timestamps would reset every check before it could complete.</p>
 *
 * <p>The returned timestamp advances by the camera silence between frames: from the moment the
 * previous frame finished processing to the capture of the next one (at least one millisecond,
 * so frames stay ordered). When a completion time is unknown, capture timestamps are returned
 * unchanged.</p>
 */
public final class EvidenceClock {
    static final long MINIMUM_STEP_NANOS = 1_000_000L;

    private long previousCaptureNanos = Long.MIN_VALUE;
    private long previousCompletedNanos = Long.MIN_VALUE;
    private long processingCreditNanos;
    private long lastProcessingNanos;

    /**
     * @param captureNanos   frame capture time in the elapsed-realtime timebase
     * @param completedNanos when analysis of this frame finished, or {@link Long#MIN_VALUE}
     */
    public synchronized long observe(long captureNanos, long completedNanos) {
        if (previousCaptureNanos != Long.MIN_VALUE && captureNanos > previousCaptureNanos
                && previousCompletedNanos > previousCaptureNanos) {
            long gap = captureNanos - previousCaptureNanos;
            long silence = Math.max(MINIMUM_STEP_NANOS, captureNanos - previousCompletedNanos);
            if (silence < gap) processingCreditNanos += gap - silence;
        }
        if (captureNanos > previousCaptureNanos) {
            previousCaptureNanos = captureNanos;
            previousCompletedNanos = completedNanos;
            lastProcessingNanos = completedNanos > captureNanos ? completedNanos - captureNanos : 0L;
        }
        return captureNanos - processingCreditNanos;
    }

    /** Processing time of the newest frame, used to size result-expiry windows. */
    public synchronized long getLastProcessingMillis() {
        return lastProcessingNanos / 1_000_000L;
    }
}
