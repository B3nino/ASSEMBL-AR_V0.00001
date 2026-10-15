package dev.chyken.assembl_ar.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.chyken.assembl_ar.vision.RecognitionResult;

/**
 * In-memory observations from testing one loose part at a time against the part catalog.
 * These are observed match rates, not accuracy measurements: selecting a target does not
 * establish which physical objects were actually visible to the camera.
 */
public final class PartRecognitionTestSession {
    public static final int REQUIRED_STABLE_FRAMES = 4;

    private final Map<String, PartStats> parts = new LinkedHashMap<>();
    private long generation;
    private PartStats active;
    private VisualPlacementGate gate;
    private boolean hasTimestamp;
    private long lastTimestamp;
    private boolean hasActiveTimestamp;
    private long lastActiveTimestamp;
    private boolean countedCurrentLock;

    /** Starts a fresh lock attempt while retaining this part's earlier observations. */
    public synchronized long selectPart(String componentId, String displayName,
                                        float minimumProjectedArea) {
        requireText(componentId, "componentId");
        requireText(displayName, "displayName");
        if (!(minimumProjectedArea > 0f) || minimumProjectedArea >= .82f) {
            throw new IllegalArgumentException("Invalid minimum projected area");
        }
        pause();
        PartStats stats = parts.get(componentId);
        if (stats == null) {
            stats = new PartStats(componentId, displayName, minimumProjectedArea);
            parts.put(componentId, stats);
        } else {
            stats.displayName = displayName;
            stats.minimumProjectedArea = minimumProjectedArea;
        }
        active = stats;
        gate = new VisualPlacementGate(REQUIRED_STABLE_FRAMES);
        return generation;
    }

    /** Invalidates in-flight camera callbacks and ends the current lock attempt. */
    public synchronized void pause() {
        generation++;
        if (active != null) active.lastObservation = null;
        active = null;
        gate = null;
        hasActiveTimestamp = false;
        countedCurrentLock = false;
    }

    /** Clear only this part; callers select it again to resume after resetting. */
    public synchronized void resetPart(String componentId) {
        if (active != null && active.componentId.equals(componentId)) pause();
        parts.remove(componentId);
    }

    /**
     * Records one analyzed frame using its capture timestamp and preview-normalized corners.
     * The caller must pass the token captured with the recognition request. Results from a
     * previous selection, duplicates and out-of-order frames are ignored without changing stats.
     */
    public synchronized boolean observe(long requestGeneration, RecognitionResult result,
                                         float[] normalizedCorners) {
        Objects.requireNonNull(result, "result");
        if (active == null || requestGeneration != generation) return false;
        long timestamp = result.getFrameTimestampNanos();
        if (hasTimestamp && timestamp <= lastTimestamp) return false;
        hasTimestamp = true;
        lastTimestamp = timestamp;
        if (hasActiveTimestamp) active.observedNanos += timestamp - lastActiveTimestamp;
        lastActiveTimestamp = timestamp;
        hasActiveTimestamp = true;

        PartStats stats = active;
        stats.analyzedFrames++;
        stats.totalLatencyMs += result.getLatency().getTotalMs();
        stats.featureLatencyMs += result.getLatency().getFeatureExtractionMs();
        stats.matchingLatencyMs += result.getLatency().getMatchingMs();
        stats.spatialLatencyMs += result.getLatency().getSpatialValidationMs();
        stats.fallbackLatencyMs += result.getLatency().getCircularFallbackMs();

        RecognitionResult.DetectionMethod method = result.getDetectionMethod();
        boolean detected = result.hasLocatedComponent();
        boolean expected = detected && stats.componentId.equals(result.getComponentId());
        RecognitionResult.RejectionEvidence rejection = result.getRejectionEvidence();
        if (rejection != null) {
            String reason = rejection.getReason().name();
            Long prior = stats.rejectionReasons.get(reason);
            stats.rejectionReasons.put(reason, prior == null ? 1L : prior + 1L);
            stats.lastRejection = rejection;
        }
        if (detected && isOrbMethod(method)) {
            stats.orbFrames++;
        } else if (detected && isFallbackMethod(method)) {
            stats.fallbackFrames++;
        }

        if (expected) {
            stats.expectedFrames++;
            if (isOrbMethod(method)) {
                stats.expectedOrbFrames++;
            } else if (isFallbackMethod(method)) {
                stats.expectedFallbackFrames++;
            }
            if (Double.isFinite(result.getConfidence())) {
                stats.confidenceTotal += result.getConfidence();
                stats.confidenceSamples++;
            }
            stats.inlierTotal += result.getInlierCount();
            stats.goodMatchTotal += result.getGoodMatchCount();
            if (result.getPose() != null) stats.poseFrames++;
            stats.lastObservation = gate.observe(stats.componentId, normalizedCorners,
                    result.getPose() == null ? null : result.getPose().getTranslationVector(),
                    true, stats.minimumProjectedArea, timestamp);
        } else {
            if (detected) {
                stats.wrongComponentFrames++;
                Long prior = stats.wrongComponents.get(result.getComponentId());
                stats.wrongComponents.put(result.getComponentId(), prior == null ? 1L : prior + 1L);
                // A conflicting identity cannot bridge two runs of expected-part evidence.
                gate.reset();
            } else {
                stats.noDetectionFrames++;
            }
            stats.lastObservation = gate.observeUnavailable(timestamp);
        }

        if (stats.lastObservation.getStableFrames() < REQUIRED_STABLE_FRAMES) {
            countedCurrentLock = false;
        }
        if (stats.lastObservation.isReady()) {
            stats.lockedFrames++;
            if (!countedCurrentLock) {
                stats.stableLockCount++;
                countedCurrentLock = true;
                if (Double.isNaN(stats.firstLockMillis)) {
                    stats.firstLockMillis = stats.observedNanos / 1_000_000.0;
                }
            }
        }
        return true;
    }

