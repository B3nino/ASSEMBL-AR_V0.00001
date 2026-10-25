package dev.chyken.assembl_ar;
import dev.chyken.assembl_ar.camera.DiagnosticTargetCatalog;
import dev.chyken.assembl_ar.camera.DiagnosticTargetCatalog.Target;
import dev.chyken.assembl_ar.ui.DiagnosticTargetPicker;
import dev.chyken.assembl_ar.vision.VisionPreloadManager;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Size;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;
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
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.common.util.concurrent.ListenableFuture;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.chyken.assembl_ar.camera.CameraCalibrationProvider;
import dev.chyken.assembl_ar.camera.IndividualPartTestCatalog;
import dev.chyken.assembl_ar.camera.VisionReferenceFactory;
import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.data.NeobotSeedData;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.model.ComponentCategory;
import dev.chyken.assembl_ar.model.OverlayType;
import dev.chyken.assembl_ar.render.ArCameraProjection;
import dev.chyken.assembl_ar.render.ArGlSurfaceView;
import dev.chyken.assembl_ar.render.ArGuidance;
import dev.chyken.assembl_ar.ui.ArHudView;
import dev.chyken.assembl_ar.ui.UiUtils;
import dev.chyken.assembl_ar.vision.AssemblyVisionAnalyzer;
import dev.chyken.assembl_ar.vision.CameraCalibration;
import dev.chyken.assembl_ar.vision.ComponentRecognizer;
import dev.chyken.assembl_ar.vision.ComponentReferenceSpec;
import dev.chyken.assembl_ar.vision.LoosePartShapeRecognizer;
import dev.chyken.assembl_ar.vision.RecognitionRequest;
import dev.chyken.assembl_ar.vision.RecognitionResult;
import dev.chyken.assembl_ar.vision.ReferenceDatabase;
import dev.chyken.assembl_ar.vision.MeasurementRecorder;
import dev.chyken.assembl_ar.vision.VisionConfig;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.workflow.PartRecognitionTestSession;
import dev.chyken.assembl_ar.workflow.PartRecognitionTestSession.Snapshot;
import dev.chyken.assembl_ar.workflow.PartTestSampleArchive;

