package dev.chyken.assembl_ar;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import android.util.Log;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.android.OpenCVLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import dev.chyken.assembl_ar.camera.CameraCalibrationProvider;
import dev.chyken.assembl_ar.camera.IndividualPartTestCatalog;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.diagnostics.BuildStamp;
import dev.chyken.assembl_ar.diagnostics.TrialLog;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.AssemblyInstruction;
import dev.chyken.assembl_ar.model.AssemblyProject;
import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.model.ComponentCategory;
import dev.chyken.assembl_ar.model.InstructionType;
import dev.chyken.assembl_ar.model.OverlayType;
import dev.chyken.assembl_ar.model.PinConnection;
import dev.chyken.assembl_ar.model.ProjectProgress;
import dev.chyken.assembl_ar.model.RequiredComponent;
import dev.chyken.assembl_ar.model.ValidationType;
import dev.chyken.assembl_ar.render.ArCameraProjection;
import dev.chyken.assembl_ar.render.ArGlSurfaceView;
import dev.chyken.assembl_ar.render.ArGuidance;
import dev.chyken.assembl_ar.render.NeobotGuidanceFactory;
import dev.chyken.assembl_ar.ui.ArHudView;
import dev.chyken.assembl_ar.ui.AssemblyDemoView;
import dev.chyken.assembl_ar.ui.AssemblyVoiceGuide;
import dev.chyken.assembl_ar.ui.AssetImageLoader;
import dev.chyken.assembl_ar.ui.ComponentModelDialog;
import dev.chyken.assembl_ar.ui.ManualReferenceDialog;
import dev.chyken.assembl_ar.ui.StepInfoPanelController;
import dev.chyken.assembl_ar.ui.UiUtils;
import dev.chyken.assembl_ar.vision.AssemblyVisionAnalyzer;
import dev.chyken.assembl_ar.vision.CameraCalibration;
import dev.chyken.assembl_ar.vision.ComponentRecognizer;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.MeasurementRecorder;
import dev.chyken.assembl_ar.vision.RecognitionRequest;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.ReferenceDatabase;
import dev.chyken.assembl_ar.vision.VisionConfig;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.vision.MotorAssemblyObservation;
import dev.chyken.assembl_ar.vision.ModelBasedPartRecognizer;
import dev.chyken.assembl_ar.workflow.AssemblySession;
import dev.chyken.assembl_ar.workflow.AssemblyState;
import dev.chyken.assembl_ar.workflow.AssemblyStateMachine;
import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate;
import dev.chyken.assembl_ar.workflow.ConsecutiveAcceptanceGate;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;
import dev.chyken.assembl_ar.workflow.GuidedStepProgressStore;
import dev.chyken.assembl_ar.workflow.GuidedRecognitionCatalog;
import dev.chyken.assembl_ar.workflow.GuidedCheckpoint;
import dev.chyken.assembl_ar.workflow.GuidedDemonstrationCatalog;
import dev.chyken.assembl_ar.workflow.SectionEvidencePolicy;
import dev.chyken.assembl_ar.workflow.VisualPlacementGate;
import dev.chyken.assembl_ar.workflow.MotorAssemblyGate;
import dev.chyken.assembl_ar.workflow.BracketSetRecognitionGate;
import dev.chyken.assembl_ar.workflow.EvidenceClock;
import dev.chyken.assembl_ar.workflow.phase.PhaseDemoScript;
import dev.chyken.assembl_ar.workflow.phase.PhaseProgressStore;

public final class AssemblyActivity extends AppCompatActivity {
    public static final String EXTRA_PROJECT_ID = "project_id";
    public static final String EXTRA_SIMULATED_STATE = "simulated_state";
    public static final String SIMULATE_MISMATCH = "mismatch";
    public static final String SIMULATE_WRONG_ORIENTATION = "wrong_orientation";
    public static final String SIMULATE_POLARITY_ERROR = "polarity_error";
    public static final String SIMULATE_POLARITY_UNCONFIRMED = "polarity_unconfirmed";
    /** Overrides the saved workflow preference: {@link #WORKFLOW_PHASES} or {@link #WORKFLOW_CARDS}. */
    public static final String EXTRA_WORKFLOW_MODE = "workflow_mode";
    public static final String WORKFLOW_PHASES = "phases";
    public static final String WORKFLOW_CARDS = "cards";

