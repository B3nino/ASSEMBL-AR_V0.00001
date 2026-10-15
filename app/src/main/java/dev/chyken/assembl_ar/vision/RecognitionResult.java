package dev.chyken.assembl_ar.vision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable recognition, spatial-validation, pose, and timing result for one frame. */
public final class RecognitionResult {
    public enum DetectionMethod {
        NONE,
        ORB_HOMOGRAPHY,
        ORB_DESCRIPTOR_ONLY,
        COLOR_SHAPE_FALLBACK,
        MODEL_EDGE_MATCH,
        CANNY_HOUGH_FALLBACK
    }

    public enum ValidationState {
        NO_COMPONENT,
        RECOGNIZED,
        EXPECTED_COMPONENT,
        COMPONENT_MISMATCH,
        WRONG_ORIENTATION,
        ORIENTATION_UNCONFIRMED,
        POLARITY_ERROR,
        POLARITY_UNCONFIRMED
    }

    public enum OrientationClassification {
        UNKNOWN,
        UPRIGHT,
        ROTATED_CLOCKWISE,
        ROTATED_COUNTERCLOCKWISE,
        UPSIDE_DOWN,
        MIRRORED
    }

    public enum PolarityState {
        NOT_REQUIRED,
        UNCONFIRMED,
        CORRECT,
        REVERSED
    }

    /** The precise stage that rejected the strongest expected-reference candidate. */
    public enum RejectionReason {
        NO_ELIGIBLE_REFERENCE,
        REFERENCE_HAS_NO_FEATURES,
        NO_FRAME_DESCRIPTORS,
        INSUFFICIENT_GOOD_MATCHES,
        INSUFFICIENT_VALID_MATCHES,
        HOMOGRAPHY_ESTIMATION_FAILED,
        INSUFFICIENT_INLIERS_OR_RATIO,
        INSUFFICIENT_FEATURE_DISTRIBUTION,
        INVALID_PROJECTED_GEOMETRY,
        INSUFFICIENT_POSE_POINTS,
        IDENTITY_AMBIGUOUS
    }

    private final long frameTimestampNanos;
    private final String referenceId;
    private final String componentId;
    private final String displayName;
    private final DetectionMethod detectionMethod;
    private final ValidationState validationState;
    private final OrientationClassification orientationClassification;
    private final double orientationDegrees;
    private final PolarityState polarityState;
    private final int frameKeypointCount;
    private final int goodMatchCount;
    private final int inlierCount;
    private final double inlierRatio;
    private final double confidence;
    private final List<VisionPoint> projectedCorners;
    private final List<InstanceObservation> instanceObservations;
    private final List<InstanceObservation> sceneDetections;
    private final Pose pose;
    private final SpatialAnchor spatialAnchor;
    private final List<DetectedCircle> circles;
    private final Latency latency;
    private final MotorAssemblyObservation motorAssembly;
    private final boolean anchorTracked;
    private final int assemblyStepNumber;
    private final BoardObservation boardObservation;
    private final RejectionEvidence rejectionEvidence;
    private final Object requestTag;

    private RecognitionResult(Builder builder) {
        frameTimestampNanos = builder.frameTimestampNanos;
        referenceId = builder.referenceId;
        componentId = builder.componentId;
        displayName = builder.displayName;
        detectionMethod = builder.detectionMethod;
        // Descriptor similarity is diagnostic evidence, not a located physical identity.
        // Enforce this at the shared result boundary so neither camera consumer can turn
        // an unlocated identity into acceptance or a named wrong-part message.
        validationState = hasLocatedComponent(builder.componentId, builder.detectionMethod,
                builder.projectedCorners, builder.instanceObservations)
                ? builder.validationState : ValidationState.NO_COMPONENT;
        orientationClassification = builder.orientationClassification;
        orientationDegrees = builder.orientationDegrees;
        polarityState = builder.polarityState;
        frameKeypointCount = builder.frameKeypointCount;
        goodMatchCount = builder.goodMatchCount;
        inlierCount = builder.inlierCount;
        inlierRatio = builder.inlierRatio;
        confidence = builder.confidence;
        projectedCorners = immutableList(builder.projectedCorners);
        instanceObservations = immutableList(builder.instanceObservations);
        sceneDetections = immutableList(builder.sceneDetections);
        pose = builder.pose;
        spatialAnchor = builder.spatialAnchor;
        circles = immutableList(builder.circles);
        latency = builder.latency;
        motorAssembly = builder.motorAssembly;
        anchorTracked = builder.anchorTracked;
        assemblyStepNumber = builder.assemblyStepNumber;
        boardObservation = builder.boardObservation;
        requestTag = builder.requestTag;
        rejectionEvidence = componentId == null
                || detectionMethod == DetectionMethod.ORB_DESCRIPTOR_ONLY
                ? builder.rejectionEvidence : null;
    }

