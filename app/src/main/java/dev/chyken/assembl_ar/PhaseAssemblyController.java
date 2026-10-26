package dev.chyken.assembl_ar;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.diagnostics.BuildStamp;
import dev.chyken.assembl_ar.diagnostics.TrialLog;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.model.ProjectProgress;
import dev.chyken.assembl_ar.render.NeobotGuidanceFactory;
import dev.chyken.assembl_ar.ui.ArHudView;
import dev.chyken.assembl_ar.ui.AssemblyDemoView;
import dev.chyken.assembl_ar.ui.AssemblyVoiceGuide;
import dev.chyken.assembl_ar.ui.AssetImageLoader;
import dev.chyken.assembl_ar.ui.ManualReferenceDialog;
import dev.chyken.assembl_ar.ui.PhaseDemoPlayer;
import dev.chyken.assembl_ar.vision.CameraCalibration;
import dev.chyken.assembl_ar.vision.MotorAssemblyObservation;
import dev.chyken.assembl_ar.vision.RecognitionRequest;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.workflow.BracketSetRecognitionGate;
import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate;
import dev.chyken.assembl_ar.workflow.ConsecutiveAcceptanceGate;
import dev.chyken.assembl_ar.workflow.GuidedStepProgressStore;
import dev.chyken.assembl_ar.workflow.MotorAssemblyGate;
import dev.chyken.assembl_ar.workflow.TransientFrameStreakGate;
import dev.chyken.assembl_ar.workflow.VisualPlacementGate;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase.Part;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase.VisualTarget;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhaseCatalog;
import dev.chyken.assembl_ar.workflow.phase.PhaseDemoScript;
import dev.chyken.assembl_ar.workflow.phase.PhaseSessionCheckpoint;
import dev.chyken.assembl_ar.workflow.phase.Phase3MotorDriverFlow;
import dev.chyken.assembl_ar.workflow.phase.PhaseDemoScript.Chapter;
import dev.chyken.assembl_ar.workflow.phase.PhaseFlow;
import dev.chyken.assembl_ar.workflow.phase.PhaseFlow.Mode;
import dev.chyken.assembl_ar.workflow.phase.PhaseMediaCatalog;
import dev.chyken.assembl_ar.workflow.phase.PhaseProgress;
import dev.chyken.assembl_ar.workflow.phase.PhaseProgressStore;
import dev.chyken.assembl_ar.workflow.phase.PhaseTargetClassifier;
import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession;
import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession.Observation;
import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession.Outcome;
import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession.TargetResult;
import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession.Verdict;

import static dev.chyken.assembl_ar.data.NeobotSeedData.GEAR_MOTOR;
import static dev.chyken.assembl_ar.data.NeobotSeedData.MAIN_BOARD;
import static dev.chyken.assembl_ar.data.NeobotSeedData.MOTOR_BRACKET;

/**
 * The grouped phase guide on the assembly screen: scan the phase's parts → watch its complete
 * narrated demo → assemble (the device may be set down) → tap Scan → per-target results, a named
 * correction with its demo chapters, or the direct checklist → save → next phase.
 *
 * <p>Camera evidence is only requested while scanning parts or after Scan, and every request is
 * tagged with the phase, mode and generation from {@link PhaseFlow}, so stale frames are ignored.
 * Placement acceptance reuses the existing board, placement, motor and repeated-frame gates.
 * No step can be passed by a timer, a demo or a tap without its camera and direct checks.</p>
 */
final class PhaseAssemblyController implements PhaseDemoPlayer.Listener {
    private static final String TAG = "AssemblyPhase";
    private static final int STABLE_FRAME_GATE = 4;
    private static final long BUDGET_POLL_MILLIS = 1_000L;

    /** Immutable request description read by the camera thread. */
    private static final class RequestPlan {
        enum Kind { NONE, INVENTORY, MOTOR_PAIR, BRACKET_SLOT, SECTION_PLACEMENT }

        static final RequestPlan NONE = new RequestPlan(Kind.NONE, null, null, 1, null, 0, 0);

        final Kind kind;
        @Nullable final PhaseFlow.ScanTag tag;
        @Nullable final String componentId;
        final int count;
        @Nullable final String anchorId;
        final int section;
        final int bracketSlot;

        RequestPlan(Kind kind, @Nullable PhaseFlow.ScanTag tag, @Nullable String componentId,
                    int count, @Nullable String anchorId, int section, int bracketSlot) {
            this.kind = kind;
            this.tag = tag;
            this.componentId = componentId;
            this.count = count;
            this.anchorId = anchorId;
            this.section = section;
            this.bracketSlot = bracketSlot;
        }
    }

    private final AssemblyActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable budgetPoll = this::pollVerificationBudget;
    private final PhaseDemoPlayer player;
    private final VisualPlacementGate identityGate = new VisualPlacementGate(STABLE_FRAME_GATE);
    // Board inventory needs identity and front-face geometry, not a stationary phone pose.
    private final TransientFrameStreakGate boardAcquisitionGate =
            new TransientFrameStreakGate(2, TimeUnit.SECONDS.toNanos(5), 2);
    private final BracketSetRecognitionGate bracketSetGate = new BracketSetRecognitionGate();
    private final VisualPlacementGate anchorGate = new VisualPlacementGate(STABLE_FRAME_GATE);
    private final VisualPlacementGate placementGate = new VisualPlacementGate(STABLE_FRAME_GATE);
    private final ConsecutiveAcceptanceGate repeatedGate = new ConsecutiveAcceptanceGate(STABLE_FRAME_GATE);
    private final MotorAssemblyGate motorGate = new MotorAssemblyGate();

    private final TextView stepCounter;
    private final TextView progressLabel;
    private final LinearProgressIndicator progress;
    private final TextView leadingContext;
    private final TextView title;
    private final TextView stage;
    private final TextView instruction;
    private final TextView inventoryView;
    private final TextView resultsView;
    private final MaterialButton replayButton;
    private final MaterialButton scanButton;
    private final MaterialButton checklistButton;
    private final MaterialButton previousButton;
    private final MaterialButton nextButton;
    private final dev.chyken.assembl_ar.ui.PhaseOneGuideView phaseOneGuide;
    private boolean phaseOneScanConfirmed;
    private volatile Phase2GuideController gearGuide;
    private volatile Phase3GuideController driverGuide;
    private volatile Phase4GuideController wiringGuide;
    private volatile Phase5GuideController microcontrollerGuide;
    private volatile Phase6GuideController driverWiringGuide;
    private volatile Phase7GuideController casterTrackerGuide;
    private volatile Phase8GuideController sensorWiringGuide;
    private volatile Phase11GuideController oledBatteryGuide;
    private volatile Phase10GuideController extensionBoardGuide;
    private volatile Phase9GuideController standoffWheelsGuide;
    private AlertDialog welcomeDialog;
    private AlertDialog phaseOneRescanDialog;
    private String phaseOneGuideAsset;
    private final MaterialButton doneChapterButton;
    private final Set<String> confirmedChapters = new HashSet<>();
    private final Set<String> viewedChapters = new HashSet<>();
    @Nullable private PhaseDemoScript reviewScript;
    private int pausedChapter;
    private long phaseStartedAtNanos;
    @Nullable private String renderedReferenceKey;
    private final Map<String,PhaseDemoScript> scriptCache=new LinkedHashMap<>();

    private PhaseDemoScript scriptFor(AssemblyPhase item) {
        return scriptCache.computeIfAbsent(item.getId(),ignored->PhaseDemoScript.forPhase(item));
    }
    private final View navigationRow;
    private final ArHudView hud;

    private volatile RequestPlan plan = RequestPlan.NONE;
    private List<AssemblyStep> steps = Collections.emptyList();
    private int currentSection;
    @Nullable private AssemblyPhase phase;
    @Nullable private PhaseDemoScript script;
    @Nullable private PhaseFlow flow;
    @Nullable private PhaseVerificationSession session;
    private int inventoryIndex;
    private final Map<String, Boolean> inventoryDone = new LinkedHashMap<>();
    private int previousAnchorFrames;
    private long lastEvidenceNanos = Long.MIN_VALUE;
    private long lastResultUptime;
    private long verifyStartedUptime;
    @Nullable private String activeGroup;
    @Nullable private TargetResult lastCorrection;
    @Nullable private List<VisualTarget> correctionOverlayTargets;
    private boolean savingPhase;
    private boolean devNavigationActive;
    private PhaseSessionCheckpoint pendingSession;
    private boolean restoringSession;
    @Nullable private AlertDialog checklistDialog;
    private Chapter shownChapter;
    /** A one-time success message shown instead of the mode banner, e.g. "Parts found". */
    @Nullable private String pendingSuccessBanner;

    PhaseAssemblyController(AssemblyActivity activity) {
        this.activity = activity;
        stepCounter = activity.findViewById(R.id.stepCounter);
        progressLabel = activity.findViewById(R.id.progressLabel);
        progress = activity.findViewById(R.id.assemblyProgress);
        leadingContext = activity.findViewById(R.id.stepLeadingContext);
        title = activity.findViewById(R.id.stepTitle);
        stage = activity.findViewById(R.id.demoStage);
        instruction = activity.findViewById(R.id.stepInstruction);
        inventoryView = activity.findViewById(R.id.phaseInventory);
        resultsView = activity.findViewById(R.id.phaseResults);
        replayButton = activity.findViewById(R.id.replayDemoButton);
        scanButton = activity.findViewById(R.id.scanAssemblyButton);
        checklistButton = activity.findViewById(R.id.confirmPlacementButton);
        previousButton = activity.findViewById(R.id.previousGuidedStepButton);
        nextButton = activity.findViewById(R.id.nextGuidedStepButton);
        phaseOneGuide=activity.findViewById(R.id.phaseOneGuide);
        phaseOneGuide.setNextAction(() -> jumpChapter(1));
        phaseOneGuide.setRescanAction(()->{if(phase==null||phase.getNumber()!=1||savingPhase||!phaseOneScanConfirmed)return;phaseOneRescanDialog=RescanConfirmationDialog.show(activity,()->{
            phaseOneRescanDialog=null;
            if(phase==null||phase.getNumber()!=1||savingPhase)return;
            if(!devNavigationActive)PhaseProgressStore.setInventoryComplete(activity,NeobotSeedData.PROJECT_ID,phase.getId(),false);
            phaseOneScanConfirmed=false;phaseOneGuide.reset();flow.restart(SystemClock.elapsedRealtimeNanos());
            inventoryIndex=0;inventoryDone.clear();resetGates();activity.resetAnalyzerTracking();enterMode();
        });});
        doneChapterButton=activity.findViewById(R.id.doneChapterButton);
        android.content.SharedPreferences review=activity.getSharedPreferences("assembly_instruction_review",0);
        confirmedChapters.addAll(review.getStringSet("done",Collections.emptySet()));
        viewedChapters.addAll(review.getStringSet("seen",Collections.emptySet()));
        navigationRow = activity.findViewById(R.id.guidedStepNavigationRow);
        hud = activity.findViewById(R.id.arHudView);
        AssemblyDemoView demoView = activity.findViewById(R.id.assemblyDemo);
        VideoView video = activity.findViewById(R.id.phaseVideo);
        player = new PhaseDemoPlayer(demoView, activity.voiceGuide(), video, this);
    }

    /** Replaces the per-card control handlers with the phase actions. */
    void bind() {
        replayButton.setOnClickListener(view -> replay());
        scanButton.setOnClickListener(view -> scan());
        checklistButton.setOnClickListener(view -> showChecklist());
        previousButton.setOnClickListener(view -> jumpChapter(-1));
        nextButton.setOnClickListener(view -> jumpChapter(1));
        doneChapterButton.setOnClickListener(view -> confirmChapter());
        previousButton.setText(R.string.hud_previous_step);
        nextButton.setText(R.string.hud_next_step);
        activity.findViewById(R.id.redoGuidedStepPanel).setVisibility(View.GONE);
        activity.findViewById(R.id.motorInspectionRow).setVisibility(View.GONE);
        activity.findViewById(R.id.viewReferenceButton).setOnClickListener(view -> showManualPage());
        activity.findViewById(R.id.manualThumbnail).setOnClickListener(view -> showManualPage());
        activity.findViewById(R.id.stepInstruction).setOnClickListener(view -> showManualPage());
        TextView restart = activity.findViewById(R.id.restartPhaseButton);
        restart.setVisibility(View.VISIBLE);
        restart.setOnClickListener(view -> restartPhase());
        TextView redo = activity.findViewById(R.id.redoPhaseButton);
        redo.setVisibility(View.VISIBLE);
        redo.setOnClickListener(view -> chooseRedoPhase());
        activity.findViewById(R.id.requiredPartReference).setOnClickListener(view -> {
            Part part = referencePart();
            Component component = part == null ? null : activity.component(part.getComponentId());
            if (component != null) {
                dev.chyken.assembl_ar.ui.ComponentModelDialog.show(activity, component.getId(),
                        component.getName());
            }
        });
    }

    // ---- Loading and saving -------------------------------------------------------------

    void load(List<AssemblyStep> manualSteps, ProjectProgress progressRecord) {
        steps = manualSteps;
        devNavigationActive = false;
        currentSection = sectionNumber(progressRecord.getCurrentStepId());
        AssemblyPhase resumed = PhaseProgress.resume(currentSection,
                PhaseProgressStore.records(activity, NeobotSeedData.PROJECT_ID));
        PhaseSessionCheckpoint saved=activity.recreationCheckpoint();
        boolean recreating=saved!=null;
        if(saved==null)saved=PhaseProgressStore.session(activity,NeobotSeedData.PROJECT_ID);
        pendingSession=saved!=null&&(recreating?saved.canRestoreActivity(resumed):saved.canRestore(resumed))?saved:null;
        if (resumed == null&&pendingSession==null) {
            activity.openCompletion();
            return;
        }
        if(pendingSession!=null){
            devNavigationActive=pendingSession.developer;
            resumed=AssemblyPhaseCatalog.byId(pendingSession.phaseId);
            if(devNavigationActive)currentSection=resumed.getFirstSection();
            activity.acceptAssemblyWelcome();
        }
        final AssemblyPhase initialPhase=resumed;
        if(resumed.getNumber()==1&&!activity.assemblyWelcomeAccepted()) {
            welcomeDialog=WelcomeAssemblyDialog.show(activity,()->{
                activity.acceptAssemblyWelcome();welcomeDialog=null;startPhase(initialPhase);
            });
            return;
        }
        startPhase(resumed);
    }

