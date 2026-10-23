# Unified reference and diagnostic tracking overhaul

## Behavior

- `VisionReferenceFactory` now delegates registration to `UnifiedReferenceCatalog`. The existing reviewed individual registrations, dynamic Phase 1–11 folders, production scene registrations, and administrator registrations enter the same graph. Parts Test selects this catalog and uses the production extraction config and cache directory.
- `VisionPreloadManager` starts in the menu, prepares compiled descriptors off the UI thread, and publishes the process-wide registry. Full descriptors use the persistent cache and an 8–48 MiB native hot cache; the registry keeps compact descriptor representatives for every usable reference, then uses BF-Hamming and distributed RANSAC on shortlisted candidates. This bounds tablet work instead of matching every full-resolution descriptor on every frame.
- `KeypointInventoryManager` exposes per-file, per-photometric-variant assigned counts. The normal variant is the on-screen primary count; the ZIP inventory contains every variant. Unusable sources are labeled explicitly, not reported as prepared zero-feature successes.
- A strong, geometrically verified competitor can reject a scan before the assembly controller consumes it. Loose identities shared between phases remain reusable. Distinct assembly states carry phase provenance. Unversioned legacy sources have unknown phase provenance instead of inventing a current phase number. Phase-specific negative references stay in local validators, so a bare-board negative for Phase 2 cannot reject the same bare board in Phase 1.

## Phase 2 precision

The top-view gate now requires both yellow motor-body regions, four independently localized bracket/shaft bands, RANSAC consensus, and spatially distributed local evidence. Each body requires at least 6 inliers (`T_MOTOR`), and each motor's bracket zones require a combined 4 inliers. Small precompiled 32×64 bracket silhouette patches verify the four bands, including plain black plastic with too few ORB corners. This avoids accepting PCB-wide matches and avoids demanding texture that the actual bracket does not have.

`STEP2_BARE_BOARD_INVALID/` contains three **copied bundled Phase 1 camera originals**. It has no Downloads dependency. The old general invalid-scan folder remains excluded; this change adds the explicit bare-board rejection set. A bare-board match cannot replace local motor evidence. Conversely, PCB features that remain visible after installation do not veto verified local hardware.

The existing top → left → right → physical stability sequence remains. Only accepted fresh detector evidence can unlock a scan. Flow/prediction is visual-only. The missing-hardware warning is spoken through the existing speech engine.

## Camera and tracking

`AssemblyVisionAnalyzer.analyze()` copies a detector-bound YUV frame into an owned snapshot and immediately closes the CameraX buffer. ORB runs on an isolated worker. Intermediate delivered frames run LK on the camera executor while detection is busy, or between the 150 ms detection intervals. No second camera session is opened.

`OpticalFlowTrackerBridge` carries the latest frame, forward/backward LK/RANSAC geometry, and delayed detector seeds forward to current image coordinates. Translation uses EMA; rotation smoothing uses shortest-arc quaternion interpolation. Device gyro freshness/angular speed bounds a decaying screen-space velocity prediction. Holds expire within 1.5 seconds of the last detector observation; without a usable gyro, the conservative hold is shorter. Fast/unsafe motion clears the hold. This is a bounded prediction, not absolute inertial localization or a world-locked SLAM anchor.

Every update retains capture timebase and request-generation guards. Predicted poses never complete a component or assembly step. Native replay tests use `analyzeForReplay()` for deterministic synchronous processing. Reference guide pictures remain positioning examples; references without an authored physical pose plane do not acquire a fabricated 3D pose.

## Diagnostics and exports

Open **Parts → Individual Component Tests** to reach the diagnostic browser. Its tabs expose individual reference files and assembled states with assigned keypoint counts. Open a selected camera test, then **Benchmark / Settings**. Assembly's diagnostic/share control opens the same settings. Main menu has a Settings button.

The elevated readout is the last child of each camera root and uses 999dp elevation. It also mirrors into a non-touchable application popup so an on-top OpenGL surface cannot obscure the text. It yields focus to dialogs and dismisses on detach. Underlying buttons remain touchable.

