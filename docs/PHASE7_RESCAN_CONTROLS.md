# Phase 7 Rescan controls — 22 October 2026

## Correction

The controller hid Rescan whenever a completed phase was being reviewed. It also required a
successful scan, so returning from an unfinished 7.3/7.4 scan to its demo removed the control.

- Both assembly steps expose **Rescan this step** once their demo has been completed, including
  replay before the first successful match and look-back from a later phase.
- The existing confirmation dialog is required. Cancel preserves the scan/checklist state and
  current preview. Frames are suspended while confirming, and stale results cannot unlock a step.
- Confirming stops the replay and immediately starts the selected step's verification camera
  request. Only that step's scan and physical stability answers are cleared; other steps and
  completed demos remain intact.
- Review rescans run in the existing isolated review flow. They can receive live results and
  open the same physical checklist. They never overwrite the active assembly checkpoint or
  automatically advance the user to another phase.
- **Return to current phase** remains available during review, including an unfinished rescan.

## Verification

- Real Gradle app and Android-test APK assembly succeeded.
- All **15 Phase7CasterTrackerFlowTest** cases passed, including replay-before-match availability
  and preservation of other completed steps during a rescan.
- `Phase7RescanControlsInstrumentedTest` exercises actual buttons for 7.3 and 7.4: confirmation
  cancellation, confirmed replay-to-scan transition, completed-phase review, tagged recognition,
  physical checklists, no automatic review exit, and unchanged saved phase preferences.
  Its final source compiles. An initial device run exposed the test's failure to wait for Android's
  asynchronous dialog callbacks; that fixture was corrected. Installation of the revised test
  package was rejected by the tablet, so the final UI regression has not been executed.
- The user's shared preferences and databases were backed up privately before the update.
  This test does not use the workflow fixture that resets learner progress.
- Per the user's direction, further installation and UI testing are left to Android Studio.
  Tablet access is reserved for diagnosing scanning problems unless requested otherwise.

## Files changed

- `app/src/main/java/dev/chyken/assembl_ar/Phase7GuideController.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/Phase7CasterTrackerFlow.java`
- `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase7CasterTrackerFlowTest.java`
- `app/src/androidTest/java/dev/chyken/assembl_ar/Phase7RescanControlsInstrumentedTest.java`
- `docs/PHASE7_CASTER_TRACKERS.md`
- `docs/PHASE7_RESCAN_CONTROLS.md`

No vision matching, reference assets, layout XML, or saved snapshot schema changed.