    private static final int STABLE_FRAME_GATE = 4;
    private static final int UNRECOGNIZED_FRAME_GATE = 7;

    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    // Descriptor extraction must never queue ahead of live camera frames or preview startup.
    private final ExecutorService visionLoaderExecutor = Executors.newSingleThreadExecutor(task ->
            new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "Assembly-References"));
    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    onCameraPermissionGranted();
                } else {
                    showCameraPermissionPanel(true);
                }
            });

    private AssemblArRepository repository;
    /** Non-null when the grouped phase guide replaces the per-card guide. */
    @Nullable private volatile PhaseAssemblyController phaseController;
    @Nullable private TrialLog trialLog;
    private List<AssemblyStep> steps = new ArrayList<>();
    private final Map<String, Component> componentById = new HashMap<>();
    private volatile AssemblyStep currentStep;
    private AssemblyStateMachine stateMachine;
    private int currentStepIndex;
    private List<GuidedStep> currentGuidedSteps = new ArrayList<>();
    private int currentGuidedStepIndex;
    private int totalGuidedStepCount;
    private volatile boolean guidedStepRecognitionEnabled;
    private volatile java.util.Set<String> availableRecognitionIds;
    @Nullable private ModelBasedPartRecognizer modelPartRecognizer;
    @Nullable private volatile String activeCheckpointComponentId;
    @Nullable private volatile GuidedCheckpoint activeCheckpoint;
    private boolean currentCheckpointComplete;
    private volatile boolean awaitingPartScan;
    private final VisualPlacementGate partIntroductionGate = new VisualPlacementGate(STABLE_FRAME_GATE);
    private final BracketSetRecognitionGate bracketSetGate = new BracketSetRecognitionGate();
    // Gates see camera silence, not analysis time, so slower devices can still finish a check.
    private final EvidenceClock evidenceClock = new EvidenceClock();
    private long frameEvidenceNanos;
    private AssemblyDemoView assemblyDemo;
    private AssemblyVoiceGuide voiceGuide;
    private GuidedDemonstrationCatalog.Demo currentDemo;
    private TextView demoStage;
    private TextView voiceStatus;
    private MaterialButton voiceGuideButton;
    private volatile long acceptRecognitionAfterNanos = Long.MAX_VALUE;
    private boolean completionInFlight;
    private long sessionStartedAt;
    private volatile int analyzedWidth = 1;
    private volatile int analyzedHeight = 1;
    private volatile CameraCalibrationProvider calibrationProvider =
            CameraCalibrationProvider.approximate();
    @Nullable private volatile CameraCalibration latestCalibration;
    private final Map<Long, CameraFrameState> calibrationByFrame = new ConcurrentHashMap<>();
    private int unrecognizedFrames;
    private boolean diagnosticsVisible;
    private volatile boolean diagnosticsModal;
    private long lastHandledCaptureNanos = Long.MIN_VALUE;
    private boolean visionInitializationStarted;
    private volatile boolean destroyed;
    private volatile int visionGeneration;
    private final VisualPlacementGate placementGate = new VisualPlacementGate(STABLE_FRAME_GATE);
    private final VisualPlacementGate anchorGate = new VisualPlacementGate(STABLE_FRAME_GATE);
    private final ConsecutiveAcceptanceGate repeatedPlacementGate =
            new ConsecutiveAcceptanceGate(STABLE_FRAME_GATE);
    private final MotorAssemblyGate motorGate = new MotorAssemblyGate();
    private int previousMotorAnchorFrames;
    private long lastMotorFrameNanos = Long.MIN_VALUE;
    private long lastMotorResultAt;
    private long lastSectionResultAt;
    private long lastCheckpointFrameNanos = Long.MIN_VALUE;
    private final Runnable expireSectionReadiness = () -> {
        if (!destroyed && currentStep != null && needsLivePlacementEvidence()
                && !this.physicalCheckInProgress) {
            resetStableTracking();
            revokeMotorReadiness();
            this.arGlView.hideGuidance();
            this.arHudView.reset();
            setBanner("Camera check paused · show the current assembly again",
                    false, R.drawable.bg_camera_warning);
        }
    };
    private boolean activityResumed;
    private volatile int assemblyVisibilityGeneration;
    private boolean physicalCheckInProgress;
    @Nullable private AlertDialog physicalValidationDialog;
    private final Runnable expireMotorReadiness = () -> {
        if (!destroyed && isMotorValidationStep()
                && !physicalCheckInProgress) {
            motorGate.reset();
            revokeMotorReadiness();
            this.motorInspectionStatus.setText(R.string.motor_inspection_initial);
            // Expire component evidence only. The visual board gate and flow tracker each
            // validate their own capture-time gap when the next frame arrives.
            if (Log.isLoggable("AssemblArBoard", Log.DEBUG)) {
                Log.d("AssemblArBoard", String.format(Locale.US,
                        "step=%d motorEvidenceExpired=true resultGapMs=%d maxGapMs=%d "
                                + "boardStableFrames=%d boardProgressPreserved=true",
                        currentStep.getStepNumber(), SystemClock.uptimeMillis() - lastMotorResultAt,
                        MotorAssemblyGate.MAXIMUM_FRAME_GAP_MILLIS, previousMotorAnchorFrames));
            }
            this.arGlView.hideGuidance();
            this.arHudView.reset();
            setBanner("Camera check paused · bring the board back into view",
                    false, R.drawable.bg_camera_warning);
        }
    };

    private PreviewView previewView;
    private final dev.chyken.assembl_ar.ui.LatestUiTask outlineUpdates=new dev.chyken.assembl_ar.ui.LatestUiTask(task->runOnUiThread(task));
    private ArGlSurfaceView arGlView;
    private ArHudView arHudView;
    private LinearLayout recognitionBanner;
    private ProgressBar recognitionSpinner;
    private TextView recognitionText;
    private LinearLayout diagnosticsPanel;
    private TextView diagnosticsText;
    private LinearLayout instructionPanel;
    private StepInfoPanelController stepInfoPanel;
    private dev.chyken.assembl_ar.ui.ComponentPreviewWindow componentPreview;
    private boolean assemblyWelcomeAccepted;
    boolean assemblyWelcomeAccepted(){return assemblyWelcomeAccepted;}
    void acceptAssemblyWelcome(){assemblyWelcomeAccepted=true;}
    private TextView stepCounter;
    private TextView progressLabel;
    private LinearProgressIndicator assemblyProgress;
    private TextView stepLeadingContext;
    private TextView stepTitle;
    private TextView stepInstruction;
    private ImageView manualThumbnail;
    private ImageView requiredPartThumbnail;
    private TextView requiredPartName;
    private TextView requiredPartQuantity;
    private TextView requiredComponentLabel;
    private TextView manualPageLabel;
    private LinearLayout guidedStepNavigationRow;
    private MaterialButton previousGuidedStepButton;
    private MaterialButton nextGuidedStepButton;
    private MaterialButton confirmPlacementButton;
    private View cameraPermissionPanel;
    private View cameraToolbar;
    private TextView motorInspectionStatus;

    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    @Nullable private Camera boundCamera;
    @Nullable private Preview cameraPreviewUseCase;
    @Nullable private dev.chyken.assembl_ar.workflow.phase.PhaseSessionCheckpoint recreationCheckpoint;
    dev.chyken.assembl_ar.workflow.phase.PhaseSessionCheckpoint recreationCheckpoint(){return recreationCheckpoint;}
    private boolean cameraBindingInProgress;
    @Nullable private View.OnLayoutChangeListener cameraLayoutListener;
    private int cameraLayoutGeneration;
    private ImageAnalysis imageAnalysis;
    private dev.chyken.assembl_ar.ui.NarrationMuteControl muteControl;
    private AssemblyVisionAnalyzer analyzer;
    private final dev.chyken.assembl_ar.diagnostics.DiagnosticTelemetryLocker diagnosticLocker=new dev.chyken.assembl_ar.diagnostics.DiagnosticTelemetryLocker();
    private dev.chyken.assembl_ar.diagnostics.DiagnosticSample pairedDiagnosticSample;
    private ReferenceDatabase referenceDatabase;
    private ComponentRecognizer recognizer;
    private VisionConfig visionConfig;

    private static final class CameraFrameState {
        final CameraCalibration calibration;
        final int width;
        final int height;

        CameraFrameState(CameraCalibration calibration, int width, int height) {
            this.calibration = calibration;
            this.width = width;
            this.height = height;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        recreationCheckpoint=savedInstanceState==null?null:dev.chyken.assembl_ar.workflow.phase.PhaseSessionCheckpoint.decode(savedInstanceState.getString("assembly_phase_session"));
        dev.chyken.assembl_ar.diagnostics.DiagnosticSession.INSTANCE.attach(this);
        assemblyWelcomeAccepted=savedInstanceState!=null&&savedInstanceState.getBoolean("assembly_welcome_accepted",false);
        MeasurementRecorder.install(this);
        setContentView(R.layout.activity_assembly);
        bindViews();
        voiceGuide = new AssemblyVoiceGuide(this, new AssemblyVoiceGuide.Callback() {
            @Override public void onStatusChanged(AssemblyVoiceGuide.Status status) {
                runOnUiThread(() -> updateVoiceStatus(status));
            }

            @Override public void onSpeechFinished(String key) {
                runOnUiThread(() -> {
                    PhaseAssemblyController phases = phaseController;
                    if (phases != null && !destroyed) phases.onSpeechFinished(key);
                });
            }

            @Override public void onSpeechStarted(String key, String text) {
                runOnUiThread(() -> {
                    PhaseAssemblyController phases=phaseController;
                    if(phases!=null && !destroyed) phases.onSpeechStarted(key,text);
                });
            }
        });
        voiceGuide.setMuted(dev.chyken.assembl_ar.diagnostics.PreferencesManager.ttsMuted(this));
        planFHelp=new PlanFScanHelpController(this,findViewById(R.id.scanHelpButton),()->referenceCatalog);
        configureTabletPanels();
        UiUtils.configureCameraWindow(this, cameraToolbar, null);
        stepInfoPanel = new StepInfoPanelController(this, savedInstanceState);
        componentPreview=findViewById(R.id.componentPreviewWindow);
        componentPreview.showComponent(NeobotSeedData.MAIN_BOARD,"Main Board");
        arHudView.setStateListener(componentPreview::onHudState);
        repository = AssemblArRepository.getInstance(this);
        sessionStartedAt = savedInstanceState == null
                ? System.currentTimeMillis()
                : savedInstanceState.getLong("session_started_at", System.currentTimeMillis());
        wireActions();
        boolean phases = usesPhaseWorkflow();
        trialLog = TrialLog.start(this, (phases ? "grouped phases" : "step-by-step cards")
                + " · " + BuildStamp.shortLabel());
        ((TextView) findViewById(R.id.buildStampLabel)).setText(BuildStamp.shortLabel());
        TextView workflowToggle = findViewById(R.id.workflowModeButton);
        workflowToggle.setText(phases ? R.string.workflow_use_cards : R.string.workflow_use_phases);
        workflowToggle.setOnClickListener(view -> {
            PhaseProgressStore.setWorkflowMode(this, phases
                    ? PhaseProgressStore.WorkflowMode.STEP_CARDS : PhaseProgressStore.WorkflowMode.PHASES);
            getIntent().removeExtra(EXTRA_WORKFLOW_MODE);
            recreate();
        });
        findViewById(R.id.shareTrialLogButton).setOnClickListener(view -> DevDiagnosticsFragment.showControls(this));
        if (phases) {
            PhaseAssemblyController controller = new PhaseAssemblyController(this);
            controller.bind();
            phaseController = controller;
        }
        loadProject();
    }

    /** The grouped phase guide is the default; simulated-state and card tests opt out explicitly. */
    private boolean usesPhaseWorkflow() {
        String requested = getIntent().getStringExtra(EXTRA_WORKFLOW_MODE);
        if (WORKFLOW_CARDS.equals(requested)) return false;
        if (WORKFLOW_PHASES.equals(requested)) return true;
        if (getIntent().getStringExtra(EXTRA_SIMULATED_STATE) != null) return false;
        return PhaseProgressStore.workflowMode(this) == PhaseProgressStore.WorkflowMode.PHASES;
    }

    private void configureTabletPanels() {
        if (!UiUtils.isTablet(this)) {
            return;
        }
        int panelWidth = getResources().getDimensionPixelSize(
                R.dimen.tablet_camera_panel_width);
        int edge = UiUtils.dp(this, 20);

        FrameLayout.LayoutParams permissionParams =
                (FrameLayout.LayoutParams) cameraPermissionPanel.getLayoutParams();
        permissionParams.width = panelWidth;
        permissionParams.gravity = Gravity.CENTER;
        permissionParams.setMargins(edge, edge, edge, edge);
        cameraPermissionPanel.setLayoutParams(permissionParams);
        recognitionText.setMaxWidth(UiUtils.dp(this, 420));
    }

    private void bindViews() {
        previewView = findViewById(R.id.previewView);
        arGlView = findViewById(R.id.arGlView);
        arHudView = findViewById(R.id.arHudView);
        arHudView.useDynamicOutlines();arGlView.useDynamicOutlines();
        recognitionBanner = findViewById(R.id.recognitionBanner);
        recognitionSpinner = findViewById(R.id.recognitionSpinner);
        recognitionText = findViewById(R.id.recognitionText);
        diagnosticsPanel = findViewById(R.id.diagnosticsPanel);
        diagnosticsText = findViewById(R.id.diagnosticsText);
        instructionPanel = findViewById(R.id.instructionPanel);
        stepCounter = findViewById(R.id.stepCounter);
        progressLabel = findViewById(R.id.progressLabel);
        assemblyProgress = findViewById(R.id.assemblyProgress);
        stepLeadingContext = findViewById(R.id.stepLeadingContext);
        stepTitle = findViewById(R.id.stepTitle);
        stepInstruction = findViewById(R.id.stepInstruction);
        manualThumbnail = findViewById(R.id.manualThumbnail);
        requiredPartThumbnail = findViewById(R.id.requiredPartThumbnail);
        requiredPartName = findViewById(R.id.requiredPartName);
        requiredPartQuantity = findViewById(R.id.requiredPartQuantity);
        requiredComponentLabel = findViewById(R.id.requiredComponentLabel);
        manualPageLabel = findViewById(R.id.manualPageLabel);
        guidedStepNavigationRow = findViewById(R.id.guidedStepNavigationRow);
        previousGuidedStepButton = findViewById(R.id.previousGuidedStepButton);
        nextGuidedStepButton = findViewById(R.id.nextGuidedStepButton);
        confirmPlacementButton = findViewById(R.id.confirmPlacementButton);
        cameraPermissionPanel = findViewById(R.id.cameraPermissionPanel);
        cameraToolbar = findViewById(R.id.cameraToolbar);
        motorInspectionStatus = findViewById(R.id.motorInspectionStatus);
        assemblyDemo = findViewById(R.id.assemblyDemo);
        demoStage = findViewById(R.id.demoStage);
        voiceStatus = findViewById(R.id.voiceGuideStatus);
        voiceGuideButton = findViewById(R.id.voiceGuideButton);
    }

    private void wireActions() {
        dev.chyken.assembl_ar.ui.AppShellController.bindAssemblyControls(this);
        previewView.setOnTouchListener((view, event) -> focusCamera(event));
        exposeAsAccessibilityButton(findViewById(R.id.toggleStepInfoButton));
        exposeAsAccessibilityButton(stepInstruction);
        exposeAsAccessibilityButton(findViewById(R.id.manualModeButton));
        exposeAsAccessibilityButton(findViewById(R.id.relockBaseButton));
        findViewById(R.id.relockBaseButton).setOnClickListener(view -> relockAssemblyBase());
        findViewById(R.id.backButton).setOnClickListener(view ->
                getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.livePill).setOnLongClickListener(view -> {
            setDiagnosticsVisible(!diagnosticsVisible);
            return true;
        });
        findViewById(R.id.wiringReviewButton).setOnClickListener(v->reviewWiringDiagrams());
        findViewById(R.id.devButton).setOnClickListener(view -> {
            if(dev.chyken.assembl_ar.diagnostics.PreferencesManager.assemblyDeveloperVisible(this))
                DevStepSkipDialog.show(this, phaseController,diagnosticsVisible,this::setDiagnosticsVisible);
        });
        recognitionBanner.setOnClickListener(v->{if(failedReferenceScope!=null){failedReferenceScope=null;refreshReferenceScope();}else if(!visionInitializationStarted)initializeVision();});
        findViewById(R.id.viewReferenceButton).setOnClickListener(view -> showManualReference());
        manualThumbnail.setOnClickListener(view -> showManualReference());
        findViewById(R.id.requiredPartReference).setOnClickListener(view -> {
            Component part = componentById.get(currentDemo == null ? "" : currentDemo.getPartId());
            if (part != null) ComponentModelDialog.show(this, part.getId(), part.getName());
        });
        stepInstruction.setOnClickListener(view -> showStepDetails());
        previousGuidedStepButton.setOnClickListener(view -> moveGuidedStep(-1));
        nextGuidedStepButton.setOnClickListener(view -> {
            if (isOnFinalGuidedStep() && activeCheckpoint != null
                    && activeCheckpoint.getKind() == GuidedCheckpoint.Kind.IDENTITY
                    && currentCheckpointComplete) completeCurrentStep();
            else moveGuidedStep(1);
        });
        findViewById(R.id.redoGuidedStepButton).setOnClickListener(view -> redoCurrentGuidedStep());
        findViewById(R.id.replayDemoButton).setOnClickListener(view -> replayDemonstration());
        findViewById(R.id.assemblyDetailsButton).setOnClickListener(view -> {
            View details = findViewById(R.id.assemblyDetails);
            boolean show = details.getVisibility() != View.VISIBLE;
            details.setVisibility(show ? View.VISIBLE : View.GONE);
            ((TextView) view).setText(show ? R.string.hide_action_details : R.string.show_action_details);
        });
        muteControl=new dev.chyken.assembl_ar.ui.NarrationMuteControl(this,findViewById(R.id.btnTtsMuteToggle),muted->{
            boolean changed=voiceGuide.isMuted()!=muted;voiceGuide.setMuted(muted);
            if(changed&&phaseController!=null)phaseController.onVoiceStatusChanged();
        });
        voiceGuideButton.setOnClickListener(view->findViewById(R.id.btnTtsMuteToggle).performClick());
        confirmPlacementButton.setOnClickListener(view -> showPhysicalValidation());
        findViewById(R.id.manualModeButton).setOnClickListener(view -> useManualReferenceMode());
        findViewById(R.id.grantCameraButton).setOnClickListener(view -> {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                cameraPermission.launch(Manifest.permission.CAMERA);
            } else {
                Intent settingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivity(settingsIntent);
            }
        });
    }

    private static void exposeAsAccessibilityButton(View view) {
        ViewCompat.setAccessibilityDelegate(view, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(
                    View host, AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
            }
        });
    }

    private void loadProject() {
        String projectId = getIntent().getStringExtra(EXTRA_PROJECT_ID);
        if (projectId == null) {
            projectId = NeobotSeedData.PROJECT_ID;
        }
        String finalProjectId = projectId;
        databaseExecutor.execute(() -> {
            AssemblyProject project = repository.getProject(finalProjectId);
            ProjectProgress progress = repository.getProjectProgress(finalProjectId);
            List<Component> components = repository.getComponents();
            runOnUiThread(() -> {
                if (destroyed || isFinishing() || isDestroyed()) return;
                if (project == null || project.getSteps().isEmpty()) {
                    finish();
                    return;
                }
                steps = project.getSteps();
                totalGuidedStepCount = GuidedAssemblySequence.countGuidedSteps(steps);
                componentById.clear();
                for (Component component : components) {
                    componentById.put(component.getId(), component);
                }
                if (progress.isComplete()) {
                    openCompletion();
                    return;
                }
                currentStepIndex = findStepIndex(progress.getCurrentStepId());
                currentStep = steps.get(currentStepIndex);
                stateMachine = new AssemblyStateMachine(steps.size(), currentStepIndex);
                PhaseAssemblyController phases = phaseController;
                if (phases != null) {
                    requiredComponentLabel.setText(requiredMaterialsText(currentStep));
                    phases.load(steps, progress);
                } else {
                    renderStep();
                }
                String simulatedState = getIntent().getStringExtra(EXTRA_SIMULATED_STATE);
                if (simulatedState != null) {
                    simulateState(simulatedState);
                } else {
                    ensureCameraPermission();
                }
            });
        });
    }

    private int findStepIndex(String stepId) {
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).getId().equals(stepId)) {
                return index;
            }
        }
        return 0;
    }

    private void ensureCameraPermission() {
        if (hasCameraPermission()) {
            onCameraPermissionGranted();
        } else {
            showCameraPermissionPanel(true);
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void onCameraPermissionGranted() {
        // Permission results and returns from system settings both happen while this Activity
        // remains alive. Remove the blocking panel before starting the asynchronous vision work
        // so the camera UI becomes available immediately without requiring an Activity restart.
        showCameraPermissionPanel(false);
        initializeVision();
        // Also retries a failed bind when returning from settings or resuming the Activity.
        // A bound camera is retained; CameraX resumes its stream through the lifecycle.
        startCamera(visionGeneration);
    }

    private void showCameraPermissionPanel(boolean show) {
        cameraPermissionPanel.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private volatile dev.chyken.assembl_ar.ui.WiringDiagramReviewDialog wiringReviewDialog;
    void refreshSchematicControl(){
        View button=findViewById(R.id.wiringReviewButton);if(button==null)return;
        boolean wiring=phaseController!=null?phaseController.focusedSchematicPhase()>0:
                !usesPhaseWorkflow()&&currentStep!=null&&!currentStep.getPinConnections().isEmpty();
        int visibility=wiring?View.VISIBLE:View.GONE;
        if(button.getVisibility()!=visibility)button.setVisibility(visibility);
    }
    private void reviewWiringDiagrams(){
        if(destroyed||wiringReviewDialog!=null&&wiringReviewDialog.isShowing())return;
        refreshSchematicControl();if(findViewById(R.id.wiringReviewButton).getVisibility()!=View.VISIBLE)return;
        PhaseAssemblyController phases=phaseController;
        int focusedPhase=phases==null?8:phases.focusedSchematicPhase();
        dev.chyken.assembl_ar.workflow.phase.WiringDiagramState state=phases!=null?phases.wiringDiagramState():dev.chyken.assembl_ar.workflow.phase.WiringDiagramState.fromCheckpoints(
            dev.chyken.assembl_ar.workflow.phase.PhaseProgressStore.wiringState(this,NeobotSeedData.PROJECT_ID),dev.chyken.assembl_ar.workflow.phase.PhaseProgressStore.driverWiringState(this,NeobotSeedData.PROJECT_ID),dev.chyken.assembl_ar.workflow.phase.PhaseProgressStore.sensorWiringState(this,NeobotSeedData.PROJECT_ID));
        if(phases!=null)phases.onPause();voiceGuide.stop();assemblyVisibilityGeneration++;arHudView.setScanningEnabled(false);
        wiringReviewDialog=new dev.chyken.assembl_ar.ui.WiringDiagramReviewDialog(this,state,focusedPhase,text->voiceGuide.speak(text,"wiring-review:"+SystemClock.elapsedRealtimeNanos()));
        wiringReviewDialog.setOnDismissListener(d->{wiringReviewDialog=null;assemblyVisibilityGeneration++;if(!destroyed&&activityResumed){if(phaseController!=null)phaseController.onResume();refreshReferenceScope();}});wiringReviewDialog.show();
        voiceGuide.speak("Wiring diagrams. Select a phase to revisit its schematic. Your assembly progress is preserved.","wiring-review:open");
    }
    private volatile List<ComponentReferenceSpec> referenceCatalog;
    private PlanFScanHelpController planFHelp;
    private volatile String loadedReferenceScope,loadingReferenceScope;
    // Bank epochs change independently of the bound camera and reject stale native results.
    private volatile int referenceBankGeneration;
    private String failedReferenceScope,referencePreparationMessage;
    private long referenceRetryAt;
    private int referenceLoadFailures;
    private volatile boolean libraryPreparing;
    private final ActivityResultLauncher<Intent> startupPreparation=registerForActivityResult(
        new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),result->{
            libraryPreparing=false;
            if(result.getResultCode()!=RESULT_OK){finish();return;}
            if(!destroyed){if(referenceCatalog==null){initializeVision();return;}if(activityResumed&&phaseController!=null)phaseController.onResume();refreshReferenceScope();}
        });
    private volatile String libraryFailure;
    private volatile int prefetchGeneration;
    private String prefetchingScope;
    private ReferenceBank prefetchedBank;
    private static final class ReferenceBank {
        final String scope;final ReferenceDatabase database;final ComponentRecognizer worker;
        ReferenceBank(String scope,ReferenceDatabase database,ComponentRecognizer worker){this.scope=scope;this.database=database;this.worker=worker;}
        void close(){closeVisionResources(worker,database);}
    }

    private final Runnable referenceScopeMonitor=new Runnable(){public void run(){
        if(destroyed)return;
        refreshSchematicControl();
        if(stepInfoPanel!=null)stepInfoPanel.setCameraFocused(phaseController!=null&&phaseController.isCameraModeFocused(),phaseController==null?null:phaseController.diagnosticScope());
        dev.chyken.assembl_ar.ui.DynamicComponentOutlineOverlayView outline=findViewById(R.id.dynamicOutlineOverlay);
        if(outline!=null)outline.setCameraActive(activityResumed&&!diagnosticsModal&&!libraryPreparing&&phaseController!=null&&phaseController.isCameraModeFocused());
        if(visionInitializationStarted&&referenceCatalog!=null&&activityResumed)refreshReferenceScope();
        if(activityResumed&&boundCamera!=null&&!cameraBindingInProgress&&cameraCaptureEdge!=desiredCaptureEdge())startCamera(visionGeneration);
        refreshPlanFHelp();
        if(recognitionText!=null)recognitionText.postDelayed(this,250);
    }};
    private void refreshPlanFHelp(){
        if(planFHelp==null)return;
        boolean enabled=dev.chyken.assembl_ar.diagnostics.PreferencesManager.planFEnabled(this);
        if(!enabled){planFHelp.suspend();return;}
        PhaseAssemblyController phases=phaseController;
        RecognitionRequest request=activeReferenceRequest();
        boolean verified=phases!=null?phases.currentScanVerified():currentCheckpointComplete;
        boolean scanning=activityResumed&&!destroyed&&!diagnosticsModal&&!libraryPreparing&&libraryFailure==null
                &&analyzer!=null&&boundCamera!=null&&!cameraBindingInProgress
                &&request!=null&&java.util.Objects.equals(loadedReferenceScope,dev.chyken.assembl_ar.camera.ActiveReferenceScope.key(request))&&!physicalCheckInProgress
                &&(phases!=null?!phases.isAnalysisSuspended()&&phases.isCameraModeFocused():awaitingPartScan||activeCheckpoint!=null&&activeCheckpoint.requiresCamera());
        String scope=phases!=null?phases.diagnosticScope():currentStep==null?null:currentStep.getId()+":"+currentGuidedStepIndex;
        Object attempt=phases!=null?phases.currentTag():request==null?null:request.getRequestTag();
        String id=request==null||request.getExpectation()==null?null:request.getExpectation().getComponentId();
        Component part=id==null?null:componentById.get(id);
        planFHelp.update(scope,attempt,request,part==null?"Current target":part.getName(),
                enabled,scanning,verified);
    }
    private RecognitionRequest activeReferenceRequest(){
        if(diagnosticsModal||libraryPreparing||libraryFailure!=null||wiringReviewDialog!=null&&wiringReviewDialog.isShowing())return null;
        PhaseAssemblyController phases=phaseController;
        if(phases!=null)return phases.isAnalysisSuspended()?(phases.isCameraModeFocused()?phases.visualRequestFor(null):null):phases.requestFor(null);
        AssemblyStep step=currentStep;if(step==null)return null;
        String target=activeCheckpoint==null?primaryComponentId(step):activeCheckpoint.getComponentId();
        return new RecognitionRequest(RecognitionRequest.AssemblyExpectation.component(target),null,null,spatialAnchorComponentId(step),1,step.getStepNumber());
    }
    void captureDiagnosticScanFrame(){
        AssemblyVisionAnalyzer current=analyzer;
        if(current==null){Toast.makeText(this,"Open a camera scanning step first",Toast.LENGTH_SHORT).show();return;}
        boolean queued=current.captureNextAnalyzedFrame(new AssemblyVisionAnalyzer.FrameSnapshotCallback(){
            @Override public void onCaptured(AssemblyVisionAnalyzer.AnalyzedFrameSnapshot snapshot){
                AssemblyVisionAnalyzer.FrameMetadata m=snapshot.getMetadata();RecognitionResult r=snapshot.getRecognitionResult();
                dev.chyken.assembl_ar.workflow.PartTestSampleArchive archive=new dev.chyken.assembl_ar.workflow.PartTestSampleArchive(
                    r.getComponentId()==null?"assembly":r.getComponentId(),m.getTimestampNanos(),m.getAnalyzedWidth(),m.getAnalyzedHeight(),m.getRotationDegrees(),
                    dev.chyken.assembl_ar.diagnostics.BuildStamp.details()+"\nRequest: "+r.getRequestTag()+"\nState: "+r.getValidationState(),snapshot.getPngBytes(),snapshot.getRgbPngBytes(),snapshot.getCameraCalibration());
                dev.chyken.assembl_ar.diagnostics.DiagnosticLogger.get(AssemblyActivity.this).recordFrame(archive);
                runOnUiThread(()->Toast.makeText(AssemblyActivity.this,"Raw scan frame saved · Download Diagnostic Logs to share",Toast.LENGTH_LONG).show());
            }
            @Override public void onError(Throwable error){runOnUiThread(()->Toast.makeText(AssemblyActivity.this,"Capture failed: "+error.getMessage(),Toast.LENGTH_LONG).show());}
        });
        Toast.makeText(this,queued?"Capture queued · return to camera and frame the component":"A capture is already queued",Toast.LENGTH_LONG).show();
    }
    void pauseForDiagnostics(){diagnosticsModal=true;if(planFHelp!=null)planFHelp.suspend();assemblyVisibilityGeneration++;if(phaseController!=null)phaseController.onPause();voiceGuide.stop();}
    void resumeAfterDiagnostics(){diagnosticsModal=false;if(!destroyed&&activityResumed){assemblyVisibilityGeneration++;if(phaseController!=null)phaseController.onResume();refreshReferenceScope();}}
    private void initializeVision() {
        if(visionInitializationStarted||destroyed)return;
        if(!dev.chyken.assembl_ar.vision.VisionPreloadManager.get(this).isReady()){prepareReferenceLibrary();return;}
        visionInitializationStarted=true;int generation=++visionGeneration;
        startCamera(generation);
        recognitionText.removeCallbacks(referenceScopeMonitor);recognitionText.post(referenceScopeMonitor);
        visionLoaderExecutor.execute(()->{
            try{
                if(!OpenCVLoader.initLocal())throw new IllegalStateException("OpenCV native runtime is unavailable");
                long catalogStarted=SystemClock.elapsedRealtime();
                VisionConfig config=VisionConfig.defaults();List<ComponentReferenceSpec> catalog=dev.chyken.assembl_ar.vision.VisionPreloadManager.get(this).catalog().get();
                ModelBasedPartRecognizer models=null;try{models=new ModelBasedPartRecognizer(getAssets());}catch(Exception failure){Log.w("AssemblyActivity","Model templates unavailable",failure);}
                Log.i("AssemblyActivity","Reference catalog indexed photos="+catalog.size()+" ms="+(SystemClock.elapsedRealtime()-catalogStarted));
                ModelBasedPartRecognizer readyModels=models;
                runOnUiThread(()->{
                    if(!isVisionGenerationActive(generation))return;
                    visionConfig=config;referenceCatalog=catalog;modelPartRecognizer=readyModels;
                    java.util.Set<String> supported=new java.util.HashSet<>();for(ComponentReferenceSpec spec:catalog)supported.add(spec.getComponentId());
                    if(readyModels!=null)supported.addAll(readyModels.supportedComponentIds());availableRecognitionIds=java.util.Collections.unmodifiableSet(supported);
                    prepareReferenceLibrary();
                });
            }catch(Throwable failure){Log.e("AssemblyActivity","Vision catalog initialization failed",failure);runOnUiThread(()->{if(isVisionGenerationActive(generation)){visionInitializationStarted=false;setBanner("Recognition preparation failed · tap to retry",false,R.drawable.bg_camera_warning);}});}
        });
    }
    private File referenceCacheDirectory(){return new File(getNoBackupFilesDir(),"orb_reference_cache_v3");}
    private void prepareReferenceLibrary(){
        if(destroyed||libraryPreparing)return;
        if(dev.chyken.assembl_ar.vision.VisionPreloadManager.get(this).isReady()){
            libraryFailure=null;if(activityResumed&&phaseController!=null)phaseController.onResume();refreshReferenceScope();return;
        }
        libraryPreparing=true;libraryFailure=null;
        if(phaseController!=null)phaseController.onPause();voiceGuide.stop();arHudView.setScanningEnabled(false);
        startupPreparation.launch(new Intent(this,SplashActivity.class));
    }
    private void installReferenceBank(ReferenceBank bank){
        detachReferenceBank();referenceBankGeneration++;
        referenceLoadFailures=0;referenceRetryAt=0;
        referenceDatabase=bank.database;recognizer=bank.worker;loadedReferenceScope=bank.scope;loadingReferenceScope=null;referencePreparationMessage=null;failedReferenceScope=null;
        if(phaseController!=null)phaseController.onRecognitionReady();showCurrentGuidedStepBanner();attachVisionAnalyzer(visionGeneration);
    }
    private void prefetchNext(RecognitionRequest request){
        RecognitionRequest next=dev.chyken.assembl_ar.camera.ActiveReferenceScope.next(request);if(next==null||destroyed)return;
        String scope=dev.chyken.assembl_ar.camera.ActiveReferenceScope.key(next);
        if(scope.equals(prefetchingScope)||prefetchedBank!=null&&scope.equals(prefetchedBank.scope))return;
        int token=++prefetchGeneration;prefetchingScope=scope;
        if(prefetchedBank!=null){ReferenceBank old=prefetchedBank;prefetchedBank=null;visionLoaderExecutor.execute(old::close);}
        List<ComponentReferenceSpec> specs=dev.chyken.assembl_ar.camera.ActiveReferenceScope.select(referenceCatalog,next);
        visionLoaderExecutor.execute(()->{
            ReferenceDatabase db=null;ComponentRecognizer worker=null;
            try{
                db=dev.chyken.assembl_ar.vision.VisionPreloadManager.get(this).loadBank(specs,()->!destroyed&&token==prefetchGeneration,(d,t)->{});
                if(destroyed||token!=prefetchGeneration){db.close();return;}worker=new ComponentRecognizer(visionConfig,db);ReferenceBank ready=new ReferenceBank(scope,db,worker);
                runOnUiThread(()->{if(destroyed||token!=prefetchGeneration){ready.close();return;}prefetchingScope=null;
                    if(scope.equals(dev.chyken.assembl_ar.camera.ActiveReferenceScope.key(activeReferenceRequest()))){installReferenceBank(ready);prefetchNext(next);}else prefetchedBank=ready;
                });
            }catch(Throwable failure){closeVisionResources(worker,db);if(!destroyed&&token==prefetchGeneration)runOnUiThread(()->{if(token!=prefetchGeneration)return;prefetchingScope=null;if(scope.equals(loadingReferenceScope))loadingReferenceScope=null;Log.w("AssemblyActivity","Upcoming scan will load on demand: "+scope,failure);});}
        });
    }
    private boolean referenceBankMatchesRequest(){
        String wanted=dev.chyken.assembl_ar.camera.ActiveReferenceScope.key(activeReferenceRequest());
        return wanted!=null&&wanted.equals(loadedReferenceScope);
    }
    private void refreshReferenceScope(){
        if(destroyed||libraryPreparing||referenceCatalog==null)return;
        RecognitionRequest request=activeReferenceRequest();String scope=dev.chyken.assembl_ar.camera.ActiveReferenceScope.key(request);
        if(scope==null){
            if(loadingReferenceScope!=null){referenceBankGeneration++;loadingReferenceScope=null;referencePreparationMessage=null;}
            return;
        }
        if(scope.equals(loadedReferenceScope)){prefetchNext(request);return;}
        if(prefetchedBank!=null&&scope.equals(prefetchedBank.scope)){ReferenceBank ready=prefetchedBank;prefetchedBank=null;installReferenceBank(ready);prefetchNext(request);return;}
        if(scope.equals(prefetchingScope))return;
        if(scope.equals(loadingReferenceScope))return;
        if(scope.equals(failedReferenceScope)&&SystemClock.elapsedRealtime()<referenceRetryAt)return;
        prefetchGeneration++;prefetchingScope=null;
        if(prefetchedBank!=null){ReferenceBank old=prefetchedBank;prefetchedBank=null;visionLoaderExecutor.execute(old::close);}
        int bank=++referenceBankGeneration;int cameraGeneration=visionGeneration;loadingReferenceScope=scope;failedReferenceScope=null;
        List<ComponentReferenceSpec> specs=dev.chyken.assembl_ar.camera.ActiveReferenceScope.select(referenceCatalog,request);
        detachReferenceBank();referencePreparationMessage="Opening scanner…";setBanner(referencePreparationMessage,true,R.drawable.bg_live_pill);
        visionLoaderExecutor.execute(()->{
            ReferenceDatabase db=null;ComponentRecognizer worker=null;long started=SystemClock.elapsedRealtime();
            try{
                db=dev.chyken.assembl_ar.vision.VisionPreloadManager.get(this).loadBank(specs,
                    ()->!destroyed&&bank==referenceBankGeneration,(done,total)->{});
                if(destroyed||bank!=referenceBankGeneration){db.close();return;}
                worker=new ComponentRecognizer(visionConfig,db);ReferenceDatabase readyDb=db;ComponentRecognizer readyWorker=worker;
                Log.i("AssemblyActivity","Reference bank ready scope="+scope+" photos="+db.size()+" ms="+(SystemClock.elapsedRealtime()-started));
                runOnUiThread(()->{
                    if(destroyed||bank!=referenceBankGeneration){closeVisionResources(readyWorker,readyDb);return;}
                    referenceDatabase=readyDb;recognizer=readyWorker;loadedReferenceScope=scope;loadingReferenceScope=null;referencePreparationMessage=null;
                    referenceLoadFailures=0;referenceRetryAt=0;
                    PhaseAssemblyController phases=phaseController;if(phases!=null)phases.onRecognitionReady();showCurrentGuidedStepBanner();attachVisionAnalyzer(cameraGeneration);prefetchNext(request);
                });
            }catch(Throwable failure){
                closeVisionResources(worker,db);
                if(destroyed||bank!=referenceBankGeneration)return;
                Log.e("AssemblyActivity","Active scan preparation failed: "+scope,failure);
                runOnUiThread(()->{if(!destroyed&&bank==referenceBankGeneration){loadingReferenceScope=null;referencePreparationMessage=null;failedReferenceScope=scope;
                    referenceRetryAt=SystemClock.elapsedRealtime()+Math.min(30_000L,2_000L*++referenceLoadFailures);
                    setBanner("Recognition preparation recovering…",true,R.drawable.bg_live_pill);}});
            }
        });
    }
    private void detachReferenceBank(){
        loadedReferenceScope=null;
        if(imageAnalysis!=null)imageAnalysis.clearAnalyzer();
        if(analyzer!=null){analyzer.close();analyzer=null;}
        ComponentRecognizer old=recognizer;ReferenceDatabase oldDb=referenceDatabase;recognizer=null;referenceDatabase=null;
        if(old!=null||oldDb!=null)cameraExecutor.execute(()->closeVisionResources(old,oldDb));
    }

    private boolean isVisionGenerationActive(int generation) {
        return !destroyed && generation == visionGeneration;
    }

    private static void closeVisionResources(
            @Nullable ComponentRecognizer componentRecognizer,
            @Nullable ReferenceDatabase database) {
        if (componentRecognizer != null) {
            componentRecognizer.close();
        }
        if (database != null) {
            database.close();
        }
    }

    private int cameraCaptureEdge=640;
    private boolean detailCaptureUnavailable;
    private int desiredCaptureEdge(){
        android.app.ActivityManager memory=(android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE);
        int phase=phaseController==null?0:phaseController.nativeDetailCapturePhase();
        return dev.chyken.assembl_ar.vision.AssemblyCapturePolicy.longEdge(phase,memory!=null&&memory.isLowRamDevice(),detailCaptureUnavailable);
    }
    @androidx.annotation.OptIn(markerClass=androidx.camera.camera2.interop.ExperimentalCamera2Interop.class)
    private void meterInstalledCapture(Camera camera){
        if(!activityResumed||cameraCaptureEdge<=640)return;
        try{
            // Respect device capabilities and let AE adapt when the room changes.
            int[] modes=androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.getCameraInfo()).getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES);
            if(modes!=null)for(int mode:modes)if(mode==android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO){
                androidx.camera.camera2.interop.Camera2CameraControl.from(camera.getCameraControl()).setCaptureRequestOptions(new androidx.camera.camera2.interop.CaptureRequestOptions.Builder().setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,mode).build());break;
            }
            MeteringPoint center=previewView.getMeteringPointFactory().createPoint(previewView.getWidth()*.5f,previewView.getHeight()*.5f,.35f);
            camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(center,FocusMeteringAction.FLAG_AF|FocusMeteringAction.FLAG_AE|FocusMeteringAction.FLAG_AWB).setAutoCancelDuration(4,TimeUnit.SECONDS).build());
        }catch(RuntimeException unsupported){Log.w("AssemblyActivity","Optional target metering unavailable",unsupported);}
    }
    private void startCamera(int generation) {
        if (!isVisionGenerationActive(generation) || !hasCameraPermission()) return;
        if (boundCamera != null&&cameraCaptureEdge==desiredCaptureEdge()) {
            if(cameraPreviewUseCase!=null)cameraPreviewUseCase.setSurfaceProvider(previewView.getSurfaceProvider());
            attachVisionAnalyzer(generation);
            return;
        }
        if (cameraBindingInProgress) return;
        cameraBindingInProgress = true;
        cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            if (!isVisionGenerationActive(generation)) {
                cameraBindingInProgress = false;
                if (!destroyed) startCamera(visionGeneration);
                return;
            }
            try {
                ProcessCameraProvider provider = cameraProviderFuture.get();
                if (!isVisionGenerationActive(generation)) {
                    return;
                }
                ViewPort viewPort = previewView.getViewPort();
                if (viewPort == null) {
                    waitForPreviewViewPort(generation);
                    return;
                }
                Preview preview = new Preview.Builder().build();

                // Bind analysis with Preview from the outset to keep their crop/timebase shared.
                // CameraX discards analysis frames until the asynchronously prepared analyzer exists.
                int requestedEdge=desiredCaptureEdge();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(new androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                            .setResolutionStrategy(new androidx.camera.core.resolutionselector.ResolutionStrategy(
                                new android.util.Size(requestedEdge,requestedEdge==640?480:720),androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER))
                            // Never let a camera's fallback silently choose a 4K analyzer.
                            .setResolutionFilter((sizes,rotation)->{java.util.List<android.util.Size> bounded=new java.util.ArrayList<>();for(android.util.Size size:sizes)if(Math.max(size.getWidth(),size.getHeight())<=requestedEdge)bounded.add(size);return bounded;}).build())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build();

                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                // A resolution switch replaces the stream, not the workflow or bank.
                // Reject detached frames from the old stream before attaching the new one.
                if(imageAnalysis!=null)imageAnalysis.clearAnalyzer();
                if(analyzer!=null)analyzer.pause();
                provider.unbindAll();
                boundCamera = null;
                UseCaseGroup.Builder useCases = new UseCaseGroup.Builder()
                        .addUseCase(preview)
                        // Analyze PreviewView's exact crop so HUD and OpenGL coordinates describe
                        // the visible pixels, including FILL_CENTER aspect-ratio cropping.
                        .setViewPort(viewPort);
                useCases.addUseCase(analysis);
                Camera camera = provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA, useCases.build());
                boundCamera = camera;
                cameraCaptureEdge=requestedEdge;
                cameraPreviewUseCase=preview;
                imageAnalysis = analysis;
                calibrationProvider = CameraCalibrationProvider.from(camera.getCameraInfo());
                meterInstalledCapture(camera);
                if(analyzer!=null){if(activityResumed)analyzer.resume();analysis.setAnalyzer(cameraExecutor,analyzer);}else attachVisionAnalyzer(generation);
                if (recognizer != null) showCurrentGuidedStepBanner();
            } catch (Exception failure) {
                boundCamera=null;
                if(desiredCaptureEdge()>640){
                    detailCaptureUnavailable=true;
                    Log.w("AssemblyActivity","Native detail stream unsupported; keeping the 640px camera",failure);
                    previewView.post(()->startCamera(visionGeneration));
                    return;
                }
                boundCamera = null;
                Log.e("AssemblyActivity", "Camera failed to start", failure);
                if (isVisionGenerationActive(generation)) {
                    setBanner("Rear camera unavailable · automatic validation is required",
                            false, R.drawable.bg_camera_warning);
                }
            } finally {
                cameraBindingInProgress = false;
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void attachVisionAnalyzer(int generation) {
        if (!isVisionGenerationActive(generation) || recognizer == null
                || imageAnalysis == null || analyzer != null) return;
        final int bankGeneration=referenceBankGeneration;
        analyzer = new AssemblyVisionAnalyzer(recognizer,
                new AssemblyVisionAnalyzer.RequestProvider() {
                    @Override
                    public RecognitionRequest requestFor(
                            AssemblyVisionAnalyzer.FrameMetadata metadata) {
                        return recognitionRequestFor(metadata);
                    }

                    @Override
                    public boolean isAnalysisSuspended() {
                        PhaseAssemblyController phases = phaseController;
                        return !referenceBankMatchesRequest() || phases != null && phases.isAnalysisSuspended();
                    }
                    @Override public RecognitionRequest requestForTracking(AssemblyVisionAnalyzer.FrameMetadata frame){return phaseController==null?null:phaseController.visualRequestFor(calibrationProvider==null?null:calibrationProvider.forFrame(frame));}
                    @Override public boolean isTrackingSuspended(){return !activityResumed||diagnosticsModal||libraryPreparing||phaseController==null||!phaseController.isCameraModeFocused();}
                    @Override public boolean requiresMetricTracking(){return dev.chyken.assembl_ar.diagnostics.DiagnosticSession.INSTANCE.runner.running();}
                },
                new AssemblyVisionAnalyzer.Callback() {
                    @Override public void onDiagnosticSample(dev.chyken.assembl_ar.diagnostics.DiagnosticSample sample){
                        int visible=assemblyVisibilityGeneration;
                        runOnUiThread(()->{if(activityResumed&&visible==assemblyVisibilityGeneration&&isVisionGenerationActive(generation)&&bankGeneration==referenceBankGeneration&&phaseController!=null
                                &&sample.step.equals(String.valueOf(phaseController.currentTag()))){
                            syncDiagnosticScope();
                            showDiagnosticSnapshot(diagnosticLocker.stream(phaseController.currentTag(),sample.telemetry));
                        }});
                    }
                    @Override public void onTargetTracking(dev.chyken.assembl_ar.vision.TargetBoundingTracker.Target target,AssemblyVisionAnalyzer.FrameMetadata frame,Object tag){
                        int visible=assemblyVisibilityGeneration;
                        outlineUpdates.submit(()->{if(!activityResumed||visible!=assemblyVisibilityGeneration||!isVisionGenerationActive(generation)||bankGeneration!=referenceBankGeneration||phaseController!=null&&!phaseController.isCameraModeFocused()||target!=null&&(analyzer==null||target.epoch!=analyzer.visualEpoch()))return;
                            dev.chyken.assembl_ar.ui.DynamicComponentOutlineOverlayView overlay=findViewById(R.id.dynamicOutlineOverlay);
                            java.util.List<float[]> regions=new java.util.ArrayList<>();if(target!=null)for(java.util.List<VisionPoint> region:target.regions)regions.add(dev.chyken.assembl_ar.vision.PreviewOutlineCoordinates.normalize(region,frame.getAnalyzedWidth(),frame.getAnalyzedHeight(),previewView.getWidth(),previewView.getHeight()));
                            overlay.show(target,regions);
                            if(target!=null&&!target.predicted&&target.state==dev.chyken.assembl_ar.vision.TargetBoundingTracker.State.WRONG&&!target.name.equals(target.expectedName))
                                voiceGuide.speak("Incorrect item detected. You are scanning "+target.name+" instead of "+target.expectedName+".","outline-wrong:"+tag+":"+target.componentId);
                        });
                    }
                    @Override
                    public void onResult(RecognitionResult result) {
                        onResultWithTelemetry(result,null);
                    }
                    @Override public void onResultWithTelemetry(RecognitionResult result,dev.chyken.assembl_ar.diagnostics.DiagnosticSample sample){
                        long completedAt = SystemClock.elapsedRealtimeNanos();
                        int resultVisibilityGeneration = assemblyVisibilityGeneration;
                        runOnUiThread(() -> {
                            if (isVisionGenerationActive(generation)&&bankGeneration==referenceBankGeneration) {
                                // Do not restore board/component evidence queued
                                // before a pause/resume transition in any step.
                                if (resultVisibilityGeneration
                                        != assemblyVisibilityGeneration) return;
                                pairedDiagnosticSample=sample;
                                try{handleRecognition(result, completedAt);}finally{pairedDiagnosticSample=null;}
                            }
                        });
                    }

                    @Override public void onTracking(RecognitionResult.SpatialAnchor anchor,AssemblyVisionAnalyzer.FrameMetadata frame,CameraCalibration calibration,boolean predicted,Object tag){
                        int trackingGeneration=assemblyVisibilityGeneration;
                        runOnUiThread(()->{if(!activityResumed||trackingGeneration!=assemblyVisibilityGeneration||!isVisionGenerationActive(generation)||bankGeneration!=referenceBankGeneration||phaseController!=null&&phaseController.isAnalysisSuspended())return;
                            RecognitionRequest active=activeReferenceRequest();if(active==null||!java.util.Objects.equals(active.getRequestTag(),tag))return;
                            calibrationByFrame.remove(frame.getTimestampNanos());
                            dev.chyken.assembl_ar.ui.TargetAlignmentBoxView guide=findViewById(R.id.targetAlignmentBox);if(guide!=null)guide.setTrackingState(anchor!=null&&!predicted,predicted);
                            arHudView.setBoardCorners(anchor==null?null:normalizeCorners(anchor.getProjectedCorners()));
                            dev.chyken.assembl_ar.render.ArOverlayRenderer.track(arGlView,anchor);
                        });
                    }
                    @Override
                    public void onError(Throwable failure) {
                        runOnUiThread(() -> {
                            PhaseAssemblyController phases = phaseController;
                            if (phases != null && isVisionGenerationActive(generation)&&bankGeneration==referenceBankGeneration) {
                                phases.onAnalysisError();
                            } else if (isVisionGenerationActive(generation)&&bankGeneration==referenceBankGeneration) {
                                if (isOnFinalGuidedStep()) {
                                    setBanner("Hold steady while recognition recovers…",
                                            true, R.drawable.bg_live_pill);
                                } else {
                                    showCurrentGuidedStepBanner();
                                }
                            }
                        });
                    }
                }, visionConfig, true, modelPartRecognizer);
        analyzer.attachMotion(this);
        if(currentStep!=null)analyzer.preloadTrainedPhase(currentStep.getStepNumber());
        imageAnalysis.setAnalyzer(cameraExecutor, analyzer);
    }

    private boolean focusCamera(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        previewView.performClick();
        Camera camera = boundCamera;
        if (!activityResumed || camera == null) return true;
        MeteringPoint point = previewView.getMeteringPointFactory().createPoint(
                event.getX(), event.getY());
        FocusMeteringAction action = new FocusMeteringAction.Builder(
                point, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(4, TimeUnit.SECONDS).build();
        camera.getCameraControl().startFocusAndMetering(action);
        return true;
    }

    private void waitForPreviewViewPort(int generation) {
        if (!isVisionGenerationActive(generation)) return;
        cameraLayoutGeneration = generation;
        if (cameraLayoutListener != null) return;
        cameraLayoutListener = (view, left, top, right, bottom,
                                oldLeft, oldTop, oldRight, oldBottom) ->
                resumeCameraWhenViewPortReady();
        previewView.addOnLayoutChangeListener(cameraLayoutListener);
        previewView.post(this::resumeCameraWhenViewPortReady);
    }

    private void resumeCameraWhenViewPortReady() {
        if (cameraLayoutListener == null || previewView.getViewPort() == null) {
            return;
        }
        int generation = cameraLayoutGeneration;
        previewView.removeOnLayoutChangeListener(cameraLayoutListener);
        cameraLayoutListener = null;
        if (isVisionGenerationActive(generation)) {
            startCamera(generation);
        }
    }

    @Nullable
    private RecognitionRequest recognitionRequestFor(AssemblyVisionAnalyzer.FrameMetadata metadata) {
        analyzedWidth = metadata.getAnalyzedWidth();
        analyzedHeight = metadata.getAnalyzedHeight();
        AssemblyStep step = currentStep;
        if (step == null) return null;
        CameraCalibration calibration = calibrationProvider.forFrame(metadata);
        if (calibrationByFrame.size() > 16) calibrationByFrame.clear();
        calibrationByFrame.put(metadata.getTimestampNanos(), new CameraFrameState(calibration,
                metadata.getAnalyzedWidth(), metadata.getAnalyzedHeight()));
        latestCalibration = calibration;
        PhaseAssemblyController phases = phaseController;
        if (phases != null) return phases.requestFor(calibration);
        if (!guidedStepRecognitionEnabled) {
            // Instruction cards still observe the board; only component validation is paused.
            return new RecognitionRequest(null, calibration, null,
                    spatialAnchorComponentId(step), 1, step.getStepNumber());
        }
        if (missingRecognitionSubject() != null) {
            return new RecognitionRequest(null, calibration, null,
                    spatialAnchorComponentId(step), 1, step.getStepNumber());
        }
        GuidedCheckpoint checkpoint = activeCheckpoint;
        if (checkpoint != null) {
            if (awaitingPartScan) {
                // Identify the loose part or bracket set first. Installed-motor inspection and slot validation
                // begin only after this automatic introduction, without a Next/confirm tap.
                return new RecognitionRequest(
                        RecognitionRequest.AssemblyExpectation.component(checkpoint.getComponentId()),
                        calibration, null, null, introductionInstanceCount(), step.getStepNumber());
            }
            RecognitionRequest request = new RecognitionRequest(
                    RecognitionRequest.AssemblyExpectation.component(checkpoint.getComponentId()),
                    calibration, null, checkpoint.getAnchorComponentId(),
                    checkpoint.getExpectedInstanceCount(), step.getStepNumber());
            if (checkpoint.getBracketSlot() != 0) {
                return request.withGuidedBracketSlot(checkpoint.getBracketSlot());
            }
            return request;
        }
        if (isManualOnlyFinalCheck(step)) {
            return new RecognitionRequest(null, calibration, null,
                spatialAnchorComponentId(step), 1, step.getStepNumber());
        }
        String expectedId = primaryComponentId(step);
        // Once a chassis is present, orientation is evaluated in its local frame by the authored
        // relative-placement gate. Absolute screen-up would reject a correct robot merely because
        // the learner rotated the whole work surface under the camera.
        Double expectedOrientation = orientationSensitive(step)
                && spatialAnchorComponentId(step) == null ? 0.0 : null;
        RecognitionRequest.PolarityRule polarityRule = polarityRuleFor(step);
        RecognitionRequest.AssemblyExpectation expectation = new RecognitionRequest.AssemblyExpectation(
                expectedId, expectedOrientation, expectedOrientation == null ? null : 30.0,
                polarityRule);
        return new RecognitionRequest(
                expectation, calibration, null, spatialAnchorComponentId(step),
                expectedInstanceCount(step), step.getStepNumber());
    }

    private boolean isManualOnlyFinalCheck(AssemblyStep step) {
        return SectionEvidencePolicy.isPhysicalFinal(step.getStepNumber()) && isOnFinalGuidedStep();
    }

    @Nullable
    private static RecognitionRequest.PolarityRule polarityRuleFor(AssemblyStep step) {
        if (step == null || !step.getOverlayTypes().contains(OverlayType.POLARITY_ARROW)) {
            return null;
        }
        // Anchors are normalized within the registered component quadrilateral. Administratively
        // captured variants should refine these defaults when their connector geometry differs.
        VisionPoint positive;
        VisionPoint negative;
        switch (step.getStepNumber()) {
            case 5: // Red/black motor leads at the driver terminals.
                positive = new VisionPoint(0.43, 0.49);
                negative = new VisionPoint(0.54, 0.49);
                break;
            case 7: // VCC/VIN and GND paths in the authored wiring diagram.
                positive = new VisionPoint(0.72, 0.40);
                negative = new VisionPoint(0.72, 0.34);
                break;
            case 15: // Red edge stripe and opposite edge of the keyed ribbon cable.
                positive = new VisionPoint(0.68, 0.55);
                negative = new VisionPoint(0.43, 0.55);
                break;
            default:
                positive = new VisionPoint(0.30, 0.50);
                negative = new VisionPoint(0.70, 0.50);
                break;
        }
        // The tightly spaced VCC/GND markers cannot achieve a 0.05 normalized margin even
        // when observed exactly. Keep a margin smaller than their separation, while still
        // requiring a decisive assignment in both directions.
        return new RecognitionRequest.PolarityRule(positive, negative, 0.24,
                step.getStepNumber() == 7 ? 0.01 : 0.02);
    }

    private void handleRecognition(RecognitionResult result) {
        handleRecognition(result, Long.MIN_VALUE);
    }

    /** @param completedAtNanos when analysis finished, or Long.MIN_VALUE when unknown */
    private void handleRecognition(RecognitionResult result, long completedAtNanos) {
        if (result == null||diagnosticsModal) return;
        CameraFrameState frame = calibrationByFrame.remove(result.getFrameTimestampNanos());
        PhaseAssemblyController phases = phaseController;
        if (destroyed || !activityResumed || currentStep == null
                || (phases == null
                && result.getAssemblyStepNumber() != currentStep.getStepNumber())
                || result.getFrameTimestampNanos() < acceptRecognitionAfterNanos) return;
        // Debug phase navigation can show a phase other than the database's current manual step.
        // The phase tag, rather than currentStep, identifies its live analyzer request.
        if (phases != null && !phases.isFrameForCurrentPhase(result)) return;
        if (result.getFrameTimestampNanos() <= lastHandledCaptureNanos) return;
        lastHandledCaptureNanos = result.getFrameTimestampNanos();
        syncDiagnosticScope();
        if(result.getDiagnosticMetrics()!=null&&result.getDiagnosticMetrics().mismatch!=null){String warning=result.getDiagnosticMetrics().mismatch;calibrationByFrame.remove(result.getFrameTimestampNanos());updateDiagnostics(result);arHudView.update(ArHudView.HudState.MISMATCH,"Incorrect component",null);arGlView.hideGuidance();setBanner(warning,false,R.drawable.bg_camera_warning);voiceGuide.speak(warning,"global-mismatch:"+result.getRequestTag());return;}
        if (frame != null) {
            latestCalibration = frame.calibration;
            analyzedWidth = frame.width;
            analyzedHeight = frame.height;
        }
        frameEvidenceNanos = evidenceClock.observe(result.getFrameTimestampNanos(), completedAtNanos);
        if (phases != null) {
            updateDiagnostics(result);
            RecognitionResult.BoardObservation phaseBoard = result.getBoardObservation();
            arHudView.setBoardCorners(normalizeCorners(phaseBoard == null || !phaseBoard.isDetected() ? null
                    : phaseBoard.getAnchor() != null ? phaseBoard.getAnchor().getProjectedCorners()
                    : phaseBoard.getDetection().getProjectedCorners()));
            if (phaseBoard != null && phaseBoard.isHeld()) return;
            phases.onResult(result, frameEvidenceNanos);
            refreshPlanFHelp();
            if(phases.currentScanVerified()&&pairedDiagnosticSample!=null){
                diagnosticLocker.freeze(result.getRequestTag(),pairedDiagnosticSample.telemetry);
                showDiagnosticSnapshot(diagnosticLocker.snapshot());
            }
            logTrialFrame(result, completedAtNanos);
            return;
        }
        boolean bracketSetScan = awaitingPartScan && introductionInstanceCount() > 1;
        AssemblyStep frameStep = currentStep;
        GuidedCheckpoint frameCheckpoint = activeCheckpoint;
        int frameGuidedIndex = currentGuidedStepIndex;
        updateDiagnostics(result);
        RecognitionResult.BoardObservation heldBoard = result.getBoardObservation();
        if (heldBoard != null && heldBoard.isHeld()) {
            arHudView.setBoardCorners(normalizeCorners(
                    heldBoard.getAnchor().getProjectedCorners()));
            return;
        }
        handleComponentRecognition(result);
        if (currentStep != frameStep || activeCheckpoint != frameCheckpoint
                || currentGuidedStepIndex != frameGuidedIndex) return;
        // Keep board presence visible even when a component/pose/polarity gate is failing.
        RecognitionResult.BoardObservation board = result.getBoardObservation();
        List<VisionPoint> boardCorners = board == null || !board.isDetected() ? null
                : board.getAnchor() != null ? board.getAnchor().getProjectedCorners()
                : board.getDetection().getProjectedCorners();
        arHudView.setBoardCorners(normalizeCorners(boardCorners));
        showFrameDetections(result, bracketSetScan);
        logTrialFrame(result, completedAtNanos);
    }

    private void logTrialFrame(RecognitionResult result, long completedAtNanos) {
        TrialLog log = trialLog;
        if (log != null) {
            log.frame(result, completedAtNanos, frameEvidenceNanos, result.getRequestTag(),
                    recognitionText.getText().toString());
        }
    }

    /** Displays every localized part while leaving all checkpoint decisions to the existing gates. */
    private void showFrameDetections(RecognitionResult result, boolean bracketSetScan) {
        if (completionInFlight) {
            arHudView.setDetectedParts(null);
            return;
        }
        String expectedId = shouldHandleRecognition(result)
                ? (activeCheckpoint == null ? primaryComponentId(currentStep)
                : activeCheckpoint.getComponentId()) : null;
        String primaryId = result.getComponentId();
        MotorAssemblyObservation motors = result.getMotorAssembly();
        boolean motorSlotsVisible = motors != null
                && motors.getQuality() == MotorAssemblyObservation.Quality.GOOD;
        boolean strictBracketSet = bracketSetScan
                && NeobotSeedData.MOTOR_BRACKET.equals(primaryId);
        List<ArHudView.DetectedPart> visible = new ArrayList<>();
        List<String> shownIds = new ArrayList<>();
        List<List<VisionPoint>> shownCorners = new ArrayList<>();
        if (motorSlotsVisible) {
            addDetectedMotor(motors.getLeft(), "left", visible, shownIds, shownCorners);
            addDetectedMotor(motors.getRight(), "right", visible, shownIds, shownCorners);
        }

        if (primaryId != null && (!NeobotSeedData.MAIN_BOARD.equals(primaryId)
                || NeobotSeedData.MAIN_BOARD.equals(expectedId))) {
            int visibleBeforePrimary = visible.size();
            List<RecognitionResult.InstanceObservation> instances = strictBracketSet
                    ? result.getDetectedInstances(primaryId) : result.getInstanceObservations();
            for (RecognitionResult.InstanceObservation instance : instances) {
                if (primaryId.equals(instance.getComponentId())
                        && instance.getDetectionMethod()
                        != RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY) {
                    boolean selectedPrimary = sameHudFootprint(
                            instance.getProjectedCorners(), result.getProjectedCorners(), true);
                    addDetectedPart(result, expectedId, primaryId,
                            instance.getProjectedCorners(), selectedPrimary,
                            !(motorSlotsVisible && NeobotSeedData.GEAR_MOTOR.equals(primaryId)),
                            visible, shownIds, shownCorners);
                }
            }
            if (!strictBracketSet && visible.size() == visibleBeforePrimary
                    && result.getDetectionMethod() != RecognitionResult.DetectionMethod.NONE
                    && result.getDetectionMethod()
                    != RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY) {
                addDetectedPart(result, expectedId, primaryId,
                        result.getProjectedCorners(), true,
                        !(motorSlotsVisible && NeobotSeedData.GEAR_MOTOR.equals(primaryId)),
                        visible, shownIds, shownCorners);
            }
        }

        for (RecognitionResult.InstanceObservation scene : result.getSceneDetections()) {
            String componentId = scene.getComponentId();
            if (NeobotSeedData.MAIN_BOARD.equals(componentId)
                    || bracketSetScan && NeobotSeedData.MOTOR_BRACKET.equals(componentId)) continue;
            addDetectedPart(result, expectedId, componentId,
                    scene.getProjectedCorners(), false, false,
                    visible, shownIds, shownCorners);
        }
        arHudView.setDetectedParts(visible);
    }

    private void addDetectedMotor(MotorAssemblyObservation.Slot slot, String side,
                                  List<ArHudView.DetectedPart> visible,
                                  List<String> shownIds,
                                  List<List<VisionPoint>> shownCorners) {
        float[] outline = normalizeCorners(slot.getProjectedCorners());
        if (outline == null || slot.getState() == MotorAssemblyObservation.SlotState.MISSING) return;
        for (int index = 0; index < shownCorners.size(); index++) {
            if (sameHudFootprint(slot.getProjectedCorners(), shownCorners.get(index),
                    NeobotSeedData.GEAR_MOTOR.equals(shownIds.get(index)))) return;
        }
        ArHudView.PartStatus status = slot.getState()
                == MotorAssemblyObservation.SlotState.MISALIGNED
                ? ArHudView.PartStatus.INVALID
                : slot.isAccepted() && arHudView.getHudState() == ArHudView.HudState.MATCHED
                ? ArHudView.PartStatus.ACCEPTED : ArHudView.PartStatus.PENDING;
        String label = status == ArHudView.PartStatus.INVALID ? "Misaligned " + side + " motor"
                : status == ArHudView.PartStatus.ACCEPTED ? side + " motor"
                : "Checking " + side + " motor";
        visible.add(new ArHudView.DetectedPart(label, outline, status));
        shownIds.add(NeobotSeedData.GEAR_MOTOR);
        shownCorners.add(slot.getProjectedCorners());
    }

    private void addDetectedPart(RecognitionResult result, String expectedId, String componentId,
                                 List<VisionPoint> corners, boolean selectedPrimary,
                                 boolean primaryGroup,
                                 List<ArHudView.DetectedPart> visible, List<String> shownIds,
                                 List<List<VisionPoint>> shownCorners) {
        if (corners == null || corners.size() != 4) return;
        for (int index = 0; index < shownCorners.size(); index++) {
            if (sameHudFootprint(corners, shownCorners.get(index),
                    componentId.equals(shownIds.get(index)))) return;
        }
        float[] outline = normalizeCorners(corners);
        if (outline == null) return;
        ArHudView.PartStatus status = hudPartStatus(result, expectedId, componentId,
                corners, selectedPrimary, primaryGroup);
        String label = shownIds.contains(componentId)
                && status != ArHudView.PartStatus.INVALID
                ? result.getMotorAssembly() != null
                && NeobotSeedData.GEAR_MOTOR.equals(componentId) ? "Other motor" : ""
                : hudPartLabel(componentId, expectedId, status);
        visible.add(new ArHudView.DetectedPart(label, outline, status));
        shownIds.add(componentId);
        shownCorners.add(corners);
    }

    private ArHudView.PartStatus hudPartStatus(RecognitionResult result, String expectedId,
                                               String componentId, List<VisionPoint> corners,
                                               boolean selectedPrimary,
                                               boolean primaryGroup) {
        if (expectedId != null && expectedId.equals(componentId)) {
            if (selectedPrimary && (result.getValidationState()
                    == RecognitionResult.ValidationState.WRONG_ORIENTATION
                    || result.getValidationState()
                    == RecognitionResult.ValidationState.POLARITY_ERROR)) {
                return ArHudView.PartStatus.INVALID;
            }
            boolean recognized = result.getValidationState()
                    == RecognitionResult.ValidationState.EXPECTED_COMPONENT
                    || result.getValidationState()
                    == RecognitionResult.ValidationState.RECOGNIZED;
            return primaryGroup && recognized
                    && arHudView.getHudState() == ArHudView.HudState.MATCHED
                    ? ArHudView.PartStatus.ACCEPTED : ArHudView.PartStatus.PENDING;
        }
        if (expectedId == null) return ArHudView.PartStatus.OTHER;
        if (selectedPrimary && (awaitingPartScan || activeCheckpoint != null
                && activeCheckpoint.getKind() == GuidedCheckpoint.Kind.IDENTITY)) {
            return ArHudView.PartStatus.INVALID;
        }
        boolean placementActive = !awaitingPartScan && !currentCheckpointComplete
                && (activeCheckpoint != null
                && activeCheckpoint.getKind() == GuidedCheckpoint.Kind.PLACEMENT
                || activeCheckpoint == null && shouldHandleRecognition(result));
        return placementActive && occupiesCurrentTarget(result, corners)
                ? ArHudView.PartStatus.INVALID : ArHudView.PartStatus.OTHER;
    }

    /** A known other part is wrong only when it occupies the slot needed now. */
    private boolean occupiesCurrentTarget(RecognitionResult result, List<VisionPoint> corners) {
        RecognitionResult.SpatialAnchor anchor = result.getSpatialAnchor();
        if (anchor == null || corners == null || corners.size() != 4) return false;
        ChassisRelativePlacementGate gate = activeCheckpoint != null
                && activeCheckpoint.getKind() == GuidedCheckpoint.Kind.PLACEMENT
                ? activeCheckpoint.getPlacementGate()
                : ChassisRelativePlacementGate.forStep(currentStep);
        if (gate == null) return false;
        double centerX = 0.0, centerY = 0.0;
        for (VisionPoint corner : corners) {
            centerX += corner.getX() * 0.25;
            centerY += corner.getY() * 0.25;
        }
        for (ChassisRelativePlacementGate.TargetProjection target
                : gate.projectTargets(anchor.getProjectedCorners())) {
            if (pointInsideExpandedQuad(centerX, centerY, target.getCorners())) return true;
        }
        return false;
    }

    /** A small margin absorbs projected-corner jitter without including a rotated target's AABB. */
    private static boolean pointInsideExpandedQuad(double x, double y, List<VisionPoint> quad) {
        if (quad == null || quad.size() != 4) return false;
        double centerX = 0.0, centerY = 0.0;
        for (VisionPoint corner : quad) {
            centerX += corner.getX() * 0.25;
            centerY += corner.getY() * 0.25;
        }
        int winding = 0;
        for (int index = 0; index < 4; index++) {
            VisionPoint first = quad.get(index);
            VisionPoint second = quad.get((index + 1) % 4);
            double ax = centerX + (first.getX() - centerX) * 1.12;
            double ay = centerY + (first.getY() - centerY) * 1.12;
            double bx = centerX + (second.getX() - centerX) * 1.12;
            double by = centerY + (second.getY() - centerY) * 1.12;
            double cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
            if (Math.abs(cross) <= 1e-9) continue;
            int edgeSide = cross > 0.0 ? 1 : -1;
            if (winding != 0 && winding != edgeSide) return false;
            winding = edgeSide;
        }
        return winding != 0;
    }

    private String hudPartLabel(String componentId, String expectedId,
                                ArHudView.PartStatus status) {
        Component component = componentById.get(componentId);
        String name = component == null ? componentId : component.getName();
        if (status == ArHudView.PartStatus.INVALID) {
            return componentId.equals(expectedId) ? "Check: " + name : "Wrong part: " + name;
        }
        if (status == ArHudView.PartStatus.OTHER) return "Other: " + name;
        if (status == ArHudView.PartStatus.PENDING) return "Checking: " + name;
        return name;
    }

    private static boolean sameHudFootprint(List<VisionPoint> first,
                                            List<VisionPoint> second, boolean sameIdentity) {
        if (first == null || second == null || first.size() != 4 || second.size() != 4) {
            return false;
        }
        double firstMinX = Double.POSITIVE_INFINITY, firstMinY = Double.POSITIVE_INFINITY;
        double firstMaxX = Double.NEGATIVE_INFINITY, firstMaxY = Double.NEGATIVE_INFINITY;
        double secondMinX = Double.POSITIVE_INFINITY, secondMinY = Double.POSITIVE_INFINITY;
        double secondMaxX = Double.NEGATIVE_INFINITY, secondMaxY = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < 4; index++) {
            VisionPoint a = first.get(index), b = second.get(index);
            firstMinX = Math.min(firstMinX, a.getX());
            firstMinY = Math.min(firstMinY, a.getY());
            firstMaxX = Math.max(firstMaxX, a.getX());
            firstMaxY = Math.max(firstMaxY, a.getY());
            secondMinX = Math.min(secondMinX, b.getX());
            secondMinY = Math.min(secondMinY, b.getY());
            secondMaxX = Math.max(secondMaxX, b.getX());
            secondMaxY = Math.max(secondMaxY, b.getY());
        }
        double firstArea = (firstMaxX - firstMinX) * (firstMaxY - firstMinY);
        double secondArea = (secondMaxX - secondMinX) * (secondMaxY - secondMinY);
        if (!(firstArea > 0.0) || !(secondArea > 0.0)) return false;
        double intersection = Math.max(0.0,
                Math.min(firstMaxX, secondMaxX) - Math.max(firstMinX, secondMinX))
                * Math.max(0.0,
                Math.min(firstMaxY, secondMaxY) - Math.max(firstMinY, secondMinY));
        double overlap = sameIdentity ? intersection / Math.min(firstArea, secondArea)
                : intersection / (firstArea + secondArea - intersection);
        return overlap >= (sameIdentity ? 0.35 : 0.60);
    }

    private void handleComponentRecognition(RecognitionResult result) {
        if (!shouldHandleRecognition(result)) return;
        if (missingRecognitionSubject() != null) {
            showMissingReference();
            return;
        }
        if (activeCheckpoint != null) {
            handleGuidedCheckpointRecognition(result);
            return;
        }
        if (!activityResumed || physicalCheckInProgress) return;
        if (isMotorValidationStep()) {
            handleMotorAssembly(result);
            return;
        }
        lastSectionResultAt = SystemClock.uptimeMillis();
        recognitionText.removeCallbacks(expireSectionReadiness);
        recognitionText.postDelayed(expireSectionReadiness, resultExpiryMillis());
        // Continue validating live placement until the learner starts the physical check.
        // Placement checks remain current until the learner starts the physical check.
        revokeMotorReadiness();
        RecognitionResult.ValidationState validation = result.getValidationState();
        // A visible chassis is useful context, never the wrong part for an installation step.
        String anchorId = spatialAnchorComponentId(currentStep);
        if (anchorId != null && (anchorId.equals(result.getComponentId())
                || result.getValidationState() == RecognitionResult.ValidationState.NO_COMPONENT)
                && result.getSpatialAnchor() != null) {
            RecognitionResult.SpatialAnchor base = result.getSpatialAnchor();
            float[] baseCorners = normalizeCorners(base.getProjectedCorners());
            VisualPlacementGate.Observation baseLock = anchorGate.observe(anchorId, baseCorners,
                    base.getPose() == null ? null : base.getPose().getTranslationVector(),
                    true, 0.010f, frameEvidenceNanos);
            logPlacementGate("board", result, baseLock);
            placementGate.reset();
            repeatedPlacementGate.reset();
            confirmPlacementButton.setVisibility(View.GONE);
            stateMachine.retryCurrentStep();
            arHudView.update(ArHudView.HudState.TRACKING, "Base board", baseCorners);
            if (baseLock.isReady()) showBaseGuidance(base);
            else arGlView.hideGuidance();
            Component target = componentById.get(primaryComponentId(currentStep));
            setBanner(baseLock.isReady() ? "Board locked · looking for "
                            + (target == null ? primaryComponentId(currentStep) : target.getName())
                            : "Board found · keep its edges visible",
                    true, R.drawable.bg_live_pill);
            return;
        }
        if (validation == RecognitionResult.ValidationState.NO_COMPONENT) {
            repeatedPlacementGate.reset();
            VisualPlacementGate.Observation placementMiss =
                    placementGate.observeUnavailable(frameEvidenceNanos);
            logPlacementGate("component", result, placementMiss);
            VisualPlacementGate.Observation anchorMiss = null;
            if (spatialAnchorComponentId(currentStep) != null) {
                anchorMiss = anchorGate.observeUnavailable(frameEvidenceNanos);
                logPlacementGate("board", result, anchorMiss);
            }
            int retainedFrames = anchorMiss == null
                    ? placementMiss.getStableFrames()
                    : Math.min(placementMiss.getStableFrames(),
                            anchorMiss.getStableFrames());
            int misses = ++unrecognizedFrames;
            if (retainedFrames == 0) {
                // A second consecutive miss (or an expired grace window) ends the lock attempt.
                resetStableTracking();
                arGlView.hideGuidance();
                arHudView.reset();
            } else {
                Component expectedComponent = componentById.get(primaryComponentId(currentStep));
                String expectedName = expectedComponent == null
                        ? primaryComponentId(currentStep) : expectedComponent.getName();
                setBanner("Tracking " + expectedName + " · hold position ("
                                + retainedFrames + "/" + STABLE_FRAME_GATE + ")",
                        true, R.drawable.bg_live_pill);
            }
            if (misses >= UNRECOGNIZED_FRAME_GATE) {
                stateMachine.onNotRecognized();
                setBanner(getString(R.string.component_not_recognized), false,
                        R.drawable.bg_camera_warning);
                arHudView.update(ArHudView.HudState.NOT_RECOGNIZED, null, null);
            }
            return;
        }
        unrecognizedFrames = 0;
        float[] corners = normalizeCorners(result.getProjectedCorners());
        String expectedId = primaryComponentId(currentStep);
        boolean expected = expectedId.equals(result.getComponentId());

        if (validation == RecognitionResult.ValidationState.COMPONENT_MISMATCH || !expected) {
            resetStableTracking();
            confirmPlacementButton.setVisibility(View.GONE);
            stateMachine.onComponentRecognized(result.getComponentId(), false);
            Component expectedComponent = componentById.get(expectedId);
            String expectedName = expectedComponent == null ? expectedId : expectedComponent.getName();
            setBanner("Detected " + safeName(result) + " · use " + expectedName,
                    false, R.drawable.bg_danger);
            arHudView.update(ArHudView.HudState.MISMATCH, safeName(result), corners);
            arGlView.hideGuidance();
            return;
        }
        if (validation == RecognitionResult.ValidationState.WRONG_ORIENTATION) {
            resetStableTracking();
            confirmPlacementButton.setVisibility(View.GONE);
            stateMachine.onComponentRecognized(result.getComponentId(), true);
            stateMachine.onWrongOrientation();
            setBanner(getString(R.string.wrong_orientation) + " · rotate the component",
                    false, R.drawable.bg_danger);
            arHudView.update(ArHudView.HudState.WRONG_ORIENTATION, safeName(result), corners);
            arGlView.hideGuidance();
            return;
        }
        if (!isGeometryOnlyResult(result) && (validation == RecognitionResult.ValidationState.ORIENTATION_UNCONFIRMED
                || orientationRequired(currentStep)
                && (result.getOrientationClassification()
                == RecognitionResult.OrientationClassification.UNKNOWN
                || !Double.isFinite(result.getOrientationDegrees())))) {
            resetStableTracking();
            confirmPlacementButton.setVisibility(View.GONE);
            stateMachine.onComponentRecognized(result.getComponentId(), true);
            stateMachine.onOrientationUnconfirmed();
            setBanner(getString(R.string.orientation_unconfirmed),
                    false, R.drawable.bg_camera_warning);
            arHudView.update(ArHudView.HudState.TRACKING, safeName(result), corners);
            arGlView.hideGuidance();
            return;
        }
        if (validation == RecognitionResult.ValidationState.POLARITY_ERROR) {
            resetStableTracking();
            confirmPlacementButton.setVisibility(View.GONE);
            stateMachine.onComponentRecognized(result.getComponentId(), true);
            stateMachine.onPolarityError();
            setBanner(getString(R.string.polarity_error) + " · swap the marked endpoints",
                    false, R.drawable.bg_danger);
            arHudView.update(ArHudView.HudState.POLARITY_ERROR, safeName(result), corners);
            arGlView.hideGuidance();
            return;
        }
        boolean polarityRequired = polarityRequired(currentStep);
        if (validation == RecognitionResult.ValidationState.POLARITY_UNCONFIRMED
                || polarityRequired
                && result.getPolarityState() != RecognitionResult.PolarityState.CORRECT) {
            resetStableTracking();
            confirmPlacementButton.setVisibility(View.GONE);
            stateMachine.onComponentRecognized(result.getComponentId(), true);
            stateMachine.onPolarityUnconfirmed();
            setBanner(getString(R.string.polarity_unconfirmed),
                    false, R.drawable.bg_camera_warning);
            arHudView.update(ArHudView.HudState.TRACKING, safeName(result), corners);
            arGlView.hideGuidance();
            return;
        }

        String requiredAnchorId = spatialAnchorComponentId(currentStep);
        RecognitionResult.SpatialAnchor spatialAnchor = result.getSpatialAnchor();
        VisualPlacementGate.Observation anchorPlacement = null;
        ChassisRelativePlacementGate relativeGate = null;
        ChassisRelativePlacementGate.Decision relativeDecision = null;
        if (requiredAnchorId != null) {
            if (spatialAnchor == null
                    || !requiredAnchorId.equals(spatialAnchor.getComponentId())) {
                resetStableTracking();
                confirmPlacementButton.setVisibility(View.GONE);
                arGlView.hideGuidance();
                Component anchorComponent = componentById.get(requiredAnchorId);
                String anchorName = anchorComponent == null
                        ? requiredAnchorId : anchorComponent.getName();
                setBanner(getString(R.string.spatial_anchor_missing, anchorName),
                        false, R.drawable.bg_camera_warning);
                arHudView.update(ArHudView.HudState.TRACKING, safeName(result), corners);
                return;
            }
            float[] anchorCorners = normalizeCorners(spatialAnchor.getProjectedCorners());
            anchorPlacement = anchorGate.observe(
                    spatialAnchor.getComponentId(),
                    anchorCorners,
                    spatialAnchor.getPose().getTranslationVector(),
                    true,
                    0.010f,
                    frameEvidenceNanos);
            logPlacementGate("board", result, anchorPlacement);
            if (!anchorPlacement.isFrameValid()) {
                placementGate.reset();
                confirmPlacementButton.setVisibility(View.GONE);
                arGlView.hideGuidance();
                setBanner(getString(R.string.spatial_anchor_hold),
                        false, R.drawable.bg_camera_warning);
                arHudView.update(ArHudView.HudState.TRACKING, safeName(result), corners);
                return;
            }
            relativeGate = ChassisRelativePlacementGate.forStep(currentStep);
            relativeDecision = relativeGate.evaluate(
                    spatialAnchor.getProjectedCorners(),
                    expectedInstanceQuads(result, expectedId));
        }

        double[] translation = result.getPose() == null
                ? null : result.getPose().getTranslationVector();
        VisualPlacementGate.Observation placement = placementGate.observe(
                result.getComponentId(), corners, translation,
                spatialPoseRequired(currentStep), minimumProjectedArea(currentStep),
                frameEvidenceNanos);
        logPlacementGate("component", result, placement);
        // A propagated pose keeps guidance alive, but completion requires current identity evidence.
        boolean anchorReady = (anchorPlacement == null || anchorPlacement.isReady())
                && !result.isAnchorTracked();
        boolean relativeAccepted = relativeGate == null
                || relativeDecision != null && relativeDecision.isAccepted();
        boolean repeatedStep = relativeGate != null
                && relativeGate.getRequiredDetectionCount() > 1;
        boolean relativeReady;
        if (relativeGate != null) {
            relativeReady = repeatedPlacementGate.observe(relativeAccepted && anchorReady
                    && placement.isFrameValid());
        } else {
            repeatedPlacementGate.reset();
            relativeReady = relativeAccepted;
        }
        arHudView.update(placement.isReady() && anchorReady && relativeReady
                        ? ArHudView.HudState.MATCHED : ArHudView.HudState.TRACKING,
                safeName(result), corners);
        if (result.getPose() != null) {
            RecognitionResult.Pose guidancePose = spatialAnchor == null
                    ? result.getPose() : spatialAnchor.getPose();
            ArGuidance guidance = NeobotGuidanceFactory.create(
                    currentStep,
                    result.getComponentId(),
                    guidancePose.getOpenGlModelViewMatrix());
            arGlView.showGuidance(guidance, cameraProjection());
        }
        stateMachine.onComponentRecognized(result.getComponentId(), true);
        stateMachine.onPoseEstimated(result.getPose() != null && anchorReady);
        stateMachine.beginGuidance();
        boolean orientationValid = isGeometryOnlyResult(result) || !orientationRequired(currentStep)
                || validation != RecognitionResult.ValidationState.WRONG_ORIENTATION
                && validation != RecognitionResult.ValidationState.ORIENTATION_UNCONFIRMED
                && result.getOrientationClassification()
                != RecognitionResult.OrientationClassification.UNKNOWN;
        boolean polarityValid = !polarityRequired
                || result.getPolarityState() == RecognitionResult.PolarityState.CORRECT;
        AssemblySession visualSession = stateMachine.onVisualValidation(
                placement.isReady() && anchorReady && relativeReady,
                orientationValid, polarityValid);
        if (!placement.isReady() || !anchorReady || !relativeReady
                || visualSession.getState() != AssemblyState.VISUALLY_VALIDATED) {
            confirmPlacementButton.setVisibility(View.GONE);
            if (!anchorReady) {
                setBanner(getString(R.string.spatial_anchor_stabilizing,
                                anchorPlacement == null ? 0 : anchorPlacement.getStableFrames(),
                                STABLE_FRAME_GATE),
                        true, R.drawable.bg_live_pill);
            } else if (placement.isReady()
                    && relativeDecision != null && !relativeDecision.isAccepted()) {
                setBanner(relativeDecision.getMessage(), false, R.drawable.bg_camera_warning);
            } else if (placement.isReady() && relativeGate != null && !relativeReady) {
                boolean holdingCompleteSet = repeatedStep
                        && relativeDecision != null && relativeDecision.isAccepted();
                String relativeMessage;
                if (holdingCompleteSet) {
                    relativeMessage = "Hold both components and the chassis steady · "
                            + repeatedPlacementGate.getAcceptedFrames() + "/"
                            + repeatedPlacementGate.getRequiredFrames();
                } else if (relativeDecision == null) {
                    relativeMessage =
                            "Keep every required component and the chassis visible together.";
                } else {
                    relativeMessage = relativeDecision.getMessage();
                }
                setBanner(relativeMessage,
                        holdingCompleteSet, R.drawable.bg_live_pill);
            } else {
                boolean recoveringPose = (placement.getReason()
                        == VisualPlacementGate.Reason.MISSING_POSE
                        || placement.getReason() == VisualPlacementGate.Reason.INVALID_POSE)
                        && placement.getStableFrames() > 0;
                setBanner(placementMessage(placement, safeName(result)),
                        placement.getReason() == VisualPlacementGate.Reason.HOLD_STEADY
                                || recoveringPose,
                        placement.isFrameValid() || recoveringPose
                                ? R.drawable.bg_live_pill : R.drawable.bg_camera_warning);
            }
            return;
        }

        stateMachine.requestPhysicalConfirmation();
        confirmPlacementButton.setVisibility(View.VISIBLE);
        setBanner(getString(R.string.validation_ready) + " · perform the physical check",
                false, R.drawable.bg_success);
    }

    /** Identification is separate from placement of the part on the authored workplane. */
    private void handleGuidedCheckpointRecognition(RecognitionResult result) {
        if (physicalCheckInProgress) return;
        if (awaitingPartScan && !handlePartIntroduction(result)) return;
        if (activeCheckpoint.getKind() == GuidedCheckpoint.Kind.PLACEMENT) {
            handleGuidedPlacement(result);
            return;
        }
        if (stateMachine.snapshot().getState() == AssemblyState.PHYSICAL_CONFIRMATION) {
            if (activeCheckpoint.getKind() == GuidedCheckpoint.Kind.IDENTITY) {
                completeGuidedCheckpoint();
                return;
            }
            confirmPlacementButton.setVisibility(View.VISIBLE);
            setBanner(getString(R.string.guided_checkpoint_ready), false, R.drawable.bg_success);
            return;
        }
        String expectedId = activeCheckpointComponentId;
        boolean expected = expectedId != null && expectedId.equals(result.getComponentId());
        boolean recognized = result.getValidationState() == RecognitionResult.ValidationState.EXPECTED_COMPONENT
                || result.getValidationState() == RecognitionResult.ValidationState.RECOGNIZED;
        float[] corners = normalizeCorners(result.getProjectedCorners());
        if (!expected || !recognized) {
            placementGate.reset();
            stateMachine.retryCurrentStep();
            confirmPlacementButton.setVisibility(View.GONE);
            arGlView.hideGuidance();
            String anchorId = spatialAnchorComponentId(currentStep);
            if (result.getComponentId() == null
                    || result.getValidationState() == RecognitionResult.ValidationState.NO_COMPONENT
                    || result.getComponentId().equals(anchorId)) {
                arHudView.reset();
                setBanner(getString(R.string.guided_checkpoint_scan, checkpointComponentName()),
                        true, R.drawable.bg_live_pill);
            } else {
                stateMachine.onComponentRecognized(result.getComponentId(), false);
                arHudView.update(ArHudView.HudState.MISMATCH, safeName(result), corners);
                setBanner("Detected " + safeName(result) + " · use " + checkpointComponentName(),
                        false, R.drawable.bg_danger);
            }
            return;
        }
        Component checkpointComponent = componentById.get(expectedId);
        // Reflective screw heads have a much smaller truthful box than whole modules. Keep the
        // four-frame pose/geometry gate without rejecting the supported small-hardware fallback.
        float minimumArea = checkpointComponent != null
                && checkpointComponent.getCategory() == ComponentCategory.FASTENER ? 0.00003f : 0.001f;
        VisualPlacementGate.Observation placement = placementGate.observe(expectedId, corners,
                result.getPose() == null ? null : result.getPose().getTranslationVector(),
                true, minimumArea, frameEvidenceNanos);
        logPlacementGate("guided-component", result, placement);
        arHudView.update(placement.isReady() ? ArHudView.HudState.MATCHED : ArHudView.HudState.TRACKING,
                checkpointComponentName(), corners);
        if (result.getPose() != null && placement.isFrameValid()) {
            // Highlight the recognized part itself, without reusing the section's completed-pair
            // ghosts or implying that a scan can establish hidden fastener tightness.
            arGlView.showGuidance(ArGuidance.builder(expectedId,
                            OverlayType.LABEL, OverlayType.TARGET_GLOW_RING)
                    .pose(result.getPose().getOpenGlModelViewMatrix())
                    .coordinateSpace(ArGuidance.CoordinateSpace.POSE_UNITS)
                    .ghostStyle(ArGuidance.GhostStyle.NONE)
                    .componentTarget(ArGuidance.Point3.at(0f, 0f, 0f))
                    .targetMarker(ArGuidance.TargetMarker.COMPONENT_PLACEMENT)
                    .markerRadius(4f)
                    .build(), cameraProjection());
        } else {
            arGlView.hideGuidance();
        }
        stateMachine.onComponentRecognized(expectedId, true);
        stateMachine.onPoseEstimated(placement.isReady());
        stateMachine.beginGuidance();
        AssemblySession visual = stateMachine.onVisualValidation(placement.isReady(), true, true);
        if (visual.getState() != AssemblyState.VISUALLY_VALIDATED) {
            confirmPlacementButton.setVisibility(View.GONE);
            setBanner(placementMessage(placement, checkpointComponentName()),
                    placement.isFrameValid(), R.drawable.bg_live_pill);
            return;
        }
        stateMachine.requestPhysicalConfirmation();
        // A stable identity scan is preparation, not an installation needing a separate tap.
        // The following action still has its own demonstration and physical confirmation.
        completeGuidedCheckpoint();
    }

    private boolean handlePartIntroduction(RecognitionResult result) {
        String expectedId = activeCheckpoint.getComponentId();
        boolean correct = expectedId.equals(result.getComponentId())
                && (result.getValidationState() == RecognitionResult.ValidationState.EXPECTED_COMPONENT
                || result.getValidationState() == RecognitionResult.ValidationState.RECOGNIZED)
                && (result.getRejectionEvidence() == null || result.getRejectionEvidence().getReason()
                != RecognitionResult.RejectionReason.IDENTITY_AMBIGUOUS);
        // A different or ambiguous part is not a brief pose dropout of the correct part.
        if (!correct) partIntroductionGate.reset();
        boolean scanSet = introductionInstanceCount() > 1;
        if (scanSet) bracketSetGate.observe(result, frameEvidenceNanos);
        VisualPlacementGate.Observation observed = correct && !scanSet
                ? partIntroductionGate.observe(expectedId, normalizeCorners(result.getProjectedCorners()),
                        null, false, .001f, frameEvidenceNanos)
                : partIntroductionGate.observeUnavailable(frameEvidenceNanos);
        if (!correct || !(scanSet ? bracketSetGate.isReady() : observed.isReady())) {
            confirmPlacementButton.setVisibility(View.GONE);
            arGlView.hideGuidance();
            boolean mismatch = result.getComponentId() != null && !expectedId.equals(result.getComponentId());
            boolean ambiguous = result.getRejectionEvidence() != null
                    && result.getRejectionEvidence().getReason() == RecognitionResult.RejectionReason.IDENTITY_AMBIGUOUS;
            arHudView.update(mismatch ? ArHudView.HudState.MISMATCH
                            : correct ? ArHudView.HudState.TRACKING : ArHudView.HudState.SEARCHING,
                    mismatch ? safeName(result) : checkpointComponentName(),
                    correct || mismatch ? normalizeCorners(result.getProjectedCorners()) : null);
            String prompt = mismatch ? getString(R.string.demo_wrong_part, safeName(result), checkpointComponentName())
                    : ambiguous ? getString(R.string.demo_ambiguous_part, checkpointComponentName())
                    : scanSet ? getString(R.string.demo_scan_set_progress, bracketSetGate.getVisibleCount(), introductionInstanceCount())
                    : correct ? getString(R.string.demo_scan_hold, checkpointComponentName())
                    : scanFramingHint(expectedId);
            setBanner(prompt, !mismatch && !ambiguous,
                    mismatch ? R.drawable.bg_danger : R.drawable.bg_live_pill);
            return false;
        }
        awaitingPartScan = false;
        renderRequiredPartReference();
        resetStableTracking();
        acceptRecognitionAfterNanos = Math.max(SystemClock.elapsedRealtimeNanos(),
                result.getFrameTimestampNanos() + 1L);
        startCurrentDemonstration();
        setBanner(getString(R.string.demo_part_found), false, R.drawable.bg_success);
        return false; // A scan frame never also supplies the installation confirmation.
    }

    private int introductionInstanceCount() {
        return currentStep != null && currentStep.getStepNumber() == 2 && activeCheckpoint != null
                && NeobotSeedData.MOTOR_BRACKET.equals(activeCheckpoint.getComponentId()) ? 4 : 1;
    }

    private String introductionPrompt() {
        return introductionInstanceCount() > 1
                ? getString(R.string.demo_scan_bracket_set, introductionInstanceCount())
                : scanFramingHint(activeCheckpoint.getComponentId());
    }

    private String scanFramingHint(String componentId) {
        return scanFramingHint(componentId, checkpointComponentName());
    }

    String scanFramingHint(String componentId, String componentName) {
        if (NeobotSeedData.MICROCONTROLLER.equals(componentId)) {
            return getString(R.string.demo_scan_nano);
        }
        if (NeobotSeedData.LINE_TRACKER.equals(componentId)) {
            return getString(R.string.demo_scan_tracker);
        }
        if (NeobotSeedData.DISTANCE_SENSOR.equals(componentId)) {
            return getString(R.string.demo_scan_ultrasonic);
        }
        if (NeobotSeedData.CASTER_WHEEL.equals(componentId)) {
            return getString(R.string.demo_scan_caster);
        }
        return getString(R.string.demo_scan_part, componentName);
    }

    private void renderRequiredPartReference() {
        Component part = componentById.get(currentDemo.getPartId());
        View reference = findViewById(R.id.requiredPartReference);
        reference.setVisibility(part == null ? View.GONE : View.VISIBLE);
        if (part == null) return;
        int quantity = 1;
        for (RequiredComponent required : currentStep.getRequiredComponents()) {
            if (part.getId().equals(required.getComponentId())) {
                quantity = required.getQuantity();
                break;
            }
        }
        requiredPartName.setText(part.getName());
        requiredPartQuantity.setText(getString(awaitingPartScan && introductionInstanceCount() > 1
                ? R.string.required_part_set_quantity : R.string.required_part_quantity, quantity));
        requiredPartThumbnail.setContentDescription(part.getName());
        AssetImageLoader.load(requiredPartThumbnail,
                partReferenceAsset(part));
        reference.setContentDescription(getString(R.string.required_part_preview_description, part.getName(), quantity));
    }

    @Nullable
    String partReferenceAsset(Component part) {
        // The legacy recognition source can contain the entire assembled board. Use the
        // reviewed loose-part instruction images for the parts needed in the opening build.
        switch (part.getId()) {
            case NeobotSeedData.MOTOR_BRACKET:
                return "references/instructional/gray/motor-bracket-half-gray.webp";
            case NeobotSeedData.GEAR_MOTOR:
                return "references/instructional/gray/gear-motor-gray.webp";
            case NeobotSeedData.MOTOR_DRIVER:
                return "references/instructional/gray/motor-driver-gray.webp";
            default:
                return part.getReferenceImagePaths().isEmpty() ? null : part.getReferenceImagePaths().get(0);
        }
    }

    private void handleGuidedPlacement(RecognitionResult result) {
        long timestamp = frameEvidenceNanos;
        if (timestamp <= lastCheckpointFrameNanos) return;
        if (lastCheckpointFrameNanos != Long.MIN_VALUE
                && timestamp - lastCheckpointFrameNanos
                > MotorAssemblyGate.MAXIMUM_FRAME_GAP_MILLIS * 1_000_000L) {
            resetStableTracking();
        }
        lastCheckpointFrameNanos = timestamp;
        lastSectionResultAt = SystemClock.uptimeMillis();
        recognitionText.removeCallbacks(expireSectionReadiness);
        recognitionText.postDelayed(expireSectionReadiness, resultExpiryMillis());
        revokeMotorReadiness();

        GuidedCheckpoint checkpoint = activeCheckpoint;
        RecognitionResult.SpatialAnchor anchor = result.getSpatialAnchor();
        boolean hasAnchor = anchor != null
                && checkpoint.getAnchorComponentId().equals(anchor.getComponentId());
        VisualPlacementGate.Observation lock = hasAnchor
                ? anchorGate.observe(anchor.getComponentId(), normalizeCorners(anchor.getProjectedCorners()),
                        anchor.getPose() == null ? null : anchor.getPose().getTranslationVector(),
                        true, 0.010f, timestamp)
                : anchorGate.observeUnavailable(timestamp);
        if (lock.getStableFrames() <= 1 && previousMotorAnchorFrames > 1) {
            motorGate.reset();
        }
        previousMotorAnchorFrames = lock.getStableFrames();
        if (!hasAnchor || !lock.isFrameValid()) {
            placementGate.reset();
            repeatedPlacementGate.reset();
            motorGate.observe(timestamp, false, false, false, false,
                    checkpoint.getMotorRequirement() != GuidedCheckpoint.MotorRequirement.FIRST);
            arGlView.hideGuidance();
            arHudView.reset();
            setBanner(hasAnchor ? placementMessage(lock, "assembly board") : checkpoint.getPrompt(),
                    true, R.drawable.bg_live_pill);
            return;
        }
        boolean anchorReady = lock.isReady() && !result.isAnchorTracked();
        arGlView.hideGuidance();
        showCheckpointTargets(anchor, false);
        if (checkpoint.getMotorRequirement() != GuidedCheckpoint.MotorRequirement.NONE) {
            handleGuidedMotorPlacement(result, lock, anchorReady);
            return;
        }

        String expectedId = checkpoint.getComponentId();
        boolean expected = expectedId.equals(result.getComponentId())
                && (result.getValidationState() == RecognitionResult.ValidationState.EXPECTED_COMPONENT
                || result.getValidationState() == RecognitionResult.ValidationState.RECOGNIZED);
        VisualPlacementGate.Observation part = expected
                ? placementGate.observe(expectedId, normalizeCorners(result.getProjectedCorners()),
                        result.getPose() == null ? null : result.getPose().getTranslationVector(),
                        true, 0.001f, timestamp)
                : placementGate.observeUnavailable(timestamp);
        List<List<VisionPoint>> observed = expectedInstanceQuads(result, expectedId);
        if (expected && observed.isEmpty() && result.getProjectedCorners().size() == 4) {
            observed.add(result.getProjectedCorners());
        }
        ChassisRelativePlacementGate.Decision relative = checkpoint.getPlacementGate().evaluate(
                anchor.getProjectedCorners(), observed);
        boolean ready = repeatedPlacementGate.observe(expected && part.isReady()
                && anchorReady && relative.isAccepted());
        arHudView.update(ready ? ArHudView.HudState.MATCHED : ArHudView.HudState.TRACKING,
                checkpointComponentName(), expected ? normalizeCorners(result.getProjectedCorners()) : null);
        showCheckpointTargets(anchor, ready);
        if (ready) {
            requestGuidedPlacementConfirmation();
        } else if (!anchorReady) {
            setBanner(result.isAnchorTracked() ? "Show the board details to confirm its position"
                    : getString(R.string.spatial_anchor_stabilizing,
                            lock.getStableFrames(), STABLE_FRAME_GATE), true, R.drawable.bg_live_pill);
        } else if (!expected) {
            setBanner(checkpoint.getPrompt(), true, R.drawable.bg_live_pill);
        } else if (!relative.isAccepted()) {
            setBanner(relative.getMessage(), false, R.drawable.bg_camera_warning);
        } else if (!part.isReady()) {
            setBanner(placementMessage(part, checkpointComponentName()), true, R.drawable.bg_live_pill);
        } else {
            setBanner("Hold this placement steady · " + repeatedPlacementGate.getAcceptedFrames()
                    + "/" + STABLE_FRAME_GATE, true, R.drawable.bg_live_pill);
        }
    }

    private void handleGuidedMotorPlacement(RecognitionResult result,
                                           VisualPlacementGate.Observation lock, boolean anchorReady) {
        boolean both = activeCheckpoint.getMotorRequirement() == GuidedCheckpoint.MotorRequirement.BOTH;
        MotorAssemblyObservation observation = result.getMotorAssembly();
        boolean quality = observation != null
                && observation.getQuality() == MotorAssemblyObservation.Quality.GOOD;
        boolean left = quality && observation.getLeft().isAccepted();
        boolean right = quality && observation.getRight().isAccepted();
        MotorAssemblyGate.Decision decision = motorGate.observe(frameEvidenceNanos,
                lock.isReady(), quality, left, right, both);
        boolean ready = decision.isReady() && anchorReady;
        arHudView.update(ready ? ArHudView.HudState.MATCHED : ArHudView.HudState.TRACKING,
                both ? "Both motor positions" : "First motor position",
                normalizeCorners(result.getSpatialAnchor().getProjectedCorners()));
        showCheckpointTargets(result.getSpatialAnchor(), ready);
        if (ready) {
            requestGuidedPlacementConfirmation();
        } else if (!anchorReady) {
            setBanner("Keep the board details visible · locking the motor mounting positions",
                    true, R.drawable.bg_live_pill);
        } else if (!quality) {
            setBanner(motorQualityMessage(observation), false, R.drawable.bg_camera_warning);
        } else if (!left || both && !right) {
            setBanner((!left ? observation.getLeft() : observation.getRight()).getMessage(),
                    false, R.drawable.bg_camera_warning);
        } else {
            setBanner("Checking " + (both ? "both motor positions" : "the first motor position")
                    + " · " + decision.getAcceptedFrames() + "/" + decision.getRequiredFrames(),
                    true, R.drawable.bg_live_pill);
        }
    }

    private void showCheckpointTargets(RecognitionResult.SpatialAnchor anchor, boolean accepted) {
        List<ChassisRelativePlacementGate.TargetProjection> targets =
                activeCheckpoint.getPlacementGate().projectTargets(anchor.getProjectedCorners());
        arHudView.setAssemblyTargets(targets.isEmpty() ? null : normalizeCorners(targets.get(0).getCorners()),
                accepted, targets.size() < 2 ? null : normalizeCorners(targets.get(1).getCorners()), accepted);
    }

    private void requestGuidedPlacementConfirmation() {
        stateMachine.onComponentRecognized(activeCheckpoint.getComponentId(), true);
        stateMachine.onPoseEstimated(true);
        stateMachine.beginGuidance();
        stateMachine.onVisualValidation(true, true, true);
        stateMachine.requestPhysicalConfirmation();
        confirmPlacementButton.setVisibility(View.VISIBLE);
        demoStage.setText(R.string.demo_stage_check);
        setBanner("Placement checked · confirm this action’s physical fit", false, R.drawable.bg_success);
    }

    /** Camera evidence expires after the gates' gap limit plus the time the next frame needs. */
    private long resultExpiryMillis() {
        return MotorAssemblyGate.MAXIMUM_FRAME_GAP_MILLIS + evidenceClock.getLastProcessingMillis();
    }

    private boolean needsLivePlacementEvidence() {
        return activeCheckpoint != null ? activeCheckpoint.getKind() == GuidedCheckpoint.Kind.PLACEMENT
                : isOnFinalGuidedStep() && !isManualOnlyFinalCheck(currentStep);
    }

    /** Board identity, motor placement, and physical fastening remain separate checks. */
    private void handleMotorAssembly(RecognitionResult result) {
        if (physicalCheckInProgress) return; // Explicitly captured visual check for the wiggle test.
        long timestamp = frameEvidenceNanos;
        if (timestamp <= lastMotorFrameNanos) return;
        lastMotorFrameNanos = timestamp;
        lastMotorResultAt = SystemClock.uptimeMillis();
        recognitionText.removeCallbacks(expireMotorReadiness);
        recognitionText.postDelayed(expireMotorReadiness, resultExpiryMillis());
        RecognitionResult.SpatialAnchor base = result.getSpatialAnchor();
        boolean hasBase = base != null && NeobotSeedData.MAIN_BOARD.equals(base.getComponentId())
                && base.getPose() != null;
        float[] corners = hasBase ? normalizeCorners(base.getProjectedCorners()) : null;
        VisualPlacementGate.Observation lock = hasBase
                ? anchorGate.observe(base.getComponentId(), corners,
                        base.getPose().getTranslationVector(), true, 0.010f, timestamp)
                : anchorGate.observeUnavailable(timestamp);
        logPlacementGate("board", result, lock);
        // Discontinuous motion/identity loss starts a new inspection, not a carried-over pass.
        if (lock.getStableFrames() <= 1 && previousMotorAnchorFrames > 1) motorGate.reset();
        previousMotorAnchorFrames = lock.getStableFrames();
        MotorAssemblyObservation observation = result.getMotorAssembly();
        boolean qualityGood = observation != null
                && observation.getQuality() == MotorAssemblyObservation.Quality.GOOD;
        boolean left = qualityGood && observation.getLeft().isAccepted();
        boolean right = qualityGood && observation.getRight().isAccepted();
        MotorAssemblyGate.Decision decision = motorGate.observe(timestamp,
                hasBase && lock.isReady(), qualityGood, left, right);
        boolean ready = decision.isReady() && !result.isAnchorTracked();
        if (!ready) revokeMotorReadiness();
        motorInspectionStatus.setText(getString(R.string.motor_inspection_status,
                lock.isReady() ? "locked" : "searching",
                motorStatus(qualityGood, left, decision.isLeftConfirmed()),
                motorStatus(qualityGood, right, decision.isRightConfirmed())));
        if (!hasBase || !lock.isFrameValid()) {
            arGlView.hideGuidance();
            arHudView.reset();
            setBanner(hasBase ? placementMessage(lock, "main board")
                            : "Find the main board · keep its center and edges visible",
                    true, R.drawable.bg_live_pill);
            return;
        }
        arHudView.update(ready ? ArHudView.HudState.MATCHED : ArHudView.HudState.TRACKING,
                result.isAnchorTracked() ? "Base tracking" : "Base board", corners);
        if (lock.isReady()) {
            showBaseGuidance(base);
            if (observation != null) arHudView.setAssemblyTargets(
                    normalizeCorners(observation.getLeft().getProjectedCorners()), left,
                    normalizeCorners(observation.getRight().getProjectedCorners()), right);
        } else {
            arGlView.hideGuidance();
            arHudView.clearAssemblyTargets();
        }
        if (ready) {
            stateMachine.onComponentRecognized(NeobotSeedData.GEAR_MOTOR, true);
            stateMachine.onPoseEstimated(true);
            stateMachine.beginGuidance();
            stateMachine.onVisualValidation(true, true, true);
            stateMachine.requestPhysicalConfirmation();
            confirmPlacementButton.setVisibility(View.VISIBLE);
            setBanner("Both motor positions checked · confirm direction and fastening",
                    false, R.drawable.bg_success);
        } else if (!lock.isReady()) {
            setBanner("Locking the base board · " + lock.getStableFrames() + "/"
                    + STABLE_FRAME_GATE, true, R.drawable.bg_live_pill);
        } else if (!qualityGood) {
            setBanner(motorQualityMessage(observation), false, R.drawable.bg_camera_warning);
        } else if (!left || !right) {
            MotorAssemblyObservation.Slot slot = !left ? observation.getLeft() : observation.getRight();
            setBanner((!left ? "Left motor: " : "Right motor: ") + slot.getMessage(),
                    false, R.drawable.bg_camera_warning);
        } else if (result.isAnchorTracked()) {
            setBanner("Both motors visible · show the board center to confirm its identity",
                    true, R.drawable.bg_live_pill);
        } else {
            setBanner("Checking both motor positions · "
                            + Math.min(decision.getAcceptedFrames(), decision.getRequiredFrames())
                            + "/" + decision.getRequiredFrames(), true, R.drawable.bg_live_pill);
        }
    }

    /** Rejects frames captured for an earlier card, even within the same manual section. */
    private boolean shouldHandleRecognition(RecognitionResult result) {
        AssemblyStep step = currentStep;
        return result != null
                && !destroyed
                && !completionInFlight
                && stateMachine != null
                && step != null
                && guidedStepRecognitionEnabled
                && (!isOnFinalGuidedStep() || activeCheckpoint != null || sectionCheckpointsComplete())
                && result.getAssemblyStepNumber() == step.getStepNumber()
                && result.getFrameTimestampNanos() >= acceptRecognitionAfterNanos;
    }

    private static String motorStatus(boolean quality, boolean seen, boolean confirmed) {
        return !quality ? "waiting" : confirmed ? "checked" : seen ? "checking" : "check view";
    }

    private static String motorQualityMessage(@Nullable MotorAssemblyObservation observation) {
        if (observation == null) return "Keep both motor positions visible";
        switch (observation.getQuality()) {
            case BLURRED: return "Image is blurred · pause briefly or move slightly farther away";
            case TOO_DARK: return "Add even light over the board and both motors";
            case GLARE: return "Tilt the camera slightly to reduce glare";
            case VIEW_TOO_OBLIQUE: return "View the board more from above to check both motor positions";
            default: return "Keep the board center and both motor positions visible";
        }
    }

    private void showBaseGuidance(RecognitionResult.SpatialAnchor base) {
        if (base.getPose() == null) return;
        arGlView.showGuidance(NeobotGuidanceFactory.create(currentStep,
                primaryComponentId(currentStep), base.getPose().getOpenGlModelViewMatrix()),
                cameraProjection());
    }

    private void revokeMotorReadiness() {
        confirmPlacementButton.setVisibility(View.GONE);
        if (stateMachine != null && (stateMachine.snapshot().getState()
                == AssemblyState.PHYSICAL_CONFIRMATION || stateMachine.snapshot().getState()
                == AssemblyState.VISUALLY_VALIDATED)) stateMachine.retryCurrentStep();
    }

    private void relockAssemblyBase() {
        if (physicalCheckInProgress) return;
        // Manual actions have no camera gate to reopen their confirmation after a reset.
        if (!guidedStepRecognitionEnabled) return;
        partIntroductionGate.reset();
        bracketSetGate.reset();
        resetStableTracking();
        revokeMotorReadiness();
        if (analyzer != null) analyzer.resetAssemblyTracking();
        arHudView.reset();
        arGlView.hideGuidance();
        motorInspectionStatus.setText(R.string.motor_inspection_initial);
        setBanner("Re-locking the base · keep the board center visible",
                true, R.drawable.bg_live_pill);
    }

    private String placementMessage(
            VisualPlacementGate.Observation placement, String componentName) {
        switch (placement.getReason()) {
            case MISSING_CORNERS:
            case NON_CONVEX:
                return getString(R.string.placement_outline_unavailable);
            case OUTSIDE_GUIDE:
                return getString(R.string.placement_inside_guide);
            case TOO_SMALL:
                return getString(R.string.placement_move_closer);
            case TOO_LARGE:
                return getString(R.string.placement_move_back);
            case MISSING_POSE:
            case INVALID_POSE:
                if (placement.getStableFrames() > 0) {
                    return getString(R.string.placement_pose_reacquiring,
                            placement.getStableFrames(), STABLE_FRAME_GATE);
                }
                return getString(R.string.placement_pose_unavailable);
            case HOLD_STEADY:
                return "Recognized " + componentName + " · hold position ("
                        + placement.getStableFrames() + "/" + STABLE_FRAME_GATE + ")";
            case READY:
            default:
                return getString(R.string.validation_ready);
        }
    }

    private static void logPlacementGate(String target, RecognitionResult result,
                                          VisualPlacementGate.Observation lock) {
        if (!Log.isLoggable("AssemblArBoard", Log.DEBUG)) return;
        Log.d("AssemblArBoard", String.format(Locale.US,
                "step=%d frame=%d lock=%s frameGapMs=%.2f maxGapMs=%d resetReason=%s "
                        + "stable=%d/%d frameValid=%s ready=%s reason=%s centerDrift=%.4f "
                        + "relativeAreaDrift=%.4f edgeAngleDriftDeg=%.2f relativePoseDrift=%.4f "
                        + "motionLimits=%s",
                result.getAssemblyStepNumber(), result.getFrameTimestampNanos(), target,
                lock.getFrameGapMillis(), lock.getMaximumFrameGapMillis(), lock.getResetReason(),
                lock.getStableFrames(), STABLE_FRAME_GATE, lock.isFrameValid(), lock.isReady(),
                lock.getReason(), lock.getCenterDrift(), lock.getRelativeAreaDrift(),
                lock.getEdgeAngleDriftDegrees(), lock.getRelativePoseDrift(), lock.getStabilityLimits()));
    }

    private void updateDiagnostics(RecognitionResult result) {
        dev.chyken.assembl_ar.diagnostics.DiagnosticSample sample=pairedDiagnosticSample;
        if(sample!=null&&sample.frameNanos==result.getFrameTimestampNanos())
            showDiagnosticSnapshot(diagnosticLocker.stream(result.getRequestTag(),sample.telemetry));

        CameraCalibration frameCalibration = latestCalibration;
        String calibrationSource = frameCalibration == null ? "UNAVAILABLE" : frameCalibration.getSource().name();
        RecognitionResult.BoardObservation board = result.getBoardObservation();
        if (diagnosticsVisible) {
            RecognitionResult.SpatialAnchor boardAnchor = board == null ? null : board.getAnchor();
            List<VisionPoint> boardCorners = boardAnchor == null
                    ? board == null ? null : board.getDetection().getProjectedCorners()
                    : boardAnchor.getProjectedCorners();
            RecognitionResult.Pose boardPose = boardAnchor == null
                    ? board == null ? null : board.getDetection().getPose()
                    : boardAnchor.getPose();
            Log.i("AssemblyTrial", "frame=" + result.getFrameTimestampNanos()
                    + " step=" + result.getAssemblyStepNumber()
                    + " analysis=" + analyzedWidth + "x" + analyzedHeight
                    + " calibration=" + calibrationSource
                    + " K=" + (frameCalibration == null ? "[]"
                    : Arrays.toString(frameCalibration.getCameraMatrixRowMajor()))
                    + " boardDetected=" + (board != null && board.isDetected())
                    + " boardTracked=" + (board != null && board.isTracked())
                    + " boardReference=" + (boardAnchor == null
                    ? board == null ? "none" : board.getDetection().getReferenceId()
                    : boardAnchor.getReferenceId())
                    + " boardCorners=" + (boardCorners == null ? "[]" : boardCorners)
                    + " boardRvec=" + (boardPose == null ? "[]"
                    : Arrays.toString(boardPose.getRotationVector()))
                    + " boardTvec=" + (boardPose == null ? "[]"
                    : Arrays.toString(boardPose.getTranslationVector())));
        }
    }

    private void setDiagnosticsVisible(boolean visible) {
        diagnosticsVisible = visible;
        // The shared elevated HUD is the single readout in both camera modes.
        // Retain the old container ID for layout compatibility, without a second overlapping panel.
        diagnosticsPanel.setVisibility(View.GONE);
        findViewById(R.id.devDiagnosticsOverlay).setVisibility(visible?View.VISIBLE:View.GONE);
    }

    void devForceCompleteCurrentStep() {
        if (currentStep == null || steps == null || completionInFlight) return;
        completionInFlight = true;
        AssemblyStep bypassed = currentStep;
        databaseExecutor.execute(() -> {
            try {
                ProjectProgress saved = repository.confirmAndCompleteStep(bypassed.getId());
                runOnUiThread(() -> {
                    if (destroyed || isFinishing() || isDestroyed()) return;
                    GuidedStepProgressStore.clearStep(this,
                            bypassed.getProjectId(), bypassed.getId());
                    completionInFlight = false;
                    if (saved.isComplete()) {
                        openCompletion();
                    } else {
                        currentStepIndex = findStepIndex(saved.getCurrentStepId());
                        currentStep = steps.get(currentStepIndex);
                        stateMachine = new AssemblyStateMachine(steps.size(), currentStepIndex);
                        renderStep();
                    }
                });
            } catch (RuntimeException failure) {
                Log.e("AssemblyActivity", "DEV step bypass failed", failure);
                runOnUiThread(() -> {
                    completionInFlight = false;
                    setBanner(getString(R.string.progress_save_failed), false,
                            R.drawable.bg_camera_warning);
                });
            }
        });
    }

    @Nullable
    float[] normalizeCorners(List<VisionPoint> points) {
        if (points == null || points.size() != 4 || analyzedWidth <= 0 || analyzedHeight <= 0) {
            return null;
        }
        int viewWidth = previewView.getWidth();
        int viewHeight = previewView.getHeight();
        double scale = viewWidth > 0 && viewHeight > 0
                ? Math.max(viewWidth / (double) analyzedWidth,
                        viewHeight / (double) analyzedHeight)
                : 1.0;
        double offsetX = viewWidth > 0
                ? (viewWidth - analyzedWidth * scale) * 0.5 : 0.0;
        double offsetY = viewHeight > 0
                ? (viewHeight - analyzedHeight * scale) * 0.5 : 0.0;
        double normalizationWidth = viewWidth > 0 ? viewWidth : analyzedWidth;
        double normalizationHeight = viewHeight > 0 ? viewHeight : analyzedHeight;
        float[] result = new float[8];
        for (int index = 0; index < 4; index++) {
            result[index * 2] = (float) ((points.get(index).getX() * scale + offsetX)
                    / normalizationWidth);
            result[index * 2 + 1] = (float) ((points.get(index).getY() * scale + offsetY)
                    / normalizationHeight);
        }
        return result;
    }

    @Nullable
    private ArCameraProjection cameraProjection() {
        CameraCalibration calibration = latestCalibration;
        if (calibration == null || analyzedWidth <= 0 || analyzedHeight <= 0) {
            return null;
        }
        try {
            return ArCameraProjection.fromCameraMatrix(
                    analyzedWidth,
                    analyzedHeight,
                    calibration.getCameraMatrixRowMajor());
        } catch (IllegalArgumentException invalidCalibration) {
            return null;
        }
    }

    private void renderStep() {
        AssemblyStep step = currentStep;
        currentGuidedSteps = GuidedAssemblySequence.forManualStep(step);
        currentGuidedStepIndex = GuidedStepProgressStore.readForCheckpoints(
                this, step, currentGuidedSteps);
        requiredComponentLabel.setText(requiredMaterialsText(step));
        motorInspectionStatus.setText(R.string.motor_inspection_initial);
        lastMotorFrameNanos = Long.MIN_VALUE;
        physicalCheckInProgress = false;
        renderGuidedStep();
    }

    private void moveGuidedStep(int offset) {
        if (completionInFlight || physicalCheckInProgress
                || currentStep == null || currentGuidedSteps.isEmpty()) return;
        if (offset > 0 && activeCheckpoint != null && !currentCheckpointComplete) return;
        int next = currentGuidedStepIndex + offset;
        if (next < 0 || next >= currentGuidedSteps.size()) return;
        currentGuidedStepIndex = next;
        GuidedStepProgressStore.write(this, currentStep.getProjectId(),
                currentStep.getId(), currentGuidedSteps.get(currentGuidedStepIndex));
        renderGuidedStep();
    }

    private void redoCurrentGuidedStep() {
        if (completionInFlight || physicalCheckInProgress || currentStep == null
                || activeCheckpoint == null || !currentCheckpointComplete) return;
        GuidedStepProgressStore.clearCheckpointsFrom(this, currentStep,
                currentGuidedSteps, currentGuidedStepIndex);
        if (analyzer != null) analyzer.resetAssemblyTracking();
        renderGuidedStep();
    }

    private boolean sectionCheckpointsComplete() {
        return currentStep != null && GuidedStepProgressStore.areCheckpointsComplete(
                this, currentStep, currentGuidedSteps);
    }

    private void renderGuidedStep() {
        if (currentStep == null || currentGuidedSteps.isEmpty()) return;
        recognitionText.removeCallbacks(expireSectionReadiness);
        recognitionText.removeCallbacks(expireMotorReadiness);
        guidedStepRecognitionEnabled = false;
        acceptRecognitionAfterNanos = Long.MAX_VALUE;
        GuidedStep guidedStep = currentGuidedSteps.get(currentGuidedStepIndex);
        activeCheckpoint = GuidedRecognitionCatalog.checkpointFor(currentStep, guidedStep);
        activeCheckpointComponentId = activeCheckpoint == null ? null : activeCheckpoint.getComponentId();
        currentCheckpointComplete = activeCheckpoint != null
                && GuidedStepProgressStore.isCheckpointComplete(this, currentStep.getProjectId(),
                currentStep.getId(), guidedStep);
        currentDemo = GuidedDemonstrationCatalog.create(currentStep, guidedStep, activeCheckpoint);
        awaitingPartScan = shouldIntroducePart();
        partIntroductionGate.reset();
        bracketSetGate.reset();
        if (voiceGuide != null) voiceGuide.stop();
        assemblyDemo.setDemo(currentDemo);
        assemblyDemo.pause();
        findViewById(R.id.assemblyDetails).setVisibility(View.GONE);
        ((TextView) findViewById(R.id.assemblyDetailsButton)).setText(R.string.show_action_details);
        int globalStep = globalGuidedStepNumber();
        int total = Math.max(1, totalGuidedStepCount);
        int completed = globalStep - currentGuidedStepIndex - 1
                + GuidedStepProgressStore.completedCheckpointCount(this, currentStep, currentGuidedSteps);
        int percent = Math.min(99, Math.round(completed * 100f / total));

        stepCounter.setText(getString(R.string.guided_step_format, globalStep, total));
        progressLabel.setText(getString(R.string.manual_step_progress_format,
                currentStep.getStepNumber(), steps.size(),
                guidedStep.getNumberWithinManualStep(), currentGuidedSteps.size(), percent));
        assemblyProgress.setProgressCompat(percent, true);
        List<AssemblyInstruction> leadingContext = guidedStep.getLeadingContext();
        stepLeadingContext.setVisibility(currentDemo.getWarningText().isEmpty() ? View.GONE : View.VISIBLE);
        stepLeadingContext.setText(currentDemo.getWarningText());
        boolean hasLeadingWarning = hasInstructionType(leadingContext, InstructionType.WARNING);
        stepLeadingContext.setTextColor(ContextCompat.getColor(this,
                hasLeadingWarning ? R.color.hud_warning : R.color.white_secondary));
        stepTitle.setText(currentDemo.getShortTitle());
        stepInstruction.setText(currentDemo.getCaption());
        ((TextView) findViewById(R.id.actionFullInstructions)).setText(
                (leadingContext.isEmpty() ? "" : contextBlock(leadingContext) + "\n\n")
                        + guidedStep.getPrimaryInstruction().getText() + "\n\n" + guidedStepBody(guidedStep));
        requiredComponentLabel.setText(requiredMaterialsText(currentStep));
        renderRequiredPartReference();

        manualPageLabel.setText(guidedStep.getPageStart() == guidedStep.getPageEnd()
                ? "Manual page " + guidedStep.getPageStart()
                : "Manual pages " + guidedStep.getPageStart() + "–" + guidedStep.getPageEnd());
        AssetImageLoader.load(manualThumbnail,
                guidedStep.getPrimaryInstruction().getReferenceImagePath());

        boolean hasMultipleGuidedSteps = currentGuidedSteps.size() > 1;
        guidedStepNavigationRow.setVisibility(hasMultipleGuidedSteps ? View.VISIBLE : View.GONE);
        boolean previousEnabled = !completionInFlight && currentGuidedStepIndex > 0;
        previousGuidedStepButton.setEnabled(previousEnabled);
        previousGuidedStepButton.setAlpha(previousEnabled ? 1f : 0.40f);
        nextGuidedStepButton.setVisibility(isOnFinalGuidedStep() ? View.INVISIBLE : View.VISIBLE);
        nextGuidedStepButton.setText(R.string.next_guided_step);
        boolean nextEnabled = !completionInFlight
                && (activeCheckpoint == null || currentCheckpointComplete);
        nextGuidedStepButton.setEnabled(nextEnabled);
        nextGuidedStepButton.setAlpha(nextEnabled ? 1f : 0.40f);
        findViewById(R.id.redoGuidedStepPanel).setVisibility(
                currentCheckpointComplete ? View.VISIBLE : View.GONE);
        findViewById(R.id.redoGuidedStepButton).setEnabled(!completionInFlight);
        findViewById(R.id.motorInspectionRow).setVisibility(
                isMotorValidationStep() ? View.VISIBLE : View.GONE);
        confirmPlacementButton.setVisibility(View.GONE);

        if (stateMachine != null) stateMachine.retryCurrentStep();
        arHudView.reset();
        arGlView.hideGuidance();
        resetStableTracking();
        lastCheckpointFrameNanos = Long.MIN_VALUE;
        guidedStepRecognitionEnabled = isOnFinalGuidedStep() && sectionCheckpointsComplete()
                && !isManualOnlyFinalCheck(currentStep)
                || activeCheckpoint != null && activeCheckpoint.requiresCamera() && !currentCheckpointComplete;
        acceptRecognitionAfterNanos = SystemClock.elapsedRealtimeNanos();
        showCurrentGuidedStepBanner();
        if (activeCheckpoint != null && activeCheckpoint.getKind() == GuidedCheckpoint.Kind.PHYSICAL
                && !currentCheckpointComplete) {
            stateMachine.requestManualActionConfirmation();
            confirmPlacementButton.setVisibility(View.VISIBLE);
        }
        if (isManualOnlyFinalCheck(currentStep)) {
            boolean checked = sectionCheckpointsComplete();
            boolean hasCheckpoint = false;
            for (GuidedStep card : currentGuidedSteps) {
                if (GuidedRecognitionCatalog.checkpointFor(currentStep, card) != null) {
                    hasCheckpoint = true;
                }
            }
            boolean physicalSection = SectionEvidencePolicy.isPhysicalFinal(currentStep.getStepNumber());
            AssemblySession manual = physicalSection && checked
                    ? stateMachine.requestManualActionConfirmation()
                    : stateMachine.requestPhysicalConfirmationFromCheckpoint(
                            primaryComponentId(currentStep), hasCheckpoint && checked);
            boolean ready = manual.getState() == AssemblyState.PHYSICAL_CONFIRMATION;
            confirmPlacementButton.setVisibility(ready ? View.VISIBLE : View.GONE);
            setBanner(ready ? physicalSection ? "Actions checked · perform the final physical check"
                    : "Actions checked · confirm the completed assembly"
                    : getString(R.string.complete_previous_actions), false, R.drawable.bg_live_pill);
        }
        stepInfoPanel.onStepChanged(currentStep.getId() + ":" + currentGuidedStepIndex);
        String previewId=activeCheckpoint==null ? primaryComponentId(currentStep) : activeCheckpoint.getComponentId();
        Component previewPart=componentById.get(previewId);
        if(previewPart!=null) showComponentPreview(previewPart.getId(),previewPart.getName());
        if (awaitingPartScan || activeCheckpoint != null
                && activeCheckpoint.getKind() == GuidedCheckpoint.Kind.IDENTITY && !currentCheckpointComplete) {
            demoStage.setText(R.string.demo_stage_scan);
            speakCurrentInstruction();
        } else {
            startCurrentDemonstration();
        }
        // Upgrades may retain the successful board identity while removing its old surface card.
        if (activeCheckpoint != null && activeCheckpoint.getKind() == GuidedCheckpoint.Kind.IDENTITY
                && currentCheckpointComplete && isOnFinalGuidedStep()) {
            stateMachine.requestPhysicalConfirmationFromCheckpoint(activeCheckpointComponentId, true);
            completeCurrentStep();
        }
    }

    private boolean shouldIntroducePart() {
        if (activeCheckpoint == null || currentCheckpointComplete
                || activeCheckpoint.getKind() != GuidedCheckpoint.Kind.PLACEMENT) return false;
        String id = activeCheckpoint.getComponentId();
        boolean looseReference = IndividualPartTestCatalog.getStandaloneComponentIds().contains(id)
                || NeobotSeedData.WHEEL.equals(id) || NeobotSeedData.BATTERY_PACK.equals(id);
        if (!looseReference) return false;
        // A series of operations on the already identified part does not repeat its loose scan.
        for (int index = 0; index < currentGuidedStepIndex; index++) {
            GuidedStep earlier = currentGuidedSteps.get(index);
            GuidedCheckpoint checkpoint = GuidedRecognitionCatalog.checkpointFor(currentStep, earlier);
            if (checkpoint != null && id.equals(checkpoint.getComponentId())
                    && GuidedStepProgressStore.isCheckpointComplete(this,
                    currentStep.getProjectId(), currentStep.getId(), earlier)) return false;
        }
        return true;
    }

    private void startCurrentDemonstration() {
        if (currentDemo == null) return;
        demoStage.setText(currentCheckpointComplete ? R.string.demo_stage_review : R.string.demo_stage_follow);
        assemblyDemo.replay();
        speakCurrentInstruction();
    }

    private void replayDemonstration() {
        if (currentDemo == null || physicalCheckInProgress || completionInFlight) return;
        assemblyDemo.replay();
        if (voiceGuide != null && activityResumed) {
            voiceGuide.stop();
            voiceGuide.speak(currentDemo.getSpokenInstruction(), "replay:" + currentGuidedStepIndex);
        }
    }

    private void speakCurrentInstruction() {
        if (voiceGuide == null || currentDemo == null || !activityResumed) return;
        boolean scan = awaitingPartScan || activeCheckpoint != null
                && activeCheckpoint.getKind() == GuidedCheckpoint.Kind.IDENTITY && !currentCheckpointComplete;
        String text = scan ? introductionPrompt()
                + (currentDemo.getWarningText().isEmpty() ? "" : ". " + currentDemo.getWarningText())
                : currentDemo.getSpokenInstruction();
        voiceGuide.speak(text, currentStep.getId() + ":" + currentGuidedStepIndex + (scan ? ":scan" : ":demo"));
    }

    private void updateVoiceStatus(AssemblyVoiceGuide.Status status) {
        if (destroyed || voiceStatus == null) return;
        voiceGuideButton.setText(status == AssemblyVoiceGuide.Status.MUTED
                ? R.string.voice_enable : R.string.voice_mute);
        voiceStatus.setText(status == AssemblyVoiceGuide.Status.UNAVAILABLE ? R.string.voice_unavailable
                : status == AssemblyVoiceGuide.Status.INITIALIZING ? R.string.voice_initializing
                : status == AssemblyVoiceGuide.Status.MUTED ? R.string.voice_muted : R.string.voice_available);
        PhaseAssemblyController phases = phaseController;
        if (phases != null) phases.onVoiceStatusChanged();
    }

    private int globalGuidedStepNumber() {
        int number = currentGuidedStepIndex + 1;
        for (int index = 0; index < currentStepIndex; index++) {
            number += GuidedAssemblySequence.forManualStep(steps.get(index)).size();
        }
        return number;
    }

    private String guidedStepBody(GuidedStep guidedStep) {
        StringBuilder output = new StringBuilder(getString(R.string.manual_step_context_format,
                currentStep.getStepNumber(), currentStep.getTitle()));
        if (activeCheckpoint != null) {
            output.append("\n\n").append(activeCheckpoint.getPrompt());
        }
        String trailing = contextBlock(guidedStep.getTrailingContext());
        if (!trailing.isEmpty()) {
            output.append("\n\n").append(trailing);
        }
        return output.toString();
    }

    private String contextBlock(List<AssemblyInstruction> context) {
        StringBuilder output = new StringBuilder();
        for (AssemblyInstruction instruction : context) {
            if (output.length() > 0) output.append("\n\n");
            output.append(instructionLabel(instruction.getType()))
                    .append(": ").append(instruction.getText());
        }
        return output.toString();
    }

    private static boolean hasInstructionType(
            List<AssemblyInstruction> instructions,
            InstructionType type) {
        for (AssemblyInstruction instruction : instructions) {
            if (instruction.getType() == type) return true;
        }
        return false;
    }

    private String instructionLabel(InstructionType type) {
        if (type == InstructionType.WARNING) return getString(R.string.instruction_warning);
        if (type == InstructionType.CHECK) return getString(R.string.instruction_check);
        return getString(R.string.instruction_note);
    }

    private void showCurrentGuidedStepBanner() {
        PhaseAssemblyController phases = phaseController;
        if (phases != null) {
            phases.showBanner();
            return;
        }
        if (isOnFinalGuidedStep() && activeCheckpoint == null && !sectionCheckpointsComplete()) {
            setBanner(getString(R.string.complete_previous_actions), false, R.drawable.bg_live_pill);
            return;
        }
        if (missingRecognitionSubject() != null) {
            showMissingReference();
            return;
        }
        if (activeCheckpoint != null) {
            setBanner(currentCheckpointComplete ? getString(R.string.guided_checkpoint_complete)
                            : awaitingPartScan ? introductionPrompt()
                            : currentDemo.getCaption(),
                    !currentCheckpointComplete && activeCheckpoint.requiresCamera(),
                    currentCheckpointComplete ? R.drawable.bg_success : R.drawable.bg_live_pill);
        } else if (isOnFinalGuidedStep()) {
            setBanner(getString(R.string.scanning), true, R.drawable.bg_live_pill);
        } else {
            setBanner(getString(R.string.guided_step_prompt), false, R.drawable.bg_live_pill);
        }
    }

    private String checkpointComponentName() {
        Component component = componentById.get(activeCheckpointComponentId);
        return component == null ? String.valueOf(activeCheckpointComponentId) : component.getName();
    }

    @Nullable
    private String missingRecognitionSubject() {
        java.util.Set<String> available = availableRecognitionIds;
        if (available == null || currentStep == null) return null;
        if (activeCheckpoint != null && (!activeCheckpoint.requiresCamera() || currentCheckpointComplete)) {
            return null;
        }
        String subject = activeCheckpointComponentId;
        if (subject != null && currentCheckpointComplete) return null;
        if (subject == null) {
            if (!isOnFinalGuidedStep() || isManualOnlyFinalCheck(currentStep)) return null;
            subject = primaryComponentId(currentStep);
        }
        if (!available.contains(subject)) return subject;
        String anchor = activeCheckpoint == null ? spatialAnchorComponentId(currentStep)
                : activeCheckpoint.getAnchorComponentId();
        return anchor != null && !available.contains(anchor) ? anchor : null;
    }

    private void showMissingReference() {
        String id = missingRecognitionSubject();
        if (id == null) return;
        Component part = componentById.get(id);
        confirmPlacementButton.setVisibility(View.GONE);
        setBanner(getString(R.string.recognition_reference_unavailable,
                part == null ? id : part.getName()), false, R.drawable.bg_camera_warning);
        arGlView.hideGuidance();
    }

    private boolean isOnFinalGuidedStep() {
        return !currentGuidedSteps.isEmpty()
                && currentGuidedStepIndex == currentGuidedSteps.size() - 1;
    }

    private boolean isMotorValidationStep() {
        return currentStep != null && currentStep.getStepNumber() == 2
                && isOnFinalGuidedStep();
    }

    private String requiredMaterialsText(AssemblyStep step) {
        StringBuilder materials = new StringBuilder();
        for (RequiredComponent required : step.getRequiredComponents()) {
            if (materials.length() > 0) {
                materials.append('\n');
            }
            Component component = componentById.get(required.getComponentId());
            String name = component == null ? required.getComponentId() : component.getName();
            materials.append("• ").append(required.getQuantity()).append(" × ").append(name);
            if (!required.getNotes().trim().isEmpty()) {
                materials.append(" — ").append(required.getNotes().trim());
            }
        }
        return materials.length() == 0
                ? getString(R.string.no_additional_materials)
                : materials.toString();
    }

    private void showStepDetails() {
        AssemblyStep step = currentStep;
        if (step == null) return;
        StringBuilder details = new StringBuilder();
        for (AssemblyInstruction instruction : step.getInstructions()) {
            details.append(instruction.getOrder()).append(". ")
                    .append(instruction.getText()).append("\n\n");
        }
        details.append(getString(R.string.required_materials)).append('\n')
                .append(requiredMaterialsText(step)).append("\n\n");
        if (!step.getPinConnections().isEmpty()) {
            details.append("Exact pin mapping\n");
            for (PinConnection connection : step.getPinConnections()) {
                details.append("• ").append(connection.getSourcePin()).append(" → ")
                        .append(connection.getTargetPin());
                if (!connection.getNotes().isEmpty()) {
                    details.append(" — ").append(connection.getNotes());
                }
                details.append('\n');
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(step.getTitle())
                .setMessage(details.toString().trim())
                .setNeutralButton(R.string.view_reference,
                        (dialog, which) -> showManualReference())
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void showManualReference() {
        if (currentStep == null || currentGuidedSteps.isEmpty()) return;
        GuidedStep guidedStep = currentGuidedSteps.get(currentGuidedStepIndex);
        ManualReferenceDialog.show(this, currentStep.getManualReference(),
                guidedStep.getPageStart());
    }

    private void useManualReferenceMode() {
        if (stateMachine == null || currentStep == null) return;
        showManualReference();
        stateMachine.useManualReferenceMode();
        setBanner(getString(R.string.manual_reference_view_only),
                false, R.drawable.bg_camera_warning);
    }

    private void showPhysicalValidation() {
        if (physicalCheckInProgress || stateMachine == null) return;
        if (isOnFinalGuidedStep() && !sectionCheckpointsComplete()) {
            revokeMotorReadiness();
            showCurrentGuidedStepBanner();
            return;
        }
        if (needsLivePlacementEvidence() && !isMotorValidationStep()
                && SystemClock.uptimeMillis() - lastSectionResultAt > resultExpiryMillis()) {
            revokeMotorReadiness();
            return;
        }
        if (isMotorValidationStep()
                && SystemClock.uptimeMillis() - lastMotorResultAt > resultExpiryMillis()) {
            revokeMotorReadiness();
            return;
        }
        AssemblySession session = stateMachine.snapshot();
        if (session.getState() != AssemblyState.PHYSICAL_CONFIRMATION) {
            return;
        }
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_validation, null, false);
        TextView title = content.findViewById(R.id.validationTitle);
        TextView eyebrow = content.findViewById(R.id.validationEyebrow);
        TextView body = content.findViewById(R.id.validationBody);
        CheckBox checkbox = content.findViewById(R.id.validationCheckbox);
        MaterialButton confirm = content.findViewById(R.id.confirmNextButton);
        ValidationType type = currentStep.getValidationType();
        if (isManualOnlyFinalCheck(currentStep)
                || activeCheckpoint != null && !activeCheckpoint.requiresCamera()) {
            eyebrow.setText(R.string.physical_action_ready);
        } else if (activeCheckpoint != null && activeCheckpoint.getKind() == GuidedCheckpoint.Kind.IDENTITY) {
            eyebrow.setText(R.string.part_identity_confirmed);
        }
        if (activeCheckpoint != null) {
            title.setText(R.string.guided_checkpoint_check_title);
            body.setText(activeCheckpoint.getPhysicalCheck());
            checkbox.setText(R.string.guided_checkpoint_check_confirm);
        } else if (type == ValidationType.WIGGLE_TEST) {
            title.setText(R.string.wiggle_test_title);
            body.setText(getString(R.string.wiggle_test_body) + "\n\n" + currentStep.getValidationPrompt());
        } else if (type == ValidationType.CONTINUITY_TEST) {
            title.setText(R.string.continuity_test_title);
            body.setText(getString(R.string.continuity_test_body) + "\n\n" + currentStep.getValidationPrompt());
        } else {
            title.setText(R.string.visual_test_title);
            body.setText(getString(R.string.visual_test_body) + "\n\n" + currentStep.getValidationPrompt());
        }
        AlertDialog dialog = new dev.chyken.assembl_ar.ui.PhysicalChecklistBottomSheetDialog.Builder(this).setTitle("Physical verification").setView(content).setNegativeButton("Check later",(d,w)->d.cancel()).create();
        if (voiceGuide != null) voiceGuide.stop();
        assemblyDemo.pause();
        physicalValidationDialog = dialog;
        physicalCheckInProgress = true;
        recognitionText.removeCallbacks(expireMotorReadiness);
        recognitionText.removeCallbacks(expireSectionReadiness);
        if (isMotorValidationStep()) {
            body.append("\n\nCheck both motors: shafts face outward, terminals/wires face inward, "
                    + "and neither motor moves in its brackets. The camera checks visible "
                    + "placement; it cannot prove screw tightness or hidden connections.");
        } else if (needsLivePlacementEvidence()) {
            body.append("\n\nCheck the part's direction, pin alignment, seating and fastening directly. "
                    + "The camera checks the visible footprint and position; it cannot verify "
                    + "insertion depth, tightness or electrical contact.");
        }
        dialog.setOnCancelListener(ignored -> {
            physicalCheckInProgress = false;
            if (needsLivePlacementEvidence()) {
                relockAssemblyBase();
            }
        });
        dialog.setOnDismissListener(ignored -> physicalValidationDialog = null);
        checkbox.setOnCheckedChangeListener((button, checked) -> confirm.setEnabled(checked));
        confirm.setOnClickListener(view -> {
            dialog.dismiss();
            if (activeCheckpoint != null) {
                completeGuidedCheckpoint();
            } else {
                completeCurrentStep();
            }
        });
        dialog.show();
    }

    private void completeGuidedCheckpoint() {
        if (currentStep == null || activeCheckpoint == null
                || stateMachine.snapshot().getState() != AssemblyState.PHYSICAL_CONFIRMATION) return;
        GuidedStepProgressStore.markCheckpointComplete(this, currentStep.getProjectId(),
                currentStep.getId(), currentGuidedSteps.get(currentGuidedStepIndex));
        currentCheckpointComplete = true;
        physicalCheckInProgress = false;
        if (isOnFinalGuidedStep()) completeCurrentStep();
        else moveGuidedStep(1);
    }

    private void completeCurrentStep() {
        if (completionInFlight || currentStep == null || stateMachine == null
                || !isOnFinalGuidedStep()
                || !sectionCheckpointsComplete()
                || stateMachine.snapshot().getState() != AssemblyState.PHYSICAL_CONFIRMATION) return;
        completionInFlight = true;
        guidedStepRecognitionEnabled = false;
        confirmPlacementButton.setEnabled(false);
        previousGuidedStepButton.setEnabled(false);
        nextGuidedStepButton.setEnabled(false);
        AssemblyStep completingStep = currentStep;
        AssemblyStateMachine completingStateMachine = stateMachine;
        String stepId = completingStep.getId();
        databaseExecutor.execute(() -> {
            try {
                ProjectProgress progress = repository.confirmAndCompleteStep(stepId);
                runOnUiThread(() -> {
                    if (destroyed || isFinishing() || isDestroyed()) return;
                    GuidedStepProgressStore.clearStep(this,
                            completingStep.getProjectId(), stepId);
                    completingStateMachine.confirmPhysicalValidation();
                    completionInFlight = false;
                    confirmPlacementButton.setEnabled(true);
                    if (progress.isComplete()) {
                        openCompletion();
                    } else {
                        currentStepIndex = findStepIndex(progress.getCurrentStepId());
                        currentStep = steps.get(currentStepIndex);
                        stateMachine = new AssemblyStateMachine(steps.size(), currentStepIndex);
                        renderStep();
                    }
                });
            } catch (RuntimeException failure) {
                Log.e("AssemblyActivity", "Could not save completed manual section", failure);
                runOnUiThread(() -> {
                    if (destroyed || isFinishing() || isDestroyed()) return;
                    completionInFlight = false;
                    physicalCheckInProgress = false;
                    confirmPlacementButton.setEnabled(true);
                    previousGuidedStepButton.setEnabled(currentGuidedStepIndex > 0);
                    nextGuidedStepButton.setEnabled(!isOnFinalGuidedStep());
                    if (activeCheckpoint != null && activeCheckpoint.getKind() == GuidedCheckpoint.Kind.IDENTITY) {
                        confirmPlacementButton.setVisibility(View.GONE);
                        nextGuidedStepButton.setText(R.string.retry_save_progress);
                        nextGuidedStepButton.setVisibility(View.VISIBLE);
                        nextGuidedStepButton.setEnabled(true);
                    } else {
                        guidedStepRecognitionEnabled = needsLivePlacementEvidence();
                    }
                    setBanner(getString(R.string.progress_save_failed),
                            false, R.drawable.bg_camera_warning);
                });
            }
        });
    }

    void openCompletion(){openCompletion(true);}
    void openCompletion(boolean verified) {
        Intent intent = new Intent(this, CompletionActivity.class);
        intent.putExtra(CompletionActivity.EXTRA_VERIFIED,verified);
        intent.putExtra(CompletionActivity.EXTRA_ELAPSED_MILLIS,
                Math.max(0L, System.currentTimeMillis() - sessionStartedAt));
        startActivity(intent);
        finish();
    }

    private void simulateState(String simulatedState) {
        String expectedId = primaryComponentId(currentStep);
        Component expected = componentById.get(expectedId);
        String expectedName = expected == null ? expectedId : expected.getName();
        recognitionSpinner.setVisibility(View.GONE);
        if (SIMULATE_MISMATCH.equals(simulatedState)) {
            stateMachine.onComponentRecognized("simulated_wrong_component", false);
            setBanner("Detected a different component · use " + expectedName,
                    false, R.drawable.bg_danger);
            arHudView.update(ArHudView.HudState.MISMATCH, "WRONG COMPONENT", sampleCorners());
        } else if (SIMULATE_WRONG_ORIENTATION.equals(simulatedState)) {
            stateMachine.onComponentRecognized(expectedId, true);
            stateMachine.onPoseEstimated(true);
            stateMachine.beginGuidance();
            stateMachine.onVisualValidation(true, false, true);
            setBanner(getString(R.string.wrong_orientation), false, R.drawable.bg_danger);
            arHudView.update(ArHudView.HudState.WRONG_ORIENTATION, expectedName, sampleCorners());
        } else if (SIMULATE_POLARITY_ERROR.equals(simulatedState)) {
            stateMachine.onComponentRecognized(expectedId, true);
            stateMachine.onPoseEstimated(true);
            stateMachine.beginGuidance();
            stateMachine.onVisualValidation(true, true, false);
            setBanner(getString(R.string.polarity_error), false, R.drawable.bg_danger);
            arHudView.update(ArHudView.HudState.POLARITY_ERROR, expectedName, sampleCorners());
        } else if (SIMULATE_POLARITY_UNCONFIRMED.equals(simulatedState)) {
            stateMachine.onComponentRecognized(expectedId, true);
            stateMachine.onPolarityUnconfirmed();
            setBanner(getString(R.string.polarity_unconfirmed),
                    false, R.drawable.bg_camera_warning);
            arHudView.update(ArHudView.HudState.TRACKING, expectedName, sampleCorners());
        }
    }

    void setBanner(String text, boolean spinning, int backgroundResource) {
        if(libraryFailure!=null){text=libraryFailure;spinning=false;backgroundResource=R.drawable.bg_camera_warning;}
        else if(referencePreparationMessage!=null&&loadingReferenceScope!=null&&activeReferenceRequest()!=null){text=referencePreparationMessage;spinning=true;backgroundResource=R.drawable.bg_live_pill;}
        else if(failedReferenceScope!=null&&failedReferenceScope.equals(dev.chyken.assembl_ar.camera.ActiveReferenceScope.key(activeReferenceRequest()))){text="Recognition preparation recovering…";spinning=true;backgroundResource=R.drawable.bg_live_pill;}

        if (!text.contentEquals(recognitionText.getText())) {
            recognitionText.setText(text);
            if (trialLog != null) trialLog.event("banner", text);
        }
        recognitionSpinner.setVisibility(spinning ? View.VISIBLE : View.GONE);
        recognitionBanner.setBackgroundResource(backgroundResource);
    }

    private void resetStableTracking() {
        placementGate.reset();
        anchorGate.reset();
        repeatedPlacementGate.reset();
        motorGate.reset();
        previousMotorAnchorFrames = 0;
    }

    private String safeName(RecognitionResult result) {
        String display = result.getDisplayName();
        return display == null || display.isEmpty() ? result.getComponentId() : display;
    }

    private String primaryComponentId(AssemblyStep step) {
        switch (step.getStepNumber()) {
            case 1: return NeobotSeedData.MAIN_BOARD;
            case 2: return NeobotSeedData.GEAR_MOTOR;
            case 3: return NeobotSeedData.MOTOR_DRIVER;
            case 4: return NeobotSeedData.MOTOR_DRIVER;
            case 5: return NeobotSeedData.MOTOR_DRIVER;
            case 6: return NeobotSeedData.MICROCONTROLLER;
            case 7: return NeobotSeedData.MOTOR_DRIVER;
            case 8: return NeobotSeedData.MOTOR_DRIVER;
            case 9: return NeobotSeedData.CASTER_WHEEL;
            case 10: return NeobotSeedData.LINE_TRACKER;
            case 11: return NeobotSeedData.DISTANCE_SENSOR;
            case 12: return NeobotSeedData.STANDOFF;
            case 13: return NeobotSeedData.WHEEL;
            case 14: return NeobotSeedData.OLED_PROTECTOR;
            case 15: return NeobotSeedData.DATA_CABLE;
            case 16: return NeobotSeedData.EXTENSION_BOARD;
            case 17: return NeobotSeedData.OLED_DISPLAY;
            case 18: return NeobotSeedData.BATTERY_PACK;
            default:
                return step.getRequiredComponents().isEmpty()
                        ? NeobotSeedData.MAIN_BOARD
                        : step.getRequiredComponents().get(0).getComponentId();
        }
    }

    private static int expectedInstanceCount(AssemblyStep step) {
        int stepNumber = step == null ? -1 : step.getStepNumber();
        return stepNumber == 2 || stepNumber == 10 || stepNumber == 13 ? 2 : 1;
    }

    static List<List<VisionPoint>> expectedInstanceQuads(
            RecognitionResult result, String expectedComponentId) {
        List<List<VisionPoint>> quads = new ArrayList<>();
        for (RecognitionResult.InstanceObservation observation
                : result.getInstanceObservations()) {
            if (expectedComponentId.equals(observation.getComponentId())) {
                quads.add(observation.getProjectedCorners());
            }
        }
        return quads;
    }

    static boolean orientationSensitive(AssemblyStep step) {
        return step.getOverlayTypes().contains(OverlayType.ORIENTATION_MARKER)
                || step.getOverlayTypes().contains(OverlayType.PIN_ALIGNMENT)
                || step.getOverlayTypes().contains(OverlayType.POLARITY_ARROW);
    }

    private static boolean orientationRequired(AssemblyStep step) {
        return orientationSensitive(step);
    }

    static boolean isGeometryOnlyResult(RecognitionResult result) {
        return result.getDetectionMethod() == RecognitionResult.DetectionMethod.MODEL_EDGE_MATCH
                || result.getDetectionMethod() == RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK
                || result.getDetectionMethod() == RecognitionResult.DetectionMethod.CANNY_HOUGH_FALLBACK;
    }

    private static boolean polarityRequired(AssemblyStep step) {
        return step.getOverlayTypes().contains(OverlayType.POLARITY_ARROW);
    }

    static boolean spatialPoseRequired(AssemblyStep step) {
        return !step.getOverlayTypes().isEmpty();
    }

    @Nullable
    private static String spatialAnchorComponentId(AssemblyStep step) {
        if (step == null || step.getStepNumber() == 1) {
            return null;
        }
        // The protector is assembled on the loose extension PCB in step 14. Once that PCB is
        // mounted in step 16, it remains the visible work plane for the OLED and battery.
        return step.getStepNumber() == 14 || step.getStepNumber() >= 17
                ? NeobotSeedData.EXTENSION_BOARD
                : NeobotSeedData.MAIN_BOARD;
    }

    static float minimumProjectedArea(AssemblyStep step) {
        if (step.getStepNumber() == 12) {
            // A truthful standoff Hough box occupies roughly 0.6–0.9% of a 4:3 frame.
            return 0.004f;
        }
        if (step.getOverlayTypes().contains(OverlayType.PIN_ALIGNMENT)
                || step.getOverlayTypes().contains(OverlayType.POLARITY_ARROW)) {
            return 0.008f;
        }
        if (step.getOverlayTypes().contains(OverlayType.GHOST_COMPONENT)) {
            return 0.014f;
        }
        return 0.020f;
    }

    private static float[] sampleCorners() {
        return new float[]{0.18f, 0.25f, 0.78f, 0.22f, 0.82f, 0.58f, 0.16f, 0.62f};
    }

    // ---- Services for PhaseAssemblyController --------------------------------------------------

    AssemblyVoiceGuide voiceGuide() { return voiceGuide; }
    AssemblArRepository repository() { return repository; }
    ExecutorService databaseExecutor() { return databaseExecutor; }
    @Nullable Component component(String id) { return componentById.get(id); }
    @Nullable java.util.Set<String> availableRecognitionIds() { return availableRecognitionIds; }
    boolean isActivityResumed() { return activityResumed; }
    boolean isGone() { return destroyed || isFinishing() || isDestroyed(); }
    @Nullable TrialLog trialLog() { return trialLog; }
    void stepInfoChanged(String key) { stepInfoPanel.onStepChanged(key); }
    void setPhaseOneGuideActive(boolean active) { stepInfoPanel.setPhaseOneActive(active); }
    void setPhaseTwoGuideActive(boolean active) { stepInfoPanel.setPhaseTwoActive(active); }
    void showComponentPreview(String id, String name) {
        if(componentPreview!=null) componentPreview.showComponent(id,name);
    }
    void hideGuidance() { arGlView.hideGuidance(); }
    void showGuidance(ArGuidance guidance) { arGlView.showGuidance(guidance, cameraProjection()); }

    private void syncDiagnosticScope(){
        PhaseAssemblyController phases=phaseController;if(phases==null)return;
        if(analyzer!=null&&phases.getPhase()!=null)analyzer.preloadTrainedPhase(phases.getPhase().getNumber());
        if(diagnosticLocker.enter(phases.diagnosticScope(),phases.currentTag(),phases.currentScanVerified())){
            dev.chyken.assembl_ar.ui.DevDiagnosticsOverlayView view=findViewById(R.id.devDiagnosticsOverlay);
            if(view!=null)view.clearTelemetry();
        }
    }
    private void showDiagnosticSnapshot(dev.chyken.assembl_ar.diagnostics.DiagnosticScanTelemetry telemetry){
        dev.chyken.assembl_ar.ui.DevDiagnosticsOverlayView view=findViewById(R.id.devDiagnosticsOverlay);
        if(view!=null&&telemetry!=null)view.showTelemetry(telemetry);
        if(diagnosticsText!=null&&telemetry!=null){String text=telemetry.hudText();if(!android.text.TextUtils.equals(diagnosticsText.getText(),text))diagnosticsText.setText(text);}
    }

    void resetAnalyzerTracking() {
        syncDiagnosticScope();
        outlineUpdates.clear();
        assemblyVisibilityGeneration++;
        dev.chyken.assembl_ar.ui.DynamicComponentOutlineOverlayView overlay=findViewById(R.id.dynamicOutlineOverlay);if(overlay!=null){overlay.clear();overlay.setCameraActive(false);}
        if (analyzer != null) analyzer.resetAssemblyTracking();
    }

    /** A grouped phase saved one or more manual sections; follow the database's current step. */
    void onPhaseSectionsSaved(ProjectProgress progress) {
        if (progress.isComplete()) return;
        currentStepIndex = findStepIndex(progress.getCurrentStepId());
        currentStep = steps.get(currentStepIndex);
        stateMachine = new AssemblyStateMachine(steps.size(), currentStepIndex);
        requiredComponentLabel.setText(requiredMaterialsText(currentStep));
    }

    /** Shows the manual page and thumbnail of the chapter currently demonstrated. */
    void showChapterReference(PhaseDemoScript.Chapter chapter) {
        GuidedStep card = chapter.getCard();
        manualPageLabel.setText(card.getPageStart() == card.getPageEnd()
                ? getString(R.string.phase_manual_page, chapter.getSectionNumber(), card.getPageStart())
                : getString(R.string.phase_manual_pages, chapter.getSectionNumber(), card.getPageStart(),
                        card.getPageEnd()));
        AssetImageLoader.load(manualThumbnail, card.getPrimaryInstruction().getReferenceImagePath());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        dev.chyken.assembl_ar.workflow.phase.PhaseSessionCheckpoint checkpoint=dev.chyken.assembl_ar.workflow.phase.PhaseProgressStore.session(this,NeobotSeedData.PROJECT_ID);
        if(checkpoint!=null)outState.putString("assembly_phase_session",checkpoint.encode());
        outState.putLong("session_started_at", sessionStartedAt);
        outState.putBoolean("assembly_welcome_accepted",assemblyWelcomeAccepted);
        stepInfoPanel.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onRestoreInstanceState(Bundle state){
        super.onRestoreInstanceState(state);
        if(recreationCheckpoint==null)recreationCheckpoint=dev.chyken.assembl_ar.workflow.phase.PhaseSessionCheckpoint.decode(state.getString("assembly_phase_session"));
        // Controllers render their restored checkpoint when the repository callback
        // completes. Never reload/reset a live phase from an Android view callback.
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(visionInitializationStarted){recognitionText.removeCallbacks(referenceScopeMonitor);recognitionText.post(referenceScopeMonitor);}
        assemblyVisibilityGeneration++;
        activityResumed = true;
        if(analyzer!=null)analyzer.resume();
        if(componentPreview!=null) componentPreview.resume();
        if (assemblyDemo != null && !awaitingPartScan
                && !(activeCheckpoint != null && activeCheckpoint.getKind() == GuidedCheckpoint.Kind.IDENTITY
                && !currentCheckpointComplete)) assemblyDemo.resume();
        acceptRecognitionAfterNanos = SystemClock.elapsedRealtimeNanos();
        PhaseAssemblyController phases = phaseController;
        if (!libraryPreparing && libraryFailure==null && phases != null && (wiringReviewDialog==null||!wiringReviewDialog.isShowing())) phases.onResume();
        if (arGlView != null) {
            arGlView.onResume();
        }
        if (currentStep != null
                && getIntent().getStringExtra(EXTRA_SIMULATED_STATE) == null
                && hasCameraPermission()) {
            // Also covers a user granting access from the app-settings screen, which does not
            // deliver a RequestPermission callback to this Activity.
            onCameraPermissionGranted();
        }
    }

    @Override
    protected void onPause() {
        if(planFHelp!=null)planFHelp.suspend();
        outlineUpdates.clear();
        dev.chyken.assembl_ar.ui.DynamicComponentOutlineOverlayView outline=findViewById(R.id.dynamicOutlineOverlay);if(outline!=null)outline.setCameraActive(false);
        recognitionText.removeCallbacks(referenceScopeMonitor);
        activityResumed = false;
        if(componentPreview!=null) componentPreview.pause();
        calibrationByFrame.clear();
        PhaseAssemblyController phases = phaseController;
        if (phases != null) phases.onPause();
        if (voiceGuide != null) voiceGuide.stop();
        if (assemblyDemo != null) assemblyDemo.pause();
        partIntroductionGate.reset();
        bracketSetGate.reset();
        assemblyVisibilityGeneration++;
        if (analyzer != null) analyzer.pause();
        if (arHudView != null) arHudView.setBoardCorners(null);
        if (currentStep != null && (activeCheckpoint == null
                ? !isManualOnlyFinalCheck(currentStep) : activeCheckpoint.requiresCamera())
                && !physicalCheckInProgress) {
            recognitionText.removeCallbacks(expireSectionReadiness);
            recognitionText.removeCallbacks(expireMotorReadiness);
            resetStableTracking();
            revokeMotorReadiness();
            motorInspectionStatus.setText(R.string.motor_inspection_initial);
            arHudView.reset();
            arGlView.hideGuidance();
        }
        if (arGlView != null) {
            arGlView.onPause();
        }
        super.onPause();
    }

    @Override public void onTrimMemory(int level){
        super.onTrimMemory(level);
        if(level==android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW||level==android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL){
            detailCaptureUnavailable=true;
            if(activityResumed&&boundCamera!=null&&cameraCaptureEdge>640)startCamera(visionGeneration);
        }
    }
    @Override protected void onStop(){
        if(analyzer!=null)analyzer.pause();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if(planFHelp!=null)planFHelp.close();
        if(muteControl!=null)muteControl.close();
        destroyed = true;prefetchGeneration++;
        if(prefetchedBank!=null){prefetchedBank.close();prefetchedBank=null;}
        if(wiringReviewDialog!=null)wiringReviewDialog.dismiss();
        if(componentPreview!=null) componentPreview.dispose();
        boundCamera = null;
        cameraPreviewUseCase=null;
        PhaseAssemblyController phases = phaseController;
        if (phases != null) phases.onDestroy();
        phaseController = null;
        if (trialLog != null) trialLog.close();
        trialLog = null;
        calibrationByFrame.clear();
        if (voiceGuide != null) voiceGuide.close();
        recognitionText.removeCallbacks(expireMotorReadiness);
        recognitionText.removeCallbacks(expireSectionReadiness);
        if (stepInfoPanel != null) stepInfoPanel.dispose();
        visionGeneration++;referenceBankGeneration++;
        recognitionText.removeCallbacks(referenceScopeMonitor);
        visionInitializationStarted = false;
        // Stop obsolete loads on exit/recreation so a resumed screen does not compete with them.
        visionLoaderExecutor.shutdownNow();
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
            imageAnalysis = null;
        }
        if (cameraLayoutListener != null) {
            previewView.removeOnLayoutChangeListener(cameraLayoutListener);
            cameraLayoutListener = null;
        }
        if (analyzer != null) analyzer.close();
        if (cameraProviderFuture != null && cameraProviderFuture.isDone()) {
            try {
                cameraProviderFuture.get().unbindAll();
            } catch (Exception ignored) {
                // Camera provider may already be shutting down with the lifecycle.
            }
        }
        ComponentRecognizer recognizerToClose = recognizer;
        ReferenceDatabase databaseToClose = referenceDatabase;
        recognizer = null;
        referenceDatabase = null;
        try {
            // Serialized after any in-flight analysis, so native recognizer/database resources
            // cannot be released while the camera thread is still using them.
            cameraExecutor.execute(() ->
                    closeVisionResources(recognizerToClose, databaseToClose));
            cameraExecutor.shutdown();
        } catch (RejectedExecutionException ignored) {
            closeVisionResources(recognizerToClose, databaseToClose);
            cameraExecutor.shutdownNow();
        }
        databaseExecutor.shutdownNow();
        super.onDestroy();
    }
}
