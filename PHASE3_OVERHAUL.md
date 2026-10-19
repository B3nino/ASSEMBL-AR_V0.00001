# Phase 3: Motor Driver Installation

## User flow

| Step | Evidence / action | Unlock |
| --- | --- | --- |
| 3.1 | Front-face driver photo match; underside matches produce a spoken flip alert | Next |
| 3.2 | Labeled, rotatable 10mm short screw + M3 nut preview; **I Have These** confirmation | Next |
| 3.3 | Two ordered video clips with accumulated instructions and spoken narration; **Ready for Placement Scan** confirmation | Next |
| 3.4 | Independent driver and board homographies verify relative placement; reversed driver produces a spoken warning | Next |
| 3.5 | Installed-module photo match; mounting-hole areas excluded from descriptors | Next to stability check |
| 3.6 | Explicit confirmation that both screws and both nuts are installed, followed by **Firm & Secure** | Completion speech, then Phase 4 |

**Still Jiggling** keeps the stability dialog open and speaks the tightening instruction.
Successful scans latch immediately; camera movement after success cannot relock Next.
Frames from a previous step or a paused activity cannot unlock the current step.
Confirmed step states, video position, displayed instruction lines, and DEV position are resumable.
Looking back preserves learner verification records; confirmed rescans invalidate the selected scan and dependent checkpoints.

## References and existing assets

Everything below is under `app/src/main/assets/`.

| Path | Use |
| --- | --- |
| `references/Phase3/STEP3_1MOTORDRIVER/` | Front driver references |
| `references/Phase3/MOTORDRIVER_FRONT/` | Additional front references; the spaced spelling is supported too |
| `references/Phase3/MOTORDRIVER_BACK_INVALID/` | Underside references for flip alerts |
| `references/Phase3/STEP3_5ASSEMBLYSCAN/` | Placement and installed-module verification |
| `references/Phase3/P3_SCANNINGUIDE/` | Actual supplied guide folder; `P3_SCANNINGGUIDE/` is also accepted |
| `references/Phase3/P3_VIDEOGUIDE/P3_HOW2SEATMOTORDRIVER.mp4` | First demo segment |
| `references/Phase3/P3_VIDEOGUIDE/P3_SCREWPOSITIONSREMINDER.mp4` | Second demo segment |
| `models/dual_motor_driver.obj` | Existing driver model |
| `models/short_screw.obj`, `models/hex_nut.obj` | Phase 3 combined fastener preview |
| `models/screw_30mm.obj`, `models/hex_nut.obj` | Phase 2 combined fastener preview |

The catalog discovers PNG/JPEG photos recursively through `AssetManager.list()`.
The supplied set contains 59 registered photo entries, including duplicated front photos in two folders.
Matching deduplicates identical role/filename entries. Both normalized and inverted ORB variants are prepared by the existing descriptor pipeline.
Guides and video files are excluded from recognition descriptors. No source photos or OBJ files were edited.

### Current final-scan photos

As agreed, the current photos with empty mounting holes remain usable as placement/orientation references.
Reference preparation finds the blue driver PCB, joins fragmented solder-mask regions, and excludes mounting-hole neighborhoods.
Matching requires features distributed across the module plus an independent board homography; board background or one unchanged terminal row cannot verify the module alone.
Camera rotation is separated from a driver rotated relative to the board.
Step 3.6 explicitly confirms the screws, hidden nuts, and firmness. The camera result does not claim to inspect hidden nuts or prove joint tightness.

## Speech, video, and HUD

- Step and segment narration uses the existing lifecycle-owned speech manager. Markdown and list formatting are removed before synthesis.
- Android TTS initialization, voice selection, speech, cancellation, and shutdown run on `Assembly-TTS`, separate from camera processing and video playback. UI callbacks return to the main thread.
- Each video segment holds its final frame until its narration finishes. Muted/unavailable speech cannot trap the playlist. Autoplay does not reveal transport controls; tapping still reveals them.
- The compact, movable info panel defaults to the lower right. Expanded content scrolls within a height limit below the model viewer.
- Phases 1–3 use a movable, resizable lower-left **Recommended Scanning Frame** window. It remains separate from the upper-right model/video viewer.
- The combined screw/nut model rotates each part in place so its label stays with the correct side of the viewer. Existing component library IDs are preserved.
- Phase 3 requests carry no AR anchor or pose, and camera-feed 3D guidance is hidden. Model rendering remains confined to the independent preview window.
- Existing later-phase content is retained, pending the new Phase 3–11 roadmap grouping.

