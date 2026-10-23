# Individual camera memory and sustained tracking

## Captured failure

The user clarified that prolonged testing crashes the **tablet app**, rather than Windows/Android Studio. While its individual mainboard test was running, /proc/26675/status showed a peak resident footprint of 548,812 KiB and 388,812 KiB swapped out. Android's app memory-dump endpoint repeatedly timed out. CPU sampling showed substantial heap/GC and kernel activity. Screenshots still showed recognition results, but the outline was unreliable.

Exit history contained older **background** low-memory kills and expected instrumentation/deployment force-stops. It did not establish the cause of a new foreground crash. The observed swapping and GC pressure are a concrete performance problem, not proof that every earlier crash had the same cause.

## Changes

- ReferenceDatabase.loadIndividualBank retains one small native reference bank instead of the complete 480-photo standalone library. Catalog registration, compiled descriptor files and the compact global identity registry remain shared. Component changes and three unsuccessful detections rotate the bank on the background detector worker. A successful view stays available. Every replaced bank releases its native descriptors; loading/copying is prohibited on the UI thread.
- ComponentRecognizer clears reference-keyed frame/batch caches when a bank changes, so those keys cannot retain earlier native banks. Timing includes bank-change overhead in total recognition time; feature extraction and Hamming matching remain their separately measured stages. No evidence/pass threshold changed.
- PartRecognitionTestActivity begins from the catalog-ready future, uses the bounded standalone bank, and distinguishes full catalog inventory from its active bank in the UI. Assembly-specific diagnostic requests keep their authored verification scopes.
- ReferenceDatabase.PreparedReference.copy removes an unnecessary intermediate descriptor clone while maintaining independent native ownership. Memory estimates allow more room for Java keypoint/point objects.
- VisionPreloadManager reduces the hot-cache budget on devices with 4 GiB or less RAM to at most 16 MiB. Compiled disk references are retained.
- Both camera activities request the additional **metric-pose** tracking pass only while an academic benchmark is running. Their user-visible LK outline tracker remains active. This avoids two independent native LK/pose passes per ordinary camera frame.
- LatestUiTask lets both cameras retain at most one pending outline update when the UI stalls. Navigation clears it; existing generation/epoch guards still reject stale work.
- AcademicBenchmarkRunner keeps a bounded 2,048-frame deduplication window. Delivered frame counters continue accumulating; duplicate and out-of-order frames do not inflate coverage. A pose callback arriving after its frame has expired from this window cannot add computed-pose coverage.

## Automated verification

From the Gradle root:

    powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
    python tools/run_host_tests.py
    python tools/prepare_candidate_replay.py

Arm64 app/test build passed. **389 host tests passed**, including 100,000 queued visual updates, long benchmark histories, target-window coverage, and selection changes.

Eight named native fixtures passed in one 26.21-second candidate-replay wrapper. The captured board preview matched on attempt one (68 good matches, 50 inliers; 831.01 ms recognition job). Its fixture took 3.20 seconds including setup. The all-component bank audit visited **17 registered standalone identities twice**, at most eight active records, with a peak estimated active-bank footprint of **16,896,896 bytes**. Old and final native descriptors were released. This audits reference availability and ownership; it does not certify physical recognition accuracy for all 17 parts.

Logs and inventory:

- app/build/tmp/tablet-audit/outline-bounded-banks-native.txt
- app/build/tmp/tablet-audit/outline-bounded-bank-cases.txt
- app/build/tmp/tablet-audit/individual-bank-inventory.csv
- Baseline screen/process evidence: app/build/tmp/outline-soak/

Do not label the 831 ms reference-preview result as meeting a 200 ms matching benchmark or as a long-session stability test. Do not count the earlier failed process-wide heap assertion as a pass; its corrected allocation-isolation test and original failure are recorded in DYNAMIC_COMPONENT_OUTLINES.md.

## Deployed two-minute check and graphics allocation finding

