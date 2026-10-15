package dev.chyken.assembl_ar.diagnostics;

import android.os.Build;

import java.util.Locale;

import dev.chyken.assembl_ar.BuildConfig;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhaseCatalog;
import dev.chyken.assembl_ar.workflow.phase.PhaseProgressStore;

/**
 * Identifies the exact source, workflow catalog and device of a run. It is shown in the
 * assembly screen and written at the top of every trial log so that a recording or exported log
 * can be matched to one APK without guessing from its appearance.
 */
public final class BuildStamp {
    private BuildStamp() { }

    /** One line for the screen, e.g. {@code v1.1.42-a93fa3cb6b · phase catalog r1}. */
    public static String shortLabel() {
        return shortLabel(BuildConfig.VERSION_NAME, AssemblyPhaseCatalog.REVISION);
    }

    static String shortLabel(String versionName, int catalogRevision) {
        return "v" + versionName + " · phase catalog r" + catalogRevision;
    }

    /** Multi-line build and device identity for Details and trial-log headers. */
    public static String details() {
        return details(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TYPE,
                BuildConfig.GIT_COMMIT, BuildConfig.GIT_COMMIT_TIME, BuildConfig.GIT_DIRTY,
                AssemblyPhaseCatalog.REVISION, PhaseProgressStore.SCHEMA_VERSION,
                Build.MANUFACTURER + " " + Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0]);
    }

    static String details(String versionName, int versionCode, String buildType, String commit,
                          String commitTime, boolean dirty, int catalogRevision, int progressSchema,
                          String device, String androidRelease, int sdk, String abi) {
        return String.format(Locale.US,
                "App: %s (code %d, %s)%nSource: %s%s, committed %s%n"
                        + "Workflow: phase catalog r%d, progress schema %d%n"
                        + "Device: %s, Android %s (API %d), %s",
                versionName, versionCode, buildType, commit,
                dirty ? " with uncommitted changes" : "", commitTime,
                catalogRevision, progressSchema, device, androidRelease, sdk, abi);
    }
}
