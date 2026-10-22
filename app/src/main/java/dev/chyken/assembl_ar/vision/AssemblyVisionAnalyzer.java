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

    @Override
    public void analyze(@NonNull ImageProxy image) {
        Objects.requireNonNull(image, "image");
        Mat grayscale = null;
        Mat rotated = null;
        Mat rgb = null;
        Mat componentGray = null;
        boolean acquired = false;
        try {
            if (closed.get()) {
                return;
            }
            // A demonstration or assembly screen needs no camera evidence; skip the frame entirely.
            if (requestProvider.isAnalysisSuspended()) return;
            long now = SystemClock.elapsedRealtimeNanos();
            long captureTimestampNanos = CaptureTimebase.toElapsedRealtimeNanos(
                    image.getImageInfo().getTimestamp());
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
            if (closed.get()) return;
            lastAcceptedFrameNanos.set(captureTimestampNanos);

            if (image.getFormat() != ImageFormat.YUV_420_888) {
                throw new IllegalArgumentException(
                        "ImageAnalysis must output YUV_420_888; received format "
                                + image.getFormat());
            }
            Rect crop = image.getCropRect();
            grayscale = yPlaneToGray(image, crop);

            int rotationDegrees = normalizeRotation(image.getImageInfo().getRotationDegrees());
            Mat analyzed = grayscale;
            if (rotatePixelsToDisplay && rotationDegrees != 0) {
                rotated = new Mat();
                rotate(grayscale, rotated, rotationDegrees);
                analyzed = rotated;
            }

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
            RecognitionRequest request = requestProvider.requestFor(metadata);
            Object requestTag = request == null ? null : request.getRequestTag();
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
                if(snapshot!=null){rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);captureRequestedFrame(snapshot,analyzed,rgb,metadata,inspected,request.getCameraCalibration());}
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
                RecognitionResult inspected=recognizer.inspectPhase10(analyzed,metadata.getTimestampNanos(),request.getPhase10Stage()).withAssemblyInspection(10,null,false,null).withRequestTag(requestTag);
                FrameSnapshotCallback snapshot=pendingFrameSnapshot.getAndSet(null);
                if(snapshot!=null){rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);captureRequestedFrame(snapshot,analyzed,rgb,metadata,inspected,request.getCameraCalibration());}
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
                RecognitionResult inspected=recognizer.inspectPhase8(analyzed,metadata.getTimestampNanos(),request.getPhase8Stage()).withAssemblyInspection(8,null,false,null).withRequestTag(requestTag);
                FrameSnapshotCallback snapshot=pendingFrameSnapshot.getAndSet(null);
                if(snapshot!=null){rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);captureRequestedFrame(snapshot,analyzed,rgb,metadata,inspected,request.getCameraCalibration());}
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
                if(snapshot!=null){rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);captureRequestedFrame(snapshot,analyzed,rgb,metadata,inspected,request.getCameraCalibration());}
                if(!closed.get())deliverResult(inspected);return;
            }
            if(request!=null&&request.getPhase3Stage()>0){
                RecognitionResult inspected=recognizer.inspectPhase3(analyzed,metadata.getTimestampNanos(),request.getPhase3Stage())
                        .withAssemblyInspection(3,null,false,null).withRequestTag(requestTag);
                FrameSnapshotCallback snapshot=pendingFrameSnapshot.getAndSet(null);
                if(snapshot!=null){rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);captureRequestedFrame(snapshot,analyzed,rgb,metadata,inspected,request.getCameraCalibration());}
                if(!closed.get())deliverResult(inspected);return;
            }
            if(request!=null && request.getPhase2Stage()==5) {
                RecognitionResult inspected=recognizer.inspectPhase2(analyzed,metadata.getTimestampNanos(),5,null)
                        .withAssemblyInspection(2,null,false,null).withRequestTag(requestTag);
                FrameSnapshotCallback snapshot=pendingFrameSnapshot.getAndSet(null);
                if(snapshot!=null){
                    if(rgb==null)rgb=YuvRgbFrame.from(image,crop,rotationDegrees,rotatePixelsToDisplay);
                    captureRequestedFrame(snapshot,analyzed,rgb,metadata,inspected,request.getCameraCalibration());
                }
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
            if(request!=null && request.getPhase2Stage()>0)
                result=recognizer.inspectPhase2(analyzed,metadata.getTimestampNanos(),request.getPhase2Stage(),result);
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
                if (closed.get()) boardTracker.close();
            }
            image.close();
        }
    }

    @Override
    public void close() {
        closed.set(true);
        measurements.close();
        failPendingFrameSnapshot(new IllegalStateException("Analyzer closed before frame capture"));
        if (!processing.get()) boardTracker.close();
    }

    public void resetAssemblyTracking() { resetAssemblyTracking.set(true); }

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
        try {
            callback.onResult(result);
        } catch (RuntimeException callbackFailure) {
            Log.e(TAG, "Vision result callback failed", callbackFailure);
        }
    }

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

        /** True while no camera evidence is needed, so frames are released unanalyzed. */
        default boolean isAnalysisSuspended() { return false; }
    }

    public interface Callback {
        void onResult(RecognitionResult result);

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
