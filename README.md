# ASSEMBL-AR

Vision-assisted, offline assembly and learning for the **NEOBOT Personal Robot Kit**. The current learner roadmap contains **11 assembly phases**, with camera verification, video demonstrations, personalized wiring diagrams and explicit hands-on checks. The target test tablet is model **2603ARP14G** (the connected development unit uses ADB serial `368e278b`).

This manual describes the implemented Android project as of **26 October 2026**. The original printed manual's 18 section identifiers remain as compatibility/progress keys; they are not 18 learner-facing phases.

The latest vision update adds first-frame Sobel/log-reflectance recovery, bounded 640-pixel/300-keypoint fast extraction, scoped reference searches and adaptive detail when a small installed part needs it. The owner-supplied YOLO11n model is enabled experimentally for **ATmega chip ROI proposals on Nano scans**; existing photo/RANSAC checks still establish the complete Nano outline and verify placement. The network loads only when a Nano target is selected. Shape-derived rectangle poses display **Coarse PnP**, without inventing ORB ratios, measured orientation accuracy or solver RMS. See [live latency QA](docs/LIVE_VISION_LATENCY_QA_2026_10_26.md) and the [model contract and limitations](app/src/main/assets/models/README.md).

## Project layout and entry points

Open **this directory**, containing `gradlew.bat`, `settings.gradle.kts` and this README, in Android Studio. The Android module is its child `app/`.

| Location | Responsibility |
| --- | --- |
| `app/src/main/java/dev/chyken/assembl_ar/` | Activities, phase UI controllers and diagnostics screens |
| `workflow/phase/` | Phase state machines, navigation policy, checkpoints and progress stores |
| `camera/` | Unified reference catalogs, reviewed video annotations and camera target requests |
| `vision/` | OpenCV recognition, pose, ROI verification, caches and tracking |
| `ui/`, `render/` | Canvas views, dialogs, narration, OpenGL ES models and overlays |
| `diagnostics/` | Authentic telemetry, logging, benchmark accumulation and HUD latching |
| `app/src/main/assets/` | Packaged photographs, video demos, schematics, models and learning material |
| `tools/` | Builds, host tests, provenance-aware video ingestion and native replay |
| `tools/replay_fixtures/` | Held-out test pixels; these are not production training/reference images |
| `docs/` | Detailed control manuals, metric explanations and dated verification records |

Paths in this table for `workflow/`, `camera/`, `vision/`, `ui/`, `render/` and `diagnostics/` are relative to the Java package directory.

## Architecture and actual technology stack

