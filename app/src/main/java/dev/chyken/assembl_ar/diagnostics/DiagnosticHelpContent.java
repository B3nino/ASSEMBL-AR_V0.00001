package dev.chyken.assembl_ar.diagnostics;

/** User-facing descriptions of the current diagnostic tools; no detector configuration. */
public final class DiagnosticHelpContent {
    private DiagnosticHelpContent() { }
    public static final String[][] SECTIONS={
        {"Choose a target", "The selector opens the searchable reference library. Arrows select the previous or next target. Loose parts and assembly targets use their own reference sets; target count changes with the library."},
        {"Start / Pause test", "Start analyzes fresh camera frames for the selected target. Pause stops test analysis. Tap the camera to focus and adjust exposure. Individual tests never complete an assembly step."},
        {"Recognition results", "Shows this tester session's analyzed frames, expected detections, wrong detections and stable locks. Expected detection rate is not the ORB good/total percentage. A lock requires repeated stable observations; this is not a physical tightness check."},
        {"Save sample / Replay sample", "Save sample captures the next analyzed camera frame and its calibration into an .arpart.zip file. Replay sample opens a saved archive and runs its recorded pixels through the current scanner. It does not retrain the app or change assembly progress. Replay is a diagnostic check, not live tracking."},
        {"Reset test results", "Clears only the selected component's in-memory tester results and stops its test. It does not erase references, assembly progress, preferences, exported files or benchmark history."},
        {"Benchmark & logging", "Camera analysis pauses while this dialog is open. Return to camera resumes a test that was running. Logging records raw measurements separately from the HUD; it can add storage and processing work. Leave it off for ordinary assembly."},
        {"Start 30-trial batch", "Collects 30 fresh analyzed scans in the current automatically estimated lighting category after you return to the camera. The motion selector labels the movement YOU perform: Static, Pan, Approach / retreat, or Rotation. It does not move the camera. Repeat in other lighting and motion conditions; the app cannot create those conditions."},
        {"Stop / Clear benchmark", "Stop ends collection and retains results. Clear asks for confirmation, then stops collection and removes in-memory benchmark trials and reports only. It does not delete exported logs or assembly progress."},
        {"Checkerboard ground truth", "For measured tests B and C, use a visible printed checkerboard, independently measured square size and target-center coordinates, and suitable camera intrinsics. Apply enables that reference; Disable removes it. Do not guess the measurements. Missing 3D values stay Unmeasured."},
        {"View live report", "Shows A: ORB yield, estimated-lighting recognition and timing; B: measured pose and 3D TRE; C: checkerboard anchor error and computed-pose coverage. Incomplete lighting samples and unavailable calibration stay Unmeasured. A geometry-verified assembly can still fail the separate ORB benchmark."},
        {"Download diagnostic logs", "Creates a timestamped export and opens Android's share sheet. TXT contains the report and scan history; CSV contains raw scan rows; ZIP adds inventory, overlay rows and saved .arpart.zip frames. Logging must be enabled before the scans you want recorded. This button does not start recording or retrain references."},
        {"Assembly: Capture current scan frame", "Queues the next analyzed assembly frame for the session bundle without completing or skipping a step. Return to camera so that the queued capture can run, then export ZIP."},
        {"Verified HUD", "Both camera screens use the same telemetry layout and colors. Match (VERIFIED) names the spatially verified target and its verification source. Verifier confidence is not ORB accuracy. When an assembly scan completes, its HUD snapshot freezes until another step or an explicit rescan; outlines keep tracking. The individual tester remains live."}
    };
}
