# Trained component recognition

The nine original ONNX models run in OpenCV 4.12 on CPU. Model weights, class
contracts, RGB /255 preprocessing and 512px letterboxing remain unchanged.
The retired ATmega detector and chip-model assets are absent.

## Scheduling

Both guided assembly and the individual tester use AssemblyVisionAnalyzer.
The relevant model is submitted at the beginning of an unresolved checkpoint,
before any ORB failure. The current phase can preload and warm during instructions.
The analyzer never waits on native inference. A measured exclusive model wait
slowed successful scans and was removed. A valid result from either path can
finish immediately; an uncertain or unavailable model retains automatic fallback.

Only one model and one owned RGB inference frame are active. No frame backlog
is allowed. Queries are spaced by max(400ms, min(1500ms, previous inference +
100ms)). A failed photo verification no longer causes the old eight-second neural
backoff. Actual native failures still retry with a bounded delay.

Scope, run tag, count, view and camera geometry isolate every result. Search
windows expire at three seconds. Identity additionally requires a result at most
1.25 seconds old and a current local grayscale structure check. The latter removes
uniform brightness offsets and rejects changed bodies, low-detail patches and
large contrast changes. Pause, reset and close invalidate in-flight results.

## Evidence and authority

TrainedDetection retains actual labels, class-score margin, bounds and temporal
confirmations. Strong 2D identity requires confidence >= .85, margin >= .15,
two independent inferences 150ms–2.5s apart with IoU > .55, useful unclipped
bounds, and no overlapping competing class. These are conservative development
thresholds, not calibrated probabilities. Every requested instance must be
visible together; predictions never accumulate a set across frames.

Accepted predictions use DetectionMethod.TRAINED_MODEL and a separate immutable
TrainedRecognitionEvidence record. They do not invent ORB matches, inliers,
semantic corner order, orientation, solvePnP vectors or a metric pose.
The individual tester can establish a stable 2D lock without requiring fake 3D
translation. Its status text distinguishes that from a pose lock.

| Check | Model role and remaining checks |
|---|---|
| Phase 1 board | Phase 1 identity uses the front/back class; exact orientation or metric guidance still uses its original verifier |
| Phase 2 loose inventory | Phase 2 can establish bracket/motor identity and distinct counts |
| Phase 2 installed top/left/right | Strong current motor detections may replace local motor texture matches at independently projected sites; each detection supports only one site. Board/view RANSAC, top-view transform, bracket evidence and motor material checks remain |
| Phase 3/5 loose | Phase 3 driver / Phase 5 full Nano identity plus independent visible-front geometry |
| Phase 3/5 installed | Model-directed local features; existing installation/direction checks retain authority |
| Phase 7 loose | Model identity/count with local ball-caster or tracker-body geometry, retained because brightness testing found a high-score false proposal |
| Phase 7 installed | Component windows aid existing independent body/mount/context checks |
| Phase 8 loose and generic sensor | Sensor identity plus visible-front geometry; the model alone confuses other kit parts |
| Phase 8 installed | Model windows aid the existing sensor/context/orientation verifier |
| Phase 9 loose | Standoff identity also requires metal-body geometry; wheel identity requires separate simultaneous instances |
| Phase 9 installed | Model windows aid board-relative standoff/wheel checks |
| Phase 10 cable | Model may establish loose identity |
| Phase 10 board/protector and Phase 11 OLED/battery authored checks | Model windows prioritize features; face and installation checks remain |
| Generic individual tester | Same routing for all 15 trained identities. Extension board, protector, OLED and battery retain independent photo identity verification; their model class scores alone do not pass |
| Phases 4 and 6 | Wiring instructions; no scan model |

Lower-confidence predictions remain search windows at .35, with class-aware NMS
at .45. Each physical window gets its own feature sample. Installed checks retain
scene context. A failed window search is followed by the original verifier, with
descriptor/keypoint/nearest-neighbour caches isolated between the two passes.

## Diagnostics and limits

The switch **Trained component recognition (ONNX first)** defaults on. Telemetry
distinguishes ONNX identity, ONNX identity plus placement/orientation verification,
and ONNX search followed by ORB/geometry. It reports phase, inference time,
confirmation count and objects without turning confidence into ORB statistics.
Current appearance, age, score and bounds are recorded for strong candidates.

Cross-component probes found high raw scores on wrong kit parts (including driver
as extension PCB/OLED and Nano as line tracker). Sensor/standoff and Phase 7
predictions therefore require their independent physical-body checks. Confusable
PCB/display classes keep photo identity verification even in the generic tester.
ONNX_PRIORITY_CROSS_COMPONENT_PROBES_2026_10_28.json records raw unfiltered probe
scores; these are not app acceptance rates.

ONNX_PRIORITY_LIGHTING_AUDIT_2026_10_27.json records 185 existing test captures under
original, darker and brighter exposure, including even-sized camera replay
resizing. It is not an independent-room accuracy study. Models still miss many
views, particularly board and some sensor/extension views. Scheduling and
acceptance changes cannot manufacture missing model detections. Training weights
were not modified by this implementation.

See ONNX_PRIORITY_QA_2026_10_27.md for the current candidate and device results.
Earlier TRAINED_MODEL and TRAINED_SCAN_FIX reports describe previous revisions.
