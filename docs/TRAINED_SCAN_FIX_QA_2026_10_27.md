# Continuous scan and trained-model routing correction — 27 October 2026

## Problem and changes

The prior implementation packaged all nine models but enabled only a subset of
loose-part searches. Phases 1 and 2 and installed component checks were excluded.
The individual tester's generic phase-zero requests were also excluded. Nano
recovery still preferred a separate chip detector. This did not meet the requested
coverage. Previous isolated tablet tests did not update the learner application.

The corrected policy maps all 15 trained component identities to their actual
model in both scanner screens. Installed targets use the appropriate bodies,
including motors/brackets in Phase 2 top/left/right scans. The old chip detector
and assets were removed. Models remain byte-identical to their trained exports.

Fast successful scans have 250ms to complete before neural work can start.
Current tracking pixels allow slow/unresolved scans to start recovery while
the photo verifier is busy. Multiple predicted windows receive separate feature
sampling; installed checks also retain whole-scene context. A measured Nano
sampling duplication was removed instead of accepting a slower proposal path.
The original full-frame verifier remains available in the same invocation after
a proposal fails. There is no neural-only assembly completion or fabricated pose.

Additional corrections:

- A partial bracket/motor inventory no longer suppresses recovery.
- Recovery eligibility uses the result after final mismatch validation.
- Run/checkpoint transitions clear camera caches and old anchors automatically.
- Proposal descriptor rows and cached nearest-neighbour indices are isolated from
  the original full-frame cache. A failed proposal cannot advance the generic
  tester's reference-bank failure schedule or pin adaptive-detail profiles.
- Trained searches use native image detail before sampling their local windows.
- RGB buffers allocated for an early neural query are reused by color/shape
  inspection instead of being overwritten by a second allocation.
- Temporary inference failures and reference/camera setup failures retry with
  bounded delays. A tester frame error no longer forces a paused session.

## Verification status

The tablet remained available over USB despite the planned disconnection.
Isolated candidate checks are running on serial 368e278b. Installation is pending
completion of the final checks. Do not apply the earlier 13/13 result to this revision.

Final debug app and Android test fixtures build successfully. Release Java
compilation also succeeds. All 770 JVM tests across 145 classes pass on the final
revision, including component routing, partial/full inventory recovery and
trained coordinate-decoder checks. git diff --check passes.

All nine model graphs pass onnx.checker. Their metadata and actual APK bytes match
the original trained files. The audit confirms the compiled full-component routing
marker and absence of both the retired detector class and chip-model assets.
See TRAINED_SCAN_FIX_ASSET_AUDIT_2026_10_27.json.

Debug APK SHA-256:
923511a421399e7551758bb578763d9da25fd35176a77c59166dad3bea4d252b

Prepared isolated replay archive SHA-256:
a5e2e744ce42f7678aad5a5171bee76dc65a61b8e798e0c08d63332c3bfebc18

No learner data has been cleared. Tests use an isolated replay archive and do
not advance learner checkpoints. Before/after progress hashes will be checked
again before and after the in-place update.

New native regressions cover all component/model routes in guided and individual
requests without restarting the worker; both physical ROI windows; descriptor
cache isolation; and two continuous board → four brackets → two motors → top →
left → right → Nano sequences with no manual analyzer reset or Rescan.
The existing camera/inference soak, missing-model fallback and wrong/missing/
reversed/duplicate-component checks remain in the replay suite.

## Training limitations

The OpenCV host proposal audit covers 185 existing test photos, using camera-like
640px resizing and the Android model preprocessing. It compares .35 with .75
proposal thresholds, without training or changing weights. It is not an app
recognition benchmark or an independent-room accuracy study.

Phase 2 localized 31/52 annotated objects at IoU >= .25 with .35, versus 25/52
with .75. Nano localized 11/15 versus 9/15. Lower thresholds also add unmatched
proposals; the original verifier remains responsible for rejecting them.
The models still miss objects, especially in some sensor and assembled views.
Training alone does not establish reliable full-session recognition.

See TRAINED_HINT_COVERAGE_2026_10_27.json for all model proposal measurements.
Prefinal native checks passed 17/17. Compatibility checks passed 19/20; the one
failure was a new test requesting nonexistent capture timestamps, corrected to
the actual retained Nano frames before the final recheck. No app acceptance
threshold was weakened to address it. The final candidate also removes duplicate
loose Nano sampling, keeps whole-scene proposal sampling for installed checks,
and gives fast initial scans 250ms before neural recovery can start.

Device logs contain an earlier Phase 3 dialog callback crash. The current source
already guards a late onShow callback against a cleared/replaced dialog; this
guard is included in the candidate. The log alone does not establish a new-model
crash or native memory exhaustion.