    private void startPhase(AssemblyPhase next) {
        if(savingPhase||activity.isGone())return;
        if(gearGuide!=null){gearGuide.close();gearGuide=null;}
        if(driverGuide!=null){driverGuide.close();driverGuide=null;}
        if(wiringGuide!=null){wiringGuide.close();wiringGuide=null;}
        if(microcontrollerGuide!=null){microcontrollerGuide.close();microcontrollerGuide=null;}
        if(driverWiringGuide!=null){driverWiringGuide.close();driverWiringGuide=null;}
        if(casterTrackerGuide!=null){casterTrackerGuide.close();casterTrackerGuide=null;}
        if(sensorWiringGuide!=null){sensorWiringGuide.close();sensorWiringGuide=null;}
        if(standoffWheelsGuide!=null){standoffWheelsGuide.close();standoffWheelsGuide=null;}
        if(extensionBoardGuide!=null){extensionBoardGuide.close();extensionBoardGuide=null;}
        if(oledBatteryGuide!=null){oledBatteryGuide.close();oledBatteryGuide=null;}
        PhaseSessionCheckpoint restored=pendingSession;
        pendingSession=null;
        restoringSession=restored!=null&&restored.phaseId.equals(next.getId());
        phaseStartedAtNanos=SystemClock.elapsedRealtimeNanos();
        reviewScript=null;
        shownChapter=null;
        renderedReferenceKey=null;
        player.release();
        dismissChecklist();
        phase = next;
        phaseOneScanConfirmed=false;
        phaseOneGuide.reset();
        activity.setPhaseTwoGuideActive(next.getNumber()>=2&&next.getNumber()<=11);
        if(next.getNumber()<=11)activity.hideGuidance();
        activity.setPhaseOneGuideActive(next.getNumber()==1);
        dev.chyken.assembl_ar.ui.TargetAlignmentBoxView alignment=activity.findViewById(R.id.targetAlignmentBox);
        alignment.showGuide(null);
        if(next.getNumber()==1)phaseOneGuideAsset=dev.chyken.assembl_ar.camera.Phase2AssetCatalog.guide(activity.getAssets(),1,1);
        script = scriptFor(next);
        flow = new PhaseFlow(next, SystemClock.elapsedRealtimeNanos(),
                PhaseProgressStore.isInventoryComplete(activity, NeobotSeedData.PROJECT_ID, next.getId()));
        if(next.getNumber()==1 && !devNavigationActive
                && (currentSection>1||PhaseProgressStore.isInventoryComplete(activity,NeobotSeedData.PROJECT_ID,next.getId()))) {
            phaseOneScanConfirmed=true;
            flow.inventoryComplete(SystemClock.elapsedRealtimeNanos());
        }
        session = null;
        lastCorrection = null;
        correctionOverlayTargets = null;
        inventoryIndex = 0;
        inventoryDone.clear();
        if(restoringSession&&(next.getNumber()==1||next.getNumber()>11)){
            inventoryIndex=Math.min(restored.inventoryIndex,next.getCameraInventory().size());
            for(int i=0;i<inventoryIndex;i++)inventoryDone.put(next.getCameraInventory().get(i).getComponentId(),true);
            flow.restoreSession(restored.mode,restored.inventoryComplete||restored.developer&&restored.mode!=Mode.SCAN_PARTS,
                    restored.previewing,SystemClock.elapsedRealtimeNanos());
            if(next.getNumber()==1)phaseOneScanConfirmed=flow.getMode()==Mode.COMPLETE&&!devNavigationActive;
        }
        resetGates();
        activity.resetAnalyzerTracking();
        activity.stepInfoChanged("phase:" + next.getId());
        log("phase", next.getId() + " (section " + currentSection + ")");
        renderStatic();
        if(next.getNumber()==1)((dev.chyken.assembl_ar.ui.ComponentPreviewWindow)activity.findViewById(R.id.componentPreviewWindow)).showInteractionHint();
        if(next.getNumber()==2){
            plan=RequestPlan.NONE;
            gearGuide=new Phase2GuideController(activity,devNavigationActive,this::finishGearPhase,
                    ()->startPhase(AssemblyPhaseCatalog.phases().get(0)),
                    restoringSession?restored.gearState:null,this::persistSession);
            flow.attachGearMotorFlow(gearGuide.flow);gearGuide.start();restoringSession=false;persistSession();return;
        }
        if(next.getNumber()==3){
            plan=RequestPlan.NONE;
            driverGuide=new Phase3GuideController(activity,devNavigationActive,this::finishDriverPhase,
                    ()->reviewPhase(AssemblyPhaseCatalog.phases().get(1),true),restoringSession?restored.gearState:null,this::persistSession);
            flow.attachDriverFlow(driverGuide.flow);driverGuide.start();restoringSession=false;persistSession();return;
        }
        if(next.getNumber()==4){
            plan=RequestPlan.NONE;
            wiringGuide=new Phase4GuideController(activity,devNavigationActive,this::finishWiringPhase,
                    ()->reviewPhase(AssemblyPhaseCatalog.phases().get(2),true),restoringSession?restored.gearState:null,this::persistSession);
            flow.attachWiringFlow(wiringGuide.flow);wiringGuide.start();restoringSession=false;persistSession();return;
        }
        if(next.getNumber()==5){
            plan=RequestPlan.NONE;
            microcontrollerGuide=new Phase5GuideController(activity,devNavigationActive,this::finishMicrocontrollerPhase,
                    ()->reviewPhase(AssemblyPhaseCatalog.phases().get(3),true),restoringSession?restored.gearState:null,this::persistSession);
            flow.attachMicrocontrollerFlow(microcontrollerGuide.flow);microcontrollerGuide.start();restoringSession=false;persistSession();return;
        }
        if(next.getNumber()==6){
            plan=RequestPlan.NONE;
            driverWiringGuide=new Phase6GuideController(activity,devNavigationActive,this::finishDriverWiringPhase,
                ()->reviewPhase(AssemblyPhaseCatalog.phases().get(4),true),restoringSession?restored.gearState:null,this::persistSession);
            flow.attachDriverWiringFlow(driverWiringGuide.flow);driverWiringGuide.start();restoringSession=false;persistSession();return;
        }
        if(next.getNumber()==7){
            plan=RequestPlan.NONE;
            String state=restoringSession?restored.gearState:PhaseProgressStore.casterTrackerState(activity,NeobotSeedData.PROJECT_ID);
            if((state==null||state.isEmpty())&&currentSection>=10)state="legacyCaster";
            casterTrackerGuide=new Phase7GuideController(activity,devNavigationActive,this::finishCasterTrackerPhase,
                ()->reviewPhase(AssemblyPhaseCatalog.phases().get(5),true),state,this::persistSession);
            flow.attachCasterTrackerFlow(casterTrackerGuide.flow);casterTrackerGuide.start();restoringSession=false;persistSession();return;
        }
        if(next.getNumber()==8){
            plan=RequestPlan.NONE;
            sensorWiringGuide=new Phase8GuideController(activity,devNavigationActive,this::finishSensorWiringPhase,
                ()->reviewPhase(AssemblyPhaseCatalog.phases().get(6),true),restoringSession?restored.gearState:null,this::persistSession);
            flow.attachSensorWiringFlow(sensorWiringGuide.flow);sensorWiringGuide.start();restoringSession=false;persistSession();return;
        }
        if(next.getNumber()==9){
            plan=RequestPlan.NONE;
            String state=restoringSession?restored.gearState:PhaseProgressStore.standoffWheelsState(activity,NeobotSeedData.PROJECT_ID);
            if((state==null||state.isEmpty())&&currentSection>=13)state=PhaseProgressStore.isInventoryComplete(activity,NeobotSeedData.PROJECT_ID,AssemblyPhaseCatalog.WHEEL_PAIR)?"legacyWheels":"legacyStandoff";
            standoffWheelsGuide=new Phase9GuideController(activity,devNavigationActive,this::finishStandoffWheelsPhase,
                ()->reviewPhase(AssemblyPhaseCatalog.phases().get(7),true),state,this::persistSession);
            flow.attachStandoffWheelsFlow(standoffWheelsGuide.flow);standoffWheelsGuide.start();restoringSession=false;persistSession();return;
        }
        if(next.getNumber()==10){
            plan=RequestPlan.NONE;
            String state=restoringSession?restored.gearState:PhaseProgressStore.extensionBoardState(activity,NeobotSeedData.PROJECT_ID);
            if((state==null||state.isEmpty())&&currentSection>14)state="legacy:"+currentSection;
            extensionBoardGuide=new Phase10GuideController(activity,devNavigationActive,this::finishExtensionBoardPhase,
                ()->reviewPhase(AssemblyPhaseCatalog.phases().get(8),true),state,this::persistSession);
            flow.attachExtensionBoardFlow(extensionBoardGuide.flow);extensionBoardGuide.start();restoringSession=false;persistSession();return;
        }
        if(next.getNumber()==11){
            plan=RequestPlan.NONE;
            String state=restoringSession?restored.gearState:PhaseProgressStore.oledBatteryState(activity,NeobotSeedData.PROJECT_ID);
            if((state==null||state.isEmpty())&&currentSection>=18)state="legacyBattery";
            oledBatteryGuide=new Phase11GuideController(activity,devNavigationActive,this::finishOledBatteryPhase,
                ()->reviewPhase(AssemblyPhaseCatalog.phases().get(9),true),state,this::persistSession);
            flow.attachOledBatteryFlow(oledBatteryGuide.flow);oledBatteryGuide.start();restoringSession=false;persistSession();return;
        }
        enterMode();
        if(restoringSession&&script!=null&&!script.getChapters().isEmpty()){
            int chapter=Math.min(restored.chapter,script.getChapters().size()-1);
            if(flow.getMode()==Mode.DEMO)player.jumpTo(chapter);
            else if(flow.getMode()==Mode.ASSEMBLE)onChapterStarted(script.getChapters().get(chapter),chapter,script.getChapters().size());
        }
        restoringSession=false;persistSession();
    }
    /** Session location is resumable even in DEV; verified learner records remain separate. */
    private void persistSession(){
        activity.refreshSchematicControl();
        if(restoringSession||savingPhase||phase==null||flow==null)return;
        int chapter=reviewScript!=null?pausedChapter:shownChapter==null?0:shownChapter.getIndex();
        String gearState=phase.getNumber()==11&&oledBatteryGuide!=null?oledBatteryGuide.checkpointState():phase.getNumber()==10&&extensionBoardGuide!=null?extensionBoardGuide.checkpointState():phase.getNumber()==9&&standoffWheelsGuide!=null?standoffWheelsGuide.checkpointState():phase.getNumber()==8&&sensorWiringGuide!=null?sensorWiringGuide.checkpointState():phase.getNumber()==7&&casterTrackerGuide!=null?casterTrackerGuide.checkpointState():phase.getNumber()==6&&driverWiringGuide!=null?driverWiringGuide.checkpointState():phase.getNumber()==5&&microcontrollerGuide!=null?microcontrollerGuide.checkpointState():phase.getNumber()==4&&wiringGuide!=null?wiringGuide.checkpointState():phase.getNumber()==3&&driverGuide!=null?driverGuide.checkpointState():phase.getNumber()==2&&gearGuide!=null?gearGuide.checkpointState():null;
        PhaseProgressStore.saveSession(activity,NeobotSeedData.PROJECT_ID,new PhaseSessionCheckpoint(
                phase.getId(),devNavigationActive,flow.getMode(),chapter,inventoryIndex,
                flow.isInventoryComplete(),flow.isPreviewing(),gearState));
    }
    private void finishGearPhase(){
        if(devNavigationActive){devJumpToPhase(AssemblyPhaseCatalog.phases().get(2));return;}
        completePhase();
    }
    private void finishDriverPhase(){if(devNavigationActive){devJumpToPhase(AssemblyPhaseCatalog.phases().get(3));return;}completePhase();}
    private void finishWiringPhase(){if(devNavigationActive){devJumpToPhase(AssemblyPhaseCatalog.phases().get(4));return;}completePhase();}
    private void finishMicrocontrollerPhase(){if(devNavigationActive){devJumpToPhase(AssemblyPhaseCatalog.phases().get(5));return;}completePhase();}
    private void finishDriverWiringPhase(){if(devNavigationActive){devJumpToPhase(AssemblyPhaseCatalog.phases().get(6));return;}completePhase();}
    private void finishCasterTrackerPhase(){if(devNavigationActive){devJumpToPhase(AssemblyPhaseCatalog.phases().get(7));return;}completePhase();}
    private void finishSensorWiringPhase(){if(devNavigationActive){devJumpToPhase(AssemblyPhaseCatalog.phases().get(8));return;}completePhase();}
    private void finishOledBatteryPhase(){if(devNavigationActive){activity.openCompletion(false);return;}completePhase();}
    private void finishExtensionBoardPhase(){if(devNavigationActive){devJumpToPhase(AssemblyPhaseCatalog.phases().get(10));return;}completePhase();}
    private void finishStandoffWheelsPhase(){if(devNavigationActive){devJumpToPhase(AssemblyPhaseCatalog.phases().get(9));return;}completePhase();}
    dev.chyken.assembl_ar.workflow.phase.WiringDiagramState wiringDiagramState(){
        dev.chyken.assembl_ar.workflow.phase.WiringDiagramState saved=dev.chyken.assembl_ar.workflow.phase.WiringDiagramState.fromCheckpoints(
            PhaseProgressStore.wiringState(activity,NeobotSeedData.PROJECT_ID),PhaseProgressStore.driverWiringState(activity,NeobotSeedData.PROJECT_ID),PhaseProgressStore.sensorWiringState(activity,NeobotSeedData.PROJECT_ID));
        return new dev.chyken.assembl_ar.workflow.phase.WiringDiagramState(wiringGuide==null?saved.motor:wiringGuide.flow.colors(),driverWiringGuide==null?saved.driver:driverWiringGuide.flow.colors(),sensorWiringGuide==null?saved.components:sensorWiringGuide.flow.colors());
    }
    int displayedPhaseNumber(){return phase==null?1:phase.getNumber();}
    /** A paused current phase must not expose its schematic while an earlier scan is reviewed. */
    int focusedSchematicPhase(){
        if(wiringGuide!=null&&wiringGuide.schematicFocused())return 4;
        if(driverWiringGuide!=null&&driverWiringGuide.schematicFocused())return 6;
        if(sensorWiringGuide!=null&&sensorWiringGuide.schematicFocused())return 8;
        return 0;
    }
    boolean isDeveloperSession(){return devNavigationActive;}
    void returnToSavedAssembly(){
        if(!devNavigationActive||savingPhase)return;
        activity.databaseExecutor().execute(()->{
            ProjectProgress saved=activity.repository().getProjectProgress(NeobotSeedData.PROJECT_ID);
            activity.runOnUiThread(()->{
                if(activity.isGone())return;
                if(gearGuide!=null){gearGuide.close();gearGuide=null;}
                if(driverGuide!=null){driverGuide.close();driverGuide=null;}
                if(wiringGuide!=null){wiringGuide.close();wiringGuide=null;}
                if(microcontrollerGuide!=null){microcontrollerGuide.close();microcontrollerGuide=null;}
                if(driverWiringGuide!=null){driverWiringGuide.close();driverWiringGuide=null;}
                if(casterTrackerGuide!=null){casterTrackerGuide.close();casterTrackerGuide=null;}
                if(sensorWiringGuide!=null){sensorWiringGuide.close();sensorWiringGuide=null;}
                if(standoffWheelsGuide!=null){standoffWheelsGuide.close();standoffWheelsGuide=null;}
                if(extensionBoardGuide!=null){extensionBoardGuide.close();extensionBoardGuide=null;}
                if(oledBatteryGuide!=null){oledBatteryGuide.close();oledBatteryGuide=null;}
                PhaseProgressStore.clearSession(activity,NeobotSeedData.PROJECT_ID);
                activity.onPhaseSectionsSaved(saved);
                load(steps,saved);
            });
        });
    }

    private int sectionNumber(String stepId) {
        for (AssemblyStep step : steps) {
            if (step.getId().equals(stepId)) return step.getStepNumber();
        }
        return 0;
    }

    @Nullable
    private AssemblyStep step(int section) {
        for (AssemblyStep step : steps) {
            if (step.getStepNumber() == section) return step;
        }
        return null;
    }

    private void completePhase() {
        if (phase == null || savingPhase) return;
        AssemblyPhase finished = phase;
        List<Integer> sections = PhaseProgress.sectionsToSave(finished, currentSection);
        List<String> stepIds = new ArrayList<>();
        for (int section : sections) {
            AssemblyStep step = step(section);
            if (step != null) stepIds.add(step.getId());
        }
        savingPhase = true;
        if(gearGuide!=null)gearGuide.setSaving(true);
        if(driverGuide!=null)driverGuide.setSaving(true);
        if(wiringGuide!=null)wiringGuide.setSaving(true);
        if(microcontrollerGuide!=null)microcontrollerGuide.setSaving(true);
        if(driverWiringGuide!=null)driverWiringGuide.setSaving(true);
        if(casterTrackerGuide!=null)casterTrackerGuide.setSaving(true);
        if(sensorWiringGuide!=null)sensorWiringGuide.setSaving(true);
        if(standoffWheelsGuide!=null)standoffWheelsGuide.setSaving(true);
        if(extensionBoardGuide!=null)extensionBoardGuide.setSaving(true);
        if(oledBatteryGuide!=null)oledBatteryGuide.setSaving(true);
        if(phase.getNumber()==1) phaseOneGuide.setScanned(true,true,!phaseOneScanConfirmed);
        plan = RequestPlan.NONE;
        activity.setBanner(activity.getString(R.string.phase_saving), true, R.drawable.bg_live_pill);
        activity.databaseExecutor().execute(() -> {
            try {
                if (!PhaseProgressStore.markComplete(activity, NeobotSeedData.PROJECT_ID, finished.getId())) {
                    throw new IllegalStateException("Phase record was not written");
                }
                ProjectProgress saved = stepIds.isEmpty()
                        ? activity.repository().getProjectProgress(NeobotSeedData.PROJECT_ID)
                        : activity.repository().confirmAndCompleteSteps(stepIds);
                activity.runOnUiThread(() -> onPhaseSaved(finished, stepIds, saved));
            } catch (RuntimeException failure) {
                Log.e(TAG, "Could not save phase " + finished.getId(), failure);
                activity.runOnUiThread(() -> onPhaseSaveFailed(finished));
            }
        });
    }

    private void onPhaseSaved(AssemblyPhase finished, List<String> stepIds, ProjectProgress saved) {
        if(phase!=finished)return;
        savingPhase = false;
        devNavigationActive = false;
        if (activity.isGone()) return;
        for (String stepId : stepIds) {
            GuidedStepProgressStore.clearStep(activity, NeobotSeedData.PROJECT_ID, stepId);
        }
        log("saved", finished.getId() + " sections=" + stepIds);
        if (saved.isComplete()) {
            activity.openCompletion();
            return;
        }
        activity.onPhaseSectionsSaved(saved);
        currentSection = sectionNumber(saved.getCurrentStepId());
        AssemblyPhase next = PhaseProgress.resume(currentSection,
                PhaseProgressStore.records(activity, NeobotSeedData.PROJECT_ID));
        if (next == null) {
            activity.openCompletion();
            return;
        }
        startPhase(next);
        activity.setBanner(activity.getString(R.string.phase_saved_next, finished.getTitle(),
                next.getTitle()), false, R.drawable.bg_success);
    }