    public long getFrameTimestampNanos() { return frameTimestampNanos; }
    public String getReferenceId() { return referenceId; }
    public String getComponentId() { return componentId; }
    public String getDisplayName() { return displayName; }
    public DetectionMethod getDetectionMethod() { return detectionMethod; }
    public ValidationState getValidationState() { return validationState; }
    public OrientationClassification getOrientationClassification() {
        return orientationClassification;
    }
    public double getOrientationDegrees() { return orientationDegrees; }
    public PolarityState getPolarityState() { return polarityState; }
    public int getFrameKeypointCount() { return frameKeypointCount; }
    public int getGoodMatchCount() { return goodMatchCount; }
    public int getInlierCount() { return inlierCount; }
    public double getInlierRatio() { return inlierRatio; }
    public double getConfidence() { return confidence; }
    /** A named component claim requires a spatial method and a valid frame-local outline. */
    public boolean hasLocatedComponent() {
        return hasLocatedComponent(componentId, detectionMethod, projectedCorners, instanceObservations);
    }

    private static boolean hasLocatedComponent(String identity, DetectionMethod method,
                                               List<VisionPoint> corners,
                                               List<InstanceObservation> observations) {
        if (identity == null || identity.trim().isEmpty()
                || method == DetectionMethod.NONE || method == DetectionMethod.ORB_DESCRIPTOR_ONLY) return false;
        if (corners != null && corners.size() == 4 && hasConvexArea(corners)) return true;
        // A counted set can locate several objects without choosing one aggregate outline.
        // It must still own a confident, spatial observation of this exact identity.
        for (InstanceObservation observation : observations) {
            if (identity.equals(observation.componentId) && observation.confidence >= .75
                    && observation.detectionMethod != DetectionMethod.NONE
                    && observation.detectionMethod != DetectionMethod.ORB_DESCRIPTOR_ONLY
                    && hasConvexArea(observation.projectedCorners)) return true;
        }
        return false;
    }
    public List<VisionPoint> getProjectedCorners() { return projectedCorners; }
    public List<InstanceObservation> getInstanceObservations() {
        return instanceObservations;
    }

    /** Accepted frame-local outlines for the HUD, independent of the current step's gate. */
    public List<InstanceObservation> getSceneDetections() { return sceneDetections; }

    /** Counts distinct accepted localizations from this frame, never from prior observations. */
    public int getDetectedInstanceCount(String expectedComponentId) {
        return getDetectedInstances(expectedComponentId).size();
    }

    /** The same distinct frame-local observations used by the instance count. */
    public List<InstanceObservation> getDetectedInstances(String expectedComponentId) {
        if (expectedComponentId == null || !expectedComponentId.equals(componentId)
                || validationState != ValidationState.EXPECTED_COMPONENT
                || detectionMethod == DetectionMethod.NONE
                || detectionMethod == DetectionMethod.ORB_DESCRIPTOR_ONLY) {
            return Collections.emptyList();
        }
        List<InstanceObservation> distinct = new ArrayList<>();
        for (InstanceObservation candidate : instanceObservations) {
            if (!expectedComponentId.equals(candidate.getComponentId())
                    || candidate.getConfidence() < .75
                    || candidate.getDetectionMethod() == DetectionMethod.ORB_DESCRIPTOR_ONLY
                    || !hasConvexArea(candidate.getProjectedCorners())) continue;
            boolean separate = true;
            for (InstanceObservation prior : distinct) {
                if (!ComponentRecognizer.areDistinctInstanceQuads(
                        prior.getProjectedCorners(), candidate.getProjectedCorners())) {
                    separate = false;
                    break;
                }
            }
            if (separate) distinct.add(candidate);
        }
        return Collections.unmodifiableList(distinct);
    }

