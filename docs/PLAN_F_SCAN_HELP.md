# PlanF guided scanning help

Enable **Settings → PlanF guided scanning help**. It starts disabled and the choice persists.

After five seconds of active, ready scanning without verification, PlanF offers the current
target's reference photos. Reference preparation, previews, wiring screens, physical checklists,
completed scans and time spent outside the activity do not count toward this delay.

- **Yes — help me scan:** shows framing, orientation and glare tips for the current target.
- **Another view:** cycles through up to six positive reference photos for that scan. Rejected
  orientations and unrelated stages are excluded. Phase 2 uses the required top/left/right view;
  Phase 7 underside checks use bottom-view assembly photos.
- **Keep scanning:** dismisses the help. The toolbar's **Scan help** button can reopen it.

The camera and outline tracker continue while help is open. Verification automatically dismisses
the help. The automatic offer appears once per attempt; an explicit rescan or a different scan
can offer help again. Returning from a pause does not repeat previously dismissed help.

Green areas on the reference image mark existing authored reference-feature regions. They are
not a live detection. If no regions were authored, the photo is shown without invented outlines.

## Verification remains independent

PlanF never supplies recognition evidence, changes a match threshold, confirms a physical
checklist, unlocks Next, advances a phase, or edits assembly checkpoints. “Yes” identifies the
user's intended target only. It is not a manual override and is not reported as a successful scan.

There are no camera scans in the wiring-only phases 4 and 6, so those screens do not show PlanF.
The same shared integration handles all authored camera scans in the 11-phase workflow.

## Performance and lifecycle

The feature reuses the existing 250 ms UI status monitor rather than adding a detector/frame
loop. Reference photos are selected from the in-memory catalog only when help is opened.
Thumbnails load asynchronously through the existing bounded image loader (768 pixel limit).
There is no added ORB pass, model inference, descriptor bank, camera session or Gradle dependency.
Pause, navigation, success, disabling the setting and activity destruction dismiss the dialog
and detach its image, including pending image-loader ownership tags.

## Verification commands

From the Gradle/project root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python -B tools/run_host_tests.py
python -B tools/prepare_candidate_replay.py
# After pushing the generated candidate archive to the connected tablet:
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run_quality_replay.ps1 -Serial 368e278b -Suite planf
```

Host coverage: `PlanFSessionTest`, `PlanFReferenceCatalogTest`, and the existing phase/vision tests.
Tablet fixtures in `UiBeautificationInstrumentedTest`:

- `planFOffersHelpOnceWithoutCompletingAnythingAndClosesOnSuccess`
- `planFSettingsPersistWithoutChangingExistingPreferencesAndLargeFontHelpIsReachable`
- `planFLoadsABoundedRealPhotoAndRendersItsHelpSurface`

Update the learner app through Android Studio with its data retained. No uninstall or data clear
is needed. These help controls do not change the underlying recognition system's accuracy.
