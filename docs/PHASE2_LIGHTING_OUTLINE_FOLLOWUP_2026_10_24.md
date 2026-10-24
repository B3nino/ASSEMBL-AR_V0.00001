# Phase 2 lighting and missing-outline follow-up — 24 October 2026

## Recording evidence

Inspected `6B0NAz5o.mp4` (40.59 seconds) and `DcccbD9S.mp4`
(79.40 seconds), sampling twelve frames from each and inspecting the transitions.
The first remains unverified through a strongly side-lit top-view attempt. The
second shows a top-view unlock while the chassis is held side-on, a later side
unlock without a persistent object outline, and repeated unsuccessful right-view
attempts. The UI's green preview border is not a measured physical-object outline.

Screen recordings contain rendered panels over the hardware. Masked-camera-area
desktop probes remain unverified; this is not proof that the underlying unobscured
camera frame fails. Recordings were not added to the reference bank. Exact room
recognition still requires raw app captures, not training on the test recording.

## Causes found and corrections

- **Incorrect side-reference crop:** yellow PCB edges/wires joined a motor collar
  to a large mostly empty rectangle. Native replay initially failed even the clean
  left-side reference. `Phase2PhotoGeometry` now removes thin connections before
  grouping side housings and requires 40% contour occupancy. Its version is 7;
  only Phase 2's prepared geometry/descriptors are invalidated.
- **Feature budget spent outside hardware:** `Phase2ReferenceMatcher` proposes
  motor/bracket/shaft windows from a small color mask before the existing ORB
  extraction. Color cannot pass an installation. Per-motor inliers, spatial spread,
  bracket appearance, material support, RANSAC and the physical checklist remain.
- **Unbounded practical search cost:** camera attempts now examine one scheduled
  requested-view photo, one strongest negative and one competing view. Preferred
  photos persist; the top two proposals alternate and an exploration proposal is
  tried on every fourth miss. Normalized references plus automatic illumination
  recovery are used live; artificial photo-negative variants remain available to
  exhaustive diagnostic inspection. No second ORB extraction was added.
- **Top/side confusion:** the top-view homography must preserve a non-reflected,
  non-collapsed local plane at both motor sites (Jacobian condition <= 2.8).
  Ordinary rotation, scaling and moderate tilt remain valid. Partial localization
  can draw acquiring outlines but cannot manufacture completion evidence.
- **Empty bracket band mistaken for installed hardware:** an erased-bracket native
  control exposed a normalized-template false positive. Each of the four top-view
  bracket bands now also needs raw dark material relative to its adjacent motor.
  This uses dark-pixel occupancy rather than mean brightness: valid bands include
  reflective screw heads and light PCB gaps. ORB counts and appearance checks remain.
- **No outline on a successful small/glossy target:** `ImageOutlineTracker` allowed
  eight initialized corners but demanded twelve on delayed catch-up. Catch-up now
  requires at least eight measured correspondences, up to twelve available inliers,
  70% consensus, forward/backward LK, RANSAC and the existing geometry limits.
  Blank/occluded images still clear tracking. Top motor outlines include their rigid
  mounting bands, providing screw/bracket corners for LK alongside smooth plastic.
- **Duplicate work and prolonged load:** `AssemblyVisionAnalyzer` no longer runs a
  generic ORB extraction before the active matcher. Bounded global wrong-item
  discovery reuses captured local descriptors after a miss, extracting a fallback
  only if the matcher supplied none. Heavy live jobs leave a 60–750 ms recovery gap
  based on their elapsed duration; independent preview and LK updates continue.
  This is a duty-cycle bound, not a claim of a completed thermal soak test.
- **Review wording:** a completed top/left view no longer announces all three views
  complete while the user is still reviewing an earlier hardware view.

Tracking/analyzer corrections are shared by Assembly and Individual Component AR
Test. Phase-specific detection changes are confined to Phase 2; Nano, driver,
tracker, sensor and later-phase geometric gates have not been lowered.

## Verification

The arm64 app and test APKs build successfully; 399 host tests pass. Native candidate
replay runs the newly built DEX on tablet `368e278b` / `2603ARP14G` without installing
the main APK or changing learner progress. The separate test runner reports one
outer JUnit invocation; named `CandidateReplay` entries identify individual fixtures.

