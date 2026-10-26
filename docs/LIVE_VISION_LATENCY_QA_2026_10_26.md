# Live vision latency changes — 26 October 2026

This update follows the hybrid-vision implementation described in `HYBRID_VISION_QA_2026_10_26.md`. That earlier report records the former delayed recovery schedule; the camera scheduling described here supersedes it.

## Scope and safeguards

The changes affect the shared camera analyzer used by assembly and the individual tester. They do not change phase navigation, completion/checklist requirements, checkpoints, database schemas, or saved progress. `OrbMatchPolicy`, the workflow acceptance rules in `VerifiedScanEvidence`, optical-flow and dynamic-outline implementations retain their existing verification rules.

“1–2 targets” means required physical identities, including their positive, negative-orientation and necessary mounting-context evidence. It does not mean discarding all but two photographs or skipping the second tracker/wheel. A selected scan no longer performs an unrelated global-identity sweep. The global registry remains available to unscoped discovery; orientation rejection is still local to the selected target's evidence bank.

## Processing changes

- `AdaptiveIlluminationPolicy`: structural descriptors are eligible on the first camera frame. Specialized verifiers choose the lighting-appropriate representation first; a miss can use adaptive detail and the other representation within that same frame, without a failed-frame counter. Verified results exit early. Successful structural input remains preferred for subsequent confirmation frames.
- `AssemblyVisionAnalyzer`: removes the additional 60–750 ms post-analysis backoff and reduces the detector admission interval from 150 to 33 ms. Only one detached detector frame can be admitted at a time. The camera/tracker path remains independent, and lifecycle epochs discard obsolete evidence.
- `EdgeGradientDescriptorExtractor.Workspace`: detector-owned native buffers normalize and sample at a maximum 640-pixel long edge. The fast pass uses at most 300 descriptors per crop. Installed scenes use four overlapping spatial crops (up to 1,200 descriptors before spatial deduplication), so one textured PCB region cannot consume the entire budget. When a specialized verifier requires an additional detail crop, its budget can rise to 1,200, bounded by the original extractor configuration. The user explicitly approved this adaptive exception after a strict 300-row limit regressed recorded mounted views. The caller's ORB configuration is restored afterward. Mat buffers are reused, then released under the detector ownership lock on pause/close. Reference preprocessing and versioned descriptor caches are unchanged.
- Normalized low-resolution pixels are used directly for extraction to avoid an upsample/downsample blur cycle. Keypoint positions and sizes are mapped back to the caller's original image coordinates before homography, pose, telemetry and outline projection. Full-frame camera calibration is retained.
- `ScopedReferenceSelector`: small prebuilt descriptor probes rank only the requested identity and explicitly required anchor. A preferred photograph and rotating alternative retain viewpoint coverage. Probe scores schedule work; they cannot verify a candidate or own diagnostic telemetry. Existing specialized verifiers retain separate negative, body and board-context searches.
- `ReferenceDatabase`: selected individual banks exclude unrelated identities. Specialized camera recovery retains the existing luminance/inverted reference variants and the separate structural representation. Actual bottom-face/reversed physical reference photographs are not removed.
- `DiagnosticHammingMatcher`: records the representation of each geometric fit so a different probe/profile cannot own the winning metrics. Frame completion releases thread-local references to detector workspaces; context fits are materialized before accessing their geometry.
- Adaptive extraction replaces telemetry's live descriptor owner when it switches from fast to detailed sampling. This prevents the fast pass's smaller live-keypoint count from being attached to a winning detailed fit.
- `Phase9ReferenceMatcher`: a fresh first wheel match may receive exactly one further bounded crop if pool preparation consumed the initial deadline. A partial match retains only its source-photo preference, never a count or pose. Two-wheel completion still requires two distinct physical instances in the same current frame.

The 300-keypoint budget is the fast per-crop budget, not a cap on all component evidence in a scene. The approved adaptive detail crop can contain up to 1,200 keypoints. If that still lacks sufficient independent body/context matches, a specialized verifier retains its original detail extraction and sampling configuration (up to 3,200 scene points). A successful detail profile is retained for the current scope rather than repeating unsuccessful low-detail work on each confirmation frame. This restores small mounted-Nano/sensor cases that a universal cap regressed, at a measured latency cost. A verifier requiring two independently observed trackers plus chassis context can also perform multiple crops and legitimately report more than 300 total extracted observations.

## Supplied Nano model

The owner supplied `arduinonano.onnx` and confirmed that its `Mega328p` class describes the chip. `OpenCvDnnRoiLocator` now accepts its YOLO `[1,5,8400]` output, letterboxes RGB pixels to its fixed 640×640 input, reverses the padding correctly, and expands the chip anchor to propose a board search. This ROI is consumed by generic extraction and the Phase 5 body matcher. Neural confidence never becomes an ORB ratio or a successful placement check. A complete Nano outline still comes from verified photo geometry.

