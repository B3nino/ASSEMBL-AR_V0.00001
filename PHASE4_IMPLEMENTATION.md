# Phase 4 — Motor wire connection diagram

## Implemented flow

1. **Select motor wire colors.** The picker opens on entry to Step 4.1. Tap one of four lead slots or the schematic, then select one of the ten swatches. Previous/Next Wire cycles between terminals. Reset restores the red/black draft. **Apply Colors** saves the assignments and advances to Step 4.2. Cancel discards the draft. A lifecycle pause/recreation preserves an unfinished draft and its selected terminal without treating it as applied.
2. **Connect using the custom schematic.** The upper-right preview draws the saved colors in `AssemblyDemoView`, with animated connection paths and individually selectable routes. Small previews use a readable compact layout; the Expand control opens a larger diagram with an Edit Colors action. **Done Wiring** unlocks Next so the user can review the extra information before the pull test.
3. **Pull-test all four wires.** **Wire is Loose** keeps the dialog open and speaks the re-insertion/tightening instruction. **All Wires Secure** confirms the phase, speaks the completion message, and advances to Phase 5. Completion saves both existing manual sections 4 and 5.

Phase 4 never starts an MP4 player or submits camera analysis requests. Its scanning reticle, alignment guide, and camera-feed 3D guidance are hidden. The live camera remains a workstation view.

## Colors and connection roles

The exact palette is Black `#000000`, Red `#FF0000`, Brown `#8B4513`, Orange `#FFA500`, Yellow `#FFFF00`, Green `#008000`, Blue `#0000FF`, Purple `#800080`, Gray `#808080`, and White `#FFFFFF`.

The schematic now uses the two supplied component images, with measured anchors for both motor copper tabs, four driver wire openings, four matching screws, and six separately typed header pins. The driver is shown in the installed orientation: green blocks toward the user, six small pins away. Both motors show their cap at the top and gearbox below. The tabs use positional names because their polarity is not marked in the artwork.

| Pictured tab | Driver opening in this view |
| --- | --- |
| Left inner | Left pair, left opening |
| Left outer | Left pair, right opening |
| Right outer | Right pair, right opening |
| Right inner | Right pair, left opening |

Colors describe the user's physical wire insulation. The route order is preserved from the existing assembly reference. Legacy positive/negative enum keys and saved color order remain compatible. Expanded views show all routes and the complete components. Compact views show the selected tab and an enlarged real screw-block detail, with four wire selectors. Very short previews offer expansion. Wires end at insertion openings; separate rings mark the screw heads to tighten. Touch hits and artwork share one crop/rotation transform.

See `app/src/main/assets/schematics/components/README.md` for image-native pin coordinates, orientation, route anchors, and a template for future component schematics.

`NeobotSeedData.getPhase4PinConnections(config)` produces four immutable runtime routes with the selected hex colors. The legacy manual seed records and saved database pin data are preserved; the active Phase 4 schematic uses this runtime override.

## UI, speech, and persistence

- The picker's motor-lead preview, active-terminal buttons, two-row swatch palette, Reset, and Previous/Next Wire follow the supplied SVG's interaction pattern. Styling uses the existing charcoal, pale preview surfaces, and green selection accents.
- Wide dialogs use two columns; narrow dialogs stack the preview above the controls. The body scrolls while Cancel and Apply remain available.
- Each entering-step instruction, selection change, wire inspection, extra-information expansion, and loose-wire correction requests clean speech through the existing `AssemblyVoiceGuide`. Its Android TTS service calls already run on the `Assembly-TTS` worker.
- Applied colors, step unlocks, and pending picker drafts are included in the existing learner/DEV session checkpoints. Looking back preserves progress. Editing applied colors invalidates the dependent wiring and pull-test confirmations.
- DEV includes all three Phase 4 sub-steps. Read-only look-back uses the learner's saved colors and cannot edit them. DEV completion does not mark learner sections verified.

## Exact files

Java paths below are relative to `app/src/main/java/dev/chyken/assembl_ar/`.

