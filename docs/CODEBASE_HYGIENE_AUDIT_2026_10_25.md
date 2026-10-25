# ASSEMBL-AR codebase hygiene audit — 25 October 2026

**Subsequent ROI follow-up:** `TARGETED_ROI_FIX_2026_10_25.md` resolves the three recognition failures below and records 48/48 native passes on a newer candidate. This audit retains the original candidate's 45/48 result as historical evidence.

This audit follows the diagnostic telemetry changes already present in this checkout. It preserves the phase checkpoints, database schema, preferences, reviewed reference pixels, matching thresholds, and dynamic/optical-flow overlay behavior. It does not install or clear the main application.

## Verification ledger

Final native results and the candidate archive hash are recorded in `CODEBASE_HYGIENE_VERIFICATION_2026_10_25.txt`. Machine-readable inventories and individual native outputs are under `app/build/tmp/qa-overhaul/`.

- Full host discovery: **130 classes, 702 tests**, all passing. The runner previously selected only 84 classes; 46 existing classes were omitted.
- Debug application and Android-test APK build: successful.
- Android lint: **0 errors, 193 warnings**. These are retained findings, principally UI/internationalization/style issues; this is not a warning-free lint result. Its one unused-resource report is `parts_test_standalone_app_name`, which is actually referenced by the `partsTest` variant manifest.
- Final native selection: **48 named groups, 45 pass, 3 fail**, all on candidate SHA256 `2dae67d7d389aa201f4d45a9be94d9c621789e3d2fcb7d0a551e7eead28ba510` on tablet 2603ARP14G. All nine new lifecycle/ownership/loop safety regressions pass.
- **Full recognition sign-off remains blocked.** Positive-view failures are the blurred Phase 8 outward assembly frame at **75 seconds**, Phase 10 protector assemblies at **159/190 seconds**, and the Phase 11 mounted OLED at **83 seconds**. Missing/inward/occluded rejection assertions in those groups did not fail. These positive assertions remain active; no test was excluded or threshold relaxed to turn the report green. The latter two gaps were exposed by the added installed-assembly coverage. Their precise matcher-gate cause is not established by this hygiene audit.
- Wiring-only Phase 4/6 transitions and checkpoint behavior are covered by the full host suite; this audit does not invent additional camera requirements for those screens. The 48 named native groups are a documented regression selection, not a claim that every Android instrumentation method in the repository ran.

## Threading, loop, and navigation findings

| Finding | Correction / verified behavior |
|---|---|
| A batch submission or iterator could fail after earlier native readers had started. The caller could then release their matrices too early. | `OrderedMatchingExecutor.compute()` drains every accepted future before propagating submission/task/interruption failures. A host regression deliberately fails the second submission while the first reader is blocked. |
| Camera provider/metadata/plane failures could occur outside complete frame ownership cleanup. | `AssemblyVisionAnalyzer.analyze()` closes the source `ImageProxy` in one outer `finally`. Detached frames transfer ownership only after successful submission; rejected work clears admission and releases its frame. |
| Pausing from an outline callback could still deliver the next tracking callback from the same old frame. | Epoch/closed/pause checks surround tracking delivery and request setup. Obsolete work cannot publish recognition or tracking evidence. |
| UI-thread close could clear detector-owned global objects concurrently with a detector reader. Direct deterministic replay could also overlap queued cleanup. | Final native cleanup is queued after detector work. A resource ownership guard covers both CameraX detection and direct replay; competing frame delivery drops rather than waiting. Cleanup waits on the background executor. UI close does not wait for native recognition. |
| Rapid pause/resume could accumulate obsolete cleanup/IMU-attach jobs. | Cleanup and attach jobs check their lifecycle epoch. Stale jobs cannot clear or reopen the current session. |
| Independently published gyro coordinates/timestamps could describe different sensor samples. Late callbacks could survive a pause. | `OpticalFlowTrackerBridge` publishes one immutable sample and checks the active sensor-session timestamp. Pause/close clear the sample and unregister motion callbacks. |
| An unexpected cyclic contour hierarchy could loop forever or count repeated mounting holes. | `MotorBracketShapeRecognizer.children()` bounds traversal by contour count and fails closed on malformed/cyclic chains. |
| Phase changes could race asynchronous progress saving. | `PhaseAssemblyController` refuses phase entry/review while saving or after Activity disposal, and ignores completion callbacks for another phase. |

