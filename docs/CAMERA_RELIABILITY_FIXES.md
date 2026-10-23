# Camera reliability fixes — October 24, 2026

## Recognition and truthful diagnostics

- Phase 2 top verification requires independent motor-body and bracket/shaft evidence on both sides. Side reference masks now exclude the surrounding PCB. Live RGB also checks material support inside the projected yellow TT housing regions; yellow color alone never verifies a motor. Bare-board negatives remain registered. Physical screw/nut tightness remains a mandatory hands-on check.
- `183 / 7590 = 2.4111%`. This is a Lowe pair yield, not a probability that the assembly is correct. The HUD explicitly separates **ORB 85% gate PASS/FAIL** from **Workflow VERIFIED/NOT VERIFIED**. The ratio and academic thresholds were not increased or lowered to obtain a pass.
- The strongest raw diagnostic probe can be a different reference/variant from the geometric winner. Its name, reference ID and keypoint count remain attached to the probe. Specialized weighted detector scores are not substituted for raw Good/Total.
- Feature preparation measures live normalization and ORB extraction. Comparison measures descriptor search, geometry measures homography/pose validation, and total verification measures the detector result path. CSV adds `geometry_ms`, `verification_ms`, `preprocessing`, and `orb_85_percent_gate`; existing columns remain in order. These times do not measure a person's wait from entering the screen or TTS/UI time.

## Tracking and performance

- `ImageOutlineTracker` supports photo-based component outlines without inventing a metric solvePnP pose. It checks forward/backward LK consistency and RANSAC support on intermediate camera frames, and catches delayed detector results up to the latest frame. Multiple mounted parts retain separate polygons while sharing plane motion. Tracking never completes a scan; fresh detector evidence is still required.
- Lost image tracking clears the green polygon. It does not display a screen-fixed success box. Existing metric pose tracking remains separate; 2D image outlines do not inflate pose/TRE benchmark coverage.
- Both the assembly camera and the individual test camera use this path. The individual tool now enables its searching indicator when testing starts.
- Specialized matchers use OpenCV's native Hamming top-two operation through flat `batchDistance` buffers, avoiding a Java/native match-wrapper allocation per descriptor row. Each caller retains its original ratio/distance thresholds. A native parity test compares these results with BFMatcher/DMatch output.
- Successful local component/board geometry no longer launches a second whole-library search and ORB extraction. Bounded global queries reuse the extracted live descriptors for wrong-item hints after local verification fails. An unrelated component on the same valid PCB must not veto the locally verified target.
- Phase 2 uses sparse descriptor ranking to schedule a bounded number of full geometric checks. Fresh primary candidates rotate through the bank so a high sparse score cannot starve valid references. Sparse votes never count as acceptance evidence.
- Detection runs on a detached worker with no frame queue backlog. Image tracking is capped at 20 Hz. Reference cache revisions are scoped to the affected phase. OpenCV's startup worker budget is two native threads.
- Idle AR rendering uses `RENDERMODE_WHEN_DIRTY`. Paused GL views release their EGL context; menu/component CPU meshes can be reused when the GL context returns.

## Lighting

- Lighting categories are estimated from live image exposure/tile statistics automatically. They are estimates, not measured lux or user declarations.
- Normal CLAHE remains the first path. Failed detections alternate with a low-frequency illumination recovery path; a successful path is preferred on subsequent frames. Geometry and rejection thresholds remain unchanged, as do the raw captured images and reference descriptor format.
- A completely obscured, blurred, overexposed, or very small component still cannot provide reliable evidence. No claim is made that every room or hidden fastener can be verified visually.

## Flow, UI and crash handling

- Phase 9 begins with the standoff/nut/screwdriver preparation card. The loose standoff scan is removed; installation scanning and physical checks remain. Old ten-screen snapshots migrate to nine screens without discarding the later evidence. Review navigation and the prefetch route use the revised screen/bank counts. The loose standoff remains available to diagnostic tools.
- Welcome/checklist dialogs use an explicit light surface with dark copy and a visible action. Delayed dialog show/dismiss callbacks check ownership so a dismissed/replaced dialog cannot dereference or clear a different dialog. A tablet log contained a historical Phase 3 `getButton()` null-pointer crash in this path.
- Assembly diagnostics now offer **Capture current scan frame**. Queue it, return to the scanning camera, then export diagnostic logs with recorded frames. This saves raw grayscale/RGB and calibration in `.arpart.zip`, without drawing HUD graphics into the image. It works away from USB so a failing lighting setup can be replayed later.

