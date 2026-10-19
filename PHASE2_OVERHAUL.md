# Phase 1–2 overhaul

## Active-session resume and Phase 3 handoff repair

Resume now restores a project-scoped active-session checkpoint in addition to the SQLite
verified-section records. Previously `load()` chose only the earliest unfinished database
section, and Phase 2 DEV sessions deliberately skipped `save()`, so exiting a testing session
could return to Phase 1. The new checkpoint remembers phase, DEV status, flow mode, confirmed
inventory position, demo chapter and the full Phase 2 snapshot (sub-step, current hardware
view, unlocked Next state, video clip/time and appended instructions). It is saved on state
changes and before the activity pauses. New camera evidence windows discard stale frames.

Normal checkpoints cannot skip earlier unfinished learner work. DEV checkpoints restore their
testing position without completing SQLite sections or overwriting the separate normal Phase 2
snapshot. **DEV → Return to saved assembly progress** leaves testing mode and opens the saved
learner position. Explicit project restart/redo clears the active checkpoint as well.

Phase 3 is available with its existing content; the upcoming overhaul does not block it.
Normal Phase 2 still requires its six checkpoints, then **Finish Phase 2** saves section 2
and opens Phase 3. A DEV session that jumped straight to the final joint check can now use
**Next phase (DEV)** after confirming that check, without requiring earlier deliberately
skipped checkpoints or manufacturing learner completion.

Production files changed for this repair:

- `workflow/phase/PhaseSessionCheckpoint.java` (new): validated session codec and restore policy.
- `workflow/phase/PhaseProgressStore.java`: project-scoped session persistence and reset cleanup.
- `workflow/phase/PhaseFlow.java`: restores modes with fresh frame generations/time windows.
- `workflow/phase/Phase2GearMotorFlow.java`: distinct normal/DEV finish eligibility.
- `PhaseAssemblyController.java`: restores phase/chapter/inventory position, saves on pause,
  routes Phase 3 and provides return from DEV to learner progress.
- `Phase2GuideController.java`: exposes its full snapshot and saves DEV session changes;
  retains the separate normal snapshot and labels the DEV finish action.
- `DevStepSkipDialog.java`: return-to-saved-progress action when testing.

Added `PhaseSessionCheckpointTest.java`; updated `Phase2GearMotorFlowTest.java` and
`PhaseWorkflowInstrumentedTest.java`. The controller instrumentation now covers normal
Phase 2 → Phase 3 saving, reopening a DEV hardware scan with Next still unlocked, restoring a
selected demo chapter, returning to learner progress, and DEV navigation into Phase 3.
All 164 Java sources compile; 129 JVM tests pass. Instrumentation compiles but has not run
on the tablet. No layout XMLs or image assets changed in this repair.

## Current hardware scan behavior: explicit Next after each view

- Top photo match → green confirmation and **Next: Scan left side**.
- Tap Next → load the Left guide and resume scanning. Left photo match → green confirmation
  and **Next: Scan right side**.
- Tap Next → load the Right guide and resume scanning. Right photo match → unlock
  **Joint Stability Check**. This remains a separate hands-on check.
- A single geometrically accepted match of the requested view latches success; the scanner
  pauses until Next. There is no three-frame hold or secondary yellow-motor-count gate.
  Green feedback and Next use the same confirmed-view state.
- Invalid-scan photos are excluded from registration and runtime matching. Legacy negative
  scores cannot reject or reset a positive match. The source asset folder is retained.
- Top-view descriptors now include the board instead of requiring two motor feature regions.
  A Phase 2 cache-version change rebuilds those descriptors on the next startup.
- The saved snapshot includes both confirmed views and the displayed view. Leaving while
  Next is unlocked restores that same confirmation. Older snapshots migrate without losing
  their confirmed views. Previous reviews earlier views; Rescan resets the selected view and
  later checks while retaining earlier confirmations.

