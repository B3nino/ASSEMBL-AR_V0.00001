# Phase 9: Standoff and wheels

## Flow and compatibility

Phase 9 now owns manual sections 12 and 13. Its ten independent screens are:

1. 9.1a — scan the metal standoff.
2. 9.1b — confirm one M3 nut and a screwdriver; inspect their rotatable models.
3. 9.2a — scan both loose wheels in one frame, accepting either hub face.
4. 9.2b — explicitly confirm both wheels are ready.
5. 9.3A — standoff installation video; explicit **Ready to Scan**.
6. 9.3B — top-view standoff installation scan, before wheels are required.
7. 9.3C — mandatory lower-nut and removed/saved-top-screw checks.
8. 9.4A — wheel installation video; explicit **Ready to Scan**.
9. 9.4B — independent left/right installed-wheel verification plus center-standoff cue.
10. 9.4C — mandatory axle-grip/stability confirmation, then Phase 10.

Video completion never navigates. Previous from each work scan returns to its own demo. Completed scans expose the shared guarded Rescan control, which clears that scan and its corresponding physical answers. Model previews, movable/minimizable information, resizable recommended-frame window, manual transport controls and asynchronous narration reuse the established UI. Scanning animation is enabled only while collecting live evidence. No layout XML changes were necessary.

The old wheel-pair phase ID aliases the combined Phase 9. Existing standoff completion is retained, but the new top-screw removal answer is mandatory. Previously identified wheel inventory is retained so users do not have to remove installed wheels for another loose-part scan. Current screen, video positions, viewed demos and individual physical answers survive activity recreation. DEV completion remains separate from learner verification records.

Per the approved roadmap decision, later source content is preserved. OLED-protector installation is now Phase 10; the working catalog has 14 phases until the remaining grouping for the planned 11-phase roadmap is supplied. Wording says **save the top screw for the extension board**. Database content revision 6 refreshes that wording without deleting progress or administrator registrations.

## Packaged assets

All paths are beneath `app/src/main/assets/references/Phase9/`; AssetManager discovers future images in the mapped folders automatically. Guides and movies are not recognition evidence.

| Folder | Current reference images | Use |
|---|---:|---|
| `STEP9_1STANDOFFSCANNING/` | 18 | Loose brass standoff |
| `STEP9_2DUALWHEELS/` | 30 | Wheel pairs |
| `WHEELSREF/` | 17 | Individual front/back hub views |
| `STEP9_4ASSEMBLYSCAN/` | 20 | Complete chassis |
| `STEP9_4ASSEMBLYSCANINVALID/` | 31 | Missing left/right/both wheels |

Both exact videos resolve: `P9_VIDEOGUIDE/P9_STANDOFFPLACEMENT.mp4` and `P9_VIDEOGUIDE/P9_WHEELS PLACEMENT.mp4`.

The actual guide folder is `P9_SCANNINGGUIDE/`; space/underscore normalization is supported. Its standoff image shows a loose part. Since no installed-standoff-only dataset/guide was supplied, 9.3 uses a supplied `INVALIDNOWHEELS` chassis photo as its **pre-wheel guidance image**, with board/standoff evidence from the chassis datasets. That same missing-wheel photo can never certify the completed wheel assembly at 9.4.

Existing model mappings use packaged `models/center_standoff.obj` and `models/wheel_ghost.obj`, plus the packaged nut and screwdriver models. The requested alternate OBJ filenames were not present; existing mapped models provide the rotatable previews.

## Recognition gates and limits

