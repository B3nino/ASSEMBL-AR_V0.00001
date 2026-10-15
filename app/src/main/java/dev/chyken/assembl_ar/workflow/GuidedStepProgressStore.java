package dev.chyken.assembl_ar.workflow;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.chyken.assembl_ar.model.AssemblyStep;
import dev.chyken.assembl_ar.workflow.GuidedAssemblySequence.GuidedStep;

/** Persists instruction progress and action checkpoints within each manual section. */
public final class GuidedStepProgressStore {
    private static final String PREFERENCES = "guided_assembly_steps";
    private static final String KEY_SEPARATOR = "\u001F";
    private static final String CHECKPOINT_PREFIX = KEY_SEPARATOR + "checkpoint" + KEY_SEPARATOR;
    // v1 accepted loose-part scans for installation actions. Keep the stable cursor, but
    // require each action to satisfy its current placement or physical-check contract.
    private static final String CHECKPOINT_VERSION = "v2" + KEY_SEPARATOR;

    private GuidedStepProgressStore() {
    }

    public static int read(
            Context context,
            String projectId,
            String manualStepId,
            List<GuidedStep> guidedSteps) {
        Objects.requireNonNull(guidedSteps, "guidedSteps");
        if (guidedSteps.isEmpty()) {
            return 0;
        }
        SharedPreferences preferences = preferences(context);
        String key = key(projectId, manualStepId);
        Object stored = preferences.getAll().get(key);
        if (!(stored instanceof String)) {
            // Positional cursors from an older schema may now point at a different action.
            if (stored != null) preferences.edit().remove(key).apply();
            return 0;
        }
        String stableId = (String) stored;
        for (int index = 0; index < guidedSteps.size(); index++) {
            if (stableId.equals(guidedSteps.get(index).getStableId())) {
                return index;
            }
        }
        // The authored action was changed or removed; restart this uncompleted manual section.
        preferences.edit().remove(key).apply();
        return 0;
    }

    public static void write(
            Context context,
            String projectId,
            String manualStepId,
            GuidedStep guidedStep) {
        Objects.requireNonNull(guidedStep, "guidedStep");
        preferences(context).edit()
                .putString(key(projectId, manualStepId), guidedStep.getStableId())
                .apply();
    }

    /**
     * Restores a cursor only as far as the earliest unfinished action checkpoint before it.
     * Legacy completions use older keys, so stronger action gates cannot be skipped.
     * Completed checkpoints remain complete when the learner goes back to review an earlier card.
     */
    public static int readForCheckpoints(
            Context context,
            AssemblyStep manualStep,
            List<GuidedStep> guidedSteps) {
        Objects.requireNonNull(manualStep, "manualStep");
        int savedIndex = read(context, manualStep.getProjectId(), manualStep.getId(), guidedSteps);
        for (int index = 0; index <= savedIndex && index < guidedSteps.size(); index++) {
            GuidedStep card = guidedSteps.get(index);
            if (GuidedRecognitionCatalog.checkpointFor(manualStep, card) != null
                    && !isCheckpointComplete(
                            context, manualStep.getProjectId(), manualStep.getId(), card)) {
                return index;
            }
        }
        return savedIndex;
    }

    public static void markCheckpointComplete(
            Context context,
            String projectId,
            String manualStepId,
            GuidedStep guidedStep) {
        preferences(context).edit()
                .putBoolean(checkpointKey(projectId, manualStepId, guidedStep), true)
                .apply();
    }

    public static boolean isCheckpointComplete(
            Context context,
            String projectId,
            String manualStepId,
            GuidedStep guidedStep) {
        return preferences(context).getBoolean(
                checkpointKey(projectId, manualStepId, guidedStep), false);
    }

    /** Counts confirmed actions independently of the card currently being reviewed. */
    public static int completedCheckpointCount(Context context, AssemblyStep section,
                                               List<GuidedStep> cards) {
        int completed = 0;
        for (GuidedStep card : cards) {
            if (GuidedRecognitionCatalog.checkpointFor(section, card) != null
                    && isCheckpointComplete(context, section.getProjectId(), section.getId(), card)) {
                completed++;
            }
        }
        return completed;
    }

    /** A section final may never substitute for any of its earlier action checks. */
    public static boolean areCheckpointsComplete(Context context, AssemblyStep section,
                                                 List<GuidedStep> cards) {
        for (GuidedStep card : cards) {
            if (GuidedRecognitionCatalog.checkpointFor(section, card) != null
                    && !isCheckpointComplete(context, section.getProjectId(), section.getId(), card)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Rechecking an earlier assembly action also invalidates later dependent checks in this
     * section. Cursor and invalidation are one preference update so restart cannot skip the redo.
     */
    public static void clearCheckpointsFrom(Context context, AssemblyStep section,
                                             List<GuidedStep> cards, int fromIndex) {
        if (fromIndex < 0 || fromIndex >= cards.size()) {
            throw new IllegalArgumentException("Redo card is outside this section");
        }
        SharedPreferences.Editor editor = preferences(context).edit();
        for (int index = fromIndex; index < cards.size(); index++) {
            editor.remove(checkpointKey(section.getProjectId(), section.getId(), cards.get(index)));
        }
        editor.putString(key(section.getProjectId(), section.getId()), cards.get(fromIndex).getStableId())
                .apply();
    }

    public static void clearStep(Context context, String projectId, String manualStepId) {
        String cursorKey = key(projectId, manualStepId);
        String checkpointPrefix = cursorKey + CHECKPOINT_PREFIX;
        SharedPreferences preferences = preferences(context);
        SharedPreferences.Editor editor = preferences.edit().remove(cursorKey);
        for (String storedKey : preferences.getAll().keySet()) {
            if (storedKey.startsWith(checkpointPrefix)) {
                editor.remove(storedKey);
            }
        }
        editor.apply();
    }

    public static void clearProject(Context context, String projectId) {
        String prefix = requireText(projectId, "projectId") + KEY_SEPARATOR;
        SharedPreferences preferences = preferences(context);
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                editor.remove(entry.getKey());
            }
        }
        editor.apply();
    }

    private static SharedPreferences preferences(Context context) {
        return Objects.requireNonNull(context, "context")
                .getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static String key(String projectId, String manualStepId) {
        return requireText(projectId, "projectId") + KEY_SEPARATOR
                + requireText(manualStepId, "manualStepId");
    }

    private static String checkpointKey(
            String projectId, String manualStepId, GuidedStep guidedStep) {
        return key(projectId, manualStepId) + CHECKPOINT_PREFIX + CHECKPOINT_VERSION
                + Objects.requireNonNull(guidedStep, "guidedStep").getStableId();
    }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " cannot be blank");
        }
        return value.trim();
    }
}