Files changed in this repair: `Phase2GuideController.java`, `workflow/phase/Phase2GearMotorFlow.java`,
`camera/Phase2AssetCatalog.java`, `vision/AssemblyVisionAnalyzer.java`,
`vision/Phase2Observation.java`, `vision/Phase2ReferenceMatcher.java`,
`vision/Phase2PhotoGeometry.java`, and `vision/ReferenceDatabase.java`, plus the Phase 2
flow/catalog/observation JVM tests, `PhaseWorkflowInstrumentedTest.java`, and this document.
No layouts or source assets were changed. The revised controller instrumentation checks
each Next button, MATCHED state, view order, removal of the negative/colour gates and resume.
It compiles but still needs an Android device run.

## Resume and ordered hardware verification repair

- The project screen offers **Resume Assembly** as soon as a section is in progress,
  including the first section. Backing out does not require completing a whole section first.
- Phase 2 stores the confirmed hardware prefix in its existing project snapshot. Back/close,
  pause and recreation retain Top/Left confirmations and the current step/video position.
  Old snapshot formats remain readable.
- Hardware verification runs **Top → Left → Right**, with a persistent checklist, numbered
  scan caption, spoken confirmation and a new camera generation after each explicit Next.
  Each view needs one accepted match. The user then chooses **Joint Stability
  Check**; both joints must pass the existing hands-on check before finishing the phase.
- Alignment switches between `GUIDEFORASSEMBLYSCANNING.jpg`,
  `GUIDEFORLEFTMOTORSCANNING.jpg` and `GUIDEFORRIIGHTTMOTORSCANNING.jpg` in
  `references/Phase2/P2_SCANNINGGUIDE/`. The supplied misspelling is supported without renaming
  files. Loose motor scanning continues to use `GUIDEFORMOTORSCANNING.jpg`.
- View matching compares the best scores across roles, rejects near ties, requires stronger
  feature coverage within side motor regions and rejects reflected homographies. Invalid
  references and the independent top-view colour counter are no longer used.

Files changed for this repair: `ProjectDetailActivity.java`, `model/ProjectProgress.java`,
`Phase2GuideController.java`, `workflow/phase/Phase2GearMotorFlow.java`,
`camera/Phase2AssetCatalog.java`, `ui/TargetAlignmentBoxView.java`,
`vision/Phase2Observation.java`, `vision/Phase2ReferenceMatcher.java`, and
`vision/AssemblyVisionAnalyzer.java` under `app/src/main/java/dev/chyken/assembl_ar/`.
Updated tests: `Phase2GearMotorFlowTest`, `Phase2AssetCatalogTest`, `Phase2ObservationTest`,
`PhaseWorkflowInstrumentedTest`; added `model/ProjectProgressTest`. This document is updated.
No layout XMLs or image assets were modified. Live camera accuracy requires tablet testing;
the JVM tests validate sequencing, persistence, score policy and asset registration.

## Phase 2 scan-unlock and narration repair

This repair changes these production files under `app/src/main/java/dev/chyken/assembl_ar/`:

- `workflow/phase/Phase2GearMotorFlow.java`: a validated simultaneous 4/4 or 2/2 commits
  completion immediately; rejected counts cannot increase progress.
- `Phase2GuideController.java`: live accepted counts, clip narration, speech completion,
  mute/readiness and lifecycle routing.
- `PhaseAssemblyController.java`: routes Phase 2 voice callbacks to its active guide.
- `ui/Phase2VideoPlayer.java`: actual playback/seek notifications, speech-aware clip
  boundaries and replay from a completed playlist.
- `ui/DemoClipNarrator.java` (new): narration cancellation, deduplication and utterance identity.

Regression coverage changes `workflow/phase/Phase2GearMotorFlowTest.java` and adds
`ui/DemoClipNarratorTest.java` under `app/src/test/java/dev/chyken/assembl_ar/`.
`app/src/androidTest/java/dev/chyken/assembl_ar/PhaseWorkflowInstrumentedTest.java` now
checks the Next button after one complete frame and after a subsequent detection drop.
This document is also updated. No layout XMLs or assets change in this repair.

## Roadmap and scope

