# Trained component search assistance

> Historical recovery-only design. The current model-first implementation and
> its acceptance boundaries are described in ONNX_PRIORITY_IMPLEMENTATION.md.
> See ONNX_PRIORITY_QA_2026_10_27.md for current verification; the scheduling and
> authority descriptions below apply to the preceding installed revision.

The nine trained ONNX files run through the existing OpenCV 4.12 CPU runtime.
Their original class contracts and SHA-256 checksums remain unchanged.
The retired ATmega chip detector, manifest, model file and UI switch were removed.

## Coverage

| Scan | Model and proposed bodies |
|---|---|
| Phase 1 | Phase 1: main board |
| Phase 2 loose inventory | Phase 2: motor brackets or TT motors |
| Phase 2 top/left/right | Phase 2: motors and brackets together |
| Phase 3 loose/installed | Phase 3: motor driver |
| Phase 5 loose/installed | Phase 5: complete Arduino Nano board |
| Phase 7 loose/installed | Phase 7: caster or line trackers |
| Phase 8 loose/installed | Phase 8: ultrasonic sensor |
| Phase 9 loose/installed | Phase 9: standoff and/or wheels |
| Phase 10 loose/installed | Phase 10: cable, extension board, protector |
| Phase 11 loose/installed | Phase 11: OLED or battery |
| Individual tester | Same component-to-model map, including phase-zero generic requests and specialized reference targets |
| Phases 4 and 6 | Wiring instructions, without a scan model |

Installed main-board checkpoints select the current phase's component model.
For example, Phase 2 hardware verification uses motor/bracket predictions,
rather than a main-board model merely because the workflow target is main_board.

## Runtime and verification

Fast successful scans get 250ms without neural contention. If the scan remains
unresolved or the verifier is still busy, current tracking frames can start one
background query. The detector never waits for the neural worker. At most one owned RGB frame is
outstanding, with no frame backlog. Current tracking frames can submit recovery
while a slow photo verifier is busy.

Models use RGB, centered 512x512 letterboxing with padding 114, /255 normalization
and class-aware NMS at .45. The .35 confidence threshold proposes search windows;
it is not an assembly acceptance probability. At most six relevant boxes survive.
Recovery windows allocate local features to separate physical parts. Installed
checks also retain a bounded whole-scene sample for independent board/context verification.
Loose Nano recovery does not duplicate its local sampling in a second detail extractor.
Local recovery preserves native image detail before per-window downsampling,
rather than cropping a previously downsampled whole-frame image.

Hints are single-use, at most three seconds old, and require matching run token,
checkpoint, target, required count, requested view, image geometry and luma motion
checks. One proposal verifier pass is followed by the original full-frame hybrid
search on failure. Failed hints cannot retain adaptive-detail profiles or
contaminate full-frame descriptor/keypoint/nearest-neighbour caches.
Proposal misses do not advance the individual tester's reference-bank failure
counter.

Neural predictions do not construct RecognitionResult, VerifiedScanEvidence,
ORB inlier counts, PnP vectors or completed assembly observations. The existing
orientation, mounting, view, distinct-instance and physical checks still decide
completion. There is no trained Uno model: Phase 5 is Arduino Nano.

## Continuous operation

A new checkpoint/run clears camera-frame caches, board anchors, tracking state,
illumination history and pending neural results. Partial bracket/motor counts
keep recovery enabled. Recovery eligibility is decided after final mismatch
validation. Losing a target reopens the original detector automatically.

Spacing is at least 1.5 seconds, increased according to inference duration.
Two unsuccessful proposals back off eight seconds. Invalid/missing model assets
disable assistance for that model while the ordinary verifier continues.
Transient native failures retry automatically with a bounded delay.

Guided reference loading and tester reference/camera setup retry automatically.
A tester frame-analysis exception logs the failure and continues on the next
frame instead of pausing the test and requiring Start. All owned RGB/gray Mats
are released; early neural submission shares the frame's RGB allocation with
later color/shape inspection.

The diagnostic switch **Trained component search assistance** defaults on in
debug and release. It can still disable assistance for comparison without
altering learner progress. There is no separate legacy Nano switch.

Only one trained Net is strongly held per analyzer. OpenCV's Java wrapper owns
Net finalization; temporary Mats are explicitly released. Continuous native
memory behaviour must be measured on the tablet; host tests cannot establish it.

## Validation

See TRAINED_SCAN_FIX_QA_2026_10_27.md for this revision. Earlier tablet reports
describe the previous restricted implementation and do not validate this revision.
The native suite includes uninterrupted board, inventory, top/left/right and Nano
scans, component routing in both screens, cache isolation, multiple physical ROIs,
negative views and a camera/inference memory soak.
