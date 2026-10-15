package dev.chyken.assembl_ar.vision;

/**
 * Places camera capture timestamps in the {@code SystemClock.elapsedRealtimeNanos()} timebase.
 *
 * <p>CameraX forwards the sensor timestamp unchanged. Devices reporting a REALTIME source use
 * elapsed realtime; others use uptime (CLOCK_MONOTONIC), which stops during deep sleep. The
 * assembly workflow rejects frames captured before each card was shown by comparing against
 * elapsed realtime, so an uptime stamp would make every live frame look stale after the phone
 * had slept once. Detecting the nearer clock mirrors CameraX's own video timebase conversion.</p>
 */
public final class CaptureTimebase {
    /** Below this, uptime and realtime are interchangeable for every frame gate in the app. */
    static final long MINIMUM_SLEEP_OFFSET_NANOS = 1_000_000_000L;

    private CaptureTimebase() {
    }

    public static long toElapsedRealtimeNanos(long sensorTimestampNanos) {
        return toElapsedRealtimeNanos(sensorTimestampNanos,
                android.os.SystemClock.elapsedRealtimeNanos(), System.nanoTime());
    }

    static long toElapsedRealtimeNanos(long sensorTimestampNanos, long realtimeNowNanos,
                                       long uptimeNowNanos) {
        long sleepOffset = realtimeNowNanos - uptimeNowNanos;
        if (sleepOffset < MINIMUM_SLEEP_OFFSET_NANOS) return sensorTimestampNanos;
        boolean uptimeSource = Math.abs(sensorTimestampNanos - uptimeNowNanos)
                < Math.abs(sensorTimestampNanos - realtimeNowNanos);
        return uptimeSource ? sensorTimestampNanos + sleepOffset : sensorTimestampNanos;
    }
}