Developer Diagnostic Logging is optional and persisted. Starting a benchmark batch enables it. The writer has a bounded background queue and reports dropped records. Files are stored in `getExternalFilesDir(DIRECTORY_DOCUMENTS)/diagnostics/`:

- timestamped scan CSV: phase, request/step tag, expected target, candidate/reference, assigned/live keypoints, measured good/total matches, ratio, extraction/matching times, lighting label, outcome/reason, pose and available ground-truth errors;
- overlay-frame CSV: computed versus predicted pose, registration error, static offset, and movement label;
- text report with scan history;
- ZIP session bundle with report, both CSV streams, keypoint inventory, and recorded replayable `.arpart.zip` frames.

**Capture Sample** retains the existing `.arpart.zip` export/replay format and also saves that exact archive to the diagnostic session. Sharing uses the existing scoped FileProvider and ACTION_SEND; no storage permission or automatic upload is introduced.

Matching accuracy uses actual Lowe-filtered BF pairs before live-point deduplication. Production consensus still deduplicates physical keypoints. Specialized scene matchers get a standardized diagnostic probe (.75 Lowe ratio, Hamming ≤64) from the same native matching pass; their acceptance scores are not relabeled as match counts. Shape fallback successes cannot inflate ORB recognition rate.

## Academic test procedure

1. Select the component or assembled state and frame the physical target. Run all four controlled lighting conditions (Normal, Bright, Weak, Uneven) with **30 fresh trials per condition**. The automatic lighting category is only an image-intensity estimate; use explicit labels for controlled academic trials.
2. Repeat motion batches for static placement, lateral pan, approach/retreat, and rotation. Collection and threshold calculations are automatic. Held predictions are excluded from computed-pose coverage. Coverage counts frames delivered to this analyzer; camera/CameraX-discarded frames are not fabricated into the denominator.
3. For B/C ground truth, use a printed checkerboard coplanar with the target pose origin. Mark the first inner corner to disambiguate grid orientation. Enter inner columns/rows, measured square size, independently measured target-center X/Y in mm, and expected orientation. Keep the grid visible. Metric 3D TRE/static offset requires Camera2 intrinsics; missing intrinsics, missing grid observations, and references without physical poses remain UNMEASURED. A solver's own reprojection residual is not substituted for independent registration error.
4. Download Text/CSV or the complete ZIP session from Settings or the diagnostic screen. Collect each target's four lighting conditions before switching targets. Prior target summaries and raw records remain in the session export.
5. Phases 4 and 6 are wiring/checklist flows without independent photo datasets. The diagnostic browser explains that gap; it does not invent assembled-state reference images or successful pose results.

The requested thresholds are implemented (85% ratio/classification, 80% per-light recognition, 300/200 ms, 10 mm, 15/30 px, 90% coverage). This build does not claim those performance thresholds have been achieved on the tablet. Measurements without the necessary ground truth stay incomplete.

## Verification commands