    private static boolean hasConvexArea(List<VisionPoint> corners) {
        double direction = 0.0;
        for (int index = 0; index < 4; index++) {
            VisionPoint a = corners.get(index), b = corners.get((index + 1) % 4),
                    c = corners.get((index + 2) % 4);
            if (a == null || b == null || c == null) return false;
            double cross = (b.getX() - a.getX()) * (c.getY() - b.getY())
                    - (b.getY() - a.getY()) * (c.getX() - b.getX());
            if (!Double.isFinite(cross) || Math.abs(cross) < 1.0
                    || direction != 0.0 && direction * cross < 0.0) return false;
            direction = cross;
        }
        return true;
    }
    public Pose getPose() { return pose; }
    public SpatialAnchor getSpatialAnchor() { return spatialAnchor; }
    public List<DetectedCircle> getCircles() { return circles; }
    public Latency getLatency() { return latency; }
    public MotorAssemblyObservation getMotorAssembly() { return motorAssembly; }
    public boolean isAnchorTracked() { return anchorTracked; }
    public int getAssemblyStepNumber() { return assemblyStepNumber; }
    public BoardObservation getBoardObservation() { return boardObservation; }
    /** The requesting caller's opaque token ({@link RecognitionRequest#getRequestTag()}). */
    public Object getRequestTag() { return requestTag; }
    /**
     * Evidence from the strongest expected reference that reached a rejection stage. This is
     * populated when no component identity was accepted, or when a test-only descriptor match
     * accepted identity without safe projected geometry. A shape-only fallback may still report
     * circles without an identity; this evidence explains why the expected identity did not
     * produce a placement pose and must never itself be treated as a detection.
     */
    public RejectionEvidence getRejectionEvidence() { return rejectionEvidence; }
    public boolean isBoardDetected() {
        return boardObservation != null && boardObservation.isDetected();
    }

    /** Attach frame-scoped assembly evidence without mislabeling the board as a motor. */
    RecognitionResult withAssemblyInspection(int stepNumber, SpatialAnchor anchor,
                                            boolean tracked, MotorAssemblyObservation observation) {
        Builder copy = copyBuilder().spatialAnchor(anchor);
        copy.motorAssembly = observation;
        copy.anchorTracked = tracked;
        copy.assemblyStepNumber = stepNumber;
        return copy.build();
    }

    RecognitionResult withRequestTag(Object tag) {
        Builder copy = copyBuilder();
        copy.requestTag = tag;
        return copy.build();
    }

    RecognitionResult withBoardObservation(BoardObservation observation) {
        Builder copy = copyBuilder();
        copy.boardObservation = observation;
        return copy.build();
    }

    RecognitionResult withRejectionEvidence(RejectionEvidence evidence) {
        return copyBuilder().rejectionEvidence(evidence).build();
    }

    /** Carries HUD evidence across a separate board pass or an expected-part fallback. */
    RecognitionResult withSceneDetectionsFrom(RecognitionResult previous) {
        return withMergedSceneDetections(previous == null
                ? Collections.emptyList() : previous.sceneDetections);
    }

