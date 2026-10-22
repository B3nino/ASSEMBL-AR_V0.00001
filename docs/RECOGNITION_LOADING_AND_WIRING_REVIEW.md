# Recognition preparation and wiring review repair

## Recognition preparation

The assembly Activity previously prepared every photo in the reference database before
attaching recognition, even when the active task only needed the Phase 9 standoff dataset.
Tablet logs showed unrelated Phase 7 photos still being prepared while the user waited.

The Activity now indexes catalog metadata once and prepares a bank for the active scan on
the dedicated loader executor. The active bank contains the appropriate positive and
negative references; changing the bank does not unbind the CameraX preview. Abandoned
loads are cancelled between photos, stale results are ignored, and native resources are
released after pending analysis. Preparation shows photo progress and an explicit retry
action on failure. Non-scanning demos and preparation cards do not start a new bank.

Phase 9's installed standoff and wheel checks share one bank. Phase 7's dual-tracker bank
retains the individual tracker support photos. Earlier phases keep their target-specific
and rejection evidence. No recognition acceptance thresholds were weakened by this repair.
Phase 3, 5, and 8 geometry sidecars also avoid decoding source photos again on warm loads.

## Wiring review

The assembly header's **Wiring** button opens a read-only diagram library:

- Phase 4 motor leads.
- Phase 6 Nano and motor driver.
- Phase 8.3 Trigger and Echo.
- Phase 8.4 LED and speaker.
- Phase 8.5 both line trackers.

Diagrams use applied saved colors (or current in-memory applied choices), the existing
orthogonal wire renderer, zoom/pan, and spoken wire labels. Unconfigured diagrams use
defaults. Opening the library pauses the underlying guide; closing it returns to the
same assembly screen without changing verification or picker state. Completed wiring
screens also expose **Review wiring diagram** instead of repeating their checklist.

## Verification on 22 October 2026

- 298 host JUnit tests passed, including active-bank selection, checkpoint color restoration,
  and geometry-cache round trips/corruption fallback.
- Debug app and instrumentation APKs built successfully. The new native bank-loading test
  was compiled; it was not executed as instrumentation in this follow-up.
- User deployed through Android Studio to tablet 2603ARP14G (ADB serial `368e278b`).
- Deployed app logs: 739 catalog entries indexed in 1,950 ms; Phase 9 standoff bank,
  18 photos, ready in 1,006 ms; wheel bank, 47 photos, in 2,219 ms; installed bank,
  51 photos, in 3,213 ms. These are observed launches with the tablet's existing cache,
  not a fresh-install benchmark.
- A tablet screenshot confirmed live camera preview and Step 9.3C's physical standoff
  checklist, beyond the previously blocked preparation screen. No app data was cleared
  and no physical checklist was answered by the agent.

## Files for this follow-up

Modified:

- `app/src/main/java/dev/chyken/assembl_ar/AssemblyActivity.java`
- `app/src/main/java/dev/chyken/assembl_ar/PhaseAssemblyController.java`
- `app/src/main/java/dev/chyken/assembl_ar/Phase4GuideController.java`
- `app/src/main/java/dev/chyken/assembl_ar/Phase6GuideController.java`
- `app/src/main/java/dev/chyken/assembl_ar/Phase8GuideController.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/ReferenceDatabase.java`
- `app/src/main/res/layout/activity_assembly.xml`
- `app/src/main/res/values/strings.xml`

Added:

- `app/src/main/java/dev/chyken/assembl_ar/camera/ActiveReferenceScope.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/PhotoGeometryCache.java`
- `app/src/main/java/dev/chyken/assembl_ar/workflow/phase/WiringDiagramState.java`
- `app/src/main/java/dev/chyken/assembl_ar/ui/WiringDiagramReviewDialog.java`
- `app/src/test/java/dev/chyken/assembl_ar/camera/ActiveReferenceScopeTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/vision/PhotoGeometryCacheTest.java`
- `app/src/test/java/dev/chyken/assembl_ar/workflow/phase/WiringDiagramStateTest.java`
- `app/src/androidTest/java/dev/chyken/assembl_ar/vision/ActiveReferenceLoadingInstrumentedTest.java`
- This document.

First preparation of an uncached bank still requires feature extraction. Its duration
depends on its own photo count and device performance, rather than every phase's photos.