- **Native Android / Java** with AndroidX, Material Components and XML layouts. Current configuration: minimum Android 10/API 29, compile/target API 37. Java source compatibility is declared by the Gradle project; use Android Studio's bundled JBR for the supplied Windows build script.
- **CameraX 1.4.2** owns the camera feed. Analysis uses bounded background work and drops obsolete frames rather than building an unbounded queue.
- **OpenCV 4.12.0** supplies ORB descriptors, Hamming comparisons, Lowe filtering, RANSAC homography, `solvePnP`/Rodrigues and Lucas-Kanade optical flow. Phase-specific verifiers use localized physical feature regions and available negative photographs.
- **OpenGL ES 2.0** renders models and guidance. This project currently has **no ARCore, SceneView, Filament, Unity, Unreal or Vuforia dependency**. Image-based board/component tracking must not be described as ARCore environmental plane mapping. The existing experimental Phase 2 ghost work uses the project's shared camera/OpenCV/OpenGL infrastructure.
- **`GlobalComponentRegistry` / `UnifiedReferenceCatalog`** expose the shared reference identities and supported assembly targets to both assembly and individual tests.
- **`VisionPreloadManager`** is process-owned and asynchronous. Startup prepares the registered catalog/compiled cache; native descriptors use a bounded warm RAM cache and proactive target loading. It does not keep the full roughly gigabyte-scale reference corpus resident on the tablet. Resuming an Activity does not intentionally rebuild the entire library. Cache I/O for an evicted bank remains background work; no universal zero-latency claim is made.
- **`OpticalFlowTrackerBridge` / `TargetBoundingTracker` / `DynamicComponentOutlineOverlayView`** maintain image-based target motion and visible outlines. Short predicted holds are distinguished from measured pose; they are not fresh scan evidence.
- **`StandoffGeometryVerifier`** supplements photo matching for a reflective loose metal standoff. It checks a complete shaft/shoulder/threaded-tip shape rather than treating gold color alone as identity. Final wheel/standoff assembly and physical checks remain separate.
- **`DiagnosticTelemetryLocker`** stores one immutable winning telemetry snapshot after a controller actually completes a scan. Later live tracking frames cannot replace that HUD snapshot. Logging and tracking continue independently; Next/new substep or explicit Rescan opens a fresh scope.
- **`AssemblyVoiceGuide`** owns offline Android TTS. The app does not contain a second `TextToSpeechManager` backend. Both camera toolbars control the existing voice owner through one persistent `PreferencesManager.KEY_TTS_MUTED` preference, migrating the earlier assembly mute setting when needed.
- **SQLite / existing progress stores** retain learner progress. `PhaseSessionCheckpoint` and stable legacy IDs preserve phase/substep state. No schema reset, data clear or application uninstall is part of the UI update.

Version pins come from `gradle/libs.versions.toml`; the manifest and Gradle files remain authoritative. No internet permission is declared for ordinary app operation. Android TTS availability still depends on a usable voice installed on the device.

## System UI and navigation

### Startup and main menu

`MainActivity` is the normal menu entry point. `SplashActivity` also provides startup recovery for camera Activities restored into a new process. Both use `BrandLaunchOverlay` and `StartupPreparationCoordinator`; the recovery screen has `activity_splash.xml`. The actual menu resource is `activity_main.xml`, including a tablet-qualified variant, rather than a separate `activity_main_menu.xml`.

The startup overlay shows the ASSEMBL-AR wordmark, a 27-block isometric assembling cube and real reference preparation counts/percentage. `AssemblyLoadingCubeView` changes its loop's assembly tightness and pacing with actual preparation progress. Animation does **not** manufacture readiness or impose an arbitrary completed percentage: the existing reference and first-robot-frame gates decide when the menu is revealed. A preparation failure retains Retry and does not discard saved progress.

The menu and standard workspaces use a light mint (`#EAF4EF`) circuit background with dark green text. `ElectronParticleBackgroundView` contains exactly 40 muted green/teal vector nodes with faint distance-faded connections. `AppShellController` shares the same shell and bottom menu across guide/manual pages, Learn, Parts, diagnostics, the individual tester and post-assembly workspaces. The established hero content and interactive robot preview remain above it. Reset view returns the robot preview to its initial orientation; Start assembly opens kit selection/resume.

Both new graphics views draw on Android's hardware-accelerated Canvas through a bounded, approximately 30 Hz Choreographer schedule. They reuse drawing buffers, allocate no bitmap/texture per frame, create no rendering worker threads and remove their frame callbacks on pause, loss of focus, ancestor invisibility or detachment. Reduced-motion mode uses a static frame. Menu particles do not animate behind the startup overlay. Camera workspaces (the individual tester and administrator capture) retain a static circuit surface so the shared shell adds no animation callbacks during camera work. This guarantees no **self-scheduled animation callbacks** while inactive; it is not a claim that the entire Activity/device consumes zero CPU/GPU or that the tablet sustains 60 FPS under every workload.

### Settings and optional sections

The shared bottom menu contains Assemble, Learn, Parts and Settings. Administrator is hidden by default. **Settings → Show Administrator section** adds or removes its menu entry immediately; existing PIN/device-owner checks still apply. This is a visibility setting, not a replacement for authentication.

