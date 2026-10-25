# Verified telemetry and diagnostic UI — implementation and QA

26 October 2026

## Result

An accepted spatial scan now owns its diagnostic candidate and verification source even when it uses a specialized phase matcher or geometry fallback. Assembly completion latches the exact winning frame's immutable telemetry. The standalone tester uses the same formatter and colors while remaining a continuous diagnostic camera.

This follows the approved distinction between **workflow verification** and **academic measurements**: shape confidence is not an ORB match percentage, and successful 2D geometry is not automatically a calibrated 3D pose. Unknown metrics remain unmeasured.

## Modified and added production files

| File | Change |
| --- | --- |
| `diagnostics/DiagnosticScanTelemetry.java` | Adds verified workflow status, source and bounded verifier confidence independently of raw ORB counts/percentage and measured 3D values. Preserves the 15-line HUD order. Unavailable pose says Unmeasured. |
| `vision/VerifiedScanEvidence.java` | Adds a read-only adapter over existing validation/phase-observation decisions, including Phase 7's composite caster/tracker target. Its RANSAC, PnP, Rodrigues and residual calculations are unchanged. |
| `diagnostics/DiagnosticSession.java` | Returns the exact frame sample directly, decorates accepted candidates with their actual source, and preserves measurements from the owned RANSAC fit. Optional checkerboard failure cannot erase core scan telemetry. Raw logging and benchmark collection continue independently of the HUD latch. |
| `vision/AssemblyVisionAnalyzer.java` | Delivers each workflow result together with its own immutable diagnostic sample; does not read back a possibly newer global sample. The dynamic outline is still delivered before workflow completion. A global probe cannot supply metrics for an already accepted local geometry candidate. |
| `diagnostics/DiagnosticTelemetryLocker.java` — new | Screen-owned synchronized streaming/frozen state keyed by logical substep and run token. Freezes only an explicitly verified frame after the controller confirms completion; rejects stale run tokens. Stores no native matrices, camera buffers or Activity reference. |
| `PhaseAssemblyController.java` | Exposes read-only logical telemetry scope and completion queries, including separate Phase 2 hardware viewpoints and Phase 7 scan completion before its hardware/stability check. No phase transition or scan acceptance rule is changed. |
| `AssemblyActivity.java` | Uses the paired sample, freezes after actual controller completion, retains the snapshot through normal pause/resume, and opens a new scope on navigation/rescan. Removes the legacy overlapping readout and its detector-cadence “FPS” calculation from the UI. Existing developer calibration logging remains available. |
| `PartRecognitionTestActivity.java` | Uses the paired sample and shared colored HUD; removes the plain-text singleton readback that could overwrite it. Clears stale telemetry on selection/new run and adds the in-app control guide. |
| `ui/DevDiagnosticsOverlayView.java` | Shared green verified/red mismatch renderer and scoped clearing. Retains elevated ordering without a large native shadow. |
| `diagnostics/DiagnosticSample.java` | Extends CSV from 36 to 39 columns: `workflow_verified`, `verification_source`, and `verifier_confidence`. Raw good/total, academic 85% gate, pose availability, RMS, TRE and center offset remain separate. |
| `DevDiagnosticsFragment.java` | Uses mint surfaces, charcoal text, themed buttons and logging switch consistent with the individual tester. Clarifies movement/logging instructions. Clearing benchmarks now confirms, stops collection and clears only in-memory benchmark data. |
| `diagnostics/DiagnosticHelpContent.java`, `ui/DiagnosticControlsHelp.java` — new | Shared current-build explanations of each diagnostic control, limits and stored data. |
| `res/layout/view_part_test_controls.xml`, `res/values/part_test_strings.xml` | Adds Diagnostic control guide, renames Benchmark / Settings to Benchmark & logging, Results to Recognition results, and Reset part to Reset test results. Instructions now cover both loose and assembled library targets. |