The planned roadmap total is **11** (`AssemblyPhaseCatalog.ROADMAP_PHASE_COUNT`).
As clarified in this task, the existing Phase 3 onward grouping and content are retained until
the revised Phase 3–11 mapping is supplied. The active catalog therefore still has 18 entries;
labels and saved progress use that actual catalog rather than hiding unfinished instructions.

Phase 2 now runs six checkpoints under `Phase2GearMotorFlow`. `PhaseFlow` delegates its
mode, generation and evidence window to that flow when attached. Completion of a checkpoint
unlocks Next; it never automatically changes steps. Finishing Phase 2 saves its existing
manual section through the existing database path.

## Learner behavior

- A fresh Phase 1 session shows the welcome modal. **Let's Assemble** creates its flow and
  opens camera evidence collection. Acknowledgment survives Activity recreation.
- Phase 1 uses `P1_SCANNINGGUIDE/GUIDEFORMAINBOARDSCANNING.jpg` as a translucent alignment aid.
  The original animated reticle is suppressed while an image guide is visible.
- Phase 2.1: four distinct brackets together; one validated full-set frame unlocks Next.
  Counts use the recognizer's confidence, convex-geometry and distinct-instance gates.
  The live count is displayed until completion; later detection drops cannot revoke success.
- Phase 2.2: two distinct TT motors together; a single motor cannot satisfy the quantity.
  Existing ORB instance localization is supplemented by the existing gearbox/metal-can
  detector, masking an accepted body before looking for the second body.
- Phase 2.3: static fastener/tool images from the component library and an explicit
  **I Have These** confirmation for four 30mm screws, four M3 nuts and a Phillips driver.
