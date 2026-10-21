# Phase 6 and shared UI overhaul

## Behavior delivered

- Phase 6 has four authored checkpoints: six-wire color selection (6.1), power/ground check (6.2), four PWM connections (6.3), and the two-answer physical checklist (6.4). It opens the wire picker immediately. There is no repeated Nano scan or camera request.
- Driver VCC → Nano VIN; GND → GND; A-IA (A-1A) → D5; A-IB (A-1B) → D9; B-IA (B-1A) → D6; B-IB (B-1B) → D10. All six insulation colors use the existing ten-swatch palette. Apply commits the draft and opens the schematic. Editing applied colors invalidates subsequent wiring confirmations.
- The supplied Nano PNG is bundled unchanged. The reusable component catalog records all 30 physical pin anchors. A shared transform crops only the on-screen view and rotates the artwork so USB points down. The images, endpoints and hit targets use the same transform.
- Phases 4 and 6 use a large left schematic, an upper-right assignment legend, and bounded lower-right instructions on wide windows. Narrow windows stack these views. Tap the diagram for a true full-screen view with pinch, pan, zoom buttons and reset. Color legend selections and full-screen pin inspection retain their context.
- Scanning animation starts only for active camera checkpoints. It stops for confirmed scans, demos, wiring, physical checks, welcome, and pause. Reference frames remain separate from live AR ghosts.
- Physical checks share readable spacing, rounded surfaces, larger answer targets and consistent buttons. Existing two-motor answers, screw/nut checkbox and sequential Nano pin checks retain their verification gates.
- The supplied logo now forms through animated native Canvas masks: green brackets, cube edges, AR lettering, wordmark, then tagline. The original PNG supplies every displayed pixel. Formation takes 1.9 seconds on menu launch and 1.15 seconds in the first assembly welcome, beginning after artwork decoding. The formed logo pulses while the robot loads; menu reveal waits for both formation completion and a completed robot frame. Animation cancellation, pause/resume and disabled system animations are handled.
- Menu surfaces use mint, white and charcoal; top-level tabs crossfade. Offline-ready badges and DEV wording on normal continuation buttons are removed. Developer session isolation remains intact.

- The Phase 6 schematic uses horizontal/vertical runs with sharp 90-degree joins, separate lanes, and a perimeter VIN route. Hit targets follow the exact polyline, including long rails. CircuitWireRoute is the shared geometry helper for future wiring phases; see WIRING_SCHEMATIC_GUIDELINES.md.

## Progress compatibility

Phase 6 now owns manual sections 7 and 8 together. Finishing saves both remaining sections and opens the caster phase (Phase 7). All 18 manual sections and all 152 source instructions remain present. The current catalog therefore has 17 phases; the planned 11-phase roadmap still awaits the user's later grouping.

Existing separate control-phase session identifiers resolve to the merged Phase 6. SQLite section progress remains authoritative. A previous power-only certificate cannot mark all six connections complete. New wire colors, in-dialog draft selections, sub-step location and physical confirmations survive recreation/resume. Reviewing earlier phases retains the active wiring checkpoint. Redo clears the corresponding Nano/wiring snapshots.

Phase 5 remains responsible for Nano identification. Phase 6 suspends analysis throughout; late camera frames cannot change its progress. Previous five-step snapshots migrate to the new four-step numbering, keeping applied/draft colors, power and signal confirmations, and the two final checklist answers.

## Verification

- All 200 main Java classes compiled with cached Android, OpenCV and Media3 dependencies.
- Android resources compiled and linked successfully across 47 resource source sets.
- 212 JUnit tests passed, including six-wire mapping/color persistence, no-camera wiring gates, legacy snapshot migration, completion gates, merged source ownership, legacy session aliases, image hashes/pin transforms and startup reveal readiness.
- Workflow, project-detail and photo-matcher instrumentation sources compiled. A new device scenario exercises Phase 6 picker → power check → resume → final checklist → atomic section save, with tablet pane boundary assertions.
- Full Gradle APK assembly could not run because the local Java/Gradle daemon fails with java.io.IOException: Unable to establish loopback connection. Device UI, camera and TTS playback tests were not executed in this run. Test compilation is not a substitute for those device checks.

## Follow-up correction files

The logo-formation and four-step wiring correction touches LogoFormationView, LogoFormationSpec, BrandLaunchOverlay, BrandingAssets, WelcomeAssemblyDialog and its layout; Phase6DriverWiringFlow, Phase6GuideController, PhaseGuideMetadata, AssemblyPhaseCatalog, PhaseAssemblyController, RecognitionRequest and AssemblyVisionAnalyzer; CircuitWireRoute and DriverSchematicRenderer; corresponding workflow, project-detail, route and logo tests; and these two documentation files. The provided logo is bundled unchanged.

## Exact changed files

Paths are relative to the Gradle project root (app/ in the outer workspace).

