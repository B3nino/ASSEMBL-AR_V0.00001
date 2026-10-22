# Phase 7 — Caster wheel and line trackers

## User flow

1. **7.1a → 7.1b:** Identify the caster's correct face, then choose **Next: Prepare hardware**. A dedicated hardware page shows the 20mm screw and M3 nut together as labeled, rotatable models. Gather two screws and four nuts, then press **I Have These** to unlock Next.
2. **7.2a → 7.2b:** Identify two distinct front-facing IR modules together; one module cannot accumulate into two. Next opens a separate page with a labeled 10mm screw, M3 nut and nylon spacer together in the upper-right viewer. Gather two of each, then confirm **I Have These** before continuing to demos.
3. **7.3:** Play the two caster videos in authored order. Append each instruction when its clip starts and narrate when playback starts. Wait for narration at clip boundaries. **Scan Work** arms bottom-view verification after the demo, followed by separate nut-retention and free-swivel questions. Both answers unlock Next.
4. **7.4:** Play both tracker videos with the same synchronization. **Scan Work** locates both sensor bodies at their board mounts in a front-half view. The explicit hardware/stability question confirms both 10mm screws, nylon spacers and M3 nuts. Stable & Secure completes Phase 7 and opens Phase 8 after the completion narration (or immediately when muted/unavailable).

The lower-right card, expandable information, completion nudge, guarded rescan, draggable/resizable reference frame, and autoplay-hidden video controls reuse the previous phases' UI. The preview title opens a component/fastener selector; every selected model remains rotatable without adding a row that crowds the viewport. Opening extra information pauses the demo before speaking it, so cancelling clip narration cannot strand the playlist at a boundary.

**Rescan this step** remains available on 7.3/7.4 demo replays once the demo was completed, and
when reviewing Phase 7 from a later phase. Confirmation restarts only the selected verification;
Cancel preserves it. Review rescans use an isolated flow and retain **Return to current phase**.
See `PHASE7_RESCAN_CONTROLS.md` for the control regression checks.

No live 3D camera ghost is created. Loose screws, nuts and spacers are confirmed with explicit hardware answers. Installed-nut image matches are advisory. Mechanical retention and swivel freedom remain physical checks.

## Reference registration and recognition

AssetManager discovery registers **153 photos**, four guides and four videos under `app/src/main/assets/references/Phase7/`.

| Photo directory | Role | Count |
| --- | --- | ---: |
| STEP7_1CASTERWHEELSCANS | Correct loose caster | 11 |
| STEP7_1CASTERWHEELSCANS_INVALID | Reversed caster | 5 |
| STEP7_2LINETRACKERSSCANS | Paired modules | 29 |
| IRTRACKERS_INDIVID | Individual front faces | 13 |
| IRTRACKERS_INDIVID_INVALID | Back faces | 13 |
| STEP7_3CASTER_BOTTOMSCANS | Installed caster | 13 |
| STEP7_3CASTER_BOTTOMSCANS_INVALID | Missing/misaligned caster | 8 |
| STEP7_4DUALTRACKER SCANS_VALIDS | Complete underside, including 25 new front-half views | 40 |
| STEP7_4DUALTRACKER SCANS_INVALID | Incomplete underside | 21 |

The catalog accepts spacing/case/punctuation variants from the request and the actual asset folder `P7_SCANNINGUIDE`. It also accepts the supplied first clip's `P7_WHERETOPLACESCREWSFORCASTERWHEEL.mp4` spelling. The other three clips retain their supplied names. Guide/video artwork never becomes an ORB recognition reference.

Normalized and inverted ORB variants are prepared and cached. Installed-view references use 3,200 features. Build-stamp catalog revision 5 records this follow-up. The final tracker check matches the individual/paired sensor photo sets and localized sensor crops from all installed views. Each sensor gets its own homography and must occupy a distinct image region; scene crops also require detailed trim-pot inliers under that sensor's homography. Both bodies must match two different mounts under the same board pose. Board context aligns the mounting positions, allowing the sensors to swivel around those positions independently. A complete-board outline or fixed knob position is not required.

