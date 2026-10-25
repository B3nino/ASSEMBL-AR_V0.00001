# Reference preparation stopping after 387

## Confirmed cause

The installed tablet build completed references 1–387, then failed saving reference 388:

`references/Phase8/STEP8_2DISTANCESENSORHARDWARESCAN_INVALID/var_light/VIDEO789_fDOmql_e_166_invalid_INWARD.jpg`

Its generated descriptor basename was **257 bytes**, beyond Android's 255-byte per-filename limit. Native reproduction returned `ENAMETOOLONG (File name too long)`. The following reference generated a 255-byte basename, which also left no room for the old `.tmp` suffix. The tablet had approximately 60 GB free. This was not a full-storage diagnosis.

The cache writer swallowed the original exception; the whole-library preparation gate then raised a generic storage error. Every retry reached the same source, so scanning could not start in Phase 7 even though the failing asset belonged to Phase 8.

## Changes

- `ReferenceCacheFileName.java`: retain existing short filenames; use a SHA-256 basename for oversized names. The digest covers the entire existing source/ROI/version fingerprint. Geometry sidecars also fit safely.
- `ReferenceDatabase.java`: use short, unique temporary filenames and atomic replacement; clean temporary files on failure; preserve the old completed descriptor until replacement is ready. Report cache publication failures with the actual source and ordinal. A write failure no longer silently marks valid reference pixels as a bad photo. A transient RAM copy cannot mask a missing durable cache entry.
- `ReferenceCacheIndex.java`: use unique temporary index files and clean them after both success and failure.
- `ReferenceCacheFileNameTest.java`: deterministic names, collision separation for changed suffixes, legacy short-name compatibility, sidecar length bounds.
- `WholeLibraryPreparationInstrumentedTest.java`: actual installed-build reproduction, cold preparation of the failing real image, warm descriptor reuse, deliberately failed index publication, whole-library completion, and persisted-index validation.

No scan thresholds, orientation rejection rules, tracking modules, phase checkpoints, progress records, or saved assembly controls were cleared to fix preparation. Existing descriptor-index entries remain reusable.

## Tablet verification

Tablet: 2603ARP14G, ADB serial `368e278b`. Tests used isolated candidate app/test DEX and the existing test runner. The main app was not installed or uninstalled.

Passed:

1. Reproduced the old failure at reference 388 with the exact 257-byte filename.
2. Cold-compiled that real reference under the bounded name; confirmed it was **not skipped**; warm bank reopening did not rewrite it.
3. Injected an index-publication failure; confirmed the source/ordinal reached the caller and the photograph was not blacklisted; temporary files were cleaned.
4. Completed the discovered full catalog, including all entries after 387. The later verification run contained **1,030** registrations; every entry was terminal in a freshly reopened persisted index.
5. Repeated that full-index pass after registry publication: **303 ms**, with no descriptor rewrites. This is an in-process warm-index measurement, not a claim that every cold app launch takes 303 ms. The preceding process initialization/registry preparation took approximately 24.5 seconds using the existing disk caches.
6. Camera pause/resume retained references and discarded paused in-flight evidence.
7. Optical-flow bridge pause released its latest frame and resumed tracking.

Host suite: **417 tests passed** at this checkpoint. APK and test APK builds succeeded.

After the remaining Phase 10–11 references were finalized, the full-catalog regression passed again with **1,081 registrations**. The previously failing reference was confirmed compiled rather than skipped. The in-process warm-index repeat took **301 ms**, with no descriptor rewrites. Phase 7–9 replay and all six Phase 10–11 video/individual-tool checks passed in the final build. See `PHASE1011_NATIVE_VERIFICATION_2026_10_25.txt`.

## Build and deploy

From the Gradle project directory `ASSEMBLAR ls/app`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python tools/run_host_tests.py
```

Deploy the `app` configuration through Android Studio to the tablet, preserving app data. Resume Phase 7. The library pass should continue past the former failure and unlock camera recognition. Do not uninstall or clear storage.

The compiled cache is local app data; source references are packaged in the APK. This fix does not depend on recordings remaining in Downloads.
