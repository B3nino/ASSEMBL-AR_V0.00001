package dev.chyken.assembl_ar.vision;

import java.util.Objects;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable per-frame assembly context supplied to the recognizer. */
public final class RecognitionRequest {
    private final AssemblyExpectation expectation;
    private final CameraCalibration cameraCalibration;
    private final PolarityObservation polarityObservation;
    private final String spatialAnchorComponentId;
    private final int expectedInstanceCount;
    private final int assemblyStepNumber;
    private final Double minimumCornerAreaRatioOverride;
    private final boolean descriptorOnlyIdentityAllowed;
    private int guidedBracketSlot;
    private List<VisionPoint> guidedBracketWorkplane = Collections.emptyList();
    private Object requestTag;
    private boolean installedMotorInspection;
    private int phase2Stage;
    public int getPhase2Stage() { return phase2Stage; }
    public RecognitionRequest withPhase2Stage(int stage) {
        if(stage!=1&&stage!=2&&stage!=5) throw new IllegalArgumentException("Phase 2 scanning stage");
        String target=stage==1?"motor_bracket":stage==2?"gear_motor_6v":"main_board";
        int count=stage==1?4:stage==2?2:1;
        if(assemblyStepNumber!=2||expectation==null||!target.equals(expectation.getComponentId())
                ||expectedInstanceCount!=count||spatialAnchorComponentId!=null)
            throw new IllegalArgumentException("Phase 2 scene request requires the matching component/count and no AR anchor");
        RecognitionRequest copy=copy(); copy.phase2Stage=stage; return copy;
    }

    public RecognitionRequest(AssemblyExpectation expectation,
                              CameraCalibration cameraCalibration,
                              PolarityObservation polarityObservation) {
        this(expectation, cameraCalibration, polarityObservation, null);
    }

    public RecognitionRequest(AssemblyExpectation expectation,
                              CameraCalibration cameraCalibration,
                              PolarityObservation polarityObservation,
                              String spatialAnchorComponentId) {
        this(expectation, cameraCalibration, polarityObservation,
                spatialAnchorComponentId, 1);
    }

    public RecognitionRequest(AssemblyExpectation expectation,
                              CameraCalibration cameraCalibration,
                              PolarityObservation polarityObservation,
                              String spatialAnchorComponentId,
                              int expectedInstanceCount) {
        this(expectation, cameraCalibration, polarityObservation, spatialAnchorComponentId,
                expectedInstanceCount, 0);
    }

    public RecognitionRequest(AssemblyExpectation expectation,
                              CameraCalibration cameraCalibration,
                              PolarityObservation polarityObservation,
                              String spatialAnchorComponentId,
                              int expectedInstanceCount, int assemblyStepNumber) {
        this(expectation, cameraCalibration, polarityObservation, spatialAnchorComponentId,
                expectedInstanceCount, assemblyStepNumber, null, false);
    }

    /**
     * Creates a request with an optional projected-area floor for diagnostic capture testing.
     * Production assembly requests leave the override null and continue to use VisionConfig.
     */
    public RecognitionRequest(AssemblyExpectation expectation,
                              CameraCalibration cameraCalibration,
                              PolarityObservation polarityObservation,
                              String spatialAnchorComponentId,
                              int expectedInstanceCount, int assemblyStepNumber,
                              Double minimumCornerAreaRatioOverride) {
        this(expectation, cameraCalibration, polarityObservation, spatialAnchorComponentId,
                expectedInstanceCount, assemblyStepNumber, minimumCornerAreaRatioOverride, false);
    }