    private void onPhaseSaveFailed(AssemblyPhase finished) {
        savingPhase = false;
        if (activity.isGone() || flow == null || phase != finished) return;
        if(gearGuide!=null){gearGuide.setSaving(false);activity.setBanner(activity.getString(R.string.progress_save_failed),false,R.drawable.bg_camera_warning);return;}
        if(driverGuide!=null){driverGuide.setSaving(false);activity.setBanner(activity.getString(R.string.progress_save_failed),false,R.drawable.bg_camera_warning);return;}
        if(wiringGuide!=null){wiringGuide.setSaving(false);activity.setBanner(activity.getString(R.string.progress_save_failed),false,R.drawable.bg_camera_warning);return;}
        if(microcontrollerGuide!=null){microcontrollerGuide.setSaving(false);activity.setBanner(activity.getString(R.string.progress_save_failed),false,R.drawable.bg_camera_warning);return;}
        if(driverWiringGuide!=null){driverWiringGuide.setSaving(false);activity.setBanner(activity.getString(R.string.progress_save_failed),false,R.drawable.bg_camera_warning);return;}
        if(casterTrackerGuide!=null){casterTrackerGuide.setSaving(false);activity.setBanner(activity.getString(R.string.progress_save_failed),false,R.drawable.bg_camera_warning);return;}
        if(sensorWiringGuide!=null){sensorWiringGuide.setSaving(false);activity.setBanner(activity.getString(R.string.progress_save_failed),false,R.drawable.bg_camera_warning);return;}
        if(standoffWheelsGuide!=null){standoffWheelsGuide.setSaving(false);activity.setBanner(activity.getString(R.string.progress_save_failed),false,R.drawable.bg_camera_warning);return;}
        if(extensionBoardGuide!=null){extensionBoardGuide.setSaving(false);activity.setBanner(activity.getString(R.string.progress_save_failed),false,R.drawable.bg_camera_warning);return;}
        if(oledBatteryGuide!=null){oledBatteryGuide.setSaving(false);activity.setBanner(activity.getString(R.string.progress_save_failed),false,R.drawable.bg_camera_warning);return;}
        flow.completionSaveFailed(SystemClock.elapsedRealtimeNanos());
        if (flow.getMode() == Mode.COMPLETE) {
            // Board preparation: rescan to retry, it has no checklist to reopen.
            flow.restart(SystemClock.elapsedRealtimeNanos());
        }
        enterMode();
        activity.setBanner(activity.getString(R.string.progress_save_failed), false,
                R.drawable.bg_camera_warning);
    }

    // ---- Learner actions ---------------------------------------------------------------

    private void replay() {
        if(reviewScript!=null) { player.jumpTo(0); return; }
        if (flow == null || savingPhase) return;
        if (flow.replay(SystemClock.elapsedRealtimeNanos())) {
            log("action", "replay");
            enterMode();
        }
    }

    private void scan() {
        if(reviewScript!=null) { returnFromReview(); return; }
        if (flow == null || savingPhase) return;
        if (flow.scan(SystemClock.elapsedRealtimeNanos())) {
            log("action", "scan");
            enterMode();
        }
    }

    private void jumpChapter(int offset) {
        if (flow == null || script == null || savingPhase) return;
        if(reviewScript!=null) {
            int target=Math.max(0,player.getPosition())+offset;
            if(target<0) {
                int previous=reviewScript.getPhase().getNumber()-2;
                if(previous>=0) reviewPhase(AssemblyPhaseCatalog.phases().get(previous),true);
            } else if(target>=reviewScript.getChapters().size()) {
                int next=reviewScript.getPhase().getNumber();
                if(next<phase.getNumber()-1) reviewPhase(AssemblyPhaseCatalog.phases().get(next),false);
                else returnFromReview();
            } else player.jumpTo(target);
            return;
        }
        Mode mode = flow.getMode();
        if(phase.getNumber()==1 && mode==Mode.COMPLETE && !devNavigationActive && offset>0) {
            completePhase(); return;
        }
        int current=shownChapter==null ? 0 : shownChapter.getIndex();
        if(mode==Mode.SCAN_PARTS || mode==Mode.VERIFY || mode==Mode.PHYSICAL_CHECK || mode==Mode.COMPLETE) {
            if(mode==Mode.COMPLETE && devNavigationActive && offset>0
                    && phase.getNumber()<AssemblyPhaseCatalog.phases().size()) {
                devJumpToPhase(AssemblyPhaseCatalog.phases().get(phase.getNumber())); return;
            }
            if(offset<0 && phase.getNumber()>1) reviewPhase(AssemblyPhaseCatalog.phases().get(phase.getNumber()-2),true);
            return;
        }
        int target=current+offset;
        if(target<0) {
            if(phase.getNumber()>1) reviewPhase(AssemblyPhaseCatalog.phases().get(phase.getNumber()-2),true);
            return;
        }
        if(target>=script.getChapters().size() || target>unlockedChapter()) return;
        if (!player.isPlaying() || player.getCount() != script.getChapters().size()) {
            // Browsing from Assemble/Correction shows the full demo from that chapter.
            playFullDemo(false);
        }
        player.jumpTo(Math.max(0, Math.min(script.getChapters().size() - 1, target)));
    }

    private int unlockedChapter() {
        if(script==null) return 0;
        int index=0;
        while(index<script.getChapters().size()-1 && confirmedChapters.contains(chapterKey(phase,script.getChapters().get(index)))) index++;
        return index;
    }

    private static String chapterKey(AssemblyPhase phase, Chapter chapter) {
        return phase.getId()+":"+chapter.getSectionNumber()+":"+chapter.getOrder();
    }

    private void persistReview() {
        activity.getSharedPreferences("assembly_instruction_review",0).edit()
                .putStringSet("done",new HashSet<>(confirmedChapters))
                .putStringSet("seen",new HashSet<>(viewedChapters)).apply();
    }

    private void confirmChapter() {
        if(reviewScript!=null || phase==null || shownChapter==null || !player.isChapterReady()) return;
        confirmedChapters.add(chapterKey(phase,shownChapter)); persistReview();
        player.confirmChapter(); updateNavigation();
    }

    private void reviewPhase(AssemblyPhase earlier, boolean lastChapter) {
        if(savingPhase||activity.isGone())return;
        if(microcontrollerGuide!=null&&phase!=null&&phase.getNumber()==5)microcontrollerGuide.prepareForReview();
        if(driverWiringGuide!=null&&phase!=null&&phase.getNumber()==6)driverWiringGuide.prepareForReview();
        if(casterTrackerGuide!=null&&phase!=null&&phase.getNumber()==7)casterTrackerGuide.prepareForReview();
        if(sensorWiringGuide!=null&&phase!=null&&phase.getNumber()==8)sensorWiringGuide.prepareForReview();
        if(extensionBoardGuide!=null&&phase!=null&&phase.getNumber()==10)extensionBoardGuide.prepareForReview();
        if(oledBatteryGuide!=null&&phase!=null&&phase.getNumber()==11)oledBatteryGuide.prepareForReview();
        if(standoffWheelsGuide!=null&&phase!=null&&phase.getNumber()==9)standoffWheelsGuide.prepareForReview();
        if(earlier.getNumber()==10&&phase!=null&&phase.getNumber()>10){
            if(reviewScript==null)pausedChapter=shownChapter==null?0:shownChapter.getIndex();
            handler.removeCallbacks(budgetPoll);plan=RequestPlan.NONE;clearOverlay();player.pause();reviewScript=null;activity.setPhaseTwoGuideActive(true);
            extensionBoardGuide=new Phase10GuideController(activity,true,()->{if(extensionBoardGuide!=null){extensionBoardGuide.close();extensionBoardGuide=null;}activity.setPhaseTwoGuideActive(false);returnFromReview();},
                ()->{if(extensionBoardGuide!=null){extensionBoardGuide.close();extensionBoardGuide=null;}activity.setPhaseTwoGuideActive(false);reviewPhase(AssemblyPhaseCatalog.phases().get(8),true);},
                PhaseProgressStore.extensionBoardState(activity,NeobotSeedData.PROJECT_ID),()->{});
            extensionBoardGuide.startReview(lastChapter);return;
        }
        if(earlier.getNumber()==9&&phase!=null&&phase.getNumber()>9){
            if(reviewScript==null)pausedChapter=shownChapter==null?0:shownChapter.getIndex();
            handler.removeCallbacks(budgetPoll);plan=RequestPlan.NONE;clearOverlay();player.pause();reviewScript=null;activity.setPhaseTwoGuideActive(true);
            standoffWheelsGuide=new Phase9GuideController(activity,true,()->{if(standoffWheelsGuide!=null){standoffWheelsGuide.close();standoffWheelsGuide=null;}activity.setPhaseTwoGuideActive(false);returnFromReview();},
                ()->{if(standoffWheelsGuide!=null){standoffWheelsGuide.close();standoffWheelsGuide=null;}activity.setPhaseTwoGuideActive(false);reviewPhase(AssemblyPhaseCatalog.phases().get(7),true);},
                PhaseProgressStore.standoffWheelsState(activity,NeobotSeedData.PROJECT_ID),()->{});
            standoffWheelsGuide.startReview(lastChapter);return;
        }
        if(earlier.getNumber()==8&&phase!=null&&phase.getNumber()>8){
            if(reviewScript==null)pausedChapter=shownChapter==null?0:shownChapter.getIndex();
            handler.removeCallbacks(budgetPoll);plan=RequestPlan.NONE;clearOverlay();player.pause();reviewScript=null;activity.setPhaseTwoGuideActive(true);
            sensorWiringGuide=new Phase8GuideController(activity,true,()->{if(sensorWiringGuide!=null){sensorWiringGuide.close();sensorWiringGuide=null;}activity.setPhaseTwoGuideActive(false);returnFromReview();},
                ()->{if(sensorWiringGuide!=null){sensorWiringGuide.close();sensorWiringGuide=null;}activity.setPhaseTwoGuideActive(false);reviewPhase(AssemblyPhaseCatalog.phases().get(6),true);},
                PhaseProgressStore.sensorWiringState(activity,NeobotSeedData.PROJECT_ID),()->{});
            sensorWiringGuide.startReview(lastChapter);return;
        }
        if(earlier.getNumber()==7&&phase!=null&&phase.getNumber()>7){
            if(reviewScript==null)pausedChapter=shownChapter==null?0:shownChapter.getIndex();
            handler.removeCallbacks(budgetPoll);plan=RequestPlan.NONE;clearOverlay();player.pause();reviewScript=null;activity.setPhaseTwoGuideActive(true);
            casterTrackerGuide=new Phase7GuideController(activity,true,()->{if(casterTrackerGuide!=null){casterTrackerGuide.close();casterTrackerGuide=null;}activity.setPhaseTwoGuideActive(false);returnFromReview();},
                ()->{if(casterTrackerGuide!=null){casterTrackerGuide.close();casterTrackerGuide=null;}activity.setPhaseTwoGuideActive(false);reviewPhase(AssemblyPhaseCatalog.phases().get(5),true);},
                PhaseProgressStore.casterTrackerState(activity,NeobotSeedData.PROJECT_ID),()->{});
            casterTrackerGuide.startReview(lastChapter);return;
        }
        if(earlier.getNumber()==6&&phase!=null&&phase.getNumber()>6){
            if(reviewScript==null)pausedChapter=shownChapter==null?0:shownChapter.getIndex();
            handler.removeCallbacks(budgetPoll);plan=RequestPlan.NONE;clearOverlay();player.pause();reviewScript=null;activity.setPhaseTwoGuideActive(true);
            driverWiringGuide=new Phase6GuideController(activity,true,()->{if(driverWiringGuide!=null){driverWiringGuide.close();driverWiringGuide=null;}activity.setPhaseTwoGuideActive(false);returnFromReview();},
                ()->{if(driverWiringGuide!=null){driverWiringGuide.close();driverWiringGuide=null;}activity.setPhaseTwoGuideActive(false);reviewPhase(AssemblyPhaseCatalog.phases().get(4),true);},
                PhaseProgressStore.driverWiringState(activity,NeobotSeedData.PROJECT_ID),()->{});
            driverWiringGuide.startReview(lastChapter);return;
        }
        if(earlier.getNumber()==5&&phase!=null&&phase.getNumber()>5){
            if(reviewScript==null)pausedChapter=shownChapter==null?0:shownChapter.getIndex();
            handler.removeCallbacks(budgetPoll);plan=RequestPlan.NONE;clearOverlay();player.pause();reviewScript=null;activity.setPhaseTwoGuideActive(true);
            microcontrollerGuide=new Phase5GuideController(activity,true,()->{if(microcontrollerGuide!=null){microcontrollerGuide.close();microcontrollerGuide=null;}activity.setPhaseTwoGuideActive(false);returnFromReview();},
                    ()->{if(microcontrollerGuide!=null){microcontrollerGuide.close();microcontrollerGuide=null;}activity.setPhaseTwoGuideActive(false);reviewPhase(AssemblyPhaseCatalog.phases().get(3),true);},
                    PhaseProgressStore.microcontrollerState(activity,NeobotSeedData.PROJECT_ID),()->{});
            microcontrollerGuide.startReview(lastChapter);return;
        }
        if(wiringGuide!=null&&phase!=null&&phase.getNumber()==4)wiringGuide.prepareForReview();
        if(earlier.getNumber()==4&&phase!=null&&phase.getNumber()>4){
            if(reviewScript==null)pausedChapter=shownChapter==null?0:shownChapter.getIndex();
            handler.removeCallbacks(budgetPoll);plan=RequestPlan.NONE;clearOverlay();player.pause();reviewScript=null;activity.setPhaseTwoGuideActive(true);
            wiringGuide=new Phase4GuideController(activity,true,()->{if(wiringGuide!=null){wiringGuide.close();wiringGuide=null;}activity.setPhaseTwoGuideActive(false);returnFromReview();},
                    ()->{if(wiringGuide!=null){wiringGuide.close();wiringGuide=null;}activity.setPhaseTwoGuideActive(false);reviewPhase(AssemblyPhaseCatalog.phases().get(2),true);},
                    PhaseProgressStore.wiringState(activity,NeobotSeedData.PROJECT_ID),()->{});
            wiringGuide.startReview(lastChapter);return;
        }
        if(driverGuide!=null&&phase!=null&&phase.getNumber()==3)driverGuide.prepareForReview();
        if(earlier.getNumber()==3&&phase!=null&&phase.getNumber()>3){
            if(reviewScript==null)pausedChapter=shownChapter==null?0:shownChapter.getIndex();
            handler.removeCallbacks(budgetPoll);plan=RequestPlan.NONE;clearOverlay();player.pause();reviewScript=null;activity.setPhaseTwoGuideActive(true);
            driverGuide=new Phase3GuideController(activity,true,()->{if(driverGuide!=null){driverGuide.close();driverGuide=null;}activity.setPhaseTwoGuideActive(false);returnFromReview();},
                    ()->{if(driverGuide!=null){driverGuide.close();driverGuide=null;}activity.setPhaseTwoGuideActive(false);reviewPhase(AssemblyPhaseCatalog.phases().get(1),true);},null,()->{});
            driverGuide.startReview(lastChapter);return;
        }
        if(earlier.getNumber()==2&&phase!=null&&phase.getNumber()>2){
            if(reviewScript==null)pausedChapter=shownChapter==null?0:shownChapter.getIndex();
            handler.removeCallbacks(budgetPoll);plan=RequestPlan.NONE;clearOverlay();player.pause();reviewScript=null;
            activity.setPhaseTwoGuideActive(true);
            gearGuide=new Phase2GuideController(activity,true,()->{
                if(gearGuide!=null){gearGuide.close();gearGuide=null;}
                activity.setPhaseTwoGuideActive(false);returnFromReview();
            },()->{
                if(gearGuide!=null){gearGuide.close();gearGuide=null;}
                activity.setPhaseTwoGuideActive(false);reviewPhase(AssemblyPhaseCatalog.phases().get(0),true);
            });
            gearGuide.startReview(lastChapter);return;
        }
        if(reviewScript==null) pausedChapter=shownChapter==null ? 0 : shownChapter.getIndex();
        handler.removeCallbacks(budgetPoll);
        plan=RequestPlan.NONE;
        clearOverlay();
        reviewScript=scriptFor(earlier);
        if(!earlier.getParts().isEmpty()) {
            Part part=earlier.getParts().get(0);
            Component component=activity.component(part.getComponentId());
            if(component!=null) activity.showComponentPreview(component.getId(),component.getName());
        }
        player.setManualAdvance(true);
        Uri[] uri=new Uri[1];
        PhaseMediaCatalog.Clip clip=resolveClip(earlier,uri);
        player.play(reviewScript.getChapters(),null,"review:"+earlier.getId(),clip,uri[0]);
        if(lastChapter) player.jumpTo(reviewScript.getChapters().size()-1);
        title.setText(earlier.getTitle());
        scanButton.setVisibility(View.VISIBLE); scanButton.setText(R.string.return_current_step);
        replayButton.setText(R.string.replay_demo);
        activity.setBanner(activity.getString(R.string.review_preserves_progress),false,R.drawable.bg_live_pill);
    }