## Reference photos

Use tablet camera captures as additional references, especially the actual 640×400 analysis view under the room lighting that fails. Also retain sharp higher-resolution photos so local component crops have useful detail. Include several distances/angles, clear USB/terminal orientation, both valid installations and distinct missing/reversed examples. Keep wire clutter realistic without fully hiding the component. Do not use HUD screenshots as raw references. Keep some captures held out from the training library so replay results remain meaningful.

## Verification commands

From this Gradle root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python tools/run_host_tests.py
python tools/validate_phase2_precision.py --opencv-path app/build/tmp/cv-test-deps --output app/build/tmp/phase2-material-parity.json
python tools/validate_phase3_placement.py --opencv-path app/build/tmp/cv-test-deps
```

The tablet model is **2603ARP14G**; its current ADB serial is **368e278b**. The other connected phone is not used. Install/update through Android Studio, preserving app data. The separate test APK can be updated using `adb -s 368e278b install -r -t …`.

Native regression classes include `ImageOutlineTrackerInstrumentedTest`, `Phase2PrecisionInstrumentedTest`, `PartTestLayoutInstrumentedTest`, and the existing Phase 5/7/8/9/10/11 matcher fixtures. Some replay methods require a captured frame argument and are not meaningful without one. Do not count assumptions/skipped methods as executed validation.

The test run output is retained under `app/build/tmp/tablet-audit/`. Desktop synthetic/photo results are not tablet timing measurements or certification of every live assembly/lighting condition.

## Measured live result and verification status

- The deployed 640×400 bare-board top-view scan stayed **NOT VERIFIED**, with **Next: Scan left side** disabled. In the latest 80 measured frames: median feature preparation **67.36 ms**, median comparison **74.56 ms**, median detector result **138.71 ms**, median total analysis **199.05 ms**. Total analysis p95 was **524.30 ms**, maximum **1784.81 ms**. One comparison outlier was **1600.54 ms**. These include the opt-in frame recording overhead and describe this bare-board scene only. The earlier recorded full sweep took **29899.3 ms**; this is not a promise of a fixed speedup in other scenes.
- Temporary `AssemblArMetrics` and `AssemblArCapture` properties were restored to their previous empty values after capture. No app uninstall, progress reset, or database restoration was performed. A progress backup is retained in `app/build/tmp/tablet-audit/progress-before-native-tests.tar`.
- Latest host run: **380 tests passed**. App and native-test APKs build successfully.
- Desktop Phase 2 photo/synthetic checks: **11/11 passed**; Phase 3 held-out/wire/lighting checks: **14/14 passed**. These do not establish recognition rates in untested physical rooms.
- Completed native checks include the standalone camera layout, Phase 2 bare/missing/rotated/dim hardware checks, and Phase 5/7/8/9 held-out fixtures. Five outline tests pass, including separate mounted-pair motion, delayed-result catch-up, occlusion clearing, Hamming-result parity, and 220 repeated scan/reset cycles. The latter observed approximately **436 KiB** native-heap growth; it is not an all-app endurance/thermal test.
- An initial tighter wheel budget failed the valid-pair fixture; it was restored to the previously validated 850 ms overall / 200 ms per-window budgets. A Phase 2 full-bank test also exposed reference starvation, which led to the rotating primary-candidate scheduler. Failed runs are retained, not counted as passing results.
- The optional exhaustive Phase 10 run was manually stopped; its “Process crashed” instrumentation footer reflects that stop. It is not counted as a completed regression or an observed product crash. Phase 11 was not revalidated in this pass.

The latest native candidate results are recorded separately in `app/build/tmp/tablet-audit/candidate-native-results.txt`. The candidate Phase 2 full-bank replay passed: left/right/top recognized after 9/6/5 current-frame attempts respectively, with longest single attempt **1560 ms**, and all three bare-board attempts rejected (**80–81 ms**). This measures bounded recognition plus retained rejection, not the 200 ms academic comparison target; some correct-assembly comparisons still exceed that target.

Candidate replay loads the exact DEX from the newly built APK in a test-only class loader. It uses the installed, unchanged reference assets and makes no learner progress edits; it is not a substitute for a final live UI deployment.

## Main files touched in this reliability pass

| Area | Files |
| --- | --- |
| Common frame scheduling, light recovery, ratios/timings | `vision/AssemblyVisionAnalyzer.java`, `DiagnosticHammingMatcher.java`, `IlluminationRecovery.java`, `ComponentRecognizer.java`, `GlobalComponentRegistry.java`, `ReferenceDatabase.java`, `ReferenceCacheIndex.java`, `VisionPreloadManager.java`; specialized Phase 2/3/5/7/8/9/10/11 matchers |
| Component outlines and individual tool | `vision/ImageOutlineTracker.java`, `ui/ArHudView.java`, `AssemblyActivity.java`, `PartRecognitionTestActivity.java`, `PhaseAssemblyController.java` |
| Honest HUD/export and automatic light estimate | `diagnostics/DiagnosticSample.java`, `DiagnosticSession.java`, `LightingEstimator.java`, `DevDiagnosticsFragment.java`, `vision/RecognitionResult.java` |
| Strict motor evidence and bounded scheduling | `vision/Phase2PhotoGeometry.java`, `Phase2ReferenceMatcher.java`, `RecognitionRequest.java`, `Phase2GuideController.java` |
| Retired loose standoff scan / saved-state migration | `workflow/phase/Phase9StandoffWheelsFlow.java`, `PhaseGuideMetadata.java`, `Phase9GuideController.java`, `camera/ActiveReferenceScope.java` |
| Readable dialogs and lifecycle guards | `WelcomeAssemblyDialog.java`, `ui/PhysicalCheckUi.java`, Phase 2–11 guide-controller dialog callbacks |
| Idle/paused GPU work | `render/ArGlSurfaceView.java`, `HomeRobotSurfaceView.java`, `HomeRobotRenderer.java` |
| Native and host regressions | `ImageOutlineTrackerInstrumentedTest.java`, `Phase2PrecisionInstrumentedTest.java`, `CandidateCodeReplayInstrumentedTest.java`, lighting/metrics/flow/cache host tests |

No new layout XML or reference photograph was needed for these last shared reliability changes. Existing individual-tool layout fixes and previously added Phase 2 bare-board / Phase 3 wire-perspective references are retained. This pass does not certify physical screw tightness, the other room's live lighting, metric checkerboard/TRE benchmarks, or an hours-long thermal/endurance run.

Final candidate-code native replay **passed both requested matcher fixtures**. Phase 9 retained two consecutive complete-frame recognitions for valid wheel pairs and installed assemblies across the fixture rotations; all 108 missing-wheel comparisons remained rejected. The Phase 9 fixture completed in 124.4 seconds (many individual trials, not a single scan). Tests retain the real durations rather than claiming the 200 ms academic target was met.

To reproduce the code-only native checks after building (with identical installed reference assets):

```powershell
python tools/prepare_candidate_replay.py
adb -s 368e278b install -r -t app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 368e278b push app/build/tmp/tablet-audit/candidate-code.zip /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip
adb -s 368e278b shell am instrument -w -r -e class dev.chyken.assembl_ar.CandidateCodeReplayInstrumentedTest -e candidateDex /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip -e candidateCases 'vision.Phase2PrecisionInstrumentedTest#boundedLiveSearchHandlesFullCatalogWithoutOneLongSweep,vision.Phase9ReferenceMatcherInstrumentedTest#heldOutCameraSizedViewsRequireBothLocalWheelsAndKeepOrientationInvariance' dev.chyken.assembl_ar.test/androidx.test.runner.AndroidJUnitRunner
```

The last fair-scheduling correction and restored wheel budgets are built and native-replayed, but require the usual Android Studio deployment to become the running app. Only the separate test package was updated over ADB. No further application deployment was requested during these final native checks.
