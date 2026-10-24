# Phase 7–9 video references and tracking refinements

## Scope and preserved decisions

- Phase 8 still requires **10 jumper wires**. Distance-sensor power and ground
  already connect through the PCB; no extra jumpers are requested for those pins.
- Phase 9 starts with hardware preparation, with the standalone standoff scan
  bypassed. The diagnostic tool can still test standoff references.
- Phase 7 keeps the caster demonstration and physical checklist. Its final
  bottom-view scan verifies the caster and both independently mounted trackers.
- Existing dynamic outline rendering, LK optical flow, global identity registry,
  illumination recovery, phase checkpoints, and prior Phase 1/2/3/5 references
  remain in use. This update adds real observations and bounded local matching.

## Copied assets and provenance

The app contains **42 real JPEG references (4,420,837 bytes)** extracted from
the seven supplied recordings. Twenty-two isolated part registrations reuse the
same JPEGs instead of copying image bytes. Runtime recognition opens packaged
assets, never the Downloads MP4 paths. The original recordings can be deleted
without removing the app's references; retain backups if you want to regenerate
or extend the dataset. Demo movies already inside app assets are separate media.

`tools/phase789_video_manifest.json` records source-file SHA256, timestamps,
reviewed body polygons, component identity regions, independent board context,
body-local sharpness, output dimensions, and JPEG SHA256. Thirty replay frames
use different timestamps from the reference frames. These are same-recording
regressions, not an independent room or population accuracy study.

Reviewed regions: [real reference contact sheet](PHASE789_REVIEWED_REFERENCE_REGIONS.jpg).

| Source | Reviewed content |
| --- | --- |
| `pJxv8DuC.mp4` | Loose caster ball, plate and mounting holes in varying reflections |
| `Uzk5p7AF.mp4` | Two loose tracker fronts in dark lighting |
| `DUsK9roS.mp4` | One tracker with different rotations; must never count as two |
| `b_60MVLl.mp4` | Bottom chassis with two independently rotated trackers and caster |
| `fDOmql_e.mp4` | Outward sensor, inward sensor, missing sensor, motion and shadows |
| `Zi6cD-KG.mp4` | Reflective standoff, both wheel faces, two separate loose wheels |
| `vuE18C1A.mp4` | Wired chassis with outward sensor, two wheels and center standoff |

The supplied recordings do not contain a loose sensor front/back set, tracker
undersides, a caster underside, a mounted standoff with its top screw attached,
or a real missing-wheel assembly. Existing photograph datasets remain available
for these categories. No new example of an absent category was fabricated.
The legacy `STEP7_TOPVIEW` alias contains actual **bottom-view** camera pixels.

## Implementation

### Registration and descriptor preparation

- `tools/ingest_phase789_videos.py` stages real decoded JPEGs before publishing,
  adds real padding around narrow loose components, and generates the manifest
  and `camera/Phase789VideoReferences.java` with reviewed regions.
- `Phase7AssetCatalog`, `Phase8AssetCatalog`, `Phase9AssetCatalog`,
  `AssemblyProgressReferenceCatalog`, `VisionReferenceFactory` and
  `UnifiedReferenceCatalog` register nested `var_light` images and distinguish
  standalone requests from assembled-state requests. Existing folder spellings
  and legacy datasets remain supported.
- `Phase7PhotoGeometry`, `Phase8PhotoGeometry` and `Phase9PhotoGeometry` retain
  reviewed masks instead of replacing them with automatic segmentation.
- `ReviewedHardwareDescriptorSampler` and `Phase9DescriptorSampler` prepare
  bounded native/detail samples at cache-build time. Descriptor coordinates are
  mapped back to the source photograph. Local body pools cannot substitute for
  independent board identity. `ReferenceDatabase` versions only affected new
  reference caches, preserving compiled caches for other phases.

### Phase 7

- Dark/neutral body proposals participate even when blue-trim-pot color hints
  are weak or merge both modules into one region.
- Each tracker gets its own descriptor search, rotated body polygon, rigid
  trim-pot identity region, distributed inliers, and distinct mounting position.
  Observations from separate frames cannot be accumulated into a two-part pass.
- Board geometry proposes local windows. It never verifies a missing tracker.
- Caster reflection uses a constrained affine fit for loose scans and a bounded
  mount-local detail pass for the combined assembly scan. Local identity and
  coherent placement remain mandatory.
