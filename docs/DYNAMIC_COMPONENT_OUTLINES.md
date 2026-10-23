# Dynamic component outline restoration — October 24, 2026

## Fault confirmed in the supplied video

`eLmgHMuG.mp4` is a 25.189-second tablet screen recording. It shows Phase 1 verifying the mainboard near 11 seconds, followed by a green polygon remaining at old screen coordinates while the camera pans. The original component HUD and OpenGL guidance retained the last success geometry. Phase 1 switched to COMPLETE, which stopped image tracking and changed the scan token, so the new tracking callbacks were rejected as well.

The welcome dialog used the platform/theme button bar. The replacement uses a fixed custom footer with an explicitly green background and white label. The footer is outside the scroll area, is immediately enabled, and does not depend on the logo animation finishing.

## New architecture

- `ui/DynamicComponentOutlineOverlayView.java`: independent, non-intercepting overlay in both camera layouts. Spring green measured locks, red wrong targets, grey acquisition/search, adaptive stroke, bounded labels, and dashed/faded predicted outlines. A stalled callback expires after 300 ms rather than leaving a frozen success polygon.
- `vision/TargetBoundingTracker.java`: convex hull/minimum rotated rectangle calculations; template-constrained Canny + opening/closing refinement; measured LK tracking via `ImageOutlineTracker`; corner EMA alpha .25 with snap to measured geometry on larger motion; bounded prediction and immutable visual snapshots.
- `vision/ComponentContourTemplates.java`: named rigid body profiles for mainboard, extension board, driver, Nano, motors/brackets, ultrasonic sensor, trackers, wheels, battery and OLED. Uses the existing reviewed **per-photo** planar polygons. Installed Nano/driver/sensor maps describe the component; the wider board feature mask is context. Motor pairs and mounted trackers retain separate bodies. Wiring-only phases have no invented camera-verification dataset.
- `vision/GlobalComponentRegistry.java`: visual discovery samples descriptors inside the component body rather than the surrounding PCB. Rotating expected/other candidates provide a bounded all-phase visual search without a full-library sweep per camera frame. Lower-support fits are acquisition hints, never verification or spoken wrong-item claims.
- `vision/AssemblyVisionAnalyzer.java`: publishes initial localization before completing workflow verification; separates camera ownership from scan-evidence ownership; continues visual-only rechecks after completion; retains fresh frame metadata; consumes reset flags for all specialized paths. Tracking and prediction never unlock Next, change scan counts, or create ORB/pose measurements.
- `vision/OpticalFlowTrackerBridge.java`: supplies timestamped gyro image-motion estimates to the 2D tracker. This is a brief visual prediction, **not** a calibrated world anchor or SLAM. Prediction is bounded to 1.5 seconds with fresh IMU data (250 ms without it), is labelled, and cannot verify hardware.
- `AssemblyActivity.java`, `PartRecognitionTestActivity.java`: same overlay, same preview-coordinate mapping, named wrong-component TTS, lifecycle clearing and generation guards. The old camera HUD/GL success geometry is suppressed; component-preview 3D windows retain their own renderer.
- `workflow/phase/CameraTrackingPolicy.java`: camera tracking remains active on the completed Phase 1 scan screen. Old scan-token evidence is still rejected. Demo/checklist transitions retain their evidence isolation.
- `WelcomeAssemblyDialog.java`, `layout/dialog_welcome_assembly.xml`: pinned 56dp action, explicit colors, scrollable copy, no animation visibility gate. `activity_assembly.xml` and `view_part_test_camera.xml` host the new full-size overlay.

## Verification

