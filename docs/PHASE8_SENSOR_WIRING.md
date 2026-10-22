# Phase 8: distance sensor installation and component wiring

## Learner flow

The five authored steps use 14 explicit screens: sensor scan → jumper preparation → mounting demo → mounting scan → prepare/select/connect distance signals → prepare/select/connect LED and speaker → routing demo → prepare/select/connect both trackers and final check.

- A movie ending unlocks its explicit **Ready to Scan** or **Continue to wire preparation** action. It cannot start scanning or move the workflow. Previous uses the screen order, never player state.
- Only the standalone sensor and installed sensor screens request camera analysis or show the scan animation/alignment guide. Neither returns an AR pose.
- A successful scan unlocks Next, retains the “learn more” opportunity, and requires the user's tap. Completed scan screens offer guarded Rescan, including when reviewing an earlier phase.
- Preparation cards precede the three color pickers. The existing ten-color palette, large left-hand diagram, right-hand legend, full-screen zoom and movable compact information card are retained.
- All entering instructions, video narration, corrections, selection prompts and checks use the existing asynchronous `AssemblyVoiceGuide`, which cleans text and dispatches Android TTS on its worker. Android views/dialogs remain on the UI thread.
- Colors, current screen, completion gates, picker draft and each movie position survive recreation. Review uses an isolated local flow and cannot overwrite learner verification. Finishing the final check saves manual section 11 and continues to Phase 9.

## Wiring counts and orientation

Per the user's clarification, **the sensor's VCC and GND already connect through the PCB socket**. Do not install additional jumpers for those pins. Prepare the requested 12-wire pack, use ten wires and keep two as spares:

| Section | Wires | Connections |
| --- | --- | --- |
| 8.3 | 2 | Trigger → D2; Echo → D4 |
| 8.4 | 2 | LED (rail 12) → A1; SPKR (rail 11) → D3 |
| 8.5 | 6 | Each tracker V+ → 5V rail and G → GND rail; physical left S → A2, physical right S → A3 |

Left/right always refer to the robot from above. Looking underneath mirrors the displayed sides: screen-left is physical right/A3. Color selections change visual guidance only. The final checklist checks ten installed leads, not twelve invented electrical connections.

Phase 6 now presents the same preparation/disclaimer card for six female-to-female jumpers before its picker. Phase 4 presents a four-motor-lead preparation card with the disclaimer; its stripped leads still go into screw terminals, not female header sockets.

## Bundled raw assets and recognition

`camera/Phase8AssetCatalog.java` recursively lists the Phase 8 asset tree and registers all image files in these photo folders. Guides, artwork and movies are excluded from ORB training:

| Folder | Current photos | Role |
| --- | ---: | --- |
| `references/Phase8/STEP8_1SCANDISTANCESENSOR/` | 15 | Front/eyes-visible sensor |
| `references/Phase8/STEP8_1SCANDISTANCESENSOR_INVALID/` | 5 | Back/incorrect surface |
| `references/Phase8/STEP8_2DISTANCESENSORHARDWARESCAN/` | 34 | Installed sensor, full and half board |
| `references/Phase8/STEP8_2DISTANCESENSORHARDWARESCAN_INVALID/` | 37 files; 36 used | 15 missing installations and 21 inward-facing installations; one mislabeled duplicate is quarantined |

The actual guide directory is `P8_SCANNINGGUIDE/`; spaces/underscores normalize to the same role. The actual mounting movie is `P8_VIDEOGUIDE/P8_DISTANCESENSORPLACEMENT.mp4`; the requested `P8_DISTANCE_SENSOR_MOUNT.mp4` name is supported too. The routing movie uses the supplied `P8_WHICHHOLESTOINSERTJUMPERWIRESFOR ARDUINOTOLINETRACKERS.mp4` name.

`AssemblyProgressReferenceCatalog` → `VisionReferenceFactory` → `ReferenceDatabase` prepares normal/inverted ORB batches. The Phase 8 matcher has its own request stage, result observation and source-region cache version. It first ranks sensor-local descriptors, then checks board context for the strongest candidates per role. Installed positives require distributed evidence over both cylinder regions plus an independent board homography, so unchanged board texture alone cannot unlock a scan. Two fresh positive frames confirm the gate. Other phases' matcher thresholds and OpenCV initialization are unchanged.

The installed rejection set now includes inward-facing transducers as well as missing sensors. Inward photos use a separate source region over the driver and produce a specific spoken correction to face both eyes outward. `DISTANCESENSORWRONGORIENTATIONTOPVIEW (15).jpg` is byte-identical to a valid outward photo; only its conflicting invalid entry is excluded by path plus SHA-256. The valid copy and all original files are retained. Replacing that invalid file with different bytes re-enables it automatically. Photo recognition cannot certify hidden contacts or electrical continuity; seating and routing remain explicit physical checks. See [Shared scanning](SHARED_SCANNING.md) for preparation policies and regression results.