The user deployed the bounded-reference build through Android Studio. A 121.9-second live mainboard run retained PID 10837 with no restart or new crash record. Seven samples kept VmHWM at 421,684 KiB, VmRSS between 92,304 and 143,664 KiB, and VmSwap between 124,128 and 171,860 KiB. These `/proc` figures **exclude substantial graphics allocations** and do not prove low overall app memory. The original peak was 548,812 KiB, but different process peaks are not an equal-workload benchmark.

Five sampled view dumps showed LOCKED outlines with 48–83 ms delivery age; two showed SEARCH, including the final screenshot. The first two screenshots show the green polygon changing with board angle/position. Session results advanced from 68 to 228 analyzed frames, expected observations from 55 to 195, and average analysis from 864 to 664 ms. The session's first stable-lock statistic was 58.46 seconds. This run establishes continued processing, **not** fast first lock, continuous tracking coverage, or crash freedom during hours of use. The user still observed intermittent camera stalls.

The successful full memory dump then exposed **591,452 KiB of graphics allocations** and TOTAL PSS 776,960 KiB. Native logcat repeatedly identified the diagnostic `PopupWindow` requesting **7,436 × 7,812**, 235,352,064-byte GraphicBuffers. `DevDiagnosticsOverlayView` copied its 999dp view elevation onto the popup. Android reserved huge shadow margins around a small diagnostic label.

The additional correction keeps the diagnostic view at its existing top Z, disables its shadow outline, and gives the separate non-touchable popup **zero elevation**. A popup already has window-level ordering above the GL surface; it does not need a 999dp shadow. Sync callbacks are coalesced, text updates retain a single popup, and WindowManager geometry updates occur only when bounds change. The mirror retains the original three-line/max-width limits and is dismissed on focus loss or detach. The same class serves both camera screens. This is independent of reference matching and does not change pass thresholds.

`DiagnosticOverlaySurfaceInstrumentedTest` **passed** in a 7.99-second candidate replay. It exercises a real camera-free window: zero popup elevation, high in-layout Z without an outline shadow, bounded content, a single popup through 1,000 text changes, and dismissal/release on removal. The arm64 app/test build passed.

Evidence: `app/build/tmp/outline-soak/after-bounded-*` and `app/build/tmp/tablet-audit/diagnostic-popup-native.txt`.

Android's [PopupWindow elevation API](https://developer.android.com/reference/android/widget/PopupWindow#setElevation(float)) and [View shadow/outline behavior](https://developer.android.com/develop/ui/views/theming/shadows-clipping) describe the separation between view ordering and the shadow-producing outline.

## Live check after removing the diagnostic shadow

The user deployed the graphics correction through Android Studio and opened the Individual Main Board test. A new 120.5-second run retained PID 30355 throughout, with no process restart. All seven sampled view dumps showed one LOCKED region, with fresh delivery ages of 14–128 ms. All three captured camera images show a green polygon registered to the board at different positions/angles. These are discrete samples, not a measured continuous coverage rate.

| Observed memory snapshot | Before shadow correction | After correction, end of run |
| --- | ---: | ---: |
| Graphics | 591,452 KiB (577.6 MiB) | 133,212 KiB (130.1 MiB) |
| Total PSS, including graphics and swap attribution | 776,960 KiB (758.8 MiB) | 348,831 KiB (340.7 MiB) |

The beginning and end graphics measurements stayed approximately 130 MiB. The captured PID-scoped logs contained no further oversized GraphicBuffer warnings, fatal exceptions, out-of-memory errors, or ANR lines. The seven main-window frame-time snapshots were 16–64 ms old. VmHWM remained 468,608 KiB throughout this process's run; the process peak was not lower than the previous process peak. This is an observed graphics-allocation reduction, not a controlled overall memory benchmark or proof of hours-long stability.

The session advanced from 8 to 329 analyzed frames, with 251 expected-part observations, zero wrong-identity observations, and 78 no-detection observations by the final capture. Average recognition-job time displayed 352 ms at the end. The first stable **metric-pose** lock statistic was 63.31 seconds, although image-space outlines were already visible in the first capture. Stable metric pose and visual LK tracking are separate measurements; this does not meet or replace a 200 ms academic matching-time threshold.