Model loading happens on the existing Nano bank-loader thread. Other selected components do not run inference or allocate RGB for this locator. Nano acquisition queries are limited to once per second, stop during verified/wrong-orientation photo lock, and do not reuse stale boxes. **Benchmark & logging → Experimental Nano chip detector (ONNX)** is a persistent comparison switch, enabled for the supplied-model trial. Disabling it leaves ORB/geometry active and stops future inference. Enabling it uses the existing reference worker to load the model. A missing/incompatible detector falls back to the existing pipeline. Initial native inference was about 400 ms warm in isolation and slower under the combined workload, so it does not meet a universal 100 ms requirement. See `app/src/main/assets/models/README.md` for provenance and model-specific limits.

## Measurement protocol

Use tablet `2603ARP14G` (ADB serial `368e278b`), real retained video frames and the candidate-code replay harness. The learner APK is not uninstalled and its data is not cleared. Instrumentation runs are isolated from the live camera UI.

The Main Board timing fixture cycles three different recorded frames, preventing repeated-image feature-cache hits from standing in for live extraction. RGB-to-YUV conversion used only to construct test input is excluded; CameraX already supplies YUV. First-call latency and warm timings are recorded separately. These measurements include analyzer processing/callbacks but do not measure camera sensor exposure or display presentation latency.

There is no defensible guarantee of correct recognition in every possible room within 100 ms: saturation, blur, occlusion, missing parts and ambiguous geometry must still fail or require additional evidence. Localization timing is also separate from the unchanged consecutive-frame and physical-checklist completion requirements. Hardware results and any remaining exceptions must be reported rather than replaced with an artificial deadline or weaker thresholds.

## Reproduction

From the repository root containing `gradlew.bat`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python -B tools/run_host_tests.py
python -B tools/prepare_candidate_replay.py
adb -s 368e278b push app/build/tmp/tablet-audit/candidate-code.zip /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run_quality_replay.ps1 -Serial 368e278b -Suite latency
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run_quality_replay.ps1 -Serial 368e278b -Suite all
adb -s 368e278b logcat -d -s LiveLatency:I '*:S'
```

The previously authorized separate instrumentation package must be installed for candidate replay. Update the learner app through Android Studio without clearing its data for final live camera/memory measurements. Do not replace the candidate archive while a replay group is running.

## Results

Build and all **737 host tests** passed. The initial full native sweep passed 82/83 groups and exposed a paired-wheel scheduling failure. After correction, all seven focused model/latency groups and six final regression runs passed, including two reruns of the wheel group. Consolidated coverage is **86 distinct native groups with passing results across these runs**, not one clean 86-group full-sweep invocation. See [command results and measurements](LIVE_VISION_LATENCY_TEST_RESULTS_2026_10_26.txt) and [case-level provenance](LIVE_VISION_NATIVE_CASES_2026_10_26.json).

The diverse-frame Main Board replay localized on frame 1, with latest warm median **90.30 ms** and P95 **197.54 ms**. Isolated warm model inference was approximately **400 ms**, with slower results under combined workload. The universal under-100 ms target has **not** been achieved; mounted adaptive-detail verification can take seconds. Camera sensor/display latency and a sustained live camera-plus-model run require checking the updated learner app after deployment.

The supplied model is bundled byte-for-byte in app assets, so deleting the Downloads copy does not remove it from the app. No main-app install, data wipe or progress reset was performed. Progress and database hashes remained identical.

## Changed implementation areas

| Files | Purpose |
| --- | --- |
| `AdaptiveIlluminationPolicy`, `LiveVisionPolicy`, `AssemblyVisionAnalyzer` | Immediate structural eligibility, bounded frame admission, detector ownership/lifecycle guards and Nano-only model cadence |
| `EdgeGradientDescriptorExtractor`, `DiagnosticHammingMatcher` | Reusable native workspaces, fast/adaptive extraction, coordinate restoration and correct telemetry ownership |
| `ScopedReferenceSelector`, `ComponentRecognizer`, `ReferenceDatabase`, `ActiveReferenceScope` | Selected-identity scheduling, smaller individual banks and retained original detail when small mounted parts need it |
| Phase 2/3/5/8 matchers and `Phase1011ReferenceSearch` | Shared representation selection and bounded photo scheduling without weaker geometry gates |
| `Phase5ReferenceMatcher`, `Phase9ReferenceMatcher` | Chip-guided Nano body sampling; bounded fresh second-wheel verification |
| `OpenCvDnnRoiLocator`, `DnnRoiDecoder`, model assets | Native supplied-model import, explicit YOLO/letterbox contract and crop proposals |
| `VisionPreloadManager`, `PreferencesManager`, `DevDiagnosticsFragment` | Background Nano-only network preparation and persistent experiment switch |
| Host/native tests, `run_quality_replay.ps1`, `prepare_candidate_replay.py`, `inspect_onnx_model.py` | Decoder arithmetic, real-pixel/model replay, memory/ownership checks and repeatable model inspection |

The phase workflow controllers, completion/checklist state machines, `OrbMatchPolicy`, `OpticalFlowTrackerBridge`, `TargetBoundingTracker` and `DynamicComponentOutlineOverlayView` were not changed by this latency/model work.
