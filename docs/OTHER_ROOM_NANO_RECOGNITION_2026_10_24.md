# Nano placement recognition under different room lighting

## Observed failure

The user-supplied `K9MG0B1q.mp4` is a 67.298-second screen recording (928×592).
It shows Phase 5.3, with a visibly installed Nano and no successful verification.
Visible diagnostics include approximately 9–17-second attempts; at 15 seconds the
HUD reports 801.6 ms feature preparation, 15,632.6 ms comparison, and 16,950.8 ms total.
This was a recognition/search problem as well as an exposure problem.

The old Phase 5 matcher compared the whole descriptor bank, including dense motor
driver detail and PCB background, against every camera frame. Desktop preparation
of the 28 installed/invalid images produced 146,430 reference descriptor rows.
The Nano could have a valid local feature cluster while the independent board
homography failed. That failure also suppressed its measured outline entirely.

## Implemented changes

- `Phase5ReferenceMatcher`: cached body/context partitions and 32-row scheduling
  probes. Full verification uses two ranked positive views, two ranked negatives,
  rotating alternatives, and a retained successful view: at most seven references.
  Other viewpoints remain available on subsequent attempts. Probe scores cannot
  verify a scan or become academic matching-accuracy data.
- `Phase5DescriptorSampler`, `LocalPhotometricRefiner`, `PhotoFeaturePartition`:
  separate feature budgets for Nano identity and nearby PCB context. Reference and
  live context use the same local CLAHE grid. Context descriptors whose support
  patches cross the Nano boundary are removed. A measured Nano pose proposes
  normal/reversed camera sampling windows; fresh PCB features must still verify
  them independently. Two body-recovery and four context-recovery attempts are
  allowed per call. Source images are never read by the live matcher.
- Verification keeps the existing **10 distributed Nano inliers, 16 independent
  PCB inliers, 50% RANSAC consensus, 45% body feature spread, and existing placement
  and reversal tolerances**. Evidence strength is bounded by the weaker independent
  geometry. An actual invalid installation or a proven reversal gives a correction;
  conflicting positive-photo geometry stays unverified without asserting bent pins.
- A measured body can deliver an acquiring outline while board verification is
  pending. It cannot unlock Next. `AssemblyVisionAnalyzer` now distinguishes
  acquiring, verified and wrong-orientation outlines across both camera screens.
- `AdaptiveIlluminationPolicy` and `IlluminationRecovery`: image-estimated input
  selection shared by Assembly and Individual AR tests, across all phase matchers.
  Normal CLAHE and illumination-balanced CLAHE remain available; recognition retains
  the working input, a sustained exposure change reevaluates it, and a target change
  resets the preference. No manual room setting or reference learning is required.
- `GlobalComponentRegistry`: a different part elsewhere on the board cannot veto
  an already localized Nano that is still checking placement. A competing identity
  at the same physical location can still challenge it.
- `ReferenceDatabase`: only installed Phase 5 descriptors get the new `p5local1`
  cache key. Other phase and loose-part cache files retain their keys. Temporary
  native buffers are released and the guided-match mask storage is reused.
- `Phase5GuideController`: matching HUD/TTS guidance asks for the entire Nano and
  nearby PCB labels, with a small increase in distance if the USB connector glares.
  The phase steps, progress format and physical pin/stability checklist are unchanged.

Guided geometry matches do not replace the unconstrained Lowe probe's good/total
counts with a favourable denominator. Sampling/masking time is attributed to spatial
processing; normalization/extraction and descriptor comparisons retain their own timers.

## Recording regression and limitations

Nine evenly spaced frames were extracted. Rendered UI regions were masked to neutral
before resizing the visible camera area to 640×400. They are **compressed screen
recording derivatives, not raw camera frames**. They were never added to the app's
reference/training library.

Desktop OpenCV replay reproduced **0/9** verifications with the prior matcher under
both input-normalization modes. The correction recovered **7/9** sampled views. Two
views remain unverified with the recording's UI obscuring needed information. This
is an engineering reproduction count, not an academic recognition-rate claim or
proof of performance in other rooms.

Eight additional controls passed in desktop replay: three held-out correct views
(normal, reduced exposure, side lighting) and rejection of a real held-out invalid
installation, erased Nano, reversed Nano, displaced Nano, and Nano without PCB context.
The geometric acceptance thresholds were not relaxed to obtain those results.