The source inventory reports **44 lexical loop sites**. They include finite file/ZIP reads, advancing SQL cursors, descriptor batches, bounded instance searches, image downsampling, string truncation, queue eviction, and the simulator's decreasing time budget. One site is a C++ `while(true)` emitted into exported robot code, not an Android loop. The contour chain above was the untrusted link traversal needing an explicit bound.

The `Future.get()` retry loop is deliberate: interrupts are remembered, all already-running native readers drain, and the interrupt is restored afterward. Cancelling a Java future does not prove that C++ stopped reading its input. There is no claimed Java-level guarantee against an arbitrary hang inside third-party native OpenCV; existing bounded image sizes, batch sizes, search deadlines, and native replay are the practical controls.

All current phase flow implementations and `PhaseFlow` were inspected. They use finite mode transitions and generation/time guards rather than recursive transition listeners. Video closure ignores released callbacks; chapter flags are set before rendering/starting playback. The shared navigation helper remains a guard, not another state machine. Host tests cover phase transitions, explicit preview actions, stale evidence, rescans, checkpoint restoration, and developer navigation.

## Native ownership and allocation reductions

- `ImageOutlineTracker`: reuses previous/latest image storage, point matrices, forward/backward LK outputs, status/error matrices, and packed primitive arrays. Reset/close release the reusable workspace. Returned homographies still have explicit per-call `finally` cleanup.
- The 3,000-frame tablet replay checks stable native image addresses, stable workspace/array identities, continued LK output, and empty native owners after reset. The final measured run completed in **16,691.6 ms** (about 5.56 ms per combined LK/bridge replay iteration). This is a fixed-frame native microbenchmark, not a claim of sustained camera/UI FPS or a multi-hour whole-app memory profile.
- `OpticalFlowTrackerBridge`: reuses its latest grayscale buffer and releases it on pause/close. The existing visual-only persistence and expiry rules remain unchanged.
- `MeasurementRecorder`: transfers a converted frame only after the writer accepts it. Conversion failures, executor rejection, directory failures, and write failures release matrices and restore pending-write admission. Close is idempotent; accepted writes drain.
- Phase 3/5/8/11 homography helpers now release a computed matrix if a later operation throws before returning it to the caller.
- Phase feature partitions, descriptor pools, local masks, and Phase 10/11 descriptor samplers release partially constructed matrices if publication fails. Successful cache publication retains ownership until the existing cache eviction/close path.
- Shared descriptor concatenation now has one publication/ownership helper in `AdminDescriptorBlobCodec`, used by the pyramid and Phase 5/9/11/reviewed-hardware samplers. A native regression rejects 100 publications and then verifies that successful output owns independent rows while borrowed inputs remain valid. Context preparation and independent-patch filtering also dispose unpublished variants on failure.
- `DiagnosticHammingMatcher.homography()` releases a native homography if telemetry capture fails before ownership transfer. Candidate binding filters component/reference identity before materializing inlier geometry for unrelated fits.

`ComponentRecognizer` recognition, inspection, camera-cache release, and close methods share its synchronized ownership boundary. Its retained descriptor batches are bounded and disposed on eviction/close. `TargetBoundingTracker` serializes morphology/flow/reset/close; its contour, hull, mask, kernel, and submatrix temporaries have `finally` release paths. `LoosePartShapeRecognizer` retains Java smoothing coordinates, not native frame matrices, and releases its per-call native image/pose intermediates.

Borrowed reference descriptor rows must not be released by consumers. Returned/cached matrices transfer ownership explicitly; mechanically putting every allocation in a local `finally` would invalidate active caches. The lexical allocation inventory is a review aid, **not** a mathematical proof that every possible native allocation or process-wide out-of-memory failure is leak-free.

## Telemetry cleanup and removed remnants

