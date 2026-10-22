# Phase 7 — dedicated hardware preparation

## Six screens, with existing verification step numbers retained

1. **7.1a: Scan caster wheel.** Only the caster model is previewed. A successful scan unlocks
   **Next: Prepare hardware** and preserves the opportunity to inspect additional information.
2. **7.1b: Caster mounting hardware.** Gather **2 × 20mm screws** and **4 × M3 nuts**.
   Both labeled models appear together in the upper-right viewer. **I Have These** unlocks Next.
3. **7.2a: Scan both line trackers.** Only the tracker model is previewed. Both modules must
   be identified together before Next opens their hardware page.
4. **7.2b: Tracker mounting hardware.** Gather **2 × 10mm screws**, **2 × nylon spacers**, and
   **2 × M3 nuts**. All three labeled models appear together; **I Have These** unlocks Next.
5. **7.3:** Existing caster demos, bottom-view verification and physical checks.
6. **7.4:** Existing tracker demos, bottom-view verification and physical checks.

Hardware pages suspend camera analysis and scanning animation. Parts rotate in place under their
labels using the existing preview gesture controls. The authored screw, nut and spacer assets
are reused; no new mesh files are required. Each screen's instructions are narrated through the
existing asynchronous voice guide. Hardware confirmation stays on its page so the user can
inspect the models or extra information before pressing Next.

## Navigation and persistence

- Previous returns through scan → hardware pages without clearing completed gates.
- Rescan, same-step demo replay and Return to current phase retain their guarded behavior.
- Snapshot version 3 records the hardware page; versions 1 and 2 preserve their scans and
  confirmations during migration. Existing assembly scans are not invalidated.
- Project details and the DEV sub-step picker list all six screens. Legacy manual-section
  shortcuts still open 7.3/7.4, and skipping a component scan does not also confirm its hardware.
- OpenCV stages remain 1–4; the already verified camera matcher and reference registrations
  are unchanged.

## Verification

- All 210 main Java sources compiled and all **244 host tests passed**.
- The Gradle app and Android-test APKs build locally. Instrumentation assertions cover both
  hardware previews, correct screw lengths and spacer visibility, and guarded progression.
- Following the user's preference, no tablet access or installation is performed for this UI
  update. Device rendering and the final UI walkthrough are left to Android Studio.

## Files changed

- `app/src/main/java/dev/chyken/assembl_ar/Phase7GuideController.java`
- `app/src/main/java/dev/chyken/assembl_ar/PhaseAssemblyController.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/Phase7CasterTrackerFlow.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/PhaseGuideMetadata.java`
- `app/src/main/java/dev/chyken/assembl_ar/ui/ComponentPreviewWindow.java`
- `app/src/main/java/dev/chyken/assembl_ar/render/ComponentModelCatalog.java`
- `app/src/main/java/dev/chyken/assembl_ar/render/HomeRobotRenderer.java`
- `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase7CasterTrackerFlowTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/Phase5MicrocontrollerFlowTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/render/ComponentModelCatalogTest.java`
- `app/src/androidTest/java/dev/chyken/assembl_ar/PhaseWorkflowInstrumentedTest.java`
- `docs/PHASE7_CASTER_TRACKERS.md`
- `docs/PHASE7_HARDWARE_PREPARATION.md`

No layout XML or reference assets changed. Changes to the OpenGL code apply only to the component
preview's grouped fastener models, not the live camera renderer.
