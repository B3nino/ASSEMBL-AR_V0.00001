# Phase 1 references, HUD and navigation

## Reference photos

The background vision loader discovers PNG, JPG and JPEG files recursively under
`references/Phase1`. Folder names are matched without case sensitivity:

- `Mainboardfront` / `MainboardFront`: front face.
- `Mainboardbottom` / `MainboardBack`: bottom face.

The current asset batch contains 31 front and 39 back photos. New filenames require no
Java entries. Native OpenCV is initialized before discovery and preparation.
Preparation detects a convex board perimeter, masks its background, and caches both
geometry and normal/inverted ORB descriptors. A photo without a usable visible perimeter
is logged and skipped during preparation. Keep the whole board visible with a clear border.

Raw views are scoped to Phase 1. Their inferred front axis is suitable for face/identity
recognition; later installation checks retain authored physical FRONT coordinates.
Bottom views retain negative canonical winding and trigger the flip prompt. The quick
board pass also checks Extension Board references before accepting a board identity.

## HUD and instructions

The top-right preview uses the existing component OBJ renderer and orbit gestures with
rendering on demand. Its resize corner permits up to 42% of screen width and 48% of screen
height, with an overall 22% screen-area limit. The model starts at a closer zoom.
The touch hint fades after three seconds. Recognition status controls a pulsing red or
solid green border. Its EGL lifecycle follows the Activity.

The information card starts at the bottom-left. Its width reserves space for the model
on wide screens, and its height reserves the area below the model on narrow screens.
Dragging or resizing cannot put the card behind the preview SurfaceView.
The information panel can be dragged by its labeled header and minimized with its
existing toggle. Position, expansion and scroll position survive Activity recreation.
Chapter narration is appended to a persistent instruction log at animation/video chapter
boundaries. Replaying a chapter does not duplicate its log entry.

Done confirms a viewed instruction and unlocks the next instruction. Previous Step can
review earlier chapters and phases without changing saved assembly progress. Returning
opens a new camera evidence window; in-flight frames cannot complete a phase during review.
Phase completion still follows the existing visual and physical checks.

Phase 1 uses a dedicated compact `PhaseOneGuideView`: phase/progress, a simple instruction,
and Expand to learn more. Expanded text explains the board's role, front face and scanning
setup. The initial instruction is Bring out the Main Board; the placement instruction
follows when the first utterance finishes. Actual TTS start callbacks keep the displayed
instruction aligned with the current utterance and discard cancelled callbacks.

A successful Phase 1 scan stays on screen, shows Part complete / Next step unlocked, and
reveals an enabled Next Step button. A five-second fading cue suggests exploring the facts.
The confirmed scan survives recreation; only explicit Next saves the phase and starts Phase 2.
The learner card and completion interaction changes are scoped to Phase 1.

The guide now has 18 phases: driver power wiring (section 7) and driver control wiring
(section 8) are independent. Existing phase IDs remain stable. Schema migration copies an
old combined wiring completion/inventory record to the new control phase.

DEV supports phase/section/sub-step selection, skipping one sub-step and completing a
selected phase while staying there. DEV phase completion is a test-session state and
does not rewrite sequential learner progress. Next Step is an explicit navigation action.

## Files changed for this refinement

Paths below are relative to `app/src/main/java/dev/chyken/assembl_ar/` unless stated otherwise.

| Files | Change |
| --- | --- |
| `camera/RawBoardReferenceCatalog.java` (new), `vision/BoardPhotoGeometry.java` (new) | Directory discovery and cached perimeter extraction |
| `camera/ReviewedIndividualReferenceCatalog.java`, `camera/VisionReferenceFactory.java`, `vision/ReferenceDatabase.java`, `vision/ComponentReferenceSpec.java` | Dynamic registration and descriptor preparation |
| `vision/ComponentRecognizer.java` | Phase-1 raw eligibility and Extension Board competition |
| `ui/ComponentPreviewWindow.java` (new), `ui/ArHudView.java` | Interactive model viewport and recognition border |
| `ui/StepInfoPanelController.java` | Draggable panel, saved position and bounded preview layout |
| `ui/PhaseOneGuideView.java` (new), `ui/AssemblyVoiceGuide.java` | Compact learner card, optional board facts, scan-success/Next gating and speech start callbacks |
| `ui/PhaseDemoPlayer.java` | Done-gated playback and seek-complete synchronization |
| `PhaseAssemblyController.java`, `AssemblyActivity.java` | Review/log/navigation integration, camera result guards and viewer lifecycle |
| `DevStepSkipDialog.java` | Phase, sub-step, skip and independent completion controls |
| `PartRecognitionTestActivity.java` | Asset-aware factory API |
| `workflow/phase/AssemblyPhaseCatalog.java`, `workflow/phase/PhaseFlow.java`, `workflow/phase/PhaseProgressStore.java` | 18-phase catalog, explicit DEV transitions and progress migration |

Resources: `app/src/main/res/layout/activity_assembly.xml`, `app/src/main/res/values/strings.xml`.

Tests: `app/src/test/java/dev/chyken/assembl_ar/camera/RawBoardReferenceCatalogTest.java`
(new), `workflow/phase/AssemblyPhaseCatalogTest.java`, `workflow/phase/PhaseFlowTest.java`,
`workflow/phase/PhaseProgressTest.java` under that test package root, and
`app/src/androidTest/java/dev/chyken/assembl_ar/PhaseWorkflowInstrumentedTest.java`.

## Verification

- All main Java sources compile against cached Android/OpenCV dependencies.
- Android resources compile and link against the project's dependency resources.
- 91 JVM tests pass across phase flow, progress, reference catalogs, dropout handling and speech lifecycle.
- Phase 1 compact/scan/explicit-Next/recreation and panel collision instrumentation coverage compiles.
- Device UI/gesture, camera and instrumentation checks still require an Android device.
- Gradle itself cannot start in this environment because its local loopback connection fails;
  compilation and resource verification were run directly with the installed JDK and Android tools.
