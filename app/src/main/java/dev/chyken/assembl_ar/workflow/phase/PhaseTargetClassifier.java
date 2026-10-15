package dev.chyken.assembl_ar.workflow.phase;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import dev.chyken.assembl_ar.vision.InstalledModuleInspector;
import dev.chyken.assembl_ar.vision.MotorAssemblyObservation;
import dev.chyken.assembl_ar.vision.RecognitionResult.ValidationState;
import dev.chyken.assembl_ar.vision.VisionPoint;
import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate;
import dev.chyken.assembl_ar.workflow.ChassisRelativePlacementGate.TargetMatch;
import dev.chyken.assembl_ar.workflow.phase.AssemblyPhase.VisualTarget;
import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession.Observation;
import dev.chyken.assembl_ar.workflow.phase.PhaseVerificationSession.Outcome;

/**
 * Converts the existing recognizers' per-frame evidence into per-target phase outcomes.
 * Readiness still comes from the existing stability gates; this class only says, for a frame,
 * whether a target looks correct, measurably wrong, or simply not visible enough to judge.
 */
public final class PhaseTargetClassifier {
    private PhaseTargetClassifier() { }

    /** Installed-motor evidence for one side from the board-relative motor inspector. */
    public static Observation motorSlot(VisualTarget target,
                                        @Nullable MotorAssemblyObservation observation,
                                        boolean left, boolean anchorReady, boolean pairReady) {
        if (observation == null || !anchorReady) {
            return Observation.of(target.getId(), Outcome.UNOBSERVABLE,
                    "Keep the board center and both motor positions visible.");
        }
        if (observation.getQuality() != MotorAssemblyObservation.Quality.GOOD) {
            return Observation.of(target.getId(), Outcome.UNOBSERVABLE,
                    qualityMessage(observation.getQuality()));
        }
        MotorAssemblyObservation.Slot slot = left ? observation.getLeft() : observation.getRight();
        switch (slot.getState()) {
            case PRESENT:
                return new Observation(target.getId(), Outcome.ACCEPTED, pairReady,
                        pairReady ? "" : "Checking both motor positions", null);
            case MISALIGNED:
                return Observation.of(target.getId(), Outcome.WRONG_POSITION, slot.getMessage());
            case MISSING:
                return Observation.of(target.getId(), Outcome.MISSING, slot.getMessage());
            case ORIENTATION_UNCONFIRMED:
            default:
                return Observation.of(target.getId(), Outcome.AMBIGUOUS, slot.getMessage());
        }
    }

    /**
     * An installed bracket either satisfies its slot or is not yet confirmed. The slot request
     * only localizes brackets inside that lane, so absence there is not proof of a wrong slot.
     */
    public static Observation bracketSlot(VisualTarget target, boolean anchorVisible,
                                          boolean bracketInSlot, boolean gateReady) {
        if (!anchorVisible) {
            return Observation.of(target.getId(), Outcome.UNOBSERVABLE,
                    "Show the complete main board with " + target.getLabel() + ".");
        }
        if (!bracketInSlot) {
            return Observation.of(target.getId(), Outcome.UNOBSERVABLE,
                    "Show " + target.getLabel() + " clearly from a 30–45° side angle.");
        }
        return new Observation(target.getId(), Outcome.ACCEPTED, gateReady,
                gateReady ? "" : "Hold steady on " + target.getLabel(), null);
    }

    /** What one analysed frame showed about a board-relative placement group. */
    public static final class PlacementFrame {
        @Nullable final List<VisionPoint> anchorCorners;
        @Nullable final String detectedComponentId;
        final ValidationState validation;
        final List<List<VisionPoint>> expectedQuads;
        @Nullable final List<VisionPoint> primaryQuad;

        /**
         * @param anchorCorners fresh board/extension-board corners, or null when not detected
         * @param detectedComponentId the step-scoped primary identity of this frame
         * @param expectedQuads distinct localizations of the expected component in this frame
         * @param primaryQuad outline of the primary identity (used for a wrong part at a target)
         */
        public PlacementFrame(@Nullable List<VisionPoint> anchorCorners,
                              @Nullable String detectedComponentId, ValidationState validation,
                              List<List<VisionPoint>> expectedQuads,
                              @Nullable List<VisionPoint> primaryQuad) {
            this.anchorCorners = anchorCorners == null || anchorCorners.size() != 4
                    ? null : anchorCorners;
            this.detectedComponentId = detectedComponentId;
            this.validation = Objects.requireNonNull(validation, "validation");
            this.expectedQuads = expectedQuads == null ? Collections.emptyList() : expectedQuads;
            this.primaryQuad = primaryQuad == null || primaryQuad.size() != 4 ? null : primaryQuad;
        }
    }