### Created Java classes

- `Phase4GuideController.java`
- `model/MotorWireColorConfig.java`
- `ui/WireColorPickerDialog.java`
- `workflow/phase/Phase4MotorWiringFlow.java`

### Modified Java classes

- `DevStepSkipDialog.java`
- `PhaseAssemblyController.java`
- `data/NeobotSeedData.java`
- `ui/ArHudView.java`
- `ui/AssemblyDemoView.java`
- `ui/CompactGuideCard.java`
- `ui/ComponentPreviewWindow.java`
- `workflow/phase/AssemblyPhaseCatalog.java`
- `workflow/phase/PhaseFlow.java`
- `workflow/phase/PhaseProgressStore.java`

### Created XML resources

- `app/src/main/res/layout/dialog_wire_color_picker.xml`
- `app/src/main/res/values/phase4_strings.xml`
- `app/src/main/res/drawable/ic_expand_diagram.xml`

### Tests and documentation

- Created `app/src/test/java/dev/chyken/assembl_ar/model/MotorWireColorConfigTest.java`
- Created `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase4MotorWiringFlowTest.java`
- Updated `app/src/androidTest/java/dev/chyken/assembl_ar/PhaseWorkflowInstrumentedTest.java`
- Created this document, `PHASE4_IMPLEMENTATION.md`

### Component-artwork refinement: exact files

Created:

- `app/src/main/assets/schematics/components/tt_gear_motor.png`
- `app/src/main/assets/schematics/components/dual_motor_driver.png`
- `app/src/main/assets/schematics/components/README.md`
- `app/src/main/java/dev/chyken/assembl_ar/data/SchematicComponentCatalog.java`
- `app/src/main/java/dev/chyken/assembl_ar/model/SchematicPlacement.java`
- `app/src/main/java/dev/chyken/assembl_ar/ui/MotorWiringArtworkRenderer.java`
- `app/src/test/java/dev/chyken/assembl_ar/data/SchematicComponentCatalogTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/model/SchematicPlacementTest.java`

Updated for the artwork refinement:

- `app/src/main/java/dev/chyken/assembl_ar/Phase4GuideController.java`
- `app/src/main/java/dev/chyken/assembl_ar/data/NeobotSeedData.java`
- `app/src/main/java/dev/chyken/assembl_ar/model/MotorWireColorConfig.java`
- `app/src/main/java/dev/chyken/assembl_ar/ui/AssemblyDemoView.java`
- `app/src/main/java/dev/chyken/assembl_ar/ui/WireColorPickerDialog.java`
- `app/src/main/res/values/phase4_strings.xml`
- `app/src/test/java/dev/chyken/assembl_ar/model/MotorWireColorConfigTest.java`
- `app/src/androidTest/java/dev/chyken/assembl_ar/PhaseWorkflowInstrumentedTest.java`
- `PHASE4_IMPLEMENTATION.md`

Both PNGs are copied unaltered. Bitmap decoding is cached and runs off the UI/camera threads. No video or OBJ assets were changed.

## Verification

- **181 unit tests passed**, covering the existing workflows plus the palette, independent assignments, serialization, dynamic routes, color-edit invalidation, completion gates, resume state, original PNG hashes/dimensions, pin types, mounted-view order, and crop/rotation transforms.
- All **178 main Java sources** compiled with the cached Android/OpenCV/Media3 dependencies.
- AAPT2 compiled and linked **47 resource source sets**, including the new XML resources.
- Updated instrumentation source compiled. Tests cover picker cancel/apply, applied-color resume, draft recreation, look-back controls, loose/secure outcomes, phase saving, panel separation, asynchronous artwork loading, recognizable motor/driver colors, wire selection, and Canvas colors at compact and expanded sizes.
- **Device UI tests were not executed.** Full offline APK assembly still fails before compilation with the installed JDK's `Unable to establish loopback connection` error. Retrying with an alternate selector and a shorter temporary socket directory did not resolve the environment failure. No build-environment settings were changed persistently, and no APK was installed.
