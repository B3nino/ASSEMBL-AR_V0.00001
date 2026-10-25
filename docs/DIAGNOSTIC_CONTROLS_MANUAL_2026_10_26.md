# ASSEMBL-AR diagnostic controls manual

Current-build guide · 26 October 2026

The diagnostics tools investigate recognition, tracking and performance. The assembly workflow remains the authority for completing steps. Running a test, saving a frame, replaying a sample or collecting a benchmark does not advance assembly progress.

## Individual part AR test

The camera occupies the left side on a landscape tablet. The scrollable recognition workspace occupies the right. Mint surfaces, charcoal text, green primary actions and outlined secondary actions match the diagnostic controls used from Assembly Mode.

| Control or display | Purpose and actual behavior | When to use it |
| --- | --- | --- |
| Back arrow | Leaves this tester. Its in-memory results belong to this activity session; export before leaving if needed. | Return to the previous screen. |
| Camera preview | Shows the physical camera feed. Tap the object to request focus/exposure metering. | Put the selected target in view with its complete body visible. |
| Target selector | Opens the searchable, categorized reference catalog. It includes loose kit parts and supported assembled verification targets. The displayed total reflects the current catalog, not a fixed number of phases. | Choose exactly the part or assembly being tested. |
| ‹ / › | Selects the previous/next library target and stops the previous test. | Quickly change targets without opening search. |
| Start test / Pause test | Starts fresh analysis for the selected target, or pauses it. Starting creates a new run token so queued results from the previous run cannot become current measurements. | Start after positioning the object; pause before changing the setup. |
| Reference/keypoint summary | Lists the target's prepared references and assigned template keypoints. This is reference inventory, not the number extracted from one live frame. | Check that the target has a real reference basis. |
| Live metrics | Shows detection method, live frame size/features, verifier confidence, available match counts and stable observations. Confidence is a verifier score, not an ORB accuracy percentage. | Understand why the current target is still acquiring or tracking. |
| Status strip | Describes waiting, searching, wrong identity, identified target or stable tracking. | Read the immediate scanning instruction. |
| Diagnostic control guide | Opens an in-app explanation of these tools. A running individual test pauses while the guide is open and resumes when it closes. | Look up a button without leaving the tester. |
| Benchmark & logging | Opens the same diagnostic controls available in Assembly Mode. Camera analysis pauses while this dialog is open. Return to camera resumes a test that was running. | Configure optional recording, academic batches and measured ground truth. |
| Recognition results | Pauses the individual test and shows analyzed frames, expected detections, wrong/no detections and stable locks for this tester session. Expected detection rate is a count of detected frames; it is **not** Good Matches ÷ Total Matches. | Review recognition history. Tap Start test to continue afterward. |
| Download diagnostic logs | Builds an export in app-specific Documents storage, then opens Android's share chooser. It does not enable logging retroactively. | Share a recorded session with the developer. |
| Save sample | Requests the **next analyzed frame**, including available grayscale/color pixels, calibration and metadata. It offers an `.arpart.zip` file for saving and retains a copy for the diagnostic session bundle. It needs an active test and analyzer. | Capture the exact difficult view before moving the camera. |
| Replay sample | Opens a saved `.arpart.zip` and runs those recorded pixels through the current scanner. Old archives without color support can only replay the available grayscale path. This is neither training nor a live movement test. | Compare a reproducible failure before and after a scanner change. |
| Reset test results | Stops this test and clears the selected component's in-memory tester statistics. Statistics are grouped by component identity, so targets sharing that identity share this result group. It does not clear assembly progress, references, preferences, benchmark history or previously exported files. | Begin a clean recognition-results run for that component. |

## Shared Benchmark & logging controls