    /**
     * Flat modules on the board are checked from above: the step 1-8 kit photos found the board in
     * 91% of top-down views but 23% of angled ones, at every assembly stage.
     */
    public static String anchorMissingPrompt(String anchorName, String targetLabel,
                                             String componentId) {
        String anchor = anchorName.toLowerCase(java.util.Locale.US);
        return InstalledModuleInspector.supports(componentId)
                ? "Hold the phone straight above the complete " + anchor + " so the "
                        + targetLabel + " can be checked."
                : "Show the complete " + anchor + " so the " + targetLabel + " can be checked.";
    }

    /**
     * Per-target outcomes for one section placement group. {@code groupReady} means the existing
     * anchor, placement and repeated-frame gates all accepted the complete group in this frame.
     */
    public static List<Observation> sectionPlacement(ChassisRelativePlacementGate gate,
                                                     List<VisualTarget> targets,
                                                     PlacementFrame frame, boolean groupReady) {
        List<Observation> observations = new ArrayList<>();
        if (targets.isEmpty()) return observations;
        String expectedId = targets.get(0).getComponentId();
        if (groupReady) {
            for (VisualTarget target : targets) observations.add(Observation.ready(target.getId()));
            return observations;
        }
        if (frame.anchorCorners == null) {
            String anchorName = AssemblyPhaseCatalog.componentName(targets.get(0).getAnchorComponentId());
            for (VisualTarget target : targets) {
                observations.add(Observation.of(target.getId(), Outcome.UNOBSERVABLE,
                        anchorMissingPrompt(anchorName, target.getLabel(), expectedId)));
            }
            return observations;
        }
        boolean expectedIdentity = expectedId.equals(frame.detectedComponentId);
        VisualTarget wrongIdentityTarget = null;
        if (!expectedIdentity && frame.detectedComponentId != null && frame.primaryQuad != null
                && frame.validation != ValidationState.NO_COMPONENT) {
            wrongIdentityTarget = occupiedTarget(gate, targets, frame.anchorCorners, frame.primaryQuad);
        }
        List<TargetMatch> matches = assign(gate, targets, frame.anchorCorners, frame.expectedQuads);
        for (int index = 0; index < targets.size(); index++) {
            VisualTarget target = targets.get(index);
            TargetMatch match = matches.get(index);
            if (target == wrongIdentityTarget) {
                observations.add(new Observation(target.getId(), Outcome.WRONG_IDENTITY, false,
                        "Wrong part at the " + target.getLabel() + ": this position needs the "
                                + AssemblyPhaseCatalog.componentName(expectedId) + ".",
                        frame.detectedComponentId));
                continue;
            }
            if (match == null) {
                observations.add(Observation.of(target.getId(), Outcome.UNOBSERVABLE,
                        "The " + target.getLabel() + " is not visible yet; show it with the whole board."));
                continue;
            }
            observations.add(classifyMatch(target, match, frame.validation, expectedIdentity));
        }
        return observations;
    }

    private static Observation classifyMatch(VisualTarget target, TargetMatch match,
                                             ValidationState validation, boolean expectedIdentity) {
        if (expectedIdentity && validation == ValidationState.WRONG_ORIENTATION) {
            return Observation.of(target.getId(), Outcome.WRONG_ORIENTATION,
                    "The " + target.getLabel() + " is turned the wrong way.");
        }
        if (expectedIdentity && validation == ValidationState.POLARITY_ERROR) {
            return Observation.of(target.getId(), Outcome.WRONG_ORIENTATION,
                    "The " + target.getLabel() + " is reversed; swap the marked ends.");
        }
        if (expectedIdentity && (validation == ValidationState.ORIENTATION_UNCONFIRMED
                || validation == ValidationState.POLARITY_UNCONFIRMED)) {
            return Observation.of(target.getId(), Outcome.AMBIGUOUS,
                    "Direction of the " + target.getLabel() + " is unclear; show its markings.");
        }
        if (!match.isPositionAccepted()) {
            return Observation.of(target.getId(), Outcome.WRONG_POSITION,
                    "Move the " + target.getLabel() + " to its highlighted position.");
        }
        if (!match.isOrientationAccepted()) {
            return Observation.of(target.getId(), Outcome.WRONG_ORIENTATION,
                    "Rotate the " + target.getLabel() + " by about "
                            + Math.round(match.getOrientationErrorDegrees()) + "°.");
        }
        if (!match.isScaleAccepted()) {
            return Observation.of(target.getId(), Outcome.AMBIGUOUS,
                    "The " + target.getLabel() + " outline is unclear; view the board flat and whole.");
        }
        return new Observation(target.getId(), Outcome.ACCEPTED, false,
                "Hold steady on the " + target.getLabel(), null);
    }

