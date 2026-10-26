# Light menu, shared navigation, Settings and raw diagnostic percentages

26 October 2026

## Result and scope

- Light mint canvas `#EAF4EF`, dark green content `#20382B`, muted green/teal circuit nodes and readable menu/loading text.
- One shared bottom menu on standard screens: Assemble, Learn, Parts, Settings; optional Administrator. Guide/manual pages, learning details, diagnostics, the individual tester and post-assembly workspaces now use the same shell. Phone and tablet menu resources both follow this policy.
- Full-screen live assembly retains its camera layout and gains a Settings gear. This follows the recommended default in the optional clarification; it avoids placing persistent navigation over the live scan controls.
- Administrator and assembly DEV visibility default to off, have independent persistent Settings switches and update immediately. Existing administrator PIN/device-owner checks remain. The visibility setting is not a new authentication system.
- Descriptor yield is shown as the actual calculated number with no 85% PASS/FAIL tag in either camera HUD, legacy diagnostic text, benchmark yield report or new CSV export. Actual verified output remains separate.

## Implementation

| File | Change |
| --- | --- |
| `ui/AppShellController.java` — new | Installs an idempotent shared shell, reuses/moves existing navigation instead of duplicating it, infers the active section and binds preference changes to visibility. Releases preference observers and graphics with the Activity lifecycle. |
| `AppSettingsDialog.java` — new | Readable light Settings dialog with Administrator/DEV switches, diagnostic-tools access and visible high-contrast footer actions. Pauses/resumes camera analysis through existing screen-owned controls. |
| `ui/UiUtils.java` | Applies the shell when standard-screen insets are installed; retains existing peer-tab/back behavior; wires Settings and guards the hidden Administrator route. |
| `diagnostics/PreferencesManager.java` | Separate `interface_settings` flags `administrator_visible` and `assembly_dev_visible`. Existing narration/logging settings and phase checkpoints are retained. |
| `MainActivity.java`, menu layouts, `colors.xml` | Light palette, dark wordmark/subtitle and shared Settings entry in place of the separate header-only button. |
| `ui/ElectronParticleBackgroundView.java`, `AssemblyLoadingCubeView.java`, `BrandLaunchOverlay.java`, splash resource | Matching light palette and muted circuit/cube colors. Existing reveal/preparation gates and animation lifecycle ownership remain. |
| `ui/LearningWorkspaceUi.java`, tester controls | Transparent outer surfaces allow the shared light circuit surface to remain visible. Camera pixels and diagnostic HUD backgrounds retain their own contrast. |
| `AssemblyActivity.java`, `DevStepSkipDialog.java`, assembly layout | Settings gear plus immediate DEV visibility binding and click guard. No phase progression/verification rule is changed. |
| Both `view_primary_navigation.xml` variants | Exactly one menu root and Settings entry; Administrator starts GONE so it cannot flash before preference binding. |
| `diagnostics/DiagnosticScanTelemetry.java` | Removes the descriptor-yield cutoff and percentage PASS/FAIL suffix. Raw counts/percentage and 15-line order remain. Unmeasured stays Unmeasured; output uses verified identity/pose or workflow verification. |
| `diagnostics/DiagnosticSample.java` | Removes the obsolete gate column and legacy yield verdict. New CSV has 38 columns instead of 39; raw `match_percentage` remains. Historical exports are not rewritten. |
| `diagnostics/AcademicBenchmarkRunner.java` | Mean descriptor yield is numeric only. Lighting recognition counts actual accepted ORB trials without a yield cutoff. Separate recognition-frequency, timing and pose/overlay benchmark criteria remain. |
| Diagnostic help, controls manual and README | Explain the two visibility switches, shared light shell and raw percentage behavior. |

## Performance and preservation

The individual tester and administrator camera workspace use a static circuit background: no particle animation callbacks compete with camera analysis. Other standard screens retain the existing bounded 40-node animation and pause it off-screen. The menu still gates its animation behind startup preparation.

No reference images, descriptor math, Lowe/Hamming policy, ROI rules, camera recognition thresholds, optical-flow pipeline or phase flow state machines were changed. `OrbMatchPolicy`, `VerifiedScanEvidence`, `DiagnosticTelemetryLocker`, `OpticalFlowTrackerBridge`, `DynamicComponentOutlineOverlayView` and `StandoffGeometryVerifier` remain unchanged. The diagnostic output change is presentation/reporting, not permission to complete a physical assembly step.

## Verification

| Check | Result |
| --- | --- |
| ARM64 app/test build | BUILD SUCCESSFUL |
| Full host suite | 724 tests passed |
| Native replay coverage | All 72 named groups cleared |
| Final diagnostic replay | Six groups passed in one generic runner (15.716s) |
| Android lint | 0 errors, 186 warnings |
| Protected vision/tracking/latch files | Unchanged |
| Saved progress | Phases 1–7 complete and saved caster/tracker session present; file timestamps predate this update's first build |

The complete native run passed 71 groups. Its sole failure was an obsolete expected `ORB unmeasured` suffix in a HUD fixture. Only the fixture expectation was corrected to the requested raw `%: Unmeasured` line. All 12 production DEX files remained byte-for-byte identical. The corrected fixture and five related telemetry/pose/latching groups then passed. The initial failed-run evidence is retained rather than overwritten.

The earlier lint attempt hit an automatic-review service usage limit; retry through the same approval path succeeded. The 186 lint warnings remain reported, not suppressed. No main-app deployment, uninstall, restore or data wipe was performed; the updated source is ready for Android Studio.

The final state hashes differ from a snapshot taken in the earlier UI session. That snapshot is not a current-task baseline. The progress XML and SQLite last-write epochs (1792964075 and 1792964030) predate the first build log for this update (1792967035). Current completed phases and the saved session were inspected read-only and remain present. No older backup was restored over current progress. Guided-step and UI-preference hashes still match the older snapshot. This records the evidence without claiming all four files stayed byte-identical across separate user sessions.

Named group results and current state hashes: [verification outputs](LIGHT_MENU_SETTINGS_AND_RAW_METRICS_TEST_RESULTS_2026_10_26.txt). Raw logs, screenshots and DEX provenance are under `app/build/tmp/qa-overhaul`.

The native UI checks exercise actual attached views/dialogs: readable contrast above 7:1 for content, at least 4.5:1 for Settings footer text, usable tester viewport, one shared menu across multiple sections, persistent independent toggles and no inactive camera-background animation. Settings/footer and menu/tester renders were visually inspected.

The tablet-specific resource variant initially retained the old menu and was corrected before delivery. A toolbar tint compatibility finding was corrected rather than suppressed. The old diagnostic fixture expected the removed `ORB unmeasured` suffix; it now asserts the exact raw `%: Unmeasured` line and still checks verified green output, no invented 100%, 15 HUD lines and unknown 3D pose.

## Reproduction

From the repository/Gradle root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build_tablet_debug.ps1
python -B .\tools\run_host_tests.py
python -B .\tools\prepare_candidate_replay.py
adb -s 368e278b push .\app\build\tmp\tablet-audit\candidate-code.zip /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\run_quality_replay.ps1 -Serial 368e278b -Suite all
```

Keep the separate test runner current when updating its generic loader. Deploy the learner app through Android Studio while retaining data. This work does not require an uninstall or data clear.