From the Gradle project directory (`app/`):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python tools/run_host_tests.py
python tools/validate_phase2_precision.py --opencv-path app/build/tmp/cv-test-deps --output app/build/tmp/phase2-photo-parity.json
```

The photo check needs test-only NumPy/OpenCV. They are installed in the ignored build directory on this workstation and are **not Android dependencies**. It exercises 640×400 positive photos, 180° rotations, held-out views, three bare-board photos, a covered motor and a covered bracket. Desktop photo checks are not native Android performance measurements.

For native device validation, deploy the app and test APK through Android Studio, select tablet `2603ARP14G`, and run:

- `Phase2PrecisionInstrumentedTest`
- `AssemblyAnchorTrackerInstrumentedTest`
- existing `AssemblyBoardContinuityInstrumentedTest` and phase-specific replay tests

Or, after installing the matching app/test packages, run:

```powershell
adb -s 2603ARP14G shell am instrument -w -r -e class dev.chyken.assembl_ar.vision.Phase2PrecisionInstrumentedTest,dev.chyken.assembl_ar.vision.AssemblyAnchorTrackerInstrumentedTest dev.chyken.assembl_ar.test/androidx.test.runner.AndroidJUnitRunner
```

Then verify live bare-board rejection, successful top/left/right unlocks, small pans, brief occlusion/reacquisition, diagnostic readout visibility above the 3D preview, untouched UI gestures, and TXT/CSV/ZIP sharing. No APK was installed automatically during this change.

## Superseded experiment

The unfinished Astra navigation/ghost/learning UI work was removed from active sources. Its recovery patch and new-file copies are in the ignored `app/build/tmp/superseded-astra/` directory. The preload/cache work was retained and adapted for this registry; no experimental Phase 2 ghost renderer is enabled by this change.

## API references

- [OpenCV solvePnP coordinate conventions](https://docs.opencv.org/4.x/d5/d1f/calib3d_solvePnP.html)
- [OpenCV pyramidal Lucas–Kanade tracking](https://docs.opencv.org/4.x/d4/dee/tutorial_optical_flow.html)

## Verification results for this change

- Android debug app + Android test APK: build successful.
- Selected host suite: **366 tests passed** (phase flows, catalog registration, wiring/progress, session counting, benchmark formulas, CSV formatting, and quaternion seam regression).
- Desktop photo parity: **11/11 cases passed** under OpenCV 5.0.0. This is not the Android runtime or a tablet latency result.
- Source ROI inventory: **19/19 Phase 2 top-view photographs** produced six usable hardware regions.
- Native device replay, live scanning, popup/GL layering, export chooser behavior, and physical checkerboard measurements: **not run on the tablet in this change**.
- `git diff --check`: clean.

## Exact changed-file inventory

```text
app/src/androidTest/java/dev/chyken/assembl_ar/AssemblyReplayHarness.java
app/src/androidTest/java/dev/chyken/assembl_ar/vision/AssemblyAnchorTrackerInstrumentedTest.java
app/src/androidTest/java/dev/chyken/assembl_ar/vision/AssemblyBoardContinuityInstrumentedTest.java
app/src/androidTest/java/dev/chyken/assembl_ar/vision/Phase2PrecisionInstrumentedTest.java
app/src/main/AndroidManifest.xml
app/src/main/assets/references/Phase2/STEP2_BARE_BOARD_INVALID/IMG_20261018_230003_009@1862131700.jpg
app/src/main/assets/references/Phase2/STEP2_BARE_BOARD_INVALID/IMG_20261018_230005_277@220787151.jpg
app/src/main/assets/references/Phase2/STEP2_BARE_BOARD_INVALID/IMG_20261018_230007_587@-702981581.jpg
app/src/main/java/dev/chyken/assembl_ar/AssemblyActivity.java
app/src/main/java/dev/chyken/assembl_ar/ComponentLibraryActivity.java
app/src/main/java/dev/chyken/assembl_ar/DevDiagnosticsActivity.java
app/src/main/java/dev/chyken/assembl_ar/DevDiagnosticsFragment.java
app/src/main/java/dev/chyken/assembl_ar/MainActivity.java
app/src/main/java/dev/chyken/assembl_ar/PartRecognitionTestActivity.java
app/src/main/java/dev/chyken/assembl_ar/Phase2GuideController.java
app/src/main/java/dev/chyken/assembl_ar/camera/ActiveReferenceScope.java
app/src/main/java/dev/chyken/assembl_ar/camera/IndividualPartTestCatalog.java
app/src/main/java/dev/chyken/assembl_ar/camera/Phase2AssetCatalog.java
app/src/main/java/dev/chyken/assembl_ar/camera/UnifiedReferenceCatalog.java
app/src/main/java/dev/chyken/assembl_ar/camera/VisionReferenceFactory.java
app/src/main/java/dev/chyken/assembl_ar/diagnostics/AcademicBenchmarkRunner.java
app/src/main/java/dev/chyken/assembl_ar/diagnostics/CheckerboardGroundTruth.java
app/src/main/java/dev/chyken/assembl_ar/diagnostics/DiagnosticLogger.java
app/src/main/java/dev/chyken/assembl_ar/diagnostics/DiagnosticSample.java
app/src/main/java/dev/chyken/assembl_ar/diagnostics/DiagnosticSession.java
app/src/main/java/dev/chyken/assembl_ar/diagnostics/PreferencesManager.java
app/src/main/java/dev/chyken/assembl_ar/render/ArGlSurfaceView.java
app/src/main/java/dev/chyken/assembl_ar/render/ArOverlayRenderer.java
app/src/main/java/dev/chyken/assembl_ar/render/ArRenderer.java
app/src/main/java/dev/chyken/assembl_ar/ui/DevDiagnosticsOverlayView.java
app/src/main/java/dev/chyken/assembl_ar/ui/TargetAlignmentBoxView.java
app/src/main/java/dev/chyken/assembl_ar/vision/AssemblyAnchorTracker.java
app/src/main/java/dev/chyken/assembl_ar/vision/AssemblyVisionAnalyzer.java
app/src/main/java/dev/chyken/assembl_ar/vision/ComponentRecognizer.java
app/src/main/java/dev/chyken/assembl_ar/vision/DetachedCameraFrame.java
app/src/main/java/dev/chyken/assembl_ar/vision/DiagnosticHammingMatcher.java
app/src/main/java/dev/chyken/assembl_ar/vision/GlobalComponentRegistry.java
app/src/main/java/dev/chyken/assembl_ar/vision/KeypointInventoryManager.java
app/src/main/java/dev/chyken/assembl_ar/vision/LoosePartShapeRecognizer.java
app/src/main/java/dev/chyken/assembl_ar/vision/OpticalFlowTrackerBridge.java
app/src/main/java/dev/chyken/assembl_ar/vision/Phase10ReferenceMatcher.java
app/src/main/java/dev/chyken/assembl_ar/vision/Phase11ReferenceMatcher.java
app/src/main/java/dev/chyken/assembl_ar/vision/Phase2AppearanceTemplates.java
app/src/main/java/dev/chyken/assembl_ar/vision/Phase2Observation.java
app/src/main/java/dev/chyken/assembl_ar/vision/Phase2PhotoGeometry.java
app/src/main/java/dev/chyken/assembl_ar/vision/Phase2ReferenceMatcher.java
app/src/main/java/dev/chyken/assembl_ar/vision/Phase3ReferenceMatcher.java
app/src/main/java/dev/chyken/assembl_ar/vision/Phase5ReferenceMatcher.java
app/src/main/java/dev/chyken/assembl_ar/vision/Phase7ReferenceMatcher.java
app/src/main/java/dev/chyken/assembl_ar/vision/Phase8ReferenceMatcher.java
app/src/main/java/dev/chyken/assembl_ar/vision/Phase9ReferenceMatcher.java
app/src/main/java/dev/chyken/assembl_ar/vision/PoseSmoothing.java
app/src/main/java/dev/chyken/assembl_ar/vision/RecognitionResult.java
app/src/main/java/dev/chyken/assembl_ar/vision/ReferenceCacheIndex.java
app/src/main/java/dev/chyken/assembl_ar/vision/ReferenceDatabase.java
app/src/main/java/dev/chyken/assembl_ar/vision/VisionPreloadManager.java
app/src/main/java/dev/chyken/assembl_ar/workflow/PartRecognitionTestSession.java
app/src/main/java/dev/chyken/assembl_ar/workflow/phase/Phase2MotorBracketFlow.java
app/src/main/res/layout/activity_assembly.xml
app/src/main/res/layout/activity_part_recognition_test.xml
app/src/main/res/values/diagnostic_ids.xml
app/src/main/res/xml/trial_log_paths.xml
app/src/test/java/dev/chyken/assembl_ar/camera/Phase2AssetCatalogTest.java
app/src/test/java/dev/chyken/assembl_ar/camera/UnifiedReferenceCatalogTest.java
app/src/test/java/dev/chyken/assembl_ar/diagnostics/AcademicBenchmarkRunnerTest.java
app/src/test/java/dev/chyken/assembl_ar/vision/Phase2ObservationTest.java
app/src/test/java/dev/chyken/assembl_ar/vision/PoseSmoothingTest.java
app/src/test/java/dev/chyken/assembl_ar/workflow/PartRecognitionTestSessionTest.java
app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase2MotorBracketFlowTest.java
docs/DIAGNOSTIC_TRACKING_OVERHAUL.md
tools/run_host_tests.py
tools/validate_phase2_precision.py
```