- Phase 9 has an isolated photo matcher; its photos cannot become generic Phase 1–8 identity or AR-anchor candidates.
- CLAHE normalization, normal/inverted ORB variants, local 512-pixel component samples and a 640-pixel scene-context sample handle material contrast and camera scale. Internal ORB pyramids retain scale/rotation handling. This avoids multiplying full-scene descriptors for every photo; the host probe used approximately 773,000 descriptor rows rather than the initial 3.2 million. These figures exclude runtime object overhead.
- Loose wheel windows have independent descriptors and distinct projected centers. A one-wheel sighting never accumulates into 2/2 over later frames.
- Final verification requires distributed board context, separate positive local ORB/homography matches for each wheel, positional agreement with the board, and a center-brass visibility cue in the expected location. Missing-wheel scenes supply context/rejection assistance, never positive wheel identities.
- Two consecutive accepted frames must each contain the complete required evidence. Request generations, capture timestamps and duplicate-frame checks reject stale results after navigation, pause or rescan.
- Camera evidence confirms visible identity/placement. Lower-nut tightness, top-screw removal and axle grip require the explicit physical answers; photos cannot certify those mechanical properties. The standoff cue is localized color evidence tied to a matched board, not a torque or screw-removal detector.
- Source-region and descriptor caches are algorithm/content versioned. Preparation remains on the existing worker, camera preview starts independently, and matching uses the existing analysis worker/timebase. No camera or OpenGL initialization settings were changed.

## Verification

- Host OpenCV source geometry: **116/116** photos localized.
- Host held-out photo probes at maximum 640-pixel camera extent: **68/68** positive photos accepted; **31/31** missing-wheel photos rejected. Each probe photo was excluded from training for its check. Host probes are algorithm diagnostics, not Android-native or live-camera tests.
- Additional host probes: representative standoff/pair/complete-chassis views at 90/180/270 degrees; front/back single-wheel versus paired instances; and the earlier standoff check on a chassis without wheels all passed.
- JVM suite: **289 tests passed**, including flow isolation, stale-frame rejection, mandatory physical answers, guarded rescan, legacy inventory/session migration, source ownership and catalog registration.
- `:app:assembleDebug` and `:app:assembleDebugAndroidTest` succeed for arm64. Native replay and SQLite migration fixtures are included. **Instrumentation and live tablet scanning have not been executed for this update.** Deploy through Android Studio and run `Phase9ReferenceMatcherInstrumentedTest` and `AssemblArDatabaseMigrationInstrumentedTest` on the tablet for the native release check.

## Java files created or modified

- `app/src/androidTest/java/dev/chyken/assembl_ar/data/AssemblArDatabaseMigrationInstrumentedTest.java`
- `app/src/androidTest/java/dev/chyken/assembl_ar/vision/Phase9ReferenceMatcherInstrumentedTest.java`
- `app/src/main/java/dev/chyken/assembl_ar/Phase9GuideController.java`
- `app/src/main/java/dev/chyken/assembl_ar/PhaseAssemblyController.java`
- `app/src/main/java/dev/chyken/assembl_ar/camera/AssemblyProgressReferenceCatalog.java`
- `app/src/main/java/dev/chyken/assembl_ar/camera/Phase9AssetCatalog.java`
- `app/src/main/java/dev/chyken/assembl_ar/camera/VisionReferenceFactory.java`
- `app/src/main/java/dev/chyken/assembl_ar/data/AssemblArContract.java`
- `app/src/main/java/dev/chyken/assembl_ar/data/AssemblArDatabaseHelper.java`
- `app/src/main/java/dev/chyken/assembl_ar/data/NeobotSeedData.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/AssemblyVisionAnalyzer.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/ComponentRecognizer.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase9DescriptorSampler.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase9Observation.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase9PhotoGeometry.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase9ReferenceMatcher.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/RecognitionRequest.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/RecognitionResult.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/ReferenceDatabase.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/AssemblyPhaseCatalog.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/Phase9StandoffWheelsFlow.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseFlow.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseGuideMetadata.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseProgressStore.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseSessionCheckpoint.java`
- `app/src/test/java/dev/chyken/assembl_ar/camera/Phase9AssetCatalogTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/vision/Phase9RecognitionTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/AssemblyPhaseCatalogTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase5MicrocontrollerFlowTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase9StandoffWheelsFlowTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/PhaseProgressTest.java`