Mounting-nut descriptors are matched only inside the expected local patch, so similar nuts elsewhere cannot steal matches. Source patches are localized with contour/circle geometry. Nut image evidence is advisory: specular highlights and occlusion never become a false missing-part alert or silently replace the required physical hardware/stability answer. Missing sensor bodies, a duplicated sensor detection, or modules away from the board mounts cannot satisfy the camera gate. The guide displays a crop of the existing artwork showing the front half; raw recognition images are unchanged.

Matching queries are restricted to component crops before deduplication. Necessary spatial-spread checks reject unusable fragments before RANSAC. These avoid extra matching work without relaxing the component evidence requirements.

## Persistence and compatibility

Saved state version 3 includes the separate inventory scan/hardware page and the demo/verification view, each scan/hardware/demo gate, tracker count, all physical answers, explicit scan readiness, per-demo caption history, media index and playback position. Versions 1 and 2 migrate without resetting those answers; a previously identified loose component resumes on its hardware page. Pause, dialog dismissal, rescan and navigation start new capture windows; stale and reordered camera results cannot unlock a step. Reviewing earlier phases retains the active Phase 7 checkpoint. Previous from verification opens the SAME step’s two-video playlist from the start; a second Previous from that playlist opens the earlier sub-step. Scan Work/Return to verification resumes that step, preserving valid scans and physical answers.

Phase 7 combines the old caster and line-tracker phases and atomically completes manual sections 9 and 10. Old `line_trackers` session destinations resolve to Phase 7; users who already completed caster section 9 resume at 7.2. All 18 manual sections and 152 source instructions remain represented exactly once. There are now **16 active phases**, with the planned 11-phase grouping still awaiting the user's later roadmap mapping.

The replacement PNG guides already supplied for Phases 3 and 5 are preferred over `(DUPE).jpg` copies. No supplied photo, guide, model or video was rewritten.

## Dual-tracker scan repair — 22 October

The reported symptom was one tracker recognized alone, but zero when both entered the frame.
A shared full-frame KNN descriptor pool lets the two identical modules compete in Lowe's ratio
check. Matching camera-sized synthetic identical pairs reproduced this failure.

Step 7.4 now extracts ORB features independently inside each module search window. Live RGB
trim-pot cues and dark module contours propose windows; they never certify a component.
Additional contour proposals and overlapping horizontal and vertical windows provide a grayscale
fallback. Native camera sampling runs every other frame, alternating with 400/600/800-pixel
detail passes, with a smaller ORB border margin so
modules near the camera edges retain features. Every match still passes the original identity,
spatial-coverage and RANSAC checks. Distinct image regions and two different mounts under one
board pose are still mandatory; separate single-module frames cannot combine into a pair.

A short per-window matching budget starts after extraction, preventing an unhelpful proposal from starving the other module.
Per-window/per-scale reference cursors advance through all registered angles over later frames.
Previously useful templates are tried first, and source descriptor crops are retained until
recognizer teardown. Only reference ordering persists between frames, never component poses or
counts. Separate deadlines cover module searches and board context; individual native calls can
outlast their scheduling deadline. These are responsiveness safeguards, not a tablet FPS promise.

The HUD outlines each located tracker. Once both mounting locations verify, the existing
hardware/stability confirmation becomes available. No phase snapshot format, saved progress,
reference assets, layout XML, global vision thresholds, or Phase 1–6 flow changed in this repair.

Earlier host checks, before the final physical-camera correction:

- All 210 main Java sources compile with cached Android/OpenCV/Media3 dependencies.
- 238 host JUnit tests pass, including two new Step 7.4 gate tests.
- **20/20 camera-sized photo probes** and **23/23 additional tracker photo probes** pass.
  They exercise identical pairs, blur, rotation, swivel, grayscale fallback, missing modules,
  front-half crops, reflective/missing nut patches and invalid boards. These host OpenCV policy probes search all angles without
  Android's scheduling deadlines; they do not measure live tablet performance.
