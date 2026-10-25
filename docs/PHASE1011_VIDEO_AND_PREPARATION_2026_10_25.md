# Phase 10–11 real-video references and preparation repair

## Dataset

`tools/ingest_phase1011_videos.py` decodes the three supplied recordings. It copies **31 real JPEG references (2,430,995 bytes)** into packaged `references/Phase10/…/var_light/` and `references/Phase11/…/var_light/` folders. `tools/phase1011_video_manifest.json` records source hashes, timestamps, source dimensions, body/identity/context polygons, body-local sharpness, and output SHA-256 hashes.

The generated `camera/Phase1011VideoReferences.java` contains the reviewed geometry. Nineteen positive loose-part registrations reuse those same pixels in the Individual Component Test. Bottom-face rejection photos are not exposed as loose positive targets. The main assembly and individual tools use the same descriptor preparation.

Twenty-five different timestamps are saved as test-only fixtures: 24 clear positive/negative views and one motion-blurred battery view. They are not included in the production reference graph. These are same-session replay regressions, not an independent-room accuracy study.

Video roles:

- `j1H57AGW`: cable heads/ribbon, black U-shaped protector, extension-board front and genuine solder-face bottom, protector fitted to a loose extension board.
- `FZcZdrAF`: both battery label faces, OLED front and genuine back, OLED fitted to the stacked board, battery beside the OLED on the top board.
- `nHkNoO6g`: wired mainboard with the extension board absent. Used as negative replay evidence, not mislabeled as a valid stacked assembly.

The recordings do not establish a distinct protector underside, a stacked board before OLED insertion, hidden M3 nut torque, or electrical continuity through the battery plug. Existing stacked-board references remain available. No synthetic replacements or guessed negative labels were created. Physical checklists remain responsible for hidden fasteners, seating firmness, levelness, and plug security.

## Production changes

| Files | Result |
|---|---|
| `Phase1011VideoReferences`, `Phase10AssetCatalog`, `Phase11AssetCatalog`, `AssemblyProgressReferenceCatalog`, `VisionReferenceFactory`, `UnifiedReferenceCatalog` | Register nested real-photo variants and authored masks; retain existing folder aliases and source references; share loose registrations across both camera tools. |
| `Phase10PhotoGeometry`, `Phase11PhotoGeometry` | Preserve reviewed per-frame geometry instead of overwriting it with automatic silhouettes. |
| `Phase1011DescriptorSampler`, `ReferenceDatabase` | Sample physical body/header regions and independent board context at source and small-part scales. Bound new variants to 2,400 descriptor rows. Release temporary native matrices. Scope descriptor revision changes to the new reviewed references. |
| `Phase1011ReferenceSearch`, `Phase10ReferenceMatcher`, `Phase11ReferenceMatcher` | Rank a bounded reference window, alternate an exploration candidate in the first slot during search, and retain the last verified reference when locked. Exploration cannot be starved behind a frame deadline. Sparse ranking scores never count as verification evidence or diagnostic good-match percentages. |
| `Phase10ReferenceMatcher` | Budget local recovery attempts; use polygon containment for oblique bodies. Reviewed extension-board coverage is measured over the actual header/button identity mask, with at least two separate regions containing three inliers each. Existing RANSAC/inlier requirements remain; blank PCB margins do not determine coverage. Protector material evidence remains independent of printing visible through its opening. |
| `Phase11ReferenceMatcher` | Preserve independent board and local OLED homographies, local detail sampling, placement checks, and competing real-back references. |
| `PhaseProgressSummary`, `ProjectSelectionActivity`, `ProjectDetailActivity`, `strings.xml` | Show current catalog phase totals (11), phase completion and percentage consistently in kit selection/detail. Keep legacy 18-section storage IDs/checkpoints intact for migration; old partial certificates cannot finish newly combined phases. Remove outdated fixed marketing counts. |

The compilation failure after 387 is separately documented in `REFERENCE_PREPARATION_FIX_2026_10_25.md`. It was a 257-byte generated filename for reference 388, not a lack of storage. Bounded SHA-256 names and short atomic-write filenames repair the shared preparation gate for all phases.

## Flow and lifecycle preservation

No replacement phase state machine was added. Existing `Phase10ExtensionBoardFlow` and `Phase11OledBatteryFlow` retain explicit demo → scan/checklist taps, two fresh scan confirmations, mandatory physical answers, rescan invalidation, review navigation, checkpoint restoration, and final celebration/learning routes.