    private void returnFromReview() {
        if(microcontrollerGuide!=null&&phase!=null&&phase.getNumber()==5){player.release();reviewScript=null;activity.setPhaseTwoGuideActive(true);microcontrollerGuide.resume();return;}
        if(driverWiringGuide!=null&&phase!=null&&phase.getNumber()==6){player.release();reviewScript=null;activity.setPhaseTwoGuideActive(true);driverWiringGuide.resume();return;}
        if(casterTrackerGuide!=null&&phase!=null&&phase.getNumber()==7){player.release();reviewScript=null;activity.setPhaseTwoGuideActive(true);casterTrackerGuide.resume();return;}
        if(sensorWiringGuide!=null&&phase!=null&&phase.getNumber()==8){player.release();reviewScript=null;activity.setPhaseTwoGuideActive(true);sensorWiringGuide.resume();return;}
        if(extensionBoardGuide!=null&&phase!=null&&phase.getNumber()==10){player.release();reviewScript=null;activity.setPhaseTwoGuideActive(true);extensionBoardGuide.resume();return;}
        if(oledBatteryGuide!=null&&phase!=null&&phase.getNumber()==11){player.release();reviewScript=null;activity.setPhaseTwoGuideActive(true);oledBatteryGuide.resume();return;}
        if(standoffWheelsGuide!=null&&phase!=null&&phase.getNumber()==9){player.release();reviewScript=null;activity.setPhaseTwoGuideActive(true);standoffWheelsGuide.resume();return;}
        if(wiringGuide!=null&&phase!=null&&phase.getNumber()==4){player.release();reviewScript=null;activity.setPhaseTwoGuideActive(true);wiringGuide.resume();return;}
        if(driverGuide!=null&&phase!=null&&phase.getNumber()==3){player.release();reviewScript=null;activity.setPhaseTwoGuideActive(true);driverGuide.resume();return;}
        player.release();
        reviewScript=null; shownChapter=null;
        renderedReferenceKey=null;
        if(script!=null && !script.getChapters().isEmpty()) {
            Chapter chapter=script.getChapters().get(Math.min(pausedChapter,script.getChapters().size()-1));
            ((AssemblyDemoView)activity.findViewById(R.id.assemblyDemo)).setDemo(chapter.getDemo());
        }
        if(flow!=null) flow.restartEvidence(SystemClock.elapsedRealtimeNanos());
        renderStatic(); enterMode();
        if(flow!=null && flow.getMode()==Mode.DEMO) player.jumpTo(Math.min(pausedChapter,unlockedChapter()));
    }

    private void restartPhase() {
        if (flow == null || savingPhase) return;
        if(gearGuide!=null){gearGuide.restart();return;}
        if(driverGuide!=null){driverGuide.restart();return;}
        if(wiringGuide!=null){wiringGuide.restart();return;}
        if(microcontrollerGuide!=null){microcontrollerGuide.restart();return;}
        if(driverWiringGuide!=null){driverWiringGuide.restart();return;}
        if(casterTrackerGuide!=null){casterTrackerGuide.restart();return;}
        if(sensorWiringGuide!=null){sensorWiringGuide.restart();return;}
        if(standoffWheelsGuide!=null){standoffWheelsGuide.restart();return;}
        if(extensionBoardGuide!=null){extensionBoardGuide.restart();return;}
        if(oledBatteryGuide!=null){oledBatteryGuide.restart();return;}
        log("action", "restart phase");
        confirmedChapters.removeIf(key->key.startsWith(phase.getId()+":")); persistReview();
        PhaseProgressStore.setInventoryComplete(activity, NeobotSeedData.PROJECT_ID, phase.getId(), false);
        flow.restart(SystemClock.elapsedRealtimeNanos());
        inventoryIndex = 0;
        inventoryDone.clear();
        session = null;
        resetGates();
        activity.resetAnalyzerTracking();
        enterMode();
    }

    /** Debug navigation changes the live guide only; saved learner progress stays sequential. */
    void devJumpToPhase(AssemblyPhase target) {
        if (target == null || steps == null || savingPhase) return;
        devNavigationActive = true;
        currentSection = target.getFirstSection();
        startPhase(target);
    }

    void devJumpToSection(int section) {
        if (section < 1 || section > 18) return;
        for (AssemblyPhase candidate : AssemblyPhaseCatalog.phases()) {
            if (candidate.getSections().contains(section)) {
                if(candidate.getNumber()==7){devJumpToChapter(candidate,section==9?4:5);return;}
                if(candidate.getNumber()==6){devJumpToChapter(candidate,section==7?1:2);return;}
                if(candidate.getNumber()==4){devJumpToChapter(candidate,section==4?0:1);return;}
                PhaseDemoScript target=PhaseDemoScript.forPhase(candidate);
                int chapter=0;
                for(Chapter item:target.getChapters()) if(item.getSectionNumber()==section) { chapter=item.getIndex(); break; }
                devJumpToChapter(candidate,chapter);
                return;
            }
        }
    }

    void devForceCompleteCurrentPhase() {
        if (phase == null || savingPhase) return;
        if(gearGuide!=null){devNavigationActive=true;gearGuide.devComplete();return;}
        if(driverGuide!=null){devNavigationActive=true;driverGuide.devComplete();return;}
        if(wiringGuide!=null){devNavigationActive=true;wiringGuide.devComplete();return;}
        if(microcontrollerGuide!=null){devNavigationActive=true;microcontrollerGuide.devComplete();return;}
        if(driverWiringGuide!=null){devNavigationActive=true;driverWiringGuide.devComplete();return;}
        if(casterTrackerGuide!=null){devNavigationActive=true;casterTrackerGuide.devComplete();return;}
        if(sensorWiringGuide!=null){devNavigationActive=true;sensorWiringGuide.devComplete();return;}
        if(standoffWheelsGuide!=null){devNavigationActive=true;standoffWheelsGuide.devComplete();return;}
        if(extensionBoardGuide!=null){devNavigationActive=true;extensionBoardGuide.devComplete();return;}
        if(oledBatteryGuide!=null){devNavigationActive=true;oledBatteryGuide.devComplete();return;}
        reviewScript=null; devNavigationActive=true;
        flow.devComplete(SystemClock.elapsedRealtimeNanos());
        plan=RequestPlan.NONE; player.stop(); clearOverlay();
        renderMode();
        activity.setBanner(activity.getString(R.string.dev_phase_complete_stay),false,R.drawable.bg_success);
    }

    void devJumpToChapter(AssemblyPhase selected, int chapter) {
        if(selected==null || savingPhase) return;
        devJumpToPhase(selected);
        if(gearGuide!=null){gearGuide.devJump(chapter+1);return;}
        if(driverGuide!=null){driverGuide.devJump(chapter+1);return;}
        if(wiringGuide!=null){wiringGuide.devJump(chapter+1);return;}
        if(microcontrollerGuide!=null){microcontrollerGuide.devJump(chapter+1);return;}
        if(driverWiringGuide!=null){driverWiringGuide.devJump(chapter+1);return;}
        if(casterTrackerGuide!=null){casterTrackerGuide.devJump(chapter+1);return;}
        if(sensorWiringGuide!=null){sensorWiringGuide.devJump(chapter+1);return;}
        if(standoffWheelsGuide!=null){standoffWheelsGuide.devJump(chapter+1);return;}
        if(extensionBoardGuide!=null){extensionBoardGuide.devJump(chapter+1);return;}
        if(oledBatteryGuide!=null){oledBatteryGuide.devJump(chapter+1);return;}
        if(script==null || chapter<0 || chapter>=script.getChapters().size()) return;
        for(int i=0;i<chapter;i++) confirmedChapters.add(chapterKey(phase,script.getChapters().get(i)));
        persistReview();
        flow.devStartDemo(SystemClock.elapsedRealtimeNanos()); enterMode(); player.jumpTo(chapter);
    }

    void devSkipCurrentStep() {
        if(phase==null || script==null || savingPhase) return;
        // Phase 1 has a dedicated learner card, not a demo chapter. Skipping its
        // nonexistent preview hid the only usable Next action. Keep this explicit
        // developer checkpoint separate from camera evidence and learner progress.
        if(phase.getNumber()==1){devForceCompleteCurrentPhase();return;}
        if(gearGuide!=null){devNavigationActive=true;gearGuide.devSkip();return;}
        if(driverGuide!=null){devNavigationActive=true;driverGuide.devSkip();return;}
        if(wiringGuide!=null){devNavigationActive=true;wiringGuide.devSkip();return;}
        if(microcontrollerGuide!=null){devNavigationActive=true;microcontrollerGuide.devSkip();return;}
        if(driverWiringGuide!=null){devNavigationActive=true;driverWiringGuide.devSkip();return;}
        if(casterTrackerGuide!=null){devNavigationActive=true;casterTrackerGuide.devSkip();return;}
        if(sensorWiringGuide!=null){devNavigationActive=true;sensorWiringGuide.devSkip();return;}
        if(standoffWheelsGuide!=null){devNavigationActive=true;standoffWheelsGuide.devSkip();return;}
        if(extensionBoardGuide!=null){devNavigationActive=true;extensionBoardGuide.devSkip();return;}
        if(oledBatteryGuide!=null){devNavigationActive=true;oledBatteryGuide.devSkip();return;}
        if(reviewScript!=null) returnFromReview();
        if(shownChapter==null || flow.getMode()==Mode.SCAN_PARTS) {
            devJumpToChapter(phase,0);
        }
        if(shownChapter==null) return;
        confirmedChapters.add(chapterKey(phase,shownChapter)); persistReview();
        updateNavigation();
        activity.setBanner(activity.getString(R.string.dev_step_skipped_stay),false,R.drawable.bg_live_pill);
    }

