package dev.chyken.assembl_ar.diagnostics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class DiagnosticsFormatTest {
    @Test
    public void buildStampNamesVersionSourceWorkflowAndDevice() {
        assertEquals("v1.1.42-a93fa3cb6b · phase catalog r1",
                BuildStamp.shortLabel("1.1.42-a93fa3cb6b", 1));
        String details = BuildStamp.details("1.1.42-a93fa3cb6b-dirty", 42, "debug", "a93fa3cb6b",
                "2026-09-30T00:53:19+08:00", true, 1, 1, "HONOR X8b", "16", 36, "arm64-v8a");
        assertTrue(details.contains("1.1.42-a93fa3cb6b-dirty (code 42, debug)"));
        assertTrue(details.contains("a93fa3cb6b with uncommitted changes, committed 2026-09-30T00:53:19+08:00"));
        assertTrue(details.contains("phase catalog r1, progress schema 1"));
        assertTrue(details.contains("HONOR X8b, Android 16 (API 36), arm64-v8a"));
    }

    @Test
    public void trialRowsAreFixedWidthCsvWithEscapedText() {
        assertEquals(20, TrialLog.COLUMNS);
        assertEquals("a,\"b,c\",\"say \"\"hi\"\"\",\"x\ny\",",
                TrialLog.row("a", "b,c", "say \"hi\"", "x\ny", null));
        String event = TrialLog.eventRow("mode", "VERIFY, generation 4");
        assertTrue(event.contains(",mode,"));
        assertTrue(event.endsWith(",\"VERIFY, generation 4\""));
        // 19 separators outside the quoted detail field.
        String unquoted = event.substring(0, event.lastIndexOf(",\"") + 1);
        assertEquals(TrialLog.COLUMNS - 1, unquoted.length() - unquoted.replace(",", "").length());
    }
}