- Removed the obsolete `RecognitionResult.Builder.orbMetrics()` overloads and raw probe-count/ratio fields superseded by `VerifiedScanEvidence`.
- Removed redundant raw `RawCandidate` telemetry fields and constructors. Recognition ranking and physical verification gates are preserved.
- Removed `DiagnosticHammingMatcher.clear()`, which was a no-op, and its production/test callers. Per-frame `end()` remains responsible for the thread-local descriptor snapshot and fit list.
- Removed unused academic-runner local accumulators that no longer supplied Test C output.
- Seven offline diagnostic helpers now read the Java `OrbMatchPolicy` constants through `tools/orb_match_policy.py`, instead of duplicating historical 0.76/0.85 or Hamming-75 predicates. Its eight boundary checks pass.
- Production Lowe acceptance remains **d1 < 0.75 × d2 and d1 ≤ 64**, followed by deduplication and geometric verification. Phase matchers use the shared matcher/policy; geometry, spread, and physical completeness gates are separate conditions, not substituted diagnostic ratios.
- The requested **85%** diagnostic pair-yield gate remains tested. No counts are inflated, no ratio is forced to 1.0, and unavailable pose/TRE/point/RMS values remain unmeasured. Solver RMS, projected-center-to-inlier-centroid distance, and calibrated 3D TRE remain distinct.

## Conservative file/resource cleanup

The inventory covers **310 production Java files and 1,293 asset files**. It found zero production top-level filename/type candidates without any external source/test/manifest/tool references. This is a reachability screen, not a claim that every private method executes.

No production class, layout, reviewed reference image, OBJ/MTL texture, video, or descriptor cache was deleted merely because a literal text search did not find its filename. Assets are registered by directory traversal and runtime catalogs, and alternate manifests consume resources the main-variant lint cannot see. Six generated `.pyc` files were removed from `tools/__pycache__`; they were outside the APK and their removal is not claimed to reduce APK size.

The packaged source assets are dominated by 1,156 JPEGs (**2,478.3 MiB**), followed by 20 MP4s (18.7 MiB) and 61 PNGs (17.6 MiB). The large APK is principally reference-image storage. Re-encoding or shrinking those images would alter reviewed pixels, hashes, ROI sampling, and cache fingerprints, so this hygiene pass preserves them. Runtime improvements here concern bounded ownership and transient buffers rather than a claim that the APK itself became substantially smaller.

The individual tester's legacy compatibility adapter now filters caller input before merging registered reviewed entries, preventing contextual/unreviewed legacy entries from being reintroduced. The retained OLED-protector hardware adapter maps its authored 20 mm screw actions to `SCREW_20MM`. These are metadata/compatibility fixes, not new camera requirements.

Full test discovery also exposed historical expectations for the old preview normalization radius, old instruction text, and an upstream split-label manifest absent from this repository. The tests now exercise current bundled assets, uniqueness, image bounds, ROI geometry, reviewed source identity, and the 1.70 preview radius. They do not invent unavailable upstream provenance.

## Saved progress and deployment

No database migration, checkpoint serialization change, preference key change, app uninstall, data clear, or main-app installation is part of this audit. Native tests use the existing isolated candidate-code replay adapter. The production harness constructor that resets assembly state is not used; only its static image adapter is used by frame fixtures.

Before/after device-side hashes of all seven existing preference XMLs and the application database are **identical**. A full state export was rejected by automatic approval review; no preference/database contents were copied as a workaround.

For a normal update, run the existing app configuration in Android Studio with app data retained. Do not uninstall or use `pm clear`. A live deployment and extended user camera session remain separate from the isolated replay checks reported here.

## Reproduce

From this Gradle root (`ASSEMBLAR ls/app`):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python -B tools/run_host_tests.py
python -B tools/orb_match_policy.py
python -B tools/audit_codebase_hygiene.py
python -B tools/prepare_candidate_replay.py
adb -s 368e278b push app/build/tmp/tablet-audit/candidate-code.zip /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run_quality_replay.ps1 -Serial 368e278b -Suite all
```

The last command reports every named case and exits with failure if any group fails; `OK (1 test)` belongs to the isolated adapter invocation, not to the number of methods covered. Do not infer success from ADB's process exit code alone. Build/test-package compatibility is required; an already-installed compatible replay adapter was used on this tablet.
