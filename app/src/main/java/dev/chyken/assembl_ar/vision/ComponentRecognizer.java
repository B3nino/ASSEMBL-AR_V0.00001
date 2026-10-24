package dev.chyken.assembl_ar.vision;

import android.os.SystemClock;
import android.util.Log;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manuscript-defined recognition and spatial-validation pipeline:
 * CLAHE -> normal/inverted ORB -> BF_HAMMING KNN ratio filtering -> RANSAC homography ->
 * solvePnP, with a Canny/Hough-circles fallback for shiny circular hardware.
 */
public final class ComponentRecognizer implements AutoCloseable {
    private static final String TAG = "AssemblArVision";
    private static final String BOARD_DIAGNOSTIC_TAG = "AssemblArBoard";
    // Same per-reference stage trace for the requested non-board component.
    private static final String EXPECTED_DIAGNOSTIC_TAG = "AssemblArExpected";
    private static final AtomicBoolean MATCHING_CONFIGURATION_LOGGED = new AtomicBoolean();
    private static final int MAXIMUM_RETAINED_DESCRIPTOR_BATCHES = 8;
    private static final String MAIN_BOARD_COMPONENT_ID = "main_board";
    private static final int EDGE_SUPPORT_SAMPLE_COUNT = 48;
    private static final double MINIMUM_CIRCLE_EDGE_SUPPORT = 0.15;
    private static final int EXPOSED_BOARD_GRID_SIZE = 4;
    private static final int MINIMUM_EXPOSED_BOARD_CELLS = 4;
    private static final double MINIMUM_EXPOSED_BOARD_SPAN = 0.24;

    private final VisionConfig config;
    private final ReferenceDatabase database;
    private final ORB orb;
    private final CLAHE clahe;
    private final Phase2ReferenceMatcher phase2Matcher;
    private final Phase7ReferenceMatcher phase7Matcher;
    private final Phase8ReferenceMatcher phase8Matcher;
    private final Phase9ReferenceMatcher phase9Matcher;
    private final Phase11ReferenceMatcher phase11Matcher;
    private final Phase10ReferenceMatcher phase10Matcher;
    private final Phase5ReferenceMatcher phase5Matcher;
    private final Phase3ReferenceMatcher phase3Matcher;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final FrameFeatureCache frameCache = new FrameFeatureCache();
    private final OrderedMatchingExecutor matchingExecutor = new OrderedMatchingExecutor(
            Runtime.getRuntime().availableProcessors(), Core.getNumThreads());
    // Reference descriptors do not change during this recognizer's lifetime. Reuse their
    // exact ordered concatenation, avoiding a megabyte-sized temporary on every camera frame.
    private final RetainedBatchCache<PreparedDescriptorBatch> descriptorBatches =
            new RetainedBatchCache<>(MAXIMUM_RETAINED_DESCRIPTOR_BATCHES,
                    PreparedDescriptorBatch::close);
    // Whole-frame descriptors of the recognition call in progress; tiles and masks differ.
    private Mat activeFrameDescriptors;
    // Set only during the synchronized board pass; no per-variant formatting when disabled.
    private String boardDiagnosticPrefix;
    private String expectedDiagnosticPrefix;
    private String expectedDiagnosticComponentId;
    // Why the most recent estimatePose call returned null; read only by diagnostics.
    private String lastPoseFailure;
    private double lastPoseErrorPx = Double.NaN;
    private boolean recognizingBoard;
    private boolean quickBoardReferencePass;
    private final IndividualReferenceSearchWindow individualWindow=new IndividualReferenceSearchWindow();
    private Set<String> individualFrameReferences;

    /** OpenCV and the supplied reference database must already be initialized. */
    public ComponentRecognizer(VisionConfig config, ReferenceDatabase database) {
        this.config = Objects.requireNonNull(config, "config");
        this.database = Objects.requireNonNull(database, "database");
        orb = OpenCvVisionUtils.createOrb(config);
        clahe = OpenCvVisionUtils.createClahe(config);
        phase2Matcher=new Phase2ReferenceMatcher(database,config);
        phase3Matcher=new Phase3ReferenceMatcher(database,config);
        phase8Matcher=new Phase8ReferenceMatcher(database,config);
        phase9Matcher=new Phase9ReferenceMatcher(database,config);phase10Matcher=new Phase10ReferenceMatcher(database,config);phase11Matcher=new Phase11ReferenceMatcher(database,config);
        phase5Matcher=new Phase5ReferenceMatcher(database,config);
        phase7Matcher=new Phase7ReferenceMatcher(database,config);
    }
    synchronized RecognitionResult inspectPhase2(Mat gray,long timestamp,int stage,RecognitionResult primary) {
        ensureOpen(); return phase2Matcher.inspect(gray,timestamp,stage,primary);
    }
    synchronized RecognitionResult inspectPhase2(Mat gray,Mat rgb,long timestamp,int stage,RecognitionResult primary){ensureOpen();return phase2Matcher.inspect(gray,rgb,timestamp,stage,primary);}
    synchronized RecognitionResult inspectPhase2(Mat gray,Mat rgb,long timestamp,int stage,RecognitionResult primary,String view){ensureOpen();return phase2Matcher.inspect(gray,rgb,timestamp,stage,primary,view);}
    synchronized RecognitionResult inspectPhase7(Mat gray,long timestamp,int step){ensureOpen();return phase7Matcher.inspect(gray,timestamp,step);}
    synchronized RecognitionResult inspectPhase7(Mat gray,Mat rgb,long timestamp,int step){ensureOpen();return phase7Matcher.inspect(gray,rgb,timestamp,step);}
    synchronized RecognitionResult inspectPhase11(Mat gray,long timestamp,int stage){ensureOpen();return phase11Matcher.inspect(gray,timestamp,stage);}
    synchronized RecognitionResult inspectPhase10(Mat gray,long timestamp,int stage){ensureOpen();return phase10Matcher.inspect(gray,timestamp,stage);}
    synchronized RecognitionResult inspectPhase9(Mat gray,Mat rgb,long timestamp,int stage){ensureOpen();return phase9Matcher.inspect(gray,rgb,timestamp,stage);}
    synchronized RecognitionResult inspectPhase8(Mat gray,long timestamp,int step){ensureOpen();return phase8Matcher.inspect(gray,timestamp,step);}
    synchronized RecognitionResult inspectPhase5(Mat gray,long timestamp,int step){ensureOpen();return phase5Matcher.inspect(gray,timestamp,step);}
    synchronized RecognitionResult inspectPhase3(Mat gray,long timestamp,int step){ensureOpen();return phase3Matcher.inspect(gray,timestamp,step);}

    /**
     * Runs the same board recognition for every assembly step on the original grayscale frame.
     * The constant step-zero request tries the baseline references used in Step 1 first.
     * Installed-board references are a fixed fallback tier in every step; they cannot replace
     * an already successful baseline match merely because the assembly step changes.
     * A small set of representative front/back views is tried first in Phase 1. Its result is
     * accepted only with strong spatial consensus; otherwise the complete catalog, including
     * competing identities and every perspective, runs on the cached frame descriptors.
     */
    public synchronized RecognitionResult recognizeBoard(Mat grayscale,
                                                         long frameTimestampNanos,
                                                         CameraCalibration calibration,
                                                         int currentStep) {
        RecognitionRequest boardRequest = new RecognitionRequest(
                RecognitionRequest.AssemblyExpectation.component(MAIN_BOARD_COMPONENT_ID),
                calibration, null, MAIN_BOARD_COMPONENT_ID, 1, currentStep == 1 ? 1 : 0);
        boolean diagnostics = Log.isLoggable(BOARD_DIAGNOSTIC_TAG, Log.DEBUG);
        try {
            recognizingBoard = true;
            if (diagnostics) {
                boardDiagnosticPrefix = "frame=" + frameTimestampNanos + " step=" + currentStep;
                logBoardConfiguration(grayscale);
            }
            RecognitionResult result;
            if (currentStep == 1) {
                quickBoardReferencePass = true;
                result = recognize(grayscale, frameTimestampNanos, boardRequest);
                quickBoardReferencePass = false;
                if (!hasStrongQuickBoardEvidence(result)) {
                    result = recognize(grayscale, frameTimestampNanos, boardRequest);
                }
            } else {
                result = recognize(grayscale, frameTimestampNanos, boardRequest);
            }
            if (diagnostics) {
                boolean boardDetected = MAIN_BOARD_COMPONENT_ID.equals(result.getComponentId())
                        && result.getProjectedCorners().size() == 4;
                Log.d(BOARD_DIAGNOSTIC_TAG, boardDiagnosticPrefix
                        + " result boardDetected=" + boardDetected
                        + " selectedComponent=" + result.getComponentId()
                        + " reference=" + result.getReferenceId()
                        + " frameKeypoints=" + result.getFrameKeypointCount()
                        + " boardConfidence=" + (boardDetected ? result.getConfidence() : "n/a")
                        + " boardCorners=" + (boardDetected ? result.getProjectedCorners() : "n/a")
                        + " boardAreaPx=" + (boardDetected
                        ? Math.abs(signedArea(result.getProjectedCorners())) : "n/a")
                        + " pose=" + (boardDetected && result.getPose() != null));
            }
            return result;
        } catch (RuntimeException failure) {
            if (diagnostics) {
                Log.e(BOARD_DIAGNOSTIC_TAG, boardDiagnosticPrefix + " boardPassFailed", failure);
            }
            throw failure;
        } finally {
            boardDiagnosticPrefix = null;
            quickBoardReferencePass = false;
            recognizingBoard = false;
        }
    }

    private static boolean hasStrongQuickBoardEvidence(RecognitionResult result) {
        return MAIN_BOARD_COMPONENT_ID.equals(result.getComponentId())
                && result.getDetectionMethod() == RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY
                && result.hasLocatedComponent() && result.getPose() != null
                && result.getInlierCount() >= 24 && result.getInlierRatio() >= 0.65
                && result.getPose().getReprojectionErrorPx() <= 4.5;
    }

    /**
     * Step 1 validates the already detected board's orientation without another component pass.
     * Polarity/repeated-instance requests still require the component pipeline.
     */
    RecognitionResult validateBoardOrientation(RecognitionResult board, RecognitionRequest request) {
        Objects.requireNonNull(board, "board");
        RecognitionRequest.AssemblyExpectation expectation = request.getExpectation();
        if (expectation == null || !MAIN_BOARD_COMPONENT_ID.equals(expectation.getComponentId())
                || expectation.getPolarityRule() != null || request.getExpectedInstanceCount() != 1) {
            throw new IllegalArgumentException("Requires a single-board orientation request without polarity");
        }
        if (!MAIN_BOARD_COMPONENT_ID.equals(board.getComponentId())
                || board.getDetectionMethod() != RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY
                || board.getProjectedCorners().size() != 4) {
            return board;
        }
        OrientationEstimate orientation = classifyOrientation(board.getProjectedCorners(), request);
        RecognitionResult.ValidationState validation = validateExpectedComponent(
                board.getComponentId(), orientation.matchesExpectation, board.getPolarityState(), request);
        return board.withOrientationValidation(orientation.classification, orientation.degrees, validation);
    }