**Settings → Show assembly DEV button** independently shows or hides the assembly camera's phase/sub-step developer controls. It defaults to off, persists across launches and does not reset current progress or change an active test session. The full-screen assembly camera retains its scanning layout and has a Settings gear in its top toolbar. Diagnostic tools remain available from Settings and Parts; hiding DEV does not disable recognition or the individual tester.

`PreferencesManager` stores these two flags separately in `interface_settings`. No SQLite or phase checkpoint is rewritten. Shell preference listeners and graphics are released with their screen lifecycle. Ordinary peer-tab navigation retains the existing task/back behavior.

### Assembly workspace

The camera/target area, top toolbar, rotatable model or video preview and instruction card each retain their existing role. Scan success, physical checklist acceptance and demonstration acknowledgment are different state-machine events.

- A demo remains reviewable. Ready to Scan or Skip Preview goes to its required next state: camera verification, wiring or checklist, depending on the step. Watching/skipping video does not fabricate camera evidence.
- Previous revisits the applicable earlier screen. Rescan is available only for supported previously verified scan steps and explicitly opens fresh scan evidence.
- Wiring diagrams remain accessible for the applicable wiring steps and use persisted selected colors. Color personalization does not change electrical behavior.
- The top speaker icon toggles spoken guidance, shows emerald when enabled and grey when muted, and has an accessible action description. On-screen instructions remain visible when muted. The earlier text voice control delegates to the same preference.
- Physical checks use `PhysicalChecklistBottomSheetDialog`: a bottom-anchored modal sheet, pale mint header/icon, dark translucent surface, large answer pills and scrollable instructions. Phase controllers still own all answers, retries, physical prerequisites and completion. Dismissing a sheet never confirms it. Task-specific labels such as Top Screw Removed or Correct remain explicit instead of being reduced to an ambiguous generic Yes.
- The generic legacy physical confirmation path retains its required checkbox and locked confirmation button in the same sheet presentation. Phase 1's current camera-only board checkpoint does not gain an invented hardware checklist.

### The 11 phases

| Phase | Job and acceptance responsibilities |
| --- | --- |
| 1 | Prepare/recognize the main board with its component side visible. |
| 2 | Identify brackets/motors; install two motors with four bracket halves, 30 mm screws and M3 nuts; verify the required top/left/right views and physical joint firmness. |
| 3 | Identify the motor driver; verify seated board-relative orientation; revisit the fastening demo; confirm 10 mm screws/M3 nuts and stability physically. No redundant screwed-driver camera stage is required. |
| 4 | Assign motor-lead colors, follow the driver wiring schematic and complete the pull/connection check. |
| 5 | Identify the Arduino Nano; verify its installed orientation and header alignment; confirm pins and firmness physically. |
| 6 | Prepare six jumper wires and follow Nano-to-driver power/control mappings, including D5, D9, D6 and D10; complete seating/pin checks. |
| 7 | Identify caster and both trackers, gather their specified screws/nuts/spacers, follow separate demos and physical checks, then verify both installed bottom trackers plus caster in the combined final scan. |
| 8 | Identify/install the outward-facing distance sensor; personalize/control the sensor, LED/speaker and dual-tracker wiring. **10 jumper wires** are required: 2 sensor signal + 2 indicator + 6 tracker leads. Sensor power/ground are provided by the PCB; do not add two unnecessary jumper leads. |
| 9 | Scan the loose standoff and wheels, gather hardware, follow mounting demos and top-screw-removal check, then verify the final wheels-and-standoff assembly. The separate intermediate installed-standoff scan is bypassed; the loose standoff scan remains. |
| 10 | Identify cable, extension board and protector; prepare hardware; connect mainboard cable, assemble protector and stacked board with the saved standoff screw, then connect the top cable. Photos and mandatory physical stability/connector checks remain separate. |
| 11 | Identify OLED and battery, verify mounted OLED and its pins physically, then secure the battery on the **upper extension board beside the OLED**, following the supplied demo. Confirm power connection; open the completion milestone and learning routes. |

