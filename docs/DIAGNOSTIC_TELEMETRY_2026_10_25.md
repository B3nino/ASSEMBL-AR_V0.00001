# Diagnostic telemetry overhaul — 25 October 2026

Implemented the requested mathematical telemetry/HUD changes in the current checkout. Existing assembly checkpoints, physical verification gates, dynamic outlines, optical-flow persistence, reference assets and bounded descriptor caches remain in place. The main application was not installed over the user's Android Studio deployment and assembly progress was not reset.

## Runtime measurements

| Field | Source and meaning |
| --- | --- |
| Candidate | The actual selected, spatially verified RANSAC fit. A shortlist/probe score cannot name the candidate. A certain global-registry RANSAC result may identify an incorrect item; ambiguous global results remain unverified. |
| Active keypoints | Exact NORMALIZED reference count from `KeypointInventoryManager` for the selected reference file, not a sum across the catalog or a feature budget. |
| Live keypoints | Unique coordinate pairs from the primary full-frame ORB extraction. Extra crop/recovery/scale passes are not added to inflate this count. |
| Good matches | Deduplicated train-index correspondences from the selected reference/ROI query, satisfying `d1 < 0.75*d2` and `d1 <= 64`. Duplicate claims on the same live feature retain the closest descriptor. |
| Total matches | Number of query rows attempted in that selected BF/Hamming two-nearest-neighbor search. This is not the number of all pairwise Hamming distance evaluations, the RANSAC inlier count, or the sum of other reference probes. A cropped query can therefore be smaller than the full reference keypoint inventory. |
| Match percentage | `100 * good / total`, with no positive denominator represented as unavailable. This is descriptor-pair yield, not statistical classification accuracy. |
| Extraction time | Monotonic timing of camera Y-plane preparation/rotation, illumination preprocessing and actual ORB extraction operations on the detector worker. Identical-pixel cache reuse does not invent an extraction cost. |
| Matching time | Timed reference comparisons/ratio filtering/deduplication plus geometric RANSAC and pose operations. Includes real work on competing references during the analyzed scan; excludes UI rendering, startup reference compilation and wait time for user controls. |
| Total frames | Atomic cumulative count of analyzed detector frames, not display FPS or every delivered optical-flow/camera frame. |

All production, recovery and scheduling match paths now share `OrbMatchPolicy`. `VisionConfig.Builder.matching` rejects attempts to override the 0.75/64 policy; the minimum match count remains configurable. The allocation-light `Core.batchDistance` backend is retained and checked against native BFMatcher/Hamming two-neighbor results.

The 85% diagnostic percentage gate is implemented exactly as requested. `Output: Match` requires a verified candidate, percentage >=85%, and correct measured pose. A low-yield geometric match can still satisfy the existing assembly scan requirements while the diagnostic output says Mismatch. These are deliberately separate decisions: no academic percentage is coerced to PASS, and no diagnostic field bypasses camera verification or a mandatory physical checklist.

## Pose and three distinct errors

`VerifiedScanEvidence` uses the selected RANSAC inlier correspondences, the authored physical reference plane/dimensions and the current camera calibration to run `solvePnP`. It checks positive depth, finite values and the existing solver residual limit. It does not manufacture a tracker pose from a mainboard fit whose physical dimensions belong to a different component.

- `Rodrigues(rvec)` produces the rotation matrix. Orientation uses `R_actual * transpose(R_expected_front)`, with the existing model +Y-up / camera +Y-down convention. Nominal front is Rx(180°), not an upside-down physical board. Independent fixed-board context supplies the expected frame when available. Signed yaw distinguishes Right and Left; backward angles wrap correctly at +/-180°. Image-quad classification is only the fallback when no valid 3D fit is available.
- Solver RMS: `sqrt(sum(||projectPoints(X_i) - observed_i||^2) / n)` over the selected pose correspondences. This is `reprojectionRmsPx`, exported separately.
- Center-to-inlier-centroid distance: the model origin is projected with `Calib3d.projectPoints`; the observed point is the measured inlier centroid. Their Euclidean 2D distance is `pixelDistance`. They are different physical locations, so this value need not be zero for a correct pose and is not solver RMS.
- Checkerboard TRE: Euclidean 3D distance from the component's solvePnP translation/model origin to an independently measured checkerboard target transformed into camera coordinates. Millimeters are available only with physical grid measurements and Camera2 metric intrinsics. Checkerboard pixel anchor error is a separate export field. The target origin is projected; it is not approximated by averaging the four image corners.