    private static boolean isOrbMethod(RecognitionResult.DetectionMethod method) {
        return method == RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY;
    }

    private static boolean isFallbackMethod(RecognitionResult.DetectionMethod method) {
        return method == RecognitionResult.DetectionMethod.CANNY_HOUGH_FALLBACK
                || method == RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK;
    }

    public synchronized long getGeneration() { return generation; }

    /** Returns null when this part has not been selected (or was reset). */
    public synchronized Snapshot getSnapshot(String componentId) {
        PartStats stats = parts.get(componentId);
        return stats == null ? null : new Snapshot(stats);
    }

    public synchronized List<Snapshot> getSnapshots() {
        List<Snapshot> snapshots = new ArrayList<>();
        for (PartStats stats : parts.values()) snapshots.add(new Snapshot(stats));
        return Collections.unmodifiableList(snapshots);
    }

    /** CSV contains one row per selected part, with explicit denominators and no ratings. */
    public synchronized String toCsv() {
        return toCsv(Collections.emptyMap());
    }

    /** Includes reference provenance so contextual baselines cannot be mistaken for clean captures. */
    public synchronized String toCsv(Map<String, String> referenceBasis) {
        Objects.requireNonNull(referenceBasis, "referenceBasis");
        StringBuilder csv = new StringBuilder("component_id,display_name,analyzed_frames,"
                + "expected_frames,wrong_component_frames,no_detection_frames,expected_match_rate,"
                + "expected_pose_frames,locked_frames,stable_lock_count,first_lock_ms,"
                + "observed_duration_ms,orb_frames,fallback_frames,expected_orb_frames,"
                + "expected_fallback_frames,mean_expected_confidence,mean_expected_inliers,"
                + "mean_expected_good_matches,mean_total_latency_ms,mean_feature_latency_ms,"
                + "mean_matching_latency_ms,mean_spatial_latency_ms,mean_fallback_latency_ms,"
                + "rejection_reason_counts,last_rejection_reference,last_rejected_good_matches,"
                + "last_rejected_valid_matches,last_rejected_inliers,reference_basis,"
                + "wrong_component_counts\r\n");
        for (Snapshot s : getSnapshots()) {
            csv.append(csvText(s.getComponentId())).append(',')
                    .append(csvText(s.getDisplayName())).append(',')
                    .append(s.getAnalyzedFrames()).append(',')
                    .append(s.getExpectedFrames()).append(',')
                    .append(s.getWrongComponentFrames()).append(',')
                    .append(s.getNoDetectionFrames()).append(',')
                    .append(csvNumber(s.getExpectedMatchRate())).append(',')
                    .append(s.getPoseFrames()).append(',')
                    .append(s.getLockedFrames()).append(',')
                    .append(s.getStableLockCount()).append(',')
                    .append(csvNumber(s.getFirstLockMillis())).append(',')
                    .append(s.getObservedDurationMillis()).append(',')
                    .append(s.getOrbFrames()).append(',')
                    .append(s.getFallbackFrames()).append(',')
                    .append(s.getExpectedOrbFrames()).append(',')
                    .append(s.getExpectedFallbackFrames()).append(',')
                    .append(csvNumber(s.getAverageConfidence())).append(',')
                    .append(csvNumber(s.getAverageInlierCount())).append(',')
                    .append(csvNumber(s.getAverageGoodMatchCount())).append(',')
                    .append(csvNumber(s.getAverageLatencyMs())).append(',')
                    .append(csvNumber(s.getAverageFeatureLatencyMs())).append(',')
                    .append(csvNumber(s.getAverageMatchingLatencyMs())).append(',')
                    .append(csvNumber(s.getAverageSpatialLatencyMs())).append(',')
                    .append(csvNumber(s.getAverageFallbackLatencyMs())).append(',');
            StringBuilder rejections = new StringBuilder();
            for (Map.Entry<String, Long> entry : s.getRejectionReasons().entrySet()) {
                if (rejections.length() > 0) rejections.append("; ");
                rejections.append(entry.getKey()).append(':').append(entry.getValue());
            }
            RecognitionResult.RejectionEvidence lastRejection = s.getLastRejection();
            csv.append(csvText(rejections.toString())).append(',')
                    .append(csvText(lastRejection == null || lastRejection.getReferenceId() == null
                            ? "" : lastRejection.getReferenceId())).append(',')
                    .append(lastRejection == null ? "" : lastRejection.getGoodMatchCount()).append(',')
                    .append(lastRejection == null ? "" : lastRejection.getValidMatchCount()).append(',')
                    .append(lastRejection == null ? "" : lastRejection.getInlierCount()).append(',');
            csv.append(csvText(referenceBasis.getOrDefault(s.getComponentId(), "unspecified"))).append(',');
            StringBuilder confusions = new StringBuilder();
            for (Map.Entry<String, Long> entry : s.getWrongComponents().entrySet()) {
                if (confusions.length() > 0) confusions.append("; ");
                confusions.append(entry.getKey()).append(':').append(entry.getValue());
            }
            csv.append(csvText(confusions.toString())).append("\r\n");
        }
        return csv.toString();
    }