    /**
     * One-to-one assignment of detections to the group's targets by smallest position error.
     * A target without a detection gets null: not seen is different from misplaced.
     */
    static List<TargetMatch> assign(ChassisRelativePlacementGate gate, List<VisualTarget> targets,
                                    List<VisionPoint> anchorCorners,
                                    List<List<VisionPoint>> quads) {
        int targetCount = targets.size();
        TargetMatch[][] candidates = new TargetMatch[targetCount][quads.size()];
        for (int t = 0; t < targetCount; t++) {
            String placementId = targets.get(t).getPlacementTargetId();
            ChassisRelativePlacementGate single = placementId == null
                    ? gate : gate.selectTargets(placementId);
            for (int q = 0; q < quads.size(); q++) {
                ChassisRelativePlacementGate.Decision decision =
                        single.evaluateSingle(anchorCorners, quads.get(q));
                candidates[t][q] = decision.getMatches().isEmpty() ? null : decision.getMatches().get(0);
            }
        }
        TargetMatch[] best = new TargetMatch[targetCount];
        double[] bestCost = {Double.POSITIVE_INFINITY};
        search(candidates, 0, new boolean[quads.size()], new TargetMatch[targetCount], 0.0,
                best, bestCost);
        List<TargetMatch> result = new ArrayList<>(targetCount);
        Collections.addAll(result, best);
        return result;
    }

    private static void search(TargetMatch[][] candidates, int target, boolean[] used,
                               TargetMatch[] current, double cost, TargetMatch[] best,
                               double[] bestCost) {
        if (target == candidates.length) {
            // Prefer assigning more detections; among equal coverage, the smallest total error.
            double adjusted = cost + 1_000.0 * unassigned(current);
            if (adjusted < bestCost[0]) {
                bestCost[0] = adjusted;
                System.arraycopy(current, 0, best, 0, current.length);
            }
            return;
        }
        current[target] = null;
        search(candidates, target + 1, used, current, cost, best, bestCost);
        for (int quad = 0; quad < used.length; quad++) {
            TargetMatch match = candidates[target][quad];
            if (used[quad] || match == null) continue;
            used[quad] = true;
            current[target] = match;
            search(candidates, target + 1, used, current, cost + match.getNormalizedPositionError(),
                    best, bestCost);
            used[quad] = false;
            current[target] = null;
        }
    }

    private static int unassigned(TargetMatch[] matches) {
        int count = 0;
        for (TargetMatch match : matches) if (match == null) count++;
        return count;
    }

    @Nullable
    private static VisualTarget occupiedTarget(ChassisRelativePlacementGate gate,
                                               List<VisualTarget> targets,
                                               List<VisionPoint> anchorCorners,
                                               List<VisionPoint> quad) {
        double centerX = 0.0, centerY = 0.0;
        for (VisionPoint corner : quad) {
            centerX += corner.getX() * 0.25;
            centerY += corner.getY() * 0.25;
        }
        for (ChassisRelativePlacementGate.TargetProjection projection
                : gate.projectTargets(anchorCorners)) {
            if (!insideExpandedQuad(centerX, centerY, projection.getCorners())) continue;
            for (VisualTarget target : targets) {
                if (projection.getId().equals(target.getPlacementTargetId())) return target;
            }
        }
        return null;
    }

    /** A small margin absorbs projected-corner jitter without including a rotated target's AABB. */
    public static boolean insideExpandedQuad(double x, double y, List<VisionPoint> quad) {
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

    public static String qualityMessage(MotorAssemblyObservation.Quality quality) {
        switch (quality) {
            case BLURRED: return "Image is blurred · pause briefly or move slightly farther away";
            case TOO_DARK: return "Add even light over the board and both motors";
            case GLARE: return "Tilt the camera slightly to reduce glare";
            case VIEW_TOO_OBLIQUE: return "View the board more from above to check both motor positions";
            default: return "Keep the board center and both motor positions visible";
        }
    }
}
