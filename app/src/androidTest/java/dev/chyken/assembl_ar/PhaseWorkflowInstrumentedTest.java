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
import dev.chyken.assembl_ar.ui.ArHudView;
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
    public void welcomeBlocksScanningUntilLetsAssembleIsTapped(){
        positionAt(1);
        try(ActivityScenario<AssemblyActivity> scenario=launch()){
            AlertDialog[] welcome=new AlertDialog[1];
            for(int i=0;i<80&&welcome[0]==null;i++){
                scenario.onActivity(activity->{PhaseAssemblyController controller=(PhaseAssemblyController)getField(activity,"phaseController");
                    if(controller!=null)welcome[0]=(AlertDialog)getField(controller,"welcomeDialog");
                });
                if(welcome[0]==null)SystemClock.sleep(100);
            }
            assertNotNull(welcome[0]);
            scenario.onActivity(activity->{
                PhaseAssemblyController controller=(PhaseAssemblyController)getField(activity,"phaseController");
                assertNull(controller.getPhase());assertTrue(controller.isAnalysisSuspended());
                assertEquals("Let's Assemble",welcome[0].getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
                welcome[0].getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            awaitPhase(scenario,AssemblyPhaseCatalog.BOARD_PREPARATION);
            scenario.onActivity(activity->assertEquals(View.VISIBLE,activity.findViewById(R.id.targetAlignmentBox).getVisibility()));
        }
    }

    @Test
    public void phaseTwoLookBackReturnsToTheSameActiveLaterPhase(){
        positionAt(3);
        try(ActivityScenario<AssemblyActivity> scenario=launch()){
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT);
            scenario.onActivity(activity->{
                PhaseFlow active=controller.getFlow();
                gearCard(activity).previous.performClick();
                assertEquals(active,controller.getFlow());assertEquals(6,gear(controller).flow.step());
                assertEquals(View.VISIBLE,gearCard(activity).getVisibility());
                gearCard(activity).next.performClick();
                assertEquals(active,controller.getFlow());assertEquals(View.VISIBLE,gearCard(activity).getVisibility());
                assertNotNull(getField(controller,"driverGuide"));
                RecognitionRequest request=controller.requestFor(CameraCalibration.pinhole(500,500,320,240));
                assertEquals(3,request.getAssemblyStepNumber());assertEquals(0,request.getPhase2Stage());assertEquals(1,request.getPhase3Stage());
                assertEquals(StepStatus.IN_PROGRESS,statusOf(3));
            });
        }
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
    public void lookingBackPreservesCurrentPhaseTwoProgressAndReturnsToItsScanner() {
        positionAt(2);
        try(ActivityScenario<AssemblyActivity> scenario=launch()) {
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
            scenario.onActivity(activity->gearCard(activity).previous.performClick());
            awaitPhase(scenario,AssemblyPhaseCatalog.BOARD_PREPARATION);
            scenario.onActivity(activity->{
                assertEquals(Mode.COMPLETE,controller.getFlow().getMode());
                assertTrue(controller.isAnalysisSuspended());
                assertEquals(StepStatus.IN_PROGRESS,statusOf(2));
                activity.findViewById(R.id.nextGuidedStepButton).performClick();
            });
            awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
            scenario.onActivity(activity->{
                assertFalse(controller.isAnalysisSuspended());
                assertEquals(1,controller.requestFor(CameraCalibration.pinhole(500,500,320,240)).getPhase2Stage());
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
    public void motorPhaseStartsWithFourBracketScanAndCompactCard() {
        positionAt(2);
        try(ActivityScenario<AssemblyActivity> scenario=launch()) {
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
            scenario.onActivity(activity->{
                assertEquals(View.VISIBLE,gearCard(activity).getVisibility());
                assertEquals(View.GONE,activity.findViewById(R.id.instructionPanel).getVisibility());
                assertFalse(gearCard(activity).next.isEnabled());
                RecognitionRequest request=controller.requestFor(CameraCalibration.pinhole(500,500,320,240));
                assertEquals(4,request.getExpectedInstanceCount());assertEquals(1,request.getPhase2Stage());
                assertEquals(controller.getFlow().tag(),request.getRequestTag());
                assertEquals(View.VISIBLE,activity.findViewById(R.id.targetAlignmentBox).getVisibility());
            });
            capture(scenario,"phase2-brackets-compact");
        }
    }

    @Test
    public void bracketAndMotorScansWaitForExplicitNextAndCountBothMotors() {
        positionAt(2);
        try(ActivityScenario<AssemblyActivity> scenario=launch()) {
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
            scenario.onActivity(activity->{
                Frames frames=new Frames(activity,controller);
                for(int i=0;i<5;i++)frames.deliver(frames.brackets(4),new PhaseFlow.ScanTag(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES,Mode.SCAN_PARTS,-1));
                assertFalse(gearCard(activity).next.isEnabled());
                frames.deliver(frames.brackets(4),null);
                assertTrue(gearCard(activity).next.isEnabled());assertTrue(controller.isAnalysisSuspended());
                frames.deliver(frames.brackets(0),null);
                assertTrue("Detection drop cannot lock a completed scan",gearCard(activity).next.isEnabled());
                assertEquals(1,gear(controller).flow.step());
                gearCard(activity).next.performClick();
                assertEquals(2,controller.requestFor(CameraCalibration.pinhole(500,500,320,240)).getPhase2Stage());
                for(int i=0;i<5;i++)frames.deliver(frames.motorPair(1),null);
                assertFalse(gearCard(activity).next.isEnabled());
                frames.deliver(frames.motorPair(2),null);
                assertTrue(gearCard(activity).next.isEnabled());assertEquals(2,gear(controller).flow.step());
                gearCard(activity).next.performClick();
                assertEquals(3,gear(controller).flow.step());assertTrue(controller.isAnalysisSuspended());
                assertEquals("I Have These",gearCard(activity).action.getText().toString());
                gearCard(activity).action.performClick();
                AlertDialog tools=(AlertDialog)getField(gear(controller),"dialog");
                tools.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
                assertFalse(gearCard(activity).next.isEnabled());
                gearCard(activity).action.performClick();
                ((AlertDialog)getField(gear(controller),"dialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertTrue(gearCard(activity).next.isEnabled());assertEquals(3,gear(controller).flow.step());
            });
        }
    }

    @Test
    public void allThreeHardwareViewsAreRequiredBeforeJointStability() {
        positionAt(2);
        try(ActivityScenario<AssemblyActivity> scenario=launch()) {
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
            scenario.onActivity(activity->{
                Phase2GuideController guide=gear(controller);
                for(int i=1;i<=4;i++){guide.flow.devJump(i,SystemClock.elapsedRealtimeNanos());guide.flow.devCompleteStep();}
                guide.flow.devJump(5,SystemClock.elapsedRealtimeNanos());guide.render();
                Frames frames=new Frames(activity,controller);
                frames.deliver(frames.hardware("left",50,0,0),null);
                assertFalse("The wrong view cannot unlock Top",gearCard(activity).next.isEnabled());
                for(String view:new String[]{"top","left"}){
                    frames.deliver(frames.hardware(view,50,100,0),null);
                    assertTrue("A positive match must unlock this view without colour counts or invalid vetoes",gearCard(activity).next.isEnabled());
                    assertEquals(view,guide.flow.expectedHardwareView());
                    assertTrue(controller.isAnalysisSuspended());
                    assertEquals(ArHudView.HudState.MATCHED,((ArHudView)activity.findViewById(R.id.arHudView)).getHudState());
                    gearCard(activity).next.performClick();
                    assertFalse(gearCard(activity).next.isEnabled());
                    assertFalse(controller.isAnalysisSuspended());
                }
                frames.deliver(frames.hardware("right",50,100,0),null);
                assertTrue(gearCard(activity).next.isEnabled());gearCard(activity).next.performClick();
                assertEquals(6,guide.flow.step());assertFalse(gearCard(activity).next.isEnabled());
                assertTrue(controller.isAnalysisSuspended());assertFalse(guide.flow.allComplete());
                gearCard(activity).action.performClick();
                AlertDialog dialog=(AlertDialog)getField(guide,"dialog");assertTrue(dialog.isShowing());
                assertEquals(StepStatus.IN_PROGRESS,statusOf(2));
                List<android.widget.RadioGroup> groups=new ArrayList<>();collectRadioGroups(dialog.getWindow().getDecorView(),groups);
                ((android.widget.RadioButton)groups.get(0).getChildAt(0)).setChecked(true);
                ((android.widget.RadioButton)groups.get(1).getChildAt(1)).setChecked(true);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertFalse(gearCard(activity).next.isEnabled());
                gearCard(activity).action.performClick();dialog=(AlertDialog)getField(guide,"dialog");
                groups.clear();collectRadioGroups(dialog.getWindow().getDecorView(),groups);
                for(android.widget.RadioGroup group:groups)((android.widget.RadioButton)group.getChildAt(0)).setChecked(true);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertTrue(gearCard(activity).next.isEnabled());assertEquals(StepStatus.IN_PROGRESS,statusOf(2));
                gearCard(activity).next.performClick();
            });
            awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT);
            assertEquals(StepStatus.COMPLETED,statusOf(2));
            assertEquals(StepStatus.IN_PROGRESS,statusOf(3));
        }
    }

    @Test
    public void legacyNegativeEvidenceDoesNotRejectAndRescanStillNeedsConfirmation() {
        positionAt(2);
        try(ActivityScenario<AssemblyActivity> scenario=launch()) {
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
            scenario.onActivity(activity->{
                Phase2GuideController guide=gear(controller);guide.flow.devJump(5,SystemClock.elapsedRealtimeNanos());guide.render();
                Frames frames=new Frames(activity,controller);
                frames.deliver(frames.hardware("top",50,100,0),null);
                assertFalse(guide.flow.hardwareViews().isEmpty());
                assertTrue(gearCard(activity).next.isEnabled());assertNull(getField(guide,"dialog"));
                PhaseFlow.ScanTag old=guide.flow.tag();gearCard(activity).rescan.performClick();
                assertEquals("The confirmation guard retains evidence until confirmed",old,guide.flow.tag());
                AlertDialog confirmation=(AlertDialog)getField(guide,"dialog");
                assertTrue(((TextView)confirmation.findViewById(android.R.id.message)).getText().toString().contains("verification progress will be reset"));
                confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertFalse(old.equals(guide.flow.tag()));assertEquals(5,guide.flow.step());
            });
        }
    }

    @Test
    public void pausingHardwareVerificationKeepsConfirmedViewsAndDiscardsOldFrames() {
        positionAt(2);PhaseFlow.ScanTag[] old=new PhaseFlow.ScanTag[1];
        try(ActivityScenario<AssemblyActivity> scenario=launch()) {
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
            scenario.onActivity(activity->{
                Phase2GuideController guide=gear(controller);guide.flow.devJump(5,SystemClock.elapsedRealtimeNanos());guide.render();
                Frames frames=new Frames(activity,controller);frames.deliver(frames.hardware("top",50,0,0),null);
                assertFalse(guide.flow.hardwareViews().isEmpty());old[0]=guide.flow.tag();
                gearCard(activity).next.performClick();
            });
            scenario.moveToState(Lifecycle.State.STARTED);scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(activity->{
                Phase2GuideController guide=gear(controller);assertEquals("left",guide.flow.expectedHardwareView());
                Frames frames=new Frames(activity,controller);for(int i=0;i<4;i++)frames.deliver(frames.hardware("top",50,0,2),old[0]);
                assertEquals(1,guide.flow.hardwareViews().size());assertEquals(Mode.VERIFY,controller.getFlow().getMode());
                assertFalse(gearCard(activity).next.isEnabled());
            });
            assertEquals(StepStatus.IN_PROGRESS,statusOf(2));
        }
    }

    @Test
    public void leavingTheAssemblyAndReopeningRestoresTheNextUnscannedHardwareSide(){
        positionAt(2);
        try(ActivityScenario<AssemblyActivity> scenario=launch()){
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
            scenario.onActivity(activity->{
                Phase2GuideController guide=gear(controller);guide.flow.devJump(5,SystemClock.elapsedRealtimeNanos());guide.render();
                Frames frames=new Frames(activity,controller);
                frames.deliver(frames.hardware("top",50,0,0),null);gearCard(activity).next.performClick();
                frames.deliver(frames.hardware("left",50,0,0),null);
                assertEquals("left",guide.flow.expectedHardwareView());assertTrue(gearCard(activity).next.isEnabled());
            });
        }
        try(ActivityScenario<AssemblyActivity> reopened=launch()){
            PhaseAssemblyController restored=awaitPhase(reopened,AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
            reopened.onActivity(activity->{
                assertEquals(5,gear(restored).flow.step());
                assertEquals("left",gear(restored).flow.expectedHardwareView());
                assertEquals(2,gear(restored).flow.hardwareViews().size());
                assertTrue(gearCard(activity).next.isEnabled());
                gearCard(activity).next.performClick();
                assertEquals("right",gear(restored).flow.expectedHardwareView());assertFalse(gearCard(activity).next.isEnabled());
            });
        }
    }

    @Test
    public void developerHardwareSessionResumesWithoutResettingToLearnerPhaseOne(){
        positionAt(1);
        try(ActivityScenario<AssemblyActivity> scenario=launch()){
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.BOARD_PREPARATION);
            scenario.onActivity(activity->{
                controller.devJumpToChapter(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES),4);
                Frames frames=new Frames(activity,controller);frames.deliver(frames.hardware("top",50,0,0),null);
                assertTrue(gearCard(activity).next.isEnabled());
                assertEquals("top",gear(controller).flow.expectedHardwareView());
            });
        }
        try(ActivityScenario<AssemblyActivity> resumed=launch()){
            PhaseAssemblyController controller=awaitPhase(resumed,AssemblyPhaseCatalog.MOTOR_ASSEMBLIES);
            resumed.onActivity(activity->{
                assertEquals(5,gear(controller).flow.step());assertEquals("top",gear(controller).flow.expectedHardwareView());
                assertTrue(gearCard(activity).next.isEnabled());gearCard(activity).next.performClick();
                assertEquals("left",gear(controller).flow.expectedHardwareView());assertFalse(gearCard(activity).next.isEnabled());
                assertEquals(StepStatus.IN_PROGRESS,statusOf(1));assertEquals(StepStatus.NOT_STARTED,statusOf(2));
            });
            resumed.onActivity(activity->controller.returnToSavedAssembly());
            awaitPhase(resumed,AssemblyPhaseCatalog.BOARD_PREPARATION);
            resumed.onActivity(activity->assertFalse(controller.isDeveloperSession()));
        }
    }

    @Test
    public void developerDemoResumesAtTheSelectedChapter(){
        positionAt(1);
        try(ActivityScenario<AssemblyActivity> scenario=launch()){
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.BOARD_PREPARATION);
            scenario.onActivity(activity->{
                controller.devJumpToChapter(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT),2);
                assertEquals(3,((Phase3GuideController)getField(controller,"driverGuide")).flow.step());
            });
        }
        try(ActivityScenario<AssemblyActivity> resumed=launch()){
            PhaseAssemblyController controller=awaitPhase(resumed,AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT);
            resumed.onActivity(activity->{
                assertEquals(3,((Phase3GuideController)getField(controller,"driverGuide")).flow.step());
                assertTrue(controller.isDeveloperSession());
            });
        }
    }

    @Test
    public void driverFlowRequiresFrontPlacementFinalScanAndExplicitHardwareCheck(){
        positionAt(3);
        try(ActivityScenario<AssemblyActivity> scenario=launch()){
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT);
            scenario.onActivity(activity->{
                activity.voiceGuide().setMuted(true);
                Phase3GuideController guide=(Phase3GuideController)getField(controller,"driverGuide");
                Frames frames=new Frames(activity,controller);
                frames.deliver(frames.driver(dev.chyken.assembl_ar.vision.Phase3Observation.State.FLIPPED),null);
                assertFalse(gearCard(activity).next.isEnabled());
                frames.deliver(frames.driver(dev.chyken.assembl_ar.vision.Phase3Observation.State.FRONT),null);
                assertTrue(gearCard(activity).next.isEnabled());assertEquals(1,guide.flow.step());
                gearCard(activity).next.performClick();assertEquals(2,guide.flow.step());
                assertFalse(gearCard(activity).next.isEnabled());gearCard(activity).action.performClick();
                ((AlertDialog)getField(guide,"dialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                gearCard(activity).next.performClick();assertEquals(3,guide.flow.step());
                assertFalse(gearCard(activity).action.isEnabled());
                dev.chyken.assembl_ar.ui.Phase2VideoPlayer video=(dev.chyken.assembl_ar.ui.Phase2VideoPlayer)getField(guide,"video");
                dev.chyken.assembl_ar.ui.Phase2VideoPlayer.Listener playback=(dev.chyken.assembl_ar.ui.Phase2VideoPlayer.Listener)getField(video,"listener");
                playback.onClip(0);playback.onClip(1);playback.onFinished();
                assertFalse("Finishing playback still waits for user confirmation",gearCard(activity).next.isEnabled());
                gearCard(activity).action.performClick();gearCard(activity).next.performClick();
                assertEquals(4,guide.flow.step());
                frames.deliver(frames.driver(dev.chyken.assembl_ar.vision.Phase3Observation.State.BACKWARD),null);
                assertFalse(gearCard(activity).next.isEnabled());
                frames.deliver(frames.driver(dev.chyken.assembl_ar.vision.Phase3Observation.State.PLACED),null);
                assertTrue(gearCard(activity).next.isEnabled());gearCard(activity).next.performClick();assertEquals(5,guide.flow.step());
                assertFalse(gearCard(activity).next.isEnabled());
                frames.deliver(frames.driver(dev.chyken.assembl_ar.vision.Phase3Observation.State.PLACED),null);
                gearCard(activity).next.performClick();assertEquals(6,guide.flow.step());
                gearCard(activity).action.performClick();AlertDialog check=(AlertDialog)getField(guide,"dialog");
                check.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertTrue(check.isShowing());assertFalse(guide.flow.complete());
                check.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertFalse(guide.flow.complete());
                List<CheckBox> hardware=new ArrayList<>();collectCheckBoxes(check.getWindow().getDecorView(),hardware);
                assertEquals(1,hardware.size());hardware.get(0).setChecked(true);
                check.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_LEADS);assertEquals(StepStatus.COMPLETED,statusOf(3));
        }
    }

    @Test
    public void driverConfirmedPlacementResumesUnlockedAndExpandedCardAvoidsBothViewers(){
        positionAt(3);
        try(ActivityScenario<AssemblyActivity> scenario=launch()){
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT);
            scenario.onActivity(activity->{
                Phase3GuideController guide=(Phase3GuideController)getField(controller,"driverGuide");
                for(int i=1;i<=3;i++){guide.flow.devSkip();guide.flow.next(SystemClock.elapsedRealtimeNanos());}
                guide.render();Frames frames=new Frames(activity,controller);
                frames.deliver(frames.driver(dev.chyken.assembl_ar.vision.Phase3Observation.State.PLACED),null);
                assertTrue(gearCard(activity).next.isEnabled());
            });
        }
        try(ActivityScenario<AssemblyActivity> resumed=launch()){
            PhaseAssemblyController controller=awaitPhase(resumed,AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT);
            resumed.onActivity(activity->{
                Phase3GuideController guide=(Phase3GuideController)getField(controller,"driverGuide");
                assertEquals(4,guide.flow.step());assertTrue(gearCard(activity).next.isEnabled());
                gearCard(activity).next.performClick();assertEquals(5,guide.flow.step());assertFalse(gearCard(activity).next.isEnabled());
                ((View)getField(gearCard(activity),"learn")).performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            resumed.onActivity(activity->{
                View card=gearCard(activity),model=activity.findViewById(R.id.componentPreviewWindow),frame=activity.findViewById(R.id.targetAlignmentBox);
                android.graphics.RectF bounds=new android.graphics.RectF(card.getX(),card.getY(),card.getX()+card.getWidth(),card.getY()+card.getHeight());
                for(View viewer:new View[]{model,frame})assertFalse(android.graphics.RectF.intersects(bounds,new android.graphics.RectF(viewer.getX(),viewer.getY(),viewer.getX()+viewer.getWidth(),viewer.getY()+viewer.getHeight())));
            });
            capture(resumed,"phase3-installed-scan-expanded");
        }
    }

    @Test
    public void developerFinishingTheSelectedJointCheckNavigatesToPhaseThreeWithoutSavingLearnerCompletion(){
        positionAt(1);
        try(ActivityScenario<AssemblyActivity> scenario=launch()){
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.BOARD_PREPARATION);
            scenario.onActivity(activity->{
                controller.devJumpToChapter(AssemblyPhaseCatalog.byId(AssemblyPhaseCatalog.MOTOR_ASSEMBLIES),5);
                gearCard(activity).action.performClick();
                AlertDialog dialog=(AlertDialog)getField(gear(controller),"dialog");
                List<android.widget.RadioGroup> groups=new ArrayList<>();collectRadioGroups(dialog.getWindow().getDecorView(),groups);
                for(android.widget.RadioGroup group:groups)((android.widget.RadioButton)group.getChildAt(0)).setChecked(true);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                assertTrue(gearCard(activity).next.isEnabled());gearCard(activity).next.performClick();
            });
            awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT);
            assertEquals(StepStatus.IN_PROGRESS,statusOf(1));assertEquals(StepStatus.NOT_STARTED,statusOf(2));
        }
        try(ActivityScenario<AssemblyActivity> resumed=launch()){
            awaitPhase(resumed,AssemblyPhaseCatalog.MOTOR_DRIVER_MOUNT);
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
                activity.voiceGuide().setMuted(true);
                Phase4GuideController guide=(Phase4GuideController)getField(controller,"wiringGuide");
                assertEquals(Mode.DEMO, controller.getFlow().getMode());
                assertTrue(controller.isAnalysisSuspended());
                assertNull(controller.requestFor(CameraCalibration.pinhole(500,500,320,240)));
                assertFalse(((ArHudView)activity.findViewById(R.id.arHudView)).isScanningEnabled());
                call(guide,"openPicker",new Class<?>[0]);
                dev.chyken.assembl_ar.ui.WireColorPickerDialog picker=(dev.chyken.assembl_ar.ui.WireColorPickerDialog)getField(guide,"picker");
                picker.findViewById(R.id.applyWireColors).performClick();assertEquals(2,guide.flow.step());
                assertFalse(gearCard(activity).next.isEnabled());gearCard(activity).action.performClick();
                assertTrue(gearCard(activity).next.isEnabled());gearCard(activity).next.performClick();
                assertEquals(Mode.PHYSICAL_CHECK, controller.getFlow().getMode());
                gearCard(activity).action.performClick();AlertDialog check=(AlertDialog)getField(guide,"pullDialog");
                check.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertTrue(check.isShowing());assertFalse(guide.flow.complete());
                check.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            awaitPhase(scenario, AssemblyPhaseCatalog.MICROCONTROLLER_FIT);
            assertEquals(StepStatus.COMPLETED, statusOf(4));
            assertEquals(StepStatus.COMPLETED, statusOf(5));
            assertEquals(StepStatus.IN_PROGRESS, statusOf(6));
            scenario.onActivity(activity ->
                    assertEquals("Phase 5 of 18", text(activity, R.id.stepCounter)));
        }
    }

    @Test
    public void motorWirePickerIsTransactionalAndAppliedColorsResumeInTheSchematic(){
        positionAt(4);
        try(ActivityScenario<AssemblyActivity> scenario=launch()){
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_LEADS);
            scenario.onActivity(activity->{
                Phase4GuideController guide=(Phase4GuideController)getField(controller,"wiringGuide");call(guide,"openPicker",new Class<?>[0]);
                dev.chyken.assembl_ar.ui.WireColorPickerDialog picker=(dev.chyken.assembl_ar.ui.WireColorPickerDialog)getField(guide,"picker");
                android.widget.GridLayout palette=picker.findViewById(R.id.motorWireSwatches),slots=picker.findViewById(R.id.motorWireSlots);
                assertEquals(10,palette.getChildCount());assertEquals(4,slots.getChildCount());palette.getChildAt(6).performClick();
                picker.findViewById(R.id.cancelWireColors).performClick();assertEquals(1,guide.flow.step());
                assertEquals(dev.chyken.assembl_ar.model.MotorWireColorConfig.defaults(),guide.flow.colors());
                call(guide,"openPicker",new Class<?>[0]);picker=(dev.chyken.assembl_ar.ui.WireColorPickerDialog)getField(guide,"picker");
                palette=picker.findViewById(R.id.motorWireSwatches);slots=picker.findViewById(R.id.motorWireSlots);
                palette.getChildAt(6).performClick();slots.getChildAt(2).performClick();palette.getChildAt(9).performClick();
                picker.findViewById(R.id.applyWireColors).performClick();assertEquals(2,guide.flow.step());
                assertEquals(0xFF0000FF,guide.flow.colors().leftMotorPositiveColor);assertEquals(0xFFFFFFFF,guide.flow.colors().rightMotorPositiveColor);
                dev.chyken.assembl_ar.ui.AssemblyDemoView diagram=(dev.chyken.assembl_ar.ui.AssemblyDemoView)getField(guide,"schematic");
                assertEquals("#0000FF",diagram.getMotorWireConnections().get(0).getWireColor());assertEquals("Right pair · right opening",diagram.getMotorWireConnections().get(2).getTargetPin());
                gearCard(activity).action.performClick();assertTrue(gearCard(activity).next.isEnabled());
            });
        }
        try(ActivityScenario<AssemblyActivity> resumed=launch()){
            PhaseAssemblyController controller=awaitPhase(resumed,AssemblyPhaseCatalog.MOTOR_LEADS);
            resumed.onActivity(activity->{
                Phase4GuideController guide=(Phase4GuideController)getField(controller,"wiringGuide");assertEquals(2,guide.flow.step());
                assertEquals(0xFF0000FF,guide.flow.colors().leftMotorPositiveColor);assertEquals(0xFFFFFFFF,guide.flow.colors().rightMotorPositiveColor);assertTrue(gearCard(activity).next.isEnabled());
                ((View)getField(gearCard(activity),"learn")).performClick();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            resumed.onActivity(activity->{
                View card=gearCard(activity),preview=activity.findViewById(R.id.componentPreviewWindow);
                assertFalse(android.graphics.RectF.intersects(new android.graphics.RectF(card.getX(),card.getY(),card.getX()+card.getWidth(),card.getY()+card.getHeight()),new android.graphics.RectF(preview.getX(),preview.getY(),preview.getX()+preview.getWidth(),preview.getY()+preview.getHeight())));
                call(getField(controller,"wiringGuide"),"openPicker",new Class<?>[0]);
            });
            capture(resumed,"phase4-wire-color-picker");
        }
    }

    @Test
    public void phaseFourLookBackRestoresItsOwnControlsAndSelectedColors(){
        positionAt(4);
        try(ActivityScenario<AssemblyActivity> scenario=launch()){
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_LEADS);
            scenario.onActivity(activity->{
                Phase4GuideController guide=(Phase4GuideController)getField(controller,"wiringGuide");call(guide,"openPicker",new Class<?>[0]);
                ((dev.chyken.assembl_ar.ui.WireColorPickerDialog)getField(guide,"picker")).findViewById(R.id.applyWireColors).performClick();
                gearCard(activity).previous.performClick();assertEquals(1,guide.flow.step());gearCard(activity).previous.performClick();
                assertNotNull(getField(controller,"driverGuide"));gearCard(activity).next.performClick();
                assertNull(getField(controller,"driverGuide"));assertEquals(guide,getField(controller,"wiringGuide"));
                assertEquals(1,guide.flow.step());gearCard(activity).next.performClick();assertEquals(2,guide.flow.step());
                gearCard(activity).action.performClick();assertTrue(gearCard(activity).next.isEnabled());assertTrue(controller.isAnalysisSuspended());
            });
        }
    }

    @Test
    public void motorWireDraftSurvivesRecreationWithoutUnlockingStepOne(){
        positionAt(4);
        try(ActivityScenario<AssemblyActivity> scenario=launch()){
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_LEADS);
            scenario.onActivity(activity->{
                Phase4GuideController guide=(Phase4GuideController)getField(controller,"wiringGuide");call(guide,"openPicker",new Class<?>[0]);
                dev.chyken.assembl_ar.ui.WireColorPickerDialog picker=(dev.chyken.assembl_ar.ui.WireColorPickerDialog)getField(guide,"picker");
                ((android.widget.GridLayout)picker.findViewById(R.id.motorWireSlots)).getChildAt(3).performClick();
                ((android.widget.GridLayout)picker.findViewById(R.id.motorWireSwatches)).getChildAt(7).performClick();
                assertEquals(0xFF000000,guide.flow.colors().rightMotorNegativeColor);assertFalse(guide.flow.complete());
            });
            scenario.recreate();PhaseAssemblyController restored=awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_LEADS);
            scenario.onActivity(activity->{
                Phase4GuideController guide=(Phase4GuideController)getField(restored,"wiringGuide");call(guide,"openPicker",new Class<?>[0]);
                dev.chyken.assembl_ar.ui.WireColorPickerDialog picker=(dev.chyken.assembl_ar.ui.WireColorPickerDialog)getField(guide,"picker");
                assertEquals(dev.chyken.assembl_ar.model.MotorWireColorConfig.Terminal.RIGHT_NEGATIVE,picker.selectedTerminal());
                assertEquals(0xFF800080,picker.draftColors().rightMotorNegativeColor);assertFalse(gearCard(activity).next.isEnabled());
                picker.findViewById(R.id.applyWireColors).performClick();assertEquals(2,guide.flow.step());assertEquals(0xFF800080,guide.flow.colors().rightMotorNegativeColor);
            });
        }
    }

    @Test public void nanoFlowRequiresFrontDemoInstalledScanAndBothPhysicalAnswers(){
        positionAt(6);
        try(ActivityScenario<AssemblyActivity> scenario=launch()){
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MICROCONTROLLER_FIT);
            scenario.onActivity(activity->{
                activity.voiceGuide().setMuted(true);
                Phase5GuideController guide=(Phase5GuideController)getField(controller,"microcontrollerGuide");Frames frames=new Frames(activity,controller);
                assertEquals(1,controller.requestFor(CameraCalibration.pinhole(500,500,320,240)).getPhase5Stage());
                assertNull(controller.requestFor(CameraCalibration.pinhole(500,500,320,240)).getSpatialAnchorComponentId());
                gearCard(activity).action.performClick();AlertDialog info=(AlertDialog)getField(guide,"dialog");assertTrue(info.isShowing());assertTrue(controller.isAnalysisSuspended());info.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertFalse(controller.isAnalysisSuspended());
                frames.deliver(frames.nano(dev.chyken.assembl_ar.vision.Phase5Observation.State.FLIPPED),null);assertFalse(gearCard(activity).next.isEnabled());
                frames.deliver(frames.nano(dev.chyken.assembl_ar.vision.Phase5Observation.State.FRONT),null);assertTrue(gearCard(activity).next.isEnabled());assertEquals(1,guide.flow.step());
                gearCard(activity).next.performClick();assertEquals(2,guide.flow.step());assertTrue(controller.isAnalysisSuspended());assertFalse(gearCard(activity).action.isEnabled());
                dev.chyken.assembl_ar.ui.Phase2VideoPlayer video=(dev.chyken.assembl_ar.ui.Phase2VideoPlayer)getField(guide,"video");
                ((dev.chyken.assembl_ar.ui.Phase2VideoPlayer.Listener)getField(video,"listener")).onFinished();
                assertFalse(gearCard(activity).next.isEnabled());gearCard(activity).action.performClick();assertTrue(gearCard(activity).next.isEnabled());gearCard(activity).next.performClick();
                assertEquals(3,guide.flow.step());assertFalse(controller.isAnalysisSuspended());assertEquals(3,controller.requestFor(CameraCalibration.pinhole(500,500,320,240)).getPhase5Stage());
                frames.deliver(frames.nano(dev.chyken.assembl_ar.vision.Phase5Observation.State.BACKWARD),null);assertFalse(gearCard(activity).next.isEnabled());
                frames.deliver(frames.nano(dev.chyken.assembl_ar.vision.Phase5Observation.State.PLACED),null);assertTrue(gearCard(activity).next.isEnabled());assertEquals(3,guide.flow.step());
                gearCard(activity).next.performClick();gearCard(activity).action.performClick();AlertDialog check=(AlertDialog)getField(guide,"dialog");
                check.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertFalse(guide.flow.complete());assertTrue(check.isShowing());
                check.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(guide.flow.firmConfirmed());assertFalse(guide.flow.complete());assertEquals("All Pins In",check.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
                check.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertFalse(guide.flow.firmConfirmed());assertFalse(guide.flow.pinsConfirmed());
                check.getButton(AlertDialog.BUTTON_POSITIVE).performClick();check.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            awaitPhase(scenario,AssemblyPhaseCatalog.DRIVER_WIRING);assertEquals(StepStatus.COMPLETED,statusOf(6));assertEquals(StepStatus.IN_PROGRESS,statusOf(7));
        }
    }

    @Test public void nanoPlacementUnlockAndPartialChecklistSurviveRecreationAndLookBack(){
        positionAt(6);
        try(ActivityScenario<AssemblyActivity> scenario=launch()){
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MICROCONTROLLER_FIT);
            scenario.onActivity(activity->{
                Phase5GuideController guide=(Phase5GuideController)getField(controller,"microcontrollerGuide");guide.flow.observe(true);guide.flow.next(SystemClock.elapsedRealtimeNanos());guide.flow.confirmDemo(true);guide.flow.next(SystemClock.elapsedRealtimeNanos());guide.render();
                Frames frames=new Frames(activity,controller);frames.deliver(frames.nano(dev.chyken.assembl_ar.vision.Phase5Observation.State.PLACED),null);
                assertTrue(gearCard(activity).next.isEnabled());
            });
        }
        try(ActivityScenario<AssemblyActivity> scenario=launch()){
            PhaseAssemblyController controller=awaitPhase(scenario,AssemblyPhaseCatalog.MICROCONTROLLER_FIT);
            scenario.onActivity(activity->{
                Phase5GuideController guide=(Phase5GuideController)getField(controller,"microcontrollerGuide");assertEquals(3,guide.flow.step());assertTrue(gearCard(activity).next.isEnabled());
                gearCard(activity).rescan.performClick();((AlertDialog)getField(guide,"dialog")).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertTrue(gearCard(activity).next.isEnabled());
                gearCard(activity).next.performClick();gearCard(activity).action.performClick();((AlertDialog)getField(guide,"dialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(guide.flow.firmConfirmed());
            });
            scenario.recreate();
            PhaseAssemblyController restored=awaitPhase(scenario,AssemblyPhaseCatalog.MICROCONTROLLER_FIT);
            scenario.onActivity(activity->{
                Phase5GuideController guide=(Phase5GuideController)getField(restored,"microcontrollerGuide");assertEquals(4,guide.flow.step());assertTrue(guide.flow.firmConfirmed());assertFalse(guide.flow.pinsConfirmed());
                gearCard(activity).previous.performClick();assertEquals(3,guide.flow.step());assertTrue(gearCard(activity).next.isEnabled());
                gearCard(activity).previous.performClick();gearCard(activity).previous.performClick();gearCard(activity).previous.performClick();assertNotNull(getField(restored,"wiringGuide"));
                gearCard(activity).next.performClick();assertNull(getField(restored,"wiringGuide"));assertEquals(guide,getField(restored,"microcontrollerGuide"));assertEquals(1,guide.flow.step());assertTrue(gearCard(activity).next.isEnabled());
            });
        }
    }

    @Test
    public void customMotorWireColorsRenderAtCompactAndExpandedPreviewSizes(){
        positionAt(4);
        try(ActivityScenario<AssemblyActivity> scenario=launch()){
            awaitPhase(scenario,AssemblyPhaseCatalog.MOTOR_LEADS);
            dev.chyken.assembl_ar.ui.AssemblyDemoView[] holder=new dev.chyken.assembl_ar.ui.AssemblyDemoView[1];
            int[] colors={0xFF000000,0xFFFFFFFF,0xFFFFFF00,0xFF800080};
            scenario.onActivity(activity->{
                holder[0]=new dev.chyken.assembl_ar.ui.AssemblyDemoView(activity);
                holder[0].setMotorWireColors(new dev.chyken.assembl_ar.model.MotorWireColorConfig(colors[0],colors[1],colors[2],colors[3]));holder[0].pause();
            });
            // Decode happens off the UI thread; never block the main looper waiting for it.
            long deadline=SystemClock.uptimeMillis()+5000;
            boolean[] ready={false};
            while(!ready[0]&&SystemClock.uptimeMillis()<deadline){
                scenario.onActivity(activity->ready[0]=holder[0].isMotorWiringArtworkReady());
                if(!ready[0])SystemClock.sleep(30);
            }
            assertTrue("Packaged component artwork did not load",ready[0]);
            scenario.onActivity(activity->{
                dev.chyken.assembl_ar.ui.AssemblyDemoView diagram=holder[0];
                float density=activity.getResources().getDisplayMetrics().density;
                for(int[] size:new int[][]{{200,94},{320,194},{900,480}}){
                    int width=Math.round(size[0]*density),height=Math.round(size[1]*density);diagram.layout(0,0,width,height);
                    Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);diagram.draw(new android.graphics.Canvas(bitmap));
                    for(int color:colors){int count=0;for(int y=0;y<height;y++)for(int x=0;x<width;x++)if(bitmap.getPixel(x,y)==color)count++;assertTrue("Wire color missing at "+size[0]+" x "+size[1],count>5);}
                    if(size[1]>=140){
                        int yellow=0,blue=0;
                        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
                            int pixel=bitmap.getPixel(x,y),r=android.graphics.Color.red(pixel),g=android.graphics.Color.green(pixel),b=android.graphics.Color.blue(pixel);
                            if(r>225&&g>140&&g<240&&b<80)yellow++;
                            if(b>60&&b>r*1.2&&g>r)blue++;
                        }
                        assertTrue("Yellow gearbox image missing",yellow>30);assertTrue("Blue driver artwork missing",blue>30);
                    }
                    bitmap.recycle();
                }
                // Compact preview exposes all four selectable tab slots without invented output labels.
                int width=Math.round(320*density),height=Math.round(194*density);diagram.layout(0,0,width,height);
                Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);diagram.draw(new android.graphics.Canvas(bitmap));
                java.util.List<dev.chyken.assembl_ar.model.MotorWireColorConfig.Terminal> selected=new java.util.ArrayList<>();diagram.setOnMotorWireSelected(selected::add);
                for(int i=0;i<4;i++){
                    float x=(6+(320-12)/4f*(i+.5f))*density,y=18*density;
                    android.view.MotionEvent down=android.view.MotionEvent.obtain(0,0,android.view.MotionEvent.ACTION_DOWN,x,y,0);
                    android.view.MotionEvent up=android.view.MotionEvent.obtain(0,1,android.view.MotionEvent.ACTION_UP,x,y,0);
                    diagram.onTouchEvent(down);diagram.onTouchEvent(up);down.recycle();up.recycle();
                }
                assertEquals(java.util.Arrays.asList(dev.chyken.assembl_ar.model.MotorWireColorConfig.Terminal.values()),selected);bitmap.recycle();
                assertTrue(diagram.getContentDescription().toString().contains("White wire to Left pair"));
                assertFalse(diagram.getContentDescription().toString().contains("OUT1"));
            });
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

    private static Phase2GuideController gear(PhaseAssemblyController controller){return (Phase2GuideController)getField(controller,"gearGuide");}
    private static dev.chyken.assembl_ar.ui.CompactGuideCard gearCard(AssemblyActivity activity){return activity.findViewById(R.id.phaseTwoGuide);}
    private static void collectRadioGroups(View view,List<android.widget.RadioGroup> groups){
        if(view instanceof android.widget.RadioGroup)groups.add((android.widget.RadioGroup)view);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collectRadioGroups(((ViewGroup)view).getChildAt(i),groups);
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
                if(controller!=null && controller.getPhase()==null) {
                    AlertDialog welcome=(AlertDialog)getField(controller,"welcomeDialog");
                    if(welcome!=null&&welcome.isShowing())welcome.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                }
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
            for(int i=0;i<3;i++)deliver(brackets(4),null);
            gearCard(activity).next.performClick();
            for(int i=0;i<3;i++)deliver(motorPair(2),null);
            gearCard(activity).next.performClick();
            assertEquals(Mode.DEMO,controller.getFlow().getMode());
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

        RecognitionResult nano(dev.chyken.assembl_ar.vision.Phase5Observation.State state){
            RecognitionResult base=component(NeobotSeedData.MICROCONTROLLER,board,Collections.emptyList(),5,null,null);
            return (RecognitionResult)call(base,"withPhase5Observation",new Class<?>[]{dev.chyken.assembl_ar.vision.Phase5Observation.class},new dev.chyken.assembl_ar.vision.Phase5Observation(state,40));
        }

        RecognitionResult driver(dev.chyken.assembl_ar.vision.Phase3Observation.State state){
            RecognitionResult base=component(NeobotSeedData.MOTOR_DRIVER,board,Collections.emptyList(),3,null,null);
            return (RecognitionResult)call(base,"withPhase3Observation",new Class<?>[]{dev.chyken.assembl_ar.vision.Phase3Observation.class},new dev.chyken.assembl_ar.vision.Phase3Observation(state,40));
        }

        RecognitionResult brackets(int count) {
            List<RecognitionResult.InstanceObservation> instances = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                double left = width * (0.10 + index * 0.21);
                instances.add(new RecognitionResult.InstanceObservation("fixture-bracket-" + index,
                        NeobotSeedData.MOTOR_BRACKET, RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK,
                        quad(left, height * 0.40, width * 0.14, height * 0.20), 0.9));
            }
            return component(NeobotSeedData.MOTOR_BRACKET, instances.isEmpty()?board:instances.get(0).getProjectedCorners(),
                    instances, 2, null, null);
        }

        RecognitionResult motorPair(int count) {
            List<RecognitionResult.InstanceObservation> objects=new ArrayList<>();
            for(int i=0;i<count;i++)objects.add(new RecognitionResult.InstanceObservation("motor-"+i,NeobotSeedData.GEAR_MOTOR,
                    RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,quad(width*(.15+i*.4),height*.35,width*.25,height*.25),.95));
            return component(NeobotSeedData.GEAR_MOTOR,objects.get(0).getProjectedCorners(),objects,2,null,null);
        }
        RecognitionResult hardware(String view,double positive,double negative,int motors) {
            RecognitionResult base=component(NeobotSeedData.MAIN_BOARD,board,Collections.emptyList(),2,null,null);
            return (RecognitionResult)call(base,"withPhase2Observation",new Class<?>[]{dev.chyken.assembl_ar.vision.Phase2Observation.class},
                    new dev.chyken.assembl_ar.vision.Phase2Observation(view,positive,negative,motors));
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