Battery instructions continue following the supplied **top-board placement beside the OLED** demo. Phase 8 remains at **10 jumper leads**, and the standalone standoff scan remains bypassed.

`VisionPreloadManager`, `AssemblyActivity`, and `AssemblyVisionAnalyzer` already have process-owned asynchronous preparation, bounded native hot caches, checkpoints, stale-frame rejection, and pause/resume buffer management. Those mechanisms were retained rather than duplicated. Shared `ReferenceDatabase`/cache publication was fixed underneath them. Native pause/resume regressions passed; physical Recent Apps/screen-recording interaction with the final app has not been claimed as a completed manual test.

The dynamic outline view, target tracker, optical-flow bridge, universal registry, illumination recovery, and Phase 1–9 ROI logic remain in use. The final Phase 7–9 recorded-frame regression is included in the verification run.

## Verification procedures

From the Gradle root (`ASSEMBLAR ls/app`):

```powershell
python tools/ingest_phase1011_videos.py --verify
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python tools/run_host_tests.py
python tools/prepare_candidate_replay.py
```

Re-extracting requires the original recordings and a Python environment with OpenCV, NumPy and Pillow:

```powershell
python tools/ingest_phase1011_videos.py --source-dir "C:/Users/Hem Bendijo/Downloads" --opencv-path app/build/tmp/cv-test-deps
```

Native candidate replay uses the connected tablet and the existing test package without installing the main app or launching progress-changing activities:

```powershell
adb -s 368e278b push app/build/tmp/tablet-audit/candidate-code.zip /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip
adb -s 368e278b shell am instrument -w -r -e class dev.chyken.assembl_ar.CandidateCodeReplayInstrumentedTest -e candidateDex /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip -e candidateCases vision.Phase1011VideoReplayInstrumentedTest#phase10LoosePartsAndRealBoardBottomRequireCorrectOrientation dev.chyken.assembl_ar.test/androidx.test.runner.AndroidJUnitRunner
```

Other selectors in `Phase1011VideoReplayInstrumentedTest` cover missing protector/top board, OLED front/back, missing OLED, individual-tool callback order, measured LK outline movement, and the motion-blurred battery. The latter may remain searching and is excluded from clear-view recognition success counts; no identity is forced from blurry pixels. `WholeLibraryPreparationInstrumentedTest` checks the actual former failure, cold/warm reuse, storage-error handling, and the entire persisted library.

Host suite: **417 passed**. The source manifest verifies all 31 copied JPEGs and all 25 train/replay-disjoint timestamps. The APK and Android test APK builds succeeded.

All **eight final native groups passed**; full output and measured results are in `PHASE1011_NATIVE_VERIFICATION_2026_10_25.txt`:

- All 15 clear positive views recognized; all nine back-face/missing-hardware views blocked. The four clear back-face views produced explicit orientation warnings. Correct matches and orientation warnings retained measured LK outlines under the controlled pan check.
- All five loose-part identities recognized through the actual individual-tool analyzer, with outline callbacks preceding success callbacks.
- The separate blurred battery case also obtained genuine matching evidence after adding the other printed battery face. It remains excluded from clear-view success counts.
- Full-catalog preparation completed **1,081** discovered registrations, including the formerly failing real reference. The persisted index was reopened and every registration validated. An immediate warm pass took **301 ms** and rewrote no descriptors; the preceding cache/registry initialization took approximately **26.4 seconds**. This is not a 301 ms cold-start claim.
- The Phase 7–9 aggregate replay passed with the existing dual-tracker/caster, outward/inward sensor, two-wheel/standoff, missing-component, and measured-outline gates intact.

The final Phase 10–11 replay recorded 65 scored inspection calls: median **1,046.9 ms**, maximum **2,108.5 ms**. These are whole ORB/geometry inspections, not camera frame times. The camera and outline tracker retain their separate frame scheduling. No claim of a universal 200 ms recognition time or a live camera FPS measurement is made.

## Deployment and source-file independence

Deploy the `app` configuration from Android Studio to 2603ARP14G, keeping app data. Resume the existing phase. No uninstall, storage reset, or progress migration is required.

The app reads packaged JPEGs and its local compiled cache. It does **not** open these recordings from Downloads at runtime. Deleting the imported original MP4s will not break scanning. Keep originals only if later re-extraction is desired; do not delete the app's packaged reference/guide assets.
