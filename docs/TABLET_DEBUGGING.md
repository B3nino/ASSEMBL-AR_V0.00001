# Live tablet diagnosis — 22 October 2026

## Confirmed device and installed code

- Authorized ADB tablet: REDMI Pad 2 9.7, model 2603ARP14G, serial 368e278b.
- Installed package: `dev.chyken.assembl_ar`, debug version `1.1.9-33826e80da-dirty`.
- Android Studio's deployed `code_cache/.overlay/base.apk/classes6.dex` contains both
  `Phase7TrackerSearch` and `ReferenceRegionCache`; checking the base APK alone would incorrectly
  suggest that the recent source changes had not been deployed.
- The latest retrieved trial records confirm repeated Step 7.4 zero detections. Host reference
  probes therefore do not establish that the physical tablet scan works.

## Build blocker resolved

The Windows JDK successfully binds its selector wakeup Unix-domain socket, but connecting to that
socket fails with `SocketException: Invalid argument: connect`. This is the cause of the prior
Gradle loopback error. Pointing **only that build process's** `jdk.net.unixdomain.tmpdir` at an
unavailable directory activates the JDK's existing TCP fallback. Project JVM criteria, Android
Studio settings, and system Java installations are unchanged.

Run `tools/build_tablet_debug.ps1` from PowerShell. It restores its environment variables on exit.
The verified build completed successfully for both tasks:

```text
:app:assembleDebug
:app:assembleDebugAndroidTest
```

Current AGP output locations:

- `app/build/intermediates/apk/debug/app-arm64-v8a-debug.apk`
- `app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk`

The app APK is approximately 1.74 GB because of its bundled assets. The test APK is approximately
130 MB. The temporary portable JDK downloaded during diagnosis is not required by the workaround;
it resides only in the ignored build directory.

The helper explicitly disables the injected `testOnly` flag for the main debug APK, so the same
signed update can also be approved through Android's normal on-device installer. On this tablet,
some later USB approval activities finished almost immediately without staying visible, then
returned a generic user-restriction error. No USB/security settings were disabled. A standard
update was staged as `/sdcard/Download/ASSEMBL-AR-Step7-fix.apk` for manual installation instead.

## Progress preservation and installation

Before attempting the update, ASSEMBL-AR was stopped and its shared preferences and databases were
backed up to `app/build/tmp/agent-check/tablet-progress-before-native-debug.tar` (299,008 bytes).
This file contains private app data, is ignored by Git, and must not be published.

Initial updates were denied by the tablet's **Install via USB** confirmation countdown,
returning `INSTALL_FAILED_USER_RESTRICTED`. The app and test package were subsequently installed
successfully with `cmd package install -r -t` from staged APKs. No uninstall, data clear, progress
reset, or restoration was performed. The native tests below do not touch learner progress.

## Diagnostic build additions

- Phase 7's early analyzer return now supports the same opt-in `AssemblArCapture` frame recorder
  and `AssemblArMetrics` logging already used by the common analyzer. They remain off by default.
- `AssemblArTracker` DEBUG logging reports each search window, sampling extent, local keypoint
  count, references visited, and match score. It never changes acceptance or learner progress.
- Enable these tags only for a short controlled capture, then restore their previous values.
- Read the actual frames and logs before changing further matcher thresholds.
- Run only the targeted `Phase7ReferenceMatcherInstrumentedTest` native-photo tests on this
  learner device. Workflow tests that reset project progress must not be run against its data.

## Physical-camera evidence and matcher correction

A short live capture at Step 7.4 recorded 640×400 analyzed frames containing both installed
trackers. These private images and logs remain in the ignored `app/build/tmp/agent-check/`
directory and the app's external diagnostic folder; none are added to the reference dataset.

The failing build had no color-generated module windows in this lighting. Its broad fallback
windows enlarged the modules, and local extraction consumed most of the matching deadline:
typically only one reference was visited per window. Full-image keypoint counts of 3,200 did not
mean that either sensor had enough useful local matches.

The corrected search adds dark PCB contour proposals, retains native camera sampling every other
frame, and also visits detail extents of 400, 600 and 800 pixels. Each matching allowance begins
after local feature extraction. Contours are sorted before suppressing nested candidates so a
narrow shadow cannot suppress the whole module. Grayscale searches retain additional proposals
when board printing and shadows compete with the sensors.

Acceptance still requires two separate, geometrically verified sensor bodies at two distinct
mounts under one board homography, in the same frame. No inter-frame count union, manual scan
bypass, relaxed identity thresholds, or changes to other phase matchers were introduced.
Reflective screws and nut retention remain explicit physical checklist answers.

The first corrected native replay recognized both trackers and both board mounts on frame 2 of
the actual captured color image. The native identical-module regression also passed camera-size,
blur, 90-degree rotation, grayscale, and missing-module cases (21.787 seconds total). A further
real-frame grayscale regression exposed nested-contour suppression; that correction is included
in the final build.

## Final installed build and native results

The final standard debug APK was manually installed on the tablet at 12:47:54 on 22 October.
Its package remains debuggable and no longer carries the injected `TEST_ONLY` flag. The existing
test package runs against that same installed app; no alternate matcher implementation is used.

The targeted run finished **OK (2 tests)** in **36.07 seconds**:

- `capturedTabletPairMustMatchAndSingleModuleMustNot`: the real 640×400 camera capture verified
  both distinct trackers and board mounts on color frame 2 and grayscale frame 3. Removing either
  module separately never passed in eight fresh searches per missing-module image.
- `identicalModulesInOneVgaFrameStillUnlockTheInstalledPairScan`: camera-sized identical-module,
  blur, 90-degree rotation, grayscale and missing-module regressions passed on native OpenCV.

Exact instrument output and detailed counts are saved only in the ignored local build directory
as `native-final-run.log` and `native-final-details.log`.

## Final live HUD confirmation

At 12:51 the user resumed Step 7.4 and framed both installed modules. The final installed app
outlined **Tracker 1** and **Tracker 2** in green, displayed **Both trackers located at their
mounts**, and automatically opened **Line tracker stability**. This verifies the actual camera,
matcher, request-generation checks and scan-to-checklist transition together. No developer skip,
progress reset, or manual stability answer was used to obtain this result.

The physical **Stable & Secure** answer remains for the user; it was not selected remotely.
The screenshot and successful live metrics remain in the ignored local build directory.
Diagnostic tags were restored to their original values after this short check. Temporary APKs
under `/data/local/tmp` were removed; the normal update remains in tablet Downloads.

## Files changed in this live-debugging update

Paths are relative to the Gradle project root.

- `app/src/main/java/dev/chyken/assembl_ar/vision/AssemblyVisionAnalyzer.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase7ReferenceMatcher.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase7TrackerSearch.java`
- `app/src/androidTest/java/dev/chyken/assembl_ar/vision/Phase7ReferenceMatcherInstrumentedTest.java`
- `tools/build_tablet_debug.ps1`
- `tools/validate_phase7_matching.py`
- `docs/TABLET_DEBUGGING.md`
- `docs/PHASE7_CASTER_TRACKERS.md`
- `docs/CAMERA_STARTUP.md`

No layout XML, reference photograph, OpenGL setting, global matching threshold, or workflow
snapshot format changed in this update.
