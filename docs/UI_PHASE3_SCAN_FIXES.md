# Camera workspace, contextual schematics and installed-component scan fixes

## Changes

- `activity_part_recognition_test.xml`: portrait camera gets 60% of the available content height. Landscape variant gives the camera 62% of content width and the entire content height. Controls, metrics, exports and benchmark settings scroll in their own pane. Fixed-height metrics and multiple button rows no longer subtract height from the camera. Shared camera/controls layouts retain existing view IDs and handlers.
- `AssemblyActivity`, `PhaseAssemblyController`, Phase 4/6/8 guide controllers: the schematic header control starts hidden and appears only on an active connection/check screen. It is hidden for scans, videos, preparation and color selection, including when a wiring phase is paused to revisit an earlier phase. Saved diagrams remain accessible from the retrospective and from wiring steps.
- `Phase3MotorDriverFlow`, `Phase3GuideController`, `PhaseGuideMetadata`: six checkpoints now mean loose scan → hardware preparation → placement video → placement/orientation scan → fastening video → screw/nut/stability checklist. Step 5 no longer issues camera requests or exposes Rescan. Placement and fastening videos have independent readiness/playhead state and TTS. Previous navigates back to the corresponding video. Versioned checkpoints migrate earlier progress without requiring completed scans again; an unfinished old Step 5 becomes the fastening demo.
- `Phase3AssetCatalog`: all 34 `MOTORDRIVERPLACEMENTWITHWIREPERSPECTIVEREF` images supplied under `STEP3_1MOTORDRIVER` register as **installed** references. Existing 21 `STEP3_5ASSEMBLYSCAN` photos remain placement references. No source files were moved or deleted. `UnifiedReferenceCatalog` uses the registered role when routing these photos to diagnostic tests and the registry.
- `Phase3ReferenceMatcher`: caches independent module/context descriptor slices. Cheap module evidence ranks refinement candidates before expensive board matching. A bounded, contrast-normalized local crop can recover detail on small camera images, while independent module and board homographies still establish orientation. Missing modules, reversed orientation and shared PCB printing cannot satisfy the placement gate. Strong verified evidence stops redundant matching; the last strong reference is tried first on subsequent frames. Existing native ORB initialization, renderer configuration and persistent descriptor format are preserved.
- `GlobalComponentRegistry`: compact driver/Nano identity must have at least eight spatially distributed local inliers. Shared PCB context is no longer scored as component identity. A different installed component elsewhere on the board cannot veto a locally verified target; a competing identity must occupy the same physical image region.
- `AssemblyVisionAnalyzer`: after reference validation and the global guard, deliver recognition to the step gate before optional tracking catch-up and diagnostic recording. Optical-flow or checkerboard work cannot delay an already verified result reaching the HUD. Predictions still cannot verify a step.

## Verification

Executed on this Windows host:

- Debug app and Android test APK builds: successful.
- `tools/run_host_tests.py`: **373 tests passed**, including Phase 3 flow/migration, reference routing, shared-PCB rejection and spatial competitor regression tests.
- `tools/validate_phase3_placement.py`: **14/14 desktop OpenCV checks passed**. Two photos are held out from training: an original top view and a new view with motor wires. Each is evaluated at 640×400 with preserved aspect under normal, dim, bright and uneven lighting, partial wire occlusion, covered/missing module and reversed module controls. Artifact: `app/build/tmp/phase3-placement-parity.json`. These are synthetic image trials, not measured tablet latency or a guarantee under every lighting condition.
- Android layout regression compiled for 1024×600, 640×360 and 400×640 logical viewports with larger text: `PartTestLayoutInstrumentedTest`. It has **not been executed on a device**.

Commands from this Gradle root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_tablet_debug.ps1
python tools/run_host_tests.py
python tools/validate_phase3_placement.py
```

The Python image check uses the existing test-only OpenCV installation in `app/build/tmp/cv-test-deps`. No Android dependency was added.

## Tablet confirmation

No APK was installed. ADB did not list tablet `2603ARP14G`; the other connected phone was not used. Deploy through Android Studio, preserving app data, then check:

1. Individual Component Tests: landscape camera is large; settings/log actions scroll separately. Rotate to portrait and verify the stacked view.
2. Phases 1–11: schematic header control appears only during actual wiring/check screens in Phases 4, 6 and 8. Revisit a scan or routing video: it hides again.
3. Phase 3: verify placement once, tap Next to watch the fastening demo, then choose **Ready for Screw Check**. Check both screws/nuts and firmness. Revisit the demo using Previous; no second assembled-driver scan should appear.
4. Step 5.3 installed Nano: check recognition and Next unlock on the same board that failed after the last update. Repeat with the motor driver also in frame. Compare timestamps/diagnostics if any delay remains.

After deploying both app and test APKs, optional native tests can be run without changing assembly progress:

```powershell
adb -s 2603ARP14G shell am instrument -w -e class dev.chyken.assembl_ar.PartTestLayoutInstrumentedTest,dev.chyken.assembl_ar.vision.Phase5ReferenceMatcherInstrumentedTest dev.chyken.assembl_ar.test/androidx.test.runner.AndroidJUnitRunner
```