    /** Merges accepted ORB candidates with this result's expected-part fallback observations. */
    RecognitionResult withMergedSceneDetections(List<InstanceObservation> earlierDetections) {
        List<InstanceObservation> merged = new ArrayList<>(earlierDetections);
        List<InstanceObservation> current = sceneDetections.isEmpty()
                ? instanceObservations : sceneDetections;
        for (InstanceObservation observation : current) {
            boolean ambiguous = false;
            for (int index = merged.size() - 1; index >= 0; index--) {
                InstanceObservation existing = merged.get(index);
                if (existing.getComponentId().equals(observation.getComponentId())) {
                    if (!ComponentRecognizer.areDistinctInstanceQuads(
                            existing.getProjectedCorners(), observation.getProjectedCorners())) {
                        // The later pass has the step-specific geometry for this same object.
                        merged.remove(index);
                    }
                } else if (ComponentRecognizer.hasConflictingFootprint(
                        existing.getProjectedCorners(), observation.getProjectedCorners())) {
                    // Neither identity is safe to label when independent passes disagree.
                    merged.remove(index);
                    ambiguous = true;
                }
            }
            if (!ambiguous) merged.add(observation);
        }
        return copyBuilder().sceneDetections(merged).build();
    }

    /** Revalidate installation orientation without recomputing identity, geometry, or pose. */
    RecognitionResult withOrientationValidation(OrientationClassification orientation,
                                                double degrees, ValidationState validation) {
        return copyBuilder().orientation(orientation, degrees).validation(validation).build();
    }

    RecognitionResult withLatency(Latency measured) {
        return copyBuilder().latency(measured).build();
    }

    /** Reject a spatial identity without converting its diagnostic evidence into acceptance. */
    RecognitionResult withoutComponentClaim(RejectionEvidence rejected) {
        return copyBuilder().identity(null, null, null).method(DetectionMethod.NONE)
                .validation(ValidationState.NO_COMPONENT).confidence(0)
                .corners(Collections.emptyList()).instances(Collections.emptyList())
                .sceneDetections(Collections.emptyList()).pose(null)
                .rejectionEvidence(rejected).build();
    }

    private Builder copyBuilder() {
        Builder copy = builder(frameTimestampNanos)
                .identity(referenceId, componentId, displayName).method(detectionMethod)
                .validation(validationState).orientation(orientationClassification, orientationDegrees)
                .polarity(polarityState).matches(frameKeypointCount, goodMatchCount, inlierCount, inlierRatio)
                .confidence(confidence).corners(projectedCorners).instances(instanceObservations)
                .sceneDetections(sceneDetections)
                .pose(pose).spatialAnchor(spatialAnchor).circles(circles).latency(latency);
        copy.motorAssembly = motorAssembly;
        copy.anchorTracked = anchorTracked;
        copy.assemblyStepNumber = assemblyStepNumber;
        copy.boardObservation = boardObservation;
        copy.rejectionEvidence = rejectionEvidence;
        copy.requestTag = requestTag;
        return copy;
    }

    /** Main-board evidence is independent of both the current component and its workplane. */
    public static final class BoardObservation {
        private final RecognitionResult detection;
        private final SpatialAnchor anchor;
        private final boolean tracked;

        BoardObservation(RecognitionResult detection, SpatialAnchor anchor, boolean tracked) {
            this.detection = Objects.requireNonNull(detection, "detection");
            this.anchor = anchor;
            this.tracked = tracked;
        }

        /** Fresh detector evidence only; optical flow does not invent ORB matches/confidence. */
        public RecognitionResult getDetection() { return detection; }
        public SpatialAnchor getAnchor() { return anchor; }
        public boolean isTracked() { return tracked; }
        public boolean isDetected() {
            return anchor != null || ("main_board".equals(detection.getComponentId())
                    && detection.getDetectionMethod() == DetectionMethod.ORB_HOMOGRAPHY
                    && detection.getProjectedCorners().size() == 4);
        }
    }

