# Phase 10: Extension circuit board placement

## Learner flow

| Section | Explicit screens |
| --- | --- |
| 10.1 | Cable scan → extension front scan → saved standoff screw preparation → protector front scan → 20mm screw/M3 nut preparation |
| 10.2 | Mainboard cable demo → cable firmness check |
| 10.3 | Protector assembly demo → top-view scan → protector stability check |
| 10.4 | Board mounting demo → stacked assembly scan → level/support/wire-clearance check |
| 10.5 | Final cable demo → final connector check → Phase 11 |

These are 15 separately persisted screens. A finished video enables **Check Connection**
or **Ready to Scan**, but never advances the flow. Camera evidence and scan animation are
disabled during videos, preparation and checklists. Previous returns to the corresponding
demo; finished scans expose the existing guarded Rescan control. DEV can select all 15
screens, skip the current screen, or mark the phase complete without immediately advancing.

The existing movable compact information card, resizable preview, rotation controls,
recommended-frame window, video scrubbing and transport behavior are reused. Hardware
preparation displays rotatable models and explicit **I Have This / I Have These** dialogs.
All new step, status, correction and video prompts use `AssemblyVoiceGuide`, whose engine
cleans spoken text and dispatches speech to its existing worker. Android view/dialog work
stays on the main thread; image preparation and analysis retain their background executors.

## Assets and recognition

All paths are packaged beneath `app/src/main/assets/references/Phase10/`:

| Folder | Current photos | Purpose |
| --- | ---: | --- |
| `STEP10_1DATACABLESCANNING` | 22 | Standalone cable |
| `STEP10_1EXTENSIONBOARDSCAN` | 16 | Extension front face |
| `STEP10_1EXTENSIONBOARDSCAN _INVALID` | 14 | Actual extension bottom face; folder space is supported |
| `STEP10_1OLEDPROTECTORSCAN` | 10 | Standalone protector front |
| `STEP10_3OLEDPROTECTORASSEMBLYSCAN` | 8 | Protector on the loose extension PCB |
| `STEP10_4EXTENSIONBOARDMOUNTINGSCAN` | 12 | Stacked board with chassis context |

The prompt's leading `I` spellings for both OLED folders are also accepted. A future
`STEP10_1OLEDPROTECTORSCAN_INVALID` (or leading-`I` equivalent) automatically supplies
protector flip evidence. That dataset is currently absent. Unmatched protector views ask
for the front face; the app does not claim a proven flip from absent evidence.

The four named MP4 demos are selected independently from `P10_VIDEOGUIDE/`; MP4/WebM
extensions are supported. The five images in `P10_SCANNINGGUIDE/` are guidance only.
They never contribute recognition evidence. Existing packaged models are reused, including
`models/extension_data_cable.obj`, `extension_board.obj`, `oled_protector.obj`, and the
20mm fastener pair. There are no runtime links to external photo/download locations.

Recognition details:

- Register every supported photo through `AssemblyProgressReferenceCatalog` and
  `VisionReferenceFactory`; prepare only the active scan bank via `ActiveReferenceScope`.
- Preserve normalized and inverted photometric ORB variants and the shared reference pyramid.
- Cache source-photo geometry. Dark printing locates the white PCB when rounded edges blend
  into paper. Protector features are separated from PCB context and from printing visible
  through its opening; both plastic side rails must remain visible in the live image.
- Installed protector and board context need independent, consistent homographies. The stacked
  check additionally needs a local chassis-wheel match in its correct board-relative position.
  Wheel grip was verified in Phase 9; this check does not re-certify both axle joints.
- Rotate unmatched photo candidates under a soft per-frame budget and prioritize the last
  successful view for the second confirmation frame. Stale/paused/duplicate frames cannot
  supply completion evidence. Two consecutive valid frames unlock Next; scans do not navigate.
- Positive/negative image duplicates are filtered by content, preventing a mistakenly copied
  front photo from simultaneously becoming rejection evidence. The initial duplicate folder
  was replaced during implementation by 14 actual bottom photographs.
