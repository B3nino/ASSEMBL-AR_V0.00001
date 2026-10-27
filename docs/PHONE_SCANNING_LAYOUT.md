# Phone scanning layout

The scanning workspaces adapt automatically when the window's shortest side is below 600dp.
Full tablet windows retain their existing layout. No setting or progress reset is needed.

## Assembly camera

- The live camera keeps the main screen area. Instructions start folded during camera scans.
- **Steps** opens or folds the current instructions. Existing Previous and Next actions remain
  visible; only the existing workflow can enable Next.
- **⋮ Scan tools** provides the reference photo and 3D preview during scanning. Showing one
  hides the other. These aids close when the scan target changes.
- Secondary controls, including **Rescan this step**, **Return to current phase**, Settings,
  narration, PlanF help and DEV controls, remain available through Scan tools when their
  original workflow/setting makes them available. A rescan action is never newly authorized
  by this layout. Phase 1 retains its original compact Next/Rescan footer.
- Portrait uses a bottom dock. Landscape keeps the scan controls on the right, leaving at
  least 60% of the width for the camera area. Demo and hardware previews remain visible outside
  scan mode, with separate space for instructions. Existing wiring workspaces retain their
  own diagram layout.

## Individual component tester

- Camera preview spans the phone width in both orientations.
- The selected target and existing Start/Pause button stay available in a compact dock.
- **Tools & results** expands the original diagnostics, results, benchmark/logging, sample
  capture/replay, export and reset controls. Previous/Next target arrows are in this pane;
  the searchable target picker remains available directly in the dock.
- The complete diagnostic HUD is displayed inside the scrollable tools pane rather than
  floating over the small camera view. Its numerical telemetry and recording are unchanged.
- The global section navigation is hidden only within this phone camera workspace; Back
  returns to the regular app navigation. Expanding tools does not stop the camera. Starting
  a test folds tools to return space to the camera.

## Preservation and performance

These adapters move or resize existing views; they do not clone workflow buttons or replace
their listeners. They restore the original parents, dimensions and toolbar when a window grows
back to tablet size. UI expansion state is saved separately from assembly checkpoints.

No recognition algorithm, camera analysis resolution, descriptor cache, matching threshold,
pose calculation, progress database or phase state machine was changed. Hidden 3D model aids
pause their renderer. There are no new detector threads, image passes, animations or dependencies.
The layout adapters use existing layout/status callbacks rather than a frame-processing loop.

## Verification

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python -B tools/run_host_tests.py
python -B tools/prepare_candidate_replay.py
# After pushing the generated candidate archive:
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run_quality_replay.ps1 -Serial 368e278b -Suite phoneui
```

Native UI fixtures render 360×780, 780×360 and 320×568dp phone windows at standard and doubled
font sizes, plus 1280×800dp tablet windows and repeated phone/tablet reflow. Checks cover camera
space, reachable original controls, unchanged button gates, non-overlapping demos, stable layout
updates and restoration. These are phone-size view fixtures running on the tablet, rather than
a physical-phone camera/performance benchmark. Black areas in those fixture images represent
the uncaptured camera surface, not an application camera failure.

Deploy through Android Studio with app data retained; no uninstall or data clear is required.