## Exact source files

Paths in this section are relative to `app/src/main/java/dev/chyken/assembl_ar/` unless stated otherwise.

### Created Java classes

- `Phase3GuideController.java`
- `camera/Phase3AssetCatalog.java`
- `ui/SpeechText.java`
- `vision/Phase3Observation.java`
- `vision/Phase3PhotoGeometry.java`
- `vision/Phase3ReferenceMatcher.java`
- `workflow/phase/Phase3MotorDriverFlow.java`

### Modified Java classes

- `DevStepSkipDialog.java`
- `Phase2GuideController.java`
- `PhaseAssemblyController.java`
- `camera/AssemblyProgressReferenceCatalog.java`
- `camera/VisionReferenceFactory.java`
- `render/ComponentModelCatalog.java`
- `render/HomeRobotRenderer.java`
- `ui/AssemblyVoiceGuide.java`
- `ui/ComponentPreviewWindow.java`
- `ui/Phase2VideoPlayer.java`
- `ui/StepInfoPanelController.java`
- `ui/TargetAlignmentBoxView.java`
- `vision/AssemblyVisionAnalyzer.java`
- `vision/ComponentRecognizer.java`
- `vision/RecognitionRequest.java`
- `vision/RecognitionResult.java`
- `vision/ReferenceDatabase.java`
- `workflow/phase/PhaseFlow.java`
- `workflow/phase/PhaseProgressStore.java`

### Layout, tests, and verification utility

- Modified: `app/src/main/res/layout/activity_assembly.xml`
- Modified: `app/src/androidTest/java/dev/chyken/assembl_ar/PhaseWorkflowInstrumentedTest.java`
- Modified: `app/src/test/java/dev/chyken/assembl_ar/render/ComponentModelCatalogTest.java`
- Created: `app/src/test/java/dev/chyken/assembl_ar/camera/Phase3AssetCatalogTest.java`
- Created: `app/src/test/java/dev/chyken/assembl_ar/ui/SpeechTextTest.java`
- Created: `app/src/test/java/dev/chyken/assembl_ar/vision/Phase3ObservationTest.java`
- Created: `app/src/test/java/dev/chyken/assembl_ar/vision/Phase3ReferenceEligibilityTest.java`
- Created: `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase3MotorDriverFlowTest.java`
- Created: `tools/validate_phase3_matching.py`
- Created: this document, `PHASE3_OVERHAUL.md`

## Verification

- All 171 main Java sources compiled with cached Android, OpenCV, and Media3 dependencies.
- AAPT2 compiled and linked all 47 resource source sets, including Media3.
- All **152** selected workflow, reference-catalog, speech, geometry, and model unit tests passed.
- Workflow instrumentation source compiled. Its UI/device tests have not been executed on a tablet.
- Offline photo regression: **150/150** probes passed against all 45 unique supplied source photos, leaving the queried photo out of candidate references. Cases include front/underside identity, camera rotations, simulated reversed and missing modules, and simulated screw heads; both reference photometric variants are included.
- The Python/OpenCV regression mirrors the matching policy; it is not an Android camera accuracy or timing benchmark. Actual finished-build photos and live tablet testing remain useful validation.
- Full offline Gradle APK assembly was attempted with the installed Android Studio JDK, including outside the filesystem sandbox. Gradle failed before compilation with `java.io.IOException: Unable to establish loopback connection`. No APK was installed by this change.

Run the photo regression from the project root with Python, NumPy, and OpenCV installed:

```text
python tools/validate_phase3_matching.py
```

Its JSON report is written to the ignored `app/build/tmp/agent-check/phase3-photo-results.json`.

API references: [Android TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech), [OpenCV homography estimation](https://docs.opencv.org/3.4.15/d9/d0c/group__calib3d.html).