    public RecognitionRequest(AssemblyExpectation expectation,
                              CameraCalibration cameraCalibration,
                              PolarityObservation polarityObservation,
                              String spatialAnchorComponentId,
                              int expectedInstanceCount, int assemblyStepNumber,
                              Double minimumCornerAreaRatioOverride,
                              boolean descriptorOnlyIdentityAllowed) {
        this.expectation = expectation;
        this.cameraCalibration = cameraCalibration;
        this.polarityObservation = polarityObservation;
        if (spatialAnchorComponentId != null && spatialAnchorComponentId.trim().isEmpty()) {
            throw new IllegalArgumentException("Spatial anchor component id cannot be blank");
        }
        this.spatialAnchorComponentId = spatialAnchorComponentId == null
                ? null : spatialAnchorComponentId.trim();
        if (expectedInstanceCount < 1 || expectedInstanceCount > 4) {
            throw new IllegalArgumentException(
                    "Expected instance count must be between one and four");
        }
        if (expectedInstanceCount > 2 && (expectation == null
                || !"motor_bracket".equals(expectation.getComponentId())
                || spatialAnchorComponentId != null)) {
            throw new IllegalArgumentException("Three or four instances require a loose bracket set");
        }
        if (expectedInstanceCount > 1 && expectation == null) {
            throw new IllegalArgumentException(
                    "Repeated-instance recognition requires an expected component");
        }
        this.expectedInstanceCount = expectedInstanceCount;
        if (assemblyStepNumber < 0 || assemblyStepNumber > 18) {
            throw new IllegalArgumentException("Invalid assembly step number");
        }
        this.assemblyStepNumber = assemblyStepNumber;
        if (minimumCornerAreaRatioOverride != null
                && (!(minimumCornerAreaRatioOverride > 0.0)
                || minimumCornerAreaRatioOverride > 1.0
                || !Double.isFinite(minimumCornerAreaRatioOverride))) {
            throw new IllegalArgumentException(
                    "Minimum corner area ratio override must be finite and in (0, 1]");
        }
        this.minimumCornerAreaRatioOverride = minimumCornerAreaRatioOverride;
        this.descriptorOnlyIdentityAllowed = descriptorOnlyIdentityAllowed;
    }

    public static RecognitionRequest unconstrained(CameraCalibration cameraCalibration) {
        return new RecognitionRequest(null, cameraCalibration, null);
    }

    public AssemblyExpectation getExpectation() { return expectation; }
    public CameraCalibration getCameraCalibration() { return cameraCalibration; }
    public PolarityObservation getPolarityObservation() { return polarityObservation; }
    public String getSpatialAnchorComponentId() { return spatialAnchorComponentId; }
    public int getExpectedInstanceCount() { return expectedInstanceCount; }
    public int getAssemblyStepNumber() { return assemblyStepNumber; }
    public Double getMinimumCornerAreaRatioOverride() {
        return minimumCornerAreaRatioOverride;
    }
    public boolean isDescriptorOnlyIdentityAllowed() { return descriptorOnlyIdentityAllowed; }
    public int getGuidedBracketSlot() { return guidedBracketSlot; }
    public List<VisionPoint> getGuidedBracketWorkplane() { return guidedBracketWorkplane; }

    /**
     * Opaque caller token returned unchanged on the result, for example the assembly phase,
     * mode and scan generation that requested this frame.
     */
    public Object getRequestTag() { return requestTag; }

    /** True when the board-relative installed-motor inspector also runs for this frame. */
    public boolean requestsInstalledMotorInspection() {
        return installedMotorInspection || inspectsInstalledMotors();
    }

    /** Uses the independently observed board only to select among already localized brackets. */
    public RecognitionRequest withGuidedBracketWorkplane(List<VisionPoint> corners) {
        if (guidedBracketSlot == 0 || corners == null || corners.size() != 4) {
            throw new IllegalArgumentException("A bracket workplane requires a guided slot and four corners");
        }
        RecognitionRequest copy = withGuidedBracketSlot(guidedBracketSlot);
        copy.guidedBracketWorkplane = Collections.unmodifiableList(new ArrayList<>(corners));
        return copy;
    }