- A separate scheduling reproduction found both mounted modules in all three held-out blurred
  identical-pair scenes over fresh frames (2, 3 and 5 frames on this host).

Final device checks on REDMI Pad 2 9.7 (2603ARP14G):

- Real Gradle app and test APK builds succeeded. The normal debug update was installed through
  the tablet's file manager after its USB approval activity repeatedly closed without staying visible.
- **2/2 targeted native OpenCV tests passed in 36.07 seconds.** The actual captured 640×400
  camera frame verified both trackers and mounts on frame 2 in color and frame 3 without color.
  Both single-module erasures were rejected across eight fresh searches each. Camera-sized
  identical modules, blur, 90-degree rotation, and grayscale regression cases also passed.
- No workflow instrumentation tests that reset learner progress were run. The existing checkpoint
  and private pre-update backup were preserved. Live HUD confirmation is recorded in
  `TABLET_DEBUGGING.md`, along with the failure evidence and opt-in diagnostics.
- The final live camera scan at 12:51 outlined both trackers in green and automatically opened
  the stability checklist. The physical hardware answer was left to the user; no developer bypass
  was used. Temporary diagnostic logging and frame capture are off again.

Files changed for this repair:

- app/src/main/java/dev/chyken/assembl_ar/Phase7GuideController.java
- app/src/main/java/dev/chyken/assembl_ar/vision/AssemblyVisionAnalyzer.java
- app/src/main/java/dev/chyken/assembl_ar/vision/ComponentRecognizer.java
- app/src/main/java/dev/chyken/assembl_ar/vision/Phase7ReferenceMatcher.java
- app/src/main/java/dev/chyken/assembl_ar/vision/Phase7TrackerPhotoGeometry.java
- app/src/main/java/dev/chyken/assembl_ar/vision/Phase7TrackerSearch.java (new)
- app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase7CasterTrackerFlowTest.java
- app/src/androidTest/java/dev/chyken/assembl_ar/vision/Phase7ReferenceMatcherInstrumentedTest.java
- tools/validate_phase7_matching.py
- tools/build_tablet_debug.ps1
- docs/PHASE7_CASTER_TRACKERS.md
- docs/TABLET_DEBUGGING.md

## Earlier verification before the dual-instance repair

- All **208 main Java sources compiled** against cached Android/OpenCV/Media3 dependencies.
- **231 JUnit tests passed**, covering the Phase 7 gates, same-frame tracker count, snapshot restore, stale frames, guarded rescan, legacy aliases, catalog membership and existing Phase 1–6 behavior.
- **182/182 full-catalog photo probes passed**. After the final distinct-mount gate, **77/77 targeted tracker probes passed**. Host OpenCV regressions cover all 153 raw photos with the current probe photo excluded from training, camera rotations, front-half crops, independently swiveled modules, missing sensor bodies with nuts still visible, and incomplete assemblies. Erased/open mounting-nut patches must not be reported as image-confirmed nuts; the physical confirmation remains mandatory. Run `python tools/validate_phase7_matching.py` (numpy + OpenCV; `--opencv-path` can specify temporary dependencies).
- Android workflow and native-photo instrumentation sources compiled. Updated scenarios exercise Previous → same-step demo → verification, existing scan preservation, and front-half native matching. Added scenarios cover hardware gates, bottom scans, physical questions, recreation, look-back preservation, two-clip playback, and atomic section 9/10 completion. They have **not been executed on a device** in this run.
- Full Gradle APK assembly was attempted offline, including access to the existing cache, but stopped with `java.io.IOException: Unable to establish loopback connection`. Live tablet camera, rendering and TTS behavior still need device verification; host photo probes are algorithm regressions, not camera performance measurements.

