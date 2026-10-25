# ASSEMBL-AR quality overhaul — 25 October 2026

## Agreed scope

- Prepare the whole reference library behind the existing startup logo, then retain a **bounded native RAM cache** and proactively load current/upcoming targets. Retaining every full descriptor bank in RAM was rejected because the compiled library is about 1 GB.
- Every **video** has an explicit Skip Preview action. Its destination is the required camera, preparation, wiring, or physical-check screen. Video completion/skip never supplies camera evidence or a physical confirmation.
- Keep the established 10-wire Phase 8 configuration, PCB-connected ultrasonic power/ground, standalone-standoff bypass, top-board battery placement, saved progress, and existing ORB/ROI/illumination/LK outlines.

## Changes

### Startup and cache ownership

`MainActivity`, new `SplashActivity`, `BrandLaunchOverlay`, `StartupRevealGate`, and new `StartupPreparationCoordinator` share a three-condition reveal gate: logo introduction, first robot frame, and reference preparation. The menu cannot be used before all conditions are ready. A restored camera Activity in a new process opens the recovery startup screen before attaching its camera. Failure/retry stays there and does not clear progress. Ordinary tab returns reuse the task-root Main Activity and process cache.

`VisionPreloadManager` continues preparation on its process-owned background executor. `GlobalComponentRegistry` now saves a small versioned, compressed shortlist with the **exact full keypoint inventory**. Its fingerprint includes every registered source/configuration/geometry key. Restore validates the complete file and gzip checksum before publishing; stale/corrupt caches fall back to normal preparation. Full descriptor bytes, source coordinates, resolved geometry and counts survive a bit-exact round trip. The sampled global shortlist is not substituted for the full banks used for scan verification.

`ReferenceDatabase` no longer falls through to JPEG loading/ORB extraction once application preparation has completed. Active banks use prepared ASOR data through the background loader. Bounded cache misses can read those compiled banks off the UI/camera delivery threads; they do not recompile photographs. New `StartupReferencePlan` warms a maximum of 24 views for the saved/current and upcoming target. Existing per-phase prefetch remains active. Low-memory events release cache-owned copies while active banks retain their own owners. Ordinary Recents/UI-hidden transitions retain the warm cache.

The previous fix for preparation stopping at 387 remains: short hashed cache filenames, durable per-reference provenance, atomic cache/index writes, and explicit storage failures rather than blacklisting valid photos. See `REFERENCE_PREPARATION_FIX_2026_10_25.md`.

### Navigation, rescan and previews

New `workflow/phase/AssemblyStepNavigationManager` centralizes eligibility checks; Phase 2/3/5/7/8/9/10/11 scan flows and controller click handlers enforce them. A pending first scan cannot show/execute Rescan. Premature calls leave the checkpoint untouched. Phase 7 previously exposed Rescan after watching a demo without a successful scan; that route is closed while its Ready to Scan/demo replay routes remain usable. Phase 1 requires actual confirmed scan evidence and hides Rescan on developer-only completion.

`ComponentPreviewWindow` provides an accessible high-contrast 48 dp Skip Preview action. Non-video/model content clears its callback, preventing a stale skip from navigating another state. Every video-bearing controller wires the action to the flow's explicit forward transition, closes playback, and keeps the next required gate locked.

| Phase | Preview skip destination |
| --- | --- |
| 1 | No video; board scan and welcome action remain required |
| 2 | Motor/bracket verification; independent required views remain locked |
| 3 | Placement demo → placement scan; fastening demo → physical check |
| 4 | No video; existing wiring preparation and schematic flow |
| 5 | Nano installation demo → placement scan |
| 6 | No video; existing wire preparation, schematic and tug checks |
| 7 | Caster demo → physical checks; tracker demo → combined bottom scan |
| 8 | Mounting demo → sensor scan; routing demo → wire preparation |
| 9 | Standoff/wheel demos → their required assembly scans/checklists |
| 10 | Cable demos → cable checks; protector/stacking demos → scans |
| 11 | OLED demo → scan; battery demo → securing/power check |

Existing phase checkpoints, scan tags, calibration, wrong-orientation rejection and physical checklists are retained. No database reset or progress migration was added.

### Individual tester and diagnostics

New `camera/DiagnosticTargetCatalog` and `ui/DiagnosticTargetPicker` build searchable categorized text rows over the unified **prepared photographic** catalog. Unsupported seed-only illustrations, negative-only entries, unknown-count sources and unversioned obsolete assembly states are excluded. The last native catalog check exposed 32 selectable targets. Phases 4 and 6 have no camera datasets; the UI explains that instead of inventing assembly scans for those wiring phases.

`PartRecognitionTestActivity` opens only the selected bounded bank, displays its human target name and exact assigned counts, retains separate trial ledgers per target, and preserves target selection across recreation. Switching targets keeps the camera preview session and replaces the analyzer. Worker-generation guards reject stale requests, overlays and metrics from the old bank. A catalog audit also fixed incorrect grouped instance counts for trackers/wheels and incorrect wheel-pair/Phase 2 side-view routing in `UnifiedReferenceCatalog`.

