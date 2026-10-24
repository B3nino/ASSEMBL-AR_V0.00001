# Phase 3/5 video references and lifecycle stabilization

## Reference images are stored in the project

The app uses **28 copied JPEGs (1,320,240 bytes)** under
`app/src/main/assets/references/Phase3/` and `Phase5/`. Eleven isolated front-face
registrations reuse those JPEGs for the individual component tool. Runtime code
does not open the original Downloads MP4 files. Deleting the original recordings
does not remove the app's reference photos; retain recordings only to regenerate
the dataset. Project demo videos under assets are separate runtime media.

`tools/phase35_video_manifest.json` records the actual source time, reviewed body,
identity and PCB context polygons, sharpness and JPEG SHA256. Its 20 replay frames
use different timestamps and are not registered app references. This is regression
coverage from the same recordings, not an independent recognition accuracy study.

| Recording | Reviewed content |
| --- | --- |
| `k19nwhAO.mp4` | Loose kit driver front, deeply shadowed solder side, correctly installed driver |
| `dn8RmIeI.mp4` | Driver installed 180 degrees backward relative to the board |
| `JlWZAbKu.mp4` | Loose Nano front under glare/rotation, backward installation, correct installation including oblique views |

The real driver in these videos is the blue kit PCB with green terminals. It is
not the heatsink/blue-terminal L298N described in the pasted specification. Masks
and voice instructions follow the actual kit. The Nano video does not show its
loose underside: no underside photograph was fabricated. Existing bottom-face
reference datasets remain registered. Both requested Nano INVALID folder spellings
are supported, including `STEP5_5ARDUINONANO_INVALID`.

Reviewed regions: [reference contact sheet](PHASE35_REVIEWED_REFERENCE_REGIONS.jpg).

## Recognition changes

- `tools/ingest_phase35_videos.py` selects reviewed sharp frames, preserves real
  pixels, generates isolated crops and the manifest/Java registry, and publishes
  images only after all reviewed windows decode successfully. Nano crops retain
  enough real surrounding pixels that ORB's border does not discard the pin rows.
- `camera/Phase35VideoReferences.java` contains the reviewed body/identity/context
  polygons. Reflections and rotated Nano views needing manual regions are authored
  explicitly. Installed backward photos never become standalone front references.
- `Phase3AssetCatalog`, `Phase5AssetCatalog`, `AssemblyProgressReferenceCatalog`,
  `VisionReferenceFactory` and `UnifiedReferenceCatalog` discover nested `var_light`
  folders and route phase photo gates separately from ordinary individual requests.
- `Phase3PhotoGeometry`, `Phase3ReferenceAnnotations`, `Phase5PhotoGeometry` and
  `PhotoFeaturePartition` preserve reviewed masks. The new Nano's body and stationary
  PCB pixels remain separate. Old reference geometry and cache versions are retained.
- `Phase3ReferenceMatcher` now uses a small, ranked/rotating candidate window for
  loose-driver scans too. Strong front evidence prunes repeated positive matches;
  bottom/invalid candidates still get their own search slots. No inlier gate changed.
- `Phase5ReferenceMatcher` can resample a bounded local crop proposed by initial
  loose-Nano matches. Fresh ORB correspondences still need the original 12 loose or
  10 installed inliers, spatial spread and RANSAC checks. Predictions cannot verify
  installation. A photo explicitly reviewed as reversed now produces BACKWARD rather
  than a generic pin-misalignment correction.
- `Phase3GuideController` and `Phase5GuideController` distinguish underside flipping
  from installed 180-degree reversal, using the existing asynchronous voice guide.
  For the recorded Nano assembly, the USB port faces the rear power connector.

`DynamicComponentOutlineOverlayView`, `TargetBoundingTracker`, the Phase 1/2 video
registry and their hardware masks were preserved. Recognitions still publish measured
outlines before workflow success; LK carries those outlines during subsequent frames.

## Lifecycle, native memory and progress

- New `AssemblArApplication` starts the existing idempotent asynchronous preload
  manager at process creation. The manifest registers this Application.
- `VisionPreloadManager` no longer treats ordinary `TRIM_MEMORY_UI_HIDDEN` as a
  low-memory event. App switching retains the warm cache. Genuine low-memory signals
  still evict it. The existing bounded RAM cache is retained, with the full compiled
  library on disk, to avoid undoing the tablet memory repairs. Active banks own their
  own descriptors and survive pause/resume. A same-process resume does not reload them.
- `AssemblyVisionAnalyzer.pause/resume` serializes cleanup behind in-flight detector
  work. It releases camera-owned frame caches and tracker images, drops obsolete
  results using a lifecycle generation, clears transient tags/timestamps, unregisters
  motion, and reattaches it on resume. A frame queued before pause cannot become fresh
  evidence after a rapid resume. UI/camera threads do not wait for a recognition job.
- `ComponentRecognizer.releaseCameraFrameCache` releases image/descriptor Mats and
  Java keypoint arrays without discarding its reference bank.
- `OpticalFlowTrackerBridge.pause` releases the latest image and sensor thread without
  permanently closing the reusable tracker. Normal in-session reset/pan behavior is
  unchanged.
- `AssemblyActivity` pauses/resumes analysis through its Android lifecycle and
  reattaches the existing CameraX Preview surface provider. Its Bundle stores the
  versioned phase checkpoint alongside existing welcome, timer and panel state.
