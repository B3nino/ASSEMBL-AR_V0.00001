# Hybrid vision implementation and QA — 26 October 2026

Historical implementation report: the later [live latency and supplied-model update](LIVE_VISION_LATENCY_QA_2026_10_26.md) replaces the delayed camera recovery schedule and the unbundled-detector contract described below. The original test measurements remain a record of that earlier build.

## Implemented processing

The existing CameraX/OpenCV/OpenGL camera and LK outline tracking remain in use. No Gradle dependency, production training image, phase state machine, progress schema or database migration was added.

`EdgeGradientDescriptorExtractor` computes local log-reflectance, smooths it, calculates Sobel X/Y gradients and normalizes their magnitude before ORB extraction. The same preprocessing is applied to camera and reference pixels. This is single-channel illumination recovery, not RGB log-chromaticity. Subtracting a local Gaussian log-intensity field removes slow spatial shading; subtracting only one global mean would shift brightness without removing a cast shadow. It cannot restore texture lost to saturation, motion blur, occlusion or deep sensor noise.

`ReferenceDatabase` precompiles one structural bank per decodable image reference, capped at 768 pixels and 600 keypoints, alongside the existing luminance/inverted banks. It preserves source-coordinate masks and maps resized keypoints back with separate X/Y scales. The EDGE bank has its own blob variant; it never shares a KNN candidate pool with luminance descriptors. Prepared copies own their native matrices, cache memory accounting includes the extra bank, and the existing bounded RAM limit remains unchanged. Existing version-2 luminance blobs and resolved geometry remain compatible. A provenance-keyed edge sidecar is compiled separately at startup and then reused. Compact-index version 2 includes the new inventory; this changes metadata without forcing expensive luminance/geometry re-extraction. Descriptor-only administrator registrations without retained images cannot acquire an edge bank from descriptors alone.

`AdaptiveIlluminationPolicy` offers structural recovery after two misses, at one in three consecutive failed attempts. A successful structural localization retains that profile for subsequent frames, allowing the existing multi-frame scan gate to complete. A miss or target/scope reset reopens recovery. This does not add another detector thread or frame queue.

Generic recognition tries structural recovery after a luminance miss for a scoped, single-component request. Phase 2/3/5/7/8/9/10/11 photo matchers select the structural bank during recovery iterations and retain their independent body/context, instance count, orientation and invalid-reference checks. Detail samplers use the same selected preprocessing. Phase 4 and 6 wiring screens do not acquire new scan requirements. Global-library wrong-item hints keep using luminance descriptors; an edge frame is not compared against the luminance global index.

`OrbMatchPolicy` remains unchanged: Lowe ratio < 0.75, best Hamming distance ≤ 64. Inlier, geometry, orientation, instance-count and physical-checklist gates were not lowered. Structural descriptors still require the existing geometric verification. Keypoint inventory includes the EDGE count, and telemetry reports the actual selected descriptor-pool count and its live keypoints rather than borrowing the luminance bank's count.

## Optional native DNN locator

`OpenCvDnnRoiLocator` uses the already bundled `org.opencv.dnn` CPU backend. Startup checks for `assets/models/roi_locator.json`; with no manifest/model it remains inactive. This was explicitly selected by the user because the repository contains OBJ models but no kit-trained ONNX detector.

The supported contract is an RGB input and SSD-style `[batch, class, score, xmin, ymin, xmax, ymax]` output with normalized coordinates. The manifest specifies preprocessing and component-ID labels. Input size is bounded to 96–416 pixels, confidence to at least 0.8, the model asset to 32 MiB, and returned detections to 256 rows. Output boxes must be finite, ordered and large enough for ORB patches. Corrupt model/output disables the optional locator rather than repeatedly retrying it.

On recovery iterations a locator box may propose a cropped structural search. Detected keypoints are translated back to full-frame coordinates before homography/pose/overlay processing. A DNN label or box never creates a successful scan, changes a rejection threshold or supplies pose telemetry. Missing proposals leave the existing photo/geometry paths available. The adapter creates no camera session or executor. OpenCV's Java `Net` has no public close/release method; one network is retained by a process owner rather than repeatedly allocating networks across Activities or frames.

No trained model is bundled, so **actual DNN inference accuracy, quantized-model support and performance have not been measured**. A future model needs a matching model card, labels/preprocessing, negative-view replay and tablet memory/latency validation before enabling it. Generic COCO classes are not suitable substitutes for kit identities.

Example manifest (a template, not an installed detector):

```json
{
  "enabled": false,
  "model": "models/neobot_roi.onnx",
  "output": "ssd_xyxy_normalized",
  "inputSize": 320,
  "scale": 0.00392156862745098,
  "meanRgb": [0, 0, 0],
  "confidence": 0.85,
  "labels": {
    "1": "dual_motor_driver",
    "2": "arduino_nano_microcontroller",
    "3": "ultrasonic_distance_sensor"
  }
}
```

Values must match the trained model; they are not universal defaults.

## Geometry consolidation and pose telemetry

`ModularShapeGeometryVerifier` dispatches existing rigid-body verifiers for brackets, TT gearbox/can pairs, driver PCB/terminals, Nano USB/PCB, ultrasonic paired eyes and line trackers. The existing guided bracket placement and pair-count logic remain required. It replaces duplicate dispatch code rather than running another shape detector on every frame. Loose standoff/caster/cable verifiers remain unchanged. Boards and OLED installation retain their existing photo/ROI checks; a rectangle alone cannot prove which board is present, OLED pin engagement or correct installation.