| Control | Purpose and limits |
| --- | --- |
| Developer Diagnostic Logging | Enables/disables future raw scan and overlay logging. It is optional for ordinary assembly. Recording and exports add I/O work; leave logging off when measuring ordinary-use smoothness unless logs are needed. |
| Movement sequence | Labels the movement the tester will perform: Static, Pan, Approach / retreat, or Rotation. The app does not move the camera or generate ground truth from this selection. |
| Start 30-trial batch | Enables logging and collects up to 30 fresh analyzed trials in the current automatically estimated lighting category. Close the dialog with Return to camera so fresh frames can arrive. Repeat under other real lighting conditions for a complete four-condition report. Switching targets separates their reports. |
| Stop batch | Stops benchmark collection while retaining its accumulated results. Logging has a separate toggle and can remain enabled. |
| Clear benchmark results | Asks for confirmation, then stops the batch and clears in-memory academic trials/reports. It preserves assembly progress, references and exported files. |
| Configure checkerboard ground truth | Accepts measured inner-corner columns/rows, square size and target-center X/Y in millimeters, plus grid orientation. These must be measured independently, not guessed from the AR overlay. The checkerboard must remain visible, coplanar with the target, and supported by suitable camera intrinsics. |
| Apply / Disable in checkerboard dialog | Apply activates the entered measured reference. Disable removes it. Without valid ground truth, physical error values remain Unmeasured. This setting does not replace normal component recognition. |
| View live report | Opens the current accumulated academic report. The displayed report is a snapshot; reopen to refresh it. |
| Download Diagnostic Logs | TXT contains build/report information and scan history. CSV contains raw scan rows. ZIP additionally includes keypoint inventory, overlay history and saved frame archives from the current logger session. It opens Android sharing rather than silently copying to Downloads. |
| Capture current scan frame — Assembly only | Queues the next analyzed assembly frame for a replay archive in the session bundle. Return to camera after requesting it, then export ZIP. It neither confirms nor skips the assembly step. |
| Return to camera / Done | Closes diagnostic controls. The assembly camera resumes its retained step; the individual tester resumes if it had been running. |

Exported files are timestamped beneath the app's external Documents `diagnostics` folder. A session starts with the app process; previously exported files remain separate. Logging is bounded; the text report includes dropped-row information. A ZIP is a diagnostic bundle, not an application update or new trained model.

## Assembly DEV controls

These are development navigation controls, separate from ordinary scan acceptance and academic measurements. Use the saved-progress return action after a developer trial. A developer bypass does **not** create measured keypoints, a verified camera frame or a fabricated passing diagnostic snapshot.

| Control | Current purpose |
| --- | --- |
| Hold AR LIVE | Toggles the elevated live telemetry HUD. Showing it does not itself start logging or a benchmark. |
| DEV → Show / Hide live diagnostics | Toggles that same HUD through the developer menu. |
| Complete current phase · stay here | Marks the current developer guide as complete for navigation testing; it stays on that phase rather than automatically opening the next one. It bypasses real verification and must not be used as evidence of scan accuracy. |
| Jump to assembly phase | Opens a selected phase in developer navigation. The current assembly workflow contains 11 phases. |
| Choose phase and sub-step | Opens the chosen phase's current substep list. This is the direct route for reproducing a particular scan or preview issue. |
| Jump to manual section (1–18) | Compatibility navigation for the original manual's 18 numbered sections, mapped into the current 11 assembly phases. It does not mean the application still has 18 assembly phases. Prefer phase/substep navigation for current testing. |
| Skip current sub-step · stay here | Bypasses the current developer checkpoint for UI/navigation testing and exposes its next action. It does not submit a successful vision trial. |
| Return to saved assembly progress | Available after entering a developer session; reloads the learner's saved sequential assembly location. |

The diagnostic controls dialog is opened by the assembly diagnostic action. Its Capture current scan frame action and export functions are described above. Ordinary Next, Previous, Ready to Scan, physical checklist and Rescan controls remain part of the assembly workflow, rather than this benchmark tool.

## Reading the shared telemetry HUD

Both camera modes use one layout, one numeric formatter and one green/red output renderer. The elevated overlay stays above preview panels; it does not intercept their touch controls or allocate a large shadow.