The final debug app and Android test APK build successfully. **398 host tests pass**,
including automatic lighting selection, room/target changes, cross-component veto
scoping, existing orientation checks, workflow navigation and persistence gates.

## Tablet-native verification (24 October)

The user reconnected tablet **2603ARP14G**. The installed separate instrumentation
runner loaded the freshly built candidate DEX and test fixtures; the main app was
not replaced, cleared or uninstalled. No assembly preferences were changed. The
Samsung phone was not used. Instrumentation temporarily closed the main app.

- **Six of nine recording-derived views verified natively:** `1, 2, 3, 6, 7, 8`.
  Each delivered a measured Nano outline, followed a synthetic 18×8 px camera pan
  through the existing EMA smoothing interval, and rejected both a Nano-only view
  without PCB context and the same board with the Nano erased. Each negative was
  checked four times after a previous successful view. Native LK outputs were
  measured, not predicted/frozen screen polygons.
- The final six-view run took 46.8 seconds **including all recognition retries,
  outline/pan checks and 48 negative-control calls**. Individual positive attempts
  were 0.66–4.57 seconds on this run. Views 1 and 8 needed retries; four passed on
  their first attempt. These are native replay timings, **not camera FPS or a
  guaranteed live scan duration**.
- **Three held-out correct and three actual invalid-photo exposure cases passed**
  at gains 1.0, 0.5 and 1.25. The held-out photos were excluded from the reference bank.
- The existing **12 held-out front/back/installed/invalid Nano rotation cases**
  passed at original, 90° and 180° camera rotations. This exercises the loose-part
  path as well as installed-board matching after the bounded matcher refactor.
- Seven other named native fixture groups passed: installed driver shadow/reversal/
  missing-driver, installed sensor highlight/missing-sensor, dark dual trackers with
  required caster, desaturated standoff/missing-standoff, dual wheels with either
  wheel removed, cable/protector exposure, and mounted protector/bare PCB.

All **10 named native fixture groups** passed (three Nano groups plus seven shared
regressions). The generic candidate runner reports one outer JUnit test; the named
`CandidateReplay` PASS entries are the per-fixture evidence. Final outputs are
`app/build/tmp/other-room-nano/native-final.txt`, `native-final-pass-log.txt`,
`native-shared-pass-log.txt`, `final-host-tests.txt`, and `final-build.txt`.

The tablet recovered **6/9**, whereas desktop OpenCV recovered **7/9**. Frame 4 did
not verify natively in six attempts. Frames 0 and 5 were not included in the native
acceptance run because the rendered/masked panels conceal needed Nano/PCB details;
frame 4 also has a partially masked module. These remain explicit limitations of
this recording regression, not counted as successes. Similarity-proposal and wider
PCB-window experiments did not consistently improve coverage and were removed.
No acceptance threshold was lowered and no recording frame was added as a reference.

Actual camera continuity and the other room's raw-camera results still need a check
after Android Studio deployment. Shared preprocessing and these controlled fixtures
do not establish that every component is verified under every unseen lighting setup.

## Commands

From the Gradle root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python tools/run_host_tests.py
python tools/replay_phase5_recording.py --input-dir app/build/tmp/other-room-nano --opencv-path app/build/tmp/cv-test-deps --controls --baseline
```

The replay command requires OpenCV/numpy and the previously extracted/masked frames;
its eight controls determine the exit status, while recording coverage is reported
separately. Detailed local artifacts are in `app/build/tmp/other-room-nano/`.

Use the existing candidate-code replay procedure with:

```text
vision.MaterialRobustnessInstrumentedTest#recordedNanoCameraAreasRequireFreshModuleAndPcbGeometry
vision.MaterialRobustnessInstrumentedTest#nanoPlacementHeldOutPhotosKeepOrientationUnderLightingChanges
```

The first fixture takes `phase5Frame` as semicolon-separated files in the app's
external `measure` directory. Use raw frames when available; the six native-verified
recording areas can be replayed as explicitly labelled diagnostic derivatives.
Native instrumentation temporarily closes the app and should run after the friend's
active camera test. Main-app deployment remains through Android Studio, preserving data.