## Packaged component artwork

Production images are in `app/src/main/assets/schematics/components/`:

- `arduino_nano.png` (replacement used by Phase 6 and Phase 8)
- `distance_sensor.png`
- `ground_signal_rail.png`
- `power_rail.png`
- `line_tracker.png`
- `female_jumper_wires.png` (supplied photograph and its Pololu credit retained)

The five supplied originals are also copied under `schematics/components/sources/`. **The application loads these packaged assets, never a Downloads or Temp path. Deleting the original attachments does not affect the project copies or installed application.**

Built-in imagegen produced five transparent cutouts. Prompt template: “Remove only the white/gray grid background and exterior shadows; isolate this component on transparent PNG; retain geometry, colors, pin count, pin centers and orientation.” For the Nano, the added instruction was to erase incorrect duplicated pin text while leaving the sockets intact. Correct labels are drawn deterministically by the Android renderer. Socket anchors and labels follow the [official classic Nano pinout](https://docs.arduino.cc/resources/pinouts/A000005-full-pinout.pdf), rather than the generated original's repeated D8, D2 and A5 labels. Motor/driver artwork remains packaged as before.

## State compatibility

Phase 8 consolidates the old distance-sensor mounting and sensor-wiring phases. Their source instructions remain in section 11; `sensor_wiring` is a compatibility alias for the combined phase. Old completed mounting checkpoints resume at wiring preparation. Later phase IDs and manual content remain intact; the old next phase (center standoff) is now Phase 9. There are 15 currently authored groups, with the planned 11-phase regrouping still deferred until the remaining mapping is supplied.

## Exact source files for this overhaul

Paths below are relative to `app/src/main/java/dev/chyken/assembl_ar/`.

### New Java classes

- `Phase8GuideController.java`
- `workflow/phase/Phase8SensorWiringFlow.java`
- `model/ComponentWiringColorConfig.java`
- `camera/Phase8AssetCatalog.java`
- `vision/Phase8Observation.java`
- `vision/Phase8PhotoGeometry.java`
- `vision/Phase8ReferenceMatcher.java`
- `ui/JumperWirePreparationDialog.java`
- `ui/ComponentWireColorPickerDialog.java`
- `ui/ComponentSchematicRenderer.java`

### Modified Java classes

- `PhaseAssemblyController.java`
- `Phase4GuideController.java`
- `Phase6GuideController.java`
- `workflow/phase/AssemblyPhaseCatalog.java`
- `workflow/phase/PhaseFlow.java`
- `workflow/phase/PhaseGuideMetadata.java`
- `workflow/phase/PhaseProgressStore.java`
- `workflow/phase/PhaseSessionCheckpoint.java`
- `camera/AssemblyProgressReferenceCatalog.java`
- `camera/VisionReferenceFactory.java`
- `vision/ReferenceDatabase.java`
- `vision/ComponentRecognizer.java`
- `vision/RecognitionRequest.java`
- `vision/RecognitionResult.java`
- `vision/AssemblyVisionAnalyzer.java`
- `data/NeobotSeedData.java`
- `data/SchematicComponentCatalog.java`
- `ui/AssemblyDemoView.java`
- `ui/WiringWorkspace.java`
- `ui/DriverSchematicRenderer.java`
- `ui/SchematicArtworkCache.java`

The new dialogs/workspace are programmatic Android views using the existing theme and guide layout. No layout XML changes were needed.

### Tests and tooling

- New host tests: `workflow/phase/Phase8SensorWiringFlowTest.java`, `camera/Phase8AssetCatalogTest.java`, `model/ComponentWiringColorConfigTest.java`, `vision/Phase8RecognitionTest.java`.
- Updated host tests: `workflow/phase/AssemblyPhaseCatalogTest.java`, `workflow/phase/PhaseProgressTest.java`, `workflow/phase/PhaseDemoScriptTest.java`, `workflow/phase/Phase5MicrocontrollerFlowTest.java`, `data/SchematicComponentCatalogTest.java`.
- New Android Studio tests: `vision/Phase8ReferenceMatcherInstrumentedTest.java`, `Phase8SchematicInstrumentedTest.java`.
- `tools/validate_phase8_matching.py`: source localization and held-out reference-photo regression. This is a host OpenCV probe, not a live tablet benchmark.

## Validation

- The initial overhaul compiled 220 main Java classes and passed 265 host tests. The shared-scanning follow-up adds its own build and regression checks documented in `SHARED_SCANNING.md`.
- The initial 69-photo regression has been superseded by the expanded, content-reviewed catalog described in `SHARED_SCANNING.md`.
- Local offline Gradle debug app and Android test-package builds passed.
- Android instrumentation and actual tablet camera/UI execution are **not** claimed as tested in this update; no APK was installed on the tablet. Run through Android Studio as requested.