As approved, unavailable TRE, points and RMS remain NaN internally, appear as `Unmeasured` in the HUD and blank numeric CSV cells. They do not become perfect zero-error measurements. Planar-photo pose remains dependent on the authored physical geometry; this change does not turn photographs into an independently calibrated 3D ground-truth dataset.

## Shared HUD and data flow

`DiagnosticScanTelemetry` is an immutable snapshot, including immutable point values. The background analyzer binds final candidate evidence, then `DiagnosticSession` records it. Both `AssemblyActivity` and `PartRecognitionTestActivity` pass that same model to `DevDiagnosticsOverlayView` on the UI thread with existing lifecycle/generation guards.

The requested 15-field line order and wording are retained, including `% TRE` with millimeter units. RMS is present in exports rather than adding a sixteenth HUD field. Output Match is #00FF7F and Mismatch is #FF3B30. Long labels can wrap without clipping the final Output line. Elevation is 999dp; costly elevation shadows remain disabled, preserving the prior tablet graphics-memory fix.

Visual-only post-success localization refreshes telemetry and outlines without adding another academic trial or changing workflow completion. CSV now has 36 fields, keeping workflow outcome and diagnostic output distinct and storing projected/observed points, center-centroid distance, solver RMS and checkerboard anchor error separately.

## Changed source files

New core classes:

- `diagnostics/DiagnosticScanTelemetry.java`
- `vision/OrbMatchPolicy.java`
- `vision/PoseOrientation.java`
- `vision/VerifiedScanEvidence.java`

Updated shared collection and presentation:

- `vision/DiagnosticHammingMatcher.java`, `AssemblyVisionAnalyzer.java`, `ComponentRecognizer.java`, `RecognitionResult.java`, `CameraCalibration.java`, `VisionConfig.java`
- `diagnostics/DiagnosticSession.java`, `DiagnosticSample.java`, `CheckerboardGroundTruth.java`, `AcademicBenchmarkRunner.java`
- `ui/DevDiagnosticsOverlayView.java`, `AssemblyActivity.java`, `PartRecognitionTestActivity.java`
- Default and landscape `activity_part_recognition_test.xml`; the existing assembly HUD already uses 999dp elevation.

Updated production consumers of the same match rule:

- `GlobalComponentRegistry.java`, `Phase1011ReferenceSearch.java`
- Phase 2, 3, 5, 7, 8, 9, 10 and 11 reference matchers

Phase builders no longer export placeholder perfect ratios. RANSAC captures use bounded primitive arrays during the recognition search and defer ROI/pose materialization until after that search, so logging does not consume the detector's search budget. Mat ownership stays local and released. The original-resolution caster detail fallback runs before enlargement; it restores a recorded caster angle under the stricter policy without relaxing identity or geometry checks.

New host tests cover exact HUD text/order, unavailable metrics, the 183/7590 example, separate pixel/RMS errors, all required gates, expected-front orientation, signed yaw and policy boundaries. Tablet tests cover selected-candidate ownership, true pose/projected origin, independent checkerboard TRE, both HUD states, real caster/sensor reference counts and certain versus ambiguous global identities.

## Verification

Final command outputs and the recorded-view limitation are in `DIAGNOSTIC_TELEMETRY_VERIFICATION_2026_10_25.txt` beside this report.

Reproduce from the Gradle root (the `app` directory containing `gradlew.bat`):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python tools/run_host_tests.py
```

Native replay uses `tools/prepare_candidate_replay.py`, pushes the generated candidate archive to the authorized tablet's application external-files directory, and invokes `CandidateCodeReplayInstrumentedTest` from the already-authorized separate test package. It runs new code without installing the main APK or resetting progress. The test classes are `vision.DiagnosticTelemetryInstrumentedTest`, `vision.GlobalOutlineRegistryInstrumentedTest`, and the existing `tools/run_quality_replay.ps1` groups. The native BF/Hamming equivalence method is `vision.ImageOutlineTrackerInstrumentedTest#allocationLightRatioProbeMatchesNativeDMatchValues`.

Deploy the main app through Android Studio, keeping app data. No app installation was performed as part of this change.