Quantities and directions in the active phase controller and current demo are the implementation source of truth. The camera cannot measure hidden nut torque, connector electrical continuity, insertion depth or wheel axle grip; explicit physical checklists remain mandatory.

### Other sections

| Section | Purpose |
| --- | --- |
| Learn | Browse concise component/electrical/mechanical explanations and existing lessons. Learn Why provides contextual explanations during assembly. |
| Parts library | Search kit components, inspect specifications/pins, assembly roles, manuals and rotatable model references. |
| Individual component tester | Select loose parts or supported assembled targets from the unified searchable catalog. Start/Pause live recognition, inspect source-aware telemetry, save/replay actual analyzed pixels and collect diagnostics without advancing assembly. |
| Diagnostic suite | Browse individual/assembly targets, start academic batches, configure optional measured checkerboard ground truth and export authentic logs. |
| Admin access / registration | Authorized local capture/registration of measured component references. This changes recognition data on that device; ordinary diagnostic sample replay does not train or register references. |
| Programming practice | Guided block-to-C++ practice, on-screen simulation and code export. It is not an on-device robot firmware upload engine. |
| Assembly retrospective | Revisit the completed build's component roles and principles as a learning summary. |
| Completion milestone | Highlights the power-switch location and routes to programming practice or retrospective after final confirmation. |

## Telemetry and academic diagnostic suite

Read [the diagnostic control manual](docs/DIAGNOSTIC_CONTROLS_MANUAL_2026_10_26.md) and [the recognition/ORB explanation report](output/pdf/ASSEMBL-AR_Recognition_and_ORB_Metrics_Report_2026-10-26.pdf) before interpreting the HUD. The toolbar mute control described above is shared between camera modes; diagnostic control behavior remains as documented in the manual.

**Workflow verification and academic benchmark gates are separate.** `Match (VERIFIED)` identifies an accepted spatial candidate and its source. Geometry confidence is not an ORB percentage, and a successful 2D fallback does not invent 3D pose, TRE or residuals. Unavailable values stay Unmeasured. An assembly HUD freezes on completion; the individual tester remains live until paused.

### A - ORB performance

- `good / attempted descriptor queries * 100` is the **deduplicated match yield**. `OrbMatchPolicy` retains `d1 < 0.75*d2` and `d1 <= 64`. This denominator is not all possible pairwise comparisons and not a labeled object-classification accuracy sample.
- Descriptor yield is displayed as its calculated percentage, with no 85% pass/fail cutoff in either camera HUD, report or CSV. Scan output follows actual verified identity/geometry/pose. Counts are not changed to inflate the number.
- Recognition by estimated Normal/Bright/Weak/Uneven lighting requires collected trials, with the current report criterion >=80% per category. Lighting labels are image-based estimates, not measured lux; one batch does not create four room conditions.
- Report extraction/preprocessing and matching/geometric filtering separately, with current project targets <=300 ms and <=200 ms. A 30-trial batch collects fresh observations after returning to the camera.

### B - pose and target registration error

Report measured correct-pose fraction (target >=85%), orientation mismatch (<=15%) and mean 3D TRE (<=10 mm). Real metric ground truth requires an independently measured visible checkerboard, consistent coordinate conventions and appropriate camera intrinsics. Rodrigues orientation is interpreted relative to the expected front-face frame. Uncalibrated values are not reported as perfect zero error.

### C - overlay/registration precision

The current report measures independent checkerboard anchor registration error over Static/Pan/Approach-retreat/Rotation sequences: mean <=15 px, maximum <=30 px, measured static offset <=10 mm and computed-pose coverage >=90%. Predicted holds are excluded from computed-pose coverage, and CameraX-dropped frames are not delivered-frame samples.

Keep **solver RMS reprojection residual**, **projected-center to observed-inlier-centroid distance**, and **checkerboard registration error/TRE** separate. They use different point definitions and are not interchangeable.

