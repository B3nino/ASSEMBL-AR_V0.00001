package dev.chyken.assembl_ar;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.diagnostics.TrialLog;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.ProjectProgress;
import dev.chyken.assembl_ar.model.StepStatus;
import dev.chyken.assembl_ar.vision.CameraCalibration;
import dev.chyken.assembl_ar.vision.MotorAssemblyObservation;
import dev.chyken.assembl_ar.vision.RecognitionRequest;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate;
import dev.chyken.assembl_ar.workflow.GuidedStepProgressStore;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhaseCatalog;
import dev.chyken.assembl_ar.workflow.phase.PhaseFlow;
import dev.chyken.assembl_ar.workflow.phase.PhaseFlow.Mode;
import dev.chyken.assembl_ar.workflow.phase.PhaseProgressStore;
import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The grouped phase guide on the real assembly screen, driven by synthetic tagged recognition
 * results (no camera): inventory → automatic demo → Scan → per-target verification, correction,
 * direct checklist and atomic multi-section save.
 */
@RunWith(AndroidJUnit4.class)
public final class PhaseWorkflowInstrumentedTest {
    private static final long MILLIS = 1_000_000L;

    @After
    public void restoreFreshProgress() {
        positionAt(1);
    }

    @Test
    public void phaseOneStartsCompactAndKeepsScanSuccessUntilNextIsTapped() {
        positionAt(1);
        try(ActivityScenario<AssemblyActivity> scenario=launch()) {
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.BOARD_PREPARATION);
            scenario.onActivity(activity -> {
                dev.chyken.assembl_ar.ui.PhaseOneGuideView guide=activity.findViewById(R.id.phaseOneGuide);
                assertEquals(View.VISIBLE,guide.getVisibility());
                assertEquals(View.GONE,activity.findViewById(R.id.instructionPanel).getVisibility());
                assertFalse(guide.isExpanded());
                assertFalse(guide.isNextEnabled());
                Frames frames=new Frames(activity,controller);
                for(int i=0;i<4;i++) frames.deliver(frames.component(NeobotSeedData.MAIN_BOARD,
                        frames.board,Collections.emptyList(),1,null,null),null);
                assertEquals(Mode.COMPLETE,controller.getFlow().getMode());
                assertTrue(guide.isNextEnabled());
                assertFalse("Phase 1 stays open until explicit Next",PhaseProgressStore.records(activity,
                        NeobotSeedData.PROJECT_ID).isPhaseComplete(AssemblyPhaseCatalog.BOARD_PREPARATION));
            });
            scenario.recreate();
            PhaseAssemblyController restored=awaitPhase(scenario,AssemblyPhaseCatalog.BOARD_PREPARATION);
            scenario.onActivity(activity -> {
                dev.chyken.assembl_ar.ui.PhaseOneGuideView guide=activity.findViewById(R.id.phaseOneGuide);
                assertEquals(Mode.COMPLETE,restored.getFlow().getMode());
                assertTrue("Recreation keeps the confirmed scan",guide.isNextEnabled());
                ((View)getField(guide,"learn")).performClick();
                assertTrue(guide.isExpanded());
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                View guide=activity.findViewById(R.id.phaseOneGuide);
                View preview=activity.findViewById(R.id.componentPreviewWindow);
                android.graphics.RectF card=new android.graphics.RectF(guide.getX(),guide.getY(),
                        guide.getX()+guide.getWidth(),guide.getY()+guide.getHeight());
                android.graphics.RectF model=new android.graphics.RectF(preview.getX(),preview.getY(),
                        preview.getX()+preview.getWidth(),preview.getY()+preview.getHeight());
                assertFalse("Expanded facts must stay clear of the 3D SurfaceView",
                        android.graphics.RectF.intersects(card,model));
                activity.findViewById(R.id.nextGuidedStepButton).performClick();
            });
            awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
        }
    }

    @Test
    public void splittingDriverWiringPreservesBothOldCompletionAndInventory() {
        Context context=ApplicationProvider.getApplicationContext();
        String prefix=NeobotSeedData.PROJECT_ID+"\u001F";
        context.getSharedPreferences("assembly_phase_progress",Context.MODE_PRIVATE).edit()
                .putInt("schema_version",1)
                .putBoolean(prefix+"phase\u001F"+AssemblyPhaseCatalog.DRIVER_WIRING,true)
                .putBoolean(prefix+"inventory\u001F"+AssemblyPhaseCatalog.DRIVER_WIRING,true).commit();
        assertTrue(PhaseProgressStore.records(context,NeobotSeedData.PROJECT_ID)
                .isPhaseComplete(AssemblyPhaseCatalog.DRIVER_WIRING));
        assertTrue(PhaseProgressStore.records(context,NeobotSeedData.PROJECT_ID)
                .isPhaseComplete(AssemblyPhaseCatalog.DRIVER_CONTROL));
        assertTrue(PhaseProgressStore.isInventoryComplete(context,NeobotSeedData.PROJECT_ID,
                AssemblyPhaseCatalog.DRIVER_CONTROL));
    }

    @Test
    public void lookingBackKeepsTheActivePhaseAndResumesItsScanner() {
        positionAt(2);
        try(ActivityScenario<AssemblyActivity> scenario=launch()) {
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
            scenario.onActivity(activity->{
                PhaseFlow active=controller.getFlow();
                activity.findViewById(R.id.previousGuidedStepButton).performClick();
                assertEquals(active,controller.getFlow());
                assertEquals(Mode.SCAN_PARTS,active.getMode());
                assertTrue(controller.isAnalysisSuspended());
                activity.findViewById(R.id.nextGuidedStepButton).performClick();
                assertEquals(active,controller.getFlow());
                assertFalse(controller.isAnalysisSuspended());
                assertEquals(2,controller.requestFor(CameraCalibration.pinhole(500,500,320,240)).getAssemblyStepNumber());
            });
        }
    }

    @Test
    public void developerCompletionStaysSelectedAndCanReturnToPhaseOneScanning() {
        positionAt(4);
        try(ActivityScenario<AssemblyActivity> scenario=launch()) {
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_LEADS);
            scenario.onActivity(activity->{
                controller.devForceCompleteCurrentPhase();
                assertEquals(Mode.COMPLETE,controller.getFlow().getMode());
                assertEquals(AssemblyPhaseCatalog.MOTOR_LEADS,controller.getFlow().getPhase().getId());
                controller.devJumpToPhase(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.BOARD_PREPARATION));
                assertEquals(Mode.SCAN_PARTS,controller.getFlow().getMode());
                assertFalse(controller.isAnalysisSuspended());
                assertEquals(1,controller.requestFor(CameraCalibration.pinhole(500,500,320,240)).getAssemblyStepNumber());
            });
        }
    }

    @Test
    public void motorPhaseStartsWithTheWholeGroupAndHonestInventoryLabels() {
        positionAt(2);
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            PhaseAssemblyController controller = awaitPhase(scenario, AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
            scenario.onActivity(activity -> {
                assertEquals(Mode.SCAN_PARTS, controller.getFlow().getMode());
                assertEquals("Phase 2 of 18", text(activity, R.id.stepCounter));
                String inventory = text(activity, R.id.phaseInventory);
                assertTrue(inventory, inventory.contains("◉ 4 × Gear-motor bracket — scanning"));
                assertTrue(inventory.contains("○ 2 × 6 V DC gear motor — camera scan next"));
                assertTrue(inventory.contains("☐ 4 × 30 mm screw"));
                assertTrue(inventory.contains("camera cannot check this yet"));
                assertTrue(inventory.contains("already installed"));
                assertTrue(inventory.contains("(or Nail polish"));
                assertEquals("Scan is not offered before the parts are found",
                        View.GONE, activity.findViewById(R.id.scanAssemblyButton).getVisibility());
                assertFalse(controller.isAnalysisSuspended());
                RecognitionRequest request = controller.requestFor(CameraCalibration.pinhole(500, 500, 320, 240));
                assertNotNull(request);
                assertEquals(4, request.getExpectedInstanceCount());
                assertTrue(request.inspectsLooseBracketSet());
                assertEquals(controller.getFlow().tag(), request.getRequestTag());
                assertTrue(text(activity, R.id.buildStampLabel).startsWith("v1.1."));
            });
            capture(scenario, "phase-motor-inventory");
        }
    }

    @Test
    public void groupedInventoryStartsTheWholeDemoAndSuspendsCameraAnalysis() {
        positionAt(2);
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            PhaseAssemblyController controller = awaitPhase(scenario, AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
            scenario.onActivity(activity -> {
                Frames frames = new Frames(activity, controller);
                // A wrong tag (for example from an earlier attempt) never counts.
                for (int index = 0; index < 5; index++) {
                    frames.deliver(frames.brackets(4), new PhaseFlow.ScanTag(
                            AssemblyPhaseCatalog.MOTOR_ASSEMBLIES, Mode.SCAN_PARTS, -1));
                }
                assertEquals(Mode.SCAN_PARTS, controller.getFlow().getMode());
                assertTrue(text(activity, R.id.phaseInventory).contains("◉ 4 × Gear-motor bracket"));

                for (int index = 0; index < 3; index++) frames.deliver(frames.brackets(4), null);
                frames.deliver(frames.brackets(3), null);
                assertTrue("Three of four brackets restart the count",
                        text(activity, R.id.recognitionText).contains("3 of 4"));
                for (int index = 0; index < 4; index++) frames.deliver(frames.brackets(4), null);
                String inventory = text(activity, R.id.phaseInventory);
                assertTrue(inventory, inventory.contains("✓ 4 × Gear-motor bracket — all 4 seen together"));
                assertTrue(inventory.contains("◉ 2 × 6 V DC gear motor — scanning"));
                RecognitionRequest motorRequest = controller.requestFor(CameraCalibration.pinhole(500, 500, 320, 240));
                assertEquals(NeobotSeedData.GEAR_MOTOR, motorRequest.getExpectation().getComponentId());
                assertNull("A loose motor scan is not an installed-motor check",
                        motorRequest.getSpatialAnchorComponentId());

                for (int index = 0; index < 4; index++) frames.deliver(frames.looseMotor(), null);
                assertEquals(Mode.DEMO, controller.getFlow().getMode());
                assertTrue(controller.isAnalysisSuspended());
                assertNull(controller.requestFor(CameraCalibration.pinhole(500, 500, 320, 240)));
                assertTrue(text(activity, R.id.demoStage).contains("step 1 of 25"));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.scanAssemblyButton).getVisibility());
                assertTrue(text(activity, R.id.phaseInventory).contains(
                        "✓ 2 × 6 V DC gear motor — identified by camera (quantity not counted)"));
            });
            capture(scenario, "phase-motor-demo");
        }
    }

    @Test
    public void scanVerifiesTheMotorPairThenProbesEachBracketSlot() {
        positionAt(2);
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            PhaseAssemblyController controller = awaitPhase(scenario, AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
            scenario.onActivity(activity -> {
                Frames frames = new Frames(activity, controller);
                frames.completeInventory();
                controller.onFinished();
                assertEquals(Mode.ASSEMBLE, controller.getFlow().getMode());
                assertTrue("The device may be set down while assembling", controller.isAnalysisSuspended());
                PhaseFlow.ScanTag assembling = controller.getFlow().tag();
                activity.findViewById(R.id.scanAssemblyButton).performClick();
                assertEquals(Mode.VERIFY, controller.getFlow().getMode());
                RecognitionRequest request = controller.requestFor(CameraCalibration.pinhole(500, 500, 320, 240));
                assertTrue(request.inspectsInstalledMotors());

                // A frame requested before Scan is not verification evidence.
                frames.deliver(frames.installedMotors(true, true), assembling);
                assertNull(controller.getSession());

                for (int index = 0; index < 4; index++) frames.deliver(frames.installedMotors(false, false), null);
                for (int index = 0; index < 6; index++) frames.deliver(frames.installedMotors(true, true), null);
                PhaseVerificationSession session = controller.getSession();
                assertTrue(session.getResult("left_motor").isAccepted());
                assertTrue(session.getResult("right_motor").isAccepted());
                assertEquals("bracket_slot_1", session.getActiveGroup());
                RecognitionRequest slot = controller.requestFor(CameraCalibration.pinhole(500, 500, 320, 240));
                assertEquals(1, slot.getGuidedBracketSlot());
                assertTrue("Motors stay inspected while brackets are probed",
                        slot.requestsInstalledMotorInspection());
                String results = text(activity, R.id.phaseResults);
                assertTrue(results, results.contains("✓ Left motor"));
                assertTrue(results.contains("… Bracket 1 · left outer slot"));
            });
            capture(scenario, "phase-motor-verifying");
        }
    }

    @Test
    public void aMisplacedMotorBecomesANamedCorrectionWithItsDemoChapters() {
        positionAt(2);
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            PhaseAssemblyController controller = awaitPhase(scenario, AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
            scenario.onActivity(activity -> {
                Frames frames = new Frames(activity, controller);
                frames.completeInventory();
                controller.onFinished();
                activity.findViewById(R.id.scanAssemblyButton).performClick();
                for (int index = 0; index < 4; index++) frames.deliver(frames.installedMotors(false, false), null);
                frames.deliver(frames.misalignedRight(), null);
                frames.deliver(frames.installedMotors(false, false), null);
                assertEquals("One noisy frame is not a correction", Mode.VERIFY, controller.getFlow().getMode());
                for (int index = 0; index < PhaseVerificationSession.ERROR_FRAMES; index++) {
                    frames.deliver(frames.misalignedRight(), null);
                }
                assertEquals(Mode.CORRECTION, controller.getFlow().getMode());
                assertEquals("right_motor", controller.getFlow().getCorrectionTargetId());
                assertTrue(text(activity, R.id.phaseResults).contains("✗ Right motor — wrong position"));
                assertTrue(text(activity, R.id.demoStage).contains("right motor"));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.scanAssemblyButton).getVisibility());
                assertFalse("Analysis continues only to keep the red target on the board",
                        controller.isAnalysisSuspended());
            });
            capture(scenario, "phase-motor-correction");
            scenario.onActivity(activity -> {
                Frames frames = new Frames(activity, controller);
                // Correction frames never count as verification evidence.
                for (int index = 0; index < 8; index++) frames.deliver(frames.installedMotors(true, true), null);
                assertEquals(Mode.CORRECTION, controller.getFlow().getMode());
                activity.findViewById(R.id.scanAssemblyButton).performClick();
                assertEquals(Mode.VERIFY, controller.getFlow().getMode());
                assertNull("A fresh Scan starts a fresh session", controller.getSession());
            });
        }
    }

    @Test
    public void pausingDuringAScanDiscardsItAndReplayNeverCompletes() {
        positionAt(2);
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            PhaseAssemblyController controller = awaitPhase(scenario, AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
            scenario.onActivity(activity -> {
                new Frames(activity, controller).completeInventory();
                controller.onFinished();
                activity.findViewById(R.id.replayDemoButton).performClick();
                assertEquals(Mode.DEMO, controller.getFlow().getMode());
                controller.onFinished();
                assertEquals(Mode.ASSEMBLE, controller.getFlow().getMode());
                activity.findViewById(R.id.scanAssemblyButton).performClick();
                assertEquals(Mode.VERIFY, controller.getFlow().getMode());
            });
            scenario.moveToState(Lifecycle.State.STARTED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(activity -> {
                assertEquals(Mode.ASSEMBLE, controller.getFlow().getMode());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.scanAssemblyButton).getVisibility());
            });
            assertEquals(StepStatus.IN_PROGRESS, statusOf(2));
        }
    }

    @Test
    public void aPassedPlacementScanOpensTheDirectChecklistAndSavesThePhase() {
        positionAt(9);
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            PhaseAssemblyController controller = awaitPhase(scenario, AssemblyPhaseCatalog.CASTER_FIT);
            scenario.onActivity(activity -> {
                Frames frames = new Frames(activity, controller);
                for (int index = 0; index < 4; index++) frames.deliver(frames.looseCaster(), null);
                assertEquals(Mode.DEMO, controller.getFlow().getMode());
                controller.onFinished();
                activity.findViewById(R.id.scanAssemblyButton).performClick();
                RecognitionRequest request = controller.requestFor(CameraCalibration.pinhole(500, 500, 320, 240));
                assertEquals(NeobotSeedData.CASTER_WHEEL, request.getExpectation().getComponentId());
                assertEquals(NeobotSeedData.MAIN_BOARD, request.getSpatialAnchorComponentId());
                for (int index = 0; index < 10 && controller.getFlow().getMode() == Mode.VERIFY; index++) {
                    frames.deliver(frames.installedCaster(), null);
                }
                assertEquals(Mode.PHYSICAL_CHECK, controller.getFlow().getMode());
                assertTrue(text(activity, R.id.phaseResults).contains("✓ Front caster"));
            });
            capture(scenario, "phase-caster-checklist");
            completeChecklist(scenario, controller);
            awaitPhase(scenario, AssemblyPhaseCatalog.LINE_TRACKERS);
            assertEquals(StepStatus.COMPLETED, statusOf(9));
            assertEquals(StepStatus.IN_PROGRESS, statusOf(10));
        }
    }

    @Test
    public void aWiringPhaseChecksDirectlyAndSavesBothOfItsSectionsAtOnce() {
        positionAt(4);
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            PhaseAssemblyController controller = awaitPhase(scenario, AssemblyPhaseCatalog.MOTOR_LEADS);
            scenario.onActivity(activity -> {
                assertEquals("Nothing new to scan starts the demo", Mode.DEMO, controller.getFlow().getMode());
                assertTrue(controller.isAnalysisSuspended());
                controller.onFinished();
                TextView scan = activity.findViewById(R.id.scanAssemblyButton);
                assertEquals(activity.getString(R.string.phase_check_work), scan.getText().toString());
                scan.performClick();
                assertEquals(Mode.PHYSICAL_CHECK, controller.getFlow().getMode());
            });
            completeChecklist(scenario, controller);
            awaitPhase(scenario, AssemblyPhaseCatalog.MICROCONTROLLER_FIT);
            assertEquals(StepStatus.COMPLETED, statusOf(4));
            assertEquals(StepStatus.COMPLETED, statusOf(5));
            assertEquals(StepStatus.IN_PROGRESS, statusOf(6));
            scenario.onActivity(activity ->
                    assertEquals("Phase 5 of 18", text(activity, R.id.stepCounter)));
        }
    }

    @Test
    public void aFinishedSensorMountResumesAtItsWiringPhase() {
        positionAt(11);
        Context context = ApplicationProvider.getApplicationContext();
        assertTrue(PhaseProgressStore.markComplete(context, NeobotSeedData.PROJECT_ID,
                AssemblyPhaseCatalog.DISTANCE_SENSOR_MOUNT));
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            awaitPhase(scenario, AssemblyPhaseCatalog.SENSOR_WIRING);
            scenario.onActivity(activity ->
                    assertEquals("Phase 11 of 18", text(activity, R.id.stepCounter)));
        }
    }

    @Test
    public void aSideLoadedClipWithChapterTimesReplacesTheSchematicDemo() throws IOException {
        positionAt(9);
        File[] files = sideLoadCasterClip(12);
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            PhaseAssemblyController controller = awaitPhase(scenario, AssemblyPhaseCatalog.CASTER_FIT);
            scenario.onActivity(activity -> {
                Frames frames = new Frames(activity, controller);
                for (int index = 0; index < 4; index++) frames.deliver(frames.looseCaster(), null);
                assertEquals(Mode.DEMO, controller.getFlow().getMode());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.phaseVideo).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.assemblyDemo).getVisibility());
            });
            // Chapter captions follow the clip's authored start times (one second apart here).
            awaitText(scenario, R.id.demoStage, "step 4 of 12", 15_000L);
            capture(scenario, "phase-caster-video");
            for (int attempt = 0; attempt < 200; attempt++) {
                Mode[] mode = new Mode[1];
                scenario.onActivity(activity -> mode[0] = controller.getFlow().getMode());
                if (mode[0] == Mode.ASSEMBLE) break;
                SystemClock.sleep(100L);
            }
            scenario.onActivity(activity -> {
                assertEquals("The end of the clip leaves the learner assembling",
                        Mode.ASSEMBLE, controller.getFlow().getMode());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.scanAssemblyButton).getVisibility());
            });
        } finally {
            for (File file : files) file.delete();
        }
    }

    @Test
    public void aClipWhoseChapterTimesDoNotFitFallsBackToTheSchematic() throws IOException {
        positionAt(9);
        File[] files = sideLoadCasterClip(11);
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            PhaseAssemblyController controller = awaitPhase(scenario, AssemblyPhaseCatalog.CASTER_FIT);
            scenario.onActivity(activity -> {
                Frames frames = new Frames(activity, controller);
                for (int index = 0; index < 4; index++) frames.deliver(frames.looseCaster(), null);
                assertEquals(Mode.DEMO, controller.getFlow().getMode());
                assertEquals(View.GONE, activity.findViewById(R.id.phaseVideo).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.assemblyDemo).getVisibility());
                assertTrue(text(activity, R.id.demoStage).contains("step 1 of 12"));
            });
        } finally {
            for (File file : files) file.delete();
        }
    }

    /** Writes the synthetic 12-second test clip and a manifest with {@code chapters} start times. */
    private static File[] sideLoadCasterClip(int chapters) throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        File media = context.getExternalFilesDir(
                dev.chyken.assembl_ar.workflow.phase.PhaseMediaCatalog.SIDELOAD_DIRECTORY);
        assertNotNull(media);
        assertTrue(media.isDirectory() || media.mkdirs());
        File video = new File(media, "phase_" + AssemblyPhaseCatalog.CASTER_FIT + ".mp4");
        File manifest = new File(media, "phase_" + AssemblyPhaseCatalog.CASTER_FIT + ".json");
        try (java.io.InputStream input = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("phase_media/synthetic_phase_clip.mp4");
             FileOutputStream output = new FileOutputStream(video)) {
            byte[] buffer = new byte[16 * 1024];
            for (int read = input.read(buffer); read > 0; read = input.read(buffer)) {
                output.write(buffer, 0, read);
            }
        }
        StringBuilder starts = new StringBuilder();
        for (int chapter = 0; chapter < chapters; chapter++) {
            if (chapter > 0) starts.append(',');
            starts.append(chapter * 1_000L);
        }
        try (FileOutputStream output = new FileOutputStream(manifest)) {
            output.write(("{\"chapterStartMillis\":[" + starts + "],\"durationMillis\":12000,"
                    + "\"provenance\":\"synthetic test pattern\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return new File[] {video, manifest};
    }

    private static void awaitText(ActivityScenario<AssemblyActivity> scenario, int id, String expected,
                                  long timeoutMillis) {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        String[] seen = new String[1];
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> seen[0] = text(activity, id));
            if (seen[0].contains(expected)) return;
            SystemClock.sleep(100L);
        }
        throw new AssertionError("Expected \"" + expected + "\" but saw \"" + seen[0] + "\"");
    }

    @Test
    public void trialLogRecordsTheBuildAndEveryPhaseTransition() throws IOException {
        positionAt(2);
        TrialLog[] trial = new TrialLog[1];
        try (ActivityScenario<AssemblyActivity> scenario = launch()) {
            PhaseAssemblyController controller = awaitPhase(scenario, AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
            scenario.onActivity(activity -> {
                new Frames(activity, controller).completeInventory();
                trial[0] = (TrialLog) getField(activity, "trialLog");
            });
        }
        // The log opens and writes on its own thread; closing the screen flushes it.
        SystemClock.sleep(500L);
        File log = trial[0].getFile();
        assertNotNull("The trial log file was created off the main thread", log);
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(log))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) lines.add(line);
        }
        String all = String.join("\n", lines);
        assertTrue(all, all.contains("# App: 1.1."));
        assertTrue(all.contains("phase catalog r" + AssemblyPhaseCatalog.REVISION));
        assertTrue(all.contains(",phase,"));
        assertTrue(all.contains("motor_assemblies/SCAN_PARTS"));
        assertTrue(all.contains(",frame,"));
        assertTrue(all.contains(",inventory,"));
        assertTrue(all.contains("motor_assemblies/DEMO"));
    }

    // ---- Fixtures ------------------------------------------------------------------------------

    private static void positionAt(int section) {
        Context context = ApplicationProvider.getApplicationContext();
        AssemblArRepository repository = AssemblArRepository.getInstance(context);
        repository.resetProjectProgress(NeobotSeedData.PROJECT_ID);
        GuidedStepProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        PhaseProgressStore.clearProject(context, NeobotSeedData.PROJECT_ID);
        for (AssemblyStep step : NeobotSeedData.getAssemblySteps()) {
            if (step.getStepNumber() < section) repository.confirmAndCompleteStep(step.getId());
        }
    }

    private static StepStatus statusOf(int section) {
        ProjectProgress progress = AssemblArRepository.getInstance(ApplicationProvider.getApplicationContext())
                .getProjectProgress(NeobotSeedData.PROJECT_ID);
        return progress.getSteps().get(section - 1).getStatus();
    }

    private static ActivityScenario<AssemblyActivity> launch() {
        Context context = ApplicationProvider.getApplicationContext();
        // The unknown simulated state keeps the emulator's virtual camera from delivering frames.
        return ActivityScenario.launch(new Intent(context, AssemblyActivity.class)
                .putExtra(AssemblyActivity.EXTRA_WORKFLOW_MODE, AssemblyActivity.WORKFLOW_PHASES)
                .putExtra(AssemblyActivity.EXTRA_SIMULATED_STATE, "none")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static PhaseAssemblyController awaitPhase(ActivityScenario<AssemblyActivity> scenario,
                                                      String phaseId) {
        PhaseAssemblyController[] found = new PhaseAssemblyController[1];
        for (int attempt = 0; attempt < 80; attempt++) {
            scenario.onActivity(activity -> {
                PhaseAssemblyController controller =
                        (PhaseAssemblyController) getField(activity, "phaseController");
                found[0] = controller != null && controller.getPhase() != null
                        && controller.getPhase().getId().equals(phaseId)
                        && activity.findViewById(R.id.previewView).getWidth() > 0 ? controller : null;
            });
            if (found[0] != null) return found[0];
            SystemClock.sleep(100L);
        }
        throw new AssertionError("Phase did not load: " + phaseId);
    }

    private static void completeChecklist(ActivityScenario<AssemblyActivity> scenario,
                                          PhaseAssemblyController controller) {
        scenario.onActivity(activity -> {
            AlertDialog dialog = (AlertDialog) getField(controller, "checklistDialog");
            assertNotNull("The direct checklist opens after the camera checks", dialog);
            assertTrue(dialog.isShowing());
            List<CheckBox> boxes = new ArrayList<>();
            collectCheckBoxes(dialog.getWindow().getDecorView(), boxes);
            assertEquals(controller.getPhase().getDirectChecks().size(), boxes.size());
            View confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            assertFalse("Every direct check must be ticked", confirm.isEnabled());
            for (CheckBox box : boxes) box.setChecked(true);
            assertTrue(confirm.isEnabled());
            confirm.performClick();
        });
    }

    private static void collectCheckBoxes(View view, List<CheckBox> boxes) {
        if (view instanceof CheckBox) boxes.add((CheckBox) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectCheckBoxes(group.getChildAt(index), boxes);
            }
        }
    }

    /** Builds tagged results in view pixels, as the analyzer would for the current request. */
    private static final class Frames {
        final AssemblyActivity activity;
        final PhaseAssemblyController controller;
        final int width;
        final int height;
        final List<VisionPoint> board;
        final RecognitionResult.Pose pose = new RecognitionResult.Pose(new double[] {0, 0, 0},
                new double[] {0, 0, 0.5}, new float[] {
                1, 0, 0, 0, 0, -1, 0, 0, 0, 0, -1, 0, 0, 0, -0.5f, 1});
        long timestamp;

        Frames(AssemblyActivity activity, PhaseAssemblyController controller) {
            this.activity = activity;
            this.controller = controller;
            View preview = activity.findViewById(R.id.previewView);
            width = preview.getWidth();
            height = preview.getHeight();
            setField(activity, "analyzedWidth", width);
            setField(activity, "analyzedHeight", height);
            board = Arrays.asList(new VisionPoint(width * 0.20, height * 0.15),
                    new VisionPoint(width * 0.80, height * 0.15),
                    new VisionPoint(width * 0.80, height * 0.80),
                    new VisionPoint(width * 0.20, height * 0.80));
        }

        void completeInventory() {
            for (int index = 0; index < 4; index++) deliver(brackets(4), null);
            for (int index = 0; index < 4; index++) deliver(looseMotor(), null);
            assertEquals(Mode.DEMO, controller.getFlow().getMode());
        }

        /** @param tag null uses the controller's current tag */
        void deliver(RecognitionResult result, PhaseFlow.ScanTag tag) {
            RecognitionResult tagged = (RecognitionResult) call(result, "withRequestTag",
                    new Class<?>[] {Object.class}, tag == null ? controller.getFlow().tag() : tag);
            call(activity, "handleRecognition", new Class<?>[] {RecognitionResult.class, long.class},
                    tagged, Long.MIN_VALUE);
        }

        private long nextTimestamp() {
            timestamp = Math.max(timestamp + 100 * MILLIS, SystemClock.elapsedRealtimeNanos());
            return timestamp;
        }

        RecognitionResult brackets(int count) {
            List<RecognitionResult.InstanceObservation> instances = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                double left = width * (0.10 + index * 0.21);
                instances.add(new RecognitionResult.InstanceObservation("fixture-bracket-" + index,
                        NeobotSeedData.MOTOR_BRACKET, RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK,
                        quad(left, height * 0.40, width * 0.14, height * 0.20), 0.9));
            }
            return component(NeobotSeedData.MOTOR_BRACKET, instances.get(0).getProjectedCorners(),
                    instances, 2, null, null);
        }

        RecognitionResult looseMotor() {
            List<VisionPoint> corners = quad(width * 0.35, height * 0.35, width * 0.30, height * 0.25);
            return component(NeobotSeedData.GEAR_MOTOR, corners, Collections.emptyList(), 2, null, null);
        }

        RecognitionResult looseCaster() {
            List<VisionPoint> corners = quad(width * 0.35, height * 0.35, width * 0.30, height * 0.25);
            return component(NeobotSeedData.CASTER_WHEEL, corners, Collections.emptyList(), 9, null, null);
        }

        RecognitionResult installedCaster() {
            List<VisionPoint> target = ChassisRelativePlacementGate.forNeobotStep(9)
                    .projectTargets(board).get(0).getCorners();
            RecognitionResult.SpatialAnchor anchor = new RecognitionResult.SpatialAnchor(
                    "fixture-main-board", NeobotSeedData.MAIN_BOARD, board, pose);
            return component(NeobotSeedData.CASTER_WHEEL, target, Collections.singletonList(
                    new RecognitionResult.InstanceObservation("fixture-caster", NeobotSeedData.CASTER_WHEEL,
                            RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY, target, 0.9)),
                    9, anchor, null);
        }

        RecognitionResult installedMotors(boolean left, boolean right) {
            return motors(new MotorAssemblyObservation(MotorAssemblyObservation.Quality.GOOD,
                    slot(left), slot(right)));
        }

        RecognitionResult misalignedRight() {
            return motors(new MotorAssemblyObservation(MotorAssemblyObservation.Quality.GOOD, slot(true),
                    new MotorAssemblyObservation.Slot(MotorAssemblyObservation.SlotState.MISALIGNED, 0.6,
                            "Right motor is not between its brackets", Collections.emptyList())));
        }

        private RecognitionResult motors(MotorAssemblyObservation observation) {
            RecognitionResult.SpatialAnchor anchor = new RecognitionResult.SpatialAnchor(
                    "fixture-main-board", NeobotSeedData.MAIN_BOARD, board, pose);
            return component(NeobotSeedData.MAIN_BOARD, board, Collections.emptyList(), 2, anchor, observation);
        }

        private RecognitionResult component(String componentId, List<VisionPoint> corners,
                                            List<RecognitionResult.InstanceObservation> instances,
                                            int step, RecognitionResult.SpatialAnchor anchor,
                                            MotorAssemblyObservation motors) {
            Object builder = call(RecognitionResult.class, "builder", new Class<?>[] {long.class},
                    nextTimestamp());
            call(builder, "identity", new Class<?>[] {String.class, String.class, String.class},
                    "fixture-" + componentId, componentId, componentId);
            call(builder, "method", new Class<?>[] {RecognitionResult.DetectionMethod.class},
                    RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY);
            call(builder, "validation", new Class<?>[] {RecognitionResult.ValidationState.class},
                    RecognitionResult.ValidationState.EXPECTED_COMPONENT);
            call(builder, "orientation", new Class<?>[] {
                    RecognitionResult.OrientationClassification.class, double.class},
                    RecognitionResult.OrientationClassification.UPRIGHT, 0.0);
            call(builder, "confidence", new Class<?>[] {double.class}, 0.95);
            call(builder, "corners", new Class<?>[] {List.class}, corners);
            call(builder, "instances", new Class<?>[] {List.class}, instances);
            call(builder, "pose", new Class<?>[] {RecognitionResult.Pose.class}, pose);
            RecognitionResult base = (RecognitionResult) call(builder, "build", new Class<?>[0]);
            return (RecognitionResult) call(base, "withAssemblyInspection",
                    new Class<?>[] {int.class, RecognitionResult.SpatialAnchor.class, boolean.class,
                            MotorAssemblyObservation.class}, step, anchor, false, motors);
        }

        private static List<VisionPoint> quad(double left, double top, double width, double height) {
            return Arrays.asList(new VisionPoint(left, top), new VisionPoint(left + width, top),
                    new VisionPoint(left + width, top + height), new VisionPoint(left, top + height));
        }

        private static MotorAssemblyObservation.Slot slot(boolean present) {
            return present ? new MotorAssemblyObservation.Slot(
                    MotorAssemblyObservation.SlotState.PRESENT, 0.9, "Motor installed", Collections.emptyList())
                    : MotorAssemblyObservation.Slot.missing("Motor not yet visible");
        }
    }

    private static void capture(ActivityScenario<AssemblyActivity> scenario, String name) {
        java.util.concurrent.CountDownLatch presented = new java.util.concurrent.CountDownLatch(1);
        scenario.onActivity(activity -> {
            View decor = activity.getWindow().getDecorView();
            decor.getViewTreeObserver().registerFrameCommitCallback(() -> decor.postOnAnimation(
                    () -> decor.postOnAnimation(presented::countDown)));
            decor.invalidate();
        });
        try {
            presented.await(5L, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(800L);
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        if (screenshot == null) return;
        Context context = ApplicationProvider.getApplicationContext();
        File directory = context.getExternalFilesDir("phase-workflow-previews");
        if (directory == null || !directory.isDirectory() && !directory.mkdirs()) return;
        File output = new File(directory, name + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } catch (IOException ignored) {
            // Previews are for visual review only.
        } finally {
            screenshot.recycle();
        }
    }

    private static String text(AssemblyActivity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private static Object getField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not read fixture field " + name, failure);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not set fixture field " + name, failure);
        }
    }

    private static Object call(Object target, String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
            Method method = type.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target instanceof Class<?> ? null : target, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Fixture invocation failed: " + name, failure);
        }
    }
}
