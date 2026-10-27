# ONNX priority and identity authority — 27–28 October 2026

This revision gives the existing models an immediate scan-start query and a
separate bounded 2D identity path in both scanner screens. It preserves the
previous cache isolation, retries, phase routing, Nano chip-detector retirement,
and learner data. ONNX_PRIORITY_IMPLEMENTATION.md describes the acceptance boundary.

## Final code verification

- App and Android test APKs compile successfully; release Java compilation passes.
- All 780 JVM tests across 146 classes pass using tools/run_host_tests.py. Normal
  Android unit compilation encounters an existing desktop AWT logo test; the
  repository host runner compiles and executes that test too.
- All 19 final model/authority checks and five final affected pipeline/lifecycle
  rechecks pass on tablet 368e278b. An earlier 12-check compatibility sweep also
  passed; unchanged photo matchers were not rerun unnecessarily after scheduling
  and class-guard changes.
- All nine original model hashes/contracts and actual APK model bytes are audited.

The prefinal run passed 17 of 19 groups. Two new positive fixtures failed: one
small Nano crop lost confidence under even-sized camera replay resizing; another
fixture omitted the target identity from the database and triggered its existing
guard. These failures were not addressed by lowering acceptance thresholds.

The corrected positive fixture uses another existing retained Nano view. A full
shared-analyzer test uses only a nonmatching bottom-face reference to prove ONNX
acceptance separately from ORB. Continuous CameraX-style delivery passes; the
earlier synchronous replay starved current-frame submission and correctly failed
the stricter identity freshness check. The final live test confirmed Nano identity
with no ORB matches or metric pose in 1.374 seconds after model warmup.

An exclusive 850ms model wait was rejected after it slowed successful replay
scans. The final implementation starts inference first and allows either valid
path to finish without waiting for a timer. Two complete board → bracket set →
motor pair → top → left → right → Nano cycles pass in the same analyzer, with no
Rescan/reset/restart. The 14 checkpoint times span 109–634ms. Native heap at the
end of the second cycle is lower than at the end of the first; this is a bounded
replay measurement, not proof of leak-free operation in every session.

During the final 640x480 camera/inference soak, sensor delivery measured 28.84 FPS
without inference and 26.89 FPS with 27 inference requests. Buffer/tracker p95
measured 10.24ms and 20.31ms respectively. The last forward took 806.74ms; reported
positive native growth after warmup was zero. No universal end-to-end speedup is
claimed from this measurement.

Cross-component probes exposed raw high scores on wrong kit parts. Real native
rejection tests now include Nano-as-tracker, driver-as-sensor/extension/OLED and
OLED-as-standoff. Sensor/standoff/Phase 7 use independent body geometry; confusable
PCB/display classes retain photo identity verification in both scanner screens.
No confidence threshold was lowered to pass these checks.

Final debug APK SHA-256:
`dabfdd2d9079a5e20110a800edb0a38b7fd07d6dcc676a8a46d041e20f356996`

Final candidate replay archive SHA-256:
`ab9a30f962de543e497a97ce6b384427e8618a0d4c7de114d5d0475bc1aefcda`

The in-place update installed successfully on 28 October 2026. The installed APK
SHA-256 matches the audited debug APK above. The app launched successfully into
MainActivity. No uninstall or data clear was performed. The following hashes are
identical before tests, after tests and after installation:

| Saved data | SHA-256 |
|---|---|
| assembly_phase_progress.xml | 390c4508d133bb76cdbfb948c33c12f7f0e0530fbfaa3a459fd9a59ff8cf78fb |
| guided_assembly_steps.xml | 3325d2a819fdd8062c2cdc48a09b995c9b012915bcdf88b1cf9742a7f057c793 |
| assembl_ar.db | f8540c308cb46ff4f36f626b2bcea862870a055619e089d50cd811245a3b956b |

ONNX_PRIORITY_DEVICE_VERIFICATION_2026_10_28.json binds the exact installed APK,
candidate archive, 19 final model groups, five final rechecks, earlier 12-check
compatibility sweep, 14 continuous checkpoints and camera measurements.

## Limits

The brightness audit covers 185 existing test images with three exposure variants.
This is development evidence, not independent-session calibration or an accuracy
claim. A high-score dim Phase 7 false proposal motivated a retained independent
shape check. Several models have low coverage at the authority threshold; automatic
fallback remains necessary. Original weights are unchanged.

Retained-image replay through CameraX-style YUV processing is distinct from a
physical full assembly session. Camera delivery measurements do not measure
displayed UI FPS or sustained thermal behaviour.