- **Scanned component:** the spatially verified candidate, rather than the highest-scoring unverified scheduling probe. `(VERIFIED)` identifies accepted spatial verification.
- **Active keypoints / Live keypoints:** assigned template count for a bound reference versus the current frame's extracted features. Unknown counts stay Unmeasured. A geometry-only verifier has no invented reference count.
- **Good/Matches and %:** real deduplicated Lowe/Hamming matches and attempted pairs for the bound candidate. The 85% academic ORB gate remains independent of workflow verification. Geometry confidence is never substituted for this percentage.
- **Extraction / Matching times:** measured preprocessing/extraction and matching/geometric-filtering durations. They are not a stopwatch for the whole human assembly step.
- **Correct Pose / Orientation:** measured 3D status where available. An accepted 2D shape or board-relative placement does not manufacture a calibrated 3D pose. Unavailable pose values say Unmeasured; available 2D orientation labels remain a fallback.
- **TRE:** 3D millimeter error against independently measured checkerboard ground truth, when available. It is not zero by default.
- **Projected / Observed points and Pixel distance:** projected model center, observed inlier centroid and their Euclidean separation. Those centers are different point definitions; this distance is separate from solver RMS and checkerboard registration error.
- **Total frames:** the cumulative analyzed-frame count when that snapshot was recorded. A frozen assembly HUD deliberately retains its winning count.
- **Output: Match (VERIFIED):** the accepted spatial result, plus its verification source and available verifier confidence. This can coexist with an ORB gate FAIL or unmeasured 3D values without falsifying them.

After the assembly controller actually confirms a scan, its diagnostic HUD freezes on that exact winning frame. Later frames cannot overwrite it with Unverified/Mismatch. Live outlines and raw diagnostic logging continue. Next, another substep, or explicit Rescan opens a fresh telemetry scope; pausing/resuming a completed scan retains its snapshot within the activity. The individual tester deliberately stays live because it is a continuous diagnostic camera.

## What the benchmark report proves

**A — ORB performance:** true match yield, recognition rates across automatically estimated Normal/Bright/Weak/Uneven categories, and extraction/matching timings. Image-based light categories are estimates, not lux measurements. Incomplete categories remain incomplete; a single 30-trial run does not fabricate four environments.

**B — pose:** measured correct-pose classification, orientation mismatch and 3D TRE. Physical ground truth and valid calibration are required for millimeter accuracy claims.

**C — tracking:** measured checkerboard anchor error and computed-pose coverage for the selected motion. Predicted holds are excluded from computed-pose coverage. CameraX-dropped frames are not counted as delivered frames. This is separate from the displayed center-to-inlier-centroid distance and solver reprojection RMS.

## Useful procedures

For a difficult recognition: select the correct target, Start test, tap to focus, keep the whole target visible, then Save sample while the failure is present. Enable logging before collecting additional trials. Export ZIP to include the archive and raw session data. A screen recording can provide movement/context, but a raw sample supplies the exact analyzed pixels.

The current sample archive preserves component identity, pixels, calibration and outcome text; it does not encode every assembly substep's state machine. The in-app Replay sample action checks component recognition and does not automatically reproduce a full phase-specific installation gate. Use the actual assembly scan to check installation acceptance. Dedicated native phase replay fixtures cover those gates separately. This distinction matters when sending an assembly capture for diagnosis.

For a performance benchmark: keep one target selected, choose the real motion sequence, enable/start the batch, Return to camera, and perform that sequence. Review the report. Repeat with appropriate lighting and measured checkerboard setup as needed. Stop before clearing results or changing the experimental protocol.

## Relevance review and remaining boundaries

The current controls all connect to active library, scanner, replay, logger or benchmark code; none trains a model or overrides matcher thresholds. Assembly capture and individual sample replay are complementary, not duplicate acceptance gates. The shared UI now exposes their purposes and replaces the misleading generic “Benchmark / Settings” title with “Benchmark & logging.”

Physical seating, torque, hidden nut security, electrical continuity and axle grip still require the explicit assembly checklists. No diagnostic button can prove those properties from a top-view photograph. This update retains scan policy, phase completion gates and user progress.