- Candidate windows and rotating fallbacks replace repeated full-bank sweeps;
  stale one-tracker hints are not refreshed indefinitely.

### Phase 8

- Both physical cylinder regions need fresh local inliers, together with
  separately matched fixed PCB context. Wire paths are excluded from reviewed
  identity masks.
- `GeometricMatchDomain` first restricts descriptor search to the measured board
  neighborhood, then allows a bounded footprint-scaled parallax fallback because
  the cylinders protrude above the PCB. This restriction is a search aid, never
  acceptance evidence. Minimum inliers, coverage and two-eye checks remain.
- Constrained affine fitting can recover shallow cylinder edges where a
  projective fit overfits one eye. Positive and inward families get search slots.
- Missing or out-of-frame sensors cannot pass from the unchanged driver/Nano.
  A motion-blurred inward view at 170 seconds lacks reliable sensor identity and
  remains blocked without claiming a detected orientation. Clear inward views
  must produce an explicit warning and measured outline.
- A native allocation in an early homography rejection path is now avoided.

### Phase 9

- Independent wheel pools and separate geometry are required for two wheels.
  A single wheel cannot satisfy wheel preparation.
- Repeated spokes may seed guided rematching, but final fresh matches still
  require at least 16 inliers, majority consensus, distributed coverage and valid
  geometry. Installed-wheel verification thresholds remain unchanged.
- Complete assemblies require board context, both separate wheel bodies and the
  center standoff. Physical seating and top-screw removal remain explicit
  checklist answers; images cannot establish axle grip or tightening torque.
- Loose-search deadlines now stop unsuccessful searches too. New reviewed
  references use bounded local descriptor pools and rotating candidate windows.
- Loose standoff samples retain real padding around the body so ORB can see
  descriptor neighborhoods at both ends. Keypoint centers remain body-masked.
  **Known limitation:** the individual-tool standoff frames still do not reach
  stable geometric consensus. Their opt-in exploratory fixture remains explicitly
  ignored with that reason; it has not been counted as a passing test. The normal
  Phase 9 bypass is retained. No threshold was lowered to report a success.

## Lifecycle and memory

The existing Phase 3/5 lifecycle fixes remain the implementation: process-owned
asynchronous preload, compiled disk descriptors, bounded concurrent hot cache,
stale-callback epochs, executor-ordered native cleanup, saved
`PhaseSessionCheckpoint`, and CameraX surface reattachment. No second camera
session or Activity restart was added. Native descriptor RAM is bounded rather
than retaining the entire library unbounded on the tablet.

`AssemblyActivity`, `AssemblyVisionAnalyzer` and `VisionPreloadManager` did not
need duplicate lifecycle code. Android may recreate an Activity under genuine
memory pressure; saved state restores the session instead of promising to
prevent operating-system lifecycle events. Actual Recent Apps/screen-recorder
interaction after deployment remains a manual device check.

## Verification

Build and **408 host tests** pass, including phase flow navigation, explicit
checklists, rescan states, wiring counts, session checkpoint restoration and
catalog/geometry tests. All 42 packaged image hashes and reviewed masks verify.

Tablet-native verification uses the authorized 2603ARP14G (ADB serial `368e278b`)
and candidate DEX/resources. No main APK install, activity launch, progress reset,
or live view of the other room was used.

**20 native regression groups pass**, with an additional combined rerun of the
three Phase 7–9 groups. All 21 recorded invocations passed. The exploratory loose
standoff false-negative fixture is excluded explicitly, not counted as a pass.

| Native group | Result |
| --- | --- |
| Real dark caster/tracker views, two distinct trackers, both mounts and caster together | Pass |
| Real outward, inward, wired, missing and out-of-frame distance-sensor views | Pass; blurred 170 stays blocked |
| Wheel face variants, distinct loose pairs, installed wheels and standoff | Pass |
| Individual-tool caster, tracker and wheel; outline callback before success | Pass |
| Dynamic catalog discovery and source-mask tablet sampling | 2 groups pass |
| Dark trackers with caster erased; sensor highlight/missing; desaturated standoff; either wheel erased | 4 groups pass |
| Pause/resume stale-evidence exclusion, native-frame release and warm reference retention | 2 groups pass |
| Completed-step token changes retain visual tracking without new workflow evidence | Pass |
| 220 repeated outline scan/reset cycles release owned frame/keypoint buffers | Pass |
| Prior Phase 1/2 raw-part, top/left/right installation and missing-bracket regressions | 3 groups pass |
| Prior Phase 3/5 driver orientations, Nano placement/reversal/missing and individual-tool outlines | 3 groups pass |