    private void chooseRedoPhase() {
        if (phase == null || savingPhase) return;
        List<AssemblyPhase> finished = new ArrayList<>();
        for (AssemblyPhase candidate : AssemblyPhaseCatalog.phases()) {
            if (candidate.getNumber() < phase.getNumber()) finished.add(candidate);
        }
        if (finished.isEmpty()) {
            Toast.makeText(activity, R.string.phase_redo_none, Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[finished.size()];
        for (int index = 0; index < labels.length; index++) {
            labels[index] = finished.get(index).getNumber() + ". " + finished.get(index).getTitle();
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.phase_redo_title)
                .setItems(labels, (dialog, which) -> confirmRedo(finished.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmRedo(AssemblyPhase redoPhase) {
        PhaseProgress.Redo redo = PhaseProgress.redoFrom(redoPhase);
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.phase_redo_confirm_title, redoPhase.getTitle()))
                .setMessage(activity.getString(R.string.phase_redo_confirm_body, redoPhase.getTitle(),
                        redo.getClearedPhaseIds().size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.phase_redo_action, (dialog, which) -> redo(redo))
                .show();
    }

    private void redo(PhaseProgress.Redo redo) {
        AssemblyStep reopen = step(redo.getReopenSection());
        if (reopen == null) return;
        savingPhase = true;
        plan = RequestPlan.NONE;
        log("action", "redo from section " + redo.getReopenSection() + " " + redo.getClearedPhaseIds());
        activity.databaseExecutor().execute(() -> {
            try {
                PhaseProgressStore.clear(activity, NeobotSeedData.PROJECT_ID, redo.getClearedPhaseIds());
                for (AssemblyStep step : steps) {
                    if (step.getStepNumber() >= redo.getReopenSection()) {
                        GuidedStepProgressStore.clearStep(activity, NeobotSeedData.PROJECT_ID, step.getId());
                    }
                }
                ProjectProgress reopened = activity.repository().reopenFromStep(reopen.getId());
                activity.runOnUiThread(() -> {
                    savingPhase = false;
                    if (activity.isGone()) return;
                    activity.onPhaseSectionsSaved(reopened);
                    load(steps, reopened);
                });
            } catch (RuntimeException failure) {
                Log.e(TAG, "Could not reopen phase", failure);
                activity.runOnUiThread(() -> {
                    savingPhase = false;
                    if (activity.isGone()) return;
                    enterMode();
                    activity.setBanner(activity.getString(R.string.progress_save_failed), false,
                            R.drawable.bg_camera_warning);
                });
            }
        });
    }

    private void showManualPage() {
        Chapter chapter = shownChapter;
        if (chapter == null && script != null) chapter = script.getFinalChapter();
        if (chapter == null) return;
        ManualReferenceDialog.show(activity, chapter.getSection().getManualReference(),
                chapter.getCard().getPageStart());
    }

    static void shareTrialLog(AssemblyActivity activity) {
        TrialLog log = activity.trialLog();
        if (log == null) {
            Toast.makeText(activity, R.string.trial_log_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        activity.findViewById(R.id.shareTrialLogButton).setEnabled(false);
        WeakReference<AssemblyActivity> screen = new WeakReference<>(activity);
        Handler main = new Handler(Looper.getMainLooper());
        log.shareSnapshot(snapshot -> main.post(() -> {
            AssemblyActivity current = screen.get();
            if (current == null || current.isGone()) return;
            current.findViewById(R.id.shareTrialLogButton).setEnabled(true);
            if (!current.isActivityResumed()) return;
            if (snapshot == null) {
                Toast.makeText(current, R.string.trial_log_unavailable, Toast.LENGTH_LONG).show();
                return;
            }
            shareTrialSnapshot(current, snapshot);
        }));
    }

    private static void shareTrialSnapshot(AssemblyActivity activity, File snapshot) {
        Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".triallogs", snapshot);
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, snapshot.getName())
                .putExtra(Intent.EXTRA_TEXT, BuildStamp.details())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(Intent.createChooser(share,
                    activity.getString(R.string.trial_log_chooser)));
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(activity, snapshot.getAbsolutePath(), Toast.LENGTH_LONG).show();
        }
    }

    // ---- Modes ----------------------------------------------------------------------------

    /** Applies the current flow mode to playback, camera requests and the screen. */
    private void enterMode() {
        if(gearGuide!=null){gearGuide.render();return;}
        if(driverGuide!=null){driverGuide.render();return;}
        if(wiringGuide!=null){wiringGuide.render();return;}
        if(microcontrollerGuide!=null){microcontrollerGuide.render();return;}
        if(driverWiringGuide!=null){driverWiringGuide.render();return;}
        if(casterTrackerGuide!=null){casterTrackerGuide.render();return;}
        if(sensorWiringGuide!=null){sensorWiringGuide.render();return;}
        if(standoffWheelsGuide!=null){standoffWheelsGuide.render();return;}
        if(extensionBoardGuide!=null){extensionBoardGuide.render();return;}
        if(oledBatteryGuide!=null){oledBatteryGuide.render();return;}
        if (flow == null || phase == null || script == null) return;
        handler.removeCallbacks(budgetPoll);
        Mode mode = flow.getMode();
        log("mode", flow.tag().toString());
        switch (mode) {
            case SCAN_PARTS:
                player.stop();
                // Scanning shows the parts list first; the demo view returns with the demo.
                activity.findViewById(R.id.assemblyDemo).setVisibility(View.GONE);
                resetGates();
                planInventory();
                if(phase.getNumber()==1) speakPhaseOneStart();
                else speak(scanPrompt(), "scan:" + flow.getGeneration());
                break;
            case DEMO:
                plan = RequestPlan.NONE;
                clearOverlay();
                playFullDemo(true);
                break;
            case ASSEMBLE:
                plan = RequestPlan.NONE;
                clearOverlay();
                player.stop();
                if (session == null || session.getVerdict() != Verdict.INCONCLUSIVE) {
                    speak(activity.getString(phase.hasVisualTargets()
                            ? R.string.phase_assemble_instruction
                            : R.string.phase_assemble_instruction_no_camera), "assemble:" + flow.getGeneration());
                }
                break;
            case VERIFY:
                player.stop();
                activity.voiceGuide().stop();
                session = null;
                activeGroup = null;
                lastEvidenceNanos = Long.MIN_VALUE;
                verifyStartedUptime = SystemClock.uptimeMillis();
                activity.resetAnalyzerTracking();
                planVerification(phase.getProbeGroups().get(0));
                handler.postDelayed(budgetPoll, BUDGET_POLL_MILLIS);
                break;
            case CORRECTION:
                playCorrection();
                break;
            case PHYSICAL_CHECK:
                plan = RequestPlan.NONE;
                player.stop();
                clearOverlay();
                showChecklist();
                break;
            case COMPLETE:
                plan = RequestPlan.NONE;
                player.stop();
                if(phase.getNumber()==1) {
                    phaseOneGuide.setScanned(true,false,!phaseOneScanConfirmed);
                    speak(activity.getString(phaseOneScanConfirmed ? R.string.phase_one_success_speech
                            : R.string.phase_one_dev_complete),"phase1:success:"+flow.getGeneration());
                } else if(!devNavigationActive) completePhase();
                break;
        }
        renderMode();
        persistSession();
    }

    private void playFullDemo(boolean withIntroduction) {
        if (script == null || phase == null) return;
        Uri[] uri = new Uri[1];
        PhaseMediaCatalog.Clip clip = resolveClip(uri);
        player.setManualAdvance(true);
        player.play(script.getChapters(), withIntroduction ? script.getIntroduction() : null,
                "phase:" + phase.getId(), clip, uri[0]);
    }

    private void playCorrection() {
        player.setManualAdvance(false);
        if (script == null || phase == null || flow == null) return;
        VisualTarget target = phase.getTarget(flow.getCorrectionTargetId());
        TargetResult result = session == null || target == null ? null : session.getResult(target.getId());
        lastCorrection = result;
        if (target == null || result == null) return;
        List<Chapter> chapters = script.correctionChapters(target);
        Uri[] uri = new Uri[1];
        PhaseMediaCatalog.Clip clip = resolveClip(uri);
        player.play(chapters.isEmpty() ? Collections.singletonList(script.getFinalChapter()) : chapters,
                PhaseDemoScript.correctionNarration(result),
                "fix:" + phase.getId() + ":" + target.getId(), clip, uri[0]);
        // Keep the red target live on the board while the learner fixes it.
        correctionOverlayTargets = Collections.singletonList(target);
        planOverlay(target);
        log("correction", target.getId() + " " + result.getOutcome() + " " + result.getMessage());
    }

    /**
     * A side-loaded clip with its chapter-time JSON (for trials without rebuilding the APK) takes
     * precedence over a registered clip bundled in res/raw. Without either, the narrated schematic
     * chapters play. An unreadable or mismatched side-loaded manifest is logged and ignored.
     */
    @Nullable
    private PhaseMediaCatalog.Clip resolveClip(Uri[] uriOut) {
        return resolveClip(phase,uriOut);
    }

    @Nullable
    private PhaseMediaCatalog.Clip resolveClip(AssemblyPhase phase, Uri[] uriOut) {
        if (phase == null) return null;
        String name = PhaseMediaCatalog.rawResourceName(phase.getId());
        File mediaDirectory = activity.getExternalFilesDir(PhaseMediaCatalog.SIDELOAD_DIRECTORY);
        if (mediaDirectory != null) {
            File video = new File(mediaDirectory, name + ".mp4");
            File manifest = new File(mediaDirectory, name + ".json");
            if (video.isFile() && manifest.isFile()) {
                try {
                    org.json.JSONObject json = new org.json.JSONObject(new String(
                            java.nio.file.Files.readAllBytes(manifest.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8));
                    org.json.JSONArray starts = json.getJSONArray("chapterStartMillis");
                    List<Long> millis = new ArrayList<>();
                    for (int index = 0; index < starts.length(); index++) millis.add(starts.getLong(index));
                    PhaseMediaCatalog.Clip clip = PhaseMediaCatalog.describe(phase.getId(), millis,
                            json.getLong("durationMillis"), json.optString("provenance", ""));
                    uriOut[0] = Uri.fromFile(video);
                    log("media", "side-loaded clip " + video.getName() + " · " + clip.getProvenance());
                    return clip;
                } catch (Exception invalid) {
                    Log.w(TAG, "Ignoring side-loaded clip manifest " + manifest, invalid);
                    log("media", "invalid side-loaded manifest: " + invalid.getMessage());
                }
            }
        }
        PhaseMediaCatalog.Clip clip = PhaseMediaCatalog.clipFor(phase.getId());
        if (clip == null) return null;
        int id = activity.getResources().getIdentifier(name, "raw", activity.getPackageName());
        if (id == 0) return null;
        uriOut[0] = Uri.parse("android.resource://" + activity.getPackageName() + "/" + id);
        return clip;
    }

    private void showChecklist() {
        if (phase == null || flow == null || flow.getMode() != Mode.PHYSICAL_CHECK) return;
        dismissChecklist();
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 2, padding, 0);
        TextView intro = new TextView(activity);
        intro.setText(R.string.phase_checklist_intro);
        content.addView(intro);
        List<CheckBox> boxes = new ArrayList<>();
        for (String check : phase.getDirectChecks()) {
            CheckBox box = new CheckBox(activity);
            box.setText(check);
            box.setMinHeight(Math.round(48 * activity.getResources().getDisplayMetrics().density));
            content.addView(box);
            boxes.add(box);
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        AlertDialog dialog = new dev.chyken.assembl_ar.ui.PhysicalChecklistBottomSheetDialog.Builder(activity)
                .setTitle(activity.getString(R.string.phase_checklist_title, phase.getTitle()))
                .setView(scroll)
                .setNegativeButton(R.string.phase_checklist_later, null)
                .setPositiveButton(R.string.phase_checklist_confirm, null)
                .create();
        dialog.setOnShowListener(shown -> {
            View confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            confirm.setEnabled(false);
            for (CheckBox box : boxes) {
                box.setOnCheckedChangeListener((button, checked) -> {
                    boolean all = true;
                    for (CheckBox other : boxes) all &= other.isChecked();
                    confirm.setEnabled(all);
                });
            }
            confirm.setOnClickListener(view -> {
                dialog.dismiss();
                if (flow != null && flow.physicalCheckConfirmed(SystemClock.elapsedRealtimeNanos())) {
                    log("checklist", "confirmed " + boxes.size() + " items");
                    enterMode();
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
                dialog.dismiss();
                if (flow != null) {
                    flow.physicalCheckCancelled(SystemClock.elapsedRealtimeNanos());
                    enterMode();
                }
            });
        });
        dialog.setOnCancelListener(cancelled -> {
            if (flow != null && flow.getMode() == Mode.PHYSICAL_CHECK) {
                flow.physicalCheckCancelled(SystemClock.elapsedRealtimeNanos());
                enterMode();
            }
        });
        checklistDialog = dialog;
        dialog.show();
    }

    private void dismissChecklist() {
        if (checklistDialog != null) {
            checklistDialog.setOnCancelListener(null);
            checklistDialog.dismiss();
        }
        checklistDialog = null;
    }

    // ---- Demo player callbacks ------------------------------------------------------------

    @Override
    public void onChapterStarted(Chapter chapter, int position, int count) {
        shownChapter = chapter;
        AssemblyPhase displayed=reviewScript==null ? phase : reviewScript.getPhase();
        if(displayed!=null) viewedChapters.add(chapterKey(displayed,chapter));
        persistReview();
        renderInstructionLog();
        activity.showChapterReference(chapter);
        dev.chyken.assembl_ar.workflow.GuidedCheckpoint checkpoint=
                dev.chyken.assembl_ar.workflow.GuidedRecognitionCatalog.checkpointFor(chapter.getSection(),chapter.getCard());
        if(checkpoint!=null) {
            Component component=activity.component(checkpoint.getComponentId());
            if(component!=null) activity.showComponentPreview(component.getId(),component.getName());
        }
        if(reviewScript!=null) {
            stage.setText(activity.getString(R.string.review_phase_step,displayed.getNumber(),position+1,count));
            instruction.setText(chapter.getNarration());
            updateNavigation(); return;
        }
        if (flow != null && (flow.getMode() == Mode.DEMO || flow.getMode() == Mode.ASSEMBLE)) {
            stage.setText(flow.isPreviewing() ? activity.getString(R.string.phase_stage_preview)
                    : activity.getString(R.string.phase_stage_demo, position + 1, count));
            instruction.setText(activity.getString(R.string.phase_chapter_caption, chapter.getTitle(),
                    chapter.getCaption()));
        } else if (flow != null && flow.getMode() == Mode.CORRECTION) {
            instruction.setText(activity.getString(R.string.phase_chapter_caption, chapter.getTitle(),
                    chapter.getCaption()));
        }
        updateNavigation();
        persistSession();
    }

    @Override
    public void onFinished() {
        if(reviewScript!=null) { updateNavigation(); return; }
        if (flow == null) return;
        if (flow.getMode() == Mode.DEMO) {
            flow.demoFinished(SystemClock.elapsedRealtimeNanos());
            enterMode();
        } else {
            renderMode();
        }
    }

    @Override public void onChapterReady(int position) { updateNavigation(); }

    private void renderInstructionLog() {
        StringBuilder text=new StringBuilder();
        for(AssemblyPhase item:AssemblyPhaseCatalog.phases()) {
            for(Chapter chapter:scriptFor(item).getChapters()) {
                if(!viewedChapters.contains(chapterKey(item,chapter))) continue;
                if(text.length()>0) text.append('\n');
                text.append("• ").append(item.getNumber()).append('.').append(chapter.getIndex()+1)
                        .append("  ").append(chapter.getNarration());
            }
        }
        ((TextView)activity.findViewById(R.id.instructionLog)).setText(text);
    }

    void onSpeechStarted(String key,String text) {
        if(isLivePhaseOne() && flow.getMode()==Mode.SCAN_PARTS
                && (key.equals("phase1:bring:"+flow.getGeneration())
                || key.equals("phase1:place:"+flow.getGeneration()))) phaseOneGuide.setInstruction(text);
    }

    void onSpeechFinished(String key) {
        if(gearGuide!=null){gearGuide.onSpeechFinished(key);return;}
        if(driverGuide!=null){driverGuide.onSpeechFinished(key);return;}
        if(wiringGuide!=null){wiringGuide.onSpeechFinished(key);return;}
        if(microcontrollerGuide!=null){microcontrollerGuide.onSpeechFinished(key);return;}
        if(driverWiringGuide!=null){driverWiringGuide.onSpeechFinished(key);return;}
        if(casterTrackerGuide!=null){casterTrackerGuide.onSpeechFinished(key);return;}
        if(sensorWiringGuide!=null){sensorWiringGuide.onSpeechFinished(key);return;}
        if(standoffWheelsGuide!=null){standoffWheelsGuide.onSpeechFinished(key);return;}
        if(extensionBoardGuide!=null){extensionBoardGuide.onSpeechFinished(key);return;}
        if(oledBatteryGuide!=null){oledBatteryGuide.onSpeechFinished(key);return;}
        player.onSpeechFinished(key);
        if(isLivePhaseOne() && flow.getMode()==Mode.SCAN_PARTS
                && key.equals("phase1:bring:"+flow.getGeneration())) {
            String text=activity.getString(R.string.phase_one_place_board);
            phaseOneGuide.setInstruction(text);
            speak(text,"phase1:place:"+flow.getGeneration());
        }
    }

    private boolean isLivePhaseOne() {
        return phase!=null && phase.getNumber()==1 && flow!=null && reviewScript==null;
    }

    private void speakPhaseOneStart() {
        phaseOneGuide.setInstruction(activity.getString(R.string.phase_one_bring_board));
        speak(activity.getString(R.string.phase_one_bring_board),"phase1:bring:"+flow.getGeneration());
    }

    void onVoiceStatusChanged() {
        if(gearGuide!=null)gearGuide.onVoiceStatusChanged();
        else if(driverGuide!=null)driverGuide.onVoiceStatusChanged();
        else if(wiringGuide!=null)wiringGuide.onVoiceStatusChanged();
        else if(microcontrollerGuide!=null)microcontrollerGuide.onVoiceStatusChanged();
        else if(driverWiringGuide!=null)driverWiringGuide.onVoiceStatusChanged();
        else if(casterTrackerGuide!=null)casterTrackerGuide.onVoiceStatusChanged();
        else if(sensorWiringGuide!=null)sensorWiringGuide.onVoiceStatusChanged();
        else if(standoffWheelsGuide!=null)standoffWheelsGuide.onVoiceStatusChanged();
        else if(extensionBoardGuide!=null)extensionBoardGuide.onVoiceStatusChanged();
        else if(oledBatteryGuide!=null)oledBatteryGuide.onVoiceStatusChanged();
        else player.onVoiceModeChanged();
    }

    // ---- Camera requests (camera thread) ----------------------------------------------------

    boolean isAnalysisSuspended() {
        if(gearGuide!=null)return !gearGuide.scanning();
        if(driverGuide!=null)return !driverGuide.scanning();
        if(wiringGuide!=null)return true;
        if(microcontrollerGuide!=null)return !microcontrollerGuide.scanning();
        if(driverWiringGuide!=null)return !driverWiringGuide.scanning();
        if(casterTrackerGuide!=null)return !casterTrackerGuide.scanning();
        if(sensorWiringGuide!=null)return !sensorWiringGuide.scanning();
        if(standoffWheelsGuide!=null)return !standoffWheelsGuide.scanning();
        if(extensionBoardGuide!=null)return !extensionBoardGuide.scanning();
        if(oledBatteryGuide!=null)return !oledBatteryGuide.scanning();
        return plan.kind == RequestPlan.Kind.NONE;
    }

    @Nullable
    private volatile RecognitionRequest lastVisualRequest;
    RecognitionRequest requestFor(CameraCalibration calibration) {
        RecognitionRequest request=buildRequestFor(calibration);
        if(request!=null)lastVisualRequest=request;
        return request;
    }
    private RecognitionRequest buildRequestFor(CameraCalibration calibration) {
        if(gearGuide!=null)return gearGuide.request(calibration);
        if(driverGuide!=null)return driverGuide.request(calibration);
        if(wiringGuide!=null)return null;
        if(microcontrollerGuide!=null)return microcontrollerGuide.request(calibration);
        if(driverWiringGuide!=null)return driverWiringGuide.request(calibration);
        if(casterTrackerGuide!=null)return casterTrackerGuide.request(calibration);
        if(sensorWiringGuide!=null)return sensorWiringGuide.request(calibration);
        if(standoffWheelsGuide!=null)return standoffWheelsGuide.request(calibration);
        if(extensionBoardGuide!=null)return extensionBoardGuide.request(calibration);
        if(oledBatteryGuide!=null)return oledBatteryGuide.request(calibration);
        RequestPlan current = plan;
        switch (current.kind) {
            case INVENTORY:
                return new RecognitionRequest(
                        RecognitionRequest.AssemblyExpectation.component(current.componentId),
                        calibration, null, null, current.count, current.section)
                        .withRequestTag(current.tag);
            case MOTOR_PAIR:
                return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(GEAR_MOTOR),
                        calibration, null, MAIN_BOARD, 2, 2).withRequestTag(current.tag);
            case BRACKET_SLOT:
                return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(MOTOR_BRACKET),
                        calibration, null, MAIN_BOARD, 1, 2)
                        .withGuidedBracketSlot(current.bracketSlot)
                        .withInstalledMotorInspection()
                        .withRequestTag(current.tag);
            case SECTION_PLACEMENT:
                return new RecognitionRequest(
                        RecognitionRequest.AssemblyExpectation.component(current.componentId),
                        calibration, null, current.anchorId, current.count, current.section)
                        .withRequestTag(current.tag);
            case NONE:
            default:
                return null;
        }
    }

    private void planInventory() {
        if (phase == null || flow == null) return;
        List<Part> scanned = phase.getCameraInventory();
        if (inventoryIndex >= scanned.size()) {
            plan = RequestPlan.NONE;
            return;
        }
        Part part = scanned.get(inventoryIndex);
        if (missingReference(part.getComponentId())) return;
        int count = part.getEvidence() == AssemblyPhase.InventoryEvidence.CAMERA_COUNT ? part.getQuantity() : 1;
        plan = new RequestPlan(RequestPlan.Kind.INVENTORY, flow.tag(), part.getComponentId(), count,
                null, currentSection, 0);
    }

    private void planVerification(String group) {
        if (phase == null || flow == null) return;
        activeGroup = group;
        resetGates();
        List<VisualTarget> targets = targetsIn(group);
        VisualTarget first = targets.get(0);
        if (missingReference(first.getComponentId()) || missingReference(first.getAnchorComponentId())) {
            return;
        }
        plan = planFor(first, targets.size(), flow.tag());
    }

    private void planOverlay(VisualTarget target) {
        if (flow == null || missingReference(target.getAnchorComponentId())) {
            plan = RequestPlan.NONE;
            return;
        }
        resetGates();
        plan = planFor(target, targetsIn(target.getProbeGroup()).size(), flow.tag());
    }

    private RequestPlan planFor(VisualTarget target, int groupSize, PhaseFlow.ScanTag tag) {
        switch (target.getKind()) {
            case MOTOR_SLOT:
                return new RequestPlan(RequestPlan.Kind.MOTOR_PAIR, tag, GEAR_MOTOR, 2, MAIN_BOARD, 2, 0);
            case BRACKET_SLOT:
                return new RequestPlan(RequestPlan.Kind.BRACKET_SLOT, tag, MOTOR_BRACKET, 1, MAIN_BOARD,
                        2, target.getBracketSlot());
            case SECTION_PLACEMENT:
            default:
                return new RequestPlan(RequestPlan.Kind.SECTION_PLACEMENT, tag, target.getComponentId(),
                        groupSize, target.getAnchorComponentId(), target.getSection(), 0);
        }
    }

    private boolean missingReference(@Nullable String componentId) {
        Set<String> available = activity.availableRecognitionIds();
        if (componentId == null || available == null || available.contains(componentId)) return false;
        plan = RequestPlan.NONE;
        activity.setBanner(activity.getString(R.string.phase_reference_unavailable,
                componentName(componentId)), false, R.drawable.bg_camera_warning);
        return true;
    }

    private List<VisualTarget> targetsIn(String group) {
        List<VisualTarget> targets = new ArrayList<>();
        if (phase == null) return targets;
        for (VisualTarget target : phase.getTargets()) {
            if (target.getProbeGroup().equals(group)) targets.add(target);
        }
        return targets;
    }

    /** Recognition references finished loading; start any camera plan that was waiting for them. */
    void onRecognitionReady() {
        if(gearGuide!=null){gearGuide.render();return;}
        if(driverGuide!=null){driverGuide.render();return;}
        if(wiringGuide!=null){wiringGuide.render();return;}
        if(microcontrollerGuide!=null){microcontrollerGuide.render();return;}
        if(driverWiringGuide!=null){driverWiringGuide.render();return;}
        if(casterTrackerGuide!=null){casterTrackerGuide.render();return;}
        if(sensorWiringGuide!=null){sensorWiringGuide.render();return;}
        if(standoffWheelsGuide!=null){standoffWheelsGuide.render();return;}
        if(extensionBoardGuide!=null){extensionBoardGuide.render();return;}
        if(oledBatteryGuide!=null){oledBatteryGuide.render();return;}
        if(reviewScript!=null) return;
        if (flow == null) return;
        if (flow.getMode() == Mode.SCAN_PARTS && !flow.isPreviewing()) planInventory();
        else if (flow.getMode() == Mode.VERIFY && activeGroup != null) planVerification(activeGroup);
        showBanner();
    }

    // ---- Results (UI thread) ------------------------------------------------------------------

    void onResult(RecognitionResult result, long evidenceNanos) {
        if (flow == null || phase == null || savingPhase || reviewScript!=null) return;
        if(gearGuide!=null){gearGuide.onResult(result);return;}
        if(driverGuide!=null){driverGuide.onResult(result);return;}
        if(wiringGuide!=null)return;
        if(microcontrollerGuide!=null){microcontrollerGuide.onResult(result);return;}
        if(driverWiringGuide!=null){driverWiringGuide.onResult(result);return;}
        if(casterTrackerGuide!=null){casterTrackerGuide.onResult(result);return;}
        if(sensorWiringGuide!=null){sensorWiringGuide.onResult(result);return;}
        if(standoffWheelsGuide!=null){standoffWheelsGuide.onResult(result);return;}
        if(extensionBoardGuide!=null){extensionBoardGuide.onResult(result);return;}
        if(oledBatteryGuide!=null){oledBatteryGuide.onResult(result);return;}
        Object tag = result.getRequestTag();
        PhaseFlow.ScanTag scanTag = tag instanceof PhaseFlow.ScanTag ? (PhaseFlow.ScanTag) tag : null;
        if (flow.getMode() == Mode.CORRECTION) {
            if (flow.isCurrent(scanTag)) showCorrectionOverlay(result);
            return;
        }
        if (!flow.accept(scanTag, result.getFrameTimestampNanos())) return;
        lastEvidenceNanos = evidenceNanos;
        lastResultUptime = SystemClock.uptimeMillis();
        if (flow.getMode() == Mode.SCAN_PARTS) {
            handleInventory(result, evidenceNanos);
        } else if (flow.getMode() == Mode.VERIFY) {
            handleVerification(result, evidenceNanos);
        }
    }

    void onAnalysisError() {
        if(reviewScript!=null) return;
        if (flow != null && flow.collectsEvidence()) {
            activity.setBanner("Hold steady while recognition recovers…", true, R.drawable.bg_live_pill);
        }
    }

    private void handleInventory(RecognitionResult result, long evidenceNanos) {
        if (phase == null || flow == null) return;
        List<Part> scanned = phase.getCameraInventory();
        if (inventoryIndex >= scanned.size()) return;
        Part part = scanned.get(inventoryIndex);
        String expectedId = part.getComponentId();
        boolean counted = part.getEvidence() == AssemblyPhase.InventoryEvidence.CAMERA_COUNT;
        boolean ambiguous = result.getRejectionEvidence() != null && result.getRejectionEvidence().getReason()
                == RecognitionResult.RejectionReason.IDENTITY_AMBIGUOUS;
        boolean correct = result.hasLocatedComponent() && expectedId.equals(result.getComponentId())
                && (result.getValidationState() == RecognitionResult.ValidationState.EXPECTED_COMPONENT
                || result.getValidationState() == RecognitionResult.ValidationState.RECOGNIZED)
                && !ambiguous
                && (!MAIN_BOARD.equals(expectedId)
                || result.getDetectionMethod() == RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY
                && result.getOrientationClassification()
                != RecognitionResult.OrientationClassification.UNKNOWN
                && result.getOrientationClassification()
                != RecognitionResult.OrientationClassification.MIRRORED);
        boolean boardFaceDown = MAIN_BOARD.equals(expectedId) && result.hasLocatedComponent()
                && MAIN_BOARD.equals(result.getComponentId())
                && result.getOrientationClassification()
                == RecognitionResult.OrientationClassification.MIRRORED;
        boolean mismatch = result.hasLocatedComponent() && !expectedId.equals(result.getComponentId());
        boolean ready;
        float[] corners = activity.normalizeCorners(result.getProjectedCorners());
        if (counted) {
            bracketSetGate.observe(result, evidenceNanos);
            ready = bracketSetGate.isReady();
            List<ArHudView.DetectedPart> parts = new ArrayList<>();
            for (RecognitionResult.InstanceObservation instance : result.getDetectedInstances(expectedId)) {
                float[] outline = activity.normalizeCorners(instance.getProjectedCorners());
                if (outline != null) {
                    parts.add(new ArHudView.DetectedPart(componentName(expectedId), outline,
                            ready ? ArHudView.PartStatus.ACCEPTED : ArHudView.PartStatus.PENDING));
                }
            }
            hud.setDetectedParts(parts);
        } else if (MAIN_BOARD.equals(expectedId)) {
            if (boardFaceDown || mismatch) boardAcquisitionGate.reset();
            boolean repeatedBoard = boardAcquisitionGate.observe(
                    correct, result.getFrameTimestampNanos());
            ready = correct && (repeatedBoard || strongBoardEvidence(result));
            hud.setDetectedParts(null);
        } else {
            if (!correct) identityGate.reset();
            VisualPlacementGate.Observation observed = correct
                    ? identityGate.observe(expectedId, corners,
                            result.getPose() == null ? null : result.getPose().getTranslationVector(),
                            false, .001f, evidenceNanos)
                    : identityGate.observeUnavailable(evidenceNanos);
            ready = correct && observed.isReady();
            hud.setDetectedParts(null);
        }
        if (ready) {
            inventoryDone.put(expectedId, true);
            log("inventory", expectedId + " established");
            inventoryIndex++;
            resetGates();
            hud.update(ArHudView.HudState.MATCHED, componentName(expectedId), corners);
            if (inventoryIndex < scanned.size()) {
                flow.restartEvidence(SystemClock.elapsedRealtimeNanos());
                planInventory();
                renderMode();
                return;
            }
            flow.inventoryComplete(SystemClock.elapsedRealtimeNanos());
            if(phase.getNumber()==1) phaseOneScanConfirmed=true;
            if(!devNavigationActive || phase.getNumber()!=1) {
                PhaseProgressStore.setInventoryComplete(activity, NeobotSeedData.PROJECT_ID,
                        phase.getId(), true);
            }
            if (flow.getMode() == Mode.DEMO) {
                pendingSuccessBanner = activity.getString(R.string.phase_inventory_complete);
            }
            enterMode();
            return;
        }
        hud.update(boardFaceDown ? ArHudView.HudState.WRONG_ORIENTATION
                        : mismatch && !ambiguous ? ArHudView.HudState.MISMATCH
                        : correct ? ArHudView.HudState.TRACKING : ArHudView.HudState.SEARCHING,
                mismatch ? safeName(result) : componentName(expectedId),
                correct || mismatch || boardFaceDown ? corners : null);
        String prompt = boardFaceDown ? activity.getString(R.string.main_board_turn_front_up)
                : MAIN_BOARD.equals(expectedId) && NeobotSeedData.EXTENSION_BOARD.equals(result.getComponentId())
                ? activity.getString(R.string.main_board_wrong_component)
                : mismatch && !ambiguous
                ? activity.getString(R.string.demo_wrong_part, safeName(result), componentName(expectedId))
                : ambiguous ? activity.getString(R.string.demo_ambiguous_part, componentName(expectedId))
                : counted ? activity.getString(R.string.phase_scan_count_progress,
                        bracketSetGate.getVisibleCount(), part.getQuantity(),
                        pluralName(expectedId))
                : correct && MAIN_BOARD.equals(expectedId)
                ? activity.getString(R.string.main_board_confirming)
                : correct ? activity.getString(R.string.demo_scan_hold, componentName(expectedId))
                : MAIN_BOARD.equals(expectedId) ? activity.getString(R.string.main_board_surface_hint)
                : activity.scanFramingHint(expectedId, componentName(expectedId));
        activity.setBanner(prompt, !boardFaceDown && !(mismatch && !ambiguous) && !ambiguous,
                boardFaceDown || mismatch && !ambiguous
                        ? R.drawable.bg_danger : R.drawable.bg_live_pill);
        renderInventory();
    }

    private static boolean strongBoardEvidence(RecognitionResult result) {
        return result.getDetectionMethod() == RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY
                && result.getPose() != null
                && result.getInlierCount() >= 24
                && result.getInlierRatio() >= 0.65
                && result.getPose().getReprojectionErrorPx() <= 4.5;
    }

    private void handleVerification(RecognitionResult result, long evidenceNanos) {
        if (phase == null || flow == null || activeGroup == null) return;
        if (session == null) session = new PhaseVerificationSession(phase, evidenceNanos);
        List<VisualTarget> targets = targetsIn(activeGroup);
        List<Observation> observations = new ArrayList<>();
        String progressMessage;
        switch (targets.get(0).getKind()) {
            case MOTOR_SLOT:
                progressMessage = observeMotors(result, evidenceNanos, targets, observations);
                break;
            case BRACKET_SLOT:
                progressMessage = observeBracket(result, evidenceNanos, targets.get(0), observations);
                break;
            case SECTION_PLACEMENT:
            default:
                progressMessage = observePlacement(result, evidenceNanos, targets, observations);
                break;
        }
        Verdict verdict = session.observe(evidenceNanos, observations);
        handleVerdict(verdict, progressMessage);
    }

    private void handleVerdict(Verdict verdict, @Nullable String progressMessage) {
        if (flow == null || phase == null || session == null) return;
        long now = SystemClock.elapsedRealtimeNanos();
        switch (verdict) {
            case PASSED:
                log("verify", "passed");
                handler.removeCallbacks(budgetPoll);
                flow.verificationPassed(now);
                activity.setBanner(activity.getString(R.string.phase_verify_passed), false, R.drawable.bg_success);
                speak(activity.getString(R.string.phase_verify_passed), "passed:" + flow.getGeneration());
                enterMode();
                return;
            case CORRECTION_NEEDED:
                TargetResult correction = session.getCorrection();
                log("verify", "correction " + (correction == null ? "" : correction.getTarget().getId()));
                handler.removeCallbacks(budgetPoll);
                if (correction != null) flow.correctionNeeded(correction.getTarget().getId(), now);
                enterMode();
                return;
            case INCONCLUSIVE:
                log("verify", "inconclusive " + resultsSummary());
                handler.removeCallbacks(budgetPoll);
                flow.verificationInconclusive(now);
                activity.setBanner(activity.getString(R.string.phase_verify_inconclusive), false,
                        R.drawable.bg_camera_warning);
                speak(activity.getString(R.string.phase_verify_inconclusive) + ". " + unresolvedSummary(),
                        "inconclusive:" + flow.getGeneration());
                enterMode();
                return;
            case IN_PROGRESS:
            default:
                String group = session.getActiveGroup();
                if (group != null && !group.equals(activeGroup)) {
                    flow.restartEvidence(now);
                    planVerification(group);
                    activity.resetAnalyzerTracking();
                    progressMessage = groupPrompt(group);
                }
                if (progressMessage != null) {
                    activity.setBanner(progressMessage, true, R.drawable.bg_live_pill);
                }
                renderResults();
        }
    }

    private void pollVerificationBudget() {
        if (flow == null || flow.getMode() != Mode.VERIFY) return;
        long idleMillis = SystemClock.uptimeMillis() - (session == null ? verifyStartedUptime : lastResultUptime);
        if (session == null) {
            if (idleMillis * 1_000_000L > PhaseVerificationSession.GROUP_BUDGET_NANOS && phase != null) {
                session = new PhaseVerificationSession(phase, 0L);
                handleVerdict(session.abandon(activity.getString(R.string.phase_verify_start)), null);
                return;
            }
        } else if (lastEvidenceNanos != Long.MIN_VALUE) {
            handleVerdict(session.checkBudget(lastEvidenceNanos + idleMillis * 1_000_000L), null);
            if (flow.getMode() != Mode.VERIFY) return;
        }
        handler.postDelayed(budgetPoll, BUDGET_POLL_MILLIS);
    }

    private String observeMotors(RecognitionResult result, long evidenceNanos, List<VisualTarget> targets,
                                 List<Observation> observations) {
        RecognitionResult.SpatialAnchor base = result.getSpatialAnchor();
        boolean hasBase = base != null && MAIN_BOARD.equals(base.getComponentId()) && base.getPose() != null;
        float[] corners = hasBase ? activity.normalizeCorners(base.getProjectedCorners()) : null;
        VisualPlacementGate.Observation lock = hasBase
                ? anchorGate.observe(base.getComponentId(), corners, base.getPose().getTranslationVector(),
                        true, 0.010f, evidenceNanos)
                : anchorGate.observeUnavailable(evidenceNanos);
        if (lock.getStableFrames() <= 1 && previousAnchorFrames > 1) motorGate.reset();
        previousAnchorFrames = lock.getStableFrames();
        MotorAssemblyObservation motors = result.getMotorAssembly();
        boolean quality = motors != null && motors.getQuality() == MotorAssemblyObservation.Quality.GOOD;
        boolean left = quality && motors.getLeft().isAccepted();
        boolean right = quality && motors.getRight().isAccepted();
        MotorAssemblyGate.Decision decision = motorGate.observe(evidenceNanos,
                hasBase && lock.isReady(), quality, left, right);
        boolean pairReady = decision.isReady() && !result.isAnchorTracked();
        boolean anchorValid = hasBase && lock.isFrameValid();
        for (VisualTarget target : targets) {
            observations.add(PhaseTargetClassifier.motorSlot(target, motors,
                    "left_motor".equals(target.getId()), anchorValid, pairReady));
        }
        if (anchorValid) {
            hud.update(pairReady ? ArHudView.HudState.MATCHED : ArHudView.HudState.TRACKING,
                    "Base board", corners);
            if (motors != null) {
                hud.setAssemblyTargets(activity.normalizeCorners(motors.getLeft().getProjectedCorners()), left,
                        activity.normalizeCorners(motors.getRight().getProjectedCorners()), right);
            }
            if (lock.isReady() && base.getPose() != null) showGuidance(2, GEAR_MOTOR, base);
        } else {
            hud.reset();
            activity.hideGuidance();
        }
        if (!anchorValid) return activity.getString(R.string.phase_verify_start);
        if (!quality) return PhaseTargetClassifier.qualityMessage(motors == null
                ? MotorAssemblyObservation.Quality.ANCHOR_UNAVAILABLE : motors.getQuality());
        return "Checking both motor positions · " + Math.min(decision.getAcceptedFrames(),
                decision.getRequiredFrames()) + "/" + decision.getRequiredFrames();
    }

    private String observeBracket(RecognitionResult result, long evidenceNanos, VisualTarget target,
                                  List<Observation> observations) {
        RecognitionResult.SpatialAnchor anchor = result.getSpatialAnchor();
        boolean hasAnchor = anchor != null && MAIN_BOARD.equals(anchor.getComponentId());
        VisualPlacementGate.Observation lock = hasAnchor
                ? anchorGate.observe(anchor.getComponentId(), activity.normalizeCorners(anchor.getProjectedCorners()),
                        anchor.getPose() == null ? null : anchor.getPose().getTranslationVector(),
                        true, 0.010f, evidenceNanos)
                : anchorGate.observeUnavailable(evidenceNanos);
        boolean anchorValid = hasAnchor && lock.isFrameValid();
        boolean anchorReady = lock.isReady() && !result.isAnchorTracked();
        boolean expected = MOTOR_BRACKET.equals(result.getComponentId())
                && (result.getValidationState() == RecognitionResult.ValidationState.EXPECTED_COMPONENT
                || result.getValidationState() == RecognitionResult.ValidationState.RECOGNIZED);
        VisualPlacementGate.Observation part = expected
                ? placementGate.observe(MOTOR_BRACKET, activity.normalizeCorners(result.getProjectedCorners()),
                        result.getPose() == null ? null : result.getPose().getTranslationVector(),
                        true, 0.001f, evidenceNanos)
                : placementGate.observeUnavailable(evidenceNanos);
        List<List<VisionPoint>> quads = AssemblyActivity.expectedInstanceQuads(result, MOTOR_BRACKET);
        if (expected && quads.isEmpty() && result.getProjectedCorners().size() == 4) {
            quads.add(result.getProjectedCorners());
        }
        ChassisRelativePlacementGate slotGate =
                ChassisRelativePlacementGate.forMotorBracketSlot(target.getBracketSlot());
        ChassisRelativePlacementGate.Decision relative = anchorValid
                ? slotGate.evaluate(anchor.getProjectedCorners(), quads) : null;
        boolean inSlot = expected && relative != null && relative.isAccepted();
        boolean ready = repeatedGate.observe(inSlot && part.isReady() && anchorReady);
        observations.add(PhaseTargetClassifier.bracketSlot(target, anchorValid, inSlot, ready));
        // Installed motors seen in the same frame can revoke an accepted motor if one was removed.
        MotorAssemblyObservation motors = result.getMotorAssembly();
        if (anchorValid && motors != null && session != null) {
            for (VisualTarget motor : phase.getTargets()) {
                if (motor.getKind() != AssemblyPhase.TargetKind.MOTOR_SLOT) continue;
                TargetResult earlier = session.getResult(motor.getId());
                Observation seen = PhaseTargetClassifier.motorSlot(motor, motors,
                        "left_motor".equals(motor.getId()), true, false);
                if (earlier != null && earlier.isAccepted() && seen.getOutcome() == Outcome.MISSING) {
                    observations.add(seen);
                }
            }
        }
        if (anchorValid) {
            List<ChassisRelativePlacementGate.TargetProjection> projected =
                    slotGate.projectTargets(anchor.getProjectedCorners());
            hud.setAssemblyTargets(projected.isEmpty() ? null
                    : activity.normalizeCorners(projected.get(0).getCorners()), ready, null, false);
            hud.update(ready ? ArHudView.HudState.MATCHED : ArHudView.HudState.TRACKING,
                    target.getLabel(), expected ? activity.normalizeCorners(result.getProjectedCorners()) : null);
        } else {
            hud.reset();
        }
        activity.hideGuidance();
        if (!anchorValid) return activity.getString(R.string.phase_verify_start);
        if (!inSlot) return groupPrompt(target.getProbeGroup());
        return "Hold steady on " + target.getLabel() + " · " + repeatedGate.getAcceptedFrames() + "/"
                + repeatedGate.getRequiredFrames();
    }

    private String observePlacement(RecognitionResult result, long evidenceNanos, List<VisualTarget> targets,
                                    List<Observation> observations) {
        VisualTarget first = targets.get(0);
        int section = first.getSection();
        AssemblyStep sectionStep = step(section);
        String expectedId = first.getComponentId();
        RecognitionResult.SpatialAnchor anchor = result.getSpatialAnchor();
        boolean hasAnchor = anchor != null && first.getAnchorComponentId().equals(anchor.getComponentId())
                && anchor.getPose() != null;
        VisualPlacementGate.Observation lock = hasAnchor
                ? anchorGate.observe(anchor.getComponentId(), activity.normalizeCorners(anchor.getProjectedCorners()),
                        anchor.getPose().getTranslationVector(), true, 0.010f, evidenceNanos)
                : anchorGate.observeUnavailable(evidenceNanos);
        boolean anchorValid = hasAnchor && lock.isFrameValid();
        boolean anchorReady = lock.isReady() && !result.isAnchorTracked();
        RecognitionResult.ValidationState validation = result.getValidationState();
        boolean expected = expectedId.equals(result.getComponentId())
                && result.getDetectionMethod() != RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY;
        boolean geometryOnly = AssemblyActivity.isGeometryOnlyResult(result);
        if (expected && sectionStep != null && AssemblyActivity.orientationSensitive(sectionStep) && !geometryOnly
                && (result.getOrientationClassification() == RecognitionResult.OrientationClassification.UNKNOWN
                || !Double.isFinite(result.getOrientationDegrees()))
                && validation != RecognitionResult.ValidationState.WRONG_ORIENTATION) {
            validation = RecognitionResult.ValidationState.ORIENTATION_UNCONFIRMED;
        }
        List<List<VisionPoint>> quads = expected
                ? AssemblyActivity.expectedInstanceQuads(result, expectedId) : new ArrayList<>();
        if (expected && quads.isEmpty() && result.getProjectedCorners().size() == 4) {
            quads.add(result.getProjectedCorners());
        }
        ChassisRelativePlacementGate gate = ChassisRelativePlacementGate.forNeobotStep(section);
        VisualPlacementGate.Observation placement = expected
                ? placementGate.observe(expectedId, activity.normalizeCorners(result.getProjectedCorners()),
                        result.getPose() == null ? null : result.getPose().getTranslationVector(),
                        sectionStep == null || AssemblyActivity.spatialPoseRequired(sectionStep),
                        sectionStep == null ? 0.02f : AssemblyActivity.minimumProjectedArea(sectionStep),
                        evidenceNanos)
                : placementGate.observeUnavailable(evidenceNanos);
        ChassisRelativePlacementGate.Decision decision = anchorValid ? gate.evaluate(anchor.getProjectedCorners(), quads) : null;
        boolean directionValid = validation != RecognitionResult.ValidationState.WRONG_ORIENTATION
                && validation != RecognitionResult.ValidationState.ORIENTATION_UNCONFIRMED
                && validation != RecognitionResult.ValidationState.POLARITY_ERROR
                && validation != RecognitionResult.ValidationState.POLARITY_UNCONFIRMED;
        boolean groupReady = repeatedGate.observe(expected && directionValid && anchorReady
                && placement.isReady() && decision != null && decision.isAccepted());
        String detectedId = validation == RecognitionResult.ValidationState.NO_COMPONENT
                || result.getDetectionMethod() == RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY
                || first.getAnchorComponentId().equals(result.getComponentId())
                ? null : result.getComponentId();
        observations.addAll(PhaseTargetClassifier.sectionPlacement(gate, targets,
                new PhaseTargetClassifier.PlacementFrame(anchorValid ? anchor.getProjectedCorners() : null,
                        detectedId, validation, quads, result.getProjectedCorners()), groupReady));
        if (anchorValid) {
            List<ChassisRelativePlacementGate.TargetProjection> projected = gate.projectTargets(anchor.getProjectedCorners());
            hud.setAssemblyTargets(projected.isEmpty() ? null : activity.normalizeCorners(projected.get(0).getCorners()),
                    groupReady, projected.size() < 2 ? null : activity.normalizeCorners(projected.get(1).getCorners()),
                    groupReady);
            hud.update(groupReady ? ArHudView.HudState.MATCHED : ArHudView.HudState.TRACKING,
                    componentName(expectedId), expected ? activity.normalizeCorners(result.getProjectedCorners()) : null);
            if (lock.isReady() && sectionStep != null) showGuidance(section, expectedId, anchor);
        } else {
            hud.reset();
            activity.hideGuidance();
        }
        if (!anchorValid) {
            return PhaseTargetClassifier.anchorMissingPrompt(componentName(first.getAnchorComponentId()),
                    first.getLabel(), expectedId);
        }
        if (decision != null && !decision.isAccepted() && expected) return decision.getMessage();
        if (!expected) return groupPrompt(first.getProbeGroup());
        return "Hold steady · " + repeatedGate.getAcceptedFrames() + "/" + repeatedGate.getRequiredFrames();
    }

    private void showCorrectionOverlay(RecognitionResult result) {
        List<VisualTarget> targets = correctionOverlayTargets;
        if (targets == null || targets.isEmpty()) return;
        VisualTarget target = targets.get(0);
        RecognitionResult.SpatialAnchor anchor = result.getSpatialAnchor();
        if (anchor == null || !target.getAnchorComponentId().equals(anchor.getComponentId())) {
            hud.clearAssemblyTargets();
            return;
        }
        ChassisRelativePlacementGate gate;
        switch (target.getKind()) {
            case BRACKET_SLOT:
                gate = ChassisRelativePlacementGate.forMotorBracketSlot(target.getBracketSlot());
                break;
            case MOTOR_SLOT:
                gate = ChassisRelativePlacementGate.forNeobotStep(2).selectTargets(target.getPlacementTargetId());
                break;
            case SECTION_PLACEMENT:
            default:
                gate = ChassisRelativePlacementGate.forNeobotStep(target.getSection())
                        .selectTargets(target.getPlacementTargetId());
                break;
        }
        List<ChassisRelativePlacementGate.TargetProjection> projected = gate.projectTargets(anchor.getProjectedCorners());
        if (projected.isEmpty()) return;
        float[] outline = activity.normalizeCorners(projected.get(0).getCorners());
        if (outline == null) return;
        String label = "Fix: " + target.getLabel();
        hud.setDetectedParts(Collections.singletonList(
                new ArHudView.DetectedPart(label, outline, ArHudView.PartStatus.INVALID)));
        hud.update(lastCorrection != null && lastCorrection.getOutcome() == Outcome.WRONG_ORIENTATION
                ? ArHudView.HudState.WRONG_ORIENTATION : ArHudView.HudState.MISMATCH, label, outline);
    }

    private void showGuidance(int section, String componentId, RecognitionResult.SpatialAnchor anchor) {
        AssemblyStep sectionStep = step(section);
        if (sectionStep == null || anchor.getPose() == null) return;
        activity.showGuidance(NeobotGuidanceFactory.create(sectionStep, componentId,
                anchor.getPose().getOpenGlModelViewMatrix()));
    }

    // ---- Lifecycle --------------------------------------------------------------------------

    void onPause() {
        persistSession();
        if(phaseOneRescanDialog!=null){phaseOneRescanDialog.dismiss();phaseOneRescanDialog=null;}
        handler.removeCallbacks(budgetPoll);
        if(gearGuide!=null){gearGuide.pause();hud.reset();return;}
        if(driverGuide!=null){driverGuide.pause();hud.reset();return;}
        if(wiringGuide!=null){wiringGuide.pause();hud.reset();return;}
        if(microcontrollerGuide!=null){microcontrollerGuide.pause();hud.reset();return;}
        if(driverWiringGuide!=null){driverWiringGuide.pause();hud.reset();return;}
        if(casterTrackerGuide!=null){casterTrackerGuide.pause();hud.reset();return;}
        if(sensorWiringGuide!=null){sensorWiringGuide.pause();hud.reset();return;}
        if(standoffWheelsGuide!=null){standoffWheelsGuide.pause();hud.reset();return;}
        if(extensionBoardGuide!=null){extensionBoardGuide.pause();hud.reset();return;}
        if(oledBatteryGuide!=null){oledBatteryGuide.pause();hud.reset();return;}
        player.pause();
        if (flow != null) {
            Mode before = flow.getMode();
            flow.interrupt(SystemClock.elapsedRealtimeNanos());
            if (before == Mode.VERIFY) {
                log("interrupt", "scan discarded by pause");
                plan = RequestPlan.NONE;
                session = null;
            }
            if (before == Mode.SCAN_PARTS) resetGates();
        }
        hud.reset();
    }

    void onResume() {
        if(flow==null){
            if(welcomeDialog!=null&&welcomeDialog.isShowing()&&welcomeDialog.findViewById(R.id.welcomeStartButton)!=null)
                activity.voiceGuide().speak(activity.getString(R.string.welcome_assembly_body),"welcome:assembly");
            return;
        }
        if(gearGuide!=null){gearGuide.resume();return;}
        if(driverGuide!=null){driverGuide.resume();return;}
        if(wiringGuide!=null){wiringGuide.resume();return;}
        if(microcontrollerGuide!=null){microcontrollerGuide.resume();return;}
        if(driverWiringGuide!=null){driverWiringGuide.resume();return;}
        if(casterTrackerGuide!=null){casterTrackerGuide.resume();return;}
        if(sensorWiringGuide!=null){sensorWiringGuide.resume();return;}
        if(standoffWheelsGuide!=null){standoffWheelsGuide.resume();return;}
        if(extensionBoardGuide!=null){extensionBoardGuide.resume();return;}
        if(oledBatteryGuide!=null){oledBatteryGuide.resume();return;}
        if(reviewScript!=null) { player.resume(); return; }
        flow.resume(SystemClock.elapsedRealtimeNanos());
        Mode mode = flow.getMode();
        if (mode == Mode.SCAN_PARTS) {
            resetGates();
            planInventory();
            if(isLivePhaseOne()) speakPhaseOneStart();
        } else if (mode == Mode.DEMO || mode == Mode.CORRECTION) {
            player.resume();
        }
        renderMode();
    }

    void onDestroy() {
        if(phaseOneRescanDialog!=null){phaseOneRescanDialog.dismiss();phaseOneRescanDialog=null;}
        if(welcomeDialog!=null){welcomeDialog.dismiss();welcomeDialog=null;}
        if(gearGuide!=null){gearGuide.close();gearGuide=null;}
        if(driverGuide!=null){driverGuide.close();driverGuide=null;}
        if(wiringGuide!=null){wiringGuide.close();wiringGuide=null;}
        if(microcontrollerGuide!=null){microcontrollerGuide.close();microcontrollerGuide=null;}
        if(driverWiringGuide!=null){driverWiringGuide.close();driverWiringGuide=null;}
        if(casterTrackerGuide!=null){casterTrackerGuide.close();casterTrackerGuide=null;}
        if(sensorWiringGuide!=null){sensorWiringGuide.close();sensorWiringGuide=null;}
        if(standoffWheelsGuide!=null){standoffWheelsGuide.close();standoffWheelsGuide=null;}
        if(extensionBoardGuide!=null){extensionBoardGuide.close();extensionBoardGuide=null;}
        if(oledBatteryGuide!=null){oledBatteryGuide.close();oledBatteryGuide=null;}
        handler.removeCallbacksAndMessages(null);
        dismissChecklist();
        player.release();
        plan = RequestPlan.NONE;
    }

    /** Banner for the current mode, used after camera start-up and recovery. */
    void showBanner() {
        if(gearGuide!=null){gearGuide.render();return;}
        if(driverGuide!=null){driverGuide.render();return;}
        if(wiringGuide!=null){wiringGuide.render();return;}
        if(microcontrollerGuide!=null){microcontrollerGuide.render();return;}
        if(driverWiringGuide!=null){driverWiringGuide.render();return;}
        if(casterTrackerGuide!=null){casterTrackerGuide.render();return;}
        if(sensorWiringGuide!=null){sensorWiringGuide.render();return;}
        if(standoffWheelsGuide!=null){standoffWheelsGuide.render();return;}
        if(extensionBoardGuide!=null){extensionBoardGuide.render();return;}
        if(oledBatteryGuide!=null){oledBatteryGuide.render();return;}
        if(reviewScript!=null) {
            activity.setBanner(activity.getString(R.string.review_preserves_progress),false,R.drawable.bg_live_pill);
            return;
        }
        if (flow == null || phase == null) return;
        if (pendingSuccessBanner != null) {
            activity.setBanner(pendingSuccessBanner, false, R.drawable.bg_success);
            pendingSuccessBanner = null;
            return;
        }
        switch (flow.getMode()) {
            case SCAN_PARTS:
                if (!flow.isPreviewing()) {
                    activity.setBanner(scanPrompt(), true, R.drawable.bg_live_pill);
                }
                break;
            case DEMO:
            case ASSEMBLE:
                activity.setBanner(activity.getString(flow.getMode() == Mode.DEMO
                                ? R.string.demo_stage_follow : R.string.phase_assemble_banner),
                        false, R.drawable.bg_live_pill);
                break;
            case VERIFY:
                activity.setBanner(activeGroup == null ? activity.getString(R.string.phase_verify_start)
                        : groupPrompt(activeGroup), true, R.drawable.bg_live_pill);
                break;
            case CORRECTION:
                if (lastCorrection != null) {
                    activity.setBanner(activity.getString(R.string.phase_correction_banner,
                            PhaseDemoScript.resultLine(lastCorrection)), false, R.drawable.bg_danger);
                }
                break;
            case PHYSICAL_CHECK:
                activity.setBanner(activity.getString(R.string.phase_verify_passed), false, R.drawable.bg_success);
                break;
            case COMPLETE:
                if(phase.getNumber()==1) activity.setBanner(activity.getString(R.string.phase_one_complete),
                        false,R.drawable.bg_success);
            default:
                break;
        }
    }

    // ---- Rendering ---------------------------------------------------------------------------

    private void renderStatic() {
        if (phase == null || script == null) return;
        int total = AssemblyPhaseCatalog.phases().size();
        int percent = Math.round((phase.getNumber() - 1) * 100f / total);
        stepCounter.setText(activity.getString(R.string.phase_counter, phase.getNumber(), total));
        List<Integer> sections = phase.getSections();
        String sectionText = sections.size() == 1
                ? activity.getString(R.string.phase_sections_one, sections.get(0))
                : activity.getString(R.string.phase_sections_many, joinSections(sections));
        progressLabel.setText(activity.getString(R.string.phase_progress, sectionText,
                script.getChapters().size(), percent));
        progress.setProgressCompat(percent, true);
        title.setText(phase.getTitle());
        List<String> warnings = script.getWarnings();
        leadingContext.setVisibility(warnings.isEmpty() ? View.GONE : View.VISIBLE);
        leadingContext.setText(String.join(" ", warnings));
        leadingContext.setTextColor(ContextCompat.getColor(activity, R.color.hud_warning));
        StringBuilder details = new StringBuilder(phase.getSummary()).append("\n\n")
                .append(script.detailsText()).append("\n\nDirect checks after Scan");
        for (String check : phase.getDirectChecks()) details.append("\n☐ ").append(check);
        ((TextView) activity.findViewById(R.id.actionFullInstructions)).setText(details.toString());
        ((TextView) activity.findViewById(R.id.requiredComponentLabel)).setText(inventoryText(false));
        Chapter last = script.getFinalChapter();
        if (last != null) activity.showChapterReference(last);
        renderReferencePart();
    }

    private void renderMode() {
        if(gearGuide!=null){gearGuide.render();return;}
        if(driverGuide!=null){driverGuide.render();return;}
        if(wiringGuide!=null){wiringGuide.render();return;}
        if(microcontrollerGuide!=null){microcontrollerGuide.render();return;}
        if(driverWiringGuide!=null){driverWiringGuide.render();return;}
        if(casterTrackerGuide!=null){casterTrackerGuide.render();return;}
        if(sensorWiringGuide!=null){sensorWiringGuide.render();return;}
        if(standoffWheelsGuide!=null){standoffWheelsGuide.render();return;}
        if(extensionBoardGuide!=null){extensionBoardGuide.render();return;}
        if(oledBatteryGuide!=null){oledBatteryGuide.render();return;}
        if (flow == null || phase == null) return;
        Mode mode = flow.getMode();
        hud.setScanningEnabled(flow.collectsEvidence()&&!savingPhase&&reviewScript==null&&activity.isActivityResumed());
        if(isLivePhaseOne())((dev.chyken.assembl_ar.ui.TargetAlignmentBoxView)activity.findViewById(R.id.targetAlignmentBox))
                .showGuide(mode==Mode.SCAN_PARTS?phaseOneGuideAsset:null);
        if(isLivePhaseOne()) {
            phaseOneGuide.setScanned(mode==Mode.COMPLETE,savingPhase,!phaseOneScanConfirmed);
            if(mode==Mode.COMPLETE && phaseOneScanConfirmed && hud.getHudState()!=ArHudView.HudState.MATCHED)
                hud.update(ArHudView.HudState.MATCHED,componentName(MAIN_BOARD),null);
        }
        switch (mode) {
            case SCAN_PARTS:
                stage.setText(R.string.phase_stage_scan);
                instruction.setText(phase.getSummary());
                break;
            case DEMO:
                if (flow.isPreviewing()) stage.setText(R.string.phase_stage_preview);
                break;
            case ASSEMBLE:
                stage.setText(R.string.phase_stage_assemble);
                instruction.setText(phase.hasVisualTargets() ? R.string.phase_assemble_instruction
                        : R.string.phase_assemble_instruction_no_camera);
                break;
            case VERIFY:
                stage.setText(R.string.phase_stage_verify);
                instruction.setText(R.string.phase_verify_start);
                break;
            case CORRECTION:
                stage.setText(activity.getString(R.string.phase_stage_correction, lastCorrection == null
                        ? "" : lastCorrection.getTarget().getLabel()));
                instruction.setText(lastCorrection == null ? activity.getString(R.string.phase_correction_instruction)
                        : PhaseDemoScript.correctionNarration(lastCorrection));
                break;
            case PHYSICAL_CHECK:
                stage.setText(R.string.phase_stage_checklist);
                break;
            case COMPLETE:
                stage.setText(R.string.phase_stage_complete);
                break;
        }
        replayButton.setText(mode == Mode.SCAN_PARTS ? R.string.phase_preview_demo : R.string.replay_demo);
        replayButton.setEnabled(mode != Mode.PHYSICAL_CHECK && mode != Mode.COMPLETE && !savingPhase);
        scanButton.setVisibility(flow.canScan() ? View.VISIBLE : View.GONE);
        scanButton.setText(phase.hasVisualTargets() ? R.string.phase_scan_assembly : R.string.phase_check_work);
        checklistButton.setText(R.string.phase_open_checklist);
        checklistButton.setEnabled(true);
        checklistButton.setVisibility(mode == Mode.PHYSICAL_CHECK ? View.VISIBLE : View.GONE);
        renderInventory();
        renderResults();
        updateNavigation();
        showBanner();
    }

    private void updateNavigation() {
        if (flow == null || script == null) return;
        Mode mode = flow.getMode();
        boolean reviewing=reviewScript!=null;
        boolean browsable = reviewing || mode==Mode.DEMO || mode==Mode.ASSEMBLE || mode==Mode.CORRECTION;
        navigationRow.setVisibility(View.VISIBLE);
        nextButton.setVisibility(View.VISIBLE);
        int position = shownChapter==null ? 0 : shownChapter.getIndex();
        previousButton.setEnabled(!savingPhase && (position>0 && browsable
                || (reviewing ? reviewScript.getPhase().getNumber() : phase.getNumber())>1));
        previousButton.setAlpha(previousButton.isEnabled() ? 1f : 0.4f);
        nextButton.setEnabled(!savingPhase && (mode==Mode.COMPLETE && (devNavigationActive || phase.getNumber()==1)
                && phase.getNumber()<AssemblyPhaseCatalog.phases().size()
                || browsable && (reviewing
                || position<script.getChapters().size()-1 && position<unlockedChapter())));
        nextButton.setAlpha(nextButton.isEnabled() ? 1f : 0.4f);
        doneChapterButton.setVisibility(!reviewing && mode==Mode.DEMO ? View.VISIBLE : View.GONE);
        doneChapterButton.setEnabled(!savingPhase && player.isChapterReady());
    }

    private void renderInventory() {
        if (phase == null) return;
        renderReferencePart();
        inventoryView.setVisibility(View.VISIBLE);
        inventoryView.setText(inventoryText(true));
    }

    private String inventoryText(boolean heading) {
        if (phase == null) return "";
        StringBuilder text = new StringBuilder();
        if (heading) text.append(activity.getString(R.string.phase_inventory_title));
        List<Part> scanned = phase.getCameraInventory();
        boolean scanning = flow != null && flow.getMode() == Mode.SCAN_PARTS && !flow.isPreviewing();
        boolean inventoryComplete = flow != null && flow.isInventoryComplete();
        for (Part part : phase.getParts()) {
            if (text.length() > 0) text.append('\n');
            String name = partName(part);
            switch (part.getRole()) {
                case INSTALLED_CONTEXT:
                    text.append(activity.getString(R.string.phase_inventory_context, name));
                    continue;
                case TOOL_OR_CONSUMABLE:
                    text.append(activity.getString(R.string.phase_inventory_tool, name));
                    continue;
                case NEW_PART:
                default:
                    break;
            }
            if (!part.isCameraScanned()) {
                text.append(activity.getString(R.string.phase_inventory_checklist, part.getQuantity(), name));
            } else if (inventoryComplete || Boolean.TRUE.equals(inventoryDone.get(part.getComponentId()))) {
                boolean counted = part.getEvidence() == AssemblyPhase.InventoryEvidence.CAMERA_COUNT;
                text.append(activity.getString(counted ? R.string.phase_inventory_counted
                        : part.getQuantity() > 1 ? R.string.phase_inventory_identified_uncounted
                        : R.string.phase_inventory_identified, part.getQuantity(), name));
            } else if (scanning && scanned.indexOf(part) == inventoryIndex) {
                text.append(activity.getString(R.string.phase_inventory_scanning, part.getQuantity(), name));
            } else {
                text.append(activity.getString(R.string.phase_inventory_waiting, part.getQuantity(), name));
            }
        }
        return text.toString();
    }

    private void renderResults() {
        if (session == null || flow == null || flow.getMode() == Mode.SCAN_PARTS
                || flow.getMode() == Mode.DEMO && session.getVerdict() == Verdict.IN_PROGRESS) {
            resultsView.setVisibility(View.GONE);
            return;
        }
        StringBuilder text = new StringBuilder();
        for (TargetResult result : session.getResults()) {
            if (text.length() > 0) text.append('\n');
            text.append(PhaseDemoScript.resultLine(result));
        }
        resultsView.setVisibility(View.VISIBLE);
        resultsView.setText(text.toString());
    }

    private void renderReferencePart() {
        Part part = referencePart();
        View reference = activity.findViewById(R.id.requiredPartReference);
        Component component = part == null ? null : activity.component(part.getComponentId());
        reference.setVisibility(component == null ? View.GONE : View.VISIBLE);
        if (component == null) return;
        String key=component.getId()+":"+part.getQuantity();
        if(key.equals(renderedReferenceKey)) return;
        renderedReferenceKey=key;
        activity.showComponentPreview(component.getId(),component.getName());
        ((TextView) activity.findViewById(R.id.requiredPartName)).setText(component.getName());
        ((TextView) activity.findViewById(R.id.requiredPartQuantity)).setText(
                activity.getString(part.getEvidence() == AssemblyPhase.InventoryEvidence.CAMERA_COUNT
                        ? R.string.required_part_set_quantity : R.string.phase_required_part_quantity,
                        part.getQuantity()));
        ImageView thumbnail = activity.findViewById(R.id.requiredPartThumbnail);
        thumbnail.setContentDescription(component.getName());
        AssetImageLoader.load(thumbnail, activity.partReferenceAsset(component));
        reference.setContentDescription(activity.getString(R.string.required_part_preview_description,
                component.getName(), part.getQuantity()));
    }

    /** The first new part of the phase: what the learner mainly works with. */
    @Nullable
    private Part referencePart() {
        if (phase == null) return null;
        if(flow!=null && flow.getMode()==Mode.SCAN_PARTS && !flow.isPreviewing()
                && inventoryIndex<phase.getCameraInventory().size()) {
            return phase.getCameraInventory().get(inventoryIndex);
        }
        for (Part part : phase.getParts()) {
            if (part.getRole() == AssemblyPhase.PartRole.NEW_PART) return part;
        }
        return phase.getParts().isEmpty() ? null : phase.getParts().get(0);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private String scanPrompt() {
        if (phase == null) return "";
        List<Part> scanned = phase.getCameraInventory();
        if (inventoryIndex >= scanned.size()) return phase.getSummary();
        Part part = scanned.get(inventoryIndex);
        if(MAIN_BOARD.equals(part.getComponentId())) return activity.getString(R.string.main_board_surface_hint);
        if (part.getEvidence() == AssemblyPhase.InventoryEvidence.CAMERA_COUNT) {
            return activity.getString(R.string.demo_scan_bracket_set, part.getQuantity());
        }
        return activity.getString(R.string.phase_scan_prompt, componentName(part.getComponentId()));
    }

    private String groupPrompt(String group) {
        List<VisualTarget> targets = targetsIn(group);
        if (targets.isEmpty()) return activity.getString(R.string.phase_verify_start);
        VisualTarget first = targets.get(0);
        switch (first.getKind()) {
            case MOTOR_SLOT:
                return activity.getString(R.string.phase_verify_group_motor);
            case BRACKET_SLOT:
                return activity.getString(R.string.phase_verify_group_bracket, first.getLabel());
            case SECTION_PLACEMENT:
            default:
                return activity.getString(R.string.phase_verify_group_section,
                        targets.size() > 1 ? "both " + pluralName(first.getComponentId()) : "the " + first.getLabel());
        }
    }

    private String unresolvedSummary() {
        if (session == null) return "";
        List<String> unresolved = new ArrayList<>();
        for (TargetResult result : session.getResults()) {
            if (!result.isAccepted()) unresolved.add(result.getTarget().getLabel());
        }
        return unresolved.isEmpty() ? "" : "Not confirmed: " + String.join(", ", unresolved) + ".";
    }

    private String resultsSummary() {
        if (session == null) return "";
        StringBuilder summary = new StringBuilder();
        for (TargetResult result : session.getResults()) {
            summary.append(result.getTarget().getId()).append('=').append(result.getOutcome()).append(' ');
        }
        return summary.toString().trim();
    }

    private void speak(String text, String key) {
        if (!activity.isActivityResumed()) return;
        activity.voiceGuide().speak(text, key);
    }

    private void clearOverlay() {
        hud.reset();
        hud.setDetectedParts(null);
        hud.clearAssemblyTargets();
        activity.hideGuidance();
        correctionOverlayTargets = null;
    }

    private void resetGates() {
        identityGate.reset();
        boardAcquisitionGate.reset();
        bracketSetGate.reset();
        anchorGate.reset();
        placementGate.reset();
        repeatedGate.reset();
        motorGate.reset();
        previousAnchorFrames = 0;
    }

    private String componentName(String componentId) {
        Component component = activity.component(componentId);
        return component == null ? AssemblyPhaseCatalog.componentName(componentId) : component.getName();
    }

    private String partName(Part part) {
        String name = componentName(part.getComponentId());
        if (part.getAlternatives().isEmpty()) return name;
        List<String> alternatives = new ArrayList<>();
        for (String alternative : part.getAlternatives()) {
            // "Nail polish (optional substitute)" reads better without its catalog qualifier here.
            alternatives.add(componentName(alternative).replaceAll("\\s*\\(.*\\)$", ""));
        }
        return activity.getString(R.string.phase_inventory_alternative, name, String.join(" or ", alternatives));
    }

    private String pluralName(String componentId) {
        String name = componentName(componentId).toLowerCase(Locale.US);
        if (MOTOR_BRACKET.equals(componentId)) return "bracket halves";
        return name.endsWith("s") ? name : name + "s";
    }

    private static String joinSections(List<Integer> sections) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < sections.size(); index++) {
            if (index > 0) text.append(index == sections.size() - 1 ? "–" : ", ");
            text.append(sections.get(index));
        }
        return text.toString();
    }

    private static String safeName(RecognitionResult result) {
        String display = result.getDisplayName();
        return display == null || display.isEmpty() ? String.valueOf(result.getComponentId()) : display;
    }

    private void log(String kind, String detail) {
        TrialLog log = activity.trialLog();
        if (log != null) log.event(kind, detail);
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, kind + " " + detail);
    }

    /** Stable across pause/resume and scan completion; distinct for each scan/checkpoint. */
    String diagnosticScope(){
        String id=phase==null?"none":phase.getId();
        if(gearGuide!=null)return id+":"+gearGuide.flow.step()+":"+gearGuide.flow.expectedHardwareView();
        if(driverGuide!=null)return id+":"+driverGuide.flow.step();
        if(microcontrollerGuide!=null)return id+":"+microcontrollerGuide.flow.step();
        if(casterTrackerGuide!=null)return id+":"+casterTrackerGuide.flow.screenIndex();
        if(sensorWiringGuide!=null)return id+":"+sensorWiringGuide.flow.step();
        if(standoffWheelsGuide!=null)return id+":"+standoffWheelsGuide.flow.step();
        if(extensionBoardGuide!=null)return id+":"+extensionBoardGuide.flow.step();
        if(oledBatteryGuide!=null)return id+":"+oledBatteryGuide.flow.step();
        return id;
    }
    /** Physical checklists and developer skip do not themselves produce telemetry evidence. */
    boolean currentScanVerified(){
        if(gearGuide!=null)return gearGuide.flow.step()==5?gearGuide.flow.currentHardwareViewComplete():gearGuide.flow.complete();
        if(driverGuide!=null)return driverGuide.flow.complete();
        if(microcontrollerGuide!=null)return microcontrollerGuide.flow.complete();
        if(casterTrackerGuide!=null)return casterTrackerGuide.flow.scanned();
        if(sensorWiringGuide!=null)return sensorWiringGuide.flow.complete();
        if(standoffWheelsGuide!=null)return standoffWheelsGuide.flow.complete();
        if(extensionBoardGuide!=null)return extensionBoardGuide.flow.complete();
        if(oledBatteryGuide!=null)return oledBatteryGuide.flow.complete();
        return phase!=null&&phase.getNumber()==1&&phaseOneScanConfirmed;
    }

    /** Current request tag, for the trial log's frame rows. */
    @Nullable
    PhaseFlow.ScanTag currentTag() {
        if(gearGuide!=null)return gearGuide.flow.tag();
        if(driverGuide!=null)return driverGuide.flow.tag();
        if(wiringGuide!=null)return wiringGuide.flow.tag();
        if(microcontrollerGuide!=null)return microcontrollerGuide.flow.tag();
        if(driverWiringGuide!=null)return driverWiringGuide.flow.tag();
        if(casterTrackerGuide!=null)return casterTrackerGuide.flow.tag();
        if(sensorWiringGuide!=null)return sensorWiringGuide.flow.tag();
        if(standoffWheelsGuide!=null)return standoffWheelsGuide.flow.tag();
        if(extensionBoardGuide!=null)return extensionBoardGuide.flow.tag();
        if(oledBatteryGuide!=null)return oledBatteryGuide.flow.tag();
        return flow == null ? null : flow.tag();
    }
    RecognitionRequest visualRequestFor(CameraCalibration calibration){
        if(isLivePhaseOne()&&flow!=null&&flow.getMode()==Mode.COMPLETE)
            return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(MAIN_BOARD),calibration,null,null,1,1).withRequestTag(flow.tag());
        RecognitionRequest last=lastVisualRequest;
        return visualRequestCurrent(last)?last.withCameraCalibration(calibration):null;
    }
    private boolean visualRequestCurrent(RecognitionRequest last){
        return last!=null&&last.getRequestTag() instanceof PhaseFlow.ScanTag&&flow!=null
            &&dev.chyken.assembl_ar.workflow.phase.CameraTrackingPolicy.retains(
                (PhaseFlow.ScanTag)last.getRequestTag(),currentTag(),flow.isPreviewing());
    }
    boolean isCameraModeFocused(){
        PhaseFlow.ScanTag tag=currentTag();
        if(tag==null||flow==null)return false;
        return dev.chyken.assembl_ar.workflow.phase.CameraTrackingPolicy.focused(phase.getNumber(),flow.getMode(),flow.isPreviewing())
            ||visualRequestCurrent(lastVisualRequest);
    }

    boolean isFrameForCurrentPhase(RecognitionResult result) {
        if(gearGuide!=null)return gearGuide.current(result);
        if(driverGuide!=null)return driverGuide.current(result);
        if(wiringGuide!=null)return false;
        if(microcontrollerGuide!=null)return microcontrollerGuide.current(result);
        if(driverWiringGuide!=null)return driverWiringGuide.current(result);
        if(casterTrackerGuide!=null)return casterTrackerGuide.current(result);
        if(sensorWiringGuide!=null)return sensorWiringGuide.current(result);
        if(standoffWheelsGuide!=null)return standoffWheelsGuide.current(result);
        if(extensionBoardGuide!=null)return extensionBoardGuide.current(result);
        if(oledBatteryGuide!=null)return oledBatteryGuide.current(result);
        return reviewScript==null && flow!=null
                && result.getFrameTimestampNanos()>=phaseStartedAtNanos
                && java.util.Objects.equals(result.getRequestTag(),flow.tag());
    }

    @Nullable
    AssemblyPhase getPhase() { return phase; }

    @Nullable
    PhaseFlow getFlow() { return flow; }

    @Nullable
    PhaseVerificationSession getSession() { return session; }
}