- app/src/androidTest/java/dev/chyken/assembl_ar/PhaseWorkflowInstrumentedTest.java
- app/src/androidTest/java/dev/chyken/assembl_ar/ProjectDetailStepNumberInstrumentedTest.java
- app/src/main/assets/branding/assembl_ar_logo.png
- app/src/main/assets/schematics/components/arduino_nano.png
- app/src/main/java/dev/chyken/assembl_ar/MainActivity.java
- app/src/main/java/dev/chyken/assembl_ar/Phase2GuideController.java
- app/src/main/java/dev/chyken/assembl_ar/Phase3GuideController.java
- app/src/main/java/dev/chyken/assembl_ar/Phase4GuideController.java
- app/src/main/java/dev/chyken/assembl_ar/Phase5GuideController.java
- app/src/main/java/dev/chyken/assembl_ar/Phase6GuideController.java
- app/src/main/java/dev/chyken/assembl_ar/PhaseAssemblyController.java
- app/src/main/java/dev/chyken/assembl_ar/ProjectDetailActivity.java
- app/src/main/java/dev/chyken/assembl_ar/WelcomeAssemblyDialog.java
- app/src/main/java/dev/chyken/assembl_ar/data/NeobotSeedData.java
- app/src/main/java/dev/chyken/assembl_ar/data/SchematicComponentCatalog.java
- app/src/main/java/dev/chyken/assembl_ar/model/CircuitWireRoute.java
- app/src/main/java/dev/chyken/assembl_ar/model/DriverWireColorConfig.java
- app/src/main/java/dev/chyken/assembl_ar/render/HomeRobotRenderer.java
- app/src/main/java/dev/chyken/assembl_ar/render/HomeRobotSurfaceView.java
- app/src/main/java/dev/chyken/assembl_ar/ui/ArHudView.java
- app/src/main/java/dev/chyken/assembl_ar/ui/AssemblyDemoView.java
- app/src/main/java/dev/chyken/assembl_ar/ui/BrandLaunchOverlay.java
- app/src/main/java/dev/chyken/assembl_ar/ui/BrandingAssets.java
- app/src/main/java/dev/chyken/assembl_ar/ui/ComponentPreviewWindow.java
- app/src/main/java/dev/chyken/assembl_ar/ui/DriverSchematicRenderer.java
- app/src/main/java/dev/chyken/assembl_ar/ui/DriverWireColorPickerDialog.java
- app/src/main/java/dev/chyken/assembl_ar/ui/LogoFormationSpec.java
- app/src/main/java/dev/chyken/assembl_ar/ui/LogoFormationView.java
- app/src/main/java/dev/chyken/assembl_ar/ui/MotorWiringArtworkRenderer.java
- app/src/main/java/dev/chyken/assembl_ar/ui/PhysicalCheckUi.java
- app/src/main/java/dev/chyken/assembl_ar/ui/SchematicArtworkCache.java
- app/src/main/java/dev/chyken/assembl_ar/ui/SchematicZoomFrame.java
- app/src/main/java/dev/chyken/assembl_ar/ui/StartupRevealGate.java
- app/src/main/java/dev/chyken/assembl_ar/ui/StepInfoPanelController.java
- app/src/main/java/dev/chyken/assembl_ar/ui/UiUtils.java
- app/src/main/java/dev/chyken/assembl_ar/ui/WiringWorkspace.java
- app/src/main/java/dev/chyken/assembl_ar/workflow/phase/AssemblyPhaseCatalog.java
- app/src/main/java/dev/chyken/assembl_ar/workflow/phase/Phase6DriverWiringFlow.java
- app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseFlow.java
- app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseGuideMetadata.java
- app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseProgressStore.java
- app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseSessionCheckpoint.java
- app/src/main/res/anim/tab_enter.xml
- app/src/main/res/anim/tab_exit.xml
- app/src/main/res/drawable/bg_canvas_gradient.xml
- app/src/main/res/layout-sw600dp/activity_main.xml
- app/src/main/res/layout/activity_main.xml
- app/src/main/res/layout/activity_project_selection.xml
- app/src/main/res/layout/dialog_driver_wire_color_picker.xml
- app/src/main/res/layout/dialog_welcome_assembly.xml
- app/src/main/res/values/colors.xml
- app/src/main/res/values/strings.xml
- app/src/main/res/values/welcome_assembly.xml
- app/src/main/res/values/wiring_ui.xml
- app/src/test/java/dev/chyken/assembl_ar/data/SchematicComponentCatalogTest.java
- app/src/test/java/dev/chyken/assembl_ar/model/CircuitWireRouteTest.java
- app/src/test/java/dev/chyken/assembl_ar/model/DriverWireColorConfigTest.java
- app/src/test/java/dev/chyken/assembl_ar/ui/LogoFormationSpecTest.java
- app/src/test/java/dev/chyken/assembl_ar/ui/StartupRevealGateTest.java
- app/src/test/java/dev/chyken/assembl_ar/vision/Phase6RecognitionRequestTest.java
- app/src/test/java/dev/chyken/assembl_ar/workflow/phase/AssemblyPhaseCatalogTest.java
- app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase5MicrocontrollerFlowTest.java
- app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase6DriverWiringFlowTest.java
- app/src/test/java/dev/chyken/assembl_ar/workflow/phase/PhaseProgressTest.java
- docs/PHASE6_AND_UI_OVERHAUL.md
- docs/WIRING_SCHEMATIC_GUIDELINES.md