`DevDiagnosticsFragment` uses the same picker and selection keys. Destroyed fragment views are not updated by delayed preload callbacks. Existing A/B/C runners and export formats remain. Their unmeasured/calibration-dependent results stay unmeasured; no benchmark scores or recognition thresholds were changed.

### UI and memory

- `DeferredCardBinder` binds one library/lesson card per animation frame, with cancellation on destruction. Android view creation stays on the UI thread. Search filters retained views instead of rebuilding all cards per keystroke.
- Library pin mappings reuse one seed-step list. Default thumbnails decode on workers at at most 768 px instead of retaining full multi-megapixel reference images. The thumbnail LRU is bounded and trims on real memory pressure without recycling displayed bitmaps.
- The single menu robot EGL context survives normal tab pauses; temporary component previews release their contexts. Existing render-on-demand behavior remains.
- Tab enter/exit animations are 300 ms and respect disabled system animations. Root-task reuse avoids stacking old tab Activities.
- Phase progress is shown against the current 11 learner phases, while the 18 internal legacy manual IDs remain stable. Phase 8 preparation now consistently requests 10 wires.
- Existing `AssemblyVisionAnalyzer`, `ComponentRecognizer`, `TargetBoundingTracker` and `OpticalFlowTrackerBridge` releases were audited and exercised by native pause/repeated-buffer tests. No tracking algorithm was replaced. `DetachedCameraFrame` explicitly opts into the CameraX image contract and narrowly documents the required library-group ImageInfo forwarding. Named UI constants and locale-stable descriptor suffixes remove lint errors without changing recognition behavior.

### Conservative cleanup

Removed the unreferenced `Phase2MotorColorCounter` (superseded color-only verifier), two unreferenced drawables, and 33 resources proved unused by lint **and** a cross-variant/test source search. This includes the obsolete `robot_hero_3d.png` and `assembl_ar_logo_sample.png` (2,342,205 bytes together); the active `assets/branding/assembl_ar_logo.png` is unchanged. The alternate Parts Test app-name resource was retained because its manifest uses it. Real photos, video demos, models and dynamically discovered reference folders were preserved. No broad resource stripping, SDK upgrades or speculative asset deletion was applied.

## Verification

- Final debug app + Android test APK: **BUILD SUCCESSFUL**.
- Host suite: **OK (428 tests)**.
- Android lint: **0 errors**. 193 non-blocking warnings remain (principally localization, older dependency versions, layout/accessibility suggestions); no dependency upgrades were imposed during QA.
- Native replay: **15 unique regression groups passed** (11 pipeline/cache suites plus 4 native layout/control checks). The final CameraX wrapper was rechecked after annotation changes.
- Registry timing on tablet 2603ARP14G: compact restore **968–995 ms**, compressed size **7.41–7.44 MB**. Full-bank reconstruction in the same replay setup took **26.4–28.1 seconds**. These are index timings, not a claim about complete cold-start or camera recognition latency.
- Native checks cover full-inventory/geometry/descriptor equality; corrupt/mismatched index rejection; accessible Skip/reset; pause/stale-evidence rejection; optical-flow resume; 220 repeated frame-buffer cycles; outline persistence after success; dark dual trackers plus caster; outward/inward/missing sensor; both wheels/standoff; Phase 10/11 loose-part outlines; welcome footer contrast at large fonts; full-size overlays on both cameras; Phase 1 developer Next; and scrolling to physical confirmation controls.
- A replay group's wall time includes fixture loading, several positive/negative frames and assertions. It is not the measured time of one live scan.
- No assembly checklist was acknowledged, main APK installed, or progress reset by these offline tests.
- **Pending deployment-dependent check:** actual new startup/menu/tab frame pacing and repeated tester target switching need the latest main app deployed through Android Studio. A universal 60 FPS claim has not been made. The user has been asked to leave the Main Menu open after deployment; no live view of the other room is needed.

## Build and replay

From the Gradle root (`app/` beneath the repository):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python tools/run_host_tests.py
python tools/prepare_candidate_replay.py
adb -s 368e278b push app/build/tmp/tablet-audit/candidate-code.zip /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run_quality_replay.ps1 -Serial 368e278b
```

Use the configured Python/JDK/ADB paths if these commands are not on PATH. The replay runner requires the existing authorized Android test package. It does not install the main app. Normal deployment remains Android Studio **Run**, preserving app data; do not uninstall.

App output: `app/build/intermediates/apk/debug/app-arm64-v8a-debug.apk`.
Test output: `app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk`.
Lint report: `app/build/reports/lint-results-debug.html`.
Raw QA outputs: `app/build/tmp/qa-overhaul/`.