    /** Returns a copy whose result carries {@code tag}; recognition itself is unchanged. */
    public RecognitionRequest withRequestTag(Object tag) {
        RecognitionRequest copy = copy();
        copy.requestTag = tag;
        return copy;
    }

    /**
     * Also inspects both installed motors against the main board in this frame, so an accepted
     * motor pair can be revoked while another section-2 target (a bracket slot) is examined.
     * It never changes which component this request identifies.
     */
    public RecognitionRequest withInstalledMotorInspection() {
        if (assemblyStepNumber != 2 || !"main_board".equals(spatialAnchorComponentId)) {
            throw new IllegalArgumentException("Installed motors are inspected on the section-2 main board");
        }
        RecognitionRequest copy = copy();
        copy.installedMotorInspection = true;
        return copy;
    }

    private RecognitionRequest copy() {
        RecognitionRequest copy = new RecognitionRequest(expectation, cameraCalibration,
                polarityObservation, spatialAnchorComponentId, expectedInstanceCount,
                assemblyStepNumber, minimumCornerAreaRatioOverride, descriptorOnlyIdentityAllowed);
        copy.guidedBracketSlot = guidedBracketSlot;
        copy.guidedBracketWorkplane = guidedBracketWorkplane;
        copy.requestTag = requestTag;
        copy.installedMotorInspection = installedMotorInspection;
        copy.phase2Stage=phase2Stage;
        return copy;
    }

    /** A loose group check identifies inventory; it cannot satisfy an installed-slot check. */
    public boolean inspectsLooseBracketSet() {
        return expectedInstanceCount > 1 && expectation != null
                && "motor_bracket".equals(expectation.getComponentId())
                && spatialAnchorComponentId == null
                && expectation.getOrientationDegrees() == null
                && expectation.getPolarityRule() == null;
    }

    /** Returns a separate request scoped to one authored bracket slot; zero leaves it unscoped. */
    public RecognitionRequest withGuidedBracketSlot(int slot) {
        if (slot < 1 || slot > 4 || assemblyStepNumber != 2 || expectation == null
                || !"motor_bracket".equals(expectation.getComponentId())
                || !"main_board".equals(spatialAnchorComponentId) || expectedInstanceCount != 1) {
            throw new IllegalArgumentException("A guided bracket slot requires one bracket on the main board");
        }
        RecognitionRequest copy = copy();
        copy.guidedBracketSlot = slot;
        copy.guidedBracketWorkplane = Collections.emptyList();
        return copy;
    }
    public boolean inspectsInstalledMotors() {
        return assemblyStepNumber == 2 && expectation != null
                && "gear_motor_6v".equals(expectation.getComponentId())
                && "main_board".equals(spatialAnchorComponentId);
    }

    /** Spatial rules compiled for the currently active assembly step. */
    public static final class AssemblyExpectation {
        private final String componentId;
        private final Double orientationDegrees;
        private final Double orientationToleranceDegrees;
        private final PolarityRule polarityRule;

        public AssemblyExpectation(String componentId, Double orientationDegrees,
                                   Double orientationToleranceDegrees,
                                   PolarityRule polarityRule) {
            if (componentId == null || componentId.trim().isEmpty()) {
                throw new IllegalArgumentException("Expected component id cannot be blank");
            }
            if (orientationDegrees != null && !Double.isFinite(orientationDegrees)) {
                throw new IllegalArgumentException("Expected orientation must be finite");
            }
            if (orientationToleranceDegrees != null
                    && (!(orientationToleranceDegrees >= 0.0)
                    || orientationToleranceDegrees > 90.0
                    || !Double.isFinite(orientationToleranceDegrees))) {
                throw new IllegalArgumentException(
                        "Orientation tolerance must be between 0 and 90 degrees");
            }
            this.componentId = componentId.trim();
            this.orientationDegrees = orientationDegrees == null
                    ? null
                    : normalizeDegrees(orientationDegrees);
            this.orientationToleranceDegrees = orientationToleranceDegrees;
            this.polarityRule = polarityRule;
        }

