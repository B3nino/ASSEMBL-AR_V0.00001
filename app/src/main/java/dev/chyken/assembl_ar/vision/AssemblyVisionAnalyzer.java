package dev.chyken.assembl_ar.vision;

import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CameraX analyzer that converts the Y plane of YUV_420_888 safely to CV_8UC1, throttles work,
 * invokes the synchronous recognizer, and closes every ImageProxy on every path.
 */
public final class AssemblyVisionAnalyzer implements ImageAnalysis.Analyzer, AutoCloseable {
    private static final String TAG = "AssemblArAnalyzer";

    private final OpticalFlowTrackerBridge flowBridge=new OpticalFlowTrackerBridge();
    private final TargetBoundingTracker targetBounds=new TargetBoundingTracker();
    private GlobalComponentRegistry.Match frameGlobalCandidate;
    private boolean visualOnlyFrame;
    private long lastOutlineFrame=Long.MIN_VALUE;
    private long lastGlobalQuery=Long.MIN_VALUE;
    private long lastDnnQuery=Long.MIN_VALUE;
    private boolean lastPhotoResolved;
    private final AdaptiveIlluminationPolicy illuminationPolicy=new AdaptiveIlluminationPolicy();
    private final EdgeGradientDescriptorExtractor.Workspace liveWorkspace=new EdgeGradientDescriptorExtractor.Workspace();
    private org.opencv.features2d.ORB globalOrb;
    private org.opencv.imgproc.CLAHE globalClahe;
    private Mat diagnosticGray;
    private FrameMetadata diagnosticFrame;
    private volatile RecognitionRequest diagnosticRequest;
    private volatile Object trackingTag;
    private volatile String trackingScope;
    private android.content.Context motionContext;
    private final AtomicBoolean lifecyclePaused=new AtomicBoolean();
    private final AtomicLong lifecycleEpoch=new AtomicLong();
    private long detectionEpoch;
    public void attachMotion(android.content.Context c){motionContext=c.getApplicationContext();flowBridge.attach(motionContext);dev.chyken.assembl_ar.diagnostics.DiagnosticSession.INSTANCE.attach(c);}
    private final ComponentRecognizer recognizer;
    private final RequestProvider requestProvider;
    private final Callback callback;
    private final long minimumFrameIntervalNanos;
    private final boolean rotatePixelsToDisplay;
    private final AtomicLong lastAcceptedFrameNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AssemblyAnchorTracker boardTracker = new AssemblyAnchorTracker();
    private final MotorAssemblyInspector motorInspector = new MotorAssemblyInspector();
    private final InstalledModuleInspector moduleInspector = new InstalledModuleInspector();
    private final MeasurementRecorder measurements = new MeasurementRecorder();
    private final LoosePartShapeRecognizer loosePartRecognizer =
            new LoosePartShapeRecognizer();
    private final AtomicBoolean resetAssemblyTracking = new AtomicBoolean(false);
    private final AtomicReference<FrameSnapshotCallback> pendingFrameSnapshot =
            new AtomicReference<>();
    private FrameMetadata previousBoardFrame;
    private RecognitionResult.SpatialAnchor previousBoardAnchor;
    private int previousGuidedBracketSlot;
    private final ModelBasedPartRecognizer modelRecognizer;

    public AssemblyVisionAnalyzer(ComponentRecognizer recognizer,
                                  RequestProvider requestProvider,
                                  Callback callback,
                                  VisionConfig config) {
        this(recognizer, requestProvider, callback, config, false);
    }

    /**
     * @param rotatePixelsToDisplay when true, rotates the Mat by ImageInfo.rotationDegrees before
     *                              recognition. The request provider must then supply intrinsics in
     *                              those rotated pixel coordinates. False preserves sensor/crop
     *                              coordinates and is the calibration-safe default.
     */
    public AssemblyVisionAnalyzer(ComponentRecognizer recognizer,
                                  RequestProvider requestProvider,
                                  Callback callback,
                                  VisionConfig config,
                                  boolean rotatePixelsToDisplay) {
        this(recognizer, requestProvider, callback, config, rotatePixelsToDisplay, null);
    }

    public AssemblyVisionAnalyzer(ComponentRecognizer recognizer, RequestProvider requestProvider,
                                  Callback callback, VisionConfig config, boolean rotatePixelsToDisplay,
                                  ModelBasedPartRecognizer modelRecognizer) {
        this.recognizer = Objects.requireNonNull(recognizer, "recognizer");
        this.requestProvider = Objects.requireNonNull(requestProvider, "requestProvider");
        this.callback = Objects.requireNonNull(callback, "callback");
        VisionConfig safeConfig = Objects.requireNonNull(config, "config");
        minimumFrameIntervalNanos = safeConfig.getMinimumFrameIntervalMs() * 1_000_000L;
        this.rotatePixelsToDisplay = rotatePixelsToDisplay;
        this.modelRecognizer = modelRecognizer;
    }