## Completion and lifecycle behavior

1. Each detection supplies its own timestamped sample; stale callbacks cannot borrow another frame's metrics.
2. Verified per-frame evidence can stream during a stability hold. It does not itself complete the workflow or bypass required quantities/checklists.
3. When the controller's scan gate completes, its paired verified snapshot freezes. Later successful or failed tracking frames cannot overwrite it or its recorded frame count.
4. Live dynamic outlines, raw session logging and tracking remain active. The latch does not hold a camera frame or native `Mat`.
5. Next/another logical substep clears the old scope. Explicit Rescan changes the run token and starts fresh telemetry. Normal pause/resume of the same completed scan retains its snapshot within the activity.
6. Developer bypasses do not invent verified camera evidence. The individual tester stays live so that later misses and movement remain observable.

The change does not serialize diagnostic snapshots as permanent learner progress; existing phase checkpoints and preferences remain the persistence authority.

## Test changes

- `DiagnosticScanTelemetryTest`: geometry verification with unknown ORB/3D values, failed academic ratio alongside a verified workflow, and invalid confidence handling.
- `DiagnosticTelemetryLockerTest` — new: exact snapshot retention, hold-before-completion behavior, stale/reordered callbacks, next/rescan, pause/resume and concurrent late frames.
- `vision/ScanSourceVerificationTest` — new: existing specialized phase decisions, wrong-phase/orientation rejection, both trackers plus caster, inward sensor and reversed Nano cases.
- `vision/VerifiedTelemetryReplayInstrumentedTest` — new: real held-out stacked-board views through the shared analyzer and two-frame gate; paired ownership; frozen metrics after tracking/misses; rescan; actual layouts using candidate classes/resources; green output and unknown measurements in both cameras.
- `AcademicBenchmarkRunnerTest`: validates the 39-column export boundary while retaining unknown values.
- `PhysicalValidationLayoutInstrumentedTest`: fixes isolated replay to load candidate resources rather than installed-app resource IDs. This is a test-harness correction; no production checklist layout was changed.
- `tools/run_quality_replay.ps1`: adds the two new native telemetry groups to the existing regression selection.

## Preservation and performance boundaries

`OrbMatchPolicy`, raw ratio thresholds, matcher ROI/morphology algorithms, reference photographs/manifests, phase acceptance thresholds, `OpticalFlowTrackerBridge`, and `DynamicComponentOutlineOverlayView` were not altered. The new verification adapter reports existing decisions; it does not grant a scan pass.

HUD state is one small immutable numeric snapshot per assembly camera screen. Its lock does not block the vision worker on UI operations. No reference image loading, descriptor compilation, additional camera session, per-frame file write, or native image retention was added by the latch or help UI. Diagnostic exports retain their optional serialized background writer.

The main application was not installed, uninstalled, reset or cleared during validation. Candidate code/resources were replayed via the already installed separate test runner. Progress-file hashes and binary provenance are recorded in the final verification record.

## Commands

Run from the Android project root (the `app` folder containing `gradlew.bat`):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python -B tools/run_host_tests.py
python -B tools/prepare_candidate_replay.py
adb -s 368e278b push app/build/tmp/tablet-audit/candidate-code.zip /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run_quality_replay.ps1 -Suite all
```

Use the existing project Python runtime if `python` is unavailable. The isolated runner must already be installed on the authorized tablet. Run native instrumentation serially and do not replace the candidate archive while it is running. Do not clear app data or run progress-resetting activity harnesses.

For normal deployment, use Android Studio Run with the existing application data retained.

## Verification record

Final execution outputs, binary provenance and progress preservation checks are recorded in `VERIFIED_TELEMETRY_TEST_RESULTS_2026_10_26.txt` after the regression run.

The user manual is `DIAGNOSTIC_CONTROLS_MANUAL_2026_10_26.md`; the same button explanations are available through Diagnostic control guide in the app.