        public static AssemblyExpectation component(String componentId) {
            return new AssemblyExpectation(componentId, null, null, null);
        }

        public String getComponentId() { return componentId; }
        public Double getOrientationDegrees() { return orientationDegrees; }
        public Double getOrientationToleranceDegrees() { return orientationToleranceDegrees; }
        public PolarityRule getPolarityRule() { return polarityRule; }

        private static double normalizeDegrees(double degrees) {
            double normalized = degrees % 360.0;
            if (normalized <= -180.0) normalized += 360.0;
            if (normalized > 180.0) normalized -= 360.0;
            return normalized;
        }
    }

    /**
     * Positive/negative anchors for a step's authored polarity rule. Anchor coordinates are
     * normalized to [0, 1] over the component quadrilateral, not over incidental background in
     * the registered source photo. This keeps the same rule aligned across cropped reference views.
     */
    public static final class PolarityRule {
        private final VisionPoint positiveAnchor;
        private final VisionPoint negativeAnchor;
        private final double maximumNormalizedError;
        private final double swappedAssignmentMargin;

        public PolarityRule(VisionPoint positiveAnchor, VisionPoint negativeAnchor,
                            double maximumNormalizedError, double swappedAssignmentMargin) {
            this.positiveAnchor = requireNormalized(positiveAnchor, "positiveAnchor");
            this.negativeAnchor = requireNormalized(negativeAnchor, "negativeAnchor");
            if (this.positiveAnchor.equals(this.negativeAnchor)) {
                throw new IllegalArgumentException("Polarity anchors must be distinct");
            }
            if (!(maximumNormalizedError > 0.0) || maximumNormalizedError > 1.0
                    || !Double.isFinite(maximumNormalizedError)) {
                throw new IllegalArgumentException(
                        "Maximum normalized polarity error must be in (0, 1]");
            }
            if (!(swappedAssignmentMargin >= 0.0) || swappedAssignmentMargin > 1.0
                    || !Double.isFinite(swappedAssignmentMargin)) {
                throw new IllegalArgumentException(
                        "Swapped assignment margin must be in [0, 1]");
            }
            this.maximumNormalizedError = maximumNormalizedError;
            this.swappedAssignmentMargin = swappedAssignmentMargin;
        }

        public VisionPoint getPositiveAnchor() { return positiveAnchor; }
        public VisionPoint getNegativeAnchor() { return negativeAnchor; }
        public double getMaximumNormalizedError() { return maximumNormalizedError; }
        public double getSwappedAssignmentMargin() { return swappedAssignmentMargin; }

        private static VisionPoint requireNormalized(VisionPoint point, String name) {
            Objects.requireNonNull(point, name);
            if (point.getX() < 0.0 || point.getX() > 1.0
                    || point.getY() < 0.0 || point.getY() > 1.0) {
                throw new IllegalArgumentException(name + " must be normalized to [0, 1]");
            }
            return point;
        }
    }

    /**
     * Observed positive/negative marker centers in analyzed-frame pixel coordinates.
     * A wire-color or terminal-marker detector can provide these without mutating this core.
     */
    public static final class PolarityObservation {
        private final VisionPoint positivePoint;
        private final VisionPoint negativePoint;

        public PolarityObservation(VisionPoint positivePoint, VisionPoint negativePoint) {
            this.positivePoint = Objects.requireNonNull(positivePoint, "positivePoint");
            this.negativePoint = Objects.requireNonNull(negativePoint, "negativePoint");
            if (positivePoint.equals(negativePoint)) {
                throw new IllegalArgumentException("Observed polarity points must be distinct");
            }
        }

        public VisionPoint getPositivePoint() { return positivePoint; }
        public VisionPoint getNegativePoint() { return negativePoint; }
    }
}
