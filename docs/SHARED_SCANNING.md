# Shared camera-scale reference preparation

## Scope

`ReferenceDescriptorPyramid` replaces the Phase-8-only sampler. `ReferenceDatabase`
uses this policy for bundled photographs, raw-folder discoveries, retained local
reference images and canonical descriptor crops. New administrator registrations
also use it through `AdminDescriptorBlobCodec.computeAndEncode`.

This is the default preparation route for current and future photo-based phases.
Phases 4 and 6 remain wiring flows; no redundant camera steps were added.
Existing compatible administrator blobs remain readable, including imports without
a source photo. Previously imported single-scale blobs are not invented into new
scales; re-registration from their retained image upgrades those descriptors.

## Geometry, identity and workload

- Prepare at 512, 768 and the reference's existing working resolution, without
  upscaling a smaller reference or extracting duplicate levels.
- Retain the original-resolution descriptors and both normal/inverted photometric
  variants. Restore every added keypoint to the original coordinate system before
  physical ROI, homography, corner projection or pose estimation.
- Added flat-component patches must stay entirely within the component or entirely
  within its surrounding context. A coarse descriptor centred inside a small PCB
  must not describe an unchanged surface behind it.
- Keep the original per-level ORB feature limit, at most three levels and two
  photometric batches. Reference extraction is on the existing background loader;
  the versioned app-private cache avoids repeating it on later loads.
- Bump the sampling token in image-backed cache filenames. Old cached single-scale
  descriptors cannot silently bypass the new preparation.
- Matching remains reference-to-camera. Existing best-per-live-keypoint filtering
  prevents multiple sampling levels from creating additional physical observations.
  Phase 2 now chooses the closest match before this deduplication, rather than the
  first extraction level encountered.

### Compatibility policies verified during regression

1. **Phase 3 installed driver:** retain its original fine-sampled descriptor set.
   The PCB is nearly rotationally symmetric. A naive global pyramid caused three
   synthetic backward installations to be falsely accepted; the final policy
   restores all 150 Phase 3 regression decisions. Loose driver scanning still
   receives shared scale-aware preparation.
2. **Phase 7 tracker bodies and installed pairs:** retain their existing native
   reference sampling. Their proven independent window searches already visit
   native, 400, 600 and 800 pixel sampling under bounded analysis deadlines.
   Redundant reference pyramids substantially increased host search work. Caster
   and other reference paths use the shared default.
3. **Phase 8 installed cylinders:** keep the tested multiscale preparation together
   with its cylinder-local and independent board descriptor pools. Cylinders extend
   outside a planar PCB boundary, so the flat-patch guard is inappropriate here.
   Positive and inward reference sets compete before a scan can unlock.

The controllers' scan generations, fresh-frame checks, completion requirements,
inventory counts, rescan confirmations and physical checklists retain their existing
rules. OpenCV initialization, CameraX frame timebase and GL rendering are unchanged.
Tablet recognition latency and cold-cache preparation time still require device
profiling; host photo timings are not tablet performance measurements.

## Phase 8 orientation data

The dynamic catalog currently finds 91 image files in the four photo folders:

| Folder | Registered photos |
| --- | ---: |
| `STEP8_1SCANDISTANCESENSOR` | 15 |
| `STEP8_1SCANDISTANCESENSOR_INVALID` | 5 |
| `STEP8_2DISTANCESENSORHARDWARESCAN` | 34 |
| `STEP8_2DISTANCESENSORHARDWARESCAN_INVALID` | 36 of 37 |

The installed negatives contain 15 missing-sensor views and 21 actual inward-facing
views. Their cylinder region is over the driver, unlike the outward-facing sensor
region beyond the board edge. Correct cylinder evidence still needs independent
board context. An inward match produces `INWARD`, keeps Next locked, and speaks:
“Distance sensor is facing inward! Re-seat it so both sensor eyes face outward,
away from the board.”

`DISTANCESENSORWRONGORIENTATIONTOPVIEW (15).jpg` actually shows the correct outward
orientation and is byte-identical to a valid photo. The conflicting invalid entry
is quarantined only when both its exact asset path and SHA-256 match:

`365e397da8047f3fcd4c0c01308a8fa97b231e623a5d301d152038cfa206cdce`

The file is retained. Replacing its bytes automatically re-enables the entry. The
valid copy remains registered. Guides, videos and schematic artwork never enter
the ORB training catalog. All references remain packaged app assets.

## Validation

Host checks use the same sampling policies, source-coordinate remapping and
live-keypoint deduplication. They do not establish native Android correctness or
live recognition latency. The Phase 8 held-out probe also excludes byte-identical
copies of its target photo from training.

- Phase 8: all 90 registered photos localized; all 90 content-held-out camera-size
  classifications passed, plus the captured valid tablet frame (91/91 probes).
  All 36 installed negatives were rejected, including the 21 inward-facing photos.
- Phase 1–2: 16 source-retention and camera-scale geometry probes, including camera
  rotation. These are same-source preparation checks, not held-out recognition.
- Phase 3: 150/150 held-out photo, rotation, backward, missing-module and added
  screw-head probes after retaining the strict installed-driver policy.
- Phase 5: 76/76 held-out orientation, missing/misaligned module and rotation probes.
- Phase 7: 20/20 dual-tracker camera-frame probes, including identical bodies,
  rotation, blur, grayscale, removed bodies, cropped frames and invalid assemblies.
- Phase 7 additionally: 32/32 scoped caster, loose-component rejection, installed
  caster, rotation and missing-hardware photo probes.
- Build: debug app and Android test APK compilation; 267 host unit tests.
- `ReferenceDescriptorPyramidInstrumentedTest` adds native source-retention,
  descriptor-blob round-trip, component/context support isolation and representative
  catalog checks across camera phases.
- `Phase8ReferenceMatcherInstrumentedTest` adds inward-installation rejection at
  camera scale and rotation, plus an optional replay of the private tablet capture.

Android tests are built for Android Studio; they were not executed on the learner
tablet in this follow-up. No APK installation or learner-state reset was performed.
The private tablet image is an ignored diagnostic fixture, never a bundled reference.

## Exact files changed in this follow-up

Production Java under `app/src/main/java/dev/chyken/assembl_ar/`:

- New `vision/ReferenceDescriptorPyramid.java` (replaces the previous Phase-8-only helper)
- `vision/ReferenceDatabase.java`
- `vision/AdminDescriptorBlobCodec.java`
- `vision/Phase2ReferenceMatcher.java`
- `vision/Phase8PhotoGeometry.java`
- `vision/Phase8ReferenceMatcher.java`
- `vision/Phase8Observation.java`
- `camera/Phase8AssetCatalog.java`
- `Phase8GuideController.java`

Tests:

- New `app/src/androidTest/java/dev/chyken/assembl_ar/vision/ReferenceDescriptorPyramidInstrumentedTest.java`
- `app/src/androidTest/java/dev/chyken/assembl_ar/vision/Phase8ReferenceMatcherInstrumentedTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/camera/Phase8AssetCatalogTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/vision/Phase8RecognitionTest.java`

Tooling and documentation:

- New `tools/reference_sampling.py`
- `tools/validate_phase3_matching.py`
- `tools/validate_phase5_matching.py`
- `tools/validate_phase7_matching.py`
- `tools/validate_phase8_matching.py`
- `docs/PHASE8_SENSOR_WIRING.md`
- New `docs/SHARED_SCANNING.md`

No layout XML or user-supplied image bytes were changed.
