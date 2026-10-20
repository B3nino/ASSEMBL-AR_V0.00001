# Phase 5 — Installing the Arduino Nano

## Learner flow

1. **5.1: Scan and View Info.** Show the existing rotatable Nano OBJ in the frosted upper-right viewer. Top-side photos unlock Next immediately; underside photos show and speak the flip instruction. View Info opens a scrollable technical overview and suspends scanning until dismissed. It introduces both 15-pin rows and the USB orientation marker. Facts were checked against [Arduino's Nano documentation](https://docs.arduino.cc/hardware/nano/); the kit photos show a Mini-USB connector, while the modal acknowledges compatible boards with other connectors.
2. **5.2: Installation demo.** Play `PHASE5_VIDS.mp4` through the existing Media3/ExoPlayer wrapper. Its controls remain hidden on autoplay and accessible on a user tap; pause and drag scrubbing are retained. Narration follows actual playback and seeks. A completed clip waits for its narration, then enables **Ready to Scan**. Confirming this unlocks Next; watching alone never skips into scanning.
3. **5.3: Installed top-view scan.** Show the supplied recommended scanning frame in the existing draggable/resizable bottom-left window. Positive Nano placement unlocks Next and retains the success result until the user continues. The underside, reversed and invalid assembly datasets cannot become ordinary identity matches. There is no camera-space 3D ghost overlay.
4. **5.4: Physical checks.** Ask whether the Nano is firm, then whether all 30 pins are inside the headers. Loose / Pins Outside speaks the reseating instruction and requires both checks again. A partial Firm answer survives recreation. Completing both saves manual section 6 and advances to the existing Phase 6 after the completion speech (or immediately when muted/unavailable).

The bounded, scrollable lower-right card, expandable extra information, completion nudge, Previous/Next buttons and guarded Rescan behavior reuse the earlier phases' UI. Looking back does not clear completed checkpoints. DEV jump/skip stays separate from learner verification. Step, scan, dialog, demo, error and detail prompts use the existing `AssemblyVoiceGuide`, whose Android TTS calls run on its worker thread. Repeated searching frames do not interrupt the entering instruction.

## Asset registration

`Phase5AssetCatalog` recursively discovers files with Android `AssetManager.list()` through the shared scanner. `AssemblyProgressReferenceCatalog.dynamicPhase5()` and `VisionReferenceFactory.build(repository, assets)` register every photo into the startup database, including the normal and inverted ORB variants prepared by the existing descriptor pipeline.

| Asset directory under `references/Phase5/` | Role | Current assets |
| --- | --- | --- |
| `STEP5_1MICROCONTROLLERSCAN/` | Nano top-side references | 17 photos |
| `MICROCONTROLLER_BOTTOMINVALID/` | Underside rejection | 11 photos |
| `STEP5_3HARDWARESCAN/` | Correct installed Nano | 15 photos |
| `STEP5_3HARDWARESCANINVALIDS/` | Reversed / invalid installation | 13 photos |
| `P5_VIDEOGUIDE/` | Installation playback | 1 MP4 |
| `P5_SCANNINGGUIDE/` | Component and installed-view framing | 2 JPEGs |

Both `P5_SCANNINGGUIDE` and the requested `P5 SCANNINGGUIDE` spelling work. Guide images and video frames are not recognition references. Existing artwork and video files were not changed. The packaged Nano model is `models/arduino_nano_microcontroller.obj`; it is already mapped by `ComponentModelCatalog` and needs no duplicate/renamed OBJ.

## Recognition policy

- Source preparation localizes the narrow Nano PCB separately from the wider motor driver. A strict blue mask is tried first; a glare fallback joins disconnected PCB patches only if the strict pass cannot find a full Nano. Tiny blue fragments are excluded. This operates during reference preparation; live identity still requires ORB and RANSAC geometry.
- Loose scans require distributed module inliers. Installation scans require **independent module and surrounding-board homographies**, preventing the unchanged main-board background from certifying a missing or reversed Nano.
- Semantic corner order compares the Nano to its expected board-relative location. Camera rotation cancels out. Nano placement uses a tighter normalized tolerance than the motor-driver footprint.
- Invalid-reference matches must also agree with their board-relative installation geometry. Simply recognizing the same Nano texture rotated in an invalid photo is not a reason to reject a correct board.
- Positive and negative evidence must have a clear score margin; ambiguous frames keep scanning. Strong valid evidence completes the current scan in one frame, without a long stillness timer.
- The camera checks visible orientation and gross placement. It cannot certify all individual hidden pins, bent pins under the module, or physical firmness; Step 5.4 explicitly confirms these.
- Request stage, phase tag, generation and capture timestamp are preserved end-to-end. Paused, duplicate, delayed and pre-rescan frames cannot unlock a new step. Checkpoints retain scan results, playback position and partial physical answers through backing out/recreation.

## Project details

The menu now renders actual assembly phases. The first five have **1, 6, 6, 3 and 4 steps**, with current summaries. Phase 4 combines its two original manual sections into one phase row. `PhaseGuideMetadata` supplies the descriptions and sub-step labels to both the menu and DEV picker, so a later overhaul can update them together. The stale global “128 guided steps” tagline was removed. Later instructions and existing saved manual-section records remain in place pending the future roadmap grouping.

## Verification

- **197 JVM tests passed**, including the existing phase workflows and new asset registration, scan unlocks, resume, partial checklist, stale-frame, orientation, rejection, and menu metadata tests.
- **185 main Java files compiled** against the cached Android/OpenCV/Media3 dependencies.
- AAPT2 compiled/linked **47 resource source sets**.
- Updated UI instrumentation and the new native matcher instrumentation sources compiled.
- **76/76 desktop OpenCV photo probes passed**: leave-one-photo-out matching of all 56 source photos, selected 90°/180° rotations, synthetic missing-Nano probes and shifted-module rejection. The source-photo glare fallback localizes all 56 images. Reproduce with `python tools/validate_phase5_matching.py` using numpy and opencv-python-headless; `--opencv-path` accepts a temporary dependency directory. Results are written under ignored `app/build/tmp/agent-check/`.
- **Android device tests were not run and no APK was installed.** The isolated `assemblePartsTest` build still fails before compilation with the installed Gradle/JDK `Unable to establish loopback connection` error. Desktop OpenCV checks are an algorithm regression, not an Android camera accuracy or timing benchmark. Connected devices and their saved main-app progress were left unchanged.

## Exact changed files

### New main Java classes

- `app/src/main/java/dev/chyken/assembl_ar/Phase5GuideController.java`
- `app/src/main/java/dev/chyken/assembl_ar/camera/Phase5AssetCatalog.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase5Observation.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase5PhotoGeometry.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase5ReferenceMatcher.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/Phase5MicrocontrollerFlow.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseGuideMetadata.java`

### Modified main Java classes

- `app/src/main/java/dev/chyken/assembl_ar/DevStepSkipDialog.java`
- `app/src/main/java/dev/chyken/assembl_ar/PhaseAssemblyController.java`
- `app/src/main/java/dev/chyken/assembl_ar/ProjectDetailActivity.java`
- `app/src/main/java/dev/chyken/assembl_ar/camera/AssemblyProgressReferenceCatalog.java`
- `app/src/main/java/dev/chyken/assembl_ar/camera/VisionReferenceFactory.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/AssemblyVisionAnalyzer.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/ComponentRecognizer.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/RecognitionRequest.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/RecognitionResult.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/ReferenceDatabase.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/AssemblyPhaseCatalog.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseFlow.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseProgressStore.java`

### Resources, tests and documentation

- Modified `app/src/main/res/values/strings.xml`; existing layout XMLs and preview containers were reused.
- New `app/src/test/java/dev/chyken/assembl_ar/camera/Phase5AssetCatalogTest.java`
- New `app/src/test/java/dev/chyken/assembl_ar/vision/Phase5ObservationTest.java`
- New `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase5MicrocontrollerFlowTest.java`
- New `app/src/androidTest/java/dev/chyken/assembl_ar/vision/Phase5ReferenceMatcherInstrumentedTest.java`
- Modified `app/src/androidTest/java/dev/chyken/assembl_ar/PhaseWorkflowInstrumentedTest.java`
- Modified `app/src/androidTest/java/dev/chyken/assembl_ar/ProjectDetailStepNumberInstrumentedTest.java`
- New `tools/validate_phase5_matching.py`
- New `PHASE5_IMPLEMENTATION.md`