/** Independent diagnostic camera: no assembly state machine, anchor, completion or progress writes. */
public final class PartRecognitionTestActivity extends AppCompatActivity {
    public static final String EXTRA_COMPONENT_ID = "component_id";
    public static final String EXTRA_TEST_MODE = "test_mode";
    private static final String STATE_PENDING_SAMPLE_FILE = "pending_sample_export_file";
    private static final String PENDING_SAMPLE_DIRECTORY = "pending_part_sample_exports";
    private static final String PENDING_SAMPLE_PREFIX = "part-sample-";
    private static final String PENDING_SAMPLE_SUFFIX = ".arpart.zip";
    private static final long STALE_PENDING_SAMPLE_AGE_MS = 7L * 24L * 60L * 60L * 1000L;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor();
    private final List<Component> components = new ArrayList<>(NeobotSeedData.getComponents());
    private List<Target> targets=java.util.Collections.emptyList();
    private volatile Target activeTarget;
    private String pendingTargetKey;
    private boolean startupPending;
    private volatile int bankGeneration;
    private final Map<String,PartRecognitionTestSession> targetSessions=new LinkedHashMap<>();
    private final ActivityResultLauncher<Intent> startupPreparation=registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),result->{startupPending=false;if(result.getResultCode()==RESULT_OK)initializeVision();else finish();});
    private final Set<String> readyPartIds = new HashSet<>();
    private PartRecognitionTestSession session;
    private int selectedIndex;
    private volatile ComponentReferenceSpec selectedReference;
    private boolean testMode;
    private boolean catalogLoading;
    private boolean catalogReady;
    private boolean resumed;
    private boolean cameraBound;
    private boolean resumeRequested;
    @Nullable private Preview cameraPreviewUseCase;
    private boolean permissionRequested;
    private volatile boolean destroyed;
    private volatile boolean testing;
    @Nullable private volatile ActiveTest activeTest;
    private final java.util.concurrent.ConcurrentHashMap<Long,FrameContext> contexts=new java.util.concurrent.ConcurrentHashMap<>();
    @Nullable private volatile FrameContext frameContext;
    private volatile CameraCalibrationProvider calibrationProvider = CameraCalibrationProvider.approximate();
    @Nullable private CameraCalibration latestCalibration;
    @Nullable private ComponentRecognizer recognizer;
    @Nullable private ReferenceDatabase referenceDatabase;
    @Nullable private AssemblyVisionAnalyzer analyzer;
    @Nullable private ImageAnalysis analysis;
    @Nullable private ProcessCameraProvider cameraProvider;
    @Nullable private Camera boundCamera;
    @Nullable private View.OnLayoutChangeListener layoutListener;
    private PreviewView preview;
    private final dev.chyken.assembl_ar.ui.LatestUiTask outlineUpdates=new dev.chyken.assembl_ar.ui.LatestUiTask(task->runOnUiThread(task));
    private ArGlSurfaceView arView;
    private ArHudView hud;
    private TextView status;
    private TextView liveMetrics;
    private TextView totals;
    private TextView referenceStatus;
    private MaterialButton selectButton;
    private MaterialButton startButton;
    private MaterialButton captureButton;
    private MaterialButton replayButton;
    @Nullable private String pendingExport;
    @Nullable private String pendingSampleExportFile;
    private final ActivityResultLauncher<String> permission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) initializeVision();
                else status.setText(R.string.part_test_permission);
            });
    private final ActivityResultLauncher<String> export = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
                String csv = pendingExport;
                pendingExport = null;
                if (uri != null && csv != null) saveCsv(uri, csv);
            });
    private final ActivityResultLauncher<String> sampleExport = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/zip"), uri -> {
                String sampleFile = pendingSampleExportFile;
                pendingSampleExportFile = null;
                if (uri != null && sampleFile != null) saveSample(uri, sampleFile);
                else deletePendingSampleFile(sampleFile);
            });
    private final ActivityResultLauncher<String[]> sampleImport = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) readAndReplaySample(uri);
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        MeasurementRecorder.install(this);
        dev.chyken.assembl_ar.diagnostics.DiagnosticSession.INSTANCE.attach(this);
        UiUtils.configureStandardWindow(this);
        setContentView(R.layout.activity_part_recognition_test);
        UiUtils.applyStandardInsets(this);
        UiUtils.setupToolbar(this, getString(R.string.part_test_title));
        RetainedState retained = (RetainedState) getLastCustomNonConfigurationInstance();
        session = retained == null ? new PartRecognitionTestSession() : retained.session;
        selectedIndex = retained == null ? initialIndex() : retained.selectedIndex;
        if(retained!=null){targetSessions.putAll(retained.sessions);pendingTargetKey=retained.targetKey;}
        pendingSampleExportFile = retained == null ? null : retained.pendingSampleExportFile;
        if (state != null) {
            resumeRequested=state.getBoolean("test_running",false);
            pendingTargetKey=state.getString("target_key",pendingTargetKey);
            selectedIndex = Math.max(0, Math.min(components.size() - 1, state.getInt("part_index", selectedIndex)));
            permissionRequested = state.getBoolean("permission_requested");
            pendingExport = state.getString("pending_export");
            String restoredSampleFile = state.getString(STATE_PENDING_SAMPLE_FILE);
            if (restoredSampleFile != null) pendingSampleExportFile = restoredSampleFile;
        }
        String protectedSampleFile = pendingSampleExportFile;
        exportExecutor.execute(() -> cleanupStalePendingSamples(protectedSampleFile));
        testMode = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent().getBooleanExtra(EXTRA_TEST_MODE, false);
        preview = findViewById(R.id.partTestPreview);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        arView = findViewById(R.id.partTestArView);
        arView.useDynamicOutlines();
        hud = findViewById(R.id.partTestHud);
        hud.useDynamicOutlines();
        status = findViewById(R.id.partTestStatus);
        liveMetrics = findViewById(R.id.partTestLiveMetrics);
        totals = findViewById(R.id.partTestTotals);
        referenceStatus = findViewById(R.id.partTestReferenceStatus);
        selectButton = findViewById(R.id.partTestSelectButton);
        startButton = findViewById(R.id.partTestStartButton);
        captureButton = findViewById(R.id.partTestCaptureButton);
        replayButton = findViewById(R.id.partTestReplayButton);
        selectButton.setOnClickListener(view -> choosePart());
        findViewById(R.id.partTestPreviousButton).setOnClickListener(view -> selectAdjacent(-1));
        findViewById(R.id.partTestNextButton).setOnClickListener(view -> selectAdjacent(1));
        startButton.setOnClickListener(view -> { if (testing) pauseTest(); else startTest(); });
        findViewById(R.id.partTestResetButton).setOnClickListener(view -> resetSelectedPart());
        findViewById(R.id.partTestResultsButton).setOnClickListener(view -> showResults());
        findViewById(R.id.partTestExportButton).setOnClickListener(view -> dev.chyken.assembl_ar.diagnostics.DiagnosticLogger.get(this).chooseExport(this,dev.chyken.assembl_ar.diagnostics.DiagnosticSession.INSTANCE.runner.report()));
        findViewById(R.id.partTestBenchmarkButton).setOnClickListener(view->DevDiagnosticsFragment.showControls(this));
        captureButton.setOnClickListener(view -> captureSample());
        replayButton.setOnClickListener(view -> sampleImport.launch(new String[]{
                "application/zip", "application/octet-stream"}));
        preview.setOnTouchListener((view, event) -> focusCamera(event));
        if (testMode) {
            for (Component component : components) readyPartIds.add(component.getId());
            catalogReady = true;
        }
        renderSelection();
    }

    private int initialIndex() {
        String id = getIntent().getStringExtra(EXTRA_COMPONENT_ID);
        for (int index = 0; index < components.size(); index++) {
            if (components.get(index).getId().equals(id)) return index;
        }
        return 0;
    }

    private void choosePart() {
        pauseTest();
        if(!testMode){if(!targets.isEmpty())DiagnosticTargetPicker.show(this,targets,this::selectTarget);return;}
        String[] names=new String[components.size()];for(int i=0;i<names.length;i++)names[i]=components.get(i).getName();
        new AlertDialog.Builder(this).setTitle(R.string.part_test_choose).setSingleChoiceItems(names,selectedIndex,(d,i)->{selectPart(i);d.dismiss();}).setNegativeButton(android.R.string.cancel,null).show();
    }
    private void selectAdjacent(int direction){
        if(!testMode&&!targets.isEmpty()){int current=activeTarget==null?0:targets.indexOf(activeTarget);selectTarget(targets.get(Math.floorMod(current+direction,targets.size())));}
        else selectPart(Math.floorMod(selectedIndex+direction,components.size()));
    }
    private void selectTarget(Target target){
        pauseTest();
        if(activeTarget!=null)targetSessions.put(activeTarget.key,session);
        activeTarget=target;pendingTargetKey=target.key;selectedReference=target.specialized?target.representative:null;
        session=targetSessions.computeIfAbsent(target.key,key->new PartRecognitionTestSession());
        for(int i=0;i<components.size();i++)if(components.get(i).getId().equals(target.componentId))selectedIndex=i;
        catalogReady=false;renderSelection();releaseTargetBank();initializeVision();
    }
    private boolean specializedTarget(){return activeTarget!=null?activeTarget.specialized:selectedReference!=null&&(dev.chyken.assembl_ar.camera.UnifiedReferenceCatalog.assembly(selectedReference)||getIntent().getBooleanExtra("assembly_test",false));}
    private void releaseTargetBank(){
        if(analysis!=null)analysis.clearAnalyzer();
        AssemblyVisionAnalyzer previous=analyzer;ComponentRecognizer worker=recognizer;ReferenceDatabase bank=referenceDatabase;
        analyzer=null;recognizer=null;referenceDatabase=null;
        cameraExecutor.execute(()->{if(previous!=null)previous.close();if(worker!=null)worker.close();if(bank!=null)bank.close();});
    }

    private void selectPart(int index) {
        if(!testMode&&!targets.isEmpty()){String id=components.get(Math.floorMod(index,components.size())).getId();for(Target target:targets)if(!target.specialized&&target.componentId.equals(id)){selectTarget(target);return;}return;}
        pauseTest();
        selectedIndex = (index + components.size()) % components.size();
        renderSelection();
    }

    private void renderSelection() {
        Component component = components.get(selectedIndex);
        selectButton.setText(getString(R.string.part_test_selected,
                activeTarget==null?selectedIndex+1:targets.indexOf(activeTarget)+1, activeTarget==null?components.size():targets.size(), activeTarget==null?component.getName():activeTarget.title));
        selectButton.setEnabled(testMode||!targets.isEmpty());
        referenceStatus.setText(testMode ? R.string.part_test_test_input : !catalogReady
                ? R.string.part_test_reference_pending : !readyPartIds.contains(component.getId())
                ? R.string.part_test_missing : IndividualPartTestCatalog.getStandaloneComponentIds().contains(component.getId())
                ? R.string.part_test_reference_ready : R.string.part_test_reference_baseline);
        liveMetrics.setText(R.string.part_test_live_empty);
        hud.reset();
        arView.hideGuidance();
        startButton.setText(R.string.part_test_start);
        startButton.setEnabled(catalogReady && readyPartIds.contains(component.getId()));
        boolean assemblyTest=specializedTarget();
        findViewById(R.id.partTestPreviousButton).setEnabled(catalogReady);findViewById(R.id.partTestNextButton).setEnabled(catalogReady);
        if(catalogReady&&activeTarget!=null)referenceStatus.setText(activeTarget.summary());
        captureButton.setEnabled(false);
        replayButton.setEnabled(catalogReady && recognizer != null);
        if (catalogReady && !readyPartIds.contains(component.getId())) status.setText(R.string.part_test_missing);
        else if (!testMode && !hasCameraPermission()) status.setText(R.string.part_test_permission);
        else status.setText(catalogLoading ? R.string.part_test_preparing : R.string.part_test_ready);
        renderTotals();
    }

    private void startTest() {
        if (!testMode && !hasCameraPermission()) {
            if (permissionRequested && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                new AlertDialog.Builder(this).setTitle(R.string.part_test_settings_title)
                        .setMessage(R.string.part_test_settings_body)
                        .setPositiveButton(R.string.part_test_settings, (dialog, which) -> startActivity(
                                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + getPackageName()))))
                        .setNegativeButton(android.R.string.cancel, null).show();
            } else {
                permissionRequested = true;
                permission.launch(Manifest.permission.CAMERA);
            }
            return;
        }
        if (!catalogReady || !testMode && !cameraBound) {
            initializeVision();
            if (catalogReady) bindCamera();
            status.setText(R.string.part_test_preparing);
            return;
        }
        Component part = components.get(selectedIndex);
        if (!readyPartIds.contains(part.getId())) {
            status.setText(R.string.part_test_missing);
            return;
        }
        float minimumAreaRatio = minimumAreaRatio(part);
        long generation = session.selectPart(part.getId(), activeTarget==null?part.getName():activeTarget.title, minimumAreaRatio);
        activeTest = new ActiveTest(generation, part.getId(), minimumAreaRatio);
        testing = true;
        resumeRequested=false;
        if(analyzer!=null)analyzer.resume();
        hud.setScanningEnabled(true);
        ((dev.chyken.assembl_ar.ui.DynamicComponentOutlineOverlayView)findViewById(R.id.dynamicOutlineOverlay)).setCameraActive(true);
        if (analyzer != null && analysis != null) analysis.setAnalyzer(cameraExecutor, analyzer);
        startButton.setText(R.string.part_test_pause);
        captureButton.setEnabled(analyzer != null);
        status.setText(getString(R.string.part_test_scanning, part.getName()));
        renderTotals();
    }

    boolean pauseForDiagnostics(){boolean active=testing;pauseTest();return active;}
    void resumeAfterDiagnostics(){if(!destroyed&&resumed)startTest();}
    private void pauseTest() {
        resumeRequested=false;
        outlineUpdates.clear();
        testing = false;
        dev.chyken.assembl_ar.ui.DynamicComponentOutlineOverlayView overlay=findViewById(R.id.dynamicOutlineOverlay);if(overlay!=null)overlay.setCameraActive(false);
        if(outlineVoice!=null)outlineVoice.stop();
        activeTest = null;
        contexts.clear();frameContext=null;
        session.pause();
        if (analyzer != null) analyzer.cancelPendingFrameSnapshot();
        if (analysis != null) analysis.clearAnalyzer();
        captureButton.setEnabled(false);
        if (hud == null) return;
        hud.reset();
        arView.hideGuidance();
        startButton.setText(R.string.part_test_start);
        status.setText(R.string.part_test_paused);
        renderTotals();
    }

    private void resetSelectedPart() {
        pauseTest();
        session.resetPart(components.get(selectedIndex).getId());
        renderSelection();
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void initializeVision() {
        if(testMode||destroyed||catalogLoading||catalogReady)return;
        VisionPreloadManager preload=VisionPreloadManager.get(this);
        if(!preload.isReady()){if(!startupPending){startupPending=true;startupPreparation.launch(new Intent(this,SplashActivity.class));}return;}
        catalogLoading=true;final int generation=++bankGeneration;
        status.setText("Opening selected scanner…");
        String desired=activeTarget==null?pendingTargetKey:activeTarget.key;
        cameraExecutor.execute(()->{
            ReferenceDatabase database=null;ComponentRecognizer loadedRecognizer=null;
            try{
                List<ComponentReferenceSpec> catalog=preload.start().get();
                List<Target> available=IndividualPartTestCatalog.selectableTargets(catalog,dev.chyken.assembl_ar.vision.KeypointInventoryManager.INSTANCE::count);
                if(available.isEmpty())throw new IllegalStateException("No prepared photographic targets in the library");
                Target chosen=null;String ref=getIntent().getStringExtra("reference_id"),component=getIntent().getStringExtra(EXTRA_COMPONENT_ID);
                for(Target t:available)if(t.key.equals(desired))chosen=t;
                if(chosen==null&&ref!=null)for(Target t:available)for(ComponentReferenceSpec r:t.references)if(r.getReferenceId().equals(ref))chosen=t;
                if(chosen==null&&component!=null)for(Target t:available)if(!t.specialized&&t.componentId.equals(component)){chosen=t;break;}
                if(chosen==null)chosen=available.get(0);
                Target selected=chosen;List<ComponentReferenceSpec> specs=chosen.specialized
                    ?dev.chyken.assembl_ar.camera.ActiveReferenceScope.select(catalog,chosen.request(null))
                    :dev.chyken.assembl_ar.camera.UnifiedReferenceCatalog.individual(catalog);
                database=chosen.specialized?preload.loadBank(specs,()->!destroyed&&generation==bankGeneration)
                    :preload.loadIndividualBank(specs,chosen.componentId,()->!destroyed&&generation==bankGeneration);
                loadedRecognizer=new ComponentRecognizer(VisionConfig.defaults(),database);
                List<Component> metadata=dev.chyken.assembl_ar.data.AssemblArRepository.getInstance(this).getComponents();
                ReferenceDatabase readyDatabase=database;ComponentRecognizer readyRecognizer=loadedRecognizer;
                runOnUiThread(()->{
                    if(destroyed||generation!=bankGeneration){readyRecognizer.close();readyDatabase.close();return;}
                    components.clear();components.addAll(metadata);targets=available;activeTarget=selected;pendingTargetKey=selected.key;
                    selectedReference=selected.specialized?selected.representative:null;
                    for(int i=0;i<components.size();i++)if(components.get(i).getId().equals(selected.componentId))selectedIndex=i;
                    targetSessions.putIfAbsent(selected.key,session);
                    referenceDatabase=readyDatabase;recognizer=readyRecognizer;readyPartIds.clear();for(Target target:available)readyPartIds.add(target.componentId);
                    catalogLoading=false;catalogReady=true;renderSelection();if(resumed)bindCamera();
                });
            }catch(Exception failure){
                if(loadedRecognizer!=null)loadedRecognizer.close();if(database!=null)database.close();
                runOnUiThread(()->{if(destroyed||generation!=bankGeneration)return;catalogLoading=false;status.setText("Scanner unavailable · tap Start to retry");startButton.setEnabled(true);android.util.Log.e("PartRecognitionTest","Prepared bank could not be opened",failure);});
            }
        });
    }

    private void bindCamera() {
        if (destroyed || testMode || !resumed || !catalogReady || !hasCameraPermission()) return;
        if(cameraBound){if(analyzer==null)analyzer=createAnalyzer();resumeSavedTest();return;}
        if (preview.getViewPort() == null) {
            if (layoutListener == null) {
                layoutListener = (view, l, t, r, b, ol, ot, or, ob) -> {
                    if (preview.getViewPort() != null) {
                        preview.removeOnLayoutChangeListener(layoutListener);
                        layoutListener = null;
                        bindCamera();
                    }
                };
                preview.addOnLayoutChangeListener(layoutListener);
            }
            return;
        }
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (destroyed || !resumed || !catalogReady || recognizer==null || cameraBound || !hasCameraPermission()) return;
            try {
                ProcessCameraProvider provider = future.get();
                ViewPort viewPort = preview.getViewPort();
                if (viewPort == null) return;
                Preview cameraPreview = new Preview.Builder().build();
                cameraPreview.setSurfaceProvider(preview.getSurfaceProvider());
                // Request the complete VGA sensor buffer; PreviewView supplies its own
                // crop. A 640x400 bound with LOWER fallback selected 320x240 on tablets
                // offering 640x480, losing the small PCB features before ORB sees them.
                ResolutionSelector analysisResolution = new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(new Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .setResolutionFilter(PartRecognitionTestActivity::boundedSampleResolutions)
                        .build();
                analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setResolutionSelector(analysisResolution).build();
                analyzer = createAnalyzer();
                provider.unbindAll();
                Camera camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        new UseCaseGroup.Builder().addUseCase(cameraPreview).addUseCase(analysis)
                                .setViewPort(viewPort).build());
                cameraProvider = provider;
                boundCamera = camera;
                calibrationProvider = CameraCalibrationProvider.from(camera.getCameraInfo());
                cameraBound = true;
                cameraPreviewUseCase=cameraPreview;
                renderSelection();
                resumeSavedTest();
            } catch (Exception failure) {
                if (analyzer != null) analyzer.close();
                if (analysis != null) analysis.clearAnalyzer();
                analyzer = null;
                analysis = null;
                boundCamera = null;
                status.setText("Rear camera unavailable · tap Start to retry");
                android.util.Log.e("PartRecognitionTest", "Camera binding failed", failure);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private AssemblyVisionAnalyzer createAnalyzer(){
        final int analyzerBank=bankGeneration;
        AssemblyVisionAnalyzer created = new AssemblyVisionAnalyzer(recognizer, new AssemblyVisionAnalyzer.RequestProvider(){
                    public RecognitionRequest requestFor(AssemblyVisionAnalyzer.FrameMetadata frame){return analyzerBank==bankGeneration?requestForFrame(frame):null;}
                    public RecognitionRequest requestForTracking(AssemblyVisionAnalyzer.FrameMetadata frame){return analyzerBank==bankGeneration?requestForFrame(frame,false):null;}
                    public boolean isAnalysisSuspended(){return analyzerBank!=bankGeneration||!testing||!resumed;}
                    public boolean isTrackingSuspended(){return analyzerBank!=bankGeneration||!testing||!resumed;}
                    public boolean requiresMetricTracking(){return dev.chyken.assembl_ar.diagnostics.DiagnosticSession.INSTANCE.runner.running();}
                },
                        new AssemblyVisionAnalyzer.Callback() {
                            @Override public void onResult(RecognitionResult result) {
                                FrameContext frame = contexts.remove(result.getFrameTimestampNanos());
                                if (frame == null || analyzerBank!=bankGeneration) return;
                                runOnUiThread(() -> {
                                    if(analyzerBank!=bankGeneration)return;
                                    latestCalibration = frame.calibration;
                                    handleTestResult(frame.generation, result, frame.width, frame.height);
                                });
                            }
                            @Override public void onDiagnosticSample(dev.chyken.assembl_ar.diagnostics.DiagnosticSample sample){runOnUiThread(()->{if(resumed&&testing&&analyzerBank==bankGeneration)((dev.chyken.assembl_ar.ui.DevDiagnosticsOverlayView)findViewById(R.id.devDiagnosticsOverlay)).showTelemetry(sample.telemetry);});}
                            @Override public void onTargetTracking(dev.chyken.assembl_ar.vision.TargetBoundingTracker.Target target,AssemblyVisionAnalyzer.FrameMetadata frame,Object tag){outlineUpdates.submit(()->{
                                if(analyzerBank!=bankGeneration||!resumed||!testing||activeTest==null||!java.util.Objects.equals(activeTest.generation,tag)||target!=null&&(analyzer==null||target.epoch!=analyzer.visualEpoch()))return;
                                java.util.List<float[]> regions=new java.util.ArrayList<>();if(target!=null)for(java.util.List<VisionPoint> region:target.regions)regions.add(dev.chyken.assembl_ar.vision.PreviewOutlineCoordinates.normalize(region,frame.getAnalyzedWidth(),frame.getAnalyzedHeight(),preview.getWidth(),preview.getHeight()));
                                ((dev.chyken.assembl_ar.ui.DynamicComponentOutlineOverlayView)findViewById(R.id.dynamicOutlineOverlay)).show(target,regions);
                                if(target!=null&&!target.predicted&&target.state==dev.chyken.assembl_ar.vision.TargetBoundingTracker.State.WRONG&&!target.name.equals(target.expectedName)){
                                    if(outlineVoice==null)outlineVoice=new dev.chyken.assembl_ar.ui.AssemblyVoiceGuide(PartRecognitionTestActivity.this,voiceStatus->{});
                                    outlineVoice.speak("Incorrect item detected. You are scanning "+target.name+" instead of "+target.expectedName+".","outline-wrong:"+tag+":"+target.componentId);
                                }
                            });}
                            @Override public void onTracking(RecognitionResult.SpatialAnchor anchor,AssemblyVisionAnalyzer.FrameMetadata frame,CameraCalibration calibration,boolean predicted,Object tag){runOnUiThread(()->{if(analyzerBank!=bankGeneration||!resumed||!testing||activeTest==null||!java.util.Objects.equals(activeTest.generation,tag))return;dev.chyken.assembl_ar.render.ArOverlayRenderer.track(arView,anchor);});}
                            @Override public void onError(Throwable failure) {
                                FrameContext frame = frameContext;
                                runOnUiThread(() -> {
                                    ActiveTest active = activeTest;
                                    if (!destroyed && analyzerBank==bankGeneration && testing && frame != null && active != null
                                            && active.generation == frame.generation) {
                                        pauseTest();
                                        status.setText("Analysis error · test paused; tap Start to retry");
                                    }
                                });
                            }
                        }, VisionConfig.defaults(), true);
        created.attachMotion(this);
        return created;
    }

    private static List<Size> boundedSampleResolutions(
            List<Size> supportedSizes, int rotationDegrees) {
        List<Size> bounded = new ArrayList<>();
        for (Size size : supportedSizes) {
            if (PartTestSampleArchive.supportsFullColorFrameGeometry(
                    size.getWidth(), size.getHeight())) {
                bounded.add(size);
            }
        }
        if (bounded.isEmpty()) {
            throw new IllegalStateException(
                    "Camera has no analysis resolution safe for diagnostic sample replay");
        }
        return bounded;
    }

    @Nullable private RecognitionRequest requestForFrame(AssemblyVisionAnalyzer.FrameMetadata metadata) {
        return requestForFrame(metadata,true);
    }
    @Nullable private RecognitionRequest requestForFrame(AssemblyVisionAnalyzer.FrameMetadata metadata,boolean detection) {
        ActiveTest active = activeTest;
        if (!testing || active == null) return null;
        CameraCalibration calibration = calibrationProvider.forFrame(metadata);
        FrameContext context = new FrameContext(active.generation, metadata.getTimestampNanos(),metadata.getAnalyzedWidth(), metadata.getAnalyzedHeight(), calibration);
        // LK frames must not evict a slower detector's result context. Only a real
        // recognition job owns a metrics/sample entry.
        if(detection){frameContext=context;if(contexts.size()>128)contexts.clear();contexts.put(context.timestamp,context);}
        RecognitionRequest request=specializedTarget()
            ?dev.chyken.assembl_ar.camera.UnifiedReferenceCatalog.request(selectedReference,calibration)
            :IndividualPartTestCatalog.requestFor(active.componentId, calibration, active.minimumAreaRatio);
        return request.withRequestTag(active.generation);
    }

    private void handleTestResult(long generation, RecognitionResult result, int width, int height) {
        ActiveTest active = activeTest;
        // Camera timestamps are not guaranteed to use the elapsedRealtime clock on every device.
        // The request token isolates runs; the session rejects duplicate/out-of-order frames.
        if (destroyed || !resumed || !testing || active == null || generation != active.generation) return;
        float[] corners = normalizeCorners(result.getProjectedCorners(), width, height);
        if (!session.observe(generation, result, corners)) return;
        Snapshot snapshot = session.getSnapshot(active.componentId);
        boolean detected = result.hasLocatedComponent();
        boolean expected = detected && active.componentId.equals(result.getComponentId())&&(result.getDiagnosticMetrics()==null||result.getDiagnosticMetrics().mismatch==null);
        String detectedName = detected ? componentName(result.getComponentId()) : "None";
        hud.update(!detected ? ArHudView.HudState.NOT_RECOGNIZED : !expected
                        ? ArHudView.HudState.MISMATCH : snapshot.isLocked()
                        ? ArHudView.HudState.MATCHED : ArHudView.HudState.TRACKING, detectedName, corners);
        String method = result.getDetectionMethod() == RecognitionResult.DetectionMethod.CANNY_HOUGH_FALLBACK
                ? "Circular fallback (shape only)" : result.getDetectionMethod() == RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY
                ? "ORB image match + pose" : result.getDetectionMethod()
                == RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY
                ? "ORB identity only (no pose)" : result.getDetectionMethod()
                == RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK
                ? "Color/shape match + coarse pose" : "None";
        StringBuilder metrics = new StringBuilder(String.format(Locale.US,
                "Detected: %s\nMethod: %s\nFrame: %d×%d · Features: %d\nConfidence: %.0f%% · Matches/inliers: %d/%d\nPose: %s · Stable frames: %d / 4",
                detectedName, method, width, height, result.getFrameKeypointCount(),
                result.getConfidence() * 100, result.getGoodMatchCount(), result.getInlierCount(),
                result.getPose() == null ? "missing" : "available", snapshot.getStableFrames()));
        RecognitionResult.RejectionEvidence rejection = result.getRejectionEvidence();
        if (rejection != null) {
            metrics.append(String.format(Locale.US,
                    "\nRejected: %s\nReference: %s · Ref features: %d\nGood: %d/%d · Valid: %d · Inliers: %d/%d (%.0f%%/%.0f%%)",
                    rejectionLabel(rejection.getReason()),
                    rejection.getReferenceId() == null ? "none" : rejection.getReferenceId(),
                    rejection.getReferenceKeypointCount(), rejection.getGoodMatchCount(),
                    rejection.getRequiredGoodMatchCount(), rejection.getValidMatchCount(),
                    rejection.getInlierCount(), rejection.getRequiredInlierCount(),
                    Double.isFinite(rejection.getInlierRatio())
                            ? rejection.getInlierRatio() * 100 : 0.0,
                    rejection.getRequiredInlierRatio() * 100));
        }
        liveMetrics.setText(metrics);
        dev.chyken.assembl_ar.ui.DevDiagnosticsOverlayView diagnostic=findViewById(R.id.devDiagnosticsOverlay);
        dev.chyken.assembl_ar.diagnostics.DiagnosticSample latest=dev.chyken.assembl_ar.diagnostics.DiagnosticSession.INSTANCE.latest;
        diagnostic.setText(latest==null?"Waiting for diagnostic sample":latest.hudText());
        if (!detected) status.setText(rejection == null
                ? "No detection · save a sample and try another angle"
                : "No detection · " + rejectionLabel(rejection.getReason()));
        else if (!expected) status.setText("Wrong identity · detected " + detectedName);
        else if (snapshot.isLocked()) status.setText("Part tracked · stable pose established");
        else if (result.getPose() == null) status.setText("Part identified · pose not established");
        else status.setText("Part identified · " + (snapshot.getLastObservation() == null
                ? "hold steady" : snapshot.getLastObservation().getReason().name().replace('_', ' ').toLowerCase(Locale.ROOT)));
        if (expected && result.getPose() != null && snapshot.getLastObservation() != null
                && snapshot.getLastObservation().isFrameValid()) {
            ArCameraProjection projection = latestCalibration == null
                    ? ArCameraProjection.pinhole(width, height, Math.max(width, height), Math.max(width, height), width / 2.0, height / 2.0)
                    : ArCameraProjection.fromCameraMatrix(width, height, latestCalibration.getCameraMatrixRowMajor());
            arView.showGuidance(ArGuidance.builder(active.componentId, OverlayType.LABEL, OverlayType.TARGET_GLOW_RING)
                    .pose(result.getPose().getOpenGlModelViewMatrix())
                    .coordinateSpace(ArGuidance.CoordinateSpace.POSE_UNITS)
                    .ghostStyle(ArGuidance.GhostStyle.NONE)
                    .componentTarget(ArGuidance.Point3.at(0, 0, 0))
                    .targetMarker(ArGuidance.TargetMarker.COMPONENT_PLACEMENT).markerRadius(4f).build(), projection);
        } else arView.hideGuidance();
        renderTotals();
    }

    @Nullable private float[] normalizeCorners(List<VisionPoint> points, int width, int height) {
        if (points == null || points.size() != 4 || width < 1 || height < 1) return null;
        int viewWidth = Math.max(1, preview.getWidth());
        int viewHeight = Math.max(1, preview.getHeight());
        double scale = Math.max(viewWidth / (double) width, viewHeight / (double) height);
        float[] corners = new float[8];
        for (int index = 0; index < 4; index++) {
            corners[index * 2] = (float) ((points.get(index).getX() * scale + (viewWidth - width * scale) / 2) / viewWidth);
            corners[index * 2 + 1] = (float) ((points.get(index).getY() * scale + (viewHeight - height * scale) / 2) / viewHeight);
        }
        return corners;
    }

    private String componentName(String id) {
        for (Component part : components) if (part.getId().equals(id)) return part.getName();
        return id;
    }

    private void renderTotals() {
        Snapshot snapshot = session.getSnapshot(components.get(selectedIndex).getId());
        if (snapshot == null || snapshot.getAnalyzedFrames() == 0) {
            totals.setText(R.string.part_test_no_samples);
            return;
        }
        String summary = String.format(Locale.US,
                "Frames: %d · Expected: %d (%.0f%%)\nWrong identity: %d · No detection: %d\nStable locks: %d · First lock: %s\nAverage analysis: %.0f ms · ORB / fallback: %d / %d",
                snapshot.getAnalyzedFrames(), snapshot.getExpectedFrames(), snapshot.getExpectedMatchRate() * 100,
                snapshot.getWrongComponentFrames(), snapshot.getNoDetectionFrames(), snapshot.getStableLockCount(),
                Double.isNaN(snapshot.getFirstLockMillis()) ? "not reached" : String.format(Locale.US, "%.2f s", snapshot.getFirstLockMillis() / 1000),
                snapshot.getAverageLatencyMs(), snapshot.getOrbFrames(), snapshot.getFallbackFrames());
        if (!snapshot.getRejectionReasons().isEmpty()) {
            summary += "\nRejections: " + snapshot.getRejectionReasons();
        }
        totals.setText(summary);
    }

    private void showResults() {
        pauseTest();
        List<Snapshot> snapshots = new ArrayList<>(session.getSnapshots());
        snapshots.removeIf(snapshot -> snapshot.getAnalyzedFrames() == 0);
        snapshots.sort((a, b) -> Double.compare(a.getExpectedMatchRate(), b.getExpectedMatchRate()));
        StringBuilder text = new StringBuilder(getString(R.string.part_test_results_note));
        if (snapshots.isEmpty()) text.append("\n\n").append(getString(R.string.part_test_no_results));
        for (Snapshot snapshot : snapshots) {
            text.append(String.format(Locale.US, "\n\n%s\n%.0f%% expected · %d frames · %d stable locks\nWrong: %d · None: %d",
                    snapshot.getDisplayName(), snapshot.getExpectedMatchRate() * 100, snapshot.getAnalyzedFrames(),
                    snapshot.getStableLockCount(), snapshot.getWrongComponentFrames(), snapshot.getNoDetectionFrames()));
            text.append("\nReference: ").append(referenceBasis(snapshot.getComponentId()));
            if (!snapshot.getWrongComponents().isEmpty()) text.append("\nConfused with: ").append(snapshot.getWrongComponents());
            if (!snapshot.getRejectionReasons().isEmpty()) text.append("\nRejected by: ").append(snapshot.getRejectionReasons());
        }
        if (catalogReady) {
            List<String> missing = new ArrayList<>();
            for (Component component : components) if (!readyPartIds.contains(component.getId())) missing.add(component.getName());
            if (!missing.isEmpty()) text.append("\n\nNeeds individual reference (not scored):\n").append(android.text.TextUtils.join(", ", missing));
        }
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextIsSelectable(true);
        body.setPadding(UiUtils.dp(this, 20), UiUtils.dp(this, 12), UiUtils.dp(this, 20), UiUtils.dp(this, 12));
        body.setTextColor(getColor(R.color.ink));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this).setTitle(R.string.part_test_results).setView(scroll)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private boolean focusCamera(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        preview.performClick();
        Camera camera = boundCamera;
        if (camera == null) return true;
        MeteringPoint point = preview.getMeteringPointFactory().createPoint(
                event.getX(), event.getY());
        FocusMeteringAction action = new FocusMeteringAction.Builder(
                point, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(4, TimeUnit.SECONDS).build();
        camera.getCameraControl().startFocusAndMetering(action);
        return true;
    }

    private void captureSample() {
        ActiveTest requested = activeTest;
        AssemblyVisionAnalyzer activeAnalyzer = analyzer;
        if (!testing || requested == null || activeAnalyzer == null) {
            status.setText(R.string.part_test_capture_first);
            return;
        }
        boolean queued = activeAnalyzer.captureNextAnalyzedFrame(
                new AssemblyVisionAnalyzer.FrameSnapshotCallback() {
                    @Override public void onCaptured(
                            AssemblyVisionAnalyzer.AnalyzedFrameSnapshot snapshot) {
                        ActiveTest current = activeTest;
                        if (destroyed || current == null
                                || current.generation != requested.generation) return;
                        try {
                            AssemblyVisionAnalyzer.FrameMetadata metadata = snapshot.getMetadata();
                            PartTestSampleArchive archive = new PartTestSampleArchive(
                                    requested.componentId, metadata.getTimestampNanos(),
                                    metadata.getAnalyzedWidth(), metadata.getAnalyzedHeight(),
                                    metadata.getRotationDegrees(),
                                    diagnosticSummary(snapshot.getRecognitionResult()),
                                    snapshot.getPngBytes(), snapshot.getRgbPngBytes(),
                                    snapshot.getCameraCalibration());
                            ByteArrayOutputStream output = new ByteArrayOutputStream();
                            archive.writeTo(output);
                            byte[] bytes = output.toByteArray();
                            String stagedSampleFile = stageSampleExport(bytes);
                            runOnUiThread(() -> {
                                if (destroyed) {
                                    deletePendingSampleFile(stagedSampleFile);
                                    return;
                                }
                                deletePendingSampleFile(pendingSampleExportFile);
                                pendingSampleExportFile = stagedSampleFile;
                                dev.chyken.assembl_ar.diagnostics.DiagnosticLogger.get(PartRecognitionTestActivity.this).recordFrame(archive);
                                sampleExport.launch("assembl-ar-" + requested.componentId + "-"
                                        + System.currentTimeMillis() + ".arpart.zip");
                            });
                        } catch (Exception failure) {
                            onError(failure);
                        }
                    }

                    @Override public void onError(Throwable failure) {
                        android.util.Log.e("PartRecognitionTest", "Sample capture failed", failure);
                        runOnUiThread(() -> {
                            if (!destroyed) Toast.makeText(PartRecognitionTestActivity.this,
                                    R.string.part_test_sample_save_failed,
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
        status.setText(queued ? R.string.part_test_capture_wait : R.string.part_test_capture_first);
    }

    private void readAndReplaySample(Uri uri) {
        if (!catalogReady || recognizer == null) {
            Toast.makeText(this, R.string.part_test_sample_preparing, Toast.LENGTH_LONG).show();
            return;
        }
        exportExecutor.execute(() -> {
            PartTestSampleArchive sample = null;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException("Sample unavailable");
                sample = PartTestSampleArchive.readFrom(input);
            } catch (Exception failure) {
                android.util.Log.e("PartRecognitionTest", "Sample import failed", failure);
            }
            PartTestSampleArchive decoded = sample;
            runOnUiThread(() -> {
                if (destroyed) return;
                if (decoded == null) {
                    Toast.makeText(this, R.string.part_test_sample_invalid,
                            Toast.LENGTH_LONG).show();
                } else {
                    replaySample(decoded);
                }
            });
        });
    }

    private void replaySample(PartTestSampleArchive sample) {
        int partIndex = -1;
        for (int index = 0; index < components.size(); index++) {
            if (components.get(index).getId().equals(sample.getComponentId())) {
                partIndex = index;
                break;
            }
        }
        if (partIndex < 0) {
            Toast.makeText(this, R.string.part_test_sample_unknown, Toast.LENGTH_LONG).show();
            return;
        }
        pauseTest();
        selectedIndex = partIndex;
        renderSelection();
        Component part = components.get(selectedIndex);
        if (!readyPartIds.contains(part.getId()) || recognizer == null) {
            status.setText(R.string.part_test_missing);
            return;
        }
        status.setText((sample.hasFullColorReplay()
                ? "Replaying exact analyzed frame for "
                : "Replaying legacy grayscale sample for ") + part.getName() + "…");
        ComponentRecognizer activeRecognizer = recognizer;
        double minimumArea = minimumAreaRatio(part);
        cameraExecutor.execute(() -> {
            MatOfByte encoded = null;
            MatOfByte rgbEncoded = null;
            Mat frame = null;
            Mat bgr = null;
            Mat rgb = null;
            RecognitionResult result = null;
            try {
                encoded = new MatOfByte(sample.getFramePng());
                if (sample.hasFullColorReplay()) {
                    rgbEncoded = new MatOfByte(sample.getRgbFramePng());
                }
                frame = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE);
                if (frame.empty() || frame.cols() != sample.getFrameWidth()
                        || frame.rows() != sample.getFrameHeight()) {
                    throw new IllegalArgumentException("Sample frame dimensions do not match metadata");
                }
                if (rgbEncoded != null) {
                    bgr = Imgcodecs.imdecode(rgbEncoded, Imgcodecs.IMREAD_COLOR);
                    if (bgr.empty() || bgr.cols() != sample.getFrameWidth()
                            || bgr.rows() != sample.getFrameHeight()) {
                        throw new IllegalArgumentException(
                                "Sample RGB frame dimensions do not match metadata");
                    }
                    rgb = new Mat();
                    Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB);
                }
                // Validate every archived pixel plane before producing any replay result. A v2
                // sample with a corrupt RGB body must not be misreported as a successful ORB-only
                // replay just because its grayscale plane happened to decode first.
                RecognitionRequest request = IndividualPartTestCatalog.requestFor(
                        part.getId(), sample.getCameraCalibration(), minimumArea);
                RecognitionResult primary = activeRecognizer.recognize(
                        frame, sample.getFrameTimestampNanos(), request);
                result = rgb == null ? primary : new LoosePartShapeRecognizer().recognize(
                        frame, rgb, sample.getFrameTimestampNanos(), request, primary);
            } catch (Exception failure) {
                android.util.Log.e("PartRecognitionTest", "Sample replay failed", failure);
            } finally {
                if (rgb != null) rgb.release();
                if (bgr != null) bgr.release();
                if (frame != null) frame.release();
                if (rgbEncoded != null) rgbEncoded.release();
                if (encoded != null) encoded.release();
            }
            RecognitionResult replayed = result;
            runOnUiThread(() -> {
                if (destroyed) return;
                if (replayed == null) {
                    Toast.makeText(this, R.string.part_test_sample_invalid,
                            Toast.LENGTH_LONG).show();
                    status.setText(R.string.part_test_sample_invalid);
                    return;
                }
                renderReplayResult(part, sample, replayed);
            });
        });
    }

    private void renderReplayResult(Component expectedPart, PartTestSampleArchive sample,
                                    RecognitionResult result) {
        boolean detected = result.getDetectionMethod() != RecognitionResult.DetectionMethod.NONE
                && result.getComponentId() != null && !result.getComponentId().isEmpty();
        boolean expectedIdentity = expectedPart.getId().equals(result.getComponentId());
        boolean localizedWithPose = expectedIdentity
                && result.getValidationState()
                == RecognitionResult.ValidationState.EXPECTED_COMPONENT
                && result.getProjectedCorners().size() == 4
                && result.getPose() != null
                && result.getDetectionMethod()
                != RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY;
        String detectedName = detected ? componentName(result.getComponentId()) : "None";
        float[] corners = normalizeCorners(result.getProjectedCorners(),
                sample.getFrameWidth(), sample.getFrameHeight());
        hud.update(!detected ? ArHudView.HudState.NOT_RECOGNIZED
                : !expectedIdentity ? ArHudView.HudState.MISMATCH
                : ArHudView.HudState.TRACKING,
                detectedName, corners);
        liveMetrics.setText("Replayed: " + sample.getFrameWidth() + "×"
                + sample.getFrameHeight() + "\n" + diagnosticSummary(result)
                + (sample.hasFullColorReplay() ? "" : "\nLegacy sample: ORB-only replay")
                + "\nOriginally recorded: " + sample.getRecordedOutcome());
        status.setText(localizedWithPose
                ? "Replay localized the expected part with pose · stability is not evaluated from one frame"
                : expectedIdentity
                ? "Replay identified the part but did not establish corners and pose"
                : detected ? "Replay detected the wrong identity: " + detectedName
                : "Replay reproduced the recognition failure");
    }

    private static String diagnosticSummary(RecognitionResult result) {
        String component = result.getComponentId() == null ? "none" : result.getComponentId();
        String summary = String.format(Locale.US,
                "method=%s component=%s validation=%s corners=%d pose=%s frame_keypoints=%d good_matches=%d inliers=%d confidence=%.3f",
                result.getDetectionMethod(), component, result.getValidationState(),
                result.getProjectedCorners().size(), result.getPose() == null ? "missing" : "available",
                result.getFrameKeypointCount(),
                result.getGoodMatchCount(), result.getInlierCount(), result.getConfidence());
        RecognitionResult.RejectionEvidence rejection = result.getRejectionEvidence();
        if (rejection != null) {
            summary += String.format(Locale.US,
                    " rejection=%s reference=%s reference_keypoints=%d rejected_good=%d required_good=%d rejected_valid=%d rejected_inliers=%d required_inliers=%d rejected_ratio=%.3f required_ratio=%.3f",
                    rejection.getReason(), rejection.getReferenceId(),
                    rejection.getReferenceKeypointCount(), rejection.getGoodMatchCount(),
                    rejection.getRequiredGoodMatchCount(), rejection.getValidMatchCount(),
                    rejection.getInlierCount(), rejection.getRequiredInlierCount(),
                    rejection.getInlierRatio(), rejection.getRequiredInlierRatio());
        }
        return summary;
    }

    private static String rejectionLabel(RecognitionResult.RejectionReason reason) {
        return reason.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private void exportResults() {
        pauseTest();
        Map<String, String> referenceBasis = new LinkedHashMap<>();
        for (Component component : components) referenceBasis.put(component.getId(), referenceBasis(component.getId()));
        pendingExport = session.toCsv(referenceBasis);
        export.launch("assembl-ar-individual-parts-" + System.currentTimeMillis() + ".csv");
    }

    private String referenceBasis(String componentId) {
        if (testMode) return "automated_fixture";
        return IndividualPartTestCatalog.getStandaloneComponentIds().contains(componentId)
                ? "reviewed_standalone" : "legacy_contextual_baseline";
    }

    private void saveCsv(Uri uri, String csv) {
        exportExecutor.execute(() -> {
            boolean saved = false;
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new java.io.IOException("Destination unavailable");
                output.write(csv.getBytes(StandardCharsets.UTF_8));
                saved = true;
            } catch (Exception failure) {
                android.util.Log.e("PartRecognitionTest", "CSV export failed", failure);
            }
            boolean success = saved;
            runOnUiThread(() -> { if (!destroyed) Toast.makeText(this,
                    success ? R.string.part_test_saved : R.string.part_test_save_failed, Toast.LENGTH_LONG).show(); });
        });
    }

    private void saveSample(Uri uri, String sampleFileName) {
        exportExecutor.execute(() -> {
            boolean saved = false;
            File sampleFile = null;
            try {
                sampleFile = pendingSampleFile(sampleFileName);
                if (!sampleFile.isFile()) throw new java.io.IOException("Pending sample is unavailable");
                try (InputStream input = new FileInputStream(sampleFile);
                     OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new java.io.IOException("Destination unavailable");
                    byte[] buffer = new byte[16 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                saved = true;
            } catch (Exception failure) {
                android.util.Log.e("PartRecognitionTest", "Sample export failed", failure);
            } finally {
                if (sampleFile != null && !sampleFile.delete() && sampleFile.exists()) {
                    android.util.Log.w("PartRecognitionTest", "Could not delete staged sample");
                }
            }
            boolean success = saved;
            runOnUiThread(() -> {
                if (!destroyed) Toast.makeText(this,
                        success ? R.string.part_test_sample_saved
                                : R.string.part_test_sample_save_failed,
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private String stageSampleExport(byte[] archiveBytes) throws java.io.IOException {
        File staged = File.createTempFile(PENDING_SAMPLE_PREFIX, PENDING_SAMPLE_SUFFIX,
                pendingSampleDirectory());
        boolean complete = false;
        try {
            try (OutputStream output = new FileOutputStream(staged)) {
                output.write(archiveBytes);
            }
            complete = true;
            return staged.getName();
        } finally {
            if (!complete && !staged.delete() && staged.exists()) {
                android.util.Log.w("PartRecognitionTest", "Could not delete incomplete sample");
            }
        }
    }

    private File pendingSampleDirectory() throws java.io.IOException {
        File directory = new File(getNoBackupFilesDir(), PENDING_SAMPLE_DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new java.io.IOException("Could not create pending-sample directory");
        }
        return directory;
    }

    private File pendingSampleFile(String fileName) throws java.io.IOException {
        if (fileName == null || !fileName.equals(new File(fileName).getName())
                || !fileName.startsWith(PENDING_SAMPLE_PREFIX)
                || !fileName.endsWith(PENDING_SAMPLE_SUFFIX)) {
            throw new java.io.IOException("Pending-sample file name is invalid");
        }
        File directory = pendingSampleDirectory();
        File file = new File(directory, fileName);
        if (!directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile())) {
            throw new java.io.IOException("Pending-sample path escaped its directory");
        }
        return file;
    }

    private void deletePendingSampleFile(@Nullable String fileName) {
        if (fileName == null) return;
        try {
            File file = pendingSampleFile(fileName);
            if (!file.delete() && file.exists()) {
                android.util.Log.w("PartRecognitionTest", "Could not delete pending sample");
            }
        } catch (java.io.IOException invalidPath) {
            android.util.Log.w("PartRecognitionTest", "Ignored invalid pending-sample path");
        }
    }

    private void cleanupStalePendingSamples(@Nullable String protectedFileName) {
        try {
            File[] files = pendingSampleDirectory().listFiles((directory, name) ->
                    name.startsWith(PENDING_SAMPLE_PREFIX)
                            && name.endsWith(PENDING_SAMPLE_SUFFIX));
            if (files == null) return;
            long cutoff = System.currentTimeMillis() - STALE_PENDING_SAMPLE_AGE_MS;
            for (File file : files) {
                if (!file.getName().equals(protectedFileName)
                        && file.lastModified() < cutoff
                        && !file.delete() && file.exists()) {
                    android.util.Log.w("PartRecognitionTest", "Could not delete stale sample");
                }
            }
        } catch (java.io.IOException failure) {
            android.util.Log.w("PartRecognitionTest", "Could not clean staged samples", failure);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if(analyzer!=null)analyzer.resume();
        if(cameraPreviewUseCase!=null)cameraPreviewUseCase.setSurfaceProvider(preview.getSurfaceProvider());
        arView.onResume();
        if (!testMode) {
            if (!catalogReady) initializeVision();
            else if (hasCameraPermission()) bindCamera();
        }
        resumeSavedTest();
    }

    private void resumeSavedTest(){
        if(resumeRequested&&resumed&&catalogReady&&(testMode||cameraBound))startTest();
    }

    private dev.chyken.assembl_ar.ui.AssemblyVoiceGuide outlineVoice;
    @Override protected void onPause() {
        boolean wasRunning=testing||resumeRequested;
        pauseTest();
        resumeRequested=wasRunning;
        resumed = false;
        if(analyzer!=null)analyzer.pause();
        arView.onPause();
        super.onPause();
    }

    @Override protected void onStop(){if(analyzer!=null)analyzer.pause();super.onStop();}

    @Override public Object onRetainCustomNonConfigurationInstance() {
        return new RetainedState(session, selectedIndex, pendingSampleExportFile,new LinkedHashMap<>(targetSessions),activeTarget==null?pendingTargetKey:activeTarget.key);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("test_running",testing||resumeRequested);
        state.putString("target_key",activeTarget==null?pendingTargetKey:activeTarget.key);
        state.putInt("part_index", selectedIndex);
        state.putBoolean("permission_requested", permissionRequested);
        state.putString("pending_export", pendingExport);
        state.putString(STATE_PENDING_SAMPLE_FILE, pendingSampleExportFile);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if(outlineVoice!=null)outlineVoice.close();
        destroyed = true;
        testing = false;
        if (layoutListener != null) preview.removeOnLayoutChangeListener(layoutListener);
        if (analysis != null) analysis.clearAnalyzer();
        if (analyzer != null) analyzer.close();
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (isFinishing() && !isChangingConfigurations()) {
            deletePendingSampleFile(pendingSampleExportFile);
            pendingSampleExportFile = null;
        }
        boundCamera = null;
        cameraPreviewUseCase=null;
        ComponentRecognizer closingRecognizer = recognizer;
        ReferenceDatabase closingDatabase = referenceDatabase;
        cameraExecutor.execute(() -> {
            if (closingRecognizer != null) closingRecognizer.close();
            if (closingDatabase != null) closingDatabase.close();
        });
        cameraExecutor.shutdown();
        exportExecutor.shutdown();
        super.onDestroy();
    }

    private static final class ActiveTest {
        final long generation;
        final String componentId;
        final float minimumAreaRatio;
        ActiveTest(long generation, String componentId, float minimumAreaRatio) {
            this.generation = generation;
            this.componentId = componentId;
            this.minimumAreaRatio = minimumAreaRatio;
        }
    }

    private static float minimumAreaRatio(Component part) {
        return part.getCategory() == ComponentCategory.FASTENER ? 0.00003f : 0.001f;
    }

    private static final class FrameContext {
        final long generation;
        final long timestamp;
        final int width;
        final int height;
        final CameraCalibration calibration;
        FrameContext(long generation, long timestamp, int width, int height, CameraCalibration calibration) {
            this.generation = generation; this.timestamp = timestamp;
            this.width = width; this.height = height; this.calibration = calibration;
        }
    }

    private static final class RetainedState {
        final PartRecognitionTestSession session;
        final int selectedIndex;
        final String pendingSampleExportFile;
        final Map<String,PartRecognitionTestSession> sessions;
        final String targetKey;
        RetainedState(PartRecognitionTestSession session, int selectedIndex,
                      String pendingSampleExportFile,Map<String,PartRecognitionTestSession> sessions,String targetKey) {
            this.sessions=sessions;this.targetKey=targetKey;
            this.session = session;
            this.selectedIndex = selectedIndex;
            this.pendingSampleExportFile = pendingSampleExportFile;
        }
    }
}
