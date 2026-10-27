# Board-relative installed-component inspection — 27 October 2026

## Scope

This is the first implementation stage of the campus/generalization plan. Phase 5
Nano placement and Phase 8 distance-sensor placement are the native-detail capture
pilots. It does not introduce new trained models, synthetic training images,
dependencies, markers, or a larger global reference bank. Existing phases retain
their physical checklists and verification thresholds.

## Changes

- `AssemblyCapturePolicy` / `AssemblyActivity` / a read-only controller getter:
  request a bounded native-detail camera stream for installation demos and
  placement scans in Phases 5 and 8, up to 1280 pixels on the long edge. Loose-part
  scans, wiring and physical checklists retain the baseline. The actual
  supported size/crop remains CameraX's choice. Other phases, low-RAM devices,
  unsupported stream combinations and running-low/critical memory pressure use
  the 640-pixel baseline. Stream replacement drops old-frame evidence, reattaches
  the existing analyzer and preserves the descriptor bank and workflow. Optional
  supported automatic antibanding and temporary AF/AE/AWB metering are requested;
  exposure is not locked across rooms.
- `BoundedOutlineTracker` / `AssemblyVisionAnalyzer`: adapt coordinates around
  the unchanged `TargetBoundingTracker` engine. LK and contour processing stay at
  <=640 pixels, while local component sampling receives the real camera pixels.
  Geometry and IMU pixel velocities are converted in both directions. Resolution
  changes invalidate old tracking geometry.
- `Phase5ReferenceMatcher`: independent fixed-PCB matches can propose a Nano
  detail crop when the initial scene extraction supplies too few component
  matches. The crop must still supply fresh distributed body matches, and the
  existing independent PCB, placement and reversed-installation gates still apply.
  A board pose alone cannot complete the step.
- `InspectionReferenceSchedule` / `PhotoFeaturePartition`: fast scheduling uses
  small, spatially spread library windows and separate positive/negative families.
  Body and PCB probes only schedule work. A preferred valid or rejected view is
  retained as a search hint, never as recognition evidence. The original full
  family ranking remains available in high-detail recovery: an initial experiment
  that restricted that path delayed a previously working view and was revised.
- `Phase8ReferenceMatcher`: reuse identical refinement results only within the
  same inspection call. Both passes no longer repeat the same expensive sensor
  crop. This cache is cleared on entry and in `finally`; it never carries a
  detection or failed score into another camera frame.
- `ComponentDetailExtractor`: reuse scratch pixels/masks/keypoint containers;
  every returned descriptor sample still owns its own rows. Phase 3/5/7/8/10
  samplers release scratch memory through the serialized camera-pause cleanup.
- Completed assembly steps keep LK outlines without repeatedly rerunning heavy
  photo verification. Lost tracking or an explicit sample capture can reopen
  visual localization. This changes frame admission, not completion/checkpoints.
- `InstalledScanDiagnostics`: optional stage-specific logs expose board/body
  evidence availability, source dimensions, component footprint pixels, mean
  luminance, dark/clipped fractions and a coarse gradient statistic. These are
  descriptive capture metrics, not confidence values or new acceptance gates.

`OrbMatchPolicy`, `VerifiedScanEvidence`, `DiagnosticTelemetryLocker`,
`OpticalFlowTrackerBridge`, `TargetBoundingTracker`,
`DynamicComponentOutlineOverlayView`, phase flow/checkpoint classes and database
schemas are preserved.

## Verification protocol

The learner APK is not installed/uninstalled or data-cleared. Existing tablet
`2603ARP14G` / ADB serial `368e278b` runs the isolated candidate DEX/test fixtures.
The physical camera benchmark opens the installed menu for foreground camera
access; it performs no assembly actions. Hashes of assembly progress, guided
steps and the SQLite database are compared before/after testing.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
$env:GRADLE_USER_HOME = 'C:/Users/Hem Bendijo/.gradle'
$env:ANDROID_HOME = 'C:/Users/Hem Bendijo/AppData/Local/Android/Sdk'
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python -B tools/run_host_tests.py
python -B tools/prepare_candidate_replay.py
adb -s 368e278b push app/build/tmp/tablet-audit/candidate-code.zip /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run_quality_replay.ps1 -Serial 368e278b -Suite all
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run_quality_replay.ps1 -Serial 368e278b -Suite boardrelative
```

The board-relative fixtures cover coordinate preservation under pan, descriptor
ownership across sampler reuse, pause cleanup, positive/reversed installed views,
completed-step tracking without repeated verification, and actual camera stream
delivery/copy/tracking cost. The deliberately withheld-global-body/erased-body
fixture tests routing and rejection, not held-out generalization accuracy.

## Campus follow-up

After updating through Android Studio without uninstalling or clearing data,
check Phase 5.3 and Phase 8.2B in both rooms. Capture difficult views using the
existing **Capture current scan frame** control, then export diagnostic logs.
The captured PNG retains the actual analyzed camera pixels and calibration.

Optional detailed trace:

```powershell
adb -s 368e278b shell setprop log.tag.InstalledScan DEBUG
adb -s 368e278b logcat -d -s InstalledScan:D AssemblArMetrics:D '*:S'
adb -s 368e278b shell setprop log.tag.InstalledScan INFO
```

Use separate recording sessions for reference selection and final evaluation;
include correct, reversed and missing installations. Higher source resolution
cannot recover clipped highlights, occluded components or out-of-focus detail.
These changes do not establish success in every unseen campus environment.

## Results

Build and all **742 host tests** passed. The broad native sweep passed 90 groups;
final focused/related runs brought normal-suite coverage to **94 distinct passing
groups**, including the added active-LK time/memory test. This is consolidated
coverage across runs, not one final 94-group sweep.

Real preview-plus-YUV camera delivery stayed approximately 14 fps at both tested
sizes. A separate recorded-pixel active-LK benchmark measured P95 **11.02 ms** at
1280-wide input, with no positive native-heap growth after warmup. In a controlled
same-pixel comparison, the committed and candidate detailed Nano matcher medians
were **918 ms** and **851 ms**, respectively. These are specific fixture results,
not a promise that every assembly will verify within two seconds.

Progress/preferences/database checksums remained unchanged. Command results,
snapshot provenance, limitations and APK checksum are in
[the results report](BOARD_RELATIVE_INSPECTION_RESULTS_2026_10_27.txt).

An optional matcher-only A/B probe can be reproduced from a fresh candidate:

```powershell
python -B tools/prepare_phase5_comparison.py --revision 9bb3ac557ba454d2d54891d6f24e3d3b4c1e43d3
adb -s 368e278b push app/build/tmp/tablet-audit/candidate-code.zip /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip
adb -s 368e278b shell am instrument -w -r -e class dev.chyken.assembl_ar.CandidateCodeReplayInstrumentedTest -e candidateDex /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip -e candidateCases 'vision.Phase5ComparisonInstrumentedTest#committedAndCandidateDetailedNanoMatchersUseIdenticalPixels' dev.chyken.assembl_ar.test/androidx.test.runner.AndroidJUnitRunner
```

The baseline is generated under the ignored build directory and appended only
to the test archive. It is absent from the learner APK. The optional fixture
skips when that test-only baseline DEX has not been prepared. Regenerating the
ordinary candidate archive removes the comparison DEX.