The final combined run covers **28 distinct held-out video views** and 85
matching calls, including repeated negative searches. Median whole-call time was
**1,003 ms**, maximum **2,691 ms**. This aggregate took 116 seconds because it
loads three phase banks and deliberately repeats negative/single-part frames;
that is not the time for one user scan. Successful combined tracker scans include
three separate outlines; installed wheel scans include three separate outlines.

Saved test statuses and selected actual logs:
[native verification evidence](PHASE789_NATIVE_VERIFICATION_2026_10_25.txt).

Limits on interpreting these checks:

- Recognition frames are real video pixels, resized to a 640×360 active image
  inside a 640×400 tablet buffer. They are separate timestamps from references.
- The outline tests seed the existing `TargetBoundingTracker`, then measure LK
  on a controlled 8×3 pixel pan of those camera frames. This verifies measured
  relocation rather than a screen-fixed predicted box. That test transform never
  enters a reference dataset. It is not a new live-room tracking study.
- Full recognition remains heavier than LK tracking. The searches are bounded
  between frames; soft deadlines cannot interrupt an in-flight native OpenCV call.
  A whole matching call can take around 1–2 seconds, occasionally 2.7 seconds in
  this replay. This is
  not a claimed 200 ms matching benchmark or a camera-preview FPS measurement.
- The motion-blurred sensor view and loose standoff limitations above are
  retained explicitly. No recognition rate or diagnostic percentage is invented.

From the Gradle root (`ASSEMBLAR ls/app`):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python tools/run_host_tests.py
python tools/ingest_phase789_videos.py --verify
```

Re-ingestion is offline authoring only and needs OpenCV/Pillow plus the source
recordings:

```powershell
python tools/ingest_phase789_videos.py --source-dir "C:/Users/Hem Bendijo/Downloads"
```

The candidate replay tool carries compiled app/test code and the small new assets
without installing the main APK or resetting progress:

```powershell
python tools/prepare_candidate_replay.py
adb -s 368e278b push app/build/tmp/tablet-audit/candidate-code.zip /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip
adb -s 368e278b shell am instrument -w -r -e class dev.chyken.assembl_ar.CandidateCodeReplayInstrumentedTest -e candidateDex /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip -e candidateCases vision.Phase789VideoReplayInstrumentedTest#allNewVideoCases dev.chyken.assembl_ar.test/androidx.test.runner.AndroidJUnitRunner
```

When present after ingestion, the 30 held-out JPEGs are included only in the
**test candidate archive**, under `assets/replay/phase789/`. They are not APK
references and cannot be matched as templates. If those ignored build files are
cleaned, regenerate them from retained recordings or restore a replay archive.

Additional native selectors:

```text
vision.Phase789VideoReplayInstrumentedTest#individualToolPublishesMeasuredOutlinesForTheReviewedLooseParts
vision.Phase789VideoReplayInstrumentedTest#catalogDiscoveryIncludesEveryReviewedAsset
vision.Phase789VideoReplayInstrumentedTest#reviewedMasksAreUsableAtTabletSampling
vision.CameraPauseResumeInstrumentedTest#pauseDropsInflightEvidenceAndReleasesFramesWhileReferencesSurvive
vision.CameraPauseResumeInstrumentedTest#opticalBridgePauseReleasesLatestImageAndCanResumeTracking
DynamicOutlinePipelineInstrumentedTest#completionChangesTheTokenButKeepsVisualLocalizationWithoutNewWorkflowEvidence
vision.ImageOutlineTrackerInstrumentedTest#resetAndCloseReleaseFrameBuffersAcrossRepeatedScans
```

`standaloneStandoffExploratoryReplay` is a documented opt-in diagnostic with a
known false negative, not part of the passing acceptance suite.

Deploy the **app** through Android Studio using Run, keeping existing app data.
Replay success does not update the installed app. No uninstall is required.
