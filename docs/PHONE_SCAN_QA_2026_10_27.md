# Responsive scanning UI verification — 27 October 2026

## Delivered

Phone-only presentation adapters reserve camera space in assembly mode and the individual
tester. Assembly uses folded instructions, original navigation buttons and a Scan tools menu
for optional aids/secondary controls. The tester retains its original selector and Start/Pause
button, with complete diagnostics and other controls in a bounded tools drawer. Tablet windows
retain the existing layouts and restore their original view hierarchy after phone-sized reflow.

See [the user guide](PHONE_SCANNING_LAYOUT.md) for where the controls appear.

Primary new classes: `PhoneAssemblyLayout`, `PhonePartTesterLayout`, `ScanViewportPolicy`.
Integration changes: `AssemblyActivity`, `PartRecognitionTestActivity`, `StepInfoPanelController`.
Small presentation APIs in the two guide cards, component/reference aids and diagnostic view
support folding content, pausing a hidden 3D preview, and displaying phone metrics inline.

No files in the vision or phase-state-machine packages were changed. Matching policies,
camera analysis dimensions, descriptor caches, pose/telemetry math and checkpoint gates remain
unchanged. Original action instances/listeners and enabled/visibility gates are retained.

## Final executed checks

- Tablet debug app/test APK build: **BUILD SUCCESSFUL**.
- Host tests: **757 passed, 142 classes**.
- Final candidate phone/tablet layout suite: **3/3 native groups passed**.
- Final candidate compatibility sweep: **12/12 native groups passed**.
- [Exact native group names and results](PHONE_SCAN_NATIVE_RESULTS_2026_10_27.json).
- `git diff --check`: passed.

Phone fixtures cover 360×780, 780×360 and 320×568dp at normal and doubled font sizes. Checks
include camera area, original button gates, successful-state footer availability, optional
instructions, independent camera overlays, non-overlapping demos and layout stability without
feedback loops. Tablet fixtures cover 1280×800dp and repeated phone/tablet reflow, checking
original view identities, parents, pane weights and preview geometry restoration. Rendered
phone screenshots were inspected. Compatibility checks cover shared tablet UI, Settings/mute,
physical checklists, PlanF, welcome contrast, lifecycle, outline persistence and honest telemetry.

These UI fixtures ran on tablet 2603ARP14G using phone-size configurations. A physical-phone
live-camera/performance soak was not performed, and no frame-rate claim is made. No detector
thread, inference pass, animation loop or dependency was added. Hidden model aids pause their
renderer, and layout tests confirm stable sizing updates.

## Data preservation and deployment

The learner app was not installed, uninstalled or cleared. The existing separate instrumentation
runner exercised candidate code/resources. Assembly preferences and SQLite database hashes were
identical before and after the final checks:

```text
50cebbcc1be456344ac727a90991699104f4b8779634e0ca00324222c3ce05ef  shared_prefs/assembly_phase_progress.xml
3325d2a819fdd8062c2cdc48a09b995c9b012915bcdf88b1cf9742a7f057c793  shared_prefs/guided_assembly_steps.xml
d3698ed0d47c85287808a321ffc101ca7c1a68d0073cf67415a563b1b1e24213  databases/assembl_ar.db
```

APK: `app/build/intermediates/apk/debug/app-arm64-v8a-debug.apk`.

SHA256: `b379c8fa979a0aef94f5752bae2b359305060ea8517e1734fa86b7d3e5275487`.

Deploy the latest source through Android Studio with app data retained. The compact layout
activates automatically for phone windows; no new setting or progress reset is necessary.