Final run evidence is saved in `app/build/tmp/phase2-followup/`. Earlier failed runs
are retained: the first exposed the bad crop, and timing runs exposed multi-second
searches. The two-second attempt limit was not raised to conceal those failures.
The tablet reported severe thermal throttling during repeated native runs and
returned to status 0 after an idle interval.

Verified candidate SHA256 before removing redundant soft-deadline bookkeeping
(the live candidate list is already capped at three distinct roles):
`8ea8fc10b15302272aa7aa3a7564f85d1d865f8b9bc0662bf93a53adbaf017f0`.

Final cleanup candidate:
`708600b93a17fd568fb1b16b90c890d3c0a606457739dc1a23ab889c3f5a77af`.
Build successful; bounded recognition and shared post-completion tracking passed
again after this cleanup (`native-cleanup.txt`). Later replay timings were slower
than the warmed run below: right 1,615 ms and top 1,885 ms, still below the unchanged
two-second per-attempt gate. These variations are retained rather than claiming
uniform subsecond performance.

**All 11 named native fixture groups listed below pass on the tablet.**

- All 12 held-out lighting cases (three requested views x normal/dim/bright/side-light
  gradient) recognized on attempt 0, retaining two top-view or one side-view outlines.
  Final per-attempt times ranged from 430 to 1,987 ms, including slower initial
  side-view cases. Do not substitute the faster warmed timings below for this range.
- Full-catalog bounded run: left 408 ms, right 354 ms, top 438 ms. Bare-board attempts
  failed in 149, 143 and 144 ms. Every attempt stayed below the unchanged 2,000 ms gate.
  These are matcher replay timings, not a room-specific live-camera success rate.
- Erased motor/bracket cases cannot certify the requested top view; remaining motor
  geometry can still provide acquiring feedback. Side photographs cannot certify top.
- Correct hardware, 180-degree rotation and dim exposure retain recognition; bare
  boards and erased motors cannot pass. Collapsed/mirrored top-plane controls reject.
- Sparse delayed targets with 8–11 corners catch up; blank frames cannot produce an
  outline. Mounted pairs retain separate outlines while moving.
- The shared analyzer delivers an outline before workflow completion and keeps
  relocating it after completion without emitting additional workflow evidence.
- All tracker-owned native image/keypoint allocations were released through 220
  scan/reset cycles. This is allocation ownership evidence, not a multi-hour app soak.

App data/progress and the installed main APK were not replaced or reset. Main-app
deployment remains through Android Studio as requested. Actual live movement and
the exact remote-room scene still need verification after deployment.

## Reproduce

From the Gradle root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python tools/run_host_tests.py
python tools/prepare_candidate_replay.py
```

Native fixture groups:

```text
vision.Phase2PrecisionInstrumentedTest#boundedLiveSearchHandlesFullCatalogWithoutOneLongSweep
vision.Phase2PrecisionInstrumentedTest#heldOutInstalledViewsKeepLocalHardwareEvidenceUnderExposureChanges
vision.Phase2PrecisionInstrumentedTest#sidePhotographsCannotCertifyTheTopView
vision.Phase2PrecisionInstrumentedTest#topReferenceRequiresBothHardwareRoisAndBareBoardNeverPasses
vision.Phase2PrecisionInstrumentedTest#missingMotorOrBracketNeverPassesAndVisibleMotorCanRemainAcquiring
vision.Phase2PrecisionInstrumentedTest#topViewGateRejectsCollapsedAndMirroredMotorPlanes
vision.ImageOutlineTrackerInstrumentedTest#sparseEightToElevenCornerTargetsCatchUpButCannotTrackABlankFrame
vision.ImageOutlineTrackerInstrumentedTest#mountedPairKeepsTwoSeparateMovingOutlines
vision.ImageOutlineTrackerInstrumentedTest#resetAndCloseReleaseFrameBuffersAcrossRepeatedScans
vision.TargetBoundingTrackerInstrumentedTest#measuredOutlineMovesAfterVerificationAndDelayedResultCannotSnapItBack
DynamicOutlinePipelineInstrumentedTest#completionChangesTheTokenButKeepsVisualLocalizationWithoutNewWorkflowEvidence
```

Deploy the main app through Android Studio, preserving data. In the problem room,
capture top, left and right separately using **Capture current scan frame** and
export the session with recorded frames. Those raw camera images preserve hardware
hidden by the HUD in recordings. A short video remains useful for movement/timing.