Evidence: `app/build/tmp/outline-soak/after-shadow-*`. The user subsequently confirmed **"Smooth now"** while panning. This is their observed result on this build, not a frame-rate benchmark.

### Scope across modes

`DevDiagnosticsOverlayView`, `DynamicComponentOutlineOverlayView`, `AssemblyVisionAnalyzer`, the latest-only outline UI queue, and ordinary-versus-benchmark tracking policy are shared by both AssemblyActivity and PartRecognitionTestActivity. The small resident reference bank is specific to standalone diagnostics. Assembly mode retains its authored phase-specific reference scopes, negative datasets, physical confirmation requirements, and pass thresholds.

## Nano field check: tracking succeeded, primary recognition remained weak

The first loose-Nano check initially showed SEARCH and then six consecutive sampled LOCKED outlines, following actual rotation, translation and scale change. The same process remained alive, its peak did not grow, and graphics stayed about 128 MiB. However, the primary recognition counter showed only **1 expected frame out of 109** by the end of the one-minute capture; later it was 2/229. A successful LK outline is not fresh ORB/pose verification. This check is **not** a recognition pass.

The native analyzed image was only **304 × 240** after cropping. The test camera requested 640 × 400 with a lower-size fallback; on this tablet it chose 320 × 240 before PreviewView cropping. The individual camera now requests the full **640 × 480** buffer, retaining normal viewport cropping and the existing sample-size safety filter. Assembly already uses CameraX's VGA analysis default; its resolution setup was not expanded.

The captured Camera2 K was `[250.3501,0,-8, 0,250.3501,0, 0,0,1]`. A read-only Camera2 audit confirmed that the tablet's raw intrinsic metadata is `[2553.5713,2553.5713,0,0,0]`, despite providing usable focal-length/sensor-size metadata. `CameraCalibrationProvider` previously checked positive focal axes but accepted the zero optical-center placeholders. It now rejects nonfinite/out-of-sensor/zero optical centers and uses the existing, explicitly tagged focal/sensor fallback, or the tagged approximation when no usable metadata exists. This shared correction applies to **both camera activities**, including assembly pose checks. Existing transforms, rotations, skew, and acceptance thresholds are preserved.

The new arm64 app/test build and **389 host tests** passed. All **10 named native calibration cases** passed in one 0.67-second replay wrapper: seven existing coordinate/rotation/provenance checks, two new invalid-intrinsic fallback checks, and the actual tablet metadata audit. The audit reports supported 640 × 480 and 640 × 400 YUV outputs.

Evidence: `app/build/tmp/outline-soak/nano-live-*`, `nano-capture-log.txt`, the four `nano-camera-burst/*.jpg` images, and `app/build/tmp/tablet-audit/camera-calibration-{native,cases}.txt`. Temporary capture and metric logging flags were returned to INFO after the five-second burst.