Build and host commands from this Gradle root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python tools/run_host_tests.py
python tools/prepare_candidate_replay.py
```

Native fixtures: `TargetBoundingTrackerInstrumentedTest`, `GlobalOutlineRegistryInstrumentedTest`, `DynamicOutlinePipelineInstrumentedTest`, and `DynamicOutlineUiInstrumentedTest`. Candidate replay uses DEX and compiled resources from the built APK against identical installed photo assets; it updates only the separate test package. It does not replace the main app, edit progress, or substitute for a final live camera deployment.

The initial tablet run passed: post-verification camera pan, delayed-result protection, wrong-target persistence/prediction expiry, hull/wire-boundary containment, welcome-button rendering and clickability at 1024×600 / 640×360 / 400×640 with 1.5 font scale, both full-size camera overlays, and separate mounted-pair motion. Host suite: 384 passed. A second native run passed body-only registry identification, wrong-item routing, empty-body/shared-background rejection, and repeated post-verification movement.

Raw results are under `app/build/tmp/tablet-audit/`. These synthetic/native checks establish the tested transitions and geometry; they do not certify every physical component, room lighting, or a 1.5-second metric world lock while the object is invisible. Physical fastener/stability checks remain explicit.

API references: [OpenCV shape/hull/rotated rectangle API](https://docs.opencv.org/4.7.0/javadoc/org/opencv/imgproc/Imgproc.html), [Android motion sensors and gyroscope units](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion). Gyro prediction is never reported as measured optical flow or metric pose accuracy.

## Final results and live deployment

### Live follow-up: delayed detector seed (03:31 tablet capture)

The first deployed live follow-up **did not pass**: the supplied board verified but no outline was visible. Captures are in `app/build/tmp/outline-live/`. Existing opt-in timing logs showed detector passes taking 5.18 and 10.05 seconds. The original native fixtures replayed synchronously and did not reproduce the resulting detached-frame age.

`ImageOutlineTracker.detectRegions()` had tried to catch a detached detector frame up to the latest camera image using the ordinary 600 ms frame-gap limit. That discarded every seed whenever recognition took longer. The correction treats detector catch-up as a fresh image-to-image relocation, requiring forward/backward LK plus **12 RANSAC inliers and 70% support**, while keeping the ordinary inter-frame timeout. It never displays old coordinates without new measured support. An added regression relocates a 4.9-second-old seed to a moved current image, and rejects the same seed against an empty current image.

The assembly camera also explicitly activates its neutral search layer when a scan owns the camera, independently of successful callbacks. Android's view dump now includes outline state, region count, and delivery age for real surface diagnostics. No temporary verbose logging remains enabled.

After this follow-up correction: arm64 app/test build successful, **384 host tests passed**, and **all 11 named native fixtures passed** (one replay wrapper, 16.34 seconds). Named native results are saved in `app/build/tmp/tablet-audit/outline-delayed-native.txt`.

The subsequent deployed **live Phase 1 check passed**. Seven tablet screenshots show the green `TARGET LOCKED: Main Board` outline during confirmation, followed by a successful scan/Next unlock, and continued translation, scale change and rotation with the physical board during camera panning. The view dump records `outlineActive=true`, `outlineState=LOCKED`, one region, and a 149 ms delivery age. Evidence: `app/build/tmp/outline-live/corrected-contact.jpg`, `corrected-0.png` through `corrected-6.png`, and `corrected-activity.txt`. This is a physical mainboard check, not certification of every component or lighting condition. The individual camera screen field check is next.

### Individual camera follow-up

The first actual individual-tool follow-up **failed**: a full-sized camera preview was visible, but it stayed at “No analyzed frames” and a neutral search outline. A read-only runtime inspection confirmed an active detector with no measured target. The separate mode used a 480-reference standalone database; sweeping all references made jobs slow, while intermediate tracking calls added metric contexts and could clear the map before a pending recognition result arrived.

Corrections:

- New `IndividualReferenceSearchWindow.java` bounds **standalone diagnostic** jobs to six expected views and two competing views, retaining the last successful target view while rotating the rest. Every cached view remains reachable. Assembly verification gates and their reference scopes are unchanged. The individual UI states the live batch policy explicitly; assigned keypoint counts still describe the full reference inventory.
- `ComponentRecognizer.java` applies the same standalone window to native batch precomputation and reference validation. Actual good/total matches, RANSAC support and timings remain measured; no pass threshold was lowered.
- `PartRecognitionTestActivity.java` separates detection requests, which own result/sample contexts, from tracking requests, which do not. `AssemblyVisionAnalyzer.java` uses that lightweight request for intermediate frames. Tracking can no longer evict a pending scan's context.
- `GlobalComponentRegistry.java` prioritizes the expected standalone identity across its phase-labelled photos when the individual tool's active phase is zero.

Latest verification: arm64 build successful; **386 host tests passed**, including bounded batch coverage, retained-view fairness and selection changes. Five focused native fixtures passed in a 48.36-second wrapper run. The captured failing individual preview was recognized on the first native attempt against **480 loaded standalone references**, with **68 good matches and 50 RANSAC inliers**; the recognition job took **1010.61 ms**. The complete fixture took 45.33 seconds including library/cache setup, so this result must not be reported as a one-second startup or as meeting the academic 200 ms matching threshold. The fixture is a resized camera-preview crop, not a raw-camera benchmark. Evidence: `app/build/tmp/tablet-audit/outline-individual-native.txt`. Final deployed individual-camera verification remains pending.

## Earlier verification before the live follow-ups

### Resumed session: individual-outline flicker

The subsequent individual field capture did publish detections and stable locks, but the outline appeared intermittently. The view dump showed a 417 ms delivery age and UI work waiting hundreds of milliseconds. `AssemblyActivity` and `PartRecognitionTestActivity` checked `TargetBoundingTracker.epoch()` from the UI thread while that accessor took the same mutex as native LK/morphology.

`epoch` is now volatile and its read is lock-free; reset/native mutations still use the existing tracker lock. A concurrency fixture deliberately holds that mutex on another worker and requires the UI accessor to complete within 100 ms. The dynamic-outline-only `ArGlSurfaceView` also stops re-clearing/re-rendering its unused transparent guidance layer on every missing-pose frame. Its initial clear and component-preview renderers remain intact.

On resuming, the saved correction built successfully and all **386 host tests passed**. Twelve named native fixtures passed, including UI-lock isolation (5 ms), the captured individual-board preview (first attempt; 68 good matches / 50 inliers; 887.66 ms recognition job), delayed-frame relocation, moving/predicted outlines, wrong-identity/ambiguity handling, mounted pairs, workflow isolation and welcome-button rendering. The full-library fixture took 117.37 seconds including preparation; a read-only worker-stack inspection confirmed it was decoding reference descriptors alongside the installed application's background preload, not deadlocked.

The thirteenth fixture initially failed its process-wide native-heap assertion: +16,782,112 bytes over the stress loop while reference preloading was active. That failure is preserved in `app/build/tmp/tablet-audit/outline-lockfree-native.txt`; it is not counted as a pass. The resource test was corrected to inspect this tracker's actual owned native allocations: after each of 220 resets, previous/latest images and tracked keypoint Mats must be empty and detached, and close must release the final seed too. Whole-process heap changes remain informational because concurrent reference preparation cannot be attributed to the tracker. This isolated fixture passed (5.65 seconds; process heap sample +521,968 bytes), and the UI-lock fixture passed again in 8 ms. This checks tracker-buffer lifetime, not whole-app soak stability.

Production source was unchanged between these native runs; the final rebuild only updated the resource fixture. The deployed individual-camera pan check is pending; prior Phase 1 live success does not establish individual-camera smoothness. The old verbose `AssemblArMetrics` trace left enabled by the interrupted session was disabled. Logs: `outline-lockfree-cases.txt`, `outline-lockfree-resource-native.txt`, and `outline-resource-cases.txt` in `app/build/tmp/tablet-audit/`.

- Arm64 app and test build: successful.
- Host suite: **384 passed**, including scan-token rejection after completion, independent visual ownership, and center-cropped preview-coordinate mapping.
- The final candidate replay wrapper passed **all 10 named native fixtures** in 12.05 seconds. This includes the end-to-end analyzer completion transition, an empty diagnostic-session case in visual-only mode, ambiguity suppression, the visible/clickable footer at large fonts, both camera layouts, component pan/persistence, wire-boundary containment, mounted pairs, and 220 reset/cleanup cycles. This was one JUnit wrapper invoking ten separately logged fixture methods; it is not ten independent full-device field trials.
- A cancelled test-package installation initially left the new ambiguity fixture unavailable. That run is retained as a harness/deployment failure. The subsequent run bundled the latest compiled fixtures into the candidate archive and passed without an additional installer prompt.
- Completed Phase 1 has its own visual-only reference request, so reopening that screen can load its bank and reacquire the board. Visual rechecks do not emit workflow results or fabricated diagnostic scan samples. Stale queued outlines are rejected using a separate visual epoch.
- Main app installation is performed through Android Studio with existing app data. The actual Phase 1 pan results and subsequent individual-tool correction are recorded above. Welcome-button contrast and clickability were checked in native rendering fixtures; existing user progress was not reset to force the introductory dialog to reopen.

Files containing the final results:

- `app/build/tmp/tablet-audit/outline-final-host.txt`
- `app/build/tmp/tablet-audit/outline-final-native.txt`
- `app/build/tmp/tablet-audit/outline-pipeline-native.txt`

Example focused native replay (the installed generic test runner must already exist):

```powershell
python tools/prepare_candidate_replay.py
adb -s 368e278b push app/build/tmp/tablet-audit/candidate-code.zip /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip
adb -s 368e278b shell am instrument -w -r -e class dev.chyken.assembl_ar.CandidateCodeReplayInstrumentedTest -e candidateDex /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip -e candidateCases 'DynamicOutlinePipelineInstrumentedTest#completionChangesTheTokenButKeepsVisualLocalizationWithoutNewWorkflowEvidence,DynamicOutlineUiInstrumentedTest#welcomeFooterIsVisibleEnabledAndContrastingAtLargeFonts' dev.chyken.assembl_ar.test/androidx.test.runner.AndroidJUnitRunner
```