- `PhaseAssemblyController` restores this checkpoint after repository loading.
  `PhaseSessionCheckpoint.canRestoreActivity` also restores an earlier completed-step
  review without allowing a learner to jump past the first unfinished phase. Normal
  menu Resume retains its existing first-unfinished-phase policy.
- `PartRecognitionTestActivity` remembers whether testing was running, invalidates old
  callback contexts while paused, and resumes after its camera/catalog is ready.
  Existing retained diagnostic observations are not reset on a normal app switch.

No user data is cleared, no database is reset, and no new duplicate phase state
class was introduced. The real state classes remain `Phase3MotorDriverFlow` and
`Phase5MicrocontrollerFlow`. Their scan/demo/hardware/physical-check requirements
and explicit Next actions remain intact. Native Mats/cache handles are not serialized
into a Bundle; the process-owned manager is reacquired. Android may still recreate
or kill activities under memory pressure; checkpoints handle that case rather than
trying to prohibit normal Android lifecycle behavior.

## Verification

App and Android test APK builds pass. **405 host tests and all 15 named tablet-native
fixture groups pass** on tablet 2603ARP14G (ADB serial 368e278b). The consolidated native
run took 269.048 seconds including photo preparation, all trials and negative controls;
it is not a prolonged thermal/memory soak. Outputs are `build-final.txt`, `host-final.txt`,
`native-final-all.txt` and `native-final-all-events.log` under `app/build/tmp/phase35-raw/`.

The 20 raw video cases include 11 usable correct loose/installed views, five actual
backward installations, two missing-Nano views and two degraded views. Usable positives
verify and actual backward installations warn, with measured outlines that follow an
8x3-pixel synthetic pan. Both missing-Nano views stay blocked. The almost-black driver
underside and motion-blurred/shadow-covered Nano at 37 seconds remain SEARCHING; those
frames are not counted as successful recognition. Physical stability and hidden-pin
checks still require the existing user checklist.

Native positive/reversal matcher calls on the recorded tablet run were:

| Target | Positive/reversal call time |
| --- | --- |
| Loose driver | 467–835 ms |
| Installed driver | 1,465–1,783 ms |
| Backward driver | 738–1,055 ms |
| Loose Nano (usable views) | 1,066–1,444 ms |
| Installed Nano | 324–717 ms |
| Backward Nano | 474–555 ms |

These are individual recognition-call timings, excluding startup/reference preparation,
not camera FPS or a guaranteed live acquisition duration. Camera/LK updates use their
existing independent path. Full numeric results are in `native-final-all-events.log`.

`CameraPauseResumeInstrumentedTest` exercises an in-flight detector through immediate
pause/resume, verifies discarded callbacks and released Mats with the same bank still
loaded, repeats 12 more pause/resume cycles, and checks 30 bridge image releases.
Phase 1/2 raw board/bracket/motor/top/left/right and missing-part controls, separate
moving outlines, post-completion tracking and 220 reset/release cycles also pass.
The individual-tool request path confirms a measured outline before the result for
both newly registered loose components. The original driver shadow/missing/reversal
checks, six actual backward-driver/camera-rotation cases, six held-out Nano exposure
cases and 12 original front/back/installed/invalid Nano rotation cases also pass.
No learner progress or app preferences were cleared by these fixtures.

Actual CameraX preview continuity through Recents, control center and screen recording
still needs the latest main APK deployed through Android Studio. The isolated native
runner exercises new code without replacing the learner's installed app or resetting
progress; it does not establish that every Android system UI transition is fixed.

## Reproduce

Run these from the repository's Gradle root (`app/`):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python tools/run_host_tests.py
python tools/prepare_candidate_replay.py
```

To regenerate the copied references (requires OpenCV, NumPy, Pillow and source clips):

```powershell
python tools/ingest_phase35_videos.py --opencv-path app/build/tmp/cv-test-deps --source-dir "C:/Users/Hem Bendijo/Downloads"
```

Push `heldout-*.jpg` from `app/build/tmp/phase35-raw/` to the tablet app's external-files
`phase35-raw/` directory. Then use the existing separate native runner:

```powershell
adb -s 368e278b push app/build/tmp/tablet-audit/candidate-code.zip /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip
adb -s 368e278b shell am instrument -w -r -e class dev.chyken.assembl_ar.CandidateCodeReplayInstrumentedTest -e candidateDex /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip -e candidateCases vision.Phase35VideoReplayInstrumentedTest#nanoHeldOutFrontInstalledReversedAndMissingRequireBodyEvidence dev.chyken.assembl_ar.test/androidx.test.runner.AndroidJUnitRunner
```

Other new fixture methods are `driverHeldOutFrontBackAndMountedOrientationsKeepOutlines`,
`isolatedCropsUseIndividualToolRequestsAndPublishOutlineBeforeResult`, and both methods
in `vision.CameraPauseResumeInstrumentedTest`. The generic runner reports one outer
JUnit test; per-fixture `CandidateReplay ... PASS` log entries identify each group.

Deploy the main app through Android Studio **keeping app data**. Check both rooms:
Phase 3 loose front/underside/placement/backward, Phase 5 loose front/bottom/placement/
backward, then pan after success. From a successful scan and from a reviewed demo,
open Recents/control center and return; the current sub-step and Next unlock should
remain. A completed-phase review should also survive activity recreation. Repeat in
the individual tool with a running Nano/driver test. Never remove app data to do this.