    private static <T> List<T> immutableList(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    static Builder builder(long frameTimestampNanos) {
        return new Builder(frameTimestampNanos);
    }

    static final class Builder {
        private final long frameTimestampNanos;
        private String referenceId;
        private String componentId;
        private String displayName;
        private DetectionMethod detectionMethod = DetectionMethod.NONE;
        private ValidationState validationState = ValidationState.NO_COMPONENT;
        private OrientationClassification orientationClassification =
                OrientationClassification.UNKNOWN;
        private double orientationDegrees = Double.NaN;
        private PolarityState polarityState = PolarityState.NOT_REQUIRED;
        private int frameKeypointCount;
        private int goodMatchCount;
        private int inlierCount;
        private double inlierRatio;
        private double confidence;
        private List<VisionPoint> projectedCorners = Collections.emptyList();
        private List<InstanceObservation> instanceObservations = Collections.emptyList();
        private List<InstanceObservation> sceneDetections = Collections.emptyList();
        private Pose pose;
        private SpatialAnchor spatialAnchor;
        private List<DetectedCircle> circles = Collections.emptyList();
        private Latency latency = Latency.zero();
        private MotorAssemblyObservation motorAssembly;
        private boolean anchorTracked;
        private int assemblyStepNumber;
        private BoardObservation boardObservation;
        private RejectionEvidence rejectionEvidence;
        private Object requestTag;

        private Builder(long frameTimestampNanos) {
            this.frameTimestampNanos = frameTimestampNanos;
        }

        Builder identity(String referenceId, String componentId, String displayName) {
            this.referenceId = referenceId;
            this.componentId = componentId;
            this.displayName = displayName;
            return this;
        }

        Builder method(DetectionMethod method) {
            detectionMethod = method;
            return this;
        }

        Builder validation(ValidationState state) {
            validationState = state;
            return this;
        }

        Builder orientation(OrientationClassification classification, double degrees) {
            orientationClassification = classification;
            orientationDegrees = degrees;
            return this;
        }

        Builder polarity(PolarityState state) {
            polarityState = state;
            return this;
        }

        Builder matches(int frameKeypoints, int goodMatches, int inliers, double ratio) {
            frameKeypointCount = frameKeypoints;
            goodMatchCount = goodMatches;
            inlierCount = inliers;
            inlierRatio = ratio;
            return this;
        }

        Builder confidence(double value) {
            confidence = Math.max(0.0, Math.min(1.0, value));
            return this;
        }

        Builder corners(List<VisionPoint> corners) {
            projectedCorners = corners;
            return this;
        }

        Builder instances(List<InstanceObservation> observations) {
            instanceObservations = observations;
            return this;
        }

        Builder sceneDetections(List<InstanceObservation> observations) {
            sceneDetections = observations;
            return this;
        }

        Builder pose(Pose pose) {
            this.pose = pose;
            return this;
        }

        Builder spatialAnchor(SpatialAnchor anchor) {
            spatialAnchor = anchor;
            return this;
        }

        Builder circles(List<DetectedCircle> circles) {
            this.circles = circles;
            return this;
        }

        Builder latency(Latency latency) {
            this.latency = latency;
            return this;
        }

        Builder rejectionEvidence(RejectionEvidence evidence) {
            rejectionEvidence = evidence;
            return this;
        }

        RecognitionResult build() {
            return new RecognitionResult(this);
        }
    }

    /**
     * Immutable rejected-candidate metrics for explaining why the expected part was not detected.
     * Counts of {@code -1} and an inlier ratio of {@link Double#NaN} mean that the corresponding
     * stage was not reached.
     */
    public static final class RejectionEvidence {
        private final RejectionReason reason;
        private final String referenceId;
        private final String componentId;
        private final String displayName;
        private final String photometricVariant;
        private final int frameKeypointCount;
        private final int referenceKeypointCount;
        private final int goodMatchCount;
        private final int validMatchCount;
        private final int inlierCount;
        private final double inlierRatio;
        private final int requiredGoodMatchCount;
        private final int requiredInlierCount;
        private final double requiredInlierRatio;

        RejectionEvidence(RejectionReason reason,
                          String referenceId,
                          String componentId,
                          String displayName,
                          String photometricVariant,
                          int frameKeypointCount,
                          int referenceKeypointCount,
                          int goodMatchCount,
                          int validMatchCount,
                          int inlierCount,
                          double inlierRatio,
                          int requiredGoodMatchCount,
                          int requiredInlierCount,
                          double requiredInlierRatio) {
            this.reason = Objects.requireNonNull(reason, "reason");
            this.referenceId = referenceId;
            this.componentId = Objects.requireNonNull(componentId, "componentId");
            this.displayName = displayName;
            this.photometricVariant = photometricVariant;
            this.frameKeypointCount = frameKeypointCount;
            this.referenceKeypointCount = referenceKeypointCount;
            this.goodMatchCount = goodMatchCount;
            this.validMatchCount = validMatchCount;
            this.inlierCount = inlierCount;
            this.inlierRatio = inlierRatio;
            this.requiredGoodMatchCount = requiredGoodMatchCount;
            this.requiredInlierCount = requiredInlierCount;
            this.requiredInlierRatio = requiredInlierRatio;
        }

        public RejectionReason getReason() { return reason; }
        public String getReferenceId() { return referenceId; }
        public String getComponentId() { return componentId; }
        public String getDisplayName() { return displayName; }
        /** Returns {@code NORMALIZED}, {@code INVERTED}, or {@code null} when no variant ran. */
        public String getPhotometricVariant() { return photometricVariant; }
        public int getFrameKeypointCount() { return frameKeypointCount; }
        public int getReferenceKeypointCount() { return referenceKeypointCount; }
        public int getGoodMatchCount() { return goodMatchCount; }
        public int getValidMatchCount() { return validMatchCount; }
        public int getInlierCount() { return inlierCount; }
        public double getInlierRatio() { return inlierRatio; }
        public int getRequiredGoodMatchCount() { return requiredGoodMatchCount; }
        public int getRequiredInlierCount() { return requiredInlierCount; }
        public double getRequiredInlierRatio() { return requiredInlierRatio; }
    }

    /**
     * One expected-component localization from this exact analyzed frame. Repeated-part assembly
     * steps consume the complete list atomically; observations are never carried across frames.
     */
    public static final class InstanceObservation {
        private final String referenceId;
        private final String componentId;
        private final DetectionMethod detectionMethod;
        private final List<VisionPoint> projectedCorners;
        private final double confidence;

        public InstanceObservation(String referenceId,
                                   String componentId,
                                   DetectionMethod detectionMethod,
                                   List<VisionPoint> projectedCorners,
                                   double confidence) {
            if (referenceId == null || referenceId.trim().isEmpty()
                    || componentId == null || componentId.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "Instance observation requires reference and component identity");
            }
            this.detectionMethod = Objects.requireNonNull(
                    detectionMethod, "detectionMethod");
            if (detectionMethod == DetectionMethod.NONE) {
                throw new IllegalArgumentException(
                        "Instance observation requires a spatial detection method");
            }
            if (projectedCorners == null || projectedCorners.size() != 4) {
                throw new IllegalArgumentException(
                        "Instance observation requires four projected corners");
            }
            for (VisionPoint corner : projectedCorners) {
                Objects.requireNonNull(corner, "projected corner");
            }
            if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException(
                        "Instance confidence must be finite and in [0, 1]");
            }
            this.referenceId = referenceId.trim();
            this.componentId = componentId.trim();
            this.projectedCorners = immutableList(projectedCorners);
            this.confidence = confidence;
        }

