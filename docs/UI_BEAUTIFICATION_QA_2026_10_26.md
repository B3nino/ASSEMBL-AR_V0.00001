# UI beautification and documentation — implementation and verification

26 October 2026

The original dark palette and descriptor gate in this record are superseded by the later [light-menu/Settings/raw-percentage update](LIGHT_MENU_SETTINGS_AND_RAW_METRICS_QA_2026_10_26.md). The original verification record is retained below.

## Scope and preserved behavior

Presentation changes only: menu background, startup cube, physical checklist surfaces, persistent narration controls and a rewritten root manual. The camera pipeline, reference assets, matcher thresholds, telemetry calculations, progress database and phase state-machine classes were not changed.

The six explicitly protected files are unchanged from the starting checkout: `OrbMatchPolicy`, `VerifiedScanEvidence`, `DiagnosticTelemetryLocker`, `OpticalFlowTrackerBridge`, `DynamicComponentOutlineOverlayView` and `StandoffGeometryVerifier`.

## New presentation classes

| File under app/src/main/java/dev/chyken/assembl_ar | Responsibility |
| --- | --- |
| ui/LifecycleAnimatedView.java | One main-thread Choreographer callback owner. Starts only while resumed, attached, visible and focused. Removes callbacks on pause, ancestor invisibility, focus loss, detach and close. Respects disabled Android animators. |
| ui/ElectronParticleBackgroundView.java | Exactly 40 bounded emerald/cyan nodes, a charcoal surface and faint distance-faded traces. Fixed position/velocity arrays and Paint; no per-frame bitmap or worker thread. |
| ui/AssemblyLoadingCubeView.java | Depth-sorted isometric 27-block cube, rotating layer and split/snap loop. Actual prepared/total counts control pacing and assembly spacing. Reuses projected vertices, sort storage and Path. |
| ui/PhysicalChecklistBottomSheetDialog.java | Bottom-anchored AlertDialog-compatible sheet, mint header/icons, dark translucent panel, scrollable body and large explicit answer pills. Preserves getButton/setOnShowListener contracts used by phase controllers. It does not use swipe-to-confirm behavior. |
| ui/NarrationMuteControl.java | Shared preference listener and accessible speaker button. Updates the existing screen-owned AssemblyVoiceGuide, with no second TTS engine. Removes listeners on destruction. |

## Existing integration points

- `MainActivity`, both `activity_main.xml` variants: particle surface behind existing hero/navigation; animation begins after the startup gate reveals the menu. Header colors remain legible on charcoal. Robot preview lifecycle remains unchanged.
- `BrandLaunchOverlay`, `SplashActivity`, new `activity_splash.xml`: cube/wordmark and quantitative reference progress. The existing preload coordinator, failure/retry handling and first-model-frame gate remain authoritative. There is no invented 100% completion or extra timer required to unlock the menu.
- `Phase2GuideController` through `Phase11GuideController`: physical dialog builders use the shared sheet. Existing component quantities, task-specific answers, correction prompts, cancel/back behavior, TTS and readiness gates are retained. Phase 1 currently has no new physical checklist prerequisite to invent.
- `PhaseAssemblyController` and the legacy physical validation route in `AssemblyActivity`: same sheet shell. Legacy acknowledgment checkbox/disabled confirmation remain mandatory.
- `PhysicalCheckUi`: consistent Firm & Secure / Needs Adjustment radio pills. Existing controller answer-index meaning is unchanged. Informational dialogs retain their own styling.
- `JumperWirePreparationDialog`: the shared scrollable sheet retains graphics/count/disclaimer and explicit preparation confirmation, without nested scroll containers.
- `PreferencesManager`: `KEY_TTS_MUTED` in `diagnostic_settings`; migrates `AssemblyActivity`'s earlier `voice_guide_muted` value only when the new key is absent. No preference or checkpoint is cleared.
- `AssemblyActivity` and `PartRecognitionTestActivity`: speaker buttons use the same stored preference. The old text voice control delegates to that same action. Lazy wrong-component narration in the tester starts with the saved mute state.
- Toolbar resources and five vector icons: 48dp accessible speaker actions plus fastener/alignment/wiring illustrations. No raster or 3D asset duplication.
- `README.md`: actual CameraX/OpenCV/OpenGL stack, all 11 phases, current 10-jumper wiring and upper-board battery decisions, menu/learning/library/admin/tester/retrospective controls, diagnostics, commands, progress rules and research bibliography.

## Animation/performance boundaries

The graphics schedule approximately 30 updates per second, independently of camera analysis; they do not claim 60 FPS. Inactive views schedule no animation frame callbacks. That is a bounded implementation guarantee, not a claim that the whole Activity/device uses literally zero CPU/GPU. Drawing uses Android's normal hardware-accelerated Canvas in the app window; bitmap renders in tests are software visual snapshots. No camera session, vision executor, native Mat or OpenGL model resource is owned by these views.

Startup still uses the existing bounded warm descriptor cache. This change does not turn the full descriptor corpus into an unbounded RAM allocation or alter preparation recovery.