## Exact code and test files created or modified

Paths below are relative to the Gradle project root (`app/` inside the outer workspace). No layout XML changed; the existing reusable views are composed by the new controller.

- app/src/androidTest/java/dev/chyken/assembl_ar/PhaseWorkflowInstrumentedTest.java
- app/src/androidTest/java/dev/chyken/assembl_ar/vision/Phase7ReferenceMatcherInstrumentedTest.java
- app/src/main/java/dev/chyken/assembl_ar/Phase7GuideController.java
- app/src/main/java/dev/chyken/assembl_ar/PhaseAssemblyController.java
- app/src/main/java/dev/chyken/assembl_ar/camera/AssemblyProgressReferenceCatalog.java
- app/src/main/java/dev/chyken/assembl_ar/camera/Phase3AssetCatalog.java
- app/src/main/java/dev/chyken/assembl_ar/camera/Phase5AssetCatalog.java
- app/src/main/java/dev/chyken/assembl_ar/camera/Phase7AssetCatalog.java
- app/src/main/java/dev/chyken/assembl_ar/camera/VisionReferenceFactory.java
- app/src/main/java/dev/chyken/assembl_ar/ui/ComponentPreviewWindow.java
- app/src/main/java/dev/chyken/assembl_ar/vision/AssemblyVisionAnalyzer.java
- app/src/main/java/dev/chyken/assembl_ar/vision/ComponentRecognizer.java
- app/src/main/java/dev/chyken/assembl_ar/vision/Phase7Observation.java
- app/src/main/java/dev/chyken/assembl_ar/vision/Phase7PhotoGeometry.java
- app/src/main/java/dev/chyken/assembl_ar/vision/Phase7ReferenceMatcher.java
- app/src/main/java/dev/chyken/assembl_ar/vision/RecognitionRequest.java
- app/src/main/java/dev/chyken/assembl_ar/vision/RecognitionResult.java
- app/src/main/java/dev/chyken/assembl_ar/vision/ReferenceDatabase.java
- app/src/main/java/dev/chyken/assembl_ar/workflow/phase/AssemblyPhaseCatalog.java
- app/src/main/java/dev/chyken/assembl_ar/workflow/phase/Phase7CasterTrackerFlow.java
- app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseFlow.java
- app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseGuideMetadata.java
- app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseProgressStore.java
- app/src/test/java/dev/chyken/assembl_ar/camera/Phase3AssetCatalogTest.java
- app/src/test/java/dev/chyken/assembl_ar/camera/Phase5AssetCatalogTest.java
- app/src/test/java/dev/chyken/assembl_ar/camera/Phase7AssetCatalogTest.java
- app/src/test/java/dev/chyken/assembl_ar/workflow/phase/AssemblyPhaseCatalogTest.java
- app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase5MicrocontrollerFlowTest.java
- app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase7CasterTrackerFlowTest.java
- app/src/test/java/dev/chyken/assembl_ar/workflow/phase/PhaseMediaCatalogTest.java
- app/src/test/java/dev/chyken/assembl_ar/workflow/phase/PhaseProgressTest.java
- app/src/test/java/dev/chyken/assembl_ar/workflow/phase/PhaseTargetClassifierTest.java
- app/src/test/java/dev/chyken/assembl_ar/workflow/phase/PhaseVerificationSessionTest.java
- tools/validate_phase7_matching.py

- docs/PHASE7_CASTER_TRACKERS.md
- app/src/main/java/dev/chyken/assembl_ar/vision/Phase7TrackerPhotoGeometry.java
- app/src/main/java/dev/chyken/assembl_ar/vision/Phase7MountGeometry.java
- app/src/main/java/dev/chyken/assembl_ar/ui/AssetImageLoader.java
- app/src/main/java/dev/chyken/assembl_ar/ui/TargetAlignmentBoxView.java
- app/src/test/java/dev/chyken/assembl_ar/vision/Phase7MountGeometryTest.java
