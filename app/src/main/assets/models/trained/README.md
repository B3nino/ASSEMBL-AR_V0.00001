# Local trained component detectors

Nine fixed-input YOLO11n ONNX files are copied byte-for-byte from the local
Phase 1/2 and Phase 3–11 experiments. The JSON companions record class names,
input/output contracts and original SHA-256 values. The runtime validates them.

Input: RGB float32 [1,3,512,512], centered letterbox with padding 114, divided
by 255. No CLAHE. Output: [1,4+classes,5376], without separate objectness.
Class-aware NMS uses .45 IoU; .35 confidence proposes search windows only.
The existing verifier decides identity, orientation, counts and installation.

Guided scans and the individual tester route every trained component to its
respective model, including Phase 1, Phase 2 inventory/top/left/right, and
installed component searches. Phases 4 and 6 remain wiring instructions.
The retired ATmega chip model is removed; Nano uses the full-board phase5 model.

One background worker holds one active trained Net and one owned RGB frame.
Fast successful scans get 250ms before neural recovery can start. Slow/unresolved
scans can start the background query from current tracking pixels. Recent
same-run/view/target/geometry/motion-checked hints are consumed once. Separate
physical boxes receive local sampling; installed checks retain a scene-context sample.
Failure retries the original full-frame search without poisoning its caches.
Query spacing/backoff bounds repeated work; pause/close invalidate old results.

The diagnostic assistance switch defaults on in debug and release. It does not
change learner progress or assembly thresholds. OpenCV owns Net finalization;
temporary Mats are explicitly released. These models still miss objects and
cannot guarantee scan success. New tablet performance and uninterrupted-session
validation are pending for the correction described in
docs/TRAINED_SCAN_FIX_QA_2026_10_27.md.

Replay JSON files are test fixtures, unused by ordinary scanning.
Original upstream Ultralytics license references remain in each contract.
