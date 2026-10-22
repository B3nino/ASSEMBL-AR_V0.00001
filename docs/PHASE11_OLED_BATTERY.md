# Phase 11 — OLED display, battery and learning hub

## Authored flow

The final roadmap has **11 phases**. Manual sections 17 (OLED) and 18 (battery) remain intact and are saved together by the new final phase. Existing completed sections and administrator references are retained by the version 8 canonical-content migration. Old battery sessions resolve to Phase 11 with OLED evidence preserved and battery verification still required.

| Screen | Evidence / action |
| --- | --- |
| 11.1 | Front OLED scan; actual bottom photos provide flip rejection |
| 11.2A | Battery pack scan |
| 11.2B | Dedicated tape / strap preparation, explicit I Have These |
| 11.3A | OLED insertion video; explicit Ready to Scan |
| 11.3B | Installed OLED + independent surrounding-board match; Next unlocks after confirmation |
| 11.3C | Mandatory OLED seating and four-pin physical check |
| 11.4A | Battery video; explicit Confirm Battery Seated |
| 11.4B | Mandatory battery retention and power-plug check |
| 11.5 | Save both source sections, then open completion and learning choices |

Battery text follows the supplied video and the user's clarification: **upper extension board beside the OLED**, rather than the lower chassis bay. Power remains off during installation.

Playback completion never advances the flow. Scan, video, preparation and checklist screens are isolated. The shared compact draggable HUD, 3D viewer, narrated videos, explicit Next unlock, Previous, guarded Rescan, lifecycle checkpoints and DEV controls are retained. DEV completion opens an explicitly labelled preview without certifying learner records. Failure to save offers a retry before any learner celebration.

## Packaged assets

No Downloads links are used. The existing photos, videos and guides are packaged from `app/src/main/assets/references/Phase11/`:

- `STEP11_1OLEDSCAN/`: 11 front OLED photos (also accepts the requested `STEP11_1OLEDDISPLAYSCAN/` alias).
- `STEP11_1OLEDSCANINVALID/`: 8 actual underside photos.
- `STEP11_2BATTERYPACKSCAN/`: 14 battery photos (accepts `STEP11_2BATTERYSCAN/`).
- `STEP11_3OLEDASSEMBLYSCAN/`: 15 full/half-board mounted photos (accepts `STEP11_3OLED_MOUNTED_TOPVIEW/`).
- `P11_VIDEOGUIDE/P11_OLEDINSERTION.mp4` and `P11_BATTERYPLACEMENT.mp4`.
- `P11_SCANNINGGUIDE/GUIDEFOROLEDSCANNING.jpg`, `GUIDEFORBATTERYPACKSCANNING.jpg`, `GUIDEFOROLEDASSEMBLYSCANNING.jpg`.

The existing rotatable models are `models/oled_096_i2c.obj` and `models/battery_pack_7v_12v.obj`, selected through canonical component IDs. The power-switch guide reuses the packaged Phase 1 mainboard photograph, zoomed to the lower edge, with the actual small slider immediately left of the barrel socket marked. It is an illustrative photograph, not a camera AR overlay.

## Recognition and preparation

- New Phase 11 scoped CLAHE/ORB matcher, immutable request/result observations and generation-tagged frame gates; no live ghost pose.
- Source ROI clustering joins the OLED's split blue PCB strips under reflection and excludes tires / unrelated cable patches. Installed verification requires distributed OLED inliers and an independent board homography with consistent placement. Board texture alone cannot certify the OLED.
- A rotating candidate budget and preferred-view reuse limit repeated angle work; both front and bottom evidence are considered during standalone OLED scans.
- `ReferenceCacheIndex` identifies photos from APK asset CRC/size plus geometry, preparation revision and ORB configuration, avoiding repeated full-JPEG reads on warm bank loads.
- One cancellable, resumable whole-build preparation pass compiles the complete registered catalog. Each prepared native photo is released immediately, and each finished cache entry is checkpointed. Existing caches are imported rather than re-extracted. Unusable source photos are logged and excluded as with the existing scoped loader; replacing a source invalidates its entry. Failure to persist the compiled library is surfaced with retry.
- The active bank and one upcoming bank are retained; old banks retire after in-flight camera work. Ordinary forward navigation adopts the prepared next bank. Distant DEV jumps / uncached backward visits may briefly show Opening scanner, rather than a new per-step compilation counter.
- CameraX viewport/timebase, native initialization and camera analysis executor remain unchanged. Preparation, matching and speech retain separate workers.
- First preparation can take time depending on the catalog/tablet. No measured tablet startup-time claim is made in this change.

## Post-assembly learning

- Completion milestone, elapsed time, actual mainboard ON/OFF location, programming, retrospective and home routes.
- Offline block workspace: add, edit, reorder and remove motion, wait, LED, tone, distance and tracker blocks; PWM and timing bounds; starter program; persisted draft.
- Deterministic on-screen block simulation. It does not send hardware commands or execute arbitrary edited C++.
- Editable generated Arduino C++, with Phase 6 motor pins D5/D9 and D6/D10, and Phase 8 Trigger D2, Echo D4, LED A1, speaker D3 and tracker A2/A3 pins.
- Export via Android's document picker to an `.ino` file. Custom C++ is retained, and replacing it through block edits requires confirmation. Hardware compilation/upload uses the Arduino IDE; no upload engine is claimed.
- Read-only 11-phase visual retrospective with component roles/electrical principles and the existing saved-color wiring-diagram library.

## Verification