    private final java.util.concurrent.ExecutorService detectorExecutor=java.util.concurrent.Executors.newSingleThreadExecutor(r->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"orb-detector"));
    private final AtomicBoolean detectorBusy=new AtomicBoolean();
    // CameraX uses the executor; deterministic replay may call directly. Both must
    // finish reading native resources before queued lifecycle cleanup can release them.
    private final java.util.concurrent.locks.ReentrantLock detectorOwnership=new java.util.concurrent.locks.ReentrantLock();
    private static final long DETECTOR_INTERVAL_NANOS=LiveVisionPolicy.DETECTOR_INTERVAL_NS;
    private volatile long lastQueuedDetection=Long.MIN_VALUE;
    @Override public void analyze(@NonNull ImageProxy image){
        Mat gray=null,rotated=null;
        long frameEpoch=lifecycleEpoch.get();
        try{
            boolean suspended=requestProvider.isAnalysisSuspended();
            if(!currentFrame(frameEpoch)||suspended&&requestProvider.isTrackingSuspended())return;
            long capturedFrameNanos=CaptureTimebase.toElapsedRealtimeNanos(image.getImageInfo().getTimestamp());
            dev.chyken.assembl_ar.diagnostics.DiagnosticSession.INSTANCE.runner.frame(capturedFrameNanos);
            boolean due=(lastQueuedDetection==Long.MIN_VALUE||capturedFrameNanos-lastQueuedDetection>=(suspended?500_000_000L:DETECTOR_INTERVAL_NANOS));
            if(due&&detectorBusy.compareAndSet(false,true)){
                lastQueuedDetection=capturedFrameNanos;
                DetachedCameraFrame owned=null;boolean submitted=false;
                try{
                    owned=new DetachedCameraFrame(image,capturedFrameNanos);
                    final DetachedCameraFrame frame=owned;
                    detectorExecutor.execute(()->{
                        try{processDetection(frame,frameEpoch);}finally{
                            detectorBusy.set(false);
                        }
                    });
                    submitted=true;
                }catch(java.util.concurrent.RejectedExecutionException stopped){
                    // close() owns final cleanup; this detached frame was never queued.
                }finally{
                    if(!submitted){detectorBusy.set(false);if(owned!=null)owned.close();}
                }
                return;
            }
            if(lastOutlineFrame!=Long.MIN_VALUE&&capturedFrameNanos-lastOutlineFrame<50_000_000L)return;
            lastOutlineFrame=capturedFrameNanos;
            if(image.getFormat()!=ImageFormat.YUV_420_888)return;
            long time=capturedFrameNanos;Rect crop=image.getCropRect();gray=yPlaneToGray(image,crop);Mat analyzed=gray;
            int rotation=normalizeRotation(image.getImageInfo().getRotationDegrees());if(rotatePixelsToDisplay&&rotation!=0){rotated=new Mat();rotate(gray,rotated,rotation);analyzed=rotated;}
            FrameMetadata frame=new FrameMetadata(time,image.getWidth(),image.getHeight(),crop.left,crop.top,crop.width(),crop.height(),analyzed.cols(),analyzed.rows(),rotation,rotatePixelsToDisplay&&rotation!=0,matrixValues(image.getImageInfo().getSensorToBufferTransformMatrix()));
            RecognitionRequest request=requestProvider.requestForTracking(frame);
            if(request==null)request=suspended?diagnosticRequest:requestProvider.requestFor(frame);if(request==null||!currentFrame(frameEpoch))return;
            if(!Objects.equals(trackingScope,dev.chyken.assembl_ar.camera.ActiveReferenceScope.key(request)))return;
            TargetBoundingTracker.Target outline=targetBounds.frame(analyzed,time,flowBridge.imageMotion(request.getCameraCalibration(),rotatePixelsToDisplay?rotation:0));
            if(!currentFrame(frameEpoch))return;
            callback.onTargetTracking(outline,frame,request.getRequestTag());
            if(!currentFrame(frameEpoch))return;
            RecognitionResult.SpatialAnchor visual=requestProvider.requiresMetricTracking()?flowBridge.trackLatest(analyzed,time,request.getCameraCalibration()):null;
            if(!currentFrame(frameEpoch))return;
            if(visual!=null&&!flowBridge.predicted())dev.chyken.assembl_ar.diagnostics.DiagnosticSession.INSTANCE.runner.computedFrame(time);
            dev.chyken.assembl_ar.diagnostics.DiagnosticSession.INSTANCE.tracking(visual,analyzed,request.getCameraCalibration(),flowBridge.predicted(),time);
            if(currentFrame(frameEpoch))callback.onTracking(visual,frame,request.getCameraCalibration(),flowBridge.predicted(),request.getRequestTag());
        }catch(RuntimeException failure){if(currentFrame(frameEpoch))deliverError(failure);}
        finally{if(rotated!=null)rotated.release();if(gray!=null)gray.release();image.close();}
    }
    private boolean currentFrame(long epoch){return !closed.get()&&!lifecyclePaused.get()&&epoch==lifecycleEpoch.get();}
    /** Deterministic offline replay entry point; realtime CameraX callers use analyze(). */
    public void analyzeForReplay(@NonNull ImageProxy image){processDetection(image,lifecycleEpoch.get());}
    private void processDetection(@NonNull ImageProxy image,long queuedEpoch) {
        if(!detectorOwnership.tryLock()){image.close();return;}
        try{processOwnedDetection(image,queuedEpoch);}finally{detectorOwnership.unlock();}
    }
    private void processOwnedDetection(@NonNull ImageProxy image,long queuedEpoch) {
        Objects.requireNonNull(image, "image");
        Mat grayscale = null;
        Mat rotated = null;
        Mat rgb = null;
        Mat componentGray = null;
        boolean acquired = false;
        try {
            if (closed.get()||lifecyclePaused.get()||queuedEpoch!=lifecycleEpoch.get()) {
                return;
            }
            // A demonstration or assembly screen needs no camera evidence; skip the frame entirely.
            long now = SystemClock.elapsedRealtimeNanos();
            long captureTimestampNanos = image instanceof DetachedCameraFrame?((DetachedCameraFrame)image).elapsedTimestamp:CaptureTimebase.toElapsedRealtimeNanos(image.getImageInfo().getTimestamp());
            long last = lastAcceptedFrameNanos.get();
            if (last != Long.MIN_VALUE && captureTimestampNanos <= last) {
                return;
            }
            if (last != Long.MIN_VALUE
                    && captureTimestampNanos - last < minimumFrameIntervalNanos) {
                return;
            }
            if (!processing.compareAndSet(false, true)) {
                return;
            }
            acquired = true;
            if (!currentFrame(queuedEpoch)) return;
            detectionEpoch=queuedEpoch;
            visualOnlyFrame=requestProvider.isAnalysisSuspended();
            if(visualOnlyFrame&&requestProvider.isTrackingSuspended())return;
            lastAcceptedFrameNanos.set(captureTimestampNanos);

            if (image.getFormat() != ImageFormat.YUV_420_888) {
                throw new IllegalArgumentException(
                        "ImageAnalysis must output YUV_420_888; received format "
                                + image.getFormat());
            }
            long preprocessingStarted=SystemClock.elapsedRealtimeNanos();
            Rect crop = image.getCropRect();
            grayscale = yPlaneToGray(image, crop);

            int rotationDegrees = normalizeRotation(image.getImageInfo().getRotationDegrees());
            Mat analyzed = grayscale;
            if (rotatePixelsToDisplay && rotationDegrees != 0) {
                rotated = new Mat();
                rotate(grayscale, rotated, rotationDegrees);
                analyzed = rotated;
            }

            long framePreprocessingNs=SystemClock.elapsedRealtimeNanos()-preprocessingStarted;
            FrameMetadata metadata = new FrameMetadata(
                    captureTimestampNanos,
                    image.getWidth(),
                    image.getHeight(),
                    crop.left,
                    crop.top,
                    crop.width(),
                    crop.height(),
                    analyzed.cols(),
                    analyzed.rows(),
                    rotationDegrees,
                    rotatePixelsToDisplay && rotationDegrees != 0,
                    matrixValues(image.getImageInfo().getSensorToBufferTransformMatrix()));
            RecognitionRequest request = visualOnlyFrame?requestProvider.requestForTracking(metadata):requestProvider.requestFor(metadata);
            if(request==null&&visualOnlyFrame)request=diagnosticRequest;
            if(request==null||!currentDetection())return;
            Object requestTag = request == null ? null : request.getRequestTag();
            diagnosticGray=analyzed;diagnosticFrame=metadata;diagnosticRequest=request;
            boolean resetRequested=resetAssemblyTracking.getAndSet(false);
            if(!Objects.equals(trackingScope,dev.chyken.assembl_ar.camera.ActiveReferenceScope.key(request))||resetRequested){flowBridge.reset();targetBounds.reset();boardTracker.reset();loosePartRecognizer.reset();illuminationPolicy.reset();previousBoardAnchor=null;lastDnnQuery=Long.MIN_VALUE;lastPhotoResolved=false;}
            trackingTag=requestTag;trackingScope=dev.chyken.assembl_ar.camera.ActiveReferenceScope.key(request);
            frameGlobalCandidate=null;
            DiagnosticHammingMatcher.begin();
            liveWorkspace.beginFrame();
            DiagnosticHammingMatcher.snapshot().fastLive=true;DiagnosticHammingMatcher.snapshot().workspace=liveWorkspace;
            DiagnosticHammingMatcher.snapshot().scope=trackingScope;
            DiagnosticHammingMatcher.snapshot().sceneFeatures=request.getPhase3Stage()>1||request.getPhase5Stage()>1||request.getPhase7Stage()>=3||request.getPhase8Stage()==2||request.getPhase9Stage()>=3||request.getPhase10Stage()>=4||request.getPhase11Stage()==3;
            DiagnosticHammingMatcher.snapshot().adaptiveDetail=request.getPhase2Stage()>0||request.getPhase3Stage()>0||request.getPhase5Stage()>0||request.getPhase7Stage()>0||request.getPhase8Stage()>0||request.getPhase9Stage()>0||request.getPhase10Stage()>0||request.getPhase11Stage()>0;
            DiagnosticHammingMatcher.snapshot().featureNs=framePreprocessingNs;
            dev.chyken.assembl_ar.diagnostics.DiagnosticSession.INSTANCE.analyzedFrame();
            DiagnosticHammingMatcher.recovery(IlluminationRecovery.choose(illuminationPolicy,analyzed));
            DiagnosticHammingMatcher.snapshot().edgeRecovery=illuminationPolicy.edgeRecovery();
            if(request.getExpectation()!=null&&OpenCvDnnRoiLocator.INSTANCE.supports(request.getExpectation().getComponentId())
                    &&(motionContext==null||dev.chyken.assembl_ar.diagnostics.PreferencesManager.nanoDetector(motionContext))
                    &&(!lastPhotoResolved||!targetBounds.hasTarget())
                    &&(lastDnnQuery==Long.MIN_VALUE||now-lastDnnQuery>=1_000_000_000L)){
                long locatorStarted=SystemClock.elapsedRealtimeNanos();
                if(rgb==null)rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);
                DiagnosticHammingMatcher.snapshot().proposedRoi=OpenCvDnnRoiLocator.INSTANCE.locate(rgb,request.getExpectation().getComponentId());
                DiagnosticHammingMatcher.snapshot().geometryNs+=SystemClock.elapsedRealtimeNanos()-locatorStarted;
                lastDnnQuery=SystemClock.elapsedRealtimeNanos();
            }
            // Native inference cannot be interrupted safely. If its owner paused
            // while it ran, drop this frame before starting any photo/detail work.
            if(!currentDetection())return;
            // Local recognition extracts the live descriptors once. On a miss,
            // globalValidation reuses those same rows for bounded visual discovery.
            // Pre-extracting a second generic ORB bank here both delayed the first
            // outline and hid the phase-specific features from diagnostic capture.
            DiagnosticHammingMatcher.snapshot().structuralFirst=illuminationPolicy.structuralFirst();
            DiagnosticHammingMatcher.snapshot().structuralInput=false;
            int assemblyStep = request == null ? 0 : request.getAssemblyStepNumber();
            if(request!=null&&request.getPhase7Stage()>0){
                // Color proposes separate module search windows only in the dual-tracker check.
                // Each window still needs geometric ORB identity and board mounting evidence.
                if(request.getPhase7Stage()==4)rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);
                RecognitionResult inspected=recognizer.inspectPhase7(analyzed,rgb,metadata.getTimestampNanos(),request.getPhase7Stage()).withAssemblyInspection(7,null,false,null).withRequestTag(requestTag);
                FrameSnapshotCallback snapshot=pendingFrameSnapshot.getAndSet(null);if(snapshot!=null){if(rgb==null)rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);captureRequestedFrame(snapshot,analyzed,rgb,metadata,inspected,request.getCameraCalibration());}
                // The isolated Phase 7 path returns before the shared recorder below. Keep its
                // existing opt-in ADB diagnostics available for real camera regression captures.
                String capture=null;
                if(measurements.captureDue(metadata.getTimestampNanos())){
                    if(rgb==null)rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);
                    capture=measurements.capture(rgb,metadata.getTimestampNanos());
                }
                if(MeasurementRecorder.enabled())measurements.log(metadata,request,inspected,
                        (SystemClock.elapsedRealtimeNanos()-now)/1e6,capture);
                if(!closed.get())deliverResult(inspected);return;
            }
            if(request!=null&&request.getPhase11Stage()>0){
                RecognitionResult inspected=recognizer.inspectPhase11(analyzed,metadata.getTimestampNanos(),request.getPhase11Stage()).withAssemblyInspection(11,null,false,null).withRequestTag(requestTag);
                FrameSnapshotCallback snapshot=pendingFrameSnapshot.getAndSet(null);
                if(snapshot!=null){if(rgb==null)rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);captureRequestedFrame(snapshot,analyzed,rgb,metadata,inspected,request.getCameraCalibration());}
                // Phase 11 returns before the shared diagnostics path. Preserve opt-in real
                // camera captures here as well; source photos cannot reproduce camera scale.
                String capture=null;
                if(measurements.captureDue(metadata.getTimestampNanos())){
                    if(rgb==null)rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);
                    capture=measurements.capture(rgb,metadata.getTimestampNanos());
                }
                if(MeasurementRecorder.enabled())measurements.log(metadata,request,inspected,
                        (SystemClock.elapsedRealtimeNanos()-now)/1e6,capture);
                if(!closed.get())deliverResult(inspected);return;
            }
            if(request!=null&&request.getPhase10Stage()>0){
                if(request.getPhase10Stage()==1)rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);
                RecognitionResult inspected=recognizer.inspectPhase10(analyzed,rgb,metadata.getTimestampNanos(),request.getPhase10Stage()).withAssemblyInspection(10,null,false,null).withRequestTag(requestTag);
                FrameSnapshotCallback snapshot=pendingFrameSnapshot.getAndSet(null);
                if(snapshot!=null){if(rgb==null)rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);captureRequestedFrame(snapshot,analyzed,rgb,metadata,inspected,request.getCameraCalibration());}
                // Phase 10 returns before the shared diagnostics path. Preserve opt-in real
                // camera captures here as well; source photos cannot reproduce camera scale.
                String capture=null;
                if(measurements.captureDue(metadata.getTimestampNanos())){
                    if(rgb==null)rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);
                    capture=measurements.capture(rgb,metadata.getTimestampNanos());
                }
                if(MeasurementRecorder.enabled())measurements.log(metadata,request,inspected,
                        (SystemClock.elapsedRealtimeNanos()-now)/1e6,capture);
                if(!closed.get())deliverResult(inspected);return;
            }
            if(request!=null&&request.getPhase9Stage()>0){
                rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);
                RecognitionResult inspected=recognizer.inspectPhase9(analyzed,rgb,metadata.getTimestampNanos(),request.getPhase9Stage()).withAssemblyInspection(9,null,false,null).withRequestTag(requestTag);
                FrameSnapshotCallback snapshot=pendingFrameSnapshot.getAndSet(null);
                if(snapshot!=null){captureRequestedFrame(snapshot,analyzed,rgb,metadata,inspected,request.getCameraCalibration());}
                // Phase 9 returns before the shared diagnostics path. Preserve opt-in real
                // camera captures here as well; source photos cannot reproduce camera scale.
                String capture=null;
                if(measurements.captureDue(metadata.getTimestampNanos())){
                    if(rgb==null)rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);
                    capture=measurements.capture(rgb,metadata.getTimestampNanos());
                }
                if(MeasurementRecorder.enabled())measurements.log(metadata,request,inspected,
                        (SystemClock.elapsedRealtimeNanos()-now)/1e6,capture);
                if(!closed.get())deliverResult(inspected);return;
            }
            if(request!=null&&request.getPhase8Stage()>0){
                if(request.getPhase8Stage()==1)rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);
                RecognitionResult inspected=recognizer.inspectPhase8(analyzed,rgb,metadata.getTimestampNanos(),request.getPhase8Stage()).withAssemblyInspection(8,null,false,null).withRequestTag(requestTag);
                FrameSnapshotCallback snapshot=pendingFrameSnapshot.getAndSet(null);
                if(snapshot!=null){if(rgb==null)rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);captureRequestedFrame(snapshot,analyzed,rgb,metadata,inspected,request.getCameraCalibration());}
                // Phase 8 returns before the shared diagnostics path. Preserve opt-in real
                // camera captures here as well; source photos cannot reproduce camera scale.
                String capture=null;
                if(measurements.captureDue(metadata.getTimestampNanos())){
                    if(rgb==null)rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);
                    capture=measurements.capture(rgb,metadata.getTimestampNanos());
                }
                if(MeasurementRecorder.enabled())measurements.log(metadata,request,inspected,
                        (SystemClock.elapsedRealtimeNanos()-now)/1e6,capture);
                if(!closed.get())deliverResult(inspected);return;
            }
            if(request!=null&&request.getPhase5Stage()>0){
                RecognitionResult inspected=recognizer.inspectPhase5(analyzed,metadata.getTimestampNanos(),request.getPhase5Stage())
                        .withAssemblyInspection(5,null,false,null).withRequestTag(requestTag);
                FrameSnapshotCallback snapshot=pendingFrameSnapshot.getAndSet(null);
                if(snapshot!=null){if(rgb==null)rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);captureRequestedFrame(snapshot,analyzed,rgb,metadata,inspected,request.getCameraCalibration());}
                if(!closed.get())deliverResult(inspected);return;
            }
            if(request!=null&&request.getPhase3Stage()>0){
                RecognitionResult inspected=recognizer.inspectPhase3(analyzed,metadata.getTimestampNanos(),request.getPhase3Stage())
                        .withAssemblyInspection(3,null,false,null).withRequestTag(requestTag);
                FrameSnapshotCallback snapshot=pendingFrameSnapshot.getAndSet(null);
                if(snapshot!=null){if(rgb==null)rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);captureRequestedFrame(snapshot,analyzed,rgb,metadata,inspected,request.getCameraCalibration());}
                rgb=recordOptInPhotoFrame(image,crop,rotationDegrees,metadata,request,inspected,rgb,now);
                if(!closed.get())deliverResult(inspected);return;
            }
            if(request!=null && request.getPhase2Stage()==5) {
                rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);
                RecognitionResult inspected=recognizer.inspectPhase2(analyzed,rgb,metadata.getTimestampNanos(),5,null,request.getPhase2VerificationView()==null?"any":request.getPhase2VerificationView())
                        .withAssemblyInspection(2,null,false,null).withRequestTag(requestTag);
                FrameSnapshotCallback snapshot=pendingFrameSnapshot.getAndSet(null);
                if(snapshot!=null){
                    if(rgb==null)rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);
                    captureRequestedFrame(snapshot,analyzed,rgb,metadata,inspected,request.getCameraCalibration());
                }
                String capture=measurements.captureDue(metadata.getTimestampNanos())?measurements.capture(rgb,metadata.getTimestampNanos()):null;
                if(MeasurementRecorder.enabled())measurements.log(metadata,request,inspected,(SystemClock.elapsedRealtimeNanos()-now)/1e6,capture);
                if(!closed.get()) deliverResult(inspected);
                return;
            }
            double frameGapMs = previousBoardFrame == null ? Double.NaN
                    : (metadata.getTimestampNanos() - previousBoardFrame.getTimestampNanos()) / 1_000_000.0;
            int recognitionPasses = 0;
            if (resetAssemblyTracking.getAndSet(false)
                    || previousBoardFrame != null && !sameFrameGeometry(previousBoardFrame, metadata)) {
                boardTracker.reset();
                loosePartRecognizer.reset();
                previousBoardAnchor = null;
            }
            previousBoardFrame = metadata;
            // Establish the main board before any component-specific work. Neither card number,
            // component identity/polarity nor extension-PCB placement changes this request.
            RecognitionResult.BoardObservation board = null;
            RecognitionResult boardDetection = null;
            // Inventory recognition needs only the loose parts; avoid a second independent board
            // recognition pass until the user starts an actual installed-slot instruction.
            if (assemblyStep > 0 && request.getPhase2Stage()==0 && !request.inspectsLooseBracketSet()) {
                recognitionPasses++;
                boardDetection = recognizer.recognizeBoard(analyzed,
                        metadata.getTimestampNanos(), request.getCameraCalibration(), assemblyStep);
                boolean identifiedBoard = "main_board".equals(boardDetection.getComponentId());
                RecognitionResult.SpatialAnchor detected = identifiedBoard
                        ? boardDetection.getSpatialAnchor() : null;
                // A different identity on the same footprint is a contradiction. An unrelated
                // component elsewhere in the frame is only a board-detector dropout.
                if (!identifiedBoard && previousBoardAnchor != null
                        && ComponentRecognizer.hasConflictingFootprint(
                        previousBoardAnchor.getProjectedCorners(), boardDetection.getProjectedCorners())) {
                    boardTracker.reset();
                }
                RecognitionResult.SpatialAnchor anchor = boardTracker.update(analyzed,
                        metadata.getTimestampNanos(), detected, request.getCameraCalibration());
                previousBoardAnchor = anchor;
                board = new RecognitionResult.BoardObservation(
                        boardDetection, anchor, boardTracker.isTracked(), boardTracker.isHeld());
            }
            boolean inspectMotors = request != null && request.inspectsInstalledMotors();
            boolean motorInspection = request != null && request.requestsInstalledMotorInspection();
            RecognitionRequest.AssemblyExpectation expectation = request == null
                    ? null : request.getExpectation();
            boolean validateBoardOrientation = board != null && expectation != null
                    && "main_board".equals(expectation.getComponentId())
                    && expectation.getPolarityRule() == null && request.getExpectedInstanceCount() == 1;
            boolean boardOnly = board != null && (expectation == null || inspectMotors
                    || validateBoardOrientation);
            RecognitionResult result = validateBoardOrientation
                    ? recognizer.validateBoardOrientation(boardDetection, request) : boardDetection;
            if (!boardOnly) {
                if (request != null && request.getGuidedBracketSlot() != 0 && board != null
                        && !board.isHeld() && board.getAnchor() != null) {
                    request = request.withGuidedBracketWorkplane(board.getAnchor().getProjectedCorners());
                }
                // Component preprocessing and repeated-instance masks own a separate buffer.
                componentGray = analyzed.clone();
                try {
                    recognitionPasses++;
                    result = recognizer.recognize(
                            componentGray, metadata.getTimestampNanos(), request);
                } catch (RuntimeException componentFailure) {
                    if (board == null) throw componentFailure;
                    deliverError(componentFailure);
                    result = RecognitionResult.builder(metadata.getTimestampNanos()).build();
                }
                // An installed driver/Nano is too small for whole-frame ORB in this frame size.
                // With the board localized, inspect its authored slot instead.
                if (board != null && !board.isHeld() && InstalledModuleInspector.shouldAttempt(
                        request, result, board.getAnchor())) {
                    try {
                        if (rgb == null) {
                            rgb = YuvRgbFrame.from(image, crop, rotationDegrees,
                                    rotatePixelsToDisplay);
                        }
                        RecognitionResult beforeModule = result;
                        result = moduleInspector.recognize(rgb, board.getAnchor(), request,
                                result, metadata.getTimestampNanos());
                        if (result != beforeModule) {
                            result = result.withSceneDetectionsFrom(beforeModule);
                        }
                    } catch (RuntimeException moduleFailure) {
                        // Slot evidence is optional: keep the complete ORB result.
                        Log.w(TAG, "Installed module inspection failed", moduleFailure);
                    }
                }
            }
            // Weak diagnostic homographies cannot claim identity in either loose camera flow.
            // An independently located expected-part shape may still pass its existing gates.
            result = LooseOrbEvidenceGate.validate(request, result);
            // These loose parts are poor planar targets. Keep ORB as the primary
            // recognizer, then use expectation-scoped physical appearance only when ORB did not
            // establish usable part geometry. The verified workplane is context; a valid detection
            // of another part is never overwritten. The two-installed-motor check remains below.
            int bracketSlot = request == null ? 0 : request.getGuidedBracketSlot();
            if (bracketSlot != previousGuidedBracketSlot) {
                loosePartRecognizer.reset();
                previousGuidedBracketSlot = bracketSlot;
            }
            Predicate<List<VisionPoint>> bracketPlacement = null;
            if (bracketSlot != 0) {
                List<VisionPoint> boardCorners = board == null || board.isHeld()
                        || board.getAnchor() == null ? null
                        : board.getAnchor().getProjectedCorners();
                ChassisRelativePlacementGate slotGate = ChassisRelativePlacementGate.forMotorBracketSlot(bracketSlot);
                bracketPlacement = corners -> slotGate.evaluateSingle(boardCorners, corners).isAccepted();
            }
            if (LoosePartShapeRecognizer.shouldAttempt(request, result, bracketPlacement)) {
                try {
                    if (rgb == null) {
                        rgb = YuvRgbFrame.from(image, crop, rotationDegrees,
                                rotatePixelsToDisplay);
                    }
                    RecognitionResult beforeShape = result;
                    result = loosePartRecognizer.recognize(analyzed, rgb,
                            metadata.getTimestampNanos(), request, result, bracketPlacement);
                    if (result != beforeShape) {
                        result = result.withSceneDetectionsFrom(beforeShape);
                    }
                } catch (RuntimeException shapeFailure) {
                    loosePartRecognizer.reset();
                    // Shape evidence is optional: retain the complete ORB diagnostic result.
                    Log.w(TAG, "Loose-part shape fallback failed", shapeFailure);
                }
            } else {
                loosePartRecognizer.reset();
            }
            if (LoosePartMismatchRecognizer.shouldAttempt(request, result)) {
                try {
                    if (rgb == null) {
                        rgb = YuvRgbFrame.from(image, crop, rotationDegrees,
                                rotatePixelsToDisplay);
                    }
                    result = LoosePartMismatchRecognizer.recognize(analyzed, rgb,
                            metadata.getTimestampNanos(), request, result);
                } catch (RuntimeException mismatchFailure) {
                    Log.w(TAG, "Loose-part mismatch inspection failed", mismatchFailure);
                }
            }
            if (expectation != null
                    && expectation.getPolarityRule() != null
                    && request.getPolarityObservation() == null
                    && result.getValidationState()
                    == RecognitionResult.ValidationState.POLARITY_UNCONFIRMED
                    && expectation.getComponentId().equals(result.getComponentId())) {
                try {
                    RecognitionRequest.PolarityObservation observation =
                        YuvPolarityDetector.detect(
                                image,
                                crop,
                                rotationDegrees,
                                metadata.arePixelsRotatedToDisplay(),
                                result.getProjectedCorners(),
                                expectation.getPolarityRule(),
                                metadata.getAnalyzedWidth(),
                                metadata.getAnalyzedHeight());
                    if (observation != null) {
                        RecognitionRequest polarityRequest = new RecognitionRequest(
                                expectation, request.getCameraCalibration(), observation,
                                request.getSpatialAnchorComponentId(),
                                request.getExpectedInstanceCount(), request.getAssemblyStepNumber(),
                                request.getMinimumCornerAreaRatioOverride(),
                                request.isDescriptorOnlyIdentityAllowed());
                        recognitionPasses++;
                        RecognitionResult beforePolarity = result;
                        result = recognizer.recognize(
                                componentGray, metadata.getTimestampNanos(), polarityRequest)
                                .withSceneDetectionsFrom(beforePolarity);
                    }
                } catch (RuntimeException componentFailure) {
                    if (board == null) throw componentFailure;
                    deliverError(componentFailure);
                }
            }
            // Main board remains its own observation when the authored workplane is the loose
            // extension PCB (14), or the upper PCB (17-18).
            RecognitionResult.SpatialAnchor workplane = boardOnly ? null : result.getSpatialAnchor();
            boolean workplaneTracked = false;
            if (board != null && "main_board".equals(request.getSpatialAnchorComponentId())) {
                workplane = board.isHeld() ? null : board.getAnchor();
                workplaneTracked = board.isTracked();
            }
            MotorAssemblyObservation motorObservation = null;
            if (modelRecognizer != null && expectation != null && workplane != null) {
                try {
                    RecognitionResult beforeModel = result;
                    result = modelRecognizer.recognize(analyzed, metadata.getTimestampNanos(),
                            request, result, workplane);
                    if (result != beforeModel) {
                        result = result.withSceneDetectionsFrom(beforeModel);
                    }
                } catch (RuntimeException modelFailure) {
                    Log.w(TAG, "Model edge inspection failed; retaining photo evidence", modelFailure);
                }
            }
            if (motorInspection) {
                if (workplane != null) {
                    try {
                        if (rgb == null) {
                            rgb = YuvRgbFrame.from(image, crop, rotationDegrees, rotatePixelsToDisplay);
                        }
                        motorObservation = motorInspector.inspect(rgb, workplane.getProjectedCorners());
                    } catch (RuntimeException componentFailure) {
                        deliverError(componentFailure);
                    }
                }
            }
            if (boardDetection != null && result != boardDetection) {
                result = result.withSceneDetectionsFrom(boardDetection);
            }
            result = result.withAssemblyInspection(assemblyStep, workplane,
                    workplaneTracked, motorObservation).withBoardObservation(board)
                    .withRequestTag(requestTag);
            // Phase 2 loose inventories already have per-instance ORB/silhouette
            // evidence above. Re-running the scene-photo matcher did not contribute
            // counts or progress, but delayed the very outline just established.
            if(request.getPhase2Stage()==1&&result.getDetectedInstanceCount("motor_bracket")<4
                    &&(result.getComponentId()==null||"motor_bracket".equals(result.getComponentId()))
                    &&(result.getRejectionEvidence()==null||result.getRejectionEvidence().getReason()!=RecognitionResult.RejectionReason.IDENTITY_AMBIGUOUS)){
                RecognitionResult group=recognizer.inspectPhase2(analyzed,null,metadata.getTimestampNanos(),1,null,"brackets");
                if(group.getDetectedInstanceCount("motor_bracket")==4)result=group.withRequestTag(requestTag).withSceneDetectionsFrom(result);
            }
            FrameSnapshotCallback requestedSnapshot = pendingFrameSnapshot.getAndSet(null);
            if (requestedSnapshot != null) {
                try {
                    // Diagnostic archives need the same analyzed coordinates as ORB plus the RGB
                    // evidence consumed by the color/shape fallback. This conversion occurs only
                    // for an explicitly requested one-frame snapshot.
                    if (rgb == null) {
                        rgb = YuvRgbFrame.from(image, crop, rotationDegrees,
                                rotatePixelsToDisplay);
                    }
                    captureRequestedFrame(requestedSnapshot, analyzed, rgb, metadata, result,
                            request == null ? null : request.getCameraCalibration());
                } catch (RuntimeException snapshotFailure) {
                    deliverSnapshotError(requestedSnapshot, snapshotFailure);
                }
            }
            double analysisMs = (SystemClock.elapsedRealtimeNanos() - now) / 1_000_000.0;
            logBoardResult(metadata, request, result, analysisMs, recognitionPasses, frameGapMs);
            if (MeasurementRecorder.enabled()) {
                // Session measurements only; the frame was already decided above.
                String capture = null;
                if (measurements.captureDue(metadata.getTimestampNanos())) {
                    try {
                        if (rgb == null) {
                            rgb = YuvRgbFrame.from(image, crop, rotationDegrees, rotatePixelsToDisplay);
                        }
                        capture = measurements.capture(rgb, metadata.getTimestampNanos());
                    } catch (RuntimeException captureFailure) {
                        Log.w(TAG, "Measurement frame capture failed", captureFailure);
                    }
                }
                measurements.log(metadata, request, result, analysisMs, capture);
            }
            if (!closed.get()) deliverResult(result);
        } catch (RuntimeException failure) {
            failPendingFrameSnapshot(failure);
            deliverError(failure);
        } finally {
            if (rgb != null) rgb.release();
            if (componentGray != null) componentGray.release();
            if (rotated != null) {
                rotated.release();
            }
            if (grayscale != null) {
                grayscale.release();
            }
            if (acquired) {
                processing.set(false);
                DiagnosticHammingMatcher.end();
            }
            image.close();
        }
    }

    @Override
    public void close() {
        if(!closed.compareAndSet(false,true))return;
        lifecycleEpoch.incrementAndGet();
        measurements.close();
        failPendingFrameSnapshot(new IllegalStateException("Analyzer closed before frame capture"));
        // The detector owns ORB/global feature objects. Queue release after its readers,
        // never clear them concurrently from the Activity/UI thread.
        try{detectorExecutor.execute(this::releaseDetectorState);}
        finally{detectorExecutor.shutdown();}
    }
    private void releaseDetectorState(){
        detectorOwnership.lock();
        try{
        boardTracker.close();flowBridge.close();targetBounds.close();closeGlobalFeatures();liveWorkspace.close();
        loosePartRecognizer.reset();diagnosticGray=null;diagnosticFrame=null;diagnosticRequest=null;
        previousBoardFrame=null;previousBoardAnchor=null;trackingScope=null;trackingTag=null;frameGlobalCandidate=null;
        }finally{detectorOwnership.unlock();}
    }

    public void resetAssemblyTracking() { resetAssemblyTracking.set(true);targetBounds.reset(); }
    /** A lifecycle pause drops only camera-owned state. The active descriptor bank survives. */
    public void pause(){
        if(closed.get()||!lifecyclePaused.compareAndSet(false,true))return;
        long pauseEpoch=lifecycleEpoch.incrementAndGet();targetBounds.reset();cancelPendingFrameSnapshot();
        try{detectorExecutor.execute(()->{
            detectorOwnership.lock();
            try{
            if(closed.get()||pauseEpoch!=lifecycleEpoch.get())return;
            boardTracker.reset();flowBridge.pause();targetBounds.reset();recognizer.releaseCameraFrameCache();loosePartRecognizer.reset();liveWorkspace.close();
            previousBoardFrame=null;previousBoardAnchor=null;diagnosticGray=null;diagnosticFrame=null;diagnosticRequest=null;trackingScope=null;trackingTag=null;frameGlobalCandidate=null;
            illuminationPolicy.reset();lastGlobalQuery=Long.MIN_VALUE;
            lastQueuedDetection=Long.MIN_VALUE;lastAcceptedFrameNanos.set(Long.MIN_VALUE);
            }finally{detectorOwnership.unlock();}
        });}catch(java.util.concurrent.RejectedExecutionException stopping){/* close owns final release */}
    }
    public void resume(){
        if(closed.get()||!lifecyclePaused.compareAndSet(true,false))return;
        lastQueuedDetection=Long.MIN_VALUE;lastOutlineFrame=Long.MIN_VALUE;lastAcceptedFrameNanos.set(Long.MIN_VALUE);
        long resumeEpoch=lifecycleEpoch.get();
        try{detectorExecutor.execute(()->{if(currentFrame(resumeEpoch)&&motionContext!=null)flowBridge.attach(motionContext);});}catch(java.util.concurrent.RejectedExecutionException stopping){/* closing */}
    }
    public long visualEpoch(){return targetBounds.epoch();}

    /** Captures the next frame that reaches recognition, using its exact analyzed pixels. */
    public boolean captureNextAnalyzedFrame(FrameSnapshotCallback callback) {
        Objects.requireNonNull(callback, "callback");
        if (closed.get() || !pendingFrameSnapshot.compareAndSet(null, callback)) return false;
        if (closed.get() && pendingFrameSnapshot.compareAndSet(callback, null)) {
            deliverSnapshotError(callback,
                    new IllegalStateException("Analyzer closed before frame capture"));
            return false;
        }
        return true;
    }

    /** Discards a queued diagnostic capture when its UI run is paused or replaced. */
    public void cancelPendingFrameSnapshot() {
        pendingFrameSnapshot.set(null);
    }

    private void captureRequestedFrame(FrameSnapshotCallback snapshot,
                                       Mat analyzedGray, Mat analyzedRgb,
                                       FrameMetadata metadata, RecognitionResult result,
                                       CameraCalibration calibration) {
        snapshot.onCaptured(new AnalyzedFrameSnapshot(
                encodeSnapshotPng(analyzedGray, false),
                encodeSnapshotPng(analyzedRgb, true),
                metadata, calibration, result));
    }

    /** Package-visible for lossless diagnostic-snapshot instrumentation. */
    static byte[] encodeSnapshotPng(Mat frame, boolean rgbChannelOrder) {
        if (frame == null || frame.empty()) {
            throw new IllegalStateException("Analyzed frame is unavailable for capture");
        }
        Mat encodedFrame = frame;
        Mat bgr = null;
        MatOfByte encoded = new MatOfByte();
        try {
            if (rgbChannelOrder) {
                // YuvRgbFrame is RGB; image codecs consume BGR. Preserve channel identity across
                // the PNG round trip instead of silently swapping red and blue.
                bgr = new Mat();
                Imgproc.cvtColor(frame, bgr, Imgproc.COLOR_RGB2BGR);
                encodedFrame = bgr;
            }
            if (!Imgcodecs.imencode(".png", encodedFrame, encoded)) {
                throw new IllegalStateException("OpenCV could not encode the analyzed frame");
            }
            return encoded.toArray();
        } finally {
            if (bgr != null) bgr.release();
            encoded.release();
        }
    }

    private void failPendingFrameSnapshot(Throwable failure) {
        FrameSnapshotCallback snapshot = pendingFrameSnapshot.getAndSet(null);
        if (snapshot == null) return;
        deliverSnapshotError(snapshot, failure);
    }

    private static void deliverSnapshotError(
            FrameSnapshotCallback snapshot, Throwable failure) {
        try {
            snapshot.onError(failure);
        } catch (RuntimeException callbackFailure) {
            Log.e(TAG, "Frame snapshot callback failed", callbackFailure);
        }
    }

    private static boolean sameFrameGeometry(FrameMetadata first, FrameMetadata second) {
        return first.sourceWidth == second.sourceWidth && first.sourceHeight == second.sourceHeight
                && first.cropLeft == second.cropLeft && first.cropTop == second.cropTop
                && first.cropWidth == second.cropWidth && first.cropHeight == second.cropHeight
                && first.rotationDegrees == second.rotationDegrees
                && first.pixelsRotatedToDisplay == second.pixelsRotatedToDisplay
                && Arrays.equals(first.sensorToBufferTransform, second.sensorToBufferTransform);
    }

    private static void logBoardResult(FrameMetadata frame, RecognitionRequest request,
                                       RecognitionResult result, double totalMs,
                                       int recognitionPasses, double frameGapMs) {
        if (!Log.isLoggable("AssemblArBoard", Log.DEBUG) || result.getBoardObservation() == null) return;
        RecognitionResult.BoardObservation board = result.getBoardObservation();
        RecognitionResult detection = board.getDetection();
        boolean freshBoard = "main_board".equals(detection.getComponentId());
        List<VisionPoint> corners = board.getAnchor() != null
                ? board.getAnchor().getProjectedCorners() : freshBoard
                ? detection.getProjectedCorners() : java.util.Collections.emptyList();
        double area = 0.0;
        for (int index = 0; index < corners.size(); index++) {
            VisionPoint a = corners.get(index), b = corners.get((index + 1) % corners.size());
            area += a.getX() * b.getY() - b.getX() * a.getY();
        }
        MotorAssemblyObservation motors = result.getMotorAssembly();
        boolean motorsDetected = motors != null
                && motors.getQuality() == MotorAssemblyObservation.Quality.GOOD
                && motors.getLeft().isAccepted() && motors.getRight().isAccepted();
        String expected = request.getExpectation() == null
                ? null : request.getExpectation().getComponentId();
        String componentDetected = expected == null ? "not_run" : request.inspectsInstalledMotors()
                ? Boolean.toString(motorsDetected) : Boolean.toString(
                        expected.equals(result.getComponentId())
                                && result.getDetectionMethod() != RecognitionResult.DetectionMethod.NONE);
        Log.d("AssemblArBoard", String.format(Locale.US,
                "step=%d frame=%d input=%dx%d crop=%d,%d,%dx%d analyzed=%dx%d rotation=%d "
                        + "boardDetected=%s source=%s freshBoardConfidence=%.3f frameKeypoints=%d "
                        + "goodMatches=%d inliers=%d boardPose=%s corners=%s area=%.1f areaRatio=%.5f "
                        + "component=%s componentDetected=%s motorDetected=%s motorQuality=%s "
                        + "recognitionPasses=%d frameGapMs=%.2f totalAnalysisMs=%.2f",
                result.getAssemblyStepNumber(), frame.timestampNanos, frame.sourceWidth, frame.sourceHeight,
                frame.cropLeft, frame.cropTop, frame.cropWidth, frame.cropHeight,
                frame.analyzedWidth, frame.analyzedHeight, frame.rotationDegrees,
                board.isDetected(), board.isTracked() ? "tracking" : freshBoard ? "detection" : "none",
                freshBoard ? detection.getConfidence() : 0.0, detection.getFrameKeypointCount(),
                freshBoard ? detection.getGoodMatchCount() : 0, freshBoard ? detection.getInlierCount() : 0,
                board.getAnchor() != null, corners, Math.abs(area) * 0.5,
                Math.abs(area) * 0.5 / (frame.analyzedWidth * (double) frame.analyzedHeight),
                expected, componentDetected, request.inspectsInstalledMotors()
                        ? Boolean.toString(motorsDetected) : "not_run",
                motors == null ? "not_run" : motors.getQuality(), recognitionPasses, frameGapMs, totalMs));
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Caller owns and must release the returned Mat. */
    public static Mat yPlaneToGray(ImageProxy image, Rect crop) {
        if (image == null || crop == null) {
            throw new IllegalArgumentException("ImageProxy and crop rectangle are required");
        }
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            throw new IllegalArgumentException("ImageProxy must use YUV_420_888 format");
        }
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        if (planes == null || planes.length < 1) {
            throw new IllegalArgumentException("YUV image has no luminance plane");
        }
        if (crop.left < 0 || crop.top < 0 || crop.right > image.getWidth()
                || crop.bottom > image.getHeight() || crop.width() <= 0 || crop.height() <= 0) {
            throw new IllegalArgumentException("Image crop rectangle is outside the frame");
        }

        ImageProxy.PlaneProxy yPlane = planes[0];
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();
        if (rowStride <= 0 || pixelStride <= 0) {
            throw new IllegalArgumentException("Y plane has invalid strides");
        }

        ByteBuffer source = yPlane.getBuffer().duplicate();
        int baseOffset = source.position();
        int width = crop.width();
        int height = crop.height();
        long finalIndex = (long) baseOffset
                + (long) (crop.bottom - 1) * rowStride
                + (long) (crop.right - 1) * pixelStride;
        if (finalIndex < baseOffset || finalIndex >= source.limit()) {
            throw new IllegalArgumentException("Y plane buffer is smaller than its stride metadata");
        }

        final int pixelCount;
        try {
            pixelCount = Math.multiplyExact(width, height);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Y plane dimensions are too large", overflow);
        }
        byte[] luminance = new byte[pixelCount];
        if (pixelStride == 1) {
            for (int row = 0; row < height; row++) {
                int sourceOffset = baseOffset + (crop.top + row) * rowStride + crop.left;
                source.position(sourceOffset);
                source.get(luminance, row * width, width);
            }
        } else {
            for (int row = 0; row < height; row++) {
                int sourceOffset = baseOffset + (crop.top + row) * rowStride
                        + crop.left * pixelStride;
                int destinationOffset = row * width;
                for (int column = 0; column < width; column++) {
                    luminance[destinationOffset + column] =
                            source.get(sourceOffset + column * pixelStride);
                }
            }
        }

        Mat grayscale = new Mat(height, width, CvType.CV_8UC1);
        grayscale.put(0, 0, luminance);
        return grayscale;
    }

    private static void rotate(Mat source, Mat destination, int rotationDegrees) {
        switch (rotationDegrees) {
            case 90:
                Core.rotate(source, destination, Core.ROTATE_90_CLOCKWISE);
                break;
            case 180:
                Core.rotate(source, destination, Core.ROTATE_180);
                break;
            case 270:
                Core.rotate(source, destination, Core.ROTATE_90_COUNTERCLOCKWISE);
                break;
            case 0:
                source.copyTo(destination);
                break;
            default:
                throw new IllegalArgumentException(
                        "CameraX rotation must be a multiple of 90 degrees");
        }
    }

    private static int normalizeRotation(int degrees) {
        int normalized = degrees % 360;
        if (normalized < 0) normalized += 360;
        if (normalized != 0 && normalized != 90
                && normalized != 180 && normalized != 270) {
            throw new IllegalArgumentException(
                    "CameraX rotation must be 0, 90, 180, or 270 degrees");
        }
        return normalized;
    }

    private static float[] matrixValues(Matrix matrix) {
        Matrix safe = matrix == null ? new Matrix() : matrix;
        float[] values = new float[9];
        safe.getValues(values);
        return values;
    }

    private void deliverResult(RecognitionResult result) {
        if(!currentDetection())return;
        // A photo-localized reversed Nano already has the evidence needed for its
        // correction. Re-running the chip network cannot improve that orientation.
        lastPhotoResolved=result.hasLocatedComponent()&&(VerifiedScanEvidence.workflowAccepted(result,diagnosticRequest)
                ||result.getValidationState()==RecognitionResult.ValidationState.WRONG_ORIENTATION);
        try {
            if(diagnosticGray!=null&&diagnosticRequest!=null){
                try{result=globalValidation(result,diagnosticGray,diagnosticRequest);}catch(RuntimeException unavailable){Log.w(TAG,"Optional cross-reference hint unavailable",unavailable);}
                if(!currentDetection())return;
                RecognitionResult.SpatialAnchor seed=result.getSpatialAnchor();
                if(seed==null&&result.getPose()!=null&&result.getProjectedCorners().size()==4&&result.getReferenceId()!=null&&result.getComponentId()!=null)seed=new RecognitionResult.SpatialAnchor(result.getReferenceId(),result.getComponentId(),result.getProjectedCorners(),result.getPose());
                boolean wrong=result.getDiagnosticMetrics()!=null&&result.getDiagnosticMetrics().mismatch!=null;
                if(result.getValidationState()==RecognitionResult.ValidationState.COMPONENT_MISMATCH||result.getValidationState()==RecognitionResult.ValidationState.WRONG_ORIENTATION||result.getValidationState()==RecognitionResult.ValidationState.POLARITY_ERROR){seed=null;wrong=true;}
                // Visual localization is delivered BEFORE workflow completion. Its owner is
                // the camera screen, not the scan token that changes when Next unlocks.
                deliverTarget(result);
                if(!currentDetection())return;
                boolean phase2Inventory=diagnosticRequest.getPhase2Stage()==1||diagnosticRequest.getPhase2Stage()==2;
                boolean inventoryReady=!phase2Inventory||diagnosticRequest.getExpectedInstanceCount()<=1
                        ||result.getDetectedInstanceCount(result.getComponentId())>=diagnosticRequest.getExpectedInstanceCount();
                // Seeing two of four brackets must not pin the preprocessing to a
                // profile that hides the two reflective faces. Continue the existing
                // automatic normal/recovery selection until the requested set exists.
                illuminationPolicy.observe(inventoryReady&&result.hasLocatedComponent()&&result.getValidationState()!=RecognitionResult.ValidationState.NO_COMPONENT,
                        DiagnosticHammingMatcher.snapshot().structuralInput||DiagnosticHammingMatcher.snapshot().edgeWinner);
                if(wrong)flowBridge.reset();
                RecognitionResult.SpatialAnchor visual=null;
                try{if(!wrong&&requestProvider.requiresMetricTracking())visual=flowBridge.update(diagnosticGray,result.getFrameTimestampNanos(),seed,diagnosticRequest.getCameraCalibration());else flowBridge.reset();}catch(RuntimeException trackingFailure){flowBridge.reset();Log.w(TAG,"Visual tracking unavailable",trackingFailure);}
                if(visual!=null&&!flowBridge.predicted())dev.chyken.assembl_ar.diagnostics.DiagnosticSession.INSTANCE.runner.computedFrame(result.getFrameTimestampNanos());
                dev.chyken.assembl_ar.diagnostics.DiagnosticSample sample=null;
                try{sample=dev.chyken.assembl_ar.diagnostics.DiagnosticSession.INSTANCE.record(result,diagnosticRequest,diagnosticGray,!visualOnlyFrame);}catch(RuntimeException diagnosticFailure){Log.w(TAG,"Optional benchmark measurement unavailable",diagnosticFailure);}
                if(!currentDetection())return;
                if(!visualOnlyFrame)callback.onResultWithTelemetry(result,sample);
                else if(sample!=null)callback.onDiagnosticSample(sample);
                if(visual!=null)callback.onTracking(visual,diagnosticFrame,diagnosticRequest.getCameraCalibration(),flowBridge.predicted(),diagnosticRequest.getRequestTag());
            }else if(!visualOnlyFrame)callback.onResult(result);
        } catch (RuntimeException callbackFailure) {
            Log.e(TAG, "Vision result callback failed", callbackFailure);
        }
    }

    /** Explicit diagnostic flags only. Ordinary scans do not allocate RGB or write images. */
    private Mat recordOptInPhotoFrame(ImageProxy image,Rect crop,int rotation,FrameMetadata metadata,RecognitionRequest request,RecognitionResult result,Mat rgb,long started){
        String captured=null;
        if(measurements.captureDue(metadata.getTimestampNanos())){
            if(rgb==null)rgb=YuvRgbFrame.from(image,crop,rotation,rotatePixelsToDisplay);
            captured=measurements.capture(rgb,metadata.getTimestampNanos());
        }
        if(MeasurementRecorder.enabled())measurements.log(metadata,request,result,(SystemClock.elapsedRealtimeNanos()-started)/1e6,captured);
        return rgb;
    }
    private boolean currentDetection(){return !closed.get()&&!lifecyclePaused.get()&&detectionEpoch==lifecycleEpoch.get();}
    private void deliverTarget(RecognitionResult result){
        if(!currentDetection())return;
        java.util.List<java.util.List<VisionPoint>> regions=new java.util.ArrayList<>();
        for(RecognitionResult.InstanceObservation part:result.getInstanceObservations())if(part.getProjectedCorners().size()==4&&regions.size()<4)regions.add(part.getProjectedCorners());
        if(regions.isEmpty()&&result.hasLocatedComponent()&&result.getProjectedCorners().size()==4)regions.add(result.getProjectedCorners());
        String id=result.getComponentId(),name=result.getDisplayName();
        boolean wrong=result.getValidationState()==RecognitionResult.ValidationState.COMPONENT_MISMATCH||result.getValidationState()==RecognitionResult.ValidationState.WRONG_ORIENTATION||result.getValidationState()==RecognitionResult.ValidationState.POLARITY_ERROR;
        boolean verified=result.getValidationState()==RecognitionResult.ValidationState.EXPECTED_COMPONENT;
        TargetBoundingTracker.State state=wrong?TargetBoundingTracker.State.WRONG:verified?TargetBoundingTracker.State.LOCKED:TargetBoundingTracker.State.ACQUIRING;
        String expected=diagnosticRequest.getExpectation()==null?null:diagnosticRequest.getExpectation().getComponentId();
        GlobalComponentRegistry.Match global=frameGlobalCandidate;
        if(global!=null&&(regions.isEmpty()||result.getDiagnosticMetrics()!=null&&result.getDiagnosticMetrics().mismatch!=null)){
            regions=new java.util.ArrayList<>(global.bodyRegions);if(regions.isEmpty())regions.add(global.targetCorners);
            id=global.componentId;name=global.name;wrong=global.identityCertain&&global.incompatible(diagnosticRequest.getAssemblyStepNumber(),expected);
            state=wrong?TargetBoundingTracker.State.WRONG:global.identityCertain?TargetBoundingTracker.State.LOCKED:TargetBoundingTracker.State.ACQUIRING;verified=false;
        }
        if(regions.isEmpty()||id==null)return;
        TargetBoundingTracker.Target located=targetBounds.detect(diagnosticGray,result.getFrameTimestampNanos(),id,ComponentContourTemplates.name(id,name),ComponentContourTemplates.name(expected,expected),state,regions,verified);
        if(currentDetection())callback.onTargetTracking(located,diagnosticFrame,diagnosticRequest.getRequestTag());else targetBounds.reset();
    }

    private RecognitionResult globalValidation(RecognitionResult result,Mat gray,RecognitionRequest request){
        RecognitionResult.DiagnosticMetrics prior=result.getDiagnosticMetrics();String mismatch=null;
        DiagnosticHammingMatcher.State probe=DiagnosticHammingMatcher.snapshot();
        // Installed components share a PCB with many earlier/later parts. Local independent
        // component + board geometry is the verification gate. The global library supplies
        // wrong-item hints only when that gate fails, at a bounded cadence, not a second
        // full-frame ORB/database search after every successful scan.
        boolean verified=result.getValidationState()==RecognitionResult.ValidationState.EXPECTED_COMPONENT&&result.getDetectionMethod()==RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY;
        boolean knownRejection=result.getPhase2Observation()!=null&&result.getPhase2Observation().rejected
                ||result.getValidationState()==RecognitionResult.ValidationState.WRONG_ORIENTATION
                ||result.getValidationState()==RecognitionResult.ValidationState.POLARITY_ERROR;
        long now=SystemClock.elapsedRealtimeNanos();
        if(!LiveVisionPolicy.scoped(request)&&!probe.structuralInput&&!verified&&!knownRejection&&GlobalComponentRegistry.INSTANCE.isReady()&&(lastGlobalQuery==Long.MIN_VALUE||now-lastGlobalQuery>=(targetBounds.hasTarget()?1_000_000_000L:350_000_000L))){
            lastGlobalQuery=now;
            if(probe.live==null){
                if(globalOrb==null){globalOrb=OpenCvVisionUtils.createOrb(VisionConfig.defaults());globalClahe=OpenCvVisionUtils.createClahe(VisionConfig.defaults());}
                Mat normalized=new Mat(),descriptors=new Mat(),mask=new Mat();org.opencv.core.MatOfKeyPoint points=new org.opencv.core.MatOfKeyPoint();
                try{DiagnosticHammingMatcher.normalize(globalClahe,gray,normalized);DiagnosticHammingMatcher.extract(globalOrb,normalized,mask,points,descriptors);DiagnosticHammingMatcher.captureLive(points.toArray(),descriptors,1);}finally{normalized.release();descriptors.release();mask.release();points.release();}
            }
            long started=SystemClock.elapsedRealtimeNanos(),geometryBefore=probe.geometryNs;
            try{GlobalComponentRegistry.Match best=probe.live==null?null:GlobalComponentRegistry.INSTANCE.queryForOverlay(probe.live,probe.liveKeys,request.getExpectation()==null?null:request.getExpectation().getComponentId(),request.getAssemblyStepNumber());
                frameGlobalCandidate=best;
                String expected=request.getExpectation()==null?null:request.getExpectation().getComponentId();
                boolean competing=best!=null&&GlobalComponentRegistry.competesWithLocalization(result,best.targetCorners);
                if(competing&&best.identityCertain&&best.incompatible(request.getAssemblyStepNumber(),expected)&&best.score>Math.max(15,result.getInlierCount()*1.25))mismatch=best.phase>0?"Incorrect component detected. This item belongs to Phase "+best.phase+".":"Incorrect component detected. Please select the requested component.";
            }finally{long finished=SystemClock.elapsedRealtimeNanos();probe.matchingNs+=Math.max(0,finished-started-(probe.geometryNs-geometryBefore));lastGlobalQuery=finished;}
        }
        VerifiedScanEvidence evidence=DiagnosticHammingMatcher.bind(result,request.getCameraCalibration());
        if(evidence==null&&!VerifiedScanEvidence.workflowAccepted(result,request)&&frameGlobalCandidate!=null&&GlobalComponentRegistry.competesWithLocalization(result,frameGlobalCandidate.targetCorners))
            evidence=DiagnosticHammingMatcher.bindGlobal(frameGlobalCandidate,request.getCameraCalibration());
        double extraction=probe.featureNs/1e6,geometry=probe.geometryNs/1e6;
        result=result.withLatency(new RecognitionResult.Latency(extraction,(probe.matchingNs+probe.geometryNs)/1e6,geometry,result.getLatency().getCircularFallbackMs(),Math.max(0,SystemClock.elapsedRealtimeNanos()-probe.started)/1e6));
        return result.withVerifiedScanEvidence(evidence,mismatch,probe.structuralInput||probe.edgeWinner?"log-reflectance+Sobel+ORB":probe.recovery?"illumination-recovery+CLAHE":"CLAHE");
    }
    private void closeGlobalFeatures(){if(globalOrb!=null){globalOrb.clear();globalOrb=null;}if(globalClahe!=null){globalClahe.clear();globalClahe=null;}}

    private void deliverError(Throwable failure) {
        if (closed.get()) return;
        try {
            callback.onError(failure);
        } catch (RuntimeException callbackFailure) {
            Log.e(TAG, "Vision error callback failed", callbackFailure);
        }
    }

    /** Supplies current step expectations and calibrated intrinsics for analyzed coordinates. */
    public interface RequestProvider {
        RecognitionRequest requestFor(FrameMetadata frameMetadata);
        default RecognitionRequest requestForTracking(FrameMetadata frame){return null;}

        /** True while no camera evidence is needed, so frames are released unanalyzed. */
        default boolean isAnalysisSuspended() { return false; }
        default boolean isTrackingSuspended(){return isAnalysisSuspended();}
        default boolean requiresMetricTracking(){return true;}
    }

    public interface Callback {
        void onResult(RecognitionResult result);
        /** One immutable frame/result pair; consumers must not read the singleton's newer frame. */
        default void onResultWithTelemetry(RecognitionResult result,dev.chyken.assembl_ar.diagnostics.DiagnosticSample sample){
            if(sample!=null)onDiagnosticSample(sample);
            onResult(result);
        }
        default void onDiagnosticSample(dev.chyken.assembl_ar.diagnostics.DiagnosticSample sample) { }
        default void onTargetTracking(TargetBoundingTracker.Target target,FrameMetadata frame,Object requestTag) { }
        default void onOutlineTracking(ImageOutlineTracker.Outline outline,FrameMetadata frame,Object requestTag) { }

        default void onTracking(RecognitionResult.SpatialAnchor anchor,FrameMetadata frame,CameraCalibration calibration,boolean predicted,Object requestTag) { }
        default void onError(Throwable failure) {
            Log.e(TAG, "Vision analysis failed", failure);
        }
    }

    public interface FrameSnapshotCallback {
        void onCaptured(AnalyzedFrameSnapshot snapshot);

        default void onError(Throwable failure) {
            Log.e(TAG, "Analyzed frame capture failed", failure);
        }
    }

    /** Lossless ORB grayscale, RGB fallback pixels, and metadata for one analyzed frame. */
    public static final class AnalyzedFrameSnapshot {
        private final byte[] pngBytes;
        private final byte[] rgbPngBytes;
        private final FrameMetadata metadata;
        private final CameraCalibration cameraCalibration;
        private final RecognitionResult recognitionResult;

        private AnalyzedFrameSnapshot(byte[] pngBytes, byte[] rgbPngBytes,
                                      FrameMetadata metadata,
                                      CameraCalibration cameraCalibration,
                                      RecognitionResult recognitionResult) {
            this.pngBytes = pngBytes.clone();
            this.rgbPngBytes = rgbPngBytes.clone();
            this.metadata = metadata;
            this.cameraCalibration = cameraCalibration;
            this.recognitionResult = recognitionResult;
        }

        public byte[] getPngBytes() { return pngBytes.clone(); }
        public byte[] getRgbPngBytes() { return rgbPngBytes.clone(); }
        public FrameMetadata getMetadata() { return metadata; }
        public CameraCalibration getCameraCalibration() { return cameraCalibration; }
        public RecognitionResult getRecognitionResult() { return recognitionResult; }
    }

    /** Immutable CameraX frame/crop/rotation metadata exposed to the request provider. */
    public static final class FrameMetadata {
        private final long timestampNanos;
        private final int sourceWidth;
        private final int sourceHeight;
        private final int cropLeft;
        private final int cropTop;
        private final int cropWidth;
        private final int cropHeight;
        private final int analyzedWidth;
        private final int analyzedHeight;
        private final int rotationDegrees;
        private final boolean pixelsRotatedToDisplay;
        private final float[] sensorToBufferTransform;

        public FrameMetadata(long timestampNanos, int sourceWidth, int sourceHeight,
                             int cropLeft, int cropTop, int cropWidth, int cropHeight,
                             int analyzedWidth, int analyzedHeight, int rotationDegrees,
                             boolean pixelsRotatedToDisplay, float[] sensorToBufferTransform) {
            if (sourceWidth <= 0 || sourceHeight <= 0
                    || cropLeft < 0 || cropTop < 0 || cropWidth <= 0 || cropHeight <= 0
                    || cropLeft + cropWidth > sourceWidth
                    || cropTop + cropHeight > sourceHeight
                    || analyzedWidth <= 0 || analyzedHeight <= 0
                    || rotationDegrees != 0 && rotationDegrees != 90
                    && rotationDegrees != 180 && rotationDegrees != 270) {
                throw new IllegalArgumentException("Frame dimensions/crop/rotation are invalid");
            }
            int expectedWidth = pixelsRotatedToDisplay
                    && (rotationDegrees == 90 || rotationDegrees == 270)
                    ? cropHeight : cropWidth;
            int expectedHeight = pixelsRotatedToDisplay
                    && (rotationDegrees == 90 || rotationDegrees == 270)
                    ? cropWidth : cropHeight;
            if (analyzedWidth != expectedWidth || analyzedHeight != expectedHeight) {
                throw new IllegalArgumentException(
                        "Analyzed dimensions do not match the crop/rotation");
            }
            this.timestampNanos = timestampNanos;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.cropLeft = cropLeft;
            this.cropTop = cropTop;
            this.cropWidth = cropWidth;
            this.cropHeight = cropHeight;
            this.analyzedWidth = analyzedWidth;
            this.analyzedHeight = analyzedHeight;
            this.rotationDegrees = rotationDegrees;
            this.pixelsRotatedToDisplay = pixelsRotatedToDisplay;
            if (sensorToBufferTransform == null || sensorToBufferTransform.length != 9) {
                throw new IllegalArgumentException(
                        "Sensor-to-buffer transform must contain nine values");
            }
            for (float value : sensorToBufferTransform) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException(
                            "Sensor-to-buffer transform must contain finite values");
                }
            }
            this.sensorToBufferTransform = sensorToBufferTransform.clone();
        }

        public long getTimestampNanos() { return timestampNanos; }
        public int getSourceWidth() { return sourceWidth; }
        public int getSourceHeight() { return sourceHeight; }
        public int getCropLeft() { return cropLeft; }
        public int getCropTop() { return cropTop; }
        public int getCropWidth() { return cropWidth; }
        public int getCropHeight() { return cropHeight; }
        public int getAnalyzedWidth() { return analyzedWidth; }
        public int getAnalyzedHeight() { return analyzedHeight; }
        public int getRotationDegrees() { return rotationDegrees; }
        public boolean arePixelsRotatedToDisplay() { return pixelsRotatedToDisplay; }
        public float[] getSensorToBufferTransform() {
            return sensorToBufferTransform.clone();
        }
    }
}
