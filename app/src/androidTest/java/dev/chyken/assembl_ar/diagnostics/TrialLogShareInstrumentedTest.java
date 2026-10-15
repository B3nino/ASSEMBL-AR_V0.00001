package dev.chyken.assembl_ar.diagnostics;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import dev.chyken.assembl_ar.BuildConfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Share log hands another app a content URI for an exported snapshot. The JVM storage tests
 * cannot see the manifest's FileProvider, so this checks on a device that the export folder is
 * inside the shared paths and that a receiving app can read the exact exported evidence.
 */
@RunWith(AndroidJUnit4.class)
public final class TrialLogShareInstrumentedTest {
    @Test
    public void exportedSnapshotIsReadableThroughTheTrialLogProvider() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        TrialLog log = TrialLog.start(context, "provider check");
        File snapshot = null;
        try {
            log.event("phase", "evidence before share");
            CountDownLatch exported = new CountDownLatch(1);
            AtomicReference<File> result = new AtomicReference<>();
            log.shareSnapshot(file -> {
                result.set(file);
                exported.countDown();
            });
            assertTrue("Export completes", exported.await(10, TimeUnit.SECONDS));
            snapshot = result.get();
            assertNotNull("Device storage produces an export", snapshot);
            assertEquals(new File(TrialLog.directory(context), "shared"), snapshot.getParentFile());

            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".triallogs", snapshot);
            assertEquals("content", uri.getScheme());
            String shared = read(context, uri);
            assertTrue(shared.contains("# App: " + BuildConfig.VERSION_NAME + " (code "));
            assertTrue(shared.contains("# Session: provider check"));
            assertTrue(shared.contains(",evidence before share\n"));
        } finally {
            File session = log.getFile();
            log.close();
            if (snapshot != null) assertTrue(snapshot.delete());
            if (session != null) session.delete();
        }
    }

    private static String read(Context context, Uri uri) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            assertNotNull(input);
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) > 0; ) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