        public String getReferenceId() { return referenceId; }
        public String getComponentId() { return componentId; }
        public DetectionMethod getDetectionMethod() { return detectionMethod; }
        public List<VisionPoint> getProjectedCorners() { return projectedCorners; }
        public double getConfidence() { return confidence; }
    }

    /** A simultaneously recognized chassis/board frame used to anchor spatial guidance. */
    public static final class SpatialAnchor {
        private final String referenceId;
        private final String componentId;
        private final List<VisionPoint> projectedCorners;
        private final Pose pose;

        public SpatialAnchor(String referenceId, String componentId,
                             List<VisionPoint> projectedCorners, Pose pose) {
            if (referenceId == null || referenceId.trim().isEmpty()
                    || componentId == null || componentId.trim().isEmpty()
                    || projectedCorners == null || projectedCorners.size() != 4
                    || pose == null) {
                throw new IllegalArgumentException(
                        "Spatial anchor requires identity, four corners, and a pose");
            }
            this.referenceId = referenceId;
            this.componentId = componentId;
            this.projectedCorners = immutableList(projectedCorners);
            this.pose = pose;
        }

        public String getReferenceId() { return referenceId; }
        public String getComponentId() { return componentId; }
        public List<VisionPoint> getProjectedCorners() { return projectedCorners; }
        public Pose getPose() { return pose; }
    }

    /** solvePnP extrinsics and OpenCV-to-OpenGL converted column-major model-view matrix. */
    public static final class Pose {
        private final double[] rotationVector;
        private final double[] translationVector;
        private final float[] openGlModelViewMatrix;
        private final double reprojectionErrorPx;

        public Pose(double[] rotationVector, double[] translationVector,
                    float[] openGlModelViewMatrix) {
            this(rotationVector, translationVector, openGlModelViewMatrix, Double.NaN);
        }

        /** {@code reprojectionErrorPx} is the RMS corner error of the solve, or NaN if unmeasured. */
        public Pose(double[] rotationVector, double[] translationVector,
                    float[] openGlModelViewMatrix, double reprojectionErrorPx) {
            if (rotationVector == null || rotationVector.length != 3
                    || translationVector == null || translationVector.length != 3
                    || openGlModelViewMatrix == null || openGlModelViewMatrix.length != 16) {
                throw new IllegalArgumentException("Invalid pose array dimensions");
            }
            for (double value : rotationVector) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("Rotation vector must be finite");
                }
            }
            for (double value : translationVector) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("Translation vector must be finite");
                }
            }
            for (float value : openGlModelViewMatrix) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("OpenGL model-view matrix must be finite");
                }
            }
            this.rotationVector = rotationVector.clone();
            this.translationVector = translationVector.clone();
            this.openGlModelViewMatrix = openGlModelViewMatrix.clone();
            this.reprojectionErrorPx = reprojectionErrorPx;
        }

        public double[] getRotationVector() { return rotationVector.clone(); }
        public double[] getTranslationVector() { return translationVector.clone(); }
        public float[] getOpenGlModelViewMatrix() { return openGlModelViewMatrix.clone(); }
        public double getReprojectionErrorPx() { return reprojectionErrorPx; }
    }

    /** Circle produced by the Canny/Hough fallback in analyzed-frame pixels. */
    public static final class DetectedCircle {
        private final VisionPoint center;
        private final double radiusPx;

        public DetectedCircle(VisionPoint center, double radiusPx) {
            if (center == null || !(radiusPx > 0.0) || !Double.isFinite(radiusPx)) {
                throw new IllegalArgumentException("Invalid detected circle");
            }
            this.center = center;
            this.radiusPx = radiusPx;
        }

        public VisionPoint getCenter() { return center; }
        public double getRadiusPx() { return radiusPx; }
    }

    /** Per-stage wall-clock timings gathered for every analyzed frame. */
    public static final class Latency {
        private final double featureExtractionMs;
        private final double matchingMs;
        private final double spatialValidationMs;
        private final double circularFallbackMs;
        private final double totalMs;

        public Latency(double featureExtractionMs, double matchingMs,
                       double spatialValidationMs, double circularFallbackMs,
                       double totalMs) {
            this.featureExtractionMs = nonNegative(featureExtractionMs);
            this.matchingMs = nonNegative(matchingMs);
            this.spatialValidationMs = nonNegative(spatialValidationMs);
            this.circularFallbackMs = nonNegative(circularFallbackMs);
            this.totalMs = nonNegative(totalMs);
        }

        static Latency zero() {
            return new Latency(0.0, 0.0, 0.0, 0.0, 0.0);
        }

        public double getFeatureExtractionMs() { return featureExtractionMs; }
        public double getMatchingMs() { return matchingMs; }
        public double getSpatialValidationMs() { return spatialValidationMs; }
        public double getCircularFallbackMs() { return circularFallbackMs; }
        public double getTotalMs() { return totalMs; }

        private static double nonNegative(double value) {
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException("Latency values must be finite and non-negative");
            }
            return value;
        }
    }
}
