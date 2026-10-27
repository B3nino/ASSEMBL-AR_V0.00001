# Trained model integration — 27 October 2026

> Historical validation of the earlier restricted implementation. The newer
> continuous-scan correction supersedes its routing and removes the chip model.
> See TRAINED_SCAN_FIX_QA_2026_10_27.md for the current revision and pending device checks.

Nine original ONNX models and their contracts are bundled and byte-verified. The shared assembly/individual camera analyzer uses bounded background recovery for reviewed loose-component stages. Successful initial photo scans bypass inference. Neural windows must pass the original verifier; failed windows retry the established full search, and empty detections skip the proposal pass.

Supported routes are loose driver, Nano, caster, paired wheels, extension board, protector and OLED. Weak classes and installed-component checks retain their existing recognition. Phases 4 and 6 remain wiring guides. ORB thresholds, pose rules, physical checks, workflow acceptance and database schemas were not changed. Earlier UI changes in the checkout were preserved.

The supplied Nano chip detector remains available before the trained board detector during recovery. Supported photo passes now skip its synchronized readiness query before it can wait on background inference. The native monitor-isolation test passes. Failed neural trials cannot change the persistent detail profile; a normal fallback can still retain its legitimate successful detail profile. See [implementation details](TRAINED_MODEL_ASSISTANCE.md).

## Executed checks

| Check | Result |
|---|---|
| Debug app and Android test APK build | Passed |
| Release Java compilation | Passed |
| Host JVM suite | 767 tests / 145 classes passed |
| Source and APK model contracts/hashes | All nine match |
| ONNX graph and fixed shape checks | All nine passed |
| Final model/native-camera suite | 13/13 groups passed |
| Initial broad native sweep | 98/100 groups passed |
| Focused final-code compatibility rechecks | 5/5 passed; each earlier failure passed twice |
| Saved progress and database hashes | Identical before/after |
| git diff --check | Passed |

The compatibility coverage is **100 distinct groups with passing results across the sweep and rechecks**, not one clean 100/100 sweep. The initial sweep missed one mounted OLED view within eight attempts and exceeded the active-flow native heap-growth assertion once. Both tests run without the neural worker; their matcher/tracker implementations are unchanged. Each passed two isolated reruns without relaxed recognition, memory or timing limits. The full analyzer's mounted-component group also passed in the broad sweep. These results expose sensitivity to recorded-view search scheduling and process-level allocation; they do not establish that either initial failure is impossible in future runs.

Two fixture assumptions were corrected during the reconnected run: an empty detection is a valid background result and must not start a proposal pass; a full-frame fallback may legitimately retain its own successful detail profile. Assertions now check result ownership and neural-trial isolation at the correct boundary. The earlier I420 fixture's even-dimension correction passed its final camera-recovery test.

The broad sweep preceded the final legacy-readiness guard. The final 13-group suite, including monitor isolation and live camera recovery, plus the final Nano orientation recheck exercised that guard. Exact archive identities, cases, measurements and hashes are retained in [the device record](TRAINED_MODEL_DEVICE_VERIFICATION_2026_10_27.json).

## Recognition-stage measurements

Final tablet replay used three different retained images per phase, six measured calls per mode, excluded warmups and reversed mode order in round two.

| Component | Median baseline verification | Median assisted verification | Reduction |
|---|---|---|---|
| Nano | 101.75 ms | 86.84 ms | 14.6% |
| Paired wheels | 404.53 ms | 251.56 ms | 37.8% |

These are **photo verification timings after proposals are available**. Separate inference times were approximately 258–529 ms for those Nano frames and 288–1,138 ms for those wheel frames. Other cold/load-sensitive calls exceeded two seconds. This does not prove a universal end-to-end recognition speedup or the proposed 20% median end-to-end promotion target. Successful initial photo scans avoid inference for this reason. See [all timing samples](TRAINED_MODEL_TIMINGS_2026_10_27.json).

## Actual camera and inference soak

The 30-second assisted run used the actual rear sensor at 640×480, a private preview stream and the trained recovery worker. It submitted 15 recovery requests using current camera gray/RGB pixels. The worker drained and terminated on close.

| Measurement | Baseline | With recovery inference |
|---|---|---|
| Delivered analysis frames/second | 29.56 | 29.06 |
| Camera buffer/tracker callback P95 | 15.37 ms | 6.13 ms |
| Native-heap growth after model warmup | — | 2,955,136 bytes / 2.82 MiB |

Delivered frame rate retained 98.34% of baseline. The configured delivery, callback, model-execution and memory bounds passed. The latest worker recovery call was approximately 461 ms. The lower assisted callback P95 does not mean inference makes capture faster: this is a short sequential comparison with warmup effects. It checks camera delivery under model load, not displayed UI FPS, a prolonged thermal soak or recognition accuracy.

## Learner data and deployment

Tablet: **2603ARP14G**, serial **368e278b**. The separate instrumentation runner executed candidate code/assets. No learner APK install, uninstall or data clear was performed. Saved learner data matched exactly before and after:

```text
c7e3751dd9a24c3f8be02740b3acadaa8c4f29f658d616071b039f0f93b69476  shared_prefs/assembly_phase_progress.xml
3325d2a819fdd8062c2cdc48a09b995c9b012915bcdf88b1cf9742a7f057c793  shared_prefs/guided_assembly_steps.xml
d76b286e0316b22fb2c4c564dac58cb4fd39fd428cdf54130406bc6bb84e97dd  databases/assembl_ar.db
```

Debug assistance defaults on. Release assistance remains opt-in through the diagnostic switch because a fresh-room accuracy study and an end-to-end speed promotion gate have not been completed. Existing stored preferences are respected. Weak classes remain inactive in ordinary scans.

APK: app/build/intermediates/apk/debug/app-arm64-v8a-debug.apk. The [asset audit](TRAINED_MODEL_ASSET_AUDIT_2026_10_27.json) records the final APK and model SHA-256 values. The nine models total 95,433,653 bytes; the APK is about 2.79 GB, mostly existing reference/video assets. Models load lazily.

## Evidence and reproduction

- [Final 13 model/camera groups](TRAINED_MODEL_NATIVE_FINAL_2026_10_27.json)
- [Initial 100-group compatibility sweep](TRAINED_MODEL_COMPATIBILITY_SWEEP_2026_10_27.json)
- [Five final-code rechecks](TRAINED_MODEL_COMPATIBILITY_RECHECKS_2026_10_27.json)
- [Native measurement log](TRAINED_MODEL_NATIVE_EVENTS_2026_10_27.txt)

Build with tools/build_tablet_debug.ps1; run tools/run_host_tests.py and tools/audit_trained_models.py --onnx. Prepare the archive with tools/prepare_candidate_replay.py and push it to the tablet's external-files candidate path. Run tools/run_trained_model_replay.ps1 and tools/run_quality_replay.ps1 -Suite all. Focused rechecks are in tools/run_trained_compatibility_rechecks.ps1. Never replace the archive while an instrumented group is running.