The recognition-rate, timing and pose/overlay targets above are existing **project benchmark criteria**. Raw descriptor yield has no pass/fail threshold. The numeric targets are not established universal standards, and the references below do not validate these kit-specific choices. Controlled labeled positive/negative trials are still needed for classification accuracy, false-positive/false-negative rates and threshold validation.

### Logs and reproducible samples

Developer Diagnostic Logging enables future raw session rows; exporting does not retrospectively start collection. TXT includes report/history; CSV includes source-specific metrics; ZIP adds inventory, overlay rows and saved `.arpart.zip` frames. The exporter writes timestamped files under app-specific external Documents and opens Android's share chooser. Saved sample replay checks recorded pixels; it does not alter the library or assembly progress. A sample's component identity is not a full phase-state checkpoint; native phase fixtures separately exercise installation gates.

## Build and verification

Requirements: Android Studio, SDK Platform 37, the existing Gradle dependencies/cache (or network on the build host for their initial download), and Android Studio's bundled JBR. The application itself remains offline. Use the ABI-specific ARM64 build for the tablet. Do not uninstall the application or clear its data to update it.

From this README's directory in PowerShell:

```powershell
# Builds the ARM64 app and separate Android-test APK, offline with bounded Gradle workers.
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build_tablet_debug.ps1

# Compile/run every discovered host test after building.
python -B .\tools\run_host_tests.py

# Validate the reviewed Phase 7-9 reference and held-out pixel provenance, without source videos.
python -B .\tools\ingest_phase789_videos.py --verify

# Package current production/test DEX, resources and held-out fixtures for isolated native replay.
python -B .\tools\prepare_candidate_replay.py
adb -s 368e278b push .\app\build\tmp\tablet-audit\candidate-code.zip /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip

# Requires the already installed separate generic test runner. Run serially.
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\run_quality_replay.ps1 -Serial 368e278b -Suite all
```

Use your available Python executable if `python` is not on PATH. On the current development host it is `C:\Users\Hem Bendijo\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe`; ADB is under `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`.

To regenerate reviewed video assets deliberately (changes reference assets/manifests; not needed for a normal build):

```powershell
python -B .\tools\ingest_phase789_videos.py --source-dir 'C:\path\to\original-clips' --opencv-path '.\app\build\tmp\cv-test-deps'
```

The source directory must contain the authored manifest/script's actual named MP4 recordings; this is not a generic auto-labeling training command. Review frame identity, orientation, masks and train/held-out separation before publishing. Packaged JPEGs are copied into `app/src/main/assets`, and held-out pixels live in `tools/replay_fixtures`; the runtime never reads recordings from Downloads. Deleting original Downloads videos does not delete packaged references, but it prevents re-ingestion unless another copy is retained.