## Test design and initial issues corrected

`UiBeautificationInstrumentedTest` adds four native fixture groups:

1. Visible/resumed animations follow the Android motion preference; pause, hide, detach and close remove scheduling. Waits for actual host window focus rather than assuming focus within 150ms.
2. The cube uses 387/1000 as 38.7%, clamps progress and cannot bypass the real reference/model reveal gates; renders cube/menu artifacts.
3. Real shown dialogs at font scales 1.0 and 2.0 retain explicit callbacks. Needs Adjustment does not confirm/dismiss the check; positive confirmation invokes only its own callback. All three answer buttons are visible, at least 48dp high and unclipped. Normal and large-font screenshots were visually inspected.
4. Both camera toolbar layouts share the persistent mute preference; the fixture restores the original value afterward.

Isolated candidate replay needed a test-only resource ownership correction. UI fixtures now load candidate AppCompat/Material classes alongside candidate resource IDs and use an isolated inflater to avoid Android's cached installed-app constructors. Production class loading is untouched. Dialog fixtures wait for Android's posted onShow event before exercising the listeners; no production callbacks were made synchronous to satisfy a test.

`tools/run_quality_replay.ps1` adds those four groups to the existing 66. Existing recognition/negative views, outline persistence, native cleanup, camera pause/resume, honest telemetry, checkerboard TRE, preview skip and welcome contrast fixtures remain in the full run.

## Verification results

| Verification | Result |
| --- | --- |
| ARM64 app + test APK build | BUILD SUCCESSFUL in 23s |
| Complete host suite | OK (723 tests); no warnings/failures in host output |
| Complete physical-tablet native suite | All 70 tablet regression groups passed |
| Final resource UI checks | Four groups passed again, in one generic runner (OK (1 test), 21.911s) |
| Android lint | 0 errors, 196 warnings; not suppressed or represented as warning-free |
| Protected production files | All six unchanged |
| Phase 2–11 guide controllers | Diff contains only physical dialog-builder replacements |
| Learner state/database | All four pre/post SHA256 hashes identical |

The last edit added a light backing to the existing menu logo for contrast on charcoal. It changed XML only; all 12 production DEX files are byte-for-byte identical to the candidate used for the complete 70-group run. The final candidate/resources then passed all four UI groups again. Host tests and lint also passed after that edit. No learner-app install, uninstall or data clear was performed; only the separate test runner was updated.

The 196 lint warnings include translation, custom loading-screen/view-tooling and layout/overdraw advisories across the existing codebase and this UI work. They remain visible in the generated report; zero lint errors is not the same as zero warnings. The fixed-size vector graphics and inactive callback behavior were checked, but a sustained whole-app 60 FPS claim was not made.

Final ARM64 APK SHA256: `adf72756d277a53ec7f6815e3f428fc1946b77765f422367854281c2ff173939`.

Full results and named native groups: [test results](UI_BEAUTIFICATION_TEST_RESULTS_2026_10_26.txt). Local build logs, lint reports, candidate DEX provenance and normal/large-font/menu/cube renders are under `app/build/tmp/qa-overhaul` and `app/build/reports`.

Unchanged state files:

- `assembly_phase_progress.xml`: `e2884e36f46857c45a09bfe7f4762919a8d2d0924c94bcc5e5150ad48a3ea5a4`
- `guided_assembly_steps.xml`: `3325d2a819fdd8062c2cdc48a09b995c9b012915bcdf88b1cf9742a7f057c793`
- `astra_ui.xml`: `d7ece400619d4e4f07de486a626fd6f8a3c01e8b6d0b3be8af72f9edc02969f5`
- `assembl_ar.db`: `86fabbcb0c8f384d2ce3dbb4a19b0ed90e48c2b38e74adacd4bea00c9fa28544`

## Reproduction

Run from the Gradle root (the repository's app directory):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build_tablet_debug.ps1
python -B .\tools\run_host_tests.py
python -B .\tools\prepare_candidate_replay.py
# Update only the separate test package when its runner changed.
adb -s 368e278b install -r -t .\app\build\intermediates\apk\androidTest\debug\app-debug-androidTest.apk
adb -s 368e278b push .\app\build\tmp\tablet-audit\candidate-code.zip /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\run_quality_replay.ps1 -Serial 368e278b -Suite all
```

Host Python and ADB locations, JDK workaround and lint command are described in the README. Do not run resource-linking/lint while host tests hold R.jar open on Windows. Do not replace the candidate ZIP during instrumentation.

Deploy the learner app through Android Studio retaining data. The native run uses the separate test package and candidate code, not an uninstall/data-clear of the learner app. Tests are offline fixture regressions and do not certify every physical lighting condition or subjective UI preference.

## Bibliography handling

The README links primary publisher/author sources for relevant Farag (2026), Xie (2022) and Stanescu (2023) papers. It documents the ambiguity between two Farag papers and labels the Ma (2023) medical-AR review as a possible match, not a confirmed intended citation. App-specific benchmark thresholds are stated as project acceptance criteria, not attributed to those papers as universal validated cutoffs.