    /**
     * Synchronously analyzes one CV_8UC1 frame. The input Mat remains caller-owned and is never
     * retained. Calls are serialized because OpenCV Feature2D/CLAHE objects are not
     * documented as thread-safe.
     */
    public synchronized RecognitionResult recognize(Mat grayscale,
                                                    long frameTimestampNanos,
                                                    RecognitionRequest request) {
        ensureOpen();
        validateFrame(grayscale);
        if (MATCHING_CONFIGURATION_LOGGED.compareAndSet(false, true)) {
            Log.i("AssemblArMetrics", "matcherConfiguration nativeThreads=" + Core.getNumThreads()
                    + " matchingWorkers=" + matchingExecutor.workerCount());
        }
        RecognitionRequest safeRequest = request == null
                ? RecognitionRequest.unconstrained(null)
                : request;
        long totalStart = SystemClock.elapsedRealtimeNanos();
        if(database.prepareIndividualBank(safeRequest)){
            // Cached pair batches own the old reference keys. Retire them on every
            // bank change so moving between parts cannot retain earlier photo banks.
            frameCache.release();descriptorBatches.close();
        }
        individualFrameReferences=null;
        if(!database.isIndividualBank()&&safeRequest.getAssemblyStepNumber()==0&&safeRequest.isDescriptorOnlyIdentityAllowed()&&safeRequest.getExpectation()!=null){
            List<ComponentReferenceSpec> photos=new ArrayList<>();for(ReferenceDatabase.PreparedReference r:database.preparedReferences())photos.add(r.spec);
            individualFrameReferences=individualWindow.next(photos,safeRequest.getExpectation().getComponentId());
        }
        long featureNanos = 0L;
        long matchingNanos = 0L;
        long spatialNanos = 0L;
        long fallbackNanos = 0L;

        MatOfKeyPoint frameKeypointMat = new MatOfKeyPoint();
        Mat frameDescriptors = new Mat();
        Mat normalizedFrame = new Mat();
        Mat emptyMask = new Mat();
        List<RawCandidate> descriptorCandidates = new ArrayList<>();
        List<SpatialCandidate> spatialCandidates = new ArrayList<>();
        List<SpatialCandidate> instanceCandidates = new ArrayList<>();
        SpatialCandidate bestCandidate = null;
        ExpectedRejectionTracker rejectionTracker = null;
        boolean databaseAcquired = false;
        try {
            database.acquireForRecognition();
            databaseAcquired = true;
            validateRequest(safeRequest);
            long stageStart = SystemClock.elapsedRealtimeNanos();
            KeyPoint[] frameKeypoints;
            if (frameCache.holds(grayscale)) {
                // Board/component passes and repeated camera frames can have identical pixels
                // despite different capture timestamps. Identity evidence then remains exact.
                frameCache.normalized.copyTo(normalizedFrame);
                frameCache.descriptors.copyTo(frameDescriptors);
                frameKeypoints = frameCache.keypoints;
            } else {
                DiagnosticHammingMatcher.normalize(clahe,grayscale, normalizedFrame);
                DiagnosticHammingMatcher.extract(orb,
                        normalizedFrame, emptyMask, frameKeypointMat, frameDescriptors);
                frameKeypoints = frameKeypointMat.toArray();
                frameCache.store(grayscale, normalizedFrame,
                        frameDescriptors, frameKeypoints);
            }
            DiagnosticHammingMatcher.captureLive(frameKeypoints,frameDescriptors,1);
            activeFrameDescriptors = frameDescriptors;
            featureNanos = SystemClock.elapsedRealtimeNanos() - stageStart;
            rejectionTracker = ExpectedRejectionTracker.initialize(
                    database.preparedReferences(), safeRequest, frameKeypoints.length,
                    frameDescriptors.empty(), config);
            String expectedId = safeRequest.getExpectation() == null
                    ? null : safeRequest.getExpectation().getComponentId();
            if (!recognizingBoard && expectedId != null
                    && !MAIN_BOARD_COMPONENT_ID.equals(expectedId)
                    && Log.isLoggable(EXPECTED_DIAGNOSTIC_TAG, Log.DEBUG)) {
                expectedDiagnosticComponentId = expectedId;
                expectedDiagnosticPrefix = "frame=" + frameTimestampNanos
                        + " step=" + safeRequest.getAssemblyStepNumber()
                        + " expected=" + expectedId;
                Log.d(EXPECTED_DIAGNOSTIC_TAG, expectedDiagnosticPrefix
                        + " frameKeypoints=" + frameKeypoints.length
                        + " input=" + grayscale.cols() + "x" + grayscale.rows()
                        + " matchingWorkers=" + matchingExecutor.workerCount()
                        + " nativeThreads=" + Core.getNumThreads());
            }
            if (boardDiagnosticPrefix != null) {
                Log.d(BOARD_DIAGNOSTIC_TAG, boardDiagnosticPrefix
                        + " frameKeypoints=" + frameKeypoints.length
                        + " frameDescriptorRows=" + frameDescriptors.rows()
                        + " homography=" + (frameDescriptors.empty()
                        ? "not_attempted_no_frame_descriptors" : "pending"));
            }

            stageStart = SystemClock.elapsedRealtimeNanos();
            List<RawCandidate> rawCandidates = frameDescriptors.empty()
                    ? Collections.emptyList()
                    : matchReferences(frameDescriptors, frameKeypoints, safeRequest, null,
                    ReferenceDatabase.PhotometricVariant.NORMALIZED, rejectionTracker);
            descriptorCandidates.addAll(rawCandidates);
            matchingNanos = SystemClock.elapsedRealtimeNanos() - stageStart;

            stageStart = SystemClock.elapsedRealtimeNanos();
            addSpatialCandidates(rawCandidates, spatialCandidates,
                    grayscale.cols(), grayscale.rows(), rejectionTracker);
            if (recognizingBoard) discardBoardCandidatesWithoutPose(
                    spatialCandidates, safeRequest.getCameraCalibration());
            spatialNanos = SystemClock.elapsedRealtimeNanos() - stageStart;

            // Descriptor-only reporting is a diagnostic output policy, not a photometric
            // matching policy. Keep the inverted-reference retry enabled for the standalone
            // tester as well; otherwise that tester silently receives less lighting coverage
            // than the production assembly recognizer.
            if (!frameDescriptors.empty()) {
                Set<String> invertedFallbackIds = missingPhotometricComponentIds(
                        spatialCandidates, safeRequest);
                // A weak normal-light board homography must not suppress its inverted ORB
                // alternatives. Side-lit, rotated white boards often retain a few matches on
                // one face while the opposite photometric variant has the stronger consensus.
                if (recognizingBoard && !hasStrongBoardCandidate(spatialCandidates)) {
                    invertedFallbackIds.add(MAIN_BOARD_COMPONENT_ID);
                }
                boolean fallbackAll = invertedFallbackIds.isEmpty()
                        && spatialCandidates.isEmpty()
                        && safeRequest.getExpectation() == null
                        && safeRequest.getSpatialAnchorComponentId() == null;
                if (fallbackAll) {
                    stageStart = SystemClock.elapsedRealtimeNanos();
                    List<RawCandidate> invertedCandidates = matchReferences(
                            frameDescriptors, frameKeypoints, safeRequest, null,
                            ReferenceDatabase.PhotometricVariant.INVERTED, rejectionTracker);
                    matchingNanos += SystemClock.elapsedRealtimeNanos() - stageStart;
                    stageStart = SystemClock.elapsedRealtimeNanos();
                    addSpatialCandidates(invertedCandidates, spatialCandidates,
                            grayscale.cols(), grayscale.rows(), rejectionTracker);
                    spatialNanos += SystemClock.elapsedRealtimeNanos() - stageStart;
                } else {
                    for (String componentId : invertedFallbackIds) {
                        stageStart = SystemClock.elapsedRealtimeNanos();
                        List<RawCandidate> invertedCandidates = matchReferences(
                                frameDescriptors, frameKeypoints, safeRequest, componentId,
                                ReferenceDatabase.PhotometricVariant.INVERTED, rejectionTracker);
                        matchingNanos += SystemClock.elapsedRealtimeNanos() - stageStart;
                        stageStart = SystemClock.elapsedRealtimeNanos();
                        addSpatialCandidates(invertedCandidates, spatialCandidates,
                                grayscale.cols(), grayscale.rows(), rejectionTracker);
                        spatialNanos += SystemClock.elapsedRealtimeNanos() - stageStart;
                    }
                }
            }

            // Once the two motors are installed, they obscure much of the pristine full-board
            // references. A normal whole-reference match is still preferred. If it produced no
            // board candidate, retry the board anchor using descriptors whose source
            // locations remain exposed between/above/below the motor housings. Matching and
            // homography thresholds remain unchanged, and distributed inliers are required
            // before the partial evidence is allowed to project the complete board outline.
            if (!quickBoardReferencePass && !frameDescriptors.empty()
                    && requestsExposedBoardAnchorFallback(safeRequest)
                    && !containsComponentCandidate(
                    spatialCandidates, MAIN_BOARD_COMPONENT_ID)) {
                stageStart = SystemClock.elapsedRealtimeNanos();
                List<RawCandidate> exposedBoardCandidates =
                        matchExposedBoardAnchorReferences(
                                frameDescriptors, frameKeypoints, safeRequest,
                                ReferenceDatabase.PhotometricVariant.NORMALIZED);
                matchingNanos += SystemClock.elapsedRealtimeNanos() - stageStart;
                stageStart = SystemClock.elapsedRealtimeNanos();
                addExposedBoardSpatialCandidates(exposedBoardCandidates, spatialCandidates,
                        grayscale.cols(), grayscale.rows(), rejectionTracker);
                spatialNanos += SystemClock.elapsedRealtimeNanos() - stageStart;

                if (!containsComponentCandidate(
                        spatialCandidates, MAIN_BOARD_COMPONENT_ID)) {
                    stageStart = SystemClock.elapsedRealtimeNanos();
                    List<RawCandidate> invertedExposedBoardCandidates =
                            matchExposedBoardAnchorReferences(
                                    frameDescriptors, frameKeypoints, safeRequest,
                                    ReferenceDatabase.PhotometricVariant.INVERTED);
                    matchingNanos += SystemClock.elapsedRealtimeNanos() - stageStart;
                    stageStart = SystemClock.elapsedRealtimeNanos();
                    addExposedBoardSpatialCandidates(
                            invertedExposedBoardCandidates, spatialCandidates,
                            grayscale.cols(), grayscale.rows(), rejectionTracker);
                    spatialNanos += SystemClock.elapsedRealtimeNanos() - stageStart;
                }
            }

            // Assembly-state board captures remain useful under occlusion, but never compete with
            // a successful baseline board match. The fallback considers every authored board
            // milestone in every step; its step number remains provenance and still scopes normal
            // component/anchor passes.
            if (recognizingBoard) discardBoardCandidatesWithoutPose(spatialCandidates,
                    safeRequest.getCameraCalibration());
            if (recognizingBoard && !quickBoardReferencePass && !frameDescriptors.empty()
                    && !containsComponentCandidate(spatialCandidates, MAIN_BOARD_COMPONENT_ID)) {
                for (ReferenceDatabase.PhotometricVariant variant : ReferenceDatabase.PhotometricVariant.values()) {
                    stageStart = SystemClock.elapsedRealtimeNanos();
                    List<RawCandidate> installed = matchReferences(frameDescriptors, frameKeypoints,
                            safeRequest, MAIN_BOARD_COMPONENT_ID, variant, true,
                            rejectionTracker);
                    matchingNanos += SystemClock.elapsedRealtimeNanos() - stageStart;
                    stageStart = SystemClock.elapsedRealtimeNanos();
                    addSpatialCandidates(installed, spatialCandidates, grayscale.cols(),
                            grayscale.rows(), rejectionTracker);
                    discardBoardCandidatesWithoutPose(spatialCandidates,safeRequest.getCameraCalibration());
                    spatialNanos += SystemClock.elapsedRealtimeNanos() - stageStart;
                    if (containsComponentCandidate(spatialCandidates, MAIN_BOARD_COMPONENT_ID)) break;
                }
            }

            if (needsInstalledBracketTileRetry(spatialCandidates, safeRequest)) {
                TileRetryTimings retry = retryInstalledBracketTiles(
                        normalizedFrame, spatialCandidates, safeRequest, rejectionTracker);
                featureNanos += retry.featureNanos;
                matchingNanos += retry.matchingNanos;
                spatialNanos += retry.spatialNanos;
            }

            stageStart = SystemClock.elapsedRealtimeNanos();
            bestCandidate = selectPrimaryCandidate(spatialCandidates, safeRequest);
            boolean identityAmbiguous = bestCandidate == null && !spatialCandidates.isEmpty();
            RawCandidate descriptorIdentity = !identityAmbiguous
                    && safeRequest.isDescriptorOnlyIdentityAllowed()
                    ? selectDescriptorIdentityCandidate(descriptorCandidates, config) : null;
            if (boardDiagnosticPrefix != null) {
                Log.d(BOARD_DIAGNOSTIC_TAG, boardDiagnosticPrefix
                        + " selection boardCandidate=" + containsComponentCandidate(
                        spatialCandidates, MAIN_BOARD_COMPONENT_ID)
                        + " identityAmbiguous=" + identityAmbiguous
                        + " selectedComponent=" + (bestCandidate == null ? "none"
                        : bestCandidate.reference.spec.getComponentId()));
            }
            if (expectedDiagnosticPrefix != null) {
                StringBuilder spatial = new StringBuilder();
                for (SpatialCandidate candidate : spatialCandidates) {
                    spatial.append(' ').append(candidate.reference.spec.getReferenceId())
                            .append('/').append(candidate.inlierCount);
                }
                Log.d(EXPECTED_DIAGNOSTIC_TAG, expectedDiagnosticPrefix
                        + " selection identityAmbiguous=" + identityAmbiguous
                        + " selected=" + (bestCandidate == null ? "none"
                        : bestCandidate.reference.spec.getReferenceId())
                        + " spatialCandidates=[" + spatial.toString().trim() + "]");
            }
            spatialNanos += SystemClock.elapsedRealtimeNanos() - stageStart;

            if (bestCandidate != null) {
                instanceCandidates.add(bestCandidate);
            }
            // Different reviewed faces can already localize different physical
            // instances in this first pass (e.g. matte and reflective brackets).
            // Retain those independently verified, non-overlapping homographies
            // before re-extracting masked frames for additional instances.
            if(isExpectedCandidate(bestCandidate,safeRequest)&&safeRequest.getExpectedInstanceCount()>1){
                for(SpatialCandidate candidate:spatialCandidates){
                    if(instanceCandidates.size()>=safeRequest.getExpectedInstanceCount())break;
                    if(!isExpectedCandidate(candidate,safeRequest)||candidate.confidence<.75)continue;
                    boolean distinct=true;
                    for(SpatialCandidate prior:instanceCandidates)if(!areDistinctInstanceQuads(prior.corners,candidate.corners)){distinct=false;break;}
                    if(distinct)instanceCandidates.add(candidate);
                }
            }
            while (instanceCandidates.size() < safeRequest.getExpectedInstanceCount()
                    && isExpectedCandidate(bestCandidate, safeRequest)) {
                AdditionalDetection additional = detectAdditionalExpectedInstance(
                        normalizedFrame, instanceCandidates, safeRequest);
                featureNanos += additional.featureNanos;
                matchingNanos += additional.matchingNanos;
                spatialNanos += additional.spatialNanos;
                if (additional.candidate != null) {
                    spatialCandidates.add(additional.candidate);
                    instanceCandidates.add(additional.candidate);
                } else break;
            }

            RecognitionResult.Builder resultBuilder =
                    RecognitionResult.builder(frameTimestampNanos);
            CircularFallback fallback = null;
            boolean fallbackAttempted = false;
            if (!identityAmbiguous
                    && shouldAttemptExpectedCircularFallback(instanceCandidates, safeRequest)) {
                stageStart = SystemClock.elapsedRealtimeNanos();
                fallback = detectCircularHardware(grayscale, safeRequest);
                fallbackNanos = SystemClock.elapsedRealtimeNanos() - stageStart;
                fallbackAttempted = true;
            }

            boolean useFallback = fallback != null
                    && fallback.reference != null
                    && (!isExpectedCandidate(bestCandidate, safeRequest)
                    || fallback.selectedCircles.size()
                    >= safeRequest.getExpectedInstanceCount());
            if (useFallback) {
                long populationStart = SystemClock.elapsedRealtimeNanos();
                populateFallbackResult(resultBuilder, fallback, safeRequest,
                        frameKeypoints.length, grayscale.cols(), grayscale.rows());
                populateSpatialAnchor(resultBuilder, spatialCandidates, safeRequest);
                spatialNanos += SystemClock.elapsedRealtimeNanos() - populationStart;
            } else if (bestCandidate != null) {
                long populationStart = SystemClock.elapsedRealtimeNanos();
                populateOrbResult(resultBuilder, bestCandidate, instanceCandidates,
                        safeRequest, frameKeypoints.length);
                populateSpatialAnchor(resultBuilder, spatialCandidates, safeRequest);
                spatialNanos += SystemClock.elapsedRealtimeNanos() - populationStart;
            } else if (identityAmbiguous) {
                // A circle/silhouette must not break a tie between textured identities occupying
                // the same physical target, nor may an ambiguous match emit a placement pose.
                resultBuilder.matches(frameKeypoints.length, 0, 0, 0.0)
                        .method(RecognitionResult.DetectionMethod.NONE)
                        .validation(RecognitionResult.ValidationState.NO_COMPONENT);
            } else if (descriptorIdentity != null) {
                long populationStart = SystemClock.elapsedRealtimeNanos();
                populateDescriptorOnlyResult(resultBuilder, descriptorIdentity,
                        safeRequest, frameKeypoints.length);
                spatialNanos += SystemClock.elapsedRealtimeNanos() - populationStart;
            } else {
                if (!fallbackAttempted) {
                    stageStart = SystemClock.elapsedRealtimeNanos();
                    fallback = detectCircularHardware(grayscale, safeRequest);
                    fallbackNanos = SystemClock.elapsedRealtimeNanos() - stageStart;
                }
                long populationStart = SystemClock.elapsedRealtimeNanos();
                populateFallbackResult(resultBuilder, fallback, safeRequest,
                        frameKeypoints.length, grayscale.cols(), grayscale.rows());
                spatialNanos += SystemClock.elapsedRealtimeNanos() - populationStart;
            }
            long totalNanos = SystemClock.elapsedRealtimeNanos() - totalStart;
            RecognitionResult.Latency latency = new RecognitionResult.Latency(
                    nanosToMs(featureNanos),
                    nanosToMs(matchingNanos),
                    nanosToMs(spatialNanos),
                    nanosToMs(fallbackNanos),
                    nanosToMs(totalNanos));
            RecognitionResult result = resultBuilder.latency(latency).build();
            result = result.withMergedSceneDetections(
                    selectSceneDetections(spatialCandidates));
            if ((result.getComponentId() == null
                    || result.getDetectionMethod()
                    == RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY)
                    && rejectionTracker != null) {
                RecognitionResult.RejectionEvidence evidence =
                        rejectionTracker.toEvidence(identityAmbiguous);
                if (evidence != null) {
                    result = result.withRejectionEvidence(evidence);
                }
            }
            DiagnosticHammingMatcher.snapshot().matchingNs += matchingNanos;
            database.observeIndividualBank(result);
            if(individualFrameReferences!=null&&result.hasLocatedComponent()&&safeRequest.getExpectation().getComponentId().equals(result.getComponentId()))individualWindow.matched(result.getReferenceId());
            logLatency(result);
            return result;
        } catch (RuntimeException failure) {
            if (config.isLatencyLoggingEnabled()) {
                double elapsedMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - totalStart);
                Log.e(TAG, String.format(Locale.US,
                        "frame=%d failed after %.2fms", frameTimestampNanos, elapsedMs), failure);
            }
            throw failure;
        } finally {
            activeFrameDescriptors = null;
            expectedDiagnosticPrefix = null;
            expectedDiagnosticComponentId = null;
            for (SpatialCandidate candidate : spatialCandidates) {
                candidate.release();
            }
            emptyMask.release();
            normalizedFrame.release();
            frameDescriptors.release();
            frameKeypointMat.release();
            if (databaseAcquired) {
                database.releaseAfterRecognition();
            }
        }
    }

    /** A homography that fails calibrated pose validation must not suppress the next board view. */
    private void discardBoardCandidatesWithoutPose(List<SpatialCandidate> candidates,
                                                    CameraCalibration calibration) {
        if(calibration==null) return;
        for(int index=candidates.size()-1;index>=0;index--) {
            SpatialCandidate candidate=candidates.get(index);
            if(MAIN_BOARD_COMPONENT_ID.equals(candidate.reference.spec.getComponentId())
                    && estimatePose(candidate,calibration)==null) {
                if (boardDiagnosticPrefix != null) {
                    Log.d(BOARD_DIAGNOSTIC_TAG, boardDiagnosticPrefix
                            + " reference=" + candidate.reference.spec.getReferenceId()
                            + " poseRejected=" + lastPoseFailure
                            + ("reprojection_error_px".equals(lastPoseFailure)
                            ? String.format(Locale.US, ":%.2f>%.2f", lastPoseErrorPx,
                            config.getMaximumPoseReprojectionErrorPx()) : "")
                            + " inliers=" + candidate.inlierCount
                            + " corners=" + candidate.corners);
                }
                candidates.remove(index);
                candidate.release();
            }
        }
    }

    /**
     * Prefer a geometrically valid observation of the step's expected part. The chassis normally
     * contributes far more keypoints than a small connector or fastener, so a global-only ranking
     * would report the board as a mismatch precisely when the user follows the manuscript and
     * keeps both objects in view. If the expected part is absent, the strongest other observation
     * is retained so the mismatch state still identifies the error.
     */
    private static SpatialCandidate selectPrimaryCandidate(
            List<SpatialCandidate> candidates, RecognitionRequest request) {
        String expectedId = request.getExpectation() == null
                ? null : request.getExpectation().getComponentId();
        SpatialCandidate bestExpected = null;
        SpatialCandidate bestBracketInSlot = null;
        dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate bracketGate =
                request.getGuidedBracketSlot() == 0 || request.getGuidedBracketWorkplane().isEmpty()
                ? null : dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate
                .forMotorBracketSlot(request.getGuidedBracketSlot());
        SpatialCandidate bestOverall = null;
        for (SpatialCandidate candidate : candidates) {
            if (bestOverall == null || candidate.rankingScore > bestOverall.rankingScore) {
                bestOverall = candidate;
            }
            if (expectedId != null
                    && expectedId.equals(candidate.reference.spec.getComponentId())
                    && (bestExpected == null
                    || candidate.rankingScore > bestExpected.rankingScore)) {
                bestExpected = candidate;
            }
            if (bracketGate != null && "motor_bracket".equals(candidate.reference.spec.getComponentId())
                    && bracketGate.evaluateSingle(request.getGuidedBracketWorkplane(),candidate.corners).isAccepted()
                    && (bestBracketInSlot == null || candidate.rankingScore > bestBracketInSlot.rankingScore)) {
                bestBracketInSlot = candidate;
            }
        }
        if (bestBracketInSlot != null) bestExpected = bestBracketInSlot;
        SpatialCandidate selected = bestExpected == null ? bestOverall : bestExpected;
        if (selected == null) return null;

        // Expected-part preference is useful for a small module lying on a large chassis. It must
        // not choose an identity from two similar-size registrations of the *same* object. For
        // example the kit's two PCBs share their perimeter and many drilled holes.
        for (SpatialCandidate candidate : candidates) {
            if (!candidate.reference.spec.getComponentId().equals(
                    selected.reference.spec.getComponentId())
                    && hasConflictingFootprint(selected.corners, candidate.corners)
                    && candidate.rankingScore > selected.rankingScore) {
                selected = candidate;
            }
        }
        for (SpatialCandidate candidate : candidates) {
            if (!candidate.reference.spec.getComponentId().equals(
                    selected.reference.spec.getComponentId())
                    && hasConflictingFootprint(selected.corners, candidate.corners)
                    && !hasDominantIdentityEvidence(selected.inlierCount, candidate.inlierCount)) {
                return null;
            }
        }
        return selected;
    }

    /**
     * HUD-only observations of all independently localized objects in this frame. The primary
     * component and placement gates continue to use their own stricter, step-scoped evidence.
     * Alternate references of one object collapse to their strongest homography; overlapping
     * different identities are omitted unless one has the same dominant inlier evidence that
     * the primary selector requires.
     */
    private static List<RecognitionResult.InstanceObservation> selectSceneDetections(
            List<SpatialCandidate> candidates) {
        List<SpatialCandidate> distinct = new ArrayList<>();
        for (SpatialCandidate candidate : candidates) {
            int duplicateIndex = -1;
            for (int index = 0; index < distinct.size(); index++) {
                SpatialCandidate earlier = distinct.get(index);
                if (candidate.reference.spec.getComponentId().equals(
                        earlier.reference.spec.getComponentId())
                        && !areDistinctInstanceQuads(candidate.corners, earlier.corners)) {
                    duplicateIndex = index;
                    break;
                }
            }
            if (duplicateIndex < 0) {
                distinct.add(candidate);
            } else if (candidate.rankingScore > distinct.get(duplicateIndex).rankingScore) {
                distinct.set(duplicateIndex, candidate);
            }
        }

        List<RecognitionResult.InstanceObservation> observations = new ArrayList<>();
        for (SpatialCandidate candidate : distinct) {
            boolean ambiguousOrWeaker = false;
            for (SpatialCandidate other : distinct) {
                if (candidate == other || candidate.reference.spec.getComponentId().equals(
                        other.reference.spec.getComponentId())
                        || !hasConflictingFootprint(candidate.corners, other.corners)) {
                    continue;
                }
                if (other.rankingScore > candidate.rankingScore
                        || !hasDominantIdentityEvidence(
                        candidate.inlierCount, other.inlierCount)) {
                    ambiguousOrWeaker = true;
                    break;
                }
            }
            if (!ambiguousOrWeaker) {
                observations.add(new RecognitionResult.InstanceObservation(
                        candidate.reference.spec.getReferenceId(),
                        candidate.reference.spec.getComponentId(),
                        RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,
                        candidate.corners, candidate.confidence));
            }
        }
        return observations;
    }

    static boolean hasDominantIdentityEvidence(int winnerInliers, int runnerUpInliers) {
        return winnerInliers >= runnerUpInliers * 1.35
                && winnerInliers - runnerUpInliers >= 8;
    }

    /**
     * Select identity evidence for the standalone parts tester when geometry was rejected. Each
     * component competes using only its strongest reference/photometric variant so components with
     * more registered views do not receive an artificial advantage. A close runner-up is treated
     * as ambiguous rather than being biased toward the requested part.
     */
    private static RawCandidate selectDescriptorIdentityCandidate(
            List<RawCandidate> candidates, VisionConfig config) {
        java.util.Map<String, RawCandidate> bestByComponent = new LinkedHashMap<>();
        for (RawCandidate candidate : candidates) {
            // Without geometry, the tester's six-match floor is too weak for identity: unrelated
            // hardware can share that many binary descriptors. Require ten independent matches
            // and twice the homography consensus before reporting a geometry-free label.
            if (candidate.goodMatches.size() < Math.max(10, config.getMinimumInliers() * 2)) continue;
            String componentId = candidate.reference.spec.getComponentId();
            RawCandidate current = bestByComponent.get(componentId);
            if (current == null
                    || compareDescriptorEvidence(candidate, current, config) > 0) {
                bestByComponent.put(componentId, candidate);
            }
        }
        RawCandidate winner = null;
        RawCandidate runnerUp = null;
        for (RawCandidate candidate : bestByComponent.values()) {
            if (winner == null
                    || compareDescriptorEvidence(candidate, winner, config) > 0) {
                runnerUp = winner;
                winner = candidate;
            } else if (runnerUp == null
                    || compareDescriptorEvidence(candidate, runnerUp, config) > 0) {
                runnerUp = candidate;
            }
        }
        if (winner == null || runnerUp == null) {
            return winner;
        }
        double winnerScore = descriptorEvidenceScore(winner, config);
        double runnerUpScore = descriptorEvidenceScore(runnerUp, config);
        return winnerScore >= runnerUpScore + 1.0
                && winnerScore >= runnerUpScore * 1.05
                ? winner : null;
    }

    private static int compareDescriptorEvidence(
            RawCandidate first, RawCandidate second, VisionConfig config) {
        int scoreComparison = Double.compare(
                descriptorEvidenceScore(first, config),
                descriptorEvidenceScore(second, config));
        if (scoreComparison != 0) {
            return scoreComparison;
        }
        return Double.compare(second.averageDistance, first.averageDistance);
    }

    private static double descriptorEvidenceScore(
            RawCandidate candidate, VisionConfig config) {
        double distanceEvidence = Math.max(0.0,
                config.getMaximumHammingDistance() - candidate.averageDistance);
        return candidate.goodMatches.size()
                + 1.5 * Math.max(0, candidate.homographyInlierCount)
                + 0.25 * distanceEvidence;
    }

    static boolean hasConflictingFootprint(List<VisionPoint> first, List<VisionPoint> second) {
        Bounds a = Bounds.from(first);
        Bounds b = Bounds.from(second);
        if (a == null || b == null) return false;
        double intersectionWidth = Math.max(0.0,
                Math.min(a.maximumX, b.maximumX) - Math.max(a.minimumX, b.minimumX));
        double intersectionHeight = Math.max(0.0,
                Math.min(a.maximumY, b.maximumY) - Math.max(a.minimumY, b.minimumY));
        double intersection = intersectionWidth * intersectionHeight;
        return intersection / (a.area + b.area - intersection) >= 0.60;
    }

    private boolean shouldAttemptExpectedCircularFallback(
            List<SpatialCandidate> candidates, RecognitionRequest request) {
        RecognitionRequest.AssemblyExpectation expectation = request.getExpectation();
        if (expectation == null) {
            return false;
        }
        String expectedId = expectation.getComponentId();
        int expectedCandidateCount = 0;
        for (SpatialCandidate candidate : candidates) {
            if (expectedId.equals(candidate.reference.spec.getComponentId())) {
                expectedCandidateCount++;
            }
        }
        if (expectedCandidateCount >= request.getExpectedInstanceCount()) {
            return false;
        }
        for (ReferenceDatabase.PreparedReference reference : database.preparedReferences()) {
            if (isEligibleCircularFallbackReference(reference.spec, expectedId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExpectedCandidate(
            SpatialCandidate candidate, RecognitionRequest request) {
        RecognitionRequest.AssemblyExpectation expectation = request.getExpectation();
        return candidate != null && expectation != null
                && expectation.getComponentId().equals(
                candidate.reference.spec.getComponentId());
    }

    private void populateSpatialAnchor(
            RecognitionResult.Builder builder,
            List<SpatialCandidate> candidates,
            RecognitionRequest request) {
        String anchorId = request.getSpatialAnchorComponentId();
        if (anchorId == null) {
            return;
        }
        SpatialCandidate bestAnchor = null;
        for (SpatialCandidate candidate : candidates) {
            if (anchorId.equals(candidate.reference.spec.getComponentId())
                    && (bestAnchor == null
                    || candidate.rankingScore > bestAnchor.rankingScore)) {
                bestAnchor = candidate;
            }
        }
        if (bestAnchor == null) {
            return;
        }
        // Anchor identity needs the same independent evidence as the primary part. A valid
        // small component elsewhere in the frame must not let two indistinguishable board
        // registrations silently resolve in favor of the requested chassis.
        for (SpatialCandidate candidate : candidates) {
            if (!anchorId.equals(candidate.reference.spec.getComponentId())
                    && hasConflictingFootprint(bestAnchor.corners, candidate.corners)
                    && !hasDominantIdentityEvidence(
                    bestAnchor.inlierCount, candidate.inlierCount)) {
                return;
            }
        }
        RecognitionResult.Pose anchorPose = estimatePose(
                bestAnchor, request.getCameraCalibration());
        if (anchorPose != null) {
            builder.spatialAnchor(new RecognitionResult.SpatialAnchor(
                    bestAnchor.reference.spec.getReferenceId(),
                    bestAnchor.reference.spec.getComponentId(),
                    bestAnchor.corners,
                    anchorPose));
        }
    }

    @Override
    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            matchingExecutor.close();
            phase2Matcher.close();
            phase3Matcher.close();
            phase11Matcher.close();phase10Matcher.close();phase9Matcher.close();phase8Matcher.close();phase5Matcher.close();phase7Matcher.close();
            descriptorBatches.close();
            frameCache.release();
            clahe.collectGarbage();
            clahe.clear();
            orb.clear();
        }
    }

    private List<RawCandidate> matchReferences(
            Mat frameDescriptors,
            KeyPoint[] frameKeypoints,
            RecognitionRequest request,
            String requiredComponentId,
            ReferenceDatabase.PhotometricVariant photometricVariant,
            ExpectedRejectionTracker rejectionTracker) {
        return matchReferences(frameDescriptors, frameKeypoints, request,
                requiredComponentId, photometricVariant, false, rejectionTracker);
    }

    private List<RawCandidate> matchReferences(
            Mat frameDescriptors,
            KeyPoint[] frameKeypoints,
            RecognitionRequest request,
            String requiredComponentId,
            ReferenceDatabase.PhotometricVariant photometricVariant,
            boolean assemblyReferencesOnly,
            ExpectedRejectionTracker rejectionTracker) {
        return matchReferences(frameDescriptors, frameKeypoints, request, requiredComponentId,
                photometricVariant, assemblyReferencesOnly, false, rejectionTracker);
    }

    private List<RawCandidate> matchReferences(
            Mat frameDescriptors,
            KeyPoint[] frameKeypoints,
            RecognitionRequest request,
            String requiredComponentId,
            ReferenceDatabase.PhotometricVariant photometricVariant,
            boolean assemblyReferencesOnly,
            boolean installedBracketViewsOnly,
            ExpectedRejectionTracker rejectionTracker) {
        List<RawCandidate> candidates = new ArrayList<>();
        if (frameDescriptors == activeFrameDescriptors) {
            precomputeWholeFramePairs(frameDescriptors, request, requiredComponentId,
                    photometricVariant, assemblyReferencesOnly, installedBracketViewsOnly);
        }
        for (ReferenceDatabase.PreparedReference reference : database.preparedReferences()) {
            if(individualFrameReferences!=null&&!individualFrameReferences.contains(reference.spec.getReferenceId()))continue;
            if (quickBoardReferencePass && !reference.spec.isFastAcquisitionView()
                    && !"extension_circuit_board".equals(reference.spec.getComponentId())) continue;
            boolean referenceEligible = assemblyReferencesOnly
                    ? isAssemblyReferenceEligibleForBoardFallback(reference.spec)
                    : isReferenceEligibleForRequest(reference.spec, request);
            if (installedBracketViewsOnly && !reference.spec.isInstalledBracketView()) {
                continue;
            }
            if (!reference.hasOrbData()
                    || (requiredComponentId != null
                    && !requiredComponentId.equals(reference.spec.getComponentId()))
                    || !referenceEligible) {
                continue;
            }
            for (ReferenceDatabase.PreparedOrbVariant variant : reference.orbVariants) {
                if (variant.photometricVariant != photometricVariant
                        || !variant.hasOrbData()) {
                    continue;
                }
                // Query=reference and train=live frame keeps homography pairs
                // reference -> frame. Variants are never combined into one evidence set.
                NearestDescriptorPairs pairs = nearestPairs(variant, frameDescriptors);
                List<DMatch> goodMatches = new ArrayList<>();
                for (int row = 0; row < pairs.size(); row++) {
                    if (!pairs.hasTwo(row)) {
                        continue;
                    }
                    float best = pairs.bestDistance(row);
                    if (best <= config.getMaximumHammingDistance()
                            && best < config.getKnnRatioThreshold() * pairs.secondDistance(row)) {
                        goodMatches.add(pairs.best(row));
                    }
                }
                // Repeated PCB holes, connector pins and fastener edges can map multiple
                // source descriptors to one live feature. They are one observation, for
                // every catalog view, and must not inflate consensus or identity ranking.
                int ratioGoodMatchCount=goodMatches.size();DiagnosticHammingMatcher.record(reference.spec,ratioGoodMatchCount,pairs.size());
                goodMatches = uniqueFrameMatches(goodMatches);
                double distanceSum = 0.0;
                for (DMatch match : goodMatches) distanceSum += match.distance;
                logBoardMatches(reference, variant, "whole", frameKeypoints.length,
                        pairs.size(), goodMatches.size());
                if (rejectionTracker != null) {
                    rejectionTracker.recordMatchAttempt(
                            reference, variant, goodMatches.size());
                }
                if (goodMatches.size() >= config.getMinimumGoodMatches()) {
                    candidates.add(new RawCandidate(reference, variant, frameKeypoints,
                            goodMatches, distanceSum / goodMatches.size(),ratioGoodMatchCount));
                }
            }
        }
        return candidates;
    }

    /**
     * Independent distance batches for eligible, uncached references. Hamming top-two search
     * is independent for each query row. Only these pure native reads run concurrently;
     * cache updates, ratio filtering, homography and identity selection retain catalog order.
     */
    private void precomputeWholeFramePairs(Mat frameDescriptors, RecognitionRequest request,
            String requiredComponentId,
            ReferenceDatabase.PhotometricVariant photometricVariant,
            boolean assemblyReferencesOnly, boolean installedBracketViewsOnly) {
        List<ReferenceDatabase.PreparedOrbVariant> missing = new ArrayList<>();
        for (ReferenceDatabase.PreparedReference reference : database.preparedReferences()) {
            if(individualFrameReferences!=null&&!individualFrameReferences.contains(reference.spec.getReferenceId()))continue;
            if (quickBoardReferencePass && !reference.spec.isFastAcquisitionView()
                    && !"extension_circuit_board".equals(reference.spec.getComponentId())) continue;
            boolean eligible = assemblyReferencesOnly
                    ? isAssemblyReferenceEligibleForBoardFallback(reference.spec)
                    : isReferenceEligibleForRequest(reference.spec, request);
            if (!eligible || !reference.hasOrbData()
                    || (installedBracketViewsOnly && !reference.spec.isInstalledBracketView())
                    || (requiredComponentId != null
                    && !requiredComponentId.equals(reference.spec.getComponentId()))) {
                continue;
            }
            for (ReferenceDatabase.PreparedOrbVariant variant : reference.orbVariants) {
                if (variant.photometricVariant == photometricVariant && variant.hasOrbData()
                        && !frameCache.pairs.containsKey(variant)) {
                    missing.add(variant);
                }
            }
        }
        // A tiny pass costs less as independent calls and uses no temporary concatenation.
        if (missing.size() < 4) return;
        if (matchingExecutor.workerCount() == 1) {
            List<NearestDescriptorPairs> pairs = descriptorBatch(missing).compute(frameDescriptors);
            for (int index = 0; index < missing.size(); index++) {
                frameCache.pairs.put(missing.get(index), pairs.get(index));
            }
            return;
        }
        int remainingRows = 0;
        for (ReferenceDatabase.PreparedOrbVariant variant : missing) {
            remainingRows += variant.descriptors.rows();
        }
        int batchCount = Math.min(matchingExecutor.workerCount(), missing.size());
        List<Callable<List<NearestDescriptorPairs>>> batches = new ArrayList<>(batchCount);
        int first = 0;
        for (int batch = 0; batch < batchCount; batch++) {
            int remainingBatches = batchCount - batch;
            int targetRows = (remainingRows + remainingBatches - 1) / remainingBatches;
            int end = first;
            int rows = 0;
            do {
                rows += missing.get(end++).descriptors.rows();
            } while (rows < targetRows && missing.size() - end >= remainingBatches);
            List<ReferenceDatabase.PreparedOrbVariant> block =
                    new ArrayList<>(missing.subList(first, end));
            PreparedDescriptorBatch descriptorBatch = descriptorBatch(block);
            batches.add(() -> descriptorBatch.compute(frameDescriptors));
            remainingRows -= rows;
            first = end;
        }
        int referenceIndex = 0;
        for (List<NearestDescriptorPairs> batch : matchingExecutor.compute(batches)) {
            for (NearestDescriptorPairs pairs : batch) {
                frameCache.pairs.put(missing.get(referenceIndex++), pairs);
            }
        }
    }

    static List<NearestDescriptorPairs> computeDescriptorBatch(
            List<ReferenceDatabase.PreparedOrbVariant> variants, Mat frameDescriptors) {
        try (PreparedDescriptorBatch batch = new PreparedDescriptorBatch(variants)) {
            return batch.compute(frameDescriptors);
        }
    }

    private PreparedDescriptorBatch descriptorBatch(
            List<ReferenceDatabase.PreparedOrbVariant> variants) {
        // At most four batches are read concurrently. The eight-entry LRU cannot evict
        // a batch created for the call in progress before all its readers have drained.
        return descriptorBatches.get(variants, () -> new PreparedDescriptorBatch(variants));
    }

    /** Whole-frame pairs are reused whenever the full grayscale pixels are unchanged. */
    private NearestDescriptorPairs nearestPairs(ReferenceDatabase.PreparedOrbVariant variant,
                                                Mat frameDescriptors) {
        if (frameDescriptors != activeFrameDescriptors) {
            return NearestDescriptorPairs.compute(variant.descriptors, frameDescriptors);
        }
        NearestDescriptorPairs pairs = frameCache.pairs.get(variant);
        if (pairs == null) {
            pairs = NearestDescriptorPairs.compute(variant.descriptors, frameDescriptors);
            frameCache.pairs.put(variant, pairs);
        }
        return pairs;
    }

    /**
     * CLAHE, ORB and two-nearest-neighbour matching are deterministic for identical pixels, so
     * the analyzer's passes and unchanged subsequent frames share them. A full pixel comparison
     * invalidates the cache on any changed frame, even when its capture timestamp is reused.
     */
    static final class FrameFeatureCache {
        final Mat source = new Mat();
        final Mat normalized = new Mat();
        final Mat descriptors = new Mat();
        final java.util.IdentityHashMap<ReferenceDatabase.PreparedOrbVariant, NearestDescriptorPairs>
                pairs = new java.util.IdentityHashMap<>();
        KeyPoint[] keypoints = new KeyPoint[0];
        boolean valid;
        boolean recovery;

        boolean holds(Mat grayscale) {
            return valid && recovery == DiagnosticHammingMatcher.snapshot().recovery
                    && source.size().equals(grayscale.size()) && source.type() == grayscale.type()
                    && Core.norm(source, grayscale, Core.NORM_INF) == 0.0;
        }

        void store(Mat grayscale, Mat normalizedFrame,
                   Mat frameDescriptors, KeyPoint[] frameKeypoints) {
            grayscale.copyTo(source);
            normalizedFrame.copyTo(normalized);
            frameDescriptors.copyTo(descriptors);
            keypoints = frameKeypoints;
            recovery = DiagnosticHammingMatcher.snapshot().recovery;
            pairs.clear();
            valid = true;
        }

        void release() {
            valid = false;
            pairs.clear();
            keypoints = new KeyPoint[0];
            source.release();
            normalized.release();
            descriptors.release();
        }
    }

    /** Release camera-owned images on the detector worker; reference banks stay warm. */
    public synchronized void releaseCameraFrameCache(){frameCache.release();}

    static List<DMatch> uniqueFrameMatches(List<DMatch> matches) {
        java.util.Map<Integer, DMatch> unique = new LinkedHashMap<>();
        for (DMatch match : matches) {
            DMatch previous = unique.get(match.trainIdx);
            if (previous == null || match.distance < previous.distance) {
                unique.put(match.trainIdx, match);
            }
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * Matches only source descriptors expected to remain visible after both Step-2 motors are
     * installed. This board-only retry is available at every assembly step; it is not a relaxed
     * catalog-wide matcher: repetitive PCB holes and headers are not sufficient identity
     * evidence elsewhere.
     */
    private List<RawCandidate> matchExposedBoardAnchorReferences(
            Mat frameDescriptors,
            KeyPoint[] frameKeypoints,
            RecognitionRequest request,
            ReferenceDatabase.PhotometricVariant photometricVariant) {
        List<RawCandidate> candidates = new ArrayList<>();
        for (ReferenceDatabase.PreparedReference reference : database.preparedReferences()) {
            if (!reference.hasOrbData()
                    || !MAIN_BOARD_COMPONENT_ID.equals(reference.spec.getComponentId())
                    || !isTopFaceReference(reference.spec)
                    || !isReferenceEligibleForRequest(reference.spec, request)) {
                continue;
            }
            Mat referenceToCanonical = referenceToCanonicalTransform(reference);
            try {
                double[] transform = new double[9];
                if (referenceToCanonical.empty()
                        || referenceToCanonical.get(0, 0, transform) <= 0
                        || !allFinite(transform)) {
                    continue;
                }
                for (ReferenceDatabase.PreparedOrbVariant variant : reference.orbVariants) {
                    if (variant.photometricVariant != photometricVariant
                            || !variant.hasOrbData()) {
                        continue;
                    }
                    NearestDescriptorPairs pairs = nearestPairs(variant, frameDescriptors);
                    List<DMatch> goodMatches = new ArrayList<>();
                    double distanceSum = 0.0;
                    for (int row = 0; row < pairs.size(); row++) {
                        if (!pairs.hasTwo(row)) {
                            continue;
                        }
                        float best = pairs.bestDistance(row);
                        if (row >= variant.keypoints.length
                                || best > config.getMaximumHammingDistance()
                                || !(best < config.getKnnRatioThreshold() * pairs.secondDistance(row))) {
                            continue;
                        }
                        Point canonical = project(transform, variant.keypoints[row].pt);
                        if (!isExposedBoardPoint(canonical)) {
                            continue;
                        }
                        goodMatches.add(pairs.best(row));
                        distanceSum += best;
                    }
                    logBoardMatches(reference, variant, "exposed", frameKeypoints.length,
                            pairs.size(), goodMatches.size());
                    if (goodMatches.size() >= config.getMinimumGoodMatches()) {
                        candidates.add(new RawCandidate(reference, variant, frameKeypoints,
                                goodMatches, distanceSum / goodMatches.size()));
                    }
                }
            } finally {
                referenceToCanonical.release();
            }
        }
        return candidates;
    }

    private static boolean requestsExposedBoardAnchorFallback(RecognitionRequest request) {
        RecognitionRequest.AssemblyExpectation expectation = request.getExpectation();
        return expectation != null
                && MAIN_BOARD_COMPONENT_ID.equals(expectation.getComponentId())
                && MAIN_BOARD_COMPONENT_ID.equals(request.getSpatialAnchorComponentId());
    }

    private static boolean isTopFaceReference(ComponentReferenceSpec spec) {
        return signedArea(spec.getPlanarCornersNormalized()) > 0.0;
    }

    private static boolean isExposedBoardPoint(Point point) {
        if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y)
                || point.x < 0.04 || point.x > 0.96
                || point.y < 0.04 || point.y > 0.96) {
            return false;
        }
        return !(point.y >= 0.28 && point.y <= 0.90
                && (point.x <= 0.30 || point.x >= 0.70));
    }

    private static Set<String> missingPhotometricComponentIds(
            List<SpatialCandidate> candidates, RecognitionRequest request) {
        Set<String> missing = new LinkedHashSet<>();
        RecognitionRequest.AssemblyExpectation expectation = request.getExpectation();
        if (expectation != null
                && !containsComponentCandidate(candidates, expectation.getComponentId())) {
            missing.add(expectation.getComponentId());
        }
        String anchorId = request.getSpatialAnchorComponentId();
        if (anchorId != null && !containsComponentCandidate(candidates, anchorId)) {
            missing.add(anchorId);
        }
        return missing;
    }

    private static boolean containsComponentCandidate(
            List<SpatialCandidate> candidates, String componentId) {
        for (SpatialCandidate candidate : candidates) {
            if (componentId.equals(candidate.reference.spec.getComponentId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasStrongBoardCandidate(List<SpatialCandidate> candidates) {
        for (SpatialCandidate candidate : candidates) {
            if (MAIN_BOARD_COMPONENT_ID.equals(candidate.reference.spec.getComponentId())
                    && candidate.inlierCount >= 24 && candidate.inlierRatio >= 0.65) return true;
        }
        return false;
    }

    private void addSpatialCandidates(
            List<RawCandidate> rawCandidates,
            List<SpatialCandidate> candidates,
            int frameWidth,
            int frameHeight,
            ExpectedRejectionTracker rejectionTracker) {
        for (RawCandidate rawCandidate : rawCandidates) {
            SpatialCandidate candidate = estimateHomography(
                    rawCandidate, frameWidth, frameHeight, rejectionTracker);
            if (candidate != null) {
                retainBestLogicalReferenceCandidate(candidates, candidate);
            }
        }
    }

    private void addExposedBoardSpatialCandidates(
            List<RawCandidate> rawCandidates,
            List<SpatialCandidate> candidates,
            int frameWidth,
            int frameHeight,
            ExpectedRejectionTracker rejectionTracker) {
        for (RawCandidate rawCandidate : rawCandidates) {
            SpatialCandidate candidate = estimateHomography(
                    rawCandidate, frameWidth, frameHeight, true, rejectionTracker);
            if (candidate != null) {
                retainBestLogicalReferenceCandidate(candidates, candidate);
            }
        }
    }

    private static void retainBestLogicalReferenceCandidate(
            List<SpatialCandidate> candidates, SpatialCandidate candidate) {
        String referenceId = candidate.reference.spec.getReferenceId();
        for (int index = 0; index < candidates.size(); index++) {
            SpatialCandidate existing = candidates.get(index);
            if (!referenceId.equals(existing.reference.spec.getReferenceId())) {
                continue;
            }
            // One installed-face template can independently identify several bracket instances.
            // Keep separate occurrences so the active slot can choose among real observations.
            if (candidate.reference.spec.isInstalledBracketView()
                    && areDistinctInstanceQuads(existing.corners, candidate.corners)) {
                continue;
            }
            if (candidate.rankingScore > existing.rankingScore) {
                existing.release();
                candidates.set(index, candidate);
            } else {
                candidate.release();
            }
            return;
        }
        candidates.add(candidate);
    }

    private boolean needsInstalledBracketTileRetry(
            List<SpatialCandidate> candidates, RecognitionRequest request) {
        if (request.getGuidedBracketSlot() == 0) return false;
        boolean hasInstalledReference = false;
        for (ReferenceDatabase.PreparedReference reference : database.preparedReferences()) {
            if (reference.spec.isInstalledBracketView() && reference.hasOrbData()
                    && isReferenceEligibleForRequest(reference.spec, request)) {
                hasInstalledReference = true;
                break;
            }
        }
        if (!hasInstalledReference) return false;
        dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate gate =
                request.getGuidedBracketWorkplane().isEmpty() ? null
                        : dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate
                        .forMotorBracketSlot(request.getGuidedBracketSlot());
        for (SpatialCandidate candidate : candidates) {
            if ("motor_bracket".equals(candidate.reference.spec.getComponentId())
                    && (gate == null || gate.evaluateSingle(
                    request.getGuidedBracketWorkplane(), candidate.corners).isAccepted())) {
                return false;
            }
        }
        return true;
    }

    /**
     * A textured PCB can consume the whole-frame ORB feature budget before the dark upright
     * bracket receives enough features. Overlapping tiles allocate a separate budget to each
     * real image region. They retain the original global CLAHE pixels and every normal matcher,
     * distribution and geometry threshold. Only installation-scoped face references participate.
     */
    private TileRetryTimings retryInstalledBracketTiles(
            Mat normalizedFrame, List<SpatialCandidate> candidates, RecognitionRequest request,
            ExpectedRejectionTracker rejectionTracker) {
        long featureNanos = 0L;
        long matchingNanos = 0L;
        long spatialNanos = 0L;
        int tileWidth = Math.max(1, (int) Math.ceil(normalizedFrame.cols() * 0.45));
        int tileHeight = Math.max(1, (int) Math.ceil(normalizedFrame.rows() * 0.45));
        double[] starts = {0.0, 0.275, 0.55};
        List<Mat> tileDescriptorSets = new ArrayList<>();
        List<KeyPoint[]> tileKeypointSets = new ArrayList<>();
        try {
            for (double verticalStart : starts) {
                int top = Math.min(normalizedFrame.rows() - 1,
                        (int) Math.round(normalizedFrame.rows() * verticalStart));
                int bottom = Math.min(normalizedFrame.rows(), top + tileHeight);
                for (double horizontalStart : starts) {
                    int left = Math.min(normalizedFrame.cols() - 1,
                            (int) Math.round(normalizedFrame.cols() * horizontalStart));
                    int right = Math.min(normalizedFrame.cols(), left + tileWidth);
                    Mat tileMask = Mat.zeros(normalizedFrame.rows(),normalizedFrame.cols(),CvType.CV_8UC1);
                    Mat tile = tileMask.submat(top, bottom, left, right);
                    tile.setTo(new Scalar(255));
                    MatOfKeyPoint tileKeypointMat = new MatOfKeyPoint();
                    Mat tileDescriptors = new Mat();
                    try {
                        long stageStart = SystemClock.elapsedRealtimeNanos();
                        // Keep the full-frame ORB pyramid's pixel origin. Cropping before ORB
                        // shifts pyramid sampling and can turn repeated print texture into a
                        // skewed homography, even for the same source photograph.
                        DiagnosticHammingMatcher.extract(orb,normalizedFrame, tileMask, tileKeypointMat, tileDescriptors);
                        featureNanos += SystemClock.elapsedRealtimeNanos() - stageStart;
                        if (tileDescriptors.empty()) {
                            tileDescriptors.release();
                        } else {
                            tileDescriptorSets.add(tileDescriptors);
                            tileKeypointSets.add(tileKeypointMat.toArray());
                        }
                    } finally {
                        tileKeypointMat.release();
                        tile.release();
                        tileMask.release();
                    }
                }
            }
            // Every tile is tried with normal-contrast references before any inverted retry, so
            // a clearly visible installed bracket ends the search after the cheaper pass.
            for (ReferenceDatabase.PhotometricVariant variant
                    : ReferenceDatabase.PhotometricVariant.values()) {
                for (int tileIndex = 0; tileIndex < tileDescriptorSets.size(); tileIndex++) {
                    long stageStart = SystemClock.elapsedRealtimeNanos();
                    // Only installed-view registrations can supply this retry's evidence.
                    List<RawCandidate> installedCandidates = matchReferences(
                            tileDescriptorSets.get(tileIndex), tileKeypointSets.get(tileIndex),
                            request, "motor_bracket", variant, false, true, rejectionTracker);
                    matchingNanos += SystemClock.elapsedRealtimeNanos() - stageStart;
                    stageStart = SystemClock.elapsedRealtimeNanos();
                    addSpatialCandidates(installedCandidates, candidates,
                            normalizedFrame.cols(), normalizedFrame.rows(), rejectionTracker);
                    spatialNanos += SystemClock.elapsedRealtimeNanos() - stageStart;
                    stageStart = SystemClock.elapsedRealtimeNanos();
                    for (SpatialCandidate observed : candidates) {
                        if (observed.reference.spec.isInstalledBracketView()
                                && observed.confidence >= 0.97
                                && observed.inlierCount >= config.getMinimumInliers() * 2
                                && estimatePose(observed,request.getCameraCalibration()) != null
                                && (request.getGuidedBracketWorkplane().isEmpty()
                                || dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate
                                .forMotorBracketSlot(request.getGuidedBracketSlot())
                                .evaluateSingle(request.getGuidedBracketWorkplane(),observed.corners).isAccepted())) {
                            spatialNanos += SystemClock.elapsedRealtimeNanos() - stageStart;
                            return new TileRetryTimings(featureNanos,matchingNanos,spatialNanos);
                        }
                    }
                    spatialNanos += SystemClock.elapsedRealtimeNanos() - stageStart;
                }
            }
            return new TileRetryTimings(featureNanos, matchingNanos, spatialNanos);
        } finally {
            for (Mat tileDescriptors : tileDescriptorSets) tileDescriptors.release();
        }
    }

    /**
     * Masks every already-localized expected-instance quadrilateral, then runs expected-only ORB
     * extraction, match, and homography pass. The original frame and first-pass candidates stay
     * untouched so chassis-anchor recognition remains simultaneous and independent.
     */
    private AdditionalDetection detectAdditionalExpectedInstance(
            Mat normalizedFrame,
            List<SpatialCandidate> observedInstances,
            RecognitionRequest request) {
        Mat exclusionMask = createExclusionMask(
                normalizedFrame.rows(), normalizedFrame.cols(), observedInstances.get(0).corners);
        MatOfKeyPoint keypointMat = new MatOfKeyPoint();
        Mat descriptors = new Mat();
        List<SpatialCandidate> candidates = new ArrayList<>();
        SpatialCandidate selected = null;
        boolean selectedTransferred = false;
        long featureNanos = 0L;
        long matchingNanos = 0L;
        long spatialNanos = 0L;
        try {
            for (int index = 1; index < observedInstances.size(); index++) {
                Mat additionalMask = createExclusionMask(normalizedFrame.rows(),
                        normalizedFrame.cols(), observedInstances.get(index).corners);
                try { Core.bitwise_and(exclusionMask, additionalMask, exclusionMask); }
                finally { additionalMask.release(); }
            }
            long stageStart = SystemClock.elapsedRealtimeNanos();
            DiagnosticHammingMatcher.extract(orb,
                    normalizedFrame, exclusionMask, keypointMat, descriptors);
            KeyPoint[] keypoints = keypointMat.toArray();
            featureNanos = SystemClock.elapsedRealtimeNanos() - stageStart;

            stageStart = SystemClock.elapsedRealtimeNanos();
            String expectedId = request.getExpectation().getComponentId();
            List<RawCandidate> rawCandidates = descriptors.empty()
                    ? Collections.emptyList()
                    : matchReferences(descriptors, keypoints, request, expectedId,
                    ReferenceDatabase.PhotometricVariant.NORMALIZED, null);
            matchingNanos = SystemClock.elapsedRealtimeNanos() - stageStart;

            stageStart = SystemClock.elapsedRealtimeNanos();
            addDistinctSpatialCandidates(rawCandidates, candidates, observedInstances,
                    normalizedFrame.cols(), normalizedFrame.rows());
            spatialNanos = SystemClock.elapsedRealtimeNanos() - stageStart;

            if (candidates.isEmpty() && !descriptors.empty()) {
                stageStart = SystemClock.elapsedRealtimeNanos();
                List<RawCandidate> invertedCandidates = matchReferences(
                        descriptors, keypoints, request, expectedId,
                        ReferenceDatabase.PhotometricVariant.INVERTED, null);
                matchingNanos += SystemClock.elapsedRealtimeNanos() - stageStart;
                stageStart = SystemClock.elapsedRealtimeNanos();
                addDistinctSpatialCandidates(invertedCandidates, candidates, observedInstances,
                        normalizedFrame.cols(), normalizedFrame.rows());
                spatialNanos += SystemClock.elapsedRealtimeNanos() - stageStart;
            }

            for (SpatialCandidate candidate : candidates) {
                if (selected == null || candidate.rankingScore > selected.rankingScore) {
                    selected = candidate;
                }
            }
            selectedTransferred = true;
            return new AdditionalDetection(
                    selected, featureNanos, matchingNanos, spatialNanos);
        } finally {
            for (SpatialCandidate candidate : candidates) {
                if (!selectedTransferred || candidate != selected) {
                    candidate.release();
                }
            }
            descriptors.release();
            keypointMat.release();
            exclusionMask.release();
        }
    }

    private void addDistinctSpatialCandidates(
            List<RawCandidate> rawCandidates,
            List<SpatialCandidate> candidates,
            List<SpatialCandidate> observedInstances,
            int frameWidth,
            int frameHeight) {
        for (RawCandidate rawCandidate : rawCandidates) {
            SpatialCandidate candidate = estimateHomography(
                    rawCandidate, frameWidth, frameHeight, null);
            if (candidate == null) {
                continue;
            }
            boolean distinct = true;
            for (SpatialCandidate prior : observedInstances) {
                if (!areDistinctInstanceQuads(prior.corners, candidate.corners)) {
                    distinct = false;
                    break;
                }
            }
            if (!distinct) {
                candidate.release();
                continue;
            }
            retainBestLogicalReferenceCandidate(candidates, candidate);
        }
    }

    private Mat createExclusionMask(
            int frameRows, int frameColumns, List<VisionPoint> corners) {
        Mat mask = new Mat(
                frameRows, frameColumns, CvType.CV_8UC1, new Scalar(255.0));
        double centerX = 0.0;
        double centerY = 0.0;
        for (VisionPoint corner : corners) {
            centerX += corner.getX();
            centerY += corner.getY();
        }
        centerX /= corners.size();
        centerY /= corners.size();
        double maximumRadius = 1.0;
        for (VisionPoint corner : corners) {
            maximumRadius = Math.max(maximumRadius,
                    Math.hypot(corner.getX() - centerX, corner.getY() - centerY));
        }
        double growth = 1.0 + Math.max(0.12,
                Math.min(0.75, config.getOrbPatchSize() / maximumRadius));
        Point[] expanded = new Point[corners.size()];
        for (int index = 0; index < corners.size(); index++) {
            VisionPoint corner = corners.get(index);
            expanded[index] = new Point(
                    clamp(centerX + (corner.getX() - centerX) * growth,
                            0.0, frameColumns - 1.0),
                    clamp(centerY + (corner.getY() - centerY) * growth,
                            0.0, frameRows - 1.0));
        }
        MatOfPoint polygon = new MatOfPoint(expanded);
        try {
            Imgproc.fillConvexPoly(mask, polygon, new Scalar(0.0));
            return mask;
        } catch (RuntimeException failure) {
            mask.release();
            throw failure;
        } finally {
            polygon.release();
        }
    }

    static boolean areDistinctInstanceQuads(
            List<VisionPoint> first, List<VisionPoint> second) {
        if (first == null || second == null || first.size() != 4 || second.size() != 4) {
            return false;
        }
        Bounds firstBounds = Bounds.from(first);
        Bounds secondBounds = Bounds.from(second);
        if (firstBounds == null || secondBounds == null) {
            return false;
        }
        double intersectionWidth = Math.max(0.0,
                Math.min(firstBounds.maximumX, secondBounds.maximumX)
                        - Math.max(firstBounds.minimumX, secondBounds.minimumX));
        double intersectionHeight = Math.max(0.0,
                Math.min(firstBounds.maximumY, secondBounds.maximumY)
                        - Math.max(firstBounds.minimumY, secondBounds.minimumY));
        double overlapOfSmaller = intersectionWidth * intersectionHeight
                / Math.min(firstBounds.area, secondBounds.area);
        double centerDistance = firstBounds.center.distanceTo(secondBounds.center);
        double minimumLinearSize = Math.sqrt(
                Math.min(firstBounds.area, secondBounds.area));
        return overlapOfSmaller < 0.35
                && centerDistance > 0.35 * minimumLinearSize;
    }

    /**
     * Additional views improve ordinary recognition, but administrator captures and new physical
     * photos may not have verified per-view red/black anchors. Applying another crop's polarity
     * coordinates could validate reversed wiring, so unverified views fail closed on polarity
     * steps while remaining available for ordinary identity and orientation checks.
     */
    static boolean isReferenceEligibleForRequest(
            ComponentReferenceSpec spec, RecognitionRequest request) {
        if(request!=null&&request.isDescriptorOnlyIdentityAllowed()&&!dev.chyken.assembl_ar.camera.UnifiedReferenceCatalog.assembly(spec))return true;
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase2AssetCatalog.PREFIX)) return false;
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase3AssetCatalog.PREFIX)) return false;
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase7AssetCatalog.PREFIX))return false;
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase8AssetCatalog.PREFIX)) return false;
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase9AssetCatalog.PREFIX)) return false;
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase5AssetCatalog.PREFIX)) return false;
        if (spec.isInstalledBracketView()
                && (request == null || request.getGuidedBracketSlot() == 0)) return false;
        if (spec.getAssemblyStepNumber() != 0
                && (request == null || request.getAssemblyStepNumber() != spec.getAssemblyStepNumber())) {
            return false;
        }
        RecognitionRequest.AssemblyExpectation expectation = request == null
                ? null : request.getExpectation();
        boolean polaritySensitiveExpectedComponent = expectation != null
                && expectation.getPolarityRule() != null
                && expectation.getComponentId().equals(spec.getComponentId());
        return !polaritySensitiveExpectedComponent
                || (!spec.isAdministratorReference() && spec.hasVerifiedPolarityAnchors());
    }

    /** Scoped main-board milestones form the board detector's fixed fallback tier. */
    static boolean isAssemblyReferenceEligibleForBoardFallback(ComponentReferenceSpec spec) {
        return spec != null && MAIN_BOARD_COMPONENT_ID.equals(spec.getComponentId())
                && !spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase2AssetCatalog.PREFIX)
                && !spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase3AssetCatalog.PREFIX)
                && !spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase7AssetCatalog.PREFIX)
                && !spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase8AssetCatalog.PREFIX)
                && !spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase9AssetCatalog.PREFIX)
                && !spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase5AssetCatalog.PREFIX)
                && !spec.isAutomaticBoardGeometry()
                && spec.getAssemblyStepNumber() > 0;
    }

    private SpatialCandidate estimateHomography(
            RawCandidate raw, int frameWidth, int frameHeight,
            ExpectedRejectionTracker rejectionTracker) {
        return estimateHomography(raw, frameWidth, frameHeight, false, rejectionTracker);
    }

    private SpatialCandidate estimateHomography(
            RawCandidate raw, int frameWidth, int frameHeight,
            boolean requireDistributedExposedBoardInliers,
            ExpectedRejectionTracker rejectionTracker) {
        List<Point> referencePoints = new ArrayList<>(raw.goodMatches.size());
        List<Point> imagePoints = new ArrayList<>(raw.goodMatches.size());
        for (DMatch match : raw.goodMatches) {
            if (match.queryIdx < 0
                    || match.queryIdx >= raw.referenceVariant.keypoints.length
                    || match.trainIdx < 0
                    || match.trainIdx >= raw.frameKeypoints.length) {
                continue;
            }
            referencePoints.add(raw.referenceVariant.keypoints[match.queryIdx].pt);
            imagePoints.add(raw.frameKeypoints[match.trainIdx].pt);
        }
        if (referencePoints.size() < config.getMinimumGoodMatches()) {
            raw.recordHomographyOutcome(
                    RecognitionResult.RejectionReason.INSUFFICIENT_VALID_MATCHES,
                    referencePoints.size(), -1);
            logBoardHomography(raw, requireDistributedExposedBoardInliers,
                    "not_attempted", "insufficient_valid_matches", referencePoints.size(),
                    -1, null, Double.NaN);
            if (rejectionTracker != null) {
                rejectionTracker.recordHomographyRejection(raw,
                        RecognitionResult.RejectionReason.INSUFFICIENT_VALID_MATCHES,
                        referencePoints.size(), -1);
            }
            return null;
        }

        MatOfPoint2f source = new MatOfPoint2f();
        MatOfPoint2f destination = new MatOfPoint2f();
        Mat inlierMask = new Mat();
        Mat homography = null;
        try {
            source.fromList(referencePoints);
            destination.fromList(imagePoints);
            homography = DiagnosticHammingMatcher.homography(
                    source,
                    destination,
                    Calib3d.RANSAC,
                    config.getHomographyReprojectionThresholdPx(),
                    inlierMask,
                    config.getHomographyMaximumIterations(),
                    config.getHomographyConfidence());
            if (homography.empty() || inlierMask.empty()) {
                raw.recordHomographyOutcome(
                        RecognitionResult.RejectionReason.HOMOGRAPHY_ESTIMATION_FAILED,
                        referencePoints.size(), -1);
                logBoardHomography(raw, requireDistributedExposedBoardInliers,
                        "failed", "empty_homography_or_inlier_mask", referencePoints.size(),
                        -1, null, Double.NaN);
                if (rejectionTracker != null) {
                    rejectionTracker.recordHomographyRejection(raw,
                            RecognitionResult.RejectionReason.HOMOGRAPHY_ESTIMATION_FAILED,
                            referencePoints.size(), -1);
                }
                homography.release();
                homography = null;
                return null;
            }

            int inliers = countInliers(inlierMask);
            double inlierRatio = inliers / (double) referencePoints.size();
            if (!hasSufficientHomographyEvidence(
                    referencePoints.size(), inliers, config)) {
                raw.recordHomographyOutcome(
                        RecognitionResult.RejectionReason.INSUFFICIENT_INLIERS_OR_RATIO,
                        referencePoints.size(), inliers);
                logBoardHomography(raw, requireDistributedExposedBoardInliers,
                        "success", "insufficient_inliers_or_ratio", referencePoints.size(),
                        inliers, null, Double.NaN);
                if (rejectionTracker != null) {
                    rejectionTracker.recordHomographyRejection(raw,
                            RecognitionResult.RejectionReason.INSUFFICIENT_INLIERS_OR_RATIO,
                            referencePoints.size(), inliers);
                }
                homography.release();
                homography = null;
                return null;
            }
            if ((requireDistributedExposedBoardInliers
                    || raw.reference.spec.getAssemblyStepNumber() != 0)
                    && !hasDistributedExposedBoardInliers(
                    raw.reference, referencePoints, inlierMask)) {
                raw.recordHomographyOutcome(
                        RecognitionResult.RejectionReason.INSUFFICIENT_FEATURE_DISTRIBUTION,
                        referencePoints.size(), inliers);
                logBoardHomography(raw, requireDistributedExposedBoardInliers,
                        "success", "insufficient_exposed_distribution", referencePoints.size(),
                        inliers, null, Double.NaN);
                if (rejectionTracker != null) {
                    rejectionTracker.recordHomographyRejection(raw,
                            RecognitionResult.RejectionReason.INSUFFICIENT_FEATURE_DISTRIBUTION,
                            referencePoints.size(), inliers);
                }
                homography.release();
                homography = null;
                return null;
            }

            List<VisionPoint> corners = projectReferenceCorners(raw.reference, homography);
            if (!validProjectedCorners(corners, frameWidth, frameHeight, rejectionTracker)) {
                raw.recordHomographyOutcome(
                        RecognitionResult.RejectionReason.INVALID_PROJECTED_GEOMETRY,
                        referencePoints.size(), inliers);
                logBoardHomography(raw, requireDistributedExposedBoardInliers,
                        "success", "invalid_corners_area_or_bounds", referencePoints.size(),
                        inliers, corners, Double.NaN);
                if (rejectionTracker != null) {
                    rejectionTracker.recordHomographyRejection(raw,
                            RecognitionResult.RejectionReason.INVALID_PROJECTED_GEOMETRY,
                            referencePoints.size(), inliers);
                }
                homography.release();
                homography = null;
                return null;
            }

            PoseInlierSet poseInliers = PoseInlierSet.empty();
            if (usesAssemblyInlierPose(raw.reference.spec)) {
                poseInliers = mapAssemblyPoseInliers(
                        raw.reference, referencePoints, imagePoints, inlierMask);
                if (poseInliers.objectPointsMm.size() < config.getMinimumInliers()
                        || poseInliers.objectPointsMm.size()
                        != poseInliers.imagePoints.size()) {
                    raw.recordHomographyOutcome(
                            RecognitionResult.RejectionReason.INSUFFICIENT_POSE_POINTS,
                            referencePoints.size(), inliers);
                    logBoardHomography(raw, requireDistributedExposedBoardInliers,
                            "success", "insufficient_pose_points", referencePoints.size(),
                            inliers, corners, Double.NaN);
                    if (rejectionTracker != null) {
                        rejectionTracker.recordHomographyRejection(raw,
                                RecognitionResult.RejectionReason.INSUFFICIENT_POSE_POINTS,
                                referencePoints.size(), inliers);
                    }
                    homography.release();
                    homography = null;
                    return null;
                }
            }

            double distanceScore = 1.0 - Math.min(1.0,
                    raw.averageDistance / config.getMaximumHammingDistance());
            double inlierCountScore = Math.min(1.0,
                    inliers / (config.getMinimumInliers() * 2.0));
            double confidence = 0.55 * inlierRatio
                    + 0.25 * inlierCountScore
                    + 0.20 * distanceScore;
            logBoardHomography(raw, requireDistributedExposedBoardInliers,
                    "success", "accepted", referencePoints.size(), inliers, corners, confidence);
            double rankingScore = inliers * 100.0 + inlierRatio * 10.0 + distanceScore;
            if (rejectionTracker != null) {
                rejectionTracker.recordAccepted(raw, referencePoints.size(), inliers);
            }
            raw.recordHomographyAccepted(referencePoints.size(), inliers);
            Mat ownedHomography = homography;
            homography = null;
            SpatialCandidate candidate = new SpatialCandidate(raw.reference, ownedHomography, corners,
                    referencePoints.size(), inliers, inlierRatio,
                    confidence, rankingScore, poseInliers.objectPointsMm,
                    poseInliers.imagePoints);
            candidate.referenceKeypoints=raw.referenceVariant.keypoints.length;candidate.ratioGoodMatchCount=raw.ratioGoodMatchCount;
            return candidate;
        } finally {
            if (homography != null) {
                homography.release();
            }
            inlierMask.release();
            destination.release();
            source.release();
        }
    }

    private static boolean usesAssemblyInlierPose(ComponentReferenceSpec spec) {
        return (spec.getAssemblyStepNumber() > 0 || spec.isPlanarInlierPoseEnabled())
                && MAIN_BOARD_COMPONENT_ID.equals(spec.getComponentId());
    }

    private static PoseInlierSet mapAssemblyPoseInliers(
            ReferenceDatabase.PreparedReference reference,
            List<Point> referencePoints,
            List<Point> imagePoints,
            Mat inlierMask) {
        if (referencePoints.size() != imagePoints.size() || referencePoints.isEmpty()) {
            return PoseInlierSet.empty();
        }
        int maskSize = (int) inlierMask.total();
        if (maskSize <= 0) {
            return PoseInlierSet.empty();
        }
        byte[] mask = new byte[maskSize];
        inlierMask.get(0, 0, mask);
        Mat referenceToCanonical = referenceToCanonicalTransform(reference);
        try {
            double[] transform = new double[9];
            if (referenceToCanonical.empty()
                    || referenceToCanonical.get(0, 0, transform) <= 0
                    || !allFinite(transform)) {
                return PoseInlierSet.empty();
            }
            double width = reference.spec.getPhysicalWidthMm();
            double height = reference.spec.getPhysicalHeightMm();
            List<VisionPoint> objectPointsMm = new ArrayList<>();
            List<VisionPoint> observedImagePoints = new ArrayList<>();
            int count = Math.min(mask.length, referencePoints.size());
            for (int index = 0; index < count; index++) {
                if (mask[index] == 0) {
                    continue;
                }
                Point canonical = project(transform, referencePoints.get(index));
                Point observed = imagePoints.get(index);
                if (canonical == null || observed == null
                        || canonical.x < 0.0 || canonical.x > 1.0
                        || canonical.y < 0.0 || canonical.y > 1.0
                        || !Double.isFinite(observed.x)
                        || !Double.isFinite(observed.y)) {
                    continue;
                }
                // Unit-square image coordinates use +Y down. The physical board plane follows
                // the existing pose convention: origin at center, +X right and +Y up.
                double objectX = (canonical.x - 0.5) * width;
                double objectY = (0.5 - canonical.y) * height;
                if (!Double.isFinite(objectX) || !Double.isFinite(objectY)) {
                    continue;
                }
                objectPointsMm.add(new VisionPoint(
                        objectX, objectY));
                observedImagePoints.add(new VisionPoint(observed.x, observed.y));
            }
            return new PoseInlierSet(objectPointsMm, observedImagePoints);
        } finally {
            referenceToCanonical.release();
        }
    }

    private static boolean hasDistributedExposedBoardInliers(
            ReferenceDatabase.PreparedReference reference,
            List<Point> referencePoints,
            Mat inlierMask) {
        int maskSize = (int) inlierMask.total();
        if (maskSize <= 0 || referencePoints.isEmpty()) {
            return false;
        }
        byte[] mask = new byte[maskSize];
        inlierMask.get(0, 0, mask);
        Mat referenceToCanonical = referenceToCanonicalTransform(reference);
        try {
            double[] transform = new double[9];
            if (referenceToCanonical.empty()
                    || referenceToCanonical.get(0, 0, transform) <= 0
                    || !allFinite(transform)) {
                return false;
            }
            boolean[] cells = new boolean[
                    EXPOSED_BOARD_GRID_SIZE * EXPOSED_BOARD_GRID_SIZE];
            boolean[] rows = new boolean[EXPOSED_BOARD_GRID_SIZE];
            boolean[] columns = new boolean[EXPOSED_BOARD_GRID_SIZE];
            int populatedCells = 0;
            int populatedRows = 0;
            int populatedColumns = 0;
            double minimumX = Double.POSITIVE_INFINITY;
            double maximumX = Double.NEGATIVE_INFINITY;
            double minimumY = Double.POSITIVE_INFINITY;
            double maximumY = Double.NEGATIVE_INFINITY;
            int count = Math.min(referencePoints.size(), mask.length);
            for (int index = 0; index < count; index++) {
                if (mask[index] == 0) {
                    continue;
                }
                Point canonical = project(transform, referencePoints.get(index));
                // Board-only exclusions remove motor-covered PCB regions. An installed bracket
                // has its own authored face mask and must use its complete canonical plane.
                boolean usable = MAIN_BOARD_COMPONENT_ID.equals(reference.spec.getComponentId())
                        ? isExposedBoardPoint(canonical)
                        : canonical != null && Double.isFinite(canonical.x) && Double.isFinite(canonical.y)
                        && canonical.x >= 0.04 && canonical.x <= 0.96
                        && canonical.y >= 0.04 && canonical.y <= 0.96;
                if (!usable) {
                    continue;
                }
                int column = Math.min(EXPOSED_BOARD_GRID_SIZE - 1,
                        Math.max(0, (int) (canonical.x * EXPOSED_BOARD_GRID_SIZE)));
                int row = Math.min(EXPOSED_BOARD_GRID_SIZE - 1,
                        Math.max(0, (int) (canonical.y * EXPOSED_BOARD_GRID_SIZE)));
                int cell = row * EXPOSED_BOARD_GRID_SIZE + column;
                if (!cells[cell]) {
                    cells[cell] = true;
                    populatedCells++;
                }
                if (!rows[row]) {
                    rows[row] = true;
                    populatedRows++;
                }
                if (!columns[column]) {
                    columns[column] = true;
                    populatedColumns++;
                }
                minimumX = Math.min(minimumX, canonical.x);
                maximumX = Math.max(maximumX, canonical.x);
                minimumY = Math.min(minimumY, canonical.y);
                maximumY = Math.max(maximumY, canonical.y);
            }
            return populatedCells >= MINIMUM_EXPOSED_BOARD_CELLS
                    && populatedRows >= 2
                    && populatedColumns >= 2
                    && maximumX - minimumX >= MINIMUM_EXPOSED_BOARD_SPAN
                    && maximumY - minimumY >= MINIMUM_EXPOSED_BOARD_SPAN;
        } finally {
            referenceToCanonical.release();
        }
    }

    private static Mat referenceToCanonicalTransform(
            ReferenceDatabase.PreparedReference reference) {
        List<VisionPoint> normalized = reference.spec.getPlanarCornersNormalized();
        MatOfPoint2f source = new MatOfPoint2f(
                new Point(normalized.get(0).getX() * (reference.imageWidth - 1.0),
                        normalized.get(0).getY() * (reference.imageHeight - 1.0)),
                new Point(normalized.get(1).getX() * (reference.imageWidth - 1.0),
                        normalized.get(1).getY() * (reference.imageHeight - 1.0)),
                new Point(normalized.get(2).getX() * (reference.imageWidth - 1.0),
                        normalized.get(2).getY() * (reference.imageHeight - 1.0)),
                new Point(normalized.get(3).getX() * (reference.imageWidth - 1.0),
                        normalized.get(3).getY() * (reference.imageHeight - 1.0)));
        MatOfPoint2f destination = new MatOfPoint2f(
                new Point(0.0, 0.0), new Point(1.0, 0.0),
                new Point(1.0, 1.0), new Point(0.0, 1.0));
        try {
            return Imgproc.getPerspectiveTransform(source, destination);
        } finally {
            destination.release();
            source.release();
        }
    }

    private static Point project(double[] transform, Point point) {
        if (transform == null || transform.length != 9 || point == null) {
            return null;
        }
        double denominator = transform[6] * point.x
                + transform[7] * point.y + transform[8];
        if (!Double.isFinite(denominator) || Math.abs(denominator) < 1e-12) {
            return null;
        }
        double x = (transform[0] * point.x
                + transform[1] * point.y + transform[2]) / denominator;
        double y = (transform[3] * point.x
                + transform[4] * point.y + transform[5]) / denominator;
        return Double.isFinite(x) && Double.isFinite(y) ? new Point(x, y) : null;
    }

    /**
     * Repetitive targets such as perforated PCBs can produce many legitimate-but-ambiguous ratio
     * matches. That lowers the inlier fraction even when dozens of independent points agree on
     * one homography. Keep the normal ratio gate for small evidence sets, but accept a slightly
     * lower ratio when the absolute consensus is four times stronger than the configured minimum.
     * A hard relaxed floor prevents a large collection of unrelated matches from bypassing the
     * geometric-quality gate.
     */
    static boolean hasSufficientHomographyEvidence(
            int goodMatchCount, int inlierCount, VisionConfig config) {
        if (config == null || goodMatchCount <= 0 || inlierCount < 0
                || inlierCount > goodMatchCount
                || inlierCount < config.getMinimumInliers()) {
            return false;
        }
        double inlierRatio = inlierCount / (double) goodMatchCount;
        if (inlierRatio >= config.getMinimumInlierRatio()) {
            return true;
        }
        int strongConsensusCount = Math.max(
                config.getMinimumInliers() * 4,
                config.getMinimumGoodMatches() * 2);
        double strongConsensusRatioFloor = Math.max(
                0.35, config.getMinimumInlierRatio() - 0.10);
        return inlierCount >= strongConsensusCount
                && inlierRatio >= strongConsensusRatioFloor;
    }

    private void populateOrbResult(RecognitionResult.Builder builder,
                                   SpatialCandidate candidate,
                                   List<SpatialCandidate> instanceCandidates,
                                   RecognitionRequest request,
                                   int frameKeypointCount) {
        ComponentReferenceSpec spec = candidate.reference.spec;
        OrientationEstimate orientation = classifyOrientation(candidate.corners, request);
        RecognitionRequest.AssemblyExpectation expectation = request.getExpectation();
        boolean isExpectedIdentity = expectation == null
                || expectation.getComponentId().equals(spec.getComponentId());
        RecognitionResult.PolarityState polarity = isExpectedIdentity
                ? validatePolarity(candidate, request)
                : expectation.getPolarityRule() == null
                ? RecognitionResult.PolarityState.NOT_REQUIRED
                : RecognitionResult.PolarityState.UNCONFIRMED;
        RecognitionResult.ValidationState validation = validateExpectedComponent(
                spec.getComponentId(), orientation.matchesExpectation, polarity, request);
        RecognitionResult.Pose pose = estimatePose(candidate, request.getCameraCalibration());
        List<RecognitionResult.InstanceObservation> observations = new ArrayList<>();
        for (SpatialCandidate instance : instanceCandidates) {
            ComponentReferenceSpec instanceSpec = instance.reference.spec;
            if (spec.getComponentId().equals(instanceSpec.getComponentId())) {
                observations.add(new RecognitionResult.InstanceObservation(
                        instanceSpec.getReferenceId(),
                        instanceSpec.getComponentId(),
                        RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,
                        instance.corners,
                        instance.confidence));
            }
        }

        builder.identity(spec.getReferenceId(), spec.getComponentId(), spec.getDisplayName())
                .method(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY)
                .validation(validation)
                .orientation(orientation.classification, orientation.degrees)
                .polarity(polarity)
                .orbMetrics(candidate.referenceKeypoints,candidate.referenceKeypoints,candidate.ratioGoodMatchCount)
                .matches(frameKeypointCount, candidate.goodMatchCount,
                        candidate.inlierCount, candidate.inlierRatio)
                .confidence(candidate.confidence)
                .corners(candidate.corners)
                .instances(observations)
                .pose(pose);
    }

    /**
     * Report a test-only identity observation without inventing corners, orientation, or pose.
     * Production assembly requests never enable this path and therefore retain the full homography
     * and placement gates.
     */
    private void populateDescriptorOnlyResult(RecognitionResult.Builder builder,
                                              RawCandidate candidate,
                                              RecognitionRequest request,
                                              int frameKeypointCount) {
        ComponentReferenceSpec spec = candidate.reference.spec;
        RecognitionRequest.AssemblyExpectation expectation = request.getExpectation();
        boolean expectedIdentity = expectation == null
                || expectation.getComponentId().equals(spec.getComponentId());
        boolean orientationRequired = expectedIdentity && expectation != null
                && expectation.getOrientationDegrees() != null;
        boolean polarityRequired = expectedIdentity && expectation != null
                && expectation.getPolarityRule() != null;
        RecognitionResult.PolarityState polarity = polarityRequired
                ? RecognitionResult.PolarityState.UNCONFIRMED
                : RecognitionResult.PolarityState.NOT_REQUIRED;
        RecognitionResult.ValidationState validation = validateExpectedComponent(
                spec.getComponentId(), orientationRequired ? null : Boolean.TRUE,
                polarity, request);
        double distanceScore = 1.0 - Math.min(1.0,
                candidate.averageDistance / config.getMaximumHammingDistance());
        double matchScore = Math.min(1.0, candidate.goodMatches.size()
                / (config.getMinimumGoodMatches() * 2.0));
        double confidence = Math.min(0.65,
                0.25 + 0.25 * matchScore + 0.15 * distanceScore);

        builder.identity(spec.getReferenceId(), spec.getComponentId(), spec.getDisplayName())
                .method(RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY)
                .validation(validation)
                .orientation(RecognitionResult.OrientationClassification.UNKNOWN, Double.NaN)
                .polarity(polarity)
                .orbMetrics(candidate.referenceVariant.keypoints.length,candidate.referenceVariant.keypoints.length,candidate.ratioGoodMatchCount)
                .matches(frameKeypointCount, candidate.goodMatches.size(), 0, 0.0)
                .confidence(confidence);
    }

    private CircularFallback detectCircularHardware(Mat grayscale, RecognitionRequest request) {
        Mat blurred = new Mat();
        Mat edges = new Mat();
        Mat circleMatrix = new Mat();
        try {
            int kernel = config.getHoughGaussianKernelSize();
            Imgproc.GaussianBlur(grayscale, blurred, new Size(kernel, kernel), 0.0);
            Imgproc.Canny(blurred, edges,
                    config.getCannyLowThreshold(), config.getCannyHighThreshold());
            int[] radiusBounds = houghRadiusBounds(
                    Math.min(grayscale.cols(), grayscale.rows()), request);
            Imgproc.HoughCircles(
                    blurred,
                    circleMatrix,
                    Imgproc.HOUGH_GRADIENT,
                    config.getHoughDp(),
                    config.getHoughMinimumDistancePx(),
                    config.getCannyHighThreshold(),
                    config.getHoughAccumulatorThreshold(),
                    radiusBounds[0],
                    radiusBounds[1]);

            List<ScoredCircle> scored = new ArrayList<>();
            if (!circleMatrix.empty()) {
                for (int column = 0; column < circleMatrix.cols(); column++) {
                    double[] circle = circleMatrix.get(0, column);
                    if (circle == null || circle.length < 3 || !(circle[2] > 0.0)) {
                        continue;
                    }
                    double edgeSupport = circleEdgeSupport(
                            edges, circle[0], circle[1], circle[2]);
                    if (edgeSupport >= MINIMUM_CIRCLE_EDGE_SUPPORT) {
                        scored.add(new ScoredCircle(new RecognitionResult.DetectedCircle(
                                new VisionPoint(circle[0], circle[1]), circle[2]), edgeSupport));
                    }
                }
            }

            if (scored.size() < config.getMinimumFallbackCircles()) {
                return new CircularFallback(
                        null, Collections.emptyList(), Collections.emptyList(), 0.0);
            }
            List<RecognitionResult.DetectedCircle> circles = new ArrayList<>(scored.size());
            double supportSum = 0.0;
            for (ScoredCircle circle : scored) {
                circles.add(circle.circle);
                supportSum += circle.edgeSupport;
            }
            CircularSelection selection = chooseCircularReference(
                    circles, grayscale.cols(), grayscale.rows(), request);
            double confidence = Math.min(0.80,
                    0.35 + 0.08 * circles.size() + 0.35 * (supportSum / scored.size()));
            return new CircularFallback(
                    selection == null ? null : selection.reference,
                    selection == null ? Collections.emptyList() : selection.circles,
                    circles,
                    confidence);
        } finally {
            circleMatrix.release();
            edges.release();
            blurred.release();
        }
    }

    /** Keeps configured Hough limits wide enough for the active component's authored ratio band. */
    private int[] houghRadiusBounds(int frameMinimumDimension, RecognitionRequest request) {
        String expectedId = request.getExpectation() == null
                ? null : request.getExpectation().getComponentId();
        double minimumRatio = Double.POSITIVE_INFINITY;
        double maximumRatio = 0.0;
        for (ReferenceDatabase.PreparedReference reference : database.preparedReferences()) {
            ComponentReferenceSpec spec = reference.spec;
            if (!isEligibleCircularFallbackReference(spec, expectedId)) {
                continue;
            }
            minimumRatio = Math.min(minimumRatio, spec.getMinimumCircleRadiusRatio());
            maximumRatio = Math.max(maximumRatio, spec.getMaximumCircleRadiusRatio());
        }
        int minimum = config.getHoughMinimumRadiusPx();
        int maximum = config.getHoughMaximumRadiusPx();
        if (Double.isFinite(minimumRatio) && maximumRatio > minimumRatio) {
            minimum = Math.max(1, Math.min(minimum,
                    (int) Math.floor(frameMinimumDimension * minimumRatio * 0.90)));
            maximum = Math.max(maximum,
                    (int) Math.ceil(frameMinimumDimension * maximumRatio * 1.08));
        }
        if (maximum <= minimum) {
            maximum = minimum + 1;
        }
        return new int[]{minimum, maximum};
    }

    private CircularSelection chooseCircularReference(
            List<RecognitionResult.DetectedCircle> circles,
            int frameWidth, int frameHeight,
            RecognitionRequest request) {
        double frameMinimumDimension = Math.min(frameWidth, frameHeight);
        String expectedComponentId = request.getExpectation() == null
                ? null : request.getExpectation().getComponentId();
        int desiredCount = request.getExpectedInstanceCount();
        ReferenceDatabase.PreparedReference best = null;
        List<RecognitionResult.DetectedCircle> bestCircles = Collections.emptyList();
        double bestAverageRadiusFit = Double.POSITIVE_INFINITY;
        for (ReferenceDatabase.PreparedReference reference : database.preparedReferences()) {
            ComponentReferenceSpec spec = reference.spec;
            if (!isEligibleCircularFallbackReference(spec, expectedComponentId)) {
                continue;
            }
            double center = (spec.getMinimumCircleRadiusRatio()
                    + spec.getMaximumCircleRadiusRatio()) * 0.5;
            double width = spec.getMaximumCircleRadiusRatio()
                    - spec.getMinimumCircleRadiusRatio();
            List<CircleFit> fits = new ArrayList<>();
            for (RecognitionResult.DetectedCircle circle : circles) {
                double ratio = circle.getRadiusPx() / frameMinimumDimension;
                if (!spec.acceptsCircleRadiusRatio(ratio)) {
                    continue;
                }
                double normalizedFit = Math.abs(ratio - center) / Math.max(width, 1e-9);
                fits.add(new CircleFit(circle, normalizedFit));
            }
            fits.sort(Comparator.comparingDouble(fit -> fit.normalizedRadiusFit));
            List<RecognitionResult.DetectedCircle> ordered = new ArrayList<>(fits.size());
            for (CircleFit fit : fits) {
                ordered.add(fit.circle);
            }
            List<RecognitionResult.DetectedCircle> selected =
                    selectDistinctCircles(ordered, desiredCount);
            if (selected.isEmpty()) {
                continue;
            }
            double fitSum = 0.0;
            for (RecognitionResult.DetectedCircle selectedCircle : selected) {
                double ratio = selectedCircle.getRadiusPx() / frameMinimumDimension;
                fitSum += Math.abs(ratio - center) / Math.max(width, 1e-9);
            }
            double averageFit = fitSum / selected.size();
            if (selected.size() > bestCircles.size()
                    || (selected.size() == bestCircles.size()
                    && averageFit < bestAverageRadiusFit)) {
                best = reference;
                bestCircles = selected;
                bestAverageRadiusFit = averageFit;
            }
        }

        return best == null ? null : new CircularSelection(best, bestCircles);
    }

    /** Removes concentric/overlapping Hough rings while preserving radius-fit order. */
    static List<RecognitionResult.DetectedCircle> selectDistinctCircles(
            List<RecognitionResult.DetectedCircle> orderedCircles, int maximumCount) {
        if (orderedCircles == null || maximumCount <= 0) {
            return Collections.emptyList();
        }
        List<RecognitionResult.DetectedCircle> selected = new ArrayList<>(maximumCount);
        for (RecognitionResult.DetectedCircle candidate : orderedCircles) {
            if (candidate == null) {
                continue;
            }
            boolean distinct = true;
            for (RecognitionResult.DetectedCircle existing : selected) {
                double minimumSeparation = 0.60
                        * (candidate.getRadiusPx() + existing.getRadiusPx());
                if (candidate.getCenter().distanceTo(existing.getCenter())
                        <= minimumSeparation) {
                    distinct = false;
                    break;
                }
            }
            if (distinct) {
                selected.add(candidate);
                if (selected.size() == maximumCount) {
                    break;
                }
            }
        }
        return Collections.unmodifiableList(selected);
    }

    /**
     * A Hough circle carries radius evidence, not enough identity evidence to report a different
     * catalog part during an assembly step. Constrained requests therefore consider only
     * references for the expected component. Unconstrained catalog recognition keeps the normal
     * best-radius selection behavior.
     */
    static boolean isEligibleCircularFallbackReference(
            ComponentReferenceSpec spec, String expectedComponentId) {
        return spec.getShapeHint() == ComponentReferenceSpec.ShapeHint.CIRCULAR_HARDWARE
                && (expectedComponentId == null
                || expectedComponentId.equals(spec.getComponentId()));
    }

    private void populateFallbackResult(RecognitionResult.Builder builder,
                                        CircularFallback fallback,
                                        RecognitionRequest request,
                                        int frameKeypointCount,
                                        int frameWidth,
                                        int frameHeight) {
        builder.matches(frameKeypointCount, 0, 0, 0.0)
                .circles(fallback.circles)
                .confidence(fallback.confidence);
        if (fallback.circles.isEmpty()) {
            builder.method(RecognitionResult.DetectionMethod.NONE)
                    .validation(RecognitionResult.ValidationState.NO_COMPONENT);
            return;
        }

        builder.method(RecognitionResult.DetectionMethod.CANNY_HOUGH_FALLBACK);
        if (fallback.reference == null || fallback.selectedCircles.isEmpty()) {
            builder.validation(RecognitionResult.ValidationState.NO_COMPONENT);
            return;
        }

        ComponentReferenceSpec spec = fallback.reference.spec;
        RecognitionRequest.AssemblyExpectation expectation = request.getExpectation();
        boolean orientationRequired = expectation != null
                && expectation.getOrientationDegrees() != null;
        boolean polarityRequired = expectation != null
                && expectation.getPolarityRule() != null;
        RecognitionResult.PolarityState polarity = polarityRequired
                ? RecognitionResult.PolarityState.UNCONFIRMED
                : RecognitionResult.PolarityState.NOT_REQUIRED;
        if (!isCircularPlacementEvidenceSufficient(expectation)) {
            RecognitionResult.ValidationState validation = validateExpectedComponent(
                    spec.getComponentId(), orientationRequired ? null : Boolean.TRUE,
                    polarity, request);
            builder.identity(spec.getReferenceId(), spec.getComponentId(), spec.getDisplayName())
                    .validation(validation)
                    .polarity(polarity)
                    .orientation(
                            RecognitionResult.OrientationClassification.UNKNOWN, Double.NaN);
            return;
        }

        List<CircularGeometry> geometries = new ArrayList<>();
        List<RecognitionResult.InstanceObservation> observations = new ArrayList<>();
        for (RecognitionResult.DetectedCircle circle : fallback.selectedCircles) {
            CircularGeometry candidateGeometry = estimateCircularGeometry(
                    spec, circle, request.getCameraCalibration(), frameWidth, frameHeight);
            if (candidateGeometry != null && validCircularProjectedCorners(
                    candidateGeometry.corners, frameWidth, frameHeight)) {
                geometries.add(candidateGeometry);
                observations.add(new RecognitionResult.InstanceObservation(
                        spec.getReferenceId(),
                        spec.getComponentId(),
                        RecognitionResult.DetectionMethod.CANNY_HOUGH_FALLBACK,
                        candidateGeometry.corners,
                        fallback.confidence));
            }
        }
        if (geometries.isEmpty()) {
            // Radius alone is insufficient to approve placement without trustworthy intrinsics.
            builder.validation(RecognitionResult.ValidationState.NO_COMPONENT);
            return;
        }
        CircularGeometry geometry = geometries.get(0);

        RecognitionResult.ValidationState validation = validateExpectedComponent(
                spec.getComponentId(), Boolean.TRUE,
                RecognitionResult.PolarityState.NOT_REQUIRED, request);
        builder.identity(spec.getReferenceId(), spec.getComponentId(), spec.getDisplayName())
                .validation(validation)
                .polarity(RecognitionResult.PolarityState.NOT_REQUIRED)
                .orientation(RecognitionResult.OrientationClassification.UNKNOWN, Double.NaN)
                .corners(geometry.corners)
                .instances(observations)
                .pose(geometry.pose);
    }

    static boolean isCircularPlacementEvidenceSufficient(
            RecognitionRequest.AssemblyExpectation expectation) {
        return expectation == null
                || (expectation.getOrientationDegrees() == null
                && expectation.getPolarityRule() == null);
    }

    /**
     * Builds a deliberately fronto-parallel pose for an orientation-invariant circular target.
     * The Hough radius is treated as the projection of half the component's smaller registered
     * physical dimension. Non-zero distortion is rejected because this closed-form estimate is a
     * pinhole approximation, not a substitute for a calibrated distorted solvePnP observation.
     */
    static CircularGeometry estimateCircularGeometry(
            ComponentReferenceSpec spec,
            RecognitionResult.DetectedCircle circle,
            CameraCalibration calibration,
            int frameWidth,
            int frameHeight) {
        if (spec == null || circle == null || calibration == null
                || frameWidth <= 0 || frameHeight <= 0) {
            return null;
        }
        double[] distortion = calibration.getDistortionCoefficients();
        for (double coefficient : distortion) {
            if (Math.abs(coefficient) > 1e-12) {
                return null;
            }
        }
        double[] matrix = calibration.getCameraMatrixRowMajor();
        double focalX = matrix[0];
        double skew = matrix[1];
        double focalY = matrix[4];
        double principalX = matrix[2];
        double principalY = matrix[5];
        if (!(focalX > 0.0) || !(focalY > 0.0)
                || principalX < 0.0 || principalX > frameWidth - 1.0
                || principalY < 0.0 || principalY > frameHeight - 1.0) {
            return null;
        }

        VisionPoint center = circle.getCenter();
        double radiusPx = circle.getRadiusPx();
        if (center.getX() < 0.0 || center.getX() > frameWidth - 1.0
                || center.getY() < 0.0 || center.getY() > frameHeight - 1.0
                || !(radiusPx > 0.0)) {
            return null;
        }
        double physicalRadiusMm = Math.min(
                spec.getPhysicalWidthMm(), spec.getPhysicalHeightMm()) * 0.5;
        double focalForRadius = Math.sqrt(focalX) * Math.sqrt(focalY);
        double depth = focalForRadius * physicalRadiusMm / radiusPx;
        if (!(depth > 0.0) || !Double.isFinite(depth)) {
            return null;
        }

        double normalizedY = (center.getY() - principalY) / focalY;
        double translationX = (center.getX() - principalX - skew * normalizedY)
                * depth / focalX;
        double translationY = normalizedY * depth;
        double halfWidthPx = focalX * spec.getPhysicalWidthMm() * 0.5 / depth;
        double halfHeightPx = focalY * spec.getPhysicalHeightMm() * 0.5 / depth;
        double halfHeightSkewPx = skew * spec.getPhysicalHeightMm() * 0.5 / depth;
        double[] translation = {translationX, translationY, depth};
        if (!(halfWidthPx > 0.0) || !(halfHeightPx > 0.0)
                || !Double.isFinite(halfWidthPx) || !Double.isFinite(halfHeightPx)
                || !Double.isFinite(halfHeightSkewPx)
                || !allFinite(translation) || !allFloatRepresentable(translation)) {
            return null;
        }

        List<VisionPoint> corners = new ArrayList<>(4);
        corners.add(new VisionPoint(
                center.getX() - halfWidthPx - halfHeightSkewPx,
                center.getY() - halfHeightPx));
        corners.add(new VisionPoint(
                center.getX() + halfWidthPx - halfHeightSkewPx,
                center.getY() - halfHeightPx));
        corners.add(new VisionPoint(
                center.getX() + halfWidthPx + halfHeightSkewPx,
                center.getY() + halfHeightPx));
        corners.add(new VisionPoint(
                center.getX() - halfWidthPx + halfHeightSkewPx,
                center.getY() + halfHeightPx));
        double[] rotation = {
                1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 1.0
        };
        RecognitionResult.Pose pose = new RecognitionResult.Pose(
                new double[]{0.0, 0.0, 0.0}, translation,
                toOpenGlModelView(rotation, translation));
        return new CircularGeometry(corners, pose);
    }

    private boolean validCircularProjectedCorners(
            List<VisionPoint> corners, int frameWidth, int frameHeight) {
        if (corners == null || corners.size() != 4 || !isConvex(corners)) {
            return false;
        }
        double areaRatio = Math.abs(signedArea(corners))
                / (frameWidth * (double) frameHeight);
        if (!(areaRatio > 0.0) || areaRatio > config.getMaximumCornerAreaRatio()) {
            return false;
        }
        for (VisionPoint point : corners) {
            if (point.getX() < 0.0 || point.getX() > frameWidth - 1.0
                    || point.getY() < 0.0 || point.getY() > frameHeight - 1.0) {
                return false;
            }
        }
        return true;
    }

    private RecognitionResult.Pose estimatePose(SpatialCandidate candidate,
                                                 CameraCalibration calibration) {
        lastPoseFailure = "no_calibration";
        if (calibration == null) {
            return null;
        }
        ComponentReferenceSpec spec = candidate.reference.spec;
        double halfWidth = spec.getPhysicalWidthMm() * 0.5;
        double halfHeight = spec.getPhysicalHeightMm() * 0.5;
        List<VisionPoint> planarObjectPointsMm;
        List<VisionPoint> observedFramePoints;
        if (usesAssemblyInlierPose(spec)) {
            planarObjectPointsMm = candidate.poseObjectPointsMm;
            observedFramePoints = candidate.poseImagePoints;
            if (planarObjectPointsMm.size() < config.getMinimumInliers()
                    || planarObjectPointsMm.size() != observedFramePoints.size()) {
                lastPoseFailure = "too_few_pose_points:" + planarObjectPointsMm.size();
                return null;
            }
        } else {
            // Conventional model coordinates: +X right, +Y up, +Z out of the face.
            planarObjectPointsMm = new ArrayList<>(4);
            planarObjectPointsMm.add(new VisionPoint(-halfWidth, halfHeight));
            planarObjectPointsMm.add(new VisionPoint(halfWidth, halfHeight));
            planarObjectPointsMm.add(new VisionPoint(halfWidth, -halfHeight));
            planarObjectPointsMm.add(new VisionPoint(-halfWidth, -halfHeight));
            observedFramePoints = candidate.corners;
        }

        Point3[] objectPointValues = new Point3[planarObjectPointsMm.size()];
        Point[] imagePointValues = new Point[observedFramePoints.size()];
        List<VisionPoint> deskewedPoints = new ArrayList<>(observedFramePoints.size());
        for (int index = 0; index < planarObjectPointsMm.size(); index++) {
            VisionPoint objectPoint = planarObjectPointsMm.get(index);
            VisionPoint imagePoint = calibration.deskew(observedFramePoints.get(index));
            objectPointValues[index] = new Point3(
                    objectPoint.getX(), objectPoint.getY(), 0.0);
            imagePointValues[index] = toPoint(imagePoint);
            deskewedPoints.add(imagePoint);
        }
        MatOfPoint3f objectPoints = new MatOfPoint3f(objectPointValues);
        MatOfPoint2f imagePoints = new MatOfPoint2f(imagePointValues);
        Mat cameraMatrix = calibration.toOpenCvCameraMatrix();
        MatOfDouble distortion = calibration.toDistortionMatrix();
        Mat rotationVector = new Mat();
        Mat translationVector = new Mat();
        Mat rotationMatrix = new Mat();
        MatOfPoint2f reprojectedPoints = new MatOfPoint2f();
        try {
            boolean solved = Calib3d.solvePnP(
                    objectPoints,
                    imagePoints,
                    cameraMatrix,
                    distortion,
                    rotationVector,
                    translationVector,
                    false,
                    Calib3d.SOLVEPNP_ITERATIVE);
            if (!solved || rotationVector.empty() || translationVector.empty()) {
                lastPoseFailure = "solvepnp_failed";
                return null;
            }
            Calib3d.Rodrigues(rotationVector, rotationMatrix);
            double[] rvec = new double[3];
            double[] tvec = new double[3];
            double[] rotation = new double[9];
            rotationVector.get(0, 0, rvec);
            translationVector.get(0, 0, tvec);
            rotationMatrix.get(0, 0, rotation);
            if (!allFinite(rvec) || !allFinite(tvec) || !allFinite(rotation)
                    || !allFloatRepresentable(tvec)
                    || !hasPositiveDepth(rotation, tvec, halfWidth, halfHeight)) {
                lastPoseFailure = "non_finite_or_behind_camera";
                return null;
            }
            Calib3d.projectPoints(objectPoints, rotationVector, translationVector,
                    cameraMatrix, distortion, reprojectedPoints);
            double poseError = reprojectionErrorPx(
                    reprojectedPoints.toArray(), deskewedPoints);
            if (!Double.isFinite(poseError)
                    || poseError > config.getMaximumPoseReprojectionErrorPx()) {
                lastPoseFailure = "reprojection_error_px";
                lastPoseErrorPx = poseError;
                return null;
            }
            return new RecognitionResult.Pose(
                    rvec, tvec, toOpenGlModelView(rotation, tvec), poseError);
        } finally {
            reprojectedPoints.release();
            rotationMatrix.release();
            translationVector.release();
            rotationVector.release();
            distortion.release();
            cameraMatrix.release();
            imagePoints.release();
            objectPoints.release();
        }
    }

    /**
     * solvePnP maps object coordinates to OpenCV camera coordinates (+x right, +y down, +z
     * forward). Pre-multiplying by diag(1,-1,-1,1) produces OpenGL camera coordinates and this
     * method packs the result column-major for glUniformMatrix4fv.
     */
    private static float[] toOpenGlModelView(double[] rotation, double[] translation) {
        return new float[] {
                (float) rotation[0], (float) -rotation[3], (float) -rotation[6], 0.0f,
                (float) rotation[1], (float) -rotation[4], (float) -rotation[7], 0.0f,
                (float) rotation[2], (float) -rotation[5], (float) -rotation[8], 0.0f,
                (float) translation[0], (float) -translation[1],
                (float) -translation[2], 1.0f
        };
    }

    private static boolean hasPositiveDepth(double[] rotation, double[] translation,
                                            double halfWidth, double halfHeight) {
        double[][] corners = {
                {-halfWidth, -halfHeight},
                {halfWidth, -halfHeight},
                {halfWidth, halfHeight},
                {-halfWidth, halfHeight}
        };
        for (double[] corner : corners) {
            double depth = rotation[6] * corner[0]
                    + rotation[7] * corner[1]
                    + translation[2];
            if (!(depth > 0.0) || !Double.isFinite(depth)) {
                return false;
            }
        }
        return true;
    }

    private static double reprojectionErrorPx(Point[] projected,
                                              List<VisionPoint> observed) {
        if (projected.length != observed.size() || projected.length == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double squaredError = 0.0;
        for (int index = 0; index < projected.length; index++) {
            if (!Double.isFinite(projected[index].x) || !Double.isFinite(projected[index].y)) {
                return Double.POSITIVE_INFINITY;
            }
            double dx = projected[index].x - observed.get(index).getX();
            double dy = projected[index].y - observed.get(index).getY();
            squaredError += dx * dx + dy * dy;
        }
        return Math.sqrt(squaredError / projected.length);
    }

    private static boolean allFinite(double[] values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allFloatRepresentable(double[] values) {
        for (double value : values) {
            if (Math.abs(value) > Float.MAX_VALUE) {
                return false;
            }
        }
        return true;
    }

    private OrientationEstimate classifyOrientation(List<VisionPoint> corners,
                                                    RecognitionRequest request) {
        double signedArea = signedArea(corners);
        double angle = normalizeDegrees(Math.toDegrees(Math.atan2(
                corners.get(1).getY() - corners.get(0).getY(),
                corners.get(1).getX() - corners.get(0).getX())));
        if (signedArea < 0.0) {
            // Loose inventory accepts either physical face. Installation and single
            // identity requests retain the authored front-face requirement.
            return new OrientationEstimate(
                    RecognitionResult.OrientationClassification.MIRRORED, angle,
                    request.inspectsLooseBracketSet());
        }

        double uprightTolerance = config.getDefaultOrientationToleranceDegrees();
        RecognitionResult.OrientationClassification classification;
        if (Math.abs(angle) <= uprightTolerance) {
            classification = RecognitionResult.OrientationClassification.UPRIGHT;
        } else if (Math.abs(Math.abs(angle) - 180.0) <= uprightTolerance) {
            classification = RecognitionResult.OrientationClassification.UPSIDE_DOWN;
        } else if (angle > 0.0) {
            classification = RecognitionResult.OrientationClassification.ROTATED_CLOCKWISE;
        } else {
            classification = RecognitionResult.OrientationClassification.ROTATED_COUNTERCLOCKWISE;
        }

        RecognitionRequest.AssemblyExpectation expectation = request.getExpectation();
        boolean matches = true;
        if (expectation != null && expectation.getOrientationDegrees() != null) {
            double tolerance = expectation.getOrientationToleranceDegrees() == null
                    ? config.getDefaultOrientationToleranceDegrees()
                    : expectation.getOrientationToleranceDegrees();
            matches = angularDistance(angle, expectation.getOrientationDegrees()) <= tolerance;
        }
        return new OrientationEstimate(classification, angle, matches);
    }

    private RecognitionResult.PolarityState validatePolarity(SpatialCandidate candidate,
                                                             RecognitionRequest request) {
        RecognitionRequest.AssemblyExpectation expectation = request.getExpectation();
        if (expectation == null || expectation.getPolarityRule() == null) {
            return RecognitionResult.PolarityState.NOT_REQUIRED;
        }
        RecognitionRequest.PolarityObservation observation = request.getPolarityObservation();
        if (observation == null) {
            return RecognitionResult.PolarityState.UNCONFIRMED;
        }

        RecognitionRequest.PolarityRule rule = expectation.getPolarityRule();
        List<VisionPoint> projectedAnchors = projectNormalizedPoints(candidate,
                rule.getPositiveAnchor(), rule.getNegativeAnchor());
        if (projectedAnchors.size() != 2) {
            return RecognitionResult.PolarityState.UNCONFIRMED;
        }
        double diagonal = candidate.corners.get(0).distanceTo(candidate.corners.get(2));
        if (!(diagonal > 1.0)) {
            return RecognitionResult.PolarityState.UNCONFIRMED;
        }

        VisionPoint expectedPositive = projectedAnchors.get(0);
        VisionPoint expectedNegative = projectedAnchors.get(1);
        double correctCost = 0.5 * (
                observation.getPositivePoint().distanceTo(expectedPositive)
                        + observation.getNegativePoint().distanceTo(expectedNegative)) / diagonal;
        double swappedCost = 0.5 * (
                observation.getPositivePoint().distanceTo(expectedNegative)
                        + observation.getNegativePoint().distanceTo(expectedPositive)) / diagonal;

        if (swappedCost <= rule.getMaximumNormalizedError()
                && swappedCost + rule.getSwappedAssignmentMargin() < correctCost) {
            return RecognitionResult.PolarityState.REVERSED;
        }
        if (correctCost <= rule.getMaximumNormalizedError()
                && correctCost + rule.getSwappedAssignmentMargin() < swappedCost) {
            return RecognitionResult.PolarityState.CORRECT;
        }
        return RecognitionResult.PolarityState.UNCONFIRMED;
    }

    private RecognitionResult.ValidationState validateExpectedComponent(
            String recognizedComponentId,
            Boolean orientationMatches,
            RecognitionResult.PolarityState polarity,
            RecognitionRequest request) {
        RecognitionRequest.AssemblyExpectation expectation = request.getExpectation();
        if (expectation == null) {
            return RecognitionResult.ValidationState.RECOGNIZED;
        }
        if (!expectation.getComponentId().equals(recognizedComponentId)) {
            return RecognitionResult.ValidationState.COMPONENT_MISMATCH;
        }
        if (orientationMatches == null) {
            return RecognitionResult.ValidationState.ORIENTATION_UNCONFIRMED;
        }
        if (!orientationMatches) {
            return RecognitionResult.ValidationState.WRONG_ORIENTATION;
        }
        if (polarity == RecognitionResult.PolarityState.REVERSED) {
            return RecognitionResult.ValidationState.POLARITY_ERROR;
        }
        if (polarity == RecognitionResult.PolarityState.UNCONFIRMED) {
            return RecognitionResult.ValidationState.POLARITY_UNCONFIRMED;
        }
        return RecognitionResult.ValidationState.EXPECTED_COMPONENT;
    }

    private List<VisionPoint> projectNormalizedPoints(SpatialCandidate candidate,
                                                      VisionPoint... normalizedPoints) {
        Point[] points = new Point[normalizedPoints.length];
        List<VisionPoint> componentCorners =
                candidate.reference.spec.getPlanarCornersNormalized();
        for (int index = 0; index < normalizedPoints.length; index++) {
            VisionPoint referencePoint = PlanarCoordinateMapper.projectUnitSquare(
                    componentCorners, normalizedPoints[index]);
            if (referencePoint == null) {
                return Collections.emptyList();
            }
            points[index] = new Point(
                    referencePoint.getX() * (candidate.reference.imageWidth - 1.0),
                    referencePoint.getY() * (candidate.reference.imageHeight - 1.0));
        }
        return projectPoints(candidate.homography, points);
    }

    private List<VisionPoint> projectReferenceCorners(
            ReferenceDatabase.PreparedReference reference, Mat homography) {
        List<VisionPoint> normalized = reference.spec.getPlanarCornersNormalized();
        Point[] pixels = new Point[normalized.size()];
        for (int index = 0; index < normalized.size(); index++) {
            pixels[index] = new Point(
                    normalized.get(index).getX() * (reference.imageWidth - 1.0),
                    normalized.get(index).getY() * (reference.imageHeight - 1.0));
        }
        return projectPoints(homography, pixels);
    }

    private List<VisionPoint> projectPoints(Mat homography, Point... sourcePoints) {
        MatOfPoint2f source = new MatOfPoint2f(sourcePoints);
        MatOfPoint2f destination = new MatOfPoint2f();
        try {
            Core.perspectiveTransform(source, destination, homography);
            Point[] projected = destination.toArray();
            List<VisionPoint> points = new ArrayList<>(projected.length);
            for (Point point : projected) {
                if (!Double.isFinite(point.x) || !Double.isFinite(point.y)) {
                    return Collections.emptyList();
                }
                points.add(new VisionPoint(point.x, point.y));
            }
            return points;
        } finally {
            destination.release();
            source.release();
        }
    }

    private boolean validProjectedCorners(List<VisionPoint> corners,
                                          int frameWidth, int frameHeight,
                                          ExpectedRejectionTracker rejectionTracker) {
        if (corners.size() != 4 || !isConvex(corners)) {
            return false;
        }
        double areaRatio = Math.abs(signedArea(corners)) / (frameWidth * (double) frameHeight);
        double minimumAreaRatio = rejectionTracker == null
                ? config.getMinimumCornerAreaRatio()
                : rejectionTracker.minimumCornerAreaRatio;
        if (areaRatio < minimumAreaRatio
                || areaRatio > config.getMaximumCornerAreaRatio()) {
            return false;
        }
        double horizontalMargin = frameWidth * 0.25;
        double verticalMargin = frameHeight * 0.25;
        for (VisionPoint point : corners) {
            if (point.getX() < -horizontalMargin
                    || point.getX() > frameWidth + horizontalMargin
                    || point.getY() < -verticalMargin
                    || point.getY() > frameHeight + verticalMargin) {
                return false;
            }
        }
        return true;
    }

    private static boolean isConvex(List<VisionPoint> points) {
        double sign = 0.0;
        for (int index = 0; index < 4; index++) {
            VisionPoint a = points.get(index);
            VisionPoint b = points.get((index + 1) % 4);
            VisionPoint c = points.get((index + 2) % 4);
            double cross = (b.getX() - a.getX()) * (c.getY() - b.getY())
                    - (b.getY() - a.getY()) * (c.getX() - b.getX());
            if (Math.abs(cross) < 1e-6) {
                return false;
            }
            if (sign == 0.0) {
                sign = Math.signum(cross);
            } else if (Math.signum(cross) != sign) {
                return false;
            }
        }
        return true;
    }

    private static double signedArea(List<VisionPoint> points) {
        double sum = 0.0;
        for (int index = 0; index < points.size(); index++) {
            VisionPoint current = points.get(index);
            VisionPoint next = points.get((index + 1) % points.size());
            sum += current.getX() * next.getY() - next.getX() * current.getY();
        }
        return sum * 0.5;
    }

    private static int countInliers(Mat mask) {
        int count = (int) mask.total();
        byte[] values = new byte[count];
        mask.get(0, 0, values);
        int inliers = 0;
        for (byte value : values) {
            if (value != 0) {
                inliers++;
            }
        }
        return inliers;
    }

    private static double circleEdgeSupport(Mat edges, double centerX, double centerY,
                                            double radius) {
        int supported = 0;
        int considered = 0;
        for (int sample = 0; sample < EDGE_SUPPORT_SAMPLE_COUNT; sample++) {
            double angle = 2.0 * Math.PI * sample / EDGE_SUPPORT_SAMPLE_COUNT;
            int x = (int) Math.round(centerX + radius * Math.cos(angle));
            int y = (int) Math.round(centerY + radius * Math.sin(angle));
            if (x < 0 || x >= edges.cols() || y < 0 || y >= edges.rows()) {
                continue;
            }
            considered++;
            double[] value = edges.get(y, x);
            if (value != null && value.length > 0 && value[0] > 0.0) {
                supported++;
            }
        }
        return considered == 0 ? 0.0 : supported / (double) considered;
    }

    private void validateRequest(RecognitionRequest request) {
        RecognitionRequest.AssemblyExpectation expectation = request.getExpectation();
        if (expectation != null && !database.containsComponent(expectation.getComponentId())) {
            throw new IllegalArgumentException(
                    "Expected component is not in the reference database: "
                            + expectation.getComponentId());
        }
        String anchorId = request.getSpatialAnchorComponentId();
        if (anchorId != null && !database.containsComponent(anchorId)) {
            throw new IllegalArgumentException(
                    "Spatial anchor component is not in the reference database: " + anchorId);
        }
    }

    private static void validateFrame(Mat grayscale) {
        if (grayscale == null || grayscale.empty()
                || grayscale.channels() != 1 || grayscale.depth() != CvType.CV_8U) {
            throw new IllegalArgumentException("Frame must be a non-empty CV_8UC1 grayscale Mat");
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("ComponentRecognizer is closed");
        }
    }

    private void logLatency(RecognitionResult result) {
        if (!config.isLatencyLoggingEnabled()) {
            return;
        }
        RecognitionResult.Latency latency = result.getLatency();
        Log.d(TAG, String.format(Locale.US,
                "frame=%d method=%s state=%s component=%s keypoints=%d good=%d inliers=%d "
                        + "latency_ms{orb=%.2f,match=%.2f,spatial=%.2f,hough=%.2f,total=%.2f}",
                result.getFrameTimestampNanos(),
                result.getDetectionMethod(),
                result.getValidationState(),
                result.getComponentId(),
                result.getFrameKeypointCount(),
                result.getGoodMatchCount(),
                result.getInlierCount(),
                latency.getFeatureExtractionMs(),
                latency.getMatchingMs(),
                latency.getSpatialValidationMs(),
                latency.getCircularFallbackMs(),
                latency.getTotalMs()));
    }

    private void logBoardConfiguration(Mat frame) {
        Log.d(BOARD_DIAGNOSTIC_TAG, boardDiagnosticPrefix
                + " input=" + (frame == null ? "null" : frame.cols() + "x" + frame.rows())
                + " referenceStep=0 expected=main_board anchor=main_board instances=1"
                + " framePreprocessing=full_frame_CLAHE confidenceThreshold=none"
                + " matchingWorkers=" + matchingExecutor.workerCount()
                + " nativeThreads=" + Core.getNumThreads()
                + " minGood=" + config.getMinimumGoodMatches()
                + " minInliers=" + config.getMinimumInliers()
                + " minInlierRatio=" + config.getMinimumInlierRatio()
                + " knnRatio=" + config.getKnnRatioThreshold()
                + " maxHamming=" + config.getMaximumHammingDistance()
                + " ransac{reprojectionPx=" + config.getHomographyReprojectionThresholdPx()
                + ",iterations=" + config.getHomographyMaximumIterations()
                + ",confidence=" + config.getHomographyConfidence() + "}"
                + " areaRatio=" + config.getMinimumCornerAreaRatio()
                + ".." + config.getMaximumCornerAreaRatio()
                + " cornerMarginRatio=0.25 poseMaxReprojectionPx="
                + config.getMaximumPoseReprojectionErrorPx()
                + " orb{features=" + config.getOrbFeatureCount()
                + ",scale=" + config.getOrbScaleFactor()
                + ",levels=" + config.getOrbPyramidLevels()
                + ",edge=" + config.getOrbEdgeThreshold()
                + ",firstLevel=" + config.getOrbFirstLevel()
                + ",wtaK=" + config.getOrbWtaK()
                + ",patch=" + config.getOrbPatchSize()
                + ",fast=" + config.getOrbFastThreshold() + "}"
                + " clahe{clip=" + config.getClaheClipLimit()
                + ",grid=" + config.getClaheTileGridWidth()
                + "x" + config.getClaheTileGridHeight() + "}"
                + " exposedFallback{minCells=" + MINIMUM_EXPOSED_BOARD_CELLS
                + ",grid=" + EXPOSED_BOARD_GRID_SIZE
                + ",minSpan=" + MINIMUM_EXPOSED_BOARD_SPAN + "}");
    }

    /** Board passes trace board references; component passes trace the expected component. */
    private String diagnosticPrefixFor(ComponentReferenceSpec spec) {
        if (boardDiagnosticPrefix != null) {
            return MAIN_BOARD_COMPONENT_ID.equals(spec.getComponentId())
                    ? boardDiagnosticPrefix : null;
        }
        return expectedDiagnosticPrefix != null
                && spec.getComponentId().equals(expectedDiagnosticComponentId)
                ? expectedDiagnosticPrefix : null;
    }

    private String diagnosticTag() {
        return boardDiagnosticPrefix != null ? BOARD_DIAGNOSTIC_TAG : EXPECTED_DIAGNOSTIC_TAG;
    }

    private void logBoardMatches(ReferenceDatabase.PreparedReference reference,
                                 ReferenceDatabase.PreparedOrbVariant variant,
                                 String pass, int frameKeypoints, int pairs, int goodMatches) {
        String prefix = diagnosticPrefixFor(reference.spec);
        if (prefix == null) {
            return;
        }
        Log.d(diagnosticTag(), prefix
                + " reference=" + reference.spec.getReferenceId()
                + " referenceTier=" + (reference.spec.getAssemblyStepNumber() == 0 ? "baseline" : "installed_fallback")
                + " variant=" + variant.photometricVariant + " pass=" + pass
                + " referenceImage=" + reference.imageWidth + "x" + reference.imageHeight
                + " physicalMm=" + reference.spec.getPhysicalWidthMm()
                + "x" + reference.spec.getPhysicalHeightMm()
                + " boardKeypoints=" + variant.keypoints.length
                + " frameKeypoints=" + frameKeypoints + " knnPairs=" + pairs
                + " goodMatches=" + goodMatches
                + " minGood=" + config.getMinimumGoodMatches()
                + " homography=" + (goodMatches < config.getMinimumGoodMatches()
                ? "not_attempted_insufficient_good_matches" : "pending"));
    }

    private void logBoardHomography(RawCandidate raw, boolean exposed, String homography,
                                    String reason, int validMatches, int inliers,
                                    List<VisionPoint> corners,
                                    double confidence) {
        String prefix = diagnosticPrefixFor(raw.reference.spec);
        if (prefix == null) {
            return;
        }
        Log.d(diagnosticTag(), prefix
                + " reference=" + raw.reference.spec.getReferenceId()
                + " variant=" + raw.referenceVariant.photometricVariant
                + " pass=" + (exposed ? "exposed" : "whole")
                + " goodMatches=" + raw.goodMatches.size()
                + " validMatches=" + validMatches
                + " homography=" + homography + " reason=" + reason
                + " inliers=" + (inliers < 0 ? "n/a" : inliers)
                + " inlierRatio=" + (inliers < 0 ? "n/a"
                : inliers / (double) validMatches)
                + " corners=" + (corners == null ? "n/a" : corners)
                + " areaPx=" + (corners == null || corners.size() != 4
                ? "n/a" : Math.abs(signedArea(corners)))
                + " confidence=" + (Double.isNaN(confidence) ? "n/a" : confidence));
    }

    private static Point toPoint(VisionPoint point) {
        return new Point(point.getX(), point.getY());
    }

    private static double normalizeDegrees(double degrees) {
        double normalized = degrees % 360.0;
        if (normalized <= -180.0) normalized += 360.0;
        if (normalized > 180.0) normalized -= 360.0;
        return normalized;
    }

    private static double angularDistance(double first, double second) {
        return Math.abs(normalizeDegrees(first - second));
    }

    private static double nanosToMs(long nanoseconds) {
        return nanoseconds / 1_000_000.0;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /** Collects metrics only; it never participates in candidate acceptance or ranking. */
    private static final class ExpectedRejectionTracker {
        private final String expectedComponentId;
        private final int frameKeypointCount;
        private final VisionConfig config;
        private final double minimumCornerAreaRatio;
        private CandidateEvidence bestRejected;
        private CandidateEvidence bestAccepted;

        private ExpectedRejectionTracker(String expectedComponentId,
                                         int frameKeypointCount,
                                         VisionConfig config,
                                         Double minimumCornerAreaRatioOverride) {
            this.expectedComponentId = expectedComponentId;
            this.frameKeypointCount = frameKeypointCount;
            this.config = config;
            minimumCornerAreaRatio = minimumCornerAreaRatioOverride == null
                    ? config.getMinimumCornerAreaRatio()
                    : minimumCornerAreaRatioOverride;
        }

        static ExpectedRejectionTracker initialize(
                List<ReferenceDatabase.PreparedReference> references,
                RecognitionRequest request,
                int frameKeypointCount,
                boolean frameDescriptorsEmpty,
                VisionConfig config) {
            RecognitionRequest.AssemblyExpectation expectation = request.getExpectation();
            if (expectation == null) {
                return null;
            }
            String expectedId = expectation.getComponentId();
            ExpectedRejectionTracker tracker = new ExpectedRejectionTracker(
                    expectedId, frameKeypointCount, config,
                    request.getMinimumCornerAreaRatioOverride());
            ReferenceDatabase.PreparedReference representative = null;
            ReferenceDatabase.PreparedReference eligibleRepresentative = null;
            boolean eligibleReferenceFound = false;
            boolean eligibleOrbVariantFound = false;
            for (ReferenceDatabase.PreparedReference reference : references) {
                if (!expectedId.equals(reference.spec.getComponentId())) {
                    continue;
                }
                if (representative == null
                        || maximumReferenceKeypoints(reference)
                        > maximumReferenceKeypoints(representative)) {
                    representative = reference;
                }
                if (!isReferenceEligibleForRequest(reference.spec, request)) {
                    continue;
                }
                eligibleReferenceFound = true;
                if (eligibleRepresentative == null
                        || maximumReferenceKeypoints(reference)
                        > maximumReferenceKeypoints(eligibleRepresentative)) {
                    eligibleRepresentative = reference;
                }
                for (ReferenceDatabase.PreparedOrbVariant variant : reference.orbVariants) {
                    if (!variant.hasOrbData()) {
                        continue;
                    }
                    eligibleOrbVariantFound = true;
                    tracker.consider(new CandidateEvidence(
                            frameDescriptorsEmpty
                                    ? RecognitionResult.RejectionReason.NO_FRAME_DESCRIPTORS
                                    : RecognitionResult.RejectionReason.INSUFFICIENT_GOOD_MATCHES,
                            reference, variant, frameKeypointCount, 0, -1, -1,
                            Double.NaN));
                }
            }
            if (!eligibleReferenceFound) {
                tracker.consider(new CandidateEvidence(
                        RecognitionResult.RejectionReason.NO_ELIGIBLE_REFERENCE,
                        representative, null, frameKeypointCount, 0, -1, -1,
                        Double.NaN));
            } else if (!eligibleOrbVariantFound) {
                tracker.consider(new CandidateEvidence(
                        RecognitionResult.RejectionReason.REFERENCE_HAS_NO_FEATURES,
                        eligibleRepresentative, null, frameKeypointCount, 0, -1, -1,
                        Double.NaN));
            }
            return tracker;
        }

        void recordMatchAttempt(ReferenceDatabase.PreparedReference reference,
                                ReferenceDatabase.PreparedOrbVariant variant,
                                int goodMatches) {
            if (!isExpected(reference)) {
                return;
            }
            consider(new CandidateEvidence(
                    RecognitionResult.RejectionReason.INSUFFICIENT_GOOD_MATCHES,
                    reference, variant, frameKeypointCount, goodMatches, -1, -1,
                    Double.NaN));
        }

        void recordHomographyRejection(RawCandidate raw,
                                       RecognitionResult.RejectionReason reason,
                                       int validMatches,
                                       int inliers) {
            if (!isExpected(raw.reference)) {
                return;
            }
            double ratio = validMatches > 0 && inliers >= 0
                    ? inliers / (double) validMatches : Double.NaN;
            consider(new CandidateEvidence(reason, raw.reference, raw.referenceVariant,
                    frameKeypointCount, raw.goodMatches.size(), validMatches, inliers, ratio));
        }

        void recordAccepted(RawCandidate raw, int validMatches, int inliers) {
            if (!isExpected(raw.reference)) {
                return;
            }
            CandidateEvidence accepted = new CandidateEvidence(
                    RecognitionResult.RejectionReason.IDENTITY_AMBIGUOUS,
                    raw.reference, raw.referenceVariant, frameKeypointCount,
                    raw.goodMatches.size(), validMatches, inliers,
                    validMatches > 0 ? inliers / (double) validMatches : Double.NaN);
            if (bestAccepted == null || isStronger(accepted, bestAccepted)) {
                bestAccepted = accepted;
            }
        }

        RecognitionResult.RejectionEvidence toEvidence(boolean identityAmbiguous) {
            CandidateEvidence selected = identityAmbiguous && bestAccepted != null
                    ? bestAccepted : bestRejected;
            if (selected == null) {
                return null;
            }
            ComponentReferenceSpec spec = selected.reference == null
                    ? null : selected.reference.spec;
            return new RecognitionResult.RejectionEvidence(
                    selected.reason,
                    spec == null ? null : spec.getReferenceId(),
                    expectedComponentId,
                    spec == null ? null : spec.getDisplayName(),
                    selected.variant == null
                            ? null : selected.variant.photometricVariant.name(),
                    selected.frameKeypointCount,
                    selected.variant == null ? maximumReferenceKeypoints(selected.reference)
                            : selected.variant.keypoints.length,
                    selected.goodMatchCount,
                    selected.validMatchCount,
                    selected.inlierCount,
                    selected.inlierRatio,
                    config.getMinimumGoodMatches(),
                    config.getMinimumInliers(),
                    config.getMinimumInlierRatio());
        }

        private boolean isExpected(ReferenceDatabase.PreparedReference reference) {
            return reference != null
                    && expectedComponentId.equals(reference.spec.getComponentId());
        }

        private void consider(CandidateEvidence candidate) {
            if (bestRejected == null || isStronger(candidate, bestRejected)) {
                bestRejected = candidate;
            }
        }

        private static boolean isStronger(CandidateEvidence candidate,
                                          CandidateEvidence current) {
            int stageComparison = Integer.compare(
                    rejectionStage(candidate.reason), rejectionStage(current.reason));
            if (stageComparison != 0) {
                return stageComparison > 0;
            }
            if (candidate.inlierCount != current.inlierCount) {
                return candidate.inlierCount > current.inlierCount;
            }
            if (candidate.validMatchCount != current.validMatchCount) {
                return candidate.validMatchCount > current.validMatchCount;
            }
            if (candidate.goodMatchCount != current.goodMatchCount) {
                return candidate.goodMatchCount > current.goodMatchCount;
            }
            return referenceKeypoints(candidate) > referenceKeypoints(current);
        }

        private static int rejectionStage(RecognitionResult.RejectionReason reason) {
            switch (reason) {
                case NO_ELIGIBLE_REFERENCE:
                    return 0;
                case REFERENCE_HAS_NO_FEATURES:
                    return 1;
                case NO_FRAME_DESCRIPTORS:
                    return 2;
                case INSUFFICIENT_GOOD_MATCHES:
                    return 3;
                case INSUFFICIENT_VALID_MATCHES:
                    return 4;
                case HOMOGRAPHY_ESTIMATION_FAILED:
                    return 5;
                case INSUFFICIENT_INLIERS_OR_RATIO:
                    return 6;
                case INSUFFICIENT_FEATURE_DISTRIBUTION:
                    return 7;
                case INVALID_PROJECTED_GEOMETRY:
                    return 8;
                case INSUFFICIENT_POSE_POINTS:
                    return 9;
                case IDENTITY_AMBIGUOUS:
                    return 10;
                default:
                    throw new AssertionError("Unknown rejection reason " + reason);
            }
        }

        private static int referenceKeypoints(CandidateEvidence evidence) {
            return evidence.variant == null
                    ? maximumReferenceKeypoints(evidence.reference)
                    : evidence.variant.keypoints.length;
        }

        private static int maximumReferenceKeypoints(
                ReferenceDatabase.PreparedReference reference) {
            if (reference == null) {
                return 0;
            }
            int maximum = 0;
            for (ReferenceDatabase.PreparedOrbVariant variant : reference.orbVariants) {
                maximum = Math.max(maximum, variant.keypoints.length);
            }
            return maximum;
        }
    }

    private static final class CandidateEvidence {
        final RecognitionResult.RejectionReason reason;
        final ReferenceDatabase.PreparedReference reference;
        final ReferenceDatabase.PreparedOrbVariant variant;
        final int frameKeypointCount;
        final int goodMatchCount;
        final int validMatchCount;
        final int inlierCount;
        final double inlierRatio;

        CandidateEvidence(RecognitionResult.RejectionReason reason,
                          ReferenceDatabase.PreparedReference reference,
                          ReferenceDatabase.PreparedOrbVariant variant,
                          int frameKeypointCount,
                          int goodMatchCount,
                          int validMatchCount,
                          int inlierCount,
                          double inlierRatio) {
            this.reason = reason;
            this.reference = reference;
            this.variant = variant;
            this.frameKeypointCount = frameKeypointCount;
            this.goodMatchCount = goodMatchCount;
            this.validMatchCount = validMatchCount;
            this.inlierCount = inlierCount;
            this.inlierRatio = inlierRatio;
        }
    }

    private static final class RawCandidate {
        final ReferenceDatabase.PreparedReference reference;
        final ReferenceDatabase.PreparedOrbVariant referenceVariant;
        final KeyPoint[] frameKeypoints;
        final List<DMatch> goodMatches;
        final double averageDistance;
        final int ratioGoodMatchCount;
        int validMatchCount = -1;
        int homographyInlierCount = -1;
        double homographyInlierRatio = Double.NaN;
        String homographyOutcome = "not_attempted";

        RawCandidate(ReferenceDatabase.PreparedReference reference,
                     ReferenceDatabase.PreparedOrbVariant referenceVariant,
                     KeyPoint[] frameKeypoints,
                     List<DMatch> goodMatches, double averageDistance) {this(reference,referenceVariant,frameKeypoints,goodMatches,averageDistance,goodMatches.size());}
        RawCandidate(ReferenceDatabase.PreparedReference reference,ReferenceDatabase.PreparedOrbVariant referenceVariant,KeyPoint[] frameKeypoints,List<DMatch> goodMatches,double averageDistance,int ratioGoodMatchCount){
            this.ratioGoodMatchCount=ratioGoodMatchCount;
            this.reference = reference;
            this.referenceVariant = referenceVariant;
            this.frameKeypoints = frameKeypoints;
            this.goodMatches = goodMatches;
            this.averageDistance = averageDistance;
        }

        void recordHomographyOutcome(RecognitionResult.RejectionReason reason,
                                     int validMatches, int inliers) {
            validMatchCount = validMatches;
            homographyInlierCount = inliers;
            homographyInlierRatio = validMatches > 0 && inliers >= 0
                    ? inliers / (double) validMatches : Double.NaN;
            homographyOutcome = reason.name();
        }

        void recordHomographyAccepted(int validMatches, int inliers) {
            validMatchCount = validMatches;
            homographyInlierCount = inliers;
            homographyInlierRatio = validMatches > 0
                    ? inliers / (double) validMatches : Double.NaN;
            homographyOutcome = "ACCEPTED";
        }
    }

    private static final class SpatialCandidate {
        final ReferenceDatabase.PreparedReference reference;
        final Mat homography;
        final List<VisionPoint> corners;
        int referenceKeypoints=-1,ratioGoodMatchCount=-1;
        final int goodMatchCount;
        final int inlierCount;
        final double inlierRatio;
        final double confidence;
        final double rankingScore;
        final List<VisionPoint> poseObjectPointsMm;
        final List<VisionPoint> poseImagePoints;

        SpatialCandidate(ReferenceDatabase.PreparedReference reference, Mat homography,
                         List<VisionPoint> corners, int goodMatchCount, int inlierCount,
                         double inlierRatio, double confidence, double rankingScore,
                         List<VisionPoint> poseObjectPointsMm,
                         List<VisionPoint> poseImagePoints) {
            this.reference = reference;
            this.homography = homography;
            this.corners = corners;
            this.goodMatchCount = goodMatchCount;
            this.inlierCount = inlierCount;
            this.inlierRatio = inlierRatio;
            this.confidence = confidence;
            this.rankingScore = rankingScore;
            this.poseObjectPointsMm = Collections.unmodifiableList(
                    new ArrayList<>(poseObjectPointsMm));
            this.poseImagePoints = Collections.unmodifiableList(
                    new ArrayList<>(poseImagePoints));
        }

        void release() {
            homography.release();
        }
    }

    private static final class PoseInlierSet {
        final List<VisionPoint> objectPointsMm;
        final List<VisionPoint> imagePoints;

        PoseInlierSet(List<VisionPoint> objectPointsMm,
                      List<VisionPoint> imagePoints) {
            this.objectPointsMm = Collections.unmodifiableList(
                    new ArrayList<>(objectPointsMm));
            this.imagePoints = Collections.unmodifiableList(
                    new ArrayList<>(imagePoints));
        }

        static PoseInlierSet empty() {
            return new PoseInlierSet(Collections.emptyList(), Collections.emptyList());
        }
    }

    private static final class TileRetryTimings {
        final long featureNanos;
        final long matchingNanos;
        final long spatialNanos;

        TileRetryTimings(long featureNanos, long matchingNanos, long spatialNanos) {
            this.featureNanos = featureNanos;
            this.matchingNanos = matchingNanos;
            this.spatialNanos = spatialNanos;
        }
    }

    private static final class AdditionalDetection {
        final SpatialCandidate candidate;
        final long featureNanos;
        final long matchingNanos;
        final long spatialNanos;

        AdditionalDetection(SpatialCandidate candidate,
                            long featureNanos,
                            long matchingNanos,
                            long spatialNanos) {
            this.candidate = candidate;
            this.featureNanos = featureNanos;
            this.matchingNanos = matchingNanos;
            this.spatialNanos = spatialNanos;
        }
    }

    private static final class Bounds {
        final double minimumX;
        final double minimumY;
        final double maximumX;
        final double maximumY;
        final double area;
        final VisionPoint center;

        private Bounds(double minimumX, double minimumY,
                       double maximumX, double maximumY) {
            this.minimumX = minimumX;
            this.minimumY = minimumY;
            this.maximumX = maximumX;
            this.maximumY = maximumY;
            area = (maximumX - minimumX) * (maximumY - minimumY);
            center = new VisionPoint(
                    (minimumX + maximumX) * 0.5,
                    (minimumY + maximumY) * 0.5);
        }

        static Bounds from(List<VisionPoint> points) {
            double minimumX = Double.POSITIVE_INFINITY;
            double minimumY = Double.POSITIVE_INFINITY;
            double maximumX = Double.NEGATIVE_INFINITY;
            double maximumY = Double.NEGATIVE_INFINITY;
            for (VisionPoint point : points) {
                if (point == null) {
                    return null;
                }
                minimumX = Math.min(minimumX, point.getX());
                minimumY = Math.min(minimumY, point.getY());
                maximumX = Math.max(maximumX, point.getX());
                maximumY = Math.max(maximumY, point.getY());
            }
            if (!(maximumX > minimumX) || !(maximumY > minimumY)) {
                return null;
            }
            return new Bounds(minimumX, minimumY, maximumX, maximumY);
        }
    }

    private static final class OrientationEstimate {
        final RecognitionResult.OrientationClassification classification;
        final double degrees;
        final boolean matchesExpectation;

        OrientationEstimate(RecognitionResult.OrientationClassification classification,
                            double degrees, boolean matchesExpectation) {
            this.classification = classification;
            this.degrees = degrees;
            this.matchesExpectation = matchesExpectation;
        }
    }

    private static final class ScoredCircle {
        final RecognitionResult.DetectedCircle circle;
        final double edgeSupport;

        ScoredCircle(RecognitionResult.DetectedCircle circle, double edgeSupport) {
            this.circle = circle;
            this.edgeSupport = edgeSupport;
        }
    }

    private static final class CircleFit {
        final RecognitionResult.DetectedCircle circle;
        final double normalizedRadiusFit;

        CircleFit(RecognitionResult.DetectedCircle circle, double normalizedRadiusFit) {
            this.circle = circle;
            this.normalizedRadiusFit = normalizedRadiusFit;
        }
    }

    private static final class CircularSelection {
        final ReferenceDatabase.PreparedReference reference;
        final List<RecognitionResult.DetectedCircle> circles;

        CircularSelection(ReferenceDatabase.PreparedReference reference,
                          List<RecognitionResult.DetectedCircle> circles) {
            this.reference = reference;
            this.circles = Collections.unmodifiableList(new ArrayList<>(circles));
        }
    }

    static final class CircularGeometry {
        final List<VisionPoint> corners;
        final RecognitionResult.Pose pose;

        CircularGeometry(List<VisionPoint> corners, RecognitionResult.Pose pose) {
            this.corners = Collections.unmodifiableList(new ArrayList<>(corners));
            this.pose = pose;
        }
    }

    private static final class CircularFallback {
        final ReferenceDatabase.PreparedReference reference;
        final List<RecognitionResult.DetectedCircle> selectedCircles;
        final List<RecognitionResult.DetectedCircle> circles;
        final double confidence;

        CircularFallback(ReferenceDatabase.PreparedReference reference,
                         List<RecognitionResult.DetectedCircle> selectedCircles,
                         List<RecognitionResult.DetectedCircle> circles,
                         double confidence) {
            this.reference = reference;
            this.selectedCircles = Collections.unmodifiableList(
                    new ArrayList<>(selectedCircles));
            this.circles = Collections.unmodifiableList(new ArrayList<>(circles));
            this.confidence = confidence;
        }
    }
}