Sources: [CameraX resolution fallback](https://developer.android.com/reference/androidx/camera/core/resolutionselector/ResolutionStrategy) and [Camera2 intrinsic-calibration coordinate definitions](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LENS_INTRINSIC_CALIBRATION).

## Deployed Nano correction check

The user deployed the correction and framed the loose Nano. Actual analysis is now **606 × 480** after viewport cropping, with K `[500.7003,0,303, 0,500.7003,240, 0,0,1]`, tagged **FOCAL_SENSOR_METADATA**. A 60.5-second run retained PID 9634, with no peak-growth, restart, captured fatal error, ANR, or oversized graphics-buffer warning. Graphics measured 133,328 KiB (130.2 MiB). All seven sampled outline dumps showed LOCKED with delivery ages 28–125 ms; screenshots show the outline attached at different positions and scales.

The first screenshot already contains actual ORB homography + pose evidence (58 good matches, 43 inliers). At the final screenshot, the session recorded **36 expected observations / 60 analyzed frames**, zero wrong identities, two stable locks, and a first stable-lock statistic of **48.94 seconds** during the movement check. This improves the observed primary recognition count over the earlier 1/109 Nano run; it is not a controlled recognition-rate benchmark and the stable-pose wait remains substantial. A subsequent three-second diagnostic burst logged six consecutive EXPECTED_COMPONENT homographies with valid poses: 26–61 good matches, 18–54 inliers, and 0.605–4.721 px pose reprojection error.

Those six live jobs measured feature extraction 82.52–104.38 ms, Hamming matching **330.76–401.19 ms**, and total analysis 477.31–579.84 ms. The 200 ms academic matching-time target is **not met**. The final UI's whole-session average was 1,111 ms, including earlier unsuccessful searches and bank rotation. No thresholds were relaxed and LK observations were not counted as fresh ORB verification.

Evidence: `app/build/tmp/outline-soak/nano-corrected-*`, including `nano-corrected-calibration.txt`. The temporary timing flag was returned to INFO.

## Motor-driver field check

The user switched to the loose motor driver and rotated/panned it. The 45.5-second check kept PID 9634 and its 478,012 KiB VmHWM unchanged. The first sample showed SEARCH while its bank changed; the three later samples showed LOCKED, with 66–184 ms outline delivery ages. The final screenshot records **63 expected observations / 66 analyzed frames**, no wrong identities, two stable locks, first stable lock 17.18 seconds, and average whole-job analysis 704 ms. Its current ORB result had 57 good matches, 50 inliers and an available pose. These are observed diagnostic counters, not an independently labeled accuracy or lighting benchmark.

Graphics measured 131,184 KiB (128.1 MiB), TOTAL PSS 332,540 KiB. The captured logs contained no fatal, out-of-memory, ANR, or large-buffer warning lines. Evidence: `app/build/tmp/outline-soak/driver-corrected-*`. The shared process survived the Nano-to-driver reference-bank change; an hours-long soak is still outside this check.

## Distance-sensor check and reacquisition

The front-facing sensor was already localized in the first capture (232 good matches, 184 inliers and an available pose). It reached a stable-pose lock at the session's reported 11.80 seconds. When the user turned the sensor edge-on so the transducer faces were hidden, the overlay returned to SEARCH; neither stale image-space geometry nor an arbitrary side silhouette was accepted as front-face ORB evidence. At the end of that 45.5-second movement check the session had 17 expected observations / 27 analyzed frames, with zero wrong identities. That denominator includes the deliberately edge-on view and is not an accuracy benchmark.

The user turned the front toward the camera again and confirmed **"panning smooth"** after the resolution change. A separate 20.6-second reacquisition check showed LOCKED in all four sampled dumps (58–99 ms delivery ages). The final screenshot shows ORB + pose evidence with 106 good matches and 55 inliers. Its cumulative sensor-session counter was 42 expected / 72 analyzed observations, including **41 ORB and one fallback** expected observation; do not present all 42 as ORB matches. Average whole-job analysis was 2,010 ms. Sensor matching/search still needs performance work, even though camera panning was smooth and front-face reacquisition was demonstrated.

PID 9634 remained unchanged through the Nano, driver and sensor runs. VmHWM stayed 478,012 KiB across their sampled windows. Sensor graphics measured 131,172 KiB (128.1 MiB), TOTAL PSS 342,245 KiB. Captured logs contained no new fatal, out-of-memory, ANR or oversized-buffer warning lines. Evidence: `app/build/tmp/outline-soak/sensor-corrected-*` and `sensor-reacquired-*`.

## Current verification limits

- The deployed board, Nano, motor driver and front-facing distance sensor were physically observed. Native inventory/ownership tests cover the 17 registered standalone identities; they do not establish equal field accuracy for all parts, assembled phase states, or rooms.
- The shared HUD/outline/queue/calibration corrections apply to assembly scans as well. The post-correction physical checks here were in the individual tool; no assembly progress was reset.
- Graphics allocation and the reported camera stutter improved substantially. No crash/restart or growing memory peak occurred during these sampled runs. They do not establish hours-long crash freedom.
- The academic 200 ms matching-time target remains unmet. Raw counters and timings, including failed observations and the initial Nano failure, are retained above.
- The latest app/test build and 389 host tests passed. Ten native calibration fixtures passed after the final source changes; earlier native tracking, reference ownership and popup-surface results are documented separately. Temporary capture/verbose timing flags are off (INFO).
