package dev.chyken.assembl_ar.vision;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class CaptureTimebaseTest {
    private static final long HOUR = 3_600_000_000_000L;
    private static final long UPTIME_NOW = 5 * HOUR;
    private static final long REALTIME_NOW = UPTIME_NOW + 7 * HOUR;

    @Test public void realtimeSensorStampsAreUnchanged() {
        long captured = REALTIME_NOW - 40_000_000L;
        assertEquals(captured, CaptureTimebase.toElapsedRealtimeNanos(
                captured, REALTIME_NOW, UPTIME_NOW));
    }

    @Test public void uptimeSensorStampsAfterDeepSleepMoveToRealtime() {
        long captured = UPTIME_NOW - 40_000_000L;
        long converted = CaptureTimebase.toElapsedRealtimeNanos(captured, REALTIME_NOW, UPTIME_NOW);
        assertEquals(REALTIME_NOW - 40_000_000L, converted);
        // A card shown just before this capture must accept the frame, not treat it as stale.
        long cardShownAt = REALTIME_NOW - 100_000_000L;
        assertTrue(converted >= cardShownAt);
    }

    @Test public void consecutiveUptimeFramesKeepTheirSpacing() {
        long first = CaptureTimebase.toElapsedRealtimeNanos(UPTIME_NOW - 100_000_000L,
                REALTIME_NOW, UPTIME_NOW);
        long second = CaptureTimebase.toElapsedRealtimeNanos(UPTIME_NOW,
                REALTIME_NOW + 100_000_000L, UPTIME_NOW + 100_000_000L);
        assertEquals(100_000_000L, second - first);
    }

    @Test public void withoutDeepSleepBothClocksAreEquivalentAndStampsAreUntouched() {
        long uptime = 2 * HOUR;
        long realtime = uptime + CaptureTimebase.MINIMUM_SLEEP_OFFSET_NANOS - 1;
        assertEquals(uptime - 5L, CaptureTimebase.toElapsedRealtimeNanos(uptime - 5L, realtime, uptime));
        assertEquals(1_000L, CaptureTimebase.toElapsedRealtimeNanos(1_000L, realtime, uptime));
    }
}