    private static String csvNumber(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "";
    }

    private static String csvText(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
        if (index < value.length() && "=+-@".indexOf(value.charAt(index)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is empty");
    }

    private static double average(double total, long count) {
        return count == 0 ? Double.NaN : total / count;
    }

    private static final class PartStats {
        private final String componentId;
        private String displayName;
        private float minimumProjectedArea;
        private long analyzedFrames, expectedFrames, wrongComponentFrames, noDetectionFrames;
        private long poseFrames, lockedFrames, stableLockCount, observedNanos;
        private long orbFrames, fallbackFrames, expectedOrbFrames, expectedFallbackFrames;
        private long confidenceSamples;
        private double firstLockMillis = Double.NaN;
        private double confidenceTotal, inlierTotal, goodMatchTotal;
        private double totalLatencyMs, featureLatencyMs, matchingLatencyMs, spatialLatencyMs, fallbackLatencyMs;
        private final Map<String, Long> wrongComponents = new LinkedHashMap<>();
        private final Map<String, Long> rejectionReasons = new LinkedHashMap<>();
        private RecognitionResult.RejectionEvidence lastRejection;
        private VisualPlacementGate.Observation lastObservation;

        private PartStats(String componentId, String displayName, float minimumProjectedArea) {
            this.componentId = componentId;
            this.displayName = displayName;
            this.minimumProjectedArea = minimumProjectedArea;
        }
    }

    /** Immutable snapshot; confidence and match means use expected-part frames only. */
    public static final class Snapshot {
        private final String componentId, displayName;
        private final long analyzedFrames, expectedFrames, wrongComponentFrames, noDetectionFrames;
        private final long poseFrames, lockedFrames, stableLockCount, orbFrames, fallbackFrames;
        private final long expectedOrbFrames, expectedFallbackFrames;
        private final double firstLockMillis, observedDurationMillis, averageConfidence;
        private final double averageInlierCount, averageGoodMatchCount, averageLatencyMs;
        private final double averageFeatureLatencyMs, averageMatchingLatencyMs;
        private final double averageSpatialLatencyMs, averageFallbackLatencyMs;
        private final Map<String, Long> wrongComponents;
        private final Map<String, Long> rejectionReasons;
        private final RecognitionResult.RejectionEvidence lastRejection;
        private final VisualPlacementGate.Observation lastObservation;

        private Snapshot(PartStats s) {
            componentId = s.componentId;
            displayName = s.displayName;
            analyzedFrames = s.analyzedFrames;
            expectedFrames = s.expectedFrames;
            wrongComponentFrames = s.wrongComponentFrames;
            noDetectionFrames = s.noDetectionFrames;
            poseFrames = s.poseFrames;
            lockedFrames = s.lockedFrames;
            stableLockCount = s.stableLockCount;
            orbFrames = s.orbFrames;
            fallbackFrames = s.fallbackFrames;
            expectedOrbFrames = s.expectedOrbFrames;
            expectedFallbackFrames = s.expectedFallbackFrames;
            firstLockMillis = s.firstLockMillis;
            observedDurationMillis = s.observedNanos / 1_000_000.0;
            averageConfidence = average(s.confidenceTotal, s.confidenceSamples);
            averageInlierCount = average(s.inlierTotal, s.expectedFrames);
            averageGoodMatchCount = average(s.goodMatchTotal, s.expectedFrames);
            averageLatencyMs = average(s.totalLatencyMs, s.analyzedFrames);
            averageFeatureLatencyMs = average(s.featureLatencyMs, s.analyzedFrames);
            averageMatchingLatencyMs = average(s.matchingLatencyMs, s.analyzedFrames);
            averageSpatialLatencyMs = average(s.spatialLatencyMs, s.analyzedFrames);
            averageFallbackLatencyMs = average(s.fallbackLatencyMs, s.analyzedFrames);
            wrongComponents = Collections.unmodifiableMap(new LinkedHashMap<>(s.wrongComponents));
            rejectionReasons = Collections.unmodifiableMap(new LinkedHashMap<>(s.rejectionReasons));
            lastRejection = s.lastRejection;
            lastObservation = s.lastObservation;
        }

        public String getComponentId() { return componentId; }
        public String getDisplayName() { return displayName; }
        public long getAnalyzedFrames() { return analyzedFrames; }
        public long getExpectedFrames() { return expectedFrames; }
        public long getWrongComponentFrames() { return wrongComponentFrames; }
        public long getNoDetectionFrames() { return noDetectionFrames; }
        public long getPoseFrames() { return poseFrames; }
        public long getLockedFrames() { return lockedFrames; }
        public long getStableLockCount() { return stableLockCount; }
        public long getOrbFrames() { return orbFrames; }
        public long getFallbackFrames() { return fallbackFrames; }
        public long getExpectedOrbFrames() { return expectedOrbFrames; }
        public long getExpectedFallbackFrames() { return expectedFallbackFrames; }
        public double getExpectedMatchRate() { return average(expectedFrames, analyzedFrames); }
        /** Captured-frame span while sampling this part, excluding pauses and other selections. */
        public double getFirstLockMillis() { return firstLockMillis; }
        public double getObservedDurationMillis() { return observedDurationMillis; }
        public double getAverageConfidence() { return averageConfidence; }
        public double getAverageInlierCount() { return averageInlierCount; }
        public double getAverageGoodMatchCount() { return averageGoodMatchCount; }
        public double getAverageLatencyMs() { return averageLatencyMs; }
        public double getAverageFeatureLatencyMs() { return averageFeatureLatencyMs; }
        public double getAverageMatchingLatencyMs() { return averageMatchingLatencyMs; }
        public double getAverageSpatialLatencyMs() { return averageSpatialLatencyMs; }
        public double getAverageFallbackLatencyMs() { return averageFallbackLatencyMs; }
        public Map<String, Long> getWrongComponents() { return wrongComponents; }
        public Map<String, Long> getRejectionReasons() { return rejectionReasons; }
        public RecognitionResult.RejectionEvidence getLastRejection() { return lastRejection; }
        public VisualPlacementGate.Observation getLastObservation() { return lastObservation; }
        public int getStableFrames() { return lastObservation == null ? 0 : lastObservation.getStableFrames(); }
        public boolean isLocked() { return lastObservation != null && lastObservation.isReady(); }
    }
}
