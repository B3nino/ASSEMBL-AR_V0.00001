# Phase 2 and other-room diagnostic review

> Follow-up: [lighting and outline repair](PHASE2_LIGHTING_OUTLINE_FOLLOWUP_2026_10_24.md)
> records the new video analysis, corrected side-reference crops, final code changes,
> and eleven passing native fixture groups. Its results supersede the pending native
> status in this earlier investigation.

## Supplied evidence

- `EwTHpMAs.mp4`: 761 frames, 23.800 fps, 31.975 seconds, 928×592.
  It shows Phase 2.5 **top-view** scanning. It contains no left/right-view attempt.
  Both motor housings are visible in portions of the recording, while the rendered
  lower-right HUD covers required hardware in the recorded pixels. The underlying
  analyzer does not receive those rendered UI panels; this limitation applies to
  replaying a screen recording, not to the actual camera input.
- `diagnostics-20261024-152642-055`: 434 logged individual-component attempts,
  all with phase 0. These are not Phase 2 assembly results. The recorded build
  predates the latest Nano correction. CSV rows contain real repeated failures,
  with no dropped log rows reported.
- The keypoint inventory has 1,767 variant rows spanning the standalone catalog
  and phases with camera references. It shows assigned descriptor counts; it does
  not prove the pictured object was recognized correctly.

Selected uncontrolled session counts:

| Selected target | Logged PASS / attempts | Median full verification time |
| --- | ---: | ---: |
| Mainboard | 80 / 80 | 632 ms |
| Motor brackets | 23 / 89 | 1,292 ms |
| Arduino Nano | 11 / 16 | 815 ms |
| Line tracker module | 10 / 23 | 1,268 ms |
| Data cable | 0 / 23 | 1,103 ms |
| OLED protector | 0 / 45 | 1,320 ms |

These counts are **logged outcomes, not recognition accuracy or labelled correct
trial rates**. The selected target does not identify what was actually in frame.
Some strongest descriptor probes belong to competing components. The shared
descriptor good/total yield is not the probability that an assembled object is
correct. Existing ORB benchmark thresholds and historical CSV values are unchanged.

`report.txt` covers two collected benchmark target batches; the session CSV includes
later logged attempts as well. All rows are labelled Normal by the image-based
estimator. That label measures coarse exposure statistics, not room lux, reflections
or local detail visibility. TRE, checkerboard pose correctness and registration error
remain unmeasured because no independent ground truth was supplied.

## Phase 2 source correction

- `Phase2ReferenceMatcher`: preserve the two strongest ranked references and retain
  a separate rotating exploration candidate. The previous code rotated the already
  ranked positive list, pushing strong views out of subsequent attempts. The last
  verified reference is still retained. Full installed-view checking visits a bounded
  window, with four minimum checks under the existing soft time budget.
- Installed top/left/right frames use a dedicated 2,400-feature, FAST-10 ORB instance.
  Loose inventory extraction keeps the existing configuration. Reference assets,
  descriptor cache keys and the other phase matchers are unchanged.
- A valid RANSAC geometry can supply **separately measured motor-body outlines**
  before all hardware checks finish. Each outlined body still needs its existing
  local inlier count, spatial spread, projected bounds and RGB material support.
  Partial outlines carry NO_COMPONENT validation and cannot confirm a hardware view.
  A successful view's outlines and reference ID are bound to its selected evidence.
- Existing completion requirements remain: at least 16 scene inliers, 50% RANSAC
  consensus, six inliers per top motor, four bracket/shaft inliers per motor group,
  all four bracket appearance checks, material checks and view-ambiguity gates.
  Side-view gates remain unchanged. Fresh evidence is required per view.
- `Phase2Observation`: a bare-PCB match alone no longer asserts missing hardware
  when a motor housing is visible. A correctly assembled board shares those same
  PCB features. Failure remains a failure; no completion threshold was reduced.
- `Phase2GuideController`: neutral HUD/TTS says installation is not verified and
  asks for visible motors/brackets/shafts. Localized motors get an explicit
  still-checking message. Workflow steps, view order, saved progress and the
  mandatory physical firmness checklist are unchanged.

The earlier Nano-specific matcher changes do not alter Phase 2's hardware gates.
The shared automatic illumination policy applies, but does not establish that this
particular Phase 2 recording is now recognized successfully.

## Verification status

- Final arm64 app and Android-test APK build successful.
- **399 host tests pass**, including the new check distinguishing shared PCB
  matches from confirmed absence and the existing workflow/lighting gates.
- Existing desktop top-view geometry checks passed their six positive/rotated,
  three bare-board and two erased-hardware controls. That helper is a geometry
  reproduction, not an exact Android/native benchmark of this patch.
- UI-masked recording-area probes remained unverified (12 sampled views; exploratory
  1,200/3,200-feature inputs). They were not added to the app's reference library.
  Required physical detail is hidden by the rendered panels. No screen-recording
  acceptance rate or successful recognition claim is made for this scene.
- New native fixtures compile for nine held-out top/left/right exposure cases and
  erased-motor/bracket acquiring-outline checks. The existing bare-board and bounded
  search regressions are also ready. **Native execution is pending**: tablet
  2603ARP14G disconnected before transfer/instrumentation could start. The remaining
  Samsung phone was not used. The installed app and assembly progress were not changed.

Build/test commands from the Gradle root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python tools/run_host_tests.py
python tools/prepare_candidate_replay.py
```

Candidate native fixtures:

```text
vision.Phase2PrecisionInstrumentedTest#topReferenceRequiresBothHardwareRoisAndBareBoardNeverPasses
vision.Phase2PrecisionInstrumentedTest#boundedLiveSearchHandlesFullCatalogWithoutOneLongSweep
vision.Phase2PrecisionInstrumentedTest#heldOutInstalledViewsKeepLocalHardwareEvidenceUnderExposureChanges
vision.Phase2PrecisionInstrumentedTest#missingMotorOrBracketNeverPassesAndVisibleMotorCanRemainAcquiring
```

Artifacts: `app/build/tmp/phase2-other-room/`. Pending native timings and held-out
results must be recorded before describing this correction as tablet-verified.

## Most useful next captures

Use the tablet's **actual failing scan**, in the problem room. For each failing
assembly view, capture three raw frames at slightly different angles/distances,
keeping the entire target and nearby PCB features visible. For Phase 2, capture
top, left and right separately. Label correct/incorrect builds and phase/step.
For trackers include both installed modules together; for cable failures capture
the actual selected loose-cable or connected-cable step as applicable.

In Assembly: developer diagnostic controls → **Capture current scan frame** →
**Return to camera**, then wait for the saved-frame toast. Repeat as needed, then
**Download Diagnostic Logs → Session + inventory + recorded frames (.zip)**.
In Individual Component AR Test use **Capture Sample** and share the `.arpart.zip`.
These contain camera pixels/calibration without rendered HUD overlays.

A 10–15-second screen recording alongside the raw frames helps inspect timing and
overlay movement. Raw app captures are preferable to screenshots or photos of the
tablet. They can be replayed without a live connection to the other room and remain
held-out test evidence until separately reviewed for reference-library registration.