- Host Java compilation: 253 main Java classes passed.
- Host JUnit suite: **336 tests passed**, including route isolation, stale/repeated frame rejection, pause/resume, snapshot/legacy migration, 11-phase section ownership, scoped photo registration, cache source/config provenance, block serialization, sketch pin assignments and simulation behavior.
- OpenCV host photo probes: **48/48** held-out Phase 11 photos recognized/classified after 90-degree rotation and downscaling, including the eight bottom views as rejection evidence. Mounted OLED removal probes: **15/15 rejected**. These are host algorithm probes, not a live camera result.
- Android app and instrumentation APK compilation passed. New native fixtures cover held-out rotated recognition, OLED removal rejection, interrupted library preparation and warm-cache reuse. They are compiled, **not executed on the tablet** in this turn.
- The mounted-OLED correction was replayed using the production Java/OpenCV classes on the authorized 2603ARP14G tablet through an isolated diagnostic process: **12/12 actual 640×400 camera frames passed**, **2/2 rotated checks passed**, **4/4 masked-OLED checks rejected**, and blank input rejected. Mean successful-frame recognition time was **550 ms** with the diagnostic process using two OpenCV threads. This tests the native matcher with real captured frames, not the app UI transition.
- After the user deployed through Android Studio, the live tablet screenshot confirmed Phase 11 / 11.3B / screen 5 of 9 displayed “Step complete · Next step unlocked,” a green preview border and enabled Next Step button. The Rescan control remained available. No navigation or physical checklist answers were entered on the user's behalf. Diagnostic capture and metrics logging were restored to normal afterward.

## Mounted OLED camera correction

The original native preparation mask intersected the OLED quadrilateral with the full-board feature region. As a result, it retained OLED features but discarded the independent board context which the installed verification required. Host probes had not reproduced that production mask; the actual tablet replay exposed it. The installed Phase 11 mask now retains the board region, and a regression asserts that every prepared mounted photo contains both module and context keypoints.

The tablet analysis stream is 640 × 400. Mounted OLED reference preparation now also compiles cropped detail at 96/160/320 pixels, remaps those keypoints to source coordinates, and stores them in the normal/inverted cached batches. Live verification matches independent PCB context first, searches a bounded 480-pixel OLED window, and requires distributed OLED matches coherent with the board. Board-only evidence is displayed as “Board located” and cannot unlock the step. Reference candidates advance fairly under the frame budget.

Only the 15 mounted-OLED descriptor entries change version; other phases and standalone OLED/battery caches keep their keys. The existing two-confirmation-frame unlock and guarded rescan / physical checks are unchanged. Captured camera frames remain diagnostics fixtures, outside the reference catalog and APK assets.

## Exact source / resource / test file inventory

- `app/src/androidTest/java/dev/chyken/assembl_ar/vision/Phase11ReferenceMatcherInstrumentedTest.java`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/dev/chyken/assembl_ar/AssemblyActivity.java`
- `app/src/main/java/dev/chyken/assembl_ar/AssemblyRetrospectiveActivity.java`
- `app/src/main/java/dev/chyken/assembl_ar/CompletionActivity.java`
- `app/src/main/java/dev/chyken/assembl_ar/Phase11GuideController.java`
- `app/src/main/java/dev/chyken/assembl_ar/PhaseAssemblyController.java`
- `app/src/main/java/dev/chyken/assembl_ar/ProgrammingPracticeActivity.java`
- `app/src/main/java/dev/chyken/assembl_ar/camera/ActiveReferenceScope.java`
- `app/src/main/java/dev/chyken/assembl_ar/camera/AssemblyProgressReferenceCatalog.java`
- `app/src/main/java/dev/chyken/assembl_ar/camera/Phase11AssetCatalog.java`
- `app/src/main/java/dev/chyken/assembl_ar/camera/VisionReferenceFactory.java`
- `app/src/main/java/dev/chyken/assembl_ar/data/AssemblArContract.java`
- `app/src/main/java/dev/chyken/assembl_ar/data/AssemblArDatabaseHelper.java`
- `app/src/main/java/dev/chyken/assembl_ar/data/NeobotSeedData.java`
- `app/src/main/java/dev/chyken/assembl_ar/learning/NeobotProgram.java`
- `app/src/main/java/dev/chyken/assembl_ar/learning/ProgramSimulator.java`
- `app/src/main/java/dev/chyken/assembl_ar/ui/LearningWorkspaceUi.java`
- `app/src/main/java/dev/chyken/assembl_ar/ui/PowerSwitchGuideView.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/AdminDescriptorBlobCodec.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/AssemblyVisionAnalyzer.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/ComponentRecognizer.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase11DescriptorSampler.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase11Observation.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase11PhotoGeometry.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase11ReferenceMatcher.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/RecognitionRequest.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/RecognitionResult.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/ReferenceCacheIndex.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/ReferenceDatabase.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/AssemblyPhaseCatalog.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/Phase11OledBatteryFlow.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseFlow.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseGuideMetadata.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseProgressStore.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseSessionCheckpoint.java`
- `app/src/main/res/layout/activity_completion.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/dev/chyken/assembl_ar/camera/Phase11AssetCatalogTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/learning/NeobotProgramTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/vision/Phase11RecognitionTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/vision/ReferenceCacheIndexTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/AssemblyPhaseCatalogTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase10ExtensionBoardFlowTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase11OledBatteryFlowTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase5MicrocontrollerFlowTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/PhaseProgressTest.java`
