# Phase 8 / 10 / 11 targeted ROI and lighting references

This change addresses the three positive-view failures exposed by the October 25 hygiene audit. It keeps `OrbMatchPolicy`, `VerifiedScanEvidence`, the dynamic outline renderer, LK tracking, and the three phase state machines unchanged. The user approved keeping OpenCV work in the vision layer, applying the existing CLAHE pipeline to raw references, and retaining physical checks for hidden nuts and connector firmness.

## Changes

| Area | Implementation |
|---|---|
| Phase 8 metal barrels | `Phase8ReferenceMatcher` projects the two authored barrel masks from independent board geometry into the live detail crop. Masks include bounded parallax padding. `ComponentDetailExtractor` closes the binary masks with a 3×3 ellipse; camera texture remains untouched. `ReviewedHardwareDescriptorSampler` applies the same closing operation to the reference detail masks. Both barrels must still supply their own real inliers. |
| Phase 10 protector | Three new real views annotate the visible screw heads/feet separately from the U-shaped protector rails and surrounding PCB. `Phase1011DescriptorSampler` samples these anchors locally. The existing rail-material test and independently matched board/protector homographies still apply. A top view cannot establish hidden M3 nut tightness; the physical checklist remains mandatory. |
| Phase 11 OLED | Two new views prioritize the actual header strip and blue/yellow bezel edges, with separate board context. Local sampling uses the existing 2,400-row per-variant limit; final live-point deduplication still applies. Header/bezel anchors do not become extra synthetic matches. |
| Reference scheduling | `Phase1011ReferenceSearch` keeps the existing 32-row sparse-probe budget, reserving up to half for authored hardware anchors and the rest for scene coverage. Rows are unique. This only schedules candidates; it never verifies a component. Rotation and negative-family opportunities remain intact. |
| Cache/catalog | Generated `Phase789VideoReferences` revision 9 and `Phase1011VideoReferences` revision 2 register the new assets and anchor metadata. Existing per-reference cache fingerprints invalidate affected prepared variants. No checkpoint/preference schema changes are involved. |

Matching thresholds are unchanged: Lowe `d1 < 0.75*d2`, Hamming `d1 <= 64`, deduplication, inlier counts/ratios, spread, geometric agreement, and physical completion checks retain their existing requirements. No failed replay assertion was removed or marked ignored.

## Seven packaged real-video references

| Phase | Video | New timestamps | Focus |
|---|---|---|---|
| 8 | `fDOmql_e.mp4` | 73s, 79s | Separate reflective barrel bodies in ambient shadow/tilt, with independent PCB landmarks |
| 10 | `j1H57AGW.mp4` | 162s, 187s, 193s | Two mounting heads/feet, black protector rails, and board context across glare changes |
| 11 | `FZcZdrAF.mp4` | 80s, 85s | Header strip and bezel borders around the mounted display |

The seven JPEGs add **964,343 bytes** and are copied into the project's `app/src/main/assets/references/Phase8`, `Phase10`, and `Phase11` `var_light` folders. The installed app never needs the source MP4s or a Downloads path. Images remain real video pixels; no synthetic lighting or generated component images are used. CLAHE is applied during descriptor preparation/live extraction rather than baked into a second differently normalized dataset.

`tools/ingest_phase789_videos.py` and `tools/ingest_phase1011_videos.py` reproduce the assets and generated Java catalogs. Their manifests retain source-video SHA256, JPEG SHA256, source timestamp, sharpness, body/identity/context masks, and the new hardware anchors. The previously packaged JPEG hashes and held-out manifest records are unchanged. New timestamps are at least two seconds from every held-out timestamp in the same video. These are held-out timestamps from the same recordings, not independent environmental trials.

The reviewed mask sheet is `docs/images/phase81011_roi_masks_20261025.jpg`: green marks body identity, magenta marks screw/header/bezel anchors, and orange marks independent board context.

## Verification

The final candidate hash, complete 48-group ledger, and command outputs are in `TARGETED_ROI_VERIFICATION_2026_10_25.txt`. **All 48/48 named native groups pass** on tablet 2603ARP14G with candidate SHA256 `b6e8c577c7b7020d7649f4bc69e61fa03ba27f7e0bc722d96b45daa99652ea37`. This includes the former positive failures, their missing/inward/occluded rejection cases, and moving-outline assertions. The debug build succeeds; lint reports 0 errors and the same 193 warnings retained by the preceding audit.

The host runner discovers all 130 test classes. Two new tests verify separate barrel identities, independent hardware anchors, retained board context, and exclusion of the failing timestamps from the reference dataset. Total: **704 host tests**.

Checksums of `OrbMatchPolicy`, `VerifiedScanEvidence`, `OpticalFlowTrackerBridge`, `TargetBoundingTracker`, `DynamicComponentOutlineOverlayView`, and the Phase 8/10/11 flow classes are unchanged. No main app was installed, uninstalled, or cleared during replay. Before/after device-side hashes of the existing progress, guided-step and UI preferences, and app database are identical; no contents were exported.

Native results validate the supplied recorded views at tablet sampling and the existing regression selection. They do not establish instantaneous recognition under every light or replace the mechanical/firmness checklists.

## Reproduce

From the `ASSEMBLAR ls/app` Gradle root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python -B tools/run_host_tests.py
python -B tools/ingest_phase789_videos.py --verify
python -B tools/ingest_phase1011_videos.py --verify
python -B tools/prepare_candidate_replay.py
adb -s 368e278b push app/build/tmp/tablet-audit/candidate-code.zip /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run_quality_replay.ps1 -Serial 368e278b -Suite all
```

For normal deployment, run the app through Android Studio with its existing data retained. The next preparation uses the updated cache revision; subsequent scans reuse the existing bounded warm-cache mechanism.