- Photo checks establish visible identity/placement. Explicit physical checklists establish
  connector firmness, protector fasteners and board stability. No live AR ghost pose is emitted.

## Saved progress and later content

Manual sections 14–16 now belong to the combined Phase 10. Their stable section IDs remain
intact. Saved `ribbon_cable` and `upper_board` sessions resolve to the combined phase and
retain previously completed work. The exact current screen, completion bits and all four
video positions are persisted. SQLite version 7 refreshes canonical protector hardware to
20mm screws without clearing learner progress or administrator references.

The existing OLED display instructions follow as Phase 11. Battery instructions remain
preserved as Phase 12 until the remaining roadmap consolidation is supplied. The closing
"Phase 9" in the request was treated as a typo; Phase 9's authored flow was retained.

## Verification

- 316 host JUnit tests passed, including new transition isolation, stale-frame rejection,
  hardware/checklist gates, rescan, checkpoint, catalog/alias and progress-preservation tests.
- Debug app and native-test APKs build successfully using the offline Android dependencies.
- Host OpenCV source-photo probes localized all 82 current positive/negative photos.
- With each queried positive photo withheld, all 68 resized, 90-degree-rotated valid views
  matched other photos in their corresponding bank.
- All 14 held-out bottom views matched other bottom references. None of those bottom views
  matched the front bank; none of the 16 front views matched the bottom bank in that probe.
- 20 masked-protector cases and 36 incomplete-assembly/chassis cases were rejected by the
  host probe. Synthetic masking is a regression aid, not a substitute for real invalid photos.
- Native replay and SQLite migration fixtures were added and compiled. They have not been
  executed on the tablet for this overhaul. No APK installation or device-state reset was done.

## Exact source file list

New main classes:

- `app/src/main/java/dev/chyken/assembl_ar/Phase10GuideController.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/Phase10ExtensionBoardFlow.java`
- `app/src/main/java/dev/chyken/assembl_ar/camera/Phase10AssetCatalog.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase10PhotoGeometry.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase10DescriptorSampler.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase10ReferenceMatcher.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase10Observation.java`

Modified main classes:

- `app/src/main/java/dev/chyken/assembl_ar/PhaseAssemblyController.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/AssemblyPhaseCatalog.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseFlow.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseGuideMetadata.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseProgressStore.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseSessionCheckpoint.java`
- `app/src/main/java/dev/chyken/assembl_ar/camera/ActiveReferenceScope.java`
- `app/src/main/java/dev/chyken/assembl_ar/camera/AssemblyProgressReferenceCatalog.java`
- `app/src/main/java/dev/chyken/assembl_ar/camera/VisionReferenceFactory.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/AssemblyVisionAnalyzer.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/ComponentRecognizer.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/RecognitionRequest.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/RecognitionResult.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/ReferenceDatabase.java`
- `app/src/main/java/dev/chyken/assembl_ar/data/NeobotSeedData.java`
- `app/src/main/java/dev/chyken/assembl_ar/data/AssemblArContract.java`
- `app/src/main/java/dev/chyken/assembl_ar/data/AssemblArDatabaseHelper.java`

Tests:

- New `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase10ExtensionBoardFlowTest.java`
- New `app/src/test/java/dev/chyken/assembl_ar/camera/Phase10AssetCatalogTest.java`
- New `app/src/androidTest/java/dev/chyken/assembl_ar/vision/Phase10ReferenceMatcherInstrumentedTest.java`
- Updated `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/AssemblyPhaseCatalogTest.java`
- Updated `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/PhaseProgressTest.java`
- Updated `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase5MicrocontrollerFlowTest.java`
- Updated `app/src/androidTest/java/dev/chyken/assembl_ar/data/AssemblArDatabaseMigrationInstrumentedTest.java`

No layout XML files were changed; the established shared HUD/video/checklist views are reused.
