package dev.chyken.assembl_ar.diagnostics;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class TrialLogStorageTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private final List<TrialLog> logs = new ArrayList<>();

    @After
    public void finishWritersBeforeTemporaryFolderCleanup() throws InterruptedException {
        for (TrialLog log : logs) log.close();
        for (TrialLog log : logs) assertTrue("Writer closes its files", log.awaitClosed(5_000));
    }

    private TrialLog start(File directory, String session, String build, String created) {
        TrialLog log = TrialLog.start(directory, session, build, created);
        logs.add(log);
        return log;
    }

    @Test
    public void sessionsWithIdenticalTimestampsKeepSeparateEvidence() throws Exception {
        File directory = folder.newFolder("trials");
        String timestamp = "20261002-120000-000";
        try (TrialLog first = start(directory, "first session", "App: exact-build", timestamp);
             TrialLog second = start(directory, "second session", "App: exact-build", timestamp)) {
            first.event("inventory", "first evidence");
            second.event("inventory", "second evidence");
            String firstContent = read(awaitSnapshot(first));
            String secondContent = read(awaitSnapshot(second));

            assertNotEquals(first.getFile(), second.getFile());
            assertTrue(firstContent.contains("# App: exact-build"));
            assertTrue(firstContent.contains("# Session: first session"));
            assertTrue(firstContent.contains("first evidence"));
            assertFalse(firstContent.contains("second evidence"));
            assertTrue(secondContent.contains("# Session: second session"));
            assertTrue(secondContent.contains("second evidence"));
            assertFalse(secondContent.contains("first evidence"));
        }
    }

    @Test
    public void shareWaitsForQueuedRowsAndKeepsAnImmutableSnapshot() throws Exception {
        try (TrialLog log = start(folder.newFolder("trials"), "current session",
                "App: exact-build", "20261002-120000-000")) {
            for (int row = 0; row < 100; row++) log.event("frame", "evidence " + row);
            CountDownLatch copied = new CountDownLatch(1);
            AtomicReference<File> exported = new AtomicReference<>();
            log.shareSnapshot(file -> {
                exported.set(file);
                copied.countDown();
            });
            // This is queued behind the copy even if storage has not opened yet.
            log.event("phase", "after share");
            File later = awaitSnapshot(log);
            assertTrue("Export completes", copied.await(5, TimeUnit.SECONDS));
            assertNotNull(exported.get());
            assertNotEquals(log.getFile(), exported.get());
            String earlierContent = read(exported.get());
            for (int row = 0; row < 100; row++) {
                assertTrue("Export retains queued evidence " + row,
                        earlierContent.contains(",evidence " + row + "\n"));
            }
            assertFalse(earlierContent.contains("after share"));
            assertTrue(read(later).contains("after share"));
            assertTrue(read(log.getFile()).contains("after share"));
        }
    }

    @Test
    public void repeatedSharingRetainsTwelveCompleteExportsAndTheLatestOne() throws Exception {
        File directory = folder.newFolder("trials");
        try (TrialLog log = start(directory, "current session", "App: exact-build",
                "20261002-120000-000")) {
            File newest = null;
            File oldest = null;
            for (int share = 0; share < 15; share++) {
                log.event("share", "export " + share);
                newest = awaitSnapshot(log);
                if (share == 0) oldest = newest;
                // Make chronology deterministic even when several copies complete in one ms.
                assertTrue(newest.setLastModified(1_000L + share * 1_000L));
            }
            File[] snapshots = new File(directory, "shared").listFiles();
            assertNotNull(snapshots);
            assertEquals(12, snapshots.length);
            assertNotNull(newest);
            assertTrue(newest.isFile());
            assertNotNull(oldest);
            assertFalse("Oldest export is pruned by age", oldest.exists());
            assertTrue(read(newest).contains("export 14"));
            assertTrue(log.getFile().isFile());
        }
    }

    @Test
    public void shareAfterCloseReportsUnavailableWithoutWaiting() throws Exception {
        TrialLog log = start(folder.newFolder("trials"), "closed session",
                "App: exact-build", "20261002-120000-000");
        awaitSnapshot(log);
        log.close();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<File> file = new AtomicReference<>();
        log.shareSnapshot(snapshot -> {
            file.set(snapshot);
            completed.countDown();
        });
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertNull(file.get());
    }

    private static File awaitSnapshot(TrialLog log) throws InterruptedException {
        CountDownLatch copied = new CountDownLatch(1);
        AtomicReference<File> file = new AtomicReference<>();
        log.shareSnapshot(snapshot -> {
            file.set(snapshot);
            copied.countDown();
        });
        assertTrue("Writer drains its queue", copied.await(5, TimeUnit.SECONDS));
        assertNotNull("Snapshot is available", file.get());
        return file.get();
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