`VerifiedScanEvidence.coarsePose` allows a located fallback with a positive-depth `RecognitionResult.Pose` to expose that estimate. `DiagnosticSession` continues to prefer verified inlier PnP evidence. If only the fallback rectangle pose is available, the HUD shows `Correct Pose: Coarse PnP` and `Orientation: Coarse PnP (orientation unverified)`. It does not label an arbitrary silhouette as a measured correct orientation. Such trials have no correct-pose classification entry in benchmark B. ORB counts, projected/observed points, RMS and checkerboard TRE stay unmeasured unless actual evidence supplies them. Workflow verification and the immutable telemetry latch remain separate.

## Preservation and verification

The learner app is not installed, uninstalled or cleared by the isolated candidate replay. Saved phase checkpoints, preferences and SQLite are hashed before/after testing. Protected match policy, LK bridge, outline renderer, telemetry locker and standoff geometry source remain unchanged. `VerifiedScanEvidence` changes only pose ownership and selected-fit metric ownership; `workflowAccepted` is unchanged.

A first cold-cache trial exposed unnecessary full-bank invalidation and was deliberately stopped. The replacement migration reuses the existing luminance/geometry cache; a native regression verifies the original blob bytes and modification time remain unchanged, the sidecar is reused, and administrator registration still produces the original two photometric banks. Build and test results are recorded after execution in `HYBRID_VISION_TEST_RESULTS_2026_10_26.txt`. Test artifacts and console logs live under `app/build/tmp/qa-overhaul/hybrid-*`.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python -B tools/run_host_tests.py
python -B tools/prepare_candidate_replay.py
adb -s 368e278b push app/build/tmp/tablet-audit/candidate-code.zip /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run_quality_replay.ps1 -Suite all
```

The native suite includes retained real-video positive/negative views, tracking, lifecycle, telemetry and the new structural-cache, exposure stress, blank-frame, coarse-pose, structural-only phase, cache-migration/administrator and optional-cache-loss fixtures. Test-only exposure changes are applied to retained real photograph pixels, not added as production references or presented as new training data. Offline replay does not establish flawless performance in every unseen room; field testing remains necessary.

## Final results

| Check | Final result |
| --- | --- |
| Production and Android test build | BUILD SUCCESSFUL, 26 seconds |
| Host unit tests | 730 passed across 135 classes |
| Native tablet replay | All 79 named groups passed on the final candidate |
| Android lint | 0 errors, 186 warnings; warning count unchanged from before this task |
| Learner state | All four checked progress/preferences/SQLite hashes identical before and after |
| Matching/tracking protection | OrbMatchPolicy, LK bridge, target tracker, dynamic outline view, telemetry locker and standoff verifier unchanged |
| Final candidate archive SHA256 | `8bd13c55e679dbccbf12f6466d0370d955641a8a351930e6d50c4ea3f209eeff` |

The migration/index stress test prepared a 1,141-reference catalog in 274,228 ms (about 4 minutes 34 seconds) and restored its compact index in 1,066 ms. The final cached-bank/index stress test covered 1,150 registered references and 32 targets in 38,469 ms; compact-index restore took 1,198 ms. These are separately measured library operations, not whole-app launch or camera-frame timings. The first structural preparation is a significant one-time startup cost; subsequent scans load the prepared data. The full-bank stress fixture deliberately visits every bank, whereas normal startup can restore the compact index and warm the selected target window.

The complete final run includes the optional-cache-loss safeguard. If an optional structural sidecar disappears while the process is already warm, luminance scanning remains usable and the loader does not re-read source photos or perform ORB compilation during scanning. Startup can prepare the missing optional bank later. No learner-app install, uninstall or data clear was used for this QA.

See [complete command results, hashes and all 79 group names](HYBRID_VISION_TEST_RESULTS_2026_10_26.txt).

## Modified source map

| Files | Responsibility |
| --- | --- |
| `EdgeGradientDescriptorExtractor`, `IlluminationRecovery`, `AdaptiveIlluminationPolicy` | Symmetric structural input, local illumination normalization, bounded recovery and persistent winning profile |
| `ReferenceDatabase`, `AdminDescriptorBlobCodec`, `VisionPreloadManager` | Separate structural sidecars, original-bank reuse, ownership/accounting, administrator two-bank compatibility, optional model initialization |
| `KeypointInventoryManager`, `GlobalComponentRegistry` | EDGE inventory and versioned compact metadata; existing global recognition decisions unchanged |
| `OpenCvDnnRoiLocator` | Optional validated SSD-style ONNX ROI proposal adapter, CPU backend, no model bundled |
| `ComponentRecognizer`, `DiagnosticHammingMatcher`, `AssemblyVisionAnalyzer` | Structural matching selection, ROI-to-frame remapping, per-fit live/count/timing ownership and shared recovery routing |
| Phase 2/3/5/7/8/9/10/11 reference matchers and `Phase1011ReferenceSearch` | Select matching reference representation while retaining existing local/context and rejection gates |
| `ModularShapeGeometryVerifier`, `LoosePartShapeRecognizer` | Consolidated rigid-body geometry dispatch; no shape-only board/OLED installation shortcut |
| `VerifiedScanEvidence`, `DiagnosticSession`, `DiagnosticScanTelemetry` | Positive-depth fallback pose ownership, Coarse PnP labels and exclusion from measured orientation classifications |
| `HybridRecoveryPolicyTest`, `CoarsePoseTelemetryTest`, `HybridVisionInstrumentedTest` | Recovery cadence/stability, honest telemetry, exposure/blank tests, structural-only matching, cache reuse and missing-sidecar safety |
| `tools/run_quality_replay.ps1`, README and diagnostic controls manual | New fixture registration and current behavior/limits documentation |

No phase flow/checkpoint state machine was changed. Existing scan, orientation, multi-instance and physical checklist requirements remain required; the recovery profile stays active long enough to satisfy their original consecutive-frame gates.