- Phase 2.4: three offline Media3 ExoPlayer clips in authored order. Tap to pause/resume;
  drag horizontally or use the controller timeline to scrub. Caption lines append on actual
  media item transitions, survive replay and remain in the scrollable card after playback.
  Text-to-speech narrates the matching instruction when a clip actually plays. Pausing or
  leaving the step cancels speech; resume, seek, replay and unmute read the current clip again.
  Clip boundaries hold the final frame until narration finishes (notably the 7-second inward-
  motor clip); mute or unavailable speech releases the hold. This uses Media3's
  [pause-at-end support](https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer#setPauseAtEndOfMediaItems(boolean)).
  **Done Assembling** becomes available when the playlist finishes, then Next unlocks.
- Phase 2.5: collect top, left and right reference views in that order. A positive photo match
  unlocks Next for the current view. Each guide transition requires an explicit tap. Invalid
  photos and the yellow-motor counter do not veto a board/view match.
- Phase 2.6: separate Left/Right Firm or Jiggling answers. Any jiggling produces the tightening
  instruction and requires another check. Both must be firm before Finish Phase 2 unlocks.

Every completed step offers **Expand to learn more** and a fading visual cue. The cards
are capped at 46% of usable screen height. Their content scrolls while navigation stays
in a fixed footer. The layout reserves real space for the model surface, even after dragging
or resizing. Guide/thumbnail images are decoded at bounded resolutions off the UI thread.

The component preview has a pale backdrop for black parts, retains orbit/zoom, and hosts the
video or tool images during the relevant steps. It uses the supplied `motor_bracket.obj` and
the existing TT motor proxy `models/motor_ghost.obj`; no `tt_gear_motor.obj` asset is present.

Previous reviews completed checkpoints without discarding them. **Rescan** always opens
`RescanConfirmationDialog`, including the rejection-alert route. Confirming resets the selected
scan and any dependent hardware/stability checks. Other inventory confirmations and video
history remain available. Confirmed verification views survive pause. Old or duplicate
camera timestamps and old scan generations cannot advance the flow. A confirmed scan waiting
for Next survives recreation. Looking back at Phase 2 from a later phase uses the new six-step
card and can return to the same active phase without rewriting its progress.

DEV selects all six Phase 2 sub-steps and can skip/complete for a test session without writing
learner progress. Live diagnostics also report FPS, keypoints and scene state without a board
observation.

## Discovered assets

All paths below are under `app/src/main/assets/references/Phase2/`. Discovery is recursive,
accepts PNG/JPG/JPEG and tolerates spaces, underscores, case and the decorative prefix shown
in the request. The actual asset folders in this workspace are:

| Folder | Files | Role |
| --- | ---: | --- |
| `BRACKETS_REFERENCE/` | 12 | Individual bracket photos |
| `STEP2_1_BRACKETS/` | 11 | Four-bracket scene photos |
| `MOTORS_REFERENCE/` | 8 | Individual TT motor photos |
| `STEP2_2_MOTORSCANS/` | 9 | Two-motor scene photos |
| `STEP2_5_HARDWARE_CHECK_LEFTSIDE/` | 17 | Positive left-side scenes |
| `STEP2_5_HARDWARE_CHECK_RIGHTSIDE/` | 18 | Positive right-side scenes |
| `STEP2_5_HARDWARE_CHECK_TOPVIEW/` | 19 | Positive top scenes |
| `STEP2_5_HARDWARE_CHECK_INVALIDSCANS/` | 29 | Ignored; excluded from registration and matching |
| `P2_SCANNINGGUIDE/` | 5 | Alignment artwork, excluded from ORB |
| `P2_VIDEOGUIDE/` | 3 | Offline clips, excluded from ORB |

**94 positive photos** are registered with unique `p2scene_...` IDs and section-2 scope through
`AssemblyProgressReferenceCatalog.dynamicPhase2()` and `VisionReferenceFactory.build()`.
ReferenceDatabase prepares/caches the existing normal and inverted ORB variants, with cached
component ROIs to reduce matches on the tabletop; the top view includes board features. A reference
without usable features is logged and skipped by the existing preparation failure handling.

Scene descriptors are kept separate from ordinary physical pose/anchor matching. The unused
negative examples cannot become accepted main-board anchors in this or a later phase.
Their nominal dimensions are metadata, not measured pose calibration. Uncertain scene matches
remain inconclusive rather than claiming direction or joint firmness.

Authored playback order:

1. `P2_VIDEOGUIDE/P2_Where to insert motor brackets.mp4`
2. `P2_VIDEOGUIDE/P2_MOTORSFACEINWARDS.mp4`
3. `P2_VIDEOGUIDE/P2_SCREWPOSITIONS.mp4`

Alignment files:

- `references/Phase1/P1_SCANNINGGUIDE/GUIDEFORMAINBOARDSCANNING.jpg`
- `references/Phase2/P2_SCANNINGGUIDE/GUIDEFORBRACKETSCANNING.jpg`
- `references/Phase2/P2_SCANNINGGUIDE/GUIDEFORMOTORSCANNING.jpg`
- `references/Phase2/P2_SCANNINGGUIDE/GUIDEFORASSEMBLYSCANNING.jpg`

## Exact code/resource changes

Java paths are relative to `app/src/main/java/dev/chyken/assembl_ar/`.

| Files | Change |
| --- | --- |
| `Phase2GuideController.java` **new** | Six-step UI, live requests, media/captions, dialogs, persistence and review |
| `WelcomeAssemblyDialog.java` **new** | Pre-assembly welcome and explicit start |
| `RescanConfirmationDialog.java` **new** | Shared rescan guard |
| `camera/Phase2AssetCatalog.java` **new** | Cached discovery, folder aliases, roles, guides and clip ordering |
| `workflow/phase/Phase2GearMotorFlow.java` **new** | Evidence, explicit-step gates, review, rescan and restoration |
| `ui/CompactGuideCard.java` **new** | Bounded content, optional detail, fixed controls and fading cue |
| `ui/TargetAlignmentBoxView.java` **new** | Translucent photo alignment guide |
| `ui/Phase2VideoPlayer.java` **new** | Media3 playlist, touch pause/scrub and lifecycle |
| `vision/Phase2Observation.java` **new** | Positive scene evidence and view ambiguity |
| `vision/Phase2PhotoGeometry.java` **new** | Cached component regions for scene descriptors |
| `vision/Phase2ReferenceMatcher.java` **new** | Positive variant matching, homography/region coverage and view competition |
| `vision/Phase2MotorColorCounter.java` **new** | Independent two-gearbox top-view corroboration |
| `AssemblyActivity.java` | Welcome restoration, custom card integration and diagnostics |
| `PhaseAssemblyController.java` | Welcome/start gate, Phase 1 guide/rescan, Phase 2 routing, safe look-back and DEV integration |
| `DevStepSkipDialog.java` | Actual catalog count and six Phase 2 sub-steps |
| `camera/AssemblyProgressReferenceCatalog.java` | Dynamic Phase 2 registration entry point |
| `camera/VisionReferenceFactory.java` | Includes the discovered scene specs in startup preparation |
| `vision/ReferenceDatabase.java` | Prepares cached scene masks before descriptor blobs |
| `vision/ComponentRecognizer.java` | Owns scene matcher; excludes all scene examples from ordinary anchors |
| `vision/AssemblyVisionAnalyzer.java` | Stage-specific scene evidence on the camera executor; no duplicate board pass for loose scans |
| `vision/LoosePartShapeRecognizer.java` | Two independent loose motor observations with existing identity vetoes |
| `vision/RecognitionRequest.java` | Validated stage and immutable-copy propagation |
| `vision/RecognitionResult.java` | Preserves scene observations across result copies |
| `workflow/phase/PhaseFlow.java` | Delegates to the six-step flow when attached |
| `workflow/phase/PhaseProgressStore.java` | Project-scoped snapshot, included in project/phase clearing |
| `workflow/phase/AssemblyPhaseCatalog.java` | Records the planned 11-phase roadmap without inventing later groups |
| `ui/StepInfoPanelController.java` | Bounded cards, saved position and guide dimensions |
| `ui/PhaseOneGuideView.java` | Guarded rescan action and fixed navigation footer |
| `ui/ComponentPreviewWindow.java` | Pale backdrop, swappable content and refreshed rotation hint |
| `ui/AssetImageLoader.java` | Optional bounded image decode |
| `ui/ArHudView.java` | Suppresses duplicate reticle while the photo guide is visible |
| `render/HomeRobotRenderer.java` | Light clear colour for isolated component preview |

Other modified/new files:

- `app/build.gradle.kts`: Media3 ExoPlayer/UI 1.5.1 dependencies. See the
  [official Media3 setup documentation](https://developer.android.com/media/media3/exoplayer/hello-world).
- `app/src/main/res/layout/activity_assembly.xml`: alignment and Phase 2 card views.
- `app/src/main/res/layout/view_phase2_video.xml` **new**: PlayerView with TextureView and seek controls.
- `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase2GearMotorFlowTest.java` **new**.
- `app/src/test/java/dev/chyken/assembl_ar/camera/Phase2AssetCatalogTest.java` **new**.
- `app/src/test/java/dev/chyken/assembl_ar/vision/Phase2ObservationTest.java` **new**.
- `app/src/test/java/dev/chyken/assembl_ar/vision/Phase2ReferenceEligibilityTest.java` **new**.
- `app/src/androidTest/java/dev/chyken/assembl_ar/PhaseWorkflowInstrumentedTest.java`: replaces superseded Phase 2 expectations and adds welcome, dual counts, explicit confirmation, negative/rescan, stability and review cases.

## Verification and remaining device checks

- All main Java sources compile against Android/OpenCV and the official Media3 AARs.
- Android resources compile and link, including Media3 view attributes.
- 129 JVM tests pass, including active-session restore, DEV finish eligibility, per-view Next gating, simultaneous count gating,
  clip narration, stale frames, negative-dataset exclusion, restore/rescan behavior and asset registrations.
- Updated instrumentation sources compile.
- A tablet is connected. Full APK assembly and device execution are blocked by the local
  JDK/Gradle `Unable to establish loopback connection` failure, including retries with IPv4,
  the Windows selector and daemon options. No new APK was installed by this task.
- Physical photo-matching thresholds, terminal-direction rejection, UI geometry and video
  gestures still need an actual build/run on the tablet. Source/unit checks do not establish
  camera accuracy on the supplied photographs.