Android lint:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:lintDebug --offline --no-daemon --max-workers=2 -Pandroid.injected.build.abi=arm64-v8a
```

The supplied Windows build helper has a process-local JDK socket workaround for this host. Do not change global Android Studio settings to reproduce that helper. On Windows, run host tests and resource-linking/lint sequentially to avoid the host JVM holding `R.jar` open.

For normal deployment use Android Studio Run while retaining application data. The build produces an ARM64 APK; `tools/prepare_candidate_replay.py` uses `app/build/intermediates/apk/debug/app-arm64-v8a-debug.apk`. Gradle also exposes normal APK output/listing paths for the current Android plugin. Test package installation is separate from the main app; do not replace a candidate ZIP while native instrumentation is running. Avoid progress-resetting activity harnesses when testing a user's current checkpoint.

Tests, lint and replay counts evolve with new regressions; use the latest dated verification record rather than assuming the old 48 native groups or 710 host tests are the full current suite. Replay passes demonstrate the named fixtures, not guaranteed recognition under every real-world lighting condition or certified frame rate. Native UI checks additionally cover animation pause/detach behavior, real preparation counts, checklist callbacks/scrolling and persistent mute controls.

The implementation details, preservation checks and latest results for this UI update are recorded in [UI beautification QA](docs/UI_BEAUTIFICATION_QA_2026_10_26.md). The later [light menu, Settings and raw-percentage update](docs/LIGHT_MENU_SETTINGS_AND_RAW_METRICS_QA_2026_10_26.md) supersedes its original dark palette and descriptor gate.

## Research bibliography and attribution boundaries

The following publications were located during documentation verification. Author/year strings alone did not uniquely identify the intended source; these are explicit, relevant matches, with limitations stated. No code or benchmark threshold was changed based on these searches.

1. **Xie, Y., Wang, Q., Chang, Y., & Zhang, X. (2022).** *Fast Target Recognition Based on Improved ORB Feature*. Applied Sciences, 12(2), 786. [Publisher / DOI](https://www.mdpi.com/2076-3417/12/2/786). Relevant to adaptive histogram equalization, ORB/Hamming/KNN matching under illumination changes; not validation of ASSEMBL-AR's particular thresholds.
2. **Farag, W. A. (2026).** *Balancing accuracy and efficiency: detector-descriptor matching strategies for object tracking in autonomous vehicles*. Journal of Electrical Systems and Information Technology. [Publisher / DOI](https://doi.org/10.1186/s43067-025-00303-z). Context for speed, robustness and energy tradeoffs; autonomous-driving experiments are not kit-specific accuracy evidence. A second related Farag & Helal paper appeared in 2026: [A comparative study of detectors, descriptors, and matching techniques](https://doi.org/10.1186/s44147-026-00993-w). The prompt did not specify which was intended.
3. **Stanescu, A., Mohr, P., Kozinski, M., Mori, S., Schmalstieg, D., & Kalkofen, D. (2023).** *State-Aware Configuration Detection for Augmented Reality Step-by-Step Tutorials*. ISMAR 2023, pp. 157-166. [Author-hosted paper](https://immersive-technology-lab.github.io/projects/stateawareconfigdetection/assets/ismar23_stanescu.pdf), [DOI](https://doi.org/10.1109/ISMAR59233.2023.00030). Supports the context of assembly-state-aware tutorials; it describes a state-aware detector, not this project's checkerboard/PnP TRE implementation or a universal 10 mm threshold.
4. **Ma, L., Huang, T., Wang, J., & Liao, H. (2023).** *Visualization, registration and tracking techniques for augmented reality guided surgery: a review*. Physics in Medicine & Biology, 68(4), 04TR02. [DOI](https://doi.org/10.1088/1361-6560/acaf23), [author's university bibliography](https://bme.tsinghua.edu.cn/en/info/1072/1153.htm). A plausible match for the abbreviated Ma citation; it is a medical AR review, not direct validation of a NEOBOT assembly benchmark. The intended Ma paper remains unconfirmed.
5. OpenCV's official documentation: [feature matching](https://docs.opencv.org/4.x/dc/dc3/tutorial_py_matcher.html), [PnP pose computation](https://docs.opencv.org/4.x/d5/d1f/calib3d_solvePnP.html), and [Lucas-Kanade optical flow](https://docs.opencv.org/4.x/d4/dee/tutorial_optical_flow.html). Use the pinned implementation/version for actual runtime behavior.

## Preservation rules for future changes

Keep `OrbMatchPolicy`, `VerifiedScanEvidence`, `DiagnosticTelemetryLocker`, `OpticalFlowTrackerBridge`, `DynamicComponentOutlineOverlayView` and `StandoffGeometryVerifier` intact during presentation work. Never invent metrics to align a green workflow result with an academic PASS. Keep raw reference provenance and explicit physical checks. Never delete or reset `PhaseSessionCheckpoint`, preferences or SQLite data to make a test pass. Pause UI animation owners and release listeners on Activity destruction; animation code must not join a camera worker or own a second camera session.
